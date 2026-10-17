package me.laumss.mosaic

import android.content.Intent
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot

/** Finger input: card long-press drags, taps on note cards and links, edge swipe, two-finger taps and tool holds, and handing pinches to the pan/zoom gesture. */
internal class TouchInput(private val board: BoardInteractionController) {
    companion object {
        private const val TAG_FINGER = "MosaicFinger"
        private const val TAG_TWO = "MosaicTwoFinger"
        const val TWO_FINGER_CANDIDATE_HOLD_MS = 300L
        const val TWO_FINGER_PAIR_WINDOW_MS = TwoFingerToolGuard.EVENT_TIME_DEV_MS
        const val CARD_FINGER_MOVE_HOLD_MS = 600L
        const val CARD_FINGER_MOVE_HOLD_FAST_MS = 400L
        const val CARD_FINGER_MOVE_HOLD_SELECTION_MS = 100L
        private const val NOTE_HEADER_PENDING = "#note-header"
        private const val NOTE_LINK_PENDING = "#note-link:"
        const val TWO_FINGER_TAP_MAX_MS = 300L
        const val TWO_FINGER_DOUBLE_TAP_GAP_MS = 400L
        const val TWO_FINGER_TAP_MOVE_SLOP_DP = 24f
        const val TWO_FINGER_DOUBLE_TAP_DIST_DP = 120f
        private const val EDGE_SWIPE_START_DP = 32f
        private const val EDGE_SWIPE_ZONE_DP = 72f
    }

    private val edgeSwipeStartPx: Float get() = EDGE_SWIPE_START_DP * board.density

    private val edgeSwipeZonePx: Float get() = EDGE_SWIPE_ZONE_DP * board.density

    val fingers = LinkedHashMap<Int, Finger>()

    var fingerGesture: FingerGesture = FingerGesture.Idle

    private var cardLongPressTask: Runnable? = null

    private var tapEpisodeActive = false

    private var tapEpisodeStartMs = 0L

    private var tapEpisodeMaxFingers = 0

    private var tapEpisodeMoved = false

    private var tapEpisodeCx = 0f

    private var tapEpisodeCy = 0f

    private val tapDownX = HashMap<Int, Float>()

    private val tapDownY = HashMap<Int, Float>()

    private var lastTwoFingerTapMs = 0L

    private var lastTwoFingerTapX = 0f

    private var lastTwoFingerTapY = 0f

    var fingerMoveSession: MoveSession? = null

    private var launcherPullStartY = Float.NaN

    private val twoCandidateTimeout = Runnable { settleTwoCandidate("hold") }

    fun viewportGestureActive(): Boolean =
        fingerGesture is FingerGesture.PanZoom || fingerGesture is FingerGesture.SurfaceScroll

    fun handle(frame: InputRouter.TouchFrame) {
        if (frame.action == InputRouter.ACTION_DOWN || frame.action == InputRouter.ACTION_POINTER_DOWN) board.trailCleanup.cancel("touch-down")
        if (maybeCloseFromLauncherPull(frame)) return
        trackToolbarToggleTap(frame)
        // Count raw contacts even when another finger lands on chrome or is
        // filtered out of the gesture's fingers map.
        if (frame.count > 1 && fingerGesture is FingerGesture.CardPending) {
            cancelCardLongPress()
            fingerGesture = FingerGesture.Idle
        }
        when (frame.action) {
            InputRouter.ACTION_DOWN, InputRouter.ACTION_POINTER_DOWN -> {
                board.host.inkHandoff.flushInkHandoff("touch")
                if (frame.action == InputRouter.ACTION_DOWN) board.refreshViewOffset()
                val i = frame.actionIndex
                fingerDown(frame.ids[i], frame.xs[i] - board.viewOffsetX, frame.ys[i] - board.viewOffsetY, frame.penPriority, frame.uptimeMs)
            }
            InputRouter.ACTION_MOVE -> {
                for (i in 0 until frame.count) {
                    val f = fingers[frame.ids[i]] ?: continue
                    f.x = frame.xs[i] - board.viewOffsetX
                    f.y = frame.ys[i] - board.viewOffsetY
                }
                fingerMove(frame.uptimeMs)
            }
            InputRouter.ACTION_UP, InputRouter.ACTION_POINTER_UP -> {
                val i = frame.actionIndex
                val id = frame.ids[i]
                fingers[id]?.let { it.x = frame.xs[i] - board.viewOffsetX; it.y = frame.ys[i] - board.viewOffsetY }
                fingerUp(id, cancelled = false)
            }
            InputRouter.ACTION_CANCEL -> {
                cancelFingerGestures("cancel")
            }
        }
    }

