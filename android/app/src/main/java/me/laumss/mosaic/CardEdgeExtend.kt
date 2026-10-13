package me.laumss.mosaic

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 在卡片边缘画一个方括号（两条腿落在边上，外侧一条横线），把卡片朝那一侧加宽或加高。
 *
 *   卡片
 *   ──────
 *   |    |
 *   ──────   ← 卡片新的下边
 */
object CardEdgeExtend {

    enum class Side { LEFT, TOP, RIGHT, BOTTOM }

    class Match(val card: BoardEngine.CardRec, val side: Side, val depth: Float, val rect: RectF)

    // 屏幕 dp，按缩放换成世界单位。
    private const val EDGE_TOLERANCE_DP = 18f
    private const val MIN_DEPTH_DP = 20f
    private const val MIN_SPAN_DP = 36f

    private const val INSIDE_SLACK_RATIO = 0.08f
    private const val OUTSIDE_RATIO = 0.6f
    private const val FAR_BAND_RATIO = 0.6f
    private const val FAR_DEPTH_RATIO = 0.75f
    private const val FAR_COVERAGE_RATIO = 0.6f

    /** [points] 是世界坐标 x,y 交替；[cards] 按 z 从高到低。 */
    fun recognize(points: FloatArray, cards: List<BoardEngine.CardRec>, scale: Float): Match? {
        val n = points.size / 2
        if (n < 4) return null
        val s = max(0.01f, scale)
        val tol = EDGE_TOLERANCE_DP / s
        val minDepth = MIN_DEPTH_DP / s
        val minSpan = MIN_SPAN_DP / s
        var best: Match? = null
        var bestError = Float.MAX_VALUE
        for (card in cards) {
            if (card.kind == "image" || card.kind == "note") continue
            for (side in Side.values()) {
                val error = endpointError(points, card, side, tol) ?: continue
                if (error >= bestError) continue
                val depth = measureDepth(points, card, side, tol, minDepth, minSpan) ?: continue
                val rect = extendedRect(card, side, depth) ?: continue
                best = Match(card, side, depth, rect)
                bestError = error
            }
        }
        return best
    }

    /** 起点和终点都落在这条边上时，返回两端离边的距离之和。 */
    private fun endpointError(points: FloatArray, card: BoardEngine.CardRec, side: Side, tol: Float): Float? {
        val last = points.size - 2
        val (t0, t1) = edgeRange(card, side)
        var error = 0f
        for (i in intArrayOf(0, last)) {
            val nOut = outward(card, side, points[i], points[i + 1])
            val t = along(side, points[i], points[i + 1])
            if (abs(nOut) > tol || t < t0 - tol || t > t1 + tol) return null
            error += abs(nOut)
        }
        return error
    }

    private fun measureDepth(
        points: FloatArray,
        card: BoardEngine.CardRec,
        side: Side,
        tol: Float,
        minDepth: Float,
        minSpan: Float,
    ): Float? {
        val n = points.size / 2
        val tStart = along(side, points[0], points[1])
        val tEnd = along(side, points[points.size - 2], points[points.size - 1])
        val span = abs(tEnd - tStart)
        if (span < minSpan) return null

        var maxOut = 0f
        var inside = 0
        var outside = 0
        var i = 0
        while (i < points.size) {
            val o = outward(card, side, points[i], points[i + 1])
            if (o > maxOut) maxOut = o
            if (o < -tol) inside++
            if (o > tol) outside++
            i += 2
        }
        if (maxOut < minDepth) return null
        // 笔迹不能伸进卡片里，大部分要在卡片外面。
        if (inside > n * INSIDE_SLACK_RATIO) return null
        if (outside < n * OUTSIDE_RATIO) return null

        // 外侧那条线要够长，才算方括号，不是 V 或一个点。
        var farMin = Float.MAX_VALUE
        var farMax = -Float.MAX_VALUE
        val far = ArrayList<Float>()
        i = 0
        while (i < points.size) {
            val o = outward(card, side, points[i], points[i + 1])
            if (o >= maxOut * FAR_BAND_RATIO) {
                val t = along(side, points[i], points[i + 1])
                farMin = min(farMin, t)
                farMax = max(farMax, t)
            }
            if (o >= maxOut * FAR_DEPTH_RATIO) far.add(o)
            i += 2
        }
        if (farMax - farMin < span * FAR_COVERAGE_RATIO) return null
        far.sort()
        return far[far.size / 2]
    }

    private fun extendedRect(card: BoardEngine.CardRec, side: Side, depth: Float): RectF? {
        val r = card.rect(RectF())
        val maxSize = BoardGeometry.MAX_CARD_SIZE
        when (side) {
            Side.LEFT -> r.left = r.right - min(maxSize, r.width() + depth)
            Side.RIGHT -> r.right = r.left + min(maxSize, r.width() + depth)
            Side.TOP -> r.top = r.bottom - min(maxSize, r.height() + depth)
            Side.BOTTOM -> r.bottom = r.top + min(maxSize, r.height() + depth)
        }
        if (r.width() <= card.width + 0.5f && r.height() <= card.height + 0.5f) return null
        return r
    }

    /** 离开卡片的距离，卡片外为正。 */
    private fun outward(card: BoardEngine.CardRec, side: Side, x: Float, y: Float): Float = when (side) {
        Side.LEFT -> card.x - x
        Side.RIGHT -> x - (card.x + card.width)
        Side.TOP -> card.y - y
        Side.BOTTOM -> y - (card.y + card.height)
    }

    private fun along(side: Side, x: Float, y: Float): Float =
        if (side == Side.TOP || side == Side.BOTTOM) x else y

    private fun edgeRange(card: BoardEngine.CardRec, side: Side): Pair<Float, Float> =
        if (side == Side.TOP || side == Side.BOTTOM) card.x to card.x + card.width
        else card.y to card.y + card.height
}
