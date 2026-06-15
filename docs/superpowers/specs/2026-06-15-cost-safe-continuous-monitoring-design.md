# Cost-safe continuous Marginalia monitoring — design

**Date:** 2026-06-15
**Status:** Approved for planning

## Problem

The shipped recommendation for running Marginalia continuously is `/loop 1m /marginalia`.
This re-invokes the `/marginalia` skill once a minute. Left running overnight (~8h) with an
empty comment queue and no actual work to do, a session accumulated **~$150** in cost: every
minute spends tokens re-reading context and producing output even when there is nothing to do,
and there is no natural stopping point — a forgotten loop bleeds indefinitely.

We want continuous review that (a) reacts to comments quickly, (b) costs near-zero while idle,
and (c) **stops on its own** so a forgotten session cannot run up an open-ended bill.

## Goals

1. Replace `/loop 1m /marginalia` as the recommended mechanism.
2. Add a server-side long-poll so the agent can wait for comments instead of busy-polling.
3. Make the `/marginalia` skill self-terminating and frugal with context.
4. Document looping honestly, including a lower-token alternative and an explicit cost warning.

## Non-goals

- No chat UI, no streaming UI (out of scope per project charter).
- No push/webhook transport. We stay on the existing Streamable-HTTP MCP transport.
- No server-enforced "max calls" limit — the stop-after-N-empties policy lives in the skill
  (agent-side), keeping the server stateless and the tool contract simple.

## Solution overview

Three coordinated changes plus tests and docs:

1. **Server:** `get_pending_comments` gains an optional `wait_seconds` argument enabling a
   long-poll hold.
2. **Skill:** `/marginalia` uses the long-poll, loops on itself, and auto-stops after 3
   consecutive empty (timed-out) returns. It is frugal: a single token `IDLE` when idle.
3. **Docs:** README drops `/loop 1m /marginalia` everywhere and gains a "Continuous
   monitoring" section.

---

## 1. Long-poll on `get_pending_comments`

### Tool contract change

Add one optional property to the `get_pending_comments` input schema:

- `wait_seconds` — integer, optional, default `0`. Clamped server-side to `[0, 1800]`.

Behavior:

- `wait_seconds == 0` → **exactly today's behavior** (one synchronous read, return immediately).
  This preserves every existing caller (`/loop`, one-shot `/marginalia`) and existing tests.
- `wait_seconds > 0` → long-poll: the handler polls `CommentQueue.pending(path)` on a 1-second
  cadence; the first time it is non-empty, return those comments immediately; if `wait_seconds`
  elapses with nothing pending, return an empty result.

### Response shape

The response object gains two fields in all cases:

- `timed_out` — boolean. `false` when comments are returned (or `wait_seconds==0`), `true` when
  a long-poll elapsed with no comments.
- `waited_seconds` — integer, actual seconds waited before returning (0 for the immediate path).

The existing `comments` array is unchanged.

### Implementation

In `McpServerBuilder` the `get_pending_comments` handler is already a `suspend` lambda, and
`McpTools.getPendingComments` / `CommentQueue.pending()` are synchronous reads wrapped in
`ReadAction.compute`. The long-poll is an internal loop in the handler (or a new
`McpTools.getPendingCommentsAwait(path, waitSeconds)`):

```
val deadline = now + waitSeconds*1000
loop:
    val result = getPendingComments(path)   // existing read, ReadAction-safe
    if result.comments not empty: return result + {timed_out:false, waited_seconds:…}
    if now >= deadline: return empty + {timed_out:true, waited_seconds:waitSeconds}
    delay(1000)                              // cancellable
```

- No new signaling into `CommentStore`/`CommentQueue` — the 1-second internal poll is simple,
  robust, and cheap (server-side CPU only; no agent tokens consumed while it waits).
- `delay` is cancellable: if the MCP client disconnects or cancels, the coroutine unwinds and
  the hold is released.
- All reads stay on the existing `ReadAction.compute` path; no EDT hop is added.

### Client-timeout risk (accepted)

