package com.github.borgand.marginalia.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.util.Disposer
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plain-text activity feed for the tool window (PRD F7): tool calls, applied hunks,
 * conflicts, server status. Not a chat — a log.
 */
@Service(Service.Level.APP)
class ActivityLog {

    private val entries = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    companion object {
        private const val MAX_ENTRIES = 500
    }

    @Synchronized
    fun log(message: String) {
        val entry = "${LocalTime.now().format(timeFormat)}  $message"
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        listeners.forEach { it(entry) }
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    fun addListener(parent: Disposable, listener: (String) -> Unit) {
        listeners.add(listener)
        Disposer.register(parent) { listeners.remove(listener) }
    }
}
