package com.github.borgand.marginalia.ui.render

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

class RenderState : BaseState() {
    var foldLinkUrls by property(true)
    var foldFrontmatter by property(true)
    var dimMarkers by property(true)
    var bigTitles by property(true)
    var renderTables by property(true)
    var inlineImages by property(false)
}

@State(name = "MarginaliaRender", storages = [Storage("marginalia.xml")])
class RenderSettings : SimplePersistentStateComponent<RenderState>(RenderState()) {

    var foldLinkUrls: Boolean
        get() = state.foldLinkUrls
        set(v) { state.foldLinkUrls = v }
    var foldFrontmatter: Boolean
        get() = state.foldFrontmatter
        set(v) { state.foldFrontmatter = v }
    var dimMarkers: Boolean
        get() = state.dimMarkers
        set(v) { state.dimMarkers = v }
    var bigTitles: Boolean
        get() = state.bigTitles
        set(v) { state.bigTitles = v }
    var renderTables: Boolean
        get() = state.renderTables
        set(v) { state.renderTables = v }
    var inlineImages: Boolean
        get() = state.inlineImages
        set(v) { state.inlineImages = v }

    companion object {
        fun getInstance(): RenderSettings =
            ApplicationManager.getApplication().getService(RenderSettings::class.java)
    }
}
