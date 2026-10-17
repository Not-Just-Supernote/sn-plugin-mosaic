package me.laumss.mosaic

import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import kotlin.math.hypot

/** Pen input on the board: writing, erasing, lasso and shape strokes, and handing finished strokes to the gestures that edit cards. */
internal class PenInput(private val board: BoardInteractionController) {
    companion object {
        const val INK_BLACK = 0xff000000.toInt()
        const val ERASER_HIT_RADIUS = 12f
        private const val SHAPE_MIN_DRAG_DP = 8f
        // A card stroke that starts this soon after the previous stroke on the same card is handwriting, not a card gesture.
        const val CARD_CONTINUOUS_WRITE_MS = 1500L
    }

    var penContact = false
        private set

    var penHovering = false

    var penHoverX = 0f
        private set

    var penHoverY = 0f
        private set

    private var trailWhite = false

    private var penLastX = 0f

    private var penLastY = 0f

    private var lastCardWriteId: String? = null

    private var lastCardWriteAt = 0L

    var penSession: PenSession? = null
        private set

    private var penMoveSession: MoveSession? = null

    private var trailCause = "none"

    private val hitBuffer = ArrayList<BoardEngine.StrokeRec>()

    private fun penX(e: MotionEvent): Float = e.x + InkAlign.offsetX

    private fun penY(e: MotionEvent): Float = e.y + InkAlign.offsetY

    private fun penHistX(e: MotionEvent, i: Int): Float = e.getHistoricalX(i) + InkAlign.offsetX

    private fun penHistY(e: MotionEvent, i: Int): Float = e.getHistoricalY(i) + InkAlign.offsetY

    /** Ends the stroke in progress because the tool changed; returns it if it was writing. */
    fun endSessionForToolChange(): PenSession.Write? {
        var discardedWrite: PenSession.Write? = null
        when (val previous = penSession) {
            is PenSession.Write -> { board.cardGestures.cancelFrameHold(); board.overlay.hideFramePreview(); penSession = null; discardedWrite = previous }
            is PenSession.Erase -> { finishErase(previous); penSession = null }
            is PenSession.Lasso -> { penSession = null; board.ink.abortStroke() }
            is PenSession.Shape -> { penSession = null; board.overlay.hideShapePreview() }
            else -> {}
        }
        return discardedWrite
    }

    /** After a tool change, a pen still touching the screen goes on with the new tool. */
    fun resumeAfterToolChange(previous: PenSession?, discardedWrite: PenSession.Write?, state: ToolArbiter.State, source: String) {
        discardedWrite?.let { discardWriteTrail(it, "tool-change:$source") }
        if (penContact && previous != null && previous !== PenSession.Consumed) {
            beginContactSession(state, discardedWrite, source)
        }
    }

    /** Abandons the pen's stroke or drag without committing anything. */
    fun cancel(reason: String) {
        penMoveSession?.let { board.transforms.cancelMoveSession(it) }
        penMoveSession = null
        board.cardGestures.cancelFrameHold()
        board.overlay.hideFramePreview()
        val s = penSession
        penSession = null
        when (s) {
            is PenSession.Write -> discardWriteTrail(s, reason)
            is PenSession.Lasso -> board.ink.abortStroke()
            is PenSession.Shape -> board.overlay.hideShapePreview()
            else -> {}
        }
    }

    fun reset() {
        penSession = null
        penMoveSession = null
    }

