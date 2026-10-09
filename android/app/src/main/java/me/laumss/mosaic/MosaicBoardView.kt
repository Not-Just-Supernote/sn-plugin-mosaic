package me.laumss.mosaic

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.ratta.supernote.pluginlib.api.HostDataCacheAPI
import java.util.concurrent.atomic.AtomicLong


@SuppressLint("ViewConstructor")
class MosaicBoardView(
    private val reactContext: ReactContext,
) : FrameLayout(reactContext), InklingLink.Host {

    companion object {
        
        private val PEN_BLOCK_REASSERT_MS = longArrayOf(0L, 60L, 200L, 500L)
        private const val TAG = "MosaicBoardView"
        private const val NATIVE_BUILD_TAG = "pressure-clock-20260928a"

        private const val RETRY_INTERVAL_MS = 500L
        private const val SLOW_RETRY_INTERVAL_MS = 2000L
        private const val FAST_MAX_ATTEMPTS = 20
        private const val BOARD_VISIBILITY_EVENT = "MosaicBoardVisibility"
        private const val INBOX_READY_EVENT = "MosaicInboxReady"
        
        private val REARM_DELAYS_MS = longArrayOf(400L, 900L, 1700L)
        
        private const val HOVER_REARM_MIN_INTERVAL_MS = 1000L
        private const val WRITE_INFO_REFRESH_MIN_INTERVAL_MS = 1000L
        
        private const val BACKGROUND_SYNC_PROMPT_DELAY_MS = 120L
        
        private const val PEN_MODE_RESET_MAX_MS = 1500L
        private const val INK_HANDOFF_IDLE_MS = 3000L
        private const val BACKGROUND_SYNC_IDLE_DELAY_MS = 2000L
        
        private const val CLOSE_WATCHDOG_MS = 3000L
        
        private const val CLOSE_WATCHDOG_NOTE_SHOT_MS = 8000L
        
        private const val HOST_PLUGIN_MANAGER_MODULE = "NativePluginManager"
        
        private const val DEVICE_TYPE_A6X2 = 4
    }

    
    private var penWidth: Int = (PenPopup.WIDTHS[PenPopup.DEFAULT_INDEX] * 100f).toInt()
    
    private var penStyle: PenStyle = PenStyle.PEN
    
    @Volatile private var penConfigDirty = true

    fun setPenStyle(style: PenStyle, width: Float) {
        penStyle = style
        penWidth = (width * 100f).toInt().coerceIn(50, 4000)
        penConfigDirty = true
        Log.i(TAG, "pen style requested type=${style.objType} stdWidth=$width sent=${drawPathPenWidth()} configured=$configured suspended=$drawPathSuspended")
        if (configured && !drawPathSuspended) rearmDrawPath("pen-style")
    }

    
    var deviceType: Int = -1
    var notesDirectory: String = ""
        set(value) { field = value; controller.setNotesDirectory(value) }

    
    private var stylusCalibX = 0
    private var stylusCalibY = 0
    private var stylusLeftHand = false

    fun setStylusCalibration(diffX: Int, diffY: Int, leftHand: Boolean) {
        val changed = diffX != stylusCalibX || diffY != stylusCalibY || leftHand != stylusLeftHand
        stylusCalibX = diffX
        stylusCalibY = diffY
        stylusLeftHand = leftHand
        if (changed && configured) rearmDrawPath("calibration")
    }

    
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
                if (configured) disableDrawPath("ink-disabled")
            } else if (configured && !lassoEnabled && !toolLassoActive && !drawPathSuspended) {
                enableDrawPath("ink-enabled")
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
                if (configured) disableDrawPath("lasso-enabled")
            } else if (configured && inkEnabled && !toolLassoActive && !drawPathSuspended) {
                enableDrawPath("lasso-disabled")
            }
            if (value && stylusContact && !shapeDrag) {
                transientStrokeActive = true
                inkBegin(lastStylusX, lastStylusY)
                Log.i(TAG, "transient ink restarted on lasso switch enabled=$value")
            }
        }

    private val contentView = BoardContentView(reactContext)
    private val inkView = TransientInkView(reactContext)
    private val overlayView = InteractionOverlayView(reactContext)
    private val chromeView = BoardChromeView(reactContext)
    private val commandEmitter = BoardCommandEmitter(reactContext).also { emitter ->
        emitter.viewMetricsDp = {
            val d = resources.displayMetrics.density
            floatArrayOf(width / d, height / d, chromeView.toolbarHeightPx() / d)
        }
    }
    val controller = BoardInteractionController(this, contentView, inkView, overlayView, chromeView, commandEmitter)
    
    private var sceneChangeCounter = 0
    private var stylusContact = false
    
    private var penModeApplied = false
    private var lastStylusX = 0f
    private var lastStylusY = 0f
    
    private var transientStrokeActive = false
    
    private var awaitingStrokeCommit = false
    private var inkHandoffPending = false
    private var plainStrokeCommit = false
    private val inkHandoffTask = Runnable {
        if (inkHandoffPending && !stylusContact) finalizeInkSession("idle")
    }
    private var trailDiscardedDuringContact = false
    private var strokeSceneChangedDuringContact = false
    
    private var hoverRearmDeferred = false
    
    private var holdPenRefreshUntilSettle = false
    
    private val penModeResetFallback = Runnable {
        if (!stylusContact && penModeApplied) {
            holdPenRefreshUntilSettle = false
            penModeApplied = false
            MosaicEinkRefreshModule.resetNative("pen")
            Log.i(TAG, "pen refresh mode reset by fallback")
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var configureGeneration = 0
    private var rotationReconfigureToken = 0
    @Volatile private var attached = false
    private var configured = false
    private var drawPathActive = false
        set(value) {
            field = value
            DrawPathGate.inkExpected = value
        }
    
    private var drawPathGateHeld = false
    private var drawPathBinder: IBinder? = null
    
    private enum class SyncUrgency { NONE, IDLE, PROMPT }
    private var backgroundSyncUrgency = SyncUrgency.NONE
    private var backgroundSyncTask: Runnable? = null
    
    private var discardSyncPending = false
    
    
    private var suspendRequested = false
    
    private var regionBlocked = false
    
    private val drawPathSuspended: Boolean get() = suspendRequested || regionBlocked

    
    private data class WriteContext(
        val notePath: String,
        val sdkPage: Int,
        val hostPage: Int,
        val layerId: Int,
    )

    @Volatile private var writeContext: WriteContext? = null
    private val writeInfoRequestId = AtomicLong(0L)
    private var lastWriteInfoSent: WriteContext? = null

    
    private val drawPathAppName: String = DrawPathClient.MOSAIC_APP_NAME
    
    private var trailWhite = false
    
    private var markerInk = MarkerInk.BLACK
    
    private val drawPathPenColor: Int
        get() = when {
            penStyle == PenStyle.MARKER -> markerInk.drawPathColor
            trailWhite -> DrawPathClient.PEN_COLOR_WHITE
            else -> DrawPathClient.PEN_COLOR_BLACK
        }
    private var lastWriteInfoRefreshAt = 0L
    private var lastObservedRotation = -1
    private val displayManager by lazy {
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (!attached || displayId != display?.displayId) return
            
            handler.post {
                if (!attached) return@post
                Log.i(TAG, "display changed: rotation=${display?.rotation}")
                requestRotationReconfigure("displayChanged")
            }
        }
    }
    
    private val writeContextWatch = object : Runnable {
        override fun run() {
            if (!attached) return
            refreshWriteInfo("contextWatch")
            handler.postDelayed(this, 1500L)
        }
    }
    private val orientationWatch = object : Runnable {
        override fun run() {
            if (!attached) return
            val rotation = display?.rotation ?: 0
            if (rotation != lastObservedRotation) {
                Log.i(TAG, "orientation watcher: $lastObservedRotation -> $rotation")
                requestRotationReconfigure("orientationWatcher")
            }
            handler.postDelayed(this, 250L)
        }
    }
    private val configurationCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (!attached) return
            
            
            handler.postDelayed({
                requestRotationReconfigure("applicationConfigurationChanged")
            }, 100L)
        }

        override fun onLowMemory() = Unit
    }

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
                noteContentChanged(moved)
            } else {
                handler.post {
                    sceneChangeCounter++
                    controller.onSceneOrViewportChanged(reason)
                    noteContentChanged(moved)
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
        InklingLink.attach(context, this)
        publishBoardVisibility(true)
        handler.post {
            if (!attached) return@post
            PortraitLock.activate(this)
        }
        
        
        MosaicSession.savedViewport(context)?.let {
            BoardEngine.setViewport(it.panX, it.panY, it.scale)
            Log.i(TAG, "viewport restored pan=(${it.panX},${it.panY}) scale=${it.scale}")
        }
        controller.attach()
        
        
        contentView.requestInitialRaster()
        handler.postDelayed({
            if (attached) contentView.requestInitialRaster()
        }, 250L)
        context.applicationContext.registerComponentCallbacks(configurationCallback)
        displayManager.registerDisplayListener(displayListener, handler)
        publishHostRotation()
        lastObservedRotation = display?.rotation ?: 0
        handler.post(orientationWatch)
        handler.postDelayed(writeContextWatch, 1500L)
        refreshWriteInfo("attach", force = true)
        scheduleConfigure("attach")
    }

    override fun onDetachedFromWindow() {
        attached = false
        InputReader.releaseBoardReader()
        publishBoardVisibility(false)
        InklingLink.detach(this)
        controller.detach()
        context.applicationContext.unregisterComponentCallbacks(configurationCallback)
        displayManager.unregisterDisplayListener(displayListener)
        configureGeneration++
        writeInfoRequestId.incrementAndGet()
        writeContext = null
        lastWriteInfoSent = null
        handler.removeCallbacksAndMessages(null)
        inkView.release()
        DrawPathGate.detach()
        val releaseBinder = drawPathBinder ?: DrawPathClient.getBinder()
        releaseBinder?.let { binder ->
            try {
                val settings = GestureSettings.readCached() ?: GestureSettings.read(context)
                synchronized(DrawPathGate.writeLock) {
                    DrawPathClient.restoreHostPenButtonStyle(binder, drawPathAppName, settings.raw["lamy_button"] == "1")
                    DrawPathClient.release(binder, drawPathAppName)
                }
            } catch (e: Throwable) { Log.w(TAG, "drawPath release on detach failed", e) }
        }
        drawPathActive = false
        configured = false
        drawPathBinder = null
        stylusContact = false
        awaitingStrokeCommit = false
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

    
    
    private fun reloadNoteLinks() {
        val file = NoteLinks.storeFile(reactContext) ?: return
        Thread({
            val dirty = NoteLinks.reload(file)
            if (dirty.isNotEmpty()) contentView.markWorldDirty(dirty)
        }, "MosaicNoteLinks").start()
    }

    fun redrawAfterHostRefresh() {
        if (!attached) return
        contentView.forceRedrawAfterHostRefresh()
        handler.postDelayed({
            if (attached) contentView.forceRedrawAfterHostRefresh()
        }, 350L)
        handler.postDelayed({
            if (attached) contentView.postInvalidateOnAnimation()
        }, 1000L)
    }

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
        Log.i(TAG, "onSizeChanged ${oldw}x$oldh -> ${w}x$h, reconfigure drawPath")
        contentView.requestInitialRaster()
        requestRotationReconfigure("sizeChanged")
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        if (!attached) return
        handler.postDelayed({
            requestRotationReconfigure("viewConfigurationChanged")
        }, 100L)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        Log.i(TAG, "window focus changed: hasFocus=$hasWindowFocus rotation=${display?.rotation}")
        if (!attached || !hasWindowFocus) return
        handler.postDelayed({
            if (attached && hasWindowFocus) {
                requestRotationReconfigure("windowFocusGained")
            }
        }, 200L)
    }

    private fun publishHostRotation() {
        val currentDisplay = display
        val realSize = Point()
        currentDisplay?.getRealSize(realSize)
        val screenWidth = realSize.x.takeIf { it > 0 } ?: width
        val screenHeight = realSize.y.takeIf { it > 0 } ?: height
        lastObservedRotation = currentDisplay?.rotation ?: 0
        InputReader.updateHostDisplay(lastObservedRotation, screenWidth, screenHeight)
    }

    fun onPropsChanged() {
        if (attached) scheduleConfigure("propsChanged")
    }

    

    
    private fun drawPathPenType(): Int = when (penStyle) {
        PenStyle.FILLED_SHAPE -> DrawPathClient.PEN_TYPE_PRESSURE
        PenStyle.BRUSH -> DrawPathClient.PEN_TYPE_NEEDLE
        else -> penStyle.objType
    }

    
    private fun drawPathPenWidth(): Int =
        DrawPathClient.liveWidthArgumentFromStdHundredths(penWidth, penStyle)

    
    private fun drawPathDisableAreas(): List<DrawPathClient.DisableArea> {
        val areas = mutableListOf<DrawPathClient.DisableArea>()
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val toolbarHeight = chromeView.toolbarHeightPx()
        if (toolbarHeight > 0 && width > 0) {
            areas.add(DrawPathClient.DisableArea(loc[0], loc[1], width, toolbarHeight))
        }
        
        
        InklingLink.toolbarDisableArea()?.let(areas::add)
        
        
        areas.addAll(InklingLink.overlayDisableAreas())
        return areas
    }

    override fun onInklingToolbarRectChanged() {
        if (!attached) return
        handler.post {
            if (!attached) return@post
            rearmDrawPath("inkling-toolbar-rect")
        }
    }

    override fun onInklingCloseRequested() {
        if (!attached) return
        handler.post {
            if (!attached) return@post
            controller.closeForInkling()
        }
    }

    override fun onInklingPasteStrokesRequested() {
        if (!attached) return
        handler.post {
            if (!attached) return@post
            if (!InklingLink.isBoardSurface()) {
                Log.i(TAG, "inkling paste deferred surface=${InklingLink.currentSurface()}")
                return@post
            }
            controller.handlePasteStrokes()
        }
    }

    override fun onInklingClearSelectionRequested(delete: Boolean) {
        if (!attached) return
        handler.post {
            if (!attached) return@post
            if (!InklingLink.isBoardSurface()) {
                Log.i(TAG, "inkling clear-selection deferred surface=${InklingLink.currentSurface()} delete=$delete")
                return@post
            }
            controller.clearLassoForInkling(delete)
        }
    }

    override fun onInklingTextCardRequested(text: String, anchorScreenX: Int, anchorScreenY: Int) {
        if (!attached) return
        handler.post {
            if (!attached) return@post
            if (!InklingLink.isBoardSurface()) {
                Log.i(TAG, "inkling text-card deferred surface=${InklingLink.currentSurface()}")
                return@post
            }
            controller.insertTextCardFromInkling(text, anchorScreenX, anchorScreenY)
        }
    }

    override fun onInklingInboxReady() {
        if (!attached) return
        handler.post {
            if (!attached) return@post
            if (!InklingLink.isBoardSurface()) {
                Log.i(TAG, "inkling inbox notification deferred surface=${InklingLink.currentSurface()}")
                return@post
            }
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(INBOX_READY_EVENT, null)
        }
    }

    override fun onInklingSurfaceChanged(surface: String, noteRef: String?) {
        if (!attached) return
        handler.post {
            if (!attached) return@post
            
            
            val data = Arguments.createMap().apply {
                putBoolean("visible", true)
                putString("surface", surface)
                noteRef?.let { putString("noteRef", it) } ?: putNull("noteRef")
            }
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(BOARD_VISIBILITY_EVENT, data)
            Log.i(TAG, "surface event surface=$surface noteRef=$noteRef")
        }
    }

    

    
    private val closeWatchdog = Runnable {
        
        Log.i(TAG, "[CloseWatchdog] fired attached=$attached sinceArmMs=${SystemClock.uptimeMillis() - closeWatchdogArmedUptime}")
        if (!attached) return@Runnable
        Log.w(TAG, "[CloseWatchdog] JS did not close within ${closeWatchdogDelayMs}ms; forcing host closePluginView")
        forceHostClosePluginView("watchdog")
    }
    private var closeWatchdogArmedUptime = 0L
    private var closeWatchdogArmedWall = 0L
    private var closeWatchdogDelayMs = CLOSE_WATCHDOG_MS

    
    private val closeHeartbeat = object : Runnable {
        override fun run() {
            val up = SystemClock.uptimeMillis() - closeWatchdogArmedUptime
            val wall = System.currentTimeMillis() - closeWatchdogArmedWall
            Log.i(TAG, "[CloseWatchdog] tick uptimeMs=$up wallMs=$wall drift=${wall - up} attached=$attached")
            if (attached) handler.postDelayed(this, 1000L)
        }
    }

    
    fun armCloseWatchdog(reason: String, noteShotSession: Boolean = false) {
        if (!attached) return
        closeWatchdogArmedUptime = SystemClock.uptimeMillis()
        closeWatchdogArmedWall = System.currentTimeMillis()
        closeWatchdogDelayMs = if (noteShotSession) CLOSE_WATCHDOG_NOTE_SHOT_MS else CLOSE_WATCHDOG_MS
        handler.removeCallbacks(closeWatchdog)
        handler.removeCallbacks(closeHeartbeat)
        handler.postDelayed(closeWatchdog, closeWatchdogDelayMs)
        handler.postDelayed(closeHeartbeat, 1000L)
        Log.i(TAG, "[CloseWatchdog] armed reason=$reason delay=${closeWatchdogDelayMs}ms")
    }

    
    private fun forceHostClosePluginView(reason: String) {
        try {
            val module = reactContext.catalystInstance?.getNativeModule(HOST_PLUGIN_MANAGER_MODULE)
            if (module == null) {
                Log.w(TAG, "[CloseWatchdog] $HOST_PLUGIN_MANAGER_MODULE unavailable reason=$reason")
                return
            }
            val methods = module.javaClass.methods.filter { it.name == "closePluginView" }
            methods.firstOrNull { it.parameterTypes.isEmpty() }?.let {
                it.invoke(module)
                Log.i(TAG, "[CloseWatchdog] closePluginView() forced reason=$reason")
                return
            }
            methods.firstOrNull { it.parameterTypes.size == 1 }?.let {
                it.invoke(module, com.facebook.react.bridge.PromiseImpl(null, null))
                Log.i(TAG, "[CloseWatchdog] closePluginView(promise) forced reason=$reason")
                return
            }
            Log.w(TAG, "[CloseWatchdog] no closePluginView method on host module reason=$reason")
        } catch (error: Throwable) {
            Log.w(TAG, "[CloseWatchdog] force close failed reason=$reason", error)
        }
    }

    
    private fun noteContentChanged(moved: Boolean) {
        val next = if (moved) SyncUrgency.PROMPT else SyncUrgency.IDLE
        if (next.ordinal > backgroundSyncUrgency.ordinal) backgroundSyncUrgency = next
        
        cancelBackgroundSync()
        
        if (stylusContact) strokeSceneChangedDuringContact = true
        when {
            plainStrokeCommit -> if (awaitingStrokeCommit || inkHandoffPending) holdInkHandoff()
            awaitingStrokeCommit -> finalizeInkSession("commit")
            inkHandoffPending && !stylusContact -> finalizeInkSession("flush")
        }
    }

    private fun onContentSettled() {
        if (holdPenRefreshUntilSettle && !stylusContact) {
            holdPenRefreshUntilSettle = false
            if (penModeApplied) {
                penModeApplied = false
                MosaicEinkRefreshModule.resetNative("pen")
            }
        }
        if (hoverRearmDeferred && !stylusContact && !awaitingStrokeCommit) {
            hoverRearmDeferred = false
            lastHoverRearmAt = System.currentTimeMillis()
            rearmDrawPath("hoverEnter:settled")
        }
        
        
        if (backgroundSyncUrgency == SyncUrgency.NONE || (stylusContact && drawPathActive)) return
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
        handler.postDelayed(task, delay)
    }

    private fun cancelBackgroundSync() {
        backgroundSyncTask?.let { handler.removeCallbacks(it) }
        backgroundSyncTask = null
    }

    private fun sendBackgroundSync(force: Boolean = false) {
        if (!attached || !configured || (!force && stylusContact && drawPathActive)) return
        val binder = drawPathBinder ?: return
        val urgency = backgroundSyncUrgency
        val discard = discardSyncPending
        try {
            DrawPathClient.syncBackground(
                binder = binder,
                appName = drawPathAppName,
                isA6X2 = deviceType == DEVICE_TYPE_A6X2,
            )
            backgroundSyncUrgency = SyncUrgency.NONE
            discardSyncPending = false
            Log.i(TAG, "drawPath background sync: urgency=$urgency discard=$discard")
        } catch (error: Throwable) {
            Log.w(TAG, "drawPath background sync failed: urgency=$urgency", error)
            drawPathBinder = null
        }
    }

    
    fun onPenTrailDiscarded(reason: String) {
        if (stylusContact) trailDiscardedDuringContact = true
        if (backgroundSyncUrgency.ordinal < SyncUrgency.PROMPT.ordinal) backgroundSyncUrgency = SyncUrgency.PROMPT
        discardSyncPending = true
        
        
        
        val immediate = stylusContact || reason.startsWith("card-")
        if (immediate) {
            cancelBackgroundSync()
            sendBackgroundSync(force = true)
            backgroundSyncUrgency = SyncUrgency.PROMPT
        }
        
        
        
        contentView.markSettleRequested()
        if (!contentView.isDeferringRefresh) contentView.postInvalidateOnAnimation()
        Log.i(TAG, "pen trail discarded: reason=$reason sync=${if (immediate) "now" else "deferred"} stylus=$stylusContact active=$drawPathActive")
    }

    
    fun setTrailWhite(white: Boolean, reason: String) {
        val previous = drawPathPenColor
        trailWhite = white
        sendPenColorIfChanged(previous, reason)
    }

    
    fun setMarkerInk(ink: MarkerInk, reason: String) {
        val previous = drawPathPenColor
        markerInk = ink
        sendPenColorIfChanged(previous, reason)
    }

    private fun sendPenColorIfChanged(previous: Int, reason: String) {
        val color = drawPathPenColor
        if (color == previous) return
        penConfigDirty = true
        if (!configured || !drawPathActive) {
            Log.i(TAG, "[MosaicTrail] color=$color stored reason=$reason configured=$configured active=$drawPathActive")
            return
        }
        if (stylusContact || awaitingStrokeCommit) {
            hoverRearmDeferred = true
            Log.i(TAG, "[MosaicTrail] color=$color deferred reason=$reason contact=$stylusContact awaitingCommit=$awaitingStrokeCommit")
            return
        }
        val binder = drawPathBinder ?: return
        try {
            synchronized(DrawPathGate.writeLock) {
                DrawPathClient.sendPenInfo(binder, drawPathAppName, drawPathPenType(), drawPathPenWidth(), color)
                DrawPathClient.sendPenButtonOverride(binder, drawPathAppName, drawPathPenType(), drawPathPenWidth(), color)
            }
            penConfigDirty = false
            Log.i(TAG, "[MosaicTrail] color=$color sent reason=$reason")
        } catch (error: Throwable) {
            drawPathBinder = null
            Log.w(TAG, "[MosaicTrail] sendPenInfo failed reason=$reason", error)
        }
    }

    
    fun setDrawPathSuspended(suspended: Boolean, reason: String) {
        if (suspendRequested == suspended) return
        val was = drawPathSuspended
        suspendRequested = suspended
        applyDrawPathSuspension(was, reason)
    }

    
    fun setDrawPathRegionBlocked(blocked: Boolean, reason: String) {
        if (regionBlocked == blocked) return
        val was = drawPathSuspended
        regionBlocked = blocked
        applyDrawPathSuspension(was, "region:$reason")
    }

    private fun applyDrawPathSuspension(was: Boolean, reason: String) {
        val suspended = drawPathSuspended
        if (was == suspended) return
        Log.i(TAG, "drawPath suspended=$suspended reason=$reason requested=$suspendRequested region=$regionBlocked configured=$configured active=$drawPathActive")
        if (!configured) return
        if (suspended) {
            if (drawPathActive) disableDrawPath("suspend:$reason")
        } else if (inkEnabled && !lassoEnabled && !drawPathActive) {
            enableDrawPath("resume:$reason")
        } else if (penConfigDirty && inkEnabled && !lassoEnabled) {
            rearmDrawPath("resume-pen-style:$reason")
        }
    }

    
    private fun refreshWriteInfo(reason: String, force: Boolean = false) {
        if (!attached) return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastWriteInfoRefreshAt < WRITE_INFO_REFRESH_MIN_INTERVAL_MS) return
        lastWriteInfoRefreshAt = now
        val requestId = writeInfoRequestId.incrementAndGet()
        Log.i(TAG, "drawPath context refresh queued reason=$reason request=$requestId")
        DrawPathContextClient.refresh(reactContext) { snapshot ->
            handler.post {
                if (!attached || requestId != writeInfoRequestId.get()) return@post
                if (snapshot == null) {
                    Log.i(TAG, "drawPath context unavailable reason=$reason request=$requestId")
                    return@post
                }
                val currentPath = try {
                    HostDataCacheAPI.getInstance()?.currentFilePath
                } catch (_: Throwable) {
                    null
                }
                if (currentPath != null && currentPath != snapshot.notePath) {
                    Log.i(
                        TAG,
                        "drawPath context changed during query reason=$reason " +
                            "${snapshot.notePath}->$currentPath",
                    )
                    return@post
                }
                val context = WriteContext(
                    notePath = snapshot.notePath,
                    sdkPage = snapshot.sdkPage,
                    hostPage = snapshot.hostPage,
                    layerId = snapshot.layerId,
                )
                writeContext = context
                val binder = drawPathBinder ?: ensureBinder()
                if (binder == null) {
                    Log.i(TAG, "drawPath context cached without binder reason=$reason")
                    return@post
                }
                if (lastWriteInfoSent == context) return@post
                try {
                    DrawPathClient.sendWriteInfo(
                        binder = binder,
                        appName = drawPathAppName,
                        pageNum = context.hostPage,
                        layer = context.layerId,
                    )
                    lastWriteInfoSent = context
                    Log.i(
                        TAG,
                        "drawPath context applied reason=$reason " +
                            "sdkPage=${snapshot.sdkPage} hostPage=${snapshot.hostPage} " +
                            "layer=${snapshot.layerId}",
                    )
                } catch (error: Throwable) {
                    Log.w(TAG, "drawPath context apply failed reason=$reason", error)
                    drawPathBinder = null
                }
            }
        }
    }

    
    private fun reassertCachedWriteInfo(binder: IBinder, reason: String): Boolean {
        val context = writeContext ?: return false
        return try {
            DrawPathClient.sendWriteInfo(
                binder = binder,
                appName = drawPathAppName,
                pageNum = context.hostPage,
                layer = context.layerId,
            )
            lastWriteInfoSent = context
            Log.i(
                TAG,
                "drawPath context reasserted reason=$reason " +
                    "sdkPage=${context.sdkPage} hostPage=${context.hostPage} " +
                    "layer=${context.layerId}",
            )
            true
        } catch (error: Throwable) {
            Log.w(TAG, "drawPath context reassert failed reason=$reason", error)
            drawPathBinder = null
            false
        }
    }


    private fun enableDrawPath(reason: String) {
        val binder = ensureBinder()
        if (binder == null) {
            Log.w(TAG, "drawPath enable pending: reason=$reason binder=null")
            drawPathActive = false
            return
        }
        try {
            configureDrawPath(binder, true, "$reason:enabled")
            drawPathActive = true
            penConfigDirty = false
            Log.i(
                TAG,
                "drawPath direct enabled: reason=$reason app=$drawPathAppName " +
                    "type=${drawPathPenType()} style=${penStyle.name} " +
                    "width=${drawPathPenWidth()} color=$drawPathPenColor",
            )
        } catch (error: Throwable) {
            drawPathActive = false
            drawPathBinder = null
            Log.w(TAG, "drawPath enable failed: reason=$reason", error)
        }
    }

    
    fun reassertPenBlock(reason: String) {
        Log.i(TAG, "[MosaicPenButton] block reason=$reason ink=$inkEnabled lasso=$lassoEnabled active=$drawPathActive configured=$configured contact=$stylusContact")
        if (!attached || !configured || inkEnabled || drawPathActive) return
        
        
        DrawPathGate.reblock("pen-block:$reason", PEN_BLOCK_REASSERT_MS)
    }

    private fun disableDrawPath(reason: String) {
        drawPathActive = false
        val binder = drawPathBinder ?: return
        try {
            synchronized(DrawPathGate.writeLock) { DrawPathClient.disableAll(binder, drawPathAppName) }
            Log.i(TAG, "drawPath disabled: reason=$reason")
        } catch (error: Throwable) {
            drawPathBinder = null
            Log.w(TAG, "drawPath disable failed: reason=$reason", error)
        }
    }

    private fun attachDrawPathGate(binder: IBinder) {
        DrawPathGate.attach(binder, drawPathAppName)
        DrawPathGate.inkExpected = drawPathActive
        DrawPathGate.onReleased = {
            if (attached && configured && drawPathActive && drawPathGateHeld) rearmDrawPath("pen-gate-release")
        }
    }

    private fun ensureBinder(): IBinder? {
        drawPathBinder?.let { return it }
        val binder = DrawPathClient.getBinder()
        drawPathBinder = binder
        return binder
    }

    private fun configureDrawPath(binder: IBinder, active: Boolean, reason: String) {
        lastWriteInfoSent = null
        attachDrawPathGate(binder)
        synchronized(DrawPathGate.writeLock) {
            
            val held = active && DrawPathGate.blocked()
            drawPathGateHeld = held
            if (active && !held) {
                DrawPathClient.configure(
                    binder = binder,
                    appName = drawPathAppName,
                    penType = drawPathPenType(),
                    penWidth = drawPathPenWidth(),
                    penColor = drawPathPenColor,
                    areas = drawPathDisableAreas(),
                )
                DrawPathClient.sendStylusCalibration(binder, drawPathAppName, stylusCalibX, stylusCalibY, stylusLeftHand)
            } else {
                
                
                DrawPathClient.disableAll(binder, drawPathAppName)
                if (held) Log.i(TAG, "drawPath enable held by pen gate reason=$reason ${DrawPathGate.describe()}")
            }
            
            DrawPathClient.sendPenButtonOverride(binder, drawPathAppName, drawPathPenType(), drawPathPenWidth(), drawPathPenColor)
        }
        reassertCachedWriteInfo(binder, reason)
        refreshWriteInfo(reason, force = true)
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
        awaitingStrokeCommit = false
        inkHandoffPending = true
        handler.removeCallbacks(inkHandoffTask)
        handler.postDelayed(inkHandoffTask, INK_HANDOFF_IDLE_MS)
    }

    private fun clearInkHandoff(): Boolean {
        handler.removeCallbacks(inkHandoffTask)
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

    
    private fun requestRotationReconfigure(source: String) {
        publishHostRotation()
        refreshWriteInfo(source)
        val expectedRotation = display?.rotation ?: -1
        val token = ++rotationReconfigureToken
        val realSize = Point()
        display?.getRealSize(realSize)
        Log.i(
            TAG,
            "drawPath reconfigure requested: source=$source token=$token " +
                "rotation=$expectedRotation display=${realSize.x}x${realSize.y}",
        )
        scheduleConfigure("$source:immediate")

        listOf(350L, 900L, 1600L, 2800L).forEach { delay ->
            handler.postDelayed({
                if (!attached) {
                    Log.i(TAG, "drawPath settle skipped: source=$source delay=$delay detached")
                    return@postDelayed
                }
                if (token != rotationReconfigureToken) {
                    Log.i(TAG, "drawPath settle skipped: source=$source delay=$delay superseded")
                    return@postDelayed
                }
                val actualRotation = display?.rotation ?: -1
                if (actualRotation != expectedRotation) {
                    Log.i(
                        TAG,
                        "drawPath settle skipped: source=$source delay=$delay " +
                            "rotationChanged=$expectedRotation->$actualRotation",
                    )
                    return@postDelayed
                }
                Log.i(
                    TAG,
                    "drawPath settle reconfigure: source=$source token=$token " +
                        "delay=$delay rotation=$actualRotation",
                )
                publishHostRotation()
                scheduleConfigure("$source:settle-$delay")
            }, delay)
        }
    }

    private fun scheduleConfigure(reason: String) {
        configured = false
        val generation = ++configureGeneration
        Log.i(
            TAG,
            "drawPath configure queued: reason=$reason generation=$generation " +
                "rotation=${display?.rotation} attached=$attached",
        )
        handler.post {
            Log.i(
                TAG,
                "drawPath configure dispatch: reason=$reason generation=$generation " +
                    "currentGeneration=$configureGeneration",
            )
            configureWithRetry(generation, 1, reason)
        }
    }

    private fun configureWithRetry(generation: Int, attempt: Int, reason: String) {
        if (!attached) {
            Log.i(TAG, "drawPath configure cancelled: reason=$reason generation=$generation detached")
            return
        }
        if (generation != configureGeneration) {
            Log.i(
                TAG,
                "drawPath configure cancelled: reason=$reason generation=$generation " +
                    "supersededBy=$configureGeneration",
            )
            return
        }
        if (configured) {
            Log.i(TAG, "drawPath configure skipped: reason=$reason generation=$generation alreadyConfigured")
            return
        }

        Log.i(
            TAG,
            "drawPath configure enter: reason=$reason generation=$generation " +
                "attempt=$attempt rotation=${display?.rotation}",
        )
        try {
            publishHostRotation()
            val binder = ensureBinder()
            if (binder == null) {
                Log.w(TAG, "drawPath binder unavailable: reason=$reason attempt=$attempt")
                scheduleRetry(generation, attempt, reason)
                return
            }
            val drawPathWidth = drawPathPenWidth()
            val shouldRender = inkEnabled && !lassoEnabled && !drawPathSuspended
            configureDrawPath(binder, shouldRender, "$reason:configured")
            configured = true
            drawPathActive = shouldRender
            Log.i(
                TAG,
                "drawPath direct configured: reason=$reason generation=$generation " +
                    "rotation=${display?.rotation} attempt=$attempt " +
                    "active=$drawPathActive width=$drawPathWidth color=$drawPathPenColor",
            )
            
            REARM_DELAYS_MS.forEach { delay ->
                handler.postDelayed({
                    if (!attached) return@postDelayed
                    if (generation != configureGeneration) return@postDelayed
                    rearmDrawPath("rearm+$delay")
                }, delay)
            }
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "drawPath configure failed: reason=$reason generation=$generation attempt=$attempt",
                error,
            )
            drawPathBinder = null
            scheduleRetry(generation, attempt, reason)
        }
    }

    
    private fun rearmDrawPath(reason: String) {
        if (!configured) return
        val binder = drawPathBinder ?: return
        try {
            configureDrawPath(binder, drawPathActive, reason)
            penConfigDirty = false
            Log.i(TAG, "drawPath rearm: reason=$reason active=$drawPathActive")
        } catch (e: Throwable) {
            Log.w(TAG, "drawPath rearm failed: reason=$reason", e)
            drawPathBinder = null
        }
    }

    
    private fun scheduleRetry(generation: Int, attempt: Int, reason: String) {
        val delay = if (attempt < FAST_MAX_ATTEMPTS) RETRY_INTERVAL_MS else SLOW_RETRY_INTERVAL_MS
        Log.i(
            TAG,
            "drawPath retry queued: reason=$reason generation=$generation " +
                "nextAttempt=${attempt + 1} delay=$delay",
        )
        handler.postDelayed({ configureWithRetry(generation, attempt + 1, reason) }, delay)
    }

    

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = if (event.pointerCount > 0) event.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
        val isPenTool = toolType == MotionEvent.TOOL_TYPE_STYLUS
            || toolType == MotionEvent.TOOL_TYPE_ERASER
        
        
        
        if (!isPenTool) {
            Log.i(TAG, "[MosaicTwoFinger] framework touch consumed action=${event.actionMasked} pointers=${event.pointerCount}")
            return true
        }

        
        
        
        
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            
            
            
            stylusContact = true
            InputArbiter.onPenContact(true)
            cancelBackgroundSync()
            lastStylusX = event.x
            lastStylusY = event.y
            
            
            
            controller.preflightPenButtonState()
            controller.onPen(event)
            
            feedInkView(event)
            
            if (toolLassoActive && !inkEnabled) reassertPenBlock("lasso-down")
        } else {
            val counterBefore = sceneChangeCounter
            feedInkView(event)
            controller.onPen(event)
            if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                && awaitingStrokeCommit && sceneChangeCounter == counterBefore
            ) {
                
                finalizeInkSession("no-commit")
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !configured) {
            Log.i(TAG, "stylus DOWN before drawPath configured, immediate configure attempt")
            scheduleConfigure("stylusDown")
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
                    handler.removeCallbacks(inkHandoffTask)
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
                    handler.removeCallbacks(penModeResetFallback)
                    handler.postDelayed(penModeResetFallback, PEN_MODE_RESET_MAX_MS)
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
            controller.onPenHover(event)
            
            
            
            
            
            
            val now = System.currentTimeMillis()
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    if (awaitingStrokeCommit) {
                        hoverRearmDeferred = true
                    } else {
                        lastHoverRearmAt = now
                        rearmDrawPath("hoverEnter")
                    }
                }
                MotionEvent.ACTION_HOVER_MOVE -> {
                    
                    if (now - lastHoverRearmAt > HOVER_REARM_MIN_INTERVAL_MS || penConfigDirty) {
                        if (awaitingStrokeCommit) {
                            hoverRearmDeferred = true
                        } else {
                            lastHoverRearmAt = now
                            rearmDrawPath("hoverHold")
                        }
                    }
                }
            }
        }
        return false
    }

    private var lastHoverRearmAt = 0L

    
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed && attached && configured) rearmDrawPath("layout")
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
