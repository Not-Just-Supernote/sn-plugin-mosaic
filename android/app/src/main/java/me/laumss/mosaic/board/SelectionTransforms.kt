package me.laumss.mosaic.board

import me.laumss.mosaic.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
import android.graphics.Color
import android.graphics.Path
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Dragging, resizing and rotating the selection: the live preview on the overlay, and the change committed on release. */
internal class SelectionTransforms(private val board: BoardInteractionController) {
    companion object {
        private const val ROTATE_SNAP_DEG = 3f
    }

    private fun presentationReason(m: MoveSession): BoardPresentation.Reason = when (m) {
        is MoveSession.CardResize -> BoardPresentation.Reason.CARD_ADJUST
        is MoveSession.Selection -> when {
            m.cards.isEmpty() -> BoardPresentation.Reason.TRANSFORM
            m.autoMoveStarted -> BoardPresentation.Reason.CARD_MOVE
            else -> BoardPresentation.Reason.CARD_ADJUST
        }
        is MoveSession.SelectionResize -> if (m.cards.isNotEmpty()) BoardPresentation.Reason.CARD_ADJUST else BoardPresentation.Reason.TRANSFORM
        is MoveSession.SelectionRotate -> BoardPresentation.Reason.TRANSFORM
    }

    fun beginResize(isPen: Boolean, pointerId: Int, wx: Float, wy: Float, card: BoardEngine.CardRec, handle: BoardGeometry.Handle): MoveSession.CardResize {
        // 与套索拖动笔迹相同：拖动期间停掉系统笔迹服务，只由 Mosaic 画预览。
        board.host.drawPath.setSuspended(true, "card-resize")
        val session = MoveSession.CardResize(isPen, pointerId, wx, wy, card, handle)
        card.rect(session.preview)
        card.rect(session.startRect)
        session.inkBounds = attachedInkWorldBounds(card.id)
        session.inkLocalBounds = session.inkBounds?.let { RectF(it).apply { offset(-card.x, -card.y) } }
        session.connectionGuard = ConnectedCardConstraints.capture(card).takeUnless { it.isEmpty }
        val strokeIds = synchronized(BoardEngine.lock) {
            BoardEngine.cardStrokes[card.id]?.map { it.id }.orEmpty()
        }
        val inkPath = Path()
        val previewInk = ArrayList<InteractionOverlayView.PreviewInk>()
        collectSelectionPreview(LassoSelection(session.startRect, listOf(card.id), strokeIds),
            ArrayList(), ArrayList(), ArrayList(), inkPath, previewInk = previewInk)
        board.overlay.beginMovePreview(null, emptyList(), inkPath, BoardInteractionController.PEN_WIDTH_DP * BoardViewport.scale * board.density, ink = previewInk)
        hideFromTiles(listOf(card.id))
        board.overlay.showCardPreview(board.worldRectToPx(session.preview, RectF()), card.kind, card.colored)
        board.presentation.acquire(presentationReason(session))
        updateConnectionPreview(session.connectionGuard, session.preview)
        Log.i(LogTags.INTERACTION, "[CardAdjust] resize begin card=${card.id} handle=$handle corner=${handle.isCorner} pen=$isPen")
        return session
    }

    private fun attachedInkWorldBounds(cardId: String): RectF? {
        var out: RectF? = null
        synchronized(BoardEngine.lock) {
            BoardEngine.cardStrokes[cardId]?.forEach {
                val b = BoardEngine.strokeWorldBounds(it)
                if (out == null) out = RectF(b) else out!!.union(b)
            }
        }
        return out
    }

    private fun computeResizePreview(m: MoveSession.CardResize, wx: Float, wy: Float) {
        val previous = RectF(m.preview)
        if (m.handle.isCorner && isTextCard(m.card)) {
            computeTextScalePreview(m, wx, wy)
            constrainConnectedResize(m, previous)
            return
        }
        when {
            m.card.kind == "note" ->
                BoardGeometry.resizeWidthKeepAspect(m.handle, m.startWorldX, m.startWorldY, m.startRect, wx, wy, m.card.kind, m.preview)
            m.card.kind == "image" && m.handle.isCorner ->
                BoardGeometry.resizeProportional(m.handle, m.startWorldX, m.startWorldY, m.startRect, wx, wy, m.card.kind, m.preview)
            else ->
                BoardGeometry.resizeRect(m.handle, m.startWorldX, m.startWorldY, m.startRect, wx, wy, m.card.kind, m.preview)
        }
        CardSizeConstraints.constrainRect(m.card, m.preview, m.handle,
            proportional = m.card.kind == "image" && m.handle.isCorner, inkBounds = m.inkLocalBounds)
        constrainConnectedResize(m, previous)
    }

