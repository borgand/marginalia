# Autonomous decisions log

Decisions I made without you (per your instruction: "research options and take your best
guess, keep a file for such cases and run them by me later"). Review and veto at will —
each entry notes the cost of reversing.

## D1 — MCP Kotlin SDK 0.10.0 + Ktor 3.2.3 (pinned to Kotlin 2.2.x), Gradle Kotlin 2.2.20
**Revised after a runtime failure.** Originally used SDK 0.13.0 + Ktor 3.4.3 (both built
with Kotlin 2.3.21). That crashed at MCP-server start on IntelliJ 2025.2 with
`NoSuchMethodError: kotlin.time.Duration$Companion.fromRawValue-…$kotlin_stdlib` — the IDE
serves its OWN bundled Kotlin stdlib (2.2.0 in 2025.2; the `fromRawValue` internal only
exists from 2.3.x), and the plugin's bundled stdlib is ignored. Verified: 2025.2 ships
Kotlin 2.2.0, 2026.1 ships 2.3.20.

Fix: pin the last SDK/Ktor releases built with Kotlin 2.2.x — **MCP SDK 0.10.0 + Ktor
3.2.3** (both Kotlin 2.2.21, binary-compatible with the platform's 2.2.0). Their public
API is identical to 0.13.0 for everything we use, so no code changed. Also stopped
bundling kotlin-stdlib/reflect (platform provides them). Gradle Kotlin plugin stays 2.2.20.

**Decision for you:** this keeps the floor at IntelliJ 2025.2. The alternative was raising
the minimum IDE to 2026.1 and keeping SDK 0.13.0. I chose to keep 2025.2 working since
that's your current IDE. *Reversal cost: low (bump SDK + sinceBuild if you drop < 2026.1).*
**Unverified at runtime** — I can't launch the GUI headless overnight; confirm the server
reaches `running` in the tool window on your 2025.2 in the morning.

## D2 — Ktor CIO engine (not Netty) for the embedded MCP server
Pure-Kotlin, smaller dependency tree, no native transports needed for localhost. The SDK
README samples use both; CIO keeps the plugin zip leaner.
*Reversal cost: trivial (one dependency + one import).*

## D3 — MCP server is an application-level service (one per IDE instance)
PRD §8 says "one server per IDE instance". A project-level server would clash on the port
when two projects are open. Tools take absolute paths, so the server routes each call to
whichever open project has that path registered in its DocRegistry. Started lazily from a
project ProjectActivity; skipped entirely in unit-test mode.
*Reversal cost: medium.*

## D4 — Port setting in Settings > Tools > Marginalia (`marginalia.mcp.port`, default 4747)
Now a real Configurable (added after the startup-failure report): change the port + restart
the server from the UI. Also a Tools menu "Restart MCP Server" action and a tool-window
"Restart server" button. Server startup is fully non-fatal: any failure is caught,
classified (port-in-use vs internal/compatibility error — no longer mislabels everything as
a port clash), and surfaced without breaking the rest of the plugin.
*Reversal cost: low.*

## D5 — Removed the message bundle (MyBundle) instead of renaming it
Personal-tool MVP, English-only strings hardcoded. Less indirection.
*Reversal cost: low.*

## D15 — "Auto-dispatch" relabeled "Auto-queue"; connectivity test added; buildSearchableOptions disabled
The MCP model is pull-based — the server cannot push a prompt to the agent. So
"auto-dispatch" never pushed; it only set new comments to QUEUED (vs DRAFT). Relabeled to
**Auto-queue** with a tooltip stating comments are delivered when the agent calls
`get_pending_comments` (run `/marginalia`). The "Test agent connectivity" button reports
the real signal we have: server listening? any MCP client connected (+ last tool call)?
Confirmed via the build's headless IDE log that the SDK-0.10.0/Ktor-3.2.3 server now
starts cleanly on 2025.2 (`MCP server started on port 4747`), validating D1 at runtime.
Disabled `buildSearchableOptions` — its headless indexer trips a platform slow-op
assertion on plugin unload; the index is non-essential.
**Open item for you:** real push (PTY injection, F8/D6) is still not built — that's the
only way "auto" can actually deliver without you running `/marginalia`.

## D16 — Install Ktor ContentNegotiation ourselves (fixes empty-body HTTP 406)
After D1's downgrade to SDK 0.10.0, Claude Code failed to connect: `HTTP 406 at
http://localhost:4747/mcp`. Root cause: SDK 0.10.0's `mcpStreamableHttp` only does
`install(SSE)` — it never installs Ktor `ContentNegotiation` (0.13.0 added an auto-install
helper that 0.10.0 lacks). Without a JSON converter, every `call.respond(jsonObject)` on
the POST endpoint falls back to Ktor's "no acceptable converter" → 406 with an empty body
(the GET/SSE stream works because it writes strings directly). Fix: we
`install(ContentNegotiation) { json(McpJson) }` in our embeddedServer block, and add
`ktor-server-content-negotiation` + `ktor-serialization-kotlinx-json` (3.2.3). Verified
headlessly by McpServerContentNegotiationTest (boots the real CIO stack, POSTs initialize,
asserts 200 not 406). *Reversal cost: n/a (required for the transport to work).*

## D6 — Dispatch without PTY for now (M3's PTY injection deferred)
The "agent pulls" model needs no idle detection: *auto* mode marks new comments `QUEUED`
immediately; *manual* mode keeps them `DRAFT` until "Submit review". The agent fetches via
`get_pending_comments` (slash command `/marginalia` shipped). PTY auto-injection (F8) is
deferred — it needs interactive testing against a live TUI which I can't do headless overnight.
*Reversal cost: none (additive feature).*

## D7 — Fuzzy re-anchoring = exact match → whitespace-tolerant → conflict
PRD F5 mentions diff-match-patch-style fuzzy as the third tier. v1 implements exact and
whitespace-tolerant matching plus position disambiguation (nearest occurrence to the
diff-mapped base position). True fuzzy (edit-distance window search) returns CONFLICT for
now — conservative, user-wins-compatible, and the agent recovers cheaply via read_doc.
*Reversal cost: low (isolated function in MergeEngine).*

## D8 — apply_edit records the post-edit content as a new base snapshot
PRD says only read_doc records a base, but apply_edit returns `new_version`; an agent will
plausibly chain a second apply_edit against it. Recording the post-apply content as a valid
base makes that work instead of forcing a STALE_BASE round-trip.
*Reversal cost: trivial.*

## D9 — Hook script requires `jq`
Robust JSON parsing in bash without jq is awful. The hook exits 0 (allows the edit) if jq
is missing — fail-open, but F6 (VFS fallback) is the safety net, and the README documents
the jq requirement. Alternative considered: python3 fallback (slower startup on every
tool call).
*Reversal cost: low.*

## D10 — Comment persistence in `.idea/marginalia.xml` (project-level @State)
Standard PersistentStateComponent storing offsets + snippets + heading paths. Markers are
re-created on file open; if the snippet no longer matches, the comment is re-anchored by
snippet search, else flagged orphaned (shown in tool window, never silently dropped).
*Reversal cost: medium (data migration if format changes).*

## D11 — MCP tools tested at the service layer, not over HTTP
Tool handlers are thin wrappers over `McpToolService` methods; tests call those methods on
light fixtures. A live HTTP round-trip test would need the Ktor server inside the test JVM
(slow, flaky port binding). End-to-end is verified manually from a live Claude Code
session (your morning test).
*Reversal cost: none.*

## D12 — Working inline on branch `feat/phase1-core-sidecar`, no worktree, no subagents
Overnight autonomous run: a worktree complicates Gradle caches; per-task subagents would
re-derive context each time. Frequent conventional commits on the branch instead; you can
review commit-by-commit.
*Reversal cost: none.*

## D14 — kotlinx-coroutines excluded from library deps, compileOnly stub for compilation
Confirmed empirically: the stock kotlinx-coroutines 1.11.0 pulled by the MCP SDK shadows
IntelliJ's patched fork and crashes the service container (`NoSuchMethodError:
runBlockingWithParallelismCompensation`). Excluded per-dependency (a global
`configurations.all` exclude crashes the Kotlin compiler itself); compilation uses a
`compileOnly` stock coroutines 1.10.2 that never ships in the plugin zip.
*Reversal cost: n/a (required by platform rules).*

## D13 — F6 fallback scope: protect-and-merge on reload
Full transparent disk-merge is deep platform surgery. Implemented: track last-synced disk
content per co-edited file; when an external change hits a file with unsaved buffer edits,
3-way merge disk hunks into the buffer (user wins on overlap) instead of letting the
platform reload clobber it. If merge machinery fails, the buffer is kept and the event is
logged loudly in the tool window.
*Reversal cost: medium.*
