package com.github.borgand.marginalia.ui.render.fold

import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D

/** Paints a single heading line as large bold text. Unfolds to source when caret enters. */
class BigTitleFoldRenderer(private val text: String, private val level: Int) : CustomFoldRegionRenderer {

    private var cachedFont: java.awt.Font? = null
    private fun scaledFont(base: Font): Font {
        cachedFont?.let { return it }
        val factor = if (level == 1) 1.9f else 1.5f
        return base.deriveFont(Font.BOLD, base.size * factor).also { cachedFont = it }
    }

    override fun calcWidthInPixels(region: CustomFoldRegion): Int {
        val editor = region.editor
        val font = scaledFont(editor.colorsScheme.getFont(EditorFontType.PLAIN))
        val metrics = editor.contentComponent.getFontMetrics(font)
        return metrics.stringWidth(text) + 8
    }

    override fun calcHeightInPixels(region: CustomFoldRegion): Int {
        val editor = region.editor
        val font = scaledFont(editor.colorsScheme.getFont(EditorFontType.PLAIN))
        return editor.contentComponent.getFontMetrics(font).height
    }

    override fun paint(region: CustomFoldRegion, g: Graphics2D, target: Rectangle2D, attributes: TextAttributes) {
        val editor = region.editor
        val font = scaledFont(editor.colorsScheme.getFont(EditorFontType.PLAIN))
        g.font = font
        g.color = editor.colorsScheme.defaultForeground ?: JBColor.foreground()
        val fm = g.fontMetrics
        g.drawString(text, target.x.toInt() + 4, (target.y + fm.ascent).toInt())
    }
}
