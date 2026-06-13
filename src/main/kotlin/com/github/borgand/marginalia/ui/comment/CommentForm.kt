package com.github.borgand.marginalia.ui.comment

import com.github.borgand.marginalia.ui.theme.MarginaliaColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The shared comment-capture body (redesign §03): a context strip naming the file + line,
 * the anchored line shown in the editor font inside a `status.pending`-ruled quote box, and
 * the text input. Reused by both [AddCommentDialog] and [InlineCommentPopup].
 */
class CommentForm(private val fileName: String, private val line: Int, private val snippet: String) {

    val textArea = JBTextArea(4, 42).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    /** Trimmed body text. */
    val body: String get() = textArea.text.trim()

    val component: JComponent by lazy { build() }

    private fun build(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(10, 12)

        add(contextStrip())
        add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
        add(quoteBox())
        add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
        add(JBScrollPane(textArea).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
    }

    private fun contextStrip(): JComponent {
        val icon = FileTypeManager.getInstance().getFileTypeByFileName(fileName).icon
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(
                JBLabel("$fileName   line ${line + 1}", icon, SwingConstants.LEFT).apply {
                    foreground = MarginaliaColors.textMuted
                    iconTextGap = JBUI.scale(5)
                },
                BorderLayout.WEST,
            )
        }
    }

    private fun quoteBox(): JComponent {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val editorFont = Font(scheme.editorFontName, Font.PLAIN, JBUI.scale(12))
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = MarginaliaColors.surfaceEditor
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(MarginaliaColors.statusPending, 0, 2, 0, 0),
                JBUI.Borders.empty(6, 8),
            )
            add(
                JBLabel(snippet.replace('\n', ' ').trim().ifEmpty { "(empty selection)" }).apply {
                    font = editorFont
                    foreground = MarginaliaColors.textPrimary
                },
                BorderLayout.CENTER,
            )
        }
    }
}
