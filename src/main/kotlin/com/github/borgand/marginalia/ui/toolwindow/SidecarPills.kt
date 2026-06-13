package com.github.borgand.marginalia.ui.toolwindow

import com.github.borgand.marginalia.ui.theme.MarginaliaColors
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/**
 * A small rounded label used for status pills and the `L13` line chip (redesign §02).
 * Paints a soft rounded background + optional leading dot, then the text.
 */
class RoundedPill(
    text: String,
    private val fg: Color,
    private val bg: Color,
    private val withDot: Boolean = false,
) : JComponent() {

    var text: String = text
        set(value) {
            field = value
            revalidate(); repaint()
        }

    init {
        font = JBUI.Fonts.smallFont()
        isOpaque = false
    }

    private val hPad get() = JBUI.scale(7)
    private val vPad get() = JBUI.scale(2)
    private val dotSize get() = JBUI.scale(6)
    private val dotGap get() = JBUI.scale(5)

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val dotW = if (withDot) dotSize + dotGap else 0
        return Dimension(
            hPad * 2 + dotW + fm.stringWidth(text),
            vPad * 2 + fm.height,
        )
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = height.toFloat()
            g2.color = bg
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            var x = hPad
            val fm = g2.fontMetrics
            if (withDot) {
                g2.color = fg
                val dy = (height - dotSize) / 2
                g2.fillOval(x, dy, dotSize, dotSize)
                x += dotSize + dotGap
            }
            g2.color = fg
            g2.font = font
            val ty = (height - fm.height) / 2 + fm.ascent
            g2.drawString(text, x, ty)
        } finally {
            g2.dispose()
        }
    }

    companion object {
        /** A status pill: soft tinted bg, status-colored dot + text. */
        fun status(status: VisualStatus): RoundedPill =
            RoundedPill(status.label, status.color, status.softColor, withDot = true)

        /** A neutral line chip, e.g. `L13`. */
        fun lineChip(line: Int): RoundedPill =
            RoundedPill("L${line + 1}", MarginaliaColors.textMuted, MarginaliaColors.soft(MarginaliaColors.textMuted))
    }
}
