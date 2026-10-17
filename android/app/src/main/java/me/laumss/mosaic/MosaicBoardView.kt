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
    }

    internal val mainHandler = Handler(Looper.getMainLooper())

    @Volatile internal var attached = false
        private set

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
            if (inkHandoff.stylusContact) inkHandoff.applyPenRefreshMode()
            if (!value) {
                inkHandoff.inkAbort()
                inkHandoff.transientStrokeActive = false
                inkHandoff.releaseInkDefer()
                if (drawPath.configured) drawPath.disableDrawPath("ink-disabled")
            } else if (drawPath.configured && !lassoEnabled && !toolLassoActive && !drawPath.drawPathSuspended) {
                drawPath.enableDrawPath("ink-enabled")
            } else if (inkHandoff.stylusContact && lassoEnabled && !shapeDrag) {
                inkHandoff.transientStrokeActive = true
                inkHandoff.inkBegin(inkHandoff.lastStylusX, inkHandoff.lastStylusY)
                Log.i(TAG, "transient ink restarted on ink enable lasso=$lassoEnabled")
            }
        }

    var shapeDrag: Boolean = false

    var toolLassoActive: Boolean = false

    var lassoEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (inkHandoff.stylusContact) inkHandoff.applyPenRefreshMode()
            inkHandoff.inkAbort()
            inkHandoff.transientStrokeActive = false
            if (value) {
                inkHandoff.releaseInkDefer()
                if (drawPath.configured) drawPath.disableDrawPath("lasso-enabled")
            } else if (drawPath.configured && inkEnabled && !toolLassoActive && !drawPath.drawPathSuspended) {
                drawPath.enableDrawPath("lasso-disabled")
            }
            if (value && inkHandoff.stylusContact && !shapeDrag) {
                inkHandoff.transientStrokeActive = true
                inkHandoff.inkBegin(inkHandoff.lastStylusX, inkHandoff.lastStylusY)
                Log.i(TAG, "transient ink restarted on lasso switch enabled=$value")
            }
        }

    internal val contentView = BoardContentView(reactContext)

    private val grayRestore = EinkGrayRestore(contentView, "board", eligible = {
        InklingLink.isBoardSurface() && !inkHandoff.stylusContact &&
            !contentView.isMovingCards && !contentView.isDeferringRefresh && !contentView.holdPaint
    })

    internal val inkView = TransientInkView(reactContext)
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
    internal val inkHandoff = InkHandoff(this)
    private var sceneChangeCounter = 0

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
                inkHandoff.noteContentChanged(reason, moved)
            } else {
                mainHandler.post {
                    sceneChangeCounter++
                    controller.onSceneOrViewportChanged(reason)
                    inkHandoff.noteContentChanged(reason, moved)
                }
            }
        }
        contentView.onContentSettled = { inkHandoff.onContentSettled() }
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
        inkHandoff.stylusContact = false
        inkHandoff.awaitingStrokeCommit = false
        inkHandoff.fullRefreshPending = false
        inkHandoff.syncHoldUntil = 0L
        inkHandoff.clearInkHandoff()
        inkHandoff.plainStrokeCommit = false
        inkHandoff.strokeSceneChangedDuringContact = false
        inkHandoff.hoverRearmDeferred = false
        InputArbiter.onPenContact(false)
        if (inkHandoff.penModeApplied) {
            inkHandoff.penModeApplied = false
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
        inkHandoff.lastPenEventAt = SystemClock.uptimeMillis()

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            inkHandoff.onStylusDown(event)
            controller.preflightPenButtonState()
            controller.pen.onPen(event)

            inkHandoff.feedInkView(event)

            if (toolLassoActive && !inkEnabled) drawPath.reassertPenBlock("lasso-down")
        } else {
            val counterBefore = sceneChangeCounter
            inkHandoff.feedInkView(event)
            controller.pen.onPen(event)
            val ended = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
            if (ended && sceneChangeCounter == counterBefore) inkHandoff.onStrokeEndedWithoutChange()
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !drawPath.configured) {
            Log.i(TAG, "stylus DOWN before drawPath configured, immediate configure attempt")
            drawPath.scheduleConfigure("stylusDown")
        }

        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val toolType = if (event.pointerCount > 0) event.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS
            || toolType == MotionEvent.TOOL_TYPE_ERASER
        ) {
            controller.pen.onPenHover(event)
            drawPath.onPenHover(event.actionMasked)
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
