package me.laumss.mosaic

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode


object TchRaster {
    data class Run(val path: Path, val width: Float, val filled: Boolean = false,
        val minWidth: Float = width, val maxWidth: Float = width)

    fun runs(
        points: FloatArray,
        pressures: FloatArray,
        sampleScale: Float,
        drawPathType: Int,
        drawPathWidth: Int,
        nibAngle: Float = Float.NaN,
    ): List<Run> {
        val n = points.size / 2
        if (n == 0) return emptyList()
        val type = PenStyle.normalizeStoredType(drawPathType)
        require(points.size % 2 == 0 && pressures.size == n) {
            "drawPath points and pressure samples must have the same length"
        }
        require(drawPathWidth > 0) { "drawPath width is unavailable" }

        
        
        if (Shapes.kindOf(points, pressures) != null) {
            val shapeWidth = DrawPathClient.nativePressureWidthUnits(type, drawPathWidth, pressures[0])
            return listOf(Run(BoardEngine.buildPolylinePath(points), shapeWidth / sampleScale))
        }

        if (type == PenStyle.PEN.objType) {
            return CalligraphyRaster.runs(points, pressures, sampleScale, drawPathWidth, nibAngle)
        }

        val widths = FloatArray(n) { index ->
            val nativeWidth = DrawPathClient.nativePressureWidthUnits(type, drawPathWidth, pressures[index])
            nativeWidth / sampleScale
        }

        val runs = ArrayList<Run>()
        var path = Path().apply { moveTo(points[0], points[1]) }
        var runWidth = widths[0]
        var lastX = points[0]
        var lastY = points[1]
        var midX = lastX
        var midY = lastY
        for (i in 1 until n) {
            val x = points[2 * i]
            val y = points[2 * i + 1]
            val mx = (lastX + x) / 2f
            val my = (lastY + y) / 2f
            
            if (widths[i] != runWidth) {
                runs.add(Run(path, runWidth))
                path = Path().apply { moveTo(midX, midY) }
                runWidth = widths[i]
            }
            path.quadTo(lastX, lastY, mx, my)
            midX = mx
            midY = my
            lastX = x
            lastY = y
        }
        path.lineTo(lastX + if (n == 1) 0.01f else 0f, lastY)
        runs.add(Run(path, runWidth))
        return runs
    }

    
    private fun isGrayMarker(stroke: BoardEngine.StrokeRec): Boolean =
        stroke.penStyle == PenStyle.MARKER.objType && MarkerInk.fromArgb(stroke.color) != MarkerInk.BLACK

    
    fun drawLayer(
        canvas: Canvas,
        strokes: List<BoardEngine.StrokeRec>,
        paint: Paint,
        widthScale: Float = 1f,
        colorOf: (BoardEngine.StrokeRec) -> Int = { it.color },
    ) {
        for (stroke in strokes) {
            if (isGrayMarker(stroke)) draw(canvas, stroke, paint, colorOf(stroke), widthScale)
        }
        val fills = strokes.filter { it.isShape && it.penStyle == PenStyle.FILLED_SHAPE.objType }
        val fillPath = Path()
        for (shape in fills) {
            draw(canvas, shape, paint, Color.BLACK, widthScale)
            fillPath.op(shape.path, Path.Op.UNION)
        }
        for (stroke in strokes) {
            if (stroke in fills || isGrayMarker(stroke)) continue
            draw(canvas, stroke, paint, colorOf(stroke), widthScale)
            if (!fillPath.isEmpty) {
                val save = canvas.save()
                canvas.clipPath(fillPath)
                draw(canvas, stroke, paint, Color.WHITE, widthScale, forceColor = true)
                canvas.restoreToCount(save)
            }
        }
    }

    fun draw(
        canvas: Canvas,
        stroke: BoardEngine.StrokeRec,
        paint: Paint,
        color: Int = stroke.color,
        widthScale: Float = 1f,
        forceColor: Boolean = false,
    ) {
        val oldAlpha = paint.alpha
        val oldDither = paint.isDither
        val oldFilter = paint.isFilterBitmap
        val oldXfermode = paint.xfermode
        val marker = stroke.penStyle == PenStyle.MARKER.objType && !stroke.isOutline
        val filledShape = stroke.penStyle == PenStyle.FILLED_SHAPE.objType && stroke.isShape
        val oldStyle = paint.style
        paint.color = if (marker && !forceColor) MarkerInk.fromArgb(stroke.color).argb else color
        if (stroke.isOutline && stroke.penStyle == PenStyle.MARKER.objType && !forceColor)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        if (filledShape) paint.style = Paint.Style.FILL
        if (marker) {
            paint.alpha = 255
        }
        paint.shader = null
        val runs = if (widthScale != 1f && stroke.penStyle == PenStyle.PEN.objType && !stroke.isShape && !stroke.isOutline) {
            CalligraphyRaster.runs(stroke.points, stroke.pressures, stroke.sampleScale,
                stroke.drawPathWidth, stroke.nibAngle, widthScale)
        } else stroke.runs
        for (run in runs) {
            paint.style = if (filledShape || run.filled) Paint.Style.FILL else oldStyle
            paint.strokeWidth = run.width * widthScale
            canvas.drawPath(run.path, paint)
        }
        paint.shader = null
        paint.alpha = oldAlpha
        paint.isDither = oldDither
        paint.isFilterBitmap = oldFilter
        paint.xfermode = oldXfermode
        paint.style = oldStyle
    }
}