    private fun beginContactSession(state: ToolArbiter.State, prevWrite: PenSession.Write?, source: String) {
        val wx = board.worldX(penLastX)
        val wy = board.worldY(penLastY)
        when {
            state.eraser -> {
                val session = PenSession.Erase(BoardHistory.Change("erase"))
                penSession = session
                var replayed = 0
                val from = prevWrite?.convertFromIndex ?: -1
                if (prevWrite != null && from >= 0) {
                    var i = from
                    while (i + 1 < prevWrite.points.size) {
                        eraseAt(session, prevWrite.points[i] + prevWrite.cardX, prevWrite.points[i + 1] + prevWrite.cardY)
                        i += 2
                        replayed++
                    }
                }
                eraseAt(session, wx, wy)
                flushErase(session)
            }
            state.lasso -> {
                val session = PenSession.Lasso()
                session.pointsPx.add(penLastX); session.pointsPx.add(penLastY)
                penSession = session
                Log.i(BoardInteractionController.TAG_PEN, "contact converted to lasso source=$source")
            }
            state.shape -> {
                penSession = PenSession.Shape(board.shapeKind, penLastX, penLastY)
                Log.i(BoardInteractionController.TAG_PEN, "contact converted to shape source=$source")
            }
            else -> {
                if (board.selection.lasso != null) { penSession = PenSession.Consumed; return }
                val card = BoardGeometry.topCardAt(BoardEngine.cardsByZ, wx, wy)
                val connection = if (card == null) BoardEngine.connectionAt(wx, wy) else null
                val session = PenSession.Write(
                    id = board.newId("stroke-"),
                    space = when {
                        card != null -> "card:${card.id}"
                        connection != null -> "connection:${connection.id}"
                        else -> "canvas"
                    },
                    cardId = card?.id,
                    cardX = card?.x ?: 0f,
                    cardY = card?.y ?: 0f,
                )
                val pressure = InputReader.currentPenPressure()
                if (pressure != null) appendWritePoint(session, wx, wy, pressure)
                else Log.i(BoardInteractionController.TAG_PEN, "contact write waiting for drawPath pressure source")
                session.frameLastMeaningfulX = wx
                session.frameLastMeaningfulY = wy
                penSession = session
                Log.i(BoardInteractionController.TAG_PEN, "contact converted to write source=$source")
            }
        }
    }

    fun discardWriteTrail(s: PenSession.Write, reason: String) {
        if (s.points.size < 4) return
        board.host.inkHandoff.onPenTrailDiscarded(reason)
    }

