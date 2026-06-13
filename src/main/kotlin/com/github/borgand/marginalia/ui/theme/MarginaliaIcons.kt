package com.github.borgand.marginalia.ui.theme

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Themed icon handles (redesign §05). Each is an SVG under `/icons`; IntelliJ serves the
 * matching `_dark` variant automatically, so these follow the IDE theme.
 */
object MarginaliaIcons {

    /** Tool-window stripe + header brand mark — the margin-rule glyph. */
    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/marginalia.svg", MarginaliaIcons::class.java)

    /** Gutter pen for a commented line. */
    @JvmField
    val Pen: Icon = IconLoader.getIcon("/icons/commentPen.svg", MarginaliaIcons::class.java)
}
