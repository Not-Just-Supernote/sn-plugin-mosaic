package me.laumss.mosaic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View


class InteractionOverlayView(context: Context) : View(context) {

    companion object {
        const val LASSO_ACTION_SIZE_DP = 52f
        const val LASSO_ACTION_GAP_DP = 14f
        const val LASSO_ACTION_MARGIN_DP = 12f
        private const val LASSO_CORNER_DP = 28f
        private const val HANDLE_DP = 18f
        private const val ERASER_RADIUS_DP = 12f
    }

    private val density: Float get() = resources.displayMetrics.density.coerceAtLeast(1f)

    
    private var cardPreview: RectF? = null
    private var cardPreviewHandles = false

    
    private var framePreview: RectF? = null

    
    private var lassoFrame: RectF? = null
    private var lassoActionRect: RectF? = null
    private var lassoVisible = true

    
    private var moveActive = false
    private var moveDx = 0f
    private var moveDy = 0f
    private val moveFrame = RectF()
    private var moveFrameVisible = false
    private val moveCards = ArrayList<RectF>()
    private val movePath = Path()
    private val moveMatrix = Matrix()

    
    private var eraserVisible = false
    private var eraserX = 0f
    private var eraserY = 0f
    private var eraserRadiusPx = 0f

    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF111111.toInt()
        strokeWidth = 2f
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF111111.toInt()
        strokeWidth = 1.5f
    }
    private val thinDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF111111.toInt()
        strokeWidth = 1f
    }
    private val fillWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF111111.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val eraserFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x14111111
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    init {
        val d = density
        solidPaint.strokeWidth = 2f * d
        dashPaint.strokeWidth = 1.5f * d
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(8f * d, 6f * d), 0f)
        thinDashPaint.strokeWidth = 1f * d
        thinDashPaint.pathEffect = DashPathEffect(floatArrayOf(6f * d, 4f * d), 0f)
        arrowPaint.textSize = 16f * d
    }

    

    fun showCardPreview(rectPx: RectF, handles: Boolean) {
        val r = cardPreview ?: RectF().also { cardPreview = it }
        r.set(rectPx)
        cardPreviewHandles = handles
        invalidate()
    }

    fun hideCardPreview() {
        if (cardPreview == null) return
        cardPreview = null
        invalidate()
    }

    

    fun showFramePreview(rectPx: RectF) {
        val r = framePreview ?: RectF().also { framePreview = it }
        r.set(rectPx)
        invalidate()
    }

    fun hideFramePreview() {
        if (framePreview == null) return
        framePreview = null
        invalidate()
    }

    

    fun setLassoFrame(framePx: RectF?, actionPx: RectF?) {
        lassoFrame = framePx?.let { RectF(it) }
        lassoActionRect = actionPx?.let { RectF(it) }
        lassoVisible = true
        invalidate()
    }

    fun setLassoVisible(visible: Boolean) {
        if (lassoVisible == visible) return
        lassoVisible = visible
        invalidate()
    }

    fun lassoActionHit(x: Float, y: Float, slopPx: Float): Boolean {
        val r = lassoActionRect ?: return false
        return x >= r.left - slopPx && x <= r.right + slopPx && y >= r.top - slopPx && y <= r.bottom + slopPx
    }

    

    fun beginMovePreview(framePx: RectF?, cardsPx: List<RectF>, inkPx: Path, strokeWidthPx: Float) {
        moveActive = true
        moveDx = 0f
        moveDy = 0f
        moveFrameVisible = framePx != null
        if (framePx != null) moveFrame.set(framePx)
        moveCards.clear()
        for (c in cardsPx) moveCards.add(RectF(c))
        movePath.set(inkPx)
        inkPaint.strokeWidth = strokeWidthPx
        lassoVisible = false
        invalidate()
    }

    fun updateMovePreview(dxPx: Float, dyPx: Float) {
        if (!moveActive) return
        moveDx = dxPx
        moveDy = dyPx
        invalidate()
    }

    fun endMovePreview() {
        if (!moveActive) return
        moveActive = false
        moveCards.clear()
        movePath.reset()
        lassoVisible = true
        invalidate()
    }

    

    
    fun showEraserCursor(xPx: Float, yPx: Float, worldScale: Float) {
        val wasVisible = eraserVisible
        val oldX = eraserX
        val oldY = eraserY
        val oldR = eraserRadiusPx
        eraserVisible = true
        eraserX = xPx
        eraserY = yPx
        eraserRadiusPx = ERASER_RADIUS_DP * worldScale * density
        if (wasVisible) invalidateCircle(oldX, oldY, oldR)
        invalidateCircle(eraserX, eraserY, eraserRadiusPx)
    }

    fun hideEraserCursor() {
        if (!eraserVisible) return
        eraserVisible = false
        invalidateCircle(eraserX, eraserY, eraserRadiusPx)
    }

    private fun invalidateCircle(cx: Float, cy: Float, r: Float) {
        val pad = r + 3f * density
        invalidate(
            kotlin.math.floor(cx - pad).toInt(),
            kotlin.math.floor(cy - pad).toInt(),
            kotlin.math.ceil(cx + pad).toInt(),
            kotlin.math.ceil(cy + pad).toInt(),
        )
    }

    fun clearAll() {
        cardPreview = null
        framePreview = null
        lassoFrame = null
        lassoActionRect = null
        moveActive = false
        moveCards.clear()
        movePath.reset()
        eraserVisible = false
        invalidate()
    }

    

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = density

        framePreview?.let { canvas.drawRect(it, dashPaint) }

        cardPreview?.let { r ->
            canvas.drawRect(r, solidPaint)
            if (cardPreviewHandles) drawHandles(canvas, r, d)
        }

        if (lassoVisible) {
            lassoFrame?.let { drawLassoFrame(canvas, it, d) }
            lassoActionRect?.let { drawTrashButton(canvas, it, d) }
        }

        if (moveActive) {
            val save = canvas.save()
            canvas.translate(moveDx, moveDy)
            if (moveFrameVisible) canvas.drawRect(moveFrame, thinDashPaint)
            for (c in moveCards) canvas.drawRect(c, solidPaint)
            if (!movePath.isEmpty) canvas.drawPath(movePath, inkPaint)
            canvas.restoreToCount(save)
        }

        if (eraserVisible) {
            canvas.drawCircle(eraserX, eraserY, eraserRadiusPx, eraserFill)
            canvas.drawCircle(eraserX, eraserY, eraserRadiusPx, solidPaint)
        }
    }

    private fun drawHandles(canvas: Canvas, r: RectF, d: Float) {
        val half = HANDLE_DP * d / 2f
        val cx = r.centerX()
        val cy = r.centerY()
        val anchors = floatArrayOf(
            r.left, r.top, cx, r.top, r.right, r.top,
            r.right, cy, r.right, r.bottom, cx, r.bottom,
            r.left, r.bottom, r.left, cy,
        )
        var i = 0
        while (i < anchors.size) {
            val ax = anchors[i]
            val ay = anchors[i + 1]
            canvas.drawRect(ax - half, ay - half, ax + half, ay + half, fillWhite)
            canvas.drawRect(ax - half, ay - half, ax + half, ay + half, solidPaint)
            i += 2
        }
    }

    private fun drawLassoFrame(canvas: Canvas, r: RectF, d: Float) {
        canvas.drawRect(r, thinDashPaint)
        val len = LASSO_CORNER_DP * d
        val off = 4f * d
        
        val hr = 17f * d
        canvas.drawCircle(r.left, r.top, hr, fillWhite)
        canvas.drawCircle(r.left, r.top, hr, thinDashPaint)
        canvas.drawText("✥", r.left, r.top + arrowPaint.textSize * 0.36f, arrowPaint)
        
        canvas.drawLine(r.right + off - len, r.top - off, r.right + off, r.top - off, solidPaint)
        canvas.drawLine(r.right + off, r.top - off, r.right + off, r.top - off + len, solidPaint)
        canvas.drawLine(r.left - off, r.bottom + off - len, r.left - off, r.bottom + off, solidPaint)
        canvas.drawLine(r.left - off, r.bottom + off, r.left - off + len, r.bottom + off, solidPaint)
        canvas.drawLine(r.right + off - len, r.bottom + off, r.right + off, r.bottom + off, solidPaint)
        canvas.drawLine(r.right + off, r.bottom + off - len, r.right + off, r.bottom + off, solidPaint)
    }

    private fun drawTrashButton(canvas: Canvas, r: RectF, d: Float) {
        val radius = r.width() / 2f
        canvas.drawCircle(r.centerX(), r.centerY(), radius, fillWhite)
        canvas.drawCircle(r.centerX(), r.centerY(), radius, thinDashPaint)
        
        val cx = r.centerX()
        val top = r.centerY() - 14f * d
        canvas.drawRect(cx - 4.5f * d, top, cx + 4.5f * d, top + 3f * d, solidPaint)
        canvas.drawLine(cx - 12f * d, top + 4f * d, cx + 12f * d, top + 4f * d, solidPaint)
        canvas.drawRect(cx - 9f * d, top + 7f * d, cx + 9f * d, top + 28f * d, solidPaint)
        canvas.drawLine(cx - 3f * d, top + 11f * d, cx - 3f * d, top + 24f * d, solidPaint)
        canvas.drawLine(cx + 3f * d, top + 11f * d, cx + 3f * d, top + 24f * d, solidPaint)
    }
}
