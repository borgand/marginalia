package com.github.borgand.marginalia.ui.toolwindow

import com.github.borgand.marginalia.ui.theme.MarginaliaColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * Renders the grouped sidecar list (redesign §02): file-header rows and comment cards,
 * switching layout on [SidecarRow] kind. Colors come from [MarginaliaColors]; the card's
 * left status accent bar is painted for a rounded look.
 */
class CommentListRenderer : ListCellRenderer<SidecarRow> {

    override fun getListCellRendererComponent(
        list: JList<out SidecarRow>,
        value: SidecarRow,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component = when (value) {
        is SidecarRow.FileHeaderRow -> header(value, isSelected)
        is SidecarRow.CommentRow -> card(value, isSelected, list.width)
    }

    // ── file header ───────────────────────────────────────────────────────────
    private fun header(row: SidecarRow.FileHeaderRow, selected: Boolean): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = true
            background = rowBackground(selected)
            border = JBUI.Borders.empty(6, 10, 4, 10)
        }

        val nameRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }
        nameRow.add(
            JBLabel(if (row.collapsed) "▸" else "▾").apply {
                foreground = MarginaliaColors.textMuted
                border = JBUI.Borders.emptyRight(4)
            },
        )
        val fileIcon = FileTypeManager.getInstance().getFileTypeByFileName(row.fileName).icon
        nameRow.add(
            JBLabel(row.fileName, fileIcon, SwingConstants.LEFT).apply {
                font = JBFont.regular().asBold()
                foreground = MarginaliaColors.textPrimary
                iconTextGap = JBUI.scale(4)
            },
        )
        nameRow.add(
            JBLabel("  ${row.total}").apply {
                font = JBFont.small()
                foreground = MarginaliaColors.textMuted
            },
        )

        panel.add(nameRow, BorderLayout.WEST)
        if (row.queuedCount > 0) {
            val east = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyLeft(6)
                add(
                    RoundedPill(
                        "${row.queuedCount} to send",
                        MarginaliaColors.statusPending,
                        MarginaliaColors.soft(MarginaliaColors.statusPending),
                    ),
                    BorderLayout.EAST,
                )
            }
            panel.add(east, BorderLayout.EAST)
        }
        return panel
    }

    // ── comment card ────────────────────────────────────────────────────────────
    private fun card(row: SidecarRow.CommentRow, selected: Boolean, listWidth: Int): JComponent {
        val status = visualStatus(row.comment)
        val card = CardPanel(status.color).apply {
            isOpaque = true
            background = rowBackground(selected)
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 16, 8, 10)
        }

        // row 1: status pill + line chip
        val top = horizontal()
        top.add(RoundedPill.status(status))
        if (row.line != null) {
            top.add(strut())
            top.add(RoundedPill.lineChip(row.line))
        }
        top.add(javax.swing.Box.createHorizontalGlue())
        card.add(top)

        // row 2: anchor snippet (editor font, muted)
        val snippet = row.comment.anchoredText.replace('\n', ' ').trim()
        if (snippet.isNotEmpty()) {
            card.add(vstrut())
            card.add(
                JBLabel(truncate(snippet, 52)).apply {
                    font = editorFont()
                    foreground = MarginaliaColors.textMuted
                    border = JBUI.Borders.emptyLeft(2)
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
        }

        // row 3: comment body, wraps to ~2 lines
        card.add(vstrut())
        val bodyWidth = (listWidth - JBUI.scale(56)).coerceAtLeast(JBUI.scale(120))
        card.add(
            JBLabel(htmlBody(row.comment.body, bodyWidth)).apply {
                font = JBFont.regular()
                foreground = MarginaliaColors.textPrimary
                alignmentX = Component.LEFT_ALIGNMENT
            },
        )

        // row 4: avatar + "You · 2m ago"
        card.add(vstrut())
        val meta = horizontal()
        meta.add(Avatar())
        meta.add(strut())
        meta.add(
            JBLabel("You · ${relativeTime(row.comment.createdAt)}").apply {
                font = JBFont.small()
                foreground = MarginaliaColors.textMuted
            },
        )
        meta.add(javax.swing.Box.createHorizontalGlue())
        card.add(meta)

        return card
    }

    private fun horizontal() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun strut() = javax.swing.Box.createHorizontalStrut(JBUI.scale(6))
    private fun vstrut() = javax.swing.Box.createVerticalStrut(JBUI.scale(5))

    private fun rowBackground(selected: Boolean): Color =
        if (selected) MarginaliaColors.selectionBg else MarginaliaColors.surfaceToolWindow

    private fun editorFont(): Font {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return Font(scheme.editorFontName, Font.PLAIN, JBUI.scale(12))
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1) + "…"

    private fun htmlBody(body: String, widthPx: Int): String {
        val collapsed = body.trim().replace(Regex("\\s+"), " ")
        val clipped = truncate(collapsed, 180)
        val escaped = clipped
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<html><body style='width:${widthPx}px'>$escaped</body></html>"
    }

    /** Card panel that paints a rounded status accent bar at the left edge. */
    private class CardPanel(private val statusColor: Color) : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = statusColor
                val x = JBUI.scale(6).toFloat()
                val w = com.intellij.ui.scale.JBUIScale.scale(2.5f)
                val inset = JBUI.scale(6).toFloat()
                g2.fill(RoundRectangle2D.Float(x, inset, w, height - inset * 2, w, w))
            } finally {
                g2.dispose()
            }
        }
    }

    /** A small initials avatar. */
    private class Avatar : JComponent() {
        private val size get() = JBUI.scale(18)

        init {
            isOpaque = false
            font = JBUI.Fonts.miniFont()
        }

        override fun getPreferredSize() = Dimension(size, size)
        override fun getMaximumSize() = preferredSize

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = ColorUtil.withAlpha(MarginaliaColors.accent, 0.20)
                g2.fillOval(0, 0, size - 1, size - 1)
                g2.color = MarginaliaColors.accent
                g2.font = font
                val fm = g2.fontMetrics
                val s = "Y"
                g2.drawString(s, (size - fm.stringWidth(s)) / 2, (size - fm.height) / 2 + fm.ascent)
            } finally {
                g2.dispose()
            }
        }
    }
}
