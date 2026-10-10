package me.laumss.mosaic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt


class BoardContentView(context: Context) : View(context), BoardEngine.Listener {

    companion object {
        private const val TAG = "MosaicBoardContent"
        
        private const val TILE_WORLD = 512f
        
        private const val CACHE_BUDGET_BYTES = 64L * 1024 * 1024
        private const val CACHE_BUDGET_MAX_BYTES = 256L * 1024 * 1024
        
        private const val PERF_LOG_INTERVAL_MS = 1000L
        
        private const val GESTURE_FRAME_MIN_MS = 70L

        
        
        const val CARD_FILL_COLOR = 0xFFD0D0D0.toInt()
        
        private const val CARD_COLORED_FILL_COLOR = 0xFF000000.toInt()
        
        private const val CARD_SHADOW_COLOR = 0xFF77838D.toInt()
        private const val CARD_SHADOW_EDGE_COLOR = 0xFF9BA5B1.toInt()
        private const val CARD_SHADOW_OFFSET = 5f
        private const val CARD_SHADOW_EDGE = 2f
        private const val CARD_SHADOW_EXTENT = CARD_SHADOW_OFFSET + CARD_SHADOW_EDGE
        
        private const val CARD_OUTLINE_WIDTH = 3f
        private const val CARD_PLACEHOLDER_COLOR = 0xFF8A8A8A.toInt()
        
        private const val CARD_OUTLINE_EMPHASIS_COLOR = Color.BLACK
        private const val CARD_SELECTED_COLOR = Color.BLACK
        private const val CARD_SELECTED_WIDTH = 2f
        private const val CARD_RADIUS = 0f
        private const val CARD_HANDLE_SIZE = 18f
        
        private const val CARD_TEXT_SIZE = CardTextFormat.BODY_SIZE
        private const val CARD_TEXT_COLOR = Color.BLACK
        private const val CARD_PADDING_X = 16f
        private val textMeasurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = CARD_TEXT_SIZE
            isSubpixelText = true
        }

        
        fun measureTextCardSize(raw: String, maxWidth: Float = BoardGeometry.DEFAULT_CARD_WIDTH): android.graphics.PointF {
            val m = CardTextFormat.WEB
            val innerMax = (maxWidth - m.padX * 2f).coerceAtLeast(1f)
            var contentW = 0f
            var y = m.padTop
            forEachTextRun(raw, innerMax, textMeasurePaint, m) { _, lineWidth, lineHeight, _ ->
                if (lineWidth > contentW) contentW = lineWidth
                y += lineHeight
            }
            return android.graphics.PointF(
                (contentW + m.padX * 2f).coerceIn(BoardGeometry.MIN_CARD_SIZE, maxWidth),
                (y + m.padBottom).coerceIn(BoardGeometry.MIN_CARD_SIZE, BoardGeometry.MAX_CARD_SIZE),
            )
        }

        
        fun noteHeaderLines(card: BoardEngine.CardRec): List<String> {
            val raw = card.content.ifBlank { card.title }
            val lines = raw.replace("\r\n", "\n").split('\n')
            
            if (lines.size > 2) return noteHeaderFromText(raw).takeIf { it.isNotEmpty() }?.split('\n') ?: emptyList()
            val plain = lines.map { plainHeaderLine(it) }
            return if (plain.all { it.isEmpty() }) emptyList() else plain
        }

        
        fun noteHeaderFromText(raw: String): String =
            raw.replace("\r\n", "\n").split('\n').map { plainHeaderLine(it) }.filter { it.isNotEmpty() }
                .take(2).joinToString("\n")

        
        fun noteHeaderText(card: BoardEngine.CardRec): String = noteHeaderLines(card).joinToString("\n")

        
        fun noteHeaderMarkdown(lines: List<String>): String {
            if (lines.isEmpty()) return ""
            val body = lines.getOrNull(1).orEmpty()
            return "# " + lines[0] + if (body.isNotEmpty()) "\n" + body else ""
        }

        fun noteHeaderOf(card: BoardEngine.CardRec): String = noteHeaderMarkdown(noteHeaderLines(card))

