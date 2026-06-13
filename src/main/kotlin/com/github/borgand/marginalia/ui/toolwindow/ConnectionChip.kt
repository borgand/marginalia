package com.github.borgand.marginalia.ui.toolwindow

import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/**
 * The connection chip (redesign §02): a rounded pill with a status dot + label, driven by
 * [ConnectionView]. Replaces the raw console as the at-a-glance signal in the header.
 */
class ConnectionChip : JComponent() {

    private var view = ConnectionView("…", ConnectionTone.STOPPED, "")

    init {
        font = JBUI.Fonts.smallFont()
        isOpaque = false
    }

    fun update(view: ConnectionView) {
        this.view = view
        toolTipText = view.footerLine
        revalidate(); repaint()
    }

    private val hPad get() = JBUI.scale(8)
    private val vPad get() = JBUI.scale(3)
    private val dot get() = JBUI.scale(6)
    private val gap get() = JBUI.scale(5)

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        return Dimension(hPad * 2 + dot + gap + fm.stringWidth(view.chipLabel), vPad * 2 + fm.height)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = height.toFloat()
            g2.color = ColorUtil.withAlpha(view.tone.color, 0.13)
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            val fm = g2.fontMetrics
            val dy = (height - dot) / 2
            g2.color = view.tone.color
            g2.fillOval(hPad, dy, dot, dot)
            g2.font = font
            g2.drawString(view.chipLabel, hPad + dot + gap, (height - fm.height) / 2 + fm.ascent)
        } finally {
            g2.dispose()
        }
    }
}
