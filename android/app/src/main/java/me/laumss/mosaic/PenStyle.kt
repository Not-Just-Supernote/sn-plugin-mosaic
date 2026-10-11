
package me.laumss.mosaic


enum class PenStyle(
    
    val objType: Int,
) {
    
    /** 第一支笔：内部记录为 16，实时使用原生书法笔类型 15 与其八档粗细。 */
    PEN(DrawPathClient.PEN_TYPE_PRESSURE),
    
    /** 第二支笔：导入原生针管笔和墨水笔；内部记录为 15，实时 drawPath 类型为 10。 */
    BRUSH(DrawPathClient.PEN_TYPE_BRUSH),
    MARKER(DrawPathClient.PEN_TYPE_MARKER),
    
    /** 实心图形：内部填充状态为 18，实时 drawPath 类型为 16，宽度使用当前选中粗细。 */
    FILLED_SHAPE(18),
    ;

    companion object {
        
        fun normalizeStoredType(type: Int): Int = when (type) {
            0 -> DrawPathClient.PEN_TYPE_PRESSURE
            10 -> DrawPathClient.PEN_TYPE_BRUSH
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
