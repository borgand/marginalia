package com.github.borgand.marginalia.ui.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RenderSettingsTest : BasePlatformTestCase() {
    fun `test defaults`() {
        val s = RenderSettings.getInstance()
        assertTrue(s.foldLinkUrls)
        assertTrue(s.foldFrontmatter)
        assertTrue(s.dimMarkers)
        assertTrue(s.bigTitles)
        assertTrue(s.renderTables)
        assertFalse(s.inlineImages)
    }

    fun `test state round-trips`() {
        val s = RenderSettings.getInstance()
        s.inlineImages = true
        assertTrue(RenderSettings.getInstance().state.inlineImages)
        s.inlineImages = false
    }
}
