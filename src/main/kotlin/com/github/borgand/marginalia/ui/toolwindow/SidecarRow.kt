package com.github.borgand.marginalia.ui.toolwindow

import com.github.borgand.marginalia.core.MarginaliaComment

/**
 * A row in the flattened sidecar list (redesign §02). The list mixes two kinds; the
 * renderer switches layout on the kind. Comments are grouped under their file's header.
 */
sealed interface SidecarRow {

    /** A per-file group header: filename, total comments, queued count, and fold state. */
    data class FileHeaderRow(
        val path: String,
        val total: Int,
        val queuedCount: Int,
        val collapsed: Boolean,
    ) : SidecarRow {
        val fileName: String get() = path.substringAfterLast('/')
    }

    /**
     * A single comment card under its file header. [line] is the 0-based line of the live
     * anchor when a valid marker exists (for the `L13` chip), else null.
     */
    data class CommentRow(val comment: MarginaliaComment, val line: Int? = null) : SidecarRow
}
