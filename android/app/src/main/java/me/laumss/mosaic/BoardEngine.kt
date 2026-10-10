package me.laumss.mosaic

import android.graphics.Path
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder


object BoardEngine {

    private const val TAG = "MosaicBoardEngine"
    const val PROTOCOL_VERSION = 5

    private const val OP_ADD_STROKE = 1
    private const val OP_REMOVE_STROKES = 2
    private const val OP_UPSERT_CARD = 3
    private const val OP_REMOVE_CARDS = 4
    private const val OP_SET_SELECTION = 7
    private const val OP_SET_CONNECTIONS = 8

    private const val GRID_CELL = 512f
    
    
    
    
    
    const val STROKE_PAD = 32f
    
    const val NECK_PAD = 16f

    class StrokeRec(
        val id: String,
        
        val space: String,
        val width: Float,
        val color: Int,
        
        val points: FloatArray,
        
        val pressures: FloatArray,
        
        rawPenStyle: Int,
        
        val sampleScale: Float,
        
        _requestedDrawPathWidth: Int = 0,
    ) {
        
        val penStyle: Int = PenStyle.normalizeStoredType(rawPenStyle)
        
        val drawPathWidth: Int = _requestedDrawPathWidth.takeIf { it > 0 }
            ?: DrawPathClient.widthArgument(PenStyle.fromObjType(penStyle), width)
        val bounds: RectF = computePointBounds(points)
        
        
        
        
        
        val isShape: Boolean by lazy { Shapes.kindOf(points, pressures) != null }
        val path: Path by lazy { if (isShape) buildPolylinePath(points) else buildStrokePath(points) }
        val cardId: String? = if (space.startsWith("card:")) space.substring(5) else null
        val connectionId: String? = if (space.startsWith("connection:")) space.substring(11) else null
        val runs: List<TchRaster.Run> by lazy {
            TchRaster.runs(points, pressures, sampleScale, penStyle, drawPathWidth)
        }

        fun sameGeometry(other: StrokeRec): Boolean =
            space == other.space && width == other.width && color == other.color && penStyle == other.penStyle && sampleScale == other.sampleScale && drawPathWidth == other.drawPathWidth &&
                points.contentEquals(other.points) && pressures.contentEquals(other.pressures)

        fun withColor(c: Int): StrokeRec =
            if (c == color) this else StrokeRec(id, space, width, c, points, pressures, penStyle, sampleScale, drawPathWidth)

        fun withPenStyle(style: PenStyle): StrokeRec =
            if (style.objType == penStyle) this else StrokeRec(id, space, width, color, points, pressures, style.objType, sampleScale, DrawPathClient.widthArgument(style, width))

        
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
                penStyle,
                sampleScale,
                drawPathWidth,
            )
        }

        
        fun translatedWorld(dx: Float, dy: Float): StrokeRec {
            val out = FloatArray(points.size)
            var i = 0
            while (i < points.size) {
                out[i] = points[i] + dx
                out[i + 1] = points[i + 1] + dy
                i += 2
            }
            return StrokeRec(id, space, width, color, out, pressures, penStyle, sampleScale, drawPathWidth)
        }

        
        fun scaled(
            fx: Float,
            fy: Float,
            pivotWorldX: Float,
            pivotWorldY: Float,
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
                val worldX = points[i] + sx
                val worldY = points[i + 1] + sy
                out[i] = pivotWorldX + fx * (worldX - pivotWorldX) - ddx
                out[i + 1] = pivotWorldY + fy * (worldY - pivotWorldY) - ddy
                i += 2
            }
            val widthScale = kotlin.math.sqrt(kotlin.math.abs(fx * fy))
            return StrokeRec(
                id,
                if (destinationCard == null) "canvas" else "card:${destinationCard.id}",
                (width * widthScale).coerceAtLeast(0.5f),
                color,
                out,
                pressures,
                penStyle,
                sampleScale,
                (drawPathWidth * widthScale).toInt().coerceAtLeast(1),
            )
        }

        
        fun rotated(
            radians: Float,
            pivotWorldX: Float,
            pivotWorldY: Float,
            sourceCard: CardRec?,
            destinationCard: CardRec?,
        ): StrokeRec {
            val sx = sourceCard?.x ?: 0f
            val sy = sourceCard?.y ?: 0f
            val ddx = destinationCard?.x ?: 0f
            val ddy = destinationCard?.y ?: 0f
            val world = FloatArray(points.size)
            var i = 0
            while (i < points.size) {
                world[i] = points[i] + sx
                world[i + 1] = points[i + 1] + sy
                i += 2
            }
            val rotated = Shapes.rotate(world, radians, pivotWorldX, pivotWorldY)
            i = 0
            while (i < rotated.size) {
                rotated[i] -= ddx
                rotated[i + 1] -= ddy
                i += 2
            }
            return StrokeRec(
                id,
                if (destinationCard == null) "canvas" else "card:${destinationCard.id}",
                width,
                color,
                rotated,
                pressures,
                penStyle,
                sampleScale,
                drawPathWidth,
            )
        }

        
        fun withPoints(pts: FloatArray): StrokeRec =
            StrokeRec(id, space, width, color, pts, if (pressures.size == pts.size / 2) pressures else Shapes.pressures(pts.size / 2), penStyle, sampleScale, drawPathWidth)

        companion object {
            const val INK_BLACK = 0xFF000000.toInt()
            const val INK_WHITE = 0xFFFFFFFF.toInt()
            
            const val INK_WHITE_PINNED = 0xFFFEFEFE.toInt()

            
            
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
        val noteRef: String = "",
        
        val bgColor: String = "",
        
        val textColor: String = "",
        
        val title: String = "",
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
                bgColor == other.bgColor && textColor == other.textColor && noteRef == other.noteRef && title == other.title

        fun moved(nx: Float, ny: Float): CardRec = CardRec(id, nx, ny, width, height, zIndex, kind, content, imagePath, noteRef, bgColor, textColor, title)

        fun withRect(r: RectF): CardRec = CardRec(id, r.left, r.top, r.width(), r.height(), zIndex, kind, content, imagePath, noteRef, bgColor, textColor, title)

        fun withZ(z: Int): CardRec = CardRec(id, x, y, width, height, z, kind, content, imagePath, noteRef, bgColor, textColor, title)

        fun withColors(bg: String, text: String): CardRec = CardRec(id, x, y, width, height, zIndex, kind, content, imagePath, noteRef, bg, text, title)

        fun withContent(value: String): CardRec = CardRec(id, x, y, width, height, zIndex, kind, value, imagePath, noteRef, bgColor, textColor, title)

        
        fun sameNeckGeometry(other: CardRec): Boolean =
            x == other.x && y == other.y && width == other.width && height == other.height && colored == other.colored
    }

    class ConnectionRec(val id: String, val fromId: String, val toId: String, val locked: Boolean = false) {
        fun withLocked(value: Boolean): ConnectionRec = if (locked == value) this else ConnectionRec(id, fromId, toId, value)
        fun links(a: String, b: String): Boolean =
            (fromId == a && toId == b) || (fromId == b && toId == a)

        fun touches(cardId: String): Boolean = fromId == cardId || toId == cardId

        fun otherEnd(cardId: String): String? = when (cardId) {
            fromId -> toId
            toId -> fromId
            else -> null
        }
    }

    
    class NeckRec(val id: String, val path: Path, val dark: Boolean) {
        val bounds: RectF = RectF().also { path.computeBounds(it, true) }
    }

    interface Listener {
        
        fun onSceneChanged(dirtyWorld: RectF?, contentMoved: Boolean)
        fun onViewportChanged()
        fun onSelectionChanged()
    }

    val lock = Any()

    data class SceneSnapshot(
        val strokes: List<StrokeRec>, val cards: List<CardRec>, val connections: List<ConnectionRec>,
        val necks: List<NeckRec>, val selected: List<String>,
        val panX: Float, val panY: Float, val scale: Float,
    )

    
    data class RenderSnapshot(
        val canvasStrokes: List<StrokeRec>,
        val cardsByZ: List<CardRec>,
        val cards: Map<String, CardRec>,
        val cardStrokes: Map<String, List<StrokeRec>>,
        val markers: Map<String, List<StrokeRec>>,
        val connections: Map<String, ConnectionRec>,
        val necks: List<NeckRec>,
        val hiddenCardId: String?,
        val scale: Float,
    )

    
    fun renderSnapshot(world: RectF): RenderSnapshot = synchronized(lock) {
        val canvas = ArrayList<StrokeRec>()
        queryCanvasStrokes(world, canvas)
        val orderedCards = cardsByZ.toList()
        val cardMap = HashMap<String, CardRec>(orderedCards.size)
        for (card in orderedCards) cardMap[card.id] = card
        val attached = HashMap<String, List<StrokeRec>>(cardStrokes.size)
        
        
        
        val cardRect = RectF()
        for (card in orderedCards) {
            card.rect(cardRect)
            if (!RectF.intersects(cardRect, world)) continue
            cardStrokes[card.id]?.let { attached[card.id] = it.toList() }
        }
        val markers = HashMap<String, MutableList<StrokeRec>>()
        for (stroke in strokes.values) {
            if (stroke.penStyle == PenStyle.MARKER.objType) {
                markers.getOrPut(stroke.space) { ArrayList(2) }.add(stroke)
            }
        }
        RenderSnapshot(
            canvasStrokes = canvas,
            cardsByZ = orderedCards,
            cards = cardMap,
            cardStrokes = attached,
            markers = markers,
            connections = HashMap(connections),
            necks = necks.values.toList(),
            hiddenCardId = hiddenCardId,
            scale = scale,
        )
    }

    fun snapshotScene(): SceneSnapshot = synchronized(lock) { SceneSnapshot(strokes.values.toList(), cards.values.toList(), connections.values.toList(), necks.values.toList(), selectedCardIds.toList(), panX, panY, scale) }
    fun replaceScene(snapshot: SceneSnapshot) {
        val m = Mutation(); synchronized(lock) {
            strokes.clear(); cards.clear(); connections.clear(); necks.clear(); selectedCardIds.clear(); cardsByZ=emptyList(); canvasStrokeGrid.clear(); cardStrokes.clear()
            snapshot.strokes.forEach { m.addStroke(it) }; snapshot.cards.forEach { m.upsertCard(it) }; snapshot.connections.forEach { connections[it.id]=it }; snapshot.necks.forEach { necks[it.id]=it }; selectedCardIds.addAll(snapshot.selected); rebuildCardOrderLocked()
        }
        hiddenCardId = null
        setViewport(snapshot.panX, snapshot.panY, snapshot.scale); m.invalidateAll(); m.dispatch()
        listener?.onSelectionChanged()
    }

    
    val strokes = LinkedHashMap<String, StrokeRec>()
    val cards = LinkedHashMap<String, CardRec>()
    val connections = LinkedHashMap<String, ConnectionRec>()
    val necks = LinkedHashMap<String, NeckRec>()
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
        synchronized(lock) {
            try {
                m.block()
            } finally {
                m.refreshNecksLocked()
            }
        }
        m.dispatch()
    }

    class Mutation {
        private var dirty: RectF? = null
        private var dirtyAll = false
        private var moved = false
        private var selectionDirty = false
        
        private val touchedCards = HashSet<String>()
        
        private val touchedConnections = HashSet<String>()
        
        private val newConnections = HashSet<String>()

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
            rec.connectionId?.let { connectionId ->
                val connection = connections[connectionId]
                if (connection != null && !connection.locked) {
                    connections[connectionId] = connection.withLocked(true)
                    touchedConnections.add(connectionId)
                }
            }
        }

        
        fun addStrokesBatch(records: List<StrokeRec>) {
            if (records.size <= 1) {
                records.firstOrNull()?.let(::addStroke)
                return
            }
            val startedAt = System.nanoTime()
            val removedRecords = ArrayList<StrokeRec>()
            val finalWrites = LinkedHashMap<String, StrokeRec>()
            val lockConnections = LinkedHashSet<String>()
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            fun includeBounds(rec: StrokeRec) {
                val card = rec.cardId?.let { cards[it] }
                val x = card?.x ?: 0f
                val y = card?.y ?: 0f
                left = minOf(left, rec.bounds.left + x - STROKE_PAD)
                top = minOf(top, rec.bounds.top + y - STROKE_PAD)
                right = maxOf(right, rec.bounds.right + x + STROKE_PAD)
                bottom = maxOf(bottom, rec.bounds.bottom + y + STROKE_PAD)
            }
            for (rec in records) {
                val existing = strokes[rec.id]
                if (existing != null && existing.sameGeometry(rec)) continue
                if (existing != null) {
                    includeBounds(existing)
                    moved = true
                    
                    strokes.remove(rec.id)
                    removedRecords.add(existing)
                }
                strokes[rec.id] = rec
                finalWrites.remove(rec.id)
                finalWrites[rec.id] = rec
                includeBounds(rec)
                rec.connectionId?.let(lockConnections::add)
            }
            if (finalWrites.isEmpty()) return

            val removeCells = HashMap<Long, MutableSet<String>>()
            val removeCards = HashMap<String, MutableSet<String>>()
            for (rec in removedRecords) {
                val cardId = rec.cardId
                if (cardId == null) {
                    forEachCell(rec.bounds) { key ->
                        removeCells.getOrPut(key) { HashSet() }.add(rec.id)
                    }
                } else removeCards.getOrPut(cardId) { HashSet() }.add(rec.id)
            }
            for ((key, ids) in removeCells) {
                val bucket = canvasStrokeGrid[key] ?: continue
                bucket.removeAll { it in ids }
                if (bucket.isEmpty()) canvasStrokeGrid.remove(key)
            }
            for ((cardId, ids) in removeCards) {
                val bucket = cardStrokes[cardId] ?: continue
                bucket.removeAll { it.id in ids }
                if (bucket.isEmpty()) cardStrokes.remove(cardId)
            }

            val cellCounts = HashMap<Long, Int>()
            val cardCounts = HashMap<String, Int>()
            for (rec in finalWrites.values) {
                val cardId = rec.cardId
                if (cardId == null) {
                    forEachCell(rec.bounds) { key ->
                        cellCounts[key] = (cellCounts[key] ?: 0) + 1
                    }
                } else cardCounts[cardId] = (cardCounts[cardId] ?: 0) + 1
            }
            val appendCells = HashMap<Long, ArrayList<String>>(cellCounts.size)
            val appendCards = HashMap<String, ArrayList<StrokeRec>>(cardCounts.size)
            for ((key, count) in cellCounts) appendCells[key] = ArrayList(count)
            for ((cardId, count) in cardCounts) appendCards[cardId] = ArrayList(count)
            for (rec in finalWrites.values) {
                val cardId = rec.cardId
                if (cardId == null) {
                    forEachCell(rec.bounds) { key -> appendCells[key]?.add(rec.id) }
                } else appendCards[cardId]?.add(rec)
            }
            for ((key, ids) in appendCells) {
                val bucket = canvasStrokeGrid[key]
                if (bucket == null) canvasStrokeGrid[key] = ids
                else {
                    @Suppress("UNCHECKED_CAST")
                    (bucket as? java.util.ArrayList<String>)?.ensureCapacity(bucket.size + ids.size)
                    bucket.addAll(ids)
                }
            }
            for ((cardId, added) in appendCards) {
                val bucket = cardStrokes[cardId]
                if (bucket == null) cardStrokes[cardId] = added
                else {
                    @Suppress("UNCHECKED_CAST")
                    (bucket as? java.util.ArrayList<StrokeRec>)?.ensureCapacity(bucket.size + added.size)
                    bucket.addAll(added)
                }
            }
            for (connectionId in lockConnections) {
                val connection = connections[connectionId] ?: continue
                if (!connection.locked) {
                    connections[connectionId] = connection.withLocked(true)
                    touchedConnections.add(connectionId)
                }
            }
            if (left.isFinite()) addDirty(RectF(left, top, right, bottom))
            if (records.size > 128) {
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
                Log.i(TAG, "[MosaicIndexPerf] strokeBatch requested=${records.size} changed=${finalWrites.size} " +
                    "cells=${appendCells.size} cards=${appendCards.size} ms=${"%.2f".format(elapsedMs)}")
            }
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
            if (previous == null || !previous.sameNeckGeometry(rec)) touchedCards.add(rec.id)
            previous?.let { addDirty(cardDirtyBounds(it)) }
            
            cards[rec.id] = rec
            rebuildCardOrderLocked()
            addDirty(cardDirtyBounds(rec))
            moved = true
        }

        fun removeCard(id: String): CardRec? {
            val rec = cards.remove(id) ?: return null
            touchedCards.add(id)
            addDirty(cardDirtyBounds(rec))
            cardStrokes.remove(id)?.forEach { strokes.remove(it.id) }
            if (selectedCardIds.remove(id)) selectionDirty = true
            rebuildCardOrderLocked()
            moved = true
            return rec
        }

        fun addConnection(rec: ConnectionRec) {
            if (connections.put(rec.id, rec) == null) newConnections.add(rec.id)
            touchedConnections.add(rec.id)
        }

        fun removeConnection(id: String): ConnectionRec? {
            val rec = connections.remove(id) ?: return null
            touchedConnections.add(id)
            return rec
        }

        
        fun replaceConnections(next: Collection<ConnectionRec>) {
            val nextIds = HashSet<String>(next.size)
            for (c in next) {
                nextIds.add(c.id)
                val previous = connections[c.id]
                if (previous == null) newConnections.add(c.id)
                if (previous == null || previous.fromId != c.fromId || previous.toId != c.toId || previous.locked != c.locked) touchedConnections.add(c.id)
            }
            for (id in connections.keys) if (id !in nextIds) touchedConnections.add(id)
            connections.clear()
            for (c in next) connections[c.id] = c
        }

        
        fun refreshNecksLocked() {
            if (touchedCards.isEmpty() && touchedConnections.isEmpty()) return
            val ids = LinkedHashSet<String>(touchedConnections)
            if (touchedCards.isNotEmpty()) {
                for (c in connections.values) if (touchedCards.contains(c.fromId) || touchedCards.contains(c.toId)) ids.add(c.id)
            }
            for (id in necks.keys) if (!connections.containsKey(id)) ids.add(id)
            for (id in ids) {
                val previous = necks[id]
                val next = connections[id]?.let { buildNeckLocked(it, wasActive = previous != null, isNew = newConnections.contains(id)) }
                if (next == null) {
                    if (previous != null) {
                        necks.remove(id)
                        addDirty(padded(previous.bounds, NECK_PAD))
                        moved = true
                    }
                    continue
                }
                previous?.let { addDirty(padded(it.bounds, NECK_PAD)) }
                necks[id] = next
                addDirty(padded(next.bounds, NECK_PAD))
                moved = true
            }
            touchedCards.clear()
            touchedConnections.clear()
            newConnections.clear()
        }

        private fun buildNeckLocked(conn: ConnectionRec, wasActive: Boolean, isNew: Boolean): NeckRec? {
            val a = cards[conn.fromId] ?: return null
            val b = cards[conn.toId] ?: return null
            
            if (a.colored != b.colored) return null
            val gap = BoardGeometry.edgeDistance(a, b)
            if (gap > NeckGeometry.MAX_DISTANCE) return null
            if (!wasActive && !isNew && gap > NeckGeometry.RECONNECT_DISTANCE) return null
            val path = NeckGeometry.build(a.rect(), b.rect()) ?: return null
            return NeckRec(conn.id, path, a.colored)
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
        
        
        
        
        val decoded = ArrayList<() -> Unit>()
        val pendingStrokes = ArrayList<StrokeRec>()
        fun flushPendingStrokes() {
            if (pendingStrokes.isEmpty()) return
            val batch = pendingStrokes.toList()
            decoded.add { m.addStrokesBatch(batch) }
            pendingStrokes.clear()
        }
        var applied = false
        try {
            val version = buf.short.toInt()
            if (version != PROTOCOL_VERSION && version != 4 && version != 3) {
                Log.w(TAG, "applyOps: unsupported protocol version=$version")
                return 0
            }
            val opCount = buf.int
            repeat(opCount) {
                val op = buf.get().toInt()
                if (op == OP_ADD_STROKE) {
                    pendingStrokes.add(readStroke(buf, version))
                    count += 1
                    return@repeat
                }
                flushPendingStrokes()
                when (op) {
                    OP_REMOVE_STROKES -> {
                        val k = buf.int
                        val ids = ArrayList<String>(k)
                        repeat(k) { ids.add(readString(buf)) }
                        decoded.add { ids.forEach { m.removeStroke(it) } }
                    }
                    OP_UPSERT_CARD -> {
                        val card = readCard(buf)
                        decoded.add { m.upsertCard(card) }
                    }
                    OP_REMOVE_CARDS -> {
                        val k = buf.int
                        val ids = ArrayList<String>(k)
                        repeat(k) { ids.add(readString(buf)) }
                        decoded.add { ids.forEach { m.removeCard(it) } }
                    }
                    OP_SET_SELECTION -> {
                        val k = buf.int
                        val ids = ArrayList<String>(k)
                        repeat(k) { ids.add(readString(buf)) }
                        decoded.add { m.setSelection(ids) }
                    }
                    OP_SET_CONNECTIONS -> {
                        val k = buf.int
                        val next = ArrayList<ConnectionRec>(k)
                        repeat(k) {
                            val id = readString(buf)
                            val from = readString(buf)
                            val to = readString(buf)
                            val locked = if (version >= 5) buf.get().toInt() != 0 else false
                            next.add(ConnectionRec(id, from, to, locked))
                        }
                        decoded.add { m.replaceConnections(next) }
                    }
                    else -> throw IllegalStateException("unknown op=$op at ${buf.position()}")
                }
                count += 1
            }
            flushPendingStrokes()
            synchronized(lock) {
                try {
                    decoded.forEach { it() }
                } finally {
                    m.refreshNecksLocked()
                }
            }
            applied = true
        } catch (error: Throwable) {
            flushPendingStrokes()
            Log.w(TAG, "applyOps: decode failed after $count op(s)", error)
            if (!applied && decoded.isNotEmpty()) {
                synchronized(lock) {
                    runCatching { decoded.forEach { it() } }
                    m.refreshNecksLocked()
                }
            }
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

    // Tight ink box for selection frames; strokeWorldBounds keeps the wide STROKE_PAD for redraw and hit tests.
    fun strokeInkBounds(rec: StrokeRec): RectF {
        val maxPressure = (rec.pressures.maxOrNull() ?: 1f).coerceIn(0f, 1f)
        val rendered = runCatching {
            DrawPathClient.nativePressureWidthUnits(PenStyle.normalizeStoredType(rec.penStyle), rec.drawPathWidth, maxPressure) / rec.sampleScale
        }.getOrDefault(rec.width)
        val half = (rendered / 2f).coerceAtLeast(1f)
        val rect = RectF(rec.bounds.left - half, rec.bounds.top - half, rec.bounds.right + half, rec.bounds.bottom + half)
        val cardId = rec.cardId ?: return rect
        val card = cards[cardId] ?: return rect
        rect.offset(card.x, card.y)
        return rect
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

    
    fun connectionAt(worldX: Float, worldY: Float): ConnectionRec? = synchronized(lock) {
        var best: ConnectionRec? = null
        var bestArea = Float.POSITIVE_INFINITY
        for ((id, neck) in necks) {
            val r = neck.bounds
            if (worldX < r.left - 8f || worldX > r.right + 8f || worldY < r.top - 8f || worldY > r.bottom + 8f) continue
            val area = r.width() * r.height()
            if (area < bestArea) {
                bestArea = area
                best = connections[id]
            }
        }
        best
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

    
    fun cardAndNecksDirtyBounds(card: CardRec): RectF = synchronized(lock) {
        val out = cardDirtyBounds(card)
        for (neck in necks.values) {
            val connection = connections[neck.id] ?: continue
            if (connection.touches(card.id)) out.union(padded(neck.bounds, NECK_PAD))
        }
        out
    }

    
    fun invalidateImages(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val set = paths.toHashSet()
        var dirty: RectF? = null
        synchronized(lock) {
            for (p in set) CardImageCache.invalidate(p)
            for (card in cards.values) {
                if (!set.contains(card.imagePath)) continue
                val r = cardDirtyBounds(card)
                if (dirty == null) dirty = r else dirty!!.union(r)
            }
        }
        listener?.onSceneChanged(dirty ?: return, false)
    }

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

    private fun readStroke(buf: ByteBuffer, version: Int): StrokeRec {
        val id = readString(buf)
        val space = readString(buf)
        val width = buf.float
        val color = buf.int
        val penStyle = buf.short.toInt() and 0xffff
        val sampleScale = buf.float
        val drawPathWidth = if (version >= 4) buf.int else DrawPathClient.widthArgument(PenStyle.fromObjType(penStyle), width)
        val pointCount = buf.int
        require(pointCount > 0 && pointCount <= 1_000_000)
        val points = FloatArray(pointCount * 2)
        val pressures = FloatArray(pointCount)
        for (i in 0 until pointCount) {
            points[2*i] = buf.float; points[2*i+1] = buf.float; pressures[i] = buf.float
        }
        require(sampleScale.isFinite() && sampleScale > 0f)
        val drawPathType = PenStyle.normalizeStoredType(penStyle)
        return StrokeRec(id, space, width, color, points, pressures, drawPathType, sampleScale, drawPathWidth)
    }

    private fun readCard(buf: ByteBuffer): CardRec {
        val id = readString(buf)
        val x = buf.float
        val y = buf.float
        val width = buf.float
        val height = buf.float
        val zIndex = buf.int
        val kind = readString(buf)
        val content = readString(buf)
        val imagePath = readString(buf)
        val noteRef = readString(buf)
        val bgColor = readString(buf)
        val textColor = readString(buf)
        val title = readString(buf)
        return CardRec(id, x, y, width, height, zIndex, kind, content, imagePath, noteRef, bgColor, textColor, title)
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

    
    
    fun buildPolylinePath(points: FloatArray): Path {
        val path = Path()
        val pointCount = points.size / 2
        if (pointCount == 0) return path
        path.moveTo(points[0], points[1])
        if (pointCount == 1) { path.lineTo(points[0] + 0.01f, points[1]); return path }
        for (index in 1 until pointCount) path.lineTo(points[index * 2], points[index * 2 + 1])
        return path
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
