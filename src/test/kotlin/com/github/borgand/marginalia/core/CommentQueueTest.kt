package com.github.borgand.marginalia.core

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CommentQueueTest : BasePlatformTestCase() {

    private val store get() = project.service<CommentStore>()
    private val queue get() = project.service<CommentQueue>()

    override fun setUp() {
        super.setUp()
        store.clear()
        queue.autoDispatch = true
    }

    fun testAutoModeQueuesImmediatelyAndDispatches() {
        val psiFile = myFixture.configureByText("doc.md", "# H\n\nline one\nline two\nline three\nline four\nline five\n")
        val doc = myFixture.editor.document
        val start = doc.text.indexOf("line three")
        store.addComment(doc, start, start + 10, "tighten this", queue.initialStatus())

        val pending = queue.pending()
        assertEquals(1, pending.size)
        val p = pending.single()
        assertEquals("line three", p.anchoredText)
        assertEquals("tighten this", p.body)
        assertEquals(listOf("H"), p.headingPath)
        assertEquals(psiFile.virtualFile.path, p.path)
        assertEquals(CommentStatus.DISPATCHED, store.comments().single().status)

        // second pull returns nothing — already dispatched
        assertTrue(queue.pending().isEmpty())
    }

    fun testManualModeHoldsDraftsUntilSubmitReview() {
        myFixture.configureByText("doc.md", "alpha\nbravo\n")
        val doc = myFixture.editor.document
        queue.autoDispatch = false

        store.addComment(doc, 0, 5, "first", queue.initialStatus())
        store.addComment(doc, 6, 11, "second", queue.initialStatus())

        assertTrue(queue.pending().isEmpty())
        assertEquals(2, queue.submitReview())
        assertEquals(2, queue.pending().size)
    }

    fun testContextLinesAroundAnchor() {
        myFixture.configureByText("doc.md", "l1\nl2\nl3\nl4\nl5\nl6\nl7\n")
        val doc = myFixture.editor.document
        val start = doc.text.indexOf("l4")
        store.addComment(doc, start, start + 2, "x", queue.initialStatus())

        val p = queue.pending().single()
        assertEquals("l2\nl3", p.contextBefore)
        assertEquals("l5\nl6", p.contextAfter)
    }

    fun testContextClampedAtFileEdges() {
        myFixture.configureByText("doc.md", "first\nsecond\n")
        val doc = myFixture.editor.document
        store.addComment(doc, 0, 5, "x", queue.initialStatus())

        val p = queue.pending().single()
        assertEquals("", p.contextBefore)
        assertEquals("second", p.contextAfter)
    }

    fun testPendingFilteredByPath() {
        myFixture.configureByText("a.md", "aaa\n")
        val docA = myFixture.editor.document
        store.addComment(docA, 0, 3, "on a", queue.initialStatus())

        myFixture.configureByText("b.md", "bbb\n")
        val docB = myFixture.editor.document
        val b = store.addComment(docB, 0, 3, "on b", queue.initialStatus())

        val pending = queue.pending(b.filePath)
        assertEquals(1, pending.size)
        assertEquals("on b", pending.single().body)
        // the comment on a.md is still queued, untouched
        assertEquals(1, store.comments().count { it.status == CommentStatus.QUEUED })
    }
}
