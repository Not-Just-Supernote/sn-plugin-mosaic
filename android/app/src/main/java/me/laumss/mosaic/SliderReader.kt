package me.laumss.mosaic

import android.text.TextUtils
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader


class SliderReader {

    companion object {
        private const val TAG = "MosaicSlider"
        const val SIDE_LEFT = 1
        const val SIDE_RIGHT = 2

        
        fun isSupportedDevice(deviceType: Int): Boolean = deviceType == 4 || deviceType == 5

        private const val SYSFS_DIR_28 = "/sys/devices/platform/fe5d0000.i2c/i2c-4/4-0028/"
        private const val SYSFS_DIR_38 = "/sys/devices/platform/fe5d0000.i2c/i2c-4/4-0038/"
        private const val PROBE_PATH = "/sys/bus/i2c/devices/4-0028/l_x"

        private const val LEFT_FILE = "l_x"
        private const val RIGHT_FILE = "r_x"

        private const val SLIDER_LENGTH = 240
        private const val SLIP_OFFSET = 2
        private const val PRESS_TIMEOUT_MS = 350L
        private const val LONG_PRESS_TIMEOUT_MS = 500L
        private const val FAST_SWIPE_MIN_SPEED = 300
        private const val IDLE_POLL_MS = 100L
        private const val ZERO_DEBOUNCE = 2

        private val isA5X2 = TextUtils.equals(Build.BOARD, "A5X2")
    }

    private var sysfsDir = SYSFS_DIR_28
    private var activePollMs = 50L
    private var doubleSlipOffset = 2

    @Volatile private var running = false
    private var pollThread: Thread? = null

    private class SideState {
        var downTime = -1L
        var downPosition = -1
        var lastPosition = -1
        var isSlide = false
        var isLongPressed = false
        var twoDownTime = -1L
        var twoDownPositions: IntArray? = null
        var isTwoEvent = false
        var isTwoLongPressed = false
        var isTwoMove = false
        var zeroCount = 0
    }

    private val sides = arrayOf(SideState(), SideState())

    init {
        if (!File(PROBE_PATH).exists()) {
            sysfsDir = SYSFS_DIR_38
            activePollMs = 30L
            doubleSlipOffset = 5
            Log.i(TAG, "using 0x38 address (poll=${activePollMs}ms)")
        } else {
            Log.i(TAG, "using 0x28 address (poll=${activePollMs}ms)")
        }
        Log.i(TAG, "logical slider axis board=${Build.BOARD} inverted=$isA5X2")
    }

    fun start() {
        if (running) return
        running = true
        pollThread = Thread({
            pollLoop()
        }, "MosaicSliderReader").also { it.start() }
        Log.i(TAG, "started")
    }

    fun stop() {
        running = false
        pollThread?.interrupt()
        pollThread = null
        Log.i(TAG, "stopped")
    }

