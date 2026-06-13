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