    private fun trackToolbarToggleTap(frame: InputRouter.TouchFrame) {
        val moveSlop = TWO_FINGER_TAP_MOVE_SLOP_DP * board.density
        when (frame.action) {
            InputRouter.ACTION_DOWN -> {
                tapEpisodeActive = true
                tapEpisodeStartMs = frame.uptimeMs
                tapEpisodeMaxFingers = frame.count
                tapEpisodeMoved = board.pen.penContact || board.pen.penHovering || frame.penPriority
                tapDownX.clear(); tapDownY.clear()
                for (i in 0 until frame.count) { tapDownX[frame.ids[i]] = frame.xs[i]; tapDownY[frame.ids[i]] = frame.ys[i] }
            }
            InputRouter.ACTION_POINTER_DOWN -> {
                if (!tapEpisodeActive) return
                tapEpisodeMaxFingers = maxOf(tapEpisodeMaxFingers, frame.count)
                if (board.pen.penContact || board.pen.penHovering || frame.penPriority) tapEpisodeMoved = true
                val i = frame.actionIndex
                tapDownX[frame.ids[i]] = frame.xs[i]; tapDownY[frame.ids[i]] = frame.ys[i]

                if (frame.count == 2) {
                    tapEpisodeCx = (frame.xs[0] + frame.xs[1]) / 2f
                    tapEpisodeCy = (frame.ys[0] + frame.ys[1]) / 2f
                }
            }
            InputRouter.ACTION_MOVE -> {
                if (!tapEpisodeActive) return
                for (i in 0 until frame.count) {
                    val sx = tapDownX[frame.ids[i]] ?: continue
                    val sy = tapDownY[frame.ids[i]] ?: continue
                    if (abs(frame.xs[i] - sx) > moveSlop || abs(frame.ys[i] - sy) > moveSlop) { tapEpisodeMoved = true; break }
                }
            }
            InputRouter.ACTION_POINTER_UP -> {
            }
            InputRouter.ACTION_UP -> {
                if (tapEpisodeActive) {
                    val dur = frame.uptimeMs - tapEpisodeStartMs
                    val qualifies = tapEpisodeMaxFingers == 2 && !tapEpisodeMoved && dur <= TWO_FINGER_TAP_MAX_MS
                    if (qualifies) onTwoFingerTap(frame.uptimeMs, tapEpisodeCx, tapEpisodeCy)
                }
                tapEpisodeActive = false
                tapDownX.clear(); tapDownY.clear()
            }
            InputRouter.ACTION_CANCEL -> {
                tapEpisodeActive = false
                tapDownX.clear(); tapDownY.clear()
            }
        }
    }

    private fun onTwoFingerTap(now: Long, cx: Float, cy: Float) {
        val gap = now - lastTwoFingerTapMs
        val near = hypot(cx - lastTwoFingerTapX, cy - lastTwoFingerTapY) <= TWO_FINGER_DOUBLE_TAP_DIST_DP * board.density
        if (lastTwoFingerTapMs != 0L && gap <= TWO_FINGER_DOUBLE_TAP_GAP_MS && near) {
            Log.i(TAG_TWO, "double-tap → toggle toolbar")
            board.chrome.toggleToolbar()
            lastTwoFingerTapMs = 0L
        } else {
            lastTwoFingerTapMs = now
            lastTwoFingerTapX = cx
            lastTwoFingerTapY = cy
        }
    }

