package com.github.borgand.marginalia.ui.render.fold

import com.github.borgand.marginalia.ui.render.MarkdownStructure
import com.github.borgand.marginalia.ui.render.RenderSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager

/**
 * Central controller for Tier-2 custom-fold rendering. Installs CustomFoldRegions for big
 * H1/H2 titles and Markdown tables, skipping any region whose line range contains the caret.
 */
@Service(Service.Level.PROJECT)
class CustomFoldController(private val project: Project) {

    fun refresh(editor: Editor) {
        val folding = editor.foldingModel as? FoldingModelEx ?: return
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        if (!file.name.endsWith(".md", ignoreCase = true)) return
        val doc = editor.document
        val caretLine = doc.getLineNumber(editor.caretModel.offset)
        val headings = MarkdownStructure.headings(file)
        val tables = MarkdownStructure.tables(file)
        val images = MarkdownStructure.images(file)

        folding.runBatchFoldingOperation {
            editor.foldingModel.allFoldRegions
                .filterIsInstance<CustomFoldRegion>()
                .filter { it.getUserData(TAG) == true }
                .forEach { folding.removeFoldRegion(it) }

            if (RenderSettings.getInstance().bigTitles) {
                for (h in headings) {
                    if (h.level > 2) continue
                    val startLine = doc.getLineNumber(h.fullRange.startOffset)
                    val endLine = doc.getLineNumber(h.fullRange.endOffset.coerceIn(0, (doc.textLength - 1).coerceAtLeast(0)))
                    if (caretLine in startLine..endLine) continue
                    val title = doc.getText(h.contentRange).trim()
                    val region = folding.addCustomLinesFolding(startLine, endLine, BigTitleFoldRenderer(title, h.level)) ?: continue
                    region.putUserData(TAG, true)
                }
            }

            if (RenderSettings.getInstance().renderTables) {
                for (t in tables) {
                    val startLine = doc.getLineNumber(t.range.startOffset)
                    val endLine = doc.getLineNumber(t.range.endOffset.coerceIn(0, (doc.textLength - 1).coerceAtLeast(0)))
                    if (caretLine in startLine..endLine) continue
                    if (t.rows.isEmpty()) continue
                    val region = folding.addCustomLinesFolding(startLine, endLine, TableFoldRenderer(t.rows)) ?: continue
                    region.putUserData(TAG, true)
                }
            }

            if (RenderSettings.getInstance().inlineImages) {
                for (img in images) {
                    val startLine = doc.getLineNumber(img.lineRange.startOffset)
                    val endLine = doc.getLineNumber(img.lineRange.endOffset.coerceIn(0, (doc.textLength - 1).coerceAtLeast(0)))
                    if (caretLine in startLine..endLine) continue
                    val region = folding.addCustomLinesFolding(startLine, endLine, ImageFoldRenderer(img.url)) ?: continue
                    region.putUserData(TAG, true)
                }
            }
        }
    }

    companion object {
        private val TAG = Key.create<Boolean>("marginalia.custom.fold")
        private val ATTACHED = Key.create<Boolean>("marginalia.bigtitle.listener")

        /** Refresh on caret move + document change, tied to [parentDisposable] (the TextEditor). */
        fun attachListeners(project: Project, editor: Editor, parentDisposable: Disposable) {
            if (editor.getUserData(ATTACHED) == true) return
            editor.putUserData(ATTACHED, true)
            val refresh = {
                ApplicationManager.getApplication().invokeLater({
                    if (!editor.isDisposed && !project.isDisposed) {
                        PsiDocumentManager.getInstance(project).performWhenAllCommitted {
                            if (!editor.isDisposed) project.service<CustomFoldController>().refresh(editor)
                        }
                    }
                }, { project.isDisposed })
            }
            editor.caretModel.addCaretListener(object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) { refresh() }
            }, parentDisposable)
            editor.document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) { refresh() }
            }, parentDisposable)
        }
    }
}
