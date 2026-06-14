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
 * Maps a comment to its visual status. Dispatch state wins over the orphaned flag: once a
 * comment has reached the agent (DISPATCHED/ADDRESSED) or been closed (RESOLVED), a lost
 * anchor is the *expected* outcome of the text being rewritten, not a failure — re-anchoring
 * the original snippet legitimately fails after restart (markers are transient, see
 * [com.github.borgand.marginalia.core.CommentStore.ensureAnchored]). Orphaned only reads as
 * [VisualStatus.FAILED] for a comment that was never delivered: there a missing anchor means
 * it can no longer be acted on.
 */
fun visualStatus(comment: MarginaliaComment): VisualStatus = when {
    comment.status == CommentStatus.RESOLVED -> VisualStatus.RESOLVED
    comment.status == CommentStatus.DISPATCHED ||
        comment.status == CommentStatus.ADDRESSED -> VisualStatus.DELIVERED
    comment.orphaned -> VisualStatus.FAILED // DRAFT/QUEUED with a lost anchor
    else -> VisualStatus.QUEUED // DRAFT, QUEUED
}
