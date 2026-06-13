package com.github.borgand.marginalia.ui.comment

import com.github.borgand.marginalia.ui.theme.MarginaliaColors
import com.github.borgand.marginalia.ui.theme.MarginaliaIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants

/**
 * In-flow comment capture (redesign §03): the same [CommentForm] in a balloon anchored at
 * the caret line, so commenting never leaves the editor. Submit with the Add button or ⌘↵.
 */
class InlineCommentPopup(
    private val editor: Editor,
    fileName: String,
    line: Int,
    snippet: String,
    private val onSubmit: (String) -> Unit,
) {

    private val form = CommentForm(fileName, line, snippet)
    private val displayName = "$fileName : ${line + 1}"
    private var popup: JBPopup? = null

    fun show() {
        val content = JPanel(BorderLayout()).apply {
            add(header(), BorderLayout.NORTH)
            add(form.component, BorderLayout.CENTER)
            add(footer(), BorderLayout.SOUTH)
        }
        bindSubmitShortcut()
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, form.textArea)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnClickOutside(false)
            .createPopup()
        popup?.showInBestPositionFor(editor)
    }

    private fun header(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
        border = JBUI.Borders.emptyBottom(2)
        add(JBLabel("Comment", MarginaliaIcons.Pen, SwingConstants.LEFT).apply { font = JBFont.regular().asBold() })
        add(JBLabel(displayName).apply { font = JBFont.small(); foreground = MarginaliaColors.textMuted })
        add(JBLabel("Queued").apply { font = JBFont.small(); foreground = MarginaliaColors.statusPending })
    }

    private fun footer(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(2, 12, 8, 12)
        add(
            JBLabel("Esc to cancel").apply { font = JBFont.small(); foreground = MarginaliaColors.textMuted },
            BorderLayout.WEST,
        )
        add(
            JButton("Add").apply {
                putClientProperty("JButton.buttonType", "default")
                toolTipText = "Add comment (${shortcutLabel()})"
                addActionListener { submit() }
            },
            BorderLayout.EAST,
        )
    }

    private fun bindSubmitShortcut() {
        val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        form.textArea.inputMap.put(stroke, "marginalia.submit")
        form.textArea.actionMap.put(
            "marginalia.submit",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = submit()
            },
        )
    }

    private fun submit() {
        val body = form.body
        if (body.isEmpty()) return
        popup?.cancel()
        onSubmit(body)
    }

    private fun shortcutLabel(): String =
        if (System.getProperty("os.name").startsWith("Mac")) "⌘↵" else "Ctrl+Enter"
}