    private fun fingerDown(id: Int, x: Float, y: Float, penPriority: Boolean, now: Long) {
        if (board.surfaceSwitching) return
        if (board.chrome.consumesPoint(x, y) && fingers.isEmpty()) {
            Log.i(TAG_FINGER, "DOWN id=$id ignored: chrome")
            return
        }

        if (board.inkling.toolbarHit(x, y)) {
            Log.i(TAG_FINGER, "DOWN id=$id ignored: inkling-toolbar")
            return
        }

        if (board.arbiter.hasOverride(ToolArbiter.Source.SLIDEBAR)) return

        if (board.pen.penContact) {
            Log.i(TAG_FINGER, "DOWN id=$id blocked: pen-session")
            return
        }
        val finger = Finger(id, x, y, now, penPriority)
        fingers[id] = finger
        Log.i(TAG_FINGER, "DOWN id=$id px=($x,$y) count=${fingers.size} penPriority=$penPriority gesture=${fingerGesture::class.simpleName}")

        if (fingers.size >= 2) {
            val (a, b) = firstTwo()

            cancelCardLongPress()
            fingerMoveSession?.let { board.transforms.cancelMoveSession(it); fingerMoveSession = null }
            if (fingerGesture is FingerGesture.SurfaceScroll) {
                board.content.setGestureThrottle(false)
                board.commitViewport(BoardViewport.panX, BoardViewport.panY, BoardViewport.scale)
            }

            if (fingerGesture is FingerGesture.PanZoom || fingerGesture is FingerGesture.ScreenTool) return
            if (fingers.size > 2) { fingerGesture = FingerGesture.Idle; return }
            if (!board.touchEnabled && board.gestureToolOf(board.settings.screenGesture) == null) { fingerGesture = FingerGesture.Idle; return }
            if (abs(a.downAt - b.downAt) > TWO_FINGER_PAIR_WINDOW_MS || board.gestureToolOf(board.settings.screenGesture) == null) {
                board.panZoom.begin(a, b)
            } else {
                fingerGesture = FingerGesture.TwoCandidate(a.id, b.id, now)
                board.handler.postDelayed(twoCandidateTimeout, TWO_FINGER_CANDIDATE_HOLD_MS)
                Log.i(TAG_TWO, "candidate a=${a.id} b=${b.id}")
            }
            return
        }

        if (penPriority) {
            fingerGesture = FingerGesture.Idle
            Log.i(TAG_FINGER, "single finger idle id=$id reason=pen-priority")
            return
        }

        if (board.selection.lasso == null && !board.selection.selectBelowArmed && board.surface.showsBoardChrome) {
            if (!board.chrome.switcherOpen && x <= edgeSwipeStartPx) {
                fingerGesture = FingerGesture.EdgeSwipe(id, x, y)
                Log.i(TAG_FINGER, "edge-swipe(open) armed id=$id x=$x")
                return
            }
        }

        if (board.notes.noteHeaderHit(x, y) && board.selection.lasso == null && !board.selection.selectBelowArmed) {
            fingerGesture = FingerGesture.CardPending(id, NOTE_HEADER_PENDING)
            cancelCardLongPress()
            val task = Runnable {
                cardLongPressTask = null
                val pending = fingerGesture as? FingerGesture.CardPending ?: return@Runnable
                if (pending.fingerId != id || pending.cardId != NOTE_HEADER_PENDING) return@Runnable
                fingerGesture = FingerGesture.Idle
                board.textEditing.editPageHeader()
            }
            cardLongPressTask = task
            board.handler.postDelayed(task, CARD_FINGER_MOVE_HOLD_MS)
            return
        }
        if (!board.touchEnabled) { fingerGesture = FingerGesture.Idle; return }
        val wx = board.worldX(x)
        val wy = board.worldY(y)

        if (board.surface.singleFingerScrolls && board.selection.lasso == null) {
            fingerGesture = FingerGesture.SurfaceScroll(id, y, BoardViewport.panY)
            board.content.setGestureThrottle(true)
            return
        }

        val currentLasso = board.selection.lasso

        val hadSelection = currentLasso != null
        if (currentLasso != null) {
            val single = if (board.selection.isSingleCardSelection(currentLasso)) BoardEngine.cards[currentLasso.cardIds[0]] else null
            if (single != null) {
                val handle = board.selection.singleCardFrameHandle(currentLasso, wx, wy)
                if (handle != null) {
                    fingerMoveSession = board.transforms.beginResize(false, id, wx, wy, single, handle)
                    fingerGesture = FingerGesture.Move(id)
                    return
                }
            }
            if (board.overlay.rotateHandleHit(x, y, BoardInteractionController.LASSO_ACTION_TAP_SLOP_DP * board.density) && board.selection.selectionRotatable(currentLasso)) {
                fingerMoveSession = board.transforms.beginSelectionRotate(false, id, wx, wy, currentLasso)
                fingerGesture = FingerGesture.Move(id)
                return
            }

            val cornerHandle = board.selection.selectionCornerHandle(currentLasso, wx, wy)
                ?: (if (board.selection.shapeKindOf(currentLasso) != null) board.selection.shapeEdgeHandle(currentLasso, wx, wy) else null)
            if (cornerHandle != null) {
                fingerMoveSession = board.transforms.beginSelectionResize(false, id, wx, wy, currentLasso, cornerHandle)
                fingerGesture = FingerGesture.Move(id)
                return
            }
            val action = board.overlay.lassoActionHit(x, y, BoardInteractionController.LASSO_ACTION_TAP_SLOP_DP * board.density)
            if (action != null) {
                board.selection.handleLassoAction(action)
                fingerGesture = FingerGesture.Idle
                return
            }
            if (board.selection.selectionHit(currentLasso, wx, wy) || board.overlay.moveHandleHit(x, y, BoardInteractionController.LASSO_ACTION_TAP_SLOP_DP * board.density)) {
                fingerMoveSession = board.transforms.beginSelectionMove(false, id, wx, wy, currentLasso)
                fingerGesture = FingerGesture.Move(id)
                return
            }
            board.selection.setLasso(null)
        }

        val linkId = board.noteShots.linkAt(wx, wy)
        if (linkId != null) {
            fingerGesture = FingerGesture.CardPending(id, NOTE_LINK_PENDING + linkId)
            cancelCardLongPress()
            return
        }
        val card = BoardGeometry.topCardAt(BoardEngine.cardsByZ, wx, wy)
        if (card == null) {
            fingerGesture = FingerGesture.Idle
            return
        }

        fingerGesture = FingerGesture.CardPending(id, card.id)
        cancelCardLongPress()
        val holdMs = when {
            hadSelection -> CARD_FINGER_MOVE_HOLD_SELECTION_MS
            board.presentation.isFlatActive -> CARD_FINGER_MOVE_HOLD_FAST_MS
            else -> CARD_FINGER_MOVE_HOLD_MS
        }
        val task = Runnable {
            cardLongPressTask = null
            val pending = fingerGesture as? FingerGesture.CardPending ?: return@Runnable
            if (pending.fingerId != id) return@Runnable
            val f = fingers[id] ?: return@Runnable
            val current = BoardEngine.cards[pending.cardId]
            if (current == null) { fingerGesture = FingerGesture.Idle; return@Runnable }

            board.selection.selectConnectedComponent(current)
            val sel = board.selection.lasso ?: return@Runnable
            fingerMoveSession = board.transforms.beginSelectionMove(false, id, board.worldX(f.x), board.worldY(f.y), sel)
            fingerGesture = FingerGesture.Move(id)
            Log.i(TAG_FINGER, "card hold → lasso move id=${current.id} holdMs=$holdMs")
        }
        cardLongPressTask = task
        board.handler.postDelayed(task, holdMs)
    }

