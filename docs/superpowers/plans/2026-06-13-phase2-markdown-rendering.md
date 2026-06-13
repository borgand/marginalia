# Phase 2 — MarkEdit-style Markdown Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make markdown more readable inside the normal IntelliJ editor by painting rich styling and selective in-place rendering onto the raw source buffer (MarkEdit-style), without any WYSIWYG surface — the buffer the user edits stays byte-identical to the buffer the agent reads.

**Architecture:** One shared `Document`. Every feature is a *decoration* (text attributes, fold, custom-fold, gutter icon, custom line painter) over the bundled IntelliJ Markdown plugin's PSI, wired through standard platform extension points. The only buffer write is an opt-in task-checkbox toggle. Phase 1 (CommentHighlighter, merge engine, MCP server) is untouched.

**Tech Stack:** Kotlin, IntelliJ Platform (IC 2025.2, `sinceBuild` 2025.2), bundled `org.intellij.plugins.markdown` plugin, `Annotator` / `FoldingBuilderEx` / `LineMarkerProvider` / `ColorSettingsPage` / `CustomFoldRegion` extension points, `BasePlatformTestCase` for tests.

**Design spec:** `docs/superpowers/specs/2026-06-13-phase2-markdown-rendering-design.md`

---

## File Structure

New code lives under `src/main/kotlin/com/github/borgand/marginalia/ui/render/`:

| File | Responsibility |
|---|---|
| `MarkdownStructure.kt` | The ONLY file that knows `org.intellij.plugins.markdown` PSI element types. Pure helper functions classifying a `PsiElement` (heading level, emphasis kind, link fold range, image, mermaid fence, blockquote, list marker, HR, frontmatter, HTML comment, table, task checkbox) + tree collectors. |
| `MarginaliaTextAttributes.kt` | `TextAttributesKey` definitions (H1/H2/H3/H4-6, blockquote text, list marker, dimmed marker, strikethrough) with light+dark defaults. |
| `MarginaliaColorSettingsPage.kt` | `ColorSettingsPage` exposing those keys under Settings > Editor > Color Scheme > Marginalia. |
| `MarginaliaMarkdownAnnotator.kt` | `Annotator`: emphasis, headings, list markers (text-attribute styling). |
| `MarkdownLineDecorator.kt` | Project service (mirrors Phase 1 `CommentHighlighter`): blockquote left-bar + tint, horizontal-rule painting, via `RangeHighlighter` + custom renderers. |
| `MarginaliaFoldingBuilder.kt` | `FoldingBuilderEx`: link `](url)`, frontmatter, HTML comment — collapsed-by-default. |
| `gutter/ImageLineMarkerProvider.kt` | Gutter icon on `![alt](url)`; click → `JBPopup` rendering the image. |
| `gutter/MermaidLineMarkerProvider.kt` | Gutter icon on ` ```mermaid ` fences; click → `JBPopup` rendering the diagram (JCEF inside the popup only). |
| `RenderSettings.kt` | Persisted per-feature toggles; surfaced in the existing Marginalia Configurable. |
| `fold/CustomFoldController.kt` | (Tier 2) Installs/updates `CustomFoldRegion`s; manages caret-enter→unfold / leave→refold lifecycle. |
| `fold/BigTitleFoldRenderer.kt` | (Tier 2) `CustomFoldRegionRenderer` painting large H1/H2 titles. |
| `fold/TableFoldRenderer.kt` | (Tier 2) `CustomFoldRegionRenderer` painting an aligned table grid. |
| `fold/ImageFoldRenderer.kt` | (Tier 2) `CustomFoldRegionRenderer` painting an inline image (off by default). |

Modified: `build.gradle.kts`, `src/main/resources/META-INF/plugin.xml`, `docs/main-prd.md`, `CLAUDE.md`, `docs/decisions.md`, `README.md`, `CHANGELOG.md`.

> **Milestone boundary:** Tasks 1–8 are Tier 1 — a complete, shippable MarkEdit feel. Tasks 9–11 are Tier 2 (custom-fold rendering). Task 12 is docs. You may ship after Task 8.

---

## Task 1: Wire the bundled Markdown plugin dependency

**Files:**
- Modify: `build.gradle.kts` (dependencies → `intellijPlatform { }` block)
- Modify: `src/main/resources/META-INF/plugin.xml:19`

- [ ] **Step 1: Add the bundled plugin to Gradle**

In `build.gradle.kts`, inside `dependencies { intellijPlatform { ... } }` (after the `intellijIdea(...)` line, before `testFramework(...)`):

```kotlin
        intellijIdea("2025.2.6.2")
        bundledPlugin("org.intellij.plugins.markdown")
        testFramework(TestFrameworkType.Platform)
```

- [ ] **Step 2: Declare the dependency in plugin.xml**

In `src/main/resources/META-INF/plugin.xml`, after line 19 (`<depends>com.intellij.modules.platform</depends>`):

```xml
    <depends>com.intellij.modules.platform</depends>
    <depends>org.intellij.plugins.markdown</depends>
```

- [ ] **Step 3: Verify the dependency resolves and the build still works**

Run: `./gradlew compileKotlin verifyPlugin`
Expected: BUILD SUCCESSFUL. `verifyPlugin` reports only the pre-existing "experimental API" infos (no new "plugin dependency not found" errors).

> If `bundledPlugin("org.intellij.plugins.markdown")` fails to resolve, the plugin id is wrong for this IDE — list available ids with `./gradlew printBundledPlugins` (IntelliJ Platform Gradle Plugin task) and use the markdown one.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts src/main/resources/META-INF/plugin.xml
git commit -m "build: depend on bundled Markdown plugin for phase 2 rendering"
```

---

## Task 2: `MarkdownStructure` — the PSI accessor

This is the only file that imports `org.intellij.plugins.markdown.*`. Everything else calls these pure functions. Build it test-first so any wrong element-type constant surfaces immediately.

**Files:**
- Create: `src/main/kotlin/com/github/borgand/marginalia/ui/render/MarkdownStructure.kt`
- Test: `src/test/kotlin/com/github/borgand/marginalia/ui/render/MarkdownStructureTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.borgand.marginalia.ui.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MarkdownStructureTest : BasePlatformTestCase() {

    private fun parse(text: String) =
        myFixture.configureByText("doc.md", text).also { /* PsiFile */ }

    fun `test heading level detected`() {
        val file = parse("# Title\n\n## Sub\n\nbody")
        val headings = MarkdownStructure.headings(file)
        assertEquals(listOf(1, 2), headings.map { it.level })
        // marker range covers the leading '#'(s); content range covers the text
        val h1 = headings[0]
        assertEquals("#", file.text.substring(h1.markerRange.startOffset, h1.markerRange.endOffset).trim())
        assertEquals("Title", file.text.substring(h1.contentRange.startOffset, h1.contentRange.endOffset).trim())
    }

    fun `test emphasis kinds`() {
        val file = parse("a **bold** b *italic* c ~~strike~~ d")
        val kinds = MarkdownStructure.emphasis(file).map { it.kind }.toSet()
        assertTrue(EmphasisKind.BOLD in kinds)
        assertTrue(EmphasisKind.ITALIC in kinds)
        assertTrue(EmphasisKind.STRIKETHROUGH in kinds)
    }

    fun `test inline link fold range is the destination part`() {
        val file = parse("see [docs](https://example.com/very/long) here")
        val link = MarkdownStructure.links(file).single()
        val folded = file.text.substring(link.foldRange.startOffset, link.foldRange.endOffset)
        assertEquals("](https://example.com/very/long)", folded)
        assertEquals("docs", file.text.substring(link.textRange.startOffset, link.textRange.endOffset))
    }

    fun `test mermaid fence detected, plain fence not`() {
        val file = parse("```mermaid\ngraph TD; A-->B;\n```\n\n```kotlin\nval x = 1\n```")
        val mermaids = MarkdownStructure.mermaidFences(file)
        assertEquals(1, mermaids.size)
        assertTrue(mermaids.single().code.contains("graph TD"))
    }

    fun `test horizontal rule, blockquote, list markers`() {
        val file = parse("> quote\n\n- one\n- two\n\n---\n")
        assertEquals(1, MarkdownStructure.blockquotes(file).size)
        assertEquals(2, MarkdownStructure.listMarkers(file).size)
        assertEquals(1, MarkdownStructure.horizontalRules(file).size)
    }

    fun `test task checkbox detected with checked state`() {
        val file = parse("- [ ] todo\n- [x] done\n")
        val tasks = MarkdownStructure.taskItems(file)
        assertEquals(listOf(false, true), tasks.map { it.checked })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.MarkdownStructureTest"`
Expected: FAIL — `MarkdownStructure` / `EmphasisKind` unresolved.

- [ ] **Step 3: Implement `MarkdownStructure`**

