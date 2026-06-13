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
data class TableSpan(val range: TextRange, val rows: List<List<String>>)

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
        // Track ranges already emitted as BOLD_ITALIC so the inner node is suppressed.
        val boldItalicRanges = mutableSetOf<TextRange>()
        file.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val type = element.node?.elementType
                when (type) {
                    MarkdownElementTypes.STRONG, MarkdownElementTypes.EMPH -> {
                        if (element.textRange !in boldItalicRanges) {
                            val kind = emphasisKindOf(element)
                            out += EmphasisSpan(kind, element.textRange)
                            if (kind == EmphasisKind.BOLD_ITALIC) {
                                // Suppress the inner EMPH or STRONG from producing its own span.
                                boldItalicRanges += innerEmphasisRange(element)
                            }
                        }
                    }
                    MarkdownElementTypes.STRIKETHROUGH ->
                        out += EmphasisSpan(EmphasisKind.STRIKETHROUGH, element.textRange)
                }
                super.visitElement(element)
            }
        })
        return out
    }

    /** Returns the text range of the nested EMPH or STRONG child element (to suppress double-emission). */
    private fun innerEmphasisRange(outer: PsiElement): TextRange {
        var child = outer.firstChild
        while (child != null) {
            val t = child.node?.elementType
            if (t == MarkdownElementTypes.EMPH || t == MarkdownElementTypes.STRONG) return child.textRange
            child = child.nextSibling
        }
        return outer.textRange // fallback: suppress entire range
    }

    /** Returns BOLD_ITALIC when a STRONG and EMPH are nested in either order; BOLD for plain STRONG; ITALIC for plain EMPH. */
    private fun emphasisKindOf(element: PsiElement): EmphasisKind {
        val type = element.node?.elementType
        val parentType = element.parent?.node?.elementType

        return when {
            // Parent-based detection: inner node whose direct parent is the complementary type
            type == MarkdownElementTypes.STRONG && parentType == MarkdownElementTypes.EMPH ->
                EmphasisKind.BOLD_ITALIC
            type == MarkdownElementTypes.EMPH && parentType == MarkdownElementTypes.STRONG ->
                EmphasisKind.BOLD_ITALIC
            // Child-based detection: outer node that directly contains the complementary type
            // (iterate all children — getChildOfType only returns the first child which may be a token leaf)
            type == MarkdownElementTypes.STRONG && hasDirectChild(element, MarkdownElementTypes.EMPH) ->
                EmphasisKind.BOLD_ITALIC
            type == MarkdownElementTypes.EMPH && hasDirectChild(element, MarkdownElementTypes.STRONG) ->
                EmphasisKind.BOLD_ITALIC
            type == MarkdownElementTypes.STRONG -> EmphasisKind.BOLD
            else -> EmphasisKind.ITALIC
        }
    }

    /** Returns true if [parent] has a direct child (not grandchild) with the given element type. */
    private fun hasDirectChild(parent: PsiElement, type: com.intellij.psi.tree.IElementType): Boolean {
        var child = parent.firstChild
        while (child != null) {
            if (child.node?.elementType == type) return true
            child = child.nextSibling
        }
        return false
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

    fun tables(file: PsiFile): List<TableSpan> {
        val out = mutableListOf<TableSpan>()
        val text = file.text
        val lines = text.split("\n")
        // precompute the start offset of each line
        val lineStarts = IntArray(lines.size)
        var acc = 0
        for (idx in lines.indices) { lineStarts[idx] = acc; acc += lines[idx].length + 1 }

        val sepRegex = Regex("""\s*\|?[\s:|-]*-[\s:|-]*\|?\s*""")
        var i = 0
        while (i < lines.size) {
            val isHeader = lines[i].contains("|")
            val hasSep = i + 1 < lines.size && lines[i + 1].contains("-") && lines[i + 1].matches(sepRegex)
            if (isHeader && hasSep) {
                var j = i
                val rows = mutableListOf<List<String>>()
                while (j < lines.size && lines[j].contains("|")) {
                    if (j != i + 1) rows += lines[j].trim().trim('|').split("|").map { it.trim() }
                    j++
                }
                val startOffset = lineStarts[i]
                val lastLine = j - 1
                val endOffset = (lineStarts[lastLine] + lines[lastLine].length).coerceAtMost(text.length)
                out += TableSpan(TextRange(startOffset, endOffset), rows)
                i = j
            } else {
                i++
            }
        }
        return out
    }

    private fun fenceContent(fence: MarkdownCodeFence): String =
        fence.text.lineSequence().drop(1).toMutableList().also { lines ->
            if (lines.isNotEmpty() && lines.last().trimStart().let { it.startsWith("```") || it.startsWith("~~~") })
                lines.removeAt(lines.size - 1)
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
