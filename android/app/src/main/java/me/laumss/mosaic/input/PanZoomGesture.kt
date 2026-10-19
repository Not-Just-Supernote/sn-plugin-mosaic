package me.laumss.mosaic.input

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Two-finger pan and pinch zoom on the board and in a note, with zoom snapping and jumps to the next card group. */
internal class PanZoomGesture(private val board: BoardInteractionController) {
    companion object {
        private const val TAG_PAN = LogTags.PAN
        private const val ZOOM_SNAP_RATIO = 0.03f
        // Releasing a note pinch this close to 100% snaps back to the page filling the width.
        private const val NOTE_ZOOM_SNAP_RATIO = 0.08f
        private const val NOTE_PAN_ZOOM_LOCK_SLOP_DP = 12f
        private const val NOTE_SCROLL_EINK_OWNER = "note-scroll"
    }

    private val noteScrollResetTask = Runnable {
        MosaicEinkRefreshModule.resetNative(NOTE_SCROLL_EINK_OWNER)
    }

    private var armed = false

    fun resetNoteScrollMode() {
        board.handler.removeCallbacks(noteScrollResetTask)
        MosaicEinkRefreshModule.resetNative(NOTE_SCROLL_EINK_OWNER)
    }

    fun begin(a: Finger, b: Finger) {
        board.trailCleanup.cancel("panzoom-begin")
        val view = board.viewWorld()
        val activeRegion = board.currentRegions().let { SparseNavigation.findActiveRegion(it, view) }
        val neighbors = if (activeRegion == null) SparseNavigation.Neighbors.NONE else SparseNavigation.findNeighbors(board.currentRegions(), activeRegion)
        val g = FingerGesture.PanZoom(
            a.id, b.id,
            BoardViewport.panX, BoardViewport.panY, BoardViewport.scale,
            (a.x + b.x) / 2f, (a.y + b.y) / 2f, max(1f, hypot(b.x - a.x, b.y - a.y)),
            activeRegion, neighbors,
        )
        board.touch.fingerGesture = g
        armed = false

        if (board.surface.showsBoardChrome) {
            board.overlay.setLassoVisible(false)
            board.presentation.acquire(BoardPresentation.Reason.PAN_ZOOM)
        } else {
            board.handler.removeCallbacks(noteScrollResetTask)
            MosaicEinkRefreshModule.applyNative(MosaicEinkRefreshModule.MODE_A2, NOTE_SCROLL_EINK_OWNER)
        }
        arm()
        val bnd = activeRegion?.bounds
        val centerX = (view.centerX() / board.density - g.startPanX) / g.startScale
        val centerY = (view.centerY() / board.density - g.startPanY) / g.startScale
        Log.i(TAG_PAN, "begin surface=${board.surface.name} a=${a.id} b=${b.id} pan=(${g.startPanX},${g.startPanY}) centerWorld=($centerX,$centerY) scale=${g.startScale} region=${activeRegion != null} neighbors=${neighbors.any()} bounds=${bnd?.let { "(${it.left},${it.top},${it.right},${it.bottom})" }} worldView=(${view.width()},${view.height()})")
    }

    private fun arm() {
        val g = board.touch.fingerGesture as? FingerGesture.PanZoom ?: return
        resnap(g)
        if (!armed) {
            armed = true
            // Notes zoom too now, so they also scale the cached frame instead of re-rastering tiles every frame.
            board.content.setZoomPreview(true)

            board.content.setGestureFreezeTiles(true)
            board.content.setGestureThrottle(true)
            Log.i(TAG_PAN, "armed pan=(${g.startPanX},${g.startPanY}) scale=${g.startScale}")
        }
    }

    private fun resnap(g: FingerGesture.PanZoom) {
        val a = board.touch.fingers[g.a] ?: return
        val b = board.touch.fingers[g.b] ?: return
        g.startPanX = BoardViewport.panX
        g.startPanY = BoardViewport.panY
        g.startScale = BoardViewport.scale
        g.startCenterX = (a.x + b.x) / 2f
        g.startCenterY = (a.y + b.y) / 2f
        g.startDistance = max(1f, hypot(b.x - a.x, b.y - a.y))
    }

