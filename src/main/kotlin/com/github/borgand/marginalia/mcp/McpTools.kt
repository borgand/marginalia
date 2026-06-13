package com.github.borgand.marginalia.mcp

import com.github.borgand.marginalia.core.ActivityLog
import com.github.borgand.marginalia.core.CommentQueue
import com.github.borgand.marginalia.core.CommentStore
import com.github.borgand.marginalia.core.DocRegistry
import com.github.borgand.marginalia.core.MarkdownHeadings
import com.github.borgand.marginalia.core.MergeEngine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * MCP tool implementations — the public API the agent depends on (PRD §6, implement
 * exactly). Thin JSON layer over the core services; the HTTP transport wraps these.
 *
 * Threading: handlers run on server threads. Reads go through ReadAction, writes go
 * through MergeEngine (EDT + WriteCommandAction inside).
 */
object McpTools {

    private val log get() = service<ActivityLog>()

    /** Record agent activity for the connectivity indicator (best-effort). */
    private fun touch(toolName: String) {
        runCatching { service<McpServerService>().recordToolCall(toolName) }
    }

    private fun error(code: String, message: String): JsonObject = buildJsonObject {
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    /** Locate the open project that co-edits [path]. */
    private fun projectFor(path: String): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull {
            !it.isDisposed && it.service<DocRegistry>().isCoEdited(path)
        }

    private fun documentFor(project: Project, path: String): Document? {
        val file = project.service<DocRegistry>().fileFor(path) ?: return null
        return FileDocumentManager.getInstance().getDocument(file)
    }

    fun listCoEditedDocs(): JsonObject = run {
        touch("list_co_edited_docs")
        ReadAction.compute<JsonObject, RuntimeException> {
            buildJsonObject {
                putJsonArray("docs") {
                    for (project in ProjectManager.getInstance().openProjects.filter { !it.isDisposed }) {
                        val registry = project.service<DocRegistry>()
                        for (path in registry.paths()) {
                            addJsonObject {
                                put("path", path)
                                put("version", registry.version(path) ?: 0L)
                            }
                        }
                    }
                }
            }
        }
    }

    fun readDoc(path: String): JsonObject {
        touch("read_doc")
        val project = projectFor(path)
            ?: return error("NOT_CO_EDITED", "File is not co-edited: $path. Use list_co_edited_docs.")
        return ReadAction.compute<JsonObject, RuntimeException> {
            val document = documentFor(project, path)
                ?: return@compute error("NOT_CO_EDITED", "No live buffer for: $path")
            val version = project.service<DocRegistry>().version(path) ?: 0L
            val content = document.text
            // side effect per §6: this content becomes the merge base for apply_edit
            project.service<MergeEngine>().recordBase(path, version, content)
            log.log("read_doc $path (v$version)")
            buildJsonObject {
                put("path", path)
                put("version", version)
                put("content", content)
                putJsonArray("headings") {
                    if (path.endsWith(".md", ignoreCase = true)) {
                        for (h in MarkdownHeadings.headings(content)) {
                            addJsonObject {
                                put("level", h.level)
                                put("text", h.text)
                                put("offset", h.offset)
                            }
                        }
                    }
                }
            }
        }
    }

    fun applyEdit(path: String, baseVersion: Long, edits: List<Pair<String, String>>): JsonObject {
        touch("apply_edit")
        val project = projectFor(path)
            ?: return error("NOT_CO_EDITED", "File is not co-edited: $path. Use list_co_edited_docs.")
        val document = ReadAction.compute<Document?, RuntimeException> { documentFor(project, path) }
            ?: return error("NOT_CO_EDITED", "No live buffer for: $path")
        val engine = project.service<MergeEngine>()

        val result = engine.applyEdits(document, path, baseVersion, edits.map { MergeEngine.Edit(it.first, it.second) })
        return when (result) {
            is MergeEngine.Result.StaleBase -> {
                log.log("apply_edit $path REJECTED: stale base v$baseVersion")
                error("STALE_BASE", "base_version $baseVersion is unknown or too old — call read_doc again.")
            }
            is MergeEngine.Result.Outcome -> {
                val registry = project.service<DocRegistry>()
                val newVersion = ReadAction.compute<Long, RuntimeException> { registry.version(path) ?: 0L }
                // record the post-apply content so the agent can chain on new_version (D8)
                ReadAction.compute<Unit, RuntimeException> {
                    engine.recordBase(path, newVersion, document.text)
                }
                log.log(
                    "apply_edit $path: ${result.applied.size} applied, " +
                        "${result.conflicts.size} conflict(s) → v$newVersion",
                )
                buildJsonObject {
                    putJsonArray("applied") { result.applied.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("conflicts") {
                        for (c in result.conflicts) {
                            addJsonObject {
                                put("edit_idx", c.index)
                                put("reason", c.reason)
                                put("current_text", c.currentText)
                            }
                        }
                    }
                    put("new_version", newVersion)
                }
            }
        }
    }

    fun getPendingComments(path: String?): JsonObject {
        touch("get_pending_comments")
        val projects = if (path == null) {
            ProjectManager.getInstance().openProjects.filter { !it.isDisposed }.toList()
        } else {
            listOfNotNull(projectFor(path))
        }
        val pending = projects.flatMap { it.service<CommentQueue>().pending(path) }
        if (pending.isNotEmpty()) log.log("get_pending_comments → dispatched ${pending.size} comment(s)")
        return buildJsonObject {
            putJsonArray("comments") {
                for (p in pending) {
                    addJsonObject {
                        put("id", p.id)
                        put("path", p.path)
                        putJsonArray("heading_path") { p.headingPath.forEach { add(JsonPrimitive(it)) } }
                        put("anchored_text", p.anchoredText)
                        put("context_before", p.contextBefore)
                        put("context_after", p.contextAfter)
                        put("body", p.body)
                        put("created_at", p.createdAt)
                    }
                }
            }
        }
    }

    fun resolveComment(id: String, note: String?): JsonObject {
        touch("resolve_comment")
        for (project in ProjectManager.getInstance().openProjects.filter { !it.isDisposed }) {
            val store = project.service<CommentStore>()
            if (store.byId(id) != null) {
                runOnEdt {
                    store.setStatus(id, com.github.borgand.marginalia.core.CommentStatus.ADDRESSED, note)
                }
                log.log("resolve_comment $id${note?.let { ": $it" } ?: ""}")
                return buildJsonObject { put("ok", true) }
            }
        }
        return error("INVALID_ANCHOR", "No comment with id $id")
    }

    private fun runOnEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) action() else app.invokeAndWait(action)
    }
}
