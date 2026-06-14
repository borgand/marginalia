package com.github.borgand.marginalia.ui.render

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Font

object MarginaliaTextAttributes {
    val H1: TextAttributesKey = key("MARGINALIA_H1", DefaultLanguageHighlighterColors.KEYWORD)
    val H2: TextAttributesKey = key("MARGINALIA_H2", DefaultLanguageHighlighterColors.NUMBER)
    val H3: TextAttributesKey = key("MARGINALIA_H3", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    val H4_6: TextAttributesKey = key("MARGINALIA_H4_6", DefaultLanguageHighlighterColors.METADATA)
    val BLOCKQUOTE: TextAttributesKey = key("MARGINALIA_BLOCKQUOTE", DefaultLanguageHighlighterColors.DOC_COMMENT)
    val LIST_MARKER: TextAttributesKey = key("MARGINALIA_LIST_MARKER", DefaultLanguageHighlighterColors.KEYWORD)
    val DIMMED_MARKER: TextAttributesKey = key("MARGINALIA_DIMMED_MARKER", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val STRIKETHROUGH: TextAttributesKey = key("MARGINALIA_STRIKETHROUGH", DefaultLanguageHighlighterColors.LINE_COMMENT)

    // Inline emphasis carries font style, not color (phase2 doc §"Inline emphasis"): default to
    // a bare fontType so the text inherits its surrounding color but gains bold/italic weight.
    val BOLD: TextAttributesKey = fontKey("MARGINALIA_BOLD", Font.BOLD)
    val ITALIC: TextAttributesKey = fontKey("MARGINALIA_ITALIC", Font.ITALIC)
    val BOLD_ITALIC: TextAttributesKey = fontKey("MARGINALIA_BOLD_ITALIC", Font.BOLD or Font.ITALIC)

    // Heading emphasis, layered over the H1/H2 color keys so headings pop even when the Tier-2
    // big-title custom fold can't apply (it conflicts with the Markdown plugin's section folds).
    // H1 also gets an underline; effect/font are user-tunable via the color settings page.
    val H1_STYLE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "MARGINALIA_H1_STYLE",
        TextAttributes(null, null, JBColor.GRAY, EffectType.LINE_UNDERSCORE, Font.BOLD or Font.ITALIC),
    )
    val H2_STYLE: TextAttributesKey = fontKey("MARGINALIA_H2_STYLE", Font.BOLD or Font.ITALIC)

    private fun key(name: String, fallback: TextAttributesKey) =
        TextAttributesKey.createTextAttributesKey(name, fallback)

    private fun fontKey(name: String, fontType: Int) =
        TextAttributesKey.createTextAttributesKey(
            name,
            TextAttributes(null, null, null, null, fontType),
        )
}
