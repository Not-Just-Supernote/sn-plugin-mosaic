package me.laumss.mosaic.engine

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Matrix
import android.graphics.Region
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Keep every active incident bridge and its currently visible ink during a single-card edit. */
internal class ConnectedCardConstraints(start: RectF, specs: List<Link>) {
    class Link(
        val id: String,
        val other: RectF,
        val movingIsFrom: Boolean,
        val dark: Boolean,
        val ink: Path,
        val strokes: List<BoardEngine.StrokeRec>,
    )
    /** Integer scan conversion avoids Skia curve boolean operations on pressure-pen outlines. */
    private class InkMask(initialBridge: Path) {
        private val matrix: Matrix
        private val clip: Region
        init {
            val bounds = RectF()
            initialBridge.computeBounds(bounds, true)
            matrix = Matrix().apply {
                setTranslate(-bounds.left, -bounds.top)
                postScale(4f, 4f)
            }
            clip = Region(0, 0, ceil(bounds.width() * 4f).toInt(), ceil(bounds.height() * 4f).toInt())
        }
        fun raster(path: Path): Region {
            val local = Path()
            path.transform(matrix, local)
            return Region().apply { setPath(local, clip) }
        }
        fun openBridge(path: Path, a: RectF, b: RectF): Region {
            val cards = Path().apply { addRect(a, Path.Direction.CW); addRect(b, Path.Direction.CW) }
            return raster(path).apply { op(raster(cards), Region.Op.DIFFERENCE) }
        }
    }
    private class BoundLink(val link: Link, val mask: InkMask?, val protectedInk: Region)
    private val initial = RectF(start)
    private val links = specs.map { spec ->
        val initialBridge = bridge(spec, initial)
        val mask = initialBridge?.let { InkMask(it) }
        val protected = Region()
        if (mask != null) {
            protected.op(mask.raster(spec.ink), Region.Op.UNION)
            val paint = Paint().apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            for (stroke in spec.strokes) for (run in stroke.runs) {
                val filled = run.filled || (stroke.isShape && stroke.penStyle == PenStyle.FILLED_SHAPE.objType)
                paint.style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
                paint.strokeWidth = if (filled) 1f else max(run.width, 0.5f) + 1f
                val outline = Path()
                paint.getFillPath(run.path, outline)
                protected.op(mask.raster(outline), Region.Op.UNION)
            }
            // One mask pixel of clearance covers scan-conversion rounding (0.25 world units).
            val core = Region(protected)
            for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                protected.op(Region(core).apply { translate(dx, dy) }, Region.Op.UNION)
            }
            protected.op(mask.openBridge(initialBridge, initial, spec.other), Region.Op.INTERSECT)
        }
        BoundLink(spec, mask, protected)
    }
    val isEmpty: Boolean get() = links.isEmpty()
    val bridgeStrokes: List<BoardEngine.StrokeRec> = specs.flatMap { it.strokes }.distinctBy { it.id }

    fun isValid(rect: RectF): Boolean {
        if (rect.isEmpty || !rect.left.isFinite() || !rect.top.isFinite() ||
            !rect.right.isFinite() || !rect.bottom.isFinite()) return false
        for (bound in links) {
            val mask = bound.mask ?: return false
            val link = bound.link
            val protected = bound.protectedInk
            val path = bridge(link, rect) ?: return false
            if (path.isEmpty) return false
            if (!protected.isEmpty) {
                val allowed = mask.openBridge(path, rect, link.other)
                val outside = Region(protected)
                outside.op(allowed, Region.Op.DIFFERENCE)
                if (!outside.isEmpty) return false
            }
        }
        return true
    }

    // The last rectangle constrain() returned. It is legal by construction, so the next
    // call starting from it does not rasterize the bridge again just to re-check it.
    private val lastLegal = RectF(start)

    /** Walk from the previous legal preview, then stop at the first invalid point. */
    fun constrain(previous: RectF, requested: RectF, out: RectF): Boolean {
        val target = RectF(requested)
        if (isEmpty) { out.set(target); return false }
        val from = if (previous == lastLegal || isValid(previous)) RectF(previous) else RectF(initial)
        if (target == from) { out.set(from); lastLegal.set(from); return false }
        val limited = walk(from, target, out)
        lastLegal.set(out)
        return limited
    }

    private fun walk(from: RectF, target: RectF, out: RectF): Boolean {
        val distance = max(max(abs(target.left - from.left), abs(target.right - from.right)),
            max(abs(target.top - from.top), abs(target.bottom - from.bottom)))
        if (!distance.isFinite()) { out.set(from); return true }
        val steps = ceil(distance / 8f).toInt().coerceIn(1, 64)
        val probe = RectF()
        var last = 0f
        for (step in 1..steps) {
            val t = step.toFloat() / steps
            interpolate(from, target, t, probe)
            if (!isValid(probe)) {
                var lo = last
                var hi = t
                repeat(16) {
                    val mid = (lo + hi) / 2f
                    interpolate(from, target, mid, probe)
                    if (isValid(probe)) lo = mid else hi = mid
                }
                interpolate(from, target, lo, out)
                return true
            }
            last = t
        }
        out.set(target)
        return false
    }

    fun previewPaths(rect: RectF): List<Pair<Path, Boolean>> =
        links.mapNotNull { bound -> bridge(bound.link, rect)?.let { it to bound.link.dark } }

    private fun bridge(link: Link, rect: RectF): Path? {
        // Stay just inside the break distance after CardRec's width/height round trip.
        val limit = max(NeckGeometry.MAX_DISTANCE - 0.25f, max(0f, NeckGeometry.signedEdgeDistance(initial, link.other)))
        return if (link.movingIsFrom) NeckGeometry.build(rect, link.other, limit)
            else NeckGeometry.build(link.other, rect, limit)
    }

    private fun interpolate(a: RectF, b: RectF, t: Float, out: RectF) {
        out.set(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
            a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t)
    }

    companion object {
        fun capture(card: BoardEngine.CardRec): ConnectedCardConstraints {
            val links = synchronized(BoardEngine.lock) {
                BoardEngine.connections.values.filter { it.touches(card.id) && it.id in BoardEngine.necks }.mapNotNull { c ->
                    val other = BoardEngine.cards[if (c.fromId == card.id) c.toId else c.fromId] ?: return@mapNotNull null
                    val strokes = BoardEngine.strokes.values.filter { it.connectionId == c.id }
                    Link(c.id, other.rect(), c.fromId == card.id, card.colored, Path(), strokes)
                }
            }
            // No path processing while holding the shared scene lock.
            return ConnectedCardConstraints(card.rect(), links)
        }
    }
}
