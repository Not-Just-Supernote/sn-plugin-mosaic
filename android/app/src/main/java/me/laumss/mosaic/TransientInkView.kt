package me.laumss.mosaic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View


class TransientInkView(context: Context) : View(context) {

    companion object {
        private const val CLEAR_DELAY_MS = 160L
        private const val LASSO_DOT_RADIUS_DP = 1.5f
        private const val LASSO_DOT_SPACING_DP = 6f
        
        private const val LASSO_DOT_HALO_DP = 0.8f
    }

    private var lastX = 0f
    private var lastY = 0f
    private var hasLast = false
    private var distanceSinceDot = 0f
    private var dirty = false
    private var pendingClear: Runnable? = null

    
    private val lassoDots = mutableListOf<FloatArray>() 
    
    private var allMinX = Float.MAX_VALUE
    private var allMinY = Float.MAX_VALUE
    private var allMaxX = -Float.MAX_VALUE
    private var allMaxY = -Float.MAX_VALUE

    private val displayDensity: Float
        get() = resources.displayMetrics.density.coerceAtLeast(1f)
    private val lassoDotRadiusPx: Float get() = LASSO_DOT_RADIUS_DP * displayDensity
    private val lassoDotHaloPx: Float get() = LASSO_DOT_HALO_DP * displayDensity
    private val lassoDotSpacingPx: Float get() = LASSO_DOT_SPACING_DP * displayDensity

    fun beginStroke(x: Float, y: Float) {
        cancelPendingClear()
        if (dirty) clearNow()
        lassoDots.add(floatArrayOf(x, y))
        lastX = x
        lastY = y
        hasLast = true
        distanceSinceDot = 0f
        dirty = true
        invalidateDots(x, y, x, y)
    }

    fun appendStroke(x: Float, y: Float) {
        if (!hasLast) return
        val fromX = lastX
        val fromY = lastY
        appendLassoDots(fromX, fromY, x, y)
        lastX = x
        lastY = y
        dirty = true
    }

    
    private fun invalidateDots(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        if (minX < allMinX) allMinX = minX; if (maxX > allMaxX) allMaxX = maxX
        if (minY < allMinY) allMinY = minY; if (maxY > allMaxY) allMaxY = maxY
        val pad = kotlin.math.ceil(lassoDotRadiusPx + lassoDotHaloPx + 2f).toInt()
        invalidate(
            kotlin.math.floor(minX).toInt() - pad,
            kotlin.math.floor(minY).toInt() - pad,
            kotlin.math.ceil(maxX).toInt() + pad,
            kotlin.math.ceil(maxY).toInt() + pad,
        )
    }

    fun endStroke() {
        hasLast = false
        distanceSinceDot = 0f
        if (!dirty) return
        cancelPendingClear()
        val task = Runnable { clearNow() }
        pendingClear = task
        postDelayed(task, CLEAR_DELAY_MS)
    }

    
    fun clearImmediately() {
        cancelPendingClear()
        clearNow()
    }

    fun abortStroke() {
        cancelPendingClear()
        hasLast = false
        distanceSinceDot = 0f
        if (dirty) clearNow()
    }

    fun release() {
        cancelPendingClear()
        lassoDots.clear()
        hasLast = false
        distanceSinceDot = 0f
        dirty = false
        allMinX = Float.MAX_VALUE; allMinY = Float.MAX_VALUE
        allMaxX = -Float.MAX_VALUE; allMaxY = -Float.MAX_VALUE
    }

    private val lassoDotPaint = Paint().apply {
        color = 0xE6000000.toInt()
        style = Paint.Style.FILL
    }
    private val lassoDotHaloPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private fun cancelPendingClear() {
        pendingClear?.let { removeCallbacks(it) }
        pendingClear = null
    }

    private fun clearNow() {
        val hadInk = dirty
        lassoDots.clear()
        dirty = false
        if (hadInk && allMinX <= allMaxX) {
            val pad = kotlin.math.ceil(lassoDotRadiusPx + lassoDotHaloPx + 2f).toInt()
            invalidate(
                kotlin.math.floor(allMinX).toInt() - pad,
                kotlin.math.floor(allMinY).toInt() - pad,
                kotlin.math.ceil(allMaxX).toInt() + pad,
                kotlin.math.ceil(allMaxY).toInt() + pad,
            )
        }
        allMinX = Float.MAX_VALUE; allMinY = Float.MAX_VALUE
        allMaxX = -Float.MAX_VALUE; allMaxY = -Float.MAX_VALUE
    }

    private fun appendLassoDots(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val dx = toX - fromX
        val dy = toY - fromY
        val length = kotlin.math.sqrt(dx * dx + dy * dy)
        if (length <= 0f) return
        val spacing = lassoDotSpacingPx
        var offset = spacing - distanceSinceDot
        var lastEmittedOffset = -1f
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        while (offset <= length) {
            val ratio = offset / length
            val px = fromX + dx * ratio
            val py = fromY + dy * ratio
            lassoDots.add(floatArrayOf(px, py))
            if (px < minX) minX = px; if (px > maxX) maxX = px
            if (py < minY) minY = py; if (py > maxY) maxY = py
            lastEmittedOffset = offset
            offset += spacing
        }
        distanceSinceDot = if (lastEmittedOffset >= 0f) {
            length - lastEmittedOffset
        } else {
            distanceSinceDot + length
        }
        if (distanceSinceDot >= spacing) distanceSinceDot %= spacing
        if (lastEmittedOffset >= 0f) invalidateDots(minX, minY, maxX, maxY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lassoDots.isNotEmpty()) {
            val r = lassoDotRadiusPx
            val halo = r + lassoDotHaloPx
            for (dot in lassoDots) {
                canvas.drawCircle(dot[0], dot[1], halo, lassoDotHaloPaint)
                canvas.drawCircle(dot[0], dot[1], r, lassoDotPaint)
            }
        }
    }
}
