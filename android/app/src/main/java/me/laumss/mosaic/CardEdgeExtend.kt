package me.laumss.mosaic

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 在卡片边缘画一个方括号（两条腿从边上伸出去，外侧一条横线），把卡片朝那一侧加宽或加高。
 *
 *   卡片
 *   ──────
 *   |    |
 *   ──────   ← 卡片新的下边
 *
 * 两条腿可以伸进卡片里不少，也可以差一点没碰到边。
 */
object CardEdgeExtend {

    enum class Side { LEFT, TOP, RIGHT, BOTTOM }

    class Match(val card: BoardEngine.CardRec, val side: Side, val depth: Float, val rect: RectF, val metrics: String)

    /** [match] 为空时，[note] 是最接近成功的那次尝试为什么没通过，供日志调参；不像方括号的笔迹没有 note。 */
    class Result(val match: Match?, val note: String?)

    // 以下单位都是屏幕 dp，按缩放换成世界单位。
    // 腿伸进卡片的最大深度：卡片在这个方向尺寸的 40%，限制在 24 到 72 之间。
    private const val INSIDE_RATIO = 0.4f
    private const val INSIDE_MIN_DP = 24f
    private const val INSIDE_MAX_DP = 72f
    // 腿没碰到边时，离边最远多少。
    private const val OUTSIDE_DP = 16f
    // 腿的位置可以超出卡片两端多少。
    private const val ALONG_DP = 24f
    private const val MIN_DEPTH_DP = 16f
    // 横线比两条腿的端点至少再往外多少。
    private const val MIN_RISE_DP = 12f
    private const val MIN_SPAN_DP = 32f
    private const val MIN_SPAN_RATIO = 0.3f

    // 中段（第一次到第二次越过半深度之间）不能折回卡片：最低点至少在 1/4 深度。
    private const val MID_BAND_RATIO = 0.5f
    private const val MID_FLOOR_RATIO = 0.25f
    // 外侧横线覆盖括号宽度的比例。
    private const val FAR_BAND_RATIO = 0.6f
    private const val FAR_COVERAGE_RATIO = 0.6f
    private const val FAR_DEPTH_RATIO = 0.75f
    // 笔迹总长不能远超一个方括号，排除涂写。
    private const val LENGTH_RATIO = 1.6f
    private const val LENGTH_SLACK_DP = 24f
    private const val DEEP_INSIDE_RATIO = 0.1f

    // 失败阶段，越大越接近成功。
    private const val STAGE_ENDS = 1
    private const val STAGE_DEPTH = 2
    private const val STAGE_SPAN = 3
    private const val STAGE_MID = 4
    private const val STAGE_FAR = 5
    private const val STAGE_LENGTH = 6
    private const val STAGE_INSIDE = 7

    /** [points] 是世界坐标 x,y 交替；[cards] 按 z 从高到低。 */
    fun recognize(points: FloatArray, cards: List<BoardEngine.CardRec>, scale: Float): Result {
        val n = points.size / 2
        if (n < 4) return Result(null, null)
        val s = max(0.01f, scale)
        val outTol = OUTSIDE_DP / s
        val alongTol = ALONG_DP / s
        val sx = points[0]; val sy = points[1]
        val ex = points[points.size - 2]; val ey = points[points.size - 1]
        val length = pathLength(points)

        var best: Match? = null
        var bestError = Float.MAX_VALUE
        var closestStage = 0
        var closestNote: String? = null
        val r = RectF()
        for (card in cards) {
            if (card.kind == "image" || card.kind == "note") continue
            card.rect(r)
            val reach = INSIDE_MAX_DP / s + outTol + alongTol
            if (!near(r, sx, sy, reach) || !near(r, ex, ey, reach)) continue
            for (side in Side.values()) {
                val c = Candidate(card, side, points, s, outTol, alongTol, length)
                if (c.stage > closestStage) {
                    closestStage = c.stage
                    closestNote = "card=${card.id} side=$side ${c.describe()}"
                }
                val match = c.match ?: continue
                if (c.error < bestError) {
                    best = match
                    bestError = c.error
                }
            }
        }
        return if (best != null) Result(best, null)
        // 只为伸出卡片又回来的笔迹留原因；卡片边上的普通手写到 depth 就停了，不记。
        else Result(null, if (closestStage >= STAGE_SPAN) closestNote else null)
    }

