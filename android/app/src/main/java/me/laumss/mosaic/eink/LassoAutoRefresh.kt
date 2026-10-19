package me.laumss.mosaic.eink

import android.util.Log
import android.view.View
import me.laumss.mosaic.MosaicEinkRefreshModule

/**
 * The lasso refresh of the Note app (AreaSelectionView / HandWriteClient): FullUiAuto stays on for the
 * whole time the lasso is in use; a drag opens Regal when pressed and closes it at its first move.
 * There is no mode change when a drag starts, only the Regal flag.
 */
internal class LassoAutoRefresh(private val view: View) {
    private var wanted = false
    private var suspended = false
    private var applied = false
    // Mirrors HandWriteClient.regalStatus. Turning FullUiAuto on or off leaves Regal on.
    private var regal = true

    private val closeTask = Runnable { apply(false, "delayed-close") }

    /** Lasso tool or lasso selection in use. Leaving closes after a short delay, as the Note app does. */
    fun hold(on: Boolean, reason: String) {
        if (wanted == on) return
        wanted = on
        view.removeCallbacks(closeTask)
        if (suspended) return
        if (on) apply(true, reason) else if (applied) view.postDelayed(closeTask, CLOSE_DELAY_MS)
    }

    /** Detach: close at once and forget the request. */
    fun release(reason: String) {
        wanted = false
        suspended = false
        view.removeCallbacks(closeTask)
        apply(false, reason)
    }

    /** Hidden or unfocused: hand the panel back to the host, reopen when the board returns. */
    fun suspend(reason: String) {
        if (suspended) return
        suspended = true
        view.removeCallbacks(closeTask)
        apply(false, "suspend:$reason")
    }

    fun resume(reason: String) {
        if (!suspended) return
        suspended = false
        if (wanted) apply(true, "resume:$reason")
    }

    /** Before a drag of the selection: Regal on for the still frame. */
    fun pressed(reason: String) {
        if (applied) setRegal(true, reason)
    }

    /** The selection has started to move: Regal off while it moves. */
    fun moved(reason: String) {
        if (applied) setRegal(false, reason)
    }

    private fun apply(on: Boolean, reason: String) {
        if (applied == on) return
        if (MosaicEinkRefreshModule.setFullUiAuto(view, on, "lasso:$reason")) regal = true
        applied = on
        Log.i(TAG, "fullAuto=$on reason=$reason")
    }

    private fun setRegal(on: Boolean, reason: String) {
        if (regal == on) return
        regal = on
        MosaicEinkRefreshModule.setUiRegal(view, on, "lasso:$reason")
    }

    private companion object {
        const val TAG = "MosaicLassoAuto"
        // NotePresenter.autoCloseDelayed("lasso view gone", 200)
        const val CLOSE_DELAY_MS = 200L
    }
}
