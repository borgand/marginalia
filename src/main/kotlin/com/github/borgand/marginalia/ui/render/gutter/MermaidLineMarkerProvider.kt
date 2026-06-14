package com.github.borgand.marginalia.ui.render.gutter

import com.github.borgand.marginalia.ui.render.MarkdownStructure
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.event.MouseEvent

class MermaidLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = elements.firstOrNull()?.containingFile ?: return
        if (!file.name.endsWith(".md", ignoreCase = true)) return
        // The daemon calls this once per element batch (visible range, then the rest). Only emit
        // a marker when the fence's anchor leaf is in THIS batch, so each fence yields exactly one
        // icon — re-scanning the whole file every call would duplicate the gutter icon.
        val batch = elements.toHashSet()
        for (m in MarkdownStructure.mermaidFences(file)) {
            val leaf = file.findElementAt(m.fenceRange.startOffset) ?: continue
            if (leaf !in batch) continue
            result += LineMarkerInfo(
                leaf, leaf.textRange,
                com.intellij.icons.AllIcons.Actions.Preview,
                { "Render Mermaid diagram" },
                { e, _ -> showDiagram(m.code, e) },
                GutterIconRenderer.Alignment.LEFT,
                { "Render Mermaid diagram" },
            )
        }
    }

    private fun showDiagram(code: String, event: MouseEvent) {
        if (!JBCefApp.isSupported()) return
        val browser = JBCefBrowser()
        browser.loadHTML(html(code))
        val panel = browser.component
        panel.preferredSize = Dimension(JBUI.scale(600), JBUI.scale(420))
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel)
            .setResizable(true).setMovable(true).setRequestFocus(true)
            .setTitle("Mermaid")
            .createPopup()
        Disposer.register(popup, browser)
        // Anchor to the clicked gutter component so the popup opens in the window that was
        // clicked. showInFocusCenter() resolves the target frame from ambient global focus,
        // which points at the wrong window when multiple IDE frames are open.
        popup.show(RelativePoint(event))
    }

    private fun mermaidJs(): String =
        javaClass.getResource("/render/mermaid.min.js")?.readText() ?: run {
            Logger.getInstance(MermaidLineMarkerProvider::class.java)
                .warn("mermaid.min.js resource missing from plugin jar")
            ""
        }

    private fun html(code: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <style>body{margin:0;background:#fff}</style>
        <script>${mermaidJs()}</script></head>
        <body><pre class="mermaid">${code.replace("&", "&amp;").replace("<", "&lt;")}</pre>
        <script>mermaid.initialize({startOnLoad:true});</script>
        </body></html>
    """.trimIndent()
}
