package com.github.borgand.marginalia.ui.toolwindow

import com.github.borgand.marginalia.core.MarginaliaComment
import javax.swing.AbstractListModel

/**
 * Flattens the comment store into the grouped sidecar list (redesign §02): for each file
 * (sorted by name, stable) a [SidecarRow.FileHeaderRow] followed by its
 * [SidecarRow.CommentRow]s. A collapsed file header hides its comment rows. Per-file
 * "to send" = comments whose [visualStatus] is [VisualStatus.QUEUED].
 *
 * Pure data transform over the model — no painting — so it is unit-testable headless.
 */
class CommentListModel : AbstractListModel<SidecarRow>() {

    private var rows: List<SidecarRow> = emptyList()
    private val collapsed = mutableSetOf<String>()
    private var lastComments: List<MarginaliaComment> = emptyList()
    private var lastLineOf: (MarginaliaComment) -> Int? = { null }

    override fun getSize(): Int = rows.size
    override fun getElementAt(index: Int): SidecarRow = rows[index]

    fun isCollapsed(path: String): Boolean = path in collapsed

    /** Collapse/expand a file group, then rebuild. */
    fun toggleCollapsed(path: String) {
        if (!collapsed.add(path)) collapsed.remove(path)
        rebuild(lastComments, lastLineOf)
    }

    /**
     * Replace the backing comments and rebuild the visible rows. [lineOf] resolves a
     * comment's live anchor line for the `L13` chip (null when no valid marker).
     */
    fun setComments(comments: List<MarginaliaComment>, lineOf: (MarginaliaComment) -> Int? = { null }) =
        rebuild(comments, lineOf)

    private fun rebuild(comments: List<MarginaliaComment>, lineOf: (MarginaliaComment) -> Int?) {
        lastComments = comments
        lastLineOf = lineOf
        val byFile = comments.groupBy { it.filePath }
        val newRows = mutableListOf<SidecarRow>()
        for (path in byFile.keys.sortedBy { it.substringAfterLast('/').lowercase() }) {
            val fileComments = byFile.getValue(path)
            val queued = fileComments.count { visualStatus(it) == VisualStatus.QUEUED }
            val isCollapsed = path in collapsed
            newRows += SidecarRow.FileHeaderRow(path, fileComments.size, queued, isCollapsed)
            if (!isCollapsed) {
                fileComments.forEach { newRows += SidecarRow.CommentRow(it, lineOf(it)) }
            }
        }
        val oldSize = rows.size
        rows = newRows
        if (oldSize > 0) fireIntervalRemoved(this, 0, oldSize - 1)
        if (rows.isNotEmpty()) fireIntervalAdded(this, 0, rows.size - 1)
    }
}
