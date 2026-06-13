package com.github.borgand.marginalia.ui.render.fold

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.ui.JBUI
import java.awt.Graphics2D
import java.awt.Image
import java.awt.geom.Rectangle2D
import java.net.URI
import javax.swing.ImageIcon

/** Paints an inline image (off by default; opt-in). Best-effort load; placeholder on failure. */
class ImageFoldRenderer(url: String) : CustomFoldRegionRenderer {

    @Volatile private var image: Image? = null

    init {
        if (url.isNotEmpty()) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val img = runCatching {
                    if (url.startsWith("http")) ImageIcon(URI(url).toURL()).image else ImageIcon(url).image
                }.getOrNull()
                image = img
            }
        }
    }

    private val maxH = JBUI.scale(240)

    private fun dims(): Pair<Int, Int> {
        val img = image
        val w = img?.getWidth(null) ?: -1
        val h = img?.getHeight(null) ?: -1
        if (img == null || w <= 0 || h <= 0) return JBUI.scale(120) to JBUI.scale(20) // placeholder until loaded
        if (h <= maxH) return w to h
        val scale = maxH.toDouble() / h
        return (w * scale).toInt().coerceAtLeast(1) to maxH
    }

    override fun calcWidthInPixels(region: CustomFoldRegion): Int = dims().first.coerceAtLeast(1)
    override fun calcHeightInPixels(region: CustomFoldRegion): Int = dims().second.coerceAtLeast(1)

    override fun paint(region: CustomFoldRegion, g: Graphics2D, target: Rectangle2D, attributes: TextAttributes) {
        val img = image ?: return
        val (w, h) = dims()
        g.drawImage(img, target.x.toInt(), target.y.toInt(), w, h, null)
    }
}
