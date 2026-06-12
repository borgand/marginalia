# Marginalia — IntelliJ Plugin

Marginalia is an IntelliJ Platform plugin that turns the IDE into a live co-editing
surface for AI coding agents (primarily Claude Code). The user comments on anchored
ranges of a document PR-review-style; the agent receives those comments via an MCP
server hosted by the plugin and applies edits back through a merge engine — while the
user keeps typing in the same buffer. The agent's own UX (Claude Code TUI in the IDE
terminal) is reused as-is; this plugin never builds a chat UI.

Read `main-prd.md` for full product requirements, architecture, and phase plan.
This file covers what you need to work effectively in this codebase.

## Build & test commands

- `./gradlew test` — run unit + light platform tests (headless, your primary feedback loop)
- `./gradlew verifyPlugin` — plugin structure / API compatibility checks (headless)
- `./gradlew buildPlugin` — produces installable zip in `build/distributions/`
- `./gradlew runIde` — launches sandbox IDE (GUI; for the human's manual testing, do not run unattended)

Always run `test` and `verifyPlugin` before considering a task done.

## Tech stack

- Kotlin only (no Java). Target JDK 21, IntelliJ Platform `IC` (Community), sinceBuild per `gradle.properties`.
- IntelliJ Platform Gradle Plugin 2.x (`org.jetbrains.intellij.platform`) — the platform is a Gradle dependency; there is no separate SDK.
- MCP server: official Kotlin SDK `io.modelcontextprotocol:kotlin-sdk`, Streamable HTTP transport on localhost (port configurable, default 4747).
- Diff: `io.github.java-diff-utils:java-diff-utils` for hunk extraction; IntelliJ `Document` API for application.
- Phase 2 frontend: Milkdown (ProseMirror) in JCEF; built with Vite into `src/main/resources/webview/`. Keep the web project in `webview/` at repo root with its own `package.json`.

## IntelliJ Platform rules (violating these causes runtime errors)

1. **All `Document` modifications go through `WriteCommandAction.runWriteCommandAction(project) { ... }`.** Never mutate a Document outside it.
2. **UI access only on EDT** — use `ApplicationManager.getApplication().invokeLater { }` from background threads.
3. **PSI/Document reads from background threads need `ReadAction.compute { }`** (or `runReadAction`).
4. The MCP server handles requests on its own threads — every handler that touches editor state must hop threads correctly per rules 1–3. This is the most common source of bugs in this project; be deliberate.
5. `RangeMarker`s auto-update through edits but can become invalid (`isValid == false`) when their range is deleted — always check before use, and define behavior for orphaned comments.
6. Services: use `@Service(Service.Level.PROJECT)` light services; obtain via `project.service<X>()`. Register extensions in `src/main/resources/META-INF/plugin.xml`.
7. Tests: prefer `BasePlatformTestCase` (light fixtures) — fast, headless, give you real Documents/Editors in-memory.

## Architecture map (keep this layering)

```
core/
  CommentStore        — comment model, RangeMarker anchoring, persistence per file
  CommentQueue        — pending dispatches, idle-flush policy
  MergeEngine         — base-version snapshots, diff → hunks, fuzzy re-anchor, Document application, conflict policy (user wins)
  DocRegistry         — which files are "co-edited"; writes ~/.marginalia/active-docs.json for the hook
mcp/
  McpServerService    — lifecycle (start on project open), HTTP transport
  tools/              — read_doc, apply_edit, get_pending_comments, resolve_comment, list_co_edited_docs
ui/
  MarginaliaToolWindow — queue view + activity log (NOT a chat)
  CommentGutter/Actions — add/edit/resolve comment actions, editor highlights
hooks/
  resources/marginalia-hook.sh — PreToolUse deny script installed into user config
pty/ (phase 1b)
  TerminalDispatcher  — writes comment prompts into the Claude Code PTY when idle
```

Tool contracts (names, schemas, error semantics) are specified in `main-prd.md` §6 — implement exactly those; they are the public API the agent depends on.

## Conventions

- Conventional commits (`feat:`, `fix:`, `refactor:`).
- Every MCP tool gets: happy-path test, stale-base-version test, and a concurrent-user-edit test using light fixtures.
- No new dependencies without noting why in the PR/commit body.
- Reference docs: IntelliJ Platform SDK docs at https://plugins.jetbrains.com/docs/intellij , MCP Kotlin SDK repo at https://github.com/modelcontextprotocol/kotlin-sdk .
- When platform APIs are ambiguous, read source from the IntelliJ Community repo rather than guessing — it's on GitHub (`JetBrains/intellij-community`), and the bundled GitHub PR-review plugin there is prior art for inline comment UI (phase 2 reference).

## Things explicitly out of scope (do not build)

- A chat window, message streaming UI, or anything that renders agent output. The agent's own TUI is the chat.
- ACP client/transport (possible phase 3; not now).
- Multi-user collaboration, cloud sync, marketplace publishing.
