package com.github.borgand.marginalia.core

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class DocRegistryTest : BasePlatformTestCase() {

    private val registry get() = project.service<DocRegistry>()
    private lateinit var marginaliaHome: Path

    override fun setUp() {
        super.setUp()
        marginaliaHome = Files.createTempDirectory("marginalia-test")
        System.setProperty("marginalia.home", marginaliaHome.toString())
        registry.clear()
    }

    override fun tearDown() {
        try {
            System.clearProperty("marginalia.home")
        } finally {
            super.tearDown()
        }
    }

    private fun activeDocsJson(): String =
        marginaliaHome.resolve(".marginalia/active-docs.json").readText()

    fun testRegisterMakesDocCoEditedAtVersion1() {
        val psiFile = myFixture.configureByText("doc.md", "hello\n")
        val file = psiFile.virtualFile

        assertTrue(registry.register(file))
        assertTrue(registry.isCoEdited(file.path))
        assertEquals(1L, registry.version(file.path))
        // registering twice is a no-op
        assertFalse(registry.register(file))
    }

    fun testVersionBumpsOnEveryBufferChange() {
        val psiFile = myFixture.configureByText("doc.md", "hello\n")
        val file = psiFile.virtualFile
        registry.register(file)

        val doc = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(0, "a") }
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(0, "b") }

        assertEquals(3L, registry.version(file.path))
    }

    fun testActiveDocsJsonContainsRegisteredPath() {
        val psiFile = myFixture.configureByText("doc.md", "hello\n")
        val file = psiFile.virtualFile
        registry.register(file)

        assertTrue(activeDocsJson().contains(file.path))
    }

    fun testUnregisterRemovesFromJsonAndRegistry() {
        val psiFile = myFixture.configureByText("doc.md", "hello\n")
        val file = psiFile.virtualFile
        registry.register(file)
        registry.unregister(file.path)

        assertFalse(registry.isCoEdited(file.path))
        assertNull(registry.version(file.path))
        assertFalse(activeDocsJson().contains(file.path))
    }

    fun testFileForReturnsRegisteredFile() {
        val psiFile = myFixture.configureByText("doc.md", "hello\n")
        val file = psiFile.virtualFile
        registry.register(file)
        assertEquals(file, registry.fileFor(file.path))
        assertNull(registry.fileFor("/nonexistent/path.md"))
    }
}
