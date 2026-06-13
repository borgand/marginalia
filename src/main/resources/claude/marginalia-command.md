---
description: Pull and address pending Marginalia review comments from IntelliJ
---

Fetch the user's pending review comments by calling `mcp__marginalia__get_pending_comments`.

If there are none, say so briefly and stop.

For each comment, in order:
1. Call `mcp__marginalia__read_doc` for the comment's file to get the live buffer and its `version`.
2. Locate the anchored text (use `heading_path`, `context_before`/`context_after` to find it) and make the change the comment asks for via `mcp__marginalia__apply_edit`, passing that `version` as `base_version` and exact `old_text` from the content you just read.
3. If `apply_edit` returns conflicts, the user edited that region meanwhile — the user wins. Re-read the doc and retry once with fresh context; if it still conflicts, leave it and mention it.
4. Call `mcp__marginalia__resolve_comment` with the comment `id` and a one-line note describing what you changed.

Do not edit co-edited files with the native Edit/Write tools.
