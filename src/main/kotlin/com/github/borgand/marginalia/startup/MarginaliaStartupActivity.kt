package com.github.borgand.marginalia.startup

import com.github.borgand.marginalia.mcp.McpServerService
import com.github.borgand.marginalia.ui.editor.CommentHighlighter
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the per-IDE MCP server and editor highlighting when a project opens. */
class MarginaliaStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Independent steps: a server-start failure must not skip editor highlighting.
        runCatching { service<McpServerService>().ensureStarted() }
        runCatching { project.service<CommentHighlighter>().install() }
    }
}
