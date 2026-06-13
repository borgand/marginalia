package com.github.borgand.marginalia.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** Where a new comment is captured (redesign §03). */
enum class CaptureSurface { INLINE, DIALOG }

/**
 * Application-level Marginalia preferences. Currently just the comment-capture surface;
 * the MCP port lives in [com.intellij.ide.util.PropertiesComponent] (see McpServerService).
 */
@Service(Service.Level.APP)
@State(name = "MarginaliaSettings", storages = [Storage("marginalia-settings.xml")])
class MarginaliaSettings : PersistentStateComponent<MarginaliaSettings.State> {

    class State {
        var captureSurface: CaptureSurface = CaptureSurface.INLINE
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    var captureSurface: CaptureSurface
        get() = state.captureSurface
        set(value) { state.captureSurface = value }
}
