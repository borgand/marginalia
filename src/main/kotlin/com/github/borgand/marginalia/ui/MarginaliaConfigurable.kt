package com.github.borgand.marginalia.ui

import com.github.borgand.marginalia.mcp.McpServerService
import com.github.borgand.marginalia.ui.render.RenderSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings > Tools > Marginalia. Gives a UI path to change the MCP server port and
 * restart it — so a failed auto-start (e.g. port clash) is never a dead end.
 */
class MarginaliaConfigurable : Configurable {

    private val server get() = service<McpServerService>()
    private val settings get() = service<MarginaliaSettings>()
    private val render get() = RenderSettings.getInstance()

    private val portField = JBTextField(10)
    private val statusLabel = JBLabel()
    private val captureSurfaceCombo = JComboBox(DefaultComboBoxModel(CaptureSurface.entries.toTypedArray()))
    private val foldLinkUrlsBox = JCheckBox("Fold link URLs")
    private val foldFrontmatterBox = JCheckBox("Fold frontmatter & HTML comments")
    private val dimMarkersBox = JCheckBox("Dim syntax markers")
    private val bigTitlesBox = JCheckBox("Render large H1/H2 titles")
    private val renderTablesBox = JCheckBox("Render tables as grids")
    private val inlineImagesBox = JCheckBox("Render images inline (experimental)")

    override fun getDisplayName(): String = "Marginalia"

    override fun createComponent(): JComponent {
        portField.text = server.port().toString()
        captureSurfaceCombo.selectedItem = settings.captureSurface
        captureSurfaceCombo.renderer = captureSurfaceRenderer()
        refreshStatus()
        foldLinkUrlsBox.isSelected = render.foldLinkUrls
        foldFrontmatterBox.isSelected = render.foldFrontmatter
        dimMarkersBox.isSelected = render.dimMarkers
        bigTitlesBox.isSelected = render.bigTitles
        renderTablesBox.isSelected = render.renderTables
        inlineImagesBox.isSelected = render.inlineImages
        val restartButton = JButton("Restart server").apply {
            addActionListener {
                applyPort()
                server.restart()
                refreshStatus()
            }
        }
        val panel: JPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("MCP server port:", portField)
            .addComponent(restartButton)
            .addComponent(statusLabel)
            .addLabeledComponent("New comment opens as:", captureSurfaceCombo)
            .addComponent(JBLabel("Markdown rendering:"))
            .addComponent(foldLinkUrlsBox)
            .addComponent(foldFrontmatterBox)
            .addComponent(dimMarkersBox)
            .addComponent(bigTitlesBox)
            .addComponent(renderTablesBox)
            .addComponent(inlineImagesBox)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        return panel
    }

    private fun captureSurfaceRenderer() =
        com.intellij.ui.SimpleListCellRenderer.create<CaptureSurface>("") {
            when (it) {
                CaptureSurface.INLINE -> "Inline popup at the line"
                CaptureSurface.DIALOG -> "Modal dialog"
            }
        }

    private fun refreshStatus() {
        statusLabel.text = "Status: ${server.status}"
    }

    private fun parsedPort(): Int? = portField.text.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    private fun applyPort() {
        val port = parsedPort()
        if (port == null) {
            Messages.showErrorDialog("Port must be a number between 1 and 65535.", "Marginalia")
            return
        }
        server.setPort(port)
    }

    override fun isModified(): Boolean =
        parsedPort() != server.port() ||
            captureSurfaceCombo.selectedItem != settings.captureSurface ||
            foldLinkUrlsBox.isSelected != render.foldLinkUrls ||
            foldFrontmatterBox.isSelected != render.foldFrontmatter ||
            dimMarkersBox.isSelected != render.dimMarkers ||
            bigTitlesBox.isSelected != render.bigTitles ||
            renderTablesBox.isSelected != render.renderTables ||
            inlineImagesBox.isSelected != render.inlineImages

    override fun apply() {
        applyPort()
        settings.captureSurface = captureSurfaceCombo.selectedItem as CaptureSurface
        render.foldLinkUrls = foldLinkUrlsBox.isSelected
        render.foldFrontmatter = foldFrontmatterBox.isSelected
        render.dimMarkers = dimMarkersBox.isSelected
        render.bigTitles = bigTitlesBox.isSelected
        render.renderTables = renderTablesBox.isSelected
        render.inlineImages = inlineImagesBox.isSelected
    }

    override fun reset() {
        portField.text = server.port().toString()
        captureSurfaceCombo.selectedItem = settings.captureSurface
        refreshStatus()
        foldLinkUrlsBox.isSelected = render.foldLinkUrls
        foldFrontmatterBox.isSelected = render.foldFrontmatter
        dimMarkersBox.isSelected = render.dimMarkers
        bigTitlesBox.isSelected = render.bigTitles
        renderTablesBox.isSelected = render.renderTables
        inlineImagesBox.isSelected = render.inlineImages
    }
}
