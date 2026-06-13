package com.github.borgand.marginalia.mcp

import com.github.borgand.marginalia.core.CommentStatus
import com.github.borgand.marginalia.core.CommentStore
import com.github.borgand.marginalia.core.DocRegistry
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class McpToolsTest : BasePlatformTestCase() {

    private val registry get() = project.service<DocRegistry>()
    private val store get() = project.service<CommentStore>()

    override fun setUp() {
        super.setUp()
        System.setProperty("marginalia.home", createTempDir("marginalia").absolutePath)
        registry.clear()
        store.clear()
    }

    override fun tearDown() {
        try {
            System.clearProperty("marginalia.home")
        } finally {
            super.tearDown()
        }
    }

    private fun coEditedDoc(text: String, name: String = "doc.md"): String {
        val psiFile = myFixture.configureByText(name, text)
        registry.register(psiFile.virtualFile)
        return psiFile.virtualFile.path
    }

    private fun JsonObject.errorCode(): String? =
        (this["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.content

    fun testReadDocNotCoEdited() {
        myFixture.configureByText("doc.md", "text\n")
        val result = McpTools.readDoc("/not/registered.md")
        assertEquals("NOT_CO_EDITED", result.errorCode())
    }

    fun testReadDocReturnsContentVersionHeadings() {
        val path = coEditedDoc("# Top\n\nbody\n\n## Sub\n\nmore\n")
        val result = McpTools.readDoc(path)

        assertNull(result.errorCode())
        assertEquals(path, result["path"]!!.jsonPrimitive.content)
        assertEquals(1L, result["version"]!!.jsonPrimitive.long)
        assertTrue(result["content"]!!.jsonPrimitive.content.startsWith("# Top"))
        val headings = result["headings"]!!.jsonArray
        assertEquals(2, headings.size)
        val top = headings[0].jsonObject
        assertEquals(1, top["level"]!!.jsonPrimitive.int)
        assertEquals("Top", top["text"]!!.jsonPrimitive.content)
        assertEquals(0, top["offset"]!!.jsonPrimitive.int)
    }

    fun testListCoEditedDocs() {
        val path = coEditedDoc("x\n")
        val result = McpTools.listCoEditedDocs()
        val docs = result["docs"]!!.jsonArray
        assertEquals(1, docs.size)
        assertEquals(path, docs[0].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(1L, docs[0].jsonObject["version"]!!.jsonPrimitive.long)
    }

    fun testApplyEditHappyPath() {
        val path = coEditedDoc("alpha\nbeta\ngamma\n")
        McpTools.readDoc(path) // records base at version 1

        val result = McpTools.applyEdit(path, 1, listOf("beta" to "BETA"))

        assertNull(result.errorCode())
        assertEquals(1, result["applied"]!!.jsonArray.size)
        assertEquals(0, result["conflicts"]!!.jsonArray.size)
        assertEquals(2L, result["new_version"]!!.jsonPrimitive.long)
        assertTrue(myFixture.editor.document.text.contains("BETA"))
    }

    fun testApplyEditStaleBase() {
        val path = coEditedDoc("alpha\n")
        McpTools.readDoc(path)
        val result = McpTools.applyEdit(path, 42, listOf("alpha" to "x"))
        assertEquals("STALE_BASE", result.errorCode())
    }

    fun testApplyEditConflictReturnsCurrentText() {
        val path = coEditedDoc("alpha\nbeta\ngamma\n")
        McpTools.readDoc(path)
        val doc = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            doc.replaceString(doc.text.indexOf("beta"), doc.text.indexOf("beta") + 4, "mine")
        }

        val result = McpTools.applyEdit(path, 1, listOf("beta" to "agent"))

        assertNull(result.errorCode())
        assertEquals(0, result["applied"]!!.jsonArray.size)
        val conflict = result["conflicts"]!!.jsonArray[0].jsonObject
        assertEquals(0, conflict["edit_idx"]!!.jsonPrimitive.int)
        assertTrue(conflict["current_text"]!!.jsonPrimitive.content.contains("mine"))
    }

    fun testApplyEditChainedOnNewVersion() {
        val path = coEditedDoc("one\ntwo\n")
        McpTools.readDoc(path)
        val first = McpTools.applyEdit(path, 1, listOf("one" to "ONE"))
        val newVersion = first["new_version"]!!.jsonPrimitive.long

        // agent chains a second edit against the version apply_edit returned (D8)
        val second = McpTools.applyEdit(path, newVersion, listOf("two" to "TWO"))
        assertNull(second.errorCode())
        assertEquals(1, second["applied"]!!.jsonArray.size)
    }

    fun testPendingCommentsAndResolveFullLoop() {
        val path = coEditedDoc("# H\n\nimprove this paragraph\n")
        val doc = myFixture.editor.document
        val start = doc.text.indexOf("improve")
        val comment = store.addComment(doc, start, start + 7, "be specific", CommentStatus.QUEUED)

        val pending = McpTools.getPendingComments(null)
        val comments = pending["comments"]!!.jsonArray
        assertEquals(1, comments.size)
        val c = comments[0].jsonObject
        assertEquals(comment.id, c["id"]!!.jsonPrimitive.content)
        assertEquals(path, c["path"]!!.jsonPrimitive.content)
        assertEquals("improve", c["anchored_text"]!!.jsonPrimitive.content)
        assertEquals("be specific", c["body"]!!.jsonPrimitive.content)
        assertEquals("H", c["heading_path"]!!.jsonArray[0].jsonPrimitive.content)

        val resolved = McpTools.resolveComment(comment.id, "rephrased it")
        assertTrue(resolved["ok"]!!.jsonPrimitive.boolean)
        assertEquals(CommentStatus.ADDRESSED, store.byId(comment.id)!!.status)
        assertEquals("rephrased it", store.byId(comment.id)!!.resolutionNote)
    }

    fun testResolveUnknownCommentIsInvalidAnchor() {
        val result = McpTools.resolveComment("nope", null)
        assertEquals("INVALID_ANCHOR", result.errorCode())
    }

    fun testApplyEditOnNonCoEditedPath() {
        val result = McpTools.applyEdit("/not/registered.md", 1, listOf("a" to "b"))
        assertEquals("NOT_CO_EDITED", result.errorCode())
    }
}
