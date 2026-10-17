package me.laumss.mosaic

import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.View
import java.util.UUID

/** An open note card: switching the board to the note page and back, the page frame and header, and turning cards into notes. */
internal class NoteSession(private val board: BoardInteractionController) {
    companion object {
        const val NOTE_CARD_BOARD_WIDTH = 450f

        fun noteCardBoardSize(contentHeight: Float, cardWidth: Float = NOTE_CARD_BOARD_WIDTH, out: FloatArray, header: String = "") {
            val width = cardWidth.coerceAtLeast(BoardGeometry.MIN_CARD_SIZE)
            val headerH = BoardContentView.noteHeaderHeight(header) * width / ScrollingDocument.WIDTH
            out[0] = width
            out[1] = (contentHeight * width / ScrollingDocument.WIDTH + headerH).coerceAtLeast(BoardGeometry.MIN_CARD_SIZE)
        }
    }

    internal val noteController = NoteController()

    internal var notesDirectory: String = ""

    internal var suspendedBoard: SuspendedBoard? = null

    internal var currentNoteHeader: String? = null

    internal var currentNoteHeaderPlaceholder = false

    internal var currentNoteHeaderHeight = 0f

    // View scale of the open note page; the header is laid out at screen size inside it.
    internal var notePageScale = 1f

    private var noteEntryCard: BoardEngine.CardRec? = null

    private var noteHeaderHoverBlocked = false

    // Bottom of the zoomed-out note page in page units; infinite on the board.
    private var notePageBottom = Float.POSITIVE_INFINITY

    // Toolbar height the note's top inset was last laid out with.
    private var noteToolbarInset = 0f

    internal class SuspendedBoard(val scene: BoardEngine.SceneSnapshot, val history: BoardHistory, val lasso: LassoSelection?)

    /** The plugin is closing inside a note: save it, and put the board scene back for the scene cache. */
    fun leaveOnDetach() {
        val suspended = suspendedBoard ?: return
        noteController.flush(false) { result -> result.onFailure { Log.e(BoardInteractionController.TAG, "note detach save failed", it) } }
        suspendedBoard = null
        board.history = suspended.history
        board.selection.lasso = null
        board.surface = board.whiteboard
        BoardEngine.replaceScene(suspended.scene)
        board.chrome.setNoteMode(false)
        WhiteboardSceneGate.release()
        noteController.clear()
    }

    fun setNotesDirectory(path: String) {
        notesDirectory = path
        if (path.isNotBlank()) MosaicSession.saveNotesDirectory(board.host.context, path)
        restoreSessionNote()
    }

    // Reopens the note the plugin was closed in, as soon as its card is in the scene (from the scene cache or JS).
    fun preparePendingNoteImport(): Boolean {
        if (!PreparedNoteImport.hasPendingRequest()) return false
        MosaicSession.saveNoteRef(board.host.context, null)
        if (!board.surfaceSwitching && suspendedBoard != null) closeNote()
        board.releasePaintHold("pending-note-import")
        return true
    }

    internal fun restoreSessionNote() {
        if (preparePendingNoteImport()) return
        if (suspendedBoard != null || board.surfaceSwitching) return
        val ref = MosaicSession.savedNoteRef(board.host.context) ?: return
        val hasCard = synchronized(BoardEngine.lock) { BoardEngine.cards.values.any { it.noteRef == ref } }
        if (!hasCard) {
            // JS's own scene has no such card: the note is gone, so the board shows normally.
            if (SceneCache.jsSceneArrived) {
                MosaicSession.saveNoteRef(board.host.context, null)
                board.releasePaintHold("session-note-missing")
            }
            return
        }
        whenLaidOut { openNote(ref) }
    }

