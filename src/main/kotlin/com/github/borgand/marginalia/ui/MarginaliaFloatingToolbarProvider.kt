package com.github.borgand.marginalia.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.toolbar.floating.AbstractFloatingToolbarProvider
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent
import com.intellij.openapi.fileEditor.FileDocumentManager

/**
 * Shows a small floating "Add Marginalia Comment" button inside the editor whenever
 * the user has a non-empty text selection — the idiomatic platform mechanism
 * (`com.intellij.editorFloatingToolbarProvider`). Reuses the same AddCommentAction as
 * the keyboard shortcut and the right-click menu; this is purely an extra trigger.
 */
class MarginaliaFloatingToolbarProvider :
    AbstractFloatingToolbarProvider("Marginalia.FloatingCommentToolbar") {

    override val autoHideable: Boolean = true

    /**
     * The real gate. The platform only creates the toolbar component (and its mouse-motion
     * "show on hover" listener) for editors where this returns true; [register] runs too
     * late to suppress it. We restrict to the main editor tabs so the button never floats
     * over embedded editors like the commit-message field, diff panes, consoles or previews.
     * Those are backed by a (light) file too, so a file-presence check alone isn't enough —
     * `EditorKind` is what distinguishes them (the commit-message editor is `UNTYPED`).
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun isApplicable(dataContext: DataContext): Boolean {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return false
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return false
        // commenting needs a real path to anchor to
        return FileDocumentManager.getInstance().getFile(editor.document) != null
    }

    override fun register(
        dataContext: DataContext,
        component: FloatingToolbarComponent,
        parentDisposable: Disposable,
    ) {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return

        if (hasSelection(editor)) component.scheduleShow()

        editor.selectionModel.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                if (hasSelection(editor)) component.scheduleShow()
                else component.scheduleHide()
            }
        }, parentDisposable)
    }

    /**
     * Reads the selection model under a read action. The platform invokes [register]
     * (and dispatches selection events) on the EDT without an implicit read lock, and
     * `SelectionModel.hasSelection()` asserts read access — so wrap it explicitly.
     */
    private fun hasSelection(editor: Editor): Boolean =
        ReadAction.compute<Boolean, RuntimeException> { editor.selectionModel.hasSelection() }
}