    private fun pollLoop() {
        while (running) {
            val leftActive = pollSide(SIDE_LEFT)
            val rightActive = pollSide(SIDE_RIGHT)
            val delay = if (leftActive || rightActive) activePollMs else IDLE_POLL_MS
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun pollSide(side: Int): Boolean {
        val file = if (side == SIDE_LEFT) LEFT_FILE else RIGHT_FILE
        val raw = readSysfs(sysfsDir + file)
        val rawPositions = parsePositions(raw)
        val s = sides[side - 1]

        if (rawPositions == null) {
            s.zeroCount++
            if (s.zeroCount >= ZERO_DEBOUNCE && (s.downTime >= 0 || s.isTwoEvent)) {
                handleUp(side, s)
                resetSide(s)
            }
            return false
        }
        s.zeroCount = 0

        if (rawPositions.size == 1) {
            if (!s.isTwoEvent) handleSingleTouch(side, s, rawPositions[0])
        } else if (rawPositions.size >= 2) {
            handleTwoTouch(side, s, rawPositions)
        }
        return true
    }

    private fun handleSingleTouch(side: Int, s: SideState, pos: Int) {
        val now = System.currentTimeMillis()

        if (s.downTime < 0) {
            s.downTime = now
            s.downPosition = pos
            s.lastPosition = pos
            Log.d(TAG, "side=$side DOWN pos=$pos")
            return
        }

        if (!s.isLongPressed && !s.isSlide
            && Math.abs(pos - s.downPosition) <= SLIP_OFFSET
            && now - s.downTime > LONG_PRESS_TIMEOUT_MS
        ) {
            s.isLongPressed = true
            emitGesture(side, "longPress", pos)
        }

        if (Math.abs(pos - s.lastPosition) > SLIP_OFFSET || s.isSlide) {
            s.isSlide = true
            s.lastPosition = pos
        }
    }

    private fun handleTwoTouch(side: Int, s: SideState, positions: IntArray) {
        s.isTwoEvent = true
        val now = System.currentTimeMillis()

        if (s.twoDownTime < 0) {
            s.twoDownTime = now
            s.twoDownPositions = positions.copyOf()
            if (s.downTime < 0) s.downTime = now
            
            
            emitGesture(side, "twoDown", positions[0])
            return
        }

        val lastTwo = s.twoDownPositions ?: return
        if (Math.abs(positions[0] - lastTwo[0]) <= doubleSlipOffset
            && Math.abs(positions[1] - lastTwo[1]) <= doubleSlipOffset
        ) {
            s.isTwoMove = false
            
            if (!s.isTwoLongPressed && now - s.twoDownTime > LONG_PRESS_TIMEOUT_MS) {
                s.isTwoLongPressed = true
                emitGesture(side, "twoLongPress", positions[0])
            }
        } else {
            s.isTwoMove = true
        }
    }

    private fun handleUp(side: Int, s: SideState) {
        val now = System.currentTimeMillis()
        val elapsed = if (s.downTime >= 0) now - s.downTime else -1
        Log.d(TAG, "side=$side UP downPos=${s.downPosition} lastPos=${s.lastPosition} isSlide=${s.isSlide} isLong=${s.isLongPressed} isTwoEvent=${s.isTwoEvent} elapsed=${elapsed}ms")

        if (s.isTwoEvent) {
            
            
            if (!s.isTwoMove && s.downTime >= 0 && now - s.downTime <= PRESS_TIMEOUT_MS) {
                emitGesture(side, "twoTap", -1)
            } else if (s.isTwoLongPressed) {
                emitGesture(side, "twoLongPressEnd", -1)
            }
            
            emitGesture(side, "twoUp", -1)
            return
        }

        if (s.downTime < 0) return

        if (s.isSlide) {
            val distance = s.lastPosition - s.downPosition
            val direction = if (distance > 0) "slideDown" else "slideUp"
            if (elapsed <= PRESS_TIMEOUT_MS && s.lastPosition >= 0 && s.downPosition >= 0) {
                val speed = if (elapsed > 0) (distance * 1000 / elapsed).toInt() else 0
                if (Math.abs(speed) >= FAST_SWIPE_MIN_SPEED) {
                    val fastDir = if (speed > 0) "fastSwipeDown" else "fastSwipeUp"
                    emitGesture(side, fastDir, s.lastPosition, speed, distance)
                    return
                }
            }
            emitGesture(side, direction, s.lastPosition, 0, distance)
        } else if (!s.isLongPressed && elapsed <= PRESS_TIMEOUT_MS) {
            emitGesture(side, "tap", s.downPosition)
        }
    }

    private fun resetSide(s: SideState) {
        s.downTime = -1
        s.downPosition = -1
        s.lastPosition = -1
        s.isSlide = false
        s.isLongPressed = false
        s.twoDownTime = -1
        s.twoDownPositions = null
        s.isTwoEvent = false
        s.isTwoLongPressed = false
        s.isTwoMove = false
        s.zeroCount = 0
    }

    private fun readSysfs(path: String): Long = try {
        BufferedReader(FileReader(path)).use { reader ->
            java.lang.Long.parseLong(reader.readLine()?.trim() ?: "0", 16)
        }
    } catch (_: Exception) {
        0L
    }

    private fun parsePositions(packed: Long): IntArray? {
        if (packed == 0L) return null
        val positions = mutableListOf<Int>()
        var remaining = packed
        while (remaining > 0) {
            var pos = (remaining and 0xFF).toInt()
            if (pos > 0) {
                if (isA5X2) pos = SLIDER_LENGTH - pos
                positions.add(pos)
            }
            remaining = remaining shr 8
        }
        return if (positions.isEmpty()) null else positions.toIntArray()
    }

    private fun emitGesture(
        side: Int,
        gesture: String,
        position: Int,
        speed: Int = 0,
        distance: Int = 0,
    ) {
        Log.i(TAG, "gesture=$gesture side=$side pos=$position speed=$speed dist=$distance")
        
        InputArbiter.onSliderActivity()
        InputRouter.postSlider(gesture, side)
    }
}
