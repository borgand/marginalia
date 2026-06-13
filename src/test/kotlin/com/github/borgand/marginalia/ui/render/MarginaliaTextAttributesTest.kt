package com.github.borgand.marginalia.ui.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MarginaliaTextAttributesTest : BasePlatformTestCase() {
    fun `test keys are distinct and externalised`() {
        val keys = listOf(
            MarginaliaTextAttributes.H1, MarginaliaTextAttributes.H2, MarginaliaTextAttributes.H3,
            MarginaliaTextAttributes.H4_6, MarginaliaTextAttributes.BLOCKQUOTE,
            MarginaliaTextAttributes.LIST_MARKER, MarginaliaTextAttributes.DIMMED_MARKER,
            MarginaliaTextAttributes.STRIKETHROUGH,
        )
        assertEquals(keys.size, keys.map { it.externalName }.toSet().size)
        assertTrue(keys.all { it.externalName.startsWith("MARGINALIA_") })
    }
}
