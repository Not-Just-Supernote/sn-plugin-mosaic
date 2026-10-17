package me.laumss.mosaic

import android.graphics.RectF
import android.util.Log
import kotlin.math.hypot
import kotlin.math.max

/** Strokes that edit cards instead of leaving ink: a held frame makes a card, a bracket on an edge grows one, a stroke out of a card makes a linked card or a thread. */
internal class CardStrokeGestures(private val board: BoardInteractionController) {
    companion object {
        const val CARD_FRAME_HOLD_MS = 500L
        const val CARD_FRAME_HOLD_MOVE_SLOP_DP = 3f
        private const val CARD_FRAME_PREVIEW_CANCEL_MOVE_DP = 8f
        const val CARD_FRAME_CAPTURE_INK_RATIO = 0.70f
        // 方括号扩卡时离挡路卡片留的空隙。
        private const val CARD_EXTEND_GAP = 12f
        // Room left around ink when a card grows to take in a stroke written past its edge.
        const val CARD_GROW_PAD = 16f
    }

    private var frameHoldTask: Runnable? = null

    /** Turns a finished stroke into a card edit if it is one of these gestures; false leaves it as ink. */
    fun consumeStroke(s: PenSession.Write, wx: Float, wy: Float): Boolean {
        var candidate = s.frameCandidate
        if (candidate != null) {
            val moved = hypot(wx - s.frameRecognizedX, wy - s.frameRecognizedY) * BoardEngine.scale
            if (moved >= CARD_FRAME_PREVIEW_CANCEL_MOVE_DP) candidate = null
        }
        board.overlay.hideFramePreview()
        if (candidate != null) {
            board.pen.discardWriteTrail(s, "card-frame")
            createCardFromFrame(candidate)
            Log.i(BoardInteractionController.TAG_PEN, "UP consumed as card frame")
            return true
        }
        if (extendCardFromBracket(s)) {
            Log.i(BoardInteractionController.TAG_PEN, "UP consumed as card edge extend")
            return true
        }
        if (completeCardStroke(s)) {
            Log.i(BoardInteractionController.TAG_PEN, "UP consumed as card connection")
            return true
        }
        return false
    }

    fun cancelFrameHold() {
        frameHoldTask?.let { board.handler.removeCallbacks(it) }
        frameHoldTask = null
    }

    fun updateFrameGesture(s: PenSession.Write, wx: Float, wy: Float) {
        val scale = max(0.01f, BoardEngine.scale)
        if (s.frameCandidate != null) {
            val moved = hypot(wx - s.frameRecognizedX, wy - s.frameRecognizedY) * scale
            if (moved < CARD_FRAME_PREVIEW_CANCEL_MOVE_DP) return
            s.frameCandidate = null
            board.overlay.hideFramePreview()
            Log.i(BoardInteractionController.TAG_PEN, "[CardFrame] preview cancelled movement=${moved}dp")
        }
        val movement = hypot(wx - s.frameLastMeaningfulX, wy - s.frameLastMeaningfulY) * scale
        if (movement < CARD_FRAME_HOLD_MOVE_SLOP_DP) return
        s.frameLastMeaningfulX = wx
        s.frameLastMeaningfulY = wy
        cancelFrameHold()
        val task = Runnable {
            frameHoldTask = null
            if (!board.pen.penContact || board.pen.penSession !== s) return@Runnable
            s.frameEvaluations++
            val evaluation = CardFrameRecognizer.evaluate(s.points.toArray(), CardFrameRecognizer.Options(viewportScale = BoardEngine.scale))
            val rect = evaluation.rect
            if (rect == null) {
                Log.i(BoardInteractionController.TAG_PEN, "[CardFrame] hold rejected attempt=${s.frameEvaluations} reason=${evaluation.reason} ${evaluation.metrics?.format()}")
                return@Runnable
            }
            s.frameCandidate = rect
            s.frameRecognizedX = s.points[s.points.size - 2]
            s.frameRecognizedY = s.points[s.points.size - 1]
            board.overlay.showFramePreview(board.worldRectToPx(BoardGeometry.cardRectFromFrame(rect), RectF()))
            Log.i(BoardInteractionController.TAG_PEN, "[CardFrame] hold recognized attempt=${s.frameEvaluations} ${evaluation.metrics?.format()}")
        }
        frameHoldTask = task
        board.handler.postDelayed(task, CARD_FRAME_HOLD_MS)
    }

