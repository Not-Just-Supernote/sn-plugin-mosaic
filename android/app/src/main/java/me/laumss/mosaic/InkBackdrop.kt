package me.laumss.mosaic

import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.max


object InkBackdrop {
    const val INK_BLACK = BoardEngine.StrokeRec.INK_BLACK
    const val INK_WHITE = BoardEngine.StrokeRec.INK_WHITE

    
    fun isAutoInk(s: BoardEngine.StrokeRec): Boolean =
        (s.color == INK_BLACK || s.color == INK_WHITE) &&
            s.penStyle != PenStyle.MARKER.objType &&
            !(s.penStyle == PenStyle.FILLED_SHAPE.objType && s.isShape)

    private fun isMarker(s: BoardEngine.StrokeRec) = s.penStyle == PenStyle.MARKER.objType

    
    private fun isDarkMarker(s: BoardEngine.StrokeRec, includeGray: Boolean = false) =
        isMarker(s) && (includeGray || MarkerInk.fromArgb(s.color).darkBackdrop)

    private fun isFill(s: BoardEngine.StrokeRec) = s.penStyle == PenStyle.FILLED_SHAPE.objType && s.isShape

    
    private fun strokesIn(space: String): Collection<BoardEngine.StrokeRec> {
        if (space.startsWith("card:")) return BoardEngine.cardStrokes[space.substring(5)] ?: emptyList()
        return BoardEngine.strokes.values.filter { it.space == space }
    }

    private fun cardDark(space: String, override: Boolean?): Boolean {
        if (!space.startsWith("card:")) return false
        return override ?: (BoardEngine.cards[space.substring(5)]?.colored == true)
    }

    
    fun resolve(
        s: BoardEngine.StrokeRec,
        cardDarkOverride: Boolean? = null,
        markers: List<BoardEngine.StrokeRec> = markersIn(s.space),
    ): Int {
        if (!isAutoInk(s)) return s.color
        val dark = cardDark(s.space, cardDarkOverride) || overMarker(s, markers)
        return if (dark) INK_WHITE else INK_BLACK
    }

    
    fun markersIn(space: String, includeGray: Boolean = false): List<BoardEngine.StrokeRec> =
        strokesIn(space).filter { isDarkMarker(it, includeGray) }

    
    fun isOverMarkers(s: BoardEngine.StrokeRec, markers: List<BoardEngine.StrokeRec>): Boolean =
        overMarker(s, markers)

    
    private fun overMarker(s: BoardEngine.StrokeRec, spaceMarkers: List<BoardEngine.StrokeRec>): Boolean {
        if (spaceMarkers.isEmpty()) return false
        val markers = spaceMarkers.filter { it.id != s.id && RectF.intersects(markerBounds(it), s.bounds) }
        if (markers.isEmpty()) return false
        var total = 0
        var hit = 0
        forEachSample(s) { x, y ->
            total++
            if (markers.any { nearMarker(it, x, y, 0f) }) hit++
        }
        return total > 0 && hit * 2 > total
    }

    private fun markerHalfWidth(m: BoardEngine.StrokeRec): Float {
        var w = 0f
        for (run in m.runs) w = max(w, run.width)
        return w / 2f
    }

    private fun markerBounds(m: BoardEngine.StrokeRec): RectF {
        val r = RectF(m.bounds)
        val h = markerHalfWidth(m)
        r.inset(-h, -h)
        return r
    }

    
    private fun nearMarker(m: BoardEngine.StrokeRec, x: Float, y: Float, pad: Float): Boolean {
        val reach = markerHalfWidth(m) + pad
        val p = m.points
        if (p.size < 2) return false
        if (p.size == 2) return hypot(x - p[0], y - p[1]) <= reach
        var i = 0
        while (i + 3 < p.size) {
            if (segmentDistance(x, y, p[i], p[i + 1], p[i + 2], p[i + 3]) <= reach) return true
            i += 2
        }
        return false
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    
    private inline fun forEachSample(s: BoardEngine.StrokeRec, block: (Float, Float) -> Unit) {
        val p = s.points
        if (!s.isShape) {
            var i = 0
            while (i + 1 < p.size) { block(p[i], p[i + 1]); i += 2 }
            return
        }
        var i = 0
        while (i + 3 < p.size) {
            for (k in 0 until SHAPE_EDGE_SAMPLES) {
                val t = k / SHAPE_EDGE_SAMPLES.toFloat()
                block(p[i] + (p[i + 2] - p[i]) * t, p[i + 1] + (p[i + 3] - p[i + 1]) * t)
            }
            i += 2
        }
    }

    private const val SHAPE_EDGE_SAMPLES = 8

    
    fun layerDarkAt(space: String, x: Float, y: Float, penRadius: Float): Boolean =
        layerAt(space, x, y, penRadius) != null

    
    fun layerAt(space: String, x: Float, y: Float, penRadius: Float): String? {
        synchronized(BoardEngine.lock) {
            for (s in strokesIn(space)) {
                if (isMarker(s)) {
                    if (isDarkMarker(s) && nearMarker(s, x, y, penRadius)) return "marker:${s.id}"
                } else if (isFill(s)) {
                    if (insideOrNearPolygon(s.points, x, y, penRadius)) return "fill:${s.id}"
                }
            }
            return null
        }
    }

    
    private fun insideOrNearPolygon(p: FloatArray, x: Float, y: Float, pad: Float): Boolean {
        val n = p.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = p[2 * i]; val yi = p[2 * i + 1]
            val xj = p[2 * j]; val yj = p[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            if (pad > 0f && segmentDistance(x, y, xj, yj, xi, yi) <= pad) return true
            j = i
        }
        return inside
    }

    
    fun reconcile(change: BoardHistory.Change): BoardHistory.Change? {
        val candidates = LinkedHashMap<String, BoardEngine.StrokeRec>()
        synchronized(BoardEngine.lock) {
            val markerAreas = ArrayList<Pair<String, RectF>>()
            for (diff in change.diffs) {
                when (diff) {
                    is BoardHistory.Diff.Stroke -> {
                        diff.after?.let { after ->
                            val live = BoardEngine.strokes[after.id]
                            if (live != null && isAutoInk(live)) candidates[live.id] = live
                            if (isMarker(after)) markerAreas.add(after.space to markerBounds(after))
                        }
                        diff.before?.let { before ->
                            if (isMarker(before)) markerAreas.add(before.space to markerBounds(before))
                        }
                    }
                    is BoardHistory.Diff.Card -> {
                        val before = diff.before
                        val after = diff.after ?: continue
                        if (before == null || before.colored != after.colored) {
                            BoardEngine.cardStrokes[after.id]?.forEach { if (isAutoInk(it)) candidates[it.id] = it }
                        }
                    }
                    else -> {}
                }
            }
            for ((space, area) in markerAreas) {
                for (s in strokesIn(space)) {
                    if (isAutoInk(s) && RectF.intersects(area, s.bounds)) candidates[s.id] = s
                }
            }
            if (candidates.isEmpty()) return null
            val fix = BoardHistory.Change(change.label + ":ink")
            val markerCache = HashMap<String, List<BoardEngine.StrokeRec>>()
            for (s in candidates.values) {
                val want = resolve(s, markers = markerCache.getOrPut(s.space) { markersIn(s.space) })
                if (want != s.color) fix.stroke(s, s.withColor(want))
            }
            return if (fix.isEmpty) null else fix
        }
    }
}
