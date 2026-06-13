package com.github.borgand.marginalia.ui

import com.github.borgand.marginalia.mcp.McpServerService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

/** Manually (re)start the MCP server — recovery path after a failed auto-start. */
class RestartServerAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        // start() blocks briefly on a socket probe + engine init — keep it off the EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            service<McpServerService>().restart()
        }
    }
}