    private fun createCardFromFrame(frame: RectF) {
        val rect = BoardGeometry.cardRectFromFrame(frame)
        val created = BoardEngine.CardRec(board.newId("card-"), rect.left, rect.top, rect.width(), rect.height(), BoardGeometry.nextZIndex(BoardEngine.cards.values))
        val change = BoardHistory.Change("card-frame").card(null, created)

        val candidates = ArrayList<BoardEngine.StrokeRec>()
        synchronized(BoardEngine.lock) { BoardEngine.queryCanvasStrokes(rect, candidates) }
        var captured = 0
        for (stroke in candidates) {
            if (strokeCoverage(stroke, rect) < CARD_FRAME_CAPTURE_INK_RATIO) continue
            change.stroke(stroke, stroke.translated(0f, 0f, null, created))
            captured++
        }
        board.applyChange(change, record = true)
        Log.i(BoardInteractionController.TAG_PEN, "[CardFrame] created card=${created.id} rect=$rect capturedInk=$captured")
    }

    // 在卡片边上画方括号，卡片朝那一侧变宽或变高，最多长到挡路的卡片前。框进新区域的画布笔迹收进卡片。
    private fun extendCardFromBracket(s: PenSession.Write): Boolean {
        if (!board.surface.allowsCardFrame || s.points.size < 8) return false
        val world = s.points.toArray()
        var i = 0
        while (i < world.size) {
            world[i] += s.cardX
            world[i + 1] += s.cardY
            i += 2
        }
        val result = CardEdgeExtend.recognize(world, BoardEngine.cardsByZ.asReversed(), BoardEngine.scale)
        val match = result.match
        if (match == null) {
            result.note?.let { Log.i(BoardInteractionController.TAG_PEN, "[CardExtend] rejected $it") }
            return false
        }
        val card = match.card
        val target = synchronized(BoardEngine.lock) {
            CardEdgeExtend.clampToBlockers(card, match.side, match.rect, BoardEngine.cards.values, CARD_EXTEND_GAP)
        }
        // 认出是方括号就吃掉这一笔，挡死了也不留下笔迹。
        board.pen.discardWriteTrail(s, "card-extend")
        if (target.width() - card.width < 1f && target.height() - card.height < 1f) {
            Log.i(BoardInteractionController.TAG_PEN, "[CardExtend] blocked card=${card.id} side=${match.side} ${match.metrics}")
            return true
        }
        val grown = card.withRect(target)
        val change = BoardHistory.Change("card-extend").card(card, grown)
        // 卡片上的笔迹存的是相对卡片左上角的坐标，向左或向上扩时要补回偏移。
        if (grown.x != card.x || grown.y != card.y) synchronized(BoardEngine.lock) {
            BoardEngine.cardStrokes[card.id]?.forEach { change.stroke(it, it.translated(0f, 0f, card, grown)) }
        }
        val added = when (match.side) {
            CardEdgeExtend.Side.LEFT -> RectF(grown.x, grown.y, card.x, grown.y + grown.height)
            CardEdgeExtend.Side.RIGHT -> RectF(card.x + card.width, grown.y, grown.x + grown.width, grown.y + grown.height)
            CardEdgeExtend.Side.TOP -> RectF(grown.x, grown.y, grown.x + grown.width, card.y)
            CardEdgeExtend.Side.BOTTOM -> RectF(grown.x, card.y + card.height, grown.x + grown.width, grown.y + grown.height)
        }
        val candidates = ArrayList<BoardEngine.StrokeRec>()
        synchronized(BoardEngine.lock) { BoardEngine.queryCanvasStrokes(added, candidates) }
        var captured = 0
        for (stroke in candidates) {
            if (stroke.space != "canvas" || strokeCoverage(stroke, added) < CARD_FRAME_CAPTURE_INK_RATIO) continue
            change.stroke(stroke, stroke.translated(0f, 0f, null, grown))
            captured++
        }
        board.applyChange(change, record = true)
        val clamped = target != match.rect
        Log.i(BoardInteractionController.TAG_PEN, "[CardExtend] card=${card.id} side=${match.side} rect=$target clamped=$clamped capturedInk=$captured ${match.metrics}")
        return true
    }

    private fun strokeCoverage(stroke: BoardEngine.StrokeRec, rect: RectF): Float {
        val pts = stroke.points
        val n = pts.size / 2
        if (n == 0) return 0f
        var inside = 0
        var i = 0
        while (i < pts.size) {
            if (rect.contains(pts[i], pts[i + 1])) inside++
            i += 2
        }
        return inside.toFloat() / n
    }

