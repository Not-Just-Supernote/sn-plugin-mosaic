package me.laumss.mosaic

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.max
import kotlin.math.min


object WhiteboardExport {

    
    const val CONTENT_PADDING = 32f

    
    const val MIN_EXPORT_SCALE = 0.05f

    
    private const val LABEL_LEFT = 16f
    private const val LABEL_TOP = 16f
    private const val LABEL_WIDTH = 240f
    private const val LABEL_HEIGHT = 52f
    private const val LABEL_PADDING_X = 14f
    private const val LABEL_TEXT_SIZE = 22f
    private const val LABEL_COLOR = 0xFF111111.toInt()

    class Plan(
        val whiteboard: BoardEngine.WhiteboardRec,
        
        val bounds: RectF,
        val cardCount: Int,
        val strokeCount: Int,
    )

    
    class Hotspot(val x: Float, val y: Float, val w: Float, val h: Float)

    
    fun plan(
        whiteboard: BoardEngine.WhiteboardRec,
        cards: Collection<BoardEngine.CardRec>,
        strokes: Collection<BoardEngine.StrokeRec>,
        padding: Float = CONTENT_PADDING,
    ): Plan? {
        val board = whiteboard.rect()
        var content: RectF? = null
        var cardCount = 0
        var strokeCount = 0

        val scratch = RectF()
        for (card in cards) {
            card.rect(scratch)
            if (!scratch.intersect(board)) continue
            content = union(content, scratch)
            cardCount++
        }
        for (stroke in strokes) {
            if (stroke.space != "canvas") continue
            val half = max(1f, stroke.width / 2f)
            scratch.set(stroke.bounds)
            scratch.inset(-half, -half)
            if (!scratch.intersect(board)) continue
            content = union(content, scratch)
            strokeCount++
        }
        val inner = content ?: return null

        val padded = RectF(inner.left - padding, inner.top - padding, inner.right + padding, inner.bottom + padding)
        if (!padded.intersect(board) || padded.width() <= 0f || padded.height() <= 0f) return null
        return Plan(whiteboard, padded, cardCount, strokeCount)
    }

    
    fun exportScale(bounds: RectF, viewWidthDp: Float, viewHeightDp: Float): Float {
        val w = max(1f, bounds.width())
        val h = max(1f, bounds.height())
        val fit = min(1f, min(viewWidthDp / w, viewHeightDp / h))
        return max(MIN_EXPORT_SCALE, fit)
    }

    
    fun drawLabel(canvas: Canvas, name: String, density: Float, widthPx: Int, heightPx: Int): Hotspot {
        val left = LABEL_LEFT * density
        val top = LABEL_TOP * density
        val width = max(1f, min(LABEL_WIDTH * density, widthPx - left))
        val height = max(1f, min(LABEL_HEIGHT * density, heightPx - top))
        val rect = RectF(left, top, left + width, top + height)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = LABEL_COLOR }
        canvas.drawRect(rect, fill)

        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = LABEL_TEXT_SIZE * density
            isFakeBoldText = true
            letterSpacing = 1f / LABEL_TEXT_SIZE
        }
        val maxTextWidth = max(1f, width - LABEL_PADDING_X * 2f * density)
        val shown = TextUtils.ellipsize(name, text, maxTextWidth, TextUtils.TruncateAt.END).toString()
        val baseline = rect.centerY() - (text.ascent() + text.descent()) / 2f
        canvas.drawText(shown, rect.left + LABEL_PADDING_X * density, baseline, text)

        return Hotspot(
            x = left / widthPx,
            y = top / heightPx,
            w = width / widthPx,
            h = height / heightPx,
        )
    }

    private fun union(a: RectF?, b: RectF): RectF =
        if (a == null) RectF(b) else a.apply { union(b) }
}
