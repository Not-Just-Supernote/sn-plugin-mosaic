package me.laumss.mosaic.eink

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.util.concurrent.Executors
import java.util.concurrent.Executor

/** Restore the existing RGB frame through explicit gray conversion after host GC. */
internal class EinkGrayRestore(
    private val view: View,
    private val owner: String,
    private val eligible: () -> Boolean,
    private val executor: Executor = worker,
) : View.OnAttachStateChangeListener {
    private var generation = 0
    private var registered = false
    private var pending = false
    private var requestedAt = 0L
    private var idleSamples = 0
    private val pollTask = Runnable { poll(generation) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == HOST_REFRESH) request("host-broadcast")
        }
    }

    init {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    fun request(reason: String) {
        cancel("replace")
        if (!available()) return
        pending = true
        requestedAt = SystemClock.uptimeMillis()
        idleSamples = 0
        // The broadcast follows submission, rather than physical panel completion.
        view.postDelayed(pollTask, SUBMISSION_GRACE_MS)
        Log.i(TAG, "wait owner=$owner reason=$reason")
    }

    fun cancel(reason: String) {
        generation++
        view.removeCallbacks(pollTask)
        if (pending) Log.i(TAG, "cancel owner=$owner reason=$reason")
        pending = false
    }

    private fun available() = view.isAttachedToWindow && view.isShown &&
        view.windowVisibility == View.VISIBLE && eligible()

    private fun poll(token: Int) {
        if (!pending || token != generation) return
        if (!available()) { cancel("scene-active"); return }
        if (SystemClock.uptimeMillis() - requestedAt >= DEADLINE_MS) { cancel("deadline"); return }
        executor.execute {
            val busy = EinkRegionDriver.displayBusy()
            view.post {
                if (!pending || token != generation) return@post
                if (SystemClock.uptimeMillis() - requestedAt >= DEADLINE_MS) { cancel("deadline"); return@post }
                if (!available()) { cancel("scene-active"); return@post }
                if (busy < 0) { cancel("panel-state-unavailable"); return@post }
                idleSamples = if (busy == 0) idleSamples + 1 else 0
                if (idleSamples >= 3) {
                    pending = false
                    val ok = MosaicEinkRefreshModule.requestGrayFrame(view, "$owner:host-refresh")
                    Log.i(TAG, "submitted owner=$owner ok=$ok elapsedMs=${SystemClock.uptimeMillis() - requestedAt}")
                } else view.postDelayed(pollTask, POLL_MS)
            }
        }
    }

    override fun onViewAttachedToWindow(v: View) {
        if (registered) return
        try {
            val context = view.context.applicationContext
            val filter = IntentFilter(HOST_REFRESH)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            registered = true
        } catch (error: Exception) { Log.w(TAG, "host refresh observer owner=$owner", error) }
    }

    override fun onViewDetachedFromWindow(v: View) {
        cancel("detach")
        if (registered) {
            view.context.applicationContext.unregisterReceiver(receiver)
            registered = false
        }
    }

    private companion object {
        const val TAG = "MosaicGrayRestore"
        const val HOST_REFRESH = "com.ratta.supernote.launcher.flashscreen"
        const val SUBMISSION_GRACE_MS = 250L
        const val POLL_MS = 48L
        const val DEADLINE_MS = 3000L
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "MosaicGrayState").apply { isDaemon = true } }
    }
}
