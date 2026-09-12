package me.laumss.mosaic

import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


object CardFrameRecognizer {

    class Options(
        val viewportScale: Float = 1f,
        val minShortSidePx: Float = 60f,
        val minLongSidePx: Float = 80f,
        val maxAspectRatio: Float = 8f,
        val sampleSpacingPx: Float = 4f,
        val maxSamples: Int = 512,
        val minPerimeterCoverage: Float = 0.72f,
        val maxPerimeterCoverage: Float = 1.16f,
        val maxBacktrackRatio: Float = 0.18f,
        val minAxisAlignedRatio: Float = 0.56f,
        val minConfidence: Float = 0.70f,
    )

    class Metrics(
        val widthPx: Float,
        val heightPx: Float,
        val pathLengthPx: Float,
        val perimeterPx: Float,
        val pathToPerimeterRatio: Float,
        val edgeBandPx: Float,
        val edgeHitRatio: Float,
        val edgeDistanceP90Px: Float,
        val perimeterCoverage: Float,
        val backtrackRatio: Float,
        val sideCoverage: FloatArray,
        val sideInteriorEvidence: BooleanArray,
        val cornerEvidence: BooleanArray,
        val axisAlignedRatio: Float,
    ) {
        var confidence: Float = 0f

        fun format(): String = buildString {
            append("size=${widthPx.roundToInt()}x${heightPx.roundToInt()}")
            append(" edge=${"%.2f".format(edgeHitRatio)}")
            append(" p90=${"%.1f".format(edgeDistanceP90Px)}")
            append(" coverage=${"%.2f".format(perimeterCoverage)}")
            append(" backtrack=${"%.2f".format(backtrackRatio)}")
            append(" sides=${sideCoverage.joinToString("/") { "%.2f".format(it) }}")
            append(" interior=${sideInteriorEvidence.joinToString("") { if (it) "1" else "0" }}")
            append(" corners=${cornerEvidence.joinToString("") { if (it) "1" else "0" }}")
            append(" axis=${"%.2f".format(axisAlignedRatio)}")
            append(" path/perimeter=${"%.2f".format(pathToPerimeterRatio)}")
            append(" confidence=${"%.2f".format(confidence)}")
        }
    }

    class Evaluation(
        
        val rect: RectF?,
        val metrics: Metrics?,
        
        val reason: String?,
    )

    private class Projection(
        val distance: Float,
        val perimeterPosition: Float,
        val side: Int,
        val sideRatio: Float,
    )

    private class Progression(val coverage: Float, val backtrackRatio: Float, val forwardDistance: Float)

    
    fun evaluate(points: FloatArray, options: Options = Options()): Evaluation {
        val scale = if (options.viewportScale.isFinite() && options.viewportScale > 0f) options.viewportScale else 1f
        val sanitized = sanitize(points, scale)
        if (sanitized.size / 2 < 4) return Evaluation(null, null, "too-few-points")

        val rawLength = polylineLength(sanitized)
        val samples = resample(sanitized, options.sampleSpacingPx, options.maxSamples)
        val n = samples.size / 2
        if (n < 8) return Evaluation(null, null, "too-few-points")

        val xs = FloatArray(n) { samples[it * 2] }.also { it.sort() }
        val ys = FloatArray(n) { samples[it * 2 + 1] }.also { it.sort() }
        val left = quantile(xs, 0.01f)
        val right = quantile(xs, 0.99f)
        val top = quantile(ys, 0.01f)
        val bottom = quantile(ys, 0.99f)
        val width = right - left
        val height = bottom - top
        val shortSide = min(width, height)
        val longSide = max(width, height)
        val perimeter = 2f * (width + height)
        val aspectRatio = if (shortSide <= 0f) Float.POSITIVE_INFINITY else longSide / shortSide
        val edgeBand = clamp(shortSide * 0.07f, 7f, 28f)

        val projections = Array(n) { i -> projectToPerimeter(samples[i * 2], samples[i * 2 + 1], left, top, right, bottom) }
        val edgeDistances = FloatArray(n) { projections[it].distance }.also { it.sort() }
        val edgeHitRatio = projections.count { it.distance <= edgeBand }.toFloat() / n
        val edgeDistanceP90 = quantile(edgeDistances, 0.90f)
        val positions = FloatArray(n) { projections[it].perimeterPosition }
        val cw = progression(positions, perimeter, 1f)
        val ccw = progression(positions, perimeter, -1f)
        val progression = if (
            cw.backtrackRatio < ccw.backtrackRatio ||
            (cw.backtrackRatio == ccw.backtrackRatio && cw.forwardDistance >= ccw.forwardDistance)
        ) cw else ccw
        val sides = sideCoverage(projections, width, height, edgeBand)
        val sideInteriors = sideInteriorEvidence(projections, edgeBand)
        val corners = cornerEvidence(samples, left, top, right, bottom, edgeBand)
        val alignedRatio = axisAlignedRatio(samples)
        val pathToPerimeterRatio = if (perimeter <= 0f) 0f else rawLength / perimeter

        val metrics = Metrics(
            widthPx = width,
            heightPx = height,
            pathLengthPx = rawLength,
            perimeterPx = perimeter,
            pathToPerimeterRatio = pathToPerimeterRatio,
            edgeBandPx = edgeBand,
            edgeHitRatio = edgeHitRatio,
            edgeDistanceP90Px = edgeDistanceP90,
            perimeterCoverage = progression.coverage,
            backtrackRatio = progression.backtrackRatio,
            sideCoverage = sides,
            sideInteriorEvidence = sideInteriors,
            cornerEvidence = corners,
            axisAlignedRatio = alignedRatio,
        )
        metrics.confidence = confidence(metrics)

        val reason: String? = when {
            shortSide < options.minShortSidePx || longSide < options.minLongSidePx -> "too-small"
            aspectRatio > options.maxAspectRatio -> "aspect-ratio"
            pathToPerimeterRatio < 0.60f || pathToPerimeterRatio > 1.65f -> "path-length"
            edgeHitRatio < 0.68f || edgeDistanceP90 > edgeBand * 2.10f -> "edge-fit"
            progression.backtrackRatio > options.maxBacktrackRatio -> "progression"
            progression.coverage < options.minPerimeterCoverage ||
                progression.coverage > options.maxPerimeterCoverage -> "perimeter-coverage"
            else -> {
                val strongSides = sides.count { it >= 0.42f }
                val weakest = sides.minOrNull() ?: 0f
                val visibleCorners = corners.count { it }
                val hasStructure = visibleCorners >= 2 || alignedRatio >= options.minAxisAlignedRatio
                if (strongSides < 3 || weakest < 0.10f || sideInteriors.any { !it } || !hasStructure) "side-coverage"
                else if (metrics.confidence < options.minConfidence) "confidence"
                else null
            }
        }
        if (reason != null) return Evaluation(null, metrics, reason)
        return Evaluation(
            RectF(left / scale, top / scale, right / scale, bottom / scale),
            metrics,
            null,
        )
    }

    

    private fun sanitize(points: FloatArray, scale: Float): FloatArray {
        val out = ArrayList<Float>(points.size)
        var i = 0
        while (i + 1 < points.size) {
            val x = points[i] * scale
            val y = points[i + 1] * scale
            i += 2
            if (!x.isFinite() || !y.isFinite()) continue
            if (out.isEmpty() || hypot(x - out[out.size - 2], y - out[out.size - 1]) >= 0.2f) {
                out.add(x)
                out.add(y)
            }
        }
        return out.toFloatArray()
    }

    private fun polylineLength(pts: FloatArray): Float {
        var length = 0f
        var i = 2
        while (i + 1 < pts.size) {
            length += hypot(pts[i] - pts[i - 2], pts[i + 1] - pts[i - 1])
            i += 2
        }
        return length
    }

    
    private fun resample(pts: FloatArray, requestedSpacing: Float, maxSamples: Int): FloatArray {
        val count = pts.size / 2
        if (count <= 1) return pts
        val total = polylineLength(pts)
        if (total <= 0f) return floatArrayOf(pts[0], pts[1])
        val spacing = max(requestedSpacing, total / max(2, maxSamples - 1))
        val out = ArrayList<Float>(maxSamples * 2)
        out.add(pts[0]); out.add(pts[1])
        var sx = pts[0]
        var sy = pts[1]
        var carried = 0f
        for (i in 1 until count) {
            val ex = pts[i * 2]
            val ey = pts[i * 2 + 1]
            var segLen = hypot(ex - sx, ey - sy)
            if (segLen <= 0f) {
                sx = ex; sy = ey
                continue
            }
            while (carried + segLen >= spacing) {
                val needed = spacing - carried
                val ratio = needed / segLen
                val nx = sx + (ex - sx) * ratio
                val ny = sy + (ey - sy) * ratio
                out.add(nx); out.add(ny)
                sx = nx; sy = ny
                segLen = hypot(ex - sx, ey - sy)
                carried = 0f
                if (out.size / 2 >= maxSamples) return out.toFloatArray()
            }
            carried += segLen
            sx = ex; sy = ey
        }
        val fx = pts[pts.size - 2]
        val fy = pts[pts.size - 1]
        if (hypot(out[out.size - 2] - fx, out[out.size - 1] - fy) >= spacing * 0.25f) {
            out.add(fx); out.add(fy)
        }
        return out.toFloatArray()
    }

    

    private fun projectToPerimeter(px: Float, py: Float, left: Float, top: Float, right: Float, bottom: Float): Projection {
        val width = right - left
        val height = bottom - top
        val perimeter = 2f * (width + height)

        
        var cx = clamp(px, left, right)
        val topDist = hypot(px - cx, py - top)
        val topRatio = if (right <= left) 0f else (cx - left) / width
        
        val bottomDist = hypot(px - cx, py - bottom)
        val bottomRatio = topRatio
        
        var cy = clamp(py, top, bottom)
        val rightDist = hypot(px - right, py - cy)
        val rightRatio = if (bottom <= top) 0f else (cy - top) / height
        
        val leftDist = hypot(px - left, py - cy)
        val leftRatio = rightRatio

        var bestDist = topDist
        var bestPos = topRatio * width
        var bestSide = 0
        var bestRatio = topRatio
        if (rightDist < bestDist) {
            bestDist = rightDist; bestPos = width + rightRatio * height; bestSide = 1; bestRatio = rightRatio
        }
        if (bottomDist < bestDist) {
            bestDist = bottomDist; bestPos = width + height + (1f - bottomRatio) * width; bestSide = 2; bestRatio = 1f - bottomRatio
        }
        if (leftDist < bestDist) {
            bestDist = leftDist; bestPos = 2f * width + height + (1f - leftRatio) * height; bestSide = 3; bestRatio = 1f - leftRatio
        }
        return Projection(bestDist, clamp(bestPos, 0f, perimeter), bestSide, clamp(bestRatio, 0f, 1f))
    }

    private fun progression(positions: FloatArray, perimeter: Float, direction: Float): Progression {
        var cursor = 0f
        var minimum = 0f
        var maximum = 0f
        var forward = 0f
        var backward = 0f
        for (i in 1 until positions.size) {
            var delta = (positions[i] - positions[i - 1]) * direction
            while (delta <= -perimeter / 2f) delta += perimeter
            while (delta > perimeter / 2f) delta -= perimeter
            cursor += delta
            minimum = min(minimum, cursor)
            maximum = max(maximum, cursor)
            if (delta >= 0f) forward += delta else backward -= delta
        }
        val traveled = forward + backward
        return Progression(
            coverage = if (perimeter <= 0f) 0f else (maximum - minimum) / perimeter,
            backtrackRatio = if (traveled <= 0f) 1f else backward / traveled,
            forwardDistance = forward,
        )
    }

    private fun sideCoverage(projections: Array<Projection>, width: Float, height: Float, edgeBand: Float): FloatArray {
        val lengths = floatArrayOf(width, height, width, height)
        val bins = IntArray(4) { clamp((lengths[it] / 20f).roundToInt().toFloat(), 4f, 18f).toInt() }
        val covered = Array(4) { HashSet<Int>() }
        for (p in projections) {
            if (p.distance > edgeBand * 1.35f) continue
            val count = bins[p.side]
            covered[p.side].add(min(count - 1, floor(p.sideRatio * count).toInt()))
        }
        return FloatArray(4) { covered[it].size.toFloat() / bins[it] }
    }

    private fun sideInteriorEvidence(projections: Array<Projection>, edgeBand: Float): BooleanArray {
        val cornerMargin = 0.08f
        return BooleanArray(4) { side ->
            projections.any {
                it.side == side && it.distance <= edgeBand * 1.35f &&
                    it.sideRatio >= cornerMargin && it.sideRatio <= 1f - cornerMargin
            }
        }
    }

    private fun cornerEvidence(samples: FloatArray, left: Float, top: Float, right: Float, bottom: Float, edgeBand: Float): BooleanArray {
        val radius = max(edgeBand * 1.65f, min(right - left, bottom - top) * 0.065f)
        val cx = floatArrayOf(left, right, right, left)
        val cy = floatArrayOf(top, top, bottom, bottom)
        return BooleanArray(4) { corner ->
            var hit = false
            var i = 0
            while (i + 1 < samples.size && !hit) {
                if (hypot(samples[i] - cx[corner], samples[i + 1] - cy[corner]) <= radius) hit = true
                i += 2
            }
            hit
        }
    }

    private fun axisAlignedRatio(samples: FloatArray): Float {
        val n = samples.size / 2
        if (n < 5) return 0f
        val half = if (n >= 24) 2 else 1
        val minAlignment = cos(20.0 * PI / 180.0).toFloat()
        var aligned = 0
        var measured = 0
        for (i in half until n - half) {
            val dx = samples[(i + half) * 2] - samples[(i - half) * 2]
            val dy = samples[(i + half) * 2 + 1] - samples[(i - half) * 2 + 1]
            val length = hypot(dx, dy)
            if (length <= 0.5f) continue
            if (max(abs(dx), abs(dy)) / length >= minAlignment) aligned += 1
            measured += 1
        }
        return if (measured <= 0) 0f else aligned.toFloat() / measured
    }

    private fun confidence(m: Metrics): Float {
        val edgeRatioScore = normalize(m.edgeHitRatio, 0.62f, 0.92f)
        val edgeDistanceScore = 1f - normalize(m.edgeDistanceP90Px, m.edgeBandPx, m.edgeBandPx * 2.35f)
        val edgeScore = edgeRatioScore * 0.68f + edgeDistanceScore * 0.32f
        val progressionScore = 1f - normalize(m.backtrackRatio, 0.02f, 0.18f)
        val coverageScore = if (m.perimeterCoverage <= 1.03f) {
            normalize(m.perimeterCoverage, 0.62f, 0.82f)
        } else {
            1f - normalize(m.perimeterCoverage, 1.03f, 1.20f)
        }
        val sorted = m.sideCoverage.sortedDescending()
        val sideScore = clamp((sorted[0] + sorted[1] + sorted[2]) / 3f * 0.72f + sorted[3] * 0.28f, 0f, 1f)
        return clamp(
            edgeScore * 0.35f + progressionScore * 0.30f + coverageScore * 0.20f + sideScore * 0.15f,
            0f,
            1f,
        )
    }

    

    private fun clamp(v: Float, lo: Float, hi: Float): Float = min(hi, max(lo, v))

    private fun normalize(v: Float, lo: Float, hi: Float): Float {
        if (hi <= lo) return if (v >= hi) 1f else 0f
        return clamp((v - lo) / (hi - lo), 0f, 1f)
    }

    private fun quantile(sorted: FloatArray, q: Float): Float {
        if (sorted.isEmpty()) return 0f
        val position = clamp(q, 0f, 1f) * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        if (lower == upper) return sorted[lower]
        val ratio = position - lower
        return sorted[lower] * (1f - ratio) + sorted[upper] * ratio
    }
}
