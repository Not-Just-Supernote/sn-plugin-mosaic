package me.laumss.mosaic

import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/** The current selection: picking cards and strokes, its frame and action buttons, and the actions themselves. */
internal class SelectionController(private val board: BoardInteractionController) {
    companion object {
        const val LASSO_BOUNDS_PAD = 10f
    }

    val selectedCardId: String?
        get() = lasso?.let { if (isSingleCardSelection(it)) it.cardIds.firstOrNull() else null }

    var selectBelowArmed = false

    var selectBelowPendingRestore = false

    var lasso: LassoSelection? = null

    private var selectionCopyBusy = false

    private fun cardResizeLocked(cardId: String): Boolean {
        val component = connectedComponent(cardId).toSet()
        return synchronized(BoardEngine.lock) {
            BoardEngine.connections.values.any { connection ->
                connection.locked && component.contains(connection.fromId) && component.contains(connection.toId)
            }
        }
    }

    fun isSingleCardSelection(sel: LassoSelection): Boolean {
        if (sel.cardIds.size != 1) return false
        if (cardResizeLocked(sel.cardIds[0])) return false
        if (sel.strokeIds.isEmpty()) return true
        val id = sel.cardIds[0]
        return sel.strokeIds.all { BoardEngine.strokes[it]?.cardId == id }
    }

    fun selectSingleCard(card: BoardEngine.CardRec) {
        val frame = card.rect(RectF()).apply { inset(-LASSO_BOUNDS_PAD, -LASSO_BOUNDS_PAD) }
        setLasso(LassoSelection(frame, listOf(card.id), emptyList()))
    }

    private fun connectedComponent(cardId: String): List<String> = synchronized(BoardEngine.lock) {
        ConnectionBindings.expand(listOf(cardId), BoardEngine.cards.keys, BoardEngine.connections.values)
    }

    private fun expandBoundSelection(sel: LassoSelection): LassoSelection = synchronized(BoardEngine.lock) {
        if (sel.cardIds.isEmpty()) return@synchronized sel
        val cards = ConnectionBindings.expand(sel.cardIds, BoardEngine.cards.keys, BoardEngine.connections.values)
        val cardSet = cards.toHashSet()
        val bridges = BoardEngine.connections.values.filter {
            it.locked && it.fromId in cardSet && it.toId in cardSet
        }.mapTo(HashSet()) { it.id }
        if (bridges.isEmpty()) return@synchronized sel
        val strokes = sel.strokeIds.filterTo(LinkedHashSet()) { it in BoardEngine.strokes }
        for (stroke in BoardEngine.strokes.values) {
            if (stroke.cardId in cardSet || stroke.connectionId in bridges) strokes.add(stroke.id)
        }
        val expanded = LassoSelection(sel.rect, cards, strokes.toList(), sel.fromLasso)
        LassoSelection(selectionFrame(expanded), cards, expanded.strokeIds, sel.fromLasso)
    }

    fun selectConnectedComponent(card: BoardEngine.CardRec) {
        val ids = connectedComponent(card.id)
        if (ids.size <= 1) { selectSingleCard(card); return }
        val frame = RectF()
        val rect = RectF()
        val strokeIds = ArrayList<String>()
        synchronized(BoardEngine.lock) {
            for (id in ids) {
                val member = BoardEngine.cards[id] ?: continue
                member.rect(rect)
                if (frame.isEmpty) frame.set(rect) else frame.union(rect)
            }
            val idSet = ids.toSet()
            for (stroke in BoardEngine.strokes.values) {
                val cardHit = stroke.cardId?.let(idSet::contains) == true
                val connectionHit = stroke.connectionId?.let { connectionId ->
                    val connection = BoardEngine.connections[connectionId]
                    connection != null && (idSet.contains(connection.fromId) || idSet.contains(connection.toId))
                } == true
                if (cardHit || connectionHit) strokeIds.add(stroke.id)
            }
        }
        frame.inset(-LASSO_BOUNDS_PAD, -LASSO_BOUNDS_PAD)
        setLasso(LassoSelection(frame, ids, strokeIds))
    }

