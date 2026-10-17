package me.laumss.mosaic

import android.graphics.RectF
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/** Edits to whole cards: color, size level, delete, pushing cards below a growing one, and handwriting recognized into a text card. */
internal class CardCommands(private val board: BoardInteractionController) {
    companion object {
        const val ACCENT_BG_COLOR = "#FFF9E3"
        const val ACCENT_TEXT_COLOR = "#3f3b2e"
        private const val RECOGNIZE_CARD_MIN_DP = 120f
    }

    private var pendingRecognition: PendingRecognition? = null

    private class PendingRecognition(val rect: RectF, val strokeIds: List<String>)

    internal fun normalizeColoredCardToDefault(card: BoardEngine.CardRec): BoardHistory.Change? {
        if (!card.colored) return null
        val next = card.withColors("", "")
        val change = BoardHistory.Change("card-normalize")
        change.card(card, next)

        reconcileConnectionsForColor(next, change)
        return change
    }

    internal fun toggleSelectedCardBlack() {
        val card = board.selection.selectedCardId?.let { BoardEngine.cards[it] } ?: return
        if (card.kind == "image" || card.kind == "note") return
        val isBlack = card.bgColor.equals("#000000", ignoreCase = true)
        val newBg = if (isBlack) "" else "#000000"
        val newText = if (isBlack) "" else "#ffffff"
        val moved = card.withColors(newBg, newText)
        val change = BoardHistory.Change("card-color").card(card, moved)

        val relinked = reconcileConnectionsForColor(moved, change)
        board.applyChange(change, record = true)
        Log.i(BoardInteractionController.TAG, "toggle card black id=${card.id} wasBlack=$isBlack connections=$relinked")
    }

    private fun reconcileConnectionsForColor(next: BoardEngine.CardRec, change: BoardHistory.Change): String {
        var removed = 0
        var added = 0
        synchronized(BoardEngine.lock) {
            val existing = ArrayList<BoardEngine.ConnectionRec>()
            BoardEngine.connectionsOf(next.id, existing)
            val linked = HashSet<String>()
            for (c in existing) {
                val otherId = c.otherEnd(next.id) ?: continue
                val other = BoardEngine.cards[otherId]
                if (other == null || !BoardGeometry.canConnect(next, other)) {
                    change.connection(c, null)
                    removed++
                } else {
                    linked.add(otherId)
                }
            }
            for (other in BoardEngine.cardsByZ) {
                if (other.id in linked || !BoardGeometry.canConnect(next, other)) continue
                if (BoardGeometry.edgeDistance(next, other) >= BoardGeometry.CONNECT_DISTANCE) continue
                change.connection(null, BoardEngine.ConnectionRec(board.newId("conn-"), next.id, other.id))
                added++
            }
        }
        return "-$removed/+$added"
    }

    internal fun collectCardRemoval(cardId: String, change: BoardHistory.Change) {
        val card = BoardEngine.cards[cardId] ?: return
        BoardEngine.cardStrokes[cardId]?.forEach { change.stroke(it, null) }
        val threads = ArrayList<BoardEngine.StrokeRec>()
        BoardEngine.threadsOf(cardId, threads)
        for (t in threads) {
            if (change.diffs.none { it is BoardHistory.Diff.Stroke && it.before?.id == t.id }) change.stroke(t, null)
        }
        val conns = ArrayList<BoardEngine.ConnectionRec>()
        BoardEngine.connectionsOf(cardId, conns)
        for (c in conns) change.connection(c, null)
        change.card(card, null)
    }

    internal fun cardsBelow(anchor: BoardEngine.CardRec): List<BoardEngine.CardRec> {
        val column = arrayListOf(anchor.rect(RectF()))
        val result = ArrayList<BoardEngine.CardRec>()
        val candidates = synchronized(BoardEngine.lock) { BoardEngine.cards.values.filter { it.id != anchor.id } }
            .sortedBy { it.y }
        val r = RectF()
        for (c in candidates) {
            c.rect(r)
            if (column.any { r.left < it.right && r.right > it.left && r.top > it.top }) {
                result.add(c)
                column.add(RectF(r))
            }
        }
        return result
    }

    internal fun pushCardsBelow(change: BoardHistory.Change, below: List<BoardEngine.CardRec>, grow: Float) {
        val dy = max(0f, grow)
        for (b in below) {
            val current = BoardEngine.cards[b.id] ?: continue
            val target = b.y + dy
            if (current.y != target) change.card(current, current.moved(current.x, target))
        }
    }

    fun deleteSelectedCard() {
        val id = board.selection.selectedCardId ?: return
        val change = BoardHistory.Change("card-delete")
        synchronized(BoardEngine.lock) { collectCardRemoval(id, change) }
        board.selection.setLasso(null)
        board.applyChange(change, record = true)
    }

    fun applySizeLevel(level: BoardGeometry.SizeLevel) {
        val id = board.selection.selectedCardId ?: return
        val card = BoardEngine.cards[id] ?: return
        val rect = RectF(level.rect)
        CardSizeConstraints.constrainRect(card, rect, BoardGeometry.Handle.BOTTOM_RIGHT,
            proportional = card.kind == "image")
        board.applyChange(BoardHistory.Change("size-level").card(card, card.withRect(rect)), record = true)
    }

