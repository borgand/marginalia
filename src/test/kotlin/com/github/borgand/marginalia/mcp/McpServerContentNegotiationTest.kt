package com.github.borgand.marginalia.mcp

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import junit.framework.TestCase
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI

/**
 * Regression test for the empty-body HTTP 406 on the MCP POST endpoint.
 *
 * SDK 0.10.0 does not install Ktor ContentNegotiation, so every JSON response on the POST
 * route fell back to Ktor's "no acceptable converter" → 406. We install ContentNegotiation
 * with McpJson in [McpServerService]; this test exercises the same server stack directly
 * (embeddedServer + ContentNegotiation + mcpStreamableHttp) and asserts a real `initialize`
 * POST is accepted.
 */
class McpServerContentNegotiationTest : TestCase() {

    private fun bareServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = "test", version = "1.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ),
        )
        server.addTool(name = "ping", description = "ping", inputSchema = ToolSchema()) {
            CallToolResult(content = listOf(TextContent("pong")))
        }
        return server
    }

    fun testInitializePostIsAcceptedNotEmpty406() {
        val port = ServerSocket(0).use { it.localPort }
        val engine = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            install(ContentNegotiation) { json(McpJson) }
            mcpStreamableHttp { bareServer() }
        }.start(wait = false)
        try {
            // give CIO a moment to bind
            waitUntilListening(port)

            val body = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-06-18","capabilities":{},
                  "clientInfo":{"name":"probe","version":"1.0"}}}
            """.trimIndent()

            val conn = (URI("http://127.0.0.1:$port/mcp").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Accept", "application/json, text/event-stream")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val payload = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.readBytes()?.decodeToString().orEmpty()

            assertFalse(
                "POST initialize must not return the empty-body 406 (ContentNegotiation missing). " +
                    "code=$code body=$payload",
                code == 406,
            )
            assertEquals("expected 200 OK for initialize, body=$payload", 200, code)
            assertTrue("response should carry a JSON-RPC result: $payload", payload.contains("\"result\""))
        } finally {
            engine.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        }
    }

    fun testBareRootRedirectsToMcpPreservingMethod() {
        val port = ServerSocket(0).use { it.localPort }
        val engine = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            install(ContentNegotiation) { json(McpJson) }
            mcpStreamableHttp { bareServer() }
            redirectBareRootToMcp()
        }.start(wait = false)
        try {
            waitUntilListening(port)

            val conn = (URI("http://127.0.0.1:$port/").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json, text/event-stream")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                outputStream.use { it.write("""{"jsonrpc":"2.0","id":1,"method":"ping"}""".toByteArray()) }
            }

            // 308 (not 301/302) so the client re-POSTs the JSON-RPC body to /mcp instead of
            // downgrading to GET; otherwise a client on the bare base URL sees an empty 404.
            assertEquals("bare root POST must 308-redirect to /mcp", 308, conn.responseCode)
            assertEquals("/mcp", conn.getHeaderField("Location"))
        } finally {
            engine.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        }
    }

    private fun waitUntilListening(port: Int, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 100) }
                return
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
    }
}
