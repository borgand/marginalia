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
}
