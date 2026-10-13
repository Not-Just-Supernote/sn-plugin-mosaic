package me.laumss.mosaic

/** 与 Notipal MosaicPaletteSpec 对齐：只接受笔形，颜色由笔形确定。 */
internal object PaletteStrokeStyle {
    data class Spec(val style: PenStyle, val notePenType: Int, val color: Int?)

    fun spec(id: String): Spec? = when (id) {
        "ball" -> Spec(PenStyle.PEN, 15, null)
        "needle" -> Spec(PenStyle.BRUSH, 10, null)
        "marker" -> Spec(PenStyle.MARKER, 11, 0xFF000000.toInt())
        "mk-lightGray" -> Spec(PenStyle.MARKER, 11, 0xFFC0C0C0.toInt())
        "mk-gray" -> Spec(PenStyle.MARKER, 11, 0xFFA0A0A0.toInt())
        "mk-darkGray" -> Spec(PenStyle.MARKER, 11, 0xFF808080.toInt())
        else -> null
    }

    fun id(stroke: BoardEngine.StrokeRec): String = when (stroke.penStyle) {
        PenStyle.BRUSH.objType -> "needle"
        PenStyle.MARKER.objType -> when (stroke.color) {
            0xFFC0C0C0.toInt(), 0xFFC9C9C9.toInt() -> "mk-lightGray"
            0xFFA0A0A0.toInt() -> "mk-gray"
            0xFF808080.toInt(), 0xFF9D9D9D.toInt() -> "mk-darkGray"
            else -> "marker"
        }
        else -> "ball"
    }

    /** 面板以宿主书法笔档位表示第一支笔；引擎保存当前分支的 Mosaic 档位。 */
    fun thickness(stroke: BoardEngine.StrokeRec): Int = when (stroke.penStyle) {
        PenStyle.PEN.objType, PenStyle.FILLED_SHAPE.objType -> DrawPathClient.noteCalligraphyThickness(stroke.drawPathWidth)
        else -> stroke.drawPathWidth
    }

    fun restyle(before: BoardEngine.StrokeRec, spec: Spec, thickness: Int, changePen: Boolean = true): BoardEngine.StrokeRec {
        val style = if (changePen) spec.style else PenStyle.fromObjType(before.penStyle)
        val raw = when (style) {
            PenStyle.PEN, PenStyle.FILLED_SHAPE -> DrawPathClient.mosaicCalligraphyThickness(thickness)
            else -> DrawPathClient.widthArgumentFromStdHundredths(thickness, style)
        }
        val color = if (!changePen) before.color else spec.color ?: if (before.penStyle == PenStyle.MARKER.objType) {
            BoardEngine.StrokeRec.INK_BLACK
        } else before.color
        return BoardEngine.StrokeRec(before.id, before.space, raw / 100f, color,
            before.points, before.pressures, style.objType, before.sampleScale, raw, before.nibAngle, before.angles, before.contourOffsets)
    }
}
