package com.github.borgand.marginalia.ui

import com.github.borgand.marginalia.core.DocRegistry
import com.github.borgand.marginalia.mcp.McpServerService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Assembles the "Test agent connectivity" report. Given the MCP model is pull-based
 * (the server cannot push to the agent), connectivity = "has an MCP client reached us,
 * and how recently". This confirms `claude mcp add` + a running agent are configured.
 */
object ConnectivityReport {

    fun build(project: Project): String {
        val server = service<McpServerService>()
        val port = server.port()
        val listening = server.probeListening()
        val coEdited = project.service<DocRegistry>().paths()

        val lines = mutableListOf<String>()

        lines += "MCP server: ${server.status}"
        lines += if (listening) "Port $port: accepting connections ✓"
        else "Port $port: NOT accepting connections ✗ (server not running — use Restart server)"

        val connectedAt = server.lastClientConnectedAt
        lines += if (connectedAt != null) {
            "Agent connection: a client connected ${ago(connectedAt)} ✓"
        } else {
            "Agent connection: no MCP client has ever connected ✗"
        }

        val toolAt = server.lastToolCallAt
        lines += if (toolAt != null) {
            "Last tool call: ${server.lastToolName} ${ago(toolAt)}"
        } else {
            "Last tool call: never"
        }

        lines += "Co-edited files in this project: ${coEdited.size}"

        lines += ""
        if (!listening) {
            lines += "→ The server isn't up. Click \"Restart server\" or check Settings > Tools > Marginalia."
        } else if (connectedAt == null) {
            lines += "→ The server is up but no agent has connected. Register it once with:"
            lines += "    claude mcp add --transport http marginalia http://localhost:$port/mcp"
            lines += "  then run \"/mcp\" in Claude Code to confirm 'marginalia' is listed."
        } else {
            lines += "→ The agent has reached the server. Note: Marginalia is pull-based —"
            lines += "  queued comments are delivered when the agent calls get_pending_comments"
            lines += "  (run /marginalia in Claude Code, or ask it to check Marginalia comments)."
        }

        return lines.joinToString("\n")
    }

    private fun ago(timestamp: Long): String {
        val seconds = (System.currentTimeMillis() - timestamp) / 1000
        return when {
            seconds < 5 -> "just now"
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            else -> "${seconds / 3600}h ago"
        }
    }
}
