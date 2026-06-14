package com.github.borgand.marginalia.ui.toolwindow

import com.github.borgand.marginalia.core.CommentStatus
import com.github.borgand.marginalia.core.MarginaliaComment
import org.junit.Assert.assertEquals
import org.junit.Test

class VisualStatusTest {

    private fun comment(status: CommentStatus, orphaned: Boolean = false) =
        MarginaliaComment().apply {
            this.status = status
            this.orphaned = orphaned
        }

    @Test
    fun draftAndQueuedAreQueued() {
        assertEquals(VisualStatus.QUEUED, visualStatus(comment(CommentStatus.DRAFT)))
        assertEquals(VisualStatus.QUEUED, visualStatus(comment(CommentStatus.QUEUED)))
    }

    @Test
    fun dispatchedAndAddressedAreDelivered() {
        assertEquals(VisualStatus.DELIVERED, visualStatus(comment(CommentStatus.DISPATCHED)))
        assertEquals(VisualStatus.DELIVERED, visualStatus(comment(CommentStatus.ADDRESSED)))
    }

    @Test
    fun resolvedIsResolved() {
        assertEquals(VisualStatus.RESOLVED, visualStatus(comment(CommentStatus.RESOLVED)))
    }

    @Test
    fun orphanedUndeliveredCommentIsFailed() {
        // A lost anchor only signals failure while the comment was never delivered.
        assertEquals(VisualStatus.FAILED, visualStatus(comment(CommentStatus.DRAFT, orphaned = true)))
        assertEquals(VisualStatus.FAILED, visualStatus(comment(CommentStatus.QUEUED, orphaned = true)))
    }

    @Test
    fun deliveredOrResolvedCommentKeepsStatusWhenOrphaned() {
        // After the agent rewrites the anchored text, the original snippet no longer matches
        // on restart and the comment orphans — but it was delivered/closed, not failed.
        assertEquals(VisualStatus.DELIVERED, visualStatus(comment(CommentStatus.DISPATCHED, orphaned = true)))
        assertEquals(VisualStatus.DELIVERED, visualStatus(comment(CommentStatus.ADDRESSED, orphaned = true)))
        assertEquals(VisualStatus.RESOLVED, visualStatus(comment(CommentStatus.RESOLVED, orphaned = true)))
    }
}
