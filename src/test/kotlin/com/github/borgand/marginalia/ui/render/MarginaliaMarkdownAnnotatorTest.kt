package com.github.borgand.marginalia.ui.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MarginaliaMarkdownAnnotatorTest : BasePlatformTestCase() {

    /**
     * Returns all MARGINALIA_* attribute keys mapped from the text they cover.
     * Each text substring may map to multiple keys; the value is the first MARGINALIA key found.
     * We use a multi-map (list of pairs) and then search with [first].
     */
    private fun keysOver(text: String): List<Pair<String, String>> {
        myFixture.configureByText("doc.md", text)
        val infos = myFixture.doHighlighting()
        val docText = myFixture.file.text
        return infos.mapNotNull { info ->
            val key = info.forcedTextAttributesKey ?: info.type?.attributesKey ?: return@mapNotNull null
            if (!key.externalName.startsWith("MARGINALIA_")) return@mapNotNull null
            val substr = docText.substring(info.startOffset, info.endOffset)
            substr to key.externalName
        }
    }

    fun `test h1 and h2 get heading keys`() {
        val keys = keysOver("# One\n\n## Two\n")
        assertEquals("MARGINALIA_H1", keys.first { it.first.contains("One") }.second)
        assertEquals("MARGINALIA_H2", keys.first { it.first.contains("Two") }.second)
    }

    fun `test strikethrough key applied`() {
        val keys = keysOver("~~gone~~\n")
        assertTrue(keys.any { it.second == "MARGINALIA_STRIKETHROUGH" })
    }

    fun `test list marker key applied`() {
        val keys = keysOver("- item\n")
        assertTrue(keys.any { it.second == "MARGINALIA_LIST_MARKER" })
    }
}
