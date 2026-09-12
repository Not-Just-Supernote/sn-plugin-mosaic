package me.laumss.mosaic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import com.facebook.react.bridge.Arguments
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min


class BoardInteractionController(
    private val host: MosaicBoardView,
    private val content: BoardContentView,
    private val ink: TransientInkView,
    private val overlay: InteractionOverlayView,
    private val chrome: BoardChromeView,
    private val emitter: BoardCommandEmitter,
) : InputRouter.Sink, BoardChromeView.Listener {

    companion object {
        private const val TAG = "MosaicInteraction"
        private const val TAG_PEN = "MosaicPen"
        private const val TAG_FINGER = "MosaicFinger"
        private const val TAG_PAN = "MosaicPan"
        private const val TAG_LASSO = "MosaicLasso"
        private const val TAG_TOOL = "MosaicTool"
        private const val TAG_TWO = "MosaicTwoFinger"

        const val PEN_WIDTH_DP = 2f
        const val INK_BLACK = 0xff000000.toInt()
        const val ERASER_HIT_RADIUS = 12f

        
        const val ACCENT_BG_COLOR = "#FFF9E3"
        const val ACCENT_TEXT_COLOR = "#3f3b2e"

        const val CARD_FRAME_HOLD_MS = 500L
        const val CARD_FRAME_HOLD_MOVE_SLOP_DP = 3f
        const val CARD_FRAME_PREVIEW_CANCEL_MOVE_DP = 8f
        const val CARD_FRAME_CAPTURE_INK_RATIO = 0.70f

        
        const val TWO_FINGER_CANDIDATE_HOLD_MS = 100L
        
        const val TWO_FINGER_PAIR_WINDOW_MS = TwoFingerToolGuard.EVENT_TIME_DEV_MS

        const val LASSO_ACTION_TAP_SLOP_DP = 12f
        
        const val LASSO_BOUNDS_PAD = 10f
        private const val EINK_OWNER = "gesture"
        
        const val GESTURE_SETTLE_MS = 300L

        
        const val SHOT_DIR = "/sdcard/EXPORT/mosaic"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density: Float get() = host.resources.displayMetrics.density.coerceAtLeast(1f)

    

    private val arbiter = ToolArbiter()
    var settings: GestureSettings.Resolved = GestureSettings.Resolved.DEFAULT
        private set
    private val history = BoardHistory()

    var touchEnabled = true
        private set

    
    private var eraserMode = false
    private var lassoMode = false

    
    private var selectedCardId: String? = null

    class LassoSelection(val rect: RectF, val cardIds: List<String>, val strokeIds: List<String>)
    private var lasso: LassoSelection? = null

    
    private var penContact = false
    private var penHovering = false
    private var penHoverX = 0f
    private var penHoverY = 0f
    
    private var penLastX = 0f
    private var penLastY = 0f
    
    private var sliderTwoDown = false

    private sealed class PenSession {
        class Write(
            val id: String,
            val space: String,
            val cardId: String?,
            val cardX: Float,
            val cardY: Float,
        ) : PenSession() {
            val points = FloatArrayList()
            val pressures = FloatArrayList()
            
            var frameLastMeaningfulX = 0f
            var frameLastMeaningfulY = 0f
            var frameCandidate: RectF? = null
            var frameRecognizedX = 0f
            var frameRecognizedY = 0f
            var frameEvaluations = 0
            
            var convertFromIndex = -1
            
            var rawMinX = Float.MAX_VALUE
            var rawMinY = Float.MAX_VALUE
            var rawMaxX = -Float.MAX_VALUE
            var rawMaxY = -Float.MAX_VALUE
            fun raw(x: Float, y: Float) {
                if (x < rawMinX) rawMinX = x; if (x > rawMaxX) rawMaxX = x
                if (y < rawMinY) rawMinY = y; if (y > rawMaxY) rawMaxY = y
            }
        }

        class Erase(val change: BoardHistory.Change) : PenSession() {
            
            var pending = BoardHistory.Change("erase")
            val pendingIds = HashSet<String>()
        }
        class Lasso : PenSession() {
            val pointsPx = FloatArrayList()
        }
        object Consumed : PenSession()
    }

    private var penSession: PenSession? = null
    private var penMoveSession: MoveSession? = null

    
    private class Finger(val id: Int, val downX: Float, val downY: Float, val downAt: Long, val penPriority: Boolean) {
        var x = downX
        var y = downY
    }

    private val fingers = LinkedHashMap<Int, Finger>()

    private sealed class FingerGesture {
        object Idle : FingerGesture()
        class CardPending(val fingerId: Int, val cardId: String, val startWorldX: Float, val startWorldY: Float, val moveEnabled: Boolean) : FingerGesture()
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
            var frames = 0
            var overscroll: SparseNavigation.Overscroll? = null
        }
        object ScreenTool : FingerGesture()
        class Move(val fingerId: Int) : FingerGesture()
    }

    private var fingerGesture: FingerGesture = FingerGesture.Idle
    private var cardLongPressTask: Runnable? = null

    
    private sealed class MoveSession(val isPen: Boolean, val pointerId: Int, val startWorldX: Float, val startWorldY: Float) {
        var rawMoves = 0
        val startedAt = SystemClock.uptimeMillis()

        class CardDrag(isPen: Boolean, pointerId: Int, sx: Float, sy: Float, val card: BoardEngine.CardRec, val liftZ: Int) : MoveSession(isPen, pointerId, sx, sy) {
            var dragging = false
        }
        class CardResize(isPen: Boolean, pointerId: Int, sx: Float, sy: Float, val card: BoardEngine.CardRec, val handle: BoardGeometry.Handle) : MoveSession(isPen, pointerId, sx, sy) {
            val preview = RectF()
        }
        class Selection(isPen: Boolean, pointerId: Int, sx: Float, sy: Float, val selection: LassoSelection, val cards: List<BoardEngine.CardRec>, val strokes: List<BoardEngine.StrokeRec>) : MoveSession(isPen, pointerId, sx, sy) {
            var dx = 0f
            var dy = 0f
        }
    }

    private var fingerMoveSession: MoveSession? = null

    
    private var regionsDirty = true
    private var regions: List<SparseNavigation.Region> = emptyList()
    private var chromeUpdatePosted = false
    private var einkApplied = false
    private var whiteboardAnchorGuardUntil = 0L
    private var gestureSettleGen = 0
    private var panZoomArmed = false
    private val settleGestureTask = Runnable { settleGestureIdle() }

    
    private val viewWorldRect = RectF()
    private var viewOffsetX = 0
    private var viewOffsetY = 0
    private val tmpLoc = IntArray(2)

    

    fun attach() {
        InputRouter.sink = this
        chrome.listener = this
        settings = GestureSettings.read(host.context)
        Log.i(TAG, "settings ${settings.describe()}")
        GestureSettings.dumpCalibrationHints(host.context)
        InkAlign.start(host.context)
        applyStylusCalibration()
        arbiter.reset()
        syncToolMirrors(arbiter.effective(), "attach")
        chrome.setTouchEnabled(touchEnabled)
        scheduleChromeUpdate()
    }

    fun detach() {
        if (InputRouter.sink === this) InputRouter.sink = null
        chrome.listener = null
        InkAlign.stop()
        cancelCardLongPress()
        cancelFrameHold()
        penSession = null
        penMoveSession = null
        fingerMoveSession = null
        fingers.clear()
        fingerGesture = FingerGesture.Idle
        sliderTwoDown = false
        content.setZoomPreview(false)
        overlay.clearAll()
        cancelGestureSettle()
        content.clearSettleWaiters()
        content.setPreviewCardsWhite(false)
        content.setSuspendTranslucent(false)
        resetEink()
        handler.removeCallbacksAndMessages(null)
    }

    fun refreshSettings(reason: String) {
        settings = GestureSettings.read(host.context)
        Log.i(TAG, "settings refreshed reason=$reason ${settings.describe()}")
        applyStylusCalibration()
    }

    
    private fun applyStylusCalibration() {
        host.setStylusCalibration(settings.calibX, settings.calibY, settings.leftHand)
        InkAlign.setBaseline(0f, 0f)
    }

    fun setTouchEnabled(enabled: Boolean, fromJs: Boolean) {
        if (touchEnabled == enabled) return
        touchEnabled = enabled
        chrome.setTouchEnabled(enabled)
        if (!fromJs) emitter.action("touchEnabled") { putBoolean("value", enabled) }
    }

    
    fun onSceneOrViewportChanged(reason: String) {
        if (reason == "scene") regionsDirty = true
        scheduleChromeUpdate()
    }

    
    fun onDocumentReplaced() {
        history.clear()
        selectedCardId = null
        setLasso(null)
        regionsDirty = true
        scheduleChromeUpdate()
    }

    

    private fun worldX(px: Float): Float = (px / density - BoardEngine.panX) / BoardEngine.scale
    private fun worldY(px: Float): Float = (px / density - BoardEngine.panY) / BoardEngine.scale
    private fun screenX(world: Float): Float = (world * BoardEngine.scale + BoardEngine.panX) * density
    private fun screenY(world: Float): Float = (world * BoardEngine.scale + BoardEngine.panY) * density

    
    private fun penX(e: MotionEvent): Float = e.x + InkAlign.offsetX
    private fun penY(e: MotionEvent): Float = e.y + InkAlign.offsetY
    private fun penHistX(e: MotionEvent, i: Int): Float = e.getHistoricalX(i) + InkAlign.offsetX
    private fun penHistY(e: MotionEvent, i: Int): Float = e.getHistoricalY(i) + InkAlign.offsetY
    private fun worldRectToPx(r: RectF, out: RectF): RectF {
        out.set(screenX(r.left), screenY(r.top), screenX(r.right), screenY(r.bottom))
        return out
    }

    private fun viewWorld(): RectF =
        SparseNavigation.viewportWorldRect(
            BoardEngine.panX, BoardEngine.panY, BoardEngine.scale,
            host.width / density, host.height / density, viewWorldRect,
        )

    private fun refreshViewOffset() {
        host.getLocationOnScreen(tmpLoc)
        viewOffsetX = tmpLoc[0]
        viewOffsetY = tmpLoc[1]
    }

    

    
    private fun apply(change: BoardHistory.Change, record: Boolean, forward: Boolean = true) {
        if (change.isEmpty) return
        emitter.begin()
        try {
            BoardEngine.mutate {
                for (diff in change.diffs) {
                    when (diff) {
                        is BoardHistory.Diff.Stroke -> {
                            val target = if (forward) diff.after else diff.before
                            val source = if (forward) diff.before else diff.after
                            if (target == null) {
                                source?.let { removeStroke(it.id); emitter.strokesRemove(listOf(it.id)) }
                            } else {
                                addStroke(target); emitter.strokeUpsert(target)
                            }
                        }
                        is BoardHistory.Diff.Card -> {
                            val target = if (forward) diff.after else diff.before
                            val source = if (forward) diff.before else diff.after
                            if (target == null) {
                                source?.let { removeCard(it.id); emitter.cardsRemove(listOf(it.id)) }
                            } else {
                                upsertCard(target); emitter.cardUpsert(target)
                            }
                        }
                        is BoardHistory.Diff.Connection -> {
                            val target = if (forward) diff.after else diff.before
                            val source = if (forward) diff.before else diff.after
                            if (target == null) {
                                source?.let { removeConnection(it.id); emitter.connectionsRemove(listOf(it.id)) }
                            } else {
                                addConnection(target); emitter.connectionAdd(target)
                            }
                        }
                        is BoardHistory.Diff.Whiteboard -> {
                            val target = if (forward) diff.after else diff.before
                            val source = if (forward) diff.before else diff.after
                            if (target == null) {
                                source?.let { removeWhiteboard(it.id); emitter.whiteboardsRemove(listOf(it.id)) }
                            } else {
                                upsertWhiteboard(target); emitter.whiteboardUpsert(target)
                            }
                        }
                    }
                }
            }
        } finally {
            emitter.commit()
        }
        if (record) history.push(change)
        regionsDirty = true
        scheduleChromeUpdate()
    }

    fun undo() {
        val change = history.popUndo() ?: return
        cancelActiveInteractions("undo")
        apply(change, record = false, forward = false)
        Log.i(TAG, "undo ${change.label}")
    }

    fun redo() {
        val change = history.popRedo() ?: return
        cancelActiveInteractions("redo")
        apply(change, record = false, forward = true)
        Log.i(TAG, "redo ${change.label}")
    }

    private fun newId(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12)

    private fun commitViewport(panX: Float, panY: Float, scale: Float) {
        BoardEngine.setViewport(panX, panY, scale)
        emitter.viewport(panX, panY, scale)
        scheduleChromeUpdate()
    }

    

    private fun applyTransition(t: ToolArbiter.Transition?, source: String) {
        if (t == null) return
        Log.i(TAG_TOOL, "$source ${t.reason} ${t.before}->${t.after} keepSelection=${t.keepSelection} ${arbiter.describe()}")
        if (!t.changed) return
        
        val previous = penSession
        var discardedWrite: PenSession.Write? = null
        when (previous) {
            is PenSession.Write -> { cancelFrameHold(); overlay.hideFramePreview(); penSession = null; discardedWrite = previous }
            is PenSession.Erase -> { finishErase(previous); penSession = null }
            is PenSession.Lasso -> { penSession = null; ink.abortStroke() }
            else -> {}
        }
        if (!t.after.lasso && !t.keepSelection) setLasso(null)
        syncToolMirrors(t.after, source)
        
        discardedWrite?.let { discardWriteTrail(it, "tool-change:$source") }
        
        
        if (penContact && previous != null && previous !== PenSession.Consumed) {
            beginContactSession(t.after, discardedWrite, source)
        }
    }

    
    private fun beginContactSession(state: ToolArbiter.State, prevWrite: PenSession.Write?, source: String) {
        val wx = worldX(penLastX)
        val wy = worldY(penLastY)
        when {
            state.eraser -> {
                val session = PenSession.Erase(BoardHistory.Change("erase"))
                penSession = session
                var replayed = 0
                val from = prevWrite?.convertFromIndex ?: -1
                if (prevWrite != null && from >= 0) {
                    var i = from
                    while (i + 1 < prevWrite.points.size) {
                        eraseAt(session, prevWrite.points[i] + prevWrite.cardX, prevWrite.points[i + 1] + prevWrite.cardY)
                        i += 2
                        replayed++
                    }
                }
                eraseAt(session, wx, wy)
                flushErase(session)
                overlay.showEraserCursor(penLastX, penLastY, BoardEngine.scale)
                Log.i(TAG_PEN, "contact converted to eraser source=$source replayedPoints=$replayed")
            }
            state.lasso -> {
                val session = PenSession.Lasso()
                session.pointsPx.add(penLastX); session.pointsPx.add(penLastY)
                penSession = session
                Log.i(TAG_PEN, "contact converted to lasso source=$source")
            }
            else -> {
                
                if (selectedCardId != null || lasso != null) { penSession = PenSession.Consumed; return }
                val card = BoardGeometry.topCardAt(BoardEngine.cardsByZ, wx, wy)
                val session = PenSession.Write(
                    id = newId("stroke-"),
                    space = if (card == null) "canvas" else "card:${card.id}",
                    cardId = card?.id,
                    cardX = card?.x ?: 0f,
                    cardY = card?.y ?: 0f,
                )
                appendWritePoint(session, wx, wy, 1f)
                session.frameLastMeaningfulX = wx
                session.frameLastMeaningfulY = wy
                penSession = session
                Log.i(TAG_PEN, "contact converted to write source=$source")
            }
        }
    }

    
    private fun discardWriteTrail(s: PenSession.Write, reason: String) {
        if (s.points.size < 4) return
        host.onPenTrailDiscarded(reason)
    }

    private fun syncToolMirrors(state: ToolArbiter.State, source: String) {
        eraserMode = state.eraser
        lassoMode = state.lasso
        host.inkEnabled = !eraserMode && selectedCardId == null && lasso == null && !chrome.switcherOpen
        host.lassoEnabled = lassoMode && lasso == null
        if (!eraserMode) overlay.hideEraserCursor()
        if (eraserMode || (lassoMode && lasso == null)) requestEink(MosaicEinkRefreshModule.MODE_DUX)
    }

    private fun refreshHostToolFlags() = syncToolMirrors(arbiter.effective(), "flags")

    private fun gestureToolOf(tool: GestureSettings.GestureTool): ToolArbiter.Tool? = when (tool) {
        GestureSettings.GestureTool.ERASER -> ToolArbiter.Tool.ERASER
        GestureSettings.GestureTool.LASSO -> ToolArbiter.Tool.LASSO
        GestureSettings.GestureTool.OFF -> null
    }

    

    private fun requestEink(mode: Int) {
        einkApplied = true
        MosaicEinkRefreshModule.applyNative(mode, EINK_OWNER)
    }

    private fun resetEink() {
        if (!einkApplied) return
        einkApplied = false
        MosaicEinkRefreshModule.resetNative(EINK_OWNER)
    }

    
    private fun holdGestureEink(mode: Int) {
        cancelGestureSettle()
        requestEink(mode)
    }

    
    private fun scheduleGestureSettle() {
        handler.removeCallbacks(settleGestureTask)
        handler.postDelayed(settleGestureTask, GESTURE_SETTLE_MS)
    }

    private fun cancelGestureSettle() {
        gestureSettleGen += 1
        handler.removeCallbacks(settleGestureTask)
    }

    private fun settleGestureIdle() {
        
        if (fingerGesture is FingerGesture.PanZoom || fingerGesture === FingerGesture.ScreenTool || sliderTwoDown) return
        val gen = gestureSettleGen + 1
        gestureSettleGen = gen
        fun finish() {
            if (gen != gestureSettleGen) return
            resetEink()
        }
        val wasPreview = content.previewCardsWhite
        val wasSuspend = content.suspendTranslucent
        if (wasPreview) content.setPreviewCardsWhite(false)
        if (wasSuspend) content.setSuspendTranslucent(false)
        if (wasPreview || wasSuspend) {
            Log.i(TAG_PAN, "settle: restore accent/translucent then reset eink")
        } else {
            Log.i(TAG_PAN, "settle: reset eink")
        }
        finish()
    }

    private fun hasColoredCards(): Boolean {
        synchronized(BoardEngine.lock) {
            for (card in BoardEngine.cards.values) if (card.colored) return true
        }
        return false
    }

    

    
    fun onPen(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> penDown(event)
            MotionEvent.ACTION_MOVE -> penMove(event)
            MotionEvent.ACTION_UP -> penUp(event, cancelled = false)
            MotionEvent.ACTION_CANCEL -> penUp(event, cancelled = true)
        }
    }

    fun onPenHover(event: MotionEvent) {
        val exit = event.actionMasked == MotionEvent.ACTION_HOVER_EXIT
        penHoverX = penX(event)
        penHoverY = penY(event)
        if (exit) overlay.hideEraserCursor()
        else if (eraserMode && !penContact) overlay.showEraserCursor(penHoverX, penHoverY, BoardEngine.scale)
    }

    private fun penDown(e: MotionEvent) {
        val x = penX(e)
        val y = penY(e)
        penLastX = x
        penLastY = y
        
        
        when (fingerGesture) {
            FingerGesture.ScreenTool -> {}
            is FingerGesture.TwoCandidate -> {
                if (!settleTwoCandidate("pen-down")) cancelFingerGestures("pen-down")
            }
            else -> cancelFingerGestures("pen-down")
        }
        if (chrome.consumesPoint(x, y)) {
            Log.i(TAG_PEN, "DOWN rejected: chrome")
            penSession = PenSession.Consumed
            return
        }
        penContact = true
        applyTransition(arbiter.penDown(), "pen-down")
        val wx = worldX(x)
        val wy = worldY(y)
        Log.i(
            TAG_PEN,
            "DOWN px=($x,$y) raw=(${e.x},${e.y}) inkAlign=(${InkAlign.offsetX},${InkAlign.offsetY}) " +
                "world=(${wx.toInt()},${wy.toInt()}) eraser=$eraserMode lasso=$lassoMode " +
                "${arbiter.describe()} | ${InputReader.describePenRaw()} density=$density " +
                "pan=(${BoardEngine.panX},${BoardEngine.panY}) scale=${BoardEngine.scale}",
        )

        
        val selected = selectedCardId?.let { BoardEngine.cards[it] }
        if (selected != null) {
            val handle = BoardGeometry.handleAt(selected, wx, wy, BoardEngine.scale)
            if (handle != null) {
                penMoveSession = beginResize(true, e.getPointerId(0), wx, wy, selected, handle)
                penSession = PenSession.Consumed
                return
            }
            finishCardAdjustment("pen-down")
            penSession = PenSession.Consumed
            return
        }

        
        val currentLasso = lasso
        if (currentLasso != null) {
            if (currentLasso.cardIds.isEmpty() && overlay.lassoActionHit(x, y, LASSO_ACTION_TAP_SLOP_DP * density)) {
                deleteLassoSelection()
                penSession = PenSession.Consumed
                return
            }
            if (selectionHit(currentLasso, wx, wy)) {
                penMoveSession = beginSelectionMove(true, e.getPointerId(0), wx, wy, currentLasso)
                penSession = PenSession.Consumed
                return
            }
            setLasso(null)
            penSession = PenSession.Consumed
            Log.i(TAG_LASSO, "pen DOWN outside selection: cleared")
            return
        }

        if (eraserMode) {
            val session = PenSession.Erase(BoardHistory.Change("erase"))
            penSession = session
            eraseAt(session, wx, wy)
            flushErase(session)
            overlay.showEraserCursor(x, y, BoardEngine.scale)
            return
        }

        if (lassoMode) {
            val session = PenSession.Lasso()
            session.pointsPx.add(x); session.pointsPx.add(y)
            penSession = session
            return
        }

        val card = BoardGeometry.topCardAt(BoardEngine.cardsByZ, wx, wy)
        val session = PenSession.Write(
            id = newId("stroke-"),
            space = if (card == null) "canvas" else "card:${card.id}",
            cardId = card?.id,
            cardX = card?.x ?: 0f,
            cardY = card?.y ?: 0f,
        )
        appendWritePoint(session, wx, wy, e.pressure)
        session.raw(e.x, e.y)
        session.frameLastMeaningfulX = wx
        session.frameLastMeaningfulY = wy
        
        if (sliderTwoDown) session.convertFromIndex = 0
        penSession = session
    }

    private fun appendWritePoint(s: PenSession.Write, wx: Float, wy: Float, pressure: Float) {
        s.points.add(wx - s.cardX)
        s.points.add(wy - s.cardY)
        s.pressures.add(if (pressure > 0f) pressure else 1f)
    }

    private fun penMove(e: MotionEvent) {
        penLastX = penX(e)
        penLastY = penY(e)
        val move = penMoveSession
        if (move != null) {
            move.rawMoves++
            updateMoveSession(move, worldX(penX(e)), worldY(penY(e)))
            return
        }
        when (val s = penSession) {
            is PenSession.Write -> {
                for (i in 0 until e.historySize) {
                    appendWritePoint(s, worldX(penHistX(e, i)), worldY(penHistY(e, i)), e.getHistoricalPressure(i))
                    s.raw(e.getHistoricalX(i), e.getHistoricalY(i))
                }
                val wx = worldX(penX(e))
                val wy = worldY(penY(e))
                appendWritePoint(s, wx, wy, e.pressure)
                s.raw(e.x, e.y)
                if (s.cardId == null) updateFrameGesture(s, wx, wy)
            }
            is PenSession.Erase -> {
                for (i in 0 until e.historySize) eraseAt(s, worldX(penHistX(e, i)), worldY(penHistY(e, i)))
                eraseAt(s, worldX(penX(e)), worldY(penY(e)))
                flushErase(s)
                overlay.showEraserCursor(penX(e), penY(e), BoardEngine.scale)
            }
            is PenSession.Lasso -> {
                for (i in 0 until e.historySize) { s.pointsPx.add(penHistX(e, i)); s.pointsPx.add(penHistY(e, i)) }
                s.pointsPx.add(penX(e)); s.pointsPx.add(penY(e))
            }
            else -> {}
        }
    }

    private fun penUp(e: MotionEvent, cancelled: Boolean) {
        val wasContact = penContact
        penContact = false
        if (wasContact) Log.i(TAG_PEN, "UP px=(${penX(e)},${penY(e)}) raw=(${e.x},${e.y}) cancelled=$cancelled | ${InputReader.describePenRaw()}")
        try {
            val move = penMoveSession
            if (move != null) {
                penMoveSession = null
                if (cancelled) cancelMoveSession(move) else endMoveSession(move, worldX(penX(e)), worldY(penY(e)))
                return
            }
            val s = penSession ?: return
            penSession = null
            when (s) {
                is PenSession.Write -> {
                    cancelFrameHold()
                    if (cancelled) {
                        overlay.hideFramePreview()
                        discardWriteTrail(s, "cancel")
                        return
                    }
                    val wx = worldX(penX(e))
                    val wy = worldY(penY(e))
                    appendWritePoint(s, wx, wy, e.pressure)
                    s.raw(e.x, e.y)
                    reportStrokeForAlignment(s)
                    var candidate = s.frameCandidate
                    if (candidate != null) {
                        val moved = hypot(wx - s.frameRecognizedX, wy - s.frameRecognizedY) * BoardEngine.scale
                        if (moved >= CARD_FRAME_PREVIEW_CANCEL_MOVE_DP) candidate = null
                    }
                    overlay.hideFramePreview()
                    if (candidate != null) {
                        
                        discardWriteTrail(s, "card-frame")
                        createCardFromFrame(candidate)
                        Log.i(TAG_PEN, "UP consumed as card frame")
                        return
                    }
                    if (completeCardStroke(s)) {
                        Log.i(TAG_PEN, "UP consumed as card connection")
                        return
                    }
                    commitStroke(s)
                }
                is PenSession.Erase -> finishErase(s)
                is PenSession.Lasso -> {
                    if (!cancelled) finalizeLasso(s)
                }
                PenSession.Consumed -> {}
            }
        } finally {
            if (wasContact) applyTransition(arbiter.penUp(), "pen-up")
            overlay.hideEraserCursor()
        }
    }

    
    private fun reportStrokeForAlignment(s: PenSession.Write) {
        if (s.rawMinX > s.rawMaxX) return
        refreshViewOffset()
        InkAlign.onPenUp(
            s.rawMinX + viewOffsetX, s.rawMinY + viewOffsetY,
            s.rawMaxX + viewOffsetX, s.rawMaxY + viewOffsetY,
        )
    }

    private fun commitStroke(s: PenSession.Write) {
        if (s.points.size < 2) return
        val rec = BoardEngine.StrokeRec(s.id, s.space, PEN_WIDTH_DP, INK_BLACK, s.points.toArray(), s.pressures.toArray())
        apply(BoardHistory.Change("draw").stroke(null, rec), record = true)
        Log.i(TAG_PEN, "UP committed stroke ${s.id} points=${s.points.size / 2} space=${s.space}")
    }

    

    private val hitBuffer = ArrayList<BoardEngine.StrokeRec>()

    
    private fun eraseAt(session: PenSession.Erase, wx: Float, wy: Float) {
        hitBuffer.clear()
        synchronized(BoardEngine.lock) { BoardEngine.hitTestStrokes(wx, wy, ERASER_HIT_RADIUS, hitBuffer) }
        if (hitBuffer.isEmpty()) return
        for (rec in hitBuffer) {
            if (session.pendingIds.add(rec.id)) session.pending.stroke(rec, null)
        }
    }

    
    private fun flushErase(session: PenSession.Erase) {
        val step = session.pending
        if (step.isEmpty) return
        session.pending = BoardHistory.Change("erase")
        session.pendingIds.clear()
        apply(step, record = false)
        session.change.absorb(step)
    }

    private fun finishErase(session: PenSession.Erase) {
        flushErase(session)
        if (!session.change.isEmpty) {
            history.push(session.change)
            Log.i(TAG_PEN, "erased ${session.change.diffs.size} strokes")
        }
    }

    

    private var frameHoldTask: Runnable? = null

    private fun cancelFrameHold() {
        frameHoldTask?.let { handler.removeCallbacks(it) }
        frameHoldTask = null
    }

    private fun updateFrameGesture(s: PenSession.Write, wx: Float, wy: Float) {
        val scale = max(0.01f, BoardEngine.scale)
        if (s.frameCandidate != null) {
            val moved = hypot(wx - s.frameRecognizedX, wy - s.frameRecognizedY) * scale
            if (moved < CARD_FRAME_PREVIEW_CANCEL_MOVE_DP) return
            s.frameCandidate = null
            overlay.hideFramePreview()
            Log.i(TAG_PEN, "[CardFrame] preview cancelled movement=${moved}dp")
        }
        val movement = hypot(wx - s.frameLastMeaningfulX, wy - s.frameLastMeaningfulY) * scale
        if (movement < CARD_FRAME_HOLD_MOVE_SLOP_DP) return
        s.frameLastMeaningfulX = wx
        s.frameLastMeaningfulY = wy
        cancelFrameHold()
        val task = Runnable {
            frameHoldTask = null
            if (!penContact || penSession !== s) return@Runnable
            s.frameEvaluations++
            val evaluation = CardFrameRecognizer.evaluate(s.points.toArray(), CardFrameRecognizer.Options(viewportScale = BoardEngine.scale))
            val rect = evaluation.rect
            if (rect == null) {
                Log.i(TAG_PEN, "[CardFrame] hold rejected attempt=${s.frameEvaluations} reason=${evaluation.reason} ${evaluation.metrics?.format()}")
                return@Runnable
            }
            s.frameCandidate = rect
            s.frameRecognizedX = s.points[s.points.size - 2]
            s.frameRecognizedY = s.points[s.points.size - 1]
            overlay.showFramePreview(worldRectToPx(BoardGeometry.cardRectFromFrame(rect), RectF()))
            Log.i(TAG_PEN, "[CardFrame] hold recognized attempt=${s.frameEvaluations} ${evaluation.metrics?.format()}")
        }
        frameHoldTask = task
        handler.postDelayed(task, CARD_FRAME_HOLD_MS)
    }

    private fun createCardFromFrame(frame: RectF) {
        val rect = BoardGeometry.cardRectFromFrame(frame)
        val created = BoardEngine.CardRec(newId("card-"), rect.left, rect.top, rect.width(), rect.height(), BoardGeometry.nextZIndex(BoardEngine.cards.values))
        val change = BoardHistory.Change("card-frame").card(null, created)
        
        val candidates = ArrayList<BoardEngine.StrokeRec>()
        synchronized(BoardEngine.lock) { BoardEngine.queryCanvasStrokes(rect, candidates) }
        var captured = 0
        for (stroke in candidates) {
            if (strokeCoverage(stroke, rect) < CARD_FRAME_CAPTURE_INK_RATIO) continue
            change.stroke(stroke, stroke.translated(0f, 0f, null, created))
            captured++
        }
        apply(change, record = true)
        Log.i(TAG_PEN, "[CardFrame] created card=${created.id} rect=$rect capturedInk=$captured")
    }

    
    private fun strokeCoverage(stroke: BoardEngine.StrokeRec, rect: RectF): Float {
        val pts = stroke.points
        val n = pts.size / 2
        if (n == 0) return 0f
        var inside = 0
        var i = 0
        while (i < pts.size) {
            if (rect.contains(pts[i], pts[i + 1])) inside++
            i += 2
        }
        return inside.toFloat() / n
    }

    

    private fun completeCardStroke(s: PenSession.Write): Boolean {
        val sourceId = s.cardId ?: return false
        if (s.points.size < 4) return false
        val source = BoardEngine.cards[sourceId] ?: return false
        val fx = s.points[0]; val fy = s.points[1]
        val lx = s.points[s.points.size - 2]; val ly = s.points[s.points.size - 1]
        if (hypot(lx - fx, ly - fy) < BoardGeometry.CARD_STROKE_MIN_DISTANCE) return false
        val endX = source.x + lx
        val endY = source.y + ly
        val target = BoardGeometry.topCardAt(BoardEngine.cardsByZ, endX, endY)
        if (target?.id == sourceId) return false
        
        discardWriteTrail(s, if (target != null) "card-connection" else "card-from-stroke")
        if (target != null) {
            if (BoardEngine.connectionBetween(sourceId, target.id) == null) {
                apply(BoardHistory.Change("connect").connection(null, BoardEngine.ConnectionRec(newId("conn-"), sourceId, target.id)), record = true)
            }
            return true
        }
        val rect = BoardGeometry.cardFromStrokeRect(source, endX, endY)
        val created = BoardEngine.CardRec(newId("card-"), rect.left, rect.top, rect.width(), rect.height(), BoardGeometry.nextZIndex(BoardEngine.cards.values))
        apply(
            BoardHistory.Change("card-from-stroke")
                .card(null, created)
                .connection(null, BoardEngine.ConnectionRec(newId("conn-"), sourceId, created.id)),
            record = true,
        )
        return true
    }

    

    private fun finalizeLasso(s: PenSession.Lasso) {
        val pts = s.pointsPx
        if (pts.size < 6) { setLasso(null); return }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < pts.size) {
            val x = pts[i]; val y = pts[i + 1]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            i += 2
        }
        val minBox = BoardGeometry.LASSO_MIN_BOX_PX * density
        if (maxX - minX < minBox) { val c = (minX + maxX) / 2f; minX = c - minBox / 2f; maxX = c + minBox / 2f }
        if (maxY - minY < minBox) { val c = (minY + maxY) / 2f; minY = c - minBox / 2f; maxY = c + minBox / 2f }
        val minScreenY = 0f
        val boxW = min(maxX - minX, host.width.toFloat())
        val boxH = min(maxY - minY, host.height - minScreenY)
        minX = min(max(minX, 0f), host.width - boxW)
        minY = min(max(minY, minScreenY), host.height - boxH)
        maxX = minX + boxW
        maxY = minY + boxH
        val query = RectF(worldX(minX), worldY(minY), worldX(maxX), worldY(maxY))
        val hits = selectInRect(query)
        if (hits == null) {
            Log.i(TAG_LASSO, "finalize empty query=$query")
            setLasso(null)
            return
        }
        val frame = selectionFrame(hits)
        val selection = LassoSelection(frame, hits.cardIds, hits.strokeIds)
        Log.i(TAG_LASSO, "finalize query=$query frame=$frame cards=${selection.cardIds.size} strokes=${selection.strokeIds.size}")
        setLasso(selection)
    }

    
    private fun selectionFrame(sel: LassoSelection): RectF {
        val out = RectF()
        var any = false
        val r = RectF()
        synchronized(BoardEngine.lock) {
            for (id in sel.cardIds) {
                val card = BoardEngine.cards[id] ?: continue
                card.rect(r)
                if (!any) { out.set(r); any = true } else out.union(r)
            }
            for (id in sel.strokeIds) {
                val stroke = BoardEngine.strokes[id] ?: continue
                val bounds = BoardEngine.strokeWorldBounds(stroke)
                if (!any) { out.set(bounds); any = true } else out.union(bounds)
            }
        }
        if (!any) return sel.rect
        out.inset(-LASSO_BOUNDS_PAD, -LASSO_BOUNDS_PAD)
        return out
    }

    private fun selectInRect(rect: RectF): LassoSelection? {
        val cardIds = ArrayList<String>()
        val strokeIds = ArrayList<String>()
        val r = RectF()
        synchronized(BoardEngine.lock) {
            for (card in BoardEngine.cardsByZ) if (rect.contains(card.rect(r))) cardIds.add(card.id)
            val strokes = ArrayList<BoardEngine.StrokeRec>()
            BoardEngine.canvasStrokesInside(rect, strokes)
            for (s in strokes) strokeIds.add(s.id)
        }
        if (cardIds.isEmpty() && strokeIds.isEmpty()) return null
        return LassoSelection(rect, cardIds, strokeIds)
    }

    private fun setLasso(next: LassoSelection?) {
        lasso = next
        BoardEngine.mutate { setSelection(selectionIds()) }
        updateLassoOverlay()
        refreshHostToolFlags()
        scheduleChromeUpdate()
        if (next != null) {
            requestEink(MosaicEinkRefreshModule.MODE_DEFAULT)
            overlay.invalidate()
            content.markSettleRequested()
            content.postInvalidateOnAnimation()
            host.invalidate()
        }
    }

    private fun selectionIds(): List<String> {
        val ids = ArrayList<String>()
        selectedCardId?.let { ids.add(it) }
        lasso?.cardIds?.forEach { if (it != selectedCardId) ids.add(it) }
        return ids
    }

    private fun updateLassoOverlay() {
        val current = lasso
        if (current == null) {
            overlay.setLassoFrame(null, null)
            return
        }
        val framePx = worldRectToPx(current.rect, RectF())
        overlay.setLassoFrame(framePx, lassoActionRect(framePx))
    }

    
    private fun lassoActionRect(frame: RectF): RectF {
        val d = density
        val size = InteractionOverlayView.LASSO_ACTION_SIZE_DP * d
        val gap = InteractionOverlayView.LASSO_ACTION_GAP_DP * d
        val margin = InteractionOverlayView.LASSO_ACTION_MARGIN_DP * d
        val w = host.width.toFloat()
        val h = host.height.toFloat()
        val sideTop = min(max(frame.top + 4f * d, margin), max(margin, h - size - margin))
        val right = frame.right + gap
        if (right >= margin && right + size + margin <= w) return RectF(right, sideTop, right + size, sideTop + size)
        val left = frame.left - gap - size
        if (left >= margin) return RectF(left, sideTop, left + size, sideTop + size)
        val cx = min(max(frame.centerX() - size / 2f, margin), max(margin, w - size - margin))
        val cy = min(max(frame.bottom + gap, margin), max(margin, h - size - margin))
        return RectF(cx, cy, cx + size, cy + size)
    }

    private fun selectionHit(sel: LassoSelection, wx: Float, wy: Float): Boolean {
        if (sel.rect.contains(wx, wy)) return true
        val r = RectF()
        for (id in sel.cardIds) {
            val card = BoardEngine.cards[id] ?: continue
            if (card.rect(r).contains(wx, wy)) return true
        }
        return false
    }

    private fun deleteLassoSelection() {
        val sel = lasso ?: return
        val change = BoardHistory.Change("lasso-delete")
        synchronized(BoardEngine.lock) {
            for (id in sel.strokeIds) BoardEngine.strokes[id]?.let { change.stroke(it, null) }
            if (sel.strokeIds.isEmpty()) {
                for (id in sel.cardIds) collectCardRemoval(id, change)
            }
        }
        setLasso(null)
        apply(change, record = true)
        Log.i(TAG_LASSO, "deleted strokes=${sel.strokeIds.size} cards=${sel.cardIds.size}")
    }

    
    private fun collectCardRemoval(cardId: String, change: BoardHistory.Change) {
        val card = BoardEngine.cards[cardId] ?: return
        BoardEngine.cardStrokes[cardId]?.forEach { change.stroke(it, null) }
        val conns = ArrayList<BoardEngine.ConnectionRec>()
        BoardEngine.connectionsOf(cardId, conns)
        for (c in conns) change.connection(c, null)
        change.card(card, null)
    }

    

    private fun beginCardAdjustment(cardId: String) {
        selectedCardId = cardId
        BoardEngine.mutate { setSelection(selectionIds()) }
        refreshHostToolFlags()
        holdGestureEink(MosaicEinkRefreshModule.MODE_DEFAULT)
        scheduleChromeUpdate()
        Log.i(TAG, "[CardAdjust] begin card=$cardId")
    }

    private fun finishCardAdjustment(reason: String): Boolean {
        val id = selectedCardId ?: return false
        selectedCardId = null
        BoardEngine.mutate { setSelection(selectionIds()) }
        refreshHostToolFlags()
        scheduleGestureSettle()
        scheduleChromeUpdate()
        Log.i(TAG, "[CardAdjust] finish card=$id reason=$reason")
        return true
    }

    

    private fun beginCardDrag(isPen: Boolean, pointerId: Int, wx: Float, wy: Float, card: BoardEngine.CardRec): MoveSession.CardDrag {
        val session = MoveSession.CardDrag(isPen, pointerId, wx, wy, card, BoardGeometry.nextZIndex(BoardEngine.cards.values))
        holdGestureEink(MosaicEinkRefreshModule.MODE_DEFAULT)
        return session
    }

    private fun beginResize(isPen: Boolean, pointerId: Int, wx: Float, wy: Float, card: BoardEngine.CardRec, handle: BoardGeometry.Handle): MoveSession.CardResize {
        val session = MoveSession.CardResize(isPen, pointerId, wx, wy, card, handle)
        card.rect(session.preview)
        hideCardFromTiles(card.id)
        overlay.showCardPreview(worldRectToPx(session.preview, RectF()), true)
        holdGestureEink(MosaicEinkRefreshModule.MODE_DEFAULT)
        Log.i(TAG, "[CardAdjust] resize begin card=${card.id} handle=$handle pen=$isPen")
        return session
    }

    private fun beginSelectionMove(isPen: Boolean, pointerId: Int, wx: Float, wy: Float, sel: LassoSelection): MoveSession.Selection {
        val cards = ArrayList<BoardEngine.CardRec>()
        val strokes = ArrayList<BoardEngine.StrokeRec>()
        val cardRects = ArrayList<RectF>()
        val inkPath = Path()
        synchronized(BoardEngine.lock) {
            for (id in sel.cardIds) BoardEngine.cards[id]?.let { cards.add(it); cardRects.add(worldRectToPx(it.rect(), RectF())) }
            for (id in sel.strokeIds) BoardEngine.strokes[id]?.let { strokes.add(it) }
        }
        val m = android.graphics.Matrix()
        val sPx = BoardEngine.scale * density
        m.setScale(sPx, sPx)
        m.postTranslate(BoardEngine.panX * density, BoardEngine.panY * density)
        for (s in strokes) inkPath.addPath(s.path, m)
        overlay.beginMovePreview(
            if (sel.cardIds.isEmpty()) worldRectToPx(sel.rect, RectF()) else null,
            cardRects, inkPath, PEN_WIDTH_DP * sPx,
        )
        holdGestureEink(MosaicEinkRefreshModule.MODE_DEFAULT)
        Log.i(TAG_LASSO, "move begin cards=${cards.size} strokes=${strokes.size} pen=$isPen")
        return MoveSession.Selection(isPen, pointerId, wx, wy, sel, cards, strokes)
    }

    private val tmpRect = RectF()

    private fun updateMoveSession(m: MoveSession, wx: Float, wy: Float) {
        val dx = wx - m.startWorldX
        val dy = wy - m.startWorldY
        when (m) {
            is MoveSession.CardDrag -> {
                if (!m.dragging) {
                    if (hypot(dx, dy) * BoardEngine.scale < BoardGeometry.CARD_DRAG_THRESHOLD_PX) return
                    m.dragging = true
                    cancelCardLongPress()
                    hideCardFromTiles(m.card.id)
                }
                tmpRect.set(m.card.x + dx, m.card.y + dy, m.card.x + dx + m.card.width, m.card.y + dy + m.card.height)
                overlay.showCardPreview(worldRectToPx(tmpRect, RectF()), false)
            }
            is MoveSession.CardResize -> {
                BoardGeometry.resizeRect(m.handle, m.startWorldX, m.startWorldY, m.card.rect(tmpRect), wx, wy, m.card.kind, m.preview)
                overlay.showCardPreview(worldRectToPx(m.preview, RectF()), true)
            }
            is MoveSession.Selection -> {
                m.dx = dx
                m.dy = dy
                overlay.updateMovePreview(dx * BoardEngine.scale * density, dy * BoardEngine.scale * density)
            }
        }
    }

    private fun endMoveSession(m: MoveSession, wx: Float, wy: Float) {
        val dx = wx - m.startWorldX
        val dy = wy - m.startWorldY
        val elapsed = SystemClock.uptimeMillis() - m.startedAt
        when (m) {
            is MoveSession.CardDrag -> {
                overlay.hideCardPreview()
                if (m.dragging) {
                    settleCardDrop(m.card, m.card.x + dx, m.card.y + dy, m.liftZ)
                    Log.i(TAG, "[CardPerf] kind=move input=${if (m.isPen) "pen" else "touch"} rawMoves=${m.rawMoves} durationMs=$elapsed")
                }
                unhideCardFromTiles()
            }
            is MoveSession.CardResize -> {
                overlay.hideCardPreview()
                BoardGeometry.resizeRect(m.handle, m.startWorldX, m.startWorldY, m.card.rect(tmpRect), wx, wy, m.card.kind, m.preview)
                val start = m.card.rect(RectF())
                if (abs(m.preview.left - start.left) > 0.01f || abs(m.preview.top - start.top) > 0.01f ||
                    abs(m.preview.width() - start.width()) > 0.01f || abs(m.preview.height() - start.height()) > 0.01f
                ) {
                    apply(BoardHistory.Change("resize").card(m.card, m.card.withRect(m.preview)), record = true)
                }
                unhideCardFromTiles()
                Log.i(TAG, "[CardPerf] kind=resize input=${if (m.isPen) "pen" else "touch"} rawMoves=${m.rawMoves} durationMs=$elapsed")
            }
            is MoveSession.Selection -> {
                overlay.endMovePreview()
                if (abs(dx) > 0.01f || abs(dy) > 0.01f) {
                    val change = BoardHistory.Change("selection-move")
                    val movedCards = HashMap<String, BoardEngine.CardRec>()
                    for (card in m.cards) {
                        val moved = card.moved(card.x + dx, card.y + dy)
                        movedCards[card.id] = moved
                        change.card(card, moved)
                    }
                    
                    val destX = m.selection.rect.centerX() + dx
                    val destY = m.selection.rect.centerY() + dy
                    val cardsAfter = BoardEngine.cardsByZ.map { movedCards[it.id] ?: it }
                    val destination = BoardGeometry.topCardAt(cardsAfter, destX, destY)
                    for (stroke in m.strokes) {
                        change.stroke(stroke, stroke.translated(dx, dy, null, destination))
                    }
                    apply(change, record = true)
                    val rect = RectF(m.selection.rect).apply { offset(dx, dy) }
                    lasso = LassoSelection(rect, m.selection.cardIds, m.selection.strokeIds)
                    BoardEngine.mutate { setSelection(selectionIds()) }
                    Log.i(TAG_LASSO, "reassigned strokes=${m.strokes.size} destination=${destination?.id ?: "canvas"}")
                }
                updateLassoOverlay()
                Log.i(TAG, "[LassoPerf] input=${if (m.isPen) "pen" else "touch"} rawMoves=${m.rawMoves} durationMs=$elapsed dx=$dx dy=$dy")
            }
        }
        scheduleGestureSettle()
    }

    private fun cancelMoveSession(m: MoveSession) {
        when (m) {
            is MoveSession.CardDrag, is MoveSession.CardResize -> { overlay.hideCardPreview(); unhideCardFromTiles() }
            is MoveSession.Selection -> { overlay.endMovePreview(); updateLassoOverlay() }
        }
        scheduleGestureSettle()
    }

    
    private fun hideCardFromTiles(cardId: String) {
        BoardEngine.hiddenCardId = cardId
        
        val card = BoardEngine.cards[cardId] ?: return
        content.onSceneChanged(BoardEngine.cardDirtyBounds(card), true)
    }

    private fun unhideCardFromTiles() {
        val id = BoardEngine.hiddenCardId ?: return
        BoardEngine.hiddenCardId = null
        val card = BoardEngine.cards[id] ?: return
        content.onSceneChanged(BoardEngine.cardDirtyBounds(card), true)
    }

    
    private fun settleCardDrop(card: BoardEngine.CardRec, x: Float, y: Float, liftZ: Int) {
        val snap = BoardGeometry.findSnap(BoardEngine.cardsByZ, card.id, x, y, card.width, card.height)
        val settled = card.moved(snap.x, snap.y).withZ(liftZ)
        val change = BoardHistory.Change("move").card(card, settled)
        val target = snap.targetId
        if (target != null && BoardEngine.connectionBetween(card.id, target) == null) {
            change.connection(null, BoardEngine.ConnectionRec(newId("conn-"), card.id, target))
        }
        apply(change, record = true)
    }

    

    override fun onTouch(frame: InputRouter.TouchFrame) {
        when (frame.action) {
            InputRouter.ACTION_DOWN, InputRouter.ACTION_POINTER_DOWN -> {
                if (frame.action == InputRouter.ACTION_DOWN) refreshViewOffset()
                val i = frame.actionIndex
                fingerDown(frame.ids[i], frame.xs[i] - viewOffsetX, frame.ys[i] - viewOffsetY, frame.penPriority, frame.uptimeMs)
            }
            InputRouter.ACTION_MOVE -> {
                for (i in 0 until frame.count) {
                    val f = fingers[frame.ids[i]] ?: continue
                    f.x = frame.xs[i] - viewOffsetX
                    f.y = frame.ys[i] - viewOffsetY
                }
                fingerMove(frame.uptimeMs)
            }
            InputRouter.ACTION_UP, InputRouter.ACTION_POINTER_UP -> {
                val i = frame.actionIndex
                val id = frame.ids[i]
                fingers[id]?.let { it.x = frame.xs[i] - viewOffsetX; it.y = frame.ys[i] - viewOffsetY }
                fingerUp(id, cancelled = false)
            }
            InputRouter.ACTION_CANCEL -> {
                cancelFingerGestures("cancel")
            }
        }
    }

    private fun fingerDown(id: Int, x: Float, y: Float, penPriority: Boolean, now: Long) {
        if (chrome.consumesPoint(x, y) && fingers.isEmpty()) {
            Log.i(TAG_FINGER, "DOWN id=$id ignored: chrome")
            return
        }
        
        if (arbiter.hasOverride(ToolArbiter.Source.SLIDEBAR)) return
        
        if (penContact) {
            Log.i(TAG_FINGER, "DOWN id=$id blocked: pen-session")
            return
        }
        val finger = Finger(id, x, y, now, penPriority)
        fingers[id] = finger
        Log.i(TAG_FINGER, "DOWN id=$id px=($x,$y) count=${fingers.size} penPriority=$penPriority gesture=${fingerGesture::class.simpleName}")

        if (fingers.size >= 2) {
            val (a, b) = firstTwo()
            
            cancelCardLongPress()
            fingerMoveSession?.let { cancelMoveSession(it); fingerMoveSession = null }
            if (fingerGesture is FingerGesture.PanZoom || fingerGesture is FingerGesture.ScreenTool) return
            if (fingers.size > 2) { fingerGesture = FingerGesture.Idle; return }
            if (!touchEnabled && gestureToolOf(settings.screenGesture) == null) { fingerGesture = FingerGesture.Idle; return }
            if (abs(a.downAt - b.downAt) > TWO_FINGER_PAIR_WINDOW_MS || gestureToolOf(settings.screenGesture) == null) {
                beginPanZoom(a, b)
            } else {
                fingerGesture = FingerGesture.TwoCandidate(a.id, b.id, now)
                handler.postDelayed(twoCandidateTimeout, TWO_FINGER_CANDIDATE_HOLD_MS)
                Log.i(TAG_TWO, "candidate a=${a.id} b=${b.id}")
            }
            return
        }

        
        if (penPriority) {
            fingerGesture = FingerGesture.Idle
            Log.i(TAG_FINGER, "single finger idle id=$id reason=pen-priority")
            return
        }
        if (!touchEnabled) { fingerGesture = FingerGesture.Idle; return }
        val wx = worldX(x)
        val wy = worldY(y)

        
        val currentLasso = lasso
        if (currentLasso != null) {
            if (currentLasso.cardIds.isEmpty() && overlay.lassoActionHit(x, y, LASSO_ACTION_TAP_SLOP_DP * density)) {
                deleteLassoSelection()
                fingerGesture = FingerGesture.Idle
                return
            }
            if (selectionHit(currentLasso, wx, wy)) {
                fingerMoveSession = beginSelectionMove(false, id, wx, wy, currentLasso)
                fingerGesture = FingerGesture.Move(id)
                return
            }
            setLasso(null)
        }

        val selected = selectedCardId?.let { BoardEngine.cards[it] }
        val card: BoardEngine.CardRec?
        if (selected != null) {
            val handle = BoardGeometry.handleAt(selected, wx, wy, BoardEngine.scale)
            if (handle != null) {
                fingerMoveSession = beginResize(false, id, wx, wy, selected, handle)
                fingerGesture = FingerGesture.Move(id)
                return
            }
            if (BoardGeometry.pointHitsCard(selected, wx, wy)) {
                card = selected
            } else {
                finishCardAdjustment("finger-down")
                fingerGesture = FingerGesture.Idle
                return
            }
        } else {
            card = BoardGeometry.topCardAt(BoardEngine.cardsByZ, wx, wy)
        }
        if (card == null) {
            finishCardAdjustment("finger-blank")
            fingerGesture = FingerGesture.Idle
            return
        }
        val moveEnabled = selected?.id == card.id
        fingerMoveSession = beginCardDrag(false, id, wx, wy, card)
        fingerGesture = FingerGesture.Move(id)
        if (!moveEnabled) {
            
            (fingerMoveSession as MoveSession.CardDrag).dragging = false
            val task = Runnable {
                cardLongPressTask = null
                val m = fingerMoveSession as? MoveSession.CardDrag ?: return@Runnable
                if (m.pointerId != id || m.card.id != card.id || m.dragging) return@Runnable
                beginCardAdjustment(card.id)
            }
            cardLongPressTask = task
            handler.postDelayed(task, BoardGeometry.CARD_LONG_PRESS_MS)
        }
    }

    private fun cancelCardLongPress() {
        cardLongPressTask?.let { handler.removeCallbacks(it) }
        cardLongPressTask = null
    }

    private fun fingerMove(now: Long) {
        when (val g = fingerGesture) {
            is FingerGesture.Move -> {
                val m = fingerMoveSession ?: return
                val f = fingers[m.pointerId] ?: return
                m.rawMoves++
                if (m is MoveSession.CardDrag && !m.dragging && selectedCardId != m.card.id) {
                    
                    if (hypot(f.x - f.downX, f.y - f.downY) >= BoardGeometry.CARD_DRAG_THRESHOLD_PX * density) {
                        cancelCardLongPress()
                    }
                    return
                }
                updateMoveSession(m, worldX(f.x), worldY(f.y))
            }
            is FingerGesture.TwoCandidate -> {
                val a = fingers[g.a] ?: return
                val b = fingers[g.b] ?: return
                if (TwoFingerToolGuard.moved(a.downX, a.downY, a.x, a.y) || TwoFingerToolGuard.moved(b.downX, b.downY, b.x, b.y)) {
                    handler.removeCallbacks(twoCandidateTimeout)
                    Log.i(TAG_TWO, "candidate moved → pinch")
                    if (touchEnabled) beginPanZoom(a, b) else fingerGesture = FingerGesture.Idle
                }
            }
            is FingerGesture.PanZoom -> updatePanZoom(g)
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
                        if (cancelled) cancelMoveSession(m) else endMoveSession(m, worldX(finger.x), worldY(finger.y))
                    }
                }
            }
            is FingerGesture.CardPending -> {
                if (g.fingerId == id) {
                    cancelCardLongPress()
                    fingerGesture = FingerGesture.Idle
                }
            }
            is FingerGesture.TwoCandidate -> {
                if (id == g.a || id == g.b) {
                    handler.removeCallbacks(twoCandidateTimeout)
                    fingerGesture = FingerGesture.Idle
                }
            }
            is FingerGesture.PanZoom -> {
                if (id == g.a || id == g.b) {
                    endPanZoom(g)
                    
                    if (fingers.size >= 2) {
                        val (a, b) = firstTwo()
                        beginPanZoom(a, b)
                    } else fingerGesture = FingerGesture.Idle
                }
            }
            FingerGesture.ScreenTool -> {
                if (fingers.isEmpty()) {
                    fingerGesture = FingerGesture.Idle
                    applyTransition(arbiter.releaseOverride(ToolArbiter.Source.SCREEN), "screen-release")
                    scheduleGestureSettle()
                }
            }
            FingerGesture.Idle -> {}
        }
        if (fingers.isEmpty()) fingerGesture = FingerGesture.Idle
    }

    private fun firstTwo(): Pair<Finger, Finger> {
        val it = fingers.values.iterator()
        return Pair(it.next(), it.next())
    }

    private fun cancelFingerGestures(reason: String) {
        cancelCardLongPress()
        handler.removeCallbacks(twoCandidateTimeout)
        fingerMoveSession?.let { cancelMoveSession(it) }
        fingerMoveSession = null
        (fingerGesture as? FingerGesture.PanZoom)?.let { endPanZoom(it) }
        if (fingerGesture === FingerGesture.ScreenTool) {
            applyTransition(arbiter.releaseOverride(ToolArbiter.Source.SCREEN), "screen-cancel")
            scheduleGestureSettle()
        }
        fingerGesture = FingerGesture.Idle
        fingers.clear()
        Log.i(TAG_FINGER, "gestures cancelled reason=$reason")
    }

    private fun cancelActiveInteractions(reason: String) {
        cancelFingerGestures(reason)
        penMoveSession?.let { cancelMoveSession(it) }
        penMoveSession = null
        cancelFrameHold()
        overlay.hideFramePreview()
        val s = penSession
        penSession = null
        when (s) {
            is PenSession.Write -> discardWriteTrail(s, reason)
            is PenSession.Lasso -> ink.abortStroke()
            else -> {}
        }
    }

    

    private val twoCandidateTimeout = Runnable { settleTwoCandidate("hold") }

    
    private fun settleTwoCandidate(reason: String): Boolean {
        val g = fingerGesture as? FingerGesture.TwoCandidate ?: return false
        handler.removeCallbacks(twoCandidateTimeout)
        val a = fingers[g.a] ?: return false
        val b = fingers[g.b] ?: return false
        val tool = gestureToolOf(settings.screenGesture)
        if (tool == null) {
            if (touchEnabled) beginPanZoom(a, b) else fingerGesture = FingerGesture.Idle
            return true
        }
        val decision = TwoFingerToolGuard.evaluate(
            a.x + viewOffsetX, a.y + viewOffsetY, b.x + viewOffsetX, b.y + viewOffsetY,
            if (penHovering) penHoverX + viewOffsetX else null,
            if (penHovering) penHoverY + viewOffsetY else null,
            if (penHovering) InputReader.penTiltDirection else null,
            settings.leftHand,
            host.resources.displayMetrics.widthPixels.toFloat(),
            host.resources.displayMetrics.heightPixels.toFloat(),
        )
        Log.i(TAG_TWO, "decision accept=${decision.accept} reason=${decision.reason} tool=$tool route=${if (decision.accept) "tool" else "pinch"} source=$reason")
        if (!decision.accept) {
            if (touchEnabled) beginPanZoom(a, b) else fingerGesture = FingerGesture.Idle
            return true
        }
        fingerGesture = FingerGesture.ScreenTool
        cancelGestureSettle()
        applyTransition(arbiter.pushOverride(ToolArbiter.Source.SCREEN, tool, ToolArbiter.Settle.ON_PEN_UP), "screen-tool")
        requestEink(MosaicEinkRefreshModule.MODE_DUX)
        if (hasColoredCards()) content.setPreviewCardsWhite(true)
        if (boardTranslucent) content.setSuspendTranslucent(true)
        return true
    }

    

    private fun beginPanZoom(a: Finger, b: Finger) {
        cancelGestureSettle()
        val view = viewWorld()
        val activeRegion = currentRegions().let { SparseNavigation.findActiveRegion(it, view) }
        val neighbors = if (activeRegion == null) SparseNavigation.Neighbors.NONE else SparseNavigation.findNeighbors(currentRegions(), activeRegion)
        val g = FingerGesture.PanZoom(
            a.id, b.id,
            BoardEngine.panX, BoardEngine.panY, BoardEngine.scale,
            (a.x + b.x) / 2f, (a.y + b.y) / 2f, max(1f, hypot(b.x - a.x, b.y - a.y)),
            activeRegion, neighbors,
        )
        fingerGesture = g
        panZoomArmed = false
        overlay.setLassoVisible(false)
        if (hasColoredCards()) content.setPreviewCardsWhite(true)
        if (boardTranslucent) content.setSuspendTranslucent(true)
        armPanZoom()
        val bnd = activeRegion?.bounds
        Log.i(TAG_PAN, "begin a=${a.id} b=${b.id} pan=(${g.startPanX},${g.startPanY}) scale=${g.startScale} region=${activeRegion != null} neighbors=${neighbors.any()} bounds=${bnd?.let { "(${it.left},${it.top},${it.right},${it.bottom})" }} worldView=(${view.width()},${view.height()})")
    }

    private fun armPanZoom() {
        val g = fingerGesture as? FingerGesture.PanZoom ?: return
        resnapPanZoom(g)
        if (!panZoomArmed) {
            panZoomArmed = true
            content.setZoomPreview(true)
            requestEink(MosaicEinkRefreshModule.MODE_DTH)
            Log.i(TAG_PAN, "armed pan=(${g.startPanX},${g.startPanY}) scale=${g.startScale}")
        }
    }

    private fun resnapPanZoom(g: FingerGesture.PanZoom) {
        val a = fingers[g.a] ?: return
        val b = fingers[g.b] ?: return
        g.startPanX = BoardEngine.panX
        g.startPanY = BoardEngine.panY
        g.startScale = BoardEngine.scale
        g.startCenterX = (a.x + b.x) / 2f
        g.startCenterY = (a.y + b.y) / 2f
        g.startDistance = max(1f, hypot(b.x - a.x, b.y - a.y))
    }

    private fun updatePanZoom(g: FingerGesture.PanZoom) {
        if (!panZoomArmed) return
        val a = fingers[g.a] ?: return
        val b = fingers[g.b] ?: return
        val d = density
        val cx = (a.x + b.x) / 2f
        val cy = (a.y + b.y) / 2f
        val dist = max(1f, hypot(b.x - a.x, b.y - a.y))
        val ratio = dist / g.startDistance
        val nextScale = BoardGeometry.clampZoom(g.startScale * ratio)
        val effectiveRatio = nextScale / g.startScale
        
        val startCxDp = g.startCenterX / d
        val startCyDp = g.startCenterY / d
        val worldCx = (startCxDp - g.startPanX) / g.startScale
        val worldCy = (startCyDp - g.startPanY) / g.startScale
        var panX = cx / d - worldCx * nextScale
        var panY = cy / d - worldCy * nextScale
        
        
        g.overscroll = null
        g.frames++
        BoardEngine.setViewport(panX, panY, nextScale)
        if (effectiveRatio != 1f && g.frames % 30 == 0) Log.i(TAG_PAN, "zoom frame scale=$nextScale")
    }

    private fun endPanZoom(g: FingerGesture.PanZoom) {
        content.setZoomPreview(false)
        overlay.setLassoVisible(true)
        var panX = BoardEngine.panX
        var panY = BoardEngine.panY
        val scale = BoardEngine.scale
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
                val centered = SparseNavigation.panToCenterRect(target.bounds, host.width / density, host.height / density, scale)
                panX = centered[0]
                panY = centered[1]
                Log.i(TAG_PAN, "jump ${over.direction} overscroll=${over.distancePx}")
            }
        }
        commitViewport(panX, panY, scale)
        updateLassoOverlay()
        panZoomArmed = false
        scheduleGestureSettle()
        Log.i(TAG_PAN, "end frames=${g.frames} pan=($panX,$panY) scale=$scale")
    }

    

    override fun onPenState(state: InputRouter.PenState, value: Boolean) {
        when (state) {
            InputRouter.PenState.HOVER -> {
                penHovering = value
                if (!value) overlay.hideEraserCursor()
            }
            InputRouter.PenState.RUBBER, InputRouter.PenState.STYLUS -> {
                val physical = if (state == InputRouter.PenState.RUBBER) ToolArbiter.Physical.RUBBER else ToolArbiter.Physical.STYLUS
                if (value) {
                    
                    val bound = if (physical == ToolArbiter.Physical.RUBBER) ToolArbiter.Tool.ERASER else gestureToolOf(settings.penButton)
                    val tool = if (penHovering || penContact) bound else ToolArbiter.Tool.ERASER
                    if (tool == null) return
                    val armed = !(penHovering || penContact)
                    cancelGestureSettle()
                    applyTransition(
                        arbiter.pushOverride(ToolArbiter.Source.PEN_BUTTON, tool, ToolArbiter.Settle.ON_NEXT_PEN_DOWN, armed = armed, physical = physical),
                        "pen-button-down",
                    )
                    if (hasColoredCards()) content.setPreviewCardsWhite(true)
                    if (boardTranslucent) content.setSuspendTranslucent(true)
                } else {
                    if (arbiter.penButtonPhysical() != null && arbiter.penButtonPhysical() != physical) return
                    applyTransition(arbiter.releaseOverride(ToolArbiter.Source.PEN_BUTTON), "pen-button-up")
                    scheduleGestureSettle()
                }
            }
        }
    }

    override fun onSlider(gesture: String, side: Int) {
        if (side != settings.sliderSide) return
        Log.i(TAG, "[Slider] $gesture side=$side")
        when (gesture) {
            "slideUp" -> redo()
            "slideDown" -> undo()
            
            
            
            
            "twoDown" -> if (gestureToolOf(settings.slidebarGesture) != null) {
                sliderTwoDown = true
                cancelGestureSettle()
                host.setDrawPathSuspended(true, "slider-two-down")
                if (hasColoredCards()) content.setPreviewCardsWhite(true)
                if (boardTranslucent) content.setSuspendTranslucent(true)
                
                (penSession as? PenSession.Write)?.let { it.convertFromIndex = it.points.size }
            }
            "twoUp" -> {
                sliderTwoDown = false
                host.setDrawPathSuspended(false, "slider-two-up")
                scheduleGestureSettle()
            }
            "twoTap" -> gestureToolOf(settings.slidebarGesture)?.let { applyTransition(arbiter.toggleBase(it), "slider-toggle") }
            "twoLongPress" -> gestureToolOf(settings.slidebarGesture)?.let {
                applyTransition(arbiter.pushOverride(ToolArbiter.Source.SLIDEBAR, it, ToolArbiter.Settle.ON_PEN_UP), "slider-long-press")
            }
            "twoLongPressEnd" -> applyTransition(arbiter.releaseOverride(ToolArbiter.Source.SLIDEBAR), "slider-long-press-end")
        }
    }

    

    private fun currentRegions(): List<SparseNavigation.Region> {
        if (!regionsDirty) return regions
        val items = ArrayList<SparseNavigation.Item>()
        synchronized(BoardEngine.lock) {
            for (c in BoardEngine.cards.values) items.add(SparseNavigation.Item(c.id, c.x, c.y, c.width, c.height))
        }
        regions = SparseNavigation.detectRegions(items)
        regionsDirty = false
        return regions
    }

    private fun scheduleChromeUpdate() {
        if (chromeUpdatePosted) return
        chromeUpdatePosted = true
        handler.post {
            chromeUpdatePosted = false
            updateChrome()
        }
    }

    private fun updateChrome() {
        if (host.width == 0) return
        chrome.setZoom(BoardEngine.scale)
        val view = viewWorld()
        val current = synchronized(BoardEngine.lock) { BoardGeometry.findCurrentWhiteboard(BoardEngine.whiteboards.values, view) }
        
        BoardEngine.mutate {
            for (wb in BoardEngine.whiteboards.values.toList()) {
                val shouldBeCurrent = wb.id == current?.id
                if (wb.current != shouldBeCurrent) {
                    upsertWhiteboard(BoardEngine.WhiteboardRec(wb.id, wb.name, wb.x, wb.y, wb.width, wb.height, shouldBeCurrent))
                }
            }
        }
        val selected = selectedCardId?.let { BoardEngine.cards[it] }
        val levels = if (selected == null) emptyList() else synchronized(BoardEngine.lock) {
            BoardGeometry.computeSizeLevels(selected, BoardEngine.cards, BoardEngine.connections.values)
        }
        chrome.setMode(
            currentWhiteboard = current != null,
            selectedCard = selected != null,
            selectedCardColored = selected?.colored == true,
            sizeLevels = levels,
            lassoCards = lasso?.cardIds?.isNotEmpty() == true,
        )
        val jumps = if (fingerGesture is FingerGesture.PanZoom) emptyList()
        else SparseNavigation.findRegionJumps(currentRegions(), view, SparseNavigation.PAN_MARGIN)
        chrome.setRegionJumps(jumps)
        updateLassoOverlay()
    }

    private fun zoomTo(nextScale: Float) {
        cancelFingerGestures("zoom")
        val view = viewWorld()
        val cx = view.centerX()
        val cy = view.centerY()
        val w = host.width / density
        val h = host.height / density
        commitViewport(w / 2f - cx * nextScale, h / 2f - cy * nextScale, nextScale)
    }

    override fun onOpenMenu() = openSwitcher()

    override fun onSync() {
        emitter.begin()
        emitter.action("sync")
        emitter.commit()
    }

    private var boardTranslucent = false
    override fun onToggleTranslucent() {
        boardTranslucent = !boardTranslucent
        host.setBoardTranslucent(boardTranslucent)
        chrome.setTranslucentActive(boardTranslucent)
    }

    override fun onZoomStep(direction: Int) {
        val index = BoardGeometry.nearestZoomIndex(BoardEngine.scale)
        val next = (index + direction).coerceIn(0, BoardGeometry.ZOOM_LEVELS.size - 1)
        if (BoardGeometry.ZOOM_LEVELS[next] == BoardEngine.scale) return
        zoomTo(BoardGeometry.ZOOM_LEVELS[next])
    }

    override fun onZoomReset() = zoomTo(BoardGeometry.DEFAULT_ZOOM)

    override fun onToggleTouch() = setTouchEnabled(!touchEnabled, fromJs = false)

    override fun onSetWhiteboard() {
        val now = SystemClock.uptimeMillis()
        if (now < whiteboardAnchorGuardUntil) return
        whiteboardAnchorGuardUntil = now + BoardGeometry.WHITEBOARD_ANCHOR_GUARD_MS
        val view = viewWorld()
        val cx = view.centerX()
        val cy = view.centerY()
        val halfW = host.width / density / BoardGeometry.WHITEBOARD_CREATION_SCALE / 2f
        val halfH = host.height / density / BoardGeometry.WHITEBOARD_CREATION_SCALE / 2f
        val rect = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val existing = synchronized(BoardEngine.lock) { BoardEngine.whiteboards.values.toList() }
        for (wb in existing) {
            if (BoardGeometry.coverage(rect, wb.rect()) >= BoardGeometry.WHITEBOARD_DUPLICATE_COVERAGE) {
                Log.i(TAG, "[Whiteboard] anchor reused ${wb.id}")
                return
            }
        }
        val wb = BoardEngine.WhiteboardRec(newId("wb-"), chrome.newWhiteboardName(existing), rect.left, rect.top, rect.width(), rect.height(), current = true)
        apply(BoardHistory.Change("whiteboard-add").whiteboard(null, wb), record = false)
        Log.i(TAG, "[Whiteboard] anchored ${wb.id} \"${wb.name}\" $rect")
    }

    override fun onDeleteWhiteboard() {
        val target = synchronized(BoardEngine.lock) { BoardGeometry.findCurrentWhiteboard(BoardEngine.whiteboards.values, viewWorld()) } ?: return
        apply(BoardHistory.Change("whiteboard-delete").whiteboard(target, null), record = false)
        Log.i(TAG, "[Whiteboard] deleted ${target.id}")
    }

    override fun onDeleteSelectedCard() {
        val id = selectedCardId ?: return
        val change = BoardHistory.Change("card-delete")
        synchronized(BoardEngine.lock) { collectCardRemoval(id, change) }
        finishCardAdjustment("delete")
        apply(change, record = true)
    }

    override fun onApplySizeLevel(level: BoardGeometry.SizeLevel) {
        val id = selectedCardId ?: return
        val card = BoardEngine.cards[id] ?: return
        apply(BoardHistory.Change("size-level").card(card, card.withRect(level.rect)), record = true)
    }

    override fun onToggleCardAccent() {
        val id = selectedCardId ?: return
        val card = BoardEngine.cards[id] ?: return
        
        val next = if (card.colored) card.withColors("", "")
        else card.withColors(ACCENT_BG_COLOR, ACCENT_TEXT_COLOR)
        apply(BoardHistory.Change("card-accent").card(card, next), record = true)
    }

    override fun onDeleteLassoSelection() = deleteLassoSelection()

    override fun onClose() {
        Log.i(TAG, "onClose: emitting close action")
        emitter.action("close")
    }

    override fun onJumpToRegion(region: SparseNavigation.Region) {
        cancelFingerGestures("jump")
        val centered = SparseNavigation.panToCenterRect(region.bounds, host.width / density, host.height / density, BoardEngine.scale)
        commitViewport(centered[0], centered[1], BoardEngine.scale)
    }

    private fun openSwitcher() {
        val list = synchronized(BoardEngine.lock) { BoardEngine.whiteboards.values.toList() }
        chrome.showSwitcher(list)
        refreshHostToolFlags()
    }

    override fun onSwitcherDismissed() {
        chrome.hideSwitcher()
        refreshHostToolFlags()
    }

    

    
    private var captureBusy = false

    
    override fun onCaptureWhiteboard(id: String) {
        chrome.hideSwitcher()
        refreshHostToolFlags()
        if (captureBusy) {
            Log.i(TAG, "[MosaicNoteShot] capture ignored: busy wb=$id")
            return
        }
        val wb = BoardEngine.whiteboards[id] ?: return
        val plan = synchronized(BoardEngine.lock) {
            WhiteboardExport.plan(wb, BoardEngine.cards.values, BoardEngine.strokes.values)
        }
        if (plan == null) {
            Log.i(TAG, "[MosaicNoteShot] export skipped empty content wb=$id")
            return
        }
        val densityValue = density
        val scale = WhiteboardExport.exportScale(plan.bounds, host.width / densityValue, host.height / densityValue)
        val scalePx = scale * densityValue
        val displayName = chrome.whiteboardDisplayName(wb.name)
        val outPath = "$SHOT_DIR/${wb.id}-${System.currentTimeMillis()}.png"
        Log.i(
            TAG,
            "[MosaicNoteShot] export bounds=${plan.bounds} items=cards:${plan.cardCount},ink:${plan.strokeCount} " +
                "scale=$scale output=${Math.ceil((plan.bounds.width() * scalePx).toDouble()).toInt()}x" +
                "${Math.ceil((plan.bounds.height() * scalePx).toDouble()).toInt()} path=$outPath",
        )
        captureBusy = true
        val startedAt = SystemClock.uptimeMillis()
        content.renderExport(plan.bounds, scalePx) { bitmap ->
            if (bitmap == null) {
                Log.w(TAG, "[MosaicNoteShot] export raster failed wb=$id")
                handler.post { captureBusy = false }
                return@renderExport
            }
            val hotspot = WhiteboardExport.drawLabel(Canvas(bitmap), displayName, densityValue, bitmap.width, bitmap.height)
            Thread({
                var ok = false
                try {
                    val file = File(outPath)
                    file.parentFile?.mkdirs()
                    val tmp = File("$outPath.tmp")
                    FileOutputStream(tmp).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
                    if (!tmp.renameTo(file)) throw IllegalStateException("rename failed $tmp -> $file")
                    ok = true
                    Log.i(
                        TAG,
                        "[MosaicNoteShot] capture ok ${bitmap.width}x${bitmap.height}px ${file.length() / 1024}KB " +
                            "in ${SystemClock.uptimeMillis() - startedAt}ms -> $outPath",
                    )
                } catch (error: Throwable) {
                    Log.e(TAG, "[MosaicNoteShot] capture: png write failed", error)
                } finally {
                    bitmap.recycle()
                }
                handler.post {
                    captureBusy = false
                    if (!ok) return@post
                    emitter.begin()
                    emitter.action("captureReady") {
                        putString("path", outPath)
                        putString("wbId", wb.id)
                        putString("wbName", wb.name)
                        putMap("rect", Arguments.createMap().apply {
                            putDouble("x", wb.x.toDouble())
                            putDouble("y", wb.y.toDouble())
                            putDouble("w", wb.width.toDouble())
                            putDouble("h", wb.height.toDouble())
                        })
                        putMap("hotspot", Arguments.createMap().apply {
                            putDouble("x", hotspot.x.toDouble())
                            putDouble("y", hotspot.y.toDouble())
                            putDouble("w", hotspot.w.toDouble())
                            putDouble("h", hotspot.h.toDouble())
                        })
                    }
                    emitter.commit()
                }
            }, "MosaicNoteShotPng").start()
        }
    }

    override fun onNavigateWhiteboard(id: String) {
        val wb = BoardEngine.whiteboards[id] ?: return
        val w = host.width / density
        val h = host.height / density
        val fit = min(w / wb.width, h / wb.height)
        val scale = BoardGeometry.clampZoom(fit)
        val centered = SparseNavigation.panToCenterRect(wb.rect(), w, h, scale)
        chrome.hideSwitcher()
        refreshHostToolFlags()
        commitViewport(centered[0], centered[1], scale)
        Log.i(TAG, "[Whiteboard] navigated to ${wb.name} scale=$scale")
    }

    

    
    class FloatArrayList(capacity: Int = 256) {
        private var data = FloatArray(capacity)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        operator fun get(i: Int): Float = data[i]
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
