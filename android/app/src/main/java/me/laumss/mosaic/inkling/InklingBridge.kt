package me.laumss.mosaic.inkling

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
import android.graphics.RectF
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** Notipal (Inkling in code): its floating-window drag, the shared clipboard and palette, and text and notes it sends over. */
internal class InklingBridge(private val board: BoardInteractionController) {
    companion object {
        private val IMPORT_CARD_WIDTHS = floatArrayOf(BoardGeometry.DEFAULT_CARD_WIDTH, 480f, 640f, 800f, 960f)
        private const val IMPORT_CARD_MAX_ASPECT = 1.6f
        private const val INKLING_DRAG_EINK_OWNER = "inkling-drag"
        // 拖动结束的广播丢失时（Notipal 被杀等）也要退出拖动显示。
        private const val INKLING_DRAG_MAX_MS = 15_000L
    }

    private val clipboardIo = ClipboardStrokeIo()

    val pasteGeneration = AtomicLong(0L)

    private val exportGeneration = AtomicLong(0L)

    private var pasteTask: Future<*>? = null

    private var dragActive = false

    private var dragOnBoard = false

    private var dragNoteModeHeld = false

    private val dragTimeoutTask = Runnable { setOverlayDrag(false, "timeout") }

    private val dragNoteResetTask = Runnable {
        dragNoteModeHeld = false
        MosaicEinkRefreshModule.resetNative(INKLING_DRAG_EINK_OWNER)
    }

    // Notipal 浮窗拖动：白板上和双指平移一样显示（A2、运动配方、扁平卡片），笔记卡片里只切 A2 刷新；松手后按各自的 settle 恢复。
    fun setOverlayDrag(active: Boolean, reason: String) {
        board.handler.removeCallbacks(dragTimeoutTask)
        if (active) board.handler.postDelayed(dragTimeoutTask, INKLING_DRAG_MAX_MS)
        if (active == dragActive) return
        dragActive = active
        if (active) {
            dragOnBoard = board.surface.showsBoardChrome
            if (dragOnBoard) {
                board.presentation.acquire(BoardPresentation.Reason.INKLING_DRAG)
            } else {
                board.handler.removeCallbacks(dragNoteResetTask)
                dragNoteModeHeld = true
                MosaicEinkRefreshModule.applyNative(MosaicEinkRefreshModule.MODE_A2, INKLING_DRAG_EINK_OWNER)
            }
        } else if (dragOnBoard) {
            board.presentation.release(BoardPresentation.Reason.INKLING_DRAG)
        } else {
            board.handler.removeCallbacks(dragNoteResetTask)
            board.handler.postDelayed(dragNoteResetTask, BoardInteractionController.NOTE_SCROLL_SETTLE_MS)
        }
        Log.i(LogTags.INTERACTION, "inkling overlay drag active=$active board=$dragOnBoard reason=$reason")
    }

    fun resetOverlayDrag() {
        board.handler.removeCallbacks(dragTimeoutTask)
        board.handler.removeCallbacks(dragNoteResetTask)
        if (dragNoteModeHeld) {
            dragNoteModeHeld = false
            MosaicEinkRefreshModule.resetNative(INKLING_DRAG_EINK_OWNER)
        }
        dragActive = false
        dragOnBoard = false
    }

    fun toolbarHit(viewX: Float, viewY: Float): Boolean =
        InklingLink.consumesScreenPoint(viewX + board.viewOffsetX, viewY + board.viewOffsetY)

    fun clearSelection(delete: Boolean) {
        if (!InklingLink.isInkSurface()) {
            Log.i(LogTags.LASSO, "inkling clear-selection deferred surface=${InklingLink.currentSurface()} delete=$delete")
            return
        }
        val sel = board.selection.lasso
        if (sel == null) {
            exportLassoStrokes(null)
            Log.i(LogTags.LASSO, "inkling clear-selection: no selection, file removed")
            return
        }
        if (delete) {
            board.selection.deleteLassoSelection()
        } else {
            board.selection.setLasso(null)
        }
        Log.i(LogTags.LASSO, "inkling clear-selection delete=$delete strokes=${sel.strokeIds.size} cards=${sel.cardIds.size}")
    }

    fun insertTextCard(text: String, anchorScreenX: Int, anchorScreenY: Int) {
        if (!InklingLink.isBoardSurface()) {
            Log.i(LogTags.INTERACTION, "inkling text-card deferred surface=${InklingLink.currentSurface()}")
            return
        }
        board.refreshViewOffset()
        val wx = board.worldX((anchorScreenX - board.viewOffsetX).toFloat())
        val wy = board.worldY((anchorScreenY - board.viewOffsetY).toFloat())
        emitImportedTextCard(TextReflow.reflow(text), wx, wy, "inkling", centered = false)
    }

