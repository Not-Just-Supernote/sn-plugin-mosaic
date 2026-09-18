package me.laumss.mosaic


class ScrollingDocument(
    val ref: String,
    var strokes: List<BoardEngine.StrokeRec>,
    var scrollY: Float,
) {
    val width: Float get() = WIDTH
    val contentHeight: Float
        get() = maxOf(80f, (strokes.maxOfOrNull { it.bounds.bottom + it.width } ?: 0f) + 20f)
    
    val contentWidth: Float
        get() = maxOf(WIDTH, (strokes.maxOfOrNull { it.bounds.right + it.width } ?: 0f) + 20f)
    fun clampScroll(y: Float, viewportHeight: Float): Float =
        y.coerceIn(0f, maxOf(0f, contentHeight + viewportHeight * 0.75f))
    fun snapshot() = ScrollingDocument(ref, strokes.toList(), scrollY)

    companion object {
        const val WIDTH = 480f

        
        private const val LEADING_MARGIN = 40f
        
        private const val MAX_EMPTY_GAP = 160f
        private const val COLLAPSED_GAP = 64f

        
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
