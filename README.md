# Marginalia

![Build](https://github.com/borgand/marginalia/workflows/Build/badge.svg)

PR-style commenting and live edit merging between you and an AI coding agent, inside
IntelliJ, with the agent's native UX (Claude Code TUI) left fully intact.

Marginalia is a **sidecar to the agent, not a wrapper around it**: you keep running
Claude Code exactly as today. The plugin hosts a local MCP server that exposes the live
document and your review comments as tools, and a merge engine that lands the agent's
edits into the buffer you are typing in — re-positioned around your concurrent edits,
never clobbering them.

See [docs/main-prd.md](docs/main-prd.md) for the full product requirements and
[docs/decisions.md](docs/decisions.md) for implementation decisions taken along the way.

## How it works

1. You comment on a text range (`Add Marginalia Comment`, <kbd>Ctrl/Cmd+Alt+M</kbd>).
   The file becomes *co-edited* and the comment is queued.
2. The agent pulls comments via the `get_pending_comments` MCP tool (use the shipped
   `/marginalia` slash command, or just tell it to check).
3. A PreToolUse hook denies the agent's native `Edit`/`Write` on co-edited files and
   redirects it to `mcp__marginalia__apply_edit`, which merges hunks into the live
   buffer. Conflicts with your edits are returned to the agent — **user wins**.
4. The Marginalia tool window shows the queue, dispatch state, applied hunks, and
   conflicts. It is not a chat — the agent's own TUI is the chat.

## Setup

1. Install the plugin (build with `./gradlew buildPlugin`, install the zip from
   `build/distributions/` via <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> >
   <kbd>Install plugin from disk…</kbd>).
2. Register the MCP server with Claude Code (once):

   ```bash
   claude mcp add --transport http marginalia http://localhost:4747/mcp
   ```

3. Run <kbd>Tools</kbd> > <kbd>Marginalia: Install Claude Code Integration</kbd>. This
   installs (with your confirmation):
   - `~/.marginalia/marginalia-hook.sh` — the PreToolUse edit-routing hook (requires `jq`)
   - a `PreToolUse` hook entry in `~/.claude/settings.json`
   - `~/.claude/commands/marginalia.md` — the `/marginalia` slash command

## Usage

- **As-I-go review:** select text → <kbd>Ctrl/Cmd+Alt+M</kbd> → type the comment → keep
  reading. With auto-dispatch on, comments are immediately available to the agent.
- **Batch review:** toggle auto-dispatch off in the tool window, accumulate comments,
  then hit *Submit review*.
- **Direct edits** need no sync step: the agent reads the live buffer via `read_doc`.

## Markdown rendering

For `.md` files Marginalia layers MarkEdit-style visual enhancements directly on the native
editor — no separate editor pane, no webview. The raw source stays byte-identical for both
you and the agent; all rendering is ephemeral decoration.

**Tier 1 (on by default):**
- Styled headings (distinct color per H1–H6), bold/italic emphasis, strikethrough, colored
  list markers — all recolorable in **Settings > Editor > Color Scheme > Marginalia**.
- Blockquote left-bar accent and horizontal-rule full-width line painting.
- Folded link `](url)` targets, YAML frontmatter, and HTML comments (caret or Ctrl+. expands).
- Gutter icons for images (preview popup) and Mermaid diagrams (rendered on demand via a
  JCEF popup with bundled mermaid.min.js).

**Tier 2 (on by default for titles and tables; opt-in for inline images):**
- Large H1/H2 custom fold glyph for a reading-flow view.
- Aligned table grid rendered in the fold region.
- Opt-in inline image fold (off by default).

**Heading outline / navigation:** provided by the IDE's built-in Structure view
(View → Tool Windows → Structure, or Cmd+7 / Ctrl+F12) — the bundled Markdown plugin
renders the heading tree with no extra code. *Final visual confirmation is a manual check
(`./gradlew runIde`).*

All Tier 1 and Tier 2 features toggle independently in **Settings > Tools > Marginalia**.

## MCP tools

`list_co_edited_docs`, `read_doc`, `apply_edit`, `get_pending_comments`,
`resolve_comment` — contracts in [docs/main-prd.md §6](docs/main-prd.md).

## Development

- `./gradlew test` — unit + light platform tests
- `./gradlew verifyPlugin` — plugin structure / API compatibility checks
- `./gradlew runIde` — sandbox IDE for manual testing
- `./gradlew buildPlugin` — installable zip in `build/distributions/`

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
