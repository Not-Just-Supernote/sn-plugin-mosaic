package me.laumss.mosaic.board

import me.laumss.mosaic.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
import android.graphics.RectF
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.WritableMap
import java.util.UUID
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
        private const val TAG = LogTags.INTERACTION
        private const val TAG_PEN = LogTags.PEN
        private const val TAG_LASSO = LogTags.LASSO
        private const val TAG_TOOL = "MosaicTool"

        const val PEN_WIDTH_DP = 2f

        // Longest the board stays unpainted at launch while a saved note reopens.
        private const val NOTE_RESTORE_PAINT_HOLD_MS = 2000L
        // Longest the pen waits for JS's scene while the board shows only the scene cache.
        private const val SCENE_CACHE_PEN_HOLD_MS = 5000L

        const val LASSO_ACTION_TAP_SLOP_DP = 12f

        private const val EINK_OWNER = "gesture"
        const val NOTE_SCROLL_SETTLE_MS = 400L

        const val GESTURE_SETTLE_MS = 600L
    }

    internal val handler = Handler(Looper.getMainLooper())

    internal val density: Float get() = host.resources.displayMetrics.density.coerceAtLeast(1f)

    internal val arbiter = ToolArbiter()

    var settings: GestureSettings.Resolved = GestureSettings.Resolved.DEFAULT

    internal var history = BoardHistory()

    internal val whiteboard = WhiteboardSurface(emitter) { host.closeWatchdog.arm("board-close", noteShots.sessionActive) }

    internal var surface: BoardSurface = whiteboard

    internal var surfaceSwitching = false

    internal val viewportTmp = FloatArray(2)

    // The parts of the controller. They hold no reference to each other at construction, so their order does not matter.
    internal val noteShots = NoteShots(this)
    internal val inkling = InklingBridge(this)
    internal val trailCleanup = CardTrailCleanup(this)
    internal val textEditing = CardTextEditing(this)
    internal val selection = SelectionController(this)
    internal val transforms = SelectionTransforms(this)
    internal val notes = NoteSession(this)
    internal val cardGestures = CardStrokeGestures(this)
    internal val pen = PenInput(this)
    internal val panZoom = PanZoomGesture(this)
    internal val touch = TouchInput(this)
    internal val cardCommands = CardCommands(this)

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
        host.drawPath.setSuspended(true, "scene-cache")
        host.postDelayed(releasePenForJsSceneTask, SCENE_CACHE_PEN_HOLD_MS)
    }

    private fun releasePenForJsScene(reason: String, notifyHost: Boolean = true) {
        host.removeCallbacks(releasePenForJsSceneTask)
        if (!penHeldForJsScene) return
        penHeldForJsScene = false
        if (notifyHost) host.drawPath.setSuspended(false, "scene-cache:$reason")
        Log.i(TAG, "pen released after scene cache reason=$reason")
    }

    var touchEnabled = true

    // The tool in force is kept only in the arbiter; these read it.
    internal val eraserMode: Boolean get() = arbiter.effective().eraser
    internal val lassoMode: Boolean get() = arbiter.effective().lasso
    internal val shapeMode: Boolean get() = arbiter.effective().shape

    // The shape the shape tool draws, also while the tool is off.
    internal var shapeKind = Shapes.Kind.RECT

    internal var shapeSelectionPending = false

    internal var penStyle: PenStyle = PenStyle.PEN

    internal var penWidth = PenPopup.WIDTHS[PenPopup.DEFAULT_INDEX]

    internal var markerInk = MarkerInk.BLACK

    fun setPenStyle(style: PenStyle, width: Float) {
        penStyle = style
        penWidth = width.coerceIn(1f, 40f)
        Log.i(TAG_PEN, "style=${style.name} stdWidth=$penWidth")
        host.drawPath.setPenStyle(style, penWidth)
        pen.reevaluateTrailColor("pen-style")
    }

    fun setMarkerInk(ink: MarkerInk) {
        markerInk = ink
        Log.i(TAG_PEN, "marker ink=${ink.name}")
        host.drawPath.setMarkerInk(ink, "marker-ink")
    }

    internal var sliderTwoDown = false

    private var regionsDirty = true
    private var regions: List<SparseNavigation.Region> = emptyList()
    private var chromeUpdatePosted = false

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
        host.drawPath.setPenStyle(penStyle, penWidth)
        host.drawPath.setMarkerInk(markerInk, "attach")
        TranslucentStore.level(host.context).let { level ->
            content.setTranslucentLevel(level)
            chrome.setTranslucentLevel(level)
        }
        arbiter.reset()
        syncToolViews()
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

        MosaicSession.saveViewport(host.context, BoardViewport.panX, BoardViewport.panY, BoardViewport.scale)
        SceneCache.save(host.context, BoardEngine.snapshotScene())
        surfaceSwitching = false
        if (InputRouter.sink === this) InputRouter.sink = null
        chrome.listener = null
        InkAlign.stop()
        touch.cancelCardLongPress()
        cardGestures.cancelFrameHold()
        pen.reset()
        transforms.dropPendingMove()
        touch.fingerMoveSession = null
        BoardEngine.hiddenCardIds = emptySet()
        BoardEngine.hiddenStrokeIds = emptySet()
        touch.fingers.clear()
        touch.fingerGesture = FingerGesture.Idle
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
        host.drawPath.setStylusCalibration(settings.calibX, settings.calibY, settings.leftHand)
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

        if (reason == "viewport" && touch.viewportGestureActive()) return
        scheduleChromeUpdate()
    }

    fun onDocumentReplaced() {
        history.clear()
        selection.setLasso(null)

        content.requestInitialRaster()
        regionsDirty = true
        scheduleChromeUpdate()

        val board = notes.suspendedBoard?.scene
        emitter.begin()
        if (board != null) emitter.viewport(board.panX, board.panY, board.scale)
        else emitter.viewport(BoardViewport.panX, BoardViewport.panY, BoardViewport.scale)
        emitter.commit()
        notes.restoreSessionNote()
    }

    internal fun worldX(px: Float): Float = (px / density - BoardViewport.panX) / BoardViewport.scale

    internal fun worldY(px: Float): Float = (px / density - BoardViewport.panY) / BoardViewport.scale

    internal fun isOnDarkCard(wx: Float, wy: Float): Boolean =
        InkBackdrop.darkAt(wx, wy)

    internal fun screenX(world: Float): Float = (world * BoardViewport.scale + BoardViewport.panX) * density

    internal fun screenY(world: Float): Float = (world * BoardViewport.scale + BoardViewport.panY) * density

    internal fun worldRectToPx(r: RectF, out: RectF): RectF {
        out.set(screenX(r.left), screenY(r.top), screenX(r.right), screenY(r.bottom))
        return out
    }

    internal fun viewWorld(): RectF =
        SparseNavigation.viewportWorldRect(
            BoardViewport.panX, BoardViewport.panY, BoardViewport.scale,
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
        BoardViewport.set(panX, panY, scale)
        surface.onViewportCommitted(panX, panY, scale)

        if (surface.showsBoardChrome) MosaicSession.saveViewport(host.context, panX, panY, scale)
        scheduleChromeUpdate()
    }

    internal fun applyTransition(t: ToolArbiter.Transition?, source: String) {
        if (t == null) return
        Log.i(TAG_TOOL, "$source ${t.reason} ${t.before}->${t.after} keepSelection=${t.keepSelection} ${arbiter.describe()}")
        if (!t.changed) {
            updateManualToolRefresh(t.after, source)
            return
        }

        val previous = pen.penSession
        val discardedWrite = pen.endSessionForToolChange()
        if (!t.after.lasso && !t.after.shape && !t.keepSelection) selection.setLasso(null)
        syncToolViews()
        pen.resumeAfterToolChange(previous, discardedWrite, t.after, source)
        updateManualToolRefresh(t.after, source)
    }

    private fun updateManualToolRefresh(state: ToolArbiter.State, source: String) {
        val manual = (source.startsWith("toolbar-") && source != "toolbar-leave-shape") ||
            source == "slider-toggle" || source == "manual-refresh"
        if (!manual) return
        // Also handle clearBase() with unchanged state: long-press selection can show
        // lasso while the arbiter's base is still the pen.
        presentation.set(BoardPresentation.Reason.LASSO, state.lasso, linger = false)
        Log.i(TAG_TOOL, "manual refresh selection source=$source lassoA2=${state.lasso}")
    }

    /**
     * Shows the tool in force, the selection and open menus on the toolbar, the system ink service and the
     * refresh mode. Tool changes run it through [applyTransition]; anything else that changes these inputs calls it.
     */
    internal fun syncToolViews() {
        val state = arbiter.effective()
        val showLasso = selection.selectBelowPendingRestore || (selection.lasso != null && !state.eraser)
        chrome.setInkTool(state.eraser && !showLasso, state.lasso || showLasso, state.shape && !showLasso)
        chrome.setCurrentShape(if (state.shape) shapeKind else null)
        host.toolLassoActive = state.lasso || state.shape
        val selectionFree = selection.lasso == null && !chrome.blocksPen && !selection.selectBelowArmed
        host.inkEnabled = !state.eraser && !state.lasso && selectionFree
        host.shapeDrag = state.shape
        host.lassoEnabled = (state.lasso || state.shape) && selectionFree
        presentation.set(BoardPresentation.Reason.TOOL, state.eraser)
        // Long-press selection shows lasso without changing the arbiter base. Hold its
        // A2 until an explicit tool choice; selection changes and gesture UP do not release it.
        if (state.lasso || showLasso) presentation.acquire(BoardPresentation.Reason.LASSO)
    }

    internal fun gestureToolOf(tool: GestureSettings.GestureTool): ToolArbiter.Tool? = when (tool) {
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

    internal fun dp(value: Float): Float = value * density

    internal fun cancelActiveInteractions(reason: String) {
        touch.cancelFingerGestures(reason)
        pen.cancel(reason)
    }

    internal fun toolbarHeightWorld(): Float = chrome.toolbarHeightPx() / density

    internal fun snapPanToPx(panDp: Float): Float = Math.round(panDp * density) / density

    override fun onPenState(state: InputRouter.PenState, value: Boolean) {
        when (state) {
            InputRouter.PenState.HOVER -> {
                pen.penHovering = value
                // The pen coming near settles a two-finger hold and ends a pinch.
                if (value || pen.penContact) when (touch.fingerGesture) {
                    is FingerGesture.TwoCandidate -> touch.settleTwoCandidate("hover-enter")
                    is FingerGesture.PanZoom -> touch.yieldPanZoomToPen("hover-enter")
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
                    host.drawPath.reassertPenBlock("pen-button:$tool")
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
        host.inkHandoff.flushInkHandoff("manual-refresh")
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
        host.inkHandoff.flushInkHandoff("slider")
        Log.i(TAG, "[Slider] $gesture side=$side")
        when (gesture) {
            "slideUp" -> redo()
            "slideDown" -> undo()

            "twoDown" -> if (gestureToolOf(settings.slidebarGesture) != null) {
                sliderTwoDown = true
                presentation.acquire(BoardPresentation.Reason.SLIDER)
                host.drawPath.setSuspended(true, "slider-two-down")

                (pen.penSession as? PenSession.Write)?.let { it.convertFromIndex = it.points.size }
            }
            "twoUp" -> {
                sliderTwoDown = false
                host.drawPath.setSuspended(false, "slider-two-up")

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
        chrome.setZoom(BoardViewport.scale)
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
        val jumps = if (touch.fingerGesture is FingerGesture.PanZoom) emptyList()
        else SparseNavigation.findRegionJumps(currentRegions(), view, SparseNavigation.PAN_MARGIN)
        chrome.setRegionJumps(jumps)
        selection.updateLassoOverlay()
    }

    private fun zoomTo(nextScale: Float) {
        touch.cancelFingerGestures("zoom")
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
        val index = BoardGeometry.nearestZoomIndex(BoardViewport.scale)
        val next = (index + direction).coerceIn(0, BoardGeometry.ZOOM_LEVELS.size - 1)
        if (BoardGeometry.ZOOM_LEVELS[next] == BoardViewport.scale) return
        zoomTo(BoardGeometry.ZOOM_LEVELS[next])
    }

    override fun onZoomReset() = zoomTo(BoardGeometry.RESET_ZOOM)

    override fun onToggleTouch() = setTouchEnabled(!touchEnabled, fromJs = false)

    override fun onPenBlockChanged() {
        InklingLink.setChromeMenuOpen(chrome.blocksPen)
        syncToolViews()
        notes.keepNoteTopOnToolbarChange()
    }

    override fun onDeleteLassoSelection() = selection.deleteLassoSelection()

    override fun onClose() {
        if (surfaceSwitching) return
        surface.onCloseRequested(BoardSurface.CloseSource.TOOLBAR)
    }

    override fun onJumpToRegion(region: SparseNavigation.Region) {
        touch.cancelFingerGestures("jump")
        val centered = SparseNavigation.panToCenterRect(region.bounds, host.width / density, host.height / density, BoardViewport.scale)
        commitViewport(centered[0], centered[1], BoardViewport.scale)
    }

    internal fun openSwitcher() {
        val notes = synchronized(BoardEngine.lock) {
            BoardEngine.cardsByZ.filter { it.kind == "note" }
        }

        val links = NoteLinks.list().filter { NoteLinks.regionOf(it.id) != null }
        chrome.showSwitcher(notes, links)
        syncToolViews()
    }

    override fun onSwitcherDismissed() {
        chrome.hideSwitcher()
        syncToolViews()
    }

    fun setSyncState(enabled: Boolean, state: String, address: String) {
        chrome.setSyncState(enabled, state, address)
    }

    override fun onOpenNoteCard(cardId: String) {
        val card = BoardEngine.cards[cardId] ?: return
        chrome.hideSwitcher()
        syncToolViews()
        notes.openNote(card.noteRef)
    }

    override fun onLocateNoteCard(cardId: String) {
        val card = BoardEngine.cards[cardId] ?: return
        touch.cancelFingerGestures("locate-note")
        val w = host.width / density
        val h = host.height / density
        val rect = card.rect()
        val fit = min(w / max(rect.width(), 1f), h / max(rect.height(), 1f))
        val scale = BoardGeometry.clampZoom(min(BoardViewport.scale, fit))
        val centered = SparseNavigation.panToCenterRect(rect, w, h, scale)
        chrome.hideSwitcher()
        syncToolViews()
        commitViewport(centered[0], centered[1], scale)
        Log.i(TAG, "locate note card=${card.id} at (${card.x},${card.y}) scale=$scale")
    }

    // Requests from outside that a part handles.
    fun focusNoteShot(json: String) = noteShots.focus(json)

    fun renderNoteShot(json: String, done: (WritableMap?) -> Unit) = noteShots.render(json, done)

    override fun onLocateNoteLink(shotId: String) = noteShots.locateLink(shotId)

    override fun onDeleteNoteLink(shotId: String) = noteShots.deleteLink(shotId)

    override fun onSelectBelow() = selection.toggleSelectBelow()

    override fun onConvertCardToNote() = notes.convertCardToNote()

    override fun onTouch(frame: InputRouter.TouchFrame) = touch.handle(frame)

    override fun onDeleteSelectedCard() = cardCommands.deleteSelectedCard()

    override fun onApplySizeLevel(level: BoardGeometry.SizeLevel) = cardCommands.applySizeLevel(level)

    override fun onToggleCardAccent() = cardCommands.toggleAccent()
}
