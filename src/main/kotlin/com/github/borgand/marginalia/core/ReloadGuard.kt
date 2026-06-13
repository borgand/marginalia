package com.github.borgand.marginalia.core

import com.github.difflib.DiffUtils
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Extracts the user's unsaved changes (base → ours) as old_text/new_text hunks,
 * anchored with one neighbouring base line so insertions and deletions stay unique.
 */
object UserHunks {

    fun editsFor(base: String, ours: String): List<MergeEngine.Edit> {
        val baseLines = base.lines()
        val oursLines = ours.lines()
        return DiffUtils.diff(baseLines, oursLines).deltas.map { delta ->
            var srcFrom = delta.source.position
            var srcTo = delta.source.position + delta.source.size()
            var tgtFrom = delta.target.position
            var tgtTo = delta.target.position + delta.target.size()
            // one anchoring line: prefer the preceding, else the following
            if (srcFrom > 0) {
                srcFrom--; tgtFrom--
            } else if (srcTo < baseLines.size) {
                srcTo++; tgtTo++
            }
            MergeEngine.Edit(
                baseLines.subList(srcFrom, srcTo).joinToString("\n"),
                oursLines.subList(tgtFrom, tgtTo).joinToString("\n"),
            )
        }
    }
}

/**
 * Disk-write fallback (PRD F6): if an external write lands on a co-edited file
 * (hook bypassed/misconfigured), merge instead of letting the platform reload
 * clobber the user's unsaved buffer. Non-conflicting external + user changes both
 * survive; a true same-region conflict restores the user's buffer wholesale
 * (user wins, D13) and logs loudly.
 */
class ReloadGuard : FileDocumentManagerListener {

    sealed interface MergeOutcome {
        object NoOp : MergeOutcome
        data class Merged(val hunks: Int) : MergeOutcome
        data class UserRestored(val conflicts: List<MergeEngine.Conflict>) : MergeOutcome
    }

    companion object {
        private val stashes = ConcurrentHashMap<String, String>()

        private fun projectFor(path: String): Project? =
            ProjectManager.getInstance().openProjects.firstOrNull {
                !it.isDisposed && it.service<DocRegistry>().isCoEdited(path)
            }

        /**
         * Re-applies the user's hunks (diff [base] → [ours]) onto [document], which
         * already holds the externally-written content. Any conflict → restore [ours].
         */
        fun mergeUserChanges(project: Project, document: Document, base: String, ours: String): MergeOutcome {
            if (base == ours) return MergeOutcome.NoOp
            val edits = UserHunks.editsFor(base, ours)
            if (edits.isEmpty()) return MergeOutcome.NoOp
            val outcome = project.service<MergeEngine>().applyAgainstBase(document, base, edits)
            return if (outcome.conflicts.isEmpty()) {
                MergeOutcome.Merged(outcome.applied.size)
            } else {
                WriteCommandAction.runWriteCommandAction(project) { document.setText(ours) }
                MergeOutcome.UserRestored(outcome.conflicts)
            }
        }
    }

    override fun beforeFileContentReload(file: VirtualFile, document: Document) {
        if (projectFor(file.path) == null) return
        if (FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
            stashes[file.path] = document.text
        }
    }

    override fun fileContentReloaded(file: VirtualFile, document: Document) {
        val path = file.path
        val project = projectFor(path) ?: return
        val registry = project.service<DocRegistry>()
        val theirs = document.text
        val ours = stashes.remove(path)
        val base = registry.lastSynced(path)
        if (ours != null && base != null) {
            val log = service<ActivityLog>()
            when (val outcome = mergeUserChanges(project, document, base, ours)) {
                is MergeOutcome.Merged ->
                    log.log("EXTERNAL WRITE on $path — merged ${outcome.hunks} unsaved user hunk(s) (hook bypassed?)")
                is MergeOutcome.UserRestored ->
                    log.log(
                        "EXTERNAL WRITE on $path CONFLICTED with unsaved edits — user buffer restored, " +
                            "external change to ${outcome.conflicts.size} region(s) overridden",
                    )
                MergeOutcome.NoOp -> {}
            }
        }
        registry.setLastSynced(path, theirs)
    }

    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        val project = projectFor(file.path) ?: return
        project.service<DocRegistry>().setLastSynced(file.path, document.text)
    }
}
