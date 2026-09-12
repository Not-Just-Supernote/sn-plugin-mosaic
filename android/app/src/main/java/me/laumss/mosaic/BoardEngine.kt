package me.laumss.mosaic

import android.graphics.Path
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder


object BoardEngine {

    private const val TAG = "MosaicBoardEngine"
    const val PROTOCOL_VERSION = 2

    private const val OP_ADD_STROKE = 1
    private const val OP_REMOVE_STROKES = 2
    private const val OP_UPSERT_CARD = 3
    private const val OP_REMOVE_CARDS = 4
    private const val OP_SET_NECKS = 5
    private const val OP_SET_WHITEBOARDS = 6
    private const val OP_SET_SELECTION = 7
    private const val OP_SET_CONNECTIONS = 8

    private const val GRID_CELL = 512f
    const val STROKE_PAD = 8f

    class StrokeRec(
        val id: String,
        
        val space: String,
        val width: Float,
        val color: Int,
        
        val points: FloatArray,
        
        val pressures: FloatArray? = null,
    ) {
        val bounds: RectF = computePointBounds(points)
        val path: Path = buildStrokePath(points)
        val cardId: String? = if (space.startsWith("card:")) space.substring(5) else null

        fun sameGeometry(other: StrokeRec): Boolean =
            space == other.space && width == other.width && color == other.color &&
                points.contentEquals(other.points)

        
        fun translated(
            dx: Float,
            dy: Float,
            sourceCard: CardRec?,
            destinationCard: CardRec?,
        ): StrokeRec {
            val sx = sourceCard?.x ?: 0f
            val sy = sourceCard?.y ?: 0f
            val ddx = destinationCard?.x ?: 0f
            val ddy = destinationCard?.y ?: 0f
            val out = FloatArray(points.size)
            var i = 0
            while (i < points.size) {
                out[i] = points[i] + sx + dx - ddx
                out[i + 1] = points[i + 1] + sy + dy - ddy
                i += 2
            }
            return StrokeRec(
                id,
                if (destinationCard == null) "canvas" else "card:${destinationCard.id}",
                width,
                color,
                out,
                pressures,
            )
        }
    }

    class CardRec(
        val id: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val zIndex: Int,
        
        val kind: String = "text",
        val content: String = "",
        val imagePath: String = "",
        
        val bgColor: String = "",
        
        val textColor: String = "",
    ) {
        
        val colored: Boolean
            get() = bgColor.isNotEmpty() && !bgColor.equals("#ffffff", ignoreCase = true)

        fun rect(out: RectF = RectF()): RectF {
            out.set(x, y, x + width, y + height)
            return out
        }

        fun sameGeometry(other: CardRec): Boolean =
            x == other.x && y == other.y && width == other.width && height == other.height &&
                zIndex == other.zIndex && kind == other.kind && content == other.content && imagePath == other.imagePath &&
                bgColor == other.bgColor && textColor == other.textColor

        fun moved(nx: Float, ny: Float): CardRec = CardRec(id, nx, ny, width, height, zIndex, kind, content, imagePath, bgColor, textColor)

        fun withRect(r: RectF): CardRec = CardRec(id, r.left, r.top, r.width(), r.height(), zIndex, kind, content, imagePath, bgColor, textColor)

        fun withZ(z: Int): CardRec = CardRec(id, x, y, width, height, z, kind, content, imagePath, bgColor, textColor)

        fun withColors(bg: String, text: String): CardRec = CardRec(id, x, y, width, height, zIndex, kind, content, imagePath, bg, text)
    }

    class ConnectionRec(val id: String, val fromId: String, val toId: String) {
        fun links(a: String, b: String): Boolean =
            (fromId == a && toId == b) || (fromId == b && toId == a)
    }

    class NeckRec(val id: String, val pathData: String, val path: Path) {
        val bounds: RectF = RectF().also { path.computeBounds(it, true) }
    }

    class WhiteboardRec(
        val id: String,
        val name: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val current: Boolean,
    ) {
        fun rect(out: RectF = RectF()): RectF {
            out.set(x, y, x + width, y + height)
            return out
        }

        fun same(o: WhiteboardRec): Boolean =
            name == o.name && x == o.x && y == o.y && width == o.width && height == o.height && current == o.current
    }

