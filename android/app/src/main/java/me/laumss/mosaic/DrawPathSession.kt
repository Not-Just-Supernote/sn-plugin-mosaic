package me.laumss.mosaic

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.ratta.supernote.pluginlib.api.HostDataCacheAPI
import java.util.concurrent.atomic.AtomicLong

/** The connection to the system ink service (drawPath) that draws the pen trail live: pen type, width and color, areas it must not draw in, the note page it writes to, and reconfiguring after rotation or errors. */
internal class DrawPathSession(private val view: MosaicBoardView) {
    companion object {
        private val PEN_BLOCK_REASSERT_MS = longArrayOf(0L, 60L, 200L, 500L)
        private const val RETRY_INTERVAL_MS = 500L
        private const val SLOW_RETRY_INTERVAL_MS = 2000L
        private const val FAST_MAX_ATTEMPTS = 20
        private val REARM_DELAYS_MS = longArrayOf(400L, 900L, 1700L)
        private const val WRITE_INFO_REFRESH_MIN_INTERVAL_MS = 1000L
    }

    private var penWidth: Int = (PenPopup.WIDTHS[PenPopup.DEFAULT_INDEX] * 100f).toInt()

    private var penStyle: PenStyle = PenStyle.PEN

    @Volatile internal var penConfigDirty = true

    private var stylusCalibX = 0

    private var stylusCalibY = 0

    private var stylusLeftHand = false

    private var configureGeneration = 0

    private var rotationReconfigureToken = 0

    internal var configured = false

    internal var drawPathActive = false
        set(value) {
            field = value
            DrawPathGate.inkExpected = value
        }

    private var drawPathGateHeld = false

    internal var drawPathBinder: IBinder? = null

    private var suspendRequested = false

    private var regionBlocked = false

    internal val drawPathSuspended: Boolean get() = suspendRequested || regionBlocked

    @Volatile private var writeContext: WriteContext? = null

    private val writeInfoRequestId = AtomicLong(0L)

    private var lastWriteInfoSent: WriteContext? = null

    internal val drawPathAppName: String = DrawPathClient.MOSAIC_APP_NAME

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
        view.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (!view.attached || displayId != view.display?.displayId) return

