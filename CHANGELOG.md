<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Marginalia Changelog

## [Unreleased]

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
