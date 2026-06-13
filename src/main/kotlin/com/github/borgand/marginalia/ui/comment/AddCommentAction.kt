package com.github.borgand.marginalia.ui.comment

import com.github.borgand.marginalia.core.ActivityLog
import com.github.borgand.marginalia.core.CommentQueue
import com.github.borgand.marginalia.core.CommentStore
import com.github.borgand.marginalia.core.DocRegistry
import com.github.borgand.marginalia.ui.CaptureSurface
import com.github.borgand.marginalia.ui.MarginaliaSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange

/**
 * Selection (or caret line) → comment capture → anchored comment (PRD W1, keyboard-first).
 * The capture surface (inline popup vs modal dialog) is chosen by [MarginaliaSettings].
 * Registering the file as co-edited is implicit — commenting IS the opt-in.
 */
class AddCommentAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible =
            e.project != null && editor != null &&
            FileDocumentManager.getInstance().getFile(editor.document) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return

        val selection = editor.selectionModel
        val (start, end) = if (selection.hasSelection()) {
            selection.selectionStart to selection.selectionEnd
        } else {
            val line = document.getLineNumber(editor.caretModel.offset)
            document.getLineStartOffset(line) to document.getLineEndOffset(line)
        }
        if (start == end) return

        val line = document.getLineNumber(start)
        val snippet = document.getText(TextRange(start, end))

        when (service<MarginaliaSettings>().captureSurface) {
            CaptureSurface.INLINE ->
                InlineCommentPopup(editor, file.name, line, snippet) { body ->
                    persist(project, document, start, end, body)
                }.show()

            CaptureSurface.DIALOG -> {
                val dialog = AddCommentDialog(project, file.name, line, snippet)
                if (dialog.showAndGet()) persist(project, document, start, end, dialog.body)
            }
        }
    }

    private fun persist(project: Project, document: Document, start: Int, end: Int, body: String) {
        if (body.isEmpty()) return
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        project.service<DocRegistry>().register(file)
        val queue = project.service<CommentQueue>()
        val comment = project.service<CommentStore>().addComment(document, start, end, body, queue.initialStatus())
        service<ActivityLog>().log(
            "comment ${comment.status.toString().lowercase()}: \"${comment.anchoredText.take(30)}\" — $body",
        )
    }
}
