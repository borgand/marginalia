package com.github.borgand.marginalia.ui.toolwindow

import com.github.borgand.marginalia.mcp.McpServerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateTest {

    private val now = 1_000_000L

    @Test
    fun failedStateIsError() {
        val view = connectionView(McpServerService.State.FAILED, hasConnected = false, lastToolCallAt = null, now = now)
        assertEquals(ConnectionTone.ERROR, view.tone)
        assertEquals("Server error", view.chipLabel)
    }

    @Test
    fun stoppedStateIsStopped() {
        val view = connectionView(McpServerService.State.STOPPED, hasConnected = false, lastToolCallAt = null, now = now)
        assertEquals(ConnectionTone.STOPPED, view.tone)
    }

    @Test
    fun runningWithoutClientIsWaiting() {
        val view = connectionView(McpServerService.State.RUNNING, hasConnected = false, lastToolCallAt = null, now = now)
        assertEquals(ConnectionTone.WAITING, view.tone)
    }

    @Test
    fun runningWithClientIsConnectedAndShowsLastCall() {
        val view = connectionView(
            McpServerService.State.RUNNING,
            hasConnected = true,
            lastToolCallAt = now - 120_000, // 2m ago
            now = now,
        )
        assertEquals(ConnectionTone.CONNECTED, view.tone)
        assertEquals("Connected", view.chipLabel)
        assertTrue("footer should mention the last call: ${view.footerLine}", view.footerLine.contains("2m ago"))
    }

    @Test
    fun connectedWithoutToolCallOmitsLastCall() {
        val view = connectionView(McpServerService.State.RUNNING, hasConnected = true, lastToolCallAt = null, now = now)
        assertEquals(ConnectionTone.CONNECTED, view.tone)
        assertEquals("Agent connected", view.footerLine)
    }
}
