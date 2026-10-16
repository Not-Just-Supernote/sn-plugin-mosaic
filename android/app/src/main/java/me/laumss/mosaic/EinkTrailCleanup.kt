package me.laumss.mosaic

import android.graphics.Region
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver

/** A masked black/white pair after the final scene settles, with asynchronous driver polling. */
internal class EinkTrailCleanup(
    private val view: View,
    private val owner: String,
    private val onFinished: (String) -> Unit = {},
) {
    var revision = 0
        private set
    private var pending: (() -> Region)? = null
    private var token = 0L
    private var whiteSubmitted = false
    private var deadline = 0L
    val phase: String get() = if (token != 0L) { if (whiteSubmitted) "white" else "black" }
        else if (pending != null) "waiting" else "idle"
    private var observer: ViewTreeObserver? = null
    private val pollTask = Runnable { poll() }
    private val expireTask = Runnable { cancel("scene-kept-changing") }
    private val startTask = object : Runnable {
        override fun run() {
            val provider = pending
            val region = provider?.invoke()
            pending = null
            if (region != null && !region.isEmpty && view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE) {
                token = EinkRegionDriver.beginBlackBatch(view, region, owner)
            }
            if (token == 0L && provider != null && region != null && !region.isEmpty && view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE && EinkRegionDriver.waitingForPreviousFrame) {
                pending = provider
                view.postDelayed(this, 16L)
            } else if (token != 0L) {
                view.removeCallbacks(expireTask)
                whiteSubmitted = false
                deadline = SystemClock.uptimeMillis() + 1200L
                view.post(pollTask)
            } else {
                view.removeCallbacks(expireTask)
                Log.i("MosaicEinkCleanup", "skip owner=$owner reason=empty-hidden-or-driver-guard")
                detachObserver()
            }
        }
    }
    private val beforeDraw = ViewTreeObserver.OnPreDrawListener {
        if (token != 0L) finish("before-new-frame")
        else if (pending != null) {
            // Final tiles/chrome may arrive in several frames. Rebuild exclusions
            // after the last frame, from the current scene.
            view.removeCallbacks(startTask)
            view.postDelayed(startTask, 16L)
        }
        true
    }
    private val focusChanged = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (!focused) cancel("focus-lost")
    }

    init {
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { cancel("detached") }
        })
    }

    fun schedule(region: () -> Region) {
        cancel("replace")
        if (!view.isAttachedToWindow || !view.isShown) return
        pending = region
        Log.i("MosaicEinkCleanup", "waiting owner=$owner")
        observer = view.viewTreeObserver.also {
            it.addOnPreDrawListener(beforeDraw)
            it.addOnWindowFocusChangeListener(focusChanged)
        }
        view.postDelayed(startTask, 16L)
        view.postDelayed(expireTask, 2000L)
    }

    fun cancel(reason: String) {
        if (pending != null) Log.i("MosaicEinkCleanup", "cancel owner=$owner reason=$reason phase=waiting")
        revision++
        pending = null
        view.removeCallbacks(startTask)
        view.removeCallbacks(expireTask)
        finish(reason)
    }

    private fun poll() {
        if (token == 0L) return
        val checkStarted = SystemClock.uptimeMillis()
        val busy = EinkRegionDriver.batchBusy(token)
        val checkMs = SystemClock.uptimeMillis() - checkStarted
        if (busy > 0 && SystemClock.uptimeMillis() < deadline) {
            view.postDelayed(pollTask, 4L)
            return
        }
        if (!whiteSubmitted) {
            Log.i("MosaicEinkCleanup", "phase owner=$owner blackBusy=$busy checkMs=$checkMs deadline=${SystemClock.uptimeMillis() >= deadline}")
            EinkRegionDriver.finishBlackBatch(token, "$owner/black-finished")
            whiteSubmitted = true
            deadline = SystemClock.uptimeMillis() + 800L
            view.postDelayed(pollTask, 4L)
        } else finish(if (busy == 0) "white-idle" else "white-deadline-or-unavailable")
    }

    private fun finish(reason: String) {
        view.removeCallbacks(pollTask)
        detachObserver()
        val active = token
        token = 0L
        if (active != 0L) {
            // Input and redraw restore source white before handing control back.
            if (!whiteSubmitted) EinkRegionDriver.finishBlackBatch(active, "$owner/$reason")
            val result = EinkRegionDriver.completeBatch(active, "$owner/$reason")
            whiteSubmitted = false
            Log.i("MosaicEinkCleanup", "released owner=$owner reason=$reason")
            val completedRevision = revision
            view.post {
                if (revision == completedRevision) {
                    if (result.contains("whiteRc=-") || result.startsWith("error:")) view.invalidate()
                    onFinished(result)
                }
            }
        }
    }

    private fun detachObserver() {
        observer?.takeIf { it.isAlive }?.let {
            it.removeOnPreDrawListener(beforeDraw)
            it.removeOnWindowFocusChangeListener(focusChanged)
        }
        observer = null
    }
}