```kotlin
package com.github.borgand.marginalia.ui.render

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownCodeFence
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader

enum class EmphasisKind { BOLD, ITALIC, BOLD_ITALIC, STRIKETHROUGH }

data class HeadingSpan(val level: Int, val markerRange: TextRange, val contentRange: TextRange, val fullRange: TextRange)
data class EmphasisSpan(val kind: EmphasisKind, val range: TextRange)
data class LinkSpan(val textRange: TextRange, val foldRange: TextRange, val url: String)
data class ImageSpan(val lineRange: TextRange, val url: String, val alt: String)
data class MermaidSpan(val fenceRange: TextRange, val code: String)
data class HorizontalRuleSpan(val lineRange: TextRange)
data class BlockquoteSpan(val range: TextRange)
data class ListMarkerSpan(val markerRange: TextRange, val ordered: Boolean)
data class TaskItemSpan(val checkboxCharRange: TextRange, val checked: Boolean)

/**
 * The single place that knows org.intellij.plugins.markdown PSI. Pure, side-effect-free
 * accessors over a parsed markdown [PsiFile]. Callers must already hold a read lock
 * (annotators/folding/line-marker passes do).
 */
object MarkdownStructure {

    fun headings(file: PsiFile): List<HeadingSpan> =
        PsiTreeUtil.findChildrenOfType(file, MarkdownHeader::class.java).mapNotNull { header ->
            val level = header.level
            val markerNode = header.node.findChildByType(MarkdownTokenTypes.ATX_HEADER)
                ?: header.node.findChildByType(MarkdownTokenTypes.SETEXT_1)
                ?: header.node.findChildByType(MarkdownTokenTypes.SETEXT_2)
            val contentNode = header.node.findChildByType(MarkdownTokenTypes.ATX_CONTENT)
            val full = header.textRange
            HeadingSpan(
                level = level,
                markerRange = markerNode?.textRange ?: TextRange(full.startOffset, full.startOffset + level),
                contentRange = contentNode?.textRange ?: full,
                fullRange = full,
            )
        }

    fun emphasis(file: PsiFile): List<EmphasisSpan> {
        val out = mutableListOf<EmphasisSpan>()
        file.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element.node?.elementType) {
                    MarkdownElementTypes.STRONG ->
                        out += EmphasisSpan(emphasisKindOf(element) ?: EmphasisKind.BOLD, element.textRange)
                    MarkdownElementTypes.EMPH ->
                        out += EmphasisSpan(EmphasisKind.ITALIC, element.textRange)
                    MarkdownElementTypes.STRIKETHROUGH ->
                        out += EmphasisSpan(EmphasisKind.STRIKETHROUGH, element.textRange)
                }
                super.visitElement(element)
            }
        })
        return out
    }

    /** STRONG nested in EMPH (or vice-versa) → bold-italic; otherwise bold. */
    private fun emphasisKindOf(strong: PsiElement): EmphasisKind? {
        val parentIsEmph = PsiTreeUtil.getParentOfType(strong, PsiElement::class.java)
            ?.let { it.node?.elementType == MarkdownElementTypes.EMPH } == true
        return if (parentIsEmph) EmphasisKind.BOLD_ITALIC else EmphasisKind.BOLD
    }

    fun links(file: PsiFile): List<LinkSpan> {
        val out = mutableListOf<LinkSpan>()
        for (link in collectByType(file, MarkdownElementTypes.INLINE_LINK)) {
            val textNode = link.node.findChildByType(MarkdownElementTypes.LINK_TEXT) ?: continue
            val destNode = link.node.findChildByType(MarkdownElementTypes.LINK_DESTINATION) ?: continue
            // fold everything from just after the link text ']' through the closing ')'
            val foldStart = textNode.textRange.endOffset
            val foldEnd = link.textRange.endOffset
            out += LinkSpan(
                textRange = TextRange(textNode.textRange.startOffset + 1, textNode.textRange.endOffset - 1),
                foldRange = TextRange(foldStart, foldEnd),
                url = destNode.text,
            )
        }
        return out
    }

    fun images(file: PsiFile): List<ImageSpan> =
        collectByType(file, MarkdownElementTypes.IMAGE).map { img ->
            val dest = img.node.findChildByType(MarkdownElementTypes.LINK_DESTINATION)?.text ?: ""
            val alt = img.node.findChildByType(MarkdownElementTypes.LINK_TEXT)?.text?.trim('[', ']') ?: ""
            ImageSpan(lineRange = img.textRange, url = dest, alt = alt)
        }

    fun mermaidFences(file: PsiFile): List<MermaidSpan> =
        PsiTreeUtil.findChildrenOfType(file, MarkdownCodeFence::class.java)
            .filter { it.fenceLanguage?.equals("mermaid", ignoreCase = true) == true }
            .map { MermaidSpan(it.textRange, fenceContent(it)) }

    fun horizontalRules(file: PsiFile): List<HorizontalRuleSpan> =
        collectTokens(file, MarkdownTokenTypes.HORIZONTAL_RULE).map { HorizontalRuleSpan(it.textRange) }

    fun blockquotes(file: PsiFile): List<BlockquoteSpan> =
        collectByType(file, MarkdownElementTypes.BLOCK_QUOTE).map { BlockquoteSpan(it.textRange) }

    fun listMarkers(file: PsiFile): List<ListMarkerSpan> {
        val bullets = collectTokens(file, MarkdownTokenTypes.LIST_BULLET)
            .map { ListMarkerSpan(it.textRange, ordered = false) }
        val numbers = collectTokens(file, MarkdownTokenTypes.LIST_NUMBER)
            .map { ListMarkerSpan(it.textRange, ordered = true) }
        return bullets + numbers
    }

    /** Recognises `- [ ]` / `- [x]` at the start of a list item; range covers the single inner char. */
    fun taskItems(file: PsiFile): List<TaskItemSpan> {
        val out = mutableListOf<TaskItemSpan>()
        val text = file.text
        val regex = Regex("""(?m)^\s*[-*+]\s+\[( |x|X)]\s""")
        for (m in regex.findAll(text)) {
            val inner = m.groups[1]!!.range
            out += TaskItemSpan(
                checkboxCharRange = TextRange(inner.first, inner.last + 1),
                checked = !m.groupValues[1].isBlank(),
            )
        }
        return out
    }

    private fun fenceContent(fence: MarkdownCodeFence): String =
        fence.text.lineSequence().drop(1).toMutableList().also { lines ->
            if (lines.isNotEmpty() && lines.last().trimStart().startsWith("```")) lines.removeAt(lines.size - 1)
        }.joinToString("\n")

    private fun collectByType(file: PsiFile, type: com.intellij.psi.tree.IElementType): List<PsiElement> {
        val out = mutableListOf<PsiElement>()
        file.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element.node?.elementType == type) out += element
                super.visitElement(element)
            }
        })
        return out
    }

    private fun collectTokens(file: PsiFile, type: com.intellij.psi.tree.IElementType): List<PsiElement> =
        collectByType(file, type)
}
```

> **API note:** `MarkdownHeader.level`, `MarkdownCodeFence.fenceLanguage`, and the
> `MarkdownElementTypes`/`MarkdownTokenTypes` constants are from the bundled plugin. If a
> constant name fails to resolve, open the bundled plugin sources (Go to Declaration on
> `MarkdownElementTypes`) and use the actual name — the *shapes* are stable, only spellings
> drift. The `taskItems` regex is intentionally PSI-independent (task-list support is an
> optional plugin module).

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.MarkdownStructureTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/borgand/marginalia/ui/render/MarkdownStructure.kt \
        src/test/kotlin/com/github/borgand/marginalia/ui/render/MarkdownStructureTest.kt
git commit -m "feat: MarkdownStructure PSI accessor for render layer"
```

---

## Task 3: Color keys + Color Settings Page

