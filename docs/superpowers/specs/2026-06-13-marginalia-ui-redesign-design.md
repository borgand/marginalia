# Marginalia UI Redesign — Design (v0.8)

**Date:** 2026-06-13
**Branch:** `feat/phase1-core-sidecar`
**Status:** Approved (brainstorming complete)

## Goal

Implement the redesigned, native-Swing Marginalia UI described in
`docs/redesign/Marginalia Implementation Guide.html` (the design intent),
`docs/redesign/Marginalia Redesign.html` (the mockups) and
`docs/redesign/mg-tokens.jsx` (the design tokens). Ship it as plugin version
**0.8.0** and tag `v0.8.0`.

The redesign brings: a role-named theme-token system, a rebuilt tool-window
("sidecar") with comments grouped by file, a review-progress ribbon, a
connection chip + collapsible footer log, a redesigned comment-capture surface
(inline popup default + dialog fallback), in-editor back-references (gutter pen
icon, line tint, error-stripe mark, tab wash), and a badged stripe icon.

Everything stays inside what the IntelliJ Platform natively supports — no web
views, no custom window chrome. Per CLAUDE.md this remains the queue/activity
surface, **not** a chat.

## Scope

Full 10-step build checklist from the Implementation Guide §08:

1. Design tokens → `MarginaliaColors`.
2. Tool window → `SimpleToolWindowPanel`; title/connection/overflow in header actions.
3. Toolbar (Auto-queue toggle, Submit review) + progress ribbon.
4. Grouped comment list (flattened `JBList` + custom renderer).
5. Jump-to-line + hover actions.
6. Footer status panel with collapsible raw log.
7. Redesigned `DialogWrapper` + inline `JBPopup` (popup is the default surface).
8. Gutter highlighters + `GutterIconRenderer` + `EditorTabColorProvider`.
9. Stripe icon badge from queued count.
10. Subtle state-transition animations (last).

Plus: version bump to 0.8.0, change-notes, `v0.8.0` tag.

### Out of scope (unchanged from CLAUDE.md)
Chat UI / agent-output rendering; ACP; multi-user/cloud. No changes to the MCP
tool contracts, `CommentStore` persistence schema, or `MergeEngine`.

## Decisions (locked during brainstorming)

- **Tokens as a Kotlin object** (`MarginaliaColors`), each role bound to its IDE
  `api` with the mock hex as `JBColor` fallback. No runtime JSON parsing, no
  `marginalia-tokens.json` loader.
- **Sub-folder package layout** under `ui/` (see below).
- **Comment capture default = inline `JBPopup`**; dialog is the fallback,
  selectable via a new setting in `MarginaliaConfigurable`.
- **"Failed" status maps to the existing `orphaned` flag** — no new enum value.
  An orphaned comment (anchor text deleted, see `CommentStore.ensureAnchored`)
  is the one genuine error state we have, and the red `status.conflict` pill
  gives it real treatment.

## Package layout

```
ui/theme/
  MarginaliaColors.kt   — object; one JBColor per role token + derived tints
  MarginaliaIcons.kt    — IconLoader handles (stripe, brand mark, gutter pen, badge)
ui/toolwindow/
  MarginaliaToolWindowFactory.kt  — (moved) builds SimpleToolWindowPanel, header actions
  MarginaliaPanel.kt    — SimpleToolWindowPanel(true,true); wires toolbar/list/footer
  SidecarRow.kt         — sealed row model: FileHeaderRow | CommentRow
  CommentListModel.kt   — comments -> ordered list of rows + per-file "to send" counts
  CommentListRenderer.kt— ListCellRenderer switching layout per row kind
  ProgressRibbon.kt     — JComponent: resolved/delivered/queued segments + legend
  ConnectionChip.kt     — JBLabel pill driven by McpServerService state
  FooterStatusPanel.kt  — agent line + MCP:port chip + collapsible raw log
  VisualStatus.kt       — enum QUEUED|DELIVERED|RESOLVED|FAILED + visualStatus(comment)
ui/comment/
  CommentForm.kt        — shared form: context strip + anchored snippet + textarea
  AddCommentDialog.kt   — DialogWrapper wrapping CommentForm
  InlineCommentPopup.kt — JBPopup wrapping CommentForm (default surface)
ui/editor/
  CommentHighlighter.kt — (moved) + GutterIconRenderer + errorStripeColor + tokenized bg
  MarginaliaTabColorProvider.kt — EditorTabColorProvider, faint status.pending wash
```

