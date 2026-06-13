package com.github.borgand.marginalia.hooks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Idempotently merges the Marginalia PreToolUse hook entry into the user's
 * `~/.claude/settings.json` content (PRD F4). Everything else is preserved verbatim.
 */
object ClaudeSettingsMerger {

    const val MATCHER = "Edit|Write|MultiEdit"

    private val json = Json { prettyPrint = true }

    fun merge(settingsContent: String, hookCommand: String): String {
        val root = settingsContent.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())

        val hooks = root["hooks"] as? JsonObject ?: JsonObject(emptyMap())
        val preToolUse = hooks["PreToolUse"] as? JsonArray ?: JsonArray(emptyList())

        val alreadyInstalled = preToolUse.any { entry ->
            val obj = entry as? JsonObject ?: return@any false
            (obj["hooks"] as? JsonArray)?.any { hook ->
                (hook as? JsonObject)?.get("command")?.jsonPrimitive?.content == hookCommand
            } == true
        }

        val newPreToolUse = if (alreadyInstalled) preToolUse else buildJsonArray {
            preToolUse.forEach { add(it) }
            add(
                buildJsonObject {
                    put("matcher", MATCHER)
                    put("hooks", buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "command")
                                put("command", hookCommand)
                            },
                        )
                    })
                },
            )
        }

        val newHooks = JsonObject(hooks + ("PreToolUse" to newPreToolUse))
        val newRoot = JsonObject(root + ("hooks" to newHooks))
        return json.encodeToString(JsonObject.serializer(), newRoot)
    }
}
