package com.github.borgand.marginalia.hooks

import junit.framework.TestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ClaudeSettingsMergerTest : TestCase() {

    private val hookCommand = "/Users/me/.marginalia/marginalia-hook.sh"

    private fun parse(s: String) = Json.parseToJsonElement(s).jsonObject

    fun testMergeIntoEmptySettings() {
        val result = parse(ClaudeSettingsMerger.merge("{}", hookCommand))
        val preToolUse = result["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray
        assertEquals(1, preToolUse.size)
        val entry = preToolUse[0].jsonObject
        assertEquals("Edit|Write|MultiEdit", entry["matcher"]!!.jsonPrimitive.content)
        val hook = entry["hooks"]!!.jsonArray[0].jsonObject
        assertEquals("command", hook["type"]!!.jsonPrimitive.content)
        assertEquals(hookCommand, hook["command"]!!.jsonPrimitive.content)
    }

    fun testMergeIsIdempotent() {
        val once = ClaudeSettingsMerger.merge("{}", hookCommand)
        val twice = ClaudeSettingsMerger.merge(once, hookCommand)
        val preToolUse = parse(twice)["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray
        assertEquals(1, preToolUse.size)
    }

    fun testMergePreservesExistingSettingsAndHooks() {
        val existing = """
            {
              "model": "opus",
              "hooks": {
                "PreToolUse": [
                  {"matcher": "Bash", "hooks": [{"type": "command", "command": "/other.sh"}]}
                ],
                "PostToolUse": [
                  {"matcher": "Edit", "hooks": [{"type": "command", "command": "/post.sh"}]}
                ]
              }
            }
        """.trimIndent()

        val result = parse(ClaudeSettingsMerger.merge(existing, hookCommand))

        assertEquals("opus", result["model"]!!.jsonPrimitive.content)
        val preToolUse = result["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray
        assertEquals(2, preToolUse.size)
        assertEquals("Bash", preToolUse[0].jsonObject["matcher"]!!.jsonPrimitive.content)
        assertNotNull(result["hooks"]!!.jsonObject["PostToolUse"])
    }

    fun testMergeFromBlankInput() {
        val result = parse(ClaudeSettingsMerger.merge("", hookCommand))
        assertNotNull(result["hooks"])
    }
}