    private fun constrainConnectedResize(m: MoveSession.CardResize, previous: RectF) {
        val guard = m.connectionGuard ?: return
        logConnectionLimit(m, guard.constrain(previous, m.preview, m.preview))
        if (m.handle.isCorner && isTextCard(m.card)) {
            m.fontScale = BoardEngine.clampFontScale(m.card.fontScale * m.preview.width() / m.startRect.width())
        }
    }

    private fun logConnectionLimit(m: MoveSession, limited: Boolean) {
        if (limited && !m.connectionLimited) Log.i(LogTags.INTERACTION, "[ConnectionLimit] stopped before bridge break or ink clipping")
        m.connectionLimited = limited
    }

    private fun updateConnectionPreview(guard: ConnectedCardConstraints?, rect: RectF) {
        if (guard == null) return
        val scale = BoardViewport.scale * board.density
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(BoardViewport.panX * board.density, BoardViewport.panY * board.density)
        }
        val bridges = guard.previewPaths(rect).map { (path, dark) ->
            Path().also { path.transform(matrix, it) } to dark
        }
        val ink = guard.bridgeStrokes.flatMap { stroke ->
            stroke.runs.map { run ->
                InteractionOverlayView.PreviewInk(Path().also { run.path.transform(matrix, it) },
                    InkBackdrop.resolve(stroke), run.width * scale, run.filled ||
                        (stroke.isShape && stroke.penStyle == PenStyle.FILLED_SHAPE.objType))
            }
        }
        board.overlay.setConnectionPreview(bridges, ink)
    }

    private fun isTextCard(card: BoardEngine.CardRec): Boolean = card.kind != "image" && card.kind != "note"

    // Corner drag on a text card: the card keeps its shape and its text grows or shrinks with it, so the lines
    // break where they did. The card still covers its handwriting.
    private fun computeTextScalePreview(m: MoveSession.CardResize, wx: Float, wy: Float) {
        val start = m.startRect
        val sw = max(start.width(), 1f)
        val sh = max(start.height(), 1f)
        BoardGeometry.resizeProportional(m.handle, m.startWorldX, m.startWorldY, start, wx, wy, m.card.kind, m.preview)
        var f = m.preview.width() / sw
        val ax = if (m.handle.movesLeft) start.right else start.left
        val ay = if (m.handle.movesTop) start.bottom else start.top
        m.inkBounds?.let { ib ->
            val fx = if (m.handle.movesLeft) (ax - ib.left) / sw else (ib.right - ax) / sw
            val fy = if (m.handle.movesTop) (ay - ib.top) / sh else (ib.bottom - ay) / sh
            f = max(f, max(fx, fy))
        }
        val base = m.card.fontScale
        f = f.coerceIn(BoardEngine.MIN_FONT_SCALE / base, BoardEngine.MAX_FONT_SCALE / base)
        val w = sw * f
        val h = sh * f
        val left = if (m.handle.movesLeft) ax - w else ax
        val top = if (m.handle.movesTop) ay - h else ay
        m.preview.set(left, top, left + w, top + h)
        m.fontScale = BoardEngine.clampFontScale(base * f)
        CardSizeConstraints.constrainRect(m.card.withFontScale(m.fontScale), m.preview, m.handle, proportional = true)
    }

    fun beginSelectionResize(isPen: Boolean, pointerId: Int, wx: Float, wy: Float, sel: LassoSelection, handle: BoardGeometry.Handle): MoveSession.SelectionResize {
        board.host.drawPath.setSuspended(true, "lasso-selection-resize")
        val cards = ArrayList<BoardEngine.CardRec>()
        val strokes = ArrayList<BoardEngine.StrokeRec>()
        synchronized(BoardEngine.lock) {
            for (id in sel.cardIds) BoardEngine.cards[id]?.let { cards.add(it) }
            val strokeIds = sel.strokeIds.toMutableSet()
            for (card in cards) BoardEngine.cardStrokes[card.id]?.forEach { strokeIds.add(it.id) }
            for (id in strokeIds) BoardEngine.strokes[id]?.let { strokes.add(it) }
        }
        val session = MoveSession.SelectionResize(isPen, pointerId, wx, wy, sel, handle, cards, strokes)
        session.startRect.set(sel.rect)
        session.preview.set(sel.rect)

        val cardRects = ArrayList<RectF>()
        val inkPath = Path()
        val previewInk = ArrayList<InteractionOverlayView.PreviewInk>()
        val previewSelection = LassoSelection(sel.rect, sel.cardIds, strokes.map { it.id }, sel.fromLasso)
        val grayBridges = ArrayList<Path>()
        collectSelectionPreview(previewSelection, ArrayList(), ArrayList(), cardRects, inkPath, grayBridges, previewInk)
        board.overlay.beginMovePreview(board.worldRectToPx(sel.rect, RectF()), cardRects, inkPath, BoardInteractionController.PEN_WIDTH_DP * BoardViewport.scale * board.density, cards.map { !it.colored }, grayBridges, ink = previewInk)
        hideFromTiles(sel.cardIds, sel.strokeIds)
        board.presentation.acquire(presentationReason(session))
        Log.i(LogTags.LASSO, "resize begin cards=${cards.size} strokes=${strokes.size} handle=$handle pen=$isPen")
        return session
    }

    private fun pushSelectionResizePreview(m: MoveSession.SelectionResize) {
        val start = m.startRect
        val fx = if (start.width() > 0f) m.preview.width() / start.width() else 1f
        val fy = if (start.height() > 0f) m.preview.height() / start.height() else 1f
        val pivotX = if (m.handle.movesLeft) start.right else start.left
        val pivotY = if (m.handle.movesTop) start.bottom else start.top
        val px = (pivotX * BoardViewport.scale + BoardViewport.panX) * board.density
        val py = (pivotY * BoardViewport.scale + BoardViewport.panY) * board.density
        board.overlay.updateScalePreview(fx, fy, px, py)
    }

    private fun computeSelectionResizePreview(m: MoveSession.SelectionResize, wx: Float, wy: Float) {
        val start = m.startRect
        val handle = m.handle
        val isEdge = handle == BoardGeometry.Handle.TOP || handle == BoardGeometry.Handle.BOTTOM ||
            handle == BoardGeometry.Handle.LEFT || handle == BoardGeometry.Handle.RIGHT
        if (isEdge) {
            val minSize = BoardGeometry.LASSO_MIN_BOX_PX / BoardViewport.scale
            val dx = wx - m.startWorldX
            val dy = wy - m.startWorldY
            var left = start.left; var top = start.top; var right = start.right; var bottom = start.bottom
            when (handle) {
                BoardGeometry.Handle.LEFT -> left = min(start.left + dx, start.right - minSize)
                BoardGeometry.Handle.RIGHT -> right = max(start.right + dx, start.left + minSize)
                BoardGeometry.Handle.TOP -> top = min(start.top + dy, start.bottom - minSize)
                BoardGeometry.Handle.BOTTOM -> bottom = max(start.bottom + dy, start.top + minSize)
                else -> {}
            }
            m.preview.set(left, top, right, bottom)
            constrainSelectionResize(m)
            return
        }
        val oldCornerX = if (handle.movesLeft) start.left else start.right
        val oldCornerY = if (handle.movesTop) start.top else start.bottom
        val ax = if (handle.movesLeft) start.right else start.left
        val ay = if (handle.movesTop) start.bottom else start.top
        val ox = oldCornerX - ax
        val oy = oldCornerY - ay
        val curX = oldCornerX + (wx - m.startWorldX)
        val curY = oldCornerY + (wy - m.startWorldY)
        val denom = ox * ox + oy * oy
        var f = if (denom > 0f) ((curX - ax) * ox + (curY - ay) * oy) / denom else 1f
        f = f.coerceIn(0.1f, 10f)
        val newW = start.width() * f
        val newH = start.height() * f
        val left = if (handle.movesLeft) ax - newW else ax
        val top = if (handle.movesTop) ay - newH else ay
        m.preview.set(left, top, left + newW, top + newH)
        constrainSelectionResize(m)
    }

    private fun constrainSelectionResize(m: MoveSession.SelectionResize) {
        val start = m.startRect
        val scale = CardSizeConstraints.selectionScale(m.cards,
            m.preview.width() / start.width(), m.preview.height() / start.height(), m.handle.isCorner)
        val w = start.width() * scale.x
        val h = start.height() * scale.y
        val x = if (m.handle.movesLeft) start.right - w else start.left
        val y = if (m.handle.movesTop) start.bottom - h else start.top
        m.preview.set(x, y, x + w, y + h)
    }

    fun beginSelectionRotate(isPen: Boolean, pointerId: Int, wx: Float, wy: Float, sel: LassoSelection): MoveSession.SelectionRotate {
        board.host.drawPath.setSuspended(true, "lasso-selection-rotate")
        val strokes = ArrayList<BoardEngine.StrokeRec>()
        val inkPath = Path()
        val previewInk = ArrayList<InteractionOverlayView.PreviewInk>()

        collectSelectionPreview(sel, ArrayList(), strokes, ArrayList(), inkPath, previewInk = previewInk)
        val framePx = board.worldRectToPx(sel.rect, RectF())
        board.overlay.beginMovePreview(framePx, emptyList(), inkPath, BoardInteractionController.PEN_WIDTH_DP * BoardViewport.scale * board.density, ink = previewInk)
        board.overlay.updateRotatePreview(0f, framePx.centerX(), framePx.centerY())
        hideFromTiles(sel.cardIds, sel.strokeIds)
        board.presentation.acquire(BoardPresentation.Reason.TRANSFORM)
        Log.i(LogTags.LASSO, "rotate begin strokes=${strokes.size} pen=$isPen")
        return MoveSession.SelectionRotate(isPen, pointerId, wx, wy, sel, strokes, sel.rect.centerX(), sel.rect.centerY())
    }

    private fun rotateAngle(m: MoveSession.SelectionRotate, wx: Float, wy: Float): Float {
        var a = kotlin.math.atan2(wy - m.pivotY, wx - m.pivotX) - m.startAngle
        val twoPi = (2 * Math.PI).toFloat()
        while (a > Math.PI) a -= twoPi
        while (a < -Math.PI) a += twoPi
        val snap = Math.toRadians(ROTATE_SNAP_DEG.toDouble()).toFloat()
        val quarter = (Math.PI / 2).toFloat()
        val nearest = Math.round(a / quarter) * quarter
        return if (abs(a - nearest) <= snap) nearest else a
    }

    private fun collectSelectionPreview(
        sel: LassoSelection,
        cards: MutableList<BoardEngine.CardRec>,
        strokes: MutableList<BoardEngine.StrokeRec>,
        cardRects: MutableList<RectF>,
        inkPath: Path,
        grayBridges: MutableList<Path>? = null,
        previewInk: MutableList<InteractionOverlayView.PreviewInk>? = null,
    ) {
        val m = android.graphics.Matrix()
        val sPx = BoardViewport.scale * board.density
        m.setScale(sPx, sPx)
        m.postTranslate(BoardViewport.panX * board.density, BoardViewport.panY * board.density)
        val toScreen = android.graphics.Matrix()
        synchronized(BoardEngine.lock) {
            for (id in sel.cardIds) BoardEngine.cards[id]?.let { cards.add(it); cardRects.add(board.worldRectToPx(it.rect(), RectF())) }
            val ids = LinkedHashSet(sel.strokeIds)
            for (id in sel.cardIds) BoardEngine.cardStrokes[id]?.forEach { ids.add(it.id) }
            for (id in ids) BoardEngine.strokes[id]?.let { strokes.add(it) }
            val cardIds = sel.cardIds.toHashSet()
            for (connection in BoardEngine.connections.values) {
                if (connection.fromId in cardIds && connection.toId in cardIds) {
                    BoardEngine.necks[connection.id]?.let { neck ->
                        if (neck.dark && previewInk != null) {
                            previewInk.add(InteractionOverlayView.PreviewInk(
                                Path().also { neck.path.transform(m, it) }, Color.BLACK, 0f, true))
                        } else inkPath.addPath(neck.path, m)
                        if (!neck.dark) grayBridges?.add(Path().also { neck.path.transform(m, it) })
                    }
                }
            }
            for (s in strokes) {
                val card = s.cardId?.let { BoardEngine.cards[it] }
                toScreen.setTranslate(card?.x ?: 0f, card?.y ?: 0f)
                toScreen.postConcat(m)
                if (previewInk != null) {
                    val color = InkBackdrop.resolve(s)
                    for (run in s.runs) {
                        val path = Path().also { run.path.transform(toScreen, it) }
                        previewInk.add(InteractionOverlayView.PreviewInk(path, color, run.width * sPx, run.filled ||
                            (s.isShape && s.penStyle == PenStyle.FILLED_SHAPE.objType)))
                    }
                } else inkPath.addPath(s.path, toScreen)
            }
        }
    }

    fun beginSelectionMove(isPen: Boolean, pointerId: Int, wx: Float, wy: Float, sel: LassoSelection): MoveSession.Selection {
        board.host.drawPath.setSuspended(true, "lasso-selection-move")
        val cards = ArrayList<BoardEngine.CardRec>()
        val strokes = ArrayList<BoardEngine.StrokeRec>()
        val cardRects = ArrayList<RectF>()
        val inkPath = Path()
        val previewInk = ArrayList<InteractionOverlayView.PreviewInk>()
        val grayBridges = ArrayList<Path>()
        collectSelectionPreview(sel, cards, strokes, cardRects, inkPath, grayBridges, previewInk)
        board.overlay.beginMovePreview(
            if (sel.cardIds.isEmpty()) board.worldRectToPx(sel.rect, RectF()) else null,
            cardRects, inkPath, BoardInteractionController.PEN_WIDTH_DP * BoardViewport.scale * board.density, cards.map { !it.colored }, grayBridges, ink = previewInk,
        )
        hideFromTiles(sel.cardIds, sel.strokeIds)
        board.presentation.acquire(if (cards.isNotEmpty()) BoardPresentation.Reason.CARD_ADJUST else BoardPresentation.Reason.TRANSFORM)
        val guard = if (board.selection.isSingleCardSelection(sel)) cards.singleOrNull()?.let {
            ConnectedCardConstraints.capture(it).takeUnless { bounds -> bounds.isEmpty }
        } else null
        val liftZ = if (board.selection.isSingleCardSelection(sel) && guard == null) BoardGeometry.nextZIndex(BoardEngine.cards.values) else null
        Log.i(LogTags.LASSO, "move begin cards=${cards.size} strokes=${strokes.size} pen=$isPen singleCard=${liftZ != null}")
        return MoveSession.Selection(isPen, pointerId, wx, wy, sel, cards, strokes, liftZ).also {
            it.connectionGuard = guard
            cards.singleOrNull()?.let { card -> updateConnectionPreview(guard, card.rect()) }
        }
    }

    fun beginConnectedCardMove(isPen: Boolean, pointerId: Int, wx: Float, wy: Float, card: BoardEngine.CardRec): MoveSession.Selection =
        beginSelectionMove(isPen, pointerId, wx, wy, LassoSelection(card.rect(), listOf(card.id), emptyList()))

    private fun updateSelectionDelta(m: MoveSession.Selection, wx: Float, wy: Float) {
        val dx = wx - m.startWorldX
        val dy = wy - m.startWorldY
        val card = m.cards.singleOrNull()
        val guard = m.connectionGuard
        if (guard == null || card == null) { m.dx = dx; m.dy = dy; return }
        val previous = card.rect().apply { offset(m.dx, m.dy) }
        val requested = card.rect().apply { offset(dx, dy) }
        logConnectionLimit(m, guard.constrain(previous, requested, requested))
        m.dx = requested.left - card.x
        m.dy = requested.top - card.y
    }

    fun updateMoveSession(m: MoveSession, wx: Float, wy: Float) {
        when (m) {
            is MoveSession.CardResize -> {
                computeResizePreview(m, wx, wy)
                board.overlay.updateMovePreview(
                    (m.preview.left - m.startRect.left) * BoardViewport.scale * board.density,
                    (m.preview.top - m.startRect.top) * BoardViewport.scale * board.density,
                )
                board.overlay.showCardPreview(board.worldRectToPx(m.preview, RectF()), m.card.kind, m.card.colored)
                updateConnectionPreview(m.connectionGuard, m.preview)
            }
            is MoveSession.Selection -> {
                val beforeX = m.dx
                val beforeY = m.dy
                updateSelectionDelta(m, wx, wy)
                if (m.cards.isNotEmpty() && !m.autoMoveStarted && (m.dx != beforeX || m.dy != beforeY)) {
                    m.autoMoveStarted = true
                    board.presentation.acquire(BoardPresentation.Reason.CARD_MOVE)
                    board.presentation.release(BoardPresentation.Reason.CARD_ADJUST, linger = false)
                    Log.i(LogTags.INTERACTION, "card drag AUTO started; release returns to held lasso A2")
                }
                board.overlay.updateMovePreview(m.dx * BoardViewport.scale * board.density, m.dy * BoardViewport.scale * board.density)
                m.cards.singleOrNull()?.let { updateConnectionPreview(m.connectionGuard, it.rect().apply { offset(m.dx, m.dy) }) }
            }
            is MoveSession.SelectionResize -> {
                computeSelectionResizePreview(m, wx, wy)
                pushSelectionResizePreview(m)
            }
            is MoveSession.SelectionRotate -> {
                m.angle = rotateAngle(m, wx, wy)
                val px = (m.pivotX * BoardViewport.scale + BoardViewport.panX) * board.density
                val py = (m.pivotY * BoardViewport.scale + BoardViewport.panY) * board.density
                board.overlay.updateRotatePreview(Math.toDegrees(m.angle.toDouble()).toFloat(), px, py)
            }
        }
    }

    fun endMoveSession(m: MoveSession, wx: Float, wy: Float) {
        if (m is MoveSession.Selection) updateSelectionDelta(m, wx, wy)
        val dx = if (m is MoveSession.Selection) m.dx else wx - m.startWorldX
        val dy = if (m is MoveSession.Selection) m.dy else wy - m.startWorldY
        val elapsed = SystemClock.uptimeMillis() - m.startedAt
        when (m) {
            is MoveSession.CardResize -> {
                board.overlay.hideCardPreview()
                board.overlay.endMovePreview()
                board.host.drawPath.setSuspended(false, "card-resize-end")
                computeResizePreview(m, wx, wy)
                unhideFromTiles()
                val start = m.startRect
                val changed = abs(m.preview.left - start.left) > 0.01f || abs(m.preview.top - start.top) > 0.01f ||
                    abs(m.preview.width() - start.width()) > 0.01f || abs(m.preview.height() - start.height()) > 0.01f ||
                    m.fontScale != m.card.fontScale
                if (changed) {
                    val moved = m.card.withRect(m.preview).withFontScale(m.fontScale)
                    val change = BoardHistory.Change("resize").card(m.card, moved)
                    board.applyChange(change, record = true)
                }
                board.selection.refreshAfterCardResize()
                Log.i(LogTags.INTERACTION, "[CardPerf] kind=resize input=${if (m.isPen) "pen" else "touch"} rawMoves=${m.rawMoves} durationMs=$elapsed handle=${m.handle}")
            }
            is MoveSession.Selection -> {
                board.overlay.endMovePreview()
                unhideFromTiles()
                board.host.drawPath.setSuspended(false, "lasso-selection-move-end")
                val single = m.liftZ?.let { z -> m.cards.singleOrNull()?.let { it to z } }
                if (m.connectionGuard != null) {
                    val card = m.cards.single()
                    if (abs(dx) > 0.01f || abs(dy) > 0.01f) {
                        board.applyChange(BoardHistory.Change("connected-card-move").card(card, card.moved(card.x + dx, card.y + dy)), record = true)
                    }
                    // The group selection remains intact; bridge ink stays in world coordinates.
                    board.selection.refreshAfterCardResize()
                    Log.i(LogTags.INTERACTION, "[CardPerf] kind=connected-move card=${card.id} dx=$dx dy=$dy limited=${m.connectionLimited}")
                } else if (single != null && (abs(dx) > 0.01f || abs(dy) > 0.01f)) {
                    val (card, z) = single
                    settleCardDrop(card, card.x + dx, card.y + dy, z)
                    board.selection.refreshSingleCardFrame()
                    Log.i(LogTags.INTERACTION, "[CardPerf] kind=move input=${if (m.isPen) "pen" else "touch"} rawMoves=${m.rawMoves} durationMs=$elapsed")
                } else if (abs(dx) > 0.01f || abs(dy) > 0.01f) {
                    val change = BoardHistory.Change("selection-move")
                    val movedCards = HashMap<String, BoardEngine.CardRec>()
                    for (card in m.cards) {
                        val moved = card.moved(card.x + dx, card.y + dy)
                        movedCards[card.id] = moved
                        change.card(card, moved)
                    }

                    val destX = m.selection.rect.centerX() + dx
                    val destY = m.selection.rect.centerY() + dy
                    val cardsAfter = BoardEngine.cardsByZ.map { movedCards[it.id] ?: it }
                    val destination = BoardGeometry.topCardAt(cardsAfter, destX, destY)

                    val destInGroup = destination != null && movedCards.containsKey(destination.id)
                    for (stroke in m.strokes) {
                        if (stroke.connectionId != null) {
                            change.stroke(stroke, stroke.translatedWorld(dx, dy))
                            continue
                        }
                        val sourceCard = stroke.cardId?.let { BoardEngine.cards[it] }

                        if (sourceCard != null && movedCards.containsKey(sourceCard.id)) continue
                        change.stroke(stroke, stroke.translated(dx, dy, sourceCard, if (destInGroup) sourceCard else destination))
                    }
                    board.applyChange(change, record = true)
                    val rect = RectF(m.selection.rect).apply { offset(dx, dy) }
                    board.selection.lasso = LassoSelection(rect, m.selection.cardIds, m.selection.strokeIds, m.selection.fromLasso)
                    BoardEngine.mutate { setSelection(board.selection.selectionIds()) }
                    Log.i(LogTags.LASSO, "reassigned strokes=${m.strokes.size} destination=${if (destInGroup) "unchanged:in-selection" else destination?.id ?: "canvas"}")
                }
                board.selection.updateLassoOverlay()
                Log.i(LogTags.INTERACTION, "[LassoPerf] input=${if (m.isPen) "pen" else "touch"} rawMoves=${m.rawMoves} durationMs=$elapsed dx=$dx dy=$dy")
            }
            is MoveSession.SelectionResize -> {
                board.overlay.endMovePreview()
                unhideFromTiles()
                board.host.drawPath.setSuspended(false, "lasso-selection-resize-end")
                computeSelectionResizePreview(m, wx, wy)
                val start = m.startRect
                val fx = m.preview.width() / start.width()
                val fy = m.preview.height() / start.height()
                val changed = abs(fx - 1f) > 0.001f || abs(fy - 1f) > 0.001f
                if (changed) {
                    val pivotX = if (m.handle.movesLeft) start.right else start.left
                    val pivotY = if (m.handle.movesTop) start.bottom else start.top
                    val change = BoardHistory.Change("selection-resize")
                    val movedCards = HashMap<String, BoardEngine.CardRec>()
                    for (card in m.cards) {
                        val cr = card.rect()
                        val nl = pivotX + fx * (cr.left - pivotX)
                        val nt = pivotY + fy * (cr.top - pivotY)
                        val nw = cr.width() * fx
                        val nh = cr.height() * fy
                        val nr = RectF(nl, nt, nl + nw, nt + nh)
                        val moved = card.withRect(nr)
                        movedCards[card.id] = moved
                        change.card(card, moved)
                    }
                    for (stroke in m.strokes) {
                        val sourceCard = stroke.cardId?.let { id -> m.cards.find { it.id == id } }
                        val destCard = sourceCard?.let { movedCards[it.id] }
                        change.stroke(stroke, stroke.scaled(fx, fy, pivotX, pivotY, sourceCard, destCard))
                    }
                    board.applyChange(change, record = true)
                    val newRect = RectF(m.preview)
                    board.selection.lasso = LassoSelection(newRect, m.selection.cardIds, m.selection.strokeIds, m.selection.fromLasso)
                    BoardEngine.mutate { setSelection(board.selection.selectionIds()) }
                }
                board.selection.updateLassoOverlay()
                Log.i(LogTags.INTERACTION, "[LassoPerf] kind=selection-resize input=${if (m.isPen) "pen" else "touch"} rawMoves=${m.rawMoves} durationMs=$elapsed fx=$fx fy=$fy")
            }
            is MoveSession.SelectionRotate -> {
                board.overlay.endMovePreview()
                unhideFromTiles()
                board.host.drawPath.setSuspended(false, "lasso-selection-rotate-end")
                m.angle = rotateAngle(m, wx, wy)
                if (abs(m.angle) > 0.001f) {
                    val change = BoardHistory.Change("selection-rotate")
                    for (stroke in m.strokes) {
                        val card = stroke.cardId?.let { BoardEngine.cards[it] }
                        change.stroke(stroke, stroke.rotated(m.angle, m.pivotX, m.pivotY, card, card))
                    }
                    board.applyChange(change, record = true)

                    val sel = LassoSelection(m.selection.rect, m.selection.cardIds, m.selection.strokeIds, m.selection.fromLasso)
                    board.selection.lasso = LassoSelection(board.selection.selectionFrame(sel), sel.cardIds, sel.strokeIds, sel.fromLasso)
                    BoardEngine.mutate { setSelection(board.selection.selectionIds()) }
                }
                board.selection.updateLassoOverlay()
                Log.i(LogTags.INTERACTION, "[LassoPerf] kind=selection-rotate input=${if (m.isPen) "pen" else "touch"} rawMoves=${m.rawMoves} durationMs=$elapsed deg=${Math.toDegrees(m.angle.toDouble())}")
            }
        }
        val reason = presentationReason(m)
        // Restore the static appearance now; the independent lasso request keeps A2
        // active after card dragging or resizing, until the user chooses another tool.
        board.presentation.release(reason, linger = reason == BoardPresentation.Reason.TRANSFORM)
        board.trailCleanup.schedule("card-transform")
    }

    fun cancelMoveSession(m: MoveSession) {
        when (m) {
            is MoveSession.CardResize -> {
                board.overlay.hideCardPreview()
                board.overlay.endMovePreview()
                unhideFromTiles()
                board.host.drawPath.setSuspended(false, "card-resize-cancel")
            }
            is MoveSession.Selection -> { board.overlay.endMovePreview(); unhideFromTiles(); board.host.drawPath.setSuspended(false, "lasso-selection-move-cancel"); board.selection.updateLassoOverlay() }
            is MoveSession.SelectionResize -> { board.overlay.endMovePreview(); unhideFromTiles(); board.host.drawPath.setSuspended(false, "lasso-selection-resize-cancel"); board.selection.updateLassoOverlay() }
            is MoveSession.SelectionRotate -> { board.overlay.endMovePreview(); unhideFromTiles(); board.host.drawPath.setSuspended(false, "lasso-selection-rotate-cancel"); board.selection.updateLassoOverlay() }
        }
        val reason = presentationReason(m)
        board.presentation.release(reason, linger = reason == BoardPresentation.Reason.TRANSFORM)
    }

    // 拖动时原位置不留东西：卡片连同桥和线、选中的笔迹都从内容层拿掉，只看叠加层的预览。
    private fun hideFromTiles(cardIds: Collection<String>, strokeIds: Collection<String> = emptyList()) {
        unhideFromTiles()
        BoardEngine.hiddenCardIds = cardIds.toHashSet()
        BoardEngine.hiddenStrokeIds = strokeIds.toHashSet()
        board.content.markWorldDirty(hiddenDirtyBounds())
    }

    private fun unhideFromTiles() {
        if (BoardEngine.hiddenCardIds.isEmpty() && BoardEngine.hiddenStrokeIds.isEmpty()) return
        val dirty = hiddenDirtyBounds()
        BoardEngine.hiddenCardIds = emptySet()
        BoardEngine.hiddenStrokeIds = emptySet()
        board.content.markWorldDirty(dirty)
    }

    private fun hiddenDirtyBounds(): List<RectF> = synchronized(BoardEngine.lock) {
        val out = ArrayList<RectF>()
        for (id in BoardEngine.hiddenCardIds) BoardEngine.cards[id]?.let { out.add(BoardEngine.cardAndNecksDirtyBounds(it)) }
        for (id in BoardEngine.hiddenStrokeIds) BoardEngine.strokes[id]?.let { out.add(BoardEngine.strokeWorldBounds(it)) }
        out
    }

    private fun settleCardDrop(card: BoardEngine.CardRec, x: Float, y: Float, liftZ: Int) {
        val snap = BoardGeometry.findSnap(BoardEngine.cardsByZ, card, x, y)
        val settled = card.moved(snap.x, snap.y).withZ(liftZ)
        val change = BoardHistory.Change("move").card(card, settled)
        val target = snap.targetId
        if (target != null && BoardEngine.connectionBetween(card.id, target) == null) {
            change.connection(null, BoardEngine.ConnectionRec(board.newId("conn-"), card.id, target))
        }
        board.applyChange(change, record = true)
    }
}

