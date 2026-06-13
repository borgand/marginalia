package com.github.borgand.marginalia.ui.render

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MarkdownLineDecoratorTest : BasePlatformTestCase() {

    fun `test highlighters added for blockquote and hr`() {
        myFixture.configureByText("doc.md", "> quote line\n\nbody\n\n---\n")
        val editor = myFixture.editor
        project.service<MarkdownLineDecorator>().refresh(editor)
        val ranges = editor.markupModel.allHighlighters
            .filter { it.customRenderer != null || it.lineMarkerRenderer != null }
        assertTrue("expected blockquote + hr decorations", ranges.size >= 2)
    }

    fun `test refresh is idempotent`() {
        myFixture.configureByText("doc.md", "> q\n\n---\n")
        val editor = myFixture.editor
        val dec = project.service<MarkdownLineDecorator>()
        dec.refresh(editor)
        val first = editor.markupModel.allHighlighters.count { it.customRenderer != null || it.lineMarkerRenderer != null }
        dec.refresh(editor)
        val second = editor.markupModel.allHighlighters.count { it.customRenderer != null || it.lineMarkerRenderer != null }
        assertEquals(first, second)
    }
}
