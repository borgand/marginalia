# Phase 2 — MarkEdit-style raw-markdown rendering (design)

Date: 2026-06-13
Status: approved (supersedes the WYSIWYG/Milkdown direction in PRD §4 Phase 2, §9 M4–M5,
and the "Phase 2 frontend: Milkdown in JCEF / `webview/`" note in CLAUDE.md)

## 1. Summary

Phase 2 makes markdown **more readable inside the normal IntelliJ editor** by painting
rich-text styling and selective in-place rendering onto the raw source buffer — the
MarkEdit philosophy. We are **not** building a WYSIWYG surface: no JCEF document editor,
no Milkdown, no ProseMirror, no second document model, no `webview/` build pipeline.

The source the user edits stays **byte-identical** to the source the agent reads. Every
mechanism is a *decoration* (markup attributes, fold, custom-fold, gutter icon) over the
bundled IntelliJ Markdown plugin's PSI. The `Document` is never mutated by rendering. The
single exception is opt-in click-to-toggle of a task-list checkbox, which writes one
character through `WriteCommandAction`.

This narrows the original PRD F9–F13:

| PRD item | Original (WYSIWYG) | Revised |
|---|---|---|
| F9 JCEF FileEditor | JCEF editor tab | **Dropped.** Native editor only. |
| F10 Milkdown + inline Mermaid | ProseMirror render | **Dropped.** Decorations on raw text; Mermaid → gutter popover. |
| F11 comments as PM decorations | PM decorations | **Moot.** Comments already render as `RangeHighlighter`s (Phase 1, `CommentHighlighter`). |
| F12 merge-as-PM-transactions | PM transactions | **Moot.** One buffer; the Phase 1 merge engine already lands agent edits in it. |
| F13 outline pane | custom/Structure | **Rely on the bundled Markdown plugin's Structure view**; verify + document, build nothing custom. |

## 2. Guiding principles & hard constraints

**Two separated concerns (from the rendering handoff):**
- *Agent coupling* — the file the agent reads is byte-identical to the file the user
  edits. **Hard requirement.** One `Document`/buffer shared by user and agent. No second
  model, no two-way binding. Every mechanism below preserves this.
- *Visual fidelity* — what's on screen matches the literal characters. A preference, not
  a requirement. The user prefers high fidelity → favor pure attributes + reversible
  folds, and use in-place custom-fold rendering only where it clearly earns its keep.

**Hard constraints (CLAUDE.md):**
- Never mutate the `Document` for rendering (decorations only). The one allowed write is
  the opt-in task-checkbox toggle (single char, `WriteCommandAction`).
- `RangeMarker`s, comment anchoring, and the merge engine are unaffected — decorations are
  zero-width / non-textual. Keep it that way.
- Markup/UI on EDT; PSI/Document reads off-thread via `ReadAction` (the platform's
  highlighting passes already provide this context for annotators/folding/line markers).

**The one MarkEdit thing IntelliJ cannot do with attributes:** larger heading *glyphs*.
`TextAttributes` carries color + `fontType` (plain/bold/italic/bold-italic) + effects
(underline/strikeout/box) but **no font size or family**. Genuinely larger editable
heading text is only possible by *stopping using editable text* for that span — i.e. a
`CustomFoldRegion` that unfolds the instant the caret enters (Tier 2, §5).

## 3. Integration approach (decided)

**Depend on the bundled Markdown plugin and extend it through standard platform
extension points.** Rationale: accurate, incrementally-maintained markdown PSI for free;
the platform schedules our highlighting/folding/line-marker passes incrementally (no
hand-rolled `DocumentListener` debounce); colors become user-customizable via
`TextAttributesKey` + `ColorSettingsPage`; least code. Annotators *stack* on top of the
bundled plugin's own markdown highlighting, so our styling is additive — verify there is
no double-styling during implementation and prefer our keys where they overlap.

Alternatives rejected: (a) standalone `org.jetbrains:markdown` parse + manual
`addRangeHighlighter` on a `DocumentListener` (Phase-1-consistent but reimplements
incremental scheduling and fold lifecycle — more code); (b) hybrid PSI-for-ranges +
manual painting (no clear win over using the platform EPs directly).

**Wiring changes:**
- `build.gradle.kts`: add `bundledPlugin("org.intellij.plugins.markdown")` to the
  `intellijPlatform { }` dependencies block.
- `plugin.xml`: add `<depends>org.intellij.plugins.markdown</depends>` and register the
  new annotator / folding builder / line-marker / color-settings-page / custom-fold
  extensions against language `Markdown`.

`sinceBuild` is 2025.2 (`intellijIdea("2025.2.6.2")`), well above the 2022.1 floor that
`CustomFoldRegion` requires, so Tier 2 is viable without raising the minimum IDE.

## 4. Component layering

