package me.laumss.mosaic

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min


object NeckGeometry {

    private const val CORNER_RADIUS = 15f
    private const val FOOTPRINT_DISTANCE = 80f
    private const val SHRINK_CLAMP_MIN = 0.01f
    private const val SHRINK_CLAMP_MAX = 0.4f
    private const val HANDLE_DISTANCE = 100f
    private const val HANDLE_SCALE_NEAR = 0.25f
    private const val TRANSITION_RAMP = 5f
    private const val INSET = 2f
    private const val EPS = 1e-7f

    
    const val MAX_DISTANCE = 160f

    
    const val RECONNECT_DISTANCE = BoardGeometry.CONNECT_DISTANCE

    private class V(val x: Float, val y: Float) {
        operator fun plus(o: V) = V(x + o.x, y + o.y)
        operator fun minus(o: V) = V(x - o.x, y - o.y)
        operator fun times(k: Float) = V(x * k, y * k)
        operator fun unaryMinus() = V(-x, -y)
        val isZero: Boolean get() = x == 0f && y == 0f
    }

    private class Exit(val point: V, val edge: Int, val s: Float)

    private class Anchor(val point: V, val dir: V)

    private fun len(v: V): Float = hypot(v.x, v.y)

    private fun normalize(v: V): V {
        val l = len(v)
        return if (l > EPS) V(v.x / l, v.y / l) else V(0f, 0f)
    }

    private fun dist(a: V, b: V): Float = hypot(a.x - b.x, a.y - b.y)

    private fun center(r: RectF) = V(r.centerX(), r.centerY())

    
    fun signedEdgeDistance(a: RectF, b: RectF): Float {
        val dx = max(0f, max(a.left - b.right, b.left - a.right))
        val dy = max(0f, max(a.top - b.bottom, b.top - a.bottom))
        if (dx > 0f || dy > 0f) return hypot(dx, dy)
        val overlapX = min(a.right, b.right) - max(a.left, b.left)
        val overlapY = min(a.bottom, b.bottom) - max(a.top, b.top)
        return -min(overlapX, overlapY)
    }

    
    private fun edgeStart(r: RectF, edge: Int): V = when (edge) {
        0 -> V(r.left, r.top)
        1 -> V(r.right, r.top)
        2 -> V(r.right, r.bottom)
        else -> V(r.left, r.bottom)
    }

