# Marginalia Phase 1 — Core Sidecar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Working Phase 1 sidecar: anchored comments + queue, MCP server (Streamable HTTP, :4747) with the five PRD §6 tools, merge engine with user-wins conflicts, edit-routing hook, tool window — daily-driver for raw MD + code files.

**Architecture:** Project-level light services (`CommentStore`, `CommentQueue`, `DocRegistry`, `MergeEngine`) hold all state; an app-level `McpServerService` hosts one Ktor CIO server per IDE instance and routes tool calls to projects by absolute path. MCP handlers are thin JSON wrappers over `McpTools`, which obeys the threading rules (ReadAction / WriteCommandAction / EDT hops). The hook script + active-docs.json redirect agent edits into `apply_edit`.

**Tech stack:** Kotlin 2.2.x, IntelliJ Platform 2025.2 (IC), `io.modelcontextprotocol:kotlin-sdk-server:0.13.0`, `io.ktor:ktor-server-cio:3.4.3`, `io.github.java-diff-utils:java-diff-utils:4.16`, JUnit4 `BasePlatformTestCase`.

**Known unknown (resolve at Task 3):** exact `addTool`/`CallToolRequest`/`ToolSchema` signatures in SDK 0.13.0 — verify against the SDK sources when wiring; the JSON contracts in this plan are fixed, the SDK call syntax may shift slightly.

**Decisions made autonomously are logged in `docs/decisions.md` (D1–D13).**

---

### Task 1: Template cleanup & identity

**Files:**
- Modify: `settings.gradle.kts` (rootProject.name = "marginalia")
- Modify: `src/main/resources/META-INF/plugin.xml` (name "Marginalia", real description, drop resource-bundle, drop sample toolWindow/activity registrations for now)
- Delete: `src/main/kotlin/com/github/borgand/marginalia/{MyBundle.kt,services/MyProjectService.kt,startup/MyProjectActivity.kt,toolWindow/MyToolWindowFactory.kt}`
- Delete: `src/main/resources/messages/MyBundle.properties`, `src/test/kotlin/.../MyPluginTest.kt`, `src/test/testData/`
- Modify: `README.md` (rewrite for Marginalia: what it is, install, register MCP, install hook, usage), `CHANGELOG.md` (Unreleased: initial Phase 1)

**Steps:**
- [ ] Create branch `feat/phase1-core-sidecar`
- [ ] Delete sample code + tests + bundle; fix plugin.xml (keep `<depends>com.intellij.modules.platform</depends>`); description ≥ 40 chars describing the real plugin
- [ ] Rewrite README (template ToDo list removed), CHANGELOG entry
- [ ] Run `./gradlew test` → BUILD SUCCESSFUL (no tests yet is fine)
- [ ] Commit `chore: strip template boilerplate, set plugin identity`

### Task 2: Dependencies

**Files:**
- Modify: `settings.gradle.kts` — Kotlin plugin `2.1.20` → `2.2.20`
- Modify: `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.13.0")
    implementation("io.ktor:ktor-server-cio:3.4.3")
    implementation("io.github.java-diff-utils:java-diff-utils:4.16")
    testImplementation("junit:junit:4.13.2")
    intellijPlatform { intellijIdea("2025.2.6.2"); testFramework(TestFrameworkType.Platform) }
}
```

- [ ] `./gradlew test` compiles (platform excludes bundled kotlinx-coroutines transitively; if verifyPlugin later flags bundled coroutines, add explicit `exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")` etc. on the SDK dependency)
- [ ] Commit `build: add MCP SDK, ktor-cio, java-diff-utils; bump Kotlin to 2.2.20`

### Task 3: Core model + CommentStore (F1)

**Files:**
- Create: `core/Comments.kt` — `CommentStatus { DRAFT, QUEUED, DISPATCHED, ADDRESSED, RESOLVED }`, bean `MarginaliaComment` (var fields, no-arg ctor: id, filePath, body, status, anchoredText, headingPath, startOffset, endOffset, createdAt, resolutionNote, orphaned)
- Create: `core/MarkdownHeadings.kt` — `headings(text): List<Heading(level,text,offset)>` via `^(#{1,6})\s+(.*)` per line; `pathAt(text, offset): List<String>` (stack by level, only for `.md`)
- Create: `core/CommentStore.kt` — `@Service(PROJECT)` + `PersistentStateComponent<State>` with `@State(name="MarginaliaComments", storages=[Storage("marginalia.xml")])`. Holds `State.comments: MutableList<MarginaliaComment>` + transient `markers: Map<id, RangeMarker>` + change listeners. API: `addComment(document, start, end, body, status)`, `comments(path?)`, `byId`, `setStatus(id, status, note?)`, `remove(id)`, `markerFor(id)`, `ensureAnchored(document, path)` (recreate markers: stored offsets if snippet matches, else snippet search nearest to stored offset, else `orphaned=true` — never drop), `getState` syncs offsets from valid markers
- Test: `core/CommentStoreTest.kt` (BasePlatformTestCase): add→marker tracks user insert above (offsets shift); persistence round-trip via `getState`/`loadState` + `ensureAnchored` re-creates marker; snippet moved → re-anchors by search; snippet deleted → orphaned, not dropped; heading path for nested MD headings