All new code under `ui/render/`. The Phase 1 `CommentHighlighter` (markup-model based,
layer `SELECTION-1`) is untouched and coexists — comment tints and rendering decorations
use different extension points and layers.

```
ui/render/
  MarkdownStructure          — thin read-only accessor over org.intellij.plugins.markdown
                               PSI: heading (level), emphasis (bold/italic/strike), link
                               (text vs url range), blockquote, list item + marker, HR,
                               fenced code (+ info string), frontmatter, HTML comment,
                               table. One place that knows the PSI element types.
  MarginaliaMarkdownAnnotator — Annotator(language=Markdown): emphasis, headings,
                               blockquote text, list markers, HR. Applies TextAttributesKeys.
  MarginaliaTextAttributes   — the TextAttributesKey definitions (H1/H2/H3/H4-6, quote,
                               list marker, dimmed-marker, strike), default light+dark.
  MarginaliaColorSettingsPage — ColorSettingsPage exposing those keys (Settings > Editor >
                               Color Scheme > Marginalia) so the user can recolor.
  MarginaliaFoldingBuilder    — FoldingBuilder(language=Markdown): link `](url)`,
                               frontmatter, HTML comment. Collapsed-by-default; the
                               platform auto-expands when the caret enters the region.
  line/
    BlockquoteBarRenderer    — left vertical bar + tint for `>` spans (CustomHighlighterRenderer).
    HorizontalRuleRenderer   — full-width rule painted over `---`/`***` lines.
  fold/
    BigTitleFoldRegion       — CustomFoldRegion + renderer: H1/H2 large painted title;
                               unfolds to editable `# Title` on caret entry. (Tier 2)
    TableFoldRegion          — CustomFoldRegion + renderer: aligned/rendered grid; caret
                               entry → raw pipe source. (Tier 2)
    ImageFoldRegion          — CustomFoldRegion + renderer: inline image; OFF by default. (Tier 2)
    CustomFoldController      — installs/updates custom folds for the visible doc; manages
                               the fold-on-blur / unfold-on-caret lifecycle consistently.
  gutter/
    ImageLineMarkerProvider  — gutter icon next to `![alt](url)`; click → JBPopup renders image.
    MermaidLineMarkerProvider— gutter icon next to ```` ```mermaid ````; click → JBPopup
                               renders the diagram. Render engine (JCEF/CLI) lives ONLY
                               inside the popup, on demand.
  RenderSettings             — persisted per-feature toggles (link-url fold, frontmatter
                               fold, big-title render, inline-image render, marker dimming);
                               surfaced in the existing Marginalia Configurable.
```

## 5. Feature → mechanism map (locked)

Legend: **TA**=`TextAttributes` via Annotator; **FOLD**=`FoldRegion`/FoldingBuilder;
**CFOLD**=`CustomFoldRegion`; **LINE**=custom line/range painter; **GUTTER**=gutter icon
→ `JBPopup`.

### Tier 1 — build first (cheap, low risk, ~90% of the MarkEdit feel)
- **Inline emphasis (TA):** `**bold**`→BOLD; `*x*`/`_x_`→ITALIC; bold-italic; `~~x~~`→
  `EffectType.STRIKEOUT`. Optionally dim the `**`/`_`/`~~` markers (faded). Inline code &
  fences already render acceptably — leave as-is; optional fence left-bar (LINE) +
  language badge.
- **Headings (TA + ColorSettingsPage):** H1 = bold + underline + color A; H2 = underline +
  color B; H3 = bold + color C; H4–H6 = muted shared color. Markers (`#`) stay on screen,
  optionally dimmed. Theme-aware via keys (light + dark defaults), user-customizable.
- **Blockquotes (LINE + TA):** left vertical bar + subtle background tint + italic colored
  text.
- **Lists (TA):** colored `-`/`*`/`1.` markers (IntelliJ already draws indent guides).
  Optional: task lists `- [ ]`/`- [x]` rendered ☐/☑ (small CFOLD), opt-in click-to-toggle
  that writes the single char via `WriteCommandAction`.
- **Horizontal rule (LINE):** real full-width rule across `---`/`***`.
- **Links (FOLD, folded by default):** `[text](url)` → fold `](url)` so only `text` shows
  (blue/underlined); caret on the line expands it.
- **Frontmatter / HTML comments (FOLD, folded by default):** YAML frontmatter block and
  `<!-- -->` folded + dimmed; caret-expand.
- **Images (GUTTER):** gutter icon next to `![alt](url)` → click → popup renders the image
  on demand. Source stays 100% literal.
- **Mermaid (GUTTER, firmly):** gutter diagram icon → click → popup renders the diagram.
  The render engine runs only inside the popup, on demand (never on open / per edit).

### Tier 2 — in scope this pass (custom fold; higher effort/risk, build after Tier 1)
- **Big-title render (CFOLD), H1/H2 only:** collapses to a large painted title; unfolds to
  editable `# Title` on caret entry. The only way to get genuinely larger heading glyphs.
  Do **not** use a block inlay.