    fun update(g: FingerGesture.PanZoom) {
        if (!armed) return
        val a = board.touch.fingers[g.a] ?: return
        val b = board.touch.fingers[g.b] ?: return
        val d = board.density
        val cx = (a.x + b.x) / 2f
        val cy = (a.y + b.y) / 2f
        g.lastCenterX = cx
        g.lastCenterY = cy
        val dist = max(1f, hypot(b.x - a.x, b.y - a.y))
        if (!board.surface.showsBoardChrome && g.lock == PanZoomLock.NONE) {
            val spread = abs(dist - g.startDistance)
            val travel = hypot(cx - g.startCenterX, cy - g.startCenterY)
            val slop = NOTE_PAN_ZOOM_LOCK_SLOP_DP * d
            if (spread < slop && travel < slop) return
            g.lock = if (spread >= travel) PanZoomLock.ZOOM else PanZoomLock.PAN
            // Start from here so the slop already travelled does not jump the page.
            resnap(g)
            Log.i(TAG_PAN, "note gesture locked ${g.lock} spread=$spread travel=$travel")
            return
        }
        val ratio = if (g.lock == PanZoomLock.PAN) 1f else dist / g.startDistance
        val nextScale = board.surface.constrainScale(g.startScale * ratio, g.startScale)

        val startCxDp = g.startCenterX / d
        val startCyDp = g.startCenterY / d
        val worldCx = (startCxDp - g.startPanX) / g.startScale
        val worldCy = (startCyDp - g.startPanY) / g.startScale
        // A locked zoom stays anchored where it started instead of following the fingers.
        val anchorX = if (g.lock == PanZoomLock.ZOOM) startCxDp else cx / d
        val anchorY = if (g.lock == PanZoomLock.ZOOM) startCyDp else cy / d
        board.surface.constrainPan(anchorX - worldCx * nextScale, anchorY - worldCy * nextScale, nextScale, board.viewportTmp)

        val panX = board.snapPanToPx(board.viewportTmp[0])
        val panY = board.snapPanToPx(board.viewportTmp[1])

        g.overscroll = null
        if (panX != BoardViewport.panX || panY != BoardViewport.panY || nextScale != BoardViewport.scale) {
            g.cleanupNeeded = true
        }
        g.frames++
        BoardViewport.set(panX, panY, nextScale)
        if (g.frames % 10 == 0) {
            val centerX = (board.host.width / board.density / 2f - panX) / nextScale
            val centerY = (board.host.height / board.density / 2f - panY) / nextScale
            Log.i(TAG_PAN, "frame=${g.frames} centerWorld=($centerX,$centerY) scale=$nextScale pan=($panX,$panY)")
        }
    }

    private fun magneticZoom(scale: Float): Float {
        val level = BoardGeometry.ZOOM_LEVELS[BoardGeometry.nearestZoomIndex(scale)]
        return if (abs(level - scale) <= scale * ZOOM_SNAP_RATIO) level else scale
    }

    fun end(g: FingerGesture.PanZoom, cleanOldPositions: Boolean = true) {
        var panX = BoardViewport.panX
        var panY = BoardViewport.panY
        var scale = BoardViewport.scale

        val snapped = when {
            scale == g.startScale -> scale
            board.surface.showsBoardChrome -> magneticZoom(scale)
            abs(scale - board.notes.notePageScale) <= board.notes.notePageScale * NOTE_ZOOM_SNAP_RATIO -> board.notes.notePageScale
            else -> scale
        }
        if (snapped != scale) {
            val zoomLocked = g.lock == PanZoomLock.ZOOM
            val ax = (if (zoomLocked) g.startCenterX else g.lastCenterX) / board.density
            val ay = (if (zoomLocked) g.startCenterY else g.lastCenterY) / board.density
            val p = BoardGeometry.zoomAroundScreenPoint(panX, panY, scale, snapped, ax, ay)
            board.surface.constrainPan(p[0], p[1], snapped, board.viewportTmp)
            panX = board.snapPanToPx(board.viewportTmp[0]); panY = board.snapPanToPx(board.viewportTmp[1]); scale = snapped

            BoardViewport.set(panX, panY, scale)
            Log.i(TAG_PAN, "snap scale ${g.startScale}->$scale")
        }
        board.content.setZoomPreview(false)
        board.content.setGestureFreezeTiles(false)
        board.content.setGestureThrottle(false)
        board.overlay.setLassoVisible(true)
        val over = g.overscroll
        val region = g.region
        if (over != null && region != null && over.distancePx >= SparseNavigation.JUMP_TRIGGER_PX) {
            val target = when (over.direction) {
                SparseNavigation.Direction.LEFT -> g.neighbors.left
                SparseNavigation.Direction.RIGHT -> g.neighbors.right
                SparseNavigation.Direction.UP -> g.neighbors.up
                SparseNavigation.Direction.DOWN -> g.neighbors.down
            }
            if (target != null) {
                val centered = SparseNavigation.panToCenterRect(target.bounds, board.host.width / board.density, board.host.height / board.density, scale)
                panX = centered[0]
                panY = centered[1]
                Log.i(TAG_PAN, "jump ${over.direction} overscroll=${over.distancePx}")
            }
        }
        board.commitViewport(panX, panY, scale)
        board.selection.updateLassoOverlay()
        armed = false
        board.presentation.release(BoardPresentation.Reason.PAN_ZOOM)
        if (cleanOldPositions && g.cleanupNeeded) board.trailCleanup.schedule("pan-zoom")
        if (!board.surface.showsBoardChrome) {
            board.handler.removeCallbacks(noteScrollResetTask)
            board.handler.postDelayed(noteScrollResetTask, BoardInteractionController.NOTE_SCROLL_SETTLE_MS)
        }
        val centerX = (board.host.width / board.density / 2f - panX) / scale
        val centerY = (board.host.height / board.density / 2f - panY) / scale
        Log.i(TAG_PAN, "end frames=${g.frames} centerWorld=($centerX,$centerY) pan=($panX,$panY) scale=$scale")
    }
}
