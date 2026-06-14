package com.github.borgand.marginalia.ui.toolwindow

import com.github.borgand.marginalia.core.CommentStatus
import com.github.borgand.marginalia.core.MarginaliaComment
import com.github.borgand.marginalia.ui.theme.MarginaliaColors
import java.awt.Color

/**
 * The four statuses the redesign surfaces (redesign §06), collapsed from the five-value
 * [CommentStatus] model plus the orphaned flag. This is the single mapping from comment
 * state to appearance — the list, the gutter, the ribbon and the badge all read it.
 */
enum class VisualStatus(val label: String) {
    /** Written, not yet delivered to the agent. */
    QUEUED("Queued"),

    /** Agent pulled it via get_pending_comments. */
    DELIVERED("Delivered"),

    /** Addressed & closed. */
    RESOLVED("Resolved"),

    /** Delivery/processing error — in Marginalia, the comment's anchor was lost. */
    FAILED("Failed"),
    ;

    val color: Color
        get() = when (this) {
            QUEUED -> MarginaliaColors.statusPending // amber — pending, not yet sent
            DELIVERED -> MarginaliaColors.statusDelivered // green — in flight / positive progress
            RESOLVED -> MarginaliaColors.statusInfo // blue — closed
            FAILED -> MarginaliaColors.statusConflict
        }

    /** Soft pill background = [color] @ 13%. */
    val softColor: Color get() = MarginaliaColors.soft(color)
}

/**
 * Maps a comment to its visual status. Orphaned (anchor text deleted, see
 * [com.github.borgand.marginalia.core.CommentStore.ensureAnchored]) is checked first so a
 * lost anchor always reads as [VisualStatus.FAILED] regardless of dispatch state.
 */
fun visualStatus(comment: MarginaliaComment): VisualStatus = when {
    comment.orphaned -> VisualStatus.FAILED
    comment.status == CommentStatus.RESOLVED -> VisualStatus.RESOLVED
    comment.status == CommentStatus.DISPATCHED ||
        comment.status == CommentStatus.ADDRESSED -> VisualStatus.DELIVERED
    else -> VisualStatus.QUEUED // DRAFT, QUEUED
}