    fun insertDocTextCard(text: String) {
        if (!board.surface.showsBoardChrome) {
            Log.i(LogTags.INTERACTION, "doc text-card ignored surface=${board.surface.name}")
            return
        }
        val view = board.viewWorld()
        emitImportedTextCard(TextReflow.reflow(text), view.centerX(), view.centerY(), "doc", centered = true)
    }

    fun importNoteCard(path: String, title: String, done: (Result<Boolean>) -> Unit) {
        if (!board.surface.showsBoardChrome || board.notes.suspendedBoard != null || board.surfaceSwitching || board.notes.notesDirectory.isBlank()) {
            Log.i(LogTags.INTERACTION, "note import deferred surface=${board.surface.name}")
            done(Result.success(false))
            return
        }
        val directory = board.notes.notesDirectory
        val ref = "note-" + UUID.randomUUID().toString().replace("-", "").take(12)
        clipboardIo.submitNoteImport(File(path)) { parsed ->
            board.handler.post {
                val imported = parsed.getOrElse {
                    Log.e(LogTags.INTERACTION, "note import parse failed path=$path", it)
                    done(Result.failure(it))
                    return@post
                }
                board.notes.noteController.create(directory, ScrollingDocument(ref, imported.strokes, 0f), imported.preparedPreview) { written ->
                    val doc = written.getOrElse {
                        Log.e(LogTags.INTERACTION, "note import write failed ref=$ref", it)
                        done(Result.failure(it))
                        return@create
                    }
                    if (!board.surface.showsBoardChrome || board.notes.suspendedBoard != null || board.surfaceSwitching) {
                        File(directory, "$ref.mnote").delete(); File(directory, ref).deleteRecursively()
                        Log.i(LogTags.INTERACTION, "note import deferred after write surface=${board.surface.name}")
                        done(Result.success(false))
                        return@create
                    }
                    val header = BoardContentView.noteHeaderFromText(title)
                    val headerLines = if (header.isEmpty()) emptyList() else header.split('\n')
                    val size = FloatArray(2)
                    NoteSession.noteCardBoardSize(doc.contentHeight, NoteSession.NOTE_CARD_BOARD_WIDTH, size, BoardContentView.noteHeaderMarkdown(headerLines))
                    val view = board.viewWorld()
                    val top = if (size[1] < view.height()) view.centerY() - size[1] / 2f
                        else view.top + board.toolbarHeightWorld() / BoardViewport.scale + SelectionController.LASSO_BOUNDS_PAD * 2f
                    val card = BoardEngine.CardRec(
                        board.newId("card-"), view.centerX() - size[0] / 2f, top, size[0], size[1],
                        BoardGeometry.nextZIndex(BoardEngine.cards.values), "note", header,
                        File(directory, ref).absolutePath, ref, "", "", headerLines.firstOrNull().orEmpty(),
                    )
                    board.applyChange(BoardHistory.Change("import-note").card(null, card), record = true)
                    board.selection.selectSingleCard(card); board.scheduleChromeUpdate()
                    Log.i(LogTags.INTERACTION, "imported note card=${card.id} ref=$ref pages=${imported.pageCount} strokes=${doc.strokes.size} size=${size[0]}x${size[1]}")
                    done(Result.success(true))
                }
            }
        }
    }

    private fun emitImportedTextCard(text: String, x: Float, y: Float, source: String, centered: Boolean) {
        if (text.isBlank()) return
        var size = BoardContentView.measureTextCardSize(text, IMPORT_CARD_WIDTHS[0])
        for (w in IMPORT_CARD_WIDTHS) {
            size = BoardContentView.measureTextCardSize(text, w)
            if (size.y <= w * IMPORT_CARD_MAX_ASPECT) break
        }
        val left = if (centered) x - size.x / 2f else x
        val top = if (centered) y - size.y / 2f else y
        board.emitter.action("insertTextCard") {
            putString("text", text)
            putDouble("x", left.toDouble())
            putDouble("y", top.toDouble())
            putDouble("width", size.x.toDouble())
            putDouble("height", size.y.toDouble())
            putString("source", source)
        }
        Log.i(LogTags.INTERACTION, "imported text card source=$source at world=($left,$top) size=${size.x}x${size.y} len=${text.length}")
    }

    fun invalidateClipboardWork(reason: String) {
        val generation = pasteGeneration.incrementAndGet()
        pasteTask?.cancel(true)
        pasteTask = null
        // Drop the old surface's selection file too; only bumping the generation would also cancel a pending
        // delete and leave a stale clip_lasso.json that makes Notipal save instead of paste.
        exportLassoStrokes(null)
        Log.i(LogTags.LASSO, "clipboard work invalidated reason=$reason generation=$generation")
    }

