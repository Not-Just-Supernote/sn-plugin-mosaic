package me.laumss.mosaic

import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.util.Log

/** Clears what moved light cards leave behind on the panel: once the scene settles, the uncovered white is driven black then white. */
internal class CardTrailCleanup(private val board: BoardInteractionController) {
    companion object {
        private const val TAG = "MosaicEinkCleanup"
        // Cancelling for these also forgets old positions not yet cleaned: the screen no longer shows them.
        private val FORGET_REASONS = setOf("surface-change", "detach", "size-change", "manual-refresh")
    }

    private val backlog = CardCleanupBacklog()

    private var runningTicket: CardCleanupBacklog.Ticket? = null

    private var queued = false

    private val driver = EinkTrailCleanup(board.content, "board-card") { result ->
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
            backlog.clear()
            runningTicket = null
        } else if (!backlog.isEmpty) {
            Log.i(TAG, "deferred owner=board-card reason=$reason retained=${backlog.capture().region.bounds}")
        }
    }

    fun resume(reason: String) {
        if (backlog.isEmpty || queued || driver.phase != "idle" ||
            !board.surface.showsBoardChrome || !board.content.isAttachedToWindow || !board.content.isShown ||
            board.content.holdPaint || board.chrome.blocksPen || board.pen.penContact || board.touch.fingers.isNotEmpty() || board.sliderTwoDown ||
            board.presentation.state.movingCards) return
        queue("resume:$reason", 0L)
    }

    fun schedule(oldPositions: Region, reason: String) {
        if (!board.surface.showsBoardChrome) return
        backlog.add(oldPositions)
        if (backlog.isEmpty) return
        queue(reason, BoardInteractionController.GESTURE_SETTLE_MS)
    }

    private fun queue(reason: String, delayMs: Long) {
        cancel("reschedule:$reason")
        queued = true
        val ticket = backlog.capture()
        val revision = driver.revision
        val scale = BoardViewport.scale; val panX = BoardViewport.panX; val panY = BoardViewport.panY
        val trail = ticket.region
        Log.i(TAG, "queued owner=board-card reason=$reason bounds=${trail.bounds}")
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
                    Log.i(TAG, "ready owner=board-card reason=$reason bounds=${exposed.bounds}")
                    exposed
                }
            })
        }, delayMs)
    }
}