    fun cancelCardLongPress() {
        cardLongPressTask?.let { board.handler.removeCallbacks(it) }
        cardLongPressTask = null
    }

    private fun fingerMove(now: Long) {
        when (val g = fingerGesture) {
            is FingerGesture.Move -> {
                val m = fingerMoveSession ?: return
                val f = fingers[m.pointerId] ?: return
                m.rawMoves++
                board.transforms.updateMoveSession(m, board.worldX(f.x), board.worldY(f.y))
            }
            is FingerGesture.CardPending -> {
                val f = fingers[g.fingerId] ?: return

                if (hypot(f.x - f.downX, f.y - f.downY) >= BoardGeometry.CARD_DRAG_THRESHOLD_PX * board.density) {
                    cancelCardLongPress()
                    fingerGesture = FingerGesture.Idle
                }
            }
            is FingerGesture.TwoCandidate -> {
                val a = fingers[g.a] ?: return
                val b = fingers[g.b] ?: return
                if (TwoFingerToolGuard.moved(a.downX, a.downY, a.x, a.y) || TwoFingerToolGuard.moved(b.downX, b.downY, b.x, b.y)) {
                    board.handler.removeCallbacks(twoCandidateTimeout)
                    Log.i(TAG_TWO, "candidate moved → pinch")

                    fallbackFromTwoCandidate(a, b, "moved")
                }
            }
            is FingerGesture.PanZoom -> board.panZoom.update(g)
            is FingerGesture.SurfaceScroll -> {
                val f = fingers[g.fingerId] ?: return
                val dy = (f.y - g.startY) / board.density
                board.surface.constrainPan(BoardViewport.panX, g.startPanY + dy, BoardViewport.scale, board.viewportTmp)
                BoardViewport.set(board.viewportTmp[0], board.snapPanToPx(board.viewportTmp[1]), BoardViewport.scale)
            }
            is FingerGesture.EdgeSwipe -> {
                val f = fingers[g.fingerId] ?: return
                val dx = f.x - g.downX
                val dy = f.y - g.downY

                if (abs(dy) > abs(dx)) return
                if (dx >= edgeSwipeZonePx) {
                    fingerGesture = FingerGesture.Idle
                    Log.i(TAG_FINGER, "edge-swipe(open) fired dx=$dx")
                    board.openSwitcher()
                }
            }
            else -> {}
        }
    }

