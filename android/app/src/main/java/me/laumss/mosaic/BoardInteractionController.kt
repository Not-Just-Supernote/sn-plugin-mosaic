package me.laumss.mosaic

import android.content.Intent
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
import org.json.JSONArray
import org.json.JSONObject
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
        
        const val PASTE_SCALE = 0.8f
        const val ERASER_HIT_RADIUS = 12f
        
        private const val TRAIL_HYSTERESIS_DP = 6f
        
        private const val ZOOM_SNAP_RATIO = 0.03f
        
        private const val ROTATE_SNAP_DEG = 3f
        
        private const val SHAPE_MIN_DRAG_DP = 8f

        
        private const val NOTE_RESTORE_PAINT_HOLD_MS = 3000L

        
        const val ACCENT_BG_COLOR = "#FFF9E3"
        const val ACCENT_TEXT_COLOR = "#3f3b2e"

        const val CARD_FRAME_HOLD_MS = 500L
        const val CARD_FRAME_HOLD_MOVE_SLOP_DP = 3f
        const val CARD_FRAME_PREVIEW_CANCEL_MOVE_DP = 8f
        const val CARD_FRAME_CAPTURE_INK_RATIO = 0.70f

        
        
        
        const val TWO_FINGER_CANDIDATE_HOLD_MS = 300L
        
        const val TWO_FINGER_PAIR_WINDOW_MS = TwoFingerToolGuard.EVENT_TIME_DEV_MS

        
        const val CARD_FINGER_MOVE_HOLD_MS = 900L

        const val LASSO_ACTION_TAP_SLOP_DP = 12f

        
        const val TWO_FINGER_TAP_MAX_MS = 300L
        const val TWO_FINGER_DOUBLE_TAP_GAP_MS = 400L
        const val TWO_FINGER_TAP_MOVE_SLOP_DP = 24f
        const val TWO_FINGER_DOUBLE_TAP_DIST_DP = 120f
        
        const val LASSO_BOUNDS_PAD = 10f
        private const val EINK_OWNER = "gesture"
        
        const val GESTURE_SETTLE_MS = 300L

        
        const val SHOT_DIR = "/sdcard/EXPORT/mosaic"

        
        private const val EDGE_SWIPE_START_DP = 32f
        
        private const val EDGE_SWIPE_ZONE_DP = 72f
        
        private const val RECOGNIZE_CARD_MIN_DP = 120f

        
        const val NOTE_CARD_BOARD_WIDTH = 300f

        
        fun noteCardBoardSize(contentHeight: Float, contentWidth: Float, cardWidth: Float = NOTE_CARD_BOARD_WIDTH, out: FloatArray) {
            val width = cardWidth.coerceAtLeast(BoardGeometry.MIN_CARD_SIZE)
            val cw = contentWidth.coerceAtLeast(ScrollingDocument.WIDTH)
            out[0] = width
            out[1] = (contentHeight * width / cw).coerceAtLeast(BoardGeometry.MIN_CARD_SIZE)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density: Float get() = host.resources.displayMetrics.density.coerceAtLeast(1f)

    private val edgeSwipeStartPx: Float get() = EDGE_SWIPE_START_DP * density
    private val edgeSwipeZonePx: Float get() = EDGE_SWIPE_ZONE_DP * density
    
    private var clippedWhiteboardIds: Set<String> = emptySet()

    
    private class PendingRecognition(val rect: RectF, val strokeIds: List<String>)
    private var pendingRecognition: PendingRecognition? = null

    

    private val arbiter = ToolArbiter()
    var settings: GestureSettings.Resolved = GestureSettings.Resolved.DEFAULT
        private set
    private var history = BoardHistory()

    

    private val whiteboard = WhiteboardSurface(emitter) { host.armCloseWatchdog("board-close") }
    
    private var surface: BoardSurface = whiteboard
    
    private var surfaceSwitching = false
    private val noteController = NoteController()
    private var notesDirectory: String = ""
    
    private class SuspendedBoard(val scene: BoardEngine.SceneSnapshot, val history: BoardHistory, val lasso: LassoSelection?)
    private var suspendedBoard: SuspendedBoard? = null
    
    private var currentNoteTitle: String? = null
    
    private val viewportTmp = FloatArray(2)

    
    private fun noteTitleFromContent(content: String): String {
        for (raw in content.replace("\r\n", "\n").split('\n')) {
            val line = raw.trim().trimStart('#').trim()
            if (line.isNotEmpty()) return if (line.length > 60) line.take(60) else line
        }
        return ""
    }

    fun setNotesDirectory(path: String) {
        notesDirectory = path
        restoreSessionNote()
    }

    
    private fun restoreSessionNote() {
        if (suspendedBoard != null || surfaceSwitching) return
        val ref = MosaicSession.savedNoteRef(host.context) ?: return
        
        
        host.post { if (host.width > 0 && host.height > 0) openNote(ref) }
    }

    
    private fun enterSurface(next: BoardSurface, scene: BoardEngine.SceneSnapshot) {
        surface = next
        BoardEngine.replaceScene(scene)
        chrome.setNoteMode(!next.showsBoardChrome, if (next.showsBoardChrome) null else currentNoteTitle)
        syncToolMirrors(arbiter.effective(), "surface:${next.name}")
        
        val noteRef = if (next.showsBoardChrome) null else noteController.document?.ref
        InklingLink.setSurface(next.name, noteRef, "surface-switch")
        MosaicSession.saveNoteRef(host.context, noteRef)
        applyTemplateForSurface(next, noteRef)
        Log.i(TAG, "surface entered name=${next.name}")
    }

    
    private fun applyTemplateForSurface(next: BoardSurface, noteRef: String?) {
        val template = TemplateStore.board(host.context)
        val pageWidth = if (next.showsBoardChrome) 0f else ScrollingDocument.WIDTH
        content.setBackgroundTemplate(template, pageWidth)
        chrome.setCurrentTemplate(template)
    }

    private fun openNote(ref: String?) {
        if (suspendedBoard != null || surfaceSwitching || ref.isNullOrBlank() || !ref.matches(Regex("note-[a-zA-Z0-9-]+")) || notesDirectory.isBlank()) return
        val sourceCard = synchronized(BoardEngine.lock) { BoardEngine.cards.values.firstOrNull { it.noteRef == ref } } ?: return
        val suspended = SuspendedBoard(BoardEngine.snapshotScene(), history, lasso)
        surfaceSwitching = true
        WhiteboardSceneGate.hold()
        noteController.open(notesDirectory, ref, create = false) { result ->
            surfaceSwitching = false
            result.onFailure { WhiteboardSceneGate.release(); releasePaintHold("note-open-failed"); Log.e(TAG, "note open failed", it) }
            result.onSuccess { doc ->
                suspendedBoard = suspended
                history = BoardHistory(); lasso = null
                currentNoteTitle = sourceCard.title
                val note = NoteSurface(noteController, host.context, { host.height / density }, ::toolbarHeightWorld, ::closeNote)
                
                
                
                
                val scale = minOf(1f, (host.width / density) / ScrollingDocument.WIDTH)
                val panY = toolbarHeightWorld() - doc.clampScroll(doc.scrollY, host.height / density)
                val scene = BoardEngine.SceneSnapshot(doc.strokes, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0f, panY, scale, null)
                enterSurface(note, scene)
                releasePaintHold("note-opened")
                Log.i(TAG, "note opened ref=$ref height=${doc.contentHeight} scrollY=${doc.scrollY}")
            }
        }
    }

    private fun closeNote() {
        val suspended = suspendedBoard ?: return
        if (surfaceSwitching) return
        surfaceSwitching = true
        cancelActiveInteractions("note-close")
        
        
        val liveStrokes = synchronized(BoardEngine.lock) { BoardEngine.strokes.values.toList() }
        
        val compacted = ScrollingDocument.compact(liveStrokes)
        if (compacted.isNotEmpty() || noteController.document?.strokes?.isEmpty() != false) {
            noteController.changed(compacted)
        }
        noteController.flush(true) { result ->
            surfaceSwitching = false
            result.onFailure { Log.e(TAG, "note save failed; keeping note open", it) }
            result.onSuccess { doc ->
                suspendedBoard = null
                history = suspended.history
                CardImageCache.invalidate(noteController.previewPath(doc.ref))
                enterSurface(whiteboard, suspended.scene)
                setLasso(suspended.lasso)
                WhiteboardSceneGate.release()
                val card = synchronized(BoardEngine.lock) { BoardEngine.cards.values.firstOrNull { it.noteRef == doc.ref } }
                if (card != null) {
                    val size = FloatArray(2); noteCardBoardSize(doc.contentHeight, doc.contentWidth, card.width, size)
                    val resized = card.withRect(RectF(card.x, card.y, card.x + size[0], card.y + size[1]))
                    BoardEngine.mutate { invalidateAll(); upsertCard(resized) }; emitter.cardUpsert(resized)
                }
                scheduleChromeUpdate(); Log.i(TAG, "note closed ref=${doc.ref} height=${doc.contentHeight}")
            }
        }
    }

    var touchEnabled = true
        private set

    
    private var eraserMode = false
    private var lassoMode = false
    
    private var shapeMode = false
    private var shapeKind = Shapes.Kind.RECT
    
    private var penStyle: PenStyle = PenStyle.PEN
    
    private var penWidth = PenPopup.WIDTHS[PenPopup.DEFAULT_INDEX]

    fun setPenStyle(style: PenStyle, width: Float) {
        penStyle = style
        penWidth = width.coerceIn(1f, 32f)
        host.setPenStyle(style, penWidth)
    }

    
    private val selectedCardId: String?
        get() = lasso?.let { if (isSingleCardSelection(it)) it.cardIds.firstOrNull() else null }

    
    private fun isSingleCardSelection(sel: LassoSelection): Boolean {
        if (sel.cardIds.size != 1) return false
        if (sel.strokeIds.isEmpty()) return true
        val id = sel.cardIds[0]
        return sel.strokeIds.all { BoardEngine.strokes[it]?.cardId == id }
    }

    
    private fun selectSingleCard(card: BoardEngine.CardRec) {
        val frame = card.rect(RectF()).apply { inset(-LASSO_BOUNDS_PAD, -LASSO_BOUNDS_PAD) }
        setLasso(LassoSelection(frame, listOf(card.id), emptyList()))
    }

    
    fun selectCards(ids: List<String>) {
        val cards = synchronized(BoardEngine.lock) { ids.mapNotNull { BoardEngine.cards[it] } }
        if (cards.isEmpty()) return
        if (cards.size == 1) { selectSingleCard(cards[0]); return }
        val frame = RectF()
        val r = RectF()
        for (c in cards) { c.rect(r); if (frame.isEmpty) frame.set(r) else frame.union(r) }
        frame.inset(-LASSO_BOUNDS_PAD, -LASSO_BOUNDS_PAD)
        setLasso(LassoSelection(frame, cards.map { it.id }, emptyList()))
        Log.i(TAG_LASSO, "selectCards from JS count=${cards.size}")
    }

    
    private var selectBelowArmed = false
    private var selectBelowPendingRestore = false

    class LassoSelection(val rect: RectF, val cardIds: List<String>, val strokeIds: List<String>)
    private var lasso: LassoSelection? = null

    
    private var penContact = false
    private var penHovering = false
    private var penHoverX = 0f
    private var penHoverY = 0f
    
    private var trailWhite = false
    private val trailScratchRect = RectF()
    
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
        
        class Shape(val kind: Shapes.Kind, val anchorX: Float, val anchorY: Float) : PenSession() {
            var curX = anchorX
            var curY = anchorY
        }
        object Consumed : PenSession()
        class OpenNote(val ref: String, val downX: Float, val downY: Float) : PenSession()
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
            var frames = 0
            var overscroll: SparseNavigation.Overscroll? = null
            
            var lastCenterX = startCenterX
            var lastCenterY = startCenterY
        }
        object ScreenTool : FingerGesture()
        class Move(val fingerId: Int) : FingerGesture()
        class NoteTap(val fingerId: Int, val card: BoardEngine.CardRec, val downX: Float, val downY: Float) : FingerGesture()
        
        class SurfaceScroll(val fingerId: Int, val startY: Float, val startPanY: Float) : FingerGesture()
        
        class EdgeSwipe(val fingerId: Int, val downX: Float, val downY: Float) : FingerGesture()
    }

    private var fingerGesture: FingerGesture = FingerGesture.Idle
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

    
    private sealed class MoveSession(val isPen: Boolean, val pointerId: Int, val startWorldX: Float, val startWorldY: Float) {
        var rawMoves = 0
        val startedAt = SystemClock.uptimeMillis()

        class CardDrag(isPen: Boolean, pointerId: Int, sx: Float, sy: Float, val card: BoardEngine.CardRec, val liftZ: Int) : MoveSession(isPen, pointerId, sx, sy) {
            var dragging = false
        }
        class CardResize(isPen: Boolean, pointerId: Int, sx: Float, sy: Float, val card: BoardEngine.CardRec, val handle: BoardGeometry.Handle) : MoveSession(isPen, pointerId, sx, sy) {
            val preview = RectF()
            
            val startRect = RectF()
            
            var inkBounds: RectF? = null
        }
        class Selection(isPen: Boolean, pointerId: Int, sx: Float, sy: Float, val selection: LassoSelection, val cards: List<BoardEngine.CardRec>, val strokes: List<BoardEngine.StrokeRec>) : MoveSession(isPen, pointerId, sx, sy) {
            var dx = 0f
            var dy = 0f
        }
        
        class SelectionResize(
            isPen: Boolean, pointerId: Int, sx: Float, sy: Float,
            val selection: LassoSelection,
            val handle: BoardGeometry.Handle,
            val cards: List<BoardEngine.CardRec>,
            val strokes: List<BoardEngine.StrokeRec>,
        ) : MoveSession(isPen, pointerId, sx, sy) {
            val startRect = RectF()
            val preview = RectF()
        }
        
        class SelectionRotate(
            isPen: Boolean, pointerId: Int, sx: Float, sy: Float,
            val selection: LassoSelection,
            val strokes: List<BoardEngine.StrokeRec>,
            val pivotX: Float,
            val pivotY: Float,
        ) : MoveSession(isPen, pointerId, sx, sy) {
            val startAngle = kotlin.math.atan2(sy - pivotY, sx - pivotX)
            var angle = 0f
        }
    }

    private var fingerMoveSession: MoveSession? = null

    
    private var regionsDirty = true
    private var regions: List<SparseNavigation.Region> = emptyList()
    private var chromeUpdatePosted = false
    private var einkApplied = false
    private var whiteboardAnchorGuardUntil = 0L
    private var launcherPullStartY = Float.NaN
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
        host.setPenStyle(penStyle, penWidth)
        arbiter.reset()
        syncToolMirrors(arbiter.effective(), "attach")
        chrome.setTouchEnabled(touchEnabled)
        
        applyTemplateForSurface(surface, if (surface.showsBoardChrome) null else noteController.document?.ref)
        
        exportLassoStrokes(null)
        
        
        if (MosaicSession.savedNoteRef(host.context) != null) {
            content.setHoldPaint(true)
            host.postDelayed(releasePaintHoldTask, NOTE_RESTORE_PAINT_HOLD_MS)
        }
        scheduleChromeUpdate()
    }

    private val releasePaintHoldTask = Runnable { releasePaintHold("timeout") }

    private fun releasePaintHold(reason: String) {
        host.removeCallbacks(releasePaintHoldTask)
        if (content.holdPaint) {
            content.setHoldPaint(false)
            Log.i(TAG, "first paint released reason=$reason surface=${surface.name}")
        }
    }

    fun detach() {
        releasePaintHold("detach")
        
        exportLassoStrokes(null)
        suspendedBoard?.let { suspended ->
            
            
            noteController.flush(false) { result -> result.onFailure { Log.e(TAG, "note detach save failed", it) } }
            suspendedBoard = null
            history = suspended.history
            lasso = null
            
            surface = whiteboard
            BoardEngine.replaceScene(suspended.scene)
            chrome.setNoteMode(false)
            WhiteboardSceneGate.release(); noteController.clear()
        }
        
        MosaicSession.saveViewport(host.context, BoardEngine.panX, BoardEngine.panY, BoardEngine.scale)
        surfaceSwitching = false
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
        content.setGestureFreezeTiles(false)
        content.setGestureThrottle(false)
        overlay.clearAll()
        cancelGestureSettle()
        content.clearSettleWaiters()
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
        
        
        if (reason == "viewport" && viewportGestureActive()) return
        scheduleChromeUpdate()
    }

    private fun viewportGestureActive(): Boolean =
        fingerGesture is FingerGesture.PanZoom || fingerGesture is FingerGesture.SurfaceScroll

    
    fun onDocumentReplaced() {
        history.clear()
        setLasso(null)
        regionsDirty = true
        scheduleChromeUpdate()
        
        
        val board = suspendedBoard?.scene
        emitter.begin()
        if (board != null) emitter.viewport(board.panX, board.panY, board.scale)
        else emitter.viewport(BoardEngine.panX, BoardEngine.panY, BoardEngine.scale)
        emitter.commit()
        restoreSessionNote()
    }

    

    private fun worldX(px: Float): Float = (px / density - BoardEngine.panX) / BoardEngine.scale
    private fun worldY(px: Float): Float = (px / density - BoardEngine.panY) / BoardEngine.scale

    
    private fun isOnDarkCard(wx: Float, wy: Float): Boolean =
        BoardGeometry.topCardAt(BoardEngine.cardsByZ, wx, wy)?.colored == true
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

    
    private fun inklingToolbarHit(viewX: Float, viewY: Float): Boolean =
        InklingLink.consumesScreenPoint(viewX + viewOffsetX, viewY + viewOffsetY)

    
    fun closeForInkling() {
        if (surfaceSwitching) return
        if (suspendedBoard == null) {
            surface.onCloseRequested(BoardSurface.CloseSource.TOOLBAR)
            return
        }
        
        surfaceSwitching = true
        cancelActiveInteractions("inkling-close")
        val liveStrokes = synchronized(BoardEngine.lock) { BoardEngine.strokes.values.toList() }
        val compacted = ScrollingDocument.compact(liveStrokes)
        if (compacted.isNotEmpty() || noteController.document?.strokes?.isEmpty() != false) {
            noteController.changed(compacted)
        }
        noteController.flush(true) { result ->
            surfaceSwitching = false
            result.onFailure { Log.e(TAG, "note save failed on inkling close", it) }
            result.onSuccess { doc -> CardImageCache.invalidate(noteController.previewPath(doc.ref)) }
            whiteboard.onCloseRequested(BoardSurface.CloseSource.TOOLBAR)
        }
    }

    

    
    private fun apply(change: BoardHistory.Change, record: Boolean, forward: Boolean = true) {
        if (change.isEmpty) return
        val emitCommands = surface.emitsCommands
        if (emitCommands) emitter.begin()
        try {
            BoardEngine.mutate {
                for (diff in change.diffs) {
                    when (diff) {
                        is BoardHistory.Diff.Stroke -> {
                            val target = if (forward) diff.after else diff.before
                            val source = if (forward) diff.before else diff.after
                            if (target == null) {
                                source?.let { removeStroke(it.id); if (emitCommands) emitter.strokesRemove(listOf(it.id)) }
                            } else {
                                addStroke(target); if (emitCommands) emitter.strokeUpsert(target)
                            }
                        }
                        is BoardHistory.Diff.Card -> {
                            val target = if (forward) diff.after else diff.before
                            val source = if (forward) diff.before else diff.after
                            if (target == null) {
                                source?.let { removeCard(it.id); if (emitCommands) emitter.cardsRemove(listOf(it.id)) }
                            } else {
                                upsertCard(target); if (emitCommands) emitter.cardUpsert(target)
                            }
                        }
                        is BoardHistory.Diff.Connection -> {
                            val target = if (forward) diff.after else diff.before
                            val source = if (forward) diff.before else diff.after
                            if (target == null) {
                                source?.let { removeConnection(it.id); if (emitCommands) emitter.connectionsRemove(listOf(it.id)) }
                            } else {
                                addConnection(target); if (emitCommands) emitter.connectionAdd(target)
                            }
                        }
                        is BoardHistory.Diff.Whiteboard -> {
                            val target = if (forward) diff.after else diff.before
                            val source = if (forward) diff.before else diff.after
                            if (target == null) {
                                source?.let { removeWhiteboard(it.id); if (emitCommands) emitter.whiteboardsRemove(listOf(it.id)) }
                            } else {
                                upsertWhiteboard(target); if (emitCommands) emitter.whiteboardUpsert(target)
                            }
                        }
                    }
                }
            }
        } finally {
            if (emitCommands) emitter.commit()
        }
        if (record) history.push(change)
        surface.onSceneMutated()
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
        surface.onViewportCommitted(panX, panY, scale)
        
        if (surface.showsBoardChrome) MosaicSession.saveViewport(host.context, panX, panY, scale)
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
            is PenSession.Shape -> { penSession = null; overlay.hideShapePreview() }
            else -> {}
        }
        if (!t.after.lasso && !t.after.shape && !t.keepSelection) setLasso(null)
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
                overlay.showEraserCursor(penLastX, penLastY, BoardEngine.scale, isOnDarkCard(wx, wy))
                Log.i(TAG_PEN, "contact converted to eraser source=$source replayedPoints=$replayed")
            }
            state.lasso -> {
                val session = PenSession.Lasso()
                session.pointsPx.add(penLastX); session.pointsPx.add(penLastY)
                penSession = session
                Log.i(TAG_PEN, "contact converted to lasso source=$source")
            }
            state.shape -> {
                penSession = PenSession.Shape(shapeKind, penLastX, penLastY)
                Log.i(TAG_PEN, "contact converted to shape source=$source")
            }
            else -> {
                
                if (lasso != null) { penSession = PenSession.Consumed; return }
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
        shapeMode = state.shape
        
        val showLasso = selectBelowPendingRestore
        chrome.setInkTool(state.eraser && !showLasso, state.lasso || showLasso, state.shape && !showLasso)
        chrome.setCurrentShape(if (shapeMode) shapeKind else null)
        
        host.inkEnabled = !eraserMode && lasso == null && !chrome.blocksPen && !selectBelowArmed
        
        host.shapeDrag = shapeMode
        host.lassoEnabled = (lassoMode || shapeMode) && lasso == null
        if (!eraserMode) overlay.hideEraserCursor()
        if (eraserMode || ((lassoMode || shapeMode) && lasso == null)) {
            requestEink(MosaicEinkRefreshModule.MODE_DUX)
        } else if (fingerGesture is FingerGesture.Idle && fingerMoveSession == null && penMoveSession == null) {
            
            
            scheduleGestureSettle()
        }
    }

    private fun refreshHostToolFlags() = syncToolMirrors(arbiter.effective(), "flags")

    private fun gestureToolOf(tool: GestureSettings.GestureTool): ToolArbiter.Tool? = when (tool) {
        GestureSettings.GestureTool.ERASER -> ToolArbiter.Tool.ERASER
        GestureSettings.GestureTool.LASSO -> ToolArbiter.Tool.LASSO
        GestureSettings.GestureTool.OFF -> null
    }

    

    
    
    private fun requestEink(mode: Int) {
        einkApplied = true
        content.setOutlineEmphasis(true)
        MosaicEinkRefreshModule.applyNative(mode, EINK_OWNER)
    }

    private fun resetEink() {
        content.setOutlineEmphasis(false)
        if (!einkApplied) return
        einkApplied = false
        MosaicEinkRefreshModule.resetNative(EINK_OWNER)
    }

    
    private fun holdGestureEink(mode: Int) {
        cancelGestureSettle()
        requestEink(mode)
    }

    
    private fun scheduleGestureSettle() {
        if (lasso != null) return
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
        val wasSuspend = content.suspendTranslucent
        if (wasSuspend) {
            content.setSuspendTranslucent(false)
            Log.i(TAG_PAN, "settle: restore translucent then reset eink")
        } else {
            Log.i(TAG_PAN, "settle: reset eink")
        }
        finish()
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
        if (exit) {
            overlay.hideEraserCursor()
            return
        }
        if (penContact) return
        if (eraserMode) {
            overlay.showEraserCursor(penHoverX, penHoverY, BoardEngine.scale,
                isOnDarkCard(worldX(penHoverX), worldY(penHoverY)))
        }
        
        reevaluateTrailColor("hover")
    }

    
    private fun reevaluateTrailColor(reason: String) {
        val want = trailWantsWhite(worldX(penHoverX), worldY(penHoverY))
        if (want == trailWhite) return
        trailWhite = want
        host.setTrailWhite(want, reason)
    }

    private fun trailWantsWhite(wx: Float, wy: Float): Boolean {
        val h = TRAIL_HYSTERESIS_DP / BoardEngine.scale
        val top = BoardGeometry.topCardAt(BoardEngine.cardsByZ, wx, wy)
        if (top != null) {
            if (!top.colored) return false
            if (trailWhite) return true
            val r = top.rect(trailScratchRect)
            r.inset(h, h)
            return r.contains(wx, wy)
        }
        
        if (!trailWhite) return false
        for (c in BoardEngine.cardsByZ) {
            if (!c.colored) continue
            val r = c.rect(trailScratchRect)
            r.inset(-h, -h)
            if (r.contains(wx, wy)) return true
        }
        return false
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
        if (surfaceSwitching || chrome.consumesPoint(x, y) || inklingToolbarHit(x, y)) {
            Log.i(
                TAG_PEN,
                "DOWN rejected: ${when {
                    surfaceSwitching -> "surface-switching"
                    inklingToolbarHit(x, y) -> "inkling-toolbar"
                    else -> "chrome"
                }}",
            )
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

        
        if (selectBelowArmed) {
            handleSelectBelow(wy)
            penSession = PenSession.Consumed
            return
        }

        
        val currentLasso = lasso
        if (currentLasso != null) {
            
            val single = if (isSingleCardSelection(currentLasso)) BoardEngine.cards[currentLasso.cardIds[0]] else null
            if (single != null) {
                val handle = singleCardFrameHandle(currentLasso, wx, wy)
                if (handle != null) {
                    penMoveSession = beginResize(true, e.getPointerId(0), wx, wy, single, handle)
                    penSession = PenSession.Consumed
                    return
                }
            }
            
            if (overlay.rotateHandleHit(x, y, LASSO_ACTION_TAP_SLOP_DP * density) && selectionRotatable(currentLasso)) {
                penMoveSession = beginSelectionRotate(true, e.getPointerId(0), wx, wy, currentLasso)
                penSession = PenSession.Consumed
                return
            }
            
            val cornerHandle = selectionCornerHandle(currentLasso, wx, wy)
                ?: (if (shapeKindOf(currentLasso) != null) shapeEdgeHandle(currentLasso, wx, wy) else null)
            if (cornerHandle != null) {
                penMoveSession = beginSelectionResize(true, e.getPointerId(0), wx, wy, currentLasso, cornerHandle)
                penSession = PenSession.Consumed
                return
            }
            val action = overlay.lassoActionHit(x, y, LASSO_ACTION_TAP_SLOP_DP * density)
            if (action != null) {
                handleLassoAction(action)
                penSession = PenSession.Consumed
                return
            }
            
            
            if (selectionHit(currentLasso, wx, wy) || overlay.moveHandleHit(x, y, LASSO_ACTION_TAP_SLOP_DP * density)) {
                penMoveSession = beginSelectionMove(true, e.getPointerId(0), wx, wy, currentLasso)
                penSession = PenSession.Consumed
                return
            }
            setLasso(null)
            if (lassoMode) {
                val session = PenSession.Lasso()
                session.pointsPx.add(x); session.pointsPx.add(y)
                penSession = session
                Log.i(TAG_LASSO, "pen DOWN outside selection: lasso tool redraws")
                return
            }
            if (shapeMode) {
                penSession = PenSession.Shape(shapeKind, x, y)
                Log.i(TAG_LASSO, "pen DOWN outside selection: shape tool draws")
                return
            }
            penSession = PenSession.Consumed
            Log.i(TAG_LASSO, "pen DOWN outside selection: cleared")
            return
        }

        if (eraserMode) {
            val session = PenSession.Erase(BoardHistory.Change("erase"))
            penSession = session
            eraseAt(session, wx, wy)
            flushErase(session)
            overlay.showEraserCursor(x, y, BoardEngine.scale, isOnDarkCard(wx, wy))
            return
        }

        if (lassoMode) {
            val session = PenSession.Lasso()
            session.pointsPx.add(x); session.pointsPx.add(y)
            penSession = session
            return
        }

        if (shapeMode) {
            penSession = PenSession.Shape(shapeKind, x, y)
            return
        }

        val card = BoardGeometry.topCardAt(BoardEngine.cardsByZ, wx, wy)
        if (card?.kind == "note") {
            
            
            host.setDrawPathSuspended(true, "note-preview-tap")
            penSession = PenSession.OpenNote(card.noteRef, wx, wy)
            Log.i(TAG_PEN, "DOWN on note preview: open on tap card=${card.id}")
            return
        }
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
                if (s.cardId == null && surface.allowsCardFrame) updateFrameGesture(s, wx, wy)
            }
            is PenSession.OpenNote -> {
                if (hypot(worldX(penX(e)) - s.downX, worldY(penY(e)) - s.downY) > BoardGeometry.CARD_DRAG_THRESHOLD_PX / BoardEngine.scale) { host.setDrawPathSuspended(false, "note-tap-cancel"); penSession = PenSession.Consumed }
            }
            is PenSession.Erase -> {
                for (i in 0 until e.historySize) eraseAt(s, worldX(penHistX(e, i)), worldY(penHistY(e, i)))
                eraseAt(s, worldX(penX(e)), worldY(penY(e)))
                flushErase(s)
                overlay.showEraserCursor(penX(e), penY(e), BoardEngine.scale,
                    isOnDarkCard(worldX(penX(e)), worldY(penY(e))))
            }
            is PenSession.Lasso -> {
                for (i in 0 until e.historySize) { s.pointsPx.add(penHistX(e, i)); s.pointsPx.add(penHistY(e, i)) }
                s.pointsPx.add(penX(e)); s.pointsPx.add(penY(e))
            }
            is PenSession.Shape -> {
                s.curX = penX(e); s.curY = penY(e)
                overlay.showShapePreview(shapePreviewPath(s))
            }
            else -> {}
        }
    }

    
    private fun shapePreviewPath(s: PenSession.Shape): Path {
        val pts = Shapes.fromDrag(s.kind, s.anchorX, s.anchorY, s.curX, s.curY)
        return BoardEngine.buildPolylinePath(pts)
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
                is PenSession.Shape -> {
                    overlay.hideShapePreview()
                    if (!cancelled) { s.curX = penX(e); s.curY = penY(e); finalizeShape(s) }
                }
                is PenSession.OpenNote -> { host.setDrawPathSuspended(false, "note-tap-end"); if (!cancelled) openNote(s.ref) }
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
        
        val ink = BoardEngine.StrokeRec.contrastInk(INK_BLACK, s.cardId?.let { BoardEngine.cards[it] })
        val rec = BoardEngine.StrokeRec(s.id, s.space, penWidth, ink, s.points.toArray(), s.pressures.toArray(), penStyle.objType, density * BoardEngine.scale)
        apply(BoardHistory.Change("draw").stroke(null, rec), record = true)
        if (ink != INK_BLACK && !trailWhite) Log.i(TAG_PEN, "[MosaicTrail] color mismatch: committed white but hardware trail was black stroke=${s.id}")
        Log.i(TAG_PEN, "UP committed stroke ${s.id} points=${s.points.size / 2} space=${s.space} ink=${Integer.toHexString(ink)}")
    }

    

    private val hitBuffer = ArrayList<BoardEngine.StrokeRec>()

    
    private fun eraseAt(session: PenSession.Erase, wx: Float, wy: Float) {
        hitBuffer.clear()
                synchronized(BoardEngine.lock) { BoardEngine.hitTestStrokes(wx, wy, ERASER_HIT_RADIUS / BoardEngine.scale, hitBuffer) }
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
            
            
            val rejected = when {
                !BoardGeometry.canConnect(source, target) -> "color-mismatch"
                BoardGeometry.edgeDistance(source, target) > NeckGeometry.MAX_DISTANCE -> "too-far"
                else -> null
            }
            if (rejected != null) {
                Log.i(TAG_PEN, "card connection rejected source=$sourceId target=${target.id} reason=$rejected")
                return true
            }
            if (BoardEngine.connectionBetween(sourceId, target.id) == null) {
                apply(BoardHistory.Change("connect").connection(null, BoardEngine.ConnectionRec(newId("conn-"), sourceId, target.id)), record = true)
            }
            return true
        }
        val rect = BoardGeometry.cardFromStrokeRect(source, endX, endY)
        
        val created = BoardEngine.CardRec(
            newId("card-"), rect.left, rect.top, rect.width(), rect.height(), BoardGeometry.nextZIndex(BoardEngine.cards.values),
            bgColor = source.bgColor, textColor = source.textColor,
        )
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
            val wholeCards = cardIds.toHashSet()

            val canvas = ArrayList<BoardEngine.StrokeRec>()
            BoardEngine.canvasStrokesInside(rect, canvas)
            for (s in canvas) strokeIds.add(s.id)
            
            for (s in BoardEngine.strokes.values) {
                val cardId = s.cardId ?: continue
                if (cardId in wholeCards || !BoardEngine.cards.containsKey(cardId)) continue
                if (rect.contains(BoardEngine.strokeWorldBounds(s))) strokeIds.add(s.id)
            }

            if (strokeIds.isEmpty()) {
                for (card in BoardEngine.cardsByZ) {
                    if (card.id in wholeCards) continue
                    if (RectF.intersects(rect, card.rect(r))) cardIds.add(card.id)
                }
            }
        }
        if (cardIds.isEmpty() && strokeIds.isEmpty()) return null
        return LassoSelection(rect, cardIds, strokeIds)
    }

    private fun setLasso(next: LassoSelection?) {
        
        
        if (next == null && selectBelowPendingRestore) {
            selectBelowPendingRestore = false
            Log.i(TAG_LASSO, "select-below selection cleared -> toolbar back to ${arbiter.describe()}")
        }
        lasso = next
        exportLassoStrokes(next)
        BoardEngine.mutate { setSelection(selectionIds()) }
        updateLassoOverlay()
        refreshHostToolFlags()
        scheduleChromeUpdate()
        if (next == null) scheduleGestureSettle()
        if (next != null) {
            
            
            handler.post {
                if (!penContact) {
                    requestEink(MosaicEinkRefreshModule.MODE_DUX)
                    overlay.invalidate()
                    content.markSettleRequested()
                    content.postInvalidateOnAnimation()
                    host.invalidate()
                }
            }
        }
    }

    
    private fun selectionIds(): List<String> {
        val sel = lasso ?: return emptyList()
        return if (isSingleCardSelection(sel)) emptyList() else sel.cardIds.toList()
    }

    private fun updateLassoOverlay() {
        val current = lasso
        if (current == null) {
            overlay.setLassoFrame(null)
            return
        }
        val framePx = worldRectToPx(current.rect, RectF())
        val singleCard = isSingleCardSelection(current)
        val shapeKind = shapeKindOf(current)
        
        
        val kind = if (singleCard) BoardEngine.cards[current.cardIds[0]]?.kind else null
        val types = when {
            
            singleCard && (kind == "image" || kind == "note") -> listOf("trash")
            singleCard -> listOf("trash", "note", "black")
            shapeKind != null -> listOf("trash") + shapeActionTypes(shapeKind)
            else -> listOf("trash")
        }
        
        val onDark = singleCard && (BoardEngine.cards[current.cardIds[0]]?.colored == true)
        overlay.topInsetPx = chrome.toolbarHeightPx().toFloat()
        overlay.setLassoFrame(framePx, lassoActionRects(framePx, types),
            cornerHandles = !singleCard || kind == "image",
            edgeHandles = singleCard || shapeKind != null, onDark = onDark,
            rotateHandle = selectionRotatable(current),
            horizontalEdgesOnly = kind == "note")
    }

    
    private fun shapeKindOf(sel: LassoSelection): Shapes.Kind? {
        if (sel.cardIds.isNotEmpty() || sel.strokeIds.size != 1) return null
        val stroke = BoardEngine.strokes[sel.strokeIds[0]] ?: return null
        return Shapes.kindOf(stroke)
    }

    private fun shapeActionTypes(kind: Shapes.Kind): List<String> = when (kind) {
        Shapes.Kind.RECT -> listOf("square")
        Shapes.Kind.ELLIPSE -> listOf("circle")
        Shapes.Kind.TRIANGLE -> listOf("iso", "equi", "right")
        Shapes.Kind.LINE -> emptyList()
    }

    
    private fun selectionRotatable(sel: LassoSelection): Boolean =
        sel.strokeIds.isNotEmpty() && !isSingleCardSelection(sel)

    
    private fun lassoActionRects(frame: RectF, types: List<String>): List<Pair<RectF, String>> {
        val d = density
        val size = InteractionOverlayView.LASSO_ACTION_SIZE_DP * d
        val gap = InteractionOverlayView.LASSO_ACTION_GAP_DP * d
        val spacing = 6f * d
        val margin = InteractionOverlayView.LASSO_ACTION_MARGIN_DP * d
        val w = host.width.toFloat()
        val h = host.height.toFloat()
        val sideTop = min(max(frame.top + 4f * d, margin), max(margin, h - size - margin))
        
        val right = frame.right + gap
        val baseX: Float
        val baseY: Float
        if (right >= margin && right + size + margin <= w) {
            baseX = right; baseY = sideTop
        } else {
            val left = frame.left - gap - size
            if (left >= margin) { baseX = left; baseY = sideTop }
            else { baseX = min(max(frame.centerX() - size / 2f, margin), max(margin, w - size - margin)); baseY = min(max(frame.bottom + gap, margin), max(margin, h - size - margin)) }
        }
        return types.mapIndexed { i, t ->
            val top = baseY + i * (size + spacing)
            Pair(RectF(baseX, top, baseX + size, top + size), t)
        }
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

    private fun handleLassoAction(action: String) {
        when (action) {
            "trash" -> deleteLassoSelection()
            "note" -> onConvertCardToNote()
            "black" -> toggleSelectedCardBlack()
            "square" -> convertShape(action) { Shapes.toSquare(it) }
            "circle" -> convertShape(action) { Shapes.toCircle(it) }
            "iso" -> convertShape(action) { Shapes.toTriangle(it, Shapes.TriangleForm.ISOSCELES) }
            "equi" -> convertShape(action) { Shapes.toTriangle(it, Shapes.TriangleForm.EQUILATERAL) }
            "right" -> convertShape(action) { Shapes.toTriangle(it, Shapes.TriangleForm.RIGHT) }
        }
    }

    

    
    private fun finalizeShape(s: PenSession.Shape) {
        val dragPx = hypot(s.curX - s.anchorX, s.curY - s.anchorY)
        if (dragPx < SHAPE_MIN_DRAG_DP * density) {
            Log.i(TAG_LASSO, "shape: drag too short ${dragPx}px")
            return
        }
        val ax = worldX(s.anchorX); val ay = worldY(s.anchorY)
        val shape = Shapes.fromDrag(s.kind, ax, ay, worldX(s.curX), worldY(s.curY))
        val card = BoardGeometry.topCardAt(BoardEngine.cardsByZ, ax, ay)
        if (card != null) {
            var i = 0
            while (i < shape.size) { shape[i] -= card.x; shape[i + 1] -= card.y; i += 2 }
        }
        val ink = BoardEngine.StrokeRec.contrastInk(INK_BLACK, card)
        val rec = BoardEngine.StrokeRec(
            newId("shape-"), if (card == null) "canvas" else "card:${card.id}", penWidth, ink,
            shape, Shapes.pressures(shape.size / 2), penStyle.objType, density * BoardEngine.scale,
        )
        apply(BoardHistory.Change("shape").stroke(null, rec), record = true)
        val kind = Shapes.kindOf(rec)
        Log.i(TAG_LASSO, "shape: ${kind} points=${shape.size / 2} card=${card?.id ?: "canvas"}")
        selectStroke(rec)
    }

    
    private fun selectStroke(rec: BoardEngine.StrokeRec) {
        val frame = synchronized(BoardEngine.lock) { RectF(BoardEngine.strokeWorldBounds(rec)) }
        frame.inset(-LASSO_BOUNDS_PAD, -LASSO_BOUNDS_PAD)
        setLasso(LassoSelection(frame, emptyList(), listOf(rec.id)))
    }

    
    private fun convertShape(action: String, transform: (FloatArray) -> FloatArray) {
        val sel = lasso ?: return
        val kind = shapeKindOf(sel) ?: return
        val stroke = BoardEngine.strokes[sel.strokeIds[0]] ?: return
        val next = stroke.withPoints(transform(stroke.points))
        if (next.points.contentEquals(stroke.points)) return
        apply(BoardHistory.Change("shape-$action").stroke(stroke, next), record = true)
        Log.i(TAG_LASSO, "shape convert $kind -> $action")
        selectStroke(next)
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

    
    fun clearLassoForInkling(delete: Boolean) {
        val sel = lasso
        if (sel == null) {
            exportLassoStrokes(null)
            Log.i(TAG_LASSO, "inkling clear-selection: no selection, file removed")
            return
        }
        if (delete) {
            deleteLassoSelection()
        } else {
            setLasso(null)
        }
        Log.i(TAG_LASSO, "inkling clear-selection delete=$delete strokes=${sel.strokeIds.size} cards=${sel.cardIds.size}")
    }

    
    fun insertTextCardFromInkling(text: String, anchorScreenX: Int, anchorScreenY: Int) {
        refreshViewOffset()
        val wx = worldX((anchorScreenX - viewOffsetX).toFloat())
        val wy = worldY((anchorScreenY - viewOffsetY).toFloat())
        val size = BoardContentView.measureTextCardSize(text)
        emitter.action("insertTextCard") {
            putString("text", text)
            putDouble("x", wx.toDouble())
            putDouble("y", wy.toDouble())
            putDouble("width", size.x.toDouble())
            putDouble("height", size.y.toDouble())
        }
        Log.i(TAG, "inkling text card at world=($wx,$wy) size=${size.x}x${size.y} len=${text.length}")
    }

    private fun toggleSelectedCardBlack() {
        val card = selectedCardId?.let { BoardEngine.cards[it] } ?: return
        if (card.kind == "image" || card.kind == "note") return
        val isBlack = card.bgColor.equals("#000000", ignoreCase = true)
        val newBg = if (isBlack) "" else "#000000"
        val newText = if (isBlack) "" else "#ffffff"
        val moved = card.withColors(newBg, newText)
        val change = BoardHistory.Change("card-color").card(card, moved)
        
        var recolored = 0
        BoardEngine.cardStrokes[card.id]?.let { attached ->
            for (s in ArrayList(attached)) {
                val next = s.withColor(BoardEngine.StrokeRec.contrastInk(s.color, moved))
                if (next !== s) { change.stroke(s, next); recolored++ }
            }
        }
        val relinked = reconcileConnectionsForColor(moved, change)
        apply(change, record = true)
        
        if (penHovering && !penContact) reevaluateTrailColor("card-color")
        Log.i(TAG, "toggle card black id=${card.id} wasBlack=$isBlack recoloredStrokes=$recolored connections=$relinked")
    }

    
    private fun reconcileConnectionsForColor(next: BoardEngine.CardRec, change: BoardHistory.Change): String {
        var removed = 0
        var added = 0
        synchronized(BoardEngine.lock) {
            val existing = ArrayList<BoardEngine.ConnectionRec>()
            BoardEngine.connectionsOf(next.id, existing)
            val linked = HashSet<String>()
            for (c in existing) {
                val otherId = c.otherEnd(next.id) ?: continue
                val other = BoardEngine.cards[otherId]
                if (other == null || !BoardGeometry.canConnect(next, other)) {
                    change.connection(c, null)
                    removed++
                } else {
                    linked.add(otherId)
                }
            }
            for (other in BoardEngine.cardsByZ) {
                if (other.id in linked || !BoardGeometry.canConnect(next, other)) continue
                if (BoardGeometry.edgeDistance(next, other) >= BoardGeometry.CONNECT_DISTANCE) continue
                change.connection(null, BoardEngine.ConnectionRec(newId("conn-"), next.id, other.id))
                added++
            }
        }
        return "-$removed/+$added"
    }

    

    
    private fun exportLassoStrokes(sel: LassoSelection?) {
        val file = File(InklingLink.CLIP_LASSO_FILE)
        if (sel == null) { file.delete(); return }
        val out = LinkedHashMap<String, BoardEngine.StrokeRec>()
        synchronized(BoardEngine.lock) {
            for (id in sel.strokeIds) BoardEngine.strokes[id]?.let { out[it.id] = it }
            for (cardId in sel.cardIds) BoardEngine.cardStrokes[cardId]?.forEach { out[it.id] = it }
        }
        if (out.isEmpty()) { file.delete(); return }
        val strokesJson = JSONArray()
        val d = density
        synchronized(BoardEngine.lock) {
            for (rec in out.values) {
                val ox = rec.cardId?.let { BoardEngine.cards[it]?.x } ?: 0f
                val oy = rec.cardId?.let { BoardEngine.cards[it]?.y } ?: 0f
                val pts = JSONArray()
                var i = 0
                var pi = 0
                while (i < rec.points.size) {
                    pts.put(((rec.points[i] + ox) * d).toDouble())
                    pts.put(((rec.points[i + 1] + oy) * d).toDouble())
                    pts.put((if (pi < rec.pressures.size) rec.pressures[pi] else 1f).toDouble())
                    i += 2; pi++
                }
                strokesJson.put(
                    JSONObject()
                        .put("penStyle", rec.penStyle)
                        .put("width", (rec.width * d).toDouble())
                        .put("pts", pts),
                )
            }
        }
        val root = JSONObject().put("v", 1).put("unit", "px").put("strokes", strokesJson)
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
        }.onFailure { Log.w(TAG_LASSO, "exportLassoStrokes failed", it) }
        Log.i(TAG_LASSO, "exported ${strokesJson.length()} strokes -> ${file.path}")
    }

    
    fun handlePasteStrokes() {
        val file = File(InklingLink.PASTE_STROKES_FILE)
        val text = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
        if (text.isNullOrBlank()) { Log.i(TAG_LASSO, "paste: no file"); return }
        val strokesJson = runCatching { JSONObject(text).optJSONArray("strokes") }.getOrNull()
        if (strokesJson == null || strokesJson.length() == 0) { file.delete(); return }

        
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (s in 0 until strokesJson.length()) {
            val pts = strokesJson.getJSONObject(s).optJSONArray("pts") ?: continue
            var i = 0
            while (i + 1 < pts.length()) {
                val x = pts.getDouble(i).toFloat(); val y = pts.getDouble(i + 1).toFloat()
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                i += 3
            }
        }
        if (minX > maxX) { file.delete(); return }
        val bboxCx = (minX + maxX) / 2f
        val bboxCy = (minY + maxY) / 2f
        val viewCx = (host.width / density / 2f - BoardEngine.panX) / BoardEngine.scale
        val viewCy = (host.height / density / 2f - BoardEngine.panY) / BoardEngine.scale
        val d = density
        
        
        val worldScale = PASTE_SCALE / (d * BoardEngine.scale)

        val change = BoardHistory.Change("paste")
        val pastedIds = ArrayList<String>()
        for (s in 0 until strokesJson.length()) {
            val obj = strokesJson.getJSONObject(s)
            val pts = obj.optJSONArray("pts") ?: continue
            if (pts.length() < 6) continue
            val n = pts.length() / 3
            val worldPts = FloatArray(n * 2)
            val pressures = FloatArray(n)
            var i = 0; var w = 0; var p = 0
            while (i + 2 < pts.length()) {
                val px = pts.getDouble(i).toFloat(); val py = pts.getDouble(i + 1).toFloat()
                worldPts[w] = viewCx + (px - bboxCx) * worldScale
                worldPts[w + 1] = viewCy + (py - bboxCy) * worldScale
                pressures[p] = pts.getDouble(i + 2).toFloat().coerceIn(0f, 1f)
                i += 3; w += 2; p++
            }
            val penStyle = obj.optInt("penStyle", PenStyle.PEN.objType)
            val width = obj.optDouble("width", PEN_WIDTH_DP.toDouble()).toFloat() * worldScale
            val id = UUID.randomUUID().toString()
            val rec = BoardEngine.StrokeRec(
                id, "canvas", width, INK_BLACK,
                worldPts, pressures, penStyle, d * BoardEngine.scale,
            )
            change.stroke(null, rec)
            pastedIds.add(id)
        }
        file.delete()
        if (pastedIds.isEmpty()) return
        apply(change, record = true)
        
        selectPastedStrokes(pastedIds)
        requestEink(MosaicEinkRefreshModule.MODE_DUX)
        content.postInvalidateOnAnimation()
        host.invalidate()
        Log.i(
            TAG_LASSO,
            "pasted ${pastedIds.size} strokes at viewport center world=(${"%.0f".format(viewCx)},${"%.0f".format(viewCy)}) " +
                "size=${"%.0f".format((maxX - minX) * worldScale)}x${"%.0f".format((maxY - minY) * worldScale)} " +
                "pan=(${BoardEngine.panX},${BoardEngine.panY}) scale=${BoardEngine.scale} pasteScale=$PASTE_SCALE",
        )
    }

    
    private fun selectPastedStrokes(ids: List<String>) {
        if (ids.isEmpty()) return
        val frame = RectF()
        var any = false
        synchronized(BoardEngine.lock) {
            for (id in ids) {
                val stroke = BoardEngine.strokes[id] ?: continue
                val b = BoardEngine.strokeWorldBounds(stroke)
                if (!any) { frame.set(b); any = true } else frame.union(b)
            }
        }
        if (!any) return
        frame.inset(-LASSO_BOUNDS_PAD, -LASSO_BOUNDS_PAD)
        setLasso(LassoSelection(frame, emptyList(), ids.toList()))
    }

    
    private fun collectCardRemoval(cardId: String, change: BoardHistory.Change) {
        val card = BoardEngine.cards[cardId] ?: return
        BoardEngine.cardStrokes[cardId]?.forEach { change.stroke(it, null) }
        val conns = ArrayList<BoardEngine.ConnectionRec>()
        BoardEngine.connectionsOf(cardId, conns)
        for (c in conns) change.connection(c, null)
        change.card(card, null)
    }

    

    

    private fun beginCardDrag(isPen: Boolean, pointerId: Int, wx: Float, wy: Float, card: BoardEngine.CardRec): MoveSession.CardDrag {
        val session = MoveSession.CardDrag(isPen, pointerId, wx, wy, card, BoardGeometry.nextZIndex(BoardEngine.cards.values))
        holdGestureEink(MosaicEinkRefreshModule.MODE_DEFAULT)
        return session
    }

    private fun beginResize(isPen: Boolean, pointerId: Int, wx: Float, wy: Float, card: BoardEngine.CardRec, handle: BoardGeometry.Handle): MoveSession.CardResize {
        val session = MoveSession.CardResize(isPen, pointerId, wx, wy, card, handle)
        card.rect(session.preview)
        card.rect(session.startRect)
        session.inkBounds = attachedInkWorldBounds(card.id)
        hideCardFromTiles(card.id)
        overlay.showCardPreview(worldRectToPx(session.preview, RectF()), true)
        holdGestureEink(MosaicEinkRefreshModule.MODE_DEFAULT)
        Log.i(TAG, "[CardAdjust] resize begin card=${card.id} handle=$handle corner=${handle.isCorner} pen=$isPen")
        return session
    }

    
    private fun attachedInkWorldBounds(cardId: String): RectF? {
        var out: RectF? = null
        synchronized(BoardEngine.lock) {
            BoardEngine.cardStrokes[cardId]?.forEach {
                val b = BoardEngine.strokeWorldBounds(it)
                if (out == null) out = RectF(b) else out!!.union(b)
            }
        }
        return out
    }

    
    private fun computeResizePreview(m: MoveSession.CardResize, wx: Float, wy: Float) {
        when {
            m.card.kind == "note" ->
                BoardGeometry.resizeWidthKeepAspect(m.handle, m.startWorldX, m.startWorldY, m.startRect, wx, wy, m.card.kind, m.preview)
            m.card.kind == "image" && m.handle.isCorner ->
                BoardGeometry.resizeProportional(m.handle, m.startWorldX, m.startWorldY, m.startRect, wx, wy, m.card.kind, m.preview)
            else ->
                BoardGeometry.resizeRect(m.handle, m.startWorldX, m.startWorldY, m.startRect, wx, wy, m.card.kind, m.preview)
        }
        if (m.card.kind != "note") m.inkBounds?.let { ib ->
            if (m.handle.movesLeft) m.preview.left = min(m.preview.left, ib.left)
            if (m.handle.movesRight) m.preview.right = max(m.preview.right, ib.right)
            if (m.handle.movesTop) m.preview.top = min(m.preview.top, ib.top)
            if (m.handle.movesBottom) m.preview.bottom = max(m.preview.bottom, ib.bottom)
        }
    }

    
    private fun refreshSingleCardFrame() {
        val sel = lasso ?: return
        if (!isSingleCardSelection(sel)) return
        val card = BoardEngine.cards[sel.cardIds[0]] ?: return
        val frame = card.rect(RectF()).apply { inset(-LASSO_BOUNDS_PAD, -LASSO_BOUNDS_PAD) }
        lasso = LassoSelection(frame, sel.cardIds, sel.strokeIds)
        BoardEngine.mutate { setSelection(selectionIds()) }
        updateLassoOverlay()
    }

    
    private fun singleCardFrameHandle(sel: LassoSelection, wx: Float, wy: Float): BoardGeometry.Handle? {
        val radius = BoardGeometry.CARD_HANDLE_HIT_PX / max(BoardEngine.scale, 0.25f)
        val r = sel.rect
        val cx = r.centerX(); val cy = r.centerY()
        fun hit(ax: Float, ay: Float) = hypot(wx - ax, wy - ay) <= radius
        val kind = sel.cardIds.firstOrNull()?.let { BoardEngine.cards[it]?.kind }
        if (kind == "note") {
            return when {
                hit(r.right, cy) -> BoardGeometry.Handle.RIGHT
                hit(r.left, cy) -> BoardGeometry.Handle.LEFT
                else -> null
            }
        }
        if (kind == "image") {
            if (hit(r.right, r.top)) return BoardGeometry.Handle.TOP_RIGHT
            if (hit(r.right, r.bottom)) return BoardGeometry.Handle.BOTTOM_RIGHT
            if (hit(r.left, r.bottom)) return BoardGeometry.Handle.BOTTOM_LEFT
        }
        return when {
            hit(cx, r.top) -> BoardGeometry.Handle.TOP
            hit(r.right, cy) -> BoardGeometry.Handle.RIGHT
            hit(cx, r.bottom) -> BoardGeometry.Handle.BOTTOM
            hit(r.left, cy) -> BoardGeometry.Handle.LEFT
            else -> null
        }
    }

    
    private fun shapeEdgeHandle(sel: LassoSelection, wx: Float, wy: Float): BoardGeometry.Handle? {
        val radius = BoardGeometry.CARD_HANDLE_HIT_PX / max(BoardEngine.scale, 0.25f)
        val r = sel.rect
        val cx = r.centerX(); val cy = r.centerY()
        fun hit(ax: Float, ay: Float) = hypot(wx - ax, wy - ay) <= radius
        return when {
            hit(cx, r.top) -> BoardGeometry.Handle.TOP
            hit(r.right, cy) -> BoardGeometry.Handle.RIGHT
            hit(cx, r.bottom) -> BoardGeometry.Handle.BOTTOM
            hit(r.left, cy) -> BoardGeometry.Handle.LEFT
            else -> null
        }
    }

    
    private fun selectionCornerHandle(sel: LassoSelection, wx: Float, wy: Float): BoardGeometry.Handle? {
        if (isSingleCardSelection(sel)) return null
        val radius = BoardGeometry.CARD_HANDLE_HIT_PX / max(BoardEngine.scale, 0.25f)
        val r = sel.rect
        fun hit(ax: Float, ay: Float) = hypot(wx - ax, wy - ay) <= radius
        
        return when {
            hit(r.right, r.top) -> BoardGeometry.Handle.TOP_RIGHT
            hit(r.right, r.bottom) -> BoardGeometry.Handle.BOTTOM_RIGHT
            hit(r.left, r.bottom) -> BoardGeometry.Handle.BOTTOM_LEFT
            else -> null
        }
    }

    private fun beginSelectionResize(isPen: Boolean, pointerId: Int, wx: Float, wy: Float, sel: LassoSelection, handle: BoardGeometry.Handle): MoveSession.SelectionResize {
        host.setDrawPathSuspended(true, "lasso-selection-resize")
        val cards = ArrayList<BoardEngine.CardRec>()
        val strokes = ArrayList<BoardEngine.StrokeRec>()
        synchronized(BoardEngine.lock) {
            for (id in sel.cardIds) BoardEngine.cards[id]?.let { cards.add(it) }
            for (id in sel.strokeIds) BoardEngine.strokes[id]?.let { strokes.add(it) }
        }
        val session = MoveSession.SelectionResize(isPen, pointerId, wx, wy, sel, handle, cards, strokes)
        session.startRect.set(sel.rect)
        session.preview.set(sel.rect)
        
        val cardRects = ArrayList<RectF>()
        val inkPath = Path()
        collectSelectionPreview(sel, ArrayList(), ArrayList(), cardRects, inkPath)
        overlay.beginMovePreview(worldRectToPx(sel.rect, RectF()), cardRects, inkPath, PEN_WIDTH_DP * BoardEngine.scale * density)
        holdGestureEink(MosaicEinkRefreshModule.MODE_DEFAULT)
        Log.i(TAG_LASSO, "resize begin cards=${cards.size} strokes=${strokes.size} handle=$handle pen=$isPen")
        return session
    }

    
    private fun pushSelectionResizePreview(m: MoveSession.SelectionResize) {
        val start = m.startRect
        val fx = if (start.width() > 0f) m.preview.width() / start.width() else 1f
        val fy = if (start.height() > 0f) m.preview.height() / start.height() else 1f
        val pivotX = if (m.handle.movesLeft) start.right else start.left
        val pivotY = if (m.handle.movesTop) start.bottom else start.top
        val px = (pivotX * BoardEngine.scale + BoardEngine.panX) * density
        val py = (pivotY * BoardEngine.scale + BoardEngine.panY) * density
        overlay.updateScalePreview(fx, fy, px, py)
    }

    
    private fun computeSelectionResizePreview(m: MoveSession.SelectionResize, wx: Float, wy: Float) {
        val start = m.startRect
        val handle = m.handle
        val isEdge = handle == BoardGeometry.Handle.TOP || handle == BoardGeometry.Handle.BOTTOM ||
            handle == BoardGeometry.Handle.LEFT || handle == BoardGeometry.Handle.RIGHT
        if (isEdge) {
            val minSize = BoardGeometry.LASSO_MIN_BOX_PX / BoardEngine.scale
            val dx = wx - m.startWorldX
            val dy = wy - m.startWorldY
            var left = start.left; var top = start.top; var right = start.right; var bottom = start.bottom
            when (handle) {
                BoardGeometry.Handle.LEFT -> left = min(start.left + dx, start.right - minSize)
                BoardGeometry.Handle.RIGHT -> right = max(start.right + dx, start.left + minSize)
                BoardGeometry.Handle.TOP -> top = min(start.top + dy, start.bottom - minSize)
                BoardGeometry.Handle.BOTTOM -> bottom = max(start.bottom + dy, start.top + minSize)
                else -> {}
            }
            m.preview.set(left, top, right, bottom)
            return
        }
        val oldCornerX = if (handle.movesLeft) start.left else start.right
        val oldCornerY = if (handle.movesTop) start.top else start.bottom
        val ax = if (handle.movesLeft) start.right else start.left
        val ay = if (handle.movesTop) start.bottom else start.top
        val ox = oldCornerX - ax
        val oy = oldCornerY - ay
        val curX = oldCornerX + (wx - m.startWorldX)
        val curY = oldCornerY + (wy - m.startWorldY)
        val denom = ox * ox + oy * oy
        var f = if (denom > 0f) ((curX - ax) * ox + (curY - ay) * oy) / denom else 1f
        f = f.coerceIn(0.1f, 10f)
        val newW = start.width() * f
        val newH = start.height() * f
        val left = if (handle.movesLeft) ax - newW else ax
        val top = if (handle.movesTop) ay - newH else ay
        m.preview.set(left, top, left + newW, top + newH)
    }

    
    private fun beginSelectionRotate(isPen: Boolean, pointerId: Int, wx: Float, wy: Float, sel: LassoSelection): MoveSession.SelectionRotate {
        host.setDrawPathSuspended(true, "lasso-selection-rotate")
        val strokes = ArrayList<BoardEngine.StrokeRec>()
        val inkPath = Path()
        
        collectSelectionPreview(sel, ArrayList(), strokes, ArrayList(), inkPath)
        val framePx = worldRectToPx(sel.rect, RectF())
        overlay.beginMovePreview(framePx, emptyList(), inkPath, PEN_WIDTH_DP * BoardEngine.scale * density)
        overlay.updateRotatePreview(0f, framePx.centerX(), framePx.centerY())
        holdGestureEink(MosaicEinkRefreshModule.MODE_DEFAULT)
        Log.i(TAG_LASSO, "rotate begin strokes=${strokes.size} pen=$isPen")
        return MoveSession.SelectionRotate(isPen, pointerId, wx, wy, sel, strokes, sel.rect.centerX(), sel.rect.centerY())
    }

    
    private fun rotateAngle(m: MoveSession.SelectionRotate, wx: Float, wy: Float): Float {
        var a = kotlin.math.atan2(wy - m.pivotY, wx - m.pivotX) - m.startAngle
        val twoPi = (2 * Math.PI).toFloat()
        while (a > Math.PI) a -= twoPi
        while (a < -Math.PI) a += twoPi
        val snap = Math.toRadians(ROTATE_SNAP_DEG.toDouble()).toFloat()
        val quarter = (Math.PI / 2).toFloat()
        val nearest = Math.round(a / quarter) * quarter
        return if (abs(a - nearest) <= snap) nearest else a
    }

    
    private fun collectSelectionPreview(
        sel: LassoSelection,
        cards: MutableList<BoardEngine.CardRec>,
        strokes: MutableList<BoardEngine.StrokeRec>,
        cardRects: MutableList<RectF>,
        inkPath: Path,
    ) {
        val m = android.graphics.Matrix()
        val sPx = BoardEngine.scale * density
        m.setScale(sPx, sPx)
        m.postTranslate(BoardEngine.panX * density, BoardEngine.panY * density)
        val toScreen = android.graphics.Matrix()
        synchronized(BoardEngine.lock) {
            for (id in sel.cardIds) BoardEngine.cards[id]?.let { cards.add(it); cardRects.add(worldRectToPx(it.rect(), RectF())) }
            for (id in sel.strokeIds) BoardEngine.strokes[id]?.let { strokes.add(it) }
            for (s in strokes) {
                
                
                val card = s.cardId?.let { BoardEngine.cards[it] }
                if (card == null) {
                    inkPath.addPath(s.path, m)
                } else {
                    toScreen.setTranslate(card.x, card.y)
                    toScreen.postConcat(m)
                    inkPath.addPath(s.path, toScreen)
                }
            }
        }
    }

    private fun beginSelectionMove(isPen: Boolean, pointerId: Int, wx: Float, wy: Float, sel: LassoSelection): MoveSession.Selection {
        
        host.setDrawPathSuspended(true, "lasso-selection-move")
        val cards = ArrayList<BoardEngine.CardRec>()
        val strokes = ArrayList<BoardEngine.StrokeRec>()
        val cardRects = ArrayList<RectF>()
        val inkPath = Path()
        collectSelectionPreview(sel, cards, strokes, cardRects, inkPath)
        overlay.beginMovePreview(
            if (sel.cardIds.isEmpty()) worldRectToPx(sel.rect, RectF()) else null,
            cardRects, inkPath, PEN_WIDTH_DP * BoardEngine.scale * density,
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
                }
                tmpRect.set(m.card.x + dx, m.card.y + dy, m.card.x + dx + m.card.width, m.card.y + dy + m.card.height)
                if (m.dragging) hideCardFromTiles(m.card.id)
                overlay.showCardPreview(worldRectToPx(tmpRect, RectF()), false)
            }
            is MoveSession.CardResize -> {
                computeResizePreview(m, wx, wy)
                overlay.showCardPreview(worldRectToPx(m.preview, RectF()), true)
            }
            is MoveSession.Selection -> {
                m.dx = dx
                m.dy = dy
                overlay.updateMovePreview(dx * BoardEngine.scale * density, dy * BoardEngine.scale * density)
            }
            is MoveSession.SelectionResize -> {
                computeSelectionResizePreview(m, wx, wy)
                pushSelectionResizePreview(m)
            }
            is MoveSession.SelectionRotate -> {
                m.angle = rotateAngle(m, wx, wy)
                val px = (m.pivotX * BoardEngine.scale + BoardEngine.panX) * density
                val py = (m.pivotY * BoardEngine.scale + BoardEngine.panY) * density
                overlay.updateRotatePreview(Math.toDegrees(m.angle.toDouble()).toFloat(), px, py)
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
                
                if (m.dragging && (abs(dx) > 0.01f || abs(dy) > 0.01f)) {
                    settleCardDrop(m.card, m.card.x + dx, m.card.y + dy, m.liftZ)
                    refreshSingleCardFrame()
                    Log.i(TAG, "[CardPerf] kind=move input=${if (m.isPen) "pen" else "touch"} rawMoves=${m.rawMoves} durationMs=$elapsed")
                }
                
                
                
                content.runWhenSettled(Runnable { unhideCardFromTiles() })
            }
            is MoveSession.CardResize -> {
                overlay.hideCardPreview()
                computeResizePreview(m, wx, wy)
                val start = m.startRect
                val changed = abs(m.preview.left - start.left) > 0.01f || abs(m.preview.top - start.top) > 0.01f ||
                    abs(m.preview.width() - start.width()) > 0.01f || abs(m.preview.height() - start.height()) > 0.01f
                if (changed) {
                    val moved = m.card.withRect(m.preview)
                    val change = BoardHistory.Change("resize").card(m.card, moved)
                    apply(change, record = true)
                }
                refreshSingleCardFrame()
                content.runWhenSettled(Runnable { unhideCardFromTiles() })
                Log.i(TAG, "[CardPerf] kind=resize input=${if (m.isPen) "pen" else "touch"} rawMoves=${m.rawMoves} durationMs=$elapsed handle=${m.handle}")
            }
            is MoveSession.Selection -> {
                overlay.endMovePreview()
                host.setDrawPathSuspended(false, "lasso-selection-move-end")
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
                    
                    
                    val destInGroup = destination != null && movedCards.containsKey(destination.id)
                    for (stroke in m.strokes) {
                        val sourceCard = stroke.cardId?.let { BoardEngine.cards[it] }
                        
                        
                        if (sourceCard != null && movedCards.containsKey(sourceCard.id)) continue
                        change.stroke(stroke, stroke.translated(dx, dy, sourceCard, if (destInGroup) sourceCard else destination))
                    }
                    apply(change, record = true)
                    val rect = RectF(m.selection.rect).apply { offset(dx, dy) }
                    lasso = LassoSelection(rect, m.selection.cardIds, m.selection.strokeIds)
                    BoardEngine.mutate { setSelection(selectionIds()) }
                    Log.i(TAG_LASSO, "reassigned strokes=${m.strokes.size} destination=${if (destInGroup) "unchanged:in-selection" else destination?.id ?: "canvas"}")
                }
                updateLassoOverlay()
                Log.i(TAG, "[LassoPerf] input=${if (m.isPen) "pen" else "touch"} rawMoves=${m.rawMoves} durationMs=$elapsed dx=$dx dy=$dy")
            }
            is MoveSession.SelectionResize -> {
                overlay.endMovePreview()
                host.setDrawPathSuspended(false, "lasso-selection-resize-end")
                computeSelectionResizePreview(m, wx, wy)
                val start = m.startRect
                val fx = m.preview.width() / start.width()
                val fy = m.preview.height() / start.height()
                val changed = abs(fx - 1f) > 0.001f || abs(fy - 1f) > 0.001f
                if (changed) {
                    val pivotX = if (m.handle.movesLeft) start.right else start.left
                    val pivotY = if (m.handle.movesTop) start.bottom else start.top
                    val change = BoardHistory.Change("selection-resize")
                    val movedCards = HashMap<String, BoardEngine.CardRec>()
                    for (card in m.cards) {
                        val cr = card.rect()
                        val nl = pivotX + fx * (cr.left - pivotX)
                        val nt = pivotY + fy * (cr.top - pivotY)
                        val nw = cr.width() * fx
                        val nh = cr.height() * fy
                        val nr = RectF(nl, nt, nl + nw, nt + nh)
                        val moved = card.withRect(nr)
                        movedCards[card.id] = moved
                        change.card(card, moved)
                    }
                    for (stroke in m.strokes) {
                        val sourceCard = stroke.cardId?.let { id -> m.cards.find { it.id == id } }
                        val destCard = sourceCard?.let { movedCards[it.id] }
                        change.stroke(stroke, stroke.scaled(fx, fy, pivotX, pivotY, sourceCard, destCard))
                    }
                    apply(change, record = true)
                    val newRect = RectF(m.preview)
                    lasso = LassoSelection(newRect, m.selection.cardIds, m.selection.strokeIds)
                    BoardEngine.mutate { setSelection(selectionIds()) }
                }
                updateLassoOverlay()
                Log.i(TAG, "[LassoPerf] kind=selection-resize input=${if (m.isPen) "pen" else "touch"} rawMoves=${m.rawMoves} durationMs=$elapsed fx=$fx fy=$fy")
            }
            is MoveSession.SelectionRotate -> {
                overlay.endMovePreview()
                host.setDrawPathSuspended(false, "lasso-selection-rotate-end")
                m.angle = rotateAngle(m, wx, wy)
                if (abs(m.angle) > 0.001f) {
                    val change = BoardHistory.Change("selection-rotate")
                    for (stroke in m.strokes) {
                        val card = stroke.cardId?.let { BoardEngine.cards[it] }
                        change.stroke(stroke, stroke.rotated(m.angle, m.pivotX, m.pivotY, card, card))
                    }
                    apply(change, record = true)
                    
                    val sel = LassoSelection(m.selection.rect, m.selection.cardIds, m.selection.strokeIds)
                    lasso = LassoSelection(selectionFrame(sel), sel.cardIds, sel.strokeIds)
                    BoardEngine.mutate { setSelection(selectionIds()) }
                }
                updateLassoOverlay()
                Log.i(TAG, "[LassoPerf] kind=selection-rotate input=${if (m.isPen) "pen" else "touch"} rawMoves=${m.rawMoves} durationMs=$elapsed deg=${Math.toDegrees(m.angle.toDouble())}")
            }
        }
        scheduleGestureSettle()
    }

    private fun cancelMoveSession(m: MoveSession) {
        when (m) {
            is MoveSession.CardDrag, is MoveSession.CardResize -> {
                overlay.hideCardPreview()
                unhideCardFromTiles()
            }
            is MoveSession.Selection -> { overlay.endMovePreview(); host.setDrawPathSuspended(false, "lasso-selection-move-cancel"); updateLassoOverlay() }
            is MoveSession.SelectionResize -> { overlay.endMovePreview(); host.setDrawPathSuspended(false, "lasso-selection-resize-cancel"); updateLassoOverlay() }
            is MoveSession.SelectionRotate -> { overlay.endMovePreview(); host.setDrawPathSuspended(false, "lasso-selection-rotate-cancel"); updateLassoOverlay() }
        }
        scheduleGestureSettle()
    }

    
    private fun hideCardFromTiles(cardId: String) {
        BoardEngine.hiddenCardId = cardId
        val card = BoardEngine.cards[cardId] ?: return
        content.onSceneChanged(BoardEngine.cardAndNecksDirtyBounds(card), true)
    }

    private fun unhideCardFromTiles() {
        val id = BoardEngine.hiddenCardId ?: return
        BoardEngine.hiddenCardId = null
        val card = BoardEngine.cards[id] ?: return
        content.onSceneChanged(BoardEngine.cardAndNecksDirtyBounds(card), true)
    }

    
    private fun settleCardDrop(card: BoardEngine.CardRec, x: Float, y: Float, liftZ: Int) {
        val snap = BoardGeometry.findSnap(BoardEngine.cardsByZ, card, x, y)
        val settled = card.moved(snap.x, snap.y).withZ(liftZ)
        val change = BoardHistory.Change("move").card(card, settled)
        val target = snap.targetId
        if (target != null && BoardEngine.connectionBetween(card.id, target) == null) {
            change.connection(null, BoardEngine.ConnectionRec(newId("conn-"), card.id, target))
        }
        apply(change, record = true)
    }

    

    override fun onTouch(frame: InputRouter.TouchFrame) {
        if (maybeCloseFromLauncherPull(frame)) return
        trackToolbarToggleTap(frame)
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

    
    private fun trackToolbarToggleTap(frame: InputRouter.TouchFrame) {
        val moveSlop = TWO_FINGER_TAP_MOVE_SLOP_DP * density
        when (frame.action) {
            InputRouter.ACTION_DOWN -> {
                tapEpisodeActive = true
                tapEpisodeStartMs = frame.uptimeMs
                tapEpisodeMaxFingers = frame.count
                tapEpisodeMoved = penContact || penHovering || frame.penPriority
                tapDownX.clear(); tapDownY.clear()
                for (i in 0 until frame.count) { tapDownX[frame.ids[i]] = frame.xs[i]; tapDownY[frame.ids[i]] = frame.ys[i] }
            }
            InputRouter.ACTION_POINTER_DOWN -> {
                if (!tapEpisodeActive) return
                tapEpisodeMaxFingers = maxOf(tapEpisodeMaxFingers, frame.count)
                if (penContact || penHovering || frame.penPriority) tapEpisodeMoved = true
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
        val near = hypot(cx - lastTwoFingerTapX, cy - lastTwoFingerTapY) <= TWO_FINGER_DOUBLE_TAP_DIST_DP * density
        if (lastTwoFingerTapMs != 0L && gap <= TWO_FINGER_DOUBLE_TAP_GAP_MS && near) {
            Log.i(TAG_TWO, "double-tap → toggle toolbar")
            chrome.toggleToolbar()
            lastTwoFingerTapMs = 0L
        } else {
            lastTwoFingerTapMs = now
            lastTwoFingerTapX = cx
            lastTwoFingerTapY = cy
        }
    }

    private fun fingerDown(id: Int, x: Float, y: Float, penPriority: Boolean, now: Long) {
        if (surfaceSwitching) return
        if (chrome.consumesPoint(x, y) && fingers.isEmpty()) {
            Log.i(TAG_FINGER, "DOWN id=$id ignored: chrome")
            return
        }
        
        
        if (inklingToolbarHit(x, y)) {
            Log.i(TAG_FINGER, "DOWN id=$id ignored: inkling-toolbar")
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
            if (fingerGesture is FingerGesture.SurfaceScroll) {
                content.setGestureThrottle(false)
                commitViewport(BoardEngine.panX, BoardEngine.panY, BoardEngine.scale)
            }
            
            
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
        
        
        if (lasso == null && !selectBelowArmed && surface.showsBoardChrome) {
            if (!chrome.switcherOpen && x <= edgeSwipeStartPx) {
                fingerGesture = FingerGesture.EdgeSwipe(id, x, y)
                Log.i(TAG_FINGER, "edge-swipe(open) armed id=$id x=$x")
                return
            }
        }
        if (!touchEnabled) { fingerGesture = FingerGesture.Idle; return }
        val wx = worldX(x)
        val wy = worldY(y)

        if (surface.singleFingerScrolls && lasso == null) {
            fingerGesture = FingerGesture.SurfaceScroll(id, y, BoardEngine.panY)
            content.setGestureThrottle(true)
            return
        }

        
        val currentLasso = lasso
        if (currentLasso != null) {
            
            val single = if (isSingleCardSelection(currentLasso)) BoardEngine.cards[currentLasso.cardIds[0]] else null
            if (single != null) {
                val handle = singleCardFrameHandle(currentLasso, wx, wy)
                if (handle != null) {
                    fingerMoveSession = beginResize(false, id, wx, wy, single, handle)
                    fingerGesture = FingerGesture.Move(id)
                    return
                }
            }
            if (overlay.rotateHandleHit(x, y, LASSO_ACTION_TAP_SLOP_DP * density) && selectionRotatable(currentLasso)) {
                fingerMoveSession = beginSelectionRotate(false, id, wx, wy, currentLasso)
                fingerGesture = FingerGesture.Move(id)
                return
            }
            
            val cornerHandle = selectionCornerHandle(currentLasso, wx, wy)
                ?: (if (shapeKindOf(currentLasso) != null) shapeEdgeHandle(currentLasso, wx, wy) else null)
            if (cornerHandle != null) {
                fingerMoveSession = beginSelectionResize(false, id, wx, wy, currentLasso, cornerHandle)
                fingerGesture = FingerGesture.Move(id)
                return
            }
            val action = overlay.lassoActionHit(x, y, LASSO_ACTION_TAP_SLOP_DP * density)
            if (action != null) {
                handleLassoAction(action)
                fingerGesture = FingerGesture.Idle
                return
            }
            if (selectionHit(currentLasso, wx, wy) || overlay.moveHandleHit(x, y, LASSO_ACTION_TAP_SLOP_DP * density)) {
                fingerMoveSession = beginSelectionMove(false, id, wx, wy, currentLasso)
                fingerGesture = FingerGesture.Move(id)
                return
            }
            setLasso(null)
        }

        
        val card = BoardGeometry.topCardAt(BoardEngine.cardsByZ, wx, wy)
        if (card == null) {
            fingerGesture = FingerGesture.Idle
            return
        }
        
        
        if (card.kind == "note") {
            
            fingerMoveSession = null
            fingerGesture = FingerGesture.NoteTap(id, card, x, y)
            return
        }
        
        if (selectedCardId == card.id) {
            fingerMoveSession = beginCardDrag(false, id, wx, wy, card)
            fingerGesture = FingerGesture.Move(id)
            (fingerMoveSession as MoveSession.CardDrag).dragging = false
            return
        }
        
        
        
        fingerGesture = FingerGesture.CardPending(id, card.id)
        cancelCardLongPress()
        val task = Runnable {
            cardLongPressTask = null
            val pending = fingerGesture as? FingerGesture.CardPending ?: return@Runnable
            if (pending.fingerId != id) return@Runnable
            val f = fingers[id] ?: return@Runnable
            val current = BoardEngine.cards[pending.cardId]
            if (current == null) { fingerGesture = FingerGesture.Idle; return@Runnable }
            
            val session = beginCardDrag(false, id, worldX(f.x), worldY(f.y), current)
            session.dragging = true
            hideCardFromTiles(current.id)
            overlay.showCardPreview(worldRectToPx(current.rect(), RectF()), false)
            fingerMoveSession = session
            fingerGesture = FingerGesture.Move(id)
            Log.i(TAG_FINGER, "card hold → movable id=${current.id} holdMs=$CARD_FINGER_MOVE_HOLD_MS")
        }
        cardLongPressTask = task
        handler.postDelayed(task, CARD_FINGER_MOVE_HOLD_MS)
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
            is FingerGesture.CardPending -> {
                val f = fingers[g.fingerId] ?: return
                
                if (hypot(f.x - f.downX, f.y - f.downY) >= BoardGeometry.CARD_DRAG_THRESHOLD_PX * density) {
                    cancelCardLongPress()
                    fingerGesture = FingerGesture.Idle
                }
            }
            is FingerGesture.TwoCandidate -> {
                val a = fingers[g.a] ?: return
                val b = fingers[g.b] ?: return
                if (TwoFingerToolGuard.moved(a.downX, a.downY, a.x, a.y) || TwoFingerToolGuard.moved(b.downX, b.downY, b.x, b.y)) {
                    handler.removeCallbacks(twoCandidateTimeout)
                    Log.i(TAG_TWO, "candidate moved → pinch")
                    
                    
                    fallbackFromTwoCandidate(a, b, "moved")
                }
            }
            is FingerGesture.NoteTap -> {
                val f = fingers[g.fingerId] ?: return
                
                if (hypot(f.x - g.downX, f.y - g.downY) >= BoardGeometry.CARD_DRAG_THRESHOLD_PX * density) {
                    cancelCardLongPress()
                }
            }
            is FingerGesture.PanZoom -> updatePanZoom(g)
            is FingerGesture.SurfaceScroll -> {
                val f = fingers[g.fingerId] ?: return
                val dy = (f.y - g.startY) / density
                surface.constrainPan(BoardEngine.panX, g.startPanY + dy, BoardEngine.scale, viewportTmp)
                BoardEngine.setViewport(viewportTmp[0], snapPanToPx(viewportTmp[1]), BoardEngine.scale)
            }
            is FingerGesture.EdgeSwipe -> {
                val f = fingers[g.fingerId] ?: return
                val dx = f.x - g.downX
                val dy = f.y - g.downY
                
                if (abs(dy) > abs(dx)) return
                if (dx >= edgeSwipeZonePx) {
                    fingerGesture = FingerGesture.Idle
                    Log.i(TAG_FINGER, "edge-swipe(open) fired dx=$dx")
                    openSwitcher()
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
            is FingerGesture.NoteTap -> {
                cancelCardLongPress()
                if (g.fingerId == id && !cancelled && hypot(finger.x - g.downX, finger.y - g.downY) < BoardGeometry.CARD_DRAG_THRESHOLD_PX * density) {
                    openNote(g.card.noteRef)
                }
                fingerGesture = FingerGesture.Idle
            }
            is FingerGesture.SurfaceScroll -> if (g.fingerId == id) {
                fingerGesture = FingerGesture.Idle
                content.setGestureThrottle(false)
                commitViewport(BoardEngine.panX, BoardEngine.panY, BoardEngine.scale)
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
                    
                    if (lasso == null) {
                        content.setSuspendTranslucent(false)
                        scheduleGestureSettle()
                    }
                }
            }
            is FingerGesture.EdgeSwipe -> if (g.fingerId == id) fingerGesture = FingerGesture.Idle
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
            content.setSuspendTranslucent(false)
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
            is PenSession.Shape -> overlay.hideShapePreview()
            else -> {}
        }
    }

    

    private val twoCandidateTimeout = Runnable { settleTwoCandidate("hold") }

    
    private fun fallbackFromTwoCandidate(a: Finger, b: Finger, reason: String) {
        if (penContact || penHovering || a.penPriority || b.penPriority) {
            fingerGesture = FingerGesture.Idle
            Log.i(TAG_TWO, "candidate $reason → blocked by pen proximity")
            return
        }
        if (touchEnabled) beginPanZoom(a, b) else fingerGesture = FingerGesture.Idle
    }

    
    private fun adoptScreenTool(a: Finger, b: Finger, reason: String): Boolean {
        val tool = gestureToolOf(settings.screenGesture) ?: return false
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
        if (!decision.accept) return false
        fingerGesture = FingerGesture.ScreenTool
        cancelGestureSettle()
        applyTransition(arbiter.pushOverride(ToolArbiter.Source.SCREEN, tool, ToolArbiter.Settle.ON_PEN_UP), "screen-tool")
        requestEink(MosaicEinkRefreshModule.MODE_DUX)
        if (boardTranslucent) content.setSuspendTranslucent(true)
        return true
    }

    
    private fun settleTwoCandidate(reason: String): Boolean {
        val g = fingerGesture as? FingerGesture.TwoCandidate ?: return false
        handler.removeCallbacks(twoCandidateTimeout)
        val a = fingers[g.a] ?: return false
        val b = fingers[g.b] ?: return false
        if (!adoptScreenTool(a, b, reason)) fallbackFromTwoCandidate(a, b, reason)
        return true
    }

    
    private fun yieldPanZoomToPen(reason: String) {
        val g = fingerGesture as? FingerGesture.PanZoom ?: return
        val a = fingers[g.a]
        val b = fingers[g.b]
        endPanZoom(g)
        fingerGesture = FingerGesture.Idle
        if (a == null || b == null) return
        if (!adoptScreenTool(a, b, reason)) {
            Log.i(TAG_PAN, "panzoom yielded to pen: no screen tool reason=$reason")
        }
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
        requestEink(MosaicEinkRefreshModule.MODE_DUX)
        if (boardTranslucent) content.setSuspendTranslucent(true)
        armPanZoom()
        val bnd = activeRegion?.bounds
        val centerX = (view.centerX() / density - g.startPanX) / g.startScale
        val centerY = (view.centerY() / density - g.startPanY) / g.startScale
        Log.i(TAG_PAN, "begin surface=${surface.name} a=${a.id} b=${b.id} pan=(${g.startPanX},${g.startPanY}) centerWorld=($centerX,$centerY) scale=${g.startScale} region=${activeRegion != null} neighbors=${neighbors.any()} bounds=${bnd?.let { "(${it.left},${it.top},${it.right},${it.bottom})" }} worldView=(${view.width()},${view.height()})")
    }

    private fun armPanZoom() {
        val g = fingerGesture as? FingerGesture.PanZoom ?: return
        resnapPanZoom(g)
        if (!panZoomArmed) {
            panZoomArmed = true
            content.setZoomPreview(true)
            
            content.setGestureFreezeTiles(true)
            content.setGestureThrottle(true)
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
        g.lastCenterX = cx
        g.lastCenterY = cy
        val dist = max(1f, hypot(b.x - a.x, b.y - a.y))
        val ratio = dist / g.startDistance
        val nextScale = surface.constrainScale(g.startScale * ratio, g.startScale)
        
        val startCxDp = g.startCenterX / d
        val startCyDp = g.startCenterY / d
        val worldCx = (startCxDp - g.startPanX) / g.startScale
        val worldCy = (startCyDp - g.startPanY) / g.startScale
        surface.constrainPan(cx / d - worldCx * nextScale, cy / d - worldCy * nextScale, nextScale, viewportTmp)
        
        
        val panX = snapPanToPx(viewportTmp[0])
        val panY = snapPanToPx(viewportTmp[1])
        
        
        g.overscroll = null
        g.frames++
        BoardEngine.setViewport(panX, panY, nextScale)
        if (g.frames % 10 == 0) {
            val centerX = (host.width / density / 2f - panX) / nextScale
            val centerY = (host.height / density / 2f - panY) / nextScale
            Log.i(TAG_PAN, "frame=${g.frames} centerWorld=($centerX,$centerY) scale=$nextScale pan=($panX,$panY)")
        }
    }

    private fun toolbarHeightWorld(): Float = chrome.toolbarHeightPx() / density

    
    private fun snapPanToPx(panDp: Float): Float = Math.round(panDp * density) / density

    
    private fun magneticZoom(scale: Float): Float {
        val level = BoardGeometry.ZOOM_LEVELS[BoardGeometry.nearestZoomIndex(scale)]
        return if (abs(level - scale) <= scale * ZOOM_SNAP_RATIO) level else scale
    }

    private fun endPanZoom(g: FingerGesture.PanZoom) {
        var panX = BoardEngine.panX
        var panY = BoardEngine.panY
        var scale = BoardEngine.scale
        
        
        
        val snapped = if (scale != g.startScale) magneticZoom(scale) else scale
        if (snapped != scale) {
            val ax = g.lastCenterX / density
            val ay = g.lastCenterY / density
            val p = BoardGeometry.zoomAroundScreenPoint(panX, panY, scale, snapped, ax, ay)
            surface.constrainPan(p[0], p[1], snapped, viewportTmp)
            panX = snapPanToPx(viewportTmp[0]); panY = snapPanToPx(viewportTmp[1]); scale = snapped
            
            BoardEngine.setViewport(panX, panY, scale)
            Log.i(TAG_PAN, "snap scale ${g.startScale}->$scale")
        }
        content.setZoomPreview(false)
        content.setGestureFreezeTiles(false)
        content.setGestureThrottle(false)
        overlay.setLassoVisible(true)
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
        val centerX = (host.width / density / 2f - panX) / scale
        val centerY = (host.height / density / 2f - panY) / scale
        Log.i(TAG_PAN, "end frames=${g.frames} centerWorld=($centerX,$centerY) pan=($panX,$panY) scale=$scale")
    }

    

    override fun onPenState(state: InputRouter.PenState, value: Boolean) {
        when (state) {
            InputRouter.PenState.HOVER -> {
                penHovering = value
                if (!value) overlay.hideEraserCursor()
                else when (fingerGesture) {
                    
                    
                    is FingerGesture.TwoCandidate -> settleTwoCandidate("hover-enter")
                    
                    is FingerGesture.PanZoom -> yieldPanZoomToPen("hover-enter")
                    else -> {}
                }
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
                if (boardTranslucent) content.setSuspendTranslucent(true)
                
                (penSession as? PenSession.Write)?.let { it.convertFromIndex = it.points.size }
            }
            "twoUp" -> {
                sliderTwoDown = false
                host.setDrawPathSuspended(false, "slider-two-up")
                
                
                content.setSuspendTranslucent(false)
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
            selectedCardKind = selected?.kind,
            sizeLevels = levels,
            
            lassoCards = selected == null && lasso?.cardIds?.isNotEmpty() == true,
            
            lassoStrokes = selected == null && lasso?.strokeIds?.isNotEmpty() == true,
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

    
    override fun onSelectBelow() {
        if (selectBelowArmed) { cancelSelectBelow("toggle-off"); return }
        
        setLasso(null)
        selectBelowArmed = true
        chrome.setSelectBelowActive(true)
        refreshHostToolFlags()
        Log.i(TAG_LASSO, "select-below armed ${arbiter.describe()}")
    }

    
    private fun cancelSelectBelow(reason: String) {
        if (!selectBelowArmed) return
        selectBelowArmed = false
        chrome.setSelectBelowActive(false)
        refreshHostToolFlags()
        Log.i(TAG_LASSO, "select-below cancelled reason=$reason")
    }

    
    private fun handleSelectBelow(wy: Float) {
        selectBelowArmed = false
        chrome.setSelectBelowActive(false)

        val bounds = RectF()
        var any = false
        val r = RectF()
        synchronized(BoardEngine.lock) {
            for (card in BoardEngine.cardsByZ) {
                if (!any) { bounds.set(card.rect(r)); any = true } else bounds.union(card.rect(r))
            }
            for (s in BoardEngine.strokes.values) {
                val b = BoardEngine.strokeWorldBounds(s)
                if (!any) { bounds.set(b); any = true } else bounds.union(b)
            }
        }
        val hits = if (any && bounds.bottom > wy) {
            
            selectInRect(RectF(bounds.left - 1f, wy, bounds.right + 1f, bounds.bottom + 1f))
        } else null

        if (hits != null) {
            
            selectBelowPendingRestore = true
            setLasso(LassoSelection(selectionFrame(hits), hits.cardIds, hits.strokeIds))
            Log.i(TAG_LASSO, "select-below hit cards=${hits.cardIds.size} strokes=${hits.strokeIds.size} y=$wy")
        } else {
            
            refreshHostToolFlags()
            Log.i(TAG_LASSO, "select-below nothing below y=$wy")
        }
    }
    override fun onPenStyle(style: PenStyle, width: Float) = setPenStyle(style, width)
    override fun onToggleEraser() = applyTransition(arbiter.toggleBase(ToolArbiter.Tool.ERASER), "toolbar-eraser")
    override fun onToggleLasso() = applyTransition(arbiter.toggleBase(ToolArbiter.Tool.LASSO), "toolbar-lasso")
    
    override fun onShapeSelected(kind: Shapes.Kind) {
        if (shapeMode && shapeKind == kind) {
            applyTransition(arbiter.toggleBase(ToolArbiter.Tool.SHAPE), "toolbar-shape-off")
            return
        }
        shapeKind = kind
        if (!shapeMode) applyTransition(arbiter.toggleBase(ToolArbiter.Tool.SHAPE), "toolbar-shape:$kind")
        else chrome.setCurrentShape(kind)
        Log.i(TAG_TOOL, "shape kind=$kind active=$shapeMode")
    }
    override fun onSelectWriteTool() = applyTransition(arbiter.clearBase(), "toolbar-write")
    
    override fun onTemplateSelected(template: BackgroundTemplate) {
        
        TemplateStore.setBoard(host.context, template)
        val pageWidth = if (surface.showsBoardChrome) 0f else ScrollingDocument.WIDTH
        content.setBackgroundTemplate(template, pageWidth)
        chrome.setCurrentTemplate(template)
    }
    override fun onUndo() = undo()
    override fun onRedo() = redo()

    override fun onSync() {
        emitter.begin()
        emitter.action("sync")
        emitter.commit()
    }

    override fun onConvertCardToNote() {
        if (suspendedBoard != null || surfaceSwitching || notesDirectory.isBlank()) return
        val source = selectedCardId?.let { BoardEngine.cards[it] } ?: return
        if (source.kind == "note" || source.kind == "image") return
        
        val moved = synchronized(BoardEngine.lock) {
            BoardEngine.cardStrokes[source.id]?.map { it.translated(0f, 0f, null, null) } ?: emptyList()
        }
        val ref = "note-" + UUID.randomUUID().toString().replace("-", "").take(12)
        noteController.open(notesDirectory, ref, create = true) { result ->
            result.onFailure { Log.e(TAG, "convert-to-note create failed", it) }
            result.onSuccess {
                noteController.changed(moved)
                noteController.flush(preview = true) { flushed ->
                    flushed.onFailure { Log.e(TAG, "convert-to-note flush failed", it) }
                    val contentHeight = flushed.getOrNull()?.contentHeight ?: ScrollingDocument.WIDTH
                    val contentWidth = flushed.getOrNull()?.contentWidth ?: ScrollingDocument.WIDTH
                    noteController.clear()
                    val size = FloatArray(2)
                    noteCardBoardSize(contentHeight, contentWidth, source.width, size)
                    
                    val note = BoardEngine.CardRec(source.id, source.x, source.y, size[0], size[1], source.zIndex, "note", "", noteController.previewPath(ref), ref, source.bgColor, source.textColor, noteTitleFromContent(source.content))
                    val change = BoardHistory.Change("card-to-note")
                    synchronized(BoardEngine.lock) { BoardEngine.cardStrokes[source.id]?.forEach { change.stroke(it, null) } }
                    change.card(source, note)
                    apply(change, record = true)
                    selectSingleCard(note); scheduleChromeUpdate()
                }
            }
        }
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

    override fun onPenBlockChanged() = refreshHostToolFlags()

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
        if (existing.size >= 5) {
            Log.i(TAG, "[Whiteboard] anchor rejected: limit=5")
            return
        }
        for (wb in existing) {
            if (BoardGeometry.coverage(rect, wb.rect()) >= BoardGeometry.WHITEBOARD_DUPLICATE_COVERAGE) {
                Log.i(TAG, "[Whiteboard] anchor reused ${wb.id}")
                return
            }
        }
        val wb = BoardEngine.WhiteboardRec(newId("wb-"), chrome.newWhiteboardName(existing), rect.left, rect.top, rect.width(), rect.height(), current = true)
        apply(BoardHistory.Change("whiteboard-add").whiteboard(null, wb), record = false)
        if (chrome.switcherOpen) {
            
            
            openSwitcher()
        }
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
        setLasso(null)
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
        if (card.kind == "image" || card.kind == "note") return
        
        val next = if (card.colored) card.withColors("", "")
        else card.withColors(ACCENT_BG_COLOR, ACCENT_TEXT_COLOR)
        val change = BoardHistory.Change("card-accent").card(card, next)
        val relinked = reconcileConnectionsForColor(next, change)
        apply(change, record = true)
        Log.i(TAG, "toggle card accent id=${card.id} colored=${next.colored} connections=$relinked")
    }

    override fun onDeleteLassoSelection() = deleteLassoSelection()

    
    override fun onRecognizeLasso() {
        val sel = lasso ?: return
        val strokeIds = synchronized(BoardEngine.lock) {
            sel.strokeIds.filter { BoardEngine.strokes[it]?.cardId == null }
        }
        if (strokeIds.isEmpty()) { Log.i(TAG_LASSO, "recognize skipped: no loose ink"); return }
        pendingRecognition = PendingRecognition(RectF(sel.rect), strokeIds)
        chrome.hideSwitcher()
        refreshHostToolFlags()
        emitter.begin()
        emitter.action("recognizeLasso")
        emitter.commit()
        Log.i(TAG_LASSO, "recognize requested strokes=${strokeIds.size}")
    }

    
    fun createRecognizedTextCard(text: String) {
        val pending = pendingRecognition ?: return
        pendingRecognition = null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) { Log.i(TAG_LASSO, "recognize empty text; scene unchanged"); return }
        val r = pending.rect
        val left = r.left
        val top = r.top
        val width = r.width().coerceAtLeast(RECOGNIZE_CARD_MIN_DP)
        val height = r.height().coerceAtLeast(RECOGNIZE_CARD_MIN_DP)
        val created = BoardEngine.CardRec(
            newId("card-"), left, top, width, height,
            BoardGeometry.nextZIndex(BoardEngine.cards.values), "text", trimmed,
        )
        val change = BoardHistory.Change("recognize-card").card(null, created)
        synchronized(BoardEngine.lock) {
            for (id in pending.strokeIds) BoardEngine.strokes[id]?.let { change.stroke(it, null) }
        }
        setLasso(null)
        apply(change, record = true)
        scheduleChromeUpdate()
        Log.i(TAG_LASSO, "recognize card=${created.id} replaced strokes=${pending.strokeIds.size} chars=${trimmed.length}")
    }

    override fun onClose() {
        if (surfaceSwitching) return
        surface.onCloseRequested(BoardSurface.CloseSource.TOOLBAR)
    }

    private fun maybeCloseFromLauncherPull(frame: InputRouter.TouchFrame): Boolean {
        if (frame.count != 1) { launcherPullStartY = Float.NaN; return false }
        val y = frame.ys[0]
        when (frame.action) {
            InputRouter.ACTION_DOWN -> if (y < 120f) {
                launcherPullStartY = y
                host.context.sendBroadcast(Intent("com.ratta.supernote.launcher.BroadcastReceiver.slidebarstatusbar").putExtra("lockStatusbar", true))
            }
            InputRouter.ACTION_UP -> {
                val start = launcherPullStartY
                launcherPullStartY = Float.NaN
                if (!start.isNaN() && y - start > 180f) {
                    Log.i(TAG, "launcher top-pull close distance=${y - start} surface=${surface.name}")
                    
                    if (!surfaceSwitching) surface.onCloseRequested(BoardSurface.CloseSource.LAUNCHER_PULL)
                    return true
                }
            }
        }
        return false
    }

    override fun onJumpToRegion(region: SparseNavigation.Region) {
        cancelFingerGestures("jump")
        val centered = SparseNavigation.panToCenterRect(region.bounds, host.width / density, host.height / density, BoardEngine.scale)
        commitViewport(centered[0], centered[1], BoardEngine.scale)
    }

    private fun openSwitcher() {
        val list = synchronized(BoardEngine.lock) { BoardEngine.whiteboards.values.toList() }
        val notes = synchronized(BoardEngine.lock) {
            BoardEngine.cardsByZ.filter { it.kind == "note" }
        }
        chrome.showSwitcher(list, notes, clippedWhiteboardIds)
        refreshHostToolFlags()
    }

    override fun onSwitcherDismissed() {
        chrome.hideSwitcher()
        refreshHostToolFlags()
    }

    
    fun setClippedWhiteboards(ids: Set<String>) {
        clippedWhiteboardIds = ids
        if (chrome.switcherOpen) openSwitcher()
    }

    
    override fun onOpenNoteCard(cardId: String) {
        val card = BoardEngine.cards[cardId] ?: return
        chrome.hideSwitcher()
        refreshHostToolFlags()
        openNote(card.noteRef)
    }

    
    override fun onLocateNoteCard(cardId: String) {
        val card = BoardEngine.cards[cardId] ?: return
        cancelFingerGestures("locate-note")
        val w = host.width / density
        val h = host.height / density
        val rect = card.rect()
        val fit = min(w / max(rect.width(), 1f), h / max(rect.height(), 1f))
        val scale = BoardGeometry.clampZoom(min(BoardEngine.scale, fit))
        val centered = SparseNavigation.panToCenterRect(rect, w, h, scale)
        chrome.hideSwitcher()
        refreshHostToolFlags()
        commitViewport(centered[0], centered[1], scale)
        Log.i(TAG, "locate note card=${card.id} at (${card.x},${card.y}) scale=$scale")
    }

    
    override fun onRemoveClip(id: String) {
        val wb = BoardEngine.whiteboards[id] ?: return
        emitter.begin()
        emitter.action("removeClip") { putString("wbId", wb.id) }
        emitter.commit()
        Log.i(TAG, "[MosaicNoteShot] removeClip requested wb=${wb.id}")
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