    interface Listener {
        
        fun onSceneChanged(dirtyWorld: RectF?, contentMoved: Boolean)
        fun onViewportChanged()
        fun onSelectionChanged()
    }

    val lock = Any()

    
    val strokes = LinkedHashMap<String, StrokeRec>()
    val cards = LinkedHashMap<String, CardRec>()
    val connections = LinkedHashMap<String, ConnectionRec>()
    val necks = LinkedHashMap<String, NeckRec>()
    val whiteboards = LinkedHashMap<String, WhiteboardRec>()
    val selectedCardIds = LinkedHashSet<String>()

    
    @Volatile var hiddenCardId: String? = null

    var cardsByZ: List<CardRec> = emptyList()
        private set

    private val canvasStrokeGrid = HashMap<Long, MutableList<String>>()
    val cardStrokes = HashMap<String, MutableList<StrokeRec>>()

    
    @Volatile var panX = 0f
        private set
    @Volatile var panY = 0f
        private set
    @Volatile var scale = 1f
        private set

    @Volatile private var listener: Listener? = null

    fun attachListener(next: Listener) {
        listener = next
        next.onSceneChanged(null, true)
        next.onViewportChanged()
    }

    fun detachListener(current: Listener) {
        if (listener === current) listener = null
    }

    fun setViewport(nextPanX: Float, nextPanY: Float, nextScale: Float) {
        val s = if (nextScale > 0f) nextScale else 1f
        if (panX == nextPanX && panY == nextPanY && scale == s) return
        panX = nextPanX
        panY = nextPanY
        scale = s
        listener?.onViewportChanged()
    }

    fun clearScene() {
        synchronized(lock) {
            strokes.clear()
            cards.clear()
            connections.clear()
            necks.clear()
            whiteboards.clear()
            selectedCardIds.clear()
            cardsByZ = emptyList()
            canvasStrokeGrid.clear()
            cardStrokes.clear()
        }
        listener?.onSceneChanged(null, true)
        listener?.onSelectionChanged()
    }

    

    
    inline fun mutate(block: Mutation.() -> Unit) {
        val m = Mutation()
        synchronized(lock) { m.block() }
        m.dispatch()
    }

    class Mutation {
        private var dirty: RectF? = null
        private var dirtyAll = false
        private var moved = false
        private var selectionDirty = false

        fun addDirty(rect: RectF) {
            if (dirtyAll) return
            val current = dirty
            if (current == null) dirty = RectF(rect) else current.union(rect)
        }

        fun invalidateAll() {
            dirtyAll = true
            moved = true
        }

        
        fun addStroke(rec: StrokeRec) {
            val existing = strokes[rec.id]
            if (existing != null) {
                if (existing.sameGeometry(rec)) return
                removeStrokeLocked(rec.id)
                addDirty(strokeWorldBounds(existing))
                moved = true
            }
            insertStrokeLocked(rec)
            addDirty(strokeWorldBounds(rec))
        }

        fun removeStroke(id: String): StrokeRec? {
            val rec = removeStrokeLocked(id) ?: return null
            addDirty(strokeWorldBounds(rec))
            moved = true
            return rec
        }

        
        fun upsertCard(rec: CardRec) {
            val previous = cards[rec.id]
            if (previous != null && previous.sameGeometry(rec)) return
            previous?.let { addDirty(cardDirtyBounds(it)) }
            
            cards[rec.id] = rec
            rebuildCardOrderLocked()
            addDirty(cardDirtyBounds(rec))
            moved = true
        }

        fun removeCard(id: String): CardRec? {
            val rec = cards.remove(id) ?: return null
            addDirty(cardDirtyBounds(rec))
            cardStrokes.remove(id)?.forEach { strokes.remove(it.id) }
            if (selectedCardIds.remove(id)) selectionDirty = true
            rebuildCardOrderLocked()
            moved = true
            return rec
        }

        fun addConnection(rec: ConnectionRec) {
            connections[rec.id] = rec
        }

        fun removeConnection(id: String): ConnectionRec? = connections.remove(id)

        fun replaceConnections(next: Collection<ConnectionRec>) {
            connections.clear()
            for (c in next) connections[c.id] = c
        }