    private fun fingerUp(id: Int, cancelled: Boolean) {
        val finger = fingers.remove(id)
        if (finger == null) return
        Log.i(TAG_FINGER, "UP id=$id remaining=${fingers.size} gesture=${fingerGesture::class.simpleName}")
        when (val g = fingerGesture) {
            is FingerGesture.Move -> {
                if (g.fingerId == id) {
                    cancelCardLongPress()
                    val m = fingerMoveSession
                    fingerMoveSession = null
                    fingerGesture = FingerGesture.Idle
                    if (m != null) {
                        if (cancelled) board.transforms.cancelMoveSession(m) else board.transforms.endMoveSession(m, board.worldX(finger.x), board.worldY(finger.y))
                    }
                }
            }
            is FingerGesture.CardPending -> {
                if (g.fingerId == id) {
                    cancelCardLongPress()
                    fingerGesture = FingerGesture.Idle
                    val card = BoardEngine.cards[g.cardId]
                    val singleFingerTap = !cancelled && tapEpisodeMaxFingers == 1 && fingers.isEmpty() &&
                        hypot(finger.x - finger.downX, finger.y - finger.downY) < BoardGeometry.CARD_DRAG_THRESHOLD_PX * board.density
                    if (singleFingerTap && g.cardId.startsWith(NOTE_LINK_PENDING)) {
                        board.noteShots.openLink(g.cardId.removePrefix(NOTE_LINK_PENDING))
                    } else if (singleFingerTap && card?.kind == "note" && card.noteRef.isNotEmpty()) {
                        board.notes.openNote(card.noteRef)
                    }
                }
            }
            is FingerGesture.SurfaceScroll -> if (g.fingerId == id) {
                fingerGesture = FingerGesture.Idle
                board.content.setGestureThrottle(false)
                board.commitViewport(BoardViewport.panX, BoardViewport.panY, BoardViewport.scale)
            }
            is FingerGesture.TwoCandidate -> {
                if (id == g.a || id == g.b) {
                    board.handler.removeCallbacks(twoCandidateTimeout)
                    fingerGesture = FingerGesture.Idle
                }
            }
            is FingerGesture.PanZoom -> {
                if (id == g.a || id == g.b) {
                    board.panZoom.end(g, cleanOldPositions = !cancelled)

                    if (fingers.size >= 2) {
                        val (a, b) = firstTwo()
                        board.panZoom.begin(a, b)
                    } else fingerGesture = FingerGesture.Idle
                }
            }
            FingerGesture.ScreenTool -> {
                if (fingers.isEmpty()) {
                    fingerGesture = FingerGesture.Idle
                    board.applyTransition(board.arbiter.releaseOverride(ToolArbiter.Source.SCREEN), "screen-release")
                    board.presentation.release(BoardPresentation.Reason.SCREEN_TOOL, linger = false)
                }
            }
            is FingerGesture.EdgeSwipe -> if (g.fingerId == id) fingerGesture = FingerGesture.Idle
            FingerGesture.Idle -> {}
        }
        if (fingers.isEmpty()) {
            fingerGesture = FingerGesture.Idle
            board.trailCleanup.resume("touch-up")
        }
    }

    private fun firstTwo(): Pair<Finger, Finger> {
        val it = fingers.values.iterator()
        return Pair(it.next(), it.next())
    }

