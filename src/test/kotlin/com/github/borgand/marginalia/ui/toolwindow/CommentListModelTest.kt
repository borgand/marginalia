package com.github.borgand.marginalia.ui.toolwindow

import com.github.borgand.marginalia.core.CommentStatus
import com.github.borgand.marginalia.core.MarginaliaComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentListModelTest {

    private fun comment(path: String, status: CommentStatus = CommentStatus.QUEUED) =
        MarginaliaComment().apply {
            this.filePath = path
            this.status = status
        }

    private fun headers(model: CommentListModel) =
        (0 until model.size).map { model.getElementAt(it) }
            .filterIsInstance<SidecarRow.FileHeaderRow>()

    @Test
    fun groupsCommentsUnderSortedFileHeaders() {
        val model = CommentListModel()
        model.setComments(
            listOf(
                comment("/proj/zebra.md"),
                comment("/proj/alpha.md"),
                comment("/proj/alpha.md"),
            ),
        )

        // alpha (header + 2 comments) then zebra (header + 1 comment) = 5 rows
        assertEquals(5, model.size)
        assertEquals("alpha.md", (model.getElementAt(0) as SidecarRow.FileHeaderRow).fileName)
        assertTrue(model.getElementAt(1) is SidecarRow.CommentRow)
        assertTrue(model.getElementAt(2) is SidecarRow.CommentRow)
        assertEquals("zebra.md", (model.getElementAt(3) as SidecarRow.FileHeaderRow).fileName)
        assertTrue(model.getElementAt(4) is SidecarRow.CommentRow)
    }

    @Test
    fun headerCountsTotalAndQueued() {
        val model = CommentListModel()
        model.setComments(
            listOf(
                comment("/proj/a.md", CommentStatus.QUEUED),
                comment("/proj/a.md", CommentStatus.DISPATCHED), // delivered, not queued
                comment("/proj/a.md", CommentStatus.RESOLVED), // not queued
            ),
        )

        val header = headers(model).single()
        assertEquals(3, header.total)
        assertEquals(1, header.queuedCount)
    }

    @Test
    fun collapsingHidesCommentRowsButKeepsHeader() {
        val model = CommentListModel()
        model.setComments(listOf(comment("/proj/a.md"), comment("/proj/a.md")))
        assertEquals(3, model.size) // header + 2

        model.toggleCollapsed("/proj/a.md")
        assertTrue(model.isCollapsed("/proj/a.md"))
        assertEquals(1, model.size) // header only
        assertTrue(model.getElementAt(0) is SidecarRow.FileHeaderRow)

        model.toggleCollapsed("/proj/a.md")
        assertFalse(model.isCollapsed("/proj/a.md"))
        assertEquals(3, model.size)
    }
}