        fun setNecks(next: LinkedHashMap<String, NeckRec>) {
            for ((id, previous) in necks) {
                val replacement = next[id]
                if (replacement == null || replacement !== previous) addDirty(padded(previous.bounds, STROKE_PAD))
            }
            for ((id, neck) in next) {
                if (necks[id] !== neck) addDirty(padded(neck.bounds, STROKE_PAD))
            }
            necks.clear()
            necks.putAll(next)
            moved = true
        }

        fun upsertWhiteboard(rec: WhiteboardRec) {
            val previous = whiteboards[rec.id]
            if (previous != null && previous.same(rec)) return
            previous?.let { addDirty(whiteboardDirtyBounds(it)) }
            whiteboards[rec.id] = rec
            addDirty(whiteboardDirtyBounds(rec))
            moved = true
        }

        fun removeWhiteboard(id: String): WhiteboardRec? {
            val rec = whiteboards.remove(id) ?: return null
            addDirty(whiteboardDirtyBounds(rec))
            moved = true
            return rec
        }

        fun replaceWhiteboards(next: LinkedHashMap<String, WhiteboardRec>) {
            for ((id, previous) in whiteboards) {
                val replacement = next[id]
                if (replacement == null || !previous.same(replacement)) addDirty(whiteboardDirtyBounds(previous))
            }
            for ((id, wb) in next) {
                val previous = whiteboards[id]
                if (previous == null || !previous.same(wb)) addDirty(whiteboardDirtyBounds(wb))
            }
            whiteboards.clear()
            whiteboards.putAll(next)
            moved = true
        }

        fun setSelection(ids: Collection<String>) {
            if (selectedCardIds.size == ids.size && selectedCardIds.containsAll(ids)) return
            selectedCardIds.clear()
            selectedCardIds.addAll(ids)
            selectionDirty = true
        }

        fun dispatch() {
            val current = listener ?: return
            if (dirtyAll) current.onSceneChanged(null, true)
            else dirty?.let { current.onSceneChanged(it, moved) }
            if (selectionDirty) current.onSelectionChanged()
        }
    }

    

