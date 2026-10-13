package me.laumss.mosaic

import android.graphics.Path

/** First offset skips original samples; subsequent offsets delimit closed contours. */
object ContourGeometry {
    fun containsOrNear(points: FloatArray, offsets: IntArray, x: Float, y: Float, pad: Float): Boolean {
        var inside = false
        var start = offsets[0]
        for (end in offsets.drop(1)) {
            var previous = end - 1
            for (i in start until end) {
                val ax = points[previous * 2]; val ay = points[previous * 2 + 1]
                val bx = points[i * 2]; val by = points[i * 2 + 1]
                val dx = bx - ax; val dy = by - ay
                val d2 = dx * dx + dy * dy
                val t = if (d2 == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / d2).coerceIn(0f, 1f)
                val ex = x - ax - t * dx; val ey = y - ay - t * dy
                if (ex * ex + ey * ey <= pad * pad) return true
                if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside
                previous = i
            }
            start = end
        }
        return inside
    }

    fun validate(ends: IntArray, pointCount: Int) {
        if (ends.isEmpty()) return
        require(ends.size >= 2 && ends[0] in 0 until pointCount) { "Invalid contour start" }
        var previous = ends[0]
        for (end in ends.drop(1)) {
            require(end > previous && end <= pointCount) { "Invalid contour boundary" }
            previous = end
        }
        require(previous == pointCount) { "Incomplete contour geometry" }
    }

    fun path(points: FloatArray, ends: IntArray): Path {
        validate(ends, points.size / 2)
        return Path().apply {
            fillType = Path.FillType.EVEN_ODD
            var start = ends[0]
            for (end in ends.drop(1)) {
                moveTo(points[start * 2], points[start * 2 + 1])
                for (i in start + 1 until end) lineTo(points[i * 2], points[i * 2 + 1])
                close()
                start = end
            }
        }
    }
}
