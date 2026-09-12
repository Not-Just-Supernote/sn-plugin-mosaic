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

        
        private const val CARD_FILL_COLOR = 0xFFDBDBDB.toInt()
        private const val CARD_OUTLINE_COLOR = 0xFFC9C9C9.toInt()
        
        private const val CARD_COLORED_FILL_COLOR = 0xFF000000.toInt()
        private const val CARD_OUTLINE_WIDTH = 2f
        private const val CARD_SELECTED_COLOR = Color.BLACK
        private const val CARD_SELECTED_WIDTH = 2f
        private const val CARD_RADIUS = 0f
        private const val CARD_HANDLE_SIZE = 18f
        private const val TITLE_COLOR = 0xFF111111.toInt()
        private const val CARD_TEXT_SIZE = 16f
        private const val CARD_TEXT_COLOR = Color.BLACK
        private const val CARD_PADDING_X = 16f
        private const val CARD_PADDING_Y = 14f

        
        private const val WB_CORNER_LEN = 44f
        private const val WB_CORNER_STROKE = 5f
        private const val WB_CORNER_OFFSET = 4f
        private const val WB_NAME_SIZE = 20f
        private const val WB_NAME_COLOR = 0xFF555555.toInt()

        
        private const val TRANSLUCENT_BG_COLOR = 0xD0000000.toInt()
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

    
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val neckFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = CARD_FILL_COLOR
    }
    private val neckStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = CARD_OUTLINE_COLOR
        strokeWidth = CARD_OUTLINE_WIDTH
    }
    private val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = CARD_FILL_COLOR
    }
    private val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = CARD_OUTLINE_COLOR
        strokeWidth = CARD_OUTLINE_WIDTH
    }
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
        strokeWidth = CARD_OUTLINE_WIDTH
    }
    private val accentPreviewFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    
    private val overlayTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CARD_TEXT_COLOR
        textSize = CARD_TEXT_SIZE
        isSubpixelText = true
    }
    
    private val overlayBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = CARD_OUTLINE_COLOR
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
    private var translucentBackground = false

    fun setTranslucentBackground(translucent: Boolean) {
        if (translucentBackground == translucent) return
        translucentBackground = translucent
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
    var previewCardsWhite = false
        private set

    
    @Volatile
    var suspendTranslucent = false
        private set

    private val settleWaiters = ArrayList<Runnable>()

    @Volatile
    private var settleRequested = false

    fun setPreviewCardsWhite(white: Boolean) {
        if (previewCardsWhite == white) return
        previewCardsWhite = white
        val dirty = coloredCardDirtyBounds()
        if (dirty != null) onSceneChanged(dirty, false)
        postInvalidateOnAnimation()
    }

    fun setSuspendTranslucent(suspend: Boolean) {
        if (suspendTranslucent == suspend) return
        suspendTranslucent = suspend
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

    private fun coloredCardDirtyBounds(): RectF? {
        var out: RectF? = null
        synchronized(BoardEngine.lock) {
            for (card in BoardEngine.cards.values) {
                if (!card.colored) continue
                val bounds = BoardEngine.cardDirtyBounds(card)
                if (out == null) out = RectF(bounds) else out!!.union(bounds)
            }
        }
        return out
    }

    private fun showsAccent(card: BoardEngine.CardRec): Boolean =
        card.colored && !previewCardsWhite

    
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
        postInvalidateOnAnimation()
    }

    override fun onSelectionChanged() {
        postInvalidateOnAnimation()
    }

    

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val startedAt = System.nanoTime()
        val scale = BoardEngine.scale
        val densityValue = density
        val scalePx = scale * densityValue
        val panXPx = BoardEngine.panX * densityValue
        val panYPx = BoardEngine.panY * densityValue
        val tilePx = TILE_WORLD * scalePx
        if (width == 0 || height == 0 || tilePx <= 0f) return

        if (translucentBackground && !suspendTranslucent) {
            
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
                            
                            if (!deferRefresh && pending.add(key)) scheduleRasterLocked()
                        }
                    } else {
                        missing += 1
                        val fallback = fallbackTiles[cellKey(ix, iy)]
                        if (fallback != null) {
                            src.set(0, 0, fallback.width, fallback.height)
                            canvas.drawBitmap(fallback, src, dst, tilePaint)
                        }
                        
                        if (pending.add(key)) scheduleRasterLocked()
                    }
                }
            }
            if (missing == 0 && !previewing) releaseFallbackLocked()
        }

        drawAccentCardsWhite(canvas, panXPx, panYPx, scalePx)
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

    
    private fun drawAccentCardsWhite(canvas: Canvas, panXPx: Float, panYPx: Float, scalePx: Float) {
        if (!previewCardsWhite) return
        overlayBorderPaint.strokeWidth = CARD_OUTLINE_WIDTH * scalePx
        synchronized(BoardEngine.lock) {
            val hidden = BoardEngine.hiddenCardId
            for (card in BoardEngine.cardsByZ) {
                if (!card.colored || card.id == hidden) continue
                val left = panXPx + card.x * scalePx
                val top = panYPx + card.y * scalePx
                val right = left + card.width * scalePx
                val bottom = top + card.height * scalePx
                canvas.drawRect(left, top, right, bottom, accentPreviewFill)
                canvas.drawRect(left, top, right, bottom, overlayBorderPaint)
                
                if (card.kind != "image" && card.content.isNotBlank()) {
                    val save = canvas.save()
                    canvas.clipRect(left, top, right, bottom)
                    canvas.translate(left, top)
                    canvas.scale(scalePx, scalePx)
                    drawOverlayTextBlock(canvas, card.content, card.width)
                    canvas.restoreToCount(save)
                }
            }
        }
    }

    
    private fun drawOverlayTextBlock(canvas: Canvas, raw: String, width: Float) {
        val maxWidth = (width - CARD_PADDING_X * 2f).coerceAtLeast(1f)
        var y = CARD_PADDING_Y - overlayTextPaint.ascent()
        for (paragraph in raw.replace("\r\n", "\n").split('\n')) {
            val heading = paragraph.trimStart().takeWhile { it == '#' }.length
            val text = paragraph.trimStart().removePrefix("#".repeat(heading)).trimStart()
            if (text.isEmpty()) { y += CARD_TEXT_SIZE * 0.7f; continue }
            overlayTextPaint.textSize = when (heading) { 1 -> 22f; 2 -> 19f; 3 -> 17f; else -> CARD_TEXT_SIZE }
            overlayTextPaint.isFakeBoldText = heading > 0
            val words = text.split(Regex("\\s+"))
            var line = ""
            for (word in words) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && overlayTextPaint.measureText(candidate) > maxWidth) {
                    canvas.drawText(line, CARD_PADDING_X, y, overlayTextPaint); y += overlayTextPaint.textSize * 1.28f; line = word
                } else line = candidate
            }
            if (line.isNotEmpty()) { canvas.drawText(line, CARD_PADDING_X, y, overlayTextPaint); y += overlayTextPaint.textSize * 1.28f }
        }
    }

    private fun drawSelection(canvas: Canvas, panXPx: Float, panYPx: Float, scalePx: Float) {
        val selected = BoardEngine.selectedCardIds
        if (selected.isEmpty()) return
        synchronized(BoardEngine.lock) {
            for (id in selected) {
                val card = BoardEngine.cards[id] ?: continue
                val left = panXPx + card.x * scalePx
                val top = panYPx + card.y * scalePx
                val right = panXPx + (card.x + card.width) * scalePx
                val bottom = panYPx + (card.y + card.height) * scalePx
                selectionBorderPaint.strokeWidth = CARD_SELECTED_WIDTH * scalePx
                canvas.drawRect(left, top, right, bottom, selectionBorderPaint)
                val half = CARD_HANDLE_SIZE * scalePx / 2f
                handleBorderPaint.strokeWidth = CARD_OUTLINE_WIDTH * scalePx
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
        if (!translucentBackground) canvas.drawColor(Color.WHITE)
        canvas.scale(scalePx, scalePx)
        val worldRect = tileWorldRect(key)
        canvas.translate(-worldRect.left, -worldRect.top)

        synchronized(BoardEngine.lock) {
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
            strokePaint.color = stroke.color
            strokePaint.strokeWidth = stroke.width
            canvas.drawPath(stroke.path, strokePaint)
        }
    }

    private fun drawLiquidAndCards(canvas: Canvas, world: RectF) {
        val hidden = BoardEngine.hiddenCardId
        
        var anyNeck = false
        for (neck in BoardEngine.necks.values) {
            if (!RectF.intersects(neck.bounds, world)) continue
            canvas.drawPath(neck.path, neckFillPaint)
            canvas.drawPath(neck.path, neckStrokePaint)
            anyNeck = true
        }
        val cardRect = RectF()
        for (card in BoardEngine.cardsByZ) {
            if (card.id == hidden) continue
            card.rect(cardRect)
            val padded = RectF(cardRect).apply { inset(-CARD_OUTLINE_WIDTH, -CARD_OUTLINE_WIDTH) }
            if (!RectF.intersects(padded, world)) continue
            
            
            val preview = card.colored && previewCardsWhite
            val fill = when {
                showsAccent(card) -> CARD_COLORED_FILL_COLOR
                preview -> Color.WHITE
                else -> CARD_FILL_COLOR
            }
            cardFillPaint.color = fill
            cardStrokePaint.color = if (preview) CARD_OUTLINE_COLOR else fill
            canvas.drawRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, cardFillPaint)
            canvas.drawRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, cardStrokePaint)
        }
        if (anyNeck) {
            for (neck in BoardEngine.necks.values) {
                if (!RectF.intersects(neck.bounds, world)) continue
                canvas.drawPath(neck.path, neckFillPaint)
            }
        }
        
        for (card in BoardEngine.cardsByZ) {
            if (card.id == hidden) continue
            card.rect(cardRect)
            if (!RectF.intersects(cardRect, world)) continue
            drawCardContent(canvas, card, cardRect)
        }
    }

    private fun drawCardContent(canvas: Canvas, card: BoardEngine.CardRec, rect: RectF) {
        val save = canvas.save()
        canvas.clipRect(rect)
        canvas.translate(card.x, card.y)

        if (card.kind == "image") {
            val bitmap = if (card.imagePath.isEmpty()) null
            else CardImageCache.get(card.imagePath, (rect.width() * BoardEngine.scale).toInt())
            if (bitmap != null) {
                drawCardImage(canvas, bitmap, rect.width(), rect.height())
            } else {
                
                drawImagePlaceholder(canvas, rect.width(), rect.height())
            }
        } else if (card.content.isNotBlank()) {
            cardTextPaint.color = if (showsAccent(card)) Color.WHITE else CARD_TEXT_COLOR
            drawTextBlock(canvas, card.content, rect.width())
        }

        
        val attached = BoardEngine.cardStrokes[card.id]
        if (attached != null) {
            for (stroke in attached) {
                strokePaint.color = stroke.color
                strokePaint.strokeWidth = stroke.width
                canvas.drawPath(stroke.path, strokePaint)
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
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + dw, top + dh), cardImagePaint)
    }

    private fun drawImagePlaceholder(canvas: Canvas, width: Float, height: Float) {
        val inset = CARD_PADDING_X
        val box = RectF(inset, inset, (width - inset).coerceAtLeast(inset + 1f), (height - inset).coerceAtLeast(inset + 1f))
        strokePaint.color = CARD_OUTLINE_COLOR
        strokePaint.strokeWidth = CARD_OUTLINE_WIDTH
        canvas.drawRect(box, strokePaint)
        canvas.drawLine(box.left, box.top, box.right, box.bottom, strokePaint)
        canvas.drawLine(box.right, box.top, box.left, box.bottom, strokePaint)
    }

    private fun drawTextBlock(canvas: Canvas, raw: String, width: Float) {
        val maxWidth = (width - CARD_PADDING_X * 2f).coerceAtLeast(1f)
        var y = CARD_PADDING_Y - cardTextPaint.ascent()
        for (paragraph in raw.replace("\r\n", "\n").split('\n')) {
            val heading = paragraph.trimStart().takeWhile { it == '#' }.length
            val text = paragraph.trimStart().removePrefix("#".repeat(heading)).trimStart()
            if (text.isEmpty()) { y += CARD_TEXT_SIZE * 0.7f; continue }
            cardTextPaint.textSize = when (heading) { 1 -> 22f; 2 -> 19f; 3 -> 17f; else -> CARD_TEXT_SIZE }
            cardTextPaint.isFakeBoldText = heading > 0
            val words = text.split(Regex("\\s+"))
            var line = ""
            for (word in words) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && cardTextPaint.measureText(candidate) > maxWidth) {
                    canvas.drawText(line, CARD_PADDING_X, y, cardTextPaint); y += cardTextPaint.textSize * 1.28f; line = word
                } else line = candidate
            }
            if (line.isNotEmpty()) { canvas.drawText(line, CARD_PADDING_X, y, cardTextPaint); y += cardTextPaint.textSize * 1.28f }
        }
    }
}

