package com.github.borgand.marginalia.ui.render.gutter

import com.github.borgand.marginalia.ui.render.MarkdownStructure
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import java.awt.Dimension

class MermaidLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = elements.firstOrNull()?.containingFile ?: return
        if (!file.name.endsWith(".md", ignoreCase = true)) return
        for (m in MarkdownStructure.mermaidFences(file)) {
            val leaf = file.findElementAt(m.fenceRange.startOffset) ?: continue
            result += LineMarkerInfo(
                leaf, leaf.textRange,
                com.intellij.icons.AllIcons.Actions.Preview,
                { "Render Mermaid diagram" },
                { _, _ -> showDiagram(m.code) },
                GutterIconRenderer.Alignment.LEFT,
                { "Render Mermaid diagram" },
            )
        }
    }

    private fun showDiagram(code: String) {
        if (!JBCefApp.isSupported()) return
        val browser = JBCefBrowser()
        browser.loadHTML(html(code))
        val panel = browser.component
        panel.preferredSize = Dimension(JBUI.scale(600), JBUI.scale(420))
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel)
            .setResizable(true).setMovable(true).setRequestFocus(true)
            .setTitle("Mermaid")
            .createPopup()
            .showInFocusCenter()
    }

    private fun mermaidJs(): String =
        javaClass.getResource("/render/mermaid.min.js")!!.readText()

    private fun html(code: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <style>body{margin:0;background:#fff}</style>
        <script>${mermaidJs()}</script></head>
        <body><pre class="mermaid">${code.replace("&", "&amp;").replace("<", "&lt;")}</pre>
        <script>mermaid.initialize({startOnLoad:true});</script>
        </body></html>
    """.trimIndent()
}