- [ ] Tests first, watch fail, implement, pass, commit `feat(core): comment model, RangeMarker anchoring, persistence`

### Task 4: DocRegistry + active-docs.json (F4 registry side)

**Files:**
- Create: `core/DocRegistry.kt` — `@Service(PROJECT)`, `register(file)` (attaches `DocumentListener` bumping per-doc `AtomicLong` version from 1; rewrites active-docs), `unregister`, `isCoEdited`, `version(path)`, `paths()`, `fileFor(path)`; rewrite on project dispose
- Create: `core/ActiveDocsFile.kt` — writes `{"docs":[...]}` to `${System.getProperty("marginalia.home") ?: user.home}/.marginalia/active-docs.json`, aggregated over all open projects' registries (test sets `marginalia.home` to a temp dir)
- Test: `core/DocRegistryTest.kt`: register → isCoEdited + version 1; type → version bumps; JSON file contains path; unregister → removed from JSON

- [ ] TDD loop, commit `feat(core): co-edited doc registry, versioning, active-docs.json`

### Task 5: CommentQueue (F2)

**Files:**
- Create: `core/CommentQueue.kt` — `@Service(PROJECT)`; `autoDispatch: Boolean = true`; `initialStatus()` = QUEUED if auto else DRAFT; `submitReview(): Int` promotes DRAFT→QUEUED; `pending(path?): List<PendingComment>` — ReadAction over QUEUED comments, payload = id, path, heading_path, anchored_text (live marker text), context_before/after (±2 lines), body, created_at; marks DISPATCHED
- Test: `core/CommentQueueTest.kt`: auto mode → comment immediately QUEUED and returned by `pending` (then DISPATCHED, second call empty); manual mode → DRAFT invisible until `submitReview`; context lines correct at file start/end edges

- [ ] TDD loop, commit `feat(core): comment queue with auto/manual dispatch policy`

### Task 6: MergeEngine (F5)

**Files:**
- Create: `core/MergeEngine.kt` — `@Service(PROJECT)`:
  - `recordBase(path, version, content)` (keep last 8 per path), `findBase`
  - `applyEdits(document, path, baseVersion, edits: List<Edit(oldText,newText)>): Result` where `Result = StaleBase | Outcome(applied: List<Int>, conflicts: List<Conflict(index, reason, currentText)>)`
  - Core algorithm in `applyAgainstBase(document, baseContent, edits)` (reused by F6), all inside one `invokeAndWait { WriteCommandAction }`:
    1. locate `old_text` in base (`indexOf`); missing → conflict `old_text not found in base_version content`
    2. locate in live text: exact occurrences → nearest to `mapBaseOffset(base, current, basePos)` (line-diff delta mapping via java-diff-utils); none → whitespace-tolerant regex (`Regex.escape` chunks joined `\s+`); none → conflict `not present in live buffer (user edit wins)` with `currentText` = base line-range mapped to current lines
    3. overlap among planned replacements → later edit conflicts
    4. apply descending by offset via `document.replaceString`
- Test: `core/MergeEngineTest.kt`: happy replace; STALE_BASE on unknown version; user typed lines above → edit lands shifted (the concurrent-edit test); user rewrote target → conflict w/ current text, other edit still applied (partial success); duplicate occurrences → nearest-to-expected chosen; whitespace-changed target → still applied; overlapping edits → second conflicts

- [ ] TDD loop, commit `feat(core): merge engine with base snapshots and user-wins conflicts`

### Task 7: MCP tools layer (F3 logic)

**Files:**
- Create: `mcp/McpTools.kt` — object; routes path→(project, registry) via `ProjectManager.openProjects`; returns `JsonObject` per PRD §6 exactly; errors `{"error":{"code","message"}}` with `NOT_CO_EDITED | STALE_BASE | CONFLICT | INVALID_ANCHOR`:
  - `listCoEditedDocs()`, `readDoc(path)` (ReadAction; records base; headings array), `applyEdit(path, baseVersion, edits)` (returns applied/conflicts/new_version; records post-apply base — D8), `getPendingComments(path?)` (aggregates projects when path omitted), `resolveComment(id, note?)` (ADDRESSED + note)
- Test: `mcp/McpToolsTest.kt`: read_doc on non-registered → NOT_CO_EDITED; read_doc returns content+version+headings; apply_edit happy → new_version bumped, buffer changed; stale → STALE_BASE error json; full loop: comment → get_pending_comments → resolve_comment → status ADDRESSED

- [ ] TDD loop, commit `feat(mcp): tool implementations per PRD contracts`

### Task 8: MCP HTTP server (F3 transport)

