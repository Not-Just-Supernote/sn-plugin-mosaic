package me.laumss.mosaic.eink

import android.os.SystemClock
import android.util.Log
import android.view.View
import me.laumss.mosaic.MosaicEinkRefreshModule
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** v3: non-auto owns an off baseline, with a bounded Regal window for the final frame. */
internal class RegalRefreshWindow(
    private val view: View,
    private val canOwn: () -> Boolean,
    private val executor: Executor = worker,
) {
    private var owned = false
    var isActive = false
        private set
    private var generation = 0
    private var openedAt = 0L
    private var idleSamples = 0
    private var sawBusy = false
    private val pollTask = Runnable { poll(generation) }
    private val deadlineTask = Runnable { finish("deadline", false) }

    init {
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { view.post { acquireIdle("attach") } }
            override fun onViewDetachedFromWindow(v: View) { release("detach") }
        })
        if (view.isAttachedToWindow) view.post { acquireIdle("initial") }
    }

    fun acquireIdle(reason: String) {
        if (!available()) {
            // FullUiAuto owns its own Regal flag, including its deliberate off state while dragging.
            if (owned) release("handoff:$reason")
            return
        }
        if (owned) return
        owned = true
        setEnabled(false, "idle:$reason")
    }

    fun begin(reason: String): Boolean {
        if (!available()) return false
        acquireIdle(reason)
        stopPolling()
        if (!setEnabled(true, "movement:$reason")) {
            setEnabled(false, "enable-failed:$reason")
            return false
        }
        isActive = true
        openedAt = SystemClock.uptimeMillis()
        sawBusy = false
        idleSamples = 0
        view.postDelayed(pollTask, SUBMISSION_GRACE_MS)
        view.postDelayed(deadlineTask, DEADLINE_MS)
        return true
    }

    fun interrupt(reason: String) {
        if (!available()) { release(reason); return }
        if (isActive) {
            stopPolling()
            setEnabled(false, "interrupt:$reason")
        } else acquireIdle(reason)
    }

    /** Restore the measured host baseline before handing the panel to auto or another window. */
    fun release(reason: String) {
        if (!owned && !isActive) return
        stopPolling()
        owned = false
        setEnabled(true, "release:$reason")
    }

    private fun available() = view.isAttachedToWindow && view.isShown && view.hasWindowFocus() &&
        view.windowVisibility == View.VISIBLE && canOwn()

    private fun poll(token: Int) {
        if (!isActive || token != generation) return
        if (!available()) { release("unavailable"); return }
        executor.execute {
            val busy = EinkRegionDriver.displayBusy()
            view.post {
                // A worker that predates a new input or FullUiAuto handoff must never close that owner.
                if (!isActive || token != generation) return@post
                if (!available()) { release("unavailable"); return@post }
                val elapsed = SystemClock.uptimeMillis() - openedAt
                if (elapsed >= DEADLINE_MS) { finish("deadline", false); return@post }
                sawBusy = sawBusy || busy > 0
                idleSamples = if (busy == 0) idleSamples + 1 else 0
                when {
                    busy < 0 && elapsed >= NO_BUSY_GRACE_MS -> finish("state-unavailable", false)
                    idleSamples >= 3 && sawBusy -> finish("panel-idle", true)
                    idleSamples >= 3 && elapsed >= NO_BUSY_GRACE_MS -> finish("idle-no-busy-observed", false)
                    else -> view.postDelayed(pollTask, POLL_MS)
                }
            }
        }
    }

    private fun finish(reason: String, confirmedIdle: Boolean) {
        if (!isActive) return
        if (!available()) { release(reason); return }
        val elapsed = SystemClock.uptimeMillis() - openedAt
        stopPolling()
        setEnabled(false, "finished:$reason")
        Log.i(TAG, "closed reason=$reason confirmedIdle=$confirmedIdle elapsedMs=$elapsed")
    }

    private fun stopPolling() {
        generation++
        isActive = false
        view.removeCallbacks(pollTask)
        view.removeCallbacks(deadlineTask)
    }

    private fun setEnabled(enabled: Boolean, reason: String) =
        MosaicEinkRefreshModule.requestRegalState(view.context, enabled, reason)

    private companion object {
        const val TAG = "MosaicRegalGate"
        const val SUBMISSION_GRACE_MS = 250L
        const val NO_BUSY_GRACE_MS = 750L
        const val POLL_MS = 48L
        const val DEADLINE_MS = 3000L
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "MosaicRegalState").apply { isDaemon = true }
        }
    }
}
