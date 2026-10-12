package me.laumss.mosaic

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Content bounds shared by edge drags, proportional drags and size presets. */
object CardSizeConstraints {
    // Keep in sync with imageStore.imageCardSizeFor: the initial display size is the floor.
    fun initialImageSize(width: Float, height: Float): PointF {
        val w = max(1f, width)
        val h = max(1f, height)
        val scale = min(BoardGeometry.MAX_CARD_SIZE / max(w, h),
            max(480f / max(w, h), BoardGeometry.MIN_IMAGE_CARD_SIZE / min(w, h)))
        return PointF(w * scale, h * scale)
    }

    fun minimum(card: BoardEngine.CardRec): PointF = when (card.kind) {
        "image" -> CardImageCache.naturalSize(card.imagePath)?.let { initialImageSize(it.x, it.y) }
            ?: PointF(card.width, card.height)
        "note" -> PointF(BoardGeometry.MIN_CARD_SIZE, BoardGeometry.MIN_CARD_SIZE)
        else -> measureText(card, max(BoardGeometry.MIN_CARD_SIZE, card.width))
    }

    private fun measureText(card: BoardEngine.CardRec, width: Float): PointF {
        val size = BoardContentView.measureTextCardSize(card.content, width,
            CardTextFormat.metricsFor(card.id), limitHeight = false)
        // Round outward so a line at its measured width still fits after a float round trip.
        return PointF(ceil(size.x), ceil(size.y))
    }

    fun fit(card: BoardEngine.CardRec, width: Float, height: Float, proportional: Boolean = false): PointF {
        if (card.kind == "note") return PointF(width, height)
        val floor = minimum(card)
        var w = max(width, floor.x)
        var h = max(height, if (card.kind == "image") floor.y else measureText(card, w).y)
        if (proportional) {
            val factor = max(w / max(width, 0.001f), h / max(height, 0.001f))
            w = width * factor
            h = height * factor
        }
        if (card.kind != "image") {
            val needed = measureText(card, w).y
            if (needed > h) {
                if (proportional) w *= needed / h
                h = needed
            }
        }
        return PointF(w, h)
    }

    fun constrainRect(card: BoardEngine.CardRec, rect: RectF, handle: BoardGeometry.Handle, proportional: Boolean) {
        val size = fit(card, rect.width(), rect.height(), proportional)
        val x = if (handle.movesLeft) rect.right - size.x else rect.left
        val y = if (handle.movesTop) rect.bottom - size.y else rect.top
        rect.set(x, y, x + size.x, y + size.y)
    }

    fun selectionScale(cards: List<BoardEngine.CardRec>, fx: Float, fy: Float, proportional: Boolean): PointF {
        var x = fx
        var y = fy
        for (card in cards) {
            if (card.kind == "note") continue
            val size = fit(card, card.width * x, card.height * y, proportional)
            x = max(x, size.x / card.width)
            y = max(y, size.y / card.height)
            if (proportional) { x = max(x, y); y = x }
        }
        return PointF(x, y)
    }
}
