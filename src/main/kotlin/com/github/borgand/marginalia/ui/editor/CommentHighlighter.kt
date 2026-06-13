package com.github.borgand.marginalia.ui.editor

import com.github.borgand.marginalia.core.CommentStatus
import com.github.borgand.marginalia.core.CommentStore
import com.github.borgand.marginalia.core.MarginaliaComment
import com.github.borgand.marginalia.ui.theme.MarginaliaColors
import com.github.borgand.marginalia.ui.theme.MarginaliaIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import javax.swing.Icon

/**
 * In-editor back-references (PRD F1, redesign §04): for each non-resolved comment, a faint
 * `status.pending @ 12%` line tint, a `status.pending` error-stripe mark, and a gutter pen
 * whose click focuses the Marginalia tool window. Refreshed on every store change.
 */
@Service(Service.Level.PROJECT)
class CommentHighlighter(private val project: Project) : com.intellij.openapi.Disposable {

    private val highlighters = mutableMapOf<Document, MutableList<RangeHighlighter>>()

    fun install() {
        project.service<CommentStore>().addChangeListener(this) {
            ApplicationManager.getApplication().invokeLater({ refreshAll() }, { project.isDisposed })
        }
        refreshAll()
    }

    fun refreshAll() {
        if (project.isDisposed) return
        val store = project.service<CommentStore>()
        val fdm = FileDocumentManager.getInstance()
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project !== project) continue
            val document = editor.document
            val path = fdm.getFile(document)?.path ?: continue

            highlighters.remove(document)?.forEach { it.dispose() }
            val added = mutableListOf<RangeHighlighter>()
            for (comment in store.comments(path)) {
                if (comment.status == CommentStatus.RESOLVED || comment.status == CommentStatus.ADDRESSED) continue
                val marker = store.markerFor(comment.id) ?: continue
                val attributes = TextAttributes().apply {
                    backgroundColor = MarginaliaColors.queuedTint
                    errorStripeColor = MarginaliaColors.statusPending
                }
                val hl = editor.markupModel.addRangeHighlighter(
                    marker.startOffset,
                    marker.endOffset,
                    HighlighterLayer.SELECTION - 1,
                    attributes,
                    HighlighterTargetArea.EXACT_RANGE,
                )
                hl.errorStripeTooltip = comment.body
                hl.gutterIconRenderer = CommentGutterRenderer(project, comment)
                added += hl
            }
            if (added.isNotEmpty()) highlighters[document] = added
        }
    }

    override fun dispose() {
        highlighters.values.flatten().forEach { it.dispose() }
        highlighters.clear()
    }

    /** Pen icon in the gutter; clicking it focuses the Marginalia sidecar. */
    private class CommentGutterRenderer(
        private val project: Project,
        private val comment: MarginaliaComment,
    ) : GutterIconRenderer() {

        override fun getIcon(): Icon = MarginaliaIcons.Pen
        override fun getTooltipText(): String = comment.body
        override fun isNavigateAction(): Boolean = true

        override fun getClickAction(): AnAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                ToolWindowManager.getInstance(project).getToolWindow("Marginalia")?.activate(null)
            }
        }

        override fun equals(other: Any?): Boolean =
            other is CommentGutterRenderer && other.comment.id == comment.id

        override fun hashCode(): Int = comment.id.hashCode()
    }
}

/** Re-anchors persisted comments and refreshes highlights when a file opens. */
class MarginaliaFileOpenListener : FileEditorManagerListener {

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val project = source.project
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        project.service<CommentStore>().ensureAnchored(document, file.path)
        project.service<CommentHighlighter>().refreshAll()
    }
}