Existing UI files that stay where they are conceptually but move into the new
sub-packages: `AddCommentAction`, `MarginaliaConfigurable`,
`MarginaliaFloatingToolbarProvider`, `RestartServerAction`, `ToggleCoEditAction`,
`InstallClaudeIntegrationAction`, `ConnectivityReport`. Moves are mechanical
(`package` line + imports + `plugin.xml` FQCNs). `MarginaliaFileOpenListener`
moves with `CommentHighlighter` into `ui/editor`.

## Components & contracts

### MarginaliaColors (ui/theme)
A Kotlin `object`. One `val` per role token returning a `JBColor` (or `Color`):
`surfaceToolWindow`, `surfaceEditor`, `accent`, `accentButton`, `textOnAccent`,
`statusPending`, `statusResolved`, `statusConflict`, `textPrimary`, `textMuted`,
`border`, `selectionBg`. Bound to the verbatim `api` from `mg-tokens.jsx`
(e.g. `accent = JBUI.CurrentTheme.Link.Foreground.ENABLED`), with the mock
light/dark hexes as the `JBColor(light, dark)` fallback where the api is a raw
key. Derived helpers (not new tokens): `queuedTint = ColorUtil.withAlpha(statusPending, 0.12)`,
soft pill backgrounds = `withAlpha(base, ~0.13)`. **Rule: no raw hex anywhere
else in the UI — every color dereferences a `MarginaliaColors` member.**

### VisualStatus (ui/toolwindow)
Pure mapping seam. `enum VisualStatus { QUEUED, DELIVERED, RESOLVED, FAILED }`
and `fun visualStatus(c: MarginaliaComment): VisualStatus`:

| VisualStatus | Token | Backed by |
|---|---|---|
| FAILED | statusConflict | `c.orphaned == true` (checked first) |
| QUEUED | statusPending | `DRAFT`, `QUEUED` |
| DELIVERED | accent | `DISPATCHED`, `ADDRESSED` |
| RESOLVED | statusResolved | `RESOLVED` |

`orphaned` is checked before the enum so a lost anchor always reads as Failed.
Also exposes `label` and `color`/`softColor` for pills. This is the single
tested unit for status→appearance.

### CommentListModel + SidecarRow (ui/toolwindow)
`SidecarRow` is a sealed type with two subtypes: `FileHeaderRow(path, total,
queuedCount)` and `CommentRow(comment)`. `CommentListModel` is an
`AbstractListModel<SidecarRow>`. Input: the `CommentStore` comments. Output: a
flat, ordered list — for each file (sorted, stable) a `FileHeaderRow` followed
by its `CommentRow`s. Collapsing a file header hides its comment rows
(expansion state held in the model). Per-file "to send" = count of comments
whose `visualStatus == QUEUED`. This is testable headless without painting.

### CommentListRenderer (ui/toolwindow)
A `ListCellRenderer<SidecarRow>` that switches layout on row kind. File header:
chevron + filetype icon + bold name + count badge + `N to send` (pending) when
queued>0. Comment card: left status accent bar (painted for rounded look),
status pill + `L13` line chip + hover jump/more icons, anchor snippet (editor
font, muted, ellipsized), comment body (UI font, ~2 lines), `You · 2m ago` with
initials avatar. Colors via `MarginaliaColors`; fonts via `JBFont` /
`EditorColorsManager` editor font. Not unit-tested (painting) — verified by eye.

### ProgressRibbon (ui/toolwindow)
`JComponent`, height `JBUI.scale(5)`, paints three proportional rounded
segments (resolved/delivered/queued counts) + a legend row of `JBLabel`s.
Counts derived from `visualStatus` over all comments.

### ConnectionChip + FooterStatusPanel (ui/toolwindow)
Chip: rounded `JBLabel` with a colored dot; text/color from
`McpServerService.status` + connection state (green "Connected" /
pending / `status.conflict` on error). Footer: one line — dot +
`Agent connected · last call Ns ago` + `MCP :4747` chip + disclosure chevron
toggling a `JBScrollPane` holding the existing raw connectivity log (the current
console — kept, tucked away by default). Text-derivation helpers are pure and
tested; layout is not.

