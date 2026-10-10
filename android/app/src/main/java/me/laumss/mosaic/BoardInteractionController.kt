package me.laumss.mosaic

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import android.widget.FrameLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
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
        
        private const val TRAIL_HYSTERESIS_DP = 6f
        
        private const val ZOOM_SNAP_RATIO = 0.03f
        
        private const val ROTATE_SNAP_DEG = 3f
        
        private const val SHAPE_MIN_DRAG_DP = 8f

        
        private const val NOTE_RESTORE_PAINT_HOLD_MS = 500L

        
        const val ACCENT_BG_COLOR = "#FFF9E3"
        const val ACCENT_TEXT_COLOR = "#3f3b2e"

        const val CARD_FRAME_HOLD_MS = 500L
        const val CARD_FRAME_HOLD_MOVE_SLOP_DP = 3f
        const val CARD_FRAME_PREVIEW_CANCEL_MOVE_DP = 8f
        const val CARD_FRAME_CAPTURE_INK_RATIO = 0.70f

        
        
        
        const val TWO_FINGER_CANDIDATE_HOLD_MS = 300L
        
        const val TWO_FINGER_PAIR_WINDOW_MS = TwoFingerToolGuard.EVENT_TIME_DEV_MS

        
        const val CARD_FINGER_MOVE_HOLD_MS = 600L
        
        const val CARD_FINGER_MOVE_HOLD_FAST_MS = 400L
        
        const val CARD_FINGER_MOVE_HOLD_SELECTION_MS = 100L
        
        private const val NOTE_HEADER_PENDING = "#note-header"
        private const val NOTE_LINK_PENDING = "#note-link:"
        private const val NOTE_LINK_TAP_SLOP_DP = 8f
        private const val NOTE_LINK_MIN_HIT_DP = 40f
        
        private val IMPORT_CARD_WIDTHS = floatArrayOf(BoardGeometry.DEFAULT_CARD_WIDTH, 480f, 640f, 800f, 960f)
        
        private const val IMPORT_CARD_MAX_ASPECT = 1.6f

        const val LASSO_ACTION_TAP_SLOP_DP = 12f

        
        const val TWO_FINGER_TAP_MAX_MS = 300L
        const val TWO_FINGER_DOUBLE_TAP_GAP_MS = 400L
        const val TWO_FINGER_TAP_MOVE_SLOP_DP = 24f
        const val TWO_FINGER_DOUBLE_TAP_DIST_DP = 120f
        
        const val LASSO_BOUNDS_PAD = 10f
        private const val EINK_OWNER = "gesture"
        private const val NOTE_SCROLL_EINK_OWNER = "note-scroll"
        private const val NOTE_SCROLL_SETTLE_MS = 400L
        
        const val GESTURE_SETTLE_MS = 600L

        
        const val NOTE_SHOT_DIR = "/sdcard/EXPORT/mosaic/shots"
        private const val TAG_SHOT = "MosaicNoteShot"
        
        private const val NOTE_SHOT_FOCUS_RETRY_MS = 120L
        private const val NOTE_SHOT_FOCUS_RETRIES = 20
        
        private const val NOTE_SHOT_MAX_SCREEN_FRACTION = 0.9f

        
        private const val EDGE_SWIPE_START_DP = 32f
        
        private const val EDGE_SWIPE_ZONE_DP = 72f
        
        private const val RECOGNIZE_CARD_MIN_DP = 120f

        
        const val NOTE_CARD_BOARD_WIDTH = 450f

        
        fun noteCardBoardSize(contentHeight: Float, cardWidth: Float = NOTE_CARD_BOARD_WIDTH, out: FloatArray, header: String = "") {
            val width = cardWidth.coerceAtLeast(BoardGeometry.MIN_CARD_SIZE)
            val headerH = BoardContentView.noteHeaderHeight(header) * width / ScrollingDocument.WIDTH
            out[0] = width
            out[1] = (contentHeight * width / ScrollingDocument.WIDTH + headerH).coerceAtLeast(BoardGeometry.MIN_CARD_SIZE)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density: Float get() = host.resources.displayMetrics.density.coerceAtLeast(1f)
    
    private val clipboardIo = ClipboardStrokeIo()
    private val clipboardPasteGeneration = AtomicLong(0L)
    private val clipboardExportGeneration = AtomicLong(0L)
    private var clipboardPasteTask: Future<*>? = null

    private val edgeSwipeStartPx: Float get() = EDGE_SWIPE_START_DP * density
    private val edgeSwipeZonePx: Float get() = EDGE_SWIPE_ZONE_DP * density
    
    private class PendingRecognition(val rect: RectF, val strokeIds: List<String>)
    private var pendingRecognition: PendingRecognition? = null

    

    private val arbiter = ToolArbiter()
    var settings: GestureSettings.Resolved = GestureSettings.Resolved.DEFAULT
        private set
    private var history = BoardHistory()

    

    private val whiteboard = WhiteboardSurface(emitter) { host.armCloseWatchdog("board-close", noteShotSessionActive) }
    
    private var surface: BoardSurface = whiteboard
    
    private var surfaceSwitching = false
    private val noteController = NoteController()
    private var notesDirectory: String = ""
    
    private class SuspendedBoard(val scene: BoardEngine.SceneSnapshot, val history: BoardHistory, val lasso: LassoSelection?)
    private var suspendedBoard: SuspendedBoard? = null
    
    private var currentNoteHeader: String? = null
    private var currentNoteHeaderPlaceholder = false
    private var currentNoteHeaderHeight = 0f
    // View scale of the open note page; the header is laid out at screen size inside it.
    private var notePageScale = 1f
    
    private var noteEntryCard: BoardEngine.CardRec? = null
    
    private var noteHeaderHoverBlocked = false
    
    private val viewportTmp = FloatArray(2)
    private val noteScrollResetTask = Runnable {
        MosaicEinkRefreshModule.resetNative(NOTE_SCROLL_EINK_OWNER)
    }

    private fun resetNoteScrollMode() {
        handler.removeCallbacks(noteScrollResetTask)
        MosaicEinkRefreshModule.resetNative(NOTE_SCROLL_EINK_OWNER)
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
        if (surface !== next) resetNoteScrollMode()
        if (surface !== next) invalidateClipboardWork("surface:${next.name}")
        surface = next
        
        NoteLinks.setActive(next.showsBoardChrome)
        BoardEngine.replaceScene(scene)
        chrome.setNoteMode(!next.showsBoardChrome)
        content.setNoteHeader(if (next.showsBoardChrome) null else currentNoteHeader, currentNoteHeaderPlaceholder, notePageScale)
        if (next.showsBoardChrome) setNoteHeaderHoverBlocked(false)
        syncToolMirrors(arbiter.effective(), "surface:${next.name}")
        
        presentation.refresh("surface:${next.name}")
        
        val noteRef = if (next.showsBoardChrome) null else noteController.document?.ref
        InklingLink.setSurface(next.name, noteRef, "surface-switch")
        host.onInklingSurfaceChanged(next.name, noteRef)
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
        invalidateClipboardWork("open-note")
        val suspended = SuspendedBoard(BoardEngine.snapshotScene(), history, lasso)
        surfaceSwitching = true
        WhiteboardSceneGate.hold()
        noteController.open(notesDirectory, ref, create = false) { result ->
            surfaceSwitching = false
            result.onFailure { WhiteboardSceneGate.release(); releasePaintHold("note-open-failed"); Log.e(TAG, "note open failed", it) }
            result.onSuccess { doc ->
                suspendedBoard = suspended
                history = BoardHistory(); lasso = null
                noteEntryCard = sourceCard
                // The page always spans the view width, whatever the screen resolution.
                val scale = ScrollingDocument.viewScale(host.width / density)
                notePageScale = scale
                setPageNoteHeader(BoardContentView.noteHeaderOf(sourceCard))
                val note = NoteSurface(noteController, host.context, { host.height / density }, { host.width / density }, { notePageScale }, ::noteTopInsetWorld, ::closeNote)
                val panY = toolbarHeightWorld() + currentNoteHeaderHeight * scale - doc.clampScroll(doc.scrollY, host.height / density / scale) * scale
                val scene = BoardEngine.SceneSnapshot(doc.strokes, emptyList(), emptyList(), emptyList(), emptyList(), 0f, panY, scale)
                enterSurface(note, scene)
                releasePaintHold("note-opened")
                Log.i(TAG, "note opened ref=$ref height=${doc.contentHeight} scrollY=${doc.scrollY}")
            }
        }
    }

    private fun closeNote() {
        val suspended = suspendedBoard ?: return
        if (surfaceSwitching) return
        invalidateClipboardWork("close-note")
        surfaceSwitching = true
        closeCardEditor()
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
                enterSurface(whiteboard, suspended.scene)
                setLasso(suspended.lasso)
                WhiteboardSceneGate.release()
                val card = synchronized(BoardEngine.lock) { BoardEngine.cards.values.firstOrNull { it.noteRef == doc.ref } }
                if (card != null) {
                    val size = FloatArray(2); noteCardBoardSize(doc.contentHeight, card.width, size, BoardContentView.noteHeaderOf(card))
                    val resized = card.withRect(RectF(card.x, card.y, card.x + size[0], card.y + size[1]))
                    
                    val entry = noteEntryCard?.takeIf { it.id == card.id } ?: card
                    val change = BoardHistory.Change("note-resize").card(card, resized)
                    pushCardsBelow(change, cardsBelow(entry), resized.height - entry.height)
                    apply(change, record = false)
                    BoardEngine.mutate { invalidateAll() }
                }
                noteEntryCard = null
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
    
    private var shapeSelectionPending = false
    
    private var penStyle: PenStyle = PenStyle.PEN
    
    private var penWidth = PenPopup.WIDTHS[PenPopup.DEFAULT_INDEX]
    
    private var markerInk = MarkerInk.BLACK
    private var cardEditorOverlay: FrameLayout? = null
    
    private var editorKeyboardShift = 0f

    fun setPenStyle(style: PenStyle, width: Float) {
        penStyle = style
        penWidth = width.coerceIn(1f, 40f)
        Log.i(TAG_PEN, "style=${style.name} stdWidth=$penWidth")
        host.setPenStyle(style, penWidth)
        reevaluateTrailColor("pen-style")
    }

    fun setMarkerInk(ink: MarkerInk) {
        markerInk = ink
        Log.i(TAG_PEN, "marker ink=${ink.name}")
        host.setMarkerInk(ink, "marker-ink")
    }

    
    private val selectedCardId: String?
        get() = lasso?.let { if (isSingleCardSelection(it)) it.cardIds.firstOrNull() else null }

    private fun cardResizeLocked(cardId: String): Boolean {
        val component = connectedComponent(cardId).toSet()
        return synchronized(BoardEngine.lock) {
            BoardEngine.connections.values.any { connection ->
                connection.locked && component.contains(connection.fromId) && component.contains(connection.toId)
            }
        }
    }

    
    private fun isSingleCardSelection(sel: LassoSelection): Boolean {
        if (sel.cardIds.size != 1) return false
        if (cardResizeLocked(sel.cardIds[0])) return false
        if (sel.strokeIds.isEmpty()) return true
        val id = sel.cardIds[0]
        return sel.strokeIds.all { BoardEngine.strokes[it]?.cardId == id }
    }

    
    private fun selectSingleCard(card: BoardEngine.CardRec) {
        val frame = card.rect(RectF()).apply { inset(-LASSO_BOUNDS_PAD, -LASSO_BOUNDS_PAD) }
        setLasso(LassoSelection(frame, listOf(card.id), emptyList()))
    }

    private fun connectedComponent(cardId: String): List<String> = synchronized(BoardEngine.lock) {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        seen.add(cardId); queue.add(cardId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (connection in BoardEngine.connections.values) {
                if (!connection.locked) continue
                val next = when {
                    connection.fromId == current -> connection.toId
                    connection.toId == current -> connection.fromId
                    else -> null
                } ?: continue
                if (BoardEngine.cards.containsKey(next) && seen.add(next)) queue.add(next)
            }
        }
        seen.toList()
    }

    private fun selectConnectedComponent(card: BoardEngine.CardRec) {
        val ids = connectedComponent(card.id)
        if (ids.size <= 1) { selectSingleCard(card); return }
        val frame = RectF()
        val rect = RectF()
        val strokeIds = ArrayList<String>()
        synchronized(BoardEngine.lock) {
            for (id in ids) {
                val member = BoardEngine.cards[id] ?: continue
                member.rect(rect)
                if (frame.isEmpty) frame.set(rect) else frame.union(rect)
            }
            val idSet = ids.toSet()
            for (stroke in BoardEngine.strokes.values) {
                val cardHit = stroke.cardId?.let(idSet::contains) == true
                val connectionHit = stroke.connectionId?.let { connectionId ->
                    val connection = BoardEngine.connections[connectionId]
                    connection != null && (idSet.contains(connection.fromId) || idSet.contains(connection.toId))
                } == true
                if (cardHit || connectionHit) strokeIds.add(stroke.id)
            }
        }
        frame.inset(-LASSO_BOUNDS_PAD, -LASSO_BOUNDS_PAD)
        setLasso(LassoSelection(frame, ids, strokeIds))
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
        class OpenNote(val ref: String, val downX: Float, val downY: Float, val shotId: String? = null) : PenSession()
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

        class CardResize(isPen: Boolean, pointerId: Int, sx: Float, sy: Float, val card: BoardEngine.CardRec, val handle: BoardGeometry.Handle) : MoveSession(isPen, pointerId, sx, sy) {
            val preview = RectF()
            
            val startRect = RectF()
            
            var inkBounds: RectF? = null
        }
        
        class Selection(isPen: Boolean, pointerId: Int, sx: Float, sy: Float, val selection: LassoSelection, val cards: List<BoardEngine.CardRec>, val strokes: List<BoardEngine.StrokeRec>, val liftZ: Int?) : MoveSession(isPen, pointerId, sx, sy) {
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
    private var launcherPullStartY = Float.NaN
    private var panZoomArmed = false
    
    private val presentation by lazy {
        BoardPresentation(handler, content, { surface.showsBoardChrome }, EINK_OWNER, GESTURE_SETTLE_MS).also {
            
            it.onStateChanged = { if (penHovering && !penContact) reevaluateTrailColor("presentation") }
        }
    }

    
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
        host.setMarkerInk(markerInk, "attach")
        TranslucentStore.level(host.context).let { level ->
            content.setTranslucentLevel(level)
            chrome.setTranslucentLevel(level)
        }
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
        resetNoteScrollMode()
        closeCardEditor()
        releasePaintHold("detach")
        noteShotSessionActive = false
        noteShotFocusToken++
        
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
        BoardEngine.hiddenCardId = null
        fingers.clear()
        fingerGesture = FingerGesture.Idle
        sliderTwoDown = false
        content.setZoomPreview(false)
        content.setGestureFreezeTiles(false)
        content.setGestureThrottle(false)
        overlay.clearAll()
        content.clearSettleWaiters()
        presentation.reset("detach")
        handler.removeCallbacksAndMessages(null)
    }

    fun refreshSettings(reason: String) {
        settings = GestureSettings.read(host.context)
        Log.i(TAG, "settings refreshed reason=$reason ${settings.describe()}")
        applyStylusCalibration()
    }

    
    private fun applyStylusCalibration() {
        
        DrawPathGate.stylusNearBlocks = gestureToolOf(settings.penButton) != null
        DrawPathGate.sliderToolSide = if (gestureToolOf(settings.slidebarGesture) != null) settings.sliderSide else 0
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
        
        
        
        content.requestInitialRaster()
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
            whiteboard.onCloseRequested(BoardSurface.CloseSource.TOOLBAR)
        }
    }

    

    
    
    private fun apply(change: BoardHistory.Change, record: Boolean, forward: Boolean = true, reconcileInk: Boolean = forward) {
        if (change.isEmpty) return
        val emitCommands = surface.emitsCommands
        var inkFixed = false
        if (emitCommands) emitter.begin()
        try {
            applyDiffs(change.diffs, forward, emitCommands)
            if (reconcileInk) {
                InkBackdrop.reconcile(change)?.let { fix ->
                    applyDiffs(fix.diffs, true, emitCommands)
                    change.absorb(fix)
                    inkFixed = true
                    Log.i(TAG, "ink reconciled change=${change.label} strokes=${fix.diffs.size}")
                }
            }
        } finally {
            if (emitCommands) emitter.commit()
        }
        if (record) history.push(change)
        surface.onSceneMutated()
        regionsDirty = true
        scheduleChromeUpdate()
        
        if (penHovering && !penContact) reevaluateTrailColor(if (inkFixed) "ink-reconciled" else "scene:${change.label}")
    }

    private fun applyDiffs(diffs: List<BoardHistory.Diff>, forward: Boolean, emitCommands: Boolean) {
        
        
        val commands = if (emitCommands) ArrayList<() -> Unit>() else null
        val pendingUpserts = ArrayList<BoardEngine.StrokeRec>()
        val pendingRemovals = ArrayList<String>()
        BoardEngine.mutate {
            fun flushUpserts() {
                if (pendingUpserts.isEmpty()) return
                addStrokesBatch(pendingUpserts)
                if (emitCommands) {
                    val records = pendingUpserts.toList()
                    commands?.add {
                        if (records.size == 1) emitter.strokeUpsert(records[0])
                        else emitter.strokeUpsertBatch(records)
                    }
                    val connectionIds = records.mapNotNull { it.connectionId }.toSet()
                    for (id in connectionIds) {
                        val connection = BoardEngine.connections[id] ?: continue
                        commands?.add { emitter.connectionAdd(connection) }
                    }
                }
                pendingUpserts.clear()
            }
            fun flushRemovals() {
                if (pendingRemovals.isEmpty()) return
                if (emitCommands) {
                    val ids = pendingRemovals.toList()
                    commands?.add { emitter.strokesRemove(ids) }
                }
                pendingRemovals.clear()
            }
            for (diff in diffs) {
                when (diff) {
                    is BoardHistory.Diff.Stroke -> {
                        val target = if (forward) diff.after else diff.before
                        val source = if (forward) diff.before else diff.after
                        if (target == null) {
                            flushUpserts()
                            source?.let {
                                removeStroke(it.id)
                                pendingRemovals.add(it.id)
                            }
                        } else {
                            flushRemovals()
                            pendingUpserts.add(target)
                        }
                    }
                    is BoardHistory.Diff.Card -> {
                        flushUpserts(); flushRemovals()
                        val target = if (forward) diff.after else diff.before
                        val source = if (forward) diff.before else diff.after
                        if (target == null) {
                            source?.let {
                                removeCard(it.id)
                                commands?.add { emitter.cardsRemove(listOf(it.id)) }
                            }
                        } else {
                            upsertCard(target)
                            commands?.add { emitter.cardUpsert(target) }
                        }
                    }
                    is BoardHistory.Diff.Connection -> {
                        flushUpserts(); flushRemovals()
                        val target = if (forward) diff.after else diff.before
                        val source = if (forward) diff.before else diff.after
                        if (target == null) {
                            source?.let {
                                removeConnection(it.id)
                                commands?.add { emitter.connectionsRemove(listOf(it.id)) }
                            }
                        } else {
                            addConnection(target)
                            commands?.add { emitter.connectionAdd(target) }
                        }
                    }
                }
            }
            flushUpserts(); flushRemovals()
        }
        commands?.forEach { it() }
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
        apply(change, record = false, forward = true, reconcileInk = false)
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
                val connection = if (card == null) BoardEngine.connectionAt(wx, wy) else null
                val session = PenSession.Write(
                    id = newId("stroke-"),
                    space = when {
                        card != null -> "card:${card.id}"
                        connection != null -> "connection:${connection.id}"
                        else -> "canvas"
                    },
                    cardId = card?.id,
                    cardX = card?.x ?: 0f,
                    cardY = card?.y ?: 0f,
                )
                val pressure = InputReader.currentPenPressure()
                if (pressure != null) appendWritePoint(session, wx, wy, pressure)
                else Log.i(TAG_PEN, "contact write waiting for drawPath pressure source")
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
        
        
        val showLasso = selectBelowPendingRestore || (lasso != null && !state.eraser)
        chrome.setInkTool(state.eraser && !showLasso, state.lasso || showLasso, state.shape && !showLasso)
        chrome.setCurrentShape(if (shapeMode) shapeKind else null)
        
        
        host.toolLassoActive = lassoMode || shapeMode
        
        
        
        
        host.inkEnabled = !eraserMode && !lassoMode && lasso == null && !chrome.blocksPen && !selectBelowArmed
        
        host.shapeDrag = shapeMode
        
        host.lassoEnabled = (lassoMode || shapeMode) && lasso == null && !chrome.blocksPen && !selectBelowArmed
        
        
        
        
        
        
        presentation.set(BoardPresentation.Reason.TOOL, eraserMode)
        presentation.set(BoardPresentation.Reason.LASSO, lassoMode || (lasso != null && !penContact))
    }

    private fun refreshHostToolFlags() = syncToolMirrors(arbiter.effective(), "flags")

    private fun gestureToolOf(tool: GestureSettings.GestureTool): ToolArbiter.Tool? = when (tool) {
        GestureSettings.GestureTool.ERASER -> ToolArbiter.Tool.ERASER
        GestureSettings.GestureTool.LASSO -> ToolArbiter.Tool.LASSO
        GestureSettings.GestureTool.OFF -> null
    }

    

    

    
    fun preflightPenButtonState() {
        val rawHover = InputReader.isPenHovering()
        val rawRubber = InputReader.isPenRubberActive()
        val rawStylusButton = InputReader.isPenStylusButtonDown()
        if (rawHover || rawRubber || rawStylusButton) {
            Log.i(
                TAG_TOOL,
                "pen-button preflight hover=$rawHover rubber=$rawRubber stylus=$rawStylusButton " +
                    "controllerHover=$penHovering contact=$penContact",
            )
        }
        if (rawHover && !penHovering) onPenState(InputRouter.PenState.HOVER, true)
        when {
            rawRubber -> onPenState(InputRouter.PenState.RUBBER, true)
            rawStylusButton -> onPenState(InputRouter.PenState.STYLUS, true)
        }
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
            if (!penContact)
            return
        }
        if (penContact) return
        setNoteHeaderHoverBlocked(noteHeaderHit(penHoverY))
        if (eraserMode) {
        }
        
        reevaluateTrailColor("hover")
    }

    
    private fun reevaluateTrailColor(reason: String) {
        val want = trailWantsWhite(worldX(penHoverX), worldY(penHoverY))
        if (want == trailWhite) return
        trailWhite = want
        host.setTrailWhite(want, "$reason:$trailCause")
    }

    
    private var trailCause = "none"

    private fun trailWantsWhite(wx: Float, wy: Float): Boolean {
        
        
        trailCause = "none"
        if (penStyle == PenStyle.MARKER) return false
        val h = TRAIL_HYSTERESIS_DP / BoardEngine.scale
        val top = BoardGeometry.topCardAt(BoardEngine.cardsByZ, wx, wy)
        
        val layerDark = if (top != null) {
            InkBackdrop.layerDarkAt("card:${top.id}", wx - top.x, wy - top.y, penWidth * 0.5f)
        } else {
            InkBackdrop.layerDarkAt("canvas", wx, wy, penWidth * 0.5f)
        }
        if (layerDark) { trailCause = "layer"; return true }
        trailCause = "card"
        if (top != null) {
            
            val tempDark = presentation.state.darkCards && top.kind != "image" && top.kind != "note"
            if (!top.colored && !tempDark) return false
            if (trailWhite) return true
            val r = top.rect(trailScratchRect)
            r.inset(h, h)
            return r.contains(wx, wy)
        }
        
        if (!trailWhite) return false
        val anyTempDark = presentation.state.darkCards
        for (c in BoardEngine.cardsByZ) {
            if (!c.colored && !(anyTempDark && c.kind != "image" && c.kind != "note")) continue
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
        if (surfaceSwitching || chrome.consumesPoint(x, y) || inklingToolbarHit(x, y) || noteHeaderHit(y)) {
            Log.i(
                TAG_PEN,
                "DOWN rejected: ${when {
                    surfaceSwitching -> "surface-switching"
                    inklingToolbarHit(x, y) -> "inkling-toolbar"
                    noteHeaderHit(y) -> "note-header"
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
        
        val downCard = BoardGeometry.topCardAt(BoardEngine.cardsByZ, wx, wy)
        val downLayer = if (downCard != null) InkBackdrop.layerAt("card:${downCard.id}", wx - downCard.x, wy - downCard.y, penWidth * 0.5f)
            else InkBackdrop.layerAt("canvas", wx, wy, penWidth * 0.5f)
        Log.i(TAG_PEN, "[MosaicTrail] down trailWhite=$trailWhite cause=$trailCause layer=${downLayer ?: "none"} card=${downCard?.id ?: "canvas"} dark=${downCard?.colored == true}")

        
        if (selectBelowArmed) {
            handleSelectBelow(wy)
            penSession = PenSession.Consumed
            return
        }

        
        val currentLasso = lasso
        
        
        val hadSelection = currentLasso != null
        if (currentLasso != null && BoardEngine.connectionAt(wx, wy) == null) {
            
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

        val linkId = noteLinkAt(wx, wy)
        if (linkId != null) {
            host.setDrawPathSuspended(true, "note-link-tap")
            penSession = PenSession.OpenNote("", wx, wy, linkId)
            Log.i(TAG_PEN, "DOWN on note link badge: open on tap id=$linkId")
            return
        }
        val card = BoardGeometry.topCardAt(BoardEngine.cardsByZ, wx, wy)
        val connection = if (card == null) BoardEngine.connectionAt(wx, wy) else null
        if (card?.kind == "note") {
            
            
            host.setDrawPathSuspended(true, "note-preview-tap")
            penSession = PenSession.OpenNote(card.noteRef, wx, wy)
            Log.i(TAG_PEN, "DOWN on note preview: open on tap card=${card.id}")
            return
        }
        val session = PenSession.Write(
            id = newId("stroke-"),
            space = when {
                card != null -> "card:${card.id}"
                connection != null -> "connection:${connection.id}"
                else -> "canvas"
            },
            cardId = card?.id,
            cardX = card?.x ?: 0f,
            cardY = card?.y ?: 0f,
        )
        val downPressure = InputReader.pressureForMotionEvent(e)
        if (downPressure != null) appendWritePoint(session, wx, wy, downPressure)
        else Log.i(TAG_PEN, "DOWN write waiting for drawPath pressure source")
        session.raw(e.x, e.y)
        session.frameLastMeaningfulX = wx
        session.frameLastMeaningfulY = wy
        
        if (sliderTwoDown) session.convertFromIndex = 0
        penSession = session
    }

    private fun appendWritePoint(
        s: PenSession.Write,
        wx: Float,
        wy: Float,
        pressure: Float?,
    ): Boolean {
        if (pressure == null || !pressure.isFinite() || pressure !in 0f..1f) return false
        s.points.add(wx - s.cardX)
        s.points.add(wy - s.cardY)
        s.pressures.add(pressure)
        return true
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
                    val pressure = InputReader.pressureForHistoricalEvent(e, i)
                    if (pressure != null) {
                        appendWritePoint(s, worldX(penHistX(e, i)), worldY(penHistY(e, i)), pressure)
                        s.raw(e.getHistoricalX(i), e.getHistoricalY(i))
                    }
                }
                val wx = worldX(penX(e))
                val wy = worldY(penY(e))
                val pressure = InputReader.pressureForMotionEvent(e)
                if (pressure != null) {
                    appendWritePoint(s, wx, wy, pressure)
                    s.raw(e.x, e.y)
                    if (s.cardId == null && surface.allowsCardFrame) updateFrameGesture(s, wx, wy)
                }
            }
            is PenSession.OpenNote -> {
                if (hypot(worldX(penX(e)) - s.downX, worldY(penY(e)) - s.downY) > BoardGeometry.CARD_DRAG_THRESHOLD_PX / BoardEngine.scale) { host.setDrawPathSuspended(false, "note-tap-cancel"); penSession = PenSession.Consumed }
            }
            is PenSession.Erase -> {
                for (i in 0 until e.historySize) eraseAt(s, worldX(penHistX(e, i)), worldY(penHistY(e, i)))
                eraseAt(s, worldX(penX(e)), worldY(penY(e)))
                flushErase(s)
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
                    if (!cancelled) {
                        s.curX = penX(e); s.curY = penY(e)
                        
                        
                        
                        if (finalizeShape(s) && arbiter.hasOverride(ToolArbiter.Source.SHAPE)) {
                            shapeSelectionPending = true
                            applyTransition(
                                arbiter.replaceOverride(
                                    ToolArbiter.Source.SHAPE,
                                    ToolArbiter.Tool.LASSO,
                                    ToolArbiter.Settle.ON_PEN_UP,
                                ),
                                "shape-stroke-to-lasso",
                            )
                        }
                    } else if (arbiter.hasOverride(ToolArbiter.Source.SHAPE)) {
                        applyTransition(arbiter.releaseOverride(ToolArbiter.Source.SHAPE), "shape-cancel")
                    }
                }
                is PenSession.OpenNote -> {
                    host.setDrawPathSuspended(false, "note-tap-end")
                    if (!cancelled) {
                        val shotId = s.shotId
                        if (shotId != null) openNoteLink(shotId) else openNote(s.ref)
                    }
                }
                PenSession.Consumed -> {}
            }
        } finally {
            if (wasContact) applyTransition(arbiter.penUp(), "pen-up")
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
        
        
        val marker = penStyle == PenStyle.MARKER
        val draft = BoardEngine.StrokeRec(
            s.id,
            s.space,
            penWidth,
            if (marker) markerInk.argb else INK_BLACK,
            s.points.toArray(),
            s.pressures.toArray(),
            penStyle.objType,
            density * BoardEngine.scale,
            DrawPathClient.widthArgument(penStyle, penWidth),
        )
        val ink = synchronized(BoardEngine.lock) { InkBackdrop.resolve(draft) }
        val rec = draft.withColor(ink)
        host.commitPlainStroke { apply(BoardHistory.Change("draw").stroke(null, rec), record = true) }
        if (!marker && (ink != INK_BLACK) != trailWhite) Log.i(TAG_PEN, "[MosaicTrail] color differs from hardware trail committedWhite=${ink != INK_BLACK} trailWhite=$trailWhite stroke=${s.id}")
        val committedPressures = s.pressures.toArray()
        val minPressure = committedPressures.minOrNull() ?: 0f
        val maxPressure = committedPressures.maxOrNull() ?: 0f
        val committedDrawPathWidth = DrawPathClient.widthArgument(penStyle, penWidth)
        var minRasterWidth = Float.POSITIVE_INFINITY
        var maxRasterWidth = 0f
        for (pressure in committedPressures) {
            val width = DrawPathClient.nativePressureWidthUnits(
                penStyle.objType,
                committedDrawPathWidth,
                pressure,
            ) / (density * BoardEngine.scale).coerceAtLeast(0.001f)
            minRasterWidth = minOf(minRasterWidth, width)
            maxRasterWidth = maxOf(maxRasterWidth, width)
        }
        if (!minRasterWidth.isFinite()) minRasterWidth = 0f
        val screenMin = minRasterWidth * density * BoardEngine.scale
        val screenMax = maxRasterWidth * density * BoardEngine.scale
        Log.i(TAG_PEN, "UP committed stroke ${s.id} style=${penStyle.name} stdWidth=$penWidth drawPathWidth=$committedDrawPathWidth pressureRange=$minPressure..$maxPressure rasterWorldRange=$minRasterWidth..$maxRasterWidth rasterScreenPxRange=$screenMin..$screenMax points=${s.points.size / 2} space=${s.space} ink=${Integer.toHexString(ink)}")
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
            
            
            if (!BoardGeometry.canConnect(source, target)) {
                Log.i(TAG_PEN, "card thread rejected source=$sourceId target=${target.id} reason=color-mismatch")
                return true
            }
            if (BoardEngine.threadBetween(sourceId, target.id) != null) return true
            val thread = BoardEngine.threadStroke(newId("stroke-"), source, target)
            if (thread == null) {
                Log.i(TAG_PEN, "card thread rejected source=$sourceId target=${target.id} reason=overlap")
                return true
            }
            apply(BoardHistory.Change("thread").stroke(null, thread), record = true)
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

    
    // LASSO_BOUNDS_PAD is world units on the board; above 100% (an open note) it stays the same on screen.
    private fun lassoPadWorld(): Float = LASSO_BOUNDS_PAD / BoardEngine.scale.coerceAtLeast(1f)

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
                val bounds = BoardEngine.strokeInkBounds(stroke)
                if (!any) { out.set(bounds); any = true } else out.union(bounds)
            }
        }
        if (!any) return sel.rect
        val pad = lassoPadWorld()
        out.inset(-pad, -pad)
        return out
    }

    
    private fun selectInRect(rect: RectF): LassoSelection? {
        val cardIds = ArrayList<String>()
        val strokeIds = ArrayList<String>()
        val r = RectF()
        synchronized(BoardEngine.lock) {
            for (card in BoardEngine.cardsByZ) if (rect.contains(card.rect(r))) cardIds.add(card.id)
            val wholeCards = cardIds.toHashSet()

            val inside = ArrayList<BoardEngine.StrokeRec>()
            BoardEngine.canvasStrokesInside(rect, inside)
            
            for (s in BoardEngine.strokes.values) {
                val cardId = s.cardId ?: continue
                if (cardId in wholeCards || !BoardEngine.cards.containsKey(cardId)) continue
                if (rect.contains(BoardEngine.strokeWorldBounds(s))) inside.add(s)
            }
            val ink = inside.filterNot { isClosedShape(it) }

            val touched = BoardEngine.strokes.values.filter { s ->
                if (!isClosedShape(s)) return@filter false
                val cardId = s.cardId
                if (cardId != null && (cardId in wholeCards || !BoardEngine.cards.containsKey(cardId))) return@filter false
                shapeTouchesRect(s, rect)
            }

            val otherContent = wholeCards.isNotEmpty() || ink.any { i -> touched.none { inkOnShape(i, it) } }
            for (i in ink) strokeIds.add(i.id)
            for (shape in touched) {
                val take = if (otherContent) {
                    rect.contains(BoardEngine.strokeWorldBounds(shape))
                } else {
                    ink.none { inkOnShape(it, shape) }
                }
                if (take) strokeIds.add(shape.id)
            }

            if (strokeIds.isEmpty() && wholeCards.isEmpty()) {
                partialInkIn(rect, strokeIds)
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

    
    private fun partialInkIn(rect: RectF, out: MutableList<String>) {
        val candidates = ArrayList<BoardEngine.StrokeRec>()
        BoardEngine.queryCanvasStrokes(rect, candidates)
        for (s in BoardEngine.strokes.values) {
            if (s.cardId != null && RectF.intersects(rect, BoardEngine.strokeWorldBounds(s))) candidates.add(s)
        }
        for (s in candidates) {
            if (isClosedShape(s)) continue
            val cardId = s.cardId
            val card = if (cardId != null) BoardEngine.cards[cardId] ?: continue else null
            val ox = card?.x ?: 0f; val oy = card?.y ?: 0f
            val p = s.points
            var i = 0
            while (i + 1 < p.size) {
                if (rect.contains(p[i] + ox, p[i + 1] + oy)) { out.add(s.id); break }
                i += 2
            }
        }
    }

    
    private fun isClosedShape(s: BoardEngine.StrokeRec): Boolean =
        s.isShape && Shapes.kindOf(s).let { it != null && it != Shapes.Kind.LINE }

    
    private fun shapeTouchesRect(shape: BoardEngine.StrokeRec, worldRect: RectF): Boolean {
        val card = shape.cardId?.let { BoardEngine.cards[it] }
        val local = RectF(worldRect)
        if (card != null) local.offset(-card.x, -card.y)
        if (!RectF.intersects(local, shape.bounds)) return false
        val probe = Path().apply { addRect(local, Path.Direction.CW) }
        return probe.op(shape.path, Path.Op.INTERSECT) && !probe.isEmpty
    }

    
    private fun inkOnShape(ink: BoardEngine.StrokeRec, shape: BoardEngine.StrokeRec): Boolean {
        if (ink.id == shape.id || ink.space != shape.space || !shape.bounds.contains(ink.bounds)) return false
        val b = RectF(ink.bounds).apply { inset(-0.01f, -0.01f) }
        return Path().apply {
            addRect(b, Path.Direction.CW)
            op(shape.path, Path.Op.DIFFERENCE)
        }.isEmpty
    }

    private fun setLasso(next: LassoSelection?) {
        
        
        if (next == null && selectBelowPendingRestore) {
            selectBelowPendingRestore = false
            Log.i(TAG_LASSO, "select-below selection cleared -> toolbar back to ${arbiter.describe()}")
        }
        lasso = next
        if (next == null && shapeSelectionPending && arbiter.hasOverride(ToolArbiter.Source.SHAPE)) {
            shapeSelectionPending = false
            applyTransition(arbiter.forceExit(ToolArbiter.Source.SHAPE), "shape-selection-cleared")
        }
        exportLassoStrokes(next)
        BoardEngine.mutate { setSelection(selectionIds()) }
        updateLassoOverlay()
        refreshHostToolFlags()
        scheduleChromeUpdate()
        if (next == null) {
            
            
            
            
            
            handler.postDelayed({
                if (lasso == null && !penContact && !lassoMode) {
                    presentation.release(BoardPresentation.Reason.LASSO, linger = false)
                    content.markSettleRequested()
                    content.postInvalidateOnAnimation()
                }
            }, 700L)
        }
        if (next != null) {
            
            
            handler.post {
                if (!penContact) {
                    refreshHostToolFlags()
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
        val selectedShape = shapeStrokeOf(current)
        val shapeFilled = selectedShape?.penStyle == PenStyle.FILLED_SHAPE.objType
        
        
        val kind = if (singleCard) BoardEngine.cards[current.cardIds[0]]?.kind else null
        val baseTypes = when {
            
            singleCard && kind == "image" -> listOf("trash")
            singleCard && kind == "note" -> listOf("trash", "edit-text")
            singleCard && kind == "text" -> listOf("trash", "note", "black", "edit-text")
            singleCard -> listOf("trash", "note", "black")
            shapeKind != null -> listOf("trash") + shapeActionTypes(shapeKind, shapeFilled)
            
            isPureInkSelection(current) -> listOf("trash", "recognize")
            else -> listOf("trash")
        }
        
        val types = if (canInsertIntoHostNote()) baseTypes + "shot" else baseTypes
        
        val onDark = singleCard &&
            (BoardEngine.cards[current.cardIds[0]]?.colored == true)
        overlay.topInsetPx = chrome.toolbarHeightPx().toFloat()
        overlay.setLassoFrame(framePx, lassoActionRects(framePx, types),
            cornerHandles = !singleCard || kind == "image",
            edgeHandles = singleCard || shapeKind != null, onDark = onDark,
            rotateHandle = selectionRotatable(current),
            horizontalEdgesOnly = kind == "note")
    }

    
    private fun shapeKindOf(sel: LassoSelection): Shapes.Kind? {
        if (sel.cardIds.isNotEmpty()) return null
        val stroke = shapeStrokeOf(sel) ?: return null
        return Shapes.kindOf(stroke)
    }

    private fun shapeStrokeOf(sel: LassoSelection): BoardEngine.StrokeRec? =
        sel.strokeIds.asSequence().mapNotNull { BoardEngine.strokes[it] }
            .firstOrNull { it.isShape && Shapes.kindOf(it) != null }

    private fun shapeActionTypes(kind: Shapes.Kind, filled: Boolean): List<String> {
        val fillAction = if (filled) "hollow" else "fill"
        return when (kind) {
            Shapes.Kind.RECT -> listOf("square", fillAction)
            Shapes.Kind.ELLIPSE -> listOf("circle", fillAction)
            Shapes.Kind.TRIANGLE -> listOf("iso", "equi", "right", fillAction)
            Shapes.Kind.LINE -> emptyList()
        }
    }

    
    private fun isPureInkSelection(sel: LassoSelection): Boolean {
        if (sel.cardIds.isNotEmpty() || sel.strokeIds.isEmpty()) return false
        return synchronized(BoardEngine.lock) {
            sel.strokeIds.all { id ->
                val s = BoardEngine.strokes[id] ?: return@all false
                s.cardId == null && s.connectionId == null &&
                    s.penStyle != PenStyle.FILLED_SHAPE.objType && !s.isShape
            }
        }
    }

    
    private fun selectionRotatable(sel: LassoSelection): Boolean =
        sel.strokeIds.isNotEmpty() && !isSingleCardSelection(sel)

    
    private fun lassoActionRects(frame: RectF, types: List<String>): List<Pair<RectF, String>> {
        val d = density
        val size = InteractionOverlayView.LASSO_ACTION_SIZE_DP * d
        val gap = InteractionOverlayView.LASSO_ACTION_GAP_DP * d
        val spacing = InteractionOverlayView.LASSO_ACTION_SPACING_DP * d
        val margin = InteractionOverlayView.LASSO_ACTION_MARGIN_DP * d
        val w = host.width.toFloat()
        val h = host.height.toFloat()
        val n = types.size
        val colH = n * size + max(0, n - 1) * spacing
        val rowW = n * size + max(0, n - 1) * spacing
        val right = frame.right + gap
        val left = frame.left - gap - size
        val sideFits = { x: Float -> x >= margin && x + size + margin <= w }
        if (sideFits(right) || sideFits(left)) {
            val x = if (sideFits(right)) right else left
            val y = min(max(frame.top + 4f * d, margin), max(margin, h - colH - margin))
            return types.mapIndexed { i, t ->
                val top = y + i * (size + spacing)
                Pair(RectF(x, top, x + size, top + size), t)
            }
        }
        val startX = min(max(frame.centerX() - rowW / 2f, margin), max(margin, w - rowW - margin))
        val below = frame.bottom + gap
        val above = frame.top - gap - size
        val y = if (below + size + margin <= h) below
        else if (above >= margin) above
        else min(max(below, margin), max(margin, h - size - margin))
        return types.mapIndexed { i, t ->
            val x = startX + i * (size + spacing)
            Pair(RectF(x, y, x + size, y + size), t)
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
            "recognize" -> onRecognizeLasso()
            "shot" -> onInsertLassoIntoNote()
            "edit-text" -> {
                val card = lasso?.cardIds?.singleOrNull()?.let { BoardEngine.cards[it] }
                if (card?.kind == "text" || card?.kind == "note") editTextCard(card)
            }
            "square" -> convertShape(action) { Shapes.toSquare(it) }
            "circle" -> convertShape(action) { Shapes.toCircle(it) }
            "iso" -> convertShape(action) { Shapes.toTriangle(it, Shapes.TriangleForm.ISOSCELES) }
            "equi" -> convertShape(action) { Shapes.toTriangle(it, Shapes.TriangleForm.EQUILATERAL) }
            "right" -> convertShape(action) { Shapes.toTriangle(it, Shapes.TriangleForm.RIGHT) }
            "fill" -> setShapeFill(true)
            "hollow" -> setShapeFill(false)
        }
    }

    private fun setShapeFill(filled: Boolean) {
        val sel = lasso ?: return
        val stroke = shapeStrokeOf(sel) ?: return
        val style = if (filled) PenStyle.FILLED_SHAPE else PenStyle.PEN
        if (stroke.penStyle == style.objType) return
        
        val change = BoardHistory.Change(if (filled) "shape-fill" else "shape-hollow")
            .stroke(stroke, stroke.withPenStyle(style))
        apply(change, record = true)
        updateLassoOverlay()
    }

    

    
    
    private fun finalizeShape(s: PenSession.Shape): Boolean {
        val dragPx = hypot(s.curX - s.anchorX, s.curY - s.anchorY)
        if (dragPx < SHAPE_MIN_DRAG_DP * density) {
            Log.i(TAG_LASSO, "shape: drag too short ${dragPx}px")
            return false
        }
        val ax = worldX(s.anchorX); val ay = worldY(s.anchorY)
        val shape = Shapes.fromDrag(s.kind, ax, ay, worldX(s.curX), worldY(s.curY))
        val card = BoardGeometry.topCardAt(BoardEngine.cardsByZ, ax, ay)
        if (card != null) {
            var i = 0
            while (i < shape.size) { shape[i] -= card.x; shape[i + 1] -= card.y; i += 2 }
        }
        
        val rec = BoardEngine.StrokeRec(
            newId("shape-"), if (card == null) "canvas" else "card:${card.id}", penWidth, INK_BLACK,
            shape, Shapes.pressures(shape.size / 2), penStyle.objType, density * BoardEngine.scale,
            DrawPathClient.widthArgument(penStyle, penWidth),
        )
        apply(BoardHistory.Change("shape").stroke(null, rec), record = true)
        val kind = Shapes.kindOf(rec)
        Log.i(TAG_LASSO, "shape: ${kind} points=${shape.size / 2} card=${card?.id ?: "canvas"}")
        selectStroke(rec)
        return true
    }

    
    private fun selectStroke(rec: BoardEngine.StrokeRec) {
        val frame = synchronized(BoardEngine.lock) { RectF(BoardEngine.strokeInkBounds(rec)) }
        val pad = lassoPadWorld()
        frame.inset(-pad, -pad)
        setLasso(LassoSelection(frame, emptyList(), listOf(rec.id)))
    }

    
    private fun convertShape(action: String, transform: (FloatArray) -> FloatArray) {
        val sel = lasso ?: return
        val kind = shapeKindOf(sel) ?: return
        val stroke = shapeStrokeOf(sel) ?: return
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
        if (!InklingLink.isInkSurface()) {
            Log.i(TAG_LASSO, "inkling clear-selection deferred surface=${InklingLink.currentSurface()} delete=$delete")
            return
        }
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
        if (!InklingLink.isBoardSurface()) {
            Log.i(TAG, "inkling text-card deferred surface=${InklingLink.currentSurface()}")
            return
        }
        refreshViewOffset()
        val wx = worldX((anchorScreenX - viewOffsetX).toFloat())
        val wy = worldY((anchorScreenY - viewOffsetY).toFloat())
        emitImportedTextCard(TextReflow.reflow(text), wx, wy, "inkling", centered = false)
    }

    
    fun insertDocTextCard(text: String) {
        if (!surface.showsBoardChrome) {
            Log.i(TAG, "doc text-card ignored surface=${surface.name}")
            return
        }
        val view = viewWorld()
        emitImportedTextCard(TextReflow.reflow(text), view.centerX(), view.centerY(), "doc", centered = true)
    }

    
    fun importNoteCard(path: String, title: String, done: (Result<Boolean>) -> Unit) {
        if (!surface.showsBoardChrome || suspendedBoard != null || surfaceSwitching || notesDirectory.isBlank()) {
            Log.i(TAG, "note import deferred surface=${surface.name}")
            done(Result.success(false))
            return
        }
        val directory = notesDirectory
        val ref = "note-" + UUID.randomUUID().toString().replace("-", "").take(12)
        clipboardIo.submitNoteImport(File(path)) { parsed ->
            handler.post {
                val imported = parsed.getOrElse {
                    Log.e(TAG, "note import parse failed path=$path", it)
                    done(Result.failure(it))
                    return@post
                }
                noteController.create(directory, ScrollingDocument(ref, imported.strokes, 0f)) { written ->
                    val doc = written.getOrElse {
                        Log.e(TAG, "note import write failed ref=$ref", it)
                        done(Result.failure(it))
                        return@create
                    }
                    if (!surface.showsBoardChrome || suspendedBoard != null || surfaceSwitching) {
                        File(directory, "$ref.mnote").delete(); File(directory, ref).deleteRecursively()
                        Log.i(TAG, "note import deferred after write surface=${surface.name}")
                        done(Result.success(false))
                        return@create
                    }
                    val header = BoardContentView.noteHeaderFromText(title)
                    val headerLines = if (header.isEmpty()) emptyList() else header.split('\n')
                    val size = FloatArray(2)
                    noteCardBoardSize(doc.contentHeight, NOTE_CARD_BOARD_WIDTH, size, BoardContentView.noteHeaderMarkdown(headerLines))
                    val view = viewWorld()
                    val top = if (size[1] < view.height()) view.centerY() - size[1] / 2f
                        else view.top + toolbarHeightWorld() / BoardEngine.scale + LASSO_BOUNDS_PAD * 2f
                    val card = BoardEngine.CardRec(
                        newId("card-"), view.centerX() - size[0] / 2f, top, size[0], size[1],
                        BoardGeometry.nextZIndex(BoardEngine.cards.values), "note", header,
                        File(directory, ref).absolutePath, ref, "", "", headerLines.firstOrNull().orEmpty(),
                    )
                    apply(BoardHistory.Change("import-note").card(null, card), record = true)
                    selectSingleCard(card); scheduleChromeUpdate()
                    Log.i(TAG, "imported note card=${card.id} ref=$ref pages=${imported.pageCount} strokes=${doc.strokes.size} size=${size[0]}x${size[1]}")
                    done(Result.success(true))
                }
            }
        }
    }

    
    private fun emitImportedTextCard(text: String, x: Float, y: Float, source: String, centered: Boolean) {
        if (text.isBlank()) return
        var size = BoardContentView.measureTextCardSize(text, IMPORT_CARD_WIDTHS[0])
        for (w in IMPORT_CARD_WIDTHS) {
            size = BoardContentView.measureTextCardSize(text, w)
            if (size.y <= w * IMPORT_CARD_MAX_ASPECT) break
        }
        val left = if (centered) x - size.x / 2f else x
        val top = if (centered) y - size.y / 2f else y
        emitter.action("insertTextCard") {
            putString("text", text)
            putDouble("x", left.toDouble())
            putDouble("y", top.toDouble())
            putDouble("width", size.x.toDouble())
            putDouble("height", size.y.toDouble())
            putString("source", source)
        }
        Log.i(TAG, "imported text card source=$source at world=($left,$top) size=${size.x}x${size.y} len=${text.length}")
    }

    
    private fun normalizeColoredCardToDefault(card: BoardEngine.CardRec): BoardHistory.Change? {
        if (!card.colored) return null
        val next = card.withColors("", "")
        val change = BoardHistory.Change("card-normalize")
        change.card(card, next)
        
        reconcileConnectionsForColor(next, change)
        return change
    }

    private fun toggleSelectedCardBlack() {
        val card = selectedCardId?.let { BoardEngine.cards[it] } ?: return
        if (card.kind == "image" || card.kind == "note") return
        val isBlack = card.bgColor.equals("#000000", ignoreCase = true)
        val newBg = if (isBlack) "" else "#000000"
        val newText = if (isBlack) "" else "#ffffff"
        val moved = card.withColors(newBg, newText)
        val change = BoardHistory.Change("card-color").card(card, moved)
        
        val relinked = reconcileConnectionsForColor(moved, change)
        apply(change, record = true)
        Log.i(TAG, "toggle card black id=${card.id} wasBlack=$isBlack connections=$relinked")
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

    

    
    
    private fun invalidateClipboardWork(reason: String) {
        val pasteGeneration = clipboardPasteGeneration.incrementAndGet()
        clipboardPasteTask?.cancel(true)
        clipboardPasteTask = null
        clipboardExportGeneration.incrementAndGet()
        Log.i(TAG_LASSO, "clipboard work invalidated reason=$reason generation=$pasteGeneration")
    }

    private fun exportLassoStrokes(sel: LassoSelection?) {
        val sequence = clipboardExportGeneration.incrementAndGet()
        val file = File(InklingLink.CLIP_LASSO_FILE)
        if (sel == null) {
            clipboardIo.submitDelete(file) { clipboardExportGeneration.get() == sequence }
            return
        }
        val out = LinkedHashMap<String, ClipboardStrokeIo.ExportStroke>()
        
        
        synchronized(BoardEngine.lock) {
            for (id in sel.strokeIds) {
                val rec = BoardEngine.strokes[id] ?: continue
                val card = rec.cardId?.let { BoardEngine.cards[it] }
                out[rec.id] = ClipboardStrokeIo.ExportStroke(
                    rec.penStyle,
                    rec.width,
                    rec.points.copyOf(),
                    rec.pressures.copyOf(),
                    card?.x ?: 0f,
                    card?.y ?: 0f,
                    rec.drawPathWidth,
                    rec.color,
                )
            }
            for (cardId in sel.cardIds) {
                val card = BoardEngine.cards[cardId] ?: continue
                BoardEngine.cardStrokes[cardId]?.forEach { rec ->
                    out[rec.id] = ClipboardStrokeIo.ExportStroke(
                        rec.penStyle,
                        rec.width,
                        rec.points.copyOf(),
                        rec.pressures.copyOf(),
                        card.x,
                        card.y,
                        rec.drawPathWidth,
                        rec.color,
                    )
                }
            }
        }
        // A note page is shown at notePageScale; export at that size so the clip matches what is on screen.
        clipboardIo.submitExport(
            target = file,
            strokes = out.values.toList(),
            density = density * if (surface.showsBoardChrome) 1f else notePageScale,
            isCurrent = { clipboardExportGeneration.get() == sequence },
        ) { result ->
            result.onSuccess { count ->
                if (count > 0) Log.i(TAG_LASSO, "exported $count strokes -> ${file.path}")
            }.onFailure { Log.w(TAG_LASSO, "exportLassoStrokes failed", it) }
        }
    }

    
    fun handlePasteStrokes() {
        if (!InklingLink.isInkSurface()) {
            Log.i(TAG_LASSO, "paste deferred surface=${InklingLink.currentSurface()}")
            return
        }
        val file = File(InklingLink.PASTE_STROKES_FILE)
        val generation = clipboardPasteGeneration.incrementAndGet()
        clipboardPasteTask?.cancel(true)
        val viewCx = (host.width / density / 2f - BoardEngine.panX) / BoardEngine.scale
        val viewCy = (host.height / density / 2f - BoardEngine.panY) / BoardEngine.scale
        val d = density
        
        
        val worldScale = 1f / (d * BoardEngine.scale)
        
        val metrics = host.resources.displayMetrics
        val transform = ClipboardStrokeIo.PasteTransform(
            viewCx, viewCy, 0f, 0f, worldScale, 1f / worldScale,
            minOf(metrics.widthPixels, metrics.heightPixels).toFloat(),
        )
        clipboardPasteTask = clipboardIo.submitPaste(file, transform) { result ->
            handler.post {
                if (generation != clipboardPasteGeneration.get() || surfaceSwitching || !InklingLink.isInkSurface()) return@post
                val batch = result.getOrNull()
                if (batch == null || batch.strokes.isEmpty()) {
                    result.exceptionOrNull()?.let { Log.w(TAG_LASSO, "paste parse failed", it) }
                    return@post
                }
                val change = BoardHistory.Change("paste")
                val pastedIds = ArrayList<String>(batch.strokes.size)
                val recs = batch.strokes.map { stroke ->
                    BoardEngine.StrokeRec(
                        UUID.randomUUID().toString(), "canvas", stroke.width, stroke.color,
                        stroke.points, stroke.pressures, stroke.penStyle, stroke.sampleScale, stroke.drawPathWidth,
                    )
                }
                
                
                val darkGrayMarkers = recs.filter {
                    it.penStyle == PenStyle.MARKER.objType && MarkerInk.fromArgb(it.color) == MarkerInk.DARK_GRAY
                }
                var pinned = 0
                for ((index, rec) in recs.withIndex()) {
                    val pin = batch.strokes[index].noteWhite && darkGrayMarkers.isNotEmpty() &&
                        InkBackdrop.isOverMarkers(rec, darkGrayMarkers)
                    if (pin) pinned++
                    change.stroke(null, if (pin) rec.withColor(BoardEngine.StrokeRec.INK_WHITE_PINNED) else rec)
                    pastedIds.add(rec.id)
                }
                if (pastedIds.isEmpty()) return@post
                apply(change, record = true)
                
                selectPastedStrokes(pastedIds)
                content.postInvalidateOnAnimation()
                host.invalidate()
                Log.i(
                    TAG_LASSO,
                    "pasted ${pastedIds.size} strokes at viewport center world=(${"%.0f".format(viewCx)},${"%.0f".format(viewCy)}) " +
                        "size=${"%.0f".format((batch.maxX - batch.minX) * worldScale * batch.pageScale)}x${"%.0f".format((batch.maxY - batch.minY) * worldScale * batch.pageScale)} " +
                        "pan=(${BoardEngine.panX},${BoardEngine.panY}) scale=${BoardEngine.scale} pageScale=${batch.pageScale} pinnedWhite=$pinned",
                )
            }
        }
    }

    
    private fun selectPastedStrokes(ids: List<String>) {
        if (ids.isEmpty()) return
        val frame = RectF()
        var any = false
        synchronized(BoardEngine.lock) {
            for (id in ids) {
                val stroke = BoardEngine.strokes[id] ?: continue
                val b = BoardEngine.strokeInkBounds(stroke)
                if (!any) { frame.set(b); any = true } else frame.union(b)
            }
        }
        if (!any) return
        val pad = lassoPadWorld()
        frame.inset(-pad, -pad)
        setLasso(LassoSelection(frame, emptyList(), ids.toList()))
    }

    
    private fun collectCardRemoval(cardId: String, change: BoardHistory.Change) {
        val card = BoardEngine.cards[cardId] ?: return
        BoardEngine.cardStrokes[cardId]?.forEach { change.stroke(it, null) }
        val threads = ArrayList<BoardEngine.StrokeRec>()
        BoardEngine.threadsOf(cardId, threads)
        for (t in threads) {
            if (change.diffs.none { it is BoardHistory.Diff.Stroke && it.before?.id == t.id }) change.stroke(t, null)
        }
        val conns = ArrayList<BoardEngine.ConnectionRec>()
        BoardEngine.connectionsOf(cardId, conns)
        for (c in conns) change.connection(c, null)
        change.card(card, null)
    }

    

    

    private fun beginResize(isPen: Boolean, pointerId: Int, wx: Float, wy: Float, card: BoardEngine.CardRec, handle: BoardGeometry.Handle): MoveSession.CardResize {
        val session = MoveSession.CardResize(isPen, pointerId, wx, wy, card, handle)
        card.rect(session.preview)
        card.rect(session.startRect)
        session.inkBounds = attachedInkWorldBounds(card.id)
        hideCardFromTiles(card.id)
        overlay.showCardPreview(worldRectToPx(session.preview, RectF()), true)
        presentation.acquire(BoardPresentation.Reason.TRANSFORM)
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
        presentation.acquire(BoardPresentation.Reason.TRANSFORM)
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
        presentation.acquire(BoardPresentation.Reason.TRANSFORM)
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
        presentation.acquire(BoardPresentation.Reason.TRANSFORM)
        val liftZ = if (isSingleCardSelection(sel)) BoardGeometry.nextZIndex(BoardEngine.cards.values) else null
        Log.i(TAG_LASSO, "move begin cards=${cards.size} strokes=${strokes.size} pen=$isPen singleCard=${liftZ != null}")
        return MoveSession.Selection(isPen, pointerId, wx, wy, sel, cards, strokes, liftZ)
    }

    private fun updateMoveSession(m: MoveSession, wx: Float, wy: Float) {
        val dx = wx - m.startWorldX
        val dy = wy - m.startWorldY
        when (m) {
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
            is MoveSession.CardResize -> {
                overlay.hideCardPreview()
                computeResizePreview(m, wx, wy)
                unhideCardFromTiles()
                val start = m.startRect
                val changed = abs(m.preview.left - start.left) > 0.01f || abs(m.preview.top - start.top) > 0.01f ||
                    abs(m.preview.width() - start.width()) > 0.01f || abs(m.preview.height() - start.height()) > 0.01f
                if (changed) {
                    val moved = m.card.withRect(m.preview)
                    val change = BoardHistory.Change("resize").card(m.card, moved)
                    apply(change, record = true)
                }
                refreshSingleCardFrame()
                Log.i(TAG, "[CardPerf] kind=resize input=${if (m.isPen) "pen" else "touch"} rawMoves=${m.rawMoves} durationMs=$elapsed handle=${m.handle}")
            }
            is MoveSession.Selection -> {
                overlay.endMovePreview()
                host.setDrawPathSuspended(false, "lasso-selection-move-end")
                val single = m.liftZ?.let { z -> m.cards.singleOrNull()?.let { it to z } }
                if (single != null && (abs(dx) > 0.01f || abs(dy) > 0.01f)) {
                    
                    val (card, z) = single
                    settleCardDrop(card, card.x + dx, card.y + dy, z)
                    refreshSingleCardFrame()
                    Log.i(TAG, "[CardPerf] kind=move input=${if (m.isPen) "pen" else "touch"} rawMoves=${m.rawMoves} durationMs=$elapsed")
                } else if (abs(dx) > 0.01f || abs(dy) > 0.01f) {
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
                        if (stroke.connectionId != null) {
                            change.stroke(stroke, stroke.translatedWorld(dx, dy))
                            continue
                        }
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
        presentation.release(BoardPresentation.Reason.TRANSFORM)
    }

    private fun cancelMoveSession(m: MoveSession) {
        when (m) {
            is MoveSession.CardResize -> {
                overlay.hideCardPreview()
                unhideCardFromTiles()
            }
            is MoveSession.Selection -> { overlay.endMovePreview(); host.setDrawPathSuspended(false, "lasso-selection-move-cancel"); updateLassoOverlay() }
            is MoveSession.SelectionResize -> { overlay.endMovePreview(); host.setDrawPathSuspended(false, "lasso-selection-resize-cancel"); updateLassoOverlay() }
            is MoveSession.SelectionRotate -> { overlay.endMovePreview(); host.setDrawPathSuspended(false, "lasso-selection-rotate-cancel"); updateLassoOverlay() }
        }
        presentation.release(BoardPresentation.Reason.TRANSFORM)
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
                host.flushInkHandoff("touch")
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
        
        if (noteHeaderHit(y) && lasso == null && !selectBelowArmed) {
            fingerGesture = FingerGesture.CardPending(id, NOTE_HEADER_PENDING)
            cancelCardLongPress()
            val task = Runnable {
                cardLongPressTask = null
                val pending = fingerGesture as? FingerGesture.CardPending ?: return@Runnable
                if (pending.fingerId != id || pending.cardId != NOTE_HEADER_PENDING) return@Runnable
                fingerGesture = FingerGesture.Idle
                editPageNoteHeader()
            }
            cardLongPressTask = task
            handler.postDelayed(task, CARD_FINGER_MOVE_HOLD_MS)
            return
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
        
        val hadSelection = currentLasso != null
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

        
        
        val linkId = noteLinkAt(wx, wy)
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
            presentation.isFlatActive -> CARD_FINGER_MOVE_HOLD_FAST_MS
            else -> CARD_FINGER_MOVE_HOLD_MS
        }
        val task = Runnable {
            cardLongPressTask = null
            val pending = fingerGesture as? FingerGesture.CardPending ?: return@Runnable
            if (pending.fingerId != id) return@Runnable
            val f = fingers[id] ?: return@Runnable
            val current = BoardEngine.cards[pending.cardId]
            if (current == null) { fingerGesture = FingerGesture.Idle; return@Runnable }
            
            selectConnectedComponent(current)
            val sel = lasso ?: return@Runnable
            fingerMoveSession = beginSelectionMove(false, id, worldX(f.x), worldY(f.y), sel)
            fingerGesture = FingerGesture.Move(id)
            Log.i(TAG_FINGER, "card hold → lasso move id=${current.id} holdMs=$holdMs")
        }
        cardLongPressTask = task
        handler.postDelayed(task, holdMs)
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
                    val card = BoardEngine.cards[g.cardId]
                    if (!cancelled && g.cardId.startsWith(NOTE_LINK_PENDING)) {
                        openNoteLink(g.cardId.removePrefix(NOTE_LINK_PENDING))
                    } else if (!cancelled && card?.kind == "note" && card.noteRef.isNotEmpty()) {
                        openNote(card.noteRef)
                    }
                }
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
                    presentation.release(BoardPresentation.Reason.SCREEN_TOOL, linger = false)
                }
            }
            is FingerGesture.EdgeSwipe -> if (g.fingerId == id) fingerGesture = FingerGesture.Idle
            FingerGesture.Idle -> {}
        }
        if (fingers.isEmpty()) fingerGesture = FingerGesture.Idle
    }

    
    private fun dp(value: Float): Float = value * density

    
    private fun editTextCard(card: BoardEngine.CardRec) {
        closeCardEditor()
        host.inkEnabled = false
        val original = card.content
        val note = card.kind == "note"
        
        val below = if (note) cardsBelow(card) else emptyList()
        var finished = false
        
        
        val tempDark = presentation.state.darkCards
        val darkText = !note && (card.colored || tempDark)
        val editor = CardTextEditor(
            host.context,
            if (note) BoardContentView.noteHeaderText(card) else original,
            darkText,
            
            fill = when {
                card.colored -> Color.BLACK
                note && tempDark -> Color.WHITE
                else -> BoardContentView.CARD_FILL_COLOR
            },
            contentScale = if (note) card.width / ScrollingDocument.WIDTH else 1f,
            headerMode = note,
            onKeyboard = { obscuredTop -> avoidKeyboard(editRegionWorld(card.id), obscuredTop) },
            cardFrame = { availableBottom ->
                val r = editRegionWorld(card.id) ?: card.rect(RectF())
                val top = screenY(r.top)
                val bottom = screenY(r.bottom).coerceAtMost(availableBottom.toFloat())
                RectF(screenX(r.left), top, screenX(r.right), bottom.coerceAtLeast(top + dp(72f)))
            },
            onChange = { markdown ->
                val current = BoardEngine.cards[card.id]
                if (current != null) {
                    val next = if (note) withNoteHeader(current, markdown) else grownToFit(current.withContent(markdown), card.height)
                    val change = BoardHistory.Change("card-text-edit-live")
                    if (!next.sameGeometry(current)) change.card(current, next)
                    pushCardsBelow(change, below, next.height - card.height)
                    apply(change, record = false)
                }
            },
            onFinish = {
                if (!finished) {
                    finished = true
                    val current = BoardEngine.cards[card.id]
                    closeCardEditor()
                    if (current != null && current.content != original) {
                        
                        val change = BoardHistory.Change("card-text-edit").card(card, current)
                        for (b in below) {
                            val moved = BoardEngine.cards[b.id] ?: continue
                            if (!moved.sameGeometry(b)) change.card(b, moved)
                        }
                        apply(change, record = true)
                    }
                }
            },
        )
        cardEditorOverlay = editor
        host.addView(editor, FrameLayout.LayoutParams(-1, -1))
        host.bringChildToFront(editor)
    }

    
    private fun avoidKeyboard(region: RectF?, obscuredTop: Int?) {
        val r = region ?: return
        val shiftPx = editorKeyboardShift * density
        val baseTop = screenY(r.top) + shiftPx
        val baseBottom = screenY(r.bottom) + shiftPx
        val wantPx = if (obscuredTop != null && baseBottom > obscuredTop) {
            minOf((host.height - obscuredTop).toFloat(), baseTop.coerceAtLeast(0f)).coerceAtLeast(0f)
        } else 0f
        val want = wantPx / density
        if (want == editorKeyboardShift) return
        Log.i(TAG, "card editor keyboard shift ${editorKeyboardShift}->$want obscuredTop=$obscuredTop")
        BoardEngine.setViewport(BoardEngine.panX, BoardEngine.panY + editorKeyboardShift - want, BoardEngine.scale)
        editorKeyboardShift = want
        scheduleChromeUpdate()
    }

    
    private fun grownToFit(card: BoardEngine.CardRec, minHeight: Float): BoardEngine.CardRec {
        val needed = BoardContentView.measureTextCardSize(card.content, card.width).y
        val height = max(minHeight, needed)
        return if (height == card.height) card else card.withRect(RectF(card.x, card.y, card.x + card.width, card.y + height))
    }

    
    private fun editRegionWorld(cardId: String): RectF? {
        val card = BoardEngine.cards[cardId] ?: return null
        val r = card.rect(RectF())
        if (card.kind == "note") {
            val k = card.width / ScrollingDocument.WIDTH
            r.bottom = r.top + BoardContentView.noteHeaderHeight(BoardContentView.noteHeaderOf(card)) * k
        }
        return r
    }

    
    private fun withNoteHeader(card: BoardEngine.CardRec, plain: String): BoardEngine.CardRec {
        val k = card.width / ScrollingDocument.WIDTH
        val oldH = BoardContentView.noteHeaderHeight(BoardContentView.noteHeaderOf(card)) * k
        
        val probe = BoardEngine.CardRec(card.id, card.x, card.y, card.width, card.height, card.zIndex, card.kind, plain,
            card.imagePath, card.noteRef, card.bgColor, card.textColor, "")
        val lines = BoardContentView.noteHeaderLines(probe)
        val newH = BoardContentView.noteHeaderHeight(BoardContentView.noteHeaderMarkdown(lines)) * k
        val height = (card.height - oldH + newH).coerceAtLeast(BoardGeometry.MIN_CARD_SIZE)
        return BoardEngine.CardRec(card.id, card.x, card.y, card.width, height, card.zIndex, card.kind, plain,
            card.imagePath, card.noteRef, card.bgColor, card.textColor, lines.firstOrNull().orEmpty())
    }

    
    private fun cardsBelow(anchor: BoardEngine.CardRec): List<BoardEngine.CardRec> {
        val column = arrayListOf(anchor.rect(RectF()))
        val result = ArrayList<BoardEngine.CardRec>()
        val candidates = synchronized(BoardEngine.lock) { BoardEngine.cards.values.filter { it.id != anchor.id } }
            .sortedBy { it.y }
        val r = RectF()
        for (c in candidates) {
            c.rect(r)
            if (column.any { r.left < it.right && r.right > it.left && r.top > it.top }) {
                result.add(c)
                column.add(RectF(r))
            }
        }
        return result
    }

    
    private fun pushCardsBelow(change: BoardHistory.Change, below: List<BoardEngine.CardRec>, grow: Float) {
        val dy = max(0f, grow)
        for (b in below) {
            val current = BoardEngine.cards[b.id] ?: continue
            val target = b.y + dy
            if (current.y != target) change.card(current, current.moved(current.x, target))
        }
    }

    
    private fun setPageNoteHeader(markdown: String) {
        currentNoteHeaderPlaceholder = markdown.isBlank()
        currentNoteHeader = if (currentNoteHeaderPlaceholder) MosaicStrings.t(MosaicStrings.Key.noteHeaderPlaceholder) else markdown
        currentNoteHeaderHeight = BoardContentView.noteHeaderHeight(currentNoteHeader.orEmpty(), notePageScale)
    }

    
    private fun pageHeaderRegionWorld(): RectF = RectF(0f, -currentNoteHeaderHeight, ScrollingDocument.WIDTH, 0f)

    
    private fun editPageNoteHeader(recognized: String? = null) {
        if (surface.showsBoardChrome) return
        val ref = noteController.document?.ref ?: return
        val source = suspendedBoard?.scene?.cards?.firstOrNull { it.noteRef == ref } ?: return
        closeCardEditor()
        host.inkEnabled = false
        val header = BoardContentView.noteHeaderText(source)
        
        var initial = header
        var selection: IntRange? = null
        if (recognized != null) {
            val text = recognized.replace(Regex("\\s*\\n\\s*"), " ").trim()
            val split = header.indexOf('\n')
            val title = if (split < 0) header else header.substring(0, split)
            val body = if (split < 0) "" else header.substring(split + 1)
            if (title.isBlank()) {
                initial = text + if (body.isNotEmpty()) "\n" + body else ""
                selection = 0 until text.length
            } else {
                val prefix = title + "\n" + body + if (body.isNotBlank()) " " else ""
                initial = prefix + text
                selection = prefix.length until initial.length
            }
        }
        val editor = CardTextEditor(
            host.context,
            initial,
            false,
            initialSelection = selection,
            initialDirty = recognized != null,
            fill = Color.WHITE,
            headerMode = true,
            onKeyboard = { obscuredTop -> avoidKeyboard(pageHeaderRegionWorld(), obscuredTop) },
            cardFrame = { availableBottom ->
                val r = pageHeaderRegionWorld()
                val top = screenY(r.top)
                val bottom = screenY(r.bottom).coerceAtMost(availableBottom.toFloat())
                RectF(screenX(r.left), top, screenX(r.right), bottom.coerceAtLeast(top + dp(72f)))
            },
            onChange = { plain -> updatePageNoteHeader(ref, plain) },
            onFinish = { closeCardEditor() },
        )
        cardEditorOverlay = editor
        host.addView(editor, FrameLayout.LayoutParams(-1, -1))
        host.bringChildToFront(editor)
        Log.i(TAG, "note header edit ref=$ref")
    }

    private fun updatePageNoteHeader(ref: String, plain: String) {
        val suspended = suspendedBoard ?: return
        val card = suspended.scene.cards.firstOrNull { it.noteRef == ref } ?: return
        val next = withNoteHeader(card, plain)
        if (next.sameGeometry(card)) return
        suspendedBoard = SuspendedBoard(
            suspended.scene.copy(cards = suspended.scene.cards.map { if (it.id == card.id) next else it }),
            suspended.history, suspended.lasso,
        )
        val oldH = currentNoteHeaderHeight
        setPageNoteHeader(BoardContentView.noteHeaderOf(next))
        content.setNoteHeader(currentNoteHeader, currentNoteHeaderPlaceholder, notePageScale)
        
        val d = (currentNoteHeaderHeight - oldH) * BoardEngine.scale
        if (d != 0f) BoardEngine.setViewport(BoardEngine.panX, BoardEngine.panY + d, BoardEngine.scale)
        
        emitter.begin()
        emitter.cardUpsert(next)
        emitter.commit()
    }

    private fun closeCardEditor() {
        val overlay = cardEditorOverlay ?: return
        if (overlay is CardTextEditor) overlay.release()
        host.removeView(overlay)
        cardEditorOverlay = null
        if (editorKeyboardShift != 0f) {
            BoardEngine.setViewport(BoardEngine.panX, BoardEngine.panY + editorKeyboardShift, BoardEngine.scale)
            editorKeyboardShift = 0f
            scheduleChromeUpdate()
        }
        refreshHostToolFlags()
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
            presentation.release(BoardPresentation.Reason.SCREEN_TOOL, linger = false)
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
        presentation.acquire(BoardPresentation.Reason.SCREEN_TOOL)
        applyTransition(arbiter.pushOverride(ToolArbiter.Source.SCREEN, tool, ToolArbiter.Settle.ON_PEN_UP), "screen-tool")
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
        
        
        
        
        if (surface.showsBoardChrome) {
            overlay.setLassoVisible(false)
            presentation.acquire(BoardPresentation.Reason.PAN_ZOOM)
        } else {
            handler.removeCallbacks(noteScrollResetTask)
            MosaicEinkRefreshModule.applyNative(MosaicEinkRefreshModule.MODE_NOTE_SCROLL, NOTE_SCROLL_EINK_OWNER)
        }
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
            // Notes zoom too now, so they also scale the cached frame instead of re-rastering tiles every frame.
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

    
    private fun noteTopInsetWorld(): Float = toolbarHeightWorld() + currentNoteHeaderHeight * BoardEngine.scale

    
    private fun noteHeaderHit(y: Float): Boolean =
        !surface.showsBoardChrome && currentNoteHeaderHeight > 0f && worldY(y) < 0f

    private fun setNoteHeaderHoverBlocked(blocked: Boolean) {
        if (noteHeaderHoverBlocked == blocked) return
        noteHeaderHoverBlocked = blocked
        host.setDrawPathRegionBlocked(blocked, "note-header")
    }

    
    private fun snapPanToPx(panDp: Float): Float = Math.round(panDp * density) / density

    
    private fun magneticZoom(scale: Float): Float {
        val level = BoardGeometry.ZOOM_LEVELS[BoardGeometry.nearestZoomIndex(scale)]
        return if (abs(level - scale) <= scale * ZOOM_SNAP_RATIO) level else scale
    }

    private fun endPanZoom(g: FingerGesture.PanZoom) {
        var panX = BoardEngine.panX
        var panY = BoardEngine.panY
        var scale = BoardEngine.scale
        
        
        
        val snapped = when {
            scale == g.startScale -> scale
            surface.showsBoardChrome -> magneticZoom(scale)
            abs(scale - notePageScale) <= notePageScale * ZOOM_SNAP_RATIO -> notePageScale
            else -> scale
        }
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
        presentation.release(BoardPresentation.Reason.PAN_ZOOM)
        if (!surface.showsBoardChrome) {
            handler.removeCallbacks(noteScrollResetTask)
            handler.postDelayed(noteScrollResetTask, NOTE_SCROLL_SETTLE_MS)
        }
        val centerX = (host.width / density / 2f - panX) / scale
        val centerY = (host.height / density / 2f - panY) / scale
        Log.i(TAG_PAN, "end frames=${g.frames} centerWorld=($centerX,$centerY) pan=($panX,$panY) scale=$scale")
    }

    

    override fun onPenState(state: InputRouter.PenState, value: Boolean) {
        when (state) {
            InputRouter.PenState.HOVER -> {
                penHovering = value
                if (!value && !penContact)
                else when (fingerGesture) {
                    
                    
                    is FingerGesture.TwoCandidate -> settleTwoCandidate("hover-enter")
                    
                    is FingerGesture.PanZoom -> yieldPanZoomToPen("hover-enter")
                    else -> {}
                }
            }
            InputRouter.PenState.RUBBER, InputRouter.PenState.STYLUS -> {
                val physical = if (state == InputRouter.PenState.RUBBER) ToolArbiter.Physical.RUBBER else ToolArbiter.Physical.STYLUS
                if (value) {
                    
                    
                    if (arbiter.hasOverride(ToolArbiter.Source.PEN_BUTTON)) {
                        if (physical == ToolArbiter.Physical.RUBBER &&
                            arbiter.overrideTool(ToolArbiter.Source.PEN_BUTTON) != ToolArbiter.Tool.ERASER
                        ) {
                            applyTransition(
                                arbiter.replaceOverride(ToolArbiter.Source.PEN_BUTTON, ToolArbiter.Tool.ERASER, ToolArbiter.Settle.ON_NEXT_PEN_DOWN),
                                "pen-button-rubber",
                            )
                        }
                        Log.i(TAG_TOOL, "pen-button down repeat physical=$physical ${arbiter.describe()}")
                        return
                    }
                    
                    
                    val near = penContact ||
                        (if (physical == ToolArbiter.Physical.STYLUS) InputReader.isPenStylusPressNear() else penHovering)
                    val bound = if (physical == ToolArbiter.Physical.RUBBER) ToolArbiter.Tool.ERASER else gestureToolOf(settings.penButton)
                    val tool = if (near) bound else ToolArbiter.Tool.ERASER
                    if (tool == null) return
                    val armed = !near
                    presentation.acquire(BoardPresentation.Reason.PEN_BUTTON)
                    applyTransition(
                        arbiter.pushOverride(ToolArbiter.Source.PEN_BUTTON, tool, ToolArbiter.Settle.ON_NEXT_PEN_DOWN, armed = armed, physical = physical),
                        "pen-button-down",
                    )
                    Log.i(TAG_TOOL, "pen-button down physical=$physical tool=$tool near=$near hover=$penHovering contact=$penContact armed=$armed")
                    host.reassertPenBlock("pen-button:$tool")
                } else {
                    if (arbiter.penButtonPhysical() != null && arbiter.penButtonPhysical() != physical) return
                    applyTransition(arbiter.releaseOverride(ToolArbiter.Source.PEN_BUTTON), "pen-button-up")
                    presentation.release(BoardPresentation.Reason.PEN_BUTTON)
                }
            }
        }
    }

    
    override fun onManualRefresh() {
        if (surfaceSwitching) return
        host.flushInkHandoff("manual-refresh")
        Log.i(TAG, "manual full refresh: exit lasso/eraser/gesture states ${arbiter.describe()} lasso=${lasso != null}")
        cancelActiveInteractions("manual-refresh")
        resetNoteScrollMode()
        for (source in ToolArbiter.Source.values()) applyTransition(arbiter.forceExit(source), "manual-refresh")
        applyTransition(arbiter.clearBase(), "manual-refresh")
        setLasso(null)
        presentation.reset("manual-refresh")
        
        
        host.redrawAfterHostRefresh()
    }

    override fun onSlider(gesture: String, side: Int) {
        if (side != settings.sliderSide) return
        host.flushInkHandoff("slider")
        Log.i(TAG, "[Slider] $gesture side=$side")
        when (gesture) {
            "slideUp" -> redo()
            "slideDown" -> undo()
            
            
            
            
            "twoDown" -> if (gestureToolOf(settings.slidebarGesture) != null) {
                sliderTwoDown = true
                presentation.acquire(BoardPresentation.Reason.SLIDER)
                host.setDrawPathSuspended(true, "slider-two-down")
                
                (penSession as? PenSession.Write)?.let { it.convertFromIndex = it.points.size }
            }
            "twoUp" -> {
                sliderTwoDown = false
                host.setDrawPathSuspended(false, "slider-two-up")
                
                presentation.release(BoardPresentation.Reason.SLIDER, linger = false)
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
        val selected = selectedCardId?.let { BoardEngine.cards[it] }
        val levels = if (selected == null) emptyList() else synchronized(BoardEngine.lock) {
            BoardGeometry.computeSizeLevels(selected, BoardEngine.cards, BoardEngine.connections.values)
        }
        chrome.setMode(
            selectedCard = selected != null,
            selectedCardColored = selected?.colored == true,
            selectedCardKind = selected?.kind,
            sizeLevels = levels,
            
            lassoCards = selected == null && lasso?.cardIds?.isNotEmpty() == true,
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

    override fun onSaveArchive() {
        emitter.begin(); emitter.action("saveArchive"); emitter.commit()
    }

    override fun onLoadArchive() {
        emitter.begin(); emitter.action("loadArchive"); emitter.commit()
    }

    
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
    override fun onPenStyle(style: PenStyle, width: Float) {
        
        if (lasso != null) leaveShapeSelectionForToolbar()
        setPenStyle(style, width)
    }
    override fun onMarkerInk(ink: MarkerInk) {
        if (lasso != null) leaveShapeSelectionForToolbar()
        setMarkerInk(ink)
    }
    private fun leaveShapeSelectionForToolbar() {
        if (arbiter.hasOverride(ToolArbiter.Source.SHAPE)) {
            shapeSelectionPending = false
            applyTransition(arbiter.forceExit(ToolArbiter.Source.SHAPE), "toolbar-leave-shape")
        }
        if (lasso != null) setLasso(null)
    }

    override fun onToggleEraser() {
        leaveShapeSelectionForToolbar()
        applyTransition(arbiter.toggleBase(ToolArbiter.Tool.ERASER), "toolbar-eraser")
    }
    override fun onToggleLasso() {
        leaveShapeSelectionForToolbar()
        applyTransition(arbiter.toggleBase(ToolArbiter.Tool.LASSO), "toolbar-lasso")
    }
    
    override fun onShapeSelected(kind: Shapes.Kind) {
        if (shapeMode && shapeKind == kind) {
            val transition = if (arbiter.hasOverride(ToolArbiter.Source.SHAPE)) {
                arbiter.releaseOverride(ToolArbiter.Source.SHAPE)
            } else {
                arbiter.toggleBase(ToolArbiter.Tool.SHAPE)
            }
            applyTransition(transition, "toolbar-shape-off")
            return
        }
        shapeKind = kind
        if (arbiter.hasOverride(ToolArbiter.Source.SHAPE)) {
            
            
            
            
            
            shapeSelectionPending = false
            applyTransition(
                arbiter.replaceOverride(
                    ToolArbiter.Source.SHAPE,
                    ToolArbiter.Tool.SHAPE,
                    ToolArbiter.Settle.ON_PEN_UP,
                ),
                "toolbar-shape-rearm:$kind",
            )
            if (lasso != null) setLasso(null)
        } else if (!shapeMode) {
            
            if (lasso != null) setLasso(null)
            
            
            applyTransition(
                arbiter.pushOverride(ToolArbiter.Source.SHAPE, ToolArbiter.Tool.SHAPE, ToolArbiter.Settle.ON_PEN_UP),
                "toolbar-shape:$kind",
            )
        }
        else chrome.setCurrentShape(kind)
        Log.i(TAG_TOOL, "shape kind=$kind active=$shapeMode")
    }
    override fun onSelectWriteTool() {
        leaveShapeSelectionForToolbar()
        applyTransition(arbiter.clearBase(), "toolbar-write")
    }
    
    override fun onTemplateSelected(template: BackgroundTemplate) {
        
        TemplateStore.setBoard(host.context, template)
        val pageWidth = if (surface.showsBoardChrome) 0f else ScrollingDocument.WIDTH
        content.setBackgroundTemplate(template, pageWidth)
        chrome.setCurrentTemplate(template)
    }
    override fun onUndo() = undo()
    override fun onRedo() = redo()

    override fun onSync(enable: Boolean, address: String) {
        emitter.begin()
        emitter.action("sync") {
            putBoolean("enable", enable)
            putString("address", address)
        }
        emitter.commit()
    }

    override fun onConvertCardToNote() {
        if (suspendedBoard != null || surfaceSwitching || notesDirectory.isBlank()) return
        var source = selectedCardId?.let { BoardEngine.cards[it] } ?: return
        if (source.kind == "note" || source.kind == "image") return
        normalizeColoredCardToDefault(source)?.let { pre ->
            apply(pre, record = true)
            source = BoardEngine.cards[source.id] ?: return
        }
        
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
                    noteController.clear()
                    val size = FloatArray(2)
                    
                    val header = BoardContentView.noteHeaderFromText(source.content)
                    val headerLines = if (header.isEmpty()) emptyList() else header.split('\n')
                    noteCardBoardSize(contentHeight, source.width, size, BoardContentView.noteHeaderMarkdown(headerLines))
                    
                    val note = BoardEngine.CardRec(source.id, source.x, source.y, size[0], size[1], source.zIndex, "note", header, noteController.previewPath(ref), ref, "", "", headerLines.firstOrNull().orEmpty())
                    val change = BoardHistory.Change("card-to-note")
                    synchronized(BoardEngine.lock) { BoardEngine.cardStrokes[source.id]?.forEach { change.stroke(it, null) } }
                    change.card(source, note)
                    pushCardsBelow(change, cardsBelow(source), note.height - source.height)
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

    override fun onTranslucentLevel(level: Int) {
        TranslucentStore.setLevel(host.context, level)
        content.setTranslucentLevel(level)
        Log.i(TAG, "translucent level=$level% seeThrough=${TranslucentStore.seeThroughPercent(level)}%")
    }

    override fun onZoomStep(direction: Int) {
        val index = BoardGeometry.nearestZoomIndex(BoardEngine.scale)
        val next = (index + direction).coerceIn(0, BoardGeometry.ZOOM_LEVELS.size - 1)
        if (BoardGeometry.ZOOM_LEVELS[next] == BoardEngine.scale) return
        zoomTo(BoardGeometry.ZOOM_LEVELS[next])
    }

    override fun onZoomReset() = zoomTo(BoardGeometry.RESET_ZOOM)

    override fun onToggleTouch() = setTouchEnabled(!touchEnabled, fromJs = false)

    override fun onPenBlockChanged() = refreshHostToolFlags()

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

    
    private fun onRecognizeLasso() {
        val sel = lasso ?: return
        if (!isPureInkSelection(sel)) {
            Log.i(TAG_LASSO, "recognize skipped: selection not pure ink cards=${sel.cardIds.size} strokes=${sel.strokeIds.size}")
            return
        }
        val strokeIds = sel.strokeIds.toList()
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
        if (!surface.showsBoardChrome) {
            
            setLasso(null)
            editPageNoteHeader(recognized = trimmed)
            Log.i(TAG_LASSO, "recognize note header strokes=${pending.strokeIds.size} chars=${trimmed.length}")
            return
        }
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
                
                
                chrome.cancelTransientPresses()
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
        val notes = synchronized(BoardEngine.lock) {
            BoardEngine.cardsByZ.filter { it.kind == "note" }
        }
        
        val links = NoteLinks.list().filter { NoteLinks.regionOf(it.id) != null }
        chrome.showSwitcher(notes, links)
        refreshHostToolFlags()
    }

    override fun onSwitcherDismissed() {
        chrome.hideSwitcher()
        refreshHostToolFlags()
    }

    
    fun setSyncState(enabled: Boolean, state: String, address: String) {
        chrome.setSyncState(enabled, state, address)
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

    
    private fun noteLinkAt(wx: Float, wy: Float): String? {
        if (!surface.showsBoardChrome) return null
        val scale = BoardEngine.scale
        return NoteLinks.badgeAt(wx, wy, NOTE_LINK_TAP_SLOP_DP / scale, NOTE_LINK_MIN_HIT_DP / scale)
    }

    private fun openNoteLink(shotId: String) {
        val link = NoteLinks.list().firstOrNull { it.id == shotId } ?: return
        if (surfaceSwitching || suspendedBoard != null) return
        val current = MosaicNoteShotModule.currentHostNotePath()
        val sameNote = current != null && current == link.notePath
        val name = link.noteName.ifBlank { MosaicStrings.t(MosaicStrings.Key.noteLinkFallback) }
        Log.i(TAG_SHOT, "open note link id=$shotId note=${link.notePath} page=${link.page} current=$current same=$sameNote")
        MosaicNoteShotModule.logHostApisOnce()
        cancelFingerGestures("note-link")
        if (!sameNote) {
            Toast.makeText(host.context, MosaicStrings.noteLinkElsewhere(name, link.page), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(host.context, MosaicStrings.noteLinkArrived(name, link.page), Toast.LENGTH_LONG).show()
        surface.onCloseRequested(BoardSurface.CloseSource.TOOLBAR)
    }

    override fun onLocateNoteLink(shotId: String) {
        val region = NoteLinks.regionOf(shotId) ?: return
        cancelFingerGestures("locate-note-link")
        val top = chrome.toolbarHeightPx() / density
        val w = host.width / density
        val h = max(1f, host.height / density - top)
        val fit = min(w / max(region.width(), 1f), h / max(region.height(), 1f))
        val scale = BoardGeometry.clampZoom(min(BoardEngine.scale, fit))
        val centered = SparseNavigation.panToCenterRect(region, w, h, scale)
        chrome.hideSwitcher()
        refreshHostToolFlags()
        commitViewport(centered[0], centered[1] + top, scale)
        Log.i(TAG_SHOT, "locate link id=$shotId region=$region scale=$scale")
    }

    

    
    private var noteShotBusy = false
    
    private var noteShotFocusToken = 0
    
    private var noteShotSessionActive = false

    
    private class NoteShotPng(val path: String, val width: Int, val height: Int, val hotspot: NoteShotExport.Hotspot)

    
    private fun canInsertIntoHostNote(): Boolean =
        surface.showsBoardChrome && MosaicNoteShotModule.currentHostNotePath() != null

    
    private fun onInsertLassoIntoNote() {
        val sel = lasso ?: return
        if (!canInsertIntoHostNote()) {
            Log.i(TAG_SHOT, "insert skipped: host file is not a note or surface=${surface.name}")
            return
        }
        if (noteShotBusy) {
            Log.i(TAG_SHOT, "insert ignored: previous snapshot still rendering")
            return
        }
        val anchored = synchronized(BoardEngine.lock) { NoteShotExport.capture(sel.cardIds, sel.strokeIds) } ?: return
        val layout = NoteShotExport.layout(
            anchored.region, BoardEngine.scale * density, density,
            host.width * NOTE_SHOT_MAX_SCREEN_FRACTION, host.height * NOTE_SHOT_MAX_SCREEN_FRACTION,
        )
        val fingerprint = NoteShotExport.fingerprint(anchored.region)
        val shotId = newId("shot-")
        val screen = realDisplaySize()
        Log.i(
            TAG_SHOT,
            "insert render id=$shotId region=${anchored.region} cards=${anchored.cards.size} " +
                "strokes=${anchored.strokes.size} pxPerWorld=${layout.pxPerWorld} out=${layout.widthPx}x${layout.heightPx}",
        )
        renderNoteShotPng(shotId, layout) { png ->
            if (png == null) return@renderNoteShotPng
            
            
            setLasso(null)
            emitter.begin()
            emitter.action("noteShotReady") {
                putString("shotId", shotId)
                putNoteShotPng(this, png, anchored, fingerprint, layout.pxPerWorld)
                putInt("screenW", screen.x)
                putInt("screenH", screen.y)
            }
            emitter.commit()
        }
    }

    
    fun focusNoteShot(json: String) {
        val shot = NoteShotExport.Shot.parse(json)
        if (shot == null) {
            Log.w(TAG_SHOT, "focus skipped: unreadable shot")
            return
        }
        noteShotSessionActive = true
        val token = ++noteShotFocusToken
        focusNoteShotWhenReady(shot, token, NOTE_SHOT_FOCUS_RETRIES)
    }

    private fun focusNoteShotWhenReady(shot: NoteShotExport.Shot, token: Int, retries: Int) {
        if (token != noteShotFocusToken) return
        if (host.width == 0 || host.height == 0 || surfaceSwitching || !surface.showsBoardChrome) {
            if (retries > 0) {
                handler.postDelayed({ focusNoteShotWhenReady(shot, token, retries - 1) }, NOTE_SHOT_FOCUS_RETRY_MS)
            } else {
                Log.w(TAG_SHOT, "focus gave up id=${shot.id} width=${host.width} surface=${surface.name}")
            }
            return
        }
        val region = synchronized(BoardEngine.lock) { NoteShotExport.resolve(shot) }?.region ?: return
        cancelFingerGestures("note-shot")
        setLasso(null)
        chrome.hideSwitcher()
        refreshHostToolFlags()
        
        val top = chrome.toolbarHeightPx() / density
        val w = host.width / density
        val h = max(1f, host.height / density - top)
        val fit = min(w / max(region.width(), 1f), h / max(region.height(), 1f))
        val captured = if (shot.pxPerWorld > 0f) shot.pxPerWorld / density else BoardEngine.scale
        val scale = BoardGeometry.clampZoom(min(captured, fit))
        val centered = SparseNavigation.panToCenterRect(region, w, h, scale)
        commitViewport(centered[0], centered[1] + top, scale)
        Log.i(TAG_SHOT, "focus id=${shot.id} region=$region scale=$scale")
    }

    
    fun renderNoteShot(json: String, done: (WritableMap?) -> Unit) {
        val shot = NoteShotExport.Shot.parse(json)
        if (shot == null || !surface.showsBoardChrome || noteShotBusy) {
            Log.i(TAG_SHOT, "render skipped id=${shot?.id} surface=${surface.name} busy=$noteShotBusy")
            done(null)
            return
        }
        val anchored = synchronized(BoardEngine.lock) { NoteShotExport.resolve(shot) }
        if (anchored == null) {
            done(null)
            return
        }
        val fingerprint = NoteShotExport.fingerprint(anchored.region)
        if (fingerprint == shot.fingerprint) {
            Log.i(TAG_SHOT, "render id=${shot.id} unchanged")
            done(Arguments.createMap().apply { putBoolean("unchanged", true) })
            return
        }
        val pxPerWorld = if (shot.pxPerWorld > 0f) shot.pxPerWorld else BoardEngine.scale * density
        val layout = NoteShotExport.layout(
            anchored.region, pxPerWorld, density,
            host.width * NOTE_SHOT_MAX_SCREEN_FRACTION, host.height * NOTE_SHOT_MAX_SCREEN_FRACTION,
        )
        Log.i(
            TAG_SHOT,
            "render id=${shot.id} region=${anchored.region} cards=${anchored.cards.size} " +
                "strokes=${anchored.strokes.size} out=${layout.widthPx}x${layout.heightPx}",
        )
        renderNoteShotPng(shot.id, layout) { png ->
            if (png == null) {
                done(null)
                return@renderNoteShotPng
            }
            done(Arguments.createMap().apply {
                putBoolean("unchanged", false)
                putNoteShotPng(this, png, anchored, fingerprint, layout.pxPerWorld)
            })
        }
    }

    private fun putNoteShotPng(
        map: WritableMap,
        png: NoteShotPng,
        anchored: NoteShotExport.Anchored,
        fingerprint: String,
        pxPerWorld: Float,
    ) {
        val region = anchored.region
        map.putString("path", png.path)
        map.putInt("width", png.width)
        map.putInt("height", png.height)
        map.putMap("rect", Arguments.createMap().apply {
            putDouble("x", region.left.toDouble())
            putDouble("y", region.top.toDouble())
            putDouble("w", region.width().toDouble())
            putDouble("h", region.height().toDouble())
        })
        map.putMap("hotspot", Arguments.createMap().apply {
            putDouble("x", png.hotspot.x.toDouble())
            putDouble("y", png.hotspot.y.toDouble())
            putDouble("w", png.hotspot.w.toDouble())
            putDouble("h", png.hotspot.h.toDouble())
        })
        map.putString("anchors", anchored.anchorsJson())
        map.putString("fingerprint", fingerprint)
        map.putDouble("pxPerWorld", pxPerWorld.toDouble())
    }

    
    private fun renderNoteShotPng(shotId: String, layout: NoteShotExport.Layout, done: (NoteShotPng?) -> Unit) {
        noteShotBusy = true
        val densityValue = density
        val outPath = "$NOTE_SHOT_DIR/$shotId-${System.currentTimeMillis()}.png"
        val startedAt = SystemClock.uptimeMillis()
        content.renderExport(layout.region, layout.pxPerWorld, layout.stripPx) { bitmap ->
            if (bitmap == null) {
                Log.w(TAG_SHOT, "raster failed id=$shotId")
                handler.post {
                    noteShotBusy = false
                    done(null)
                }
                return@renderExport
            }
            val hotspot = NoteShotExport.drawTag(Canvas(bitmap), densityValue, bitmap.width, bitmap.height)
            Thread({
                var png: NoteShotPng? = null
                try {
                    val file = File(outPath)
                    file.parentFile?.mkdirs()
                    val tmp = File("$outPath.tmp")
                    NoteShotExport.writeGrayPng(bitmap, tmp)
                    if (!tmp.renameTo(file)) throw IllegalStateException("rename failed $tmp -> $file")
                    png = NoteShotPng(outPath, bitmap.width, bitmap.height, hotspot)
                    Log.i(
                        TAG_SHOT,
                        "png ok ${bitmap.width}x${bitmap.height}px ${file.length() / 1024}KB " +
                            "in ${SystemClock.uptimeMillis() - startedAt}ms -> $outPath",
                    )
                } catch (error: Throwable) {
                    Log.e(TAG_SHOT, "png write failed id=$shotId", error)
                } finally {
                    bitmap.recycle()
                }
                handler.post {
                    noteShotBusy = false
                    done(png)
                }
            }, "MosaicNoteShotPng").start()
        }
    }

    
    @Suppress("DEPRECATION")
    private fun realDisplaySize(): Point {
        val point = Point()
        try {
            (host.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.getRealSize(point)
        } catch (error: Throwable) {
            Log.w(TAG_SHOT, "display size read failed: $error")
        }
        if (point.x <= 0 || point.y <= 0) point.set(host.width, host.height)
        return point
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