    private fun paletteStrokes(): List<BoardEngine.StrokeRec> = synchronized(BoardEngine.lock) {
        val selected = board.selection.lasso ?: return@synchronized emptyList()
        val records = LinkedHashMap<String, BoardEngine.StrokeRec>()
        selected.strokeIds.forEach { id -> BoardEngine.strokes[id]?.let { records[id] = it } }
        selected.cardIds.forEach { id -> BoardEngine.cardStrokes[id]?.forEach { records[it.id] = it } }
        records.values.toList()
    }

    fun paletteSelectionInfo(): org.json.JSONObject {
        val strokes = paletteStrokes()
        val penId = strokes.groupingBy { PaletteStrokeStyle.id(it) }.eachCount()
            .maxByOrNull { it.value }?.key ?: "ball"
        val spec = PaletteStrokeStyle.spec(penId)!!
        val widths = strokes.filter { PaletteStrokeStyle.id(it) == penId }.map { PaletteStrokeStyle.thickness(it) }
        return org.json.JSONObject().apply {
            put("success", true)
            put("mosaic", true)
            put("selectionVersion", exportGeneration.get())
            put("strokeIds", org.json.JSONArray(strokes.map { it.id }))
            put("strokeCount", strokes.count { !it.isShape })
            put("geometryCount", strokes.count { it.isShape })
            put("avgThickness", if (widths.isEmpty()) DrawPathClient.noteCalligraphyThickness(400) else widths.average().toInt())
            put("dominantPenId", penId)
            put("dominantPenType", spec.notePenType)
            put("hasMarkerStroke", strokes.any { it.penStyle == PenStyle.MARKER.objType })
        }
    }

    fun applyPaletteSelection(data: org.json.JSONObject): org.json.JSONObject {
        fun failure(reason: String) = org.json.JSONObject().put("success", false).put("error", reason)
        val ids = data.optJSONArray("strokeIds") ?: return failure("select_ink")
        val requested = (0 until ids.length()).map { ids.optString(it) }.toSet()
        val strokes = paletteStrokes()
        if (requested.isEmpty() || strokes.isEmpty()) return failure("select_ink")
        if (data.optLong("selectionVersion", -1L) != exportGeneration.get() ||
            requested != strokes.map { it.id }.toSet()) return failure("selection_changed")
        val spec = PaletteStrokeStyle.spec(data.optString("penId")) ?: return failure("invalid_pen")
        val thickness = data.optInt("thickness", 0)
        if (thickness !in 1..4000) return failure("invalid_width")
        val change = BoardHistory.Change("palette")
        strokes.forEach { before ->
            val after = PaletteStrokeStyle.restyle(before, spec, thickness, data.optBoolean("changePen", true))
            if (!before.sameGeometry(after)) change.stroke(before, after)
        }
        val changed = change.diffs.size
        board.applyChange(change, record = true)
        board.selection.setLasso(board.selection.lasso)
        board.content.markSettleRequested()
        board.content.postInvalidateOnAnimation()
        board.host.invalidate()
        Log.i(LogTags.LASSO, "Mosaic palette applied pen=${data.optString("penId")} strokes=$changed")
        return org.json.JSONObject().put("success", true).put("changed", changed)
    }

