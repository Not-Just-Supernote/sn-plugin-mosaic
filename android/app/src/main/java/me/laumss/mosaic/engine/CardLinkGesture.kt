package me.laumss.mosaic.engine

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Distinguishes a deliberate pull-out line from handwriting during a continuous writing session. */
internal object CardLinkGesture {
    fun isStraightLine(points: FloatArray, viewportScale: Float): Boolean {
        if (points.size < 4 || points.size % 2 != 0 || points.any { !it.isFinite() }) return false
        val sx = points[0]
        val sy = points[1]
        val dx = points[points.size - 2] - sx
        val dy = points[points.size - 1] - sy
        val distance = hypot(dx, dy)
        if (distance <= 0f) return false
        val scale = if (viewportScale.isFinite()) max(0.01f, viewportScale) else 1f
        val tolerance = max(6f / scale, distance * 0.10f)
        var length = 0f
        for (i in 2 until points.size step 2) {
            length += hypot(points[i] - points[i - 2], points[i + 1] - points[i - 1])
            val deviation = abs(dx * (points[i + 1] - sy) - dy * (points[i] - sx)) / distance
            if (deviation > tolerance) return false
        }
        return distance >= length * 0.90f
    }
}
