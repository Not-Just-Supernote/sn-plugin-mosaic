package me.laumss.mosaic


object PenPopup {
    
    val WIDTHS = floatArrayOf(4f, 5f, 6f, 7f, 9f, 10f, 11f, 12f, 18f, 24f)
    
    val BRUSH_WIDTHS = floatArrayOf(2f, 3f, 4f, 5f, 6f, 7f, 9f, 10f, 11f, 12f, 18f, 24f)
    
    val MARKER_WIDTHS = floatArrayOf(38f)
    
    val LABELS = arrayOf("0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0", "1.5", "2.0")
    val BRUSH_LABELS = arrayOf("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0", "1.5", "2.0")
    val MARKER_LABELS = arrayOf("3.0")
    
    const val DEFAULT_INDEX = 0
    
    const val BRUSH_DEFAULT_INDEX = 4

    fun defaultIndex(style: PenStyle): Int = if (style == PenStyle.BRUSH) BRUSH_DEFAULT_INDEX else DEFAULT_INDEX

    
    val MAX_WIDTH_COUNT: Int get() = maxOf(WIDTHS.size, BRUSH_WIDTHS.size, MARKER_WIDTHS.size)
}
