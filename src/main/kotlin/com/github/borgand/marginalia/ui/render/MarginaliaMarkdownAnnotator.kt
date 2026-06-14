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
            // Layer bold/italic (and an H1 underline) over the heading color so titles stand out
            // without the Tier-2 big-title fold (which the platform's section folding blocks).
            when (h.level) {
                1 -> paint(holder, h.contentRange, MarginaliaTextAttributes.H1_STYLE)
                2 -> paint(holder, h.contentRange, MarginaliaTextAttributes.H2_STYLE)
            }
            if (settings.dimMarkers) paint(holder, h.markerRange, MarginaliaTextAttributes.DIMMED_MARKER)
        }

        for (e in MarkdownStructure.emphasis(element)) {
            val key = when (e.kind) {
                EmphasisKind.BOLD -> MarginaliaTextAttributes.BOLD
                EmphasisKind.ITALIC -> MarginaliaTextAttributes.ITALIC
                EmphasisKind.BOLD_ITALIC -> MarginaliaTextAttributes.BOLD_ITALIC
                EmphasisKind.STRIKETHROUGH -> MarginaliaTextAttributes.STRIKETHROUGH
            }
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
