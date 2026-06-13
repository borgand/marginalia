package com.github.borgand.marginalia.ui.render.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GutterMarkerTest : BasePlatformTestCase() {

    private fun markers(provider: com.intellij.codeInsight.daemon.LineMarkerProvider, text: String): List<LineMarkerInfo<*>> {
        val file = myFixture.configureByText("doc.md", text)
        val elements = PsiTreeUtil.collectElements(file) { true }.toList()
        val result = mutableListOf<LineMarkerInfo<*>>()
        provider.collectSlowLineMarkers(elements, result)
        return result
    }

    fun `test mermaid fence yields a gutter marker`() {
        val infos = markers(MermaidLineMarkerProvider(), "```mermaid\ngraph TD; A-->B;\n```\n")
        assertEquals(1, infos.size)
    }

    fun `test plain fence yields no mermaid marker`() {
        val infos = markers(MermaidLineMarkerProvider(), "```kotlin\nval x = 1\n```\n")
        assertEquals(0, infos.size)
    }

    fun `test image yields a gutter marker`() {
        val infos = markers(ImageLineMarkerProvider(), "![alt](https://example.com/a.png)\n")
        assertEquals(1, infos.size)
    }
}
