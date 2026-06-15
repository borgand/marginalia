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
