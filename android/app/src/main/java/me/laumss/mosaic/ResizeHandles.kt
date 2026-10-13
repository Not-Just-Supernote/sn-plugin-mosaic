package me.laumss.mosaic

import android.graphics.PointF
import android.graphics.RectF

/** One handle policy for drawing and hit testing. */
object ResizeHandles {
    private val corners = listOf(BoardGeometry.Handle.TOP_LEFT, BoardGeometry.Handle.TOP_RIGHT,
        BoardGeometry.Handle.BOTTOM_RIGHT, BoardGeometry.Handle.BOTTOM_LEFT)
    private val edges = listOf(BoardGeometry.Handle.TOP, BoardGeometry.Handle.RIGHT,
        BoardGeometry.Handle.BOTTOM, BoardGeometry.Handle.LEFT)
    private val horizontal = listOf(BoardGeometry.Handle.RIGHT, BoardGeometry.Handle.LEFT)

    // Text cards get corners too: a corner drag scales the card and its text size together.
    fun forCard(kind: String?): List<BoardGeometry.Handle> =
        forSelection(kind != "note", true, kind == "note")

    fun forSelection(cornerHandles: Boolean, edgeHandles: Boolean, horizontalOnly: Boolean): List<BoardGeometry.Handle> =
        (if (cornerHandles) corners else emptyList()) +
            (if (edgeHandles) { if (horizontalOnly) horizontal else edges } else emptyList())

    fun x(handle: BoardGeometry.Handle, rect: RectF): Float = when {
        handle.movesLeft -> rect.left
        handle.movesRight -> rect.right
        else -> rect.centerX()
    }

    fun y(handle: BoardGeometry.Handle, rect: RectF): Float = when {
        handle.movesTop -> rect.top
        handle.movesBottom -> rect.bottom
        else -> rect.centerY()
    }

    fun hit(handles: List<BoardGeometry.Handle>, rect: RectF, px: Float, py: Float, radius: Float): BoardGeometry.Handle? =
        handles.minByOrNull { handle ->
            val dx = px - x(handle, rect)
            val dy = py - y(handle, rect)
            dx * dx + dy * dy
        }?.takeIf { handle ->
            val dx = px - x(handle, rect)
            val dy = py - y(handle, rect)
            dx * dx + dy * dy <= radius * radius
        }

    fun moveCenter(rect: RectF, density: Float, topInset: Float, viewWidth: Float, viewHeight: Float,
                   avoid: List<RectF> = emptyList()): PointF {
        val radius = InteractionOverlayView.MOVE_HANDLE_RADIUS_DP * density
        val offset = 56f * density
        val left = radius
        val top = topInset + radius
        val right = (viewWidth - radius).coerceAtLeast(left)
        val bottom = (viewHeight - radius).coerceAtLeast(top)
        val candidates = listOf(
            PointF(rect.left - offset, rect.top - offset), PointF(rect.right + offset, rect.top - offset),
            PointF(rect.left - offset, rect.bottom + offset), PointF(rect.right + offset, rect.bottom + offset),
            PointF(rect.left - offset, rect.top + offset), PointF(rect.right + offset, rect.top + offset),
            PointF(rect.left + offset, rect.top - offset), PointF(rect.left + offset, rect.bottom + offset),
            PointF(rect.left + offset, rect.top + offset),
        )
        return candidates.firstOrNull { p ->
            p.x in left..right && p.y in top..bottom &&
                hit(corners + edges, rect, p.x, p.y, 52f * density) == null &&
                avoid.none { p.x >= it.left - radius && p.x <= it.right + radius &&
                    p.y >= it.top - radius && p.y <= it.bottom + radius }
        } ?: PointF((rect.left + offset).coerceIn(left, right), (rect.top + offset).coerceIn(top, bottom))
    }
}
