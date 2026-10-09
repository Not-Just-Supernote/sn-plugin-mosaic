package me.laumss.mosaic

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.text.TextUtils
import android.util.Log
import android.view.Surface
import android.view.MotionEvent
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs


class InputReader(
    private val context: Context,
    
    private val routeToBoard: Boolean = true,
    private val readPenEvents: Boolean = true,
    private val rawEventListener: ((action: Int, x: Float, y: Float, toolType: Int, pointerCount: Int) -> Unit)? = null,
) {

    companion object {
        private const val TAG = "MosaicInput"

        private const val EV_SYN = 0
        private const val EV_KEY = 1
        private const val EV_ABS = 3
        private const val SYN_REPORT = 0
        private const val ABS_MT_SLOT = 47
        private const val ABS_MT_POSITION_X = 53
        private const val ABS_MT_POSITION_Y = 54
        private const val ABS_MT_TRACKING_ID = 57
        private const val BTN_DIGI = 320
        private const val BTN_TOOL_RUBBER = 321
        private const val BTN_STYLUS = 331
        
        private const val NEAR_PRESS_MIN_HOVER_MS = 50L
        private const val ABS_TILT_X = 26
        private const val ABS_TILT_Y = 27
        private const val ABS_X = 0
        private const val ABS_Y = 1
        private const val ABS_PRESSURE = 24

        private const val TOOL_TYPE_FINGER = 1

        private const val MAX_SLOTS = 10
        private const val INPUT_EVENT_SIZE_64 = 24
        private const val INPUT_EVENT_SIZE_32 = 16

        private val isA5X = TextUtils.equals(Build.MODEL, "Supernote A5 X")
        private val isA5X2 = TextUtils.equals(Build.BOARD, "A5X2")

        @Volatile private var pluginViewVisible = false
        private val boardReaderLock = Any()
        @Volatile private var boardReader: InputReader? = null

        
        @JvmStatic
        fun ensureBoardReader(context: Context): InputReader = synchronized(boardReaderLock) {
            boardReader ?: InputReader(context).also {
                InputArbiter.reset()
                it.start()
                boardReader = it
                Log.i(TAG, "board reader ready from native View lifecycle")
            }
        }

        @JvmStatic
        fun releaseBoardReader() {
            synchronized(boardReaderLock) {
                boardReader?.stop()
                boardReader = null
                InputArbiter.reset()
            }
        }

        private data class HostDisplay(val rotation: Int, val width: Int, val height: Int)

        @Volatile private var hostDisplay: HostDisplay? = null

        
        @Volatile
        var penTiltDirection: FloatArray? = null
            private set

        
        @Volatile var penRawX = -1
            private set
        @Volatile var penRawY = -1
            private set
        @Volatile var penRawPressure = -1
            private set
        
        @Volatile var penPressure01 = -1f
            private set
        @Volatile private var penPressureUpdatedAtMs = 0L
        @Volatile private var penPressureMin = 0
        
        @Volatile private var penPressureMax = 0
        private const val PRESSURE_HISTORY_SIZE = 2048
        
        private const val PRESSURE_SAMPLE_WAIT_MS = 160L
        private const val PRESSURE_SAMPLE_MAX_AGE_MS = 160L
        private val pressureHistory = ArrayDeque<PressureSample>(PRESSURE_HISTORY_SIZE)
        private val pressureSignal = java.lang.Object()
        private data class PressureSample(val timeMs: Long, val pressure: Float)
        
        @Volatile private var pressureClockOffsetMs = 0L
        @Volatile private var pressureClockOffsetReady = false
        @Volatile var penRawTiltX = 0
            private set
        @Volatile var penRawTiltY = 0
            private set

        fun describePenRaw(): String =
            "raw=($penRawX,$penRawY) pressure=$penRawPressure pressure01=$penPressure01 " +
                "tilt=($penRawTiltX,$penRawTiltY)"

        
        @JvmStatic
        fun pressureForMotionEvent(event: MotionEvent): Float? {
            return pressureAt(event.eventTime, event.actionMasked == MotionEvent.ACTION_DOWN)
        }

        @JvmStatic
        fun pressureForHistoricalEvent(event: MotionEvent, index: Int): Float? {
            return pressureAt(event.getHistoricalEventTime(index))
        }

        @JvmStatic
        fun currentPenPressure(): Float? {
            val raw = penPressure01
            val age = SystemClock.uptimeMillis() - penPressureUpdatedAtMs
            if (!raw.isFinite() || raw < 0f || age !in 0..120L) {
                Log.w(TAG, "drawPath pressure sample unavailable age=$age raw=$penRawPressure")
                return null
            }
            return raw
        }

        private fun updatePenPressureRange(min: Int, max: Int) {
            if (max <= min) return
            penPressureMin = min
            penPressureMax = max
            Log.i(TAG, "pen pressure range min=$min max=$max")
        }

        private fun pressureAt(eventTimeMs: Long, logMatch: Boolean = false): Float? {
            synchronized(pressureSignal) {
                val deadline = SystemClock.uptimeMillis() + PRESSURE_SAMPLE_WAIT_MS
                while (true) {
                    
                    
                    
                    val match = pressureHistory.minByOrNull { abs(it.timeMs - eventTimeMs) }
                    val delta = match?.let { abs(it.timeMs - eventTimeMs) }
                    if (match != null && delta != null && delta <= PRESSURE_SAMPLE_MAX_AGE_MS) {
                        if (logMatch) Log.i(
                            TAG,
                            "pressure matched eventTime=$eventTimeMs sampleTime=${match.timeMs} " +
                                "delta=$delta pressure=${match.pressure} offset=$pressureClockOffsetMs",
                        )
                        return match.pressure
                    }
                    val remaining = deadline - SystemClock.uptimeMillis()
                    if (remaining <= 0L) {
                        Log.w(TAG, "drawPath pressure sample unavailable eventTime=$eventTimeMs " +
                            "first=${pressureHistory.peekFirst()?.timeMs} last=${pressureHistory.peekLast()?.timeMs} " +
                            "uptime=${SystemClock.uptimeMillis()} raw=$penRawPressure history=${pressureHistory.size}")
                        return null
                    }
                    try {
                        pressureSignal.wait(remaining)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        Log.w(TAG, "drawPath pressure sample wait interrupted eventTime=$eventTimeMs", e)
                        return null
                    }
                }
            }
        }

        @JvmStatic
        fun setPluginViewVisible(visible: Boolean) {
            pluginViewVisible = visible
            Log.i(TAG, "plugin view visible=$visible")
            
            
            
            if (!visible) {
                InputArbiter.onPenContact(false)
                InputArbiter.onPenHover(false)
            }
        }

        @JvmStatic
        fun updateHostDisplay(rotation: Int, width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            hostDisplay = HostDisplay(rotation, width, height)
            Log.i(TAG, "host display rotation=$rotation size=${width}x$height")
        }

        
        @JvmStatic
        fun isPenHovering(): Boolean = boardReader?.penHover == true

        @JvmStatic
        fun isPenRubberActive(): Boolean = boardReader?.penRubber == true

        @JvmStatic
        fun isPenStylusButtonDown(): Boolean = boardReader?.penStylusButton == true

        
        @JvmStatic
        fun isPenStylusPressNear(): Boolean = boardReader?.penStylusPressNear == true
    }

    private var touchThread: Thread? = null
    private var penThread: Thread? = null
    @Volatile private var running = false
    private var penReadyLatch = CountDownLatch(0)
    @Volatile private var penClockReady = false
    private var framePressure: Float? = null
    private var firstPressureFrame = true

    
    private val fingerX = IntArray(MAX_SLOTS) { -1 }
    private val fingerY = IntArray(MAX_SLOTS) { -1 }
    private val rawFingerX = IntArray(MAX_SLOTS) { -1 }
    private val rawFingerY = IntArray(MAX_SLOTS) { -1 }
    private val fingerId = IntArray(MAX_SLOTS) { -1 }
    private val lastFingerX = IntArray(MAX_SLOTS) { -1 }
    private val lastFingerY = IntArray(MAX_SLOTS) { -1 }
    private val fingerDownPending = BooleanArray(MAX_SLOTS)
    private val fingerUpPending = BooleanArray(MAX_SLOTS)
    private var mtSlot = 0

    private var touchGeneration = 0
    private var touchStreamDropped = false

    
    @Volatile private var penHover = false
    @Volatile private var penRubber = false
    @Volatile private var penStylusButton = false
    @Volatile private var penStylusPressNear = false
    
    private var penHoverSinceMs = 0L
    private var penTiltX = 0
    private var penTiltY = 0

    private val displayMetrics = context.resources.displayMetrics
    private val fallbackDisplayWidth = displayMetrics.widthPixels
    private val fallbackDisplayHeight = displayMetrics.heightPixels

    private fun currentDisplay(): HostDisplay =
        hostDisplay ?: HostDisplay(0, fallbackDisplayWidth, fallbackDisplayHeight)

    private var isPtMt = false

    private val inputEventSize: Int
        get() = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) INPUT_EVENT_SIZE_64 else INPUT_EVENT_SIZE_32

    fun start() {
        if (running) return
        running = true
        val paths = scanDevicePaths()
        val touchPath = paths["touch"]
        val penPath = paths["pen"]
        isPtMt = paths["touchName"]?.contains("pt_mt", ignoreCase = true) == true
        val display = currentDisplay()
        Log.i(
            TAG,
            "start: touch=$touchPath pen=$penPath isPtMt=$isPtMt " +
                "display=${display.width}x${display.height} rotation=${display.rotation} routeToBoard=$routeToBoard",
        )
        if (touchPath != null) {
            touchThread = Thread({ readLoop(touchPath, false) }, "MosaicTouchReader").also { it.start() }
        }
        if (readPenEvents && penPath != null) {
            penReadyLatch = CountDownLatch(1)
            penThread = Thread({ readLoop(penPath, true) }, "MosaicPenReader").also { it.start() }
            try {
                if (!penReadyLatch.await(160L, TimeUnit.MILLISECONDS) || !penClockReady) {
                    Log.w(TAG, "pen reader awaiting monotonic clock path=$penPath")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "pen reader open wait interrupted path=$penPath")
            }
        }
    }

    fun stop() {
        running = false
        touchThread?.interrupt()
        penThread?.interrupt()
        touchThread = null
        penThread = null
        resetState()
    }

    private fun resetState() {
        for (i in 0 until MAX_SLOTS) {
            fingerX[i] = -1; fingerY[i] = -1
            rawFingerX[i] = -1; rawFingerY[i] = -1
            fingerId[i] = -1
            lastFingerX[i] = -1; lastFingerY[i] = -1
            fingerDownPending[i] = false; fingerUpPending[i] = false
        }
        mtSlot = 0
        penHover = false; penRubber = false; penStylusButton = false; penStylusPressNear = false
        penTiltX = 0; penTiltY = 0
        framePressure = null
        firstPressureFrame = true
        touchStreamDropped = false
        if (routeToBoard && readPenEvents) {
            synchronized(pressureSignal) {
                pressureHistory.clear()
                penPressure01 = -1f
                penPressureUpdatedAtMs = 0L
                pressureClockOffsetMs = 0L
                pressureClockOffsetReady = false
                pressureSignal.notifyAll()
            }
            InputArbiter.onPenHover(false)
            DrawPathGate.resetInputs()
            penTiltDirection = null
        }
    }

    private fun readLoop(path: String, isPen: Boolean) {
        val size = inputEventSize
        val buffer = ByteArray(size)
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        try {
            FileInputStream(path).use { fis ->
                if (isPen) {
                    val clockConfigured = runCatching { EvdevClock.useMonotonic(fis.fd) }
                        .onFailure { Log.w(TAG, "pen clock ioctl unavailable; using measured timestamp offset", it) }
                        .isSuccess
                    penClockReady = true
                    Log.i(
                        TAG,
                        "pen clock=${if (clockConfigured) "CLOCK_MONOTONIC" else "measured-offset"} " +
                            "path=$path uptime=${SystemClock.uptimeMillis()}",
                    )
                }
                Log.i(TAG, "opened $path (eventSize=$size, isPen=$isPen)")
                if (isPen) penReadyLatch.countDown()
                val pollFd = StructPollfd().apply { fd = fis.fd; events = OsConstants.POLLIN.toShort() }
                while (running) {
                    if (Os.poll(arrayOf(pollFd), 50) == 0) continue
                    if (!running) return
                    var offset = 0
                    while (offset < size) {
                        val n = fis.read(buffer, offset, size - offset)
                        if (n < 0) {
                            Log.w(TAG, "EOF on $path")
                            return
                        }
                        offset += n
                    }
                    if (!running) return
                    val eventTimeMs = readEventTimeMs(buffer, size)
                    bb.position(size - 8)
                    val type = bb.short.toInt() and 0xFFFF
                    val code = bb.short.toInt() and 0xFFFF
                    val value = bb.int
                    if (isPen) handlePenEvent(type, code, value, eventTimeMs) else handleTouchEvent(type, code, value)
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "reader interrupted: $path")
        } catch (e: Exception) {
            Log.e(TAG, "reader error on $path", e)
        } finally {
            if (isPen) {
                penClockReady = false
                penReadyLatch.countDown()
            }
        }
    }

    private fun readEventTimeMs(buffer: ByteArray, size: Int): Long {
        val view = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val seconds: Long
        val micros: Long
        if (size == INPUT_EVENT_SIZE_64) {
            seconds = view.long
            micros = view.long
        } else {
            seconds = view.int.toLong()
            micros = view.int.toLong()
        }
        return seconds * 1000L + micros / 1000L
    }

    

    private fun handleTouchEvent(type: Int, code: Int, value: Int) {
        when (type) {
            EV_ABS -> when (code) {
                ABS_MT_SLOT -> mtSlot = value.coerceIn(0, MAX_SLOTS - 1)
                ABS_MT_TRACKING_ID -> {
                    if (value == -1) {
                        fingerUpPending[mtSlot] = true
                    } else {
                        fingerId[mtSlot] = value
                        fingerDownPending[mtSlot] = true
                        fingerUpPending[mtSlot] = false
                        rawFingerX[mtSlot] = -1
                        rawFingerY[mtSlot] = -1
                        lastFingerX[mtSlot] = -1
                        lastFingerY[mtSlot] = -1
                    }
                }
                ABS_MT_POSITION_X -> { rawFingerX[mtSlot] = value; updateTouchCoordinates(mtSlot) }
                ABS_MT_POSITION_Y -> { rawFingerY[mtSlot] = value; updateTouchCoordinates(mtSlot) }
            }
            EV_SYN -> if (code == SYN_REPORT) flushTouch()
        }
    }

    
    private fun updateTouchCoordinates(slot: Int) {
        val rawX = rawFingerX[slot]
        val rawY = rawFingerY[slot]
        if (rawX < 0 || rawY < 0) return
        val display = currentDisplay()
        val landscape = display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
        val portraitWidth = if (landscape) display.height else display.width
        val portraitHeight = if (landscape) display.width else display.height
        if (portraitWidth <= 0 || portraitHeight <= 0) return

        val baseX: Float
        val baseY: Float
        if (isA5X2) {
            if (isPtMt) {
                baseX = rawX * portraitWidth.toFloat() / portraitHeight
                baseY = rawY * portraitHeight.toFloat() / portraitWidth
            } else {
                baseX = rawX.toFloat()
                baseY = rawY.toFloat()
            }
        } else {
            baseX = portraitWidth - rawY.toFloat()
            baseY = rawX.toFloat()
        }
        val position = rotatePortraitPoint(baseX, baseY, portraitWidth, portraitHeight, display)
        fingerX[slot] = position[0]
        fingerY[slot] = position[1]
    }

    private fun rotatePortraitPoint(
        baseX: Float,
        baseY: Float,
        portraitWidth: Int,
        portraitHeight: Int,
        display: HostDisplay,
    ): IntArray {
        val x: Float
        val y: Float
        when (display.rotation) {
            Surface.ROTATION_90 -> { x = baseY; y = portraitWidth - baseX }
            Surface.ROTATION_180 -> { x = portraitWidth - baseX; y = portraitHeight - baseY }
            Surface.ROTATION_270 -> { x = portraitHeight - baseY; y = baseX }
            else -> { x = baseX; y = baseY }
        }
        return intArrayOf(
            Math.round(x).coerceIn(0, display.width),
            Math.round(y).coerceIn(0, display.height),
        )
    }

    private fun flushTouch() {
        
        for (i in 0 until MAX_SLOTS) {
            if (!fingerUpPending[i]) continue
            if (isReportedFinger(i)) {
                val activeCount = countReportedFingers()
                if (activeCount <= 1) {
                    emitTouch(InputRouter.ACTION_UP, 0)
                } else {
                    emitTouch(InputRouter.ACTION_POINTER_UP, getPointerIndex(i))
                }
            }
            fingerUpPending[i] = false
            fingerId[i] = -1
            fingerX[i] = -1; fingerY[i] = -1
            rawFingerX[i] = -1; rawFingerY[i] = -1
            lastFingerX[i] = -1; lastFingerY[i] = -1
            fingerDownPending[i] = false
        }
        
        var isFirst = countReportedFingers() == 0
        for (i in 0 until MAX_SLOTS) {
            if (!fingerDownPending[i]) continue
            if (fingerId[i] < 0 || fingerX[i] < 0 || fingerY[i] < 0) continue
            fingerDownPending[i] = false
            if (isFirst) {
                emitTouch(InputRouter.ACTION_DOWN, 0)
                isFirst = false
            } else {
                emitTouch(InputRouter.ACTION_POINTER_DOWN, getPointerIndex(i))
            }
            lastFingerX[i] = fingerX[i]
            lastFingerY[i] = fingerY[i]
        }
        
        var anyMoved = false
        for (i in 0 until MAX_SLOTS) {
            if (isReportedFinger(i) && (fingerX[i] != lastFingerX[i] || fingerY[i] != lastFingerY[i])) {
                lastFingerX[i] = fingerX[i]
                lastFingerY[i] = fingerY[i]
                anyMoved = true
            }
        }
        if (anyMoved) emitTouch(InputRouter.ACTION_MOVE, 0)
    }

    private fun isReportedFinger(slot: Int): Boolean =
        fingerId[slot] >= 0 && !fingerDownPending[slot] && fingerX[slot] >= 0 && fingerY[slot] >= 0

    private fun countReportedFingers(): Int {
        var count = 0
        for (i in 0 until MAX_SLOTS) if (isReportedFinger(i)) count++
        return count
    }

    private fun getPointerIndex(targetSlot: Int): Int {
        var index = 0
        for (i in 0 until targetSlot) if (isReportedFinger(i)) index++
        return index
    }

    private fun emitTouch(action: Int, actionIndex: Int) {
        val primarySlot = (0 until MAX_SLOTS).firstOrNull { isReportedFinger(it) }
        if (primarySlot != null) {
            rawEventListener?.invoke(
                action,
                fingerX[primarySlot].toFloat(),
                fingerY[primarySlot].toFloat(),
                TOOL_TYPE_FINGER,
                countReportedFingers(),
            )
        }
        if (!routeToBoard || !pluginViewVisible) return

        val now = SystemClock.uptimeMillis()
        
        if (action == InputRouter.ACTION_DOWN) {
            touchStreamDropped = InputArbiter.touchBlockedBySlider(now)
            if (!touchStreamDropped) touchGeneration++
            Log.i(
                TAG,
                "touch stream ${if (touchStreamDropped) "dropped" else "begin"} " +
                    "generation=$touchGeneration ${InputArbiter.describe()}",
            )
        }
        if (touchStreamDropped) {
            if (action == InputRouter.ACTION_UP) touchStreamDropped = false
            return
        }

        val count = countReportedFingers()
        val ids = IntArray(count)
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        var k = 0
        for (i in 0 until MAX_SLOTS) {
            if (!isReportedFinger(i)) continue
            ids[k] = fingerId[i]
            xs[k] = fingerX[i].toFloat()
            ys[k] = fingerY[i].toFloat()
            k++
        }
        InputRouter.postTouch(
            InputRouter.TouchFrame(
                action = action,
                actionIndex = actionIndex,
                generation = touchGeneration,
                ids = ids,
                xs = xs,
                ys = ys,
                penPriority = InputArbiter.penPriority(now),
                uptimeMs = now,
            ),
        )
    }

    

    private fun handlePenEvent(type: Int, code: Int, value: Int, eventTimeMs: Long) {
        if (type == EV_SYN && code == SYN_REPORT) {
            val pressure = framePressure ?: return
            val now = SystemClock.uptimeMillis()
            val observedOffset = now - eventTimeMs
            if (!pressureClockOffsetReady || abs(observedOffset - pressureClockOffsetMs) > 500L) {
                pressureClockOffsetMs = observedOffset
                pressureClockOffsetReady = true
            } else {
                
                
                pressureClockOffsetMs = (pressureClockOffsetMs * 7L + observedOffset) / 8L
            }
            val sampleTimeMs = eventTimeMs + pressureClockOffsetMs
            synchronized(pressureSignal) {
                if (!running) return
                penPressure01 = pressure
                penPressureUpdatedAtMs = sampleTimeMs
                if (pressureHistory.size == PRESSURE_HISTORY_SIZE) pressureHistory.removeFirst()
                pressureHistory.addLast(PressureSample(sampleTimeMs, pressure))
                pressureSignal.notifyAll()
            }
            if (firstPressureFrame) {
                firstPressureFrame = false
                Log.i(
                    TAG,
                    "pressure frame clock=MONOTONIC rawTime=$eventTimeMs sampleTime=$sampleTimeMs " +
                        "uptime=$now offset=$pressureClockOffsetMs",
                )
            }
            return
        }
        if (type == EV_KEY) {
            when (code) {
                BTN_DIGI -> {
                    val hovering = value == 1
                    if (hovering != penHover) setHover(hovering, eventTimeMs)
                }
                BTN_TOOL_RUBBER -> {
                    val active = value == 1
                    
                    if (active && !penHover) setHover(true, eventTimeMs)
                    if (active != penRubber) {
                        penRubber = active
                        
                        if (routeToBoard) DrawPathGate.onRubber(active)
                        emitPenState(InputRouter.PenState.RUBBER, active)
                    }
                }
                BTN_STYLUS -> {
                    val pressed = value == 1
                    if (pressed != penStylusButton) {
                        if (pressed) {
                            penStylusPressNear = penHover && eventTimeMs - penHoverSinceMs >= NEAR_PRESS_MIN_HOVER_MS
                        }
                        penStylusButton = pressed
                        if (routeToBoard) DrawPathGate.onStylus(pressed, penStylusPressNear)
                        emitPenState(InputRouter.PenState.STYLUS, pressed)
                    }
                }
            }
            return
        }
        if (type == EV_ABS) {
            when (code) {
                ABS_TILT_X -> { penTiltX = value; penRawTiltX = value; updateTiltDirection() }
                ABS_TILT_Y -> { penTiltY = value; penRawTiltY = value; updateTiltDirection() }
                ABS_X -> penRawX = value
                ABS_Y -> penRawY = value
                ABS_PRESSURE -> {
                    penRawPressure = value
                    if (penPressureMax <= penPressureMin) {
                        Log.e(
                            TAG,
                            "drawPath ABS_PRESSURE range unavailable min=$penPressureMin max=$penPressureMax",
                        )
                        return
                    }
                    val range = penPressureMax - penPressureMin
                    framePressure = ((value - penPressureMin).toFloat() / range).coerceIn(0f, 1f)
                }
            }
        }
    }

    private fun setHover(hovering: Boolean, eventTimeMs: Long) {
        penHover = hovering
        if (hovering) penHoverSinceMs = eventTimeMs
        if (routeToBoard) InputArbiter.onPenHover(hovering)
        updateTiltDirection()
        emitPenState(InputRouter.PenState.HOVER, hovering)
    }

    private fun updateTiltDirection() {
        if (!routeToBoard) return
        if (!penHover) {
            penTiltDirection = null
            return
        }
        val raw = transformPenTilt(penTiltX, penTiltY)
        val magnitude = Math.hypot(raw[0].toDouble(), raw[1].toDouble())
        penTiltDirection = if (magnitude < 0.001) null else floatArrayOf(
            (raw[0] / magnitude).toFloat(),
            (raw[1] / magnitude).toFloat(),
        )
    }

    
    private fun transformPenTilt(rawTiltX: Int, rawTiltY: Int): FloatArray {
        val tiltX = signedTilt(rawTiltX)
        val tiltY = signedTilt(rawTiltY)
        val rotation = currentDisplay().rotation
        val quadX: Int
        val quadY: Int
        if (isA5X2) {
            when (rotation) {
                Surface.ROTATION_90 -> { quadX = tiltY; quadY = tiltX }
                Surface.ROTATION_180 -> { quadX = -tiltX; quadY = tiltY }
                Surface.ROTATION_270 -> { quadX = -tiltY; quadY = -tiltX }
                else -> { quadX = tiltX; quadY = -tiltY }
            }
        } else if (isA5X) {
            when (rotation) {
                Surface.ROTATION_90 -> { quadX = -tiltX; quadY = tiltY }
                Surface.ROTATION_180 -> { quadX = -tiltY; quadY = -tiltX }
                Surface.ROTATION_270 -> { quadX = tiltX; quadY = -tiltY }
                else -> { quadX = tiltY; quadY = tiltX }
            }
        } else {
            when (rotation) {
                Surface.ROTATION_90 -> { quadX = tiltX; quadY = -tiltY }
                Surface.ROTATION_180 -> { quadX = tiltY; quadY = tiltX }
                Surface.ROTATION_270 -> { quadX = -tiltX; quadY = tiltY }
                else -> { quadX = -tiltY; quadY = -tiltX }
            }
        }
        
        
        
        return floatArrayOf(quadX.toFloat(), quadY.toFloat())
    }

    private fun signedTilt(value: Int): Int = if (value > 9000) value - 65535 else value

    private fun emitPenState(state: InputRouter.PenState, value: Boolean) {
        if (!routeToBoard || !pluginViewVisible) return
        Log.i(TAG, "penState type=$state value=$value")
        InputRouter.postPenState(state, value)
    }

    

    private fun scanDevicePaths(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val pb = ProcessBuilder("getevent", "-lp")
            pb.redirectErrorStream(true)
            val process = pb.start()
            val content = process.inputStream.bufferedReader().readText()
            process.waitFor()
            for (block in content.split("add device")) {
                if (block.isBlank()) continue
                val path = Regex("/dev/input/event\\d+").find(block)?.value ?: continue
                val name = Regex("name:\\s*\"(.+?)\"").find(block)?.groupValues?.get(1) ?: continue
                Log.i(TAG, "found device: $name -> $path")
                when {
                    name.contains("Wacom", true) || name.contains("Digitizer", true) -> {
                        result["pen"] = path
                        val pressure = Regex(
                            "ABS_PRESSURE[^\\n]*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)",
                            RegexOption.IGNORE_CASE,
                        ).find(block)
                        if (pressure != null) {
                            updatePenPressureRange(
                                pressure.groupValues[1].toInt(),
                                pressure.groupValues[2].toInt(),
                            )
                        }
                    }
                    name.contains("Atmel", true) || name.contains("maXTouch", true) ||
                        name.contains("pt_mt", true) || name.contains("fts_ts", true) ||
                        name.contains("Touchscreen", true) -> {
                        result["touch"] = path
                        result["touchName"] = name
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanDevicePaths failed", e)
        }
        if (result["touch"] == null) {
            result["touch"] = "/dev/input/event1"
            Log.w(TAG, "touch device not found, fallback to event1")
        }
        if (result["pen"] == null) {
            result["pen"] = "/dev/input/event0"
            Log.w(TAG, "pen device not found, fallback to event0")
        }
        return result
    }
}