    fun toggleAccent() {
        val id = board.selection.selectedCardId ?: return
        val card = BoardEngine.cards[id] ?: return
        if (card.kind == "image" || card.kind == "note") return

        val next = if (card.colored) card.withColors("", "")
        else card.withColors(ACCENT_BG_COLOR, ACCENT_TEXT_COLOR)
        val change = BoardHistory.Change("card-accent").card(card, next)
        val relinked = reconcileConnectionsForColor(next, change)
        board.applyChange(change, record = true)
        Log.i(BoardInteractionController.TAG, "toggle card accent id=${card.id} colored=${next.colored} connections=$relinked")
    }

    internal fun recognizeSelection() {
        val sel = board.selection.lasso ?: return
        if (!board.selection.isPureInkSelection(sel)) {
            Log.i(BoardInteractionController.TAG_LASSO, "recognize skipped: selection not pure ink cards=${sel.cardIds.size} strokes=${sel.strokeIds.size}")
            return
        }
        val strokeIds = sel.strokeIds.toList()
        pendingRecognition = PendingRecognition(RectF(sel.rect), strokeIds)
        board.chrome.hideSwitcher()
        board.syncToolViews()
        board.emitter.begin()
        board.emitter.action("recognizeLasso")
        board.emitter.commit()
        Log.i(BoardInteractionController.TAG_LASSO, "recognize requested strokes=${strokeIds.size}")
    }

    fun createRecognizedTextCard(text: String) {
        val pending = pendingRecognition ?: return
        pendingRecognition = null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) { Log.i(BoardInteractionController.TAG_LASSO, "recognize empty text; scene unchanged"); return }
        if (!board.surface.showsBoardChrome) {
            board.selection.setLasso(null)
            board.textEditing.editPageHeader(recognized = trimmed)
            Log.i(BoardInteractionController.TAG_LASSO, "recognize note header strokes=${pending.strokeIds.size} chars=${trimmed.length}")
            return
        }
        val ink = synchronized(BoardEngine.lock) {
            pending.strokeIds.mapNotNull { id -> BoardEngine.strokes[id]?.let { BoardEngine.strokeWorldBounds(it) } }
        }
        val fontScale = handwritingFontScale(ink, trimmed)
        val m = CardTextFormat.WEB.scaled(fontScale)
        // The text sits where the handwriting was: the card's padding goes around the ink.
        val r = ink.fold(null as RectF?) { acc, b -> acc?.apply { union(b) } ?: RectF(b) } ?: RectF(pending.rect)
        val left = r.left - m.padX
        val top = r.top - m.padTop
        val probe = BoardEngine.CardRec("probe", left, top, 1f, 1f, 0, "text", trimmed, fontScale = fontScale)
        val size = CardSizeConstraints.fit(probe,
            (r.width() + m.padX * 2f).coerceAtLeast(RECOGNIZE_CARD_MIN_DP),
            (r.height() + m.padTop + m.padBottom).coerceAtLeast(RECOGNIZE_CARD_MIN_DP))
        val created = BoardEngine.CardRec(
            board.newId("card-"), left, top, size.x, size.y,
            BoardGeometry.nextZIndex(BoardEngine.cards.values), "text", trimmed, fontScale = fontScale,
        )
        val change = BoardHistory.Change("recognize-card").card(null, created)
        synchronized(BoardEngine.lock) {
            for (id in pending.strokeIds) BoardEngine.strokes[id]?.let { change.stroke(it, null) }
        }
        board.selection.setLasso(null)
        board.applyChange(change, record = true)
        board.scheduleChromeUpdate()
        Log.i(BoardInteractionController.TAG_LASSO, "recognize card=${created.id} replaced strokes=${pending.strokeIds.size} chars=${trimmed.length} fontScale=$fontScale")
    }

    // Text scale that makes recognized text about as large as the handwriting. Strokes are grouped into rows; the
    // rows' total length against the same text set on one line at the base size gives the scale. The typical row
    // height only caps it, since rows of handwriting that touch merge into one tall row.
    private fun handwritingFontScale(ink: List<RectF>, text: String): Float {
        val rows = ArrayList<RectF>()
        for (b in ink.sortedBy { it.top }) {
            val row = rows.lastOrNull()
            if (row != null && b.centerY() in row.top..row.bottom) row.union(b) else rows.add(RectF(b))
        }
        val heights = rows.map { it.height() }.filter { it > 0f }.sorted()
        if (heights.isEmpty()) return 1f
        val m = CardTextFormat.WEB
        val byHeight = heights[heights.size / 2] / m.body
        val oneLine = text.replace(Regex("\\s*\\n\\s*"), " ")
        val typeset = BoardContentView.measureTextCardSize(oneLine, 1_000_000f, m, limitHeight = false).x - m.padX * 2f
        val inkLength = rows.fold(0f) { sum, row -> sum + row.width() }
        val scale = if (typeset > 1f) min(inkLength / typeset, byHeight) else byHeight
        // Round to tenths so nearby recognitions share a size.
        return BoardEngine.clampFontScale(Math.round(scale * 10f) / 10f)
    }
}
