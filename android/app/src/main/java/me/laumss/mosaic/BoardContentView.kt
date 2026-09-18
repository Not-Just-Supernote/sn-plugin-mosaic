package me.laumss.mosaic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.text.TextPaint
import android.util.Log
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor


class BoardContentView(context: Context) : View(context), BoardEngine.Listener {

    companion object {
        private const val TAG = "MosaicBoardContent"
        
        private const val TILE_WORLD = 512f
        
        private const val CACHE_BUDGET_BYTES = 64L * 1024 * 1024
        
        private const val PERF_LOG_INTERVAL_MS = 1000L
        
        private const val GESTURE_FRAME_MIN_MS = 70L

        
        
        private const val CARD_FILL_COLOR = 0xFFD9D9D9.toInt()
        
        private const val CARD_COLORED_FILL_COLOR = 0xFF000000.toInt()
        
        private const val CARD_SHADOW_COLOR = 0xFF77838D.toInt()
        private const val CARD_SHADOW_EDGE_COLOR = 0xFF9BA5B1.toInt()
        private const val CARD_SHADOW_OFFSET = 8f
        private const val CARD_SHADOW_EDGE = 3f
        private const val CARD_SHADOW_EXTENT = CARD_SHADOW_OFFSET + CARD_SHADOW_EDGE
        
        private const val CARD_OUTLINE_WIDTH = 3f
        private const val CARD_PLACEHOLDER_COLOR = 0xFF8A8A8A.toInt()
        
        private const val CARD_OUTLINE_EMPHASIS_COLOR = Color.BLACK
        private const val CARD_SELECTED_COLOR = Color.BLACK
        private const val CARD_SELECTED_WIDTH = 2f
        private const val CARD_RADIUS = 0f
        private const val CARD_HANDLE_SIZE = 18f
        private const val TITLE_COLOR = 0xFF111111.toInt()
        private const val CARD_TEXT_SIZE = 16f
        private const val CARD_TEXT_LINE_MUL = 1.28f
        private const val CARD_TEXT_COLOR = Color.BLACK
        private const val CARD_PADDING_X = 16f
        private const val CARD_PADDING_Y = 14f
        private val textMeasurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = CARD_TEXT_SIZE
            isSubpixelText = true
        }

        
        fun measureTextCardSize(raw: String, maxWidth: Float = BoardGeometry.DEFAULT_CARD_WIDTH): android.graphics.PointF {
            val innerMax = (maxWidth - CARD_PADDING_X * 2f).coerceAtLeast(1f)
            var contentW = 0f
            var y = CARD_PADDING_Y
            forEachTextRun(raw, innerMax, textMeasurePaint) { _, lineWidth, lineHeight ->
                if (lineWidth > contentW) contentW = lineWidth
                y += lineHeight
            }
            return android.graphics.PointF(
                (contentW + CARD_PADDING_X * 2f).coerceIn(BoardGeometry.MIN_CARD_SIZE, maxWidth),
                (y + CARD_PADDING_Y).coerceIn(BoardGeometry.MIN_CARD_SIZE, BoardGeometry.MAX_CARD_SIZE),
            )
        }

