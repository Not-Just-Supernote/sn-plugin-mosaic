
package me.laumss.mosaic


enum class PenStyle(
    
    val objType: Int,
) {
    
    NEEDLE(DrawPathClient.PEN_TYPE_NEEDLE),
    
    PEN(DrawPathClient.PEN_TYPE_PRESSURE),
    
    BRUSH(DrawPathClient.PEN_TYPE_BRUSH),
    MARKER(DrawPathClient.PEN_TYPE_MARKER),
    
    FILLED_SHAPE(18),
    ;

    companion object {
        
        fun normalizeStoredType(type: Int): Int = when (type) {
            0 -> DrawPathClient.PEN_TYPE_PRESSURE
            10 -> DrawPathClient.PEN_TYPE_NEEDLE
            14 -> DrawPathClient.PEN_TYPE_BRUSH
            17 -> DrawPathClient.PEN_TYPE_MARKER
            DrawPathClient.PEN_TYPE_MARKER,
            DrawPathClient.PEN_TYPE_BRUSH,
            DrawPathClient.PEN_TYPE_PRESSURE,
            PenStyle.FILLED_SHAPE.objType -> type
            else -> throw IllegalArgumentException("Unsupported drawPath pen type: $type")
        }

        fun fromObjType(objType: Int): PenStyle {
            val normalized = normalizeStoredType(objType)
            return entries.first { it.objType == normalized }
        }
    }
}


enum class MarkerInk(
    val argb: Int,
    val drawPathColor: Int,
    val noteColor: Int,
    val darkBackdrop: Boolean,
    
    val badge: String,
) {
    BLACK(0xFF000000.toInt(), DrawPathClient.PEN_COLOR_BLACK, 0x00, darkBackdrop = true, badge = "BK"),
    DARK_GRAY(0xFF808080.toInt(), DrawPathClient.PEN_COLOR_DARK_GRAY, 0x9D, darkBackdrop = false, badge = "DG"),
    LIGHT_GRAY(0xFFC0C0C0.toInt(), DrawPathClient.PEN_COLOR_LIGHT_GRAY, 0xC9, darkBackdrop = false, badge = "LG"),
    ;

    companion object {
        
        fun fromArgb(argb: Int): MarkerInk = when (argb) {
            0xFF9D9D9D.toInt() -> DARK_GRAY
            0xFFC9C9C9.toInt() -> LIGHT_GRAY
            else -> entries.firstOrNull { it.argb == argb } ?: BLACK
        }

        
        fun fromNoteColor(color: Int): MarkerInk = when (color and 0xFF) {
            in 0x00 until 0x4F -> BLACK
            in 0x4F until 0xB3 -> DARK_GRAY
            else -> LIGHT_GRAY
        }
    }
}