        private fun plainHeaderLine(raw: String): String {
            var t = raw.trim().removePrefix("> ").trimStart('#').trim()
            if (t.length >= 4 && t.startsWith("**") && t.endsWith("**")) t = t.substring(2, t.length - 2)
            else if (t.length >= 2 && t.startsWith("*") && t.endsWith("*")) t = t.substring(1, t.length - 1)
            return t.trim()
        }

        
        fun noteHeaderHeight(markdown: String): Float {
            if (markdown.isBlank()) return 0f
            val m = CardTextFormat.WEB
            val innerMax = ScrollingDocument.WIDTH - m.padX * 2f
            var y = m.padTop + m.padBottom
            
            synchronized(headerMeasurePaint) {
                forEachTextRun(markdown, innerMax, headerMeasurePaint, m) { _, _, lineHeight, _ -> y += lineHeight }
            }
            return y
        }

        private val headerMeasurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = CARD_TEXT_SIZE
            isSubpixelText = true
        }

        private const val NOTE_HEADER_DIVIDER_COLOR = 0xFF9A9A9A.toInt()
        private const val NOTE_HEADER_DIVIDER_WIDTH = 2f
        private const val NOTE_HEADER_PLACEHOLDER_COLOR = 0xFF9A9A9A.toInt()

        
        private fun forEachTextRun(
            raw: String,
            innerMax: Float,
            paint: TextPaint,
            m: CardTextFormat.Metrics,
            emit: (text: String, width: Float, height: Float, blackBackground: Boolean) -> Unit,
        ) {
            var prev = 0 
            var prevSize = m.body
            var blank = false
            for (paragraph in raw.replace("\r\n", "\n").split('\n')) {
                val normalized = paragraph.trimStart()
                val blackBackground = normalized.startsWith("> ")
                val styled = normalized.removePrefix("> ")
                val heading = styled.takeWhile { it == '#' }.length
                var text = styled.removePrefix("#".repeat(heading)).trimStart()
                val bold = text.startsWith("**") && text.endsWith("**") && text.length >= 4
                if (bold) text = text.substring(2, text.length - 2)
                val italic = !bold && text.startsWith("*") && text.endsWith("*") && text.length >= 2
                if (italic) text = text.substring(1, text.length - 1)
                if (text.isEmpty()) {
                    blank = true
                    continue
                }
                val kind = if (heading > 0) 2 else if (blackBackground) 3 else 1
                val size = CardTextFormat.textSize(heading, m)
                val gap = when {
                    prev == 0 -> 0f
                    prev == 2 -> prevSize * m.headingGapEm
                    prev == 3 && kind == 3 && !blank -> 0f
                    prev == 1 && kind == 1 && !blank -> 0f
                    else -> m.body * m.paraGapEm
                }
                if (gap > 0f) emit("", 0f, gap, false)
                blank = false
                prev = kind
                prevSize = size
                paint.textSize = size
                paint.isFakeBoldText = heading > 0 || bold
                paint.textSkewX = if (italic) -0.2f else 0f
                val lineH = size * (if (heading > 0) m.headingLineMul else m.lineMul)
                val words = text.split(Regex("\\s+"))
                var line = ""
                fun flush(chunk: String) {
                    if (chunk.isEmpty()) return
                    var start = 0
                    while (start < chunk.length) {
                        val count = paint.breakText(chunk, start, chunk.length, true, innerMax, null)
                        val n = if (count > 0) count else 1
                        val piece = chunk.substring(start, start + n)
                        emit(piece, paint.measureText(piece), lineH, blackBackground)
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
            paint.textSkewX = 0f
        }

        
        private const val TRANSLUCENT_BG_COLOR = 0xD0FFFFFF.toInt()
    }

    
    @Volatile
    private var translucentFogColor = TRANSLUCENT_BG_COLOR

    
    fun setTranslucentLevel(level: Int) {
        val seeThrough = TranslucentStore.seeThroughPercent(level)
        val alpha = (255 * (100f - seeThrough) / 100f).roundToInt().coerceIn(0, 255)
        val color = (alpha shl 24) or 0x00FFFFFF
        if (color == translucentFogColor) return
        translucentFogColor = color
        if (translucentFogActive()) postInvalidateOnAnimation()
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
    
    private var rasterExecutor: ExecutorService? = null
    private var rasterScheduled = false
    private var rasterWorkers = 0
    
    private var rasterGeneration = 0L

    
    
    private val rasteringKeys = HashSet<TileKey>()
    private val rasteringDirtyKeys = HashSet<TileKey>()

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

    
    
    
    
    private class RasterScratch {
        val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val neckFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = CARD_FILL_COLOR }
        val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = CARD_FILL_COLOR }
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = CARD_SHADOW_COLOR }
        val shadowEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = CARD_SHADOW_EDGE_COLOR }
        val shadowPathNear = Path()
        val shadowPathFar = Path()
        val shadowFarUnion = Path()
        val outlineOccluders = Path()
        val emphasisStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = CARD_OUTLINE_EMPHASIS_COLOR
            strokeWidth = CARD_OUTLINE_WIDTH
        }
        val cardImagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val cardTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CARD_TEXT_COLOR
            textSize = CARD_TEXT_SIZE
            isSubpixelText = true
        }
        val flatShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.BLACK }
        val strokeQueryScratch = ArrayList<BoardEngine.StrokeRec>(64)
    }

    private val rasterScratch = ThreadLocal.withInitial { RasterScratch() }
    private val strokePaint get() = rasterScratch.get().strokePaint
    private val placeholderPaint get() = rasterScratch.get().placeholderPaint
    private val neckFillPaint get() = rasterScratch.get().neckFillPaint
    private val cardFillPaint get() = rasterScratch.get().cardFillPaint
    private val shadowPaint get() = rasterScratch.get().shadowPaint
    private val shadowEdgePaint get() = rasterScratch.get().shadowEdgePaint
    private val shadowPathNear get() = rasterScratch.get().shadowPathNear
    private val shadowPathFar get() = rasterScratch.get().shadowPathFar
    private val emphasisStrokePaint get() = rasterScratch.get().emphasisStrokePaint
    private val cardImagePaint get() = rasterScratch.get().cardImagePaint
    private val cardTextPaint get() = rasterScratch.get().cardTextPaint
    private val flatShadowPaint get() = rasterScratch.get().flatShadowPaint
    private val strokeQueryScratch get() = rasterScratch.get().strokeQueryScratch
    
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
    private val tilePaint = Paint().apply { isFilterBitmap = false }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        synchronized(cacheLock) {
            rasterGeneration++
            val thread = HandlerThread("MosaicBoardRaster").apply { start() }
            rasterThread = thread
            rasterHandler = Handler(thread.looper)
            rasterExecutor = Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "MosaicBoardRasterWorker").apply { isDaemon = true }
            }
        }
        BoardEngine.attachListener(this)
    }

    override fun onDetachedFromWindow() {
        BoardEngine.detachListener(this)
        synchronized(cacheLock) {
            rasterGeneration++
            rasterHandler?.removeCallbacksAndMessages(null)
            rasterThread?.quitSafely()
            rasterExecutor?.shutdownNow()
            rasterHandler = null
            rasterThread = null
            rasterExecutor = null
            rasterScheduled = false
            for (entry in cache.values) entry.bitmap.recycle()
            cache.clear()
            pending.clear()
            cacheBytes = 0L
            rasterWorkers = 0
            rasteringKeys.clear()
            rasteringDirtyKeys.clear()
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
            if (rasteringKeys.isNotEmpty()) rasteringDirtyKeys.addAll(rasteringKeys)
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
            
            if (rasteringKeys.isNotEmpty()) rasteringDirtyKeys.addAll(rasteringKeys)
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
            if (pending.isNotEmpty() || rasteringKeys.isNotEmpty()) {
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
        if (pending.isNotEmpty() || rasteringKeys.isNotEmpty()) return true
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

    
    fun forceRedrawAfterHostRefresh() {
        settleRequested = true
        rerasterAllTiles(atomic = true)
    }

    
    fun requestInitialRaster() {
        if (width <= 0 || height <= 0) {
            post { if (width > 0 && height > 0) requestInitialRaster() }
            return
        }
        synchronized(cacheLock) {
            forEachVisibleTileLocked { key ->
                val entry = cache[key]
                if (entry == null || entry.dirty) pending.add(key)
            }
            if (pending.isNotEmpty()) scheduleRasterLocked()
        }
        settleRequested = true
        postInvalidateOnAnimation()
    }

    

    override fun onSceneChanged(dirtyWorld: RectF?, contentMoved: Boolean) {
        
        val frameDirty = NoteLinks.refresh(dirtyWorld)
        synchronized(cacheLock) {
            if (dirtyWorld == null) {
                for (entry in cache.values) entry.dirty = true
                if (rasteringKeys.isNotEmpty()) rasteringDirtyKeys.addAll(rasteringKeys)
            } else {
                markWorldDirtyLocked(dirtyWorld)
                for (rect in frameDirty) markWorldDirtyLocked(rect)
            }
        }
        settleRequested = true
        if (!deferRefresh) postInvalidateOnAnimation()
        onContentChanged?.invoke("scene", contentMoved)
    }

    
    fun markWorldDirty(rects: List<RectF>) {
        if (rects.isEmpty()) return
        synchronized(cacheLock) { for (rect in rects) markWorldDirtyLocked(rect) }
        settleRequested = true
        if (!deferRefresh) postInvalidateOnAnimation()
    }

    private fun markWorldDirtyLocked(dirtyWorld: RectF) {
        for ((key, entry) in cache) {
            if (entry.dirty) continue
            if (tileWorldRect(key).intersects(dirtyWorld.left, dirtyWorld.top, dirtyWorld.right, dirtyWorld.bottom)) {
                entry.dirty = true
            }
        }
        for (active in rasteringKeys) {
            if (!rasteringDirtyKeys.contains(active) &&
                tileWorldRect(active).intersects(dirtyWorld.left, dirtyWorld.top, dirtyWorld.right, dirtyWorld.bottom)
            ) {
                rasteringDirtyKeys.add(active)
            }
        }
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
            canvas.drawColor(translucentFogColor)
        } else {
            canvas.drawColor(Color.WHITE)
        }

        
        
        val scaleBits = rasterScale.toRawBits()
        val previewing = rasterScaleOverride != null
        val minIx = floor((-panXPx) / tilePx).toInt()
        val maxIx = floor((width - panXPx) / tilePx).toInt()
        val minIy = floor((-panYPx) / tilePx).toInt()
        val maxIy = floor((height - panYPx) / tilePx).toInt()

        val rasterTilePx = ceil(TILE_WORLD * rasterScale * densityValue).toLong().coerceAtLeast(1L)
        visibleTileBytes = rasterTilePx * rasterTilePx * 4L * ((maxIx - minIx + 1).toLong() * (maxIy - minIy + 1).toLong())

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
    
    @Volatile
    private var darkCards = false
    
    @Volatile
    private var gestureTemplateHidden = false

    
    
    @Volatile
    private var pageNoteHeader: String? = null
    @Volatile
    private var pageNoteHeaderPlaceholder = false
    @Volatile
    private var pageNoteHeaderHeight = 0f

    
    fun setNoteHeader(markdown: String?, placeholder: Boolean = false) {
        val next = markdown?.takeIf { it.isNotBlank() }
        if (next == pageNoteHeader && placeholder == pageNoteHeaderPlaceholder) return
        pageNoteHeader = next
        pageNoteHeaderPlaceholder = placeholder
        pageNoteHeaderHeight = if (next == null) 0f else noteHeaderHeight(next)
        rerasterAllTiles()
    }

    private fun drawPageNoteHeader(canvas: Canvas, world: RectF) {
        val header = pageNoteHeader ?: return
        val h = pageNoteHeaderHeight
        if (world.bottom <= -h || world.top >= 0f || world.left >= ScrollingDocument.WIDTH || world.right <= 0f) return
        val save = canvas.save()
        canvas.translate(0f, -h)
        drawNoteHeader(canvas, header, h, Color.WHITE, if (pageNoteHeaderPlaceholder) NOTE_HEADER_PLACEHOLDER_COLOR else CARD_TEXT_COLOR)
        canvas.restoreToCount(save)
    }

    
    private fun drawNoteHeader(canvas: Canvas, markdown: String, height: Float, fill: Int?, textColor: Int) {
        if (fill != null) {
            cardFillPaint.color = fill
            canvas.drawRect(0f, 0f, ScrollingDocument.WIDTH, height, cardFillPaint)
        }
        cardTextPaint.color = textColor
        drawTextBlock(canvas, markdown, ScrollingDocument.WIDTH, false)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = NOTE_HEADER_DIVIDER_COLOR
        strokePaint.strokeWidth = NOTE_HEADER_DIVIDER_WIDTH
        val y = height - NOTE_HEADER_DIVIDER_WIDTH / 2f
        canvas.drawLine(CARD_PADDING_X, y, ScrollingDocument.WIDTH - CARD_PADDING_X, y, strokePaint)
    }

    fun setGesturePresentation(flatCards: Boolean, darkCards: Boolean, hideTemplate: Boolean) {
        if (outlineEmphasis == flatCards && this.darkCards == darkCards && gestureTemplateHidden == hideTemplate) return
        outlineEmphasis = flatCards
        this.darkCards = darkCards
        gestureTemplateHidden = hideTemplate
        rerasterAllTiles(atomic = true)
    }

    
    
    private fun rerasterAllTiles(atomic: Boolean = false) {
        var wait = false
        synchronized(cacheLock) {
            for (entry in cache.values) entry.dirty = true
            if (rasteringKeys.isNotEmpty()) rasteringDirtyKeys.addAll(rasteringKeys)
            forEachVisibleTileLocked { key ->
                val entry = cache[key]
                if (entry == null || entry.dirty) pending.add(key)
            }
            if (pending.isNotEmpty()) scheduleRasterLocked()
            if (atomic && !deferRefresh && (pending.isNotEmpty() || rasteringKeys.isNotEmpty())) {
                invalidateWhenFresh = true
                wait = true
            }
        }
        if (!wait) postInvalidateOnAnimation()
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
        val executor = rasterExecutor ?: return
        rasterScheduled = true
        rasterWorkers = 2
        val generation = rasterGeneration
        Log.i(TAG, "[MosaicRenderPerf] raster schedule workers=2 pending=${pending.size} generation=$generation")
        repeat(2) { executor.execute { rasterLoop(generation) } }
    }

    private fun rasterLoop(generation: Long) {
        while (true) {
            val key = synchronized(cacheLock) {
                if (generation != rasterGeneration) return@synchronized null
                
                
                pending.removeAll { it in rasteringKeys }
                val next = pending.firstOrNull()
                if (next == null) {
                    rasterWorkers--
                    if (rasterWorkers <= 0) rasterScheduled = false
                    null
                } else {
                    pending.remove(next)
                    rasteringKeys.add(next)
                    rasteringDirtyKeys.remove(next)
                    next
                }
            } ?: return

            
            if (!tileVisible(key)) {
                synchronized(cacheLock) { rasteringKeys.remove(key); rasteringDirtyKeys.remove(key) }
                notifyTileProcessed(produced = false)
                continue
            }

            val startedAt = System.nanoTime()
            val bitmap = try {
                rasterTile(key)
            } catch (error: Throwable) {
                
                
                
                Log.w(TAG, "raster tile failed key=$key", error)
                null
            }
            if (bitmap == null) {
                synchronized(cacheLock) { rasteringKeys.remove(key); rasteringDirtyKeys.remove(key) }
                notifyTileProcessed(produced = false)
                continue
            }
            var accepted = false
            synchronized(cacheLock) {
                if (generation == rasterGeneration) {
                    val previous = cache.remove(key)
                    if (previous != null) {
                        cacheBytes -= previous.bitmap.allocationByteCount
                        previous.bitmap.recycle()
                    }
                    val entry = TileEntry(bitmap)
                    
                    
                    entry.dirty = rasteringDirtyKeys.remove(key)
                    if (entry.dirty && !deferRefresh) pending.add(key)
                    cache[key] = entry
                    cacheBytes += bitmap.allocationByteCount
                    rasteringKeys.remove(key)
                    evictLocked()
                    accepted = true
                }
            }
            if (!accepted) {
                bitmap.recycle()
                synchronized(cacheLock) {
                    rasteringKeys.remove(key)
                    rasteringDirtyKeys.remove(key)
                }
                continue
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
            val drained = synchronized(cacheLock) { pending.isEmpty() && rasteringKeys.isEmpty() }
            if (drained) {
                invalidateWhenFresh = false
                postInvalidateOnAnimation()
            }
        } else if (produced) {
            postInvalidateOnAnimation()
        } else {
            
            
            
            postInvalidateOnAnimation()
        }
    }

    
    private fun markAllCachedTilesDirtyLocked() {
        synchronized(cacheLock) {
            for (entry in cache.values) entry.dirty = true
            if (rasteringKeys.isNotEmpty()) rasteringDirtyKeys.addAll(rasteringKeys)
        }
    }

    @Volatile
    private var visibleTileBytes = 0L

    private fun evictLocked() {
        val visible = visibleTileBytes
        val budget = minOf(CACHE_BUDGET_MAX_BYTES, maxOf(CACHE_BUDGET_BYTES, visible + visible / 4))
        val iterator = cache.entries.iterator()
        while (cacheBytes > budget && iterator.hasNext()) {
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

        
        
        val scene = BoardEngine.renderSnapshot(worldRect)
        if (!gestureTemplateHidden) {
            TemplatePaper.draw(canvas, worldRect, backgroundTemplate, templatePageWidth)
        }
        
        NoteLinks.draw(canvas, worldRect)
        drawPageNoteHeader(canvas, worldRect)
        drawCanvasStrokes(canvas, worldRect, scene)
        drawLiquidAndCards(canvas, worldRect, scene)
        drawConnectionStrokes(canvas, worldRect, scene)
        return bitmap
    }

    

    
    fun renderExport(world: RectF, scalePx: Float, headerPx: Int = 0, onDone: (Bitmap?) -> Unit) {
        val handler = rasterHandler
        if (handler == null || scalePx <= 0f || world.width() <= 0f || world.height() <= 0f) {
            onDone(null)
            return
        }
        val generation = synchronized(cacheLock) { rasterGeneration }
        handler.post {
            val active = synchronized(cacheLock) { generation == rasterGeneration }
            if (!active) { onDone(null); return@post }
            val widthPx = ceil(world.width() * scalePx).toInt().coerceAtLeast(1)
            val heightPx = ceil(world.height() * scalePx).toInt().coerceAtLeast(1) + headerPx.coerceAtLeast(0)
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
            canvas.translate(0f, headerPx.coerceAtLeast(0).toFloat())
            canvas.scale(scalePx, scalePx)
            canvas.translate(-world.left, -world.top)
            val scene = BoardEngine.renderSnapshot(world)
            drawCanvasStrokes(canvas, world, scene)
            drawLiquidAndCards(canvas, world, scene)
            drawConnectionStrokes(canvas, world, scene)
            val stillActive = synchronized(cacheLock) { generation == rasterGeneration }
            if (stillActive) onDone(bitmap) else {
                bitmap.recycle()
                onDone(null)
            }
        }
    }

    private fun drawCanvasStrokes(canvas: Canvas, world: RectF, scene: BoardEngine.RenderSnapshot) {
        strokeQueryScratch.clear()
        strokeQueryScratch.addAll(scene.canvasStrokes)
        strokeQueryScratch.removeAll { it.connectionId != null }
        TchRaster.drawLayer(canvas, strokeQueryScratch, strokePaint, colorOf = a2InkColor("canvas", scene))
    }

    
    private fun drawConnectionStrokes(canvas: Canvas, world: RectF, scene: BoardEngine.RenderSnapshot) {
        strokeQueryScratch.clear()
        strokeQueryScratch.addAll(scene.canvasStrokes)
        strokeQueryScratch.removeAll { it.connectionId == null }
        TchRaster.drawLayer(canvas, strokeQueryScratch, strokePaint, colorOf = a2InkColor("canvas", scene))
    }

    
    private fun a2InkColor(
        space: String,
        scene: BoardEngine.RenderSnapshot,
        cardDarkOverride: Boolean? = null,
    ): (BoardEngine.StrokeRec) -> Int {
        if (!darkCards) return { it.color }
        val markers = scene.markers[space] ?: emptyList()
        if (markers.all { MarkerInk.fromArgb(it.color).darkBackdrop }) return { it.color }
        return { stroke -> InkBackdrop.resolve(stroke, cardDarkOverride = cardDarkOverride, markers = markers) }
    }

    
    private fun neckTouchesHidden(neck: BoardEngine.NeckRec, hidden: String?, scene: BoardEngine.RenderSnapshot): Boolean {
        val hiddenId = hidden ?: return false
        val connection = scene.connections[neck.id] ?: return false
        return connection.fromId == hiddenId || connection.toId == hiddenId
    }

    
    private fun shadowedIntersects(bounds: RectF, world: RectF): Boolean =
        bounds.left < world.right && bounds.top < world.bottom &&
            bounds.right + CARD_SHADOW_EXTENT > world.left && bounds.bottom + CARD_SHADOW_EXTENT > world.top

    
    private val shadowFarUnion get() = rasterScratch.get().shadowFarUnion

    
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

    
    private fun drawSilhouetteShadow(canvas: Canvas, world: RectF, hidden: String?, scene: BoardEngine.RenderSnapshot) {
        val cardRect = RectF()
        shadowFarUnion.rewind()
        var any = false
        var unionOk = true
        
        for (neck in scene.necks) {
            if (neckTouchesHidden(neck, hidden, scene)) continue
            if (!shadowedIntersects(neck.bounds, world)) continue
            shadowCopiesOf(null, neck.path)
            canvas.drawPath(shadowPathNear, shadowEdgePaint)
            canvas.drawPath(shadowPathFar, shadowEdgePaint)
            if (unionOk) unionOk = shadowFarUnion.op(shadowPathFar, Path.Op.UNION)
            any = true
        }
        for (card in scene.cardsByZ) {
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
        for (neck in scene.necks) {
            if (neckTouchesHidden(neck, hidden, scene)) continue
            if (!shadowedIntersects(neck.bounds, world)) continue
            shadowCopiesOf(null, neck.path)
            canvas.drawPath(shadowPathNear, shadowPaint)
        }
        val off = CARD_SHADOW_OFFSET
        for (card in scene.cardsByZ) {
            if (card.id == hidden) continue
            card.rect(cardRect)
            if (!shadowedIntersects(cardRect, world)) continue
            canvas.drawRect(cardRect.left + off, cardRect.top + off, cardRect.right + off, cardRect.bottom + off, shadowPaint)
        }
        canvas.restoreToCount(save)
    }

    private fun drawLiquidAndCards(canvas: Canvas, world: RectF, scene: BoardEngine.RenderSnapshot) {
        val hidden = scene.hiddenCardId
        val emphasis = outlineEmphasis
        val dark = darkCards
        val cardRect = RectF()
        
        
        if (emphasis) {
            drawFlatShadow(canvas, world, hidden, scene)
        } else {
            drawSilhouetteShadow(canvas, world, hidden, scene)
            
            for (neck in scene.necks) {
                if (neckTouchesHidden(neck, hidden, scene)) continue
                if (!RectF.intersects(neck.bounds, world)) continue
                neckFillPaint.color = if (neck.dark) CARD_COLORED_FILL_COLOR else CARD_FILL_COLOR
                canvas.drawPath(neck.path, neckFillPaint)
            }
        }
        for (card in scene.cardsByZ) {
            if (card.id == hidden) continue
            card.rect(cardRect)
            if (!RectF.intersects(cardRect, world)) continue
            
            
            cardFillPaint.color = when {
                dark && !flatDark(card) -> Color.WHITE
                showsAccent(card) -> CARD_COLORED_FILL_COLOR
                else -> CARD_FILL_COLOR
            }
            canvas.drawRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, cardFillPaint)
        }
        
        if (emphasis) drawCardOutlines(canvas, world, hidden, scene)
        
        for (card in scene.cardsByZ) {
            if (card.id == hidden) continue
            card.rect(cardRect)
            if (!RectF.intersects(cardRect, world)) continue
            drawCardContent(canvas, card, cardRect, dark, scene)
        }
    }

    
    private fun drawFlatShadow(canvas: Canvas, world: RectF, hidden: String?, scene: BoardEngine.RenderSnapshot) {
        val cardRect = RectF()
        val off = CARD_SHADOW_OFFSET
        val far = CARD_SHADOW_EXTENT
        for (card in scene.cardsByZ) {
            if (card.id == hidden) continue
            card.rect(cardRect)
            if (!shadowedIntersects(cardRect, world)) continue
            canvas.drawRect(cardRect.left + off, cardRect.top + off, cardRect.right + far, cardRect.bottom + far, flatShadowPaint)
        }
    }

    private val outlineOccluders get() = rasterScratch.get().outlineOccluders

    
    private fun drawCardOutlines(canvas: Canvas, world: RectF, hidden: String?, scene: BoardEngine.RenderSnapshot) {
        outlineOccluders.rewind()
        val cardRect = RectF()
        val cards = scene.cardsByZ
        for (index in cards.indices.reversed()) {
            val card = cards[index]
            if (card.id == hidden) continue
            card.rect(cardRect)
            if (!RectF.intersects(cardRect, world)) continue
            val save = canvas.save()
            if (!outlineOccluders.isEmpty) canvas.clipOutPath(outlineOccluders)
            emphasisStrokePaint.color = if (darkCards && flatDark(card)) Color.WHITE else CARD_OUTLINE_EMPHASIS_COLOR
            canvas.drawRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, emphasisStrokePaint)
            canvas.restoreToCount(save)
            outlineOccluders.addRect(cardRect, Path.Direction.CW)
        }
    }

    
    private fun flatDark(card: BoardEngine.CardRec): Boolean = card.kind != "image" && card.kind != "note"

    
    private fun drawCardContent(canvas: Canvas, card: BoardEngine.CardRec, rect: RectF, dark: Boolean, scene: BoardEngine.RenderSnapshot) {
        val save = canvas.save()
        canvas.clipRect(rect)
        canvas.translate(card.x, card.y)

        if (card.kind == "image" || card.kind == "note") {
            
            var headerH = 0f
            if (card.kind == "note") {
                val header = noteHeaderOf(card)
                val pageH = noteHeaderHeight(header)
                if (pageH > 0f) {
                    val k = rect.width() / ScrollingDocument.WIDTH
                    headerH = (pageH * k).coerceAtMost(rect.height())
                    val headerSave = canvas.save()
                    canvas.scale(k, k)
                    drawNoteHeader(canvas, header, pageH, null, CARD_TEXT_COLOR)
                    canvas.restoreToCount(headerSave)
                }
            }
            val imageH = (rect.height() - headerH).coerceAtLeast(1f)
            canvas.translate(0f, headerH)
            val bitmap = if (card.imagePath.isEmpty()) null
            else CardImageCache.get(card.imagePath, (rect.width() * scene.scale).toInt(), alpha = card.kind == "note")
            if (bitmap != null) {
                drawCardImage(canvas, bitmap, rect.width(), imageH)
            } else {
                
                drawImagePlaceholder(canvas, rect.width(), imageH)
            }
            canvas.translate(0f, -headerH)
        } else if (card.content.isNotBlank()) {
            
            val darkBase = showsAccent(card) || (dark && flatDark(card))
            cardTextPaint.color = if (darkBase) Color.WHITE else CARD_TEXT_COLOR
            drawTextBlock(canvas, card.content, rect.width(), darkBase, CardTextFormat.metricsFor(card.id))
        }

        
        val attached = scene.cardStrokes[card.id]
        if (attached != null) {
            
            val darkened = dark && flatDark(card) && !showsAccent(card)
            val a2Color = a2InkColor("card:${card.id}", scene, cardDarkOverride = card.colored)
            TchRaster.drawLayer(canvas, attached, strokePaint) { stroke ->
                if (darkened) InkBackdrop.resolve(stroke, cardDarkOverride = true) else a2Color(stroke)
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
        placeholderPaint.color = CARD_PLACEHOLDER_COLOR
        placeholderPaint.strokeWidth = CARD_OUTLINE_WIDTH
        canvas.drawRect(box, placeholderPaint)
        canvas.drawLine(box.left, box.top, box.right, box.bottom, placeholderPaint)
        canvas.drawLine(box.right, box.top, box.left, box.bottom, placeholderPaint)
    }

    
    private fun drawTextBlock(canvas: Canvas, raw: String, width: Float, darkBase: Boolean, m: CardTextFormat.Metrics = CardTextFormat.WEB) {
        val innerMax = (width - m.padX * 2f).coerceAtLeast(1f)
        var top = m.padTop
        val baseColor = cardTextPaint.color
        val highlight = if (darkBase) Color.WHITE else Color.BLACK
        val highlightText = if (darkBase) Color.BLACK else Color.WHITE
        forEachTextRun(raw, innerMax, cardTextPaint, m) { text, _, lineHeight, blackBackground ->
            if (text.isNotEmpty()) {
                
                val fm = cardTextPaint.fontMetrics
                val baseline = top + (lineHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent
                if (blackBackground) {
                    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = highlight; style = Paint.Style.FILL }
                    canvas.drawRect(m.padX - 6f, top, width - m.padX + 6f, top + lineHeight, fill)
                    cardTextPaint.color = highlightText
                }
                canvas.drawText(text, m.padX, baseline, cardTextPaint)
                if (blackBackground) cardTextPaint.color = baseColor
            }
            top += lineHeight
        }
    }
}
