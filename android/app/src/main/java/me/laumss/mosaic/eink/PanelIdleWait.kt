package me.laumss.mosaic.eink

import android.os.SystemClock
import android.view.View
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Waits until the frame drawn just now has reached the panel and the panel has finished updating it.
 * An HWC command takes effect at once, ahead of a frame still on its way through RenderThread and
 * the compositor, so a command meant for the final frame must wait for this.
 */
internal class PanelIdleWait(private val view: View, private val executor: Executor = worker) {
    private var generation = 0
    private var startedAt = 0L
    private var sawBusy = false
    private var idleSamples = 0
    private var done: ((String) -> Unit)? = null

    val isWaiting: Boolean get() = done != null

    fun start(onIdle: (how: String) -> Unit) {
        cancel()
        done = onIdle
        startedAt = SystemClock.uptimeMillis()
        sawBusy = false
        idleSamples = 0
        val token = generation
        view.postDelayed({ poll(token) }, SUBMISSION_GRACE_MS)
    }

    fun cancel() {
        generation++
        done = null
    }

    private fun poll(token: Int) {
        if (token != generation || done == null) return
        executor.execute {
            val busy = EinkRegionDriver.displayBusy()
            view.post { sample(token, busy) }
        }
    }

    private fun sample(token: Int, busy: Int) {
        if (token != generation) return
        val elapsed = SystemClock.uptimeMillis() - startedAt
        sawBusy = sawBusy || busy > 0
        idleSamples = if (busy == 0) idleSamples + 1 else 0
        val how = when {
            elapsed >= DEADLINE_MS -> "deadline"
            busy < 0 && elapsed >= NO_BUSY_GRACE_MS -> "state-unavailable"
            idleSamples >= 2 && sawBusy -> "panel-idle"
            idleSamples >= 2 && elapsed >= NO_BUSY_GRACE_MS -> "idle-no-busy-observed"
            else -> null
        }
        if (how == null) { view.postDelayed({ poll(token) }, POLL_MS); return }
        val callback = done ?: return
        done = null
        callback(how)
    }

    private companion object {
        const val SUBMISSION_GRACE_MS = 100L
        // Without a busy reading, an idle reading this early may predate the frame.
        const val NO_BUSY_GRACE_MS = 400L
        const val POLL_MS = 48L
        const val DEADLINE_MS = 1500L
        val worker: Executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "MosaicPanelIdle").apply { isDaemon = true }
        }
    }
}