**Files:**
- Create: `mcp/McpServerService.kt` — `@Service(APP)`, `Disposable`; port = `PropertiesComponent marginalia.mcp.port` default 4747; pre-check port free (fail loudly into status + ActivityLog + notification); `embeddedServer(CIO, host="127.0.0.1", port) { mcpStreamableHttp { buildMcpServer() } }.start(wait=false)`; skip in unit-test mode; stop in `dispose`
- Create: `mcp/McpServerBuilder.kt` — builds SDK `Server`, `addTool` × 5 with JSON schemas, handlers parse args → `McpTools` → `CallToolResult(TextContent(json))`
- Create: `startup/MarginaliaStartupActivity.kt` (ProjectActivity → `ensureStarted()`); register in plugin.xml
- Verification: `./gradlew test` + `verifyPlugin`; HTTP round-trip is manual (D11)

- [ ] Implement, commit `feat(mcp): streamable HTTP server on localhost:4747`

### Task 9: UI — tool window, actions, highlights (F7 + F1 visuals)

**Files:**
- Create: `core/ActivityLog.kt` — app service, ring buffer + listeners; MCP/merge/queue events log here
- Create: `ui/MarginaliaToolWindowFactory.kt` — toolbar (Auto-dispatch checkbox, Submit review, server status), comments JBTable (status/file/anchor/body, context menu: resolve, requeue, delete), log pane; refresh on store/log listeners
- Create: `ui/AddCommentAction.kt` — selection (or caret line) → multiline input dialog → `DocRegistry.register` + `CommentStore.addComment` (status from queue mode); shortcut `ctrl alt M` (`meta alt M` mac), EditorPopupMenu
- Create: `ui/CommentHighlighter.kt` — store listener; refresh RangeHighlighters (soft bg + gutter icon) in open editors; brief flash highlight on agent-applied ranges
- Create: `ui/ToggleCoEditAction.kt` — register/unregister current file
- Modify: `plugin.xml` — toolWindow id="Marginalia" (right), actions, listeners (FileEditorManagerListener → `ensureAnchored` on open)
- Test: light smoke test for AddComment data flow (action logic invoked directly)

- [ ] Implement, commit `feat(ui): tool window, add-comment action, highlights`

### Task 10: Hook + slash command (F4 + F8 fallback)

**Files:**
- Create: `src/main/resources/hooks/marginalia-hook.sh` — PreToolUse: stdin JSON → jq `.tool_input.file_path`; if listed in `~/.marginalia/active-docs.json` → stderr redirect message, exit 2; jq missing → exit 0 (fail-open, D9)
- Create: `src/main/resources/claude/marginalia-command.md` — `/marginalia` slash command: pull `get_pending_comments`, per comment read_doc → apply_edit → resolve_comment
- Create: `ui/InstallClaudeIntegrationAction.kt` — confirmation dialog, then: write hook to `~/.marginalia/`, chmod +x; merge `{"hooks":{"PreToolUse":[{"matcher":"Edit|Write|MultiEdit","hooks":[{"type":"command","command":"~/.marginalia/marginalia-hook.sh"}]}]}}` into `~/.claude/settings.json` (kotlinx JSON, idempotent); write `~/.claude/commands/marginalia.md`; notification with `claude mcp add` one-liner
- Test: settings-merge unit test (pure JSON function) incl. idempotency and preserving existing hooks

- [ ] TDD on the merge function, implement action, commit `feat(hooks): edit-routing hook, installer, /marginalia command`

### Task 11: F6 — VFS external-write fallback

**Files:**
- Create: `core/ReloadGuard.kt` — `FileDocumentManagerListener` (app listener in plugin.xml): track `lastSynced` disk content per co-edited path (set on register/save/reload); `beforeFileContentReload` stash buffer if unsaved changes; `fileContentReloaded` → 3-way: diff lastSynced→stashed-ours, re-apply ours' hunks onto reloaded doc via `MergeEngine.applyAgainstBase` (user hunks win by construction), log outcome loudly
- Test: simulate with documents directly: base/ours/theirs strings → merged buffer keeps user edits + external non-conflicting edits; conflict → user version kept + logged

- [ ] TDD loop, commit `feat(core): external-write fallback merges instead of clobbering`

### Task 12: Final verification & handoff

- [ ] `./gradlew test` → all green (paste summary)
- [ ] `./gradlew verifyPlugin` → no errors (warnings triaged)
- [ ] `./gradlew buildPlugin` → zip exists in `build/distributions/`
- [ ] README final: quick-start (install zip → runIde, `claude mcp add --transport http marginalia http://localhost:4747/mcp`, Install Claude Integration action, usage workflows W1–W3)
- [ ] `docs/decisions.md` complete; write morning-handoff summary (what works, what to manually verify in runIde, M1 acceptance steps)
- [ ] Commit `docs: README, changelog, handoff notes`

## Self-review notes

- PRD §6 contracts: all five tools in Task 7; error codes covered (CONFLICT used inside apply_edit conflict entries' semantics; INVALID_ANCHOR for old_text-not-in-base).
- F1→Task 3/9, F2→Task 5, F3→Tasks 7/8, F4→Tasks 4/10, F5→Task 6, F6→Task 11, F7→Task 9, F8→slash-command half in Task 10 (PTY deferred, D6).
- Phase 1 acceptance (concurrent typing + 2 new comments while agent mid-turn) is covered by MergeEngineTest concurrent tests + CommentQueue tests headlessly; full interactive pass is the user's morning runIde test.
