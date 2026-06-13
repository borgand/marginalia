package com.github.borgand.marginalia.ui.toolwindow

import com.github.borgand.marginalia.core.ActivityLog
import com.github.borgand.marginalia.mcp.McpServerService
import com.github.borgand.marginalia.ui.theme.MarginaliaColors
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Timer

/**
 * Footer status bar (redesign §02): a one-line "agent connected · last call Ns ago" line
 * with an `MCP :port` chip and a disclosure that toggles the raw connectivity log (the old
 * console — kept, but tucked away by default).
 */
class FooterStatusPanel(parent: Disposable) : JPanel(BorderLayout()) {

    private val server get() = service<McpServerService>()

    private val dot = JBLabel("●")
    private val statusText = JBLabel().apply { font = JBFont.small() }
    private val mcpChip = RoundedPill("MCP", MarginaliaColors.textMuted, MarginaliaColors.soft(MarginaliaColors.textMuted))
    private val toggle = JBLabel("▸ Log").apply {
        font = JBFont.small()
        foreground = MarginaliaColors.accent
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }

    private val logArea = JTextArea().apply {
        isEditable = false
        lineWrap = false
        font = JBFont.small()
    }
    private val logScroll = JBScrollPane(logArea).apply {
        isVisible = false
        preferredSize = java.awt.Dimension(0, JBUI.scale(150))
    }

    private val refreshTimer = Timer(2000) { refresh() }

    init {
        isOpaque = true
        background = MarginaliaColors.surfaceToolWindow
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(MarginaliaColors.border),
            JBUI.Borders.empty(4, 10),
        )

        val line = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        line.add(dot)
        line.add(statusText)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply { isOpaque = false }
        right.add(mcpChip)
        right.add(toggle)

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(line, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }

        add(header, BorderLayout.NORTH)
        add(logScroll, BorderLayout.CENTER)

        toggle.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                logScroll.isVisible = !logScroll.isVisible
                toggle.text = if (logScroll.isVisible) "▾ Log" else "▸ Log"
                revalidate(); repaint()
            }
        })

        val log = service<ActivityLog>()
        log.snapshot().forEach { appendLog(it) }
        log.addListener(parent) { line -> onEdt { appendLog(line) } }

        refreshTimer.isRepeats = true
        refreshTimer.start()
        Disposer.register(parent) { refreshTimer.stop() }

        refresh()
    }

    fun refresh() {
        val view = connectionView(server.state, server.lastClientConnectedAt != null, server.lastToolCallAt)
        dot.foreground = view.tone.color
        statusText.foreground = MarginaliaColors.textMuted
        statusText.text = view.footerLine
        mcpChip.text = "MCP :${server.port()}"
    }

    private fun appendLog(lineText: String) {
        logArea.append(lineText + "\n")
        logArea.caretPosition = logArea.document.length
    }

    private fun onEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) action() else app.invokeLater(action)
    }
}