    private fun edgeEnd(r: RectF, edge: Int): V = when (edge) {
        0 -> V(r.right, r.top)
        1 -> V(r.right, r.bottom)
        2 -> V(r.left, r.bottom)
        else -> V(r.left, r.top)
    }

    
    private fun rayExit(r: RectF, from: V, dir: V): Exit {
        var s = Float.POSITIVE_INFINITY
        var edge = 0
        if (dir.x > 1e-9f) {
            val t = (r.right - from.x) / dir.x
            if (t < s) { s = t; edge = 1 }
        } else if (dir.x < -1e-9f) {
            val t = (r.left - from.x) / dir.x
            if (t < s) { s = t; edge = 3 }
        }
        if (dir.y > 1e-9f) {
            val t = (r.bottom - from.y) / dir.y
            if (t < s) { s = t; edge = 2 }
        } else if (dir.y < -1e-9f) {
            val t = (r.top - from.y) / dir.y
            if (t < s) { s = t; edge = 0 }
        }
        return Exit(V(from.x + dir.x * s, from.y + dir.y * s), edge, s)
    }

    
    private fun wallAnchor(r: RectF, exit: Exit, sameEdge: Boolean, wrapEnd: Boolean): Anchor {
        val radius = min(CORNER_RADIUS, min(r.width() / 2f, r.height() / 2f))
        val start = edgeStart(r, exit.edge)
        val end = edgeEnd(r, exit.edge)
        val edgeLen = dist(start, end)
        val own = if (wrapEnd) end else start
        val other = if (wrapEnd) start else end
        val uOwnInto = normalize(own - other)
        if (sameEdge) {
            return Anchor(own - uOwnInto * radius, -uOwnInto)
        }
        val t = if (edgeLen > EPS) dist(exit.point, other) / edgeLen else 0f
        val blend = min(1f, TRANSITION_RAMP * t)
        val d = radius + (edgeLen - 2f * radius) * blend
        return Anchor(other + uOwnInto * d, -uOwnInto)
    }

    
    private fun insetTowardCenter(r: RectF, p: V): V {
        val inward = normalize(center(r) - p)
        return p + inward * INSET
    }

    
    fun build(a: RectF, b: RectF, maxDistance: Float = MAX_DISTANCE): Path? {
        val centerA = center(a)
        val centerB = center(b)
        val axis = normalize(centerB - centerA)
        if (axis.isZero) return null
        val perpendicular = V(-axis.y, axis.x)

        val gap = max(0f, signedEdgeDistance(a, b))
        if (gap > maxDistance) return null

        
        val shrink = 1f - (gap / FOOTPRINT_DISTANCE).coerceIn(SHRINK_CLAMP_MIN, SHRINK_CLAMP_MAX)
        fun guide(r: RectF, c: V, side: Float): V {
            val dir = perpendicular * side
            val s = rayExit(r, c, dir).s * shrink
            return c + dir * s
        }
        val guideALow = guide(a, centerA, -1f)
        val guideAHigh = guide(a, centerA, 1f)
        val guideBLow = guide(b, centerB, -1f)
        val guideBHigh = guide(b, centerB, 1f)

        
        val wallLowDir = normalize(guideBLow - guideALow)
        val wallHighDir = normalize(guideBHigh - guideAHigh)
        if (wallLowDir.isZero || wallHighDir.isZero) return null
        val exitALow = rayExit(a, guideALow, wallLowDir)
        val exitBLow = rayExit(b, guideBLow, -wallLowDir)
        val exitAHigh = rayExit(a, guideAHigh, wallHighDir)
        val exitBHigh = rayExit(b, guideBHigh, -wallHighDir)

        
        val sameEdgeA = exitALow.edge == exitAHigh.edge
        val sameEdgeB = exitBLow.edge == exitBHigh.edge
        val anchorALow = wallAnchor(a, exitALow, sameEdgeA, wrapEnd = false)
        val anchorAHigh = wallAnchor(a, exitAHigh, sameEdgeA, wrapEnd = true)
        val anchorBLow = wallAnchor(b, exitBLow, sameEdgeB, wrapEnd = true)
        val anchorBHigh = wallAnchor(b, exitBHigh, sameEdgeB, wrapEnd = false)

        val aLow = insetTowardCenter(a, anchorALow.point)
        val aHigh = insetTowardCenter(a, anchorAHigh.point)
        val bLow = insetTowardCenter(b, anchorBLow.point)
        val bHigh = insetTowardCenter(b, anchorBHigh.point)

        
        
        fun orderByPerpendicular(p: V, q: V): Array<V> {
            val pProjection = p.x * perpendicular.x + p.y * perpendicular.y
            val qProjection = q.x * perpendicular.x + q.y * perpendicular.y
            return if (pProjection <= qProjection) arrayOf(p, q) else arrayOf(q, p)
        }
        val orderedA = orderByPerpendicular(aLow, aHigh)
        val orderedB = orderByPerpendicular(bLow, bHigh)
        val aLowFinal = orderedA[0]
        val aHighFinal = orderedA[1]
        val bLowFinal = orderedB[0]
        val bHighFinal = orderedB[1]

        
        
        
        
        val wallT = lerp(HANDLE_SCALE_NEAR, 0.42f, min(1f, gap / HANDLE_DISTANCE))
        val cpALow = aLowFinal + (bLowFinal - aLowFinal) * wallT
        val cpBLow = bLowFinal + (aLowFinal - bLowFinal) * wallT
        val cpAHigh = aHighFinal + (bHighFinal - aHighFinal) * wallT
        val cpBHigh = bHighFinal + (aHighFinal - bHighFinal) * wallT

        
        
        val chordA = dist(aHighFinal, aLowFinal)
        val chordB = dist(bHighFinal, bLowFinal)
        val wallLowLength = dist(aLowFinal, bLowFinal)
        val wallHighLength = dist(aHighFinal, bHighFinal)
        val wallTotal = max(1e-6f, wallLowLength + wallHighLength)
        val lowRoundness = 0.78f + 0.44f * (wallHighLength / wallTotal)
        val highRoundness = 0.78f + 0.44f * (wallLowLength / wallTotal)
        val capLow = min(CORNER_RADIUS * 1.35f * lowRoundness, chordB * 0.32f)
        val capHigh = min(CORNER_RADIUS * 1.35f * highRoundness, chordB * 0.32f)
        
        
        
        
        val bCapDir = normalize(bHighFinal - bLowFinal)
        val capCpLow = bLowFinal + bCapDir * capLow
        val capCpHigh = bHighFinal - bCapDir * capHigh
        val aCap = min(CORNER_RADIUS * 1.35f, chordA * 0.32f)
        val aCapDir = normalize(aLowFinal - aHighFinal)
        val aCapCpHigh = aHighFinal + aCapDir * aCap
        val aCapCpLow = aLowFinal - aCapDir * aCap

        val path = Path()
        path.moveTo(aLowFinal.x, aLowFinal.y)
        path.cubicTo(cpALow.x, cpALow.y, cpBLow.x, cpBLow.y, bLowFinal.x, bLowFinal.y)
        path.cubicTo(capCpLow.x, capCpLow.y, capCpHigh.x, capCpHigh.y, bHighFinal.x, bHighFinal.y)
        path.cubicTo(cpBHigh.x, cpBHigh.y, cpAHigh.x, cpAHigh.y, aHighFinal.x, aHighFinal.y)
        path.cubicTo(aCapCpHigh.x, aCapCpHigh.y, aCapCpLow.x, aCapCpLow.y, aLowFinal.x, aLowFinal.y)
        path.close()
        if (!pathIsFinite(aLowFinal, bLowFinal, bHighFinal, aHighFinal)) return null
        return path
    }

    private fun pathIsFinite(vararg pts: V): Boolean = pts.all { it.x.isFinite() && it.y.isFinite() }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
