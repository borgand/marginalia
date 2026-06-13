package com.github.borgand.marginalia.ui.render

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.JBColor
import java.awt.Graphics

/**
 * Custom-painted line decorations (blockquote bar+tint, horizontal rule) that text
 * attributes cannot express. Mirrors the Phase 1 CommentHighlighter pattern: own the
 * RangeHighlighters we add (tagged via user data), dispose+re-add on refresh.
 */
@Service(Service.Level.PROJECT)
class MarkdownLineDecorator(private val project: Project) {

    fun refresh(editor: Editor) {
        val markup = editor.markupModel
        markup.allHighlighters
            .filter { it.getUserData(TAG) == true }
            .forEach { it.dispose() }

        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        if (!file.name.endsWith(".md", ignoreCase = true)) return

        for (bq in MarkdownStructure.blockquotes(file)) {
            add(editor, bq.range.startOffset, bq.range.endOffset, BlockquoteRenderer())
        }
        for (hr in MarkdownStructure.horizontalRules(file)) {
            add(editor, hr.lineRange.startOffset, hr.lineRange.endOffset, RuleRenderer())
        }
    }

    private fun add(editor: Editor, start: Int, end: Int, renderer: CustomHighlighterRenderer) {
        val len = editor.document.textLength
        val s = start.coerceIn(0, len)
        val e = end.coerceIn(s, len)
        val hl: RangeHighlighter = editor.markupModel.addRangeHighlighter(
            s, e, HighlighterLayer.ADDITIONAL_SYNTAX, null, HighlighterTargetArea.EXACT_RANGE,
        )
        hl.customRenderer = renderer
        hl.putUserData(TAG, true)
    }

    private class BlockquoteRenderer : CustomHighlighterRenderer {
        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            val start = editor.offsetToXY(highlighter.startOffset)
            val end = editor.offsetToXY(highlighter.endOffset)
            val lineHeight = editor.lineHeight
            g.color = JBColor.GRAY
            val x = start.x + 2
            g.fillRect(x, start.y, 3, (end.y - start.y) + lineHeight)
        }
    }

    private class RuleRenderer : CustomHighlighterRenderer {
        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            val p = editor.offsetToXY(highlighter.startOffset)
            val width = editor.component.width
            val y = p.y + editor.lineHeight / 2
            g.color = JBColor.GRAY
            g.fillRect(p.x, y, width - p.x - 10, 1)
        }
    }

    companion object {
        private val TAG = Key.create<Boolean>("marginalia.line.decoration.tag")
        private val ATTACHED = Key.create<Boolean>("marginalia.line.decoration.listener")

        /** Debounced refresh on edits, disposed with the editor. Idempotent per editor. */
        fun attachDocumentListener(project: Project, editor: Editor) {
            if (editor.getUserData(ATTACHED) == true) return
            editor.putUserData(ATTACHED, true)
            editor.document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    ApplicationManager.getApplication().invokeLater({
                        if (!editor.isDisposed && !project.isDisposed) {
                            PsiDocumentManager.getInstance(project).performWhenAllCommitted {
                                if (!editor.isDisposed) project.service<MarkdownLineDecorator>().refresh(editor)
                            }
                        }
                    }, { project.isDisposed })
                }
            })
        }
    }
}