### Comment capture (ui/comment)
`CommentForm` is a reusable `JPanel`: context strip (file icon + name + `line N`),
the anchored line in the editor font inside a `status.pending`-ruled quote box,
and a `JBTextArea`/`EditorTextField` input. `AddCommentDialog` wraps it in a
`DialogWrapper` (title "Add comment", OK relabelled "Add comment", `⌘↵` bound).
`InlineCommentPopup` wraps the same form in a `JBPopup` shown via
`showInBestPositionFor(editor)`. `AddCommentAction` replaces
`Messages.showMultilineInputDialog` with whichever surface the setting selects
(default INLINE). The floating toolbar and `⌘⌥M` shortcut are unchanged
triggers. New setting `captureSurface: INLINE | DIALOG` in
`MarginaliaConfigurable` (persisted in its existing state).

### Back-references (ui/editor)
`CommentHighlighter` gains, per comment with a valid marker and non-resolved
status: a `GutterIconRenderer` (pen icon; click focuses the sidecar row /
opens the comment), `TextAttributes.backgroundColor = queuedTint`, and
`errorStripeColor = statusPending` (scrollbar map). `MarginaliaTabColorProvider`
implements `EditorTabColorProvider` and returns a faint `status.pending` wash
for files that have at least one non-resolved comment, else `null`. Registered
in `plugin.xml` (`com.intellij.editorTabColorProvider`).

### Icons & badge (ui/theme + factory)
New SVG icons under `src/main/resources/icons/` (theme-aware via IntelliJ SVG
patching): stripe/tool-window icon (margin-rule mark), 16×16 brand mark, gutter
pen, badge base. `MarginaliaToolWindowFactory` sets the stripe icon and, on
`CommentStore` change, overlays the queued count with
`BadgeIconSupplier`/`LayeredIcon` and calls `toolWindow.setIcon(...)`. No focus
stealing.

### Animations (last)
`JBAnimator`, ~150–200ms ease-outs, only on state transitions: a card fading
pending→accent on delivery, a ribbon segment growing on resolve. No looping or
decorative motion. Lowest priority; cut first if time-constrained.

## Threading (CLAUDE.md rules)
All highlighter/markup mutations and `toolWindow.setIcon` on EDT via
`invokeLater`. Any editor/document/selection read off the EDT (or on EDT under
the current platform's read-assertion, as just seen with the floating toolbar)
wrapped in `ReadAction.compute`. The `CommentStore` change listener already
hops to EDT in the existing panel; reuse that discipline.

## Testing strategy
TDD the verifiable seams; verify the rest by compile + `verifyPlugin` + manual
`runIde`.

- `MarginaliaColors` — fallback values resolve, every member non-null (pure).
- `VisualStatus.visualStatus()` — table-driven over all enum values + orphaned
  precedence (pure).
- `CommentListModel` — grouping order, per-file queued ("to send") counts,
  collapse hides rows (`BasePlatformTestCase`).
- `FooterStatusPanel`/`ConnectionChip` text helpers — pure derivation from a
  status/last-call-time input.
- `MarginaliaTabColorProvider` — color only for files with open comments, else
  null (`BasePlatformTestCase`).

Renderers, ribbon/badge painting, popup, dialog, animations → not unit-tested;
verified visually. Run `./gradlew test` and `./gradlew verifyPlugin` before done.

## Sequencing & checkpoint
Build in checklist order. **Checkpoint after steps 1–4** (tokens + icons +
`SimpleToolWindowPanel` shell + toolbar/ribbon + grouped list): user runs
`runIde` and confirms direction before back-refs/popup/badge/animations proceed.

## Version & tagging
`gradle.properties` `version = 0.8.0`; add `<change-notes>` / changelog entry;
commit; `git tag v0.8.0` as the final step after tests pass and the user has
eyeballed `runIde`.

## Pre-existing fix folded in
`MarginaliaFloatingToolbarProvider` read `selectionModel.hasSelection()` on the
EDT without a read action → startup exception flood. Fixed by wrapping in
`ReadAction.compute`. Independent of the redesign; included in this branch.
