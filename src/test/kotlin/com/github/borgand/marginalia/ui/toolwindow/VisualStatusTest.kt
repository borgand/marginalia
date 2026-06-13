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
    fun orphanedIsFailedRegardlessOfStatus() {
        for (status in CommentStatus.entries) {
            assertEquals(
                "orphaned comment with status $status should be FAILED",
                VisualStatus.FAILED,
                visualStatus(comment(status, orphaned = true)),
            )
        }
    }
}
