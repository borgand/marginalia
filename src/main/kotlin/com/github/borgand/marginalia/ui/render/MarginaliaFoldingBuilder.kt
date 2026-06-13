package com.github.borgand.marginalia.ui.render

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class MarginaliaFoldingBuilder : FoldingBuilderEx() {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file = root as? PsiFile ?: return emptyArray()
        val settings = RenderSettings.getInstance()
        val out = mutableListOf<FoldingDescriptor>()

        if (settings.foldLinkUrls) {
            for (link in MarkdownStructure.links(file)) {
                if (!link.foldRange.isEmpty) {
                    out += FoldingDescriptor(file.node, link.foldRange, null, "]")
                }
            }
        }
        if (settings.foldFrontmatter) {
            for (comment in htmlComments(file)) {
                out += FoldingDescriptor(file.node, comment, null, "<!-- … -->")
            }
            frontmatter(file)?.let { out += FoldingDescriptor(file.node, it, null, "--- front matter ---") }
        }
        return out.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "…"

    override fun isCollapsedByDefault(node: ASTNode): Boolean = true

    private fun htmlComments(file: PsiFile): List<TextRange> {
        val out = mutableListOf<TextRange>()
        val text = file.text
        Regex("""(?s)<!--.*?-->""").findAll(text).forEach { out += TextRange(it.range.first, it.range.last + 1) }
        return out
    }

    private fun frontmatter(file: PsiFile): TextRange? {
        val text = file.text
        if (!text.startsWith("---")) return null
        val end = text.indexOf("\n---", 3)
        if (end < 0) return null
        val close = text.indexOf('\n', end + 1).let { if (it < 0) text.length else it }
        return TextRange(0, close)
    }
}
