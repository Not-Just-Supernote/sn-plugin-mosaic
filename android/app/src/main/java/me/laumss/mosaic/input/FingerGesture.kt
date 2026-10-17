package me.laumss.mosaic.input

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
import android.graphics.Region

internal class Finger(val id: Int, val downX: Float, val downY: Float, val downAt: Long, val penPriority: Boolean) {
    var x = downX
    var y = downY
}

internal enum class PanZoomLock { NONE, PAN, ZOOM }

internal sealed class FingerGesture {
    object Idle : FingerGesture()

    class CardPending(val fingerId: Int, val cardId: String) : FingerGesture()

    class TwoCandidate(val a: Int, val b: Int, val startedAt: Long) : FingerGesture()

    class PanZoom(
        val a: Int,
        val b: Int,
        var startPanX: Float,
        var startPanY: Float,
        var startScale: Float,
        var startCenterX: Float,
        var startCenterY: Float,
        var startDistance: Float,
        val region: SparseNavigation.Region?,
        val neighbors: SparseNavigation.Neighbors,
    ) : FingerGesture() {
        val cleanupTrail = Region()
        var frames = 0
        var overscroll: SparseNavigation.Overscroll? = null

        var lastCenterX = startCenterX
        var lastCenterY = startCenterY
        // In a note, the gesture locks to pan or zoom once one motion passes the slop; the board does both at once.
        var lock = PanZoomLock.NONE
    }

    object ScreenTool : FingerGesture()

    class Move(val fingerId: Int) : FingerGesture()

    class SurfaceScroll(val fingerId: Int, val startY: Float, val startPanY: Float) : FingerGesture()

    class EdgeSwipe(val fingerId: Int, val downX: Float, val downY: Float) : FingerGesture()
}
