package com.github.borgand.marginalia.ui.toolwindow

import com.github.borgand.marginalia.core.ActivityLog
import com.github.borgand.marginalia.core.CommentQueue
import com.github.borgand.marginalia.core.CommentStatus
import com.github.borgand.marginalia.core.CommentStore
import com.github.borgand.marginalia.core.DocRegistry
import com.github.borgand.marginalia.core.MarginaliaComment
import com.github.borgand.marginalia.mcp.McpServerService
import com.github.borgand.marginalia.ui.ConnectivityReport
import com.github.borgand.marginalia.ui.theme.MarginaliaColors
import com.github.borgand.marginalia.ui.theme.MarginaliaIcons
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.BadgeIconSupplier
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListSelectionModel

/**
 * The sidecar body. A [SimpleToolWindowPanel] with an action toolbar + connection chip on
 * top, a progress ribbon, the grouped comment list, and the footer status panel.
 */
class MarginaliaPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val store = project.service<CommentStore>()
    private val queue = project.service<CommentQueue>()

    private val listModel = CommentListModel()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = CommentListRenderer()
        background = MarginaliaColors.surfaceToolWindow
    }
    private val ribbon = ProgressRibbon()
    private val connectionChip = ConnectionChip()
    private val footer = FooterStatusPanel(this)
    private val baseBadge = BadgeIconSupplier(MarginaliaIcons.ToolWindow)

    init {
        toolbar = buildToolbar()
        setContent(buildContent())

        list.componentPopupMenu = buildPopupMenu()
        installListInteractions()

        store.addChangeListener(this) { onEdt { refresh() } }
        refresh()
    }

    // ── construction ────────────────────────────────────────────────────────────
    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(AutoQueueToggle())
            val more = DefaultActionGroup("More", true).apply {
                templatePresentation.icon = AllIcons.General.GearPlain
                add(RestartServerAction())
                add(TestConnectivityAction())
            }
            add(more)
        }
        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar("MarginaliaToolbar", group, true)
        actionToolbar.targetComponent = this

        val submit = JButton("Submit review").apply {
            putClientProperty("JButton.buttonType", "default")
            toolTipText = "Queue all draft comments for the agent"
            addActionListener {
                val n = queue.submitReview()
                service<ActivityLog>().log("submit review: $n comment(s) queued")
            }
        }

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), JBUI.scale(2))).apply {
            isOpaque = false
            add(submit)
            add(connectionChip)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(actionToolbar.component, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }
    }

    private fun buildContent(): JComponent = JPanel(BorderLayout()).apply {
        background = MarginaliaColors.surfaceToolWindow
        add(ribbon, BorderLayout.NORTH)
        add(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
    }

    private fun installListInteractions() {
        // single click on a file header toggles its fold
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = list.locationToIndex(e.point).takeIf { it >= 0 } ?: return
                if (!list.getCellBounds(index, index).contains(e.point)) return
                val row = listModel.getElementAt(index)
                if (row is SidecarRow.FileHeaderRow) listModel.toggleCollapsed(row.path)
            }
        })
        // double click / Enter on a comment jumps to its anchored line
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                jumpToSelected(); return true
            }
        }.installOn(list)
    }

    private fun buildPopupMenu(): JPopupMenu = JPopupMenu().apply {
        add(JMenuItem("Jump to line").apply { addActionListener { jumpToSelected() } })
        add(JMenuItem("Resolve").apply {
            addActionListener { selectedComment()?.let { store.setStatus(it.id, CommentStatus.RESOLVED) } }
        })
        add(JMenuItem("Re-queue").apply {
            addActionListener { selectedComment()?.let { store.setStatus(it.id, CommentStatus.QUEUED) } }
        })
        add(JMenuItem("Delete").apply {
            addActionListener { selectedComment()?.let { store.remove(it.id) } }
        })
        add(JMenuItem("Stop co-editing this file").apply {
            addActionListener {
                selectedComment()?.let { project.service<DocRegistry>().unregister(it.filePath) }
            }
        })
    }

    // ── behavior ──────────────────────────────────────────────────────────────
    private fun selectedComment(): MarginaliaComment? =
        (list.selectedValue as? SidecarRow.CommentRow)?.comment

    private fun jumpToSelected() {
        val comment = selectedComment() ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(comment.filePath) ?: return
        val line = lineOf(comment)
        val descriptor = if (line != null) {
            OpenFileDescriptor(project, file, line, 0)
        } else {
            OpenFileDescriptor(project, file, comment.startOffset)
        }
        descriptor.navigate(true)
    }

    /** 0-based anchor line from a live marker, or null when no valid marker exists. */
    private fun lineOf(comment: MarginaliaComment): Int? {
        val marker = store.markerFor(comment.id) ?: return null
        return ReadAction.compute<Int?, RuntimeException> {
            if (marker.isValid) marker.document.getLineNumber(marker.startOffset) else null
        }
    }

    private fun refresh() {
        store.syncOffsetsFromMarkers()
        val comments = store.comments()
        listModel.setComments(comments) { lineOf(it) }

        val counts = comments.groupingBy { visualStatus(it) }.eachCount()
        ribbon.update(
            resolved = counts[VisualStatus.RESOLVED] ?: 0,
            delivered = counts[VisualStatus.DELIVERED] ?: 0,
            queued = counts[VisualStatus.QUEUED] ?: 0,
        )

        val view = connectionView(
            service<McpServerService>().state,
            service<McpServerService>().lastClientConnectedAt != null,
            service<McpServerService>().lastToolCallAt,
        )
        connectionChip.update(view)
        footer.refresh()

        val queued = counts[VisualStatus.QUEUED] ?: 0
        toolWindow.setIcon(baseBadge.getWarningIcon(queued > 0))
    }

    private fun onEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) action() else app.invokeLater(action)
    }

    override fun dispose() {}

    // ── toolbar actions ─────────────────────────────────────────────────────────
    private inner class AutoQueueToggle : ToggleAction("Auto-queue", AUTO_QUEUE_TOOLTIP, AllIcons.Actions.Lightning) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = queue.autoDispatch
        override fun setSelected(e: AnActionEvent, state: Boolean) { queue.autoDispatch = state }
    }

    private inner class RestartServerAction : AnAction("Restart Server", "Restart the MCP server (picks up a changed port)", AllIcons.Actions.Restart) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            ApplicationManager.getApplication().executeOnPooledThread {
                service<McpServerService>().restart()
                onEdt { refresh() }
            }
        }
    }

    private inner class TestConnectivityAction : AnAction("Test Agent Connectivity", "Check whether the agent has reached the MCP server", AllIcons.Actions.Lightning) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val report = ConnectivityReport.build(project)
                onEdt {
                    service<ActivityLog>().log("connectivity test:\n$report")
                    Messages.showMessageDialog(
                        project, report, "Marginalia — Agent Connectivity", Messages.getInformationIcon(),
                    )
                }
            }
        }
    }

    companion object {
        private const val AUTO_QUEUE_TOOLTIP =
            "On: new comments are immediately available to the agent (status queued). " +
                "Off: comments stay draft until you click Submit review."
    }
}
