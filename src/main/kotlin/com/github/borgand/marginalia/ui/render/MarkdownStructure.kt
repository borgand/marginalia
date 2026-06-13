package com.github.borgand.marginalia.ui.render

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownCodeFence
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader

enum class EmphasisKind { BOLD, ITALIC, BOLD_ITALIC, STRIKETHROUGH }

data class HeadingSpan(val level: Int, val markerRange: TextRange, val contentRange: TextRange, val fullRange: TextRange)
data class EmphasisSpan(val kind: EmphasisKind, val range: TextRange)
data class LinkSpan(val textRange: TextRange, val foldRange: TextRange, val url: String)
data class ImageSpan(val lineRange: TextRange, val url: String, val alt: String)
data class MermaidSpan(val fenceRange: TextRange, val code: String)
data class HorizontalRuleSpan(val lineRange: TextRange)
data class BlockquoteSpan(val range: TextRange)
data class ListMarkerSpan(val markerRange: TextRange, val ordered: Boolean)
data class TaskItemSpan(val checkboxCharRange: TextRange, val checked: Boolean)

object MarkdownStructure {

    fun headings(file: PsiFile): List<HeadingSpan> =
        PsiTreeUtil.findChildrenOfType(file, MarkdownHeader::class.java).mapNotNull { header ->
            val level = header.level
            val markerNode = header.node.findChildByType(MarkdownTokenTypes.ATX_HEADER)
                ?: header.node.findChildByType(MarkdownTokenTypes.SETEXT_1)
                ?: header.node.findChildByType(MarkdownTokenTypes.SETEXT_2)
            val contentNode = header.node.findChildByType(MarkdownTokenTypes.ATX_CONTENT)
                ?: header.node.findChildByType(MarkdownTokenTypes.SETEXT_CONTENT)
            val full = header.textRange
            HeadingSpan(
                level = level,
                markerRange = markerNode?.textRange ?: TextRange(full.startOffset, full.startOffset + level),
                contentRange = contentNode?.textRange ?: full,
                fullRange = full,
            )
        }

    fun emphasis(file: PsiFile): List<EmphasisSpan> {
        val out = mutableListOf<EmphasisSpan>()
        file.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element.node?.elementType) {
                    MarkdownElementTypes.STRONG ->
                        out += EmphasisSpan(emphasisKindOf(element) ?: EmphasisKind.BOLD, element.textRange)
                    MarkdownElementTypes.EMPH ->
                        out += EmphasisSpan(EmphasisKind.ITALIC, element.textRange)
                    MarkdownElementTypes.STRIKETHROUGH ->
                        out += EmphasisSpan(EmphasisKind.STRIKETHROUGH, element.textRange)
                }
                super.visitElement(element)
            }
        })
        return out
    }

    private fun emphasisKindOf(strong: PsiElement): EmphasisKind? {
        val parentIsEmph = PsiTreeUtil.getParentOfType(strong, PsiElement::class.java)
            ?.let { it.node?.elementType == MarkdownElementTypes.EMPH } == true
        return if (parentIsEmph) EmphasisKind.BOLD_ITALIC else EmphasisKind.BOLD
    }

    fun links(file: PsiFile): List<LinkSpan> {
        val out = mutableListOf<LinkSpan>()
        for (link in collectByType(file, MarkdownElementTypes.INLINE_LINK)) {
            val textNode = link.node.findChildByType(MarkdownElementTypes.LINK_TEXT) ?: continue
            val destNode = link.node.findChildByType(MarkdownElementTypes.LINK_DESTINATION) ?: continue
            // foldRange starts at the closing ']' of LINK_TEXT (textRange.endOffset - 1)
            // so the fold covers "](url)" — matching the test contract
            val foldStart = textNode.textRange.endOffset - 1
            val foldEnd = link.textRange.endOffset
            out += LinkSpan(
                textRange = TextRange(textNode.textRange.startOffset + 1, textNode.textRange.endOffset - 1),
                foldRange = TextRange(foldStart, foldEnd),
                url = destNode.text,
            )
        }
        return out
    }

    fun images(file: PsiFile): List<ImageSpan> =
        collectByType(file, MarkdownElementTypes.IMAGE).map { img ->
            val dest = img.node.findChildByType(MarkdownElementTypes.LINK_DESTINATION)?.text ?: ""
            val alt = img.node.findChildByType(MarkdownElementTypes.LINK_TEXT)?.text?.trim('[', ']') ?: ""
            ImageSpan(lineRange = img.textRange, url = dest, alt = alt)
        }

    fun mermaidFences(file: PsiFile): List<MermaidSpan> =
        PsiTreeUtil.findChildrenOfType(file, MarkdownCodeFence::class.java)
            .filter { it.fenceLanguage?.equals("mermaid", ignoreCase = true) == true }
            .map { MermaidSpan(it.textRange, fenceContent(it)) }

    fun horizontalRules(file: PsiFile): List<HorizontalRuleSpan> =
        collectTokens(file, MarkdownTokenTypes.HORIZONTAL_RULE).map { HorizontalRuleSpan(it.textRange) }

    fun blockquotes(file: PsiFile): List<BlockquoteSpan> =
        collectByType(file, MarkdownElementTypes.BLOCK_QUOTE).map { BlockquoteSpan(it.textRange) }

    fun listMarkers(file: PsiFile): List<ListMarkerSpan> {
        val bullets = collectTokens(file, MarkdownTokenTypes.LIST_BULLET)
            .map { ListMarkerSpan(it.textRange, ordered = false) }
        val numbers = collectTokens(file, MarkdownTokenTypes.LIST_NUMBER)
            .map { ListMarkerSpan(it.textRange, ordered = true) }
        return bullets + numbers
    }

    fun taskItems(file: PsiFile): List<TaskItemSpan> {
        val out = mutableListOf<TaskItemSpan>()
        val text = file.text
        val regex = Regex("""(?m)^\s*[-*+]\s+\[( |x|X)]\s""")
        for (m in regex.findAll(text)) {
            val inner = m.groups[1]!!.range
            out += TaskItemSpan(
                checkboxCharRange = TextRange(inner.first, inner.last + 1),
                checked = !m.groupValues[1].isBlank(),
            )
        }
        return out
    }

    private fun fenceContent(fence: MarkdownCodeFence): String =
        fence.text.lineSequence().drop(1).toMutableList().also { lines ->
            if (lines.isNotEmpty() && lines.last().trimStart().startsWith("```")) lines.removeAt(lines.size - 1)
        }.joinToString("\n")

    private fun collectByType(file: PsiFile, type: com.intellij.psi.tree.IElementType): List<PsiElement> {
        val out = mutableListOf<PsiElement>()
        file.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element.node?.elementType == type) out += element
                super.visitElement(element)
            }
        })
        return out
    }

    private fun collectTokens(file: PsiFile, type: com.intellij.psi.tree.IElementType): List<PsiElement> =
        collectByType(file, type)
}
