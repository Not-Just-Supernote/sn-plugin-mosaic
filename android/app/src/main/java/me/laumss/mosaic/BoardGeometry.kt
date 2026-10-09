package me.laumss.mosaic

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt


object BoardGeometry {

    
    const val MIN_CARD_SIZE = 80f
    const val MIN_IMAGE_CARD_SIZE = MIN_CARD_SIZE * 3f
    const val MAX_CARD_SIZE = 2000f
    const val DEFAULT_CARD_WIDTH = 320f
    const val DEFAULT_CARD_HEIGHT = 280f

    
    const val CONNECT_DISTANCE = 40f
    const val SNAP_DISTANCE = 56f
    const val BRIDGE_GAP = 18f
    const val CARD_STROKE_MIN_DISTANCE = 24f
    const val CARD_STROKE_MIN_OVERLAP = 48f
    const val CARD_STROKE_GAP = 72f

    
    const val CARD_HANDLE_HIT_PX = 34f
    const val CARD_DRAG_THRESHOLD_PX = 14f
    const val CARD_LONG_PRESS_MS = 500L
    const val LASSO_MIN_BOX_PX = 50f

    
    
    
    const val MIN_ZOOM_SCALE = 0.36666667f
    const val NORMAL_ZOOM_SCALE = 0.8f
    const val MAX_ZOOM_SCALE = 2.0f
    val UI_ZOOM_LEVELS = intArrayOf(10, 25, 50, 75, 100, 125, 150, 175, 200)
    val ZOOM_LEVELS: FloatArray = FloatArray(UI_ZOOM_LEVELS.size) { scaleForZoomPercent(UI_ZOOM_LEVELS[it].toFloat()) }
    val DEFAULT_ZOOM: Float = scaleForZoomPercent(75f)
    val RESET_ZOOM: Float = scaleForZoomPercent(100f)
    val GESTURE_MIN_ZOOM: Float = scaleForZoomPercent(75f)
    val GESTURE_MAX_ZOOM: Float = scaleForZoomPercent(200f)

    enum class Handle(val movesLeft: Boolean, val movesRight: Boolean, val movesTop: Boolean, val movesBottom: Boolean) {
        TOP_LEFT(true, false, true, false),
        TOP_RIGHT(false, true, true, false),
        BOTTOM_RIGHT(false, true, false, true),
        BOTTOM_LEFT(true, false, false, true),
        TOP(false, false, true, false),
        RIGHT(false, true, false, false),
        BOTTOM(false, false, false, true),
        LEFT(true, false, false, false);

        
        val isCorner: Boolean get() = (movesLeft || movesRight) && (movesTop || movesBottom)
    }

    class SnapResult(val x: Float, val y: Float, val targetId: String?)

    class SizeLevel(val factor: Float, val neighborId: String, val rect: RectF)

    

    fun minCardSize(kind: String?): Float = if (kind == "image") MIN_IMAGE_CARD_SIZE else MIN_CARD_SIZE

    
    fun clampCardSize(width: Float, height: Float, kind: String?, out: PointF = PointF()): PointF {
        val lo = minCardSize(kind)
        out.set(
            max(lo, min(MAX_CARD_SIZE, width)).roundToInt().toFloat(),
            max(lo, min(MAX_CARD_SIZE, height)).roundToInt().toFloat(),
        )
        return out
    }

    
    fun cardRectFromFrame(frame: RectF, kind: String? = "text"): RectF {
        val size = clampCardSize(frame.width(), frame.height(), kind)
        val x = frame.left + (frame.width() - size.x) / 2f
        val y = frame.top + (frame.height() - size.y) / 2f
        return RectF(x, y, x + size.x, y + size.y)
    }

    

    fun pointInRect(x: Float, y: Float, r: RectF): Boolean =
        x >= r.left && x <= r.right && y >= r.top && y <= r.bottom

    fun pointHitsCard(card: BoardEngine.CardRec, x: Float, y: Float): Boolean =
        x >= card.x && x <= card.x + card.width && y >= card.y && y <= card.y + card.height

    
    fun topCardAt(cardsByZ: List<BoardEngine.CardRec>, x: Float, y: Float): BoardEngine.CardRec? {
        var hit: BoardEngine.CardRec? = null
        for (card in cardsByZ) if (pointHitsCard(card, x, y)) hit = card
        return hit
    }

