# Marginalia — PRD

**One-liner:** PR-style commenting and live edit merging between a human and an AI coding
agent, inside IntelliJ, with the agent's native UX (Claude Code TUI) left fully intact.

**Owner / pilot user:** Primariliy to solve personal need; later some marketplace ambitions to be able
to share with team and perhaps wider community if they see need for this. Optimize for working software over generality.

---

## 1. Problem

Today's workflow for PRDs and architecture docs: one-shot a draft with an agent, then
iterate via chat. This fails in three ways:

1. **Pinpointing is exhausting.** Describing small changes in chat is slower than just
   editing the text — but editing directly desyncs the agent's view.
2. **Section targeting is clumsy.** "Rewrite the third paragraph under Deployment" is a
   bad addressing scheme. Comments anchored to text ranges are the right one.
3. **Turn-taking destroys flow.** While the agent processes one comment, the user must
   not edit or comment, or work gets clobbered when the canvas/file reloads.

The same gap exists for code: agent-delivered changes deserve GitHub-PR-style inline
comments, sent one at a time ("as I go") or batched ("submit review") — a feature no
current ACP editor surface provides.

## 2. Solution overview

An IntelliJ plugin that is a **sidecar to the agent, not a wrapper around it**:

- The user runs Claude Code exactly as today (TUI in the IDE terminal, or the official
  Claude Code plugin panel). All skills, slash commands, hooks, plan mode remain available.
- The plugin hosts a local **MCP server** exposing the live document and the comment
  queue as tools.
- A **PreToolUse hook** denies the agent's native `Edit`/`Write` on co-edited files and
  redirects it to the plugin's `apply_edit` tool — guaranteeing every agent edit passes
  through the plugin's **merge engine**, which applies hunks into the live buffer with
  positions remapped through the user's concurrent typing. Nothing ever reloads.
- Comments are **anchored ranges** (RangeMarkers / ProseMirror decorations) that survive
  edits by either party.

Agent-agnostic by construction: anything that speaks MCP (Codex CLI, OpenCode) gains the
same capabilities with one config entry.

## 3. Core user workflows

**W1 — As-I-go review (primary).**
User reads a doc the agent drafted. Selects a sentence → `Add Comment` (keyboard-first)
→ types "too vague, give concrete failure modes" → keeps reading and commenting. The
plugin dispatches queued comments to the agent whenever the agent is idle. Agent edits
arrive inline in the buffer (subtle highlight on changed ranges); the user never stops
typing or waits.

**W2 — Batch review ("submit review").**
User accumulates N comments with dispatch paused, then flushes the queue as a single
prompt — PR style. Useful for coherent multi-section rework.

**W3 — Direct edit + comment.**
User rewrites a paragraph by hand, then comments elsewhere. The agent's next turn reads
the buffer fresh (via `read_doc`), so direct edits are automatically part of its context.
No explicit sync action exists or is needed.

**W4 — Code review of agent changes.**
Agent delivers code changes; user comments on hunks in normal code files with the same
mechanism. (Same core; native editor UI instead of webview.)

## 4. Requirements by phase

### Phase 1 — Core sidecar (MVP; raw MD + code files, native editor)

- **F1. Comment model & anchoring.** Create/edit/resolve comments on any text range in
  any open file. Anchor = `RangeMarker` + captured snippet + heading-path (for MD) for
  fuzzy re-anchoring if the marker invalidates. Persist per-file across IDE restarts
  (store offsets + snippets; re-anchor on open). Visual: range highlight + gutter icon.
- **F2. Comment queue & dispatch policy.** States: `draft → queued → dispatched →
  addressed/resolved`. Modes: *auto* (flush when agent idle) and *manual* (explicit
  "submit review"). Dispatched payload per comment: body, anchored snippet, heading
  path, ±2 lines context, file path, comment id.
- **F3. MCP server.** Project-level service, Streamable HTTP on localhost:4747
  (configurable). Tools per §6. Registered once by user:
  `claude mcp add --transport http marginalia http://localhost:4747/mcp`.
- **F4. Edit routing hook.** Installable PreToolUse hook (shell script + settings
  snippet the plugin can write into `~/.claude/settings.json` on user confirmation).
  Denies `Edit|Write|MultiEdit` whose target path is in
  `~/.marginalia/active-docs.json`, with feedback: *"This file is live co-edited in
  IntelliJ. Use mcp__marginalia__apply_edit."* Plugin keeps that JSON current.
