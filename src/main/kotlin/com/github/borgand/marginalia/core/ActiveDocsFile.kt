package com.github.borgand.marginalia.core

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes `~/.marginalia/active-docs.json` — the list of live co-edited files the
 * PreToolUse hook checks before denying the agent's native Edit/Write (PRD F4).
 * Aggregated across all open projects; `marginalia.home` system property overrides
 * the home directory in tests.
 */
object ActiveDocsFile {

    private val log = logger<ActiveDocsFile>()

    fun path(): Path {
        val home = System.getProperty("marginalia.home") ?: System.getProperty("user.home")
        return Path.of(home, ".marginalia", "active-docs.json")
    }

    @Synchronized
    fun rewrite() {
        val docs = ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .flatMap { it.service<DocRegistry>().paths() }
            .distinct()
            .sorted()
        val json = buildJsonObject {
            put("docs", buildJsonArray { docs.forEach { add(JsonPrimitive(it)) } })
        }
        try {
            val target = path()
            Files.createDirectories(target.parent)
            Files.writeString(target, json.toString())
        } catch (e: Exception) {
            log.warn("Failed to write active-docs.json", e)
        }
    }
}
