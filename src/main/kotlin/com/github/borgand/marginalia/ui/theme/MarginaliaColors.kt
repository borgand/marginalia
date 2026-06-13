package com.github.borgand.marginalia.ui.theme

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * Role-named design tokens (redesign §01). Every UI color in Marginalia dereferences a
 * member here — never a raw hex on a layer. Each token binds to the verbatim IntelliJ
 * Platform `api` from the design handoff (`docs/redesign/mg-tokens.jsx`) with the mock's
 * light/dark hexes as the [JBColor] fallback, so the whole UI follows the user's theme.
 *
 * All members are computed getters so they re-resolve on every access — a stored value
 * would freeze at the theme active when the object loaded and not follow a theme switch.
 *
 * Translucency is expressed as `token @ NN%` via [queuedTint] / [soft], never a new token.
 */
object MarginaliaColors {

    // ── the core six ──────────────────────────────────────────────────────────
    /** surface.toolWindow — tool window & its rows. */
    val surfaceToolWindow: Color get() = UIUtil.getPanelBackground()

    /** surface.editor — editor + overlays drawn on it (add-comment popup, gutter). */
    val surfaceEditor: Color get() = EditorColorsManager.getInstance().globalScheme.defaultBackground

    /** accent — links, active icons, delivered. */
    val accent: Color get() = JBUI.CurrentTheme.Link.Foreground.ENABLED

    /** status.pending — queued / pending text & marks. */
    val statusPending: Color get() = JBUI.CurrentTheme.Label.warningForeground()

    /** status.resolved — resolved / connected / success. */
    val statusResolved: JBColor get() = JBColor.namedColor("Banner.successBackground", JBColor(0x208A3C, 0x5FAD65))

    /** text.muted — secondary / metadata text. */
    val textMuted: JBColor get() = JBColor.namedColor("Label.infoForeground", JBColor(0x818594, 0x6F737A))

    // ── companions ──────────────────────────────────────────────────────────────
    /** text.primary — body / comment text. */
    val textPrimary: Color get() = UIUtil.getLabelForeground()

    /** accent.button — filled primary button bg. */
    val accentButton: Color get() = JBUI.CurrentTheme.Button.defaultButtonColorStart()

    /** text.onAccent — label on the filled button. */
    val textOnAccent: JBColor get() = JBColor.namedColor("Button.default.foreground", JBColor(0xFFFFFF, 0xFFFFFF))

    /** border — separators & card borders. */
    val border: JBColor get() = JBColor.namedColor("Component.borderColor", JBColor(0xEBECF0, 0x393B40))

    /** selection.bg — active / selected row. */
    val selectionBg: JBColor get() = JBColor.namedColor("List.selectionBackground", JBColor(0xD5E4FF, 0x2E436E))

    /** status.conflict — failed / delivery error (Marginalia: orphaned anchor). */
    val statusConflict: JBColor get() = JBColor.namedColor("Component.errorFocusColor", JBColor(0xE53E4D, 0xF75464))

    // ── derived (functions of a token, never new raw hex) ─────────────────────────
    /** status.pending @ 12% — the queued line tint drawn in the editor. */
    val queuedTint: Color get() = ColorUtil.withAlpha(statusPending, 0.12)

    /** A soft pill background: any status color @ 13%. */
    fun soft(base: Color): Color = ColorUtil.withAlpha(base, 0.13)
}
