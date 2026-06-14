package com.github.borgand.marginalia.mcp

import com.github.borgand.marginalia.core.ActivityLog
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * One MCP server per IDE instance (PRD §8, D3). Streamable HTTP on
 * localhost:<port>/mcp; port from PropertiesComponent key [PORT_KEY] (default 4747).
 *
 * Starting the server must NEVER break the plugin: every failure is caught, classified
 * and surfaced (tool window status + notification), and the IDE keeps working so the
 * comment features and the Settings page (to change the port / retry) stay available.
 */
@Service(Service.Level.APP)
class McpServerService : Disposable {

    private val log = logger<McpServerService>()

    enum class State { STOPPED, RUNNING, FAILED }

    @Volatile
    var state: State = State.STOPPED
        private set

    /** Human-readable detail for the tool window / notifications. */
    @Volatile
    var status: String = "stopped"
        private set

    /** When an MCP client (the agent) last opened a connection to us; null = never. */
    @Volatile
    var lastClientConnectedAt: Long? = null
        private set

    /** When the agent last invoked a tool, and which one; null = never. */
    @Volatile
    var lastToolCallAt: Long? = null
        private set

    @Volatile
    var lastToolName: String? = null
        private set

    private var engine: EmbeddedServer<*, *>? = null

    /** Called per incoming MCP connection (the server factory block runs per connection). */
    fun recordConnection() {
        lastClientConnectedAt = System.currentTimeMillis()
    }

    /** Called by every tool handler so connectivity reflects real agent activity. */
    fun recordToolCall(name: String) {
        lastToolCallAt = System.currentTimeMillis()
        lastToolName = name
    }

    /** True if something is actually accepting connections on the configured port. */
    fun probeListening(): Boolean = try {
        java.net.Socket().use {
            it.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port()), 300)
            true
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        const val PORT_KEY = "marginalia.mcp.port"
        const val DEFAULT_PORT = 4747
    }

    fun port(): Int = PropertiesComponent.getInstance().getInt(PORT_KEY, DEFAULT_PORT)

    fun setPort(port: Int) {
        PropertiesComponent.getInstance().setValue(PORT_KEY, port, DEFAULT_PORT)
    }

    fun isRunning(): Boolean = state == State.RUNNING

    /** Start the server if not already running. Idempotent; never throws. */
    @Synchronized
    fun ensureStarted() {
        if (engine != null) return
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val port = port()
        try {
            // CIO binds asynchronously with start(wait=false); probe the port up front
            // so a real clash is reported as such instead of vanishing into a coroutine.
            try {
                ServerSocket(port, 1, InetAddress.getLoopbackAddress()).close()
            } catch (e: BindException) {
                fail(
                    "port $port is already in use",
                    "Another process is listening on 127.0.0.1:$port. " +
                        "Free it (lsof -nP -iTCP:$port) or change the port in " +
                        "Settings > Tools > Marginalia, then restart the server.",
                    e,
                )
                return
            }

            engine = embeddedServer(CIO, host = "127.0.0.1", port = port) {
                // SDK 0.10.0 doesn't register a JSON converter; without this the POST
                // endpoint answers every request with an empty-body HTTP 406.
                install(ContentNegotiation) { json(McpJson) }
                mcpStreamableHttp {
                    recordConnection()
                    McpServerBuilder.build()
                }
                redirectBareRootToMcp()
            }.start(wait = false)

            state = State.RUNNING
            status = "running on http://127.0.0.1:$port/mcp"
            safeLog("MCP server $status")
            log.info("Marginalia MCP server started on port $port")
        } catch (t: Throwable) {
            engine = null
            // Not a port problem — most likely a binary-compatibility issue (the MCP/Ktor
            // stack vs the IDE's bundled Kotlin). Report it accurately, do not blame the port.
            val kind = if (t is LinkageError) "internal compatibility error" else "internal error"
            fail(
                "$kind — ${t.message}",
                "The MCP server could not start ($kind). Comment features still work. " +
                    "Details: ${t.message}",
                t,
            )
        }
    }

    /** Stop then start, picking up a changed port. Never throws. */
    @Synchronized
    fun restart() {
        stopEngine()
        state = State.STOPPED
        status = "stopped"
        ensureStarted()
    }

    private fun fail(shortStatus: String, detail: String, cause: Throwable) {
        state = State.FAILED
        status = "FAILED: $shortStatus"
        safeLog("MCP server $status")
        log.warn("Marginalia MCP server failed to start: $shortStatus", cause)
        notify("Marginalia MCP server failed to start", detail, NotificationType.WARNING)
    }

    private fun safeLog(message: String) {
        try {
            service<ActivityLog>().log(message)
        } catch (_: Throwable) {
            // never let logging failure escape startup
        }
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        try {
            val group = NotificationGroupManager.getInstance().getNotificationGroup("Marginalia") ?: return
            group.createNotification(title, content, type).notify(null)
        } catch (_: Throwable) {
            // notifications are best-effort; status is already recorded
        }
    }

    private fun stopEngine() {
        try {
            engine?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        } catch (_: Throwable) {
            // ignore — we're tearing down
        }
        engine = null
    }

    override fun dispose() {
        stopEngine()
        state = State.STOPPED
        status = "stopped"
    }
}

/**
 * Tolerate MCP clients registered with the bare base URL (e.g. `http://localhost:4747`)
 * instead of the documented `.../mcp`. Without this, a request to `/` hits Ktor's default
 * empty-body 404; Claude Code then attempts OAuth discovery against the server and fails
 * parsing the empty 404 body ("Invalid OAuth error response… Unexpected EOF"), so the
 * connection looks broken / auth-required. A 308 preserves the POST method and JSON-RPC
 * body, so a conformant client transparently retries against [mcpPath].
 */
internal fun Application.redirectBareRootToMcp(mcpPath: String = "/mcp") {
    suspend fun RoutingContext.toMcp() {
        call.response.headers.append(HttpHeaders.Location, mcpPath)
        call.respond(HttpStatusCode.PermanentRedirect)
    }
    routing {
        get("/") { toMcp() }
        post("/") { toMcp() }
        delete("/") { toMcp() }
    }
}
