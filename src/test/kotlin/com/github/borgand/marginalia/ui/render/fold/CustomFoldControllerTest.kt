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

    /**
     * Documents a known platform limitation: in the real IDE the bundled Markdown folding builder
     * creates a section-fold region on the heading line, and [FoldingModelEx.addCustomLinesFolding]
     * returns null when its lines overlap an existing fold region — so the Tier-2 big-title fold
     * silently can't apply. This is why headings instead get bold/italic/underline text styling
     * (see MarginaliaMarkdownAnnotator). If a future platform change lifts the overlap restriction,
     * this test will start failing and we can revisit the big-title fold.
     */
    fun `test big title fold is blocked by an overlapping section fold (known limitation)`() {
        myFixture.configureByText("doc.md", "# Big One\n\nbody text here\n")
        val editor = myFixture.editor
        val doc = editor.document
        editor.foldingModel.runBatchFoldingOperation {
            editor.foldingModel.addFoldRegion(0, doc.text.indexOf("body"), "…")
        }
        editor.caretModel.moveToOffset(doc.text.indexOf("body"))
        project.service<CustomFoldController>().refresh(editor)
        val custom = editor.foldingModel.allFoldRegions.filterIsInstance<com.intellij.openapi.editor.CustomFoldRegion>()
        assertEquals("overlapping section fold blocks the big-title custom fold", 0, custom.size)
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

    fun `test link fold collapses off its line and expands on it`() {
        myFixture.configureByText("doc.md", "see [docs](https://example.com/x) ok\n\nmore body\n")
        val editor = myFixture.editor
        val doc = editor.document
        // Build the link fold region exactly as MarginaliaFoldingBuilder would, collapsed.
        val descriptors = com.github.borgand.marginalia.ui.render.MarginaliaFoldingBuilder()
            .buildFoldRegions(myFixture.file, doc, false)
        editor.foldingModel.runBatchFoldingOperation {
            for (d in descriptors) {
                editor.foldingModel.addFoldRegion(d.range.startOffset, d.range.endOffset, "]")
                    ?.let { it.isExpanded = false }
            }
        }
        val linkRegion = editor.foldingModel.allFoldRegions
            .first { it !is com.intellij.openapi.editor.CustomFoldRegion }

        editor.caretModel.moveToOffset(doc.text.indexOf("more"))
        project.service<CustomFoldController>().refresh(editor)
        assertFalse("link fold should stay collapsed when caret is off its line", linkRegion.isExpanded)

        editor.caretModel.moveToOffset(doc.text.indexOf("docs"))
        project.service<CustomFoldController>().refresh(editor)
        assertTrue("link fold should expand when caret is on its line", linkRegion.isExpanded)

        editor.caretModel.moveToOffset(doc.text.indexOf("more"))
        project.service<CustomFoldController>().refresh(editor)
        assertFalse("link fold should re-collapse when caret leaves its line", linkRegion.isExpanded)
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
