package com.github.borgand.marginalia.core

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MergeEngineTest : BasePlatformTestCase() {

    private val engine get() = project.service<MergeEngine>()

    private fun doc(text: String): Document {
        myFixture.configureByText("doc.md", text)
        return myFixture.editor.document
    }

    private fun userTypes(document: Document, action: (Document) -> Unit) {
        WriteCommandAction.runWriteCommandAction(project) { action(document) }
    }

    fun testHappyPathReplace() {
        val d = doc("alpha\nbeta\ngamma\n")
        engine.recordBase("p", 1, d.text)

        val result = engine.applyEdits(d, "p", 1, listOf(MergeEngine.Edit("beta", "BETA")))

        result as MergeEngine.Result.Outcome
        assertEquals(listOf(0), result.applied)
        assertTrue(result.conflicts.isEmpty())
        assertEquals("alpha\nBETA\ngamma\n", d.text)
    }

    fun testStaleBaseVersion() {
        val d = doc("alpha\n")
        engine.recordBase("p", 1, d.text)

        val result = engine.applyEdits(d, "p", 99, listOf(MergeEngine.Edit("alpha", "x")))

        assertTrue(result is MergeEngine.Result.StaleBase)
        assertEquals("alpha\n", d.text)
    }

    fun testConcurrentUserTypingAboveShiftsEdit() {
        val d = doc("intro\nbody text here\noutro\n")
        engine.recordBase("p", 1, d.text)

        // user keeps typing above the agent's target while the agent is mid-turn
        userTypes(d) { it.insertString(0, "user added line one\nuser added line two\n") }

        val result = engine.applyEdits(d, "p", 1, listOf(MergeEngine.Edit("body text here", "body text improved")))

        result as MergeEngine.Result.Outcome
        assertEquals(listOf(0), result.applied)
        assertEquals("user added line one\nuser added line two\nintro\nbody text improved\noutro\n", d.text)
    }

    fun testUserWinsConflictReturnsCurrentText() {
        val d = doc("alpha\nbeta\ngamma\n")
        engine.recordBase("p", 1, d.text)

        // user rewrote the agent's target region — user wins
        userTypes(d) { it.replaceString(d.text.indexOf("beta"), d.text.indexOf("beta") + 4, "user version") }

        val result = engine.applyEdits(
            d, "p", 1,
            listOf(
                MergeEngine.Edit("beta", "agent version"),
                MergeEngine.Edit("gamma", "GAMMA"),
            ),
        )

        result as MergeEngine.Result.Outcome
        // partial success is normal: the non-conflicting edit applied
        assertEquals(listOf(1), result.applied)
        assertEquals(1, result.conflicts.size)
        val conflict = result.conflicts.single()
        assertEquals(0, conflict.index)
        assertTrue(conflict.currentText.contains("user version"))
        assertEquals("alpha\nuser version\nGAMMA\n", d.text)
    }

    fun testOldTextNotInBaseIsInvalidAnchor() {
        val d = doc("alpha\n")
        engine.recordBase("p", 1, d.text)

        val result = engine.applyEdits(d, "p", 1, listOf(MergeEngine.Edit("never existed", "x")))

        result as MergeEngine.Result.Outcome
        assertTrue(result.applied.isEmpty())
        assertTrue(result.conflicts.single().reason.contains("base"))
    }

    fun testMultiLineOldTextApplies() {
        val base = "item\nfiller filler\nfiller filler\nfiller filler\nitem\n"
        val d = doc(base)
        engine.recordBase("p", 1, base)

        // multi-line old_text targets the second occurrence of "item" via context
        val result = engine.applyEdits(
            d, "p", 1,
            listOf(MergeEngine.Edit("filler\nitem", "filler\nITEM")),
        )

        result as MergeEngine.Result.Outcome
        assertEquals(listOf(0), result.applied)
        assertEquals("item\nfiller filler\nfiller filler\nfiller filler\nITEM\n", d.text)
    }

    fun testDuplicateOccurrencePicksNearestToBasePosition() {
        // duplicated old_text: the occurrence nearest the diff-mapped base position wins;
        // the duplicate sits at the end, far from the base position of the first
        val base = "header\ntarget\nmiddle middle middle\nmiddle middle middle\ntarget\n"
        val d = doc(base)
        engine.recordBase("p", 1, base)

        val result = engine.applyEdits(d, "p", 1, listOf(MergeEngine.Edit("target", "TARGET")))

        result as MergeEngine.Result.Outcome
        assertEquals(listOf(0), result.applied)
        // first occurrence (base position) replaced, duplicate untouched
        assertEquals("header\nTARGET\nmiddle middle middle\nmiddle middle middle\ntarget\n", d.text)
    }

    fun testWhitespaceTolerantMatch() {
        val d = doc("alpha\nfoo   bar\ngamma\n")
        // agent's base had single spacing; user reformatted whitespace since
        engine.recordBase("p", 1, "alpha\nfoo bar\ngamma\n")

        val result = engine.applyEdits(d, "p", 1, listOf(MergeEngine.Edit("foo bar", "foo baz")))

        result as MergeEngine.Result.Outcome
        assertEquals(listOf(0), result.applied)
        assertEquals("alpha\nfoo baz\ngamma\n", d.text)
    }

    fun testOverlappingEditsSecondConflicts() {
        val d = doc("one two three four\n")
        engine.recordBase("p", 1, d.text)

        val result = engine.applyEdits(
            d, "p", 1,
            listOf(
                MergeEngine.Edit("one two three", "ONE TWO THREE"),
                MergeEngine.Edit("three four", "THREE FOUR"),
            ),
        )

        result as MergeEngine.Result.Outcome
        assertEquals(listOf(0), result.applied)
        assertEquals(1, result.conflicts.single().index)
        assertEquals("ONE TWO THREE four\n", d.text)
    }

    fun testMultipleEditsApplyInOneCall() {
        val d = doc("aaa\nbbb\nccc\n")
        engine.recordBase("p", 1, d.text)

        val result = engine.applyEdits(
            d, "p", 1,
            listOf(MergeEngine.Edit("aaa", "AAA"), MergeEngine.Edit("ccc", "CCC")),
        )

        result as MergeEngine.Result.Outcome
        assertEquals(listOf(0, 1), result.applied)
        assertEquals("AAA\nbbb\nCCC\n", d.text)
    }

    fun testBaseSnapshotRetentionLimit() {
        val d = doc("text\n")
        for (v in 1L..20L) engine.recordBase("p", v, "content v$v")
        // old snapshots evicted
        assertTrue(engine.applyEdits(d, "p", 1, listOf(MergeEngine.Edit("a", "b"))) is MergeEngine.Result.StaleBase)
        // recent ones retained
        assertTrue(engine.applyEdits(d, "p", 20, listOf(MergeEngine.Edit("nope", "b"))) is MergeEngine.Result.Outcome)
    }
}