    fun onPen(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) board.trailCleanup.cancel("pen-down")
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> penDown(event)
            MotionEvent.ACTION_MOVE -> penMove(event)
            MotionEvent.ACTION_UP -> penUp(event, cancelled = false)
            MotionEvent.ACTION_CANCEL -> penUp(event, cancelled = true)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) board.trailCleanup.resume("pen-up")
    }

    fun onPenHover(event: MotionEvent) {
        val exit = event.actionMasked == MotionEvent.ACTION_HOVER_EXIT
        penHoverX = penX(event)
        penHoverY = penY(event)
        if (exit) {
            if (!penContact)
            return
        }
        if (penContact) return
        board.notes.setNoteHeaderHoverBlocked(board.notes.noteHeaderHit(penHoverX, penHoverY) || board.notes.noteOffPageHit(penHoverX, penHoverY))
        if (board.eraserMode) {
        }

        reevaluateTrailColor("hover")
    }

    fun reevaluateTrailColor(reason: String) {
        val want = trailWantsWhite(board.worldX(penHoverX), board.worldY(penHoverY))
        if (want == trailWhite) return
        trailWhite = want
        board.host.drawPath.setTrailWhite(want, "$reason:$trailCause")
    }

    private fun trailWantsWhite(wx: Float, wy: Float): Boolean {
        trailCause = "none"
        if (board.penStyle == PenStyle.MARKER) return false
        val top = BoardGeometry.topCardAt(BoardEngine.cardsByZ, wx, wy)
        val space = when {
            top != null -> "card:${top.id}"
            else -> BoardEngine.connectionAt(wx, wy)?.let { "connection:${it.id}" } ?: "canvas"
        }
        val layerDark = InkBackdrop.layerDarkAt(space, wx - (top?.x ?: 0f), wy - (top?.y ?: 0f), board.penWidth * 0.5f)
        if (layerDark) { trailCause = "layer"; return true }
        trailCause = "background"
        return InkBackdrop.darkAt(wx, wy)
    }

    private fun penDown(e: MotionEvent) {
        val x = penX(e)
        val y = penY(e)
        penLastX = x
        penLastY = y

        when (board.touch.fingerGesture) {
            FingerGesture.ScreenTool -> {}
            is FingerGesture.TwoCandidate -> {
                if (!board.touch.settleTwoCandidate("pen-down")) board.touch.cancelFingerGestures("pen-down")
            }
            else -> board.touch.cancelFingerGestures("pen-down")
        }
        val waitingForJsScene = board.penHeldForJsScene && board.surface.showsBoardChrome
        if (board.surfaceSwitching || waitingForJsScene || board.chrome.consumesPoint(x, y) || board.inkling.toolbarHit(x, y) || board.notes.noteHeaderHit(x, y) || board.notes.noteOffPageHit(x, y)) {
            Log.i(
                BoardInteractionController.TAG_PEN,
                "DOWN rejected: ${when {
                    board.surfaceSwitching -> "surface-switching"
                    waitingForJsScene -> "scene-cache"
                    board.inkling.toolbarHit(x, y) -> "inkling-toolbar"
                    board.notes.noteHeaderHit(x, y) -> "note-header"
                    board.notes.noteOffPageHit(x, y) -> "note-off-page"
                    else -> "chrome"
                }}",
            )
            penSession = PenSession.Consumed
            return
        }
        penContact = true
        board.applyTransition(board.arbiter.penDown(), "pen-down")
        val wx = board.worldX(x)
        val wy = board.worldY(y)
        penHoverX = x; penHoverY = y
        reevaluateTrailColor("down")
        Log.i(
            BoardInteractionController.TAG_PEN,
            "DOWN px=($x,$y) raw=(${e.x},${e.y}) inkAlign=(${InkAlign.offsetX},${InkAlign.offsetY}) " +
                "world=(${wx.toInt()},${wy.toInt()}) eraser=${board.eraserMode} lasso=${board.lassoMode} " +
                "${board.arbiter.describe()} | ${InputReader.describePenRaw()} density=${board.density} " +
                "pan=(${BoardEngine.panX},${BoardEngine.panY}) scale=${BoardEngine.scale}",
        )

        val downCard = BoardGeometry.topCardAt(BoardEngine.cardsByZ, wx, wy)
        val downLayer = if (downCard != null) InkBackdrop.layerAt("card:${downCard.id}", wx - downCard.x, wy - downCard.y, board.penWidth * 0.5f)
            else InkBackdrop.layerAt("canvas", wx, wy, board.penWidth * 0.5f)
        Log.i(BoardInteractionController.TAG_PEN, "[MosaicTrail] down trailWhite=$trailWhite cause=$trailCause layer=${downLayer ?: "none"} card=${downCard?.id ?: "canvas"} dark=${downCard?.colored == true}")

        if (board.selection.selectBelowArmed) {
            board.selection.handleSelectBelow(wy)
            penSession = PenSession.Consumed
            return
        }

        val currentLasso = board.selection.lasso

        val hadSelection = currentLasso != null
        if (currentLasso != null && BoardEngine.connectionAt(wx, wy) == null) {
            val single = if (board.selection.isSingleCardSelection(currentLasso)) BoardEngine.cards[currentLasso.cardIds[0]] else null
            if (single != null) {
                val handle = board.selection.singleCardFrameHandle(currentLasso, wx, wy)
                if (handle != null) {
                    penMoveSession = board.transforms.beginResize(true, e.getPointerId(0), wx, wy, single, handle)
                    penSession = PenSession.Consumed
                    return
                }
            }

            if (board.overlay.rotateHandleHit(x, y, BoardInteractionController.LASSO_ACTION_TAP_SLOP_DP * board.density) && board.selection.selectionRotatable(currentLasso)) {
                penMoveSession = board.transforms.beginSelectionRotate(true, e.getPointerId(0), wx, wy, currentLasso)
                penSession = PenSession.Consumed
                return
            }

            val cornerHandle = board.selection.selectionCornerHandle(currentLasso, wx, wy)
                ?: (if (board.selection.shapeKindOf(currentLasso) != null) board.selection.shapeEdgeHandle(currentLasso, wx, wy) else null)
            if (cornerHandle != null) {
                penMoveSession = board.transforms.beginSelectionResize(true, e.getPointerId(0), wx, wy, currentLasso, cornerHandle)
                penSession = PenSession.Consumed
                return
            }
            val action = board.overlay.lassoActionHit(x, y, BoardInteractionController.LASSO_ACTION_TAP_SLOP_DP * board.density)
            if (action != null) {
                board.selection.handleLassoAction(action)
                penSession = PenSession.Consumed
                return
            }

            if (board.selection.selectionHit(currentLasso, wx, wy) || board.overlay.moveHandleHit(x, y, BoardInteractionController.LASSO_ACTION_TAP_SLOP_DP * board.density)) {
                penMoveSession = board.transforms.beginSelectionMove(true, e.getPointerId(0), wx, wy, currentLasso)
                penSession = PenSession.Consumed
                return
            }
            board.selection.setLasso(null)
            if (board.lassoMode) {
                val session = PenSession.Lasso()
                session.pointsPx.add(x); session.pointsPx.add(y)
                penSession = session
                Log.i(BoardInteractionController.TAG_LASSO, "pen DOWN outside selection: lasso tool redraws")
                return
            }
            if (board.shapeMode) {
                penSession = PenSession.Shape(board.shapeKind, x, y)
                Log.i(BoardInteractionController.TAG_LASSO, "pen DOWN outside selection: shape tool draws")
                return
            }
            penSession = PenSession.Consumed
            Log.i(BoardInteractionController.TAG_LASSO, "pen DOWN outside selection: cleared")
            return
        }

        if (board.eraserMode) {
            val session = PenSession.Erase(BoardHistory.Change("erase"))
            penSession = session
            eraseAt(session, wx, wy)
            flushErase(session)
            return
        }

        if (board.lassoMode) {
            val session = PenSession.Lasso()
            session.pointsPx.add(x); session.pointsPx.add(y)
            penSession = session
            return
        }

        if (board.shapeMode) {
            penSession = PenSession.Shape(board.shapeKind, x, y)
            return
        }

        val linkId = board.noteShots.linkAt(wx, wy)
        if (linkId != null) {
            board.host.drawPath.setSuspended(true, "note-link-tap")
            penSession = PenSession.OpenNote("", wx, wy, linkId)
            Log.i(BoardInteractionController.TAG_PEN, "DOWN on note link badge: open on tap id=$linkId")
            return
        }
        val card = BoardGeometry.topCardAt(BoardEngine.cardsByZ, wx, wy)
        val connection = if (card == null) BoardEngine.connectionAt(wx, wy) else null
        // Note previews accept card-local annotations through the normal ink path.
        // Opening the note is handled by the single-finger tap path.
        val session = PenSession.Write(
            id = board.newId("stroke-"),
            space = when {
                card != null -> "card:${card.id}"
                connection != null -> "connection:${connection.id}"
                else -> "canvas"
            },
            cardId = card?.id,
            cardX = card?.x ?: 0f,
            cardY = card?.y ?: 0f,
        )
        val downPressure = InputReader.pressureForMotionEvent(e)
        // Every stroke keeps its pen-down nib so a later palette restyle to the calligraphy pen
        // renders with the same directional nib; only the PEN raster reads it.
        session.nibAngle = InputReader.calligraphyNibAngleForMotionEvent(e)
        if (downPressure != null) appendWritePoint(session, wx, wy, downPressure)
        else Log.i(BoardInteractionController.TAG_PEN, "DOWN write waiting for drawPath pressure source")
        session.raw(e.x, e.y)
        session.frameLastMeaningfulX = wx
        session.frameLastMeaningfulY = wy
        session.continuousWrite = card != null && card.id == lastCardWriteId &&
            SystemClock.uptimeMillis() - lastCardWriteAt < CARD_CONTINUOUS_WRITE_MS

        if (board.sliderTwoDown) session.convertFromIndex = 0
        penSession = session
    }

    private fun appendWritePoint(
        s: PenSession.Write,
        wx: Float,
        wy: Float,
        pressure: Float?,
    ): Boolean {
        if (pressure == null || !pressure.isFinite() || pressure !in 0f..1f) return false
        if (s.clipped) return false
        // A stroke that runs off a note page ends at the edge; the system ink is blocked there too.
        if (board.notes.noteInkOffPage(wx, wy)) { s.clipped = true; return false }
        s.points.add(wx - s.cardX)
        s.points.add(wy - s.cardY)
        s.pressures.add(pressure)
        return true
    }

    private fun penMove(e: MotionEvent) {
        penLastX = penX(e)
        penLastY = penY(e)
        penHoverX = penLastX; penHoverY = penLastY
        reevaluateTrailColor("move")
        val move = penMoveSession
        if (move != null) {
            move.rawMoves++
            board.transforms.updateMoveSession(move, board.worldX(penX(e)), board.worldY(penY(e)))
            return
        }
        when (val s = penSession) {
            is PenSession.Write -> {
                for (i in 0 until e.historySize) {
                    val pressure = InputReader.pressureForHistoricalEvent(e, i)
                    if (pressure != null) {
                        appendWritePoint(s, board.worldX(penHistX(e, i)), board.worldY(penHistY(e, i)), pressure)
                        s.raw(e.getHistoricalX(i), e.getHistoricalY(i))
                    }
                }
                val wx = board.worldX(penX(e))
                val wy = board.worldY(penY(e))
                val pressure = InputReader.pressureForMotionEvent(e)
                if (pressure != null) {
                    appendWritePoint(s, wx, wy, pressure)
                    s.raw(e.x, e.y)
                    if (s.cardId == null && board.surface.allowsCardFrame) board.cardGestures.updateFrameGesture(s, wx, wy)
                }
            }
            is PenSession.OpenNote -> {
                if (hypot(board.worldX(penX(e)) - s.downX, board.worldY(penY(e)) - s.downY) > BoardGeometry.CARD_DRAG_THRESHOLD_PX / BoardEngine.scale) {
                    s.moved = true
                    board.host.drawPath.setSuspended(false, "note-tap-cancel")
                    penSession = PenSession.Consumed
                }
            }
            is PenSession.Erase -> {
                for (i in 0 until e.historySize) eraseAt(s, board.worldX(penHistX(e, i)), board.worldY(penHistY(e, i)))
                eraseAt(s, board.worldX(penX(e)), board.worldY(penY(e)))
                flushErase(s)
            }
            is PenSession.Lasso -> {
                for (i in 0 until e.historySize) { s.pointsPx.add(penHistX(e, i)); s.pointsPx.add(penHistY(e, i)) }
                s.pointsPx.add(penX(e)); s.pointsPx.add(penY(e))
            }
            is PenSession.Shape -> {
                s.curX = penX(e); s.curY = penY(e)
                board.overlay.showShapePreview(shapePreviewPath(s), board.isOnDarkCard(board.worldX(s.anchorX), board.worldY(s.anchorY)))
            }
            else -> {}
        }
    }

    private fun shapePreviewPath(s: PenSession.Shape): Path {
        val pts = Shapes.fromDrag(s.kind, s.anchorX, s.anchorY, s.curX, s.curY)
        return BoardEngine.buildPolylinePath(pts)
    }

    private fun penUp(e: MotionEvent, cancelled: Boolean) {
        val wasContact = penContact
        penContact = false
        if (wasContact) Log.i(BoardInteractionController.TAG_PEN, "UP px=(${penX(e)},${penY(e)}) raw=(${e.x},${e.y}) cancelled=$cancelled | ${InputReader.describePenRaw()}")
        try {
            val move = penMoveSession
            if (move != null) {
                penMoveSession = null
                if (cancelled) board.transforms.cancelMoveSession(move) else board.transforms.endMoveSession(move, board.worldX(penX(e)), board.worldY(penY(e)))
                return
            }
            val s = penSession ?: return
            penSession = null
            when (s) {
                is PenSession.Write -> {
                    board.cardGestures.cancelFrameHold()
                    if (cancelled) {
                        board.overlay.hideFramePreview()
                        discardWriteTrail(s, "cancel")
                        return
                    }
                    val wx = board.worldX(penX(e))
                    val wy = board.worldY(penY(e))
                    s.raw(e.x, e.y)
                    reportStrokeForAlignment(s)
                    if (!board.cardGestures.consumeStroke(s, wx, wy)) commitStroke(s)
                }
                is PenSession.Erase -> finishErase(s)
                is PenSession.Lasso -> {
                    if (!cancelled) board.selection.finalizeLasso(s.pointsPx)
                }
                is PenSession.Shape -> {
                    board.overlay.hideShapePreview()
                    if (!cancelled) {
                        s.curX = penX(e); s.curY = penY(e)

                        if (finalizeShape(s) && board.arbiter.hasOverride(ToolArbiter.Source.SHAPE)) {
                            board.shapeSelectionPending = true
                            board.applyTransition(
                                board.arbiter.replaceOverride(
                                    ToolArbiter.Source.SHAPE,
                                    ToolArbiter.Tool.LASSO,
                                    ToolArbiter.Settle.ON_PEN_UP,
                                ),
                                "shape-stroke-to-lasso",
                            )
                        }
                    } else if (board.arbiter.hasOverride(ToolArbiter.Source.SHAPE)) {
                        board.applyTransition(board.arbiter.releaseOverride(ToolArbiter.Source.SHAPE), "shape-cancel")
                    }
                }
                is PenSession.OpenNote -> {
                    board.host.drawPath.setSuspended(false, "note-tap-end")
                    if (!cancelled) {
                        val endX = board.worldX(penX(e)); val endY = board.worldY(penY(e))
                        val distance = hypot(endX - s.downX, endY - s.downY)
                        if (!s.moved && distance <= BoardGeometry.CARD_DRAG_THRESHOLD_PX / BoardEngine.scale) {
                            val shotId = s.shotId
                            if (shotId != null) board.noteShots.openLink(shotId) else board.notes.openNote(s.ref)
                        }
                    }
                }
                PenSession.Consumed -> {}
            }
        } finally {
            if (wasContact) board.applyTransition(board.arbiter.penUp(), "pen-up")
        }
    }

    private fun reportStrokeForAlignment(s: PenSession.Write) {
        if (s.rawMinX > s.rawMaxX) return
        board.refreshViewOffset()
        InkAlign.onPenUp(
            s.rawMinX + board.viewOffsetX, s.rawMinY + board.viewOffsetY,
            s.rawMaxX + board.viewOffsetX, s.rawMaxY + board.viewOffsetY,
        )
    }

    private fun commitStroke(s: PenSession.Write) {
        if (s.points.size < 2) return

        val marker = board.penStyle == PenStyle.MARKER
        val draft = BoardEngine.StrokeRec(
            s.id,
            s.space,
            board.penWidth,
            if (marker) board.markerInk.argb else INK_BLACK,
            s.points.toArray(),
            s.pressures.toArray(),
            board.penStyle.objType,
            board.density * BoardEngine.scale,
            DrawPathClient.widthArgument(board.penStyle, board.penWidth),
            s.nibAngle,
        )
        val ink = synchronized(BoardEngine.lock) { InkBackdrop.resolve(draft) }
        val rec = draft.withColor(ink)
        val change = (if (s.continuousWrite) board.cardGestures.growCardForStroke(rec) else null)
            ?: BoardHistory.Change("draw").stroke(null, rec)
        board.host.inkHandoff.commitPlainStroke { board.applyChange(change, record = true) }
        lastCardWriteId = s.cardId
        lastCardWriteAt = SystemClock.uptimeMillis()
        if (!marker && (ink != INK_BLACK) != trailWhite) Log.i(BoardInteractionController.TAG_PEN, "[MosaicTrail] color differs from hardware trail committedWhite=${ink != INK_BLACK} trailWhite=$trailWhite stroke=${s.id}")
        val committedPressures = s.pressures.toArray()
        val minPressure = committedPressures.minOrNull() ?: 0f
        val maxPressure = committedPressures.maxOrNull() ?: 0f
        val committedDrawPathWidth = DrawPathClient.widthArgument(board.penStyle, board.penWidth)
        var minRasterWidth = Float.POSITIVE_INFINITY
        var maxRasterWidth = 0f
        for (run in rec.runs) {
            minRasterWidth = minOf(minRasterWidth, run.minWidth)
            maxRasterWidth = maxOf(maxRasterWidth, run.maxWidth)
        }
        if (!minRasterWidth.isFinite()) minRasterWidth = 0f
        val screenMin = minRasterWidth * board.density * BoardEngine.scale
        val screenMax = maxRasterWidth * board.density * BoardEngine.scale
        Log.i(BoardInteractionController.TAG_PEN, "UP committed stroke ${s.id} style=${board.penStyle.name} stdWidth=${board.penWidth} drawPathWidth=$committedDrawPathWidth pressureRange=$minPressure..$maxPressure rasterWorldRange=$minRasterWidth..$maxRasterWidth rasterScreenPxRange=$screenMin..$screenMax nibAngle=${rec.nibAngle} points=${s.points.size / 2} space=${s.space} ink=${Integer.toHexString(ink)}")
    }

    private fun eraseAt(session: PenSession.Erase, wx: Float, wy: Float) {
        hitBuffer.clear()
                synchronized(BoardEngine.lock) { BoardEngine.hitTestStrokes(wx, wy, ERASER_HIT_RADIUS / BoardEngine.scale, hitBuffer) }
        if (hitBuffer.isEmpty()) return
        for (rec in hitBuffer) {
            if (session.pendingIds.add(rec.id)) session.pending.stroke(rec, null)
        }
    }

    private fun flushErase(session: PenSession.Erase) {
        val step = session.pending
        if (step.isEmpty) return
        session.pending = BoardHistory.Change("erase")
        session.pendingIds.clear()
        board.applyChange(step, record = false)
        session.change.absorb(step)
    }

    private fun finishErase(session: PenSession.Erase) {
        flushErase(session)
        if (!session.change.isEmpty) {
            board.history.push(session.change)
            Log.i(BoardInteractionController.TAG_PEN, "erased ${session.change.diffs.size} strokes")
        }
    }

    private fun finalizeShape(s: PenSession.Shape): Boolean {
        val dragPx = hypot(s.curX - s.anchorX, s.curY - s.anchorY)
        if (dragPx < SHAPE_MIN_DRAG_DP * board.density) {
            Log.i(BoardInteractionController.TAG_LASSO, "shape: drag too short ${dragPx}px")
            return false
        }
        val ax = board.worldX(s.anchorX); val ay = board.worldY(s.anchorY)
        val shape = Shapes.fromDrag(s.kind, ax, ay, board.worldX(s.curX), board.worldY(s.curY))
        val card = BoardGeometry.topCardAt(BoardEngine.cardsByZ, ax, ay)
        if (card != null) {
            var i = 0
            while (i < shape.size) { shape[i] -= card.x; shape[i + 1] -= card.y; i += 2 }
        }

        val rec = BoardEngine.StrokeRec(
            board.newId("shape-"), if (card != null) "card:${card.id}" else
                BoardEngine.connectionAt(ax, ay)?.let { "connection:${it.id}" } ?: "canvas", board.penWidth, INK_BLACK,
            shape, Shapes.pressures(shape.size / 2), board.penStyle.objType, board.density * BoardEngine.scale,
            DrawPathClient.widthArgument(board.penStyle, board.penWidth),
        )
        board.applyChange(BoardHistory.Change("shape").stroke(null, rec), record = true)
        val kind = Shapes.kindOf(rec)
        Log.i(BoardInteractionController.TAG_LASSO, "shape: ${kind} points=${shape.size / 2} card=${card?.id ?: "canvas"}")
        board.selection.selectStroke(rec)
        return true
    }
}
