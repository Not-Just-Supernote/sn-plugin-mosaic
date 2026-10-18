package me.laumss.mosaic.eink

import android.util.Log
import android.view.View
import me.laumss.mosaic.MosaicEinkRefreshModule
import me.laumss.mosaic.board.BoardInteractionController

/** Request UI Regal in the final content frame, without adding a separate cleanup frame. */
internal class CardTrailCleanup(private val board: BoardInteractionController) {
    private var pendingReason: String? = null
    private var pendingRegal = true

    fun cancel(reason: String) {
        board.host.cancelGrayRestore(reason)
        if (reason in FORGET_REASONS) pendingReason = null
    }

    fun resume(reason: String) {
        if (pendingReason == null || !ready()) return
        board.content.postInvalidateOnAnimation()
        Log.i(TAG, "resume owner=board-card reason=$reason requestRegal=$pendingRegal")
    }

    fun schedule(reason: String, requestRegal: Boolean = true) {
        pendingReason = reason
        pendingRegal = requestRegal
        board.host.cancelGrayRestore("movement:$reason")
        // Presentation already schedules the static recipe after releasing the gesture mode.
        // Consume this request in that final draw, not in a post-settle redraw.
        Log.i(TAG, "queued owner=board-card reason=$reason requestRegal=$requestRegal")
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

    private companion object {
        const val TAG = "MosaicEinkCleanup"
        val FORGET_REASONS = setOf("surface-change", "detach", "size-change", "manual-refresh")
    }
}