            view.mainHandler.post {
                if (!view.attached) return@post
                Log.i(MosaicBoardView.TAG, "display changed: rotation=${view.display?.rotation}")
                requestRotationReconfigure("displayChanged")
            }
        }
    }

    private val writeContextWatch = object : Runnable {
        override fun run() {
            if (!view.attached) return
            refreshWriteInfo("contextWatch")
            view.mainHandler.postDelayed(this, 1500L)
        }
    }

    private val orientationWatch = object : Runnable {
        override fun run() {
            if (!view.attached) return
            val rotation = view.display?.rotation ?: 0
            if (rotation != lastObservedRotation) {
                Log.i(MosaicBoardView.TAG, "orientation watcher: $lastObservedRotation -> $rotation")
                requestRotationReconfigure("orientationWatcher")
            }
            view.mainHandler.postDelayed(this, 250L)
        }
    }

    private val configurationCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (!view.attached) return

            view.mainHandler.postDelayed({
                requestRotationReconfigure("applicationConfigurationChanged")
            }, 100L)
        }

        override fun onLowMemory() = Unit
    }

    internal var lastHoverRearmAt = 0L

    /** Starts watching rotation and the note being written to, and configures the service. */
    fun attach() {
        view.context.applicationContext.registerComponentCallbacks(configurationCallback)
        displayManager.registerDisplayListener(displayListener, view.mainHandler)
        publishHostRotation()
        lastObservedRotation = view.display?.rotation ?: 0
        view.mainHandler.post(orientationWatch)
        view.mainHandler.postDelayed(writeContextWatch, 1500L)
        refreshWriteInfo("attach", force = true)
        scheduleConfigure("attach")
    }

    /** Stops the watchers and drops configure and write-info work still queued. */
    fun stopWatching() {
        view.context.applicationContext.unregisterComponentCallbacks(configurationCallback)
        displayManager.unregisterDisplayListener(displayListener)
        configureGeneration++
        writeInfoRequestId.incrementAndGet()
        writeContext = null
        lastWriteInfoSent = null
    }

    /** Gives the pen back to the host: default pressure curve and pen button, service released. */
    fun release() {
        DrawPathGate.detach()
        val releaseBinder = drawPathBinder ?: DrawPathClient.getBinder()
        releaseBinder?.let { binder ->
            synchronized(DrawPathGate.writeLock) {
                DrawPathClient.restoreDefaultPressureCurve(binder, drawPathAppName)
            }
            try {
                val settings = GestureSettings.readCached() ?: GestureSettings.read(view.context)
                synchronized(DrawPathGate.writeLock) {
                    DrawPathClient.restoreHostPenButtonStyle(binder, drawPathAppName, settings.raw["lamy_button"] == "1")
                    DrawPathClient.release(binder, drawPathAppName)
                }
            } catch (e: Throwable) { Log.w(MosaicBoardView.TAG, "drawPath release on detach failed", e) }
        }
        drawPathActive = false
        configured = false
        // A suspend asked for in this session (scene cache, note tap) must not outlive it.
        suspendRequested = false
        drawPathBinder = null
    }

    fun setPenStyle(style: PenStyle, width: Float) {
        penStyle = style
        penWidth = (width * 100f).toInt().coerceIn(50, 4000)
        penConfigDirty = true
        Log.i(MosaicBoardView.TAG, "pen style requested type=${style.objType} stdWidth=$width sent=${drawPathPenWidth()} configured=$configured suspended=$drawPathSuspended")
        if (configured && !drawPathSuspended) rearmDrawPath("pen-style")
    }

    fun setStylusCalibration(diffX: Int, diffY: Int, leftHand: Boolean) {
        val changed = diffX != stylusCalibX || diffY != stylusCalibY || leftHand != stylusLeftHand
        stylusCalibX = diffX
        stylusCalibY = diffY
        stylusLeftHand = leftHand
        if (changed && configured) rearmDrawPath("calibration")
    }

    private data class WriteContext(
        val notePath: String,
        val sdkPage: Int,
        val hostPage: Int,
        val layerId: Int,
    )

    internal fun restoreDrawPathPressureCurve() {
        val binder = drawPathBinder ?: return
        synchronized(DrawPathGate.writeLock) {
            DrawPathClient.restoreDefaultPressureCurve(binder, drawPathAppName)
        }
    }

    private fun publishHostRotation() {
        val currentDisplay = view.display
        val realSize = Point()
        currentDisplay?.getRealSize(realSize)
        val screenWidth = realSize.x.takeIf { it > 0 } ?: view.width
        val screenHeight = realSize.y.takeIf { it > 0 } ?: view.height
        lastObservedRotation = currentDisplay?.rotation ?: 0
        InputReader.updateHostDisplay(lastObservedRotation, screenWidth, screenHeight)
    }

    private fun drawPathPenType(): Int = when (penStyle) {
        PenStyle.PEN -> DrawPathClient.PEN_TYPE_CALLIGRAPHY
        PenStyle.FILLED_SHAPE -> DrawPathClient.PEN_TYPE_PRESSURE
        PenStyle.BRUSH -> DrawPathClient.PEN_TYPE_NEEDLE
        else -> penStyle.objType
    }

    private fun drawPathPenWidth(): Int =
        DrawPathClient.liveWidthArgumentFromStdHundredths(penWidth, penStyle)

    private fun drawPathDisableAreas(): List<DrawPathClient.DisableArea> {
        val areas = mutableListOf<DrawPathClient.DisableArea>()
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val toolbarHeight = view.chromeView.toolbarHeightPx()
        if (toolbarHeight > 0 && view.width > 0) {
            areas.add(DrawPathClient.DisableArea(loc[0], loc[1], view.width, toolbarHeight))
        }

        view.chromeView.touchToggleBounds()?.let { r ->
            areas.add(DrawPathClient.DisableArea(loc[0] + r.left, loc[1] + r.top, r.width(), r.height()))
        }
        InklingLink.toolbarDisableArea()?.let(areas::add)

        areas.addAll(InklingLink.overlayDisableAreas())
        // Around a zoomed-out note page; refreshed by the hover rearm before every stroke.
        for (r in view.controller.notes.noteOffPageRectsPx()) {
            areas.add(DrawPathClient.DisableArea(loc[0] + r.left, loc[1] + r.top, r.width(), r.height()))
        }
        return areas
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
            Log.i(MosaicBoardView.TAG, "[MosaicTrail] color=$color stored reason=$reason configured=$configured active=$drawPathActive")
            return
        }
        if (view.stylusContact || view.awaitingStrokeCommit) {
            view.hoverRearmDeferred = true
            Log.i(MosaicBoardView.TAG, "[MosaicTrail] color=$color deferred reason=$reason contact=${view.stylusContact} awaitingCommit=${view.awaitingStrokeCommit}")
            return
        }
        val binder = drawPathBinder ?: return
        try {
            synchronized(DrawPathGate.writeLock) {
                DrawPathClient.sendPenInfo(binder, drawPathAppName, drawPathPenType(), drawPathPenWidth(), color,
                    pressureCurveEnabled = view.hasWindowFocus() && view.isShown)
                DrawPathClient.sendPenButtonOverride(binder, drawPathAppName, drawPathPenType(), drawPathPenWidth(), color)
            }
            penConfigDirty = false
            Log.i(MosaicBoardView.TAG, "[MosaicTrail] color=$color sent reason=$reason")
        } catch (error: Throwable) {
            drawPathBinder = null
            Log.w(MosaicBoardView.TAG, "[MosaicTrail] sendPenInfo failed reason=$reason", error)
        }
    }

    fun setSuspended(suspended: Boolean, reason: String) {
        if (suspendRequested == suspended) return
        val was = drawPathSuspended
        suspendRequested = suspended
        applyDrawPathSuspension(was, reason)
    }

    fun setRegionBlocked(blocked: Boolean, reason: String) {
        if (regionBlocked == blocked) return
        val was = drawPathSuspended
        regionBlocked = blocked
        applyDrawPathSuspension(was, "region:$reason")
    }

    private fun applyDrawPathSuspension(was: Boolean, reason: String) {
        val suspended = drawPathSuspended
        if (was == suspended) return
        Log.i(MosaicBoardView.TAG, "drawPath suspended=$suspended reason=$reason requested=$suspendRequested region=$regionBlocked configured=$configured active=$drawPathActive")
        if (!configured) return
        if (suspended) {
            if (drawPathActive) disableDrawPath("suspend:$reason")
        } else if (view.inkEnabled && !view.lassoEnabled && !drawPathActive) {
            enableDrawPath("resume:$reason")
        } else if (penConfigDirty && view.inkEnabled && !view.lassoEnabled) {
            rearmDrawPath("resume-pen-style:$reason")
        }
    }

    private fun refreshWriteInfo(reason: String, force: Boolean = false) {
        if (!view.attached) return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastWriteInfoRefreshAt < WRITE_INFO_REFRESH_MIN_INTERVAL_MS) return
        lastWriteInfoRefreshAt = now
        val requestId = writeInfoRequestId.incrementAndGet()
        Log.i(MosaicBoardView.TAG, "drawPath context refresh queued reason=$reason request=$requestId")
        DrawPathContextClient.refresh(view.reactContext) { snapshot ->
            view.mainHandler.post {
                if (!view.attached || requestId != writeInfoRequestId.get()) return@post
                if (snapshot == null) {
                    Log.i(MosaicBoardView.TAG, "drawPath context unavailable reason=$reason request=$requestId")
                    return@post
                }
                val currentPath = try {
                    HostDataCacheAPI.getInstance()?.currentFilePath
                } catch (_: Throwable) {
                    null
                }
                if (currentPath != null && currentPath != snapshot.notePath) {
                    Log.i(
                        MosaicBoardView.TAG,
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
                    Log.i(MosaicBoardView.TAG, "drawPath context cached without binder reason=$reason")
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
                        MosaicBoardView.TAG,
                        "drawPath context applied reason=$reason " +
                            "sdkPage=${snapshot.sdkPage} hostPage=${snapshot.hostPage} " +
                            "layer=${snapshot.layerId}",
                    )
                } catch (error: Throwable) {
                    Log.w(MosaicBoardView.TAG, "drawPath context apply failed reason=$reason", error)
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
                MosaicBoardView.TAG,
                "drawPath context reasserted reason=$reason " +
                    "sdkPage=${context.sdkPage} hostPage=${context.hostPage} " +
                    "layer=${context.layerId}",
            )
            true
        } catch (error: Throwable) {
            Log.w(MosaicBoardView.TAG, "drawPath context reassert failed reason=$reason", error)
            drawPathBinder = null
            false
        }
    }

    internal fun enableDrawPath(reason: String) {
        val binder = ensureBinder()
        if (binder == null) {
            Log.w(MosaicBoardView.TAG, "drawPath enable pending: reason=$reason binder=null")
            drawPathActive = false
            return
        }
        try {
            configureDrawPath(binder, true, "$reason:enabled")
            drawPathActive = true
            penConfigDirty = false
            Log.i(
                MosaicBoardView.TAG,
                "drawPath direct enabled: reason=$reason app=$drawPathAppName " +
                    "type=${drawPathPenType()} style=${penStyle.name} " +
                    "width=${drawPathPenWidth()} color=$drawPathPenColor",
            )
        } catch (error: Throwable) {
            drawPathActive = false
            drawPathBinder = null
            Log.w(MosaicBoardView.TAG, "drawPath enable failed: reason=$reason", error)
        }
    }

    fun reassertPenBlock(reason: String) {
        Log.i(MosaicBoardView.TAG, "[MosaicPenButton] block reason=$reason ink=${view.inkEnabled} lasso=${view.lassoEnabled} active=$drawPathActive configured=$configured contact=${view.stylusContact}")
        if (!view.attached || !configured || view.inkEnabled || drawPathActive) return

        DrawPathGate.reblock("pen-block:$reason", PEN_BLOCK_REASSERT_MS)
    }

    internal fun disableDrawPath(reason: String) {
        drawPathActive = false
        val binder = drawPathBinder ?: return
        try {
            synchronized(DrawPathGate.writeLock) { DrawPathClient.disableAll(binder, drawPathAppName) }
            Log.i(MosaicBoardView.TAG, "drawPath disabled: reason=$reason")
        } catch (error: Throwable) {
            drawPathBinder = null
            Log.w(MosaicBoardView.TAG, "drawPath disable failed: reason=$reason", error)
        }
    }

    private fun attachDrawPathGate(binder: IBinder) {
        DrawPathGate.attach(binder, drawPathAppName)
        DrawPathGate.inkExpected = drawPathActive
        DrawPathGate.onReleased = {
            if (view.attached && configured && drawPathActive && drawPathGateHeld) rearmDrawPath("pen-gate-release")
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
                    pressureCurveEnabled = view.hasWindowFocus() && view.isShown,
                )
                DrawPathClient.sendStylusCalibration(binder, drawPathAppName, stylusCalibX, stylusCalibY, stylusLeftHand)
            } else {
                DrawPathClient.disableAll(binder, drawPathAppName)
                if (held) Log.i(MosaicBoardView.TAG, "drawPath enable held by pen gate reason=$reason ${DrawPathGate.describe()}")
            }

            DrawPathClient.sendPenButtonOverride(binder, drawPathAppName, drawPathPenType(), drawPathPenWidth(), drawPathPenColor)
        }
        reassertCachedWriteInfo(binder, reason)
        refreshWriteInfo(reason, force = true)
    }

    internal fun requestRotationReconfigure(source: String) {
        publishHostRotation()
        refreshWriteInfo(source)
        val expectedRotation = view.display?.rotation ?: -1
        val token = ++rotationReconfigureToken
        val realSize = Point()
        view.display?.getRealSize(realSize)
        Log.i(
            MosaicBoardView.TAG,
            "drawPath reconfigure requested: source=$source token=$token " +
                "rotation=$expectedRotation display=${realSize.x}x${realSize.y}",
        )
        scheduleConfigure("$source:immediate")

        listOf(350L, 900L, 1600L, 2800L).forEach { delay ->
            view.mainHandler.postDelayed({
                if (!view.attached) {
                    Log.i(MosaicBoardView.TAG, "drawPath settle skipped: source=$source delay=$delay detached")
                    return@postDelayed
                }
                if (token != rotationReconfigureToken) {
                    Log.i(MosaicBoardView.TAG, "drawPath settle skipped: source=$source delay=$delay superseded")
                    return@postDelayed
                }
                val actualRotation = view.display?.rotation ?: -1
                if (actualRotation != expectedRotation) {
                    Log.i(
                        MosaicBoardView.TAG,
                        "drawPath settle skipped: source=$source delay=$delay " +
                            "rotationChanged=$expectedRotation->$actualRotation",
                    )
                    return@postDelayed
                }
                Log.i(
                    MosaicBoardView.TAG,
                    "drawPath settle reconfigure: source=$source token=$token " +
                        "delay=$delay rotation=$actualRotation",
                )
                publishHostRotation()
                scheduleConfigure("$source:settle-$delay")
            }, delay)
        }
    }

    internal fun scheduleConfigure(reason: String) {
        configured = false
        val generation = ++configureGeneration
        Log.i(
            MosaicBoardView.TAG,
            "drawPath configure queued: reason=$reason generation=$generation " +
                "rotation=${view.display?.rotation} attached=${view.attached}",
        )
        view.mainHandler.post {
            Log.i(
                MosaicBoardView.TAG,
                "drawPath configure dispatch: reason=$reason generation=$generation " +
                    "currentGeneration=$configureGeneration",
            )
            configureWithRetry(generation, 1, reason)
        }
    }

    private fun configureWithRetry(generation: Int, attempt: Int, reason: String) {
        if (!view.attached) {
            Log.i(MosaicBoardView.TAG, "drawPath configure cancelled: reason=$reason generation=$generation detached")
            return
        }
        if (generation != configureGeneration) {
            Log.i(
                MosaicBoardView.TAG,
                "drawPath configure cancelled: reason=$reason generation=$generation " +
                    "supersededBy=$configureGeneration",
            )
            return
        }
        if (configured) {
            Log.i(MosaicBoardView.TAG, "drawPath configure skipped: reason=$reason generation=$generation alreadyConfigured")
            return
        }

        Log.i(
            MosaicBoardView.TAG,
            "drawPath configure enter: reason=$reason generation=$generation " +
                "attempt=$attempt rotation=${view.display?.rotation}",
        )
        try {
            publishHostRotation()
            val binder = ensureBinder()
            if (binder == null) {
                Log.w(MosaicBoardView.TAG, "drawPath binder unavailable: reason=$reason attempt=$attempt")
                scheduleRetry(generation, attempt, reason)
                return
            }
            val drawPathWidth = drawPathPenWidth()
            val shouldRender = view.inkEnabled && !view.lassoEnabled && !drawPathSuspended
            configureDrawPath(binder, shouldRender, "$reason:configured")
            configured = true
            drawPathActive = shouldRender
            Log.i(
                MosaicBoardView.TAG,
                "drawPath direct configured: reason=$reason generation=$generation " +
                    "rotation=${view.display?.rotation} attempt=$attempt " +
                    "active=$drawPathActive width=$drawPathWidth color=$drawPathPenColor",
            )

            REARM_DELAYS_MS.forEach { delay ->
                view.mainHandler.postDelayed({
                    if (!view.attached) return@postDelayed
                    if (generation != configureGeneration) return@postDelayed
                    rearmDrawPath("rearm+$delay")
                }, delay)
            }
        } catch (error: Throwable) {
            Log.w(
                MosaicBoardView.TAG,
                "drawPath configure failed: reason=$reason generation=$generation attempt=$attempt",
                error,
            )
            drawPathBinder = null
            scheduleRetry(generation, attempt, reason)
        }
    }

    internal fun rearmDrawPath(reason: String) {
        if (!configured) return
        val binder = drawPathBinder ?: return
        try {
            configureDrawPath(binder, drawPathActive, reason)
            penConfigDirty = false
            Log.i(MosaicBoardView.TAG, "drawPath rearm: reason=$reason active=$drawPathActive")
        } catch (e: Throwable) {
            Log.w(MosaicBoardView.TAG, "drawPath rearm failed: reason=$reason", e)
            drawPathBinder = null
        }
    }

    private fun scheduleRetry(generation: Int, attempt: Int, reason: String) {
        val delay = if (attempt < FAST_MAX_ATTEMPTS) RETRY_INTERVAL_MS else SLOW_RETRY_INTERVAL_MS
        Log.i(
            MosaicBoardView.TAG,
            "drawPath retry queued: reason=$reason generation=$generation " +
                "nextAttempt=${attempt + 1} delay=$delay",
        )
        view.mainHandler.postDelayed({ configureWithRetry(generation, attempt + 1, reason) }, delay)
    }
}