    fun exportLassoStrokes(sel: LassoSelection?) {
        val sequence = exportGeneration.incrementAndGet()
        val file = File(InklingLink.CLIP_LASSO_FILE)
        if (sel == null) {
            clipboardIo.submitDelete(file) { exportGeneration.get() == sequence }
            return
        }
        val out = LinkedHashMap<String, ClipboardStrokeIo.ExportStroke>()

        synchronized(BoardEngine.lock) {
            for (id in sel.strokeIds) {
                val rec = BoardEngine.strokes[id] ?: continue
                val card = rec.cardId?.let { BoardEngine.cards[it] }
                out[rec.id] = ClipboardStrokeIo.ExportStroke(
                    rec.penStyle,
                    rec.width,
                    rec.points.copyOf(),
                    rec.pressures.copyOf(),
                    card?.x ?: 0f,
                    card?.y ?: 0f,
                    rec.drawPathWidth,
                    rec.color,
                    rec.sampleScale,
                    rec.nibAngle,
                    rec.angles.copyOf(),
                    rec.contourOffsets.copyOf(),
                )
            }
            for (cardId in sel.cardIds) {
                val card = BoardEngine.cards[cardId] ?: continue
                BoardEngine.cardStrokes[cardId]?.forEach { rec ->
                    out[rec.id] = ClipboardStrokeIo.ExportStroke(
                        rec.penStyle,
                        rec.width,
                        rec.points.copyOf(),
                        rec.pressures.copyOf(),
                        card.x,
                        card.y,
                        rec.drawPathWidth,
                        rec.color,
                        rec.sampleScale,
                        rec.nibAngle,
                        rec.angles.copyOf(),
                    rec.contourOffsets.copyOf(),
                    )
                }
            }
        }
        // A note page is shown at notePageScale; export at that size so the clip matches what is on screen.
        clipboardIo.submitExport(
            target = file,
            strokes = out.values.toList(),
            density = board.density * if (board.surface.showsBoardChrome) 1f else board.notes.notePageScale,
            screenShortPx = board.host.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels).toFloat() },
            isCurrent = { exportGeneration.get() == sequence },
        ) { result ->
            result.onSuccess { count ->
                if (count > 0) Log.i(LogTags.LASSO, "exported $count strokes -> ${file.path}")
            }.onFailure { Log.w(LogTags.LASSO, "exportLassoStrokes failed", it) }
        }
    }

    fun handlePasteStrokes() {
        if (!InklingLink.isInkSurface()) {
            Log.i(LogTags.LASSO, "paste deferred surface=${InklingLink.currentSurface()}")
            return
        }
        val file = File(InklingLink.PASTE_STROKES_FILE)
        val generation = pasteGeneration.incrementAndGet()
        pasteTask?.cancel(true)
        val viewCx = (board.host.width / board.density / 2f - BoardViewport.panX) / BoardViewport.scale
        val viewCy = (board.host.height / board.density / 2f - BoardViewport.panY) / BoardViewport.scale
        val d = board.density

        // Clipboard dimensions use the fixed board scale; note pages have their intrinsic view scale.
        val worldScale = 1f / (d * if (board.surface.showsBoardChrome) 1f else board.notes.notePageScale)

        val metrics = board.host.resources.displayMetrics
        val transform = ClipboardStrokeIo.PasteTransform(
            viewCx, viewCy, 0f, 0f, worldScale, 1f / worldScale,
            minOf(metrics.widthPixels, metrics.heightPixels).toFloat(),
        )
        pasteTask = clipboardIo.submitPaste(file, transform) { result ->
            board.handler.post {
                if (generation != pasteGeneration.get() || board.surfaceSwitching || !InklingLink.isInkSurface()) return@post
                val batch = result.getOrNull()
                if (batch == null || batch.strokes.isEmpty()) {
                    result.exceptionOrNull()?.let { Log.w(LogTags.LASSO, "paste parse failed", it) }
                    return@post
                }
                val change = BoardHistory.Change("paste")
                val pastedIds = ArrayList<String>(batch.strokes.size)
                val recs = batch.strokes.map { stroke ->
                    BoardEngine.StrokeRec(
                        UUID.randomUUID().toString(), "canvas", stroke.width, stroke.color,
                        stroke.points, stroke.pressures, stroke.penStyle, stroke.sampleScale, stroke.drawPathWidth,
                        stroke.nibAngle,
                        stroke.angles,
                        stroke.contourOffsets,
                    )
                }

                val darkGrayMarkers = recs.filter {
                    it.penStyle == PenStyle.MARKER.objType && MarkerInk.fromArgb(it.color) == MarkerInk.DARK_GRAY
                }
                var pinned = 0
                for ((index, rec) in recs.withIndex()) {
                    val pin = batch.strokes[index].noteWhite && darkGrayMarkers.isNotEmpty() &&
                        InkBackdrop.isOverMarkers(rec, darkGrayMarkers)
                    if (pin) pinned++
                    change.stroke(null, if (pin) rec.withColor(BoardEngine.StrokeRec.INK_WHITE_PINNED) else rec)
                    pastedIds.add(rec.id)
                }
                if (pastedIds.isEmpty()) return@post
                board.applyChange(change, record = true)

                selectPastedStrokes(pastedIds)
                board.content.postInvalidateOnAnimation()
                board.host.invalidate()
                Log.i(
                    LogTags.LASSO,
                    "pasted ${pastedIds.size} strokes at viewport center world=(${"%.0f".format(viewCx)},${"%.0f".format(viewCy)}) " +
                        "size=${"%.0f".format((batch.maxX - batch.minX) * worldScale * batch.pageScale)}x${"%.0f".format((batch.maxY - batch.minY) * worldScale * batch.pageScale)} " +
                        "pan=(${BoardViewport.panX},${BoardViewport.panY}) scale=${BoardViewport.scale} pageScale=${batch.pageScale} pinnedWhite=$pinned",
                )
            }
        }
    }

    private fun selectPastedStrokes(ids: List<String>) {
        if (ids.isEmpty()) return
        val frame = RectF()
        var any = false
        synchronized(BoardEngine.lock) {
            for (id in ids) {
                val stroke = BoardEngine.strokes[id] ?: continue
                val b = BoardEngine.strokeInkBounds(stroke)
                if (!any) { frame.set(b); any = true } else frame.union(b)
            }
        }
        if (!any) return
        val pad = board.selection.lassoPadWorld()
        frame.inset(-pad, -pad)
        board.selection.setLasso(LassoSelection(frame, emptyList(), ids.toList()))
    }
}
