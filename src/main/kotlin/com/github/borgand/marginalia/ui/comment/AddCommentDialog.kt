package com.github.borgand.marginalia.ui.comment

import com.github.borgand.marginalia.ui.theme.MarginaliaColors
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The "Add comment" modal (redesign §03): the native [DialogWrapper] frame around a
 * [CommentForm], with the anchored-line context strip. OK is relabelled "Add comment".
 */
class AddCommentDialog(
    project: Project,
    fileName: String,
    line: Int,
    snippet: String,
) : DialogWrapper(project) {

    private val form = CommentForm(fileName, line, snippet)

    init {
        title = "Add comment"
        isResizable = true
        setOKButtonText("Add comment")
        init()
    }

    override fun createCenterPanel(): JComponent = form.component

    override fun getPreferredFocusedComponent(): JComponent = form.textArea

    override fun createSouthAdditionalPanel(): JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyLeft(8)
        add(
            JBLabel("Queues to the agent").apply {
                font = JBFont.small()
                foreground = MarginaliaColors.statusPending
            },
            BorderLayout.WEST,
        )
    }

    override fun doValidate() =
        if (form.body.isEmpty()) {
            ValidationInfo("Enter a comment for the agent.", form.textArea)
        } else {
            null
        }

    /** The captured body, valid only after [showAndGet] returns true. */
    val body: String get() = form.body

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)
}
