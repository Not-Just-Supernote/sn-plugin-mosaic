package me.laumss.mosaic

import android.util.Log
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot


object TwoFingerToolGuard {

    private const val TAG = "MosaicTwoFinger"

    
    const val EVENT_TIME_DEV_MS = 500L
    
    const val EVENT_DISTANCE_MAX_PX = 600f
    
    const val EVENT_DISTANCE_MOVE_PX = 100f
    
    private const val SECTOR_HALF_ANGLE_DEG = 75.0
    
    private const val ERASER_RECT_SIZE = 300f

    class Decision(val accept: Boolean, val reason: String)

    
    fun evaluate(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        penX: Float?, penY: Float?,
        tilt: FloatArray?,
        leftHand: Boolean,
        screenW: Float, screenH: Float,
    ): Decision {
        val distance = hypot(x2 - x1, y2 - y1)
        if (distance >= EVENT_DISTANCE_MAX_PX) return Decision(false, "distance=$distance")
        
        
        if (penX == null || penY == null || tilt == null) return Decision(false, "pen-far")

        val in1 = inSector(penX, penY, tilt, x1, y1)
        val in2 = inSector(penX, penY, tilt, x2, y2)
        Log.i(TAG, "isFingerInPenPolygon $in1 $in2 pen=($penX,$penY) tilt=(${tilt[0]},${tilt[1]}) hand=${if (leftHand) "left" else "right"}")
        if (!in1 && !in2) return Decision(true, "outside-sector")
        val special = checkSpecialScenario(in1, in2, x1, y1, x2, y2, penX, penY, leftHand, screenW, screenH)
        return if (special != null) Decision(true, special) else Decision(false, "inside-sector")
    }

    private fun inSector(px: Float, py: Float, tilt: FloatArray, x: Float, y: Float): Boolean {
        val dx = x - px
        val dy = y - py
        val len = hypot(dx, dy)
        if (len < 1f) return true
        val cos = ((dx * tilt[0] + dy * tilt[1]) / len).coerceIn(-1f, 1f)
        val angle = Math.toDegrees(acos(cos.toDouble()))
        return angle <= SECTOR_HALF_ANGLE_DEG
    }

    
    private fun checkSpecialScenario(
        in1: Boolean, in2: Boolean,
        x1: Float, y1: Float, x2: Float, y2: Float,
        penX: Float, penY: Float,
        leftHand: Boolean,
        screenW: Float, screenH: Float,
    ): String? {
        if (!leftHand) {
            
            if (((penX - x1) >= ERASER_RECT_SIZE && penX > x2) || ((penX - x2) >= ERASER_RECT_SIZE && penX > x1)) {
                return "fingers-left-of-pen"
            }
            if (in1 != in2) {
                
                if ((penX > x1 || penX > x2) && (penY > y1 || penY > y2)) return "finger-top-left-of-pen"
            }
        } else {
            if (in1 != in2) {
                if (x1 > penX || x2 > penX) return "finger-right-of-pen"
            }
        }

        
        val left = if (leftHand) screenW - ERASER_RECT_SIZE else 0f
        val right = if (leftHand) screenW else ERASER_RECT_SIZE
        val inStrip = x1 >= left && x1 <= right && x2 >= left && x2 <= right &&
            y1 >= 0f && y1 <= screenH && y2 >= 0f && y2 <= screenH
        if (inStrip) {
            if (!leftHand && x1 > penX && x2 > penX &&
                (y1 - penY) < ERASER_RECT_SIZE / 2f && (y2 - penY) < ERASER_RECT_SIZE / 2f
            ) {
                return null
            }
            return "edge-strip"
        }
        return null
    }

    
    fun moved(startX: Float, startY: Float, x: Float, y: Float): Boolean =
        abs(x - startX) > EVENT_DISTANCE_MOVE_PX || abs(y - startY) > EVENT_DISTANCE_MOVE_PX
}
