package me.laumss.mosaic

import android.content.Intent
import android.graphics.RectF
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.WritableMap
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class BoardInteractionController(
    internal val host: MosaicBoardView,
    internal val content: BoardContentView,
    internal val ink: TransientInkView,
    internal val overlay: InteractionOverlayView,
    internal val chrome: BoardChromeView,
    internal val emitter: BoardCommandEmitter,
) : InputRouter.Sink, BoardChromeView.Listener {
    companion object {
        const val TAG = "MosaicInteraction"
        const val TAG_PEN = "MosaicPen"
        private const val TAG_FINGER = "MosaicFinger"
        const val TAG_LASSO = "MosaicLasso"
        private const val TAG_TOOL = "MosaicTool"
        private const val TAG_TWO = "MosaicTwoFinger"

        const val PEN_WIDTH_DP = 2f

        // Longest the board stays unpainted at launch while a saved note reopens.
        private const val NOTE_RESTORE_PAINT_HOLD_MS = 2000L
        // Longest the pen waits for JS's scene while the board shows only the scene cache.
        private const val SCENE_CACHE_PEN_HOLD_MS = 5000L

        const val ACCENT_BG_COLOR = "#FFF9E3"
        const val ACCENT_TEXT_COLOR = "#3f3b2e"

        const val TWO_FINGER_CANDIDATE_HOLD_MS = 300L

        const val TWO_FINGER_PAIR_WINDOW_MS = TwoFingerToolGuard.EVENT_TIME_DEV_MS

        const val CARD_FINGER_MOVE_HOLD_MS = 600L

        const val CARD_FINGER_MOVE_HOLD_FAST_MS = 400L

        const val CARD_FINGER_MOVE_HOLD_SELECTION_MS = 100L

        private const val NOTE_HEADER_PENDING = "#note-header"
        private const val NOTE_LINK_PENDING = "#note-link:"

        const val LASSO_ACTION_TAP_SLOP_DP = 12f

        const val TWO_FINGER_TAP_MAX_MS = 300L
        const val TWO_FINGER_DOUBLE_TAP_GAP_MS = 400L
        const val TWO_FINGER_TAP_MOVE_SLOP_DP = 24f
        const val TWO_FINGER_DOUBLE_TAP_DIST_DP = 120f

        private const val EINK_OWNER = "gesture"
        const val NOTE_SCROLL_SETTLE_MS = 400L

        const val GESTURE_SETTLE_MS = 600L

        private const val EDGE_SWIPE_START_DP = 32f

        private const val EDGE_SWIPE_ZONE_DP = 72f

        private const val RECOGNIZE_CARD_MIN_DP = 120f
    }

    internal val handler = Handler(Looper.getMainLooper())

    internal val density: Float get() = host.resources.displayMetrics.density.coerceAtLeast(1f)

    private val edgeSwipeStartPx: Float get() = EDGE_SWIPE_START_DP * density
    private val edgeSwipeZonePx: Float get() = EDGE_SWIPE_ZONE_DP * density

    private class PendingRecognition(val rect: RectF, val strokeIds: List<String>)

    private var pendingRecognition: PendingRecognition? = null

    internal val arbiter = ToolArbiter()

    var settings: GestureSettings.Resolved = GestureSettings.Resolved.DEFAULT
        private set

    internal var history = BoardHistory()

    internal val whiteboard = WhiteboardSurface(emitter) { host.armCloseWatchdog("board-close", noteShots.sessionActive) }

    internal var surface: BoardSurface = whiteboard

    internal var surfaceSwitching = false

    internal val viewportTmp = FloatArray(2)

    // ── Notipal 浮窗拖动 ──
    // 与双指平移一致：白板上走 PAN_ZOOM 同款显示（A2 + 灰卡临时白底 + flat），
    // 笔记卡片里只切 A2 刷新、不改卡片显示；松手后按各自的 settle 恢复。

    // At launch the board draws Kotlin's cached scene until JS has read board.mosaic, then reopens a saved note
    // from it at once. Nothing is loaded when the scene is already in memory.
    private fun loadSceneCache() {
        val empty = synchronized(BoardEngine.lock) { BoardEngine.cards.isEmpty() && BoardEngine.strokes.isEmpty() }
        if (!empty || SceneCache.jsSceneArrived) return
        val generation = SceneCache.jsGeneration
        val context = host.context
        SceneCache.load(context) { bytes ->
            WhiteboardSceneGate.dispatch {
                if (SceneCache.jsGeneration != generation || SceneCache.jsSceneArrived) return@dispatch
                if (InputRouter.sink !== this || surfaceSwitching || notes.suspendedBoard != null) return@dispatch
                val stillEmpty = synchronized(BoardEngine.lock) { BoardEngine.cards.isEmpty() && BoardEngine.strokes.isEmpty() }
                if (!stillEmpty) return@dispatch
                val ops = BoardEngine.applyOpsBytes(bytes)
                Log.i(TAG, "scene cache drawn ops=$ops")
                content.requestInitialRaster()
                regionsDirty = true
                scheduleChromeUpdate()
                if (MosaicSession.savedNoteRef(context) != null) notes.restoreSessionNote() else holdPenForJsScene()
            }
        }
    }

    // Strokes written on the cached board before JS has loaded would be lost when JS's scene replaces it.
    internal var penHeldForJsScene = false
    private val releasePenForJsSceneTask = Runnable { releasePenForJsScene("timeout") }

    internal fun holdPenForJsScene() {
        if (penHeldForJsScene) return
        penHeldForJsScene = true
        host.setDrawPathSuspended(true, "scene-cache")
        host.postDelayed(releasePenForJsSceneTask, SCENE_CACHE_PEN_HOLD_MS)
    }

    private fun releasePenForJsScene(reason: String, notifyHost: Boolean = true) {
        host.removeCallbacks(releasePenForJsSceneTask)
        if (!penHeldForJsScene) return
        penHeldForJsScene = false
        if (notifyHost) host.setDrawPathSuspended(false, "scene-cache:$reason")
        Log.i(TAG, "pen released after scene cache reason=$reason")
    }

    var touchEnabled = true
        private set

    internal var eraserMode = false
    internal var lassoMode = false

    internal var shapeMode = false
    internal var shapeKind = Shapes.Kind.RECT

    internal var shapeSelectionPending = false

    internal var penStyle: PenStyle = PenStyle.PEN

    internal var penWidth = PenPopup.WIDTHS[PenPopup.DEFAULT_INDEX]

    internal var markerInk = MarkerInk.BLACK

    fun setPenStyle(style: PenStyle, width: Float) {
        penStyle = style
        penWidth = width.coerceIn(1f, 40f)
        Log.i(TAG_PEN, "style=${style.name} stdWidth=$penWidth")
        host.setPenStyle(style, penWidth)
        pen.reevaluateTrailColor("pen-style")
    }

    fun setMarkerInk(ink: MarkerInk) {
        markerInk = ink
        Log.i(TAG_PEN, "marker ink=${ink.name}")
        host.setMarkerInk(ink, "marker-ink")
    }

    internal var sliderTwoDown = false

    internal val fingers = LinkedHashMap<Int, Finger>()

    internal var fingerGesture: FingerGesture = FingerGesture.Idle
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

    private var fingerMoveSession: MoveSession? = null

    private var regionsDirty = true
    private var regions: List<SparseNavigation.Region> = emptyList()
    private var chromeUpdatePosted = false
    private var launcherPullStartY = Float.NaN

    internal val presentation by lazy {
        BoardPresentation(handler, content, { surface.showsBoardChrome }, EINK_OWNER, GESTURE_SETTLE_MS).also {
            it.onStateChanged = { if (pen.penHovering && !pen.penContact) pen.reevaluateTrailColor("presentation") }
        }
    }

    private val viewWorldRect = RectF()
    internal var viewOffsetX = 0
    internal var viewOffsetY = 0
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

        notes.applyTemplateForSurface(surface, if (surface.showsBoardChrome) null else notes.noteController.document?.ref)

        inkling.exportLassoStrokes(null)

        if (MosaicSession.savedNoteRef(host.context) != null) {
            content.setHoldPaint(true)
            host.postDelayed(releasePaintHoldTask, NOTE_RESTORE_PAINT_HOLD_MS)
        }
        if (notes.notesDirectory.isBlank()) MosaicSession.savedNotesDirectory(host.context)?.let { notes.notesDirectory = it }
        SceneCache.onJsSceneApplied = { releasePenForJsScene("js-scene") }
        loadSceneCache()
        notes.restoreSessionNote()
        scheduleChromeUpdate()
    }

    private val releasePaintHoldTask = Runnable { releasePaintHold("timeout") }

    internal fun releasePaintHold(reason: String) {
        host.removeCallbacks(releasePaintHoldTask)
        if (content.holdPaint) {
            content.setHoldPaint(false)
            Log.i(TAG, "first paint released reason=$reason surface=${surface.name}")
        }
    }

    fun detach() {
        trailCleanup.cancel("detach")
        SceneCache.onJsSceneApplied = null
        // The view drops its suspend request itself once the system ink is released.
        releasePenForJsScene("detach", notifyHost = false)
        panZoom.resetNoteScrollMode()
        textEditing.close()
        releasePaintHold("detach")
        noteShots.reset()

        inkling.exportLassoStrokes(null)
        notes.leaveOnDetach()

        MosaicSession.saveViewport(host.context, BoardEngine.panX, BoardEngine.panY, BoardEngine.scale)
        SceneCache.save(host.context, BoardEngine.snapshotScene())
        surfaceSwitching = false
        if (InputRouter.sink === this) InputRouter.sink = null
        chrome.listener = null
        InkAlign.stop()
        cancelCardLongPress()
        cardGestures.cancelFrameHold()
        pen.reset()
        fingerMoveSession = null
        BoardEngine.hiddenCardIds = emptySet()
        BoardEngine.hiddenStrokeIds = emptySet()
        fingers.clear()
        fingerGesture = FingerGesture.Idle
        sliderTwoDown = false
        content.setZoomPreview(false)
        content.setGestureFreezeTiles(false)
        content.setGestureThrottle(false)
        overlay.clearAll()
        content.clearSettleWaiters()
        inkling.resetOverlayDrag()
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
        selection.setLasso(null)

        content.requestInitialRaster()
        regionsDirty = true
        scheduleChromeUpdate()

        val board = notes.suspendedBoard?.scene
        emitter.begin()
        if (board != null) emitter.viewport(board.panX, board.panY, board.scale)
        else emitter.viewport(BoardEngine.panX, BoardEngine.panY, BoardEngine.scale)
        emitter.commit()
        notes.restoreSessionNote()
    }

    internal fun worldX(px: Float): Float = (px / density - BoardEngine.panX) / BoardEngine.scale

    internal fun worldY(px: Float): Float = (px / density - BoardEngine.panY) / BoardEngine.scale

    internal fun isOnDarkCard(wx: Float, wy: Float): Boolean =
        InkBackdrop.darkAt(wx, wy)

    internal fun screenX(world: Float): Float = (world * BoardEngine.scale + BoardEngine.panX) * density

    internal fun screenY(world: Float): Float = (world * BoardEngine.scale + BoardEngine.panY) * density

    internal fun worldRectToPx(r: RectF, out: RectF): RectF {
        out.set(screenX(r.left), screenY(r.top), screenX(r.right), screenY(r.bottom))
        return out
    }

    internal fun viewWorld(): RectF =
        SparseNavigation.viewportWorldRect(
            BoardEngine.panX, BoardEngine.panY, BoardEngine.scale,
            host.width / density, host.height / density, viewWorldRect,
        )

    internal fun refreshViewOffset() {
        host.getLocationOnScreen(tmpLoc)
        viewOffsetX = tmpLoc[0]
        viewOffsetY = tmpLoc[1]
    }

    internal fun applyChange(change: BoardHistory.Change, record: Boolean, forward: Boolean = true, reconcileInk: Boolean = forward) {
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
        selection.lasso?.let { selection.setLasso(it) }
        surface.onSceneMutated()
        notes.syncNotePageFrame()
        regionsDirty = true
        scheduleChromeUpdate()

        if (pen.penHovering && !pen.penContact) pen.reevaluateTrailColor(if (inkFixed) "ink-reconciled" else "scene:${change.label}")
    }

    private fun applyDiffs(diffs: List<BoardHistory.Diff>, forward: Boolean, emitCommands: Boolean) {
        val commands = if (emitCommands) ArrayList<() -> Unit>() else null
        val pendingUpserts = ArrayList<BoardEngine.StrokeRec>()
        val pendingRemovals = ArrayList<String>()
        val connectionUpdates = LinkedHashSet<String>()
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
                        source?.connectionId?.let(connectionUpdates::add)
                        target?.connectionId?.let(connectionUpdates::add)
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
                            connectionUpdates.add(target.id)
                            commands?.add { emitter.connectionAdd(target) }
                        }
                    }
                }
            }
            flushUpserts(); flushRemovals()
        }
        commands?.forEach { it() }
        if (emitCommands) {
            for (id in connectionUpdates) {
                BoardEngine.connections[id]?.let { emitter.connectionAdd(it) }
            }
        }
    }

    fun undo() {
        val change = history.popUndo() ?: return
        cancelActiveInteractions("undo")
        applyChange(change, record = false, forward = false)
        Log.i(TAG, "undo ${change.label}")
    }

    fun redo() {
        val change = history.popRedo() ?: return
        cancelActiveInteractions("redo")
        applyChange(change, record = false, forward = true, reconcileInk = false)
        Log.i(TAG, "redo ${change.label}")
    }

    internal fun newId(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12)

    internal fun commitViewport(panX: Float, panY: Float, scale: Float) {
        BoardEngine.setViewport(panX, panY, scale)
        surface.onViewportCommitted(panX, panY, scale)

        if (surface.showsBoardChrome) MosaicSession.saveViewport(host.context, panX, panY, scale)
        scheduleChromeUpdate()
    }

    internal fun applyTransition(t: ToolArbiter.Transition?, source: String) {
        if (t == null) return
        Log.i(TAG_TOOL, "$source ${t.reason} ${t.before}->${t.after} keepSelection=${t.keepSelection} ${arbiter.describe()}")
        if (!t.changed) return

        val previous = pen.penSession
        val discardedWrite = pen.endSessionForToolChange()
        if (!t.after.lasso && !t.after.shape && !t.keepSelection) selection.setLasso(null)
        syncToolMirrors(t.after, source)
        pen.resumeAfterToolChange(previous, discardedWrite, t.after, source)
    }

    internal fun syncToolMirrors(state: ToolArbiter.State, source: String) {
        eraserMode = state.eraser
        lassoMode = state.lasso
        shapeMode = state.shape

        val showLasso = selection.selectBelowPendingRestore || (selection.lasso != null && !state.eraser)
        chrome.setInkTool(state.eraser && !showLasso, state.lasso || showLasso, state.shape && !showLasso)
        chrome.setCurrentShape(if (shapeMode) shapeKind else null)

        host.toolLassoActive = lassoMode || shapeMode

        host.inkEnabled = !eraserMode && !lassoMode && selection.lasso == null && !chrome.blocksPen && !selection.selectBelowArmed

        host.shapeDrag = shapeMode

        host.lassoEnabled = (lassoMode || shapeMode) && selection.lasso == null && !chrome.blocksPen && !selection.selectBelowArmed

        presentation.set(BoardPresentation.Reason.TOOL, eraserMode)
        // 只在套索工具下用 auto；选中后静止时交还宿主刷新，长按拖卡片和拖边改尺寸都只在拖动时切一次。
        presentation.set(BoardPresentation.Reason.LASSO, lassoMode)
    }

    internal fun refreshHostToolFlags() = syncToolMirrors(arbiter.effective(), "flags")

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
                    "controllerHover=${pen.penHovering} contact=${pen.penContact}",
            )
        }
        if (rawHover && !pen.penHovering) onPenState(InputRouter.PenState.HOVER, true)
        when {
            rawRubber -> onPenState(InputRouter.PenState.RUBBER, true)
            rawStylusButton -> onPenState(InputRouter.PenState.STYLUS, true)
        }
    }

    internal fun normalizeColoredCardToDefault(card: BoardEngine.CardRec): BoardHistory.Change? {
        if (!card.colored) return null
        val next = card.withColors("", "")
        val change = BoardHistory.Change("card-normalize")
        change.card(card, next)

        reconcileConnectionsForColor(next, change)
        return change
    }

    internal fun toggleSelectedCardBlack() {
        val card = selection.selectedCardId?.let { BoardEngine.cards[it] } ?: return
        if (card.kind == "image" || card.kind == "note") return
        val isBlack = card.bgColor.equals("#000000", ignoreCase = true)
        val newBg = if (isBlack) "" else "#000000"
        val newText = if (isBlack) "" else "#ffffff"
        val moved = card.withColors(newBg, newText)
        val change = BoardHistory.Change("card-color").card(card, moved)

        val relinked = reconcileConnectionsForColor(moved, change)
        applyChange(change, record = true)
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

    internal fun collectCardRemoval(cardId: String, change: BoardHistory.Change) {
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

    override fun onTouch(frame: InputRouter.TouchFrame) {
        if (frame.action == InputRouter.ACTION_DOWN || frame.action == InputRouter.ACTION_POINTER_DOWN) trailCleanup.cancel("touch-down")
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
                tapEpisodeMoved = pen.penContact || pen.penHovering || frame.penPriority
                tapDownX.clear(); tapDownY.clear()
                for (i in 0 until frame.count) { tapDownX[frame.ids[i]] = frame.xs[i]; tapDownY[frame.ids[i]] = frame.ys[i] }
            }
            InputRouter.ACTION_POINTER_DOWN -> {
                if (!tapEpisodeActive) return
                tapEpisodeMaxFingers = maxOf(tapEpisodeMaxFingers, frame.count)
                if (pen.penContact || pen.penHovering || frame.penPriority) tapEpisodeMoved = true
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

        if (inkling.toolbarHit(x, y)) {
            Log.i(TAG_FINGER, "DOWN id=$id ignored: inkling-toolbar")
            return
        }

        if (arbiter.hasOverride(ToolArbiter.Source.SLIDEBAR)) return

        if (pen.penContact) {
            Log.i(TAG_FINGER, "DOWN id=$id blocked: pen-session")
            return
        }
        val finger = Finger(id, x, y, now, penPriority)
        fingers[id] = finger
        Log.i(TAG_FINGER, "DOWN id=$id px=($x,$y) count=${fingers.size} penPriority=$penPriority gesture=${fingerGesture::class.simpleName}")

        if (fingers.size >= 2) {
            val (a, b) = firstTwo()

            cancelCardLongPress()
            fingerMoveSession?.let { transforms.cancelMoveSession(it); fingerMoveSession = null }
            if (fingerGesture is FingerGesture.SurfaceScroll) {
                content.setGestureThrottle(false)
                commitViewport(BoardEngine.panX, BoardEngine.panY, BoardEngine.scale)
            }

            if (fingerGesture is FingerGesture.PanZoom || fingerGesture is FingerGesture.ScreenTool) return
            if (fingers.size > 2) { fingerGesture = FingerGesture.Idle; return }
            if (!touchEnabled && gestureToolOf(settings.screenGesture) == null) { fingerGesture = FingerGesture.Idle; return }
            if (abs(a.downAt - b.downAt) > TWO_FINGER_PAIR_WINDOW_MS || gestureToolOf(settings.screenGesture) == null) {
                panZoom.begin(a, b)
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

        if (selection.lasso == null && !selection.selectBelowArmed && surface.showsBoardChrome) {
            if (!chrome.switcherOpen && x <= edgeSwipeStartPx) {
                fingerGesture = FingerGesture.EdgeSwipe(id, x, y)
                Log.i(TAG_FINGER, "edge-swipe(open) armed id=$id x=$x")
                return
            }
        }

        if (notes.noteHeaderHit(x, y) && selection.lasso == null && !selection.selectBelowArmed) {
            fingerGesture = FingerGesture.CardPending(id, NOTE_HEADER_PENDING)
            cancelCardLongPress()
            val task = Runnable {
                cardLongPressTask = null
                val pending = fingerGesture as? FingerGesture.CardPending ?: return@Runnable
                if (pending.fingerId != id || pending.cardId != NOTE_HEADER_PENDING) return@Runnable
                fingerGesture = FingerGesture.Idle
                textEditing.editPageHeader()
            }
            cardLongPressTask = task
            handler.postDelayed(task, CARD_FINGER_MOVE_HOLD_MS)
            return
        }
        if (!touchEnabled) { fingerGesture = FingerGesture.Idle; return }
        val wx = worldX(x)
        val wy = worldY(y)

        if (surface.singleFingerScrolls && selection.lasso == null) {
            fingerGesture = FingerGesture.SurfaceScroll(id, y, BoardEngine.panY)
            content.setGestureThrottle(true)
            return
        }

        val currentLasso = selection.lasso

        val hadSelection = currentLasso != null
        if (currentLasso != null) {
            val single = if (selection.isSingleCardSelection(currentLasso)) BoardEngine.cards[currentLasso.cardIds[0]] else null
            if (single != null) {
                val handle = selection.singleCardFrameHandle(currentLasso, wx, wy)
                if (handle != null) {
                    fingerMoveSession = transforms.beginResize(false, id, wx, wy, single, handle)
                    fingerGesture = FingerGesture.Move(id)
                    return
                }
            }
            if (overlay.rotateHandleHit(x, y, LASSO_ACTION_TAP_SLOP_DP * density) && selection.selectionRotatable(currentLasso)) {
                fingerMoveSession = transforms.beginSelectionRotate(false, id, wx, wy, currentLasso)
                fingerGesture = FingerGesture.Move(id)
                return
            }

            val cornerHandle = selection.selectionCornerHandle(currentLasso, wx, wy)
                ?: (if (selection.shapeKindOf(currentLasso) != null) selection.shapeEdgeHandle(currentLasso, wx, wy) else null)
            if (cornerHandle != null) {
                fingerMoveSession = transforms.beginSelectionResize(false, id, wx, wy, currentLasso, cornerHandle)
                fingerGesture = FingerGesture.Move(id)
                return
            }
            val action = overlay.lassoActionHit(x, y, LASSO_ACTION_TAP_SLOP_DP * density)
            if (action != null) {
                selection.handleLassoAction(action)
                fingerGesture = FingerGesture.Idle
                return
            }
            if (selection.selectionHit(currentLasso, wx, wy) || overlay.moveHandleHit(x, y, LASSO_ACTION_TAP_SLOP_DP * density)) {
                fingerMoveSession = transforms.beginSelectionMove(false, id, wx, wy, currentLasso)
                fingerGesture = FingerGesture.Move(id)
                return
            }
            selection.setLasso(null)
        }

        val linkId = noteShots.linkAt(wx, wy)
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

            selection.selectConnectedComponent(current)
            val sel = selection.lasso ?: return@Runnable
            fingerMoveSession = transforms.beginSelectionMove(false, id, worldX(f.x), worldY(f.y), sel)
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
                transforms.updateMoveSession(m, worldX(f.x), worldY(f.y))
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
            is FingerGesture.PanZoom -> panZoom.update(g)
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
                        if (cancelled) transforms.cancelMoveSession(m) else transforms.endMoveSession(m, worldX(finger.x), worldY(finger.y))
                    }
                }
            }
            is FingerGesture.CardPending -> {
                if (g.fingerId == id) {
                    cancelCardLongPress()
                    fingerGesture = FingerGesture.Idle
                    val card = BoardEngine.cards[g.cardId]
                    val singleFingerTap = !cancelled && tapEpisodeMaxFingers == 1 && fingers.isEmpty() &&
                        hypot(finger.x - finger.downX, finger.y - finger.downY) < BoardGeometry.CARD_DRAG_THRESHOLD_PX * density
                    if (singleFingerTap && g.cardId.startsWith(NOTE_LINK_PENDING)) {
                        noteShots.openLink(g.cardId.removePrefix(NOTE_LINK_PENDING))
                    } else if (singleFingerTap && card?.kind == "note" && card.noteRef.isNotEmpty()) {
                        notes.openNote(card.noteRef)
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
                    panZoom.end(g, cleanOldPositions = !cancelled)

                    if (fingers.size >= 2) {
                        val (a, b) = firstTwo()
                        panZoom.begin(a, b)
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
        if (fingers.isEmpty()) {
            fingerGesture = FingerGesture.Idle
            trailCleanup.resume("touch-up")
        }
    }

    internal fun dp(value: Float): Float = value * density

    internal fun cardsBelow(anchor: BoardEngine.CardRec): List<BoardEngine.CardRec> {
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

    internal fun pushCardsBelow(change: BoardHistory.Change, below: List<BoardEngine.CardRec>, grow: Float) {
        val dy = max(0f, grow)
        for (b in below) {
            val current = BoardEngine.cards[b.id] ?: continue
            val target = b.y + dy
            if (current.y != target) change.card(current, current.moved(current.x, target))
        }
    }

    private fun firstTwo(): Pair<Finger, Finger> {
        val it = fingers.values.iterator()
        return Pair(it.next(), it.next())
    }

    internal fun cancelFingerGestures(reason: String) {
        cancelCardLongPress()
        handler.removeCallbacks(twoCandidateTimeout)
        fingerMoveSession?.let { transforms.cancelMoveSession(it) }
        fingerMoveSession = null
        (fingerGesture as? FingerGesture.PanZoom)?.let { panZoom.end(it, cleanOldPositions = false) }
        if (fingerGesture === FingerGesture.ScreenTool) {
            applyTransition(arbiter.releaseOverride(ToolArbiter.Source.SCREEN), "screen-cancel")
            presentation.release(BoardPresentation.Reason.SCREEN_TOOL, linger = false)
        }
        fingerGesture = FingerGesture.Idle
        fingers.clear()
        Log.i(TAG_FINGER, "gestures cancelled reason=$reason")
    }

    internal fun cancelActiveInteractions(reason: String) {
        cancelFingerGestures(reason)
        pen.cancel(reason)
    }

    private val twoCandidateTimeout = Runnable { settleTwoCandidate("hold") }

    private fun fallbackFromTwoCandidate(a: Finger, b: Finger, reason: String) {
        if (pen.penContact || pen.penHovering || a.penPriority || b.penPriority) {
            fingerGesture = FingerGesture.Idle
            Log.i(TAG_TWO, "candidate $reason → blocked by pen proximity")
            return
        }
        if (touchEnabled) panZoom.begin(a, b) else fingerGesture = FingerGesture.Idle
    }

    private fun adoptScreenTool(a: Finger, b: Finger, reason: String): Boolean {
        val tool = gestureToolOf(settings.screenGesture) ?: return false
        val decision = TwoFingerToolGuard.evaluate(
            a.x + viewOffsetX, a.y + viewOffsetY, b.x + viewOffsetX, b.y + viewOffsetY,
            if (pen.penHovering) pen.penHoverX + viewOffsetX else null,
            if (pen.penHovering) pen.penHoverY + viewOffsetY else null,
            if (pen.penHovering) InputReader.penTiltDirection else null,
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

    internal fun settleTwoCandidate(reason: String): Boolean {
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
        panZoom.end(g, cleanOldPositions = false)
        fingerGesture = FingerGesture.Idle
        if (a == null || b == null) return
        if (!adoptScreenTool(a, b, reason)) {
            Log.i(PanZoomGesture.TAG_PAN, "panzoom yielded to pen: no screen tool reason=$reason")
        }
    }

    internal fun toolbarHeightWorld(): Float = chrome.toolbarHeightPx() / density

    internal fun snapPanToPx(panDp: Float): Float = Math.round(panDp * density) / density

    override fun onPenState(state: InputRouter.PenState, value: Boolean) {
        when (state) {
            InputRouter.PenState.HOVER -> {
                pen.penHovering = value
                if (!value && !pen.penContact)
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

                    val near = pen.penContact ||
                        (if (physical == ToolArbiter.Physical.STYLUS) InputReader.isPenStylusPressNear() else pen.penHovering)
                    val bound = if (physical == ToolArbiter.Physical.RUBBER) ToolArbiter.Tool.ERASER else gestureToolOf(settings.penButton)
                    val tool = if (near) bound else ToolArbiter.Tool.ERASER
                    if (tool == null) return
                    val armed = !near
                    presentation.acquire(BoardPresentation.Reason.PEN_BUTTON)
                    applyTransition(
                        arbiter.pushOverride(ToolArbiter.Source.PEN_BUTTON, tool, ToolArbiter.Settle.ON_NEXT_PEN_DOWN, armed = armed, physical = physical),
                        "pen-button-down",
                    )
                    Log.i(TAG_TOOL, "pen-button down physical=$physical tool=$tool near=$near hover=${pen.penHovering} contact=${pen.penContact} armed=$armed")
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
        trailCleanup.cancel("manual-refresh")
        if (surfaceSwitching) return
        host.flushInkHandoff("manual-refresh")
        Log.i(TAG, "manual full refresh: exit lasso/eraser/gesture states ${arbiter.describe()} lasso=${selection.lasso != null}")
        cancelActiveInteractions("manual-refresh")
        panZoom.resetNoteScrollMode()
        for (source in ToolArbiter.Source.values()) applyTransition(arbiter.forceExit(source), "manual-refresh")
        applyTransition(arbiter.clearBase(), "manual-refresh")
        selection.setLasso(null)
        presentation.reset("manual-refresh")

        host.redrawAfterHostRefresh()
    }

    override fun onSlider(gesture: String, side: Int) {
        if (side != settings.sliderSide) return
        trailCleanup.cancel("slider")
        host.flushInkHandoff("slider")
        Log.i(TAG, "[Slider] $gesture side=$side")
        when (gesture) {
            "slideUp" -> redo()
            "slideDown" -> undo()

            "twoDown" -> if (gestureToolOf(settings.slidebarGesture) != null) {
                sliderTwoDown = true
                presentation.acquire(BoardPresentation.Reason.SLIDER)
                host.setDrawPathSuspended(true, "slider-two-down")

                (pen.penSession as? PenSession.Write)?.let { it.convertFromIndex = it.points.size }
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
        trailCleanup.resume("slider-finished")
    }

    internal fun currentRegions(): List<SparseNavigation.Region> {
        if (!regionsDirty) return regions
        val items = ArrayList<SparseNavigation.Item>()
        synchronized(BoardEngine.lock) {
            for (c in BoardEngine.cards.values) items.add(SparseNavigation.Item(c.id, c.x, c.y, c.width, c.height))
        }
        regions = SparseNavigation.detectRegions(items)
        regionsDirty = false
        return regions
    }

    internal fun scheduleChromeUpdate() {
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
        val selected = selection.selectedCardId?.let { BoardEngine.cards[it] }
        val levels = if (selected == null) emptyList() else synchronized(BoardEngine.lock) {
            BoardGeometry.computeSizeLevels(selected, BoardEngine.cards, BoardEngine.connections.values)
        }
        chrome.setMode(
            selectedCard = selected != null,
            selectedCardColored = selected?.colored == true,
            selectedCardKind = selected?.kind,
            sizeLevels = levels,

            lassoCards = selected == null && selection.lasso?.cardIds?.isNotEmpty() == true,
        )
        val jumps = if (fingerGesture is FingerGesture.PanZoom) emptyList()
        else SparseNavigation.findRegionJumps(currentRegions(), view, SparseNavigation.PAN_MARGIN)
        chrome.setRegionJumps(jumps)
        selection.updateLassoOverlay()
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

    override fun onPenStyle(style: PenStyle, width: Float) {
        if (selection.lasso != null) leaveShapeSelectionForToolbar()
        setPenStyle(style, width)
    }

    override fun onMarkerInk(ink: MarkerInk) {
        if (selection.lasso != null) leaveShapeSelectionForToolbar()
        setMarkerInk(ink)
    }

    private fun leaveShapeSelectionForToolbar() {
        if (arbiter.hasOverride(ToolArbiter.Source.SHAPE)) {
            shapeSelectionPending = false
            applyTransition(arbiter.forceExit(ToolArbiter.Source.SHAPE), "toolbar-leave-shape")
        }
        if (selection.lasso != null) selection.setLasso(null)
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
            if (selection.lasso != null) selection.setLasso(null)
        } else if (!shapeMode) {
            if (selection.lasso != null) selection.setLasso(null)

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

    override fun onPenBlockChanged() {
        InklingLink.setChromeMenuOpen(chrome.blocksPen)
        refreshHostToolFlags()
        notes.keepNoteTopOnToolbarChange()
    }

    override fun onDeleteSelectedCard() {
        val id = selection.selectedCardId ?: return
        val change = BoardHistory.Change("card-delete")
        synchronized(BoardEngine.lock) { collectCardRemoval(id, change) }
        selection.setLasso(null)
        applyChange(change, record = true)
    }

    override fun onApplySizeLevel(level: BoardGeometry.SizeLevel) {
        val id = selection.selectedCardId ?: return
        val card = BoardEngine.cards[id] ?: return
        val rect = RectF(level.rect)
        CardSizeConstraints.constrainRect(card, rect, BoardGeometry.Handle.BOTTOM_RIGHT,
            proportional = card.kind == "image")
        applyChange(BoardHistory.Change("size-level").card(card, card.withRect(rect)), record = true)
    }

    override fun onToggleCardAccent() {
        val id = selection.selectedCardId ?: return
        val card = BoardEngine.cards[id] ?: return
        if (card.kind == "image" || card.kind == "note") return

        val next = if (card.colored) card.withColors("", "")
        else card.withColors(ACCENT_BG_COLOR, ACCENT_TEXT_COLOR)
        val change = BoardHistory.Change("card-accent").card(card, next)
        val relinked = reconcileConnectionsForColor(next, change)
        applyChange(change, record = true)
        Log.i(TAG, "toggle card accent id=${card.id} colored=${next.colored} connections=$relinked")
    }

    override fun onDeleteLassoSelection() = selection.deleteLassoSelection()

    internal fun onRecognizeLasso() {
        val sel = selection.lasso ?: return
        if (!selection.isPureInkSelection(sel)) {
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
            selection.setLasso(null)
            textEditing.editPageHeader(recognized = trimmed)
            Log.i(TAG_LASSO, "recognize note header strokes=${pending.strokeIds.size} chars=${trimmed.length}")
            return
        }
        val ink = synchronized(BoardEngine.lock) {
            pending.strokeIds.mapNotNull { id -> BoardEngine.strokes[id]?.let { BoardEngine.strokeWorldBounds(it) } }
        }
        val fontScale = handwritingFontScale(ink, trimmed)
        val m = CardTextFormat.WEB.scaled(fontScale)
        // The text sits where the handwriting was: the card's padding goes around the ink.
        val r = ink.fold(null as RectF?) { acc, b -> acc?.apply { union(b) } ?: RectF(b) } ?: RectF(pending.rect)
        val left = r.left - m.padX
        val top = r.top - m.padTop
        val probe = BoardEngine.CardRec("probe", left, top, 1f, 1f, 0, "text", trimmed, fontScale = fontScale)
        val size = CardSizeConstraints.fit(probe,
            (r.width() + m.padX * 2f).coerceAtLeast(RECOGNIZE_CARD_MIN_DP),
            (r.height() + m.padTop + m.padBottom).coerceAtLeast(RECOGNIZE_CARD_MIN_DP))
        val created = BoardEngine.CardRec(
            newId("card-"), left, top, size.x, size.y,
            BoardGeometry.nextZIndex(BoardEngine.cards.values), "text", trimmed, fontScale = fontScale,
        )
        val change = BoardHistory.Change("recognize-card").card(null, created)
        synchronized(BoardEngine.lock) {
            for (id in pending.strokeIds) BoardEngine.strokes[id]?.let { change.stroke(it, null) }
        }
        selection.setLasso(null)
        applyChange(change, record = true)
        scheduleChromeUpdate()
        Log.i(TAG_LASSO, "recognize card=${created.id} replaced strokes=${pending.strokeIds.size} chars=${trimmed.length} fontScale=$fontScale")
    }

    // Text scale that makes recognized text about as large as the handwriting. Strokes are grouped into rows; the
    // rows' total length against the same text set on one line at the base size gives the scale. The typical row
    // height only caps it, since rows of handwriting that touch merge into one tall row.
    private fun handwritingFontScale(ink: List<RectF>, text: String): Float {
        val rows = ArrayList<RectF>()
        for (b in ink.sortedBy { it.top }) {
            val row = rows.lastOrNull()
            if (row != null && b.centerY() in row.top..row.bottom) row.union(b) else rows.add(RectF(b))
        }
        val heights = rows.map { it.height() }.filter { it > 0f }.sorted()
        if (heights.isEmpty()) return 1f
        val m = CardTextFormat.WEB
        val byHeight = heights[heights.size / 2] / m.body
        val oneLine = text.replace(Regex("\\s*\\n\\s*"), " ")
        val typeset = BoardContentView.measureTextCardSize(oneLine, 1_000_000f, m, limitHeight = false).x - m.padX * 2f
        val inkLength = rows.fold(0f) { sum, row -> sum + row.width() }
        val scale = if (typeset > 1f) min(inkLength / typeset, byHeight) else byHeight
        // Round to tenths so nearby recognitions share a size.
        return BoardEngine.clampFontScale(Math.round(scale * 10f) / 10f)
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
        notes.openNote(card.noteRef)
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

    internal val noteShots = NoteShots(this)

    fun focusNoteShot(json: String) = noteShots.focus(json)

    fun renderNoteShot(json: String, done: (WritableMap?) -> Unit) = noteShots.render(json, done)

    override fun onLocateNoteLink(shotId: String) = noteShots.locateLink(shotId)

    override fun onDeleteNoteLink(shotId: String) = noteShots.deleteLink(shotId)

    internal val inkling = InklingBridge(this)

    internal val trailCleanup = CardTrailCleanup(this)

    internal val textEditing = CardTextEditing(this)

    internal val selection = SelectionController(this)

    override fun onSelectBelow() = selection.toggleSelectBelow()

    internal val transforms = SelectionTransforms(this)

    internal val notes = NoteSession(this)

    override fun onConvertCardToNote() = notes.convertCardToNote()

    internal val cardGestures = CardStrokeGestures(this)

    internal val pen = PenInput(this)

    internal val panZoom = PanZoomGesture(this)
}