- **F5. Merge engine.** For each agent session+file, snapshot the content at last
  `read_doc` (the *base version*, identified by a monotonically increasing id).
  `apply_edit` carries the base version id; engine computes the user's delta since
  base, re-anchors each agent hunk (exact match → whitespace-tolerant → fuzzy via
  diff-match-patch-style matching), and applies via `WriteCommandAction`.
  **Conflict policy: user wins.** A hunk overlapping a user-edited region is not
  applied; it is returned to the agent as a conflict (with current text) AND surfaced
  in the tool window for manual apply. Changed ranges get a fading highlight.
- **F6. Disk-write fallback.** VFS listener on co-edited files: if an external write
  lands anyway (hook bypassed/misconfigured), diff disk vs buffer and merge through F5
  instead of letting IntelliJ's "file changed on disk" flow clobber the buffer.
- **F7. Tool window.** A queue/activity view, *not* a chat: pending comments, dispatch
  state, applied hunks log, conflicts needing manual action, agent-idle indicator,
  auto/manual mode toggle. Plain-text log aesthetics are fine.
- **F8. PTY dispatch (1b).** Auto-dispatch writes the prompt into the Claude Code
  terminal PTY when idle (IntelliJ terminal API). Fallback if brittle: user triggers a
  `/marginalia` slash command in the TUI that pulls via `get_pending_comments` — ship
  the slash command definition regardless, it's ~10 lines.

**Phase 1 acceptance:** While the agent is mid-turn applying an earlier comment, the
user can type in the same file and add two more comments; nothing is lost, agent hunks
land correctly re-positioned, and both new comments dispatch on idle.

### Phase 2 — Enhanced raw-markdown rendering (native editor)

No WYSIWYG editor, no JCEF editor pane, no Milkdown/ProseMirror, no webview. One shared
buffer — byte-identical for user and agent — with visual richness delivered entirely as
decorations over the bundled `org.intellij.plugins.markdown` PSI via platform extension
points. Design spec: `docs/superpowers/specs/2026-06-13-phase2-markdown-rendering-design.md`.

**Tier 1 — Annotator / FoldingBuilder / LineMarkerProvider (on by default):**

- **F9. Heading color hierarchy.** `MarginaliaMarkdownAnnotator` applies
  `TextAttributesKey`s for H1–H6 (distinct hue per level), inline emphasis (bold, italic,
  strikethrough dimming), and list-marker color. Colors user-customizable via
  Settings > Editor > Color Scheme > Marginalia (`MarginaliaColorSettingsPage`).
- **F10. Structural folding.** `MarginaliaFoldingBuilder` folds link `](url)` targets,
  YAML frontmatter blocks, and HTML comments by default; caret enters / Ctrl+. expands.
- **F11. Line painting.** `MarkdownLineDecorator` (EditorLinePainter) draws a left-bar
  accent on blockquote lines and a full-width rule on `---`/`***` separator lines.
- **F12. Gutter popovers.** `ImageLineMarkerProvider` shows an image preview popup on
  demand from the gutter icon; `MermaidLineMarkerProvider` renders the diagram via the
  bundled `mermaid.min.js` in a lightweight JCEF popup (on demand only — no always-on
  render loop). All Tier 1 features toggle independently in Settings > Tools > Marginalia
  (`RenderSettings`).

**Tier 2 — CustomFoldRegion rendering (off by default for inline image; on for titles/tables):**

- **F13. Big-title custom folds.** `BigTitleFoldRenderer` collapses H1/H2 source into a
  large, styled glyph in the editor fold region (reading-flow enhancement).
  `TableFoldRenderer` renders pipe-table source as aligned text in a custom fold.
  `ImageFoldRenderer` optionally collapses `![…](…)` into an inline image preview
  (off by default; toggle in Settings).
  `CustomFoldController` manages lifecycle.

**Outline (F13 structural navigation):** provided by the bundled Markdown plugin's native
Structure view (View → Tool Windows → Structure, or Cmd+7 / Ctrl+F12) — displays the
heading tree for any `.md` file with zero additional code. Final visual confirmation
is a manual check: run `./gradlew runIde` and verify the Structure view shows headings.

**Agent coupling:** the IntelliJ `Document` remains canonical and byte-identical between
user and agent; decorations are ephemeral. No normalization noise, no extra sync surface.

### Phase 3 (optional, only if a real need emerges)

- ACP client as a second transport into the same core (fs/read+write interception
  replacing the MCP/hook pair); would require building turn UI — see CLAUDE.md
  out-of-scope note until explicitly re-decided.

