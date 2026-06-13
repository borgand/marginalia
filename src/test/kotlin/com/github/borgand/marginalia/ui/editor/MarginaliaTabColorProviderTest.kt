package com.github.borgand.marginalia.ui.editor

import com.github.borgand.marginalia.core.CommentStatus
import com.github.borgand.marginalia.core.CommentStore
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MarginaliaTabColorProviderTest : BasePlatformTestCase() {

    private val provider = MarginaliaTabColorProvider()
    private val store get() = project.service<CommentStore>()

    override fun setUp() {
        super.setUp()
        store.clear()
    }

    fun testNoColorWithoutComments() {
        val file = myFixture.configureByText("doc.md", "line one\nline two\n").virtualFile
        assertNull(provider.getEditorTabColor(project, file))
    }

    fun testColorWhenFileHasAnOpenComment() {
        val file = myFixture.configureByText("doc.md", "line one\nline two\n").virtualFile
        store.addComment(myFixture.editor.document, 0, 4, "note", CommentStatus.QUEUED)
        assertNotNull(provider.getEditorTabColor(project, file))
    }

    fun testNoColorWhenOnlyResolvedComments() {
        val file = myFixture.configureByText("doc.md", "line one\nline two\n").virtualFile
        val comment = store.addComment(myFixture.editor.document, 0, 4, "note", CommentStatus.QUEUED)
        store.setStatus(comment.id, CommentStatus.RESOLVED)
        assertNull(provider.getEditorTabColor(project, file))
    }
}
