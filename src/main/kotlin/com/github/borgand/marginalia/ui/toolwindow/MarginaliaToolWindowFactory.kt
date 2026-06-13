package com.github.borgand.marginalia.ui.toolwindow

import com.github.borgand.marginalia.ui.theme.MarginaliaIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Queue + activity sidecar (PRD F7, redesign §02). Explicitly NOT a chat: pending comments
 * grouped by file, a review-progress ribbon, a connection chip, and a tucked-away log.
 */
class MarginaliaToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.setIcon(MarginaliaIcons.ToolWindow)
        val panel = MarginaliaPanel(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
