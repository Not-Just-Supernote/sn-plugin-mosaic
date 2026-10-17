package me.laumss.mosaic

import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Two-finger pan and pinch zoom on the board and in a note, with zoom snapping and jumps to the next card group. */
internal class PanZoomGesture(private val board: BoardInteractionController) {
    companion object {
        const val TAG_PAN = "MosaicPan"
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

    private fun recordCleanupPosition(g: FingerGesture.PanZoom) {
        if (!board.surface.showsBoardChrome) return
        val bounds = Rect()
        val px = RectF()
        val strokes = ArrayList<BoardEngine.StrokeRec>()
        synchronized(BoardEngine.lock) {
            for (card in BoardEngine.cards.values) {
                if (card.colored) continue
                board.worldRectToPx(BoardEngine.cardDirtyBounds(card), px).roundOut(bounds)
                if (bounds.intersect(0, 0, board.content.width, board.content.height)) g.cleanupTrail.op(bounds, Region.Op.UNION)
            }
            // Capture ink at the old viewport before each pan/zoom step, including
            // boards containing only handwriting. The driver selects settled white
            // pixels after the final frame, preserving ink at its new position.
            BoardEngine.queryCanvasStrokes(board.viewWorld(), strokes)
            for (stroke in strokes) {
                if (stroke.connectionId?.let { BoardEngine.necks[it]?.dark } == true) continue
                val ends = stroke.threadEnds
                if (ends != null && BoardEngine.cards[ends.first]?.colored == true && BoardEngine.cards[ends.second]?.colored == true) continue
                board.worldRectToPx(BoardEngine.strokeWorldBounds(stroke), px).roundOut(bounds)
                if (bounds.intersect(0, 0, board.content.width, board.content.height)) g.cleanupTrail.op(bounds, Region.Op.UNION)
            }
        }
    }

    fun begin(a: Finger, b: Finger) {
        board.trailCleanup.cancel("panzoom-begin")
        val view = board.viewWorld()
        val activeRegion = board.currentRegions().let { SparseNavigation.findActiveRegion(it, view) }
        val neighbors = if (activeRegion == null) SparseNavigation.Neighbors.NONE else SparseNavigation.findNeighbors(board.currentRegions(), activeRegion)
        val g = FingerGesture.PanZoom(
            a.id, b.id,
            BoardEngine.panX, BoardEngine.panY, BoardEngine.scale,
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
        g.startPanX = BoardEngine.panX
        g.startPanY = BoardEngine.panY
        g.startScale = BoardEngine.scale
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
        if (panX != BoardEngine.panX || panY != BoardEngine.panY || nextScale != BoardEngine.scale) {
            recordCleanupPosition(g)
        }
        g.frames++
        BoardEngine.setViewport(panX, panY, nextScale)
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
        if (cleanOldPositions && !g.cleanupTrail.isEmpty) recordCleanupPosition(g)
        var panX = BoardEngine.panX
        var panY = BoardEngine.panY
        var scale = BoardEngine.scale

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

            BoardEngine.setViewport(panX, panY, scale)
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
        if (cleanOldPositions) board.trailCleanup.schedule(g.cleanupTrail, "pan-zoom")
        if (!board.surface.showsBoardChrome) {
            board.handler.removeCallbacks(noteScrollResetTask)
            board.handler.postDelayed(noteScrollResetTask, BoardInteractionController.NOTE_SCROLL_SETTLE_MS)
        }
        val centerX = (board.host.width / board.density / 2f - panX) / scale
        val centerY = (board.host.height / board.density / 2f - panY) / scale
        Log.i(TAG_PAN, "end frames=${g.frames} centerWorld=($centerX,$centerY) pan=($panX,$panY) scale=$scale")
    }
}
