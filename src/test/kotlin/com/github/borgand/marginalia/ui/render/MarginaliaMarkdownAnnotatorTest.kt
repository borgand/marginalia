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
        assertTrue(keys.any { it.first.contains("One") && it.second == "MARGINALIA_H1" })
        assertTrue(keys.any { it.first.contains("Two") && it.second == "MARGINALIA_H2" })
    }

    fun `test h1 and h2 also get emphasis style keys`() {
        val keys = keysOver("# One\n\n## Two\n")
        assertTrue("expected H1 emphasis style, keys: $keys",
            keys.any { it.first.contains("One") && it.second == "MARGINALIA_H1_STYLE" })
        assertTrue("expected H2 emphasis style, keys: $keys",
            keys.any { it.first.contains("Two") && it.second == "MARGINALIA_H2_STYLE" })
    }

    fun `test strikethrough key applied`() {
        val keys = keysOver("~~gone~~\n")
        assertTrue(keys.any { it.second == "MARGINALIA_STRIKETHROUGH" })
    }

    fun `test bold key applied`() {
        val keys = keysOver("a **strong** b\n")
        assertTrue("keys were: $keys", keys.any { it.second == "MARGINALIA_BOLD" })
    }

    fun `test italic key applied`() {
        val keys = keysOver("a _slanted_ b\n")
        assertTrue("keys were: $keys", keys.any { it.second == "MARGINALIA_ITALIC" })
    }

    fun `test bold italic key applied`() {
        val keys = keysOver("a ***both*** b\n")
        assertTrue("keys were: $keys", keys.any { it.second == "MARGINALIA_BOLD_ITALIC" })
    }

    fun `test list marker key applied`() {
        val keys = keysOver("- item\n")
        assertTrue(keys.any { it.second == "MARGINALIA_LIST_MARKER" })
    }
}
