package com.github.borgand.marginalia.core

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CommentStoreTest : BasePlatformTestCase() {

    private val store get() = project.service<CommentStore>()

    override fun setUp() {
        super.setUp()
        store.clear() // light fixture reuses the project between tests
    }

    private fun configure(text: String, name: String = "doc.md") =
        myFixture.configureByText(name, text)

    fun testAddCommentCreatesAnchoredComment() {
        configure("# Title\n\nalpha beta gamma\n")
        val doc = myFixture.editor.document
        val start = doc.text.indexOf("beta")
        val comment = store.addComment(doc, start, start + 4, "too vague", CommentStatus.QUEUED)

        assertEquals("beta", comment.anchoredText)
        assertEquals(listOf("Title"), comment.headingPath)
        assertEquals(CommentStatus.QUEUED, comment.status)
        assertEquals(1, store.comments().size)
        assertNotNull(store.markerFor(comment.id))
    }

    fun testMarkerTracksUserEditAbove() {
        configure("first line\ntarget here\n")
        val doc = myFixture.editor.document
        val start = doc.text.indexOf("target")
        val comment = store.addComment(doc, start, start + 6, "check this", CommentStatus.QUEUED)

        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(0, "inserted line\n")
        }

        val marker = store.markerFor(comment.id)!!
        assertEquals("target", doc.getText(com.intellij.openapi.util.TextRange(marker.startOffset, marker.endOffset)))
    }

    fun testPersistenceRoundTripAndReanchor() {
        configure("alpha\nbravo\ncharlie\n")
        val doc = myFixture.editor.document
        val start = doc.text.indexOf("bravo")
        val original = store.addComment(doc, start, start + 5, "rename this", CommentStatus.QUEUED)

        val state = store.state
        val fresh = CommentStore(project)
        fresh.loadState(state)
        // markers are transient — must re-anchor against the (re-opened) document
        fresh.ensureAnchored(doc, original.filePath)

        val restored = fresh.comments().single()
        assertEquals(original.id, restored.id)
        assertFalse(restored.orphaned)
        val marker = fresh.markerFor(restored.id)!!
        assertEquals("bravo", doc.getText(com.intellij.openapi.util.TextRange(marker.startOffset, marker.endOffset)))
    }

    fun testReanchorBySnippetSearchWhenOffsetsStale() {
        configure("alpha\nbravo\ncharlie\n")
        val doc = myFixture.editor.document
        val start = doc.text.indexOf("charlie")
        val comment = store.addComment(doc, start, start + 7, "expand", CommentStatus.QUEUED)
        val state = store.state

        // text shifted since offsets were stored
        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(0, "zero\nzero\nzero\n")
        }

        val fresh = CommentStore(project)
        fresh.loadState(state)
        fresh.ensureAnchored(doc, comment.filePath)

        val restored = fresh.comments().single()
        assertFalse(restored.orphaned)
        val marker = fresh.markerFor(restored.id)!!
        assertEquals("charlie", doc.getText(com.intellij.openapi.util.TextRange(marker.startOffset, marker.endOffset)))
    }

    fun testOrphanedWhenSnippetDeleted() {
        configure("alpha\nbravo\ncharlie\n")
        val doc = myFixture.editor.document
        val start = doc.text.indexOf("bravo")
        val comment = store.addComment(doc, start, start + 5, "note", CommentStatus.QUEUED)
        val state = store.state

        WriteCommandAction.runWriteCommandAction(project) {
            doc.setText("alpha\ncharlie\n")
        }

        val fresh = CommentStore(project)
        fresh.loadState(state)
        fresh.ensureAnchored(doc, comment.filePath)

        val restored = fresh.comments().single()
        assertTrue(restored.orphaned)
        assertNull(fresh.markerFor(restored.id))
        assertEquals(1, fresh.comments().size) // never silently dropped
    }

    fun testResolveSetsStatusAndNote() {
        configure("alpha\n")
        val doc = myFixture.editor.document
        val comment = store.addComment(doc, 0, 5, "fix", CommentStatus.QUEUED)
        assertTrue(store.setStatus(comment.id, CommentStatus.ADDRESSED, "done, rephrased"))
        val updated = store.byId(comment.id)!!
        assertEquals(CommentStatus.ADDRESSED, updated.status)
        assertEquals("done, rephrased", updated.resolutionNote)
    }

    fun testHeadingPathNested() {
        val text = "# Top\n\n## Section\n\ntext under section\n\n## Other\n\nmore\n"
        configure(text)
        val doc = myFixture.editor.document
        val start = doc.text.indexOf("text under")
        val comment = store.addComment(doc, start, start + 4, "x", CommentStatus.QUEUED)
        assertEquals(listOf("Top", "Section"), comment.headingPath)
    }

    fun testHeadingPathOnlyForMarkdown() {
        configure("# not a heading, just Kotlin comment\nval x = 1\n", "code.kt")
        val doc = myFixture.editor.document
        val comment = store.addComment(doc, doc.text.indexOf("val"), doc.text.indexOf("val") + 3, "x", CommentStatus.QUEUED)
        assertTrue(comment.headingPath.isEmpty())
    }
}
