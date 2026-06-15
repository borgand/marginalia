package com.github.borgand.marginalia.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Wires the PRD §6 tools into an SDK [Server]. A fresh instance is created per
 * MCP session; all state lives in the project services behind [McpTools].
 */
object McpServerBuilder {

    fun build(): Server {
        val server = Server(
            serverInfo = Implementation(name = "marginalia", version = "0.0.1"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ),
        )

        server.addTool(
            name = "list_co_edited_docs",
            description = "List files currently live co-edited in IntelliJ, with their buffer versions.",
        ) { _ ->
            McpTools.listCoEditedDocs().toResult()
        }

        server.addTool(
            name = "read_doc",
            description = "Read the LIVE IntelliJ buffer (never disk) of a co-edited file. " +
                "Returns content, version and markdown headings. The returned content becomes " +
                "your merge base: pass its version as base_version to apply_edit.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Absolute file path as given by list_co_edited_docs")
                    }
                },
                required = listOf("path"),
            ),
        ) { request ->
            val args = request.arguments
            val path = args.string("path") ?: return@addTool missing("path")
            McpTools.readDoc(path).toResult()
        }

        server.addTool(
            name = "apply_edit",
            description = "Apply edits to a live co-edited buffer through the merge engine. " +
                "old_text must come from the base_version content (read_doc). Partial success " +
                "is normal: non-conflicting edits apply, the rest return as conflicts with the " +
                "current text. The USER ALWAYS WINS conflicts — re-read and retry with fresh context.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string") }
                    putJsonObject("base_version") {
                        put("type", "integer")
                        put("description", "Version from read_doc (or new_version of a previous apply_edit)")
                    }
                    putJsonObject("edits") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("old_text") { put("type", "string") }
                                putJsonObject("new_text") { put("type", "string") }
                            }
                            put("required", kotlinx.serialization.json.buildJsonArray {
                                add(kotlinx.serialization.json.JsonPrimitive("old_text"))
                                add(kotlinx.serialization.json.JsonPrimitive("new_text"))
                            })
                        }
                    }
                },
                required = listOf("path", "base_version", "edits"),
            ),
        ) { request ->
            val args = request.arguments
            val path = args.string("path") ?: return@addTool missing("path")
            val baseVersion = args?.get("base_version")?.jsonPrimitive?.long
                ?: return@addTool missing("base_version")
            val edits = args["edits"]?.jsonArray?.map {
                val edit = it.jsonObject
                (edit["old_text"]?.jsonPrimitive?.content ?: "") to (edit["new_text"]?.jsonPrimitive?.content ?: "")
            } ?: return@addTool missing("edits")
            McpTools.applyEdit(path, baseVersion, edits).toResult()
        }

        server.addTool(
            name = "get_pending_comments",
            description = "Fetch the user's pending review comments (optionally for one file) and " +
                "mark them dispatched. For each comment: read_doc the file, make the requested " +
                "change via apply_edit, then call resolve_comment with a short note. Pass " +
                "wait_seconds (1-1800) to long-poll: the call holds open until a comment arrives " +
                "or the wait elapses (response has timed_out:true). Omit it (or 0) for a one-shot read.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Optional: only comments for this absolute path")
                    }
                    putJsonObject("wait_seconds") {
                        put("type", "integer")
                        put("description", "Optional long-poll hold in seconds (0-1800, default 0 = immediate)")
                    }
                },
            ),
        ) { request ->
            val path = request.arguments.string("path")
            val waitSeconds = request.arguments.int("wait_seconds") ?: 0
            McpTools.getPendingCommentsAwait(path, waitSeconds).toResult()
        }

        server.addTool(
            name = "resolve_comment",
            description = "Mark a review comment addressed after editing, with an optional short note " +
                "shown to the user.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("id") { put("type", "string") }
                    putJsonObject("note") { put("type", "string") }
                },
                required = listOf("id"),
            ),
        ) { request ->
            val args = request.arguments
            val id = args.string("id") ?: return@addTool missing("id")
            McpTools.resolveComment(id, args.string("note")).toResult()
        }

        return server
    }

    private fun JsonObject?.string(key: String): String? =
        this?.get(key)?.jsonPrimitive?.takeIf { it.isString }?.content

    private fun JsonObject?.int(key: String): Int? =
        this?.get(key)?.jsonPrimitive?.content?.toIntOrNull()

    private fun JsonObject.toResult() = CallToolResult(
        content = listOf(TextContent(toString())),
        structuredContent = this,
    )

    private fun missing(field: String) = CallToolResult(
        content = listOf(TextContent("""{"error":{"code":"INVALID_ANCHOR","message":"missing required argument: $field"}}""")),
        isError = true,
    )
}
