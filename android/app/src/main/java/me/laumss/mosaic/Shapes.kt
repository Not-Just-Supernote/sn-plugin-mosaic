package me.laumss.mosaic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt


object Shapes {

    enum class Kind { LINE, RECT, TRIANGLE, ELLIPSE }

    
    const val PRESSURE = 0.6f
    const val ELLIPSE_SAMPLES = 64

    fun pressures(pointCount: Int): FloatArray = FloatArray(pointCount) { PRESSURE }

    

    fun kindOf(stroke: BoardEngine.StrokeRec): Kind? = kindOf(stroke.points, stroke.pressures)

    fun kindOf(points: FloatArray, pressures: FloatArray): Kind? {
        val n = points.size / 2
        if (n < 2 || pressures.size != n) return null
        for (p in pressures) if (p != PRESSURE) return null
        if (n == 2) return Kind.LINE
        if (!closed(points)) return null
        return when (n) {
            4 -> Kind.TRIANGLE
            5 -> Kind.RECT
            ELLIPSE_SAMPLES + 1 -> Kind.ELLIPSE
            else -> null
        }
    }

    private fun closed(points: FloatArray): Boolean {
        val n = points.size / 2
        return n >= 2 && points[0] == points[(n - 1) * 2] && points[1] == points[(n - 1) * 2 + 1]
    }

    

    
    fun fromDrag(kind: Kind, x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
        if (kind == Kind.LINE) return floatArrayOf(x0, y0, x1, y1)
        val l = min(x0, x1); val r = max(x0, x1)
        val t = min(y0, y1); val b = max(y0, y1)
        return when (kind) {
            Kind.RECT -> floatArrayOf(l, t, r, t, r, b, l, b, l, t)
            Kind.TRIANGLE -> triangleAlong(x0, y0, x1, y1)
            Kind.ELLIPSE -> ellipseAround((l + r) / 2f, (t + b) / 2f, (r - l) / 2f, (b - t) / 2f)
            Kind.LINE -> floatArrayOf(x0, y0, x1, y1)
        }
    }

    
    private fun triangleAlong(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
        val dx = x1 - x0; val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len <= 1e-3f) return floatArrayOf(x0, y0, x1, y1, x1, y1, x0, y0)
        
        val hx = -dy / 2f; val hy = dx / 2f
        return floatArrayOf(x0, y0, x1 + hx, y1 + hy, x1 - hx, y1 - hy, x0, y0)
    }

    private fun ellipseAround(cx: Float, cy: Float, rx: Float, ry: Float): FloatArray {
        val out = FloatArray((ELLIPSE_SAMPLES + 1) * 2)
        for (i in 0 until ELLIPSE_SAMPLES) {
            val a = 2.0 * PI * i / ELLIPSE_SAMPLES - PI / 2
            out[i * 2] = cx + (rx * cos(a)).toFloat()
            out[i * 2 + 1] = cy + (ry * sin(a)).toFloat()
        }
        out[ELLIPSE_SAMPLES * 2] = out[0]
        out[ELLIPSE_SAMPLES * 2 + 1] = out[1]
        return out
    }

    

    
    fun toSquare(rect: FloatArray): FloatArray {
        val cx = (rect[0] + rect[2] + rect[4] + rect[6]) / 4f
        val cy = (rect[1] + rect[3] + rect[5] + rect[7]) / 4f
        var e1x = rect[2] - rect[0]; var e1y = rect[3] - rect[1]
        var e2x = rect[6] - rect[0]; var e2y = rect[7] - rect[1]
        val l1 = hypot(e1x, e1y); val l2 = hypot(e2x, e2y)
        if (l1 <= 1e-3f || l2 <= 1e-3f) return rect
        
        val cross = e1x * e2y - e1y * e2x
        e1x /= l1; e1y /= l1
        val sgn = if (cross >= 0f) 1f else -1f
        e2x = -e1y * sgn; e2y = e1x * sgn
        val h = (l1 + l2) / 4f
        val c0x = cx - e1x * h - e2x * h; val c0y = cy - e1y * h - e2y * h
        val c1x = c0x + e1x * 2f * h; val c1y = c0y + e1y * 2f * h
        val c2x = c1x + e2x * 2f * h; val c2y = c1y + e2y * 2f * h
        val c3x = c0x + e2x * 2f * h; val c3y = c0y + e2y * 2f * h
        return floatArrayOf(c0x, c0y, c1x, c1y, c2x, c2y, c3x, c3y, c0x, c0y)
    }

    
    fun toCircle(ellipsePts: FloatArray): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < ellipsePts.size) {
            val x = ellipsePts[i]; val y = ellipsePts[i + 1]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            i += 2
        }
        val r = ((maxX - minX) + (maxY - minY)) / 4f
        return ellipseAround((minX + maxX) / 2f, (minY + maxY) / 2f, r, r)
    }

    enum class TriangleForm { ISOSCELES, EQUILATERAL, RIGHT }

    
    fun toTriangle(tri: FloatArray, form: TriangleForm): FloatArray {
        if (tri.size < 6) return tri
        
        
        
        
        val legacy = tri[1] == tri[3] && tri[0] < tri[2] && tri[5] < tri[1] && tri[4] != tri[2]
        val apexIndex = if (legacy) 2 else 0
        val ax = tri[apexIndex * 2]; val ay = tri[apexIndex * 2 + 1]
        val b1x = tri[((apexIndex + 1) % 3) * 2]; val b1y = tri[((apexIndex + 1) % 3) * 2 + 1]
        val b2x = tri[((apexIndex + 2) % 3) * 2]; val b2y = tri[((apexIndex + 2) % 3) * 2 + 1]
        val ex = b2x - b1x; val ey = b2y - b1y
        val len = hypot(ex, ey)
        if (len <= 1e-3f) return tri
        val mx = (b1x + b2x) / 2f; val my = (b1y + b2y) / 2f
        
        var nx = -ey / len; var ny = ex / len
        var h = (ax - mx) * nx + (ay - my) * ny
        if (h < 0f) { nx = -nx; ny = -ny; h = -h }
        if (h <= 1e-3f) h = len / 2f
        val (px, py) = when (form) {
            TriangleForm.ISOSCELES -> mx + nx * h to my + ny * h
            TriangleForm.EQUILATERAL -> {
                val eh = len * sqrt(3f) / 2f
                mx + nx * eh to my + ny * eh
            }
            TriangleForm.RIGHT -> {
                val t = ((ax - b1x) * ex + (ay - b1y) * ey) / (len * len)
                if (t < 0.5f) b1x + nx * h to b1y + ny * h else b2x + nx * h to b2y + ny * h
            }
        }
        return floatArrayOf(px, py, b1x, b1y, b2x, b2y, px, py)
    }

    

    fun rotate(pts: FloatArray, radians: Float, cx: Float, cy: Float): FloatArray {
        val c = cos(radians); val s = sin(radians)
        val out = FloatArray(pts.size)
        var i = 0
        while (i < pts.size) {
            val x = pts[i] - cx; val y = pts[i + 1] - cy
            out[i] = cx + x * c - y * s
            out[i + 1] = cy + x * s + y * c
            i += 2
        }
        
        if (closed(pts) && pts.size >= 4) { out[out.size - 2] = out[0]; out[out.size - 1] = out[1] }
        return out
    }
}
