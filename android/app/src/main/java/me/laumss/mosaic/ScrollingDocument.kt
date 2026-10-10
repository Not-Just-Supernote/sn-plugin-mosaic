package me.laumss.mosaic

import kotlin.math.ceil
import kotlin.math.floor


// A note is a 480-wide column scaled to the view width when opened, so every device shows the whole width.
// Its thumbnail is cut into 480x640 tiles so a save only redraws the tiles that changed.
class ScrollingDocument(
    val ref: String,
    var strokes: List<BoardEngine.StrokeRec>,
    var scrollY: Float,
) {
    val width: Float get() = WIDTH
    val contentHeight: Float
        get() = maxOf(80f, (strokes.maxOfOrNull { it.bounds.bottom + it.width } ?: 0f) + 20f)
    val tileCount: Int
        get() = maxOf(1, ceil(contentHeight / TILE_HEIGHT).toInt())
    fun clampScroll(y: Float, viewportHeight: Float): Float =
        y.coerceIn(0f, maxOf(0f, contentHeight + viewportHeight * 0.75f))
    fun snapshot() = ScrollingDocument(ref, strokes.toList(), scrollY)

    companion object {
        const val WIDTH = 480f
        const val TILE_HEIGHT = 640f
        // Template pitch on the note relative to the board, so the paper looks the same as before on a 1920px screen.
        const val TEMPLATE_PITCH_SCALE = 0.5f

        private const val LEADING_MARGIN = 40f
        private const val MAX_EMPTY_GAP = 160f
        private const val COLLAPSED_GAP = 64f

        fun viewScale(viewWidthDp: Float): Float = (viewWidthDp / WIDTH).takeIf { it.isFinite() && it > 0f } ?: 1f

        // Ink padding used for tile hit tests; rendered width can exceed the stored width for brushes.
        fun inkPad(stroke: BoardEngine.StrokeRec): Float = stroke.width * 2f + 4f

        fun tilesOf(stroke: BoardEngine.StrokeRec): IntRange {
            val pad = inkPad(stroke)
            val first = floor((stroke.bounds.top - pad) / TILE_HEIGHT).toInt().coerceAtLeast(0)
            val last = floor((stroke.bounds.bottom + pad) / TILE_HEIGHT).toInt().coerceAtLeast(first)
            return first..last
        }

        // Tiles touched by strokes added, removed or replaced between two versions.
        fun dirtyTiles(before: Map<String, BoardEngine.StrokeRec>, after: List<BoardEngine.StrokeRec>): Set<Int> {
            val dirty = HashSet<Int>()
            val seen = HashSet<String>()
            for (s in after) {
                seen.add(s.id)
                val old = before[s.id]
                if (old === s) continue
                dirty.addAll(tilesOf(s))
                if (old != null) dirty.addAll(tilesOf(old))
            }
            for ((id, old) in before) if (id !in seen) dirty.addAll(tilesOf(old))
            return dirty
        }

        // Pulls the ink up to LEADING_MARGIN and shrinks every blank gap taller than MAX_EMPTY_GAP to COLLAPSED_GAP.
        fun compact(strokes: List<BoardEngine.StrokeRec>): List<BoardEngine.StrokeRec> {
            if (strokes.isEmpty()) return strokes
            val sorted = strokes.sortedBy { it.bounds.top }
            val shiftById = HashMap<String, Float>()
            var cumulativeShift = 0f
            var runningMaxBottom = Float.NaN
            for (stroke in sorted) {
                val top = stroke.bounds.top
                if (runningMaxBottom.isNaN()) {
                    val lead = top - LEADING_MARGIN
                    if (lead > 0f) cumulativeShift = lead
                } else {
                    val gap = top - runningMaxBottom
                    if (gap > MAX_EMPTY_GAP) cumulativeShift += gap - COLLAPSED_GAP
                }
                if (cumulativeShift > 0.5f) shiftById[stroke.id] = cumulativeShift
                val bottom = stroke.bounds.bottom
                runningMaxBottom = if (runningMaxBottom.isNaN()) bottom else maxOf(runningMaxBottom, bottom)
            }
            if (shiftById.isEmpty()) return strokes
            return strokes.map { s -> shiftById[s.id]?.let { s.translated(0f, -it, null, null) } ?: s }
        }
    }
}