    // Grows a text card to take in a stroke written past its edge; the card keeps its strokes in place on the board.
    fun growCardForStroke(rec: BoardEngine.StrokeRec): BoardHistory.Change? = synchronized(BoardEngine.lock) {
        val cardId = rec.cardId ?: return null
        val card = BoardEngine.cards[cardId] ?: return null
        if (card.kind != "text") return null
        val old = card.rect(RectF())
        val ink = RectF(rec.bounds).apply { offset(card.x, card.y) }
        if (old.contains(ink)) return null
        val grown = RectF(old)
        if (ink.left < old.left) grown.left = ink.left - CARD_GROW_PAD
        if (ink.top < old.top) grown.top = ink.top - CARD_GROW_PAD
        if (ink.right > old.right) grown.right = ink.right + CARD_GROW_PAD
        if (ink.bottom > old.bottom) grown.bottom = ink.bottom + CARD_GROW_PAD
        val r = RectF()
        for (other in BoardEngine.cards.values) {
            if (other.id != cardId && RectF.intersects(grown, other.rect(r)) && !RectF.intersects(old, r)) {
                Log.i(BoardInteractionController.TAG_PEN, "card grow skipped card=$cardId blocked-by=${other.id}")
                return null
            }
        }
        val next = card.withRect(grown)
        val change = BoardHistory.Change("draw-grow").card(card, next)
        if (next.x != card.x || next.y != card.y) {
            BoardEngine.cardStrokes[cardId]?.forEach { change.stroke(it, it.translated(0f, 0f, card, next)) }
            change.stroke(null, rec.translated(0f, 0f, card, next))
        } else {
            change.stroke(null, rec)
        }
        Log.i(BoardInteractionController.TAG_PEN, "card grow card=$cardId $old -> $grown")
        change
    }

    private fun completeCardStroke(s: PenSession.Write): Boolean {
        val sourceId = s.cardId ?: return false
        if (s.points.size < 4) return false
        if (s.continuousWrite) return false
        val source = BoardEngine.cards[sourceId] ?: return false
        val fx = s.points[0]; val fy = s.points[1]
        val lx = s.points[s.points.size - 2]; val ly = s.points[s.points.size - 1]
        if (hypot(lx - fx, ly - fy) < BoardGeometry.CARD_STROKE_MIN_DISTANCE) return false
        val endX = source.x + lx
        val endY = source.y + ly
        return completeCardLink(source, endX, endY) { reason -> board.pen.discardWriteTrail(s, reason) }
    }

    private fun completeCardLink(source: BoardEngine.CardRec, endX: Float, endY: Float,
                                 onRecognized: (String) -> Unit): Boolean {
        val sourceId = source.id
        val target = BoardGeometry.topCardAt(BoardEngine.cardsByZ, endX, endY)
        if (target?.id == sourceId) return false
        // Ending on this card's own bridge, or on a card it already links to, is writing across the link.
        if (target == null) {
            val bridge = BoardEngine.connectionAt(endX, endY)
            if (bridge != null && bridge.touches(sourceId)) return false
        } else if (BoardEngine.connectionBetween(sourceId, target.id) != null || BoardEngine.threadBetween(sourceId, target.id) != null) {
            return false
        }

        onRecognized(if (target != null) "card-connection" else "card-from-stroke")
        if (target != null) {
            if (!BoardGeometry.canConnect(source, target)) {
                Log.i(BoardInteractionController.TAG_PEN, "card thread rejected source=$sourceId target=${target.id} reason=color-mismatch")
                return true
            }
            val thread = BoardEngine.threadStroke(board.newId("stroke-"), source, target)
            if (thread == null) {
                Log.i(BoardInteractionController.TAG_PEN, "card thread rejected source=$sourceId target=${target.id} reason=overlap")
                return true
            }
            board.applyChange(BoardHistory.Change("thread").stroke(null, thread), record = true)
            return true
        }
        val rect = BoardGeometry.cardFromStrokeRect(source, endX, endY)

        val created = BoardEngine.CardRec(
            board.newId("card-"), rect.left, rect.top, rect.width(), rect.height(), BoardGeometry.nextZIndex(BoardEngine.cards.values),
            bgColor = source.bgColor, textColor = source.textColor,
        )
        board.applyChange(
            BoardHistory.Change("card-from-stroke")
                .card(null, created)
                .connection(null, BoardEngine.ConnectionRec(board.newId("conn-"), sourceId, created.id)),
            record = true,
        )
        return true
    }
}