    private class Candidate(
        val card: BoardEngine.CardRec,
        val side: Side,
        points: FloatArray,
        val scale: Float,
        outTol: Float,
        alongTol: Float,
        val length: Float,
    ) {
        var stage = 0
        var reason = ""
        var match: Match? = null
        var error = Float.MAX_VALUE
        private var oStart = 0f
        private var oEnd = 0f
        private var depth = 0f
        private var span = 0f
        private var inTol = 0f

        init {
            evaluate(points, outTol, alongTol)
        }

        private fun fail(stage: Int, reason: String) {
            this.stage = stage
            this.reason = reason
        }

        private fun evaluate(points: FloatArray, outTol: Float, alongTol: Float) {
            val n = points.size / 2
            val across = if (side == Side.TOP || side == Side.BOTTOM) card.height else card.width
            inTol = (across * INSIDE_RATIO).coerceIn(INSIDE_MIN_DP / scale, INSIDE_MAX_DP / scale)
            val (t0, t1) = edgeRange(card, side)
            val last = points.size - 2
            oStart = outward(card, side, points[0], points[1])
            oEnd = outward(card, side, points[last], points[last + 1])
            val tStart = along(side, points[0], points[1])
            val tEnd = along(side, points[last], points[last + 1])
            if (oStart < -inTol || oStart > outTol || oEnd < -inTol || oEnd > outTol ||
                tStart < t0 - alongTol || tStart > t1 + alongTol || tEnd < t0 - alongTol || tEnd > t1 + alongTol
            ) return fail(STAGE_ENDS, "ends")

            val o = FloatArray(n)
            val t = FloatArray(n)
            var maxOut = -Float.MAX_VALUE
            for (k in 0 until n) {
                o[k] = outward(card, side, points[2 * k], points[2 * k + 1])
                t[k] = along(side, points[2 * k], points[2 * k + 1])
                if (o[k] > maxOut) maxOut = o[k]
            }
            depth = maxOut
            if (maxOut < MIN_DEPTH_DP / scale || maxOut - max(oStart, oEnd) < MIN_RISE_DP / scale) return fail(STAGE_DEPTH, "depth")

            span = abs(tEnd - tStart)
            if (span < max(MIN_SPAN_DP / scale, (t1 - t0) * MIN_SPAN_RATIO)) return fail(STAGE_SPAN, "span")

            // 出去以后不能半路折回卡片。
            var first = -1
            var lastFar = -1
            for (k in 0 until n) if (o[k] >= maxOut * MID_BAND_RATIO) { if (first < 0) first = k; lastFar = k }
            var midFloor = Float.MAX_VALUE
            for (k in first..lastFar) midFloor = min(midFloor, o[k])
            if (midFloor < maxOut * MID_FLOOR_RATIO) return fail(STAGE_MID, "returns-inside")

            var farMin = Float.MAX_VALUE
            var farMax = -Float.MAX_VALUE
            val far = ArrayList<Float>()
            for (k in 0 until n) {
                if (o[k] >= maxOut * FAR_BAND_RATIO) {
                    farMin = min(farMin, t[k])
                    farMax = max(farMax, t[k])
                }
                if (o[k] >= maxOut * FAR_DEPTH_RATIO) far.add(o[k])
            }
            if (farMax - farMin < span * FAR_COVERAGE_RATIO) return fail(STAGE_FAR, "far-line")

            val legsInside = max(0f, -oStart) + max(0f, -oEnd)
            val expected = 2f * maxOut + span + legsInside
            if (length > LENGTH_RATIO * expected + LENGTH_SLACK_DP / scale) return fail(STAGE_LENGTH, "too-long")

            var deep = 0
            for (k in 0 until n) if (o[k] < -inTol) deep++
            if (deep > n * DEEP_INSIDE_RATIO) return fail(STAGE_INSIDE, "deep-inside")

            far.sort()
            val extend = far[far.size / 2]
            val rect = extendedRect(card, side, extend) ?: return fail(STAGE_INSIDE, "max-size")
            stage = Int.MAX_VALUE
            error = (abs(oStart) + abs(oEnd)) / inTol
            match = Match(card, side, extend, rect, describe())
        }

        fun describe(): String {
            val k = scale
            return "reason=${reason.ifEmpty { "ok" }} startOut=${dp(oStart, k)} endOut=${dp(oEnd, k)} " +
                "insideTol=${dp(inTol, k)} depth=${dp(depth, k)} span=${dp(span, k)} length=${dp(length, k)} (dp)"
        }
    }

    private fun dp(world: Float, scale: Float): Int = (world * scale).toInt()

    private fun near(r: RectF, x: Float, y: Float, reach: Float): Boolean =
        x >= r.left - reach && x <= r.right + reach && y >= r.top - reach && y <= r.bottom + reach

    private fun pathLength(points: FloatArray): Float {
        var total = 0f
        var i = 2
        while (i < points.size) {
            total += hypot(points[i] - points[i - 2], points[i + 1] - points[i - 1])
            i += 2
        }
        return total
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

    /** 往外长的那条边停在挡路卡片前 [gap] 处；挡路卡片是和原卡片不相交、但和扩充后相交的卡片。 */
    fun clampToBlockers(card: BoardEngine.CardRec, side: Side, grown: RectF, others: Collection<BoardEngine.CardRec>, gap: Float): RectF {
        val old = card.rect(RectF())
        val out = RectF(grown)
        val o = RectF()
        for (other in others) {
            if (other.id == card.id) continue
            other.rect(o)
            if (RectF.intersects(old, o) || !RectF.intersects(out, o)) continue
            when (side) {
                Side.RIGHT -> out.right = max(old.right, min(out.right, o.left - gap))
                Side.LEFT -> out.left = min(old.left, max(out.left, o.right + gap))
                Side.BOTTOM -> out.bottom = max(old.bottom, min(out.bottom, o.top - gap))
                Side.TOP -> out.top = min(old.top, max(out.top, o.bottom + gap))
            }
        }
        return out
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
