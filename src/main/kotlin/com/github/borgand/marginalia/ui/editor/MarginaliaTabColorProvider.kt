package com.github.borgand.marginalia.ui.editor

import com.github.borgand.marginalia.core.CommentStatus
import com.github.borgand.marginalia.core.CommentStore
import com.github.borgand.marginalia.ui.theme.MarginaliaColors
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorUtil
import java.awt.Color

/**
 * Tab affordance for back-referencing (redesign §04): a barely-there `status.pending` wash
 * on the tab of any file that has at least one open (non-resolved, non-orphaned) comment.
 * Theme-safe — derived from the token, never a raw hex.
 */
class MarginaliaTabColorProvider : EditorTabColorProvider {

    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? {
        val hasOpenComment = project.service<CommentStore>().comments(file.path).any {
            it.status != CommentStatus.RESOLVED && it.status != CommentStatus.ADDRESSED && !it.orphaned
        }
        return if (hasOpenComment) ColorUtil.withAlpha(MarginaliaColors.statusPending, 0.10) else null
    }
}
