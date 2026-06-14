package com.github.borgand.marginalia.ui.comment

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI

/**
 * The Add-comment surface must not stretch with the selected text: a very long single-line
 * selection used to drive the dialog/popup width off-screen (bug: width "highly dependent on
 * the total length of selected text"). Width is now clamped regardless of snippet length.
 */
class CommentFormTest : BasePlatformTestCase() {

    fun testWidthIsClampedForLongSelection() {
        val longSnippet = "x".repeat(4000)
        val width = CommentForm("README.md", 0, longSnippet).component.preferredSize.width

        assertTrue(
            "long selection must not stretch the form past the max width (was $width)",
            width <= JBUI.scale(560),
        )
        assertTrue("form should keep a usable minimum width (was $width)", width >= JBUI.scale(360))
    }

    fun testWidthIsStableOnceSelectionExceedsSnippetCap() {
        // Any selection past the snippet truncation cap renders identically, so the dialog
        // width no longer tracks "the total length of selected text".
        val long = CommentForm("README.md", 0, "y".repeat(500)).component.preferredSize.width
        val longer = CommentForm("README.md", 0, "z".repeat(5000)).component.preferredSize.width

        assertEquals("width must not depend on selection length", long, longer)
    }
}
