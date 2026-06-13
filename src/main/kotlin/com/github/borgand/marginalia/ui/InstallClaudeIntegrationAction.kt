package com.github.borgand.marginalia.ui

import com.github.borgand.marginalia.core.ActivityLog
import com.github.borgand.marginalia.hooks.ClaudeSettingsMerger
import com.github.borgand.marginalia.mcp.McpServerService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Installs the Claude Code integration (PRD F4/F8 fallback), with confirmation:
 *  - ~/.marginalia/marginalia-hook.sh        (PreToolUse deny script, +x)
 *  - hook entry in ~/.claude/settings.json   (idempotent merge)
 *  - ~/.claude/commands/marginalia.md        (/marginalia slash command)
 */
class InstallClaudeIntegrationAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val home = Path.of(System.getProperty("marginalia.home") ?: System.getProperty("user.home"))
        val hookTarget = home.resolve(".marginalia/marginalia-hook.sh")
        val settingsTarget = home.resolve(".claude/settings.json")
        val commandTarget = home.resolve(".claude/commands/marginalia.md")

        val confirmed = Messages.showYesNoDialog(
            project,
            """
            This will:
              • write $hookTarget (and make it executable)
              • add a PreToolUse hook entry to $settingsTarget
              • write $commandTarget (/marginalia slash command)

            The hook denies Claude's native Edit/Write on co-edited files and requires 'jq'.
            Register the MCP server separately (once):
              claude mcp add --transport http marginalia http://localhost:${service<McpServerService>().port()}/mcp

            Proceed?
            """.trimIndent(),
            "Install Claude Code Integration",
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!confirmed) return

        try {
            installResource("/hooks/marginalia-hook.sh", hookTarget, executable = true)
            installResource("/claude/marginalia-command.md", commandTarget, executable = false)

            val existing = if (Files.exists(settingsTarget)) Files.readString(settingsTarget) else ""
            Files.createDirectories(settingsTarget.parent)
            Files.writeString(settingsTarget, ClaudeSettingsMerger.merge(existing, hookTarget.toString()))

            service<ActivityLog>().log("installed Claude Code integration (hook + settings + /marginalia)")
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Marginalia")
                .createNotification(
                    "Marginalia integration installed",
                    "Hook, settings entry and /marginalia command are in place. " +
                        "Restart any running Claude Code session to pick up the hook.",
                    NotificationType.INFORMATION,
                )
                .notify(project)
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, "Installation failed: ${ex.message}", "Marginalia")
        }
    }

    private fun installResource(resource: String, target: Path, executable: Boolean) {
        val stream = javaClass.getResourceAsStream(resource)
            ?: error("plugin resource missing: $resource")
        Files.createDirectories(target.parent)
        stream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        if (executable) {
            try {
                val perms = Files.getPosixFilePermissions(target).toMutableSet()
                perms += setOf(
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_EXECUTE,
                )
                Files.setPosixFilePermissions(target, perms)
            } catch (_: UnsupportedOperationException) {
                // non-POSIX FS — hook won't run anyway
            }
        }
    }
}
