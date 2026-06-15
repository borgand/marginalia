# Cost-safe Continuous Marginalia Monitoring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the cost-dangerous `/loop 1m /marginalia` recommendation with a server-side long-poll on `get_pending_comments`, a self-terminating frugal `/marginalia` skill, and honest looping docs.

**Architecture:** Add an optional `wait_seconds` arg to the `get_pending_comments` MCP tool. When > 0, the already-`suspend` handler polls `CommentQueue.pending()` once per second until a comment appears or the deadline passes, then returns (with `timed_out`/`waited_seconds` markers). The `/marginalia` skill calls it with `wait_seconds: 1800`, processes any comments, and auto-stops after 3 consecutive empty returns (~90 min). The README drops `/loop 1m /marginalia` and documents the trade-offs.

**Tech Stack:** Kotlin, IntelliJ Platform Gradle Plugin 2.x, `io.modelcontextprotocol:kotlin-sdk-server`, `kotlinx.coroutines` (compileOnly — patched runtime supplied by the platform), `BasePlatformTestCase` light fixtures.

---

## File Structure

- `src/main/kotlin/com/github/borgand/marginalia/mcp/McpTools.kt` — add `timed_out`/`waited_seconds` to `getPendingComments` output; add `suspend fun getPendingCommentsAwait(...)` and an internal testable `suspend fun pollUntil(...)` loop.
- `src/main/kotlin/com/github/borgand/marginalia/mcp/McpServerBuilder.kt` — declare `wait_seconds` in the tool schema; parse it; route to `getPendingCommentsAwait`.
- `src/test/kotlin/com/github/borgand/marginalia/mcp/McpToolsTest.kt` — new long-poll tests.
- `build.gradle.kts` — `testCompileOnly` coroutines so test sources compile against `delay`/`runBlocking`.
- `src/main/resources/claude/marginalia-command.md` — rewrite the skill (long-poll, auto-stop, frugality).
- `README.md` — remove `/loop 1m /marginalia`; add a "Continuous monitoring" section.

---

## Task 1: Long-poll core in McpTools

**Files:**
- Modify: `src/main/kotlin/com/github/borgand/marginalia/mcp/McpTools.kt:152-177` (the `getPendingComments` function)
- Modify: `build.gradle.kts:48` (add a `testCompileOnly` line)
- Test: `src/test/kotlin/com/github/borgand/marginalia/mcp/McpToolsTest.kt`

- [ ] **Step 1: Allow test sources to compile against coroutines**

In `build.gradle.kts`, immediately after the existing line:

```kotlin
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
```

add:

```kotlin
    // Test sources call delay()/runBlocking directly; the platform supplies the
    // patched coroutines runtime, so this is compile-only like the main dependency.
    testCompileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
```

- [ ] **Step 2: Write the failing tests**

Add these imports to the top of `McpToolsTest.kt` (after the existing `import kotlinx.serialization.json.long`):

```kotlin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
```

Add these test methods inside `McpToolsTest` (before the final closing `}`):

```kotlin
    fun testGetPendingCommentsDefaultReportsNotTimedOut() {
        coEditedDoc("# H\n\nbody\n")
        val result = McpTools.getPendingComments(null)
        assertEquals(0, result["comments"]!!.jsonArray.size)
        assertFalse(result["timed_out"]!!.jsonPrimitive.boolean)
        assertEquals(0, result["waited_seconds"]!!.jsonPrimitive.int)
    }

    fun testAwaitReturnsImmediatelyWhenCommentAlreadyQueued() {
        val path = coEditedDoc("# H\n\nimprove this paragraph\n")
        val doc = myFixture.editor.document
        val start = doc.text.indexOf("improve")
        store.addComment(doc, start, start + 7, "be specific", CommentStatus.QUEUED)

        val result = runBlocking { McpTools.getPendingCommentsAwait(null, waitSeconds = 30) }

        assertEquals(1, result["comments"]!!.jsonArray.size)
        assertFalse(result["timed_out"]!!.jsonPrimitive.boolean)
        assertEquals(path, result["comments"]!!.jsonArray[0].jsonObject["path"]!!.jsonPrimitive.content)
    }

    fun testAwaitTimesOutWhenNoComments() {
        coEditedDoc("# H\n\nbody\n")
        val result = runBlocking {
            McpTools.getPendingCommentsAwait(null, waitSeconds = 1, pollMillis = 20)
        }
        assertEquals(0, result["comments"]!!.jsonArray.size)
        assertTrue(result["timed_out"]!!.jsonPrimitive.boolean)
        assertEquals(1, result["waited_seconds"]!!.jsonPrimitive.int)
    }

    fun testPollUntilReturnsAsSoonAsPredicatePopulates() {
        var calls = 0
        val populated = buildJsonObject {
            putJsonArray("comments") { add(JsonPrimitive("x")) }
        }
        val empty = buildJsonObject { putJsonArray("comments") {} }
        val result = runBlocking {
            McpTools.pollUntil(waitMillis = 5_000, pollMillis = 10) {
                calls++
                if (calls >= 3) populated else empty
            }
        }
        assertEquals(1, result["comments"]!!.jsonArray.size)
        assertFalse(result["timed_out"]!!.jsonPrimitive.boolean)
        assertEquals(3, calls)
    }
```