    fun nextZIndex(cards: Collection<BoardEngine.CardRec>): Int =
        (cards.maxOfOrNull { it.zIndex } ?: 0) + 1

    
    fun handleAt(card: BoardEngine.CardRec, x: Float, y: Float, scale: Float): Handle? {
        val radius = CARD_HANDLE_HIT_PX / max(scale, 0.25f)
        val left = card.x
        val right = card.x + card.width
        val top = card.y
        val bottom = card.y + card.height
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        fun hit(ax: Float, ay: Float) = hypot(x - ax, y - ay) <= radius
        return when {
            hit(left, top) -> Handle.TOP_LEFT
            hit(right, top) -> Handle.TOP_RIGHT
            hit(right, bottom) -> Handle.BOTTOM_RIGHT
            hit(left, bottom) -> Handle.BOTTOM_LEFT
            hit(cx, top) -> Handle.TOP
            hit(right, cy) -> Handle.RIGHT
            hit(cx, bottom) -> Handle.BOTTOM
            hit(left, cy) -> Handle.LEFT
            else -> null
        }
    }

    
    fun resizeRect(handle: Handle, startX: Float, startY: Float, start: RectF, worldX: Float, worldY: Float, kind: String?, out: RectF): RectF {
        val dx = worldX - startX
        val dy = worldY - startY
        val requestedW = start.width() + (if (handle.movesRight) dx else 0f) - (if (handle.movesLeft) dx else 0f)
        val requestedH = start.height() + (if (handle.movesBottom) dy else 0f) - (if (handle.movesTop) dy else 0f)
        val size = clampCardSize(requestedW, requestedH, kind)
        val x = if (handle.movesLeft) start.right - size.x else start.left
        val y = if (handle.movesTop) start.bottom - size.y else start.top
        out.set(x, y, x + size.x, y + size.y)
        return out
    }

    
    fun resizeProportional(handle: Handle, startX: Float, startY: Float, start: RectF, worldX: Float, worldY: Float, kind: String?, out: RectF): RectF {
        val oldCornerX = if (handle.movesLeft) start.left else start.right
        val oldCornerY = if (handle.movesTop) start.top else start.bottom
        val ax = if (handle.movesLeft) start.right else start.left
        val ay = if (handle.movesTop) start.bottom else start.top
        val ox = oldCornerX - ax
        val oy = oldCornerY - ay
        val curX = oldCornerX + (worldX - startX)
        val curY = oldCornerY + (worldY - startY)
        val denom = ox * ox + oy * oy
        var f = if (denom > 0f) ((curX - ax) * ox + (curY - ay) * oy) / denom else 1f
        f = f.coerceIn(0.1f, 10f)
        val aspect = if (start.width() > 0f) start.height() / start.width() else 1f
        applyAspectSize(start.width() * f, aspect, kind, handle, start, out)
        return out
    }

    
    fun resizeWidthKeepAspect(handle: Handle, startX: Float, startY: Float, start: RectF, worldX: Float, worldY: Float, kind: String?, out: RectF): RectF {
        val dx = worldX - startX
        val requestedW = start.width() + (if (handle.movesRight) dx else 0f) - (if (handle.movesLeft) dx else 0f)
        val aspect = if (start.width() > 0f) start.height() / start.width() else 1f
        applyAspectSize(requestedW, aspect, kind, handle, start, out)
        val w = out.width()
        val h = out.height()
        val x = if (handle.movesLeft) start.right - w else start.left
        out.set(x, start.top, x + w, start.top + h)
        return out
    }

    private fun applyAspectSize(requestedW: Float, aspect: Float, kind: String?, handle: Handle, start: RectF, out: RectF) {
        val lo = minCardSize(kind)
        val a = if (aspect > 0f) aspect else 1f
        var w = max(lo, min(MAX_CARD_SIZE, requestedW))
        var h = w * a
        if (h > MAX_CARD_SIZE) { h = MAX_CARD_SIZE; w = h / a }
        if (h < lo) { h = lo; w = h / a }
        if (w > MAX_CARD_SIZE) { w = MAX_CARD_SIZE; h = w * a }
        if (w < lo) { w = lo; h = w * a }
        w = w.roundToInt().toFloat()
        h = h.roundToInt().toFloat()
        val x = if (handle.movesLeft) start.right - w else start.left
        val y = if (handle.movesTop) start.bottom - h else start.top
        out.set(x, y, x + w, y + h)
    }

    

    private fun overlap(a0: Float, a1: Float, b0: Float, b1: Float): Float = max(0f, min(a1, b1) - max(a0, b0))

