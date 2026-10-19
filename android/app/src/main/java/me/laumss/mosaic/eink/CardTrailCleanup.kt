package me.laumss.mosaic.eink

import android.util.Log
import android.view.View
import me.laumss.mosaic.MosaicEinkRefreshModule
import me.laumss.mosaic.board.BoardInteractionController

/** Final-frame cleanup: v3 window outside FullUiAuto, existing UI Regal request inside it. */
internal class CardTrailCleanup(private val board: BoardInteractionController) {
    private var pendingReason: String? = null
    private var pendingRegal = true
    private var foreground = true
    private val window = RegalRefreshWindow(board.content, canOwn = { foreground && !board.lassoAuto.isApplied })

    fun cancel(reason: String) {
        board.host.cancelGrayRestore(reason)
        if (reason in RELEASE_REASONS) foreground = false
        if (window.isActive && reason !in FORGET_REASONS && pendingReason == null) {
            pendingReason = "interrupted:$reason"
            pendingRegal = true
        }
        if (reason in RELEASE_REASONS) window.release(reason) else window.interrupt(reason)
        if (reason in FORGET_REASONS) pendingReason = null
    }

    fun resume(reason: String) {
        if (reason in setOf("attach", "focus-gained", "visible")) foreground = true
        window.acquireIdle(reason)
        if (pendingReason == null || !ready()) return
        board.content.postInvalidateOnAnimation()
        Log.i(TAG, "resume owner=board-card reason=$reason requestRegal=$pendingRegal")
    }

    /** FullUiAuto is about to take control; invalidate all outstanding window completions first. */
    fun onAutoStarting() {
        if (window.isActive && pendingReason == null) pendingReason = "interrupted:full-auto"
        window.release("full-auto")
    }

    /** FullUiAuto has released control; restore the non-auto idle baseline when visible. */
    fun onAutoStopped() = resume("full-auto-stopped")

    fun schedule(reason: String, requestRegal: Boolean = true) {
        pendingReason = reason
        pendingRegal = requestRegal
        board.host.cancelGrayRestore("movement:$reason")
        window.acquireIdle("queued:$reason")
        Log.i(TAG, "queued owner=board-card reason=$reason requestRegal=$requestRegal via=final-frame")
    }

    /** Covers non-auto A2 motions such as an Inkling overlay drag as well as explicit gesture ends. */
    fun onA2MotionSettled() {
        if (!board.lassoAuto.isApplied && pendingReason == null) schedule("a2-motion")
    }

    /** Runs in the natural final draw after visible tiles are fresh, before buffer submission. */
    fun onSettledFrameDrawing() {
        window.acquireIdle("frame")
        val reason = pendingReason ?: return
        if (!ready() || MosaicEinkRefreshModule.hasFastModeRequest()) return
        pendingReason = null
        if (!pendingRegal) {
            Log.i(TAG, "final frame owner=board-card reason=$reason redrawOnly=true")
        } else if (board.lassoAuto.isApplied) {
            val requested = MosaicEinkRefreshModule.requestUiRegal(board.content, true, "board-card:$reason")
            Log.i(TAG, "final frame owner=board-card reason=$reason mode=auto uiRegalRequested=$requested extraFrame=false")
        } else {
            val opened = window.begin(reason)
            Log.i(TAG, "final frame owner=board-card reason=$reason mode=non-auto regalWindowOpened=$opened extraFrame=false")
        }
    }

    private fun ready() = foreground && board.content.isAttachedToWindow && board.content.isShown && board.content.hasWindowFocus() &&
        board.content.windowVisibility == View.VISIBLE &&
        !board.content.holdPaint && !board.content.isLookPending && !board.chrome.blocksPen &&
        !board.pen.penContact && board.touch.fingers.isEmpty() && !board.sliderTwoDown &&
        !board.presentation.state.movingCards

    private companion object {
        const val TAG = "MosaicEinkCleanup"
        val RELEASE_REASONS = setOf("detach", "hidden", "focus-lost")
        val FORGET_REASONS = setOf("surface-change", "detach", "size-change", "manual-refresh")
    }
}
