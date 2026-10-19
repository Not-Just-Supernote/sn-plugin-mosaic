package me.laumss.mosaic.eink

import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.View
import me.laumss.mosaic.MosaicEinkRefreshModule
import me.laumss.mosaic.board.BoardInteractionController

/**
 * Cleanup after movement. Outside FullUiAuto: the v3 Regal window in the final frame. Under FullUiAuto
 * (lasso): the Note app's lasso refresh, a mode-10 region refresh of everything the drag drew over,
 * 600ms after release and once the final frame is drawn.
 */
internal class CardTrailCleanup(private val board: BoardInteractionController) {
    private var pendingReason: String? = null
    private var pendingRegal = true
    private var foreground = true
    private val window = RegalRefreshWindow(board.content, canOwn = { foreground && !board.lassoAuto.isApplied })
    // FullUiAuto: screen area still to refresh, in content view pixels (AreaSelectionView.refreshRect).
    private val swept = RectF()
    private var regionGeneration = 0
    private var regionPosted = false
    private val regionTask = Runnable { regionDelayElapsed(regionGeneration) }

    fun cancel(reason: String) {
        board.host.cancelGrayRestore(reason)
        // New input restarts the wait, as AreaSelectionView removes its refresh task on touch;
        // the area is kept and refreshed after that input ends.
        stopRegion()
        // A pan or zoom moves the content away from the swept pixels.
        if (reason in FORGET_REASONS || reason in RELEASE_REASONS || reason == "panzoom-begin") swept.setEmpty()
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
        if (!swept.isEmpty && !regionPosted && !board.pen.penContact && board.touch.fingers.isEmpty()) postRegion("resume:$reason")
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

    fun schedule(reason: String, requestRegal: Boolean = true, sweptPx: RectF? = null) {
        if (sweptPx != null) scheduleRegion(reason, sweptPx)
        pendingReason = reason
        pendingRegal = requestRegal
        board.host.cancelGrayRestore("movement:$reason")
        window.acquireIdle("queued:$reason")
        Log.i(TAG, "queued owner=board-card reason=$reason requestRegal=$requestRegal via=final-frame")
    }

    /** Under FullUiAuto, refresh this screen area 600ms after the drag, as the Note app's lasso does. */
    fun scheduleRegion(reason: String, sweptPx: RectF) {
        if (sweptPx.isEmpty || !board.lassoAuto.isApplied) return
        swept.union(sweptPx)
        postRegion(reason)
    }

    private fun postRegion(reason: String) {
        stopRegion()
        regionPosted = true
        board.content.postDelayed(regionTask, REGION_DELAY_MS)
        Log.i(TAG, "region queued owner=board-card reason=$reason bounds=$swept delayMs=$REGION_DELAY_MS")
    }

    private fun stopRegion() {
        regionGeneration++
        regionPosted = false
        board.content.removeCallbacks(regionTask)
    }

    private fun regionDelayElapsed(token: Int) {
        if (token != regionGeneration) return
        // The final frame may still be waiting for its tiles; refresh what will stay on screen.
        board.content.runWhenSettled(Runnable {
            if (token != regionGeneration) return@Runnable
            regionPosted = false
            if (!ready()) { Log.i(TAG, "region deferred owner=board-card: not ready"); return@Runnable }
            val rect = Rect()
            swept.roundOut(rect)
            swept.setEmpty()
            if (!rect.intersect(0, 0, board.content.width, board.content.height)) return@Runnable
            MosaicEinkRefreshModule.refreshRegion(board.content, rect, "board-card")
        })
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
            // As in the Note app, Regal stays off from the first move until the next press. A Regal
            // request here did not clear the ghosting: the region refresh (scheduleRegion) does.
            Log.i(TAG, "final frame owner=board-card reason=$reason mode=auto regionRefreshPending=$regionPosted")
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
        // AreaSelectionView.delayRefreshTime
        const val REGION_DELAY_MS = 600L
        val RELEASE_REASONS = setOf("detach", "hidden", "focus-lost")
        val FORGET_REASONS = setOf("surface-change", "detach", "size-change", "manual-refresh")
    }
}