    private fun whenLaidOut(action: () -> Unit) {
        if (board.host.width > 0 && board.host.height > 0) { board.host.post(action); return }
        board.host.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                if (v.width <= 0 || v.height <= 0) return
                v.removeOnLayoutChangeListener(this)
                v.post(action)
            }
        })
    }

    private fun enterSurface(next: BoardSurface, scene: BoardEngine.SceneSnapshot) {
        board.trailCleanup.cancel("surface-change")
        if (board.surface !== next) board.panZoom.resetNoteScrollMode()
        if (board.surface !== next) board.inkling.invalidateClipboardWork("surface:${next.name}")
        board.surface = next

        NoteLinks.setActive(next.showsBoardChrome)
        BoardEngine.replaceScene(scene)
        board.chrome.setNoteMode(!next.showsBoardChrome)
        board.content.setNoteHeader(if (next.showsBoardChrome) null else currentNoteHeader, currentNoteHeaderPlaceholder, notePageScale)
        noteToolbarInset = board.toolbarHeightWorld()
        syncNotePageFrame()
        if (next.showsBoardChrome) setNoteHeaderHoverBlocked(false)
        board.syncToolViews()

        board.presentation.refresh("surface:${next.name}")

        val noteRef = if (next.showsBoardChrome) null else noteController.document?.ref
        InklingLink.setSurface(next.name, noteRef, "surface-switch")
        board.host.inklingCallbacks.onInklingSurfaceChanged(next.name, noteRef)
        MosaicSession.saveNoteRef(board.host.context, noteRef)
        applyTemplateForSurface(next, noteRef)
        Log.i(BoardInteractionController.TAG, "surface entered name=${next.name}")
    }

    internal fun applyTemplateForSurface(next: BoardSurface, noteRef: String?) {
        val template = TemplateStore.board(board.host.context)
        val pageWidth = if (next.showsBoardChrome) 0f else ScrollingDocument.WIDTH
        board.content.setBackgroundTemplate(template, pageWidth)
        board.chrome.setCurrentTemplate(template)
    }

    internal fun openNote(ref: String?) {
        if (suspendedBoard != null || board.surfaceSwitching || ref.isNullOrBlank() || !ref.matches(Regex("note-[a-zA-Z0-9-]+")) || notesDirectory.isBlank()) return
        val sourceCard = synchronized(BoardEngine.lock) { BoardEngine.cards.values.firstOrNull { it.noteRef == ref } } ?: return
        board.inkling.invalidateClipboardWork("open-note")
        val suspended = SuspendedBoard(BoardEngine.snapshotScene(), board.history, board.selection.lasso)
        board.surfaceSwitching = true
        WhiteboardSceneGate.hold()
        noteController.open(notesDirectory, ref, create = false) { result ->
            board.surfaceSwitching = false
            result.onFailure { WhiteboardSceneGate.release(); board.releasePaintHold("note-open-failed"); Log.e(BoardInteractionController.TAG, "note open failed", it) }
            result.onSuccess { doc ->
                suspendedBoard = suspended
                board.history = BoardHistory(); board.selection.lasso = null
                noteEntryCard = sourceCard
                // The page always spans the view width, whatever the screen resolution.
                val scale = ScrollingDocument.viewScale(board.host.width / board.density)
                notePageScale = scale
                setPageNoteHeader(BoardContentView.noteHeaderOf(sourceCard))
                val note = NoteSurface(noteController, board.host.context, { board.host.height / board.density }, { board.host.width / board.density }, { notePageScale }, ::noteTopInsetWorld, ::closeNote) { exitFromNote("launcher-pull") }
                val panY = board.toolbarHeightWorld() + currentNoteHeaderHeight * scale - doc.clampScroll(doc.scrollY, board.host.height / board.density / scale) * scale
                val scene = BoardEngine.SceneSnapshot(doc.strokes, emptyList(), emptyList(), emptyList(), emptyList(), 0f, panY, scale)
                enterSurface(note, scene)
                board.releasePaintHold("note-opened")
                Log.i(BoardInteractionController.TAG, "note opened ref=$ref height=${doc.contentHeight} scrollY=${doc.scrollY}")
                preparePendingNoteImport()
            }
        }
    }

    private fun closeNote() {
        val suspended = suspendedBoard ?: return
        if (board.surfaceSwitching) return
        board.inkling.invalidateClipboardWork("close-note")
        board.surfaceSwitching = true
        board.textEditing.close()
        board.cancelActiveInteractions("note-close")

        val liveStrokes = synchronized(BoardEngine.lock) { BoardEngine.strokes.values.toList() }
        val compacted = ScrollingDocument.compact(liveStrokes)
        if (compacted.isNotEmpty() || noteController.document?.strokes?.isEmpty() != false) {
            noteController.changed(compacted)
        }
        noteController.flush(true) { result ->
            board.surfaceSwitching = false
            result.onFailure { Log.e(BoardInteractionController.TAG, "note save failed; keeping note open", it) }
            result.onSuccess { doc ->
                suspendedBoard = null
                board.history = suspended.history
                enterSurface(board.whiteboard, suspended.scene)
                board.selection.setLasso(suspended.lasso)
                WhiteboardSceneGate.release()
                // A note reopened from the scene cache can close before JS has loaded the board.
                if (!SceneCache.jsSceneArrived) board.holdPenForJsScene()
                val card = synchronized(BoardEngine.lock) { BoardEngine.cards.values.firstOrNull { it.noteRef == doc.ref } }
                if (card != null) {
                    val size = FloatArray(2); noteCardBoardSize(doc.contentHeight, card.width, size, BoardContentView.noteHeaderOf(card))
                    val resized = card.withRect(RectF(card.x, card.y, card.x + size[0], card.y + size[1]))

                    val entry = noteEntryCard?.takeIf { it.id == card.id } ?: card
                    val change = BoardHistory.Change("note-resize").card(card, resized)
                    board.cardCommands.pushCardsBelow(change, board.cardCommands.cardsBelow(entry), resized.height - entry.height)
                    board.applyChange(change, record = false)
                    BoardEngine.mutate { invalidateAll() }
                }
                noteEntryCard = null
                board.scheduleChromeUpdate(); Log.i(BoardInteractionController.TAG, "note closed ref=${doc.ref} height=${doc.contentHeight}")
            }
        }
    }

    fun closeForInkling() {
        if (board.surfaceSwitching) return
        if (suspendedBoard == null) {
            board.surface.onCloseRequested(BoardSurface.CloseSource.TOOLBAR)
            return
        }
        exitFromNote("inkling-close")
    }

    // Saves the open note and closes the plugin without leaving the note, so the next launch reopens it.
    private fun exitFromNote(reason: String) {
        if (board.surfaceSwitching || suspendedBoard == null) return
        board.surfaceSwitching = true
        board.cancelActiveInteractions(reason)
        val liveStrokes = synchronized(BoardEngine.lock) { BoardEngine.strokes.values.toList() }
        val compacted = ScrollingDocument.compact(liveStrokes)
        if (compacted.isNotEmpty() || noteController.document?.strokes?.isEmpty() != false) {
            noteController.changed(compacted)
        }
        noteController.flush(true) { result ->
            board.surfaceSwitching = false
            result.onFailure { Log.e(BoardInteractionController.TAG, "note save failed on exit reason=$reason", it) }
            Log.i(BoardInteractionController.TAG, "exit from note reason=$reason")
            board.whiteboard.onCloseRequested(BoardSurface.CloseSource.TOOLBAR)
        }
    }

    internal fun setPageNoteHeader(markdown: String) {
        currentNoteHeaderPlaceholder = markdown.isBlank()
        currentNoteHeader = if (currentNoteHeaderPlaceholder) MosaicStrings.t(MosaicStrings.Key.noteHeaderPlaceholder) else markdown
        currentNoteHeaderHeight = BoardContentView.noteHeaderHeight(currentNoteHeader.orEmpty(), notePageScale)
    }

    internal fun pageHeaderRegionWorld(): RectF = RectF(0f, -currentNoteHeaderHeight, ScrollingDocument.WIDTH, 0f)

    private fun noteTopInsetWorld(): Float = board.toolbarHeightWorld() + currentNoteHeaderHeight * BoardEngine.scale

    internal fun noteHeaderHit(x: Float, y: Float): Boolean =
        !board.surface.showsBoardChrome && currentNoteHeaderHeight > 0f && board.worldY(y) < 0f && !noteOffPageHit(x, y)

    // Zoomed out, the white around the note page cannot be written on.
    internal fun noteOffPageHit(x: Float, y: Float): Boolean {
        if (board.surface.showsBoardChrome) return false
        val wx = board.worldX(x)
        val wy = board.worldY(y)
        return wx < 0f || wx > ScrollingDocument.WIDTH || wy < -currentNoteHeaderHeight || noteBelowPageEnd(wy)
    }

    private fun noteBelowPageEnd(wy: Float): Boolean =
        wy > notePageBottom && (board.surface as? NoteSurface)?.zoomedOut(BoardEngine.scale) == true

    // Ink inside a note stays on the writable page: below the header, between the sides, above the zoomed-out end.
    internal fun noteInkOffPage(wx: Float, wy: Float): Boolean =
        !board.surface.showsBoardChrome && (wx < 0f || wx > ScrollingDocument.WIDTH || wy < 0f || noteBelowPageEnd(wy))

    // Off-page parts of the view in view pixels, handed to the system ink service so it never draws there.
    fun noteOffPageRectsPx(): List<Rect> {
        if (board.surface.showsBoardChrome || board.surfaceSwitching) return emptyList()
        val w = board.host.width
        val h = board.host.height
        if (w <= 0 || h <= 0) return emptyList()
        val left = board.screenX(0f).toInt().coerceIn(0, w)
        val right = kotlin.math.ceil(board.screenX(ScrollingDocument.WIDTH)).toInt().coerceIn(0, w)
        val top = board.screenY(-currentNoteHeaderHeight).toInt().coerceIn(0, h)
        val zoomedOut = (board.surface as? NoteSurface)?.zoomedOut(BoardEngine.scale) == true
        val bottom = if (zoomedOut) kotlin.math.ceil(board.screenY(notePageBottom)).toInt().coerceIn(0, h) else h
        val out = ArrayList<Rect>(4)
        if (left > 0) out.add(Rect(0, 0, left, h))
        if (right < w) out.add(Rect(right, 0, w, h))
        if (right > left && top > 0) out.add(Rect(left, 0, right, top))
        if (right > left && bottom < h) out.add(Rect(left, bottom, right, h))
        return out
    }

    // Zoomed out, the note page ends where the ink ends; the content view frames it there and the pen stops at it.
    internal fun syncNotePageFrame() {
        val note = board.surface as? NoteSurface
        notePageBottom = note?.zoomedOutPageBottom() ?: Float.POSITIVE_INFINITY
        board.content.setNotePageFrame(if (note != null) notePageScale else 0f, notePageBottom)
    }

    internal fun setNoteHeaderHoverBlocked(blocked: Boolean) {
        if (noteHeaderHoverBlocked == blocked) return
        noteHeaderHoverBlocked = blocked
        board.host.drawPath.setRegionBlocked(blocked, "note-header")
    }

    fun convertCardToNote() {
        if (suspendedBoard != null || board.surfaceSwitching || notesDirectory.isBlank()) return
        var source = board.selection.selectedCardId?.let { BoardEngine.cards[it] } ?: return
        if (source.kind == "note" || source.kind == "image") return
        board.cardCommands.normalizeColoredCardToDefault(source)?.let { pre ->
            board.applyChange(pre, record = true)
            source = BoardEngine.cards[source.id] ?: return
        }
        // The note is a 480-wide column; ink from a wider card is scaled to fit, otherwise its right side is cut off.
        val fit = (ScrollingDocument.WIDTH / source.width).coerceAtMost(1f)
        val moved = synchronized(BoardEngine.lock) {
            BoardEngine.cardStrokes[source.id]?.map {
                if (fit < 1f) it.scaled(fit, fit, 0f, 0f, null, null) else it.translated(0f, 0f, null, null)
            } ?: emptyList()
        }
        val ref = "note-" + UUID.randomUUID().toString().replace("-", "").take(12)
        noteController.open(notesDirectory, ref, create = true) { result ->
            result.onFailure { Log.e(BoardInteractionController.TAG, "convert-to-note create failed", it) }
            result.onSuccess {
                noteController.changed(moved)
                noteController.flush(preview = true) { flushed ->
                    flushed.onFailure { Log.e(BoardInteractionController.TAG, "convert-to-note flush failed", it) }
                    val contentHeight = flushed.getOrNull()?.contentHeight ?: ScrollingDocument.WIDTH
                    noteController.clear()
                    val size = FloatArray(2)

                    val header = BoardContentView.noteHeaderFromText(source.content)
                    val headerLines = if (header.isEmpty()) emptyList() else header.split('\n')
                    noteCardBoardSize(contentHeight, source.width, size, BoardContentView.noteHeaderMarkdown(headerLines))

                    val note = BoardEngine.CardRec(source.id, source.x, source.y, size[0], size[1], source.zIndex, "note", header, noteController.previewPath(ref), ref, "", "", headerLines.firstOrNull().orEmpty())
                    val change = BoardHistory.Change("card-to-note")
                    synchronized(BoardEngine.lock) { BoardEngine.cardStrokes[source.id]?.forEach { change.stroke(it, null) } }
                    change.card(source, note)
                    board.cardCommands.pushCardsBelow(change, board.cardCommands.cardsBelow(source), note.height - source.height)
                    board.applyChange(change, record = true)
                    board.selection.selectSingleCard(note); board.scheduleChromeUpdate()
                }
            }
        }
    }

    // When the toolbar hides or shows in a note, a page resting at its top follows the toolbar edge,
    // so hiding it leaves no blank band and showing it does not cover the header.
    internal fun keepNoteTopOnToolbarChange() {
        val now = board.toolbarHeightWorld()
        val before = noteToolbarInset
        noteToolbarInset = now
        if (board.surface.showsBoardChrome || now == before) return
        val scale = BoardEngine.scale
        val restingTop = before + currentNoteHeaderHeight * scale
        val panY = if (BoardEngine.panY >= restingTop - 0.5f) BoardEngine.panY + (now - before) else BoardEngine.panY
        board.surface.constrainPan(BoardEngine.panX, panY, scale, board.viewportTmp)
        board.commitViewport(board.snapPanToPx(board.viewportTmp[0]), board.snapPanToPx(board.viewportTmp[1]), scale)
        Log.i(BoardInteractionController.TAG, "note toolbar inset $before->$now panY ${BoardEngine.panY}")
    }
}
