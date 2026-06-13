package com.github.borgand.marginalia.core

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ReloadGuardTest : BasePlatformTestCase() {

    private fun doc(text: String): Document {
        myFixture.configureByText("doc.md", text)
        return myFixture.editor.document
    }

    private fun setDoc(d: Document, text: String) =
        WriteCommandAction.runWriteCommandAction(project) { d.setText(text) }

    fun testUserHunksRoundTrip() {
        val base = "a\nb\nc\nd\n"
        val ours = "a\nB\nc\nd\nnew line\n"
        val edits = UserHunks.editsFor(base, ours)
        // applying the extracted hunks back onto base must reproduce ours
        var text = base
        for (e in edits) {
            assertTrue("old_text '${e.oldText}' must exist in base", text.contains(e.oldText))
            text = text.replaceFirst(e.oldText, e.newText)
        }
        assertEquals(ours, text)
    }

    fun testNonConflictingExternalAndUserChangesBothSurvive() {
        val base = "alpha\nbravo\ncharlie\ndelta\necho\n"
        val ours = "alpha\nUSER EDIT\ncharlie\ndelta\necho\n"       // user changed line 2
        val theirs = "alpha\nbravo\ncharlie\ndelta\nEXTERNAL\n"     // external changed line 5

        val d = doc(theirs) // reload already happened: buffer == disk
        val result = ReloadGuard.mergeUserChanges(project, d, base, ours)

        assertTrue(result is ReloadGuard.MergeOutcome.Merged)
        assertEquals("alpha\nUSER EDIT\ncharlie\ndelta\nEXTERNAL\n", d.text)
    }

    fun testOverlappingChangeUserWinsWholesale() {
        val base = "alpha\nbravo\ncharlie\n"
        val ours = "alpha\nUSER VERSION\ncharlie\n"
        val theirs = "alpha\nEXTERNAL VERSION\ncharlie\n" // same line — true conflict

        val d = doc(theirs)
        val result = ReloadGuard.mergeUserChanges(project, d, base, ours)

        assertTrue(result is ReloadGuard.MergeOutcome.UserRestored)
        assertEquals(ours, d.text) // user wins: buffer restored wholesale
    }

    fun testNoUserChangesIsNoOp() {
        val base = "alpha\n"
        val d = doc("alpha external\n")
        val result = ReloadGuard.mergeUserChanges(project, d, base, base)
        assertTrue(result is ReloadGuard.MergeOutcome.NoOp)
        assertEquals("alpha external\n", d.text)
    }

    fun testInsertionAtStartOfFile() {
        val base = "first\nsecond\n"
        val ours = "inserted\nfirst\nsecond\n"
        val edits = UserHunks.editsFor(base, ours)
        var text = base
        for (e in edits) {
            assertTrue(text.contains(e.oldText))
            text = text.replaceFirst(e.oldText, e.newText)
        }
        assertEquals(ours, text)
    }

    fun testDeletionHunk() {
        val base = "keep\nremove me\nkeep too\n"
        val ours = "keep\nkeep too\n"
        val edits = UserHunks.editsFor(base, ours)
        var text = base
        for (e in edits) {
            assertTrue(text.contains(e.oldText))
            text = text.replaceFirst(e.oldText, e.newText)
        }
        assertEquals(ours, text)
    }
}