- **Tables (CFOLD):** folded → aligned/rendered grid; caret entry → raw pipe source. Most
  effort (column measuring). When table render is disabled, fall back to a cheap LINE
  box/tint around the literal pipe source.
- **Inline images (CFOLD), OFF by default:** opt-in toggle in RenderSettings; the gutter
  popover remains the default to avoid vertical churn.

### Explicitly NOT built
No WYSIWYG editing surface; no block inlays that duplicate source text; no second document
model / two-way binding; no inline (always-on) Mermaid rendering.

## 6. Outline (F13)

Rely on the bundled Markdown plugin's **Structure view** (heading tree + jump-to). The
implementation step is to verify it activates for our `.md` files and document it in the
README as the outline solution. Build nothing custom unless it proves insufficient in use.

## 7. Threading & lifecycle

- Annotator / FoldingBuilder / LineMarkerProvider passes are scheduled and run by the
  platform's highlighting infrastructure off the markdown PSI — incremental, in the
  platform's read context. We do **not** add a `DocumentListener` for these.
- `CustomFoldController` reacts to caret movement (unfold the region under the caret, refold
  on leave) and to document structure changes; fold mutations run on EDT inside
  `FoldingModel.runBatchFoldingOperation`.
- Gutter popups (`JBPopup`) are built and shown on EDT; the Mermaid render engine is created
  lazily inside the popup and disposed with it.
- Phase 1's `CommentHighlighter.refreshAll()` loop is unchanged.

## 8. Settings / customization

- Colors: `TextAttributesKey`s with light + dark defaults, exposed through
  `MarginaliaColorSettingsPage`. No hardcoded RGB in the annotator.
- Feature toggles in `RenderSettings`, surfaced in the existing Marginalia Configurable:
  link-url folding (default on), frontmatter folding (default on), marker dimming
  (default on), big-title render (default on), table render (default on), inline-image
  render (default **off**). Toggling a feature refreshes the relevant passes / folds.

## 9. Testing strategy

- `MarkdownStructure`: `BasePlatformTestCase` with markdown fixtures — assert it classifies
  heading levels, emphasis kinds, link text/url ranges, blockquote/list/HR/fence/
  frontmatter/comment/table nodes at the right offsets.
- `MarginaliaMarkdownAnnotator`: fixture-based — assert the expected `TextAttributesKey`
  (or highlight info) lands on the expected ranges for representative `.md`.
- `MarginaliaFoldingBuilder`: assert fold regions cover the right spans and are
  collapsed-by-default for links/frontmatter/comments.
- Custom-fold renderers (`BigTitle`, `Table`, `Image`): light tests for fold placement and
  the caret-enter→unfold / leave→refold lifecycle via `CustomFoldController`; actual
  painting verified manually in `runIde`.
- Gutter providers (image/mermaid): assert the icon appears on the correct line and the
  click action builds a popup; actual render verified manually.
- Regression: existing 49 tests stay green; comment highlighting and the merge engine are
  untouched. `./gradlew test` + `verifyPlugin` green before done.

## 10. Documentation changes (part of the work)

- **PRD** (`docs/main-prd.md`): rewrite §4 Phase 2 (F9–F13) and §9 milestones M4–M5 to
  describe this rendering layer; drop JCEF/Milkdown/ProseMirror wording.
- **CLAUDE.md**: remove the stale "Phase 2 frontend: Milkdown in JCEF; built with Vite into
  `webview/`" tech-stack bullet and the Phase-2 references that assume a webview; add
  `ui/render/` to the architecture map.
- **decisions.md**: add entries — (D17) supersede WYSIWYG with MarkEdit-style raw rendering;
  (D18) depend on the bundled Markdown plugin + platform extension points; (D19) Mermaid &
  images via gutter popover only, custom-fold for big titles/tables/inline-images, block
  inlays rejected.
- **README/CHANGELOG**: note the rendering enhancements and the Structure-view outline.

## 11. Build order

1. Wiring: add the bundled-Markdown dependency + `MarkdownStructure` accessor + the
   `TextAttributesKey`s + `ColorSettingsPage` + `RenderSettings`.
2. Tier 1 attributes: emphasis, headings, list markers (Annotator).
3. Tier 1 line painters: blockquote bar + tint, horizontal rule.
4. Tier 1 folds: link-url, frontmatter, HTML comments (FoldingBuilder).
5. Tier 1 gutter popovers: images, Mermaid.
6. Tier 1 task-list checkboxes (render + opt-in toggle).
7. Tier 2 custom folds: big H1/H2 titles → tables → optional inline images;
   `CustomFoldController` lifecycle.
8. Outline verification + all documentation updates.

Tier 1 (steps 1–6) lands the bulk of the value at low risk; Tier 2 (step 7) adds the
custom-fold rendering where it earns its keep.
