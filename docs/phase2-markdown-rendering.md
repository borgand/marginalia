# Phase 2 — Enhanced raw-markdown rendering (design handoff)

> Handoff note for the agent designing Phase 2. This captures decisions reached in a
> separate brainstorming session about how to make markdown *more readable in the
> IntelliJ editor* without moving to a WYSIWYG render surface. Read this before
> finalizing the Phase 2 plan; it narrows the design space considerably.

Date: 2026-06-13

## TL;DR

We are **not** building WYSIWYG (no Milkdown/ProseMirror/JCEF editing surface for the
document body). Instead we enrich the **raw markdown shown in the normal IntelliJ
editor** with styling and selective in-place rendering, so the source the user edits is
*byte-identical* to the source the agent reads. The reference product is **MarkEdit**.

## Reference: what MarkEdit does

Screenshot (look at it directly): https://github.com/MarkEdit-app/MarkEdit/blob/main/Screenshots/01.png

MarkEdit (built on CodeMirror) keeps the **raw source visible** — every `#`, `**`,
`` ` ``, `>` stays on screen — and paints rich-text styling onto that same buffer. No
second model, no separate render pane. Headings are bold/larger, `**bold**` is bold,
`_italic_` is italic, blockquotes are colored italic, code is styled — all inline, in
place, on the editable text.

This is exactly the philosophy we want, and it matches what Phase 1 already does:
`CommentHighlighter` paints `TextAttributes` over `RangeMarker`-anchored spans via
`markupModel.addRangeHighlighter` without touching the `Document`.

### The one thing IntelliJ cannot copy

MarkEdit makes **heading lines a physically larger font** while they stay editable text.
**IntelliJ's editor cannot do this.** `TextAttributes` carries foreground/background
color and `fontType` (plain / **bold** / *italic* / bold-italic) and effects
(underline, strikethrough, box) — but **no font size and no font family**. The editor
renders every line on one fixed font-size grid. Bigger editable heading text is
architecturally impossible with attributes; the only way to get larger glyphs is to
stop using editable text for that span (see "custom fold region" below).

Everything else MarkEdit does (bold, italic, strikethrough, blockquote color, link
styling, code) needs only weight/slant/color/effect — all fully supported.

## Guiding principle: coupling

Separate two concerns:

- **Agent coupling** — *is the file the agent reads byte-identical to the file the user
  edits?* This is the hard requirement. **Every** mechanism below preserves it: there is
  always exactly one `Document`/buffer, shared by user and agent. None of these create a
  second model or a two-way binding. (This is precisely what a WYSIWYG webview would have
  threatened, and why we're avoiding it.)
- **Visual fidelity** — *does what's on screen match the literal characters?* A
  preference, not a hard requirement. Pure attribute styling = perfect fidelity. Folding
  hides chars reversibly. Inlays add non-existent visual chars. The user prefers high
  fidelity, which steers us toward attributes + folds, and toward using in-place
  rendering only where it clearly earns its keep.

**Hard constraints (from CLAUDE.md — do not violate):**
- Never mutate the `Document`. These are all decorations (markup, folds, inlays, gutter)
  — the buffer is never changed by rendering.
- `RangeMarker`s, comment anchoring, and the merge engine are unaffected by any of this
  (decorations are zero-width / non-textual). Keep it that way.
- UI/markup on EDT; reads off-thread via `ReadAction`. Same threading discipline as the
  rest of the plugin.

## Mechanisms glossary

| Tag | Mechanism | Fidelity | Notes |
|---|---|---|---|
| **TA** | `RangeHighlighter` + `TextAttributes` (what Phase 1 uses) | perfect | color, bold, italic, underline, strikethrough, box. **No size.** Cheap. |
| **FOLD** | `FoldRegion` collapsing a span to a placeholder | hides reversibly | auto-expands when caret enters. Buffer untouched. Cheap–medium. |
| **CUSTOM FOLD** | `CustomFoldRegion` + `CustomFoldRegionRenderer` | renders in place | folds source line(s) and **paints anything** (big text, image, table grid) in their place; **unfolds to raw markdown the instant the caret enters**. The right tool for "rendered but still editable." Medium–high effort. Needs a modern `sinceBuild` (2022.1+); verify against `gradle.properties`. |
| **BLOCK INLAY** | `inlayModel` block element between lines | adds non-text pixels | duplicates rather than replaces source (a big banner *above* the still-normal heading). **Avoid** — this is the one that felt wrong. |
| **LINE** | custom line/range painter (`LineMarkerRenderer` / `CustomHighlighterRenderer`) | perfect | bars and rules drawn beside/over text. Medium. |
| **GUTTER POPOVER** | `GutterIconRenderer` + click → `JBPopup` | perfect | render-on-demand in a transient popup, like the CSS color swatch. Source stays fully literal. |

**Key unification:** "render in place but keep it editable" is a **custom fold region**,
not a block inlay — and it reuses the *same fold-on-blur / unfold-on-caret interaction*
we already chose for link URLs. One consistent model across links, headings, and tables.

## Per-feature rendering decisions

All of the following are **Tier 1** (cheap, build first) unless marked **Tier 2**.

### Inline emphasis — TA, perfect parity with MarkEdit
- **Bold** `**x**` → `fontType = BOLD` on the inner text; optionally dim the `**` markers.
- **Italic** `*x*` / `_x_` → italic.
- **Bold-italic** → bold + italic.
- **Strikethrough** `~~x~~` → `EffectType.STRIKEOUT` (real strikethrough).
- **Inline code** / **code fences** → already render well; leave as-is. Optional: fence
  left-bar (**LINE**) and a language badge.

### Headings — TA, user's scheme (Tier 1)
Pure attributes, always editable in place, `#`/`##`/`###` markers stay on screen. The
user uses H1–H3 in practice; H4–H6 get a shared muted color.

