package com.github.borgand.marginalia.ui.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MarkdownStructureTest : BasePlatformTestCase() {

    private fun parse(text: String) =
        myFixture.configureByText("doc.md", text)

    fun `test heading level detected`() {
        val file = parse("# Title\n\n## Sub\n\nbody")
        val headings = MarkdownStructure.headings(file)
        assertEquals(listOf(1, 2), headings.map { it.level })
        val h1 = headings[0]
        assertEquals("#", file.text.substring(h1.markerRange.startOffset, h1.markerRange.endOffset).trim())
        assertEquals("Title", file.text.substring(h1.contentRange.startOffset, h1.contentRange.endOffset).trim())
    }

    fun `test emphasis kinds`() {
        val src = "a **bold** b *italic* c ~~strike~~ d"
        val file = parse(src)
        val spans = MarkdownStructure.emphasis(file)
        val byKind = spans.associateBy { it.kind }
        assertTrue("expected BOLD", EmphasisKind.BOLD in byKind)
        assertTrue("expected ITALIC", EmphasisKind.ITALIC in byKind)
        assertTrue("expected STRIKETHROUGH", EmphasisKind.STRIKETHROUGH in byKind)
        assertEquals("**bold**", file.text.substring(byKind[EmphasisKind.BOLD]!!.range.startOffset, byKind[EmphasisKind.BOLD]!!.range.endOffset))
        assertEquals("*italic*", file.text.substring(byKind[EmphasisKind.ITALIC]!!.range.startOffset, byKind[EmphasisKind.ITALIC]!!.range.endOffset))
        assertEquals("~~strike~~", file.text.substring(byKind[EmphasisKind.STRIKETHROUGH]!!.range.startOffset, byKind[EmphasisKind.STRIKETHROUGH]!!.range.endOffset))
    }

    fun `test bold italic detected`() {
        val file = parse("a ***both*** b")
        val kinds = MarkdownStructure.emphasis(file).map { it.kind }
        assertTrue("expected BOLD_ITALIC, got $kinds", EmphasisKind.BOLD_ITALIC in kinds)
        // Must not double-count: no separate BOLD or ITALIC for the same ***both*** span
        assertFalse("must not report separate BOLD for ***both***, got $kinds", EmphasisKind.BOLD in kinds)
        assertFalse("must not report separate ITALIC for ***both***, got $kinds", EmphasisKind.ITALIC in kinds)
    }

    fun `test inline link fold range is the destination part`() {
        val file = parse("see [docs](https://example.com/very/long) here")
        val link = MarkdownStructure.links(file).single()
        val folded = file.text.substring(link.foldRange.startOffset, link.foldRange.endOffset)
        assertEquals("](https://example.com/very/long)", folded)
        assertEquals("docs", file.text.substring(link.textRange.startOffset, link.textRange.endOffset))
    }

    fun `test mermaid fence detected, plain fence not`() {
        val file = parse("```mermaid\ngraph TD; A-->B;\n```\n\n```kotlin\nval x = 1\n```")
        val mermaids = MarkdownStructure.mermaidFences(file)
        assertEquals(1, mermaids.size)
        assertTrue(mermaids.single().code.contains("graph TD"))
    }

    fun `test horizontal rule, blockquote, list markers`() {
        val file = parse("> quote\n\n- one\n- two\n\n---\n")
        assertEquals(1, MarkdownStructure.blockquotes(file).size)
        assertEquals(2, MarkdownStructure.listMarkers(file).size)
        assertEquals(1, MarkdownStructure.horizontalRules(file).size)
    }

    fun `test task checkbox detected with checked state`() {
        val file = parse("- [ ] todo\n- [x] done\n")
        val tasks = MarkdownStructure.taskItems(file)
        assertEquals(listOf(false, true), tasks.map { it.checked })
    }
}