    fun edgeDistance(ax: Float, ay: Float, aw: Float, ah: Float, b: BoardEngine.CardRec): Float {
        val hGap = max(max(b.x - (ax + aw), ax - (b.x + b.width)), 0f)
        val vGap = max(max(b.y - (ay + ah), ay - (b.y + b.height)), 0f)
        return hypot(hGap, vGap)
    }

    fun edgeDistance(a: BoardEngine.CardRec, b: BoardEngine.CardRec): Float =
        edgeDistance(a.x, a.y, a.width, a.height, b)

    
    fun canConnect(a: BoardEngine.CardRec, b: BoardEngine.CardRec): Boolean =
        a.id != b.id && a.colored == b.colored

    
    fun findSnap(
        others: Iterable<BoardEngine.CardRec>,
        card: BoardEngine.CardRec,
        x: Float,
        y: Float,
    ): SnapResult {
        val w = card.width
        val h = card.height
        var nearest: BoardEngine.CardRec? = null
        var nearestDist = Float.MAX_VALUE
        for (other in others) {
            if (!canConnect(card, other)) continue
            val d = edgeDistance(x, y, w, h, other)
            if (d >= CONNECT_DISTANCE) continue
            if (d < nearestDist) {
                nearest = other
                nearestDist = d
            }
        }
        val other = nearest ?: return SnapResult(x, y, null)

        var snapX = x
        var snapY = y
        var snapMove = SNAP_DISTANCE
        var snapped = false
        fun consider(cx: Float, cy: Float) {
            val move = hypot(cx - x, cy - y)
            if (move <= snapMove) {
                snapMove = move
                snapX = cx
                snapY = cy
                snapped = true
            }
        }
        if (overlap(y, y + h, other.y, other.y + other.height) >= CARD_STROKE_MIN_OVERLAP) {
            consider(other.x - w - BRIDGE_GAP, y)
            consider(other.x + other.width + BRIDGE_GAP, y)
        }
        if (overlap(x, x + w, other.x, other.x + other.width) >= CARD_STROKE_MIN_OVERLAP) {
            consider(x, other.y - h - BRIDGE_GAP)
            consider(x, other.y + other.height + BRIDGE_GAP)
        }
        
        
        
        
        
        return if (snapped) SnapResult(snapX, snapY, other.id) else SnapResult(x, y, null)
    }

    

    
    fun cardFromStrokeRect(source: BoardEngine.CardRec, endX: Float, endY: Float): RectF {
        val w = source.width
        val h = source.height
        val dx = endX - (source.x + w / 2f)
        val dy = endY - (source.y + h / 2f)
        val x: Float
        val y: Float
        if (abs(dx) >= abs(dy)) {
            x = if (dx >= 0f) source.x + w + CARD_STROKE_GAP else source.x - w - CARD_STROKE_GAP
            val ov = min(CARD_STROKE_MIN_OVERLAP, h)
            y = max(source.y + ov - h, min(source.y + h - ov, endY - h / 2f))
        } else {
            y = if (dy >= 0f) source.y + h + CARD_STROKE_GAP else source.y - h - CARD_STROKE_GAP
            val ov = min(CARD_STROKE_MIN_OVERLAP, w)
            x = max(source.x + ov - w, min(source.x + w - ov, endX - w / 2f))
        }
        return RectF(x, y, x + w, y + h)
    }

    

    private val SIZE_LEVEL_FACTORS = floatArrayOf(0.5f, 2f, 4f)
    private const val SIZE_LEVEL_SHRINK_NEAR_RATIO = 1.1f