## 5. Non-goals

- No chat UI, no rendering of agent thinking/streams (the agent's own TUI does this).
- No multi-human collaboration, no cloud.
- No attempt to replace the native editor with a WYSIWYG surface — Phase 2 enhances
  raw-source readability via decorations, not a separate editor pane.

## 6. MCP tool contracts (public API — implement exactly)

All tools return structured JSON. Errors are returned as `{ "error": { "code", "message" } }`
with codes: `NOT_CO_EDITED`, `STALE_BASE`, `CONFLICT`, `INVALID_ANCHOR`.

**`list_co_edited_docs()`** → `{ docs: [{ path, version }] }`

**`read_doc(path)`** → `{ path, version, content, headings: [{ level, text, offset }] }`
Serves the live buffer (never disk). Bumps nothing; `version` increments on every
buffer change. Side effect: records `(session, path, version, content)` as the merge base.

**`apply_edit(path, base_version, edits: [{ old_text, new_text }])`** →
`{ applied: [edit_idx], conflicts: [{ edit_idx, reason, current_text }], new_version }`
`old_text` must come from the `base_version` content (familiar Edit-tool semantics).
Engine re-anchors against the live buffer per F5. Partial success is normal: apply what
cleanly lands, return the rest as conflicts. If `base_version` is unknown/too old →
`STALE_BASE` (agent should `read_doc` again).

**`get_pending_comments(path?)`** →
`{ comments: [{ id, path, heading_path, anchored_text, context_before, context_after, body, created_at }] }`
Marks returned comments `dispatched`.

**`resolve_comment(id, note?)`** → `{ ok: true }`
Marks `addressed`; note shown in tool window. The dispatch prompt template instructs the
agent to call this per comment after editing.

## 7. Key design decisions (with rationale)

| Decision | Rationale |
|---|---|
| MCP sidecar + hook instead of ACP client | Keeps 100% of Claude Code UX/skills; deletes chat UI from scope; agent-agnostic via MCP. ACP adapter feature coverage of skills/commands is uneven. |
| Document is the primary surface | Co-editing output belongs inline; chat is secondary at best. |
| User wins conflicts | A personal tool must never overwrite the human; agent retries with fresh context cheaply. |
| `old_text/new_text` edit format | Matches the agent's native Edit-tool mental model → fewer malformed calls. |
| Version ids on read/apply | Makes staleness explicit instead of silently merging garbage. |
| Hook + VFS fallback (belt and suspenders) | Hooks can be bypassed; fallback turns the failure mode into a merge, not a clobber. |

## 8. Risks & mitigations

- **PTY injection brittleness** (TUI redraws, busy states): idle detection from PTY
  output quiescence + the slash-command pull fallback (F8).
- **Markdown normalization noise** (not a concern for Phase 2): the native editor keeps the
  buffer byte-identical; no remark/serializer pipeline is involved.
- **RangeMarker invalidation** on large agent rewrites: snippet+heading-path fuzzy
  re-anchor; orphaned comments surface in the tool window rather than vanish.
- **MCP server lifetime/port clashes**: one server per IDE instance, port from settings,
  fail loudly in the tool window if bind fails.
- **Threading bugs** (MCP threads ↔ EDT/write actions): the #1 expected defect class;
  CLAUDE.md rules + the mandated concurrency tests per tool.

## 9. Milestones

1. **M1 (first session):** Template builds; tool window shows queue; Add Comment action
   creates RangeMarker + entry; MCP server up with `read_doc` + `get_pending_comments`;
   verified end-to-end from a live Claude Code terminal.
2. **M2:** `apply_edit` + merge engine + tests (incl. concurrent-typing test); hook
   script + active-docs registry; F6 fallback. *Phase 1 acceptance test passes.*
3. **M3:** PTY auto-dispatch + slash command; resolve flow; comment persistence. Daily-driver ready for code + raw MD.
4. **M4:** Tier 1 rendering — heading/emphasis/list-marker color attributes, blockquote bar,
   horizontal rule, structural folding (link URLs, frontmatter, HTML comments), image and
   Mermaid gutter popovers. All Tier 1 features toggle in `RenderSettings`.
5. **M5:** Tier 2 custom folds — large H1/H2 title glyph, aligned table grid, opt-in
   inline image fold (off by default). Design spec:
   `docs/superpowers/specs/2026-06-13-phase2-markdown-rendering-design.md`.