/** A drag on the selection in progress; the board is changed only when it ends. */
internal sealed class MoveSession(val isPen: Boolean, val pointerId: Int, val startWorldX: Float, val startWorldY: Float) {
    var connectionLimited = false
    var rawMoves = 0
    val startedAt = SystemClock.uptimeMillis()

    class CardResize(isPen: Boolean, pointerId: Int, sx: Float, sy: Float, val card: BoardEngine.CardRec, val handle: BoardGeometry.Handle) : MoveSession(isPen, pointerId, sx, sy) {
        val preview = RectF()

        val startRect = RectF()

        var inkBounds: RectF? = null
        var inkLocalBounds: RectF? = null
        var connectionGuard: ConnectedCardConstraints? = null
        // A corner drag on a text card scales its text with the card; this is the scale it would get.
        var fontScale = card.fontScale
    }

    class Selection(isPen: Boolean, pointerId: Int, sx: Float, sy: Float, val selection: LassoSelection, val cards: List<BoardEngine.CardRec>, val strokes: List<BoardEngine.StrokeRec>, val liftZ: Int?) : MoveSession(isPen, pointerId, sx, sy) {
        var autoMoveStarted = false
        var connectionGuard: ConnectedCardConstraints? = null
        var dx = 0f
        var dy = 0f
    }

    class SelectionResize(
        isPen: Boolean, pointerId: Int, sx: Float, sy: Float,
        val selection: LassoSelection,
        val handle: BoardGeometry.Handle,
        val cards: List<BoardEngine.CardRec>,
        val strokes: List<BoardEngine.StrokeRec>,
    ) : MoveSession(isPen, pointerId, sx, sy) {
        val startRect = RectF()
        val preview = RectF()
    }

    class SelectionRotate(
        isPen: Boolean, pointerId: Int, sx: Float, sy: Float,
        val selection: LassoSelection,
        val strokes: List<BoardEngine.StrokeRec>,
        val pivotX: Float,
        val pivotY: Float,
    ) : MoveSession(isPen, pointerId, sx, sy) {
        val startAngle = kotlin.math.atan2(sy - pivotY, sx - pivotX)
        var angle = 0f
    }
}
