package com.github.borgand.marginalia.ui.toolwindow

import com.github.borgand.marginalia.mcp.McpServerService
import com.github.borgand.marginalia.ui.theme.MarginaliaColors
import java.awt.Color

/** Visual weight of the connection chip/footer (redesign §02 — "Connected" chip). */
enum class ConnectionTone {
    CONNECTED, WAITING, ERROR, STOPPED;

    val color: Color
        get() = when (this) {
            CONNECTED -> MarginaliaColors.statusResolved
            WAITING -> MarginaliaColors.statusPending
            ERROR -> MarginaliaColors.statusConflict
            STOPPED -> MarginaliaColors.textMuted
        }
}

/** What the chip and footer should show, derived from server state. */
data class ConnectionView(val chipLabel: String, val tone: ConnectionTone, val footerLine: String)

/**
 * Pure derivation of the connection view from MCP server state — the single place that
 * turns "is the agent there?" into words. Testable headless.
 */
fun connectionView(
    state: McpServerService.State,
    hasConnected: Boolean,
    lastToolCallAt: Long?,
    now: Long = System.currentTimeMillis(),
): ConnectionView = when (state) {
    McpServerService.State.FAILED ->
        ConnectionView("Server error", ConnectionTone.ERROR, "MCP server failed — see Settings > Tools > Marginalia")

    McpServerService.State.STOPPED ->
        ConnectionView("Stopped", ConnectionTone.STOPPED, "MCP server stopped")

    McpServerService.State.RUNNING ->
        if (hasConnected) {
            val tail = lastToolCallAt?.let { " · last call ${relativeTime(it, now)}" } ?: ""
            ConnectionView("Connected", ConnectionTone.CONNECTED, "Agent connected$tail")
        } else {
            ConnectionView("Waiting for agent", ConnectionTone.WAITING, "Server up · no agent has connected yet")
        }
}