Note: `add(JsonPrimitive("x"))` needs `import kotlinx.serialization.json.add` — it is already imported in this test file via the existing imports? It is NOT; add `import kotlinx.serialization.json.add` to the import block in this step as well.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.github.borgand.marginalia.mcp.McpToolsTest"`
Expected: compile failure / FAIL — `getPendingCommentsAwait` and `pollUntil` are unresolved, and `timed_out`/`waited_seconds` keys are absent.

- [ ] **Step 4: Add the new fields and long-poll functions**

In `McpTools.kt`, add these imports to the existing import block:

```kotlin
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
```

Replace the current `getPendingComments` function (lines 152–177) with this — note the two new fields and the two new functions:

```kotlin
    /** Hard server ceiling for a single long-poll hold (seconds). */
    const val MAX_WAIT_SECONDS = 1800

    fun getPendingComments(path: String?): JsonObject {
        touch("get_pending_comments")
        val projects = if (path == null) {
            ProjectManager.getInstance().openProjects.filter { !it.isDisposed }.toList()
        } else {
            listOfNotNull(projectFor(path))
        }
        val pending = projects.flatMap { it.service<CommentQueue>().pending(path) }
        if (pending.isNotEmpty()) log.log("get_pending_comments → dispatched ${pending.size} comment(s)")
        return buildJsonObject {
            putJsonArray("comments") {
                for (p in pending) {
                    addJsonObject {
                        put("id", p.id)
                        put("path", p.path)
                        putJsonArray("heading_path") { p.headingPath.forEach { add(JsonPrimitive(it)) } }
                        put("anchored_text", p.anchoredText)
                        put("context_before", p.contextBefore)
                        put("context_after", p.contextAfter)
                        put("body", p.body)
                        put("created_at", p.createdAt)
                    }
                }
            }
            put("timed_out", false)
            put("waited_seconds", 0)
        }
    }

    /**
     * Long-poll wrapper over [getPendingComments]. With [waitSeconds] <= 0 this is the plain
     * one-shot read. Otherwise it re-reads every [pollMillis] until comments appear or the
     * deadline passes, then returns an empty result tagged `timed_out:true`. Runs on the MCP
     * server coroutine (background) — `delay` is cancellable, so a dropped client releases it.
     */
    suspend fun getPendingCommentsAwait(
        path: String?,
        waitSeconds: Int,
        pollMillis: Long = 1000,
    ): JsonObject {
        val clamped = waitSeconds.coerceIn(0, MAX_WAIT_SECONDS)
        if (clamped == 0) return getPendingComments(path)
        val result = pollUntil(clamped * 1000L, pollMillis) { getPendingComments(path) }
        // Stamp how long we actually held the request (best-effort, full wait on timeout).
        return buildJsonObject {
            result.forEach { (k, v) -> if (k != "waited_seconds") put(k, v) }
            put("waited_seconds", clamped)
        }
    }

    /**
     * Re-evaluate [read] every [pollMillis] until it returns a non-empty `comments` array or
     * [waitMillis] elapses. Pure loop (no IntelliJ deps) so it is unit-testable with a fake
     * [read]. The returned object is [read]'s last value, with `timed_out` set accordingly.
     */
    suspend fun pollUntil(
        waitMillis: Long,
        pollMillis: Long,
        read: () -> JsonObject,
    ): JsonObject {
        val deadline = System.currentTimeMillis() + waitMillis
        while (true) {
            val r = read()
            val hasComments = (r["comments"] as? JsonArray)?.isNotEmpty() == true
            if (hasComments) return withTimedOut(r, false)
            if (System.currentTimeMillis() >= deadline) return withTimedOut(r, true)
            delay(pollMillis)
        }
    }

    private fun withTimedOut(obj: JsonObject, timedOut: Boolean): JsonObject = buildJsonObject {
        obj.forEach { (k, v) -> if (k != "timed_out") put(k, v) }
        put("timed_out", timedOut)
    }
```