        private fun forEachTextRun(
            raw: String,
            innerMax: Float,
            paint: TextPaint,
            emit: (text: String, width: Float, height: Float) -> Unit,
        ) {
            for (paragraph in raw.replace("\r\n", "\n").split('\n')) {
                val heading = paragraph.trimStart().takeWhile { it == '#' }.length
                val text = paragraph.trimStart().removePrefix("#".repeat(heading)).trimStart()
                if (text.isEmpty()) {
                    emit("", 0f, CARD_TEXT_SIZE * 0.7f)
                    continue
                }
                paint.textSize = when (heading) { 1 -> 22f; 2 -> 19f; 3 -> 17f; else -> CARD_TEXT_SIZE }
                paint.isFakeBoldText = heading > 0
                val lineH = paint.textSize * CARD_TEXT_LINE_MUL
                val words = text.split(Regex("\\s+"))
                var line = ""
                fun flush(chunk: String) {
                    if (chunk.isEmpty()) return
                    var start = 0
                    while (start < chunk.length) {
                        val count = paint.breakText(chunk, start, chunk.length, true, innerMax, null)
                        val n = if (count > 0) count else 1
                        val piece = chunk.substring(start, start + n)
                        emit(piece, paint.measureText(piece), lineH)
                        start += n
                    }
                }
                for (word in words) {
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (line.isNotEmpty() && paint.measureText(candidate) > innerMax) {
                        flush(line)
                        line = word
                    } else {
                        line = candidate
                    }
                }
                if (line.isNotEmpty()) flush(line)
            }
            paint.textSize = CARD_TEXT_SIZE
            paint.isFakeBoldText = false
        }

        
        private const val WB_CORNER_LEN = 44f
        private const val WB_CORNER_STROKE = 5f
        private const val WB_CORNER_OFFSET = 4f
        private const val WB_NAME_SIZE = 20f
        private const val WB_NAME_COLOR = 0xFF555555.toInt()

        
        private const val TRANSLUCENT_BG_COLOR = 0xD0FFFFFF.toInt()
    }

    private data class TileKey(val ix: Int, val iy: Int, val scaleBits: Int)

    private class TileEntry(var bitmap: Bitmap) {
        
        var dirty = false
    }

    
    private val cache = object : LinkedHashMap<TileKey, TileEntry>(32, 0.75f, true) {}
    private val cacheLock = Any()
    private var cacheBytes = 0L
    private val pending = LinkedHashSet<TileKey>()

    
    
    private var rasterThread: HandlerThread? = null
    private var rasterHandler: Handler? = null
    private var rasterScheduled = false

    
    
    private var rasteringKey: TileKey? = null
    private var rasteringDirty = false

    private var lastScaleBits = 0
    private var lastPerfLogAt = 0L

    
    @Volatile
    private var rasterScaleOverride: Float? = null

    
    private val fallbackTiles = HashMap<Long, Bitmap>()
    private var fallbackScaleBits = 0

    
    private val rasterScale: Float
        get() = rasterScaleOverride ?: BoardEngine.scale

    
    @Volatile
    private var gestureFreezeTiles = false

    fun setGestureFreezeTiles(active: Boolean) {
        if (gestureFreezeTiles == active) return
        gestureFreezeTiles = active
        if (!active) {
            
            
            synchronized(cacheLock) {
                forEachVisibleTileLocked { key ->
                    val entry = cache[key]
                    if (entry == null || entry.dirty) pending.add(key)
                }
                if (pending.isNotEmpty()) {
                    invalidateWhenFresh = true
                    scheduleRasterLocked()
                }
            }
        }
        postInvalidateOnAnimation()
    }

    

    @Volatile
    private var gestureThrottle = false
    private var lastGestureFrameAt = 0L
    private var gestureFramePosted = false
    private val gestureFrameTask = Runnable {
        gestureFramePosted = false
        postInvalidateOnAnimation()
    }

    
    fun setGestureThrottle(active: Boolean) {
        if (gestureThrottle == active) return
        gestureThrottle = active
        if (!active) {
            removeCallbacks(gestureFrameTask)
            gestureFramePosted = false
            postInvalidateOnAnimation()
        }
    }

    private fun requestViewportFrame() {
        if (!gestureThrottle) {
            postInvalidateOnAnimation()
            return
        }
        if (gestureFramePosted) return
        val elapsed = SystemClock.uptimeMillis() - lastGestureFrameAt
        if (elapsed >= GESTURE_FRAME_MIN_MS) {
            postInvalidateOnAnimation()
            return
        }
        gestureFramePosted = true
        postDelayed(gestureFrameTask, GESTURE_FRAME_MIN_MS - elapsed)
    }

    
    fun setZoomPreview(active: Boolean) {
        if (active) {
            if (rasterScaleOverride == null) rasterScaleOverride = BoardEngine.scale
        } else {
            val locked = rasterScaleOverride ?: return
            rasterScaleOverride = null
            val nextBits = BoardEngine.scale.toRawBits()
            lastScaleBits = nextBits
            onScaleChanged(locked.toRawBits(), nextBits)
            postInvalidateOnAnimation()
        }
    }

    
    private fun onScaleChanged(previousBits: Int, nextBits: Int) {
        if (previousBits == nextBits) return
        synchronized(cacheLock) {
            for (bitmap in fallbackTiles.values) bitmap.recycle()
            fallbackTiles.clear()
            fallbackScaleBits = previousBits
            val iterator = cache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.scaleBits == nextBits) continue
                cacheBytes -= entry.value.bitmap.allocationByteCount
                iterator.remove()
                if (entry.key.scaleBits == previousBits && tileOnScreen(entry.key.ix, entry.key.iy)) {
                    fallbackTiles[cellKey(entry.key.ix, entry.key.iy)] = entry.value.bitmap
                } else {
                    entry.value.bitmap.recycle()
                }
            }
            pending.removeAll { it.scaleBits != nextBits }
        }
    }

    private fun releaseFallbackLocked() {
        if (fallbackTiles.isEmpty()) return
        for (bitmap in fallbackTiles.values) bitmap.recycle()
        fallbackTiles.clear()
    }

    private fun cellKey(ix: Int, iy: Int): Long = (ix.toLong() shl 32) or (iy.toLong() and 0xffffffffL)

    
    private fun tileOnScreen(ix: Int, iy: Int): Boolean {
        val densityValue = density
        val scalePx = BoardEngine.scale * densityValue
        val tilePx = TILE_WORLD * scalePx
        val left = BoardEngine.panX * densityValue + ix * tilePx
        val top = BoardEngine.panY * densityValue + iy * tilePx
        return left < width && top < height && left + tilePx > 0 && top + tilePx > 0
    }

    private val density: Float
        get() = resources.displayMetrics.density.coerceAtLeast(1f)

    
    
    private val strokePaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val neckFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = CARD_FILL_COLOR
    }
    private val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = CARD_FILL_COLOR
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = CARD_SHADOW_COLOR
    }
    private val shadowEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = CARD_SHADOW_EDGE_COLOR
    }
    
    private val shadowPathNear = Path()
    private val shadowPathFar = Path()
    private val wbCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = TITLE_COLOR
        strokeWidth = WB_CORNER_STROKE
    }
    private val wbDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = WB_NAME_COLOR
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val wbNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = WB_NAME_SIZE
        isFakeBoldText = true
    }
    private val wbBadgePaint = Paint().apply { color = TITLE_COLOR }
    
    private val selectionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = CARD_SELECTED_COLOR
        strokeWidth = CARD_SELECTED_WIDTH
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = CARD_SELECTED_COLOR
        strokeWidth = CARD_SELECTED_WIDTH
    }
    
    private val emphasisStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = CARD_OUTLINE_EMPHASIS_COLOR
        strokeWidth = CARD_OUTLINE_WIDTH
    }
    private val tilePaint = Paint().apply { isFilterBitmap = false }
    
    private val cardImagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cardTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CARD_TEXT_COLOR
        textSize = CARD_TEXT_SIZE
        isSubpixelText = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val thread = HandlerThread("MosaicBoardRaster").apply { start() }
        rasterThread = thread
        rasterHandler = Handler(thread.looper)
        BoardEngine.attachListener(this)
    }

    override fun onDetachedFromWindow() {
        BoardEngine.detachListener(this)
        rasterHandler?.removeCallbacksAndMessages(null)
        rasterThread?.quitSafely()
        rasterHandler = null
        rasterThread = null
        synchronized(cacheLock) {
            for (entry in cache.values) entry.bitmap.recycle()
            cache.clear()
            pending.clear()
            cacheBytes = 0L
            rasterScheduled = false
            releaseFallbackLocked()
        }
        rasterScaleOverride = null
        removeCallbacks(gestureFrameTask)
        gestureFramePosted = false
        super.onDetachedFromWindow()
    }

    

    
    @Volatile
    private var deferRefresh = false

    
    @Volatile
    private var invalidateWhenFresh = false

    val isDeferringRefresh: Boolean get() = deferRefresh

    
    fun setDeferRefresh(defer: Boolean) {
        if (deferRefresh == defer) return
        deferRefresh = defer
        if (defer) {
            
            invalidateWhenFresh = false
        } else {
            postInvalidateOnAnimation()
        }
    }

    
    
    @Volatile
    private var translucentOverlay = false

    fun setTranslucentOverlay(enabled: Boolean) {
        if (translucentOverlay == enabled) return
        translucentOverlay = enabled
        if (enabled && !suspendTranslucent) {
            setBackgroundColor(Color.TRANSPARENT)
        } else if (!enabled && !translucentBackground) {
            setBackgroundColor(Color.WHITE)
        }
        markAllCachedTilesDirtyLocked()
        postInvalidateOnAnimation()
    }

    
    private fun translucentFogActive(): Boolean =
        (translucentOverlay || translucentBackground) && !suspendTranslucent

    
    @Volatile
    private var backgroundTemplate = BackgroundTemplate.BLANK

    
    @Volatile
    private var templatePageWidth = 0f

    
    fun setBackgroundTemplate(template: BackgroundTemplate, pageWidth: Float) {
        if (backgroundTemplate == template && templatePageWidth == pageWidth) return
        backgroundTemplate = template
        templatePageWidth = pageWidth
        synchronized(cacheLock) {
            for (entry in cache.values) entry.bitmap.recycle()
            cache.clear()
            pending.clear()
            cacheBytes = 0L
            if (rasteringKey != null) rasteringDirty = true
            releaseFallbackLocked()
        }
        postInvalidateOnAnimation()
    }

    @Volatile
    private var translucentBackground = false
    fun setTranslucentBackground(translucent: Boolean) {
        if (translucentBackground == translucent) return
        translucentBackground = translucent
        setBackgroundColor(if (translucent) Color.TRANSPARENT else Color.WHITE)
        synchronized(cacheLock) {
            for (entry in cache.values) entry.bitmap.recycle()
            cache.clear()
            pending.clear()
            cacheBytes = 0L
            
            if (rasteringKey != null) rasteringDirty = true
            releaseFallbackLocked()
        }
        postInvalidateOnAnimation()
    }

    
    fun refreshAfterRaster() {
        deferRefresh = false
        var wait = false
        synchronized(cacheLock) {
            forEachVisibleTileLocked { key ->
                val entry = cache[key]
                if (entry == null || entry.dirty) pending.add(key)
            }
            if (pending.isNotEmpty() || rasteringKey != null) {
                wait = true
                invalidateWhenFresh = true
                scheduleRasterLocked()
            } else {
                invalidateWhenFresh = false
            }
        }
        if (!wait) postInvalidateOnAnimation()
    }

    
    fun hasPendingVisibleWork(): Boolean = synchronized(cacheLock) {
        if (pending.isNotEmpty() || rasteringKey != null) return true
        var stale = false
        forEachVisibleTileLocked { key ->
            val entry = cache[key]
            if (entry == null || entry.dirty) stale = true
        }
        stale
    }

    
    private inline fun forEachVisibleTileLocked(block: (TileKey) -> Unit) {
        val densityValue = density
        val scalePx = BoardEngine.scale * densityValue
        val panXPx = BoardEngine.panX * densityValue
        val panYPx = BoardEngine.panY * densityValue
        val tilePx = TILE_WORLD * scalePx
        if (width == 0 || height == 0 || tilePx <= 0f) return
        val scaleBits = rasterScale.toRawBits()
        val minIx = floor((-panXPx) / tilePx).toInt()
        val maxIx = floor((width - panXPx) / tilePx).toInt()
        val minIy = floor((-panYPx) / tilePx).toInt()
        val maxIy = floor((height - panYPx) / tilePx).toInt()
        for (iy in minIy..maxIy) for (ix in minIx..maxIx) block(TileKey(ix, iy, scaleBits))
    }

    
    @Volatile
    var onContentChanged: ((reason: String, moved: Boolean) -> Unit)? = null

    
    var onContentSettled: (() -> Unit)? = null

    
    @Volatile
    var suspendTranslucent = false
        private set

    private val settleWaiters = ArrayList<Runnable>()

    @Volatile
    private var settleRequested = false

    
    @Volatile
    var holdPaint = false
        private set

    fun setHoldPaint(hold: Boolean) {
        if (holdPaint == hold) return
        holdPaint = hold
        if (!hold) postInvalidateOnAnimation()
    }


    fun setSuspendTranslucent(suspend: Boolean) {
        if (suspendTranslucent == suspend) return
        suspendTranslucent = suspend
        
        
        if (translucentOverlay || translucentBackground) {
            setBackgroundColor(if (suspend) Color.WHITE else Color.TRANSPARENT)
        }
        postInvalidateOnAnimation()
    }

    fun runWhenSettled(callback: Runnable) {
        settleWaiters.add(callback)
        markSettleRequested()
        postInvalidateOnAnimation()
    }

    fun clearSettleWaiters() {
        settleWaiters.clear()
    }

    private fun showsAccent(card: BoardEngine.CardRec): Boolean = card.colored

    
    fun markSettleRequested() {
        settleRequested = true
    }

    

    override fun onSceneChanged(dirtyWorld: RectF?, contentMoved: Boolean) {
        synchronized(cacheLock) {
            if (dirtyWorld == null) {
                for (entry in cache.values) entry.dirty = true
                if (rasteringKey != null) rasteringDirty = true
            } else {
                for ((key, entry) in cache) {
                    if (entry.dirty) continue
                    if (tileWorldRect(key).intersects(
                            dirtyWorld.left, dirtyWorld.top, dirtyWorld.right, dirtyWorld.bottom,
                        )
                    ) {
                        entry.dirty = true
                    }
                }
                val active = rasteringKey
                if (active != null && !rasteringDirty
                    && tileWorldRect(active).intersects(
                        dirtyWorld.left, dirtyWorld.top, dirtyWorld.right, dirtyWorld.bottom,
                    )
                ) {
                    rasteringDirty = true
                }
            }
        }
        settleRequested = true
        if (!deferRefresh) postInvalidateOnAnimation()
        onContentChanged?.invoke("scene", contentMoved)
    }

    override fun onViewportChanged() {
        settleRequested = true
        
        invalidateWhenFresh = false
        onContentChanged?.invoke("viewport", true)
        
        val scaleBits = rasterScale.toRawBits()
        if (scaleBits != lastScaleBits) {
            val previous = lastScaleBits
            lastScaleBits = scaleBits
            if (previous != 0) onScaleChanged(previous, scaleBits)
        }
        requestViewportFrame()
    }

    override fun onSelectionChanged() {
        postInvalidateOnAnimation()
    }

    

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (holdPaint) return
        val startedAt = System.nanoTime()
        if (gestureThrottle) lastGestureFrameAt = SystemClock.uptimeMillis()
        val scale = BoardEngine.scale
        val densityValue = density
        val scalePx = scale * densityValue
        val panXPx = BoardEngine.panX * densityValue
        val panYPx = BoardEngine.panY * densityValue
        val tilePx = TILE_WORLD * scalePx
        if (width == 0 || height == 0 || tilePx <= 0f) return

        if (translucentFogActive()) {
            
            
            
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawColor(TRANSLUCENT_BG_COLOR)
        } else {
            canvas.drawColor(Color.WHITE)
        }

        
        
        val scaleBits = rasterScale.toRawBits()
        val previewing = rasterScaleOverride != null
        val minIx = floor((-panXPx) / tilePx).toInt()
        val maxIx = floor((width - panXPx) / tilePx).toInt()
        val minIy = floor((-panYPx) / tilePx).toInt()
        val maxIy = floor((height - panYPx) / tilePx).toInt()

        var missing = 0
        var stale = 0
        val src = Rect()
        val dst = RectF()
        
        val freeze = gestureFreezeTiles
        synchronized(cacheLock) {
            tilePaint.isFilterBitmap = previewing || fallbackTiles.isNotEmpty()
            for (iy in minIy..maxIy) {
                for (ix in minIx..maxIx) {
                    val key = TileKey(ix, iy, scaleBits)
                    val entry = cache[key]
                    val left = panXPx + ix * tilePx
                    val top = panYPx + iy * tilePx
                    dst.set(left, top, left + tilePx, top + tilePx)
                    if (entry != null) {
                        src.set(0, 0, entry.bitmap.width, entry.bitmap.height)
                        canvas.drawBitmap(entry.bitmap, src, dst, tilePaint)
                        if (entry.dirty) {
                            stale += 1
                            
                            if (!deferRefresh && !freeze && pending.add(key)) scheduleRasterLocked()
                        }
                    } else {
                        missing += 1
                        val fallback = fallbackTiles[cellKey(ix, iy)]
                        if (fallback != null) {
                            src.set(0, 0, fallback.width, fallback.height)
                            canvas.drawBitmap(fallback, src, dst, tilePaint)
                        }
                        
                        
                        if (!freeze && pending.add(key)) scheduleRasterLocked()
                    }
                }
            }
            if (missing == 0 && !previewing) releaseFallbackLocked()
        }

        drawSelection(canvas, panXPx, panYPx, scalePx)

        if (settleRequested && missing == 0 && stale == 0 && !deferRefresh) {
            settleRequested = false
            val waiters = ArrayList(settleWaiters)
            settleWaiters.clear()
            onContentSettled?.invoke()
            if (waiters.isNotEmpty()) post { for (waiter in waiters) waiter.run() }
        }

        val now = System.currentTimeMillis()
        if ((missing > 0 || stale > 0) && now - lastPerfLogAt > PERF_LOG_INTERVAL_MS) {
            lastPerfLogAt = now
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
            Log.i(
                TAG,
                "[MosaicRenderPerf] draw ms=${"%.2f".format(elapsedMs)} " +
                    "tiles=${(maxIx - minIx + 1) * (maxIy - minIy + 1)} missing=$missing stale=$stale",
            )
        }
    }

    
    @Volatile
    private var outlineEmphasis = false

    fun setOutlineEmphasis(active: Boolean) {
        if (outlineEmphasis == active) return
        outlineEmphasis = active
        synchronized(cacheLock) {
            for (entry in cache.values) entry.dirty = true
            if (rasteringKey != null) rasteringDirty = true
            forEachVisibleTileLocked { key ->
                val entry = cache[key]
                if (entry == null || entry.dirty) pending.add(key)
            }
            if (pending.isNotEmpty()) scheduleRasterLocked()
        }
        postInvalidateOnAnimation()
    }

    private fun drawSelection(canvas: Canvas, panXPx: Float, panYPx: Float, scalePx: Float) {
        val selected = BoardEngine.selectedCardIds
        if (selected.isEmpty()) return
        val hidden = BoardEngine.hiddenCardId
        synchronized(BoardEngine.lock) {
            for (id in selected) {
                if (id == hidden) continue
                val card = BoardEngine.cards[id] ?: continue
                val left = panXPx + card.x * scalePx
                val top = panYPx + card.y * scalePx
                val right = panXPx + (card.x + card.width) * scalePx
                val bottom = panYPx + (card.y + card.height) * scalePx
                selectionBorderPaint.strokeWidth = CARD_SELECTED_WIDTH * scalePx
                canvas.drawRect(left, top, right, bottom, selectionBorderPaint)
                val half = CARD_HANDLE_SIZE * scalePx / 2f
                handleBorderPaint.strokeWidth = CARD_SELECTED_WIDTH * scalePx
                val centerX = (left + right) / 2f
                val centerY = (top + bottom) / 2f
                val anchors = floatArrayOf(
                    left, top, centerX, top, right, top,
                    right, centerY, right, bottom, centerX, bottom,
                    left, bottom, left, centerY,
                )
                var index = 0
                while (index < anchors.size) {
                    val ax = anchors[index]
                    val ay = anchors[index + 1]
                    canvas.drawRect(ax - half, ay - half, ax + half, ay + half, handleFillPaint)
                    canvas.drawRect(ax - half, ay - half, ax + half, ay + half, handleBorderPaint)
                    index += 2
                }
            }
        }
    }

    

    
    private fun scheduleRasterLocked() {
        if (rasterScheduled) return
        val handler = rasterHandler ?: return
        rasterScheduled = true
        handler.post { rasterLoop() }
    }

    private fun rasterLoop() {
        while (true) {
            val key = synchronized(cacheLock) {
                val next = pending.firstOrNull()
                if (next == null) {
                    rasterScheduled = false
                    null
                } else {
                    pending.remove(next)
                    rasteringKey = next
                    rasteringDirty = false
                    next
                }
            } ?: return

            
            if (!tileVisible(key)) {
                synchronized(cacheLock) { rasteringKey = null }
                notifyTileProcessed(produced = false)
                continue
            }

            val startedAt = System.nanoTime()
            val bitmap = rasterTile(key)
            if (bitmap == null) {
                synchronized(cacheLock) { rasteringKey = null }
                notifyTileProcessed(produced = false)
                continue
            }
            synchronized(cacheLock) {
                val previous = cache.remove(key)
                if (previous != null) {
                    cacheBytes -= previous.bitmap.allocationByteCount
                    previous.bitmap.recycle()
                }
                val entry = TileEntry(bitmap)
                
                
                entry.dirty = rasteringDirty
                if (entry.dirty && !deferRefresh) pending.add(key)
                cache[key] = entry
                cacheBytes += bitmap.allocationByteCount
                rasteringKey = null
                evictLocked()
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
            if (elapsedMs > 24) {
                Log.i(
                    TAG,
                    "[MosaicRenderPerf] raster tile=(${key.ix},${key.iy}) ms=${"%.1f".format(elapsedMs)}",
                )
            }
            notifyTileProcessed(produced = true)
        }
    }

    
    private fun notifyTileProcessed(produced: Boolean) {
        if (invalidateWhenFresh) {
            val drained = synchronized(cacheLock) { pending.isEmpty() && rasteringKey == null }
            if (drained) {
                invalidateWhenFresh = false
                postInvalidateOnAnimation()
            }
        } else if (produced) {
            postInvalidateOnAnimation()
        }
    }

    
    private fun markAllCachedTilesDirtyLocked() {
        synchronized(cacheLock) {
            for (entry in cache.values) entry.dirty = true
            if (rasteringKey != null) rasteringDirty = true
        }
    }

    private fun evictLocked() {
        val iterator = cache.entries.iterator()
        while (cacheBytes > CACHE_BUDGET_BYTES && iterator.hasNext()) {
            val entry = iterator.next()
            cacheBytes -= entry.value.bitmap.allocationByteCount
            entry.value.bitmap.recycle()
            iterator.remove()
        }
    }

    private fun tileVisible(key: TileKey): Boolean {
        if (rasterScale.toRawBits() != key.scaleBits) return false
        return tileOnScreen(key.ix, key.iy)
    }

    
    private fun tileWorldRect(key: TileKey): RectF = RectF(
        key.ix * TILE_WORLD,
        key.iy * TILE_WORLD,
        (key.ix + 1) * TILE_WORLD,
        (key.iy + 1) * TILE_WORLD,
    )

    private fun rasterTile(key: TileKey): Bitmap? {
        val scale = Float.fromBits(key.scaleBits)
        if (scale <= 0f) return null
        val scalePx = scale * density
        val sizePx = ceil(TILE_WORLD * scalePx).toInt().coerceAtLeast(1)
        val bitmap = try {
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "raster: bitmap alloc failed size=$sizePx", error)
            synchronized(cacheLock) {
                for (entry in cache.values) entry.bitmap.recycle()
                cache.clear()
                cacheBytes = 0L
            }
            return null
        }
        val canvas = Canvas(bitmap)
        
        
        canvas.scale(scalePx, scalePx)
        val worldRect = tileWorldRect(key)
        canvas.translate(-worldRect.left, -worldRect.top)

        synchronized(BoardEngine.lock) {
            TemplatePaper.draw(canvas, worldRect, backgroundTemplate, templatePageWidth)
            drawWhiteboardFrames(canvas, worldRect)
            drawCanvasStrokes(canvas, worldRect)
            drawLiquidAndCards(canvas, worldRect)
        }
        return bitmap
    }

    

    
    fun renderExport(world: RectF, scalePx: Float, onDone: (Bitmap?) -> Unit) {
        val handler = rasterHandler
        if (handler == null || scalePx <= 0f || world.width() <= 0f || world.height() <= 0f) {
            onDone(null)
            return
        }
        handler.post {
            val widthPx = ceil(world.width() * scalePx).toInt().coerceAtLeast(1)
            val heightPx = ceil(world.height() * scalePx).toInt().coerceAtLeast(1)
            val bitmap = try {
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            } catch (error: OutOfMemoryError) {
                Log.w(TAG, "export: bitmap alloc failed ${widthPx}x$heightPx", error)
                null
            }
            if (bitmap == null) {
                onDone(null)
                return@post
            }
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.scale(scalePx, scalePx)
            canvas.translate(-world.left, -world.top)
            synchronized(BoardEngine.lock) {
                drawCanvasStrokes(canvas, world)
                drawLiquidAndCards(canvas, world)
            }
            onDone(bitmap)
        }
    }

    private fun drawWhiteboardFrames(canvas: Canvas, world: RectF) {
        for (wb in BoardEngine.whiteboards.values) {
            val frame = RectF(wb.x, wb.y, wb.x + wb.width, wb.y + wb.height)
            val padded = RectF(frame).apply { inset(-8f, -8f) }
            if (!RectF.intersects(padded, world)) continue
            if (wb.current) canvas.drawRect(frame, wbDashPaint)
            drawWhiteboardCorners(canvas, frame)
            drawWhiteboardName(canvas, wb, frame)
        }
    }

    private fun drawWhiteboardCorners(canvas: Canvas, frame: RectF) {
        val len = WB_CORNER_LEN
        val offset = WB_CORNER_OFFSET
        val half = WB_CORNER_STROKE / 2f
        
        val left = frame.left - offset
        val top = frame.top - offset
        val right = frame.right + offset
        val bottom = frame.bottom + offset
        
        canvas.drawLine(left, top + half, left + len, top + half, wbCornerPaint)
        canvas.drawLine(left + half, top, left + half, top + len, wbCornerPaint)
        
        canvas.drawLine(right - len, top + half, right, top + half, wbCornerPaint)
        canvas.drawLine(right - half, top, right - half, top + len, wbCornerPaint)
        
        canvas.drawLine(left, bottom - half, left + len, bottom - half, wbCornerPaint)
        canvas.drawLine(left + half, bottom - len, left + half, bottom, wbCornerPaint)
        
        canvas.drawLine(right - len, bottom - half, right, bottom - half, wbCornerPaint)
        canvas.drawLine(right - half, bottom - len, right - half, bottom, wbCornerPaint)
    }

    private fun drawWhiteboardName(canvas: Canvas, wb: BoardEngine.WhiteboardRec, frame: RectF) {
        if (wb.name.isEmpty()) return
        val textX = frame.left + 52f
        val textTop = frame.top + 8f
        if (wb.current) {
            val textWidth = wbNamePaint.measureText(wb.name)
            val badge = RectF(
                textX,
                textTop,
                textX + textWidth + 20f,
                textTop + WB_NAME_SIZE * 1.25f + 4f,
            )
            canvas.drawRect(badge, wbBadgePaint)
            wbNamePaint.color = Color.WHITE
            canvas.drawText(wb.name, textX + 10f, textTop + 2f - wbNamePaint.ascent(), wbNamePaint)
        } else {
            wbNamePaint.color = WB_NAME_COLOR
            canvas.drawText(wb.name, textX, textTop - wbNamePaint.ascent(), wbNamePaint)
        }
    }

    private val strokeQueryScratch = ArrayList<BoardEngine.StrokeRec>(64)

    private fun drawCanvasStrokes(canvas: Canvas, world: RectF) {
        strokeQueryScratch.clear()
        BoardEngine.queryCanvasStrokes(world, strokeQueryScratch)
        for (stroke in strokeQueryScratch) {
            TchRaster.draw(canvas, stroke, strokePaint)
        }
    }

    
    private fun neckTouchesHidden(neck: BoardEngine.NeckRec, hidden: String?): Boolean {
        val hiddenId = hidden ?: return false
        val connection = BoardEngine.connections[neck.id] ?: return false
        return connection.fromId == hiddenId || connection.toId == hiddenId
    }

    
    private fun shadowedIntersects(bounds: RectF, world: RectF): Boolean =
        bounds.left < world.right && bounds.top < world.bottom &&
            bounds.right + CARD_SHADOW_EXTENT > world.left && bounds.bottom + CARD_SHADOW_EXTENT > world.top

    
    private val shadowFarUnion = Path()

    
    private fun shadowCopiesOf(rect: RectF?, path: Path?) {
        val off = CARD_SHADOW_OFFSET
        val far = CARD_SHADOW_OFFSET + CARD_SHADOW_EDGE
        if (rect != null) {
            shadowPathNear.rewind()
            shadowPathNear.addRect(rect.left + off, rect.top + off, rect.right + off, rect.bottom + off, Path.Direction.CW)
            shadowPathFar.rewind()
            shadowPathFar.addRect(rect.left + far, rect.top + far, rect.right + far, rect.bottom + far, Path.Direction.CW)
        } else if (path != null) {
            path.offset(off, off, shadowPathNear)
            path.offset(far, far, shadowPathFar)
        }
    }

    
    private fun drawSilhouetteShadow(canvas: Canvas, world: RectF, hidden: String?) {
        val cardRect = RectF()
        shadowFarUnion.rewind()
        var any = false
        var unionOk = true
        
        for (neck in BoardEngine.necks.values) {
            if (neckTouchesHidden(neck, hidden)) continue
            if (!shadowedIntersects(neck.bounds, world)) continue
            shadowCopiesOf(null, neck.path)
            canvas.drawPath(shadowPathNear, shadowEdgePaint)
            canvas.drawPath(shadowPathFar, shadowEdgePaint)
            if (unionOk) unionOk = shadowFarUnion.op(shadowPathFar, Path.Op.UNION)
            any = true
        }
        for (card in BoardEngine.cardsByZ) {
            if (card.id == hidden) continue
            card.rect(cardRect)
            if (!shadowedIntersects(cardRect, world)) continue
            shadowCopiesOf(cardRect, null)
            canvas.drawPath(shadowPathNear, shadowEdgePaint)
            canvas.drawPath(shadowPathFar, shadowEdgePaint)
            if (unionOk) unionOk = shadowFarUnion.op(shadowPathFar, Path.Op.UNION)
            any = true
        }
        if (!any) return
        
        
        val save = canvas.save()
        if (unionOk) canvas.clipPath(shadowFarUnion)
        for (neck in BoardEngine.necks.values) {
            if (neckTouchesHidden(neck, hidden)) continue
            if (!shadowedIntersects(neck.bounds, world)) continue
            shadowCopiesOf(null, neck.path)
            canvas.drawPath(shadowPathNear, shadowPaint)
        }
        val off = CARD_SHADOW_OFFSET
        for (card in BoardEngine.cardsByZ) {
            if (card.id == hidden) continue
            card.rect(cardRect)
            if (!shadowedIntersects(cardRect, world)) continue
            canvas.drawRect(cardRect.left + off, cardRect.top + off, cardRect.right + off, cardRect.bottom + off, shadowPaint)
        }
        canvas.restoreToCount(save)
    }

    private fun drawLiquidAndCards(canvas: Canvas, world: RectF) {
        val hidden = BoardEngine.hiddenCardId
        val emphasis = outlineEmphasis
        val cardRect = RectF()
        
        
        if (!emphasis) {
            drawSilhouetteShadow(canvas, world, hidden)
            
            for (neck in BoardEngine.necks.values) {
                if (neckTouchesHidden(neck, hidden)) continue
                if (!RectF.intersects(neck.bounds, world)) continue
                neckFillPaint.color = if (neck.dark) CARD_COLORED_FILL_COLOR else CARD_FILL_COLOR
                canvas.drawPath(neck.path, neckFillPaint)
            }
        }
        for (card in BoardEngine.cardsByZ) {
            if (card.id == hidden) continue
            card.rect(cardRect)
            if (!RectF.intersects(cardRect, world)) continue
            
            cardFillPaint.color = when {
                emphasis -> Color.WHITE
                showsAccent(card) -> CARD_COLORED_FILL_COLOR
                else -> CARD_FILL_COLOR
            }
            canvas.drawRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, cardFillPaint)
        }
        
        if (emphasis) drawCardOutlines(canvas, world, hidden)
        
        for (card in BoardEngine.cardsByZ) {
            if (card.id == hidden) continue
            card.rect(cardRect)
            if (!RectF.intersects(cardRect, world)) continue
            drawCardContent(canvas, card, cardRect, emphasis)
        }
    }

    private val outlineOccluders = Path()

    
    private fun drawCardOutlines(canvas: Canvas, world: RectF, hidden: String?) {
        outlineOccluders.rewind()
        val cardRect = RectF()
        val cards = BoardEngine.cardsByZ
        for (index in cards.indices.reversed()) {
            val card = cards[index]
            if (card.id == hidden) continue
            card.rect(cardRect)
            if (!RectF.intersects(cardRect, world)) continue
            val save = canvas.save()
            if (!outlineOccluders.isEmpty) canvas.clipOutPath(outlineOccluders)
            canvas.drawRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, emphasisStrokePaint)
            canvas.restoreToCount(save)
            outlineOccluders.addRect(cardRect, Path.Direction.CW)
        }
    }

    private fun drawCardContent(canvas: Canvas, card: BoardEngine.CardRec, rect: RectF, emphasis: Boolean) {
        val save = canvas.save()
        canvas.clipRect(rect)
        canvas.translate(card.x, card.y)

        if (card.kind == "image" || card.kind == "note") {
            val bitmap = if (card.imagePath.isEmpty()) null
            else CardImageCache.get(card.imagePath, (rect.width() * BoardEngine.scale).toInt(), alpha = card.kind == "note")
            if (bitmap != null) {
                drawCardImage(canvas, bitmap, rect.width(), rect.height())
            } else {
                
                drawImagePlaceholder(canvas, rect.width(), rect.height())
            }
        } else if (card.content.isNotBlank()) {
            
            cardTextPaint.color = if (showsAccent(card) && !emphasis) Color.WHITE else CARD_TEXT_COLOR
            drawTextBlock(canvas, card.content, rect.width())
        }

        
        val attached = BoardEngine.cardStrokes[card.id]
        if (attached != null) {
            
            val invert = emphasis && showsAccent(card)
            for (stroke in attached) {
                val c = if (invert) BoardEngine.StrokeRec.contrastInk(stroke.color, null) else stroke.color
                TchRaster.draw(canvas, stroke, strokePaint, c)
            }
        }
        canvas.restoreToCount(save)
    }

    
    private fun drawCardImage(canvas: Canvas, bitmap: Bitmap, width: Float, height: Float) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        if (bw <= 0f || bh <= 0f) return
        val fit = minOf(width / bw, height / bh)
        val dw = bw * fit
        val dh = bh * fit
        canvas.drawBitmap(bitmap, null, RectF(0f, 0f, dw, dh), cardImagePaint)
    }

    private fun drawImagePlaceholder(canvas: Canvas, width: Float, height: Float) {
        val inset = CARD_PADDING_X
        val box = RectF(inset, inset, (width - inset).coerceAtLeast(inset + 1f), (height - inset).coerceAtLeast(inset + 1f))
        strokePaint.color = CARD_PLACEHOLDER_COLOR
        strokePaint.strokeWidth = CARD_OUTLINE_WIDTH
        canvas.drawRect(box, strokePaint)
        canvas.drawLine(box.left, box.top, box.right, box.bottom, strokePaint)
        canvas.drawLine(box.right, box.top, box.left, box.bottom, strokePaint)
    }

    private fun drawTextBlock(canvas: Canvas, raw: String, width: Float) {
        val innerMax = (width - CARD_PADDING_X * 2f).coerceAtLeast(1f)
        var y = CARD_PADDING_Y - cardTextPaint.ascent()
        forEachTextRun(raw, innerMax, cardTextPaint) { text, _, lineHeight ->
            if (text.isNotEmpty()) canvas.drawText(text, CARD_PADDING_X, y, cardTextPaint)
            y += lineHeight
        }
    }
}
