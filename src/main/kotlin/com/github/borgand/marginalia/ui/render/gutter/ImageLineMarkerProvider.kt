package com.github.borgand.marginalia.ui.render.gutter

import com.github.borgand.marginalia.ui.render.MarkdownStructure
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.net.URI
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JLabel

class ImageLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = elements.firstOrNull()?.containingFile ?: return
        if (!file.name.endsWith(".md", ignoreCase = true)) return
        for (img in MarkdownStructure.images(file)) {
            val leaf = file.findElementAt(img.lineRange.startOffset) ?: continue
            result += LineMarkerInfo(
                leaf, leaf.textRange,
                com.intellij.icons.AllIcons.FileTypes.Image,
                { "Preview image" },
                { _, _ -> showImage(img.url) },
                GutterIconRenderer.Alignment.LEFT,
                { "Preview image" },
            )
        }
    }

    private fun showImage(url: String) {
        val icon: Icon = try {
            if (url.startsWith("http")) ImageIcon(URI(url).toURL()) else ImageIcon(url)
        } catch (e: Exception) {
            return
        }
        val label = JLabel(icon)
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(label, label)
            .setResizable(true).setMovable(true).setRequestFocus(true)
            .createPopup()
            .showInFocusCenter()
    }
}
