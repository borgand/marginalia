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
