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
        
        const val LASSO_ACTION_SPACING_DP = 16f
        private const val LASSO_CORNER_DP = 28f
        private const val HANDLE_DP = 18f
        
        const val MOVE_HANDLE_RADIUS_DP = 17f
        private const val ERASER_RADIUS_DP = 12f
        
        private const val ERASER_LIGHT_FILL = 0xFFB7B7B7.toInt()
        private const val ERASER_LIGHT_BORDER = 0xFF7F7F7F.toInt()
        private const val ERASER_DARK_FILL = 0xFF7F7F7F.toInt()
        private const val ERASER_DARK_BORDER = 0xFFB7B7B7.toInt()
        
        const val ROTATE_HANDLE_GAP_DP = 30f
        const val ROTATE_HANDLE_RADIUS_DP = 15f
        
        private const val INK = 0xFF111111.toInt()

        
        fun rotateHandleCenter(framePx: RectF, density: Float, topInsetPx: Float, out: FloatArray) {
            val reach = (ROTATE_HANDLE_GAP_DP + 2f * ROTATE_HANDLE_RADIUS_DP) * density
            out[0] = framePx.centerX()
            out[1] = if (framePx.top - reach >= topInsetPx) {
                framePx.top - (ROTATE_HANDLE_GAP_DP + ROTATE_HANDLE_RADIUS_DP) * density
            } else {
                framePx.bottom + (ROTATE_HANDLE_GAP_DP + ROTATE_HANDLE_RADIUS_DP) * density
            }
        }
    }

    
    var topInsetPx = 0f

    private val density: Float get() = resources.displayMetrics.density.coerceAtLeast(1f)

    
    private var cardPreview: RectF? = null
    private var cardPreviewHandles = false

    
    private var framePreview: RectF? = null

    
    private val shapePreview = Path()
    private var shapePreviewVisible = false
    private val shapePreviewBounds = RectF()

    
    private var lassoFrame: RectF? = null
    
    private val lassoActionRects = ArrayList<RectF>()
    
    private val lassoActionTypes = ArrayList<String>()
    private var lassoVisible = true
    
    private var lassoCornerHandles = false
    
    private var lassoEdgeHandles = false
    
    private var lassoHorizontalEdgesOnly = false
    
    private var lassoOnDark = false
    
    private var lassoRotateHandle = false

    
    private var moveActive = false
    private var moveDx = 0f
    private var moveDy = 0f
    
    private var moveAngleDeg = 0f
    private var movePivotX = 0f
    private var movePivotY = 0f
    
    private var moveScaled = false
    private val moveScaleMatrix = Matrix()
    private val movePathScaled = Path()
    private val moveCardsScaled = ArrayList<RectF>()
    private val moveFrameScaled = RectF()
    private val moveFrame = RectF()
    private var moveFrameVisible = false
    private val moveCards = ArrayList<RectF>()
    private val movePath = Path()
    private val moveMatrix = Matrix()

    
    private var eraserVisible = false
    private var eraserX = 0f
    private var eraserY = 0f
    private var eraserRadiusPx = 0f
    
    private var eraserOnDark = false
    
    private val eraserTrail = Path()
    private var eraserTrailVisible = false
    private var eraserTrailRadiusPx = 0f
    private var eraserTrailOnDark = false

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
    private val fillBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF111111.toInt()
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF111111.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    
    private val eraserFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ERASER_LIGHT_FILL
    }
    private val eraserBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ERASER_LIGHT_BORDER
        strokeWidth = 2f
    }
    private val eraserFillDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ERASER_DARK_FILL
    }
    private val eraserBorderDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ERASER_DARK_BORDER
        strokeWidth = 2f
    }
    private val eraserTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        
        alpha = 255
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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
        eraserBorder.strokeWidth = 2f * d
        eraserBorderDark.strokeWidth = 2f * d
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

    

    
    fun showShapePreview(pathPx: Path) {
        val old = RectF(shapePreviewBounds)
        shapePreview.set(pathPx)
        shapePreview.computeBounds(shapePreviewBounds, true)
        shapePreviewVisible = true
        invalidateUnion(old, shapePreviewBounds)
    }

    fun hideShapePreview() {
        if (!shapePreviewVisible) return
        shapePreviewVisible = false
        shapePreview.reset()
        val old = RectF(shapePreviewBounds)
        shapePreviewBounds.setEmpty()
        invalidateUnion(old, shapePreviewBounds)
    }

    private fun invalidateUnion(a: RectF, b: RectF) {
        val u = RectF(a)
        if (u.isEmpty) u.set(b) else if (!b.isEmpty) u.union(b)
        if (u.isEmpty) { invalidate(); return }
        val pad = 6f * density
        invalidate(
            kotlin.math.floor(u.left - pad).toInt(),
            kotlin.math.floor(u.top - pad).toInt(),
            kotlin.math.ceil(u.right + pad).toInt(),
            kotlin.math.ceil(u.bottom + pad).toInt(),
        )
    }

    

    fun setLassoFrame(framePx: RectF?, actions: List<Pair<RectF, String>> = emptyList(),
                      cornerHandles: Boolean = false, edgeHandles: Boolean = false,
                      onDark: Boolean = false, rotateHandle: Boolean = false,
                      horizontalEdgesOnly: Boolean = false) {
        
        if (framePx == null && lassoFrame == null && lassoActionRects.isEmpty()) return
        lassoFrame = framePx?.let { RectF(it) }
        lassoActionRects.clear(); lassoActionTypes.clear()
        for ((r, t) in actions) { lassoActionRects.add(RectF(r)); lassoActionTypes.add(t) }
        lassoCornerHandles = cornerHandles
        lassoEdgeHandles = edgeHandles
        lassoHorizontalEdgesOnly = horizontalEdgesOnly
        lassoOnDark = onDark
        lassoRotateHandle = rotateHandle
        lassoVisible = true
        invalidate()
    }

    
    fun moveHandleHit(x: Float, y: Float, slopPx: Float): Boolean {
        if (!lassoVisible) return false
        val frame = lassoFrame ?: return false
        val r = MOVE_HANDLE_RADIUS_DP * density + slopPx
        val dx = x - frame.left; val dy = y - frame.top
        return dx * dx + dy * dy <= r * r
    }

    
    fun rotateHandleHit(x: Float, y: Float, slopPx: Float): Boolean {
        if (!lassoRotateHandle || !lassoVisible) return false
        val frame = lassoFrame ?: return false
        val c = FloatArray(2)
        rotateHandleCenter(frame, density, topInsetPx, c)
        val r = ROTATE_HANDLE_RADIUS_DP * density + slopPx
        val dx = x - c[0]; val dy = y - c[1]
        return dx * dx + dy * dy <= r * r
    }

    fun setLassoVisible(visible: Boolean) {
        if (lassoVisible == visible) return
        lassoVisible = visible
        invalidate()
    }

    
    fun lassoActionHit(x: Float, y: Float, slopPx: Float): String? {
        for (i in lassoActionRects.indices) {
            val r = lassoActionRects[i]
            if (x >= r.left - slopPx && x <= r.right + slopPx && y >= r.top - slopPx && y <= r.bottom + slopPx)
                return lassoActionTypes[i]
        }
        return null
    }

    

    fun beginMovePreview(framePx: RectF?, cardsPx: List<RectF>, inkPx: Path, strokeWidthPx: Float) {
        moveActive = true
        moveDx = 0f
        moveDy = 0f
        moveAngleDeg = 0f
        moveScaled = false
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

    
    fun updateRotatePreview(angleDeg: Float, pivotXPx: Float, pivotYPx: Float) {
        if (!moveActive) return
        moveAngleDeg = angleDeg
        movePivotX = pivotXPx
        movePivotY = pivotYPx
        invalidate()
    }

    
    fun updateScalePreview(sx: Float, sy: Float, pivotXPx: Float, pivotYPx: Float) {
        if (!moveActive) return
        moveScaled = true
        moveScaleMatrix.setScale(sx, sy, pivotXPx, pivotYPx)
        movePath.transform(moveScaleMatrix, movePathScaled)
        moveCardsScaled.clear()
        for (c in moveCards) moveCardsScaled.add(RectF(c).also { moveScaleMatrix.mapRect(it) })
        moveFrameScaled.set(moveFrame)
        moveScaleMatrix.mapRect(moveFrameScaled)
        invalidate()
    }

    fun endMovePreview() {
        if (!moveActive) return
        moveActive = false
        moveAngleDeg = 0f
        moveScaled = false
        moveCards.clear()
        moveCardsScaled.clear()
        movePath.reset()
        movePathScaled.reset()
        lassoVisible = true
        invalidate()
    }

    

    
    fun showEraserCursor(xPx: Float, yPx: Float, worldScale: Float, onDark: Boolean = false) {
        val wasVisible = eraserVisible
        val oldX = eraserX
        val oldY = eraserY
        val oldR = eraserRadiusPx
        eraserVisible = true
        eraserX = xPx
        eraserY = yPx
        eraserOnDark = onDark
        eraserRadiusPx = ERASER_RADIUS_DP * density
        if (wasVisible) invalidateCircle(oldX, oldY, oldR)
        invalidateCircle(eraserX, eraserY, eraserRadiusPx)
    }

    fun hideEraserCursor() {
        if (!eraserVisible) return
        eraserVisible = false
        invalidateCircle(eraserX, eraserY, eraserRadiusPx)
    }

    
    fun beginEraserStroke(xPx: Float, yPx: Float, onDark: Boolean = false) {
        eraserTrail.reset()
        eraserTrail.moveTo(xPx, yPx)
        eraserTrailVisible = true
        eraserTrailOnDark = onDark
        eraserTrailRadiusPx = ERASER_RADIUS_DP * density
        invalidateCircle(xPx, yPx, eraserTrailRadiusPx)
    }

    
    fun appendEraserStroke(xPx: Float, yPx: Float, onDark: Boolean = eraserTrailOnDark) {
        if (!eraserTrailVisible) {
            beginEraserStroke(xPx, yPx, onDark)
            return
        }
        val oldBounds = RectF()
        eraserTrail.computeBounds(oldBounds, true)
        eraserTrail.lineTo(xPx, yPx)
        eraserTrailOnDark = onDark
        val newBounds = RectF()
        eraserTrail.computeBounds(newBounds, true)
        invalidateTrailUnion(oldBounds, newBounds)
    }

    
    fun endEraserStroke() {
        if (!eraserTrailVisible) return
        val bounds = RectF()
        eraserTrail.computeBounds(bounds, true)
        eraserTrailVisible = false
        eraserTrail.reset()
        invalidateTrailUnion(bounds, RectF())
    }

    private fun invalidateTrailUnion(a: RectF, b: RectF) {
        val u = RectF(a)
        if (u.isEmpty && b.isEmpty) {
            
            invalidateCircle(a.centerX(), a.centerY(), eraserTrailRadiusPx)
            return
        }
        if (u.isEmpty) u.set(b) else if (!b.isEmpty) u.union(b)
        val pad = eraserTrailRadiusPx + 3f * density
        invalidate(
            kotlin.math.floor(u.left - pad).toInt(),
            kotlin.math.floor(u.top - pad).toInt(),
            kotlin.math.ceil(u.right + pad).toInt(),
            kotlin.math.ceil(u.bottom + pad).toInt(),
        )
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
        shapePreviewVisible = false
        shapePreview.reset()
        shapePreviewBounds.setEmpty()
        lassoFrame = null
        lassoActionRects.clear(); lassoActionTypes.clear()
        lassoCornerHandles = false
        lassoEdgeHandles = false
        lassoOnDark = false
        lassoRotateHandle = false
        moveAngleDeg = 0f
        moveActive = false
        moveCards.clear()
        movePath.reset()
        eraserVisible = false
        eraserOnDark = false
        eraserTrailVisible = false
        eraserTrail.reset()
        invalidate()
    }

    

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = density

        framePreview?.let { canvas.drawRect(it, dashPaint) }
        if (shapePreviewVisible) canvas.drawPath(shapePreview, dashPaint)

        cardPreview?.let { r ->
            canvas.drawRect(r, solidPaint)
            if (cardPreviewHandles) drawHandles(canvas, r, d)
        }

        if (lassoVisible) {
            lassoFrame?.let { frame ->
                
                if (lassoOnDark) setLassoInk(Color.WHITE, INK)
                drawLassoFrame(canvas, frame, d)
                if (lassoOnDark) setLassoInk(INK, Color.WHITE)
            }
            for (i in lassoActionRects.indices) {
                val type = lassoActionTypes[i]
                when (type) {
                    "trash" -> drawTrashButton(canvas, lassoActionRects[i], d)
                    "note" -> drawNoteButton(canvas, lassoActionRects[i], d)
                    "black" -> drawBlackButton(canvas, lassoActionRects[i], d)
                    "edit-text" -> drawEditTextButton(canvas, lassoActionRects[i], d)
                    "recognize" -> drawRecognizeButton(canvas, lassoActionRects[i], d)
                    "shot" -> drawShotButton(canvas, lassoActionRects[i], d)
                    "square", "circle", "iso", "equi", "right" -> drawShapeButton(canvas, lassoActionRects[i], d, type)
                    "fill", "hollow" -> drawPaintBucketButton(canvas, lassoActionRects[i], d, type == "fill")
                }
            }
        }

        if (moveActive) {
            val save = canvas.save()
            canvas.translate(moveDx, moveDy)
            if (moveAngleDeg != 0f) canvas.rotate(moveAngleDeg, movePivotX, movePivotY)
            val frame = if (moveScaled) moveFrameScaled else moveFrame
            val cards = if (moveScaled) moveCardsScaled else moveCards
            val path = if (moveScaled) movePathScaled else movePath
            if (moveFrameVisible) canvas.drawRect(frame, thinDashPaint)
            for (c in cards) canvas.drawRect(c, solidPaint)
            if (!path.isEmpty) canvas.drawPath(path, inkPaint)
            canvas.restoreToCount(save)
        }

        if (eraserTrailVisible) {
            eraserTrailPaint.color = if (eraserTrailOnDark) ERASER_DARK_FILL else ERASER_LIGHT_FILL
            eraserTrailPaint.strokeWidth = eraserTrailRadiusPx * 2f
            canvas.drawPath(eraserTrail, eraserTrailPaint)
        }

        if (eraserVisible) {
            val fill = if (eraserOnDark) eraserFillDark else eraserFill
            val border = if (eraserOnDark) eraserBorderDark else eraserBorder
            canvas.drawCircle(eraserX, eraserY, eraserRadiusPx, fill)
            canvas.drawCircle(eraserX, eraserY, eraserRadiusPx, border)
        }
    }

    
    private fun setLassoInk(ink: Int, paper: Int) {
        thinDashPaint.color = ink
        solidPaint.color = ink
        arrowPaint.color = ink
        fillWhite.color = paper
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
        
        val hr = MOVE_HANDLE_RADIUS_DP * d
        canvas.drawCircle(r.left, r.top, hr, fillWhite)
        canvas.drawCircle(r.left, r.top, hr, thinDashPaint)
        canvas.drawText("✥", r.left, r.top + arrowPaint.textSize * 0.36f, arrowPaint)
        if (lassoRotateHandle) drawRotateHandle(canvas, r, d)
        if (lassoCornerHandles) {
            
            val half = HANDLE_DP * d / 2f
            val corners = floatArrayOf(r.right, r.top, r.right, r.bottom, r.left, r.bottom)
            var i = 0
            while (i < corners.size) {
                val ax = corners[i]; val ay = corners[i + 1]
                canvas.drawRect(ax - half, ay - half, ax + half, ay + half, fillWhite)
                canvas.drawRect(ax - half, ay - half, ax + half, ay + half, solidPaint)
                i += 2
            }
        }
        if (lassoEdgeHandles) {
            
            val half = HANDLE_DP * d / 2f
            val cx = r.centerX(); val cy = r.centerY()
            val edges = if (lassoHorizontalEdgesOnly) floatArrayOf(r.right, cy, r.left, cy)
                        else floatArrayOf(cx, r.top, r.right, cy, cx, r.bottom, r.left, cy)
            var i = 0
            while (i < edges.size) {
                val ax = edges[i]; val ay = edges[i + 1]
                canvas.drawRect(ax - half, ay - half, ax + half, ay + half, fillWhite)
                canvas.drawRect(ax - half, ay - half, ax + half, ay + half, solidPaint)
                i += 2
            }
        }
        if (!lassoCornerHandles && !lassoEdgeHandles) {
            
            val len = LASSO_CORNER_DP * d
            val off = 4f * d
            canvas.drawLine(r.right + off - len, r.top - off, r.right + off, r.top - off, solidPaint)
            canvas.drawLine(r.right + off, r.top - off, r.right + off, r.top - off + len, solidPaint)
            canvas.drawLine(r.left - off, r.bottom + off - len, r.left - off, r.bottom + off, solidPaint)
            canvas.drawLine(r.left - off, r.bottom + off, r.left - off + len, r.bottom + off, solidPaint)
            canvas.drawLine(r.right + off - len, r.bottom + off, r.right + off, r.bottom + off, solidPaint)
            canvas.drawLine(r.right + off, r.bottom + off - len, r.right + off, r.bottom + off, solidPaint)
        }
    }

    
    private fun drawRotateHandle(canvas: Canvas, r: RectF, d: Float) {
        val c = FloatArray(2)
        rotateHandleCenter(r, d, topInsetPx, c)
        val radius = ROTATE_HANDLE_RADIUS_DP * d
        if (c[1] < r.top) canvas.drawLine(c[0], r.top, c[0], c[1] + radius, thinDashPaint)
        else canvas.drawLine(c[0], r.bottom, c[0], c[1] - radius, thinDashPaint)
        canvas.drawCircle(c[0], c[1], radius, fillWhite)
        canvas.drawCircle(c[0], c[1], radius, thinDashPaint)
        
        val ar = radius * 0.55f
        val oval = RectF(c[0] - ar, c[1] - ar, c[0] + ar, c[1] + ar)
        canvas.drawArc(oval, -60f, 270f, false, solidPaint)
        
        val endRad = Math.toRadians(210.0)
        val ex = c[0] + ar * Math.cos(endRad).toFloat()
        val ey = c[1] + ar * Math.sin(endRad).toFloat()
        val ah = 4.5f * d
        canvas.drawLine(ex, ey, ex + ah, ey + ah * 0.2f, solidPaint)
        canvas.drawLine(ex, ey, ex - ah * 0.2f, ey - ah, solidPaint)
    }

    
    private fun drawShapeButton(canvas: Canvas, r: RectF, d: Float, type: String) {
        val radius = r.width() / 2f
        val cx = r.centerX(); val cy = r.centerY()
        canvas.drawCircle(cx, cy, radius, fillWhite)
        canvas.drawCircle(cx, cy, radius, thinDashPaint)
        val s = 11f * d
        when (type) {
            "square" -> canvas.drawRect(cx - s, cy - s, cx + s, cy + s, solidPaint)
            "circle" -> canvas.drawCircle(cx, cy, s, solidPaint)
            "iso" -> {
                val p = Path()
                p.moveTo(cx, cy - s * 1.1f); p.lineTo(cx + s * 0.75f, cy + s * 0.9f); p.lineTo(cx - s * 0.75f, cy + s * 0.9f); p.close()
                canvas.drawPath(p, solidPaint)
            }
            "equi" -> {
                val h = s * 1.732f
                val p = Path()
                p.moveTo(cx, cy - h * 0.58f); p.lineTo(cx + s, cy + h * 0.42f); p.lineTo(cx - s, cy + h * 0.42f); p.close()
                canvas.drawPath(p, solidPaint)
            }
            "right" -> {
                val p = Path()
                p.moveTo(cx - s * 0.9f, cy - s); p.lineTo(cx - s * 0.9f, cy + s); p.lineTo(cx + s * 1.1f, cy + s); p.close()
                canvas.drawPath(p, solidPaint)
                
                val m = 4f * d
                canvas.drawLine(cx - s * 0.9f + m, cy + s, cx - s * 0.9f + m, cy + s - m, solidPaint)
                canvas.drawLine(cx - s * 0.9f + m, cy + s - m, cx - s * 0.9f, cy + s - m, solidPaint)
            }
        }
    }

    
    private fun drawPaintBucketButton(canvas: Canvas, r: RectF, d: Float, fill: Boolean) {
        val radius = r.width() / 2f
        val cx = r.centerX(); val cy = r.centerY()
        canvas.drawCircle(cx, cy, radius, fillWhite)
        canvas.drawCircle(cx, cy, radius, thinDashPaint)
        val s = 11f * d
        val bucket = Path().apply {
            moveTo(cx - s * .72f, cy - s * .25f)
            lineTo(cx + s * .72f, cy - s * .25f)
            lineTo(cx + s * .52f, cy + s * .95f)
            lineTo(cx - s * .52f, cy + s * .95f)
            close()
        }
        if (fill) {
            canvas.drawPath(bucket, fillBlack)
            canvas.drawOval(cx - s * .72f, cy - s * .43f, cx + s * .72f, cy - s * .05f, fillBlack)
            canvas.drawLine(cx + s * .2f, cy - s * 1.05f, cx + s * .2f, cy - s * .48f, solidPaint)
            canvas.drawLine(cx + s * .2f, cy - s * 1.05f, cx + s * .02f, cy - s * .78f, solidPaint)
            canvas.drawLine(cx + s * .2f, cy - s * 1.05f, cx + s * .38f, cy - s * .78f, solidPaint)
        } else {
            canvas.drawPath(bucket, solidPaint)
            canvas.drawOval(cx - s * .72f, cy - s * .43f, cx + s * .72f, cy - s * .05f, solidPaint)
            canvas.drawLine(cx - s * 1.05f, cy + s * 1.1f, cx + s * 1.05f, cy - s * 1.1f, solidPaint)
        }
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

    
    private fun drawNoteButton(canvas: Canvas, r: RectF, d: Float) {
        val radius = r.width() / 2f
        canvas.drawCircle(r.centerX(), r.centerY(), radius, fillWhite)
        canvas.drawCircle(r.centerX(), r.centerY(), radius, thinDashPaint)
        val cx = r.centerX(); val cy = r.centerY()
        val hw = 10f * d; val hh = 12f * d
        val fold = 5f * d
        
        val path = Path()
        path.moveTo(cx - hw, cy + hh)
        path.lineTo(cx - hw, cy - hh)
        path.lineTo(cx + hw - fold, cy - hh)
        path.lineTo(cx + hw, cy - hh + fold)
        path.lineTo(cx + hw, cy + hh)
        path.close()
        canvas.drawPath(path, solidPaint)
        
        canvas.drawLine(cx + hw - fold, cy - hh, cx + hw - fold, cy - hh + fold, solidPaint)
        canvas.drawLine(cx + hw - fold, cy - hh + fold, cx + hw, cy - hh + fold, solidPaint)
        
        canvas.drawLine(cx - hw + 3f * d, cy - 2f * d, cx + hw - 3f * d, cy - 2f * d, solidPaint)
        canvas.drawLine(cx - hw + 3f * d, cy + 4f * d, cx + hw - 3f * d, cy + 4f * d, solidPaint)
    }

    
    private fun drawBlackButton(canvas: Canvas, r: RectF, d: Float) {
        val radius = r.width() / 2f
        canvas.drawCircle(r.centerX(), r.centerY(), radius, fillWhite)
        canvas.drawCircle(r.centerX(), r.centerY(), radius, thinDashPaint)
        
        canvas.drawCircle(r.centerX(), r.centerY(), 11f * d, fillBlack)
    }

    
    private fun drawEditTextButton(canvas: Canvas, r: RectF, d: Float) {
        val radius = r.width() / 2f
        canvas.drawCircle(r.centerX(), r.centerY(), radius, fillWhite)
        canvas.drawCircle(r.centerX(), r.centerY(), radius, thinDashPaint)
        val cx = r.centerX(); val cy = r.centerY()
        val p = Path()
        p.moveTo(cx - 10f * d, cy + 9f * d)
        p.lineTo(cx - 7f * d, cy - 7f * d)
        p.lineTo(cx + 5f * d, cy - 11f * d)
        p.lineTo(cx + 10f * d, cy + 4f * d)
        p.lineTo(cx - 10f * d, cy + 9f * d)
        p.close()
        canvas.drawPath(p, solidPaint)
        canvas.drawLine(cx - 5f * d, cy - 2f * d, cx + 5f * d, cy - 5f * d, thinDashPaint)
        canvas.drawLine(cx - 6f * d, cy + 4f * d, cx + 4f * d, cy + 1f * d, thinDashPaint)
    }

    
    private fun drawShotButton(canvas: Canvas, r: RectF, d: Float) {
        val radius = r.width() / 2f
        canvas.drawCircle(r.centerX(), r.centerY(), radius, fillWhite)
        canvas.drawCircle(r.centerX(), r.centerY(), radius, thinDashPaint)
        val cx = r.centerX(); val cy = r.centerY()
        val hw = 10f * d; val hh = 12f * d
        canvas.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, solidPaint)
        val mountains = Path()
        mountains.moveTo(cx - hw + 3f * d, cy + hh - 4f * d)
        mountains.lineTo(cx - 3f * d, cy - 1f * d)
        mountains.lineTo(cx + 1f * d, cy + 4f * d)
        mountains.lineTo(cx + 4f * d, cy + 1f * d)
        mountains.lineTo(cx + hw - 3f * d, cy + hh - 4f * d)
        canvas.drawPath(mountains, solidPaint)
        canvas.drawCircle(cx + 4f * d, cy - 6f * d, 2.4f * d, fillBlack)
    }

    
    private fun drawRecognizeButton(canvas: Canvas, r: RectF, d: Float) {
        val radius = r.width() / 2f
        canvas.drawCircle(r.centerX(), r.centerY(), radius, fillWhite)
        canvas.drawCircle(r.centerX(), r.centerY(), radius, thinDashPaint)
        val cx = r.centerX(); val cy = r.centerY()
        canvas.drawLine(cx - 9f * d, cy - 11f * d, cx + 9f * d, cy - 11f * d, solidPaint)
        canvas.drawLine(cx, cy - 11f * d, cx, cy + 3f * d, solidPaint)
        val wave = Path()
        wave.moveTo(cx - 11f * d, cy + 10f * d)
        wave.cubicTo(cx - 7f * d, cy + 5f * d, cx - 3f * d, cy + 15f * d, cx, cy + 10f * d)
        wave.cubicTo(cx + 3f * d, cy + 5f * d, cx + 7f * d, cy + 15f * d, cx + 11f * d, cy + 10f * d)
        canvas.drawPath(wave, solidPaint)
    }
}