(`JsonArray` is covered by the `import kotlinx.serialization.json.JsonArray` added above.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.github.borgand.marginalia.mcp.McpToolsTest"`
Expected: PASS (all McpToolsTest tests, including the four new ones). The timeout test takes ~1s.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/main/kotlin/com/github/borgand/marginalia/mcp/McpTools.kt src/test/kotlin/com/github/borgand/marginalia/mcp/McpToolsTest.kt
git commit -m "feat: long-poll core for get_pending_comments"
```

---

## Task 2: Wire `wait_seconds` through the MCP tool schema

**Files:**
- Modify: `src/main/kotlin/com/github/borgand/marginalia/mcp/McpServerBuilder.kt:102-118` (the `get_pending_comments` registration)

- [ ] **Step 1: Add an int argument reader**

In `McpServerBuilder.kt`, just below the existing private helper:

```kotlin
    private fun JsonObject?.string(key: String): String? =
        this?.get(key)?.jsonPrimitive?.takeIf { it.isString }?.content
```

add:

```kotlin
    private fun JsonObject?.int(key: String): Int? =
        this?.get(key)?.jsonPrimitive?.content?.toIntOrNull()
```

- [ ] **Step 2: Declare `wait_seconds` and route to the long-poll**

Replace the `get_pending_comments` registration block (lines 102–118) with:

```kotlin
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
```

- [ ] **Step 3: Verify it compiles and the suite passes**

Run: `./gradlew test`
Expected: PASS (the handler is already a `suspend` lambda, so calling the suspend `getPendingCommentsAwait` is legal).

- [ ] **Step 4: Verify plugin structure**

Run: `./gradlew verifyPlugin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/borgand/marginalia/mcp/McpServerBuilder.kt
git commit -m "feat: expose wait_seconds long-poll arg on get_pending_comments"
```

---

## Task 3: Rewrite the `/marginalia` skill

**Files:**
- Modify: `src/main/resources/claude/marginalia-command.md` (full body replacement)

- [ ] **Step 1: Replace the skill body**

Overwrite `src/main/resources/claude/marginalia-command.md` with exactly:

```markdown
---
description: Pull and address pending Marginalia review comments from IntelliJ (self-terminating long-poll)
---

You are the continuous review loop. Keep yourself frugal — idle polls must cost almost nothing.

Maintain an `empty` counter, starting at 0.

1. Call `mcp__marginalia__get_pending_comments` with `wait_seconds: 1800`. This holds open up
   to 30 minutes and returns as soon as the user queues a comment (or when it times out with
   `timed_out: true`).

2. **If `comments` is empty** (including `timed_out: true`): reply with the single word `IDLE`
   and nothing else. Increment `empty`. If `empty` reaches 3, stop entirely and reply on one
   line: `No comments for ~90 min — stopping. Run /marginalia to resume.` Otherwise go to step 1.

3. **If there are comments:** reset `empty` to 0 and address each, in order:
   1. Call `mcp__marginalia__read_doc` for the comment's file to get the live buffer and its `version`.
   2. Locate the anchored text (use `heading_path`, `context_before`/`context_after`) and make the
      change via `mcp__marginalia__apply_edit`, passing that `version` as `base_version` and the
      exact `old_text` from the content you just read.
   3. If `apply_edit` returns conflicts, the user edited that region meanwhile — the user wins.
      Re-read the doc and retry once with fresh context; if it still conflicts, leave it and say so.
   4. Call `mcp__marginalia__resolve_comment` with the comment `id` and a one-line note.

   Then go back to step 1.

Do not edit co-edited files with the native Edit/Write tools. Keep all output minimal: no
preamble, no narration between polls, one-line resolve notes.
```

- [ ] **Step 2: Verify the resource still packages**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL; `build/distributions/` contains the updated zip.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/claude/marginalia-command.md
git commit -m "feat: self-terminating frugal /marginalia long-poll skill"
```

---

## Task 4: README — remove `/loop 1m /marginalia`, add Continuous monitoring section

**Files:**
- Modify: `README.md` (lines ~99, ~108-112, ~118, ~122-127, ~174-177)

- [ ] **Step 1: Fix the mermaid note**

Replace:

```
    Agent->>M: get_pending_comments<br/>(/loop 1m /marginalia)
```

with:

```
    Agent->>M: get_pending_comments<br/>(long-poll via /marginalia)
```

- [ ] **Step 2: Fix the "How it works" numbered step**

Replace the bullet that currently reads:

```
2. The agent pulls comments via the `get_pending_comments` MCP tool. The recommended way to
   run it is **`/loop 1m /marginalia`** — this re-runs the shipped `/marginalia` command once
   a minute, so the agent discovers and addresses new comments on its own without you asking
   each time. (One-shot `/marginalia`, or just telling it to check, also works.)
```

with:

```
2. The agent pulls comments via the `get_pending_comments` MCP tool. The recommended way to
   run it is a single **`/marginalia`** — it long-polls the queue (holding the call open until
   you comment), addresses what arrives, and stops on its own after ~90 minutes idle so a
   forgotten session can't run up cost. See [Continuous monitoring](#continuous-monitoring).
```

- [ ] **Step 3: Add the Continuous monitoring section**

Immediately after the "## How it works" section (before "## Workflows"), insert:

```markdown
---

## Continuous monitoring

You want the agent to pick up comments as you write, without babysitting it. Pick one:

- **Recommended — plain `/marginalia`.** It calls `get_pending_comments` with a 30-minute
  long-poll: the call holds open and returns the instant you queue a comment, so reaction is
  near-immediate while idle polling costs almost nothing. After 3 consecutive empty 30-minute
  holds (~90 minutes with no comments) it **stops by itself** and tells you to re-run
  `/marginalia`. This auto-stop is deliberate: a session you forget about cannot keep burning
  tokens overnight.

- **Alternative — `/loop Use MonitorTool to poll /marginalia command for new comments`.** A
  background monitor tool checks the queue with a smaller per-iteration footprint. Use this if
  you specifically want a `/loop`.

  > ⚠️ **A forgotten `/loop` never stops on its own and keeps accruing cost.** An idle overnight
  > `/loop` session once ran up roughly **$150** doing no useful work. Only use `/loop` if you
  > will remember to end it.

> The old advice to run **`/loop 1m /marginalia`** is discouraged — re-running the command every
> minute spends tokens continuously even when the queue is empty, with no natural stopping point.
> Prefer plain `/marginalia`.
```

- [ ] **Step 4: Fix the "As-I-go review" workflow**

Replace:

```
Start the agent with `/loop 1m /marginalia` so it keeps pulling the queue on its own. Read the
```

with:

```
Start the agent with `/marginalia` so it long-polls the queue on its own. Read the
```

- [ ] **Step 5: Fix the Setup closing paragraph**

Replace:

```
That's it. Open a file, add a comment, and start the agent with **`/loop 1m /marginalia`** —
it polls for your comments every minute and addresses them as they arrive, so you never have
to prompt it to check. (Requires the `/loop` command in your Claude Code; otherwise run
`/marginalia` whenever you want it to pick up the queue.)
```

with:

```
That's it. Open a file, add a comment, and start the agent with **`/marginalia`** — it
long-polls for your comments and addresses them as they arrive, then stops on its own after
~90 minutes idle. See [Continuous monitoring](#continuous-monitoring) for the trade-offs and a
lower-footprint `/loop` alternative.
```

- [ ] **Step 6: Verify no stale references remain**

Run: `grep -n "loop 1m" README.md`
Expected: only the single discouraging mention inside the Continuous monitoring section (Step 3); no recommendation lines remain.

- [ ] **Step 7: Commit**

```bash
git add README.md
git commit -m "docs: replace /loop 1m recommendation with long-poll + cost warning"
```

---

## Task 5: Final verification

- [ ] **Step 1: Full headless suite**

Run: `./gradlew test verifyPlugin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Confirm the long-poll contract end-to-end**

Run: `./gradlew test --tests "com.github.borgand.marginalia.mcp.McpToolsTest"`
Expected: PASS, including `testAwaitReturnsImmediatelyWhenCommentAlreadyQueued`, `testAwaitTimesOutWhenNoComments`, `testPollUntilReturnsAsSoonAsPredicatePopulates`, `testGetPendingCommentsDefaultReportsNotTimedOut`.

---

## Notes for the implementer

- **Client-timeout caveat (known/accepted):** Claude Code's MCP HTTP client may cut a 30-minute
  hold short. That is fine — a cut looks like an empty/timed-out return and the skill re-calls.
  If 30 min proves unreliable in practice, lower `wait_seconds` in the skill (Task 3) and/or
  `MAX_WAIT_SECONDS` in `McpTools` (Task 1); no redesign needed.
- **Why poll-not-signal:** the 1-second internal re-read avoids wiring change events out of
  `CommentStore`; it costs only server CPU and zero agent tokens while waiting. Revisit only if
  the cadence ever shows up as a problem.
- **Stop-after-3 lives in the skill, not the server** — the server stays stateless; a different
  agent that ignores the skill still just gets hold-and-return semantics with no runaway state.
```
