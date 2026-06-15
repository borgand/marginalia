<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Marginalia Changelog

## [Unreleased]

## [1.1.0] - 2026-06-15

### Added
- **Long-poll for `get_pending_comments`.** The MCP tool takes an optional `wait_seconds`
  (0–1800): the call holds open until a comment is queued or the wait elapses (response is
  tagged `timed_out` / `waited_seconds`). Idle waiting costs no agent tokens, and reaction to
  new comments is near-immediate. Omitting `wait_seconds` (or `0`) keeps the one-shot read.

### Changed
- **`/marginalia` is now a self-terminating continuous loop.** It long-polls with
  `wait_seconds: 1800`, replies `IDLE` when the queue is empty, and stops on its own after
  three consecutive empty 30-minute holds (~90 minutes), so a forgotten session can't run up
  cost. Replaces the previous `/loop 1m /marginalia` recommendation.

### Fixed
- **Release notes and assets.** GitHub release drafts are now prefilled from the matching
  `CHANGELOG.md` section, and the built `marginalia-<version>.zip` plugin is attached to the
  release as a downloadable asset.

## [1.0.1] - 2026-06-14

### Added
- **MIT license.** The project is now distributed under the MIT License (`LICENSE`).

## [1.0.0] - 2026-06-14

### Added
- **Screenshots in the plugin listing.** The plugin manager and JetBrains Marketplace
  descriptions now embed the hero shot and the review-sidecar screenshot.

## [0.9.4] - 2026-06-14

### Changed
- **Comment status colors.** Delivered is now a lighter, more legible green (the previous
  green was hard to see), and Resolved is blue — distinct from the amber Queued/pending and
  from Delivered. The progress-ribbon bar now follows the same status→color mapping as the
  legend instead of using its own hardcoded colors.

## [0.9.3] - 2026-06-14

### Fixed
- **MCP connection from the bare base URL.** The server only served `/mcp`, so a client
  registered with `http://localhost:<port>` hit Ktor's empty-body 404 — which Claude Code
  surfaced as a broken/auth-required server ("Invalid OAuth error response… Unexpected
  EOF"). The root path now issues a 308 redirect (method + JSON-RPC body preserved) to
  `/mcp`, so either URL connects.
- **Queue hover overflow.** Disabled the tool-window list's expandable-items hover popup,
  which repainted comment cards overflowing past the IDE edge without adding detail.
- **Add-comment dialog width.** The dialog/popup stretched with the length of the selected
  text and could run off-screen. Width is now clamped and the anchored snippet is truncated
  (full text in a tooltip), so the surface size no longer depends on the selection.

## [0.9.2] - 2026-06-14

### Changed
- **New plugin icons.** Added a colored plugin logo (`pluginIcon.svg` / `_dark`) so the
  marketplace and plugin list show real branding instead of the generic plug. Redesigned
  the tool-window icon as a document with an amber comment balloon, filling the 16×16 box
  so it reads at the same size as neighboring icons; monochrome themed variants for light
  and dark.

## [0.9.0] - 2026-06-13

### Added
- **Phase 2 — MarkEdit-style markdown rendering (native editor).** All features are
  decorations over the bundled `org.intellij.plugins.markdown` PSI; the raw buffer stays
  byte-identical for user and agent.
  - Heading colors (H1–H6 distinct hues), inline emphasis, strikethrough, list-marker
    color — user-customizable in Settings > Editor > Color Scheme > Marginalia.
  - Blockquote left-bar accent and horizontal-rule full-width painting.
  - Structural folding: link `](url)` targets, YAML frontmatter, HTML comments
    (folded by default; caret or Ctrl+. expands).
  - Gutter popovers: image preview and on-demand Mermaid diagram rendering (bundled
    mermaid.min.js in a JCEF popup).
  - Custom-fold Tier 2: large H1/H2 title glyph, aligned table grid, opt-in inline
    image fold (off by default).
  - All features toggle independently in Settings > Tools > Marginalia.
  - Heading outline navigation via the IDE's built-in Structure view (no extra code).

## [0.8.0] - 2026-06-13
### Added
- **UI redesign** — a native-Swing refresh built entirely on Platform components:
  - Role-named design tokens (`MarginaliaColors`) bound to IntelliJ theme keys, so the
    whole UI follows the user's light/dark theme with no hand-tuning
  - Rebuilt sidecar (`SimpleToolWindowPanel`): comments **grouped by file** with
    collapsible headers and a per-file "N to send" count, rendered as status-colored cards
  - Review-progress ribbon (resolved / delivered / queued), animated on state change
  - "Connected" chip + a footer status line with the raw connectivity log tucked behind a
    disclosure toggle
  - Comment capture redesigned: an inline popup anchored at the line (new default) and a
    refined modal dialog with the anchored-line context strip; switchable in Settings
  - In-editor back-references: gutter pen icon, `status.pending @ 12%` line tint,
    scrollbar error-stripe mark, and a faint tab wash (`EditorTabColorProvider`) for files
    with open comments
  - Tool-window stripe icon with a pending-count badge
- In-editor floating toolbar: a small "Add Marginalia Comment" button appears on text
  selection (idiomatic `editorFloatingToolbarProvider`), reusing the existing action
- Tool window "Test agent connectivity" button: reports whether the MCP server is
  listening, whether an agent has connected, and the last tool call — with guidance
- Phase 1 core sidecar: anchored comments with persistence, comment queue with
  auto/manual dispatch, local MCP server (Streamable HTTP, port 4747) with
  `read_doc` / `apply_edit` / `get_pending_comments` / `resolve_comment` /
  `list_co_edited_docs`, merge engine with user-wins conflict policy, edit-routing
  PreToolUse hook + `/marginalia` slash command, tool window with queue and activity log
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

### Fixed
- MCP clients (Claude Code) got `HTTP 406` connecting to the server: SDK 0.10.0 doesn't
  install Ktor ContentNegotiation, so JSON POST responses fell back to an empty-body 406.
  We now install it with `McpJson`.
- Floating-toolbar provider read the editor selection on the EDT without a read action,
  flooding startup with "Read access is allowed from inside read-action only" — the
  selection check now runs inside `ReadAction.compute`.