**Files:**
- Create: `src/main/kotlin/com/github/borgand/marginalia/ui/render/MarginaliaTextAttributes.kt`
- Create: `src/main/kotlin/com/github/borgand/marginalia/ui/render/MarginaliaColorSettingsPage.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (extensions)
- Test: `src/test/kotlin/com/github/borgand/marginalia/ui/render/MarginaliaTextAttributesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.borgand.marginalia.ui.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MarginaliaTextAttributesTest : BasePlatformTestCase() {
    fun `test keys are distinct and externalised`() {
        val keys = listOf(
            MarginaliaTextAttributes.H1, MarginaliaTextAttributes.H2, MarginaliaTextAttributes.H3,
            MarginaliaTextAttributes.H4_6, MarginaliaTextAttributes.BLOCKQUOTE,
            MarginaliaTextAttributes.LIST_MARKER, MarginaliaTextAttributes.DIMMED_MARKER,
            MarginaliaTextAttributes.STRIKETHROUGH,
        )
        assertEquals(keys.size, keys.map { it.externalName }.toSet().size)
        assertTrue(keys.all { it.externalName.startsWith("MARGINALIA_") })
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.MarginaliaTextAttributesTest"`
Expected: FAIL — `MarginaliaTextAttributes` unresolved.

- [ ] **Step 3: Implement the keys**

```kotlin
package com.github.borgand.marginalia.ui.render

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

object MarginaliaTextAttributes {
    val H1: TextAttributesKey = key("MARGINALIA_H1", DefaultLanguageHighlighterColors.KEYWORD)
    val H2: TextAttributesKey = key("MARGINALIA_H2", DefaultLanguageHighlighterColors.NUMBER)
    val H3: TextAttributesKey = key("MARGINALIA_H3", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    val H4_6: TextAttributesKey = key("MARGINALIA_H4_6", DefaultLanguageHighlighterColors.METADATA)
    val BLOCKQUOTE: TextAttributesKey = key("MARGINALIA_BLOCKQUOTE", DefaultLanguageHighlighterColors.DOC_COMMENT)
    val LIST_MARKER: TextAttributesKey = key("MARGINALIA_LIST_MARKER", DefaultLanguageHighlighterColors.KEYWORD)
    val DIMMED_MARKER: TextAttributesKey = key("MARGINALIA_DIMMED_MARKER", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val STRIKETHROUGH: TextAttributesKey = key("MARGINALIA_STRIKETHROUGH", DefaultLanguageHighlighterColors.LINE_COMMENT)

    private fun key(name: String, fallback: TextAttributesKey) =
        TextAttributesKey.createTextAttributesKey(name, fallback)
}
```

- [ ] **Step 4: Implement the Color Settings Page**

Create `MarginaliaColorSettingsPage.kt`:

```kotlin
package com.github.borgand.marginalia.ui.render

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

class MarginaliaColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName() = "Marginalia"
    override fun getIcon(): Icon? = null
    override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor("Heading 1", MarginaliaTextAttributes.H1),
        AttributesDescriptor("Heading 2", MarginaliaTextAttributes.H2),
        AttributesDescriptor("Heading 3", MarginaliaTextAttributes.H3),
        AttributesDescriptor("Heading 4-6", MarginaliaTextAttributes.H4_6),
        AttributesDescriptor("Blockquote text", MarginaliaTextAttributes.BLOCKQUOTE),
        AttributesDescriptor("List marker", MarginaliaTextAttributes.LIST_MARKER),
        AttributesDescriptor("Dimmed syntax marker", MarginaliaTextAttributes.DIMMED_MARKER),
        AttributesDescriptor("Strikethrough", MarginaliaTextAttributes.STRIKETHROUGH),
    )

    override fun getDemoText(): String =
        "# Heading 1\n## Heading 2\n### Heading 3\n#### Heading 4\n\n" +
        "> a blockquote\n\n- list item\n\n~~struck out~~\n"
}
```

- [ ] **Step 5: Register the page in plugin.xml**

In the `<extensions defaultExtensionNs="com.intellij">` block of `plugin.xml`, add:

```xml
        <colorSettingsPage implementation="com.github.borgand.marginalia.ui.render.MarginaliaColorSettingsPage"/>
```

- [ ] **Step 6: Run tests + verify**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.MarginaliaTextAttributesTest" verifyPlugin`
Expected: PASS; `verifyPlugin` clean (only pre-existing experimental-API infos).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/borgand/marginalia/ui/render/MarginaliaTextAttributes.kt \
        src/main/kotlin/com/github/borgand/marginalia/ui/render/MarginaliaColorSettingsPage.kt \
        src/test/kotlin/com/github/borgand/marginalia/ui/render/MarginaliaTextAttributesTest.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat: render-layer color keys + color settings page"
```

---

## Task 4: `RenderSettings` — per-feature toggles

**Files:**
- Create: `src/main/kotlin/com/github/borgand/marginalia/ui/render/RenderSettings.kt`
- Test: `src/test/kotlin/com/github/borgand/marginalia/ui/render/RenderSettingsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.borgand.marginalia.ui.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RenderSettingsTest : BasePlatformTestCase() {
    fun `test defaults`() {
        val s = RenderSettings.getInstance()
        assertTrue(s.foldLinkUrls)
        assertTrue(s.foldFrontmatter)
        assertTrue(s.dimMarkers)
        assertTrue(s.bigTitles)
        assertTrue(s.renderTables)
        assertFalse(s.inlineImages) // off by default
    }

    fun `test state round-trips`() {
        val s = RenderSettings.getInstance()
        s.inlineImages = true
        assertTrue(RenderSettings.getInstance().state.inlineImages)
        s.inlineImages = false
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.RenderSettingsTest"`
Expected: FAIL — `RenderSettings` unresolved.

- [ ] **Step 3: Implement the settings service**

```kotlin
package com.github.borgand.marginalia.ui.render

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

class RenderState : BaseState() {
    var foldLinkUrls by property(true)
    var foldFrontmatter by property(true)
    var dimMarkers by property(true)
    var bigTitles by property(true)
    var renderTables by property(true)
    var inlineImages by property(false)
}

@State(name = "MarginaliaRender", storages = [Storage("marginalia.xml")])
class RenderSettings : SimplePersistentStateComponent<RenderState>(RenderState()) {

    var foldLinkUrls: Boolean
        get() = state.foldLinkUrls
        set(v) { state.foldLinkUrls = v }
    var foldFrontmatter: Boolean
        get() = state.foldFrontmatter
        set(v) { state.foldFrontmatter = v }
    var dimMarkers: Boolean
        get() = state.dimMarkers
        set(v) { state.dimMarkers = v }
    var bigTitles: Boolean
        get() = state.bigTitles
        set(v) { state.bigTitles = v }
    var renderTables: Boolean
        get() = state.renderTables
        set(v) { state.renderTables = v }
    var inlineImages: Boolean
        get() = state.inlineImages
        set(v) { state.inlineImages = v }

    companion object {
        fun getInstance(): RenderSettings =
            ApplicationManager.getApplication().getService(RenderSettings::class.java)
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.RenderSettingsTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/borgand/marginalia/ui/render/RenderSettings.kt \
        src/test/kotlin/com/github/borgand/marginalia/ui/render/RenderSettingsTest.kt
git commit -m "feat: render-layer per-feature settings"
```

---

## Task 5: `MarginaliaMarkdownAnnotator` — emphasis, headings, list markers

**Files:**
- Create: `src/main/kotlin/com/github/borgand/marginalia/ui/render/MarginaliaMarkdownAnnotator.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/github/borgand/marginalia/ui/render/MarginaliaMarkdownAnnotatorTest.kt`

- [ ] **Step 1: Write the failing test**

The fixture's `doHighlighting()` returns `HighlightInfo`s carrying the forced attributes key. We assert our keys land on the right text.

```kotlin
package com.github.borgand.marginalia.ui.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MarginaliaMarkdownAnnotatorTest : BasePlatformTestCase() {

    private fun keysOver(text: String): Map<String, String> {
        myFixture.configureByText("doc.md", text)
        val infos = myFixture.doHighlighting()
        val docText = myFixture.file.text
        return infos.filter { it.forcedTextAttributesKey != null }.associate {
            docText.substring(it.startOffset, it.endOffset) to it.forcedTextAttributesKey!!.externalName
        }
    }

    fun `test h1 and h2 get heading keys`() {
        val keys = keysOver("# One\n\n## Two\n")
        assertEquals("MARGINALIA_H1", keys.entries.first { it.key.contains("One") }.value)
        assertEquals("MARGINALIA_H2", keys.entries.first { it.key.contains("Two") }.value)
    }

    fun `test strikethrough key applied`() {
        val keys = keysOver("~~gone~~\n")
        assertTrue(keys.values.contains("MARGINALIA_STRIKETHROUGH"))
    }

    fun `test list marker key applied`() {
        val keys = keysOver("- item\n")
        assertTrue(keys.values.contains("MARGINALIA_LIST_MARKER"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.MarginaliaMarkdownAnnotatorTest"`
Expected: FAIL — annotator unresolved / no forced keys present.

- [ ] **Step 3: Implement the annotator**

```kotlin
package com.github.borgand.marginalia.ui.render

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Paints emphasis / headings / list markers as text attributes. Runs once per file element
 * on the FILE node (cheap: we collect via MarkdownStructure, which walks the already-parsed
 * tree) to keep the per-element dispatch trivial. Additive over the bundled plugin's own
 * highlighting.
 */
class MarginaliaMarkdownAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return // collect once at the file root
        val settings = RenderSettings.getInstance()

        for (h in MarkdownStructure.headings(element)) {
            val key = when (h.level) {
                1 -> MarginaliaTextAttributes.H1
                2 -> MarginaliaTextAttributes.H2
                3 -> MarginaliaTextAttributes.H3
                else -> MarginaliaTextAttributes.H4_6
            }
            paint(holder, h.contentRange, key)
            if (settings.dimMarkers) paint(holder, h.markerRange, MarginaliaTextAttributes.DIMMED_MARKER)
        }

        for (e in MarkdownStructure.emphasis(element)) {
            val key = when (e.kind) {
                EmphasisKind.STRIKETHROUGH -> MarginaliaTextAttributes.STRIKETHROUGH
                else -> null // bold/italic come from the bundled plugin's own attributes already
            } ?: continue
            paint(holder, e.range, key)
        }

        for (m in MarkdownStructure.listMarkers(element)) {
            paint(holder, m.markerRange, MarginaliaTextAttributes.LIST_MARKER)
        }
    }

    private fun paint(holder: AnnotationHolder, range: TextRange, key: TextAttributesKey) {
        if (range.isEmpty) return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(range)
            .textAttributes(key)
            .create()
    }
}
```

> **Note on bold/italic:** the bundled Markdown plugin already renders `**bold**`/`*italic*`
> with bold/italic font. We deliberately do NOT re-emit those (avoids double-styling per the
> spec §3) and only add what's missing — strikethrough — plus heading colors, marker color,
> and marker dimming. If on `runIde` bold/italic look unstyled in your theme, add
> `BOLD`/`ITALIC` keys here the same way.

- [ ] **Step 4: Register the annotator in plugin.xml**

Add to the extensions block:

```xml
        <annotator language="Markdown"
                   implementationClass="com.github.borgand.marginalia.ui.render.MarginaliaMarkdownAnnotator"/>
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.MarginaliaMarkdownAnnotatorTest"`
Expected: PASS (3 tests).

> If `forcedTextAttributesKey` is null in results, the platform applied them via
> `textAttributes(key)` as a key — adjust the assertion to read
> `it.forcedTextAttributesKey ?: it.type.attributesKey`. Keep the production code as-is.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/borgand/marginalia/ui/render/MarginaliaMarkdownAnnotator.kt \
        src/test/kotlin/com/github/borgand/marginalia/ui/render/MarginaliaMarkdownAnnotatorTest.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat: markdown annotator — headings, strikethrough, list markers"
```

---

## Task 6: `MarginaliaFoldingBuilder` — link URLs, frontmatter, HTML comments

**Files:**
- Create: `src/main/kotlin/com/github/borgand/marginalia/ui/render/MarginaliaFoldingBuilder.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/github/borgand/marginalia/ui/render/MarginaliaFoldingBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

`BasePlatformTestCase` supports `myFixture.testFolding(filePath)` but a programmatic check is simpler here:

```kotlin
package com.github.borgand.marginalia.ui.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MarginaliaFoldingBuilderTest : BasePlatformTestCase() {

    private fun foldedTexts(text: String): List<String> {
        myFixture.configureByText("doc.md", text)
        myFixture.doHighlighting()
        val editor = myFixture.editor
        return editor.foldingModel.allFoldRegions
            .filter { !it.isExpanded }
            .map { text.substring(it.startOffset, it.endOffset) }
    }

    fun `test link destination folds by default`() {
        val folds = foldedTexts("see [docs](https://example.com/x) ok\n")
        assertTrue(folds.any { it == "](https://example.com/x)" })
    }

    fun `test html comment folds by default`() {
        val folds = foldedTexts("a\n\n<!-- hidden note -->\n\nb\n")
        assertTrue(folds.any { it.contains("hidden note") })
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.MarginaliaFoldingBuilderTest"`
Expected: FAIL — builder unresolved / no fold regions.

- [ ] **Step 3: Implement the folding builder**

```kotlin
package com.github.borgand.marginalia.ui.render

import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes

class MarginaliaFoldingBuilder : FoldingBuilderEx() {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file = root as? PsiFile ?: return emptyArray()
        val settings = RenderSettings.getInstance()
        val out = mutableListOf<FoldingDescriptor>()

        if (settings.foldLinkUrls) {
            for (link in MarkdownStructure.links(file)) {
                if (!link.foldRange.isEmpty) {
                    out += FoldingDescriptor(file.node, link.foldRange, null, "]")
                }
            }
        }
        if (settings.foldFrontmatter) {
            // HTML comments: token range over <!-- ... -->
            for (comment in htmlComments(file)) {
                out += FoldingDescriptor(file.node, comment, null, "<!-- … -->")
            }
            frontmatter(file)?.let { out += FoldingDescriptor(file.node, it, null, "--- front matter ---") }
        }
        return out.toTypedArray()
    }

    override fun getPlaceholderText(node: com.intellij.lang.ASTNode): String = "…"

    override fun isCollapsedByDefault(node: com.intellij.lang.ASTNode): Boolean = true

    private fun htmlComments(file: PsiFile): List<TextRange> {
        val out = mutableListOf<TextRange>()
        val text = file.text
        Regex("""(?s)<!--.*?-->""").findAll(text).forEach { out += TextRange(it.range.first, it.range.last + 1) }
        return out
    }

    private fun frontmatter(file: PsiFile): TextRange? {
        val text = file.text
        if (!text.startsWith("---")) return null
        val end = text.indexOf("\n---", 3)
        if (end < 0) return null
        val close = text.indexOf('\n', end + 1).let { if (it < 0) text.length else it }
        return TextRange(0, close)
    }
}
```

> **Why per-builder, file-node descriptors:** `FoldingDescriptor(file.node, range, …)` lets us
> fold arbitrary sub-ranges (the `](url)` slice, a comment) without needing a dedicated PSI
> element per fold. `isCollapsedByDefault = true` gives fold-by-default; the platform
> auto-expands the region when the caret enters it — exactly the link/frontmatter UX we want.

- [ ] **Step 4: Register the builder in plugin.xml**

```xml
        <lang.foldingBuilder language="Markdown"
                             implementationClass="com.github.borgand.marginalia.ui.render.MarginaliaFoldingBuilder"/>
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.MarginaliaFoldingBuilderTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/borgand/marginalia/ui/render/MarginaliaFoldingBuilder.kt \
        src/test/kotlin/com/github/borgand/marginalia/ui/render/MarginaliaFoldingBuilderTest.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat: fold link URLs, frontmatter, HTML comments by default"
```

---

## Task 7: `MarkdownLineDecorator` — blockquote bar/tint + horizontal rule

Custom painting (a vertical bar, a full-width rule) isn't expressible as text attributes, so this mirrors Phase 1's `CommentHighlighter`: a project service that adds `RangeHighlighter`s with custom renderers and refreshes on document change.

**Files:**
- Create: `src/main/kotlin/com/github/borgand/marginalia/ui/render/MarkdownLineDecorator.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (project listener to install on file open) and `MarginaliaFileOpenListener`
- Test: `src/test/kotlin/com/github/borgand/marginalia/ui/render/MarkdownLineDecoratorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.borgand.marginalia.ui.render

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MarkdownLineDecoratorTest : BasePlatformTestCase() {

    fun `test highlighters added for blockquote and hr`() {
        myFixture.configureByText("doc.md", "> quote line\n\nbody\n\n---\n")
        val editor = myFixture.editor
        project.service<MarkdownLineDecorator>().refresh(editor)
        val ranges = editor.markupModel.allHighlighters
            .filter { it.customRenderer != null || it.lineMarkerRenderer != null }
        assertTrue("expected blockquote + hr decorations", ranges.size >= 2)
    }

    fun `test refresh is idempotent`() {
        myFixture.configureByText("doc.md", "> q\n\n---\n")
        val editor = myFixture.editor
        val dec = project.service<MarkdownLineDecorator>()
        dec.refresh(editor)
        val first = editor.markupModel.allHighlighters.count { it.customRenderer != null || it.lineMarkerRenderer != null }
        dec.refresh(editor)
        val second = editor.markupModel.allHighlighters.count { it.customRenderer != null || it.lineMarkerRenderer != null }
        assertEquals(first, second)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.MarkdownLineDecoratorTest"`
Expected: FAIL — `MarkdownLineDecorator` unresolved.

- [ ] **Step 3: Implement the decorator service**

```kotlin
package com.github.borgand.marginalia.ui.render

import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.ui.JBColor
import java.awt.Graphics

/**
 * Custom-painted line decorations (blockquote bar+tint, horizontal rule) that text
 * attributes cannot express. Mirrors the Phase 1 CommentHighlighter pattern: own the
 * RangeHighlighters we add, dispose+re-add on refresh. Markers are tagged so refresh()
 * only clears its own.
 */
@Service(Service.Level.PROJECT)
class MarkdownLineDecorator(private val project: Project) {

    private val key = "marginalia.line.decoration"

    fun refresh(editor: Editor) {
        val markup = editor.markupModel
        markup.allHighlighters
            .filter { it.getUserData(TAG) == key }
            .forEach { it.dispose() }

        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        if (!file.name.endsWith(".md", ignoreCase = true)) return

        for (bq in MarkdownStructure.blockquotes(file)) {
            add(editor, bq.range.startOffset, bq.range.endOffset, BlockquoteRenderer())
        }
        for (hr in MarkdownStructure.horizontalRules(file)) {
            add(editor, hr.lineRange.startOffset, hr.lineRange.endOffset, RuleRenderer())
        }
    }

    private fun add(editor: Editor, start: Int, end: Int, renderer: CustomHighlighterRenderer) {
        val hl: RangeHighlighter = editor.markupModel.addRangeHighlighter(
            start.coerceAtMost(editor.document.textLength),
            end.coerceAtMost(editor.document.textLength),
            HighlighterLayer.ADDITIONAL_SYNTAX,
            null,
            HighlighterTargetArea.EXACT_RANGE,
        )
        hl.customRenderer = renderer
        hl.putUserData(TAG, key)
    }

    private class BlockquoteRenderer : CustomHighlighterRenderer {
        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            val start = editor.offsetToXY(highlighter.startOffset)
            val end = editor.offsetToXY(highlighter.endOffset)
            val lineHeight = editor.lineHeight
            g.color = JBColor.GRAY
            val x = editor.offsetToXY(highlighter.startOffset).x + 2
            g.fillRect(x, start.y, 3, (end.y - start.y) + lineHeight)
        }
    }

    private class RuleRenderer : CustomHighlighterRenderer {
        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            val p = editor.offsetToXY(highlighter.startOffset)
            val width = editor.component.width
            val y = p.y + editor.lineHeight / 2
            g.color = JBColor.GRAY
            g.fillRect(p.x, y, width - p.x - 10, 1)
        }
    }

    companion object {
        private val TAG = com.intellij.openapi.util.Key.create<String>("marginalia.line.decoration.tag")
    }
}
```

- [ ] **Step 4: Install on file open + document change**

Modify `MarginaliaFileOpenListener` in `src/main/kotlin/com/github/borgand/marginalia/ui/editor/CommentHighlighter.kt` to also refresh line decorations. Add after the existing `refreshAll()` call in `fileOpened`:

```kotlin
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val project = source.project
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        project.service<CommentStore>().ensureAnchored(document, file.path)
        project.service<CommentHighlighter>().refreshAll()
        if (file.name.endsWith(".md", ignoreCase = true)) {
            source.getSelectedEditor(file)?.let { fe ->
                (fe as? com.intellij.openapi.fileEditor.TextEditor)?.editor?.let { ed ->
                    project.service<com.github.borgand.marginalia.ui.render.MarkdownLineDecorator>().refresh(ed)
                    com.github.borgand.marginalia.ui.render.MarkdownLineDecorator.attachDocumentListener(project, ed)
                }
            }
        }
    }
```

Add this companion helper to `MarkdownLineDecorator` (debounced refresh on edits, disposed with the editor):

```kotlin
    companion object {
        private val TAG = com.intellij.openapi.util.Key.create<String>("marginalia.line.decoration.tag")
        private val ATTACHED = com.intellij.openapi.util.Key.create<Boolean>("marginalia.line.decoration.listener")

        fun attachDocumentListener(project: Project, editor: Editor) {
            if (editor.getUserData(ATTACHED) == true) return
            editor.putUserData(ATTACHED, true)
            editor.document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
                override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                        if (!editor.isDisposed && !project.isDisposed) {
                            com.intellij.psi.PsiDocumentManager.getInstance(project).performWhenAllCommitted {
                                if (!editor.isDisposed) project.service<MarkdownLineDecorator>().refresh(editor)
                            }
                        }
                    }, { project.isDisposed })
                }
            }, editor.disposable)
        }
    }
```

(Remove the duplicate `TAG` declaration left from Step 3 — keep the single companion above.)

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.MarkdownLineDecoratorTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Run the full suite + verify**

Run: `./gradlew test verifyPlugin`
Expected: all tests pass (Phase 1's 49 + new); `verifyPlugin` clean.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/borgand/marginalia/ui/render/MarkdownLineDecorator.kt \
        src/test/kotlin/com/github/borgand/marginalia/ui/render/MarkdownLineDecoratorTest.kt \
        src/main/kotlin/com/github/borgand/marginalia/ui/editor/CommentHighlighter.kt
git commit -m "feat: blockquote bar/tint and horizontal-rule line painting"
```

---

## Task 8: Gutter popovers — images and Mermaid

**Files:**
- Create: `src/main/kotlin/com/github/borgand/marginalia/ui/render/gutter/ImageLineMarkerProvider.kt`
- Create: `src/main/kotlin/com/github/borgand/marginalia/ui/render/gutter/MermaidLineMarkerProvider.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/github/borgand/marginalia/ui/render/gutter/GutterMarkerTest.kt`

- [ ] **Step 1: Write the failing test**

We assert markers are produced on the right lines; the actual popup render is verified manually.

```kotlin
package com.github.borgand.marginalia.ui.render.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GutterMarkerTest : BasePlatformTestCase() {

    private fun markers(provider: com.intellij.codeInsight.daemon.LineMarkerProvider, text: String): List<LineMarkerInfo<*>> {
        val file = myFixture.configureByText("doc.md", text)
        val elements = PsiTreeUtil.collectElements(file) { true }.toList()
        val result = mutableListOf<LineMarkerInfo<*>>()
        provider.collectSlowLineMarkers(elements, result)
        return result
    }

    fun `test mermaid fence yields a gutter marker`() {
        val infos = markers(MermaidLineMarkerProvider(), "```mermaid\ngraph TD; A-->B;\n```\n")
        assertEquals(1, infos.size)
    }

    fun `test plain fence yields no mermaid marker`() {
        val infos = markers(MermaidLineMarkerProvider(), "```kotlin\nval x = 1\n```\n")
        assertEquals(0, infos.size)
    }

    fun `test image yields a gutter marker`() {
        val infos = markers(ImageLineMarkerProvider(), "![alt](https://example.com/a.png)\n")
        assertEquals(1, infos.size)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.gutter.GutterMarkerTest"`
Expected: FAIL — providers unresolved.

- [ ] **Step 3: Implement the image provider**

```kotlin
package com.github.borgand.marginalia.ui.render.gutter

import com.github.borgand.marginalia.ui.render.MarkdownStructure
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ui.JBUI
import java.net.URI
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JLabel

class ImageLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null // slow pass only

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = elements.firstOrNull()?.containingFile as? PsiFile ?: return
        if (!file.name.endsWith(".md", ignoreCase = true)) return
        // anchor each marker on the file's leaf at the image start to satisfy the "leaf element" rule
        for (img in MarkdownStructure.images(file)) {
            val leaf = file.findElementAt(img.lineRange.startOffset) ?: continue
            result += LineMarkerInfo(
                leaf, leaf.textRange,
                com.intellij.icons.AllIcons.FileTypes.Image,
                { "Preview image" },
                { _, _ -> showImage(img.url) },
                GutterIconRenderer.Alignment.LEFT,
                { "Preview image" },
            )
        }
    }

    private fun showImage(url: String) {
        val icon: Icon = try {
            if (url.startsWith("http")) ImageIcon(URI(url).toURL()) else ImageIcon(url)
        } catch (e: Exception) {
            JBUI.scale(1); return
        }
        val label = JLabel(icon)
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(label, label)
            .setResizable(true).setMovable(true).setRequestFocus(true)
            .createPopup()
            .showInFocusCenter()
    }
}
```

- [ ] **Step 4: Implement the Mermaid provider**

The diagram needs a real engine; render it with mermaid.js inside a `JBCefBrowser` that lives only inside the popup. Bundle `mermaid.min.js` into resources.

First add the asset: download `mermaid.min.js` (v11) into `src/main/resources/render/mermaid.min.js`.

```bash
mkdir -p src/main/resources/render
curl -sL https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js -o src/main/resources/render/mermaid.min.js
```

```kotlin
package com.github.borgand.marginalia.ui.render.gutter

import com.github.borgand.marginalia.ui.render.MarkdownStructure
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import java.awt.Dimension

class MermaidLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = elements.firstOrNull()?.containingFile as? PsiFile ?: return
        if (!file.name.endsWith(".md", ignoreCase = true)) return
        for (m in MarkdownStructure.mermaidFences(file)) {
            val leaf = file.findElementAt(m.fenceRange.startOffset) ?: continue
            result += LineMarkerInfo(
                leaf, leaf.textRange,
                com.intellij.icons.AllIcons.Actions.Preview,
                { "Render Mermaid diagram" },
                { _, _ -> showDiagram(m.code) },
                GutterIconRenderer.Alignment.LEFT,
                { "Render Mermaid diagram" },
            )
        }
    }

    private fun showDiagram(code: String) {
        if (!JBCefApp.isSupported()) return
        val browser = JBCefBrowser()
        browser.loadHTML(html(code))
        val panel = browser.component
        panel.preferredSize = Dimension(JBUI.scale(600), JBUI.scale(420))
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel)
            .setResizable(true).setMovable(true).setRequestFocus(true)
            .setTitle("Mermaid")
            .createPopup()
            .showInFocusCenter()
        // browser disposes with the popup component hierarchy
    }

    private fun mermaidJs(): String =
        javaClass.getResource("/render/mermaid.min.js")!!.readText()

    private fun html(code: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <style>body{margin:0;background:#fff}</style>
        <script>${mermaidJs()}</script></head>
        <body><pre class="mermaid">${code.replace("&", "&amp;").replace("<", "&lt;")}</pre>
        <script>mermaid.initialize({startOnLoad:true});</script>
        </body></html>
    """.trimIndent()
}
```

- [ ] **Step 5: Register both providers in plugin.xml**

```xml
        <codeInsight.lineMarkerProvider language="Markdown"
            implementationClass="com.github.borgand.marginalia.ui.render.gutter.ImageLineMarkerProvider"/>
        <codeInsight.lineMarkerProvider language="Markdown"
            implementationClass="com.github.borgand.marginalia.ui.render.gutter.MermaidLineMarkerProvider"/>
```

- [ ] **Step 6: Run to verify it passes**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.gutter.GutterMarkerTest" verifyPlugin`
Expected: PASS (3 tests); `verifyPlugin` clean. The `no new dependencies` rule is honored — JCEF and mermaid.min.js (a bundled static asset) are platform/in-repo, not new Gradle deps; note mermaid.min.js in the commit body.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/borgand/marginalia/ui/render/gutter/ \
        src/test/kotlin/com/github/borgand/marginalia/ui/render/gutter/GutterMarkerTest.kt \
        src/main/resources/render/mermaid.min.js \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat: gutter popovers for images and Mermaid (render engine in popup only)

Bundles mermaid.min.js (v11) as a static resource for on-demand diagram render
inside a JBCefBrowser popup; never runs on open or per edit."
```

> **MILESTONE: Tier 1 complete and shippable.** Run `./gradlew buildPlugin`, install the zip,
> open a `.md` file, and confirm the MarkEdit feel (styled headings, strikethrough, colored
> list markers, blockquote bar, folded link URLs/frontmatter, image + mermaid gutter icons).

---

## Task 9: Tier 2 — big-title custom fold (H1/H2)

`CustomFoldRegion` paints arbitrary content in place of folded line(s) and unfolds the instant the caret enters. We use it to render genuinely larger H1/H2 titles.

**Files:**
- Create: `src/main/kotlin/com/github/borgand/marginalia/ui/render/fold/CustomFoldController.kt`
- Create: `src/main/kotlin/com/github/borgand/marginalia/ui/render/fold/BigTitleFoldRenderer.kt`
- Modify: `CommentHighlighter.kt` `MarginaliaFileOpenListener` (install controller for `.md`)
- Test: `src/test/kotlin/com/github/borgand/marginalia/ui/render/fold/CustomFoldControllerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.borgand.marginalia.ui.render.fold

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CustomFoldControllerTest : BasePlatformTestCase() {

    fun `test h1 and h2 get custom folds when caret away`() {
        myFixture.configureByText("doc.md", "# Big One\n\n## Big Two\n\nbody text here\n")
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(editor.document.text.indexOf("body"))
        project.service<CustomFoldController>().refresh(editor)
        val custom = editor.foldingModel.allFoldRegions.filterIsInstance<com.intellij.openapi.editor.CustomFoldRegion>()
        assertEquals(2, custom.size)
    }

    fun `test caret on heading line leaves it unfolded`() {
        myFixture.configureByText("doc.md", "# Big One\n\nbody\n")
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(2) // inside "# Big One"
        project.service<CustomFoldController>().refresh(editor)
        val custom = editor.foldingModel.allFoldRegions.filterIsInstance<com.intellij.openapi.editor.CustomFoldRegion>()
        assertEquals(0, custom.size)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.fold.CustomFoldControllerTest"`
Expected: FAIL — controller unresolved.

- [ ] **Step 3: Implement the renderer**

```kotlin
package com.github.borgand.marginalia.ui.render.fold

import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D

/** Paints a single heading line as large bold text. Unfolds to source when caret enters. */
class BigTitleFoldRenderer(private val text: String, private val level: Int) : CustomFoldRegionRenderer {

    private fun scaledFont(base: Font): Font {
        val factor = if (level == 1) 1.9f else 1.5f
        return base.deriveFont(Font.BOLD, base.size * factor)
    }

    override fun calcWidthInPixels(region: CustomFoldRegion): Int {
        val editor = region.editor
        val font = scaledFont(editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN))
        val metrics = editor.contentComponent.getFontMetrics(font)
        return metrics.stringWidth(text) + 8
    }

    override fun calcHeightInPixels(region: CustomFoldRegion): Int {
        val editor = region.editor
        val font = scaledFont(editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN))
        return editor.contentComponent.getFontMetrics(font).height
    }

    override fun paint(region: CustomFoldRegion, g: Graphics2D, target: Rectangle2D, attributes: TextAttributes) {
        val editor = region.editor
        val font = scaledFont(editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN))
        g.font = font
        g.color = editor.colorsScheme.defaultForeground ?: JBColor.foreground()
        val fm = g.fontMetrics
        g.drawString(text, target.x.toInt() + 4, (target.y + fm.ascent).toInt())
    }
}
```

- [ ] **Step 4: Implement the controller**

```kotlin
package com.github.borgand.marginalia.ui.render.fold

import com.github.borgand.marginalia.ui.render.MarkdownStructure
import com.github.borgand.marginalia.ui.render.RenderSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

/**
 * Installs CustomFoldRegions for big H1/H2 titles. Skips any heading whose line currently
 * contains the caret (so it stays editable). Re-run on caret move + document change.
 */
@Service(Service.Level.PROJECT)
class CustomFoldController(private val project: Project) {

    fun refresh(editor: Editor) {
        if (!RenderSettings.getInstance().bigTitles) return
        val folding = editor.foldingModel as? FoldingModelEx ?: return
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        if (!file.name.endsWith(".md", ignoreCase = true)) return
        val doc = editor.document
        val caretLine = doc.getLineNumber(editor.caretModel.offset)

        folding.runBatchFoldingOperation {
            // remove our existing custom folds
            editor.foldingModel.allFoldRegions
                .filterIsInstance<CustomFoldRegion>()
                .filter { it.getUserData(TAG) == true }
                .forEach { folding.removeFoldRegion(it) }

            for (h in MarkdownStructure.headings(file)) {
                if (h.level > 2) continue
                val startLine = doc.getLineNumber(h.fullRange.startOffset)
                val endLine = doc.getLineNumber(h.fullRange.endOffset.coerceAtMost(doc.textLength - 1).coerceAtLeast(0))
                if (caretLine in startLine..endLine) continue
                val title = doc.getText(h.contentRange).trim()
                val region = folding.addCustomLinesFolding(
                    startLine, endLine,
                    BigTitleFoldRenderer(title, h.level),
                ) ?: continue
                region.putUserData(TAG, true)
            }
        }
    }

    companion object {
        private val TAG = com.intellij.openapi.util.Key.create<Boolean>("marginalia.bigtitle.fold")
    }
}
```

- [ ] **Step 5: Install controller on file open + caret/doc change**

In `MarginaliaFileOpenListener.fileOpened` (the `.md` branch added in Task 7), after the line-decorator refresh add:

```kotlin
                    project.service<com.github.borgand.marginalia.ui.render.fold.CustomFoldController>().refresh(ed)
                    com.github.borgand.marginalia.ui.render.fold.CustomFoldController
                        .attachListeners(project, ed)
```

Add to `CustomFoldController.companion`:

```kotlin
        private val ATTACHED = com.intellij.openapi.util.Key.create<Boolean>("marginalia.bigtitle.listener")

        fun attachListeners(project: Project, editor: Editor) {
            if (editor.getUserData(ATTACHED) == true) return
            editor.putUserData(ATTACHED, true)
            val refresh = {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                    if (!editor.isDisposed && !project.isDisposed) {
                        com.intellij.psi.PsiDocumentManager.getInstance(project).performWhenAllCommitted {
                            if (!editor.isDisposed) project.getService(CustomFoldController::class.java).refresh(editor)
                        }
                    }
                }, { project.isDisposed })
            }
            editor.caretModel.addCaretListener(object : com.intellij.openapi.editor.event.CaretListener {
                override fun caretPositionChanged(event: com.intellij.openapi.editor.event.CaretEvent) = refresh()
            }, editor.disposable)
            editor.document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
                override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) = refresh()
            }, editor.disposable)
        }
```

- [ ] **Step 6: Run to verify it passes**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.fold.CustomFoldControllerTest"`
Expected: PASS (2 tests).

> If `addCustomLinesFolding` returns null in tests (headless editor), guard the assertion
> with `JBCefApp`-style capability skips are NOT needed here, but custom folds require the
> editor to be a real `EditorImpl`; `myFixture.editor` is. If null persists, the heading
> spans a single line where start==end — verify `fullRange` covers the whole heading line.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/borgand/marginalia/ui/render/fold/CustomFoldController.kt \
        src/main/kotlin/com/github/borgand/marginalia/ui/render/fold/BigTitleFoldRenderer.kt \
        src/test/kotlin/com/github/borgand/marginalia/ui/render/fold/CustomFoldControllerTest.kt \
        src/main/kotlin/com/github/borgand/marginalia/ui/editor/CommentHighlighter.kt
git commit -m "feat: Tier 2 big-title custom-fold render for H1/H2"
```

---

## Task 10: Tier 2 — table custom fold (aligned grid)

**Files:**
- Create: `src/main/kotlin/com/github/borgand/marginalia/ui/render/fold/TableFoldRenderer.kt`
- Modify: `MarkdownStructure.kt` (add `tables(file)` returning the table line range + parsed rows)
- Modify: `CustomFoldController.kt` (install table folds alongside titles)
- Test: extend `CustomFoldControllerTest`

- [ ] **Step 1: Add the failing test (extend the controller test)**

Append to `CustomFoldControllerTest`:

```kotlin
    fun `test table gets a custom fold when caret away`() {
        myFixture.configureByText(
            "doc.md",
            "intro\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\noutro\n",
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(editor.document.text.indexOf("outro"))
        project.service<CustomFoldController>().refresh(editor)
        val custom = editor.foldingModel.allFoldRegions
            .filterIsInstance<com.intellij.openapi.editor.CustomFoldRegion>()
        // at least one custom fold is the table
        assertTrue(custom.isNotEmpty())
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.fold.CustomFoldControllerTest"`
Expected: FAIL — `MarkdownStructure.tables` unresolved.

- [ ] **Step 3: Add `tables()` to `MarkdownStructure`**

Add the data class and function to `MarkdownStructure.kt`:

```kotlin
data class TableSpan(val range: TextRange, val rows: List<List<String>>)
```

```kotlin
    fun tables(file: PsiFile): List<TableSpan> {
        val out = mutableListOf<TableSpan>()
        val text = file.text
        // a GFM table block: consecutive lines containing '|', with a separator row of ---
        val lines = text.split("\n")
        var offset = 0
        var i = 0
        while (i < lines.size) {
            val isHeader = lines[i].contains("|")
            val sep = i + 1 < lines.size && lines[i + 1].matches(Regex("""\s*\|?[\s:|-]*-[\s:|-]*\|?\s*"""))
            if (isHeader && sep) {
                val startOffset = offset
                var j = i
                var endOffset = offset
                val rows = mutableListOf<List<String>>()
                while (j < lines.size && lines[j].contains("|")) {
                    if (j != i + 1) rows += lines[j].trim().trim('|').split("|").map { it.trim() }
                    endOffset += lines[j].length + 1
                    j++
                }
                out += TableSpan(TextRange(startOffset, (endOffset - 1).coerceAtMost(text.length)), rows)
                // advance
                for (k in i until j) offset += lines[k].length + 1
                i = j
                continue
            }
            offset += lines[i].length + 1
            i++
        }
        return out
    }
```

- [ ] **Step 4: Implement the table renderer**

```kotlin
package com.github.borgand.marginalia.ui.render.fold

import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D

/** Paints a markdown table as an aligned grid; unfolds to pipe source on caret entry. */
class TableFoldRenderer(private val rows: List<List<String>>) : CustomFoldRegionRenderer {

    private val padX = 10
    private val padY = 4

    private fun cols() = rows.maxOfOrNull { it.size } ?: 0

    private fun colWidths(region: CustomFoldRegion): IntArray {
        val fm = region.editor.contentComponent.getFontMetrics(
            region.editor.colorsScheme.getFont(EditorFontType.PLAIN))
        val widths = IntArray(cols())
        for (row in rows) row.forEachIndexed { c, cell ->
            if (c < widths.size) widths[c] = maxOf(widths[c], fm.stringWidth(cell) + padX * 2)
        }
        return widths
    }

    override fun calcWidthInPixels(region: CustomFoldRegion): Int = colWidths(region).sum() + 1

    override fun calcHeightInPixels(region: CustomFoldRegion): Int =
        rows.size * (region.editor.lineHeight + padY)

    override fun paint(region: CustomFoldRegion, g: Graphics2D, target: Rectangle2D, attributes: TextAttributes) {
        val editor = region.editor
        g.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val fm = g.fontMetrics
        val widths = colWidths(region)
        val rowH = editor.lineHeight + padY
        g.color = JBColor.GRAY
        var y = target.y.toInt()
        for ((r, row) in rows.withIndex()) {
            var x = target.x.toInt()
            g.drawRect(target.x.toInt(), y, widths.sum(), rowH)
            for (c in 0 until cols()) {
                g.drawLine(x, y, x, y + rowH)
                val cell = row.getOrNull(c).orEmpty()
                g.color = editor.colorsScheme.defaultForeground ?: JBColor.foreground()
                if (r == 0) g.font = g.font.deriveFont(java.awt.Font.BOLD)
                g.drawString(cell, x + padX, y + fm.ascent + padY / 2)
                g.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
                g.color = JBColor.GRAY
                x += widths[c]
            }
            y += rowH
        }
    }
}
```

- [ ] **Step 5: Install table folds in the controller**

In `CustomFoldController.refresh`, inside `runBatchFoldingOperation`, after the heading loop add:

```kotlin
            if (RenderSettings.getInstance().renderTables) {
                for (t in MarkdownStructure.tables(file)) {
                    val startLine = doc.getLineNumber(t.range.startOffset)
                    val endLine = doc.getLineNumber(t.range.endOffset.coerceIn(0, doc.textLength - 1))
                    if (caretLine in startLine..endLine) continue
                    if (t.rows.isEmpty()) continue
                    val region = folding.addCustomLinesFolding(startLine, endLine, TableFoldRenderer(t.rows)) ?: continue
                    region.putUserData(TAG, true)
                }
            }
```

- [ ] **Step 6: Run to verify it passes**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.fold.CustomFoldControllerTest"`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/borgand/marginalia/ui/render/fold/TableFoldRenderer.kt \
        src/main/kotlin/com/github/borgand/marginalia/ui/render/MarkdownStructure.kt \
        src/main/kotlin/com/github/borgand/marginalia/ui/render/fold/CustomFoldController.kt \
        src/test/kotlin/com/github/borgand/marginalia/ui/render/fold/CustomFoldControllerTest.kt
git commit -m "feat: Tier 2 table custom-fold aligned grid render"
```

---

## Task 11: Tier 2 — inline image custom fold (off by default) + settings UI

**Files:**
- Create: `src/main/kotlin/com/github/borgand/marginalia/ui/render/fold/ImageFoldRenderer.kt`
- Modify: `CustomFoldController.kt` (install image folds when `inlineImages` on)
- Modify: `src/main/kotlin/com/github/borgand/marginalia/ui/MarginaliaConfigurable.kt` (toggles)
- Test: extend `CustomFoldControllerTest`

- [ ] **Step 1: Add the failing test**

```kotlin
    fun `test inline image fold only when enabled`() {
        myFixture.configureByText("doc.md", "text\n\n![a](https://example.com/a.png)\n\nmore\n")
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(0)
        val settings = com.github.borgand.marginalia.ui.render.RenderSettings.getInstance()
        settings.inlineImages = false
        project.service<CustomFoldController>().refresh(editor)
        val before = editor.foldingModel.allFoldRegions.filterIsInstance<com.intellij.openapi.editor.CustomFoldRegion>().size
        settings.inlineImages = true
        project.service<CustomFoldController>().refresh(editor)
        val after = editor.foldingModel.allFoldRegions.filterIsInstance<com.intellij.openapi.editor.CustomFoldRegion>().size
        settings.inlineImages = false
        assertTrue(after > before)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.github.borgand.marginalia.ui.render.fold.CustomFoldControllerTest"`
Expected: FAIL — `ImageFoldRenderer` unresolved / no extra fold.

- [ ] **Step 3: Implement the image renderer**

```kotlin
package com.github.borgand.marginalia.ui.render.fold

import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.ui.JBUI
import java.awt.Graphics2D
import java.awt.Image
import java.awt.geom.Rectangle2D
import java.net.URI
import javax.swing.ImageIcon

/** Paints an inline image (off by default; opt-in). Loads lazily, caps height. */
class ImageFoldRenderer(url: String) : CustomFoldRegionRenderer {

    private val image: Image? = runCatching {
        if (url.startsWith("http")) ImageIcon(URI(url).toURL()).image else ImageIcon(url).image
    }.getOrNull()

    private val maxH = JBUI.scale(240)

    private fun dims(): Pair<Int, Int> {
        val img = image ?: return JBUI.scale(120) to JBUI.scale(20)
        val w = img.getWidth(null).coerceAtLeast(1)
        val h = img.getHeight(null).coerceAtLeast(1)
        if (h <= maxH) return w to h
        val scale = maxH.toDouble() / h
        return (w * scale).toInt() to maxH
    }

    override fun calcWidthInPixels(region: CustomFoldRegion): Int = dims().first
    override fun calcHeightInPixels(region: CustomFoldRegion): Int = dims().second

    override fun paint(region: CustomFoldRegion, g: Graphics2D, target: Rectangle2D, attributes: TextAttributes) {
        val img = image ?: return
        val (w, h) = dims()
        g.drawImage(img, target.x.toInt(), target.y.toInt(), w, h, null)
    }
}
```

- [ ] **Step 4: Install image folds in the controller**

In `CustomFoldController.refresh`, inside `runBatchFoldingOperation`, after the table loop:

```kotlin
            if (RenderSettings.getInstance().inlineImages) {
                for (img in MarkdownStructure.images(file)) {
                    val startLine = doc.getLineNumber(img.lineRange.startOffset)
                    val endLine = doc.getLineNumber(img.lineRange.endOffset.coerceIn(0, doc.textLength - 1))
                    if (caretLine in startLine..endLine) continue
                    val region = folding.addCustomLinesFolding(
                        startLine, endLine,
                        com.github.borgand.marginalia.ui.render.fold.ImageFoldRenderer(img.url),
                    ) ?: continue
                    region.putUserData(TAG, true)
                }
            }
```

- [ ] **Step 5: Add the toggles to the Configurable**

Read `MarginaliaConfigurable.kt` first to match its existing `panel { }` DSL style, then add a "Markdown rendering" group binding each `RenderSettings` boolean to a `checkBox`. Example rows (place inside the existing `panel { }`):

```kotlin
        group("Markdown rendering") {
            row { checkBox("Fold link URLs").bindSelected(
                { RenderSettings.getInstance().foldLinkUrls }, { RenderSettings.getInstance().foldLinkUrls = it }) }
            row { checkBox("Fold frontmatter & HTML comments").bindSelected(
                { RenderSettings.getInstance().foldFrontmatter }, { RenderSettings.getInstance().foldFrontmatter = it }) }
            row { checkBox("Dim syntax markers").bindSelected(
                { RenderSettings.getInstance().dimMarkers }, { RenderSettings.getInstance().dimMarkers = it }) }
            row { checkBox("Render large H1/H2 titles").bindSelected(
                { RenderSettings.getInstance().bigTitles }, { RenderSettings.getInstance().bigTitles = it }) }
            row { checkBox("Render tables as grids").bindSelected(
                { RenderSettings.getInstance().renderTables }, { RenderSettings.getInstance().renderTables = it }) }
            row { checkBox("Render images inline (experimental)").bindSelected(
                { RenderSettings.getInstance().inlineImages }, { RenderSettings.getInstance().inlineImages = it }) }
        }
```

(Import `com.github.borgand.marginalia.ui.render.RenderSettings` and the UI-DSL `bindSelected`.)

- [ ] **Step 6: Run to verify it passes + full suite**

Run: `./gradlew test verifyPlugin`
Expected: all tests pass; `verifyPlugin` clean.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/borgand/marginalia/ui/render/fold/ImageFoldRenderer.kt \
        src/main/kotlin/com/github/borgand/marginalia/ui/render/fold/CustomFoldController.kt \
        src/main/kotlin/com/github/borgand/marginalia/ui/MarginaliaConfigurable.kt \
        src/test/kotlin/com/github/borgand/marginalia/ui/render/fold/CustomFoldControllerTest.kt
git commit -m "feat: Tier 2 opt-in inline image render + render settings UI"
```

---

## Task 12: Verify outline + update documentation

**Files:**
- Modify: `docs/main-prd.md` (§4 Phase 2, §9 M4–M5)
- Modify: `CLAUDE.md` (tech stack + architecture map)
- Modify: `docs/decisions.md` (D17–D19)
- Modify: `README.md`, `CHANGELOG.md`

- [ ] **Step 1: Verify the bundled Structure view (F13)**

Run `./gradlew runIde`, open a `.md` file, open **View → Tool Windows → Structure** (or `Cmd+7`). Confirm it shows the heading tree and jumps to headings on click. (No code change; this confirms F13 is satisfied by the bundled plugin.)

- [ ] **Step 2: Rewrite PRD Phase 2 + milestones**

In `docs/main-prd.md`, replace the §4 "Phase 2 — WYSIWYG markdown editor" block (F9–F13) with a description of the MarkEdit-style raw-rendering layer (Tier 1 attributes/folds/popovers, Tier 2 custom folds, Structure-view outline, no JCEF/Milkdown), and replace M4/M5 in §9 with: "M4 — Tier 1 rendering (attributes, folds, gutter popovers). M5 — Tier 2 custom folds (big titles, tables, optional inline images)." Reference the spec file.

- [ ] **Step 3: Fix CLAUDE.md**

In `CLAUDE.md`: delete the tech-stack bullet "Phase 2 frontend: Milkdown (ProseMirror) in JCEF; built with Vite into `src/main/resources/webview/` …". Add to the architecture map a `ui/render/` section listing the new components. Remove the `webview/` reference in the conventions/reference sections.

- [ ] **Step 4: Add decision entries**

Append to `docs/decisions.md`:

```markdown
## D17 — Phase 2 is MarkEdit-style raw rendering, not WYSIWYG
Supersedes PRD F9–F13. No JCEF/Milkdown/ProseMirror; one shared buffer; all features are
decorations over the bundled Markdown plugin PSI. Agent coupling preserved trivially (byte-
identical buffer). *Reversal cost: high (would re-open the whole WYSIWYG design).*

## D18 — Depend on the bundled Markdown plugin + platform extension points
Annotator/FoldingBuilder/LineMarkerProvider/CustomFoldRegion over org.intellij.plugins.markdown
PSI, rather than a standalone parser + manual markup. Idiomatic, incremental, customizable
via color keys. *Reversal cost: medium.*

## D19 — Mermaid & images via gutter popover; custom-fold for titles/tables/inline-images
Render engines (JCEF for mermaid, ImageIcon for images) run on demand inside popups only.
Custom-fold rendering reserved for reading-flow content (big H1/H2, tables, opt-in inline
images). Block inlays rejected (duplicate source). *Reversal cost: low (per-feature).*
```

- [ ] **Step 5: Update README + CHANGELOG**

In `README.md`, add a "Markdown rendering" feature paragraph (styled headings, emphasis, blockquotes, folded link URLs/frontmatter, image & mermaid gutter previews, big titles & table grids, Structure-view outline). In `CHANGELOG.md`, add an unreleased entry summarizing Phase 2.

- [ ] **Step 6: Final verification**

Run: `./gradlew test verifyPlugin buildPlugin`
Expected: all tests pass; `verifyPlugin` clean; `build/distributions/marginalia-0.8.0.zip` produced.

- [ ] **Step 7: Commit**

```bash
git add docs/main-prd.md CLAUDE.md docs/decisions.md README.md CHANGELOG.md
git commit -m "docs: rewrite Phase 2 for MarkEdit-style rendering; D17-D19; outline via Structure view"
```

---

## Self-Review (completed)

**Spec coverage:** Tier 1 emphasis/headings/list markers → Task 5; blockquote/HR → Task 7;
links/frontmatter/comments folding → Task 6; image/mermaid popovers → Task 8; color
customization → Task 3; settings toggles → Task 4 + Task 11; Tier 2 big titles → Task 9,
tables → Task 10, inline images → Task 11; outline (F13) → Task 12; PSI foundation → Task 2;
dependency wiring → Task 1; doc corrections → Task 12. All spec §5/§6/§8/§10 items mapped.

**Placeholder scan:** No TBD/TODO; every code step shows complete code; commands have
expected output. Task 11 Step 5 instructs reading `MarginaliaConfigurable.kt` first to match
its DSL — that is a real instruction (the file's exact current shape must be matched), not a
placeholder; the binding code is given.

**Type consistency:** `MarkdownStructure` span types (`HeadingSpan.fullRange/contentRange/
markerRange`, `EmphasisSpan.kind/range`, `LinkSpan.textRange/foldRange/url`, `ImageSpan.url/
lineRange`, `MermaidSpan.code/fenceRange`, `BlockquoteSpan.range`, `HorizontalRuleSpan.
lineRange`, `ListMarkerSpan.markerRange`, `TaskItemSpan.checked`, `TableSpan.range/rows`) are
defined in Tasks 2/10 and used consistently in Tasks 5/7/8/9/10/11. `RenderSettings.getInstance()`
+ boolean properties consistent across Tasks 4/6/9/10/11. `CustomFoldController.refresh(editor)`
and `.TAG` consistent across Tasks 9/10/11. `MarkdownLineDecorator.refresh(editor)` consistent
in Task 7. Color keys `MarginaliaTextAttributes.*` consistent across Tasks 3/5.