Claude Code's MCP Streamable-HTTP client may enforce its own request timeout shorter than
30 minutes and cut a long hold. We accept this risk and degrade gracefully:

- The server cap is a single constant (`MAX_WAIT_SECONDS = 1800`); easy to lower without
  redesign.
- A client-side cutoff is indistinguishable from a normal empty/timed-out return, and the skill
  simply re-issues the call. Worst case the effective hold is shorter than requested; nothing
  breaks.

---

## 2. `/marginalia` skill rewrite

File: `src/main/resources/claude/marginalia-command.md` (installed to
`~/.claude/commands/marginalia.md`). This becomes the **recommended continuous mechanism — no
`/loop` required**. A single `/marginalia` invocation self-sustains via long-poll and stops on
its own.

### Loop behavior

- Call `get_pending_comments` with `wait_seconds: 1800`.
- **Comments returned:** process each in order using the existing flow
  (`read_doc` → locate anchor → `apply_edit` with `base_version` → `resolve_comment`),
  conflict policy unchanged (user wins, retry once). Then resume the long-poll and **reset** the
  empty counter.
- **Empty / `timed_out:true`:** increment an empty counter and call again.
- **After 3 consecutive empty returns** (~90 min of coverage at 30 min each): **stop**, printing
  one line telling the user to re-run `/marginalia` to resume. This auto-stop is the core fix for
  the forgotten-overnight failure mode.

### Frugality rules (baked into the skill text)

- When a poll returns no comments, reply with the single token `IDLE` — nothing else.
- No preamble, no narration between polls.
- Keep `resolve_comment` notes to one line.

### Compaction

Explicitly **not** included. With long-poll the agent makes at most 3 idle calls before
stopping, so context barely grows; the `IDLE` frugality plus auto-stop make per-N-empties
self-compaction unnecessary (YAGNI).

---

## 3. README changes

### Remove `/loop 1m /marginalia`

Strip it from all current occurrences:

- Mermaid diagram note (~line 99) — change the `get_pending_comments` note to reference plain
  `/marginalia` long-poll.
- Numbered "How it works" step (~line 110–112).
- "Workflows → As-I-go review" (~line 123).
- Setup closing paragraph (~line 174–177).

Replace the primary recommendation with the self-terminating long-poll `/marginalia`.

### New "Continuous monitoring" section

Lay out the options honestly:

- **Recommended — plain `/marginalia`:** long-polls, reacts to comments within seconds, and
  self-terminates after ~90 min idle, so a forgotten session stops on its own.
- **Alternative (lower token, manual) — `/loop Use MonitorTool to poll /marginalia command for
  new comments`:** cheaper per iteration via a background monitor tool. **Bold warning:** a
  forgotten `/loop` keeps running and accrues cost — an idle overnight session previously ran up
  ~$150. Only use it if you will remember to stop it.
- Note that the old `/loop 1m /marginalia` is **removed/discouraged** for exactly this reason.

---

## 4. Testing

Add to `McpToolsTest` (light fixtures, headless):

1. **Backward compatible:** `wait_seconds` omitted / `0` → returns immediately, response matches
   today plus `timed_out:false`, `waited_seconds:0`.
2. **Already-queued fast path:** a comment is queued, `wait_seconds>0` → returns immediately
   (well under the wait) with the comment and `timed_out:false`.
3. **Queued mid-wait:** start a long-poll with a small `wait_seconds`, queue a comment shortly
   after, assert it is returned before the deadline with `timed_out:false`. (Use a short wait,
   e.g. 3–5s, to keep the test fast.)
4. **Timeout path:** no comments, small `wait_seconds` → returns empty `comments` with
   `timed_out:true` and `waited_seconds==wait_seconds`.

Keep tests fast: long-poll tests use small `wait_seconds`, not 1800. Run `./gradlew test` and
`./gradlew verifyPlugin` before done.

## Out-of-scope / future

- Event-driven wake (Channel/SharedFlow from `CommentStore`) instead of 1-second internal poll —
  unnecessary now; revisit only if the poll cadence shows up as a cost.
- Server-enforced max-empties — kept agent-side for now.
