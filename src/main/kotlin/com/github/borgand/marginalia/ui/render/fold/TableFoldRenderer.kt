package com.github.borgand.marginalia.ui.render.fold

import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D

/** Paints a markdown table as an aligned grid; unfolds to pipe source on caret entry. */
class TableFoldRenderer(private val rows: List<List<String>>) : CustomFoldRegionRenderer {

    private val padX = 10
    private val padY = 4

    private fun cols() = rows.maxOfOrNull { it.size } ?: 0

    private fun colWidths(region: CustomFoldRegion): IntArray {
        val fm = region.editor.contentComponent.getFontMetrics(
            region.editor.colorsScheme.getFont(EditorFontType.PLAIN))
        val widths = IntArray(cols())
        for (row in rows) row.forEachIndexed { c, cell ->
            if (c < widths.size) widths[c] = maxOf(widths[c], fm.stringWidth(cell) + padX * 2)
        }
        return widths
    }

    override fun calcWidthInPixels(region: CustomFoldRegion): Int = (colWidths(region).sum() + 1).coerceAtLeast(1)

    override fun calcHeightInPixels(region: CustomFoldRegion): Int =
        (rows.size.coerceAtLeast(1)) * (region.editor.lineHeight + padY)

    override fun paint(region: CustomFoldRegion, g: Graphics2D, target: Rectangle2D, attributes: TextAttributes) {
        val editor = region.editor
        val baseFont = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        g.font = baseFont
        val fm = g.fontMetrics
        val widths = colWidths(region)
        val rowH = editor.lineHeight + padY
        var y = target.y.toInt()
        for ((r, row) in rows.withIndex()) {
            var x = target.x.toInt()
            g.color = JBColor.GRAY
            g.drawRect(target.x.toInt(), y, widths.sum(), rowH)
            for (c in 0 until cols()) {
                g.color = JBColor.GRAY
                g.drawLine(x, y, x, y + rowH)
                val cell = row.getOrNull(c).orEmpty()
                g.color = editor.colorsScheme.defaultForeground
                g.font = if (r == 0) baseFont.deriveFont(Font.BOLD) else baseFont
                g.drawString(cell, x + padX, y + fm.ascent + padY / 2)
                x += widths[c]
            }
            y += rowH
        }
    }
}
