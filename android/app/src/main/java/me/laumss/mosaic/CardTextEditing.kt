package me.laumss.mosaic

import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import android.widget.FrameLayout
import kotlin.math.max

/** Editing the text of a card or of an open note's header, with the board moved clear of the keyboard. */
internal class CardTextEditing(private val board: BoardInteractionController) {
    companion object {
        private val FONT_LEVELS = floatArrayOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 6f)
    }

    private var editorView: FrameLayout? = null

    private var keyboardShift = 0f

    // Steps of the text editor's smaller and larger buttons.
    private fun nextFontLevel(current: Float, step: Int): Float {
        val levels = FONT_LEVELS
        return if (step > 0) levels.firstOrNull { it > current + 0.01f } ?: levels.last()
        else levels.lastOrNull { it < current - 0.01f } ?: levels.first()
    }

    internal fun editCard(card: BoardEngine.CardRec) {
        close()
        board.host.inkEnabled = false
        val original = card.content
        val note = card.kind == "note"

        val below = if (note) board.cardCommands.cardsBelow(card) else emptyList()
        var finished = false

        val darkText = !note && card.colored
        val editor = CardTextEditor(
            board.host.context,
            if (note) BoardContentView.noteHeaderText(card) else original,
            darkText,
            fill = if (card.colored) Color.BLACK else board.content.cardFillColor,
            contentScale = if (note) card.width / ScrollingDocument.WIDTH else card.fontScale,
            headerMode = note,
            onFontStep = if (note) null else { step ->
                val current = BoardEngine.cards[card.id]
                if (current == null) null else {
                    val next = grownToFit(current.withFontScale(nextFontLevel(current.fontScale, step)), current.height)
                    val change = BoardHistory.Change("card-font-live")
                    if (!next.sameGeometry(current)) change.card(current, next)
                    board.cardCommands.pushCardsBelow(change, below, next.height - card.height)
                    board.applyChange(change, record = false)
                    next.fontScale
                }
            },
            onKeyboard = { obscuredTop -> avoidKeyboard(editRegionWorld(card.id), obscuredTop) },
            cardFrame = { availableBottom ->
                val r = editRegionWorld(card.id) ?: card.rect(RectF())
                val top = board.screenY(r.top)
                val bottom = board.screenY(r.bottom).coerceAtMost(availableBottom.toFloat())
                RectF(board.screenX(r.left), top, board.screenX(r.right), bottom.coerceAtLeast(top + board.dp(72f)))
            },
            onChange = { markdown ->
                val current = BoardEngine.cards[card.id]
                if (current != null) {
                    val next = if (note) withNoteHeader(current, markdown) else grownToFit(current.withContent(markdown), card.height)
                    val change = BoardHistory.Change("card-text-edit-live")
                    if (!next.sameGeometry(current)) change.card(current, next)
                    board.cardCommands.pushCardsBelow(change, below, next.height - card.height)
                    board.applyChange(change, record = false)
                }
            },
            onFinish = {
                if (!finished) {
                    finished = true
                    val current = BoardEngine.cards[card.id]
                    close()
                    if (current != null && (current.content != original || current.fontScale != card.fontScale)) {
                        val change = BoardHistory.Change("card-text-edit").card(card, current)
                        for (b in below) {
                            val moved = BoardEngine.cards[b.id] ?: continue
                            if (!moved.sameGeometry(b)) change.card(b, moved)
                        }
                        board.applyChange(change, record = true)
                    }
                }
            },
        )
        editorView = editor
        board.host.addView(editor, FrameLayout.LayoutParams(-1, -1))
        board.host.bringChildToFront(editor)
    }

    private fun avoidKeyboard(region: RectF?, obscuredTop: Int?) {
        val r = region ?: return
        val shiftPx = keyboardShift * board.density
        val baseTop = board.screenY(r.top) + shiftPx
        val baseBottom = board.screenY(r.bottom) + shiftPx
        val wantPx = if (obscuredTop != null && baseBottom > obscuredTop) {
            minOf((board.host.height - obscuredTop).toFloat(), baseTop.coerceAtLeast(0f)).coerceAtLeast(0f)
        } else 0f
        val want = wantPx / board.density
        if (want == keyboardShift) return
        Log.i(BoardInteractionController.TAG, "card editor keyboard shift ${keyboardShift}->$want obscuredTop=$obscuredTop")
        BoardEngine.setViewport(BoardEngine.panX, BoardEngine.panY + keyboardShift - want, BoardEngine.scale)
        keyboardShift = want
        board.scheduleChromeUpdate()
    }

    private fun grownToFit(card: BoardEngine.CardRec, minHeight: Float): BoardEngine.CardRec {
        val needed = BoardContentView.measureTextCardSize(card.content, card.width, CardTextFormat.metricsFor(card)).y
        val height = max(minHeight, needed)
        return if (height == card.height) card else card.withRect(RectF(card.x, card.y, card.x + card.width, card.y + height))
    }

    private fun editRegionWorld(cardId: String): RectF? {
        val card = BoardEngine.cards[cardId] ?: return null
        val r = card.rect(RectF())
        if (card.kind == "note") {
            val k = card.width / ScrollingDocument.WIDTH
            r.bottom = r.top + BoardContentView.noteHeaderHeight(BoardContentView.noteHeaderOf(card)) * k
        }
        return r
    }

    private fun withNoteHeader(card: BoardEngine.CardRec, plain: String): BoardEngine.CardRec {
        val k = card.width / ScrollingDocument.WIDTH
        val oldH = BoardContentView.noteHeaderHeight(BoardContentView.noteHeaderOf(card)) * k

        val probe = BoardEngine.CardRec(card.id, card.x, card.y, card.width, card.height, card.zIndex, card.kind, plain,
            card.imagePath, card.noteRef, card.bgColor, card.textColor, "")
        val lines = BoardContentView.noteHeaderLines(probe)
        val newH = BoardContentView.noteHeaderHeight(BoardContentView.noteHeaderMarkdown(lines)) * k
        val height = (card.height - oldH + newH).coerceAtLeast(BoardGeometry.MIN_CARD_SIZE)
        return BoardEngine.CardRec(card.id, card.x, card.y, card.width, height, card.zIndex, card.kind, plain,
            card.imagePath, card.noteRef, card.bgColor, card.textColor, lines.firstOrNull().orEmpty())
    }

    internal fun editPageHeader(recognized: String? = null) {
        if (board.surface.showsBoardChrome) return
        val ref = board.notes.noteController.document?.ref ?: return
        val source = board.notes.suspendedBoard?.scene?.cards?.firstOrNull { it.noteRef == ref } ?: return
        close()
        board.host.inkEnabled = false
        val header = BoardContentView.noteHeaderText(source)

        var initial = header
        var selection: IntRange? = null
        if (recognized != null) {
            val text = recognized.replace(Regex("\\s*\\n\\s*"), " ").trim()
            val split = header.indexOf('\n')
            val title = if (split < 0) header else header.substring(0, split)
            val body = if (split < 0) "" else header.substring(split + 1)
            if (title.isBlank()) {
                initial = text + if (body.isNotEmpty()) "\n" + body else ""
                selection = 0 until text.length
            } else {
                val prefix = title + "\n" + body + if (body.isNotBlank()) " " else ""
                initial = prefix + text
                selection = prefix.length until initial.length
            }
        }
        val editor = CardTextEditor(
            board.host.context,
            initial,
            false,
            initialSelection = selection,
            initialDirty = recognized != null,
            fill = Color.WHITE,
            headerMode = true,
            onKeyboard = { obscuredTop -> avoidKeyboard(board.notes.pageHeaderRegionWorld(), obscuredTop) },
            cardFrame = { availableBottom ->
                val r = board.notes.pageHeaderRegionWorld()
                val top = board.screenY(r.top)
                val bottom = board.screenY(r.bottom).coerceAtMost(availableBottom.toFloat())
                RectF(board.screenX(r.left), top, board.screenX(r.right), bottom.coerceAtLeast(top + board.dp(72f)))
            },
            onChange = { plain -> updatePageHeader(ref, plain) },
            onFinish = { close() },
        )
        editorView = editor
        board.host.addView(editor, FrameLayout.LayoutParams(-1, -1))
        board.host.bringChildToFront(editor)
        Log.i(BoardInteractionController.TAG, "note header edit ref=$ref")
    }

    private fun updatePageHeader(ref: String, plain: String) {
        val suspended = board.notes.suspendedBoard ?: return
        val card = suspended.scene.cards.firstOrNull { it.noteRef == ref } ?: return
        val next = withNoteHeader(card, plain)
        if (next.sameGeometry(card)) return
        board.notes.suspendedBoard = NoteSession.SuspendedBoard(
            suspended.scene.copy(cards = suspended.scene.cards.map { if (it.id == card.id) next else it }),
            suspended.history, suspended.lasso,
        )
        val oldH = board.notes.currentNoteHeaderHeight
        board.notes.setPageNoteHeader(BoardContentView.noteHeaderOf(next))
        board.content.setNoteHeader(board.notes.currentNoteHeader, board.notes.currentNoteHeaderPlaceholder, board.notes.notePageScale)

        val d = (board.notes.currentNoteHeaderHeight - oldH) * BoardEngine.scale
        if (d != 0f) BoardEngine.setViewport(BoardEngine.panX, BoardEngine.panY + d, BoardEngine.scale)

        board.emitter.begin()
        board.emitter.cardUpsert(next)
        board.emitter.commit()
    }

    internal fun close() {
        val overlay = editorView ?: return
        if (overlay is CardTextEditor) overlay.release()
        board.host.removeView(overlay)
        editorView = null
        if (keyboardShift != 0f) {
            BoardEngine.setViewport(BoardEngine.panX, BoardEngine.panY + keyboardShift, BoardEngine.scale)
            keyboardShift = 0f
            board.scheduleChromeUpdate()
        }
        board.syncToolViews()
    }
}