    fun selectCards(ids: List<String>) {
        val cards = synchronized(BoardEngine.lock) { ids.mapNotNull { BoardEngine.cards[it] } }
        if (cards.isEmpty()) return
        if (cards.size == 1) { selectSingleCard(cards[0]); return }
        val frame = RectF()
        val r = RectF()
        for (c in cards) { c.rect(r); if (frame.isEmpty) frame.set(r) else frame.union(r) }
        frame.inset(-LASSO_BOUNDS_PAD, -LASSO_BOUNDS_PAD)
        setLasso(LassoSelection(frame, cards.map { it.id }, emptyList()))
        Log.i(BoardInteractionController.TAG_LASSO, "selectCards from JS count=${cards.size}")
    }

    /** Selects what a lasso drawn through [pts] (view pixels, x/y pairs) encloses. */
    fun finalizeLasso(pts: FloatArrayList) {
        if (pts.size < 6) { setLasso(null); return }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < pts.size) {
            val x = pts[i]; val y = pts[i + 1]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            i += 2
        }
        val minBox = BoardGeometry.LASSO_MIN_BOX_PX * board.density
        if (maxX - minX < minBox) { val c = (minX + maxX) / 2f; minX = c - minBox / 2f; maxX = c + minBox / 2f }
        if (maxY - minY < minBox) { val c = (minY + maxY) / 2f; minY = c - minBox / 2f; maxY = c + minBox / 2f }
        val minScreenY = 0f
        val boxW = min(maxX - minX, board.host.width.toFloat())
        val boxH = min(maxY - minY, board.host.height - minScreenY)
        minX = min(max(minX, 0f), board.host.width - boxW)
        minY = min(max(minY, minScreenY), board.host.height - boxH)
        maxX = minX + boxW
        maxY = minY + boxH
        val query = RectF(board.worldX(minX), board.worldY(minY), board.worldX(maxX), board.worldY(maxY))
        val hits = selectInRect(query)
        if (hits == null) {
            Log.i(BoardInteractionController.TAG_LASSO, "finalize empty query=$query")
            setLasso(null)
            return
        }
        val frame = selectionFrame(hits)
        val selection = LassoSelection(frame, hits.cardIds, hits.strokeIds, fromLasso = true)
        Log.i(BoardInteractionController.TAG_LASSO, "finalize query=$query frame=$frame cards=${selection.cardIds.size} strokes=${selection.strokeIds.size}")
        setLasso(selection)
    }

    // LASSO_BOUNDS_PAD is world units on the board; above 100% (an open note) it stays the same on screen.
    fun lassoPadWorld(): Float = LASSO_BOUNDS_PAD / BoardEngine.scale.coerceAtLeast(1f)

    fun selectionFrame(sel: LassoSelection): RectF {
        val out = RectF()
        var any = false
        val r = RectF()
        synchronized(BoardEngine.lock) {
            for (id in sel.cardIds) {
                val card = BoardEngine.cards[id] ?: continue
                card.rect(r)
                if (!any) { out.set(r); any = true } else out.union(r)
            }
            for (id in sel.strokeIds) {
                val stroke = BoardEngine.strokes[id] ?: continue
                val bounds = BoardEngine.strokeInkBounds(stroke)
                if (!any) { out.set(bounds); any = true } else out.union(bounds)
            }
        }
        if (!any) return sel.rect
        val pad = lassoPadWorld()
        out.inset(-pad, -pad)
        return out
    }

    private fun selectInRect(rect: RectF): LassoSelection? {
        val cardIds = ArrayList<String>()
        val strokeIds = ArrayList<String>()
        val r = RectF()
        synchronized(BoardEngine.lock) {
            for (card in BoardEngine.cardsByZ) if (rect.contains(card.rect(r))) cardIds.add(card.id)
            val wholeCards = cardIds.toHashSet()

            val inside = ArrayList<BoardEngine.StrokeRec>()
            BoardEngine.canvasStrokesInside(rect, inside)

            for (s in BoardEngine.strokes.values) {
                val cardId = s.cardId ?: continue
                if (cardId in wholeCards || !BoardEngine.cards.containsKey(cardId)) continue
                if (rect.contains(BoardEngine.strokeWorldBounds(s))) inside.add(s)
            }
            val ink = inside.filterNot { isClosedShape(it) }

            val touched = BoardEngine.strokes.values.filter { s ->
                if (!isClosedShape(s)) return@filter false
                val cardId = s.cardId
                if (cardId != null && (cardId in wholeCards || !BoardEngine.cards.containsKey(cardId))) return@filter false
                shapeTouchesRect(s, rect)
            }

            val otherContent = wholeCards.isNotEmpty() || ink.any { i -> touched.none { inkOnShape(i, it) } }
            for (i in ink) strokeIds.add(i.id)
            for (shape in touched) {
                val take = if (otherContent) {
                    rect.contains(BoardEngine.strokeWorldBounds(shape))
                } else {
                    ink.none { inkOnShape(it, shape) }
                }
                if (take) strokeIds.add(shape.id)
            }

            if (strokeIds.isEmpty() && wholeCards.isEmpty()) {
                partialInkIn(rect, strokeIds)
            }

            if (strokeIds.isEmpty()) {
                for (card in BoardEngine.cardsByZ) {
                    if (card.id in wholeCards) continue
                    if (RectF.intersects(rect, card.rect(r))) cardIds.add(card.id)
                }
            }
        }
        if (cardIds.isEmpty() && strokeIds.isEmpty()) return null
        return LassoSelection(rect, cardIds, strokeIds)
    }

    private fun partialInkIn(rect: RectF, out: MutableList<String>) {
        val candidates = ArrayList<BoardEngine.StrokeRec>()
        BoardEngine.queryCanvasStrokes(rect, candidates)
        for (s in BoardEngine.strokes.values) {
            if (s.cardId != null && RectF.intersects(rect, BoardEngine.strokeWorldBounds(s))) candidates.add(s)
        }
        for (s in candidates) {
            if (isClosedShape(s)) continue
            val cardId = s.cardId
            val card = if (cardId != null) BoardEngine.cards[cardId] ?: continue else null
            val ox = card?.x ?: 0f; val oy = card?.y ?: 0f
            val p = s.points
            var i = 0
            while (i + 1 < p.size) {
                if (rect.contains(p[i] + ox, p[i + 1] + oy)) { out.add(s.id); break }
                i += 2
            }
        }
    }

    private fun isClosedShape(s: BoardEngine.StrokeRec): Boolean =
        s.isShape && Shapes.kindOf(s).let { it != null && it != Shapes.Kind.LINE }

    private fun shapeTouchesRect(shape: BoardEngine.StrokeRec, worldRect: RectF): Boolean {
        val card = shape.cardId?.let { BoardEngine.cards[it] }
        val local = RectF(worldRect)
        if (card != null) local.offset(-card.x, -card.y)
        if (!RectF.intersects(local, shape.bounds)) return false
        val probe = Path().apply { addRect(local, Path.Direction.CW) }
        return probe.op(shape.path, Path.Op.INTERSECT) && !probe.isEmpty
    }

    private fun inkOnShape(ink: BoardEngine.StrokeRec, shape: BoardEngine.StrokeRec): Boolean {
        if (ink.id == shape.id || ink.space != shape.space || !shape.bounds.contains(ink.bounds)) return false
        val b = RectF(ink.bounds).apply { inset(-0.01f, -0.01f) }
        return Path().apply {
            addRect(b, Path.Direction.CW)
            op(shape.path, Path.Op.DIFFERENCE)
        }.isEmpty
    }

    fun setLasso(requested: LassoSelection?) {
        val next = requested?.let(::expandBoundSelection)

        if (next == null && selectBelowPendingRestore) {
            selectBelowPendingRestore = false
            Log.i(BoardInteractionController.TAG_LASSO, "select-below selection cleared -> toolbar back to ${board.arbiter.describe()}")
        }
        lasso = next
        if (next == null && board.shapeSelectionPending && board.arbiter.hasOverride(ToolArbiter.Source.SHAPE)) {
            board.shapeSelectionPending = false
            board.applyTransition(board.arbiter.forceExit(ToolArbiter.Source.SHAPE), "shape-selection-cleared")
        }
        board.inkling.exportLassoStrokes(next)
        BoardEngine.mutate { setSelection(selectionIds()) }
        updateLassoOverlay()
        board.syncToolViews()
        board.scheduleChromeUpdate()
        if (next == null) {
            board.handler.postDelayed({
                if (lasso == null && !board.pen.penContact && !board.lassoMode) {
                    board.presentation.release(BoardPresentation.Reason.LASSO, linger = false)
                    board.content.markSettleRequested()
                    board.content.postInvalidateOnAnimation()
                }
            }, 700L)
        }
        if (next != null) {
            board.handler.post {
                if (!board.pen.penContact) {
                    board.syncToolViews()
                    board.overlay.invalidate()
                    board.content.markSettleRequested()
                    board.content.postInvalidateOnAnimation()
                    board.host.invalidate()
                }
            }
        }
    }

    fun selectionIds(): List<String> {
        val sel = lasso ?: return emptyList()
        return if (isSingleCardSelection(sel)) emptyList() else sel.cardIds.toList()
    }

    fun updateLassoOverlay() {
        val current = lasso
        if (current == null) {
            board.overlay.setLassoFrame(null)
            return
        }
        val framePx = board.worldRectToPx(current.rect, RectF())
        val singleCard = isSingleCardSelection(current)
        val shapeKind = shapeKindOf(current)
        val selectedShape = shapeStrokeOf(current)
        val shapeFilled = selectedShape?.penStyle == PenStyle.FILLED_SHAPE.objType

        val kind = if (singleCard) BoardEngine.cards[current.cardIds[0]]?.kind else null
        val baseTypes = when {
            singleCard && kind == "image" -> listOf("trash")
            singleCard && kind == "note" -> listOf("trash", "edit-text")
            singleCard && kind == "text" -> listOf("trash", "note", "black", "edit-text")
            singleCard -> listOf("trash", "note", "black")
            shapeKind != null -> listOf("trash") + shapeActionTypes(shapeKind, shapeFilled)

            isPureInkSelection(current) -> listOf("trash", "recognize")
            else -> listOf("trash")
        }

        val actions = baseTypes + "copy"
        val types = if (board.noteShots.canInsert(current)) actions + "shot" else actions

        val onDark = singleCard &&
            (BoardEngine.cards[current.cardIds[0]]?.colored == true)
        board.overlay.topInsetPx = board.chrome.toolbarHeightPx().toFloat()
        board.overlay.setLassoFrame(framePx, lassoActionRects(framePx, types),
            cornerHandles = !singleCard || kind != "note",
            edgeHandles = singleCard || shapeKind != null, onDark = onDark,
            rotateHandle = selectionRotatable(current),
            horizontalEdgesOnly = kind == "note")
    }

    fun shapeKindOf(sel: LassoSelection): Shapes.Kind? {
        if (sel.cardIds.isNotEmpty()) return null
        val stroke = shapeStrokeOf(sel) ?: return null
        return Shapes.kindOf(stroke)
    }

    private fun shapeStrokeOf(sel: LassoSelection): BoardEngine.StrokeRec? =
        sel.strokeIds.asSequence().mapNotNull { BoardEngine.strokes[it] }
            .firstOrNull { it.isShape && Shapes.kindOf(it) != null }

    private fun shapeActionTypes(kind: Shapes.Kind, filled: Boolean): List<String> {
        val fillAction = if (filled) "hollow" else "fill"
        return when (kind) {
            Shapes.Kind.RECT -> listOf("square", fillAction)
            Shapes.Kind.ELLIPSE -> listOf("circle", fillAction)
            Shapes.Kind.TRIANGLE -> listOf("iso", "equi", "right", fillAction)
            Shapes.Kind.LINE -> emptyList()
        }
    }

    fun isPureInkSelection(sel: LassoSelection): Boolean {
        if (sel.cardIds.isNotEmpty() || sel.strokeIds.isEmpty()) return false
        return synchronized(BoardEngine.lock) {
            sel.strokeIds.all { id ->
                val s = BoardEngine.strokes[id] ?: return@all false
                s.cardId == null && s.connectionId == null &&
                    s.penStyle != PenStyle.FILLED_SHAPE.objType && !s.isShape
            }
        }
    }

    fun selectionRotatable(sel: LassoSelection): Boolean =
        sel.strokeIds.isNotEmpty() && sel.cardIds.isEmpty()

    private fun lassoActionRects(frame: RectF, types: List<String>): List<Pair<RectF, String>> {
        val d = board.density
        val size = InteractionOverlayView.LASSO_ACTION_SIZE_DP * d
        val gap = InteractionOverlayView.LASSO_ACTION_GAP_DP * d
        val spacing = InteractionOverlayView.LASSO_ACTION_SPACING_DP * d
        val margin = InteractionOverlayView.LASSO_ACTION_MARGIN_DP * d
        val w = board.host.width.toFloat()
        val h = board.host.height.toFloat()
        val n = types.size
        val colH = n * size + max(0, n - 1) * spacing
        val rowW = n * size + max(0, n - 1) * spacing
        val right = frame.right + gap
        val left = frame.left - gap - size
        val sideFits = { x: Float -> x >= margin && x + size + margin <= w }
        if (sideFits(right) || sideFits(left)) {
            val x = if (sideFits(right)) right else left
            val y = min(max(frame.top + 4f * d, margin), max(margin, h - colH - margin))
            return types.mapIndexed { i, t ->
                val top = y + i * (size + spacing)
                Pair(RectF(x, top, x + size, top + size), t)
            }
        }
        val startX = min(max(frame.centerX() - rowW / 2f, margin), max(margin, w - rowW - margin))
        val below = frame.bottom + gap
        val above = frame.top - gap - size
        val y = if (below + size + margin <= h) below
        else if (above >= margin) above
        else min(max(below, margin), max(margin, h - size - margin))
        return types.mapIndexed { i, t ->
            val x = startX + i * (size + spacing)
            Pair(RectF(x, y, x + size, y + size), t)
        }
    }

    fun selectionHit(sel: LassoSelection, wx: Float, wy: Float): Boolean {
        if (sel.rect.contains(wx, wy)) return true
        val r = RectF()
        for (id in sel.cardIds) {
            val card = BoardEngine.cards[id] ?: continue
            if (card.rect(r).contains(wx, wy)) return true
        }
        return false
    }

    fun handleLassoAction(action: String) {
        when (action) {
            "trash" -> deleteLassoSelection()
            "copy" -> copyLassoSelection()
            "note" -> board.notes.convertCardToNote()
            "black" -> board.cardCommands.toggleSelectedCardBlack()
            "recognize" -> board.cardCommands.recognizeSelection()
            "shot" -> board.noteShots.insertSelection()
            "edit-text" -> {
                val card = lasso?.cardIds?.singleOrNull()?.let { BoardEngine.cards[it] }
                if (card?.kind == "text" || card?.kind == "note") board.textEditing.editCard(card)
            }
            "square" -> convertShape(action) { Shapes.toSquare(it) }
            "circle" -> convertShape(action) { Shapes.toCircle(it) }
            "iso" -> convertShape(action) { Shapes.toTriangle(it, Shapes.TriangleForm.ISOSCELES) }
            "equi" -> convertShape(action) { Shapes.toTriangle(it, Shapes.TriangleForm.EQUILATERAL) }
            "right" -> convertShape(action) { Shapes.toTriangle(it, Shapes.TriangleForm.RIGHT) }
            "fill" -> setShapeFill(true)
            "hollow" -> setShapeFill(false)
        }
    }

    private fun setShapeFill(filled: Boolean) {
        val sel = lasso ?: return
        val stroke = shapeStrokeOf(sel) ?: return
        val style = if (filled) PenStyle.FILLED_SHAPE else PenStyle.PEN
        if (stroke.penStyle == style.objType) return

        val change = BoardHistory.Change(if (filled) "shape-fill" else "shape-hollow")
            .stroke(stroke, stroke.withPenStyle(style))
        board.applyChange(change, record = true)
        updateLassoOverlay()
    }

    fun selectStroke(rec: BoardEngine.StrokeRec) {
        val frame = synchronized(BoardEngine.lock) { RectF(BoardEngine.strokeInkBounds(rec)) }
        val pad = lassoPadWorld()
        frame.inset(-pad, -pad)
        setLasso(LassoSelection(frame, emptyList(), listOf(rec.id)))
    }

    private fun convertShape(action: String, transform: (FloatArray) -> FloatArray) {
        val sel = lasso ?: return
        val kind = shapeKindOf(sel) ?: return
        val stroke = shapeStrokeOf(sel) ?: return
        val next = stroke.withPoints(transform(stroke.points))
        if (next.points.contentEquals(stroke.points)) return
        board.applyChange(BoardHistory.Change("shape-$action").stroke(stroke, next), record = true)
        Log.i(BoardInteractionController.TAG_LASSO, "shape convert $kind -> $action")
        selectStroke(next)
    }

    private fun copyLassoSelection() {
        val sel = lasso ?: return
        if (selectionCopyBusy || board.surfaceSwitching) return
        val offset = 24f / BoardEngine.scale.coerceAtLeast(0.01f)
        val plan = SelectionCopy.build(sel.cardIds, sel.strokeIds, BoardEngine.snapshotScene(), offset, board.notes.notesDirectory, board::newId)
        if (plan.change.isEmpty) return
        fun commit() {
            board.applyChange(plan.change, record = true)
            val frame = RectF(sel.rect).apply { offset(offset, offset) }
            val copied = LassoSelection(frame, plan.cardIds, plan.strokeIds, sel.fromLasso)
            setLasso(LassoSelection(selectionFrame(copied), copied.cardIds, copied.strokeIds, copied.fromLasso))
            Log.i(BoardInteractionController.TAG_LASSO, "copy cards=${plan.cardIds.size} strokes=${plan.strokeIds.size} offset=$offset")
        }
        if (plan.noteRefs.isEmpty()) { commit(); return }
        if (board.notes.notesDirectory.isBlank()) return
        selectionCopyBusy = true
        val directory = board.notes.notesDirectory
        val generation = board.inkling.pasteGeneration.get()
        board.notes.noteController.copyAssets(directory, plan.noteRefs) { result ->
            selectionCopyBusy = false
            result.onFailure { Log.e(BoardInteractionController.TAG_LASSO, "copy note assets failed", it) }
            if (result.isFailure) return@copyAssets
            if (lasso !== sel || generation != board.inkling.pasteGeneration.get() || !board.surface.showsBoardChrome || board.surfaceSwitching) {
                board.notes.noteController.discardCopiedAssets(directory, plan.noteRefs.values)
                return@copyAssets
            }
            commit()
        }
    }

    fun deleteLassoSelection() {
        val sel = lasso ?: return
        val change = BoardHistory.Change("lasso-delete")
        synchronized(BoardEngine.lock) {
            for (id in sel.strokeIds) BoardEngine.strokes[id]?.let { change.stroke(it, null) }
            if (sel.strokeIds.isEmpty()) {
                for (id in sel.cardIds) board.cardCommands.collectCardRemoval(id, change)
            }
        }
        setLasso(null)
        board.applyChange(change, record = true)
        Log.i(BoardInteractionController.TAG_LASSO, "deleted strokes=${sel.strokeIds.size} cards=${sel.cardIds.size}")
    }

    fun refreshSingleCardFrame() {
        val sel = lasso ?: return
        if (!isSingleCardSelection(sel)) return
        val card = BoardEngine.cards[sel.cardIds[0]] ?: return
        val frame = card.rect(RectF()).apply { inset(-LASSO_BOUNDS_PAD, -LASSO_BOUNDS_PAD) }
        lasso = LassoSelection(frame, sel.cardIds, sel.strokeIds, sel.fromLasso)
        BoardEngine.mutate { setSelection(selectionIds()) }
        updateLassoOverlay()
    }

    fun singleCardFrameHandle(sel: LassoSelection, wx: Float, wy: Float): BoardGeometry.Handle? {
        val radius = BoardGeometry.CARD_HANDLE_HIT_PX / max(BoardEngine.scale, 0.25f)
        val kind = sel.cardIds.firstOrNull()?.let { BoardEngine.cards[it]?.kind }
        return ResizeHandles.hit(ResizeHandles.forCard(kind), sel.rect, wx, wy, radius)
    }

    fun shapeEdgeHandle(sel: LassoSelection, wx: Float, wy: Float): BoardGeometry.Handle? {
        val radius = BoardGeometry.CARD_HANDLE_HIT_PX / max(BoardEngine.scale, 0.25f)
        return ResizeHandles.hit(ResizeHandles.forSelection(false, true, false), sel.rect, wx, wy, radius)
    }

    fun selectionCornerHandle(sel: LassoSelection, wx: Float, wy: Float): BoardGeometry.Handle? {
        if (isSingleCardSelection(sel)) return null
        val radius = BoardGeometry.CARD_HANDLE_HIT_PX / max(BoardEngine.scale, 0.25f)
        return ResizeHandles.hit(ResizeHandles.forSelection(true, shapeKindOf(sel) != null, false), sel.rect, wx, wy, radius)
    }

    fun toggleSelectBelow() {
        if (selectBelowArmed) { cancelSelectBelow("toggle-off"); return }

        setLasso(null)
        selectBelowArmed = true
        board.chrome.setSelectBelowActive(true)
        board.syncToolViews()
        Log.i(BoardInteractionController.TAG_LASSO, "select-below armed ${board.arbiter.describe()}")
    }

    private fun cancelSelectBelow(reason: String) {
        if (!selectBelowArmed) return
        selectBelowArmed = false
        board.chrome.setSelectBelowActive(false)
        board.syncToolViews()
        Log.i(BoardInteractionController.TAG_LASSO, "select-below cancelled reason=$reason")
    }

    fun handleSelectBelow(wy: Float) {
        selectBelowArmed = false
        board.chrome.setSelectBelowActive(false)

        val bounds = RectF()
        var any = false
        val r = RectF()
        synchronized(BoardEngine.lock) {
            for (card in BoardEngine.cardsByZ) {
                if (!any) { bounds.set(card.rect(r)); any = true } else bounds.union(card.rect(r))
            }
            for (s in BoardEngine.strokes.values) {
                val b = BoardEngine.strokeWorldBounds(s)
                if (!any) { bounds.set(b); any = true } else bounds.union(b)
            }
        }
        val hits = if (any && bounds.bottom > wy) {
            selectInRect(RectF(bounds.left - 1f, wy, bounds.right + 1f, bounds.bottom + 1f))
        } else null

        if (hits != null) {
            selectBelowPendingRestore = true
            setLasso(LassoSelection(selectionFrame(hits), hits.cardIds, hits.strokeIds))
            Log.i(BoardInteractionController.TAG_LASSO, "select-below hit cards=${hits.cardIds.size} strokes=${hits.strokeIds.size} y=$wy")
        } else {
            board.syncToolViews()
            Log.i(BoardInteractionController.TAG_LASSO, "select-below nothing below y=$wy")
        }
    }
}
