package me.laumss.mosaic.engine

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
/**
 * Where the board is seen from: world point (x, y) is drawn at (x * scale + panX, y * scale + panY) dp in the view.
 * An open note uses the same viewport for its page.
 */
object BoardViewport {
    @Volatile var panX = 0f
        private set
    @Volatile var panY = 0f
        private set
    @Volatile var scale = 1f
        private set

    fun set(nextPanX: Float, nextPanY: Float, nextScale: Float) {
        val s = if (nextScale > 0f) nextScale else 1f
        if (panX == nextPanX && panY == nextPanY && scale == s) return
        panX = nextPanX
        panY = nextPanY
        scale = s
        BoardEngine.onViewportChanged()
    }
}
