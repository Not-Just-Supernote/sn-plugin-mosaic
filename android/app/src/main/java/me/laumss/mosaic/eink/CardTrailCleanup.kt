package me.laumss.mosaic.eink

import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.util.Log
import android.view.View
import me.laumss.mosaic.MosaicEinkRefreshModule
import me.laumss.mosaic.board.BoardInteractionController
import me.laumss.mosaic.engine.BoardEngine
import me.laumss.mosaic.engine.BoardViewport

/**
 * Two cleanups after movement:
 * - canvas pan/zoom: once the scene settles, the uncovered white at old positions is driven black then white;
 * - card and selection transforms: UI Regal is requested in the final content frame, without an extra frame.
 */
internal class CardTrailCleanup(private val board: BoardInteractionController) {
    private var pendingReason: String? = null
    private var pendingRegal = true

    private val backlog = CardCleanupBacklog()
    private var runningTicket: CardCleanupBacklog.Ticket? = null
    private var queued = false

    private val driver = EinkTrailCleanup(board.content, "board-pan") { result ->
        val ticket = runningTicket
        if (ticket != null && CardCleanupBacklog.whiteCompleted(result) && backlog.complete(ticket)) {
            runningTicket = null
            Log.i(TAG, "backlog cleared after white completion")
        }
    }

    fun cancel(reason: String) {
        board.host.cancelGrayRestore(reason)
        queued = false
        driver.cancel(reason)
        if (reason in FORGET_REASONS) {
            pendingReason = null
            backlog.clear()
            runningTicket = null
        } else if (!backlog.isEmpty) {
            Log.i(TAG, "deferred owner=board-pan reason=$reason retained=${backlog.capture().region.bounds}")
        }
    }

    fun resume(reason: String) {
        if (pendingReason != null && ready()) {
            board.content.postInvalidateOnAnimation()
            Log.i(TAG, "resume owner=board-card reason=$reason requestRegal=$pendingRegal")
        }
        if (backlog.isEmpty || queued || driver.phase != "idle" ||
            !board.surface.showsBoardChrome || !board.content.isAttachedToWindow || !board.content.isShown ||
            board.content.holdPaint || board.chrome.blocksPen || board.pen.penContact || board.touch.fingers.isNotEmpty() || board.sliderTwoDown ||
            board.presentation.state.movingCards) return
        queue("resume:$reason", 0L)
    }

    /** Card and selection transforms: request UI Regal in the final settled frame. */
    fun schedule(reason: String, requestRegal: Boolean = true) {
        pendingReason = reason
        pendingRegal = requestRegal
        board.host.cancelGrayRestore("movement:$reason")
        // Presentation already schedules the static recipe after releasing the gesture mode.
        // Consume this request in that final draw, not in a post-settle redraw.
        Log.i(TAG, "queued owner=board-card reason=$reason requestRegal=$requestRegal")
    }

    /** Canvas pan/zoom: clean the old positions once the scene settles. */
    fun schedule(oldPositions: Region, reason: String) {
        if (!board.surface.showsBoardChrome) return
        backlog.add(oldPositions)
        if (backlog.isEmpty) return
        queue(reason, BoardInteractionController.GESTURE_SETTLE_MS)
    }

    /** Called inside onDraw after all visible tiles are fresh, before buffer submission. */
    fun onSettledFrameDrawing() {
        val reason = pendingReason ?: return
        if (!ready() || MosaicEinkRefreshModule.hasFastModeRequest()) return
        pendingReason = null
        if (!pendingRegal) {
            Log.i(TAG, "final frame owner=board-card reason=$reason redrawOnly=true")
            return
        }
        val requested = MosaicEinkRefreshModule.requestUiRegal(board.content, true, "board-card:$reason")
        Log.i(TAG, "final frame owner=board-card reason=$reason uiRegalRequested=$requested extraFrame=false")
    }

    private fun ready() = board.content.isAttachedToWindow && board.content.isShown && board.content.hasWindowFocus() &&
        board.content.windowVisibility == View.VISIBLE &&
        !board.content.holdPaint && !board.content.isLookPending && !board.chrome.blocksPen &&
        !board.pen.penContact && board.touch.fingers.isEmpty() && !board.sliderTwoDown &&
        !board.presentation.state.movingCards

    private fun queue(reason: String, delayMs: Long) {
        board.host.cancelGrayRestore("reschedule:$reason")
        driver.cancel("reschedule:$reason")
        queued = true
        val ticket = backlog.capture()
        val revision = driver.revision
        val scale = BoardViewport.scale; val panX = BoardViewport.panX; val panY = BoardViewport.panY
        val trail = ticket.region
        Log.i(TAG, "queued owner=board-pan reason=$reason bounds=${trail.bounds}")
        // Presentation restores the normal scene after its gesture linger. Wait
        // for that restoration before requesting the final settled content frame.
        board.handler.postDelayed({
            if (revision != driver.revision) return@postDelayed
            board.content.runWhenSettled(Runnable {
                if (revision != driver.revision) return@Runnable
                queued = false
                runningTicket = ticket
                driver.schedule {
                    val exposed = Region(trail)
                    if (!board.surface.showsBoardChrome || board.chrome.blocksPen || board.pen.penContact ||
                        board.touch.fingers.isNotEmpty() || board.sliderTwoDown || scale != BoardViewport.scale || panX != BoardViewport.panX || panY != BoardViewport.panY) {
                        exposed.setEmpty()
                    } else {
                        val bounds = Rect()
                        synchronized(BoardEngine.lock) {
                            for (card in BoardEngine.cards.values) {
                                board.worldRectToPx(BoardEngine.cardDirtyBounds(card), RectF()).roundOut(bounds)
                                exposed.op(bounds, Region.Op.DIFFERENCE)
                            }
                        }
                        board.chrome.cleanupExclusionBounds().forEach { exposed.op(it, Region.Op.DIFFERENCE) }
                        board.overlay.cleanupExclusionBounds().forEach {
                            it.roundOut(bounds); exposed.op(bounds, Region.Op.DIFFERENCE)
                        }
                    }
                    Log.i(TAG, "ready owner=board-pan reason=$reason bounds=${exposed.bounds}")
                    exposed
                }
            })
        }, delayMs)
    }

    private companion object {
        const val TAG = "MosaicEinkCleanup"
        // Cancelling for these also forgets old positions not yet cleaned: the screen no longer shows them.
        val FORGET_REASONS = setOf("surface-change", "detach", "size-change", "manual-refresh")
    }
}