    fun cancelFingerGestures(reason: String) {
        cancelCardLongPress()
        board.handler.removeCallbacks(twoCandidateTimeout)
        fingerMoveSession?.let { board.transforms.cancelMoveSession(it) }
        fingerMoveSession = null
        (fingerGesture as? FingerGesture.PanZoom)?.let { board.panZoom.end(it, cleanOldPositions = false) }
        if (fingerGesture === FingerGesture.ScreenTool) {
            board.applyTransition(board.arbiter.releaseOverride(ToolArbiter.Source.SCREEN), "screen-cancel")
            board.presentation.release(BoardPresentation.Reason.SCREEN_TOOL, linger = false)
        }
        fingerGesture = FingerGesture.Idle
        fingers.clear()
        Log.i(TAG_FINGER, "gestures cancelled reason=$reason")
    }

    private fun fallbackFromTwoCandidate(a: Finger, b: Finger, reason: String) {
        if (board.pen.penContact || board.pen.penHovering || a.penPriority || b.penPriority) {
            fingerGesture = FingerGesture.Idle
            Log.i(TAG_TWO, "candidate $reason → blocked by pen proximity")
            return
        }
        if (board.touchEnabled) board.panZoom.begin(a, b) else fingerGesture = FingerGesture.Idle
    }

    private fun adoptScreenTool(a: Finger, b: Finger, reason: String): Boolean {
        val tool = board.gestureToolOf(board.settings.screenGesture) ?: return false
        val decision = TwoFingerToolGuard.evaluate(
            a.x + board.viewOffsetX, a.y + board.viewOffsetY, b.x + board.viewOffsetX, b.y + board.viewOffsetY,
            if (board.pen.penHovering) board.pen.penHoverX + board.viewOffsetX else null,
            if (board.pen.penHovering) board.pen.penHoverY + board.viewOffsetY else null,
            if (board.pen.penHovering) InputReader.penTiltDirection else null,
            board.settings.leftHand,
            board.host.resources.displayMetrics.widthPixels.toFloat(),
            board.host.resources.displayMetrics.heightPixels.toFloat(),
        )
        Log.i(TAG_TWO, "decision accept=${decision.accept} reason=${decision.reason} tool=$tool route=${if (decision.accept) "tool" else "pinch"} source=$reason")
        if (!decision.accept) return false
        fingerGesture = FingerGesture.ScreenTool
        board.presentation.acquire(BoardPresentation.Reason.SCREEN_TOOL)
        board.applyTransition(board.arbiter.pushOverride(ToolArbiter.Source.SCREEN, tool, ToolArbiter.Settle.ON_PEN_UP), "screen-tool")
        return true
    }

    fun settleTwoCandidate(reason: String): Boolean {
        val g = fingerGesture as? FingerGesture.TwoCandidate ?: return false
        board.handler.removeCallbacks(twoCandidateTimeout)
        val a = fingers[g.a] ?: return false
        val b = fingers[g.b] ?: return false
        if (!adoptScreenTool(a, b, reason)) fallbackFromTwoCandidate(a, b, reason)
        return true
    }

    fun yieldPanZoomToPen(reason: String) {
        val g = fingerGesture as? FingerGesture.PanZoom ?: return
        val a = fingers[g.a]
        val b = fingers[g.b]
        board.panZoom.end(g, cleanOldPositions = false)
        fingerGesture = FingerGesture.Idle
        if (a == null || b == null) return
        if (!adoptScreenTool(a, b, reason)) {
            Log.i(PanZoomGesture.TAG_PAN, "panzoom yielded to pen: no screen tool reason=$reason")
        }
    }

    private fun maybeCloseFromLauncherPull(frame: InputRouter.TouchFrame): Boolean {
        if (frame.count != 1) { launcherPullStartY = Float.NaN; return false }
        val y = frame.ys[0]
        when (frame.action) {
            InputRouter.ACTION_DOWN -> if (y < 120f) {
                board.chrome.cancelTransientPresses()
                launcherPullStartY = y
                board.host.context.sendBroadcast(Intent("com.ratta.supernote.launcher.BroadcastReceiver.slidebarstatusbar").putExtra("lockStatusbar", true))
            }
            InputRouter.ACTION_UP -> {
                val start = launcherPullStartY
                launcherPullStartY = Float.NaN
                if (!start.isNaN() && y - start > 180f) {
                    Log.i(BoardInteractionController.TAG, "launcher top-pull close distance=${y - start} surface=${board.surface.name}")

                    if (!board.surfaceSwitching) board.surface.onCloseRequested(BoardSurface.CloseSource.LAUNCHER_PULL)
                    return true
                }
            }
        }
        return false
    }
}