    fun applyOps(base64: String): Int {
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (error: Throwable) {
            Log.w(TAG, "applyOps: base64 decode failed", error)
            return 0
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var count = 0
        val startedAt = System.nanoTime()
        val m = Mutation()
        try {
            val version = buf.short.toInt()
            if (version != PROTOCOL_VERSION) {
                Log.w(TAG, "applyOps: unsupported protocol version=$version")
                return 0
            }
            val opCount = buf.int
            synchronized(lock) {
                repeat(opCount) {
                    when (val op = buf.get().toInt()) {
                        OP_ADD_STROKE -> m.addStroke(readStroke(buf))
                        OP_REMOVE_STROKES -> repeat(buf.int) { m.removeStroke(readString(buf)) }
                        OP_UPSERT_CARD -> m.upsertCard(readCard(buf))
                        OP_REMOVE_CARDS -> repeat(buf.int) { m.removeCard(readString(buf)) }
                        OP_SET_NECKS -> {
                            val k = buf.int
                            val next = LinkedHashMap<String, NeckRec>(k)
                            repeat(k) {
                                val id = readString(buf)
                                val pathData = readString(buf)
                                val previous = necks[id]
                                next[id] = if (previous != null && previous.pathData == pathData) previous
                                else NeckRec(id, pathData, SvgPathParser.parse(pathData))
                            }
                            m.setNecks(next)
                        }
                        OP_SET_WHITEBOARDS -> {
                            val k = buf.int
                            val next = LinkedHashMap<String, WhiteboardRec>(k)
                            repeat(k) {
                                val id = readString(buf)
                                val name = readString(buf)
                                val x = buf.float
                                val y = buf.float
                                val w = buf.float
                                val h = buf.float
                                
                                
                                buf.get()
                                next[id] = WhiteboardRec(id, name, x, y, w, h, whiteboards[id]?.current ?: false)
                            }
                            m.replaceWhiteboards(next)
                        }
                        OP_SET_SELECTION -> {
                            val k = buf.int
                            val ids = ArrayList<String>(k)
                            repeat(k) { ids.add(readString(buf)) }
                            m.setSelection(ids)
                        }
                        OP_SET_CONNECTIONS -> {
                            val k = buf.int
                            val next = ArrayList<ConnectionRec>(k)
                            repeat(k) { next.add(ConnectionRec(readString(buf), readString(buf), readString(buf))) }
                            m.replaceConnections(next)
                        }
                        else -> throw IllegalStateException("unknown op=$op at ${buf.position()}")
                    }
                    count += 1
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "applyOps: decode failed after $count op(s)", error)
            m.invalidateAll()
        }
        m.dispatch()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
        Log.i(TAG, "[MosaicRenderPerf] applyOps ops=$count bytes=${bytes.size} ms=${"%.2f".format(elapsedMs)}")
        return count
    }

    

    fun queryCanvasStrokes(world: RectF, out: MutableList<StrokeRec>) {
        val minCx = cellIndex(world.left - STROKE_PAD)
        val maxCx = cellIndex(world.right + STROKE_PAD)
        val minCy = cellIndex(world.top - STROKE_PAD)
        val maxCy = cellIndex(world.bottom + STROKE_PAD)
        val seen = HashSet<String>()
        for (cx in minCx..maxCx) for (cy in minCy..maxCy) {
            val bucket = canvasStrokeGrid[cellKey(cx, cy)] ?: continue
            for (id in bucket) {
                if (!seen.add(id)) continue
                val rec = strokes[id] ?: continue
                if (RectF.intersects(padded(rec.bounds, STROKE_PAD), world)) out.add(rec)
            }
        }
    }

    
    fun hitTestStrokes(worldX: Float, worldY: Float, radius: Float, out: MutableList<StrokeRec>) {
        val minCx = cellIndex(worldX - radius)
        val maxCx = cellIndex(worldX + radius)
        val minCy = cellIndex(worldY - radius)
        val maxCy = cellIndex(worldY + radius)
        val seen = HashSet<String>()
        for (cx in minCx..maxCx) for (cy in minCy..maxCy) {
            val bucket = canvasStrokeGrid[cellKey(cx, cy)] ?: continue
            for (id in bucket) {
                if (!seen.add(id)) continue
                val rec = strokes[id] ?: continue
                if (boundsContain(rec.bounds, worldX, worldY, radius)) out.add(rec)
            }
        }
        if (cardStrokes.isEmpty()) return
        for ((cardId, bucket) in cardStrokes) {
            val card = cards[cardId] ?: continue
            val lx = worldX - card.x
            val ly = worldY - card.y
            for (rec in bucket) if (boundsContain(rec.bounds, lx, ly, radius)) out.add(rec)
        }
    }

    
    fun canvasStrokesInside(world: RectF, out: MutableList<StrokeRec>) {
        val minCx = cellIndex(world.left)
        val maxCx = cellIndex(world.right)
        val minCy = cellIndex(world.top)
        val maxCy = cellIndex(world.bottom)
        val seen = HashSet<String>()
        for (cx in minCx..maxCx) for (cy in minCy..maxCy) {
            val bucket = canvasStrokeGrid[cellKey(cx, cy)] ?: continue
            for (id in bucket) {
                if (!seen.add(id)) continue
                val rec = strokes[id] ?: continue
                if (world.contains(rec.bounds)) out.add(rec)
            }
        }
    }

    fun strokeWorldBounds(rec: StrokeRec): RectF {
        val rect = padded(rec.bounds, STROKE_PAD)
        val cardId = rec.cardId ?: return rect
        val card = cards[cardId] ?: return rect
        rect.offset(card.x, card.y)
        return rect
    }

    fun connectionBetween(a: String, b: String): ConnectionRec? {
        for (c in connections.values) if (c.links(a, b)) return c
        return null
    }

    fun connectionsOf(cardId: String, out: MutableList<ConnectionRec>) {
        for (c in connections.values) if (c.fromId == cardId || c.toId == cardId) out.add(c)
    }

    

    private fun insertStrokeLocked(rec: StrokeRec) {
        strokes[rec.id] = rec
        val cardId = rec.cardId
        if (cardId == null) {
            forEachCell(rec.bounds) { key -> canvasStrokeGrid.getOrPut(key) { ArrayList(4) }.add(rec.id) }
        } else {
            cardStrokes.getOrPut(cardId) { ArrayList(4) }.add(rec)
        }
    }

    private fun removeStrokeLocked(id: String): StrokeRec? {
        val rec = strokes.remove(id) ?: return null
        val cardId = rec.cardId
        if (cardId == null) {
            forEachCell(rec.bounds) { key ->
                val bucket = canvasStrokeGrid[key] ?: return@forEachCell
                bucket.remove(id)
                if (bucket.isEmpty()) canvasStrokeGrid.remove(key)
            }
        } else {
            val bucket = cardStrokes[cardId]
            if (bucket != null) {
                bucket.removeAll { it.id == id }
                if (bucket.isEmpty()) cardStrokes.remove(cardId)
            }
        }
        return rec
    }

    private fun rebuildCardOrderLocked() {
        cardsByZ = cards.values.sortedBy { it.zIndex }
    }

    
    fun cardDirtyBounds(card: CardRec): RectF =
        RectF(card.x - 16f, card.y - 16f, card.x + card.width + 16f, card.y + card.height + 16f)

    private fun whiteboardDirtyBounds(wb: WhiteboardRec): RectF =
        RectF(wb.x - 8f, wb.y - 8f, wb.x + wb.width + 8f, wb.y + wb.height + 8f)

    private inline fun forEachCell(bounds: RectF, block: (Long) -> Unit) {
        val minCx = cellIndex(bounds.left - STROKE_PAD)
        val maxCx = cellIndex(bounds.right + STROKE_PAD)
        val minCy = cellIndex(bounds.top - STROKE_PAD)
        val maxCy = cellIndex(bounds.bottom + STROKE_PAD)
        for (cx in minCx..maxCx) for (cy in minCy..maxCy) block(cellKey(cx, cy))
    }

    private fun cellIndex(value: Float): Int = Math.floorDiv(value.toInt(), GRID_CELL.toInt())

    private fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    private fun padded(rect: RectF, pad: Float): RectF =
        RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)

    private fun boundsContain(b: RectF, x: Float, y: Float, r: Float): Boolean =
        x >= b.left - r && x <= b.right + r && y >= b.top - r && y <= b.bottom + r

    

    private fun readString(buf: ByteBuffer): String {
        val length = buf.short.toInt() and 0xffff
        val bytes = ByteArray(length)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readStroke(buf: ByteBuffer): StrokeRec {
        val id = readString(buf)
        val space = readString(buf)
        val width = buf.float
        val color = buf.int
        val pointCount = buf.int
        val points = FloatArray(pointCount * 2)
        for (index in points.indices) points[index] = buf.float
        return StrokeRec(id, space, width, color, points)
    }

    private fun readCard(buf: ByteBuffer): CardRec {
        val id = readString(buf)
        val x = buf.float
        val y = buf.float
        val width = buf.float
        val height = buf.float
        val zIndex = buf.int
        val kind = readString(buf)
        val content = if (buf.remaining() >= 2) readString(buf) else ""
        val imagePath = if (buf.remaining() >= 2) readString(buf) else ""
        val bgColor = if (buf.remaining() >= 2) readString(buf) else ""
        val textColor = if (buf.remaining() >= 2) readString(buf) else ""
        return CardRec(id, x, y, width, height, zIndex, if (kind.isEmpty()) "text" else kind, content, imagePath, bgColor, textColor)
    }

    fun computePointBounds(points: FloatArray): RectF {
        if (points.isEmpty()) return RectF()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var index = 0
        while (index < points.size) {
            val x = points[index]
            val y = points[index + 1]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            index += 2
        }
        return RectF(minX, minY, maxX, maxY)
    }

    
    fun buildStrokePath(points: FloatArray): Path {
        val path = Path()
        val pointCount = points.size / 2
        if (pointCount == 0) return path
        val x0 = points[0]
        val y0 = points[1]
        path.moveTo(x0, y0)
        if (pointCount == 1) {
            path.lineTo(x0 + 0.01f, y0)
            return path
        }
        for (index in 1 until pointCount - 1) {
            val px = points[index * 2]
            val py = points[index * 2 + 1]
            val nx = points[(index + 1) * 2]
            val ny = points[(index + 1) * 2 + 1]
            path.quadTo(px, py, (px + nx) / 2f, (py + ny) / 2f)
        }
        path.lineTo(points[(pointCount - 1) * 2], points[(pointCount - 1) * 2 + 1])
        return path
    }
}
