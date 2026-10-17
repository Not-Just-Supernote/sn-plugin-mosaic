package me.laumss.mosaic

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.modules.core.DeviceEventManagerModule

@SuppressLint("ViewConstructor")
class MosaicBoardView(
    internal val reactContext: ReactContext,
) : FrameLayout(reactContext) {
    companion object {
        const val TAG = "MosaicBoardView"
        private const val NATIVE_BUILD_TAG = "pressure-clock-20260928a"

        const val BOARD_VISIBILITY_EVENT = "MosaicBoardVisibility"

        private const val HOVER_REARM_MIN_INTERVAL_MS = 1000L

        private const val BACKGROUND_SYNC_PROMPT_DELAY_MS = 120L

        private const val PEN_MODE_RESET_MAX_MS = 1500L
        private const val INK_HANDOFF_IDLE_MS = 3000L
        private const val INK_HANDOFF_RETRY_MS = 500L
        private const val INK_CONTACT_STALE_MS = 1500L
        private const val BACKGROUND_SYNC_IDLE_DELAY_MS = 2000L
        // 卡片手势后，背景同步等整屏刷新做完再过这么久；刷新迟迟不来时最多等到 MAX。
        private const val SYNC_AFTER_FULL_REFRESH_MS = 1200L
        private const val SYNC_HOLD_FOR_REFRESH_MAX_MS = 10_000L
        // 卡片手势抬笔后多久整屏刷新，清掉系统笔迹。
        private const val TRAIL_CLEAR_REFRESH_DELAY_MS = 600L

        private const val DEVICE_TYPE_A6X2 = 4
    }

    var deviceType: Int = -1

    var notesDirectory: String = ""
        set(value) { field = value; controller.notes.setNotesDirectory(value) }

    var touchEnabled: Boolean
        get() = controller.touchEnabled
        set(value) = controller.setTouchEnabled(value, fromJs = true)

    private var boardTranslucent = false

    fun setBoardTranslucent(translucent: Boolean) {
        if (boardTranslucent == translucent) return
        boardTranslucent = translucent
        contentView.alpha = 1f
        setBackgroundColor(if (translucent) Color.TRANSPARENT else Color.WHITE)
        contentView.setTranslucentOverlay(translucent)
        Log.i(TAG, "board translucent=$translucent overlay")
    }

    var inkEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (stylusContact) applyPenRefreshMode()
            if (!value) {
                inkAbort()
                transientStrokeActive = false
                releaseInkDefer()
                if (drawPath.configured) drawPath.disableDrawPath("ink-disabled")
            } else if (drawPath.configured && !lassoEnabled && !toolLassoActive && !drawPath.drawPathSuspended) {
                drawPath.enableDrawPath("ink-enabled")
            } else if (stylusContact && lassoEnabled && !shapeDrag) {
                transientStrokeActive = true
                inkBegin(lastStylusX, lastStylusY)
                Log.i(TAG, "transient ink restarted on ink enable lasso=$lassoEnabled")
            }
        }

    var shapeDrag: Boolean = false

    var toolLassoActive: Boolean = false

    var lassoEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (stylusContact) applyPenRefreshMode()
            inkAbort()
            transientStrokeActive = false
            if (value) {
                releaseInkDefer()
                if (drawPath.configured) drawPath.disableDrawPath("lasso-enabled")
            } else if (drawPath.configured && inkEnabled && !toolLassoActive && !drawPath.drawPathSuspended) {
                drawPath.enableDrawPath("lasso-disabled")
            }
            if (value && stylusContact && !shapeDrag) {
                transientStrokeActive = true
                inkBegin(lastStylusX, lastStylusY)
                Log.i(TAG, "transient ink restarted on lasso switch enabled=$value")
            }
        }

    private val contentView = BoardContentView(reactContext)

    private val grayRestore = EinkGrayRestore(contentView, "board", eligible = {
        InklingLink.isBoardSurface() && !stylusContact &&
            !contentView.isMovingCards && !contentView.isDeferringRefresh && !contentView.holdPaint
    })

    private val inkView = TransientInkView(reactContext)
    private val overlayView = InteractionOverlayView(reactContext)
    internal val chromeView = BoardChromeView(reactContext)

    private val commandEmitter = BoardCommandEmitter(reactContext).also { emitter ->
        emitter.viewMetricsDp = {
            val d = resources.displayMetrics.density
            floatArrayOf(width / d, height / d, chromeView.toolbarHeightPx() / d)
        }
    }

    val controller = BoardInteractionController(this, contentView, inkView, overlayView, chromeView, commandEmitter)
    internal val closeWatchdog = CloseWatchdog(this)
    internal val inklingCallbacks = InklingCallbacks(this)
    internal val drawPath = DrawPathSession(this)
    private var sceneChangeCounter = 0
    internal var stylusContact = false

    private var penModeApplied = false
    private var lastStylusX = 0f
    private var lastStylusY = 0f

    private var transientStrokeActive = false

    internal var awaitingStrokeCommit = false
    private var fullRefreshPending = false
    private var inkHandoffPending = false
    private var plainStrokeCommit = false
    private var plainStrokeCommitted = false
    private var lastPenEventAt = 0L

    private val inkHandoffTask: Runnable = Runnable {
        if (!inkHandoffPending) return@Runnable
        val sincePen = SystemClock.uptimeMillis() - lastPenEventAt
        if (stylusContact && sincePen < INK_CONTACT_STALE_MS) {
            Log.i(TAG, "ink handoff idle: pen in contact, retry sincePen=$sincePen")
            mainHandler.postDelayed(inkHandoffTask, INK_HANDOFF_RETRY_MS)
            return@Runnable
        }
        if (stylusContact) {
            Log.w(TAG, "ink handoff idle: stale pen contact cleared sincePen=$sincePen")
            stylusContact = false
            InputArbiter.onPenContact(false)
        }
        Log.i(TAG, "ink handoff idle: finalize")
        finalizeInkSession("idle")
    }

    private var trailDiscardedDuringContact = false
    private var strokeSceneChangedDuringContact = false

    internal var hoverRearmDeferred = false

    private var holdPenRefreshUntilSettle = false

    private val penModeResetFallback = Runnable {
        if (!stylusContact && penModeApplied) {
            holdPenRefreshUntilSettle = false
            penModeApplied = false
            MosaicEinkRefreshModule.resetNative("pen")
            Log.i(TAG, "pen refresh mode reset by fallback")
        }
    }

    internal val mainHandler = Handler(Looper.getMainLooper())
    @Volatile internal var attached = false

    private enum class SyncUrgency { NONE, IDLE, PROMPT }

    private var backgroundSyncUrgency = SyncUrgency.NONE
    private var backgroundSyncTask: Runnable? = null

    private var discardSyncPending = false
    // 在这之前不同步背景，见 onPenTrailDiscarded。
    private var syncHoldUntil = 0L

    init {
        addView(
            contentView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            inkView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        addView(
            overlayView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            chromeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        contentView.onContentChanged = { reason, moved ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                sceneChangeCounter++
                controller.onSceneOrViewportChanged(reason)
                noteContentChanged(reason, moved)
            } else {
                mainHandler.post {
                    sceneChangeCounter++
                    controller.onSceneOrViewportChanged(reason)
                    noteContentChanged(reason, moved)
                }
            }
        }
        contentView.onContentSettled = { onContentSettled() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.i(TAG, "onAttachedToWindow nativeBuild=$NATIVE_BUILD_TAG")
        attached = true

        InputReader.ensureBoardReader(context)
        InklingLink.attach(context, inklingCallbacks)
        publishBoardVisibility(true)
        mainHandler.post {
            if (!attached) return@post
            PortraitLock.activate(this)
        }

        MosaicSession.savedViewport(context)?.let {
            BoardEngine.setViewport(it.panX, it.panY, it.scale)
            Log.i(TAG, "viewport restored pan=(${it.panX},${it.panY}) scale=${it.scale}")
        }
        controller.attach()

        contentView.requestInitialRaster()
        mainHandler.postDelayed({
            if (attached) contentView.requestInitialRaster()
        }, 250L)
        drawPath.attach()
    }

    override fun onDetachedFromWindow() {
        attached = false
        InputReader.releaseBoardReader()
        publishBoardVisibility(false)
        InklingLink.detach(inklingCallbacks)
        controller.detach()
        drawPath.stopWatching()
        mainHandler.removeCallbacksAndMessages(null)
        inkView.release()
        drawPath.release()
        stylusContact = false
        awaitingStrokeCommit = false
        fullRefreshPending = false
        syncHoldUntil = 0L
        clearInkHandoff()
        plainStrokeCommit = false
        strokeSceneChangedDuringContact = false
        hoverRearmDeferred = false
        InputArbiter.onPenContact(false)
        if (penModeApplied) {
            penModeApplied = false
            MosaicEinkRefreshModule.resetNative("pen")
        }
        PortraitLock.release()
        Log.i(TAG, "onDetachedFromWindow: drawPath released")
        super.onDetachedFromWindow()
    }

    fun reloadNoteLinks() {
        val file = NoteLinks.storeFile(reactContext) ?: return
        Thread({
            val dirty = NoteLinks.reload(file)
            if (dirty.isNotEmpty()) contentView.markWorldDirty(dirty)
            mainHandler.post { chromeView.refreshNoteLinks() }
        }, "MosaicNoteLinks").start()
    }

    fun redrawAfterHostRefresh() {
        if (!attached) return
        contentView.forceRedrawAfterHostRefresh()
        if (InklingLink.isBoardSurface()) grayRestore.request("manual-key")
        else {
            mainHandler.postDelayed({ if (attached) contentView.forceRedrawAfterHostRefresh() }, 350L)
            mainHandler.postDelayed({ if (attached) contentView.postInvalidateOnAnimation() }, 1000L)
        }
    }

    internal fun cancelGrayRestore(reason: String) = grayRestore.cancel(reason)

    private fun publishBoardVisibility(visible: Boolean) {
        if (visible) reloadNoteLinks()
        InputReader.setPluginViewVisible(visible)
        MosaicNoteShotModule.updateBoardVisibility(visible)
        InklingLink.setBoardVisible(visible, "board-view")
        val data = Arguments.createMap().apply {
            putBoolean("visible", visible)
            putString("surface", InklingLink.currentSurface())
            InklingLink.currentNoteRef()?.let { putString("noteRef", it) } ?: putNull("noteRef")
        }
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(BOARD_VISIBILITY_EVENT, data)
        Log.i(TAG, "board visibility=$visible")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!attached || (w == oldw && h == oldh)) return
        controller.trailCleanup.cancel("size-change")
        Log.i(TAG, "onSizeChanged ${oldw}x$oldh -> ${w}x$h, reconfigure drawPath")
        contentView.requestInitialRaster()
        drawPath.requestRotationReconfigure("sizeChanged")
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        if (!attached) return
        mainHandler.postDelayed({
            drawPath.requestRotationReconfigure("viewConfigurationChanged")
        }, 100L)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        Log.i(TAG, "window focus changed: hasFocus=$hasWindowFocus rotation=${display?.rotation}")
        if (attached && !hasWindowFocus) {
            controller.trailCleanup.cancel("focus-lost")
            drawPath.restoreDrawPathPressureCurve()
        }
        if (!attached || !hasWindowFocus) return
        controller.trailCleanup.resume("focus-gained")
        mainHandler.postDelayed({
            if (attached && hasWindowFocus) {
                drawPath.requestRotationReconfigure("windowFocusGained")
            }
        }, 200L)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!attached) return
        if (visibility != VISIBLE) controller.trailCleanup.cancel("hidden")
        else controller.trailCleanup.resume("visible")
        if (visibility != VISIBLE) drawPath.restoreDrawPathPressureCurve()
        else if (hasWindowFocus()) drawPath.scheduleConfigure("window-visible")
    }

    fun onPropsChanged() {
        if (attached) drawPath.scheduleConfigure("propsChanged")
    }

    private fun noteContentChanged(reason: String, moved: Boolean) {
        val next = if (moved) SyncUrgency.PROMPT else SyncUrgency.IDLE
        if (next.ordinal > backgroundSyncUrgency.ordinal) backgroundSyncUrgency = next

        cancelBackgroundSync()

        if (stylusContact) strokeSceneChangedDuringContact = true
        when {
            plainStrokeCommit -> if (awaitingStrokeCommit || inkHandoffPending) holdInkHandoff()
            awaitingStrokeCommit -> finalizeInkSession("commit")
            inkHandoffPending && !stylusContact -> {
                Log.i(TAG, "ink handoff flushed by content change reason=$reason moved=$moved")
                finalizeInkSession("flush")
            }
        }
    }

    private fun onContentSettled() {
        controller.trailCleanup.resume("content-settled")
        if (holdPenRefreshUntilSettle && !stylusContact) {
            holdPenRefreshUntilSettle = false
            if (penModeApplied) {
                penModeApplied = false
                MosaicEinkRefreshModule.resetNative("pen")
            }
        }
        if (hoverRearmDeferred && !stylusContact && !awaitingStrokeCommit) {
            hoverRearmDeferred = false
            drawPath.lastHoverRearmAt = System.currentTimeMillis()
            drawPath.rearmDrawPath("hoverEnter:settled")
        }

        if (backgroundSyncUrgency == SyncUrgency.NONE || (stylusContact && drawPath.drawPathActive)) return
        if (discardSyncPending) {
            cancelBackgroundSync()
            sendBackgroundSync()
            return
        }
        val delay = if (backgroundSyncUrgency == SyncUrgency.PROMPT) {
            BACKGROUND_SYNC_PROMPT_DELAY_MS
        } else {
            BACKGROUND_SYNC_IDLE_DELAY_MS
        }
        cancelBackgroundSync()
        val task = Runnable {
            backgroundSyncTask = null
            sendBackgroundSync()
        }
        backgroundSyncTask = task
        mainHandler.postDelayed(task, delay)
    }

    private fun cancelBackgroundSync() {
        backgroundSyncTask?.let { mainHandler.removeCallbacks(it) }
        backgroundSyncTask = null
    }

    private fun scheduleBackgroundSync(delay: Long) {
        cancelBackgroundSync()
        val task = Runnable {
            backgroundSyncTask = null
            sendBackgroundSync()
        }
        backgroundSyncTask = task
        mainHandler.postDelayed(task, delay)
    }

    private fun sendBackgroundSync(force: Boolean = false) {
        if (!attached || !drawPath.configured || (!force && stylusContact && drawPath.drawPathActive)) return
        val held = syncHoldUntil - SystemClock.uptimeMillis()
        if (held > 0) {
            if (backgroundSyncUrgency == SyncUrgency.NONE) backgroundSyncUrgency = SyncUrgency.PROMPT
            scheduleBackgroundSync(held)
            return
        }
        val binder = drawPath.drawPathBinder ?: return
        val urgency = backgroundSyncUrgency
        val discard = discardSyncPending
        try {
            DrawPathClient.syncBackground(
                binder = binder,
                appName = drawPath.drawPathAppName,
                isA6X2 = deviceType == DEVICE_TYPE_A6X2,
            )
            backgroundSyncUrgency = SyncUrgency.NONE
            discardSyncPending = false
            Log.i(TAG, "drawPath background sync: urgency=$urgency discard=$discard")
        } catch (error: Throwable) {
            Log.w(TAG, "drawPath background sync failed: urgency=$urgency", error)
            drawPath.drawPathBinder = null
        }
    }

    fun onPenTrailDiscarded(reason: String) {
        if (stylusContact) trailDiscardedDuringContact = true
        if (backgroundSyncUrgency.ordinal < SyncUrgency.PROMPT.ordinal) backgroundSyncUrgency = SyncUrgency.PROMPT
        discardSyncPending = true

        if (stylusContact) {
            cancelBackgroundSync()
            sendBackgroundSync(force = true)
        }

        contentView.markSettleRequested()
        if (!contentView.isDeferringRefresh) contentView.postInvalidateOnAnimation()
        val fullRefresh = reason.startsWith("card-")
        if (fullRefresh) {
            // 书法笔的轨迹抬笔后系统服务还会再画，整屏刷新太早（150ms 内）会清不掉；设备实测 0.6 秒后刷新每次都清掉。
            // 背景同步等刷新做完再发，由 runPendingFullRefresh 安排。
            syncHoldUntil = SystemClock.uptimeMillis() + SYNC_HOLD_FOR_REFRESH_MAX_MS
            mainHandler.postDelayed({ if (attached) requestFullRefreshWhenSettled(reason) }, TRAIL_CLEAR_REFRESH_DELAY_MS)
        }
        Log.i(TAG, "pen trail discarded: reason=$reason sync=${if (stylusContact) "now" else "deferred"} fullRefresh=$fullRefresh stylus=$stylusContact active=${drawPath.drawPathActive}")
    }

    private fun requestFullRefreshWhenSettled(reason: String) {
        fullRefreshPending = true
        contentView.runWhenSettled(Runnable { runPendingFullRefresh(reason) })
    }

    private fun runPendingFullRefresh(reason: String) {
        if (!fullRefreshPending || !attached) return
        if (stylusContact || awaitingStrokeCommit || inkHandoffPending) {
            Log.i(TAG, "full refresh waits for ink reason=$reason contact=$stylusContact awaiting=$awaitingStrokeCommit handoff=$inkHandoffPending")
            return
        }
        fullRefreshPending = false
        MosaicEinkRefreshModule.requestFullRefresh(contentView, reason)
        syncHoldUntil = SystemClock.uptimeMillis() + SYNC_AFTER_FULL_REFRESH_MS
        if (backgroundSyncUrgency != SyncUrgency.NONE) scheduleBackgroundSync(SYNC_AFTER_FULL_REFRESH_MS)
    }

    private fun inkBegin(x: Float, y: Float) = inkView.beginStroke(x, y)

    private fun inkAppend(x: Float, y: Float) =
        inkView.appendStroke(x, y)

    private fun inkEnd() = inkView.endStroke()

    private fun inkAbort() {
        inkView.abortStroke()
    }

    private fun beginStrokeCommitWait() {
        awaitingStrokeCommit = true
    }

    private fun cancelStrokeCommitWait(): Boolean {
        val was = awaitingStrokeCommit
        awaitingStrokeCommit = false
        return was
    }

    private fun holdInkHandoff() {
        if (fullRefreshPending) {
            finalizeInkSession("refresh-pending")
            return
        }
        awaitingStrokeCommit = false
        inkHandoffPending = true
        mainHandler.removeCallbacks(inkHandoffTask)
        mainHandler.postDelayed(inkHandoffTask, INK_HANDOFF_IDLE_MS)
        Log.i(TAG, "ink handoff hold: ${INK_HANDOFF_IDLE_MS}ms")
    }

    private fun clearInkHandoff(): Boolean {
        mainHandler.removeCallbacks(inkHandoffTask)
        val was = inkHandoffPending
        inkHandoffPending = false
        return was
    }

    fun <T> commitPlainStroke(block: () -> T): T {
        plainStrokeCommit = true
        try {
            return block()
        } finally {
            plainStrokeCommit = false
            plainStrokeCommitted = true
            if (awaitingStrokeCommit || inkHandoffPending) holdInkHandoff()
        }
    }

    fun flushInkHandoff(reason: String) {
        if (inkHandoffPending && !stylusContact) finalizeInkSession(reason)
    }

    private fun finalizeInkSession(reason: String) {
        cancelStrokeCommitWait()
        clearInkHandoff()
        contentView.markSettleRequested()
        contentView.refreshAfterRaster()
        inkView.clearImmediately()
        if (fullRefreshPending) contentView.runWhenSettled(Runnable { runPendingFullRefresh("after-ink:$reason") })
        if (reason == "no-commit") {
            contentView.runWhenSettled {
                if (attached && !stylusContact) sendBackgroundSync(force = true)
            }
        }
        Log.i(TAG, "ink session finalize: reason=$reason")
    }

    private fun releaseInkDefer() {
        val waiting = cancelStrokeCommitWait() or clearInkHandoff()
        if (waiting || contentView.isDeferringRefresh) {
            contentView.markSettleRequested()
            contentView.refreshAfterRaster()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            controller.trailCleanup.cancel("view-input")
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = if (event.pointerCount > 0) event.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
        val isPenTool = toolType == MotionEvent.TOOL_TYPE_STYLUS
            || toolType == MotionEvent.TOOL_TYPE_ERASER

        if (!isPenTool) {
            Log.i(TAG, "[MosaicTwoFinger] framework touch consumed action=${event.actionMasked} pointers=${event.pointerCount}")
            return true
        }
        lastPenEventAt = SystemClock.uptimeMillis()

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            stylusContact = true
            InputArbiter.onPenContact(true)
            cancelBackgroundSync()
            lastStylusX = event.x
            lastStylusY = event.y

            controller.preflightPenButtonState()
            controller.pen.onPen(event)

            feedInkView(event)

            if (toolLassoActive && !inkEnabled) drawPath.reassertPenBlock("lasso-down")
        } else {
            val counterBefore = sceneChangeCounter
            feedInkView(event)
            controller.pen.onPen(event)
            if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                && awaitingStrokeCommit && sceneChangeCounter == counterBefore && !plainStrokeCommitted
            ) {
                finalizeInkSession("no-commit")
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !drawPath.configured) {
            Log.i(TAG, "stylus DOWN before drawPath configured, immediate configure attempt")
            drawPath.scheduleConfigure("stylusDown")
        }

        return true
    }

    private fun applyPenRefreshMode() {
        val mode = when {
            !inkEnabled && !lassoEnabled -> MosaicEinkRefreshModule.MODE_DEFAULT
            lassoEnabled -> MosaicEinkRefreshModule.MODE_DEFAULT
            else -> null
        }
        if (mode == null) {
            if (penModeApplied) {
                penModeApplied = false
                MosaicEinkRefreshModule.resetNative("pen")
            }
            return
        }
        penModeApplied = true
        MosaicEinkRefreshModule.applyNative(mode, "pen")
    }

    private fun feedInkView(event: MotionEvent) {
        val writing = inkEnabled && !lassoEnabled
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                applyPenRefreshMode()
                if (writing) {
                    cancelStrokeCommitWait()
                    mainHandler.removeCallbacks(inkHandoffTask)
                    plainStrokeCommitted = false
                    strokeSceneChangedDuringContact = false
                    contentView.setDeferRefresh(true)
                } else {
                    releaseInkDefer()
                }

                transientStrokeActive = lassoEnabled && !shapeDrag
                if (transientStrokeActive) {
                    inkBegin(event.x, event.y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (transientStrokeActive) {
                    for (i in 0 until event.historySize) {
                        inkAppend(event.getHistoricalX(i), event.getHistoricalY(i))
                    }
                    inkAppend(event.x, event.y)
                }
                lastStylusX = event.x
                lastStylusY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastStylusX = event.x
                lastStylusY = event.y
                stylusContact = false
                InputArbiter.onPenContact(false)
                if (penModeApplied) {
                    holdPenRefreshUntilSettle = true
                    if (!writing) {
                        contentView.markSettleRequested()
                        contentView.postInvalidateOnAnimation()
                    }
                    mainHandler.removeCallbacks(penModeResetFallback)
                    mainHandler.postDelayed(penModeResetFallback, PEN_MODE_RESET_MAX_MS)
                }
                if (transientStrokeActive) inkEnd()
                transientStrokeActive = false
                if (writing) {
                    if (trailDiscardedDuringContact) {
                        trailDiscardedDuringContact = false
                        finalizeInkSession("discard")
                    } else if (strokeSceneChangedDuringContact) finalizeInkSession("commit-before-up")
                    else beginStrokeCommitWait()
                } else if (backgroundSyncUrgency != SyncUrgency.NONE) {
                    contentView.markSettleRequested()
                    contentView.postInvalidateOnAnimation()
                }
            }
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val toolType = if (event.pointerCount > 0) event.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS
            || toolType == MotionEvent.TOOL_TYPE_ERASER
        ) {
            controller.pen.onPenHover(event)

            val now = System.currentTimeMillis()
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    if (awaitingStrokeCommit) {
                        hoverRearmDeferred = true
                    } else {
                        drawPath.lastHoverRearmAt = now
                        drawPath.rearmDrawPath("hoverEnter")
                    }
                }
                MotionEvent.ACTION_HOVER_MOVE -> {
                    if (now - drawPath.lastHoverRearmAt > HOVER_REARM_MIN_INTERVAL_MS || drawPath.penConfigDirty) {
                        if (awaitingStrokeCommit) {
                            hoverRearmDeferred = true
                        } else {
                            drawPath.lastHoverRearmAt = now
                            drawPath.rearmDrawPath("hoverHold")
                        }
                    }
                }
            }
        }
        return false
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed && attached && drawPath.configured) drawPath.rearmDrawPath("layout")
    }

    private var layoutPassPosted = false

    private val manualLayoutPass = Runnable {
        layoutPassPosted = false
        val w = width
        val h = height
        if (!attached || w == 0 || h == 0) return@Runnable
        measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
        layout(left, top, right, bottom)
    }

    override fun requestLayout() {
        super.requestLayout()
        if (!layoutPassPosted && width > 0 && height > 0) {
            layoutPassPosted = true
            post(manualLayoutPass)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }
}
