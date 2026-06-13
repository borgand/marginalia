package com.github.borgand.marginalia.ui.render

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class MarginaliaMarkdownAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return
        val settings = RenderSettings.getInstance()

        for (h in MarkdownStructure.headings(element)) {
            val key = when (h.level) {
                1 -> MarginaliaTextAttributes.H1
                2 -> MarginaliaTextAttributes.H2
                3 -> MarginaliaTextAttributes.H3
                else -> MarginaliaTextAttributes.H4_6
            }
            paint(holder, h.contentRange, key)
            if (settings.dimMarkers) paint(holder, h.markerRange, MarginaliaTextAttributes.DIMMED_MARKER)
        }

        for (e in MarkdownStructure.emphasis(element)) {
            val key = when (e.kind) {
                EmphasisKind.STRIKETHROUGH -> MarginaliaTextAttributes.STRIKETHROUGH
                else -> null
            } ?: continue
            paint(holder, e.range, key)
        }

        for (m in MarkdownStructure.listMarkers(element)) {
            paint(holder, m.markerRange, MarginaliaTextAttributes.LIST_MARKER)
        }
    }

    private fun paint(holder: AnnotationHolder, range: TextRange, key: TextAttributesKey) {
        if (range.isEmpty) return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(range)
            .textAttributes(key)
            .create()
    }
}
