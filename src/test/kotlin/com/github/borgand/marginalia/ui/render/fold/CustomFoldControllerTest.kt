package com.github.borgand.marginalia.ui.render.fold

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CustomFoldControllerTest : BasePlatformTestCase() {

    fun `test h1 and h2 get custom folds when caret away`() {
        myFixture.configureByText("doc.md", "# Big One\n\n## Big Two\n\nbody text here\n")
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(editor.document.text.indexOf("body"))
        project.service<CustomFoldController>().refresh(editor)
        val custom = editor.foldingModel.allFoldRegions.filterIsInstance<com.intellij.openapi.editor.CustomFoldRegion>()
        assertEquals(2, custom.size)
    }

    fun `test caret on heading line leaves it unfolded`() {
        myFixture.configureByText("doc.md", "# Big One\n\nbody\n")
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(2) // inside "# Big One"
        project.service<CustomFoldController>().refresh(editor)
        val custom = editor.foldingModel.allFoldRegions.filterIsInstance<com.intellij.openapi.editor.CustomFoldRegion>()
        assertEquals(0, custom.size)
    }

    fun `test caret on table leaves it unfolded`() {
        myFixture.configureByText(
            "doc.md",
            "intro\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\noutro\n",
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(editor.document.text.indexOf("| 1 |"))
        project.service<CustomFoldController>().refresh(editor)
        val custom = editor.foldingModel.allFoldRegions
            .filterIsInstance<com.intellij.openapi.editor.CustomFoldRegion>()
        assertTrue("table should not be folded while caret is inside it", custom.isEmpty())
    }

    fun `test table gets a custom fold when caret away`() {
        myFixture.configureByText(
            "doc.md",
            "intro\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\noutro\n",
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(editor.document.text.indexOf("outro"))
        project.service<CustomFoldController>().refresh(editor)
        val custom = editor.foldingModel.allFoldRegions
            .filterIsInstance<com.intellij.openapi.editor.CustomFoldRegion>()
        assertTrue("expected at least one custom fold (the table)", custom.isNotEmpty())
    }

    fun `test inline image fold only when enabled`() {
        myFixture.configureByText("doc.md", "text\n\n![a](https://example.com/a.png)\n\nmore\n")
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(0)
        val settings = com.github.borgand.marginalia.ui.render.RenderSettings.getInstance()
        settings.inlineImages = false
        project.service<CustomFoldController>().refresh(editor)
        val before = editor.foldingModel.allFoldRegions.filterIsInstance<com.intellij.openapi.editor.CustomFoldRegion>().size
        settings.inlineImages = true
        project.service<CustomFoldController>().refresh(editor)
        val after = editor.foldingModel.allFoldRegions.filterIsInstance<com.intellij.openapi.editor.CustomFoldRegion>().size
        settings.inlineImages = false
        assertTrue("enabling inlineImages should add a fold (before=$before after=$after)", after > before)
    }
}