| Level | Style |
|---|---|
| **H1** | bold + underline + bright color A |
| **H2** | underline + color B |
| **H3** | bold + color C |
| **H4–H6** | color C (or a muted shared color) |

- Colors must be **theme-aware** (resolve for light vs dark; ideally exposed as
  color-scheme keys the user can customize) — not hardcoded RGB.
- Optional: **dim the `#` markers** (faded gray) so heading text pops while markers stay.
- **Tier 2 (optional):** big-title **CUSTOM FOLD** render for **H1/H2 only** — collapses
  to a large painted title, unfolds to editable `# Title` on caret entry. This is the
  only way to get genuinely larger heading glyphs. Add it *after* Tier 1, and only if the
  styled version doesn't feel like enough. Do **not** use a block inlay for this.

### Blockquotes `>` — LINE + TA (Tier 1)
Left vertical bar + subtle background tint + italic colored text (MarkEdit-style).

### Lists — TA (Tier 1)
Colored list markers (`-`, `*`, `1.`). IntelliJ already draws indent guides for nesting.
Optional: task lists `- [ ]` / `- [x]` rendered as ☐ / ☑ (custom fold or small inlay),
optionally click-to-toggle (toggle writes the single char via `WriteCommandAction`).

### Horizontal rule `---` / `***` — LINE (Tier 1)
Paint a real full-width rule across the line.

### Links — FOLD, fold-by-default (Tier 1)
`[text](https://long-url)` → fold `](url)` so only `text` shows (blue / underlined);
**unfold when the caret enters the line**. Decision: **fold by default.**

### Frontmatter / HTML comments — FOLD (Tier 1)
YAML frontmatter block and `<!-- comments -->` folded by default + dimmed; caret-expand.
Decision: **fold by default.**

### Images — GUTTER POPOVER by default (Tier 1); inline opt-in (Tier 2)
Gutter icon next to `![alt](url)` → click → popup renders the image on demand (like the
image-file thumbnail / CSS color swatch). Source stays 100% literal.
- **Tier 2 (optional toggle):** inline image **CUSTOM FOLD** render — the strongest
  candidate for true inline rendering in a reading tool, but off by default to avoid
  vertical churn.

### Mermaid ` ```mermaid ` — GUTTER POPOVER, firmly (Tier 1)
Gutter diagram icon → click → render the diagram in a popup. Rendering needs a real
engine (JCEF or a CLI), so it must run **on demand only** — inline rendering would run
the engine for every diagram on open and re-run on every edit. The render engine lives
entirely inside the popup; the buffer never sees it.

### Tables — Tier 2, CUSTOM FOLD
A table is reading-flow content you want to see in place (popover would be wrong), so use
a **custom fold region**: folded → aligned/rendered grid, caret entry → raw pipe source
for editing. Most effort of the table options (column measuring); fair to defer.
Cheap interim: leave literal with a subtle box/tint (**LINE**).

## Build order

**Tier 1 (build first — cheap, low risk, ~90% of the MarkEdit feel):**
inline emphasis (bold/italic/strike), heading attribute scheme, blockquote bar+tint,
list-marker color, horizontal rule, link-URL folding, frontmatter/comment folding,
gutter popovers for images and mermaid. All reuse the Phase 1 markup path; zero Document
impact.

**Tier 2 (add sparingly, after Tier 1 lands):**
big-title custom-fold render for H1/H2, table custom-fold render, optional inline image
custom-fold render (toggle).

The rendering cost and risk rise from Tier 1 → Tier 2; the gutter popover is the pressure
valve for the expensive cases (mermaid always, images by default), and custom-fold render
is reserved for things that belong in the reading flow and are cheap-ish to paint
(headings, tables).

## What is explicitly NOT being built

- No WYSIWYG editing surface for the document body (no Milkdown/ProseMirror/JCEF editor).
- No block inlays that duplicate source text.
- No second document model / two-way binding. One buffer, always.
- No inline mermaid rendering (popover only).