package me.laumss.mosaic

import android.graphics.Path
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Type-15 calligraphy, evaluated on drawPath's four-times-screen coordinate grid. */
internal object CalligraphyRaster {
    private const val NATIVE_GRID_SCALE = 4f
    private const val NIB_ASPECT = 0.35f
    private const val MIN_DIRECTION_WIDTH_RATIO = 0.39f
    private const val REVERSE_PRESSURE_WEIGHT = 0.7f
    private const val DIRECTION_CURRENT_WEIGHT = 0.7f
    private const val DIRECTION_PREVIOUS_WEIGHT = 0.3f
    private const val MIN_NATIVE_WIDTH = 4
    private const val MAX_NATIVE_WIDTH = 872
    private const val WIDTH_STEP_INTERVAL = 3
    private const val INITIAL_DISTANCE = 80f
    private const val PRESSURE_MAX = 4095

    private data class Point(val x: Float, val y: Float)
    private data class Direction(val x: Float, val y: Float) {
        fun normalized(): Direction {
            val length = hypot(x, y)
            return if (length > 0.00001f) Direction(x / length, y / length) else this
        }
        fun dot(other: Direction): Float = x * other.x + y * other.y
    }

    /** Counts and widths belong to the whole stroke, not individual Bezier segments. */
    private class WidthState {
        var previousDirection: Direction? = null
        private var previousWidth = 0
        private var initialized = false
        private var initial = true
        private var count = 0
        private var downInterval = WIDTH_STEP_INTERVAL
        private var upInterval = WIDTH_STEP_INTERVAL
        private var negativeSegments = 0
        private var positiveSegments = 0
        private val directions = ArrayDeque<Direction>(3)

        fun beginSegment(target: Int, direction: Direction, dot: Float, points: Int,
            distance: Float, inputMoved: Boolean) {
            if (distance > INITIAL_DISTANCE) initial = false
            if (!initialized) {
                previousWidth = target
                initialized = true
            }
            var turn = false
            if (dot < 0f) {
                negativeSegments++
                positiveSegments = 0
            } else {
                val oldPositive = positiveSegments++
                if (oldPositive >= 1 && negativeSegments >= 4) {
                    turn = true
                    negativeSegments = 0
                }
            }
            if (directions.isEmpty() || inputMoved) {
                if (directions.size == 3) directions.removeFirst()
                directions.addLast(direction)
            }
            if (turn && directions.first.normalized().dot(directions.last.normalized()) < 0f) {
                previousWidth = target
            }
            if (!turn && previousWidth != target) {
                downInterval = WIDTH_STEP_INTERVAL
                upInterval = WIDTH_STEP_INTERVAL
                val difference = abs(previousWidth - target)
                val interval = max(WIDTH_STEP_INTERVAL,
                    if (count < WIDTH_STEP_INTERVAL) (points + count) / (difference + 1)
                    else points / difference)
                if (previousWidth > target) downInterval = interval else upInterval = interval
            }
        }

        fun next(target: Int): Int {
            val width = when {
                previousWidth > target -> {
                    if (count < downInterval) { count++; previousWidth }
                    else { count = 1; previousWidth - 1 }
                }
                previousWidth < target && !initial -> {
                    if (count < upInterval) { count++; previousWidth }
                    else { count = 1; previousWidth + 1 }
                }
                else -> { count++; target }
            }.coerceIn(0, MAX_NATIVE_WIDTH)
            previousWidth = width
            return width
        }
    }

    fun runs(
        points: FloatArray,
        pressures: FloatArray,
        sampleScale: Float,
        drawPathWidth: Int,
        nibAngle: Float,
        widthScale: Float = 1f,
    ): List<TchRaster.Run> {
        val n = pressures.size
        if (n == 0) return emptyList()
        require(sampleScale.isFinite() && sampleScale > 0f)
        val grid = sampleScale * NATIVE_GRID_SCALE
        fun inputPoint(index: Int) = Point(points[index * 2] * grid, points[index * 2 + 1] * grid)
        fun pressure(index: Int) = (pressures[index].coerceIn(0f, 1f) * PRESSURE_MAX).roundToInt()

        // nibAngle is the reference vector in screen coordinates (positive Y points down).
        // The ellipse's short axis lies on this vector; its long axis is perpendicular.
        // Old/imported strokes without tilt keep their existing circular fallback.
        val directional = nibAngle.isFinite()
        val angle = if (directional) nibAngle else 0f
        val reference = Direction(cos(angle), sin(angle))
        val aspect = if (directional) NIB_ASPECT else 1f
        val baseWidth = (DrawPathClient.noteCalligraphyThickness(drawPathWidth) * 0.01f)
            .roundToInt().coerceAtLeast(1)
        val fullNativeWidth = baseWidth * NATIVE_GRID_SCALE
        val minimumWidth = (fullNativeWidth * MIN_DIRECTION_WIDTH_RATIO).roundToInt()
        // Display calibration is applied only after the native width/turn rules.
        val outputPerNativeWidth = DrawPathClient.calligraphyFullPressureWidthUnits(drawPathWidth) *
            widthScale / (fullNativeWidth * sampleScale)
        val state = WidthState()
        val origin = inputPoint(0)
        var previousInput = origin
        val path = Path()
        var maxMajorWidth = 0f
        var minInkWidth = Float.POSITIVE_INFINITY
        var maxInkWidth = 0f

        fun appendSegment(samples: List<Point>, pressure01: Float, inputEnd: Point,
            geometryEnd: Point = samples.last()) {
            val first = samples.first()
            val last = samples.last()
            val delta = Direction(last.x - first.x, last.y - first.y)
            val moving = hypot(delta.x, delta.y) > 0.00001f
            val current = if (moving) delta.normalized() else Direction(0f, 0f)
            val previous = state.previousDirection ?: Direction(0f, 0f)
            val smoothed = Direction(
                current.x * DIRECTION_CURRENT_WEIGHT + previous.x * DIRECTION_PREVIOUS_WEIGHT,
                current.y * DIRECTION_CURRENT_WEIGHT + previous.y * DIRECTION_PREVIOUS_WEIGHT,
            ).normalized()
            state.previousDirection = current
            val dot = if (directional) smoothed.dot(reference).coerceIn(-1f, 1f) else 1f
            val ratio = DrawPathClient.calligraphyPressureWidthRatio(drawPathWidth, pressure01)
            val pressureWidth = (fullNativeWidth * ratio).roundToInt()
            val target = (when {
                !directional -> pressureWidth
                dot >= 0f -> max(minimumWidth, (dot * pressureWidth).roundToInt())
                else -> (minimumWidth * (1f + REVERSE_PRESSURE_WEIGHT * ratio)).toInt()
            }).coerceIn(MIN_NATIVE_WIDTH, MAX_NATIVE_WIDTH)
            state.beginSegment(target, smoothed, dot, samples.size,
                hypot(inputEnd.x - origin.x, inputEnd.y - origin.y), inputEnd != previousInput)
            previousInput = inputEnd
            val nativeWidths = FloatArray(samples.size) { state.next(target) * outputPerNativeWidth }
            // Seal the exact midpoint/UP location without advancing native width counters.
            val sealEnd = samples.last() != geometryEnd
            val outlineSamples = if (sealEnd) samples + geometryEnd else samples
            val widths = if (sealEnd) nativeWidths + nativeWidths.last() else nativeWidths
            fun appendSweep(from: Int, through: Int) {
                val normals = ArrayList<Direction>(through - from + 1)
                for (i in from..through) {
                    val before = outlineSamples[maxOf(from, i - 1)]
                    val after = outlineSamples[minOf(through, i + 1)]
                    val tangent = Direction(after.x - before.x, after.y - before.y)
                    val dir = if (hypot(tangent.x, tangent.y) > 0.00001f) tangent.normalized() else reference
                    val normal = Direction(-dir.y, dir.x)
                    normals.add(normal)
                    maxMajorWidth = max(maxMajorWidth, widths[i])
                    val offset = support(normal.x, normal.y, widths[i] * 0.5f, reference, aspect)
                    val inkWidth = 2f * abs(offset.x * normal.x + offset.y * normal.y)
                    minInkWidth = minOf(minInkWidth, inkWidth)
                    maxInkWidth = maxOf(maxInkWidth, inkWidth)
                }
                for (i in from..through) {
                    val point = outlineSamples[i]
                    val normal = normals[i - from]
                    val offset = support(normal.x, normal.y, widths[i] * 0.5f, reference, aspect)
                    val x = point.x / grid + offset.x
                    val y = point.y / grid + offset.y
                    if (i == from) path.moveTo(x, y) else path.lineTo(x, y)
                }
                fun cap(index: Int, normal: Direction) {
                    val point = outlineSamples[index]
                    val start = atan2(normal.y, normal.x)
                    for (step in 1..12) {
                        val a = start - Math.PI.toFloat() * step / 12f
                        val offset = support(cos(a), sin(a), widths[index] * 0.5f, reference, aspect)
                        path.lineTo(point.x / grid + offset.x, point.y / grid + offset.y)
                    }
                }
                cap(through, normals.last())
                for (i in through downTo from) {
                    val point = outlineSamples[i]
                    val normal = normals[i - from]
                    val offset = support(normal.x, normal.y, widths[i] * 0.5f, reference, aspect)
                    path.lineTo(point.x / grid - offset.x, point.y / grid - offset.y)
                }
                cap(from, Direction(-normals[0].x, -normals[0].y))
                path.close()
            }

            // A single quadratic can double back. Split its filled contour at each cusp,
            // while keeping the original segment's pressure, direction and width counters.
            var sweepStart = 0
            for (i in 1 until outlineSamples.lastIndex) {
                val incoming = Direction(outlineSamples[i].x - outlineSamples[i - 1].x, outlineSamples[i].y - outlineSamples[i - 1].y)
                val outgoing = Direction(outlineSamples[i + 1].x - outlineSamples[i].x, outlineSamples[i + 1].y - outlineSamples[i].y)
                if (incoming.dot(outgoing) <= 0f) {
                    appendSweep(sweepStart, i)
                    sweepStart = i
                }
            }
            appendSweep(sweepStart, outlineSamples.lastIndex)
        }

        // DOWN establishes the minimum directional nib before the first moving segment.
        appendSegment(listOf(origin), pressure(0).toFloat() / PRESSURE_MAX, origin)
        if (n > 1) {
            var start = origin
            for (i in 1 until n) {
                val control = inputPoint(i - 1)
                val input = inputPoint(i)
                val end = Point((control.x + input.x) * 0.5f, (control.y + input.y) * 0.5f)
                // drawPath pairs each midpoint segment with the adjacent original pressures.
                val averagePressure = (pressure(i - 1) + pressure(i)) / 2
                appendSegment(sampleBezier(start, control, end), averagePressure.toFloat() / PRESSURE_MAX, input, end)
                start = end
            }
            val end = inputPoint(n - 1)
            if (hypot(end.x - start.x, end.y - start.y) > 0.00001f) {
                val control = Point((start.x + end.x) * 0.5f, (start.y + end.y) * 0.5f)
                appendSegment(sampleBezier(start, control, end), pressure(n - 1).toFloat() / PRESSURE_MAX, end, end)
            }
        }
        return listOf(TchRaster.Run(path, maxMajorWidth, filled = true,
            minWidth = minInkWidth, maxWidth = maxInkWidth))
    }

    private fun sampleBezier(start: Point, control: Point, end: Point): List<Point> {
        val distance = hypot(end.x - start.x, end.y - start.y)
        val step = max(0.5 / (floor(distance.toDouble()) + 1.0), 0.0001)
        val samples = ArrayList<Point>()
        samples.add(start)
        var t = step
        while (t <= 1.0) {
            val u = 1.0 - t
            val point = Point((u * u * start.x + 2.0 * u * t * control.x + t * t * end.x).toFloat(),
                (u * u * start.y + 2.0 * u * t * control.y + t * t * end.y).toFloat())
            val previous = samples.last()
            if (abs(point.x - previous.x) >= 1f || abs(point.y - previous.y) >= 1f) {
                samples.add(point)
            }
            t += step
        }
        return samples
    }

    private fun support(nx: Float, ny: Float, radius: Float, reference: Direction, aspect: Float): Point {
        val alongMinor = nx * reference.x + ny * reference.y
        val alongMajor = -nx * reference.y + ny * reference.x
        val minor = radius * aspect
        val denominator = hypot(minor * alongMinor, radius * alongMajor).coerceAtLeast(0.00001f)
        val a = minor * minor * alongMinor / denominator
        val b = radius * radius * alongMajor / denominator
        return Point(reference.x * a - reference.y * b, reference.y * a + reference.x * b)
    }
}
