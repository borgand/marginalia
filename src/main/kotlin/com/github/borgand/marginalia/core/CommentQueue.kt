package com.github.borgand.marginalia.core

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange

/**
 * Dispatch policy over the CommentStore (PRD F2).
 *
 * *auto* mode: new comments are QUEUED immediately — the agent sees them on its next
 * `get_pending_comments` pull. *manual* mode: comments stay DRAFT until "Submit review".
 * [pending] builds the dispatch payload (PRD §6) from the live buffer and marks the
 * returned comments DISPATCHED.
 */
@Service(Service.Level.PROJECT)
class CommentQueue(private val project: Project) {

    @Volatile
    var autoDispatch: Boolean = true

    fun initialStatus(): CommentStatus =
        if (autoDispatch) CommentStatus.QUEUED else CommentStatus.DRAFT

    /** Promotes all DRAFT comments to QUEUED; returns how many were promoted. */
    fun submitReview(): Int {
        val store = project.service<CommentStore>()
        val drafts = store.comments().filter { it.status == CommentStatus.DRAFT }
        drafts.forEach { store.setStatus(it.id, CommentStatus.QUEUED) }
        return drafts.size
    }

    data class PendingComment(
        val id: String,
        val path: String,
        val headingPath: List<String>,
        val anchoredText: String,
        val contextBefore: String,
        val contextAfter: String,
        val body: String,
        val createdAt: Long,
    )

    /** Dispatch payload for QUEUED comments (optionally limited to [path]); marks them DISPATCHED. */
    fun pending(path: String? = null): List<PendingComment> = ReadAction.compute<List<PendingComment>, RuntimeException> {
        val store = project.service<CommentStore>()
        val queued = store.comments(path).filter { it.status == CommentStatus.QUEUED }
        val result = queued.map { comment ->
            val marker = store.markerFor(comment.id)
            val document = marker?.document
            val anchoredText: String
            val contextBefore: String
            val contextAfter: String
            if (document != null && marker != null) {
                anchoredText = document.getText(TextRange(marker.startOffset, marker.endOffset))
                contextBefore = contextLines(document, marker.startOffset, before = true)
                contextAfter = contextLines(document, marker.endOffset, before = false)
            } else {
                // orphaned or file not open — fall back to the captured snippet
                anchoredText = comment.anchoredText
                contextBefore = ""
                contextAfter = ""
            }
            PendingComment(
                id = comment.id,
                path = comment.filePath,
                headingPath = comment.headingPath.toList(),
                anchoredText = anchoredText,
                contextBefore = contextBefore,
                contextAfter = contextAfter,
                body = comment.body,
                createdAt = comment.createdAt,
            )
        }
        queued.forEach { store.setStatus(it.id, CommentStatus.DISPATCHED) }
        result
    }

    private fun contextLines(document: Document, offset: Int, before: Boolean, lines: Int = 2): String {
        val anchorLine = document.getLineNumber(offset.coerceIn(0, document.textLength))
        val (from, to) = if (before) {
            (anchorLine - lines).coerceAtLeast(0) to anchorLine - 1
        } else {
            anchorLine + 1 to (anchorLine + lines).coerceAtMost(document.lineCount - 1)
        }
        if (from > to) return ""
        return document.getText(TextRange(document.getLineStartOffset(from), document.getLineEndOffset(to)))
            .trimEnd('\n') // a trailing newline in the doc yields an empty final line — not context
    }
}
