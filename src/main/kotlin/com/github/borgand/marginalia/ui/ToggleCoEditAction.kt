package com.github.borgand.marginalia.ui

import com.github.borgand.marginalia.core.ActivityLog
import com.github.borgand.marginalia.core.DocRegistry
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

/** Manually mark the current file co-edited (or stop) without adding a comment. */
class ToggleCoEditAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val project = e.project
        if (project == null || file == null || file.isDirectory) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val registered = project.service<DocRegistry>().isCoEdited(file.path)
        e.presentation.text = if (registered) "Marginalia: Stop Co-Editing" else "Marginalia: Co-Edit This File"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val registry = project.service<DocRegistry>()
        if (registry.isCoEdited(file.path)) {
            registry.unregister(file.path)
            service<ActivityLog>().log("stopped co-editing ${file.path}")
        } else {
            registry.register(file)
            service<ActivityLog>().log("co-editing ${file.path}")
        }
    }
}
