package com.github.borgand.marginalia.ui.render

import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MarginaliaFoldingBuilderTest : BasePlatformTestCase() {

    private val builder = MarginaliaFoldingBuilder()

    /** Calls buildFoldRegions directly and collapses all resulting regions
     *  (simulating what the platform does on initial open via isCollapsedByDefault=true),
     *  then returns the text covered by each collapsed region. */
    private fun foldedTexts(text: String): List<String> {
        myFixture.configureByText("doc.md", text)
        val file = myFixture.file
        val editor = myFixture.editor
        val document = editor.document
        val descriptors: Array<FoldingDescriptor> = builder.buildFoldRegions(file, document, false)
        // Collapse each descriptor to simulate isCollapsedByDefault=true
        val collapsed = mutableListOf<String>()
        editor.foldingModel.runBatchFoldingOperation {
            for (d in descriptors) {
                val region: FoldRegion? = editor.foldingModel.addFoldRegion(
                    d.range.startOffset, d.range.endOffset, d.placeholderText ?: "…"
                )
                if (region != null) {
                    region.isExpanded = false
                }
            }
        }
        for (region in editor.foldingModel.allFoldRegions) {
            if (!region.isExpanded) {
                collapsed += text.substring(region.startOffset, region.endOffset)
            }
        }
        return collapsed
    }

    fun `test link destination folds by default`() {
        val folds = foldedTexts("see [docs](https://example.com/x) ok\n")
        assertTrue("folds were: $folds", folds.any { it == "](https://example.com/x)" })
    }

    fun `test html comment folds by default`() {
        val folds = foldedTexts("a\n\n<!-- hidden note -->\n\nb\n")
        assertTrue("folds were: $folds", folds.any { it.contains("hidden note") })
    }

    fun `test isCollapsedByDefault is true`() {
        myFixture.configureByText("doc.md", "see [docs](https://example.com/x)\n")
        val file = myFixture.file
        val document = myFixture.editor.document
        val descriptors = builder.buildFoldRegions(file, document, false)
        assertTrue("expected at least one descriptor", descriptors.isNotEmpty())
        for (d in descriptors) {
            assertTrue("isCollapsedByDefault must be true for ${d.range}", builder.isCollapsedByDefault(d.element))
        }
    }
}