    fun nearestConnectedCard(
        card: BoardEngine.CardRec,
        cards: Map<String, BoardEngine.CardRec>,
        connections: Iterable<BoardEngine.ConnectionRec>,
    ): BoardEngine.CardRec? {
        var nearest: BoardEngine.CardRec? = null
        var nearestDist = Float.MAX_VALUE
        for (c in connections) {
            val otherId = when (card.id) {
                c.fromId -> c.toId
                c.toId -> c.fromId
                else -> continue
            }
            if (otherId == card.id) continue
            val other = cards[otherId] ?: continue
            val d = edgeDistance(card, other)
            if (d < nearestDist) {
                nearest = other
                nearestDist = d
            }
        }
        return nearest
    }

    
    fun sizeLevelRect(card: BoardEngine.CardRec, neighbor: BoardEngine.CardRec, width: Float, height: Float): RectF {
        val hGap = card.x >= neighbor.x + neighbor.width || card.x + card.width <= neighbor.x
        val vGap = card.y >= neighbor.y + neighbor.height || card.y + card.height <= neighbor.y
        val cx = card.x + card.width / 2f
        val cy = card.y + card.height / 2f
        val ncx = neighbor.x + neighbor.width / 2f
        val ncy = neighbor.y + neighbor.height / 2f
        val horizontal = hGap || (!vGap && abs(cx - ncx) >= abs(cy - ncy))
        val x: Float
        val y: Float
        if (horizontal) {
            x = (if (cx >= ncx) card.x else card.x + card.width - width).roundToInt().toFloat()
            y = (cy - height / 2f).roundToInt().toFloat()
        } else {
            x = (cx - width / 2f).roundToInt().toFloat()
            y = (if (cy >= ncy) card.y else card.y + card.height - height).roundToInt().toFloat()
        }
        return RectF(x, y, x + width, y + height)
    }

    fun computeSizeLevels(
        card: BoardEngine.CardRec,
        cards: Map<String, BoardEngine.CardRec>,
        connections: Iterable<BoardEngine.ConnectionRec>,
    ): List<SizeLevel> {
        val neighbor = nearestConnectedCard(card, cards, connections) ?: return emptyList()
        val minSide = minCardSize(card.kind)
        val levels = ArrayList<SizeLevel>(3)
        for (factor in SIZE_LEVEL_FACTORS) {
            val grow = factor > 1f
            val rawW = neighbor.width * factor
            val rawH = if (card.kind == "note" && card.width > 0f) rawW * (card.height / card.width)
                       else neighbor.height * factor
            if (!grow && (rawW < minSide * SIZE_LEVEL_SHRINK_NEAR_RATIO || rawH < minSide * SIZE_LEVEL_SHRINK_NEAR_RATIO)) continue
            if (grow && (rawW > MAX_CARD_SIZE || rawH > MAX_CARD_SIZE)) continue
            val clamped = clampCardSize(rawW, rawH, card.kind)
            val width = clamped.x
            val height = if (card.kind == "note" && card.width > 0f) {
                (width * (card.height / card.width)).coerceIn(MIN_CARD_SIZE, MAX_CARD_SIZE)
            } else clamped.y
            levels.add(SizeLevel(factor, neighbor.id, sizeLevelRect(card, neighbor, width, height)))
        }
        return levels
    }

    

    fun scaleForZoomPercent(percent: Float): Float =
        if (percent <= 100f) {
            MIN_ZOOM_SCALE + ((percent - 10f) / 90f) * (NORMAL_ZOOM_SCALE - MIN_ZOOM_SCALE)
        } else {
            val t = ((percent - 100f) / 100f).coerceIn(0f, 1f)
            NORMAL_ZOOM_SCALE * ((MAX_ZOOM_SCALE / NORMAL_ZOOM_SCALE).toDouble().pow(t.toDouble())).toFloat()
        }

    fun zoomPercentForScale(scale: Float): Float {
        val c = clampZoom(scale)
        return if (c <= NORMAL_ZOOM_SCALE) {
            10f + ((c - MIN_ZOOM_SCALE) / (NORMAL_ZOOM_SCALE - MIN_ZOOM_SCALE)) * 90f
        } else {
            100f + (ln(c / NORMAL_ZOOM_SCALE) / ln(MAX_ZOOM_SCALE / NORMAL_ZOOM_SCALE)) * 100f
        }
    }

    fun clampZoom(scale: Float): Float = min(MAX_ZOOM_SCALE, max(MIN_ZOOM_SCALE, scale))

    fun nearestZoomIndex(scale: Float): Int {
        var best = 0
        var bestDelta = Float.MAX_VALUE
        for (i in ZOOM_LEVELS.indices) {
            val d = abs(ZOOM_LEVELS[i] - scale)
            if (d < bestDelta) {
                best = i
                bestDelta = d
            }
        }
        return best
    }

    
    fun zoomAroundScreenPoint(panX: Float, panY: Float, scale: Float, nextScale: Float, ax: Float, ay: Float): FloatArray {
        val s = if (scale > 0f) scale else 1f
        val worldX = (ax - panX) / s
        val worldY = (ay - panY) / s
        return floatArrayOf(ax - worldX * nextScale, ay - worldY * nextScale)
    }
}
