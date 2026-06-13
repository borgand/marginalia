package com.github.borgand.marginalia.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Comment model + RangeMarker anchoring + persistence (PRD F1).
 *
 * Persisted state: offsets + snippet + heading path per comment (survives IDE restarts).
 * Runtime state: RangeMarkers (transient, auto-track edits) re-created on file open.
 * Orphaned comments (anchor text gone) are flagged, never dropped.
 */
@Service(Service.Level.PROJECT)
@State(name = "MarginaliaComments", storages = [Storage("marginalia.xml")])
class CommentStore(private val project: Project) : PersistentStateComponent<CommentStore.State> {

    class State {
        var comments: MutableList<MarginaliaComment> = mutableListOf()
    }

    private var state = State()
    private val markers = ConcurrentHashMap<String, RangeMarker>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun getState(): State {
        syncOffsetsFromMarkers()
        return state
    }

    override fun loadState(state: State) {
        this.state = state
    }

    fun addChangeListener(parent: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        com.intellij.openapi.util.Disposer.register(parent) { listeners.remove(listener) }
    }

    private fun notifyChanged() = listeners.forEach { it() }

    fun addComment(
        document: Document,
        startOffset: Int,
        endOffset: Int,
        body: String,
        initialStatus: CommentStatus,
    ): MarginaliaComment {
        val file = FileDocumentManager.getInstance().getFile(document)
        val path = file?.path ?: ""
        val isMarkdown = path.endsWith(".md", ignoreCase = true)
        val comment = MarginaliaComment().apply {
            id = UUID.randomUUID().toString()
            filePath = path
            this.body = body
            status = initialStatus
            anchoredText = document.getText(TextRange(startOffset, endOffset))
            headingPath = if (isMarkdown) {
                MarkdownHeadings.pathAt(document.text, startOffset).toMutableList()
            } else mutableListOf()
            this.startOffset = startOffset
            this.endOffset = endOffset
            createdAt = System.currentTimeMillis()
        }
        markers[comment.id] = document.createRangeMarker(startOffset, endOffset)
        state.comments.add(comment)
        notifyChanged()
        return comment
    }

    fun comments(path: String? = null): List<MarginaliaComment> =
        state.comments.filter { path == null || it.filePath == path }

    fun byId(id: String): MarginaliaComment? = state.comments.find { it.id == id }

    fun markerFor(id: String): RangeMarker? = markers[id]?.takeIf { it.isValid }

    fun setStatus(id: String, status: CommentStatus, note: String? = null): Boolean {
        val comment = byId(id) ?: return false
        comment.status = status
        if (note != null) comment.resolutionNote = note
        notifyChanged()
        return true
    }

    fun remove(id: String) {
        state.comments.removeAll { it.id == id }
        markers.remove(id)?.dispose()
        notifyChanged()
    }

    fun clear() {
        state.comments.clear()
        markers.values.forEach { it.dispose() }
        markers.clear()
        notifyChanged()
    }

    /**
     * Re-create markers for [path] against an (re)opened document.
     * Strategy per PRD F1: stored offsets if the snippet still matches there,
     * else snippet search nearest to the stored offset, else flag orphaned.
     */
    fun ensureAnchored(document: Document, path: String) {
        val text = document.text
        var changed = false
        for (comment in state.comments) {
            if (comment.filePath != path) continue
            if (markers[comment.id]?.isValid == true) continue
            val snippet = comment.anchoredText
            val start = when {
                snippet.isEmpty() -> null
                comment.endOffset <= text.length &&
                    comment.endOffset - comment.startOffset == snippet.length &&
                    text.regionMatches(comment.startOffset, snippet, 0, snippet.length) -> comment.startOffset
                else -> nearestIndexOf(text, snippet, comment.startOffset)
            }
            if (start != null) {
                markers[comment.id] = document.createRangeMarker(start, start + snippet.length)
                comment.startOffset = start
                comment.endOffset = start + snippet.length
                comment.orphaned = false
            } else {
                markers.remove(comment.id)
                comment.orphaned = true
            }
            changed = true
        }
        if (changed) notifyChanged()
    }

    /** Push live marker positions back into the persistable beans. */
    fun syncOffsetsFromMarkers() {
        for (comment in state.comments) {
            val marker = markers[comment.id] ?: continue
            if (marker.isValid) {
                comment.startOffset = marker.startOffset
                comment.endOffset = marker.endOffset
            }
        }
    }

    private fun nearestIndexOf(text: String, snippet: String, preferredOffset: Int): Int? {
        var best: Int? = null
        var index = text.indexOf(snippet)
        while (index >= 0) {
            if (best == null || abs(index - preferredOffset) < abs(best - preferredOffset)) best = index
            index = text.indexOf(snippet, index + 1)
        }
        return best
    }
}
