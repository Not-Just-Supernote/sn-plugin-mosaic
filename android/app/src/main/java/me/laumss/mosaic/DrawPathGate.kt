package me.laumss.mosaic

import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


object DrawPathGate {
    private const val TAG = "MosaicPenGate"

    
    val writeLock = Any()

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, TAG).apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
    }

    @Volatile private var binder: IBinder? = null
    @Volatile private var appName: String? = null
    @Volatile private var stylusHeld = false
    @Volatile private var rubberActive = false
    @Volatile private var sliderHeld = false
    @Volatile private var postedSeq = 0
    @Volatile private var handledSeq = 0

    
    @Volatile var inkExpected = false
    
    @Volatile var stylusNearBlocks = true
    
    @Volatile var sliderToolSide = 0
    
    @Volatile var onReleased: (() -> Unit)? = null

    fun attach(binder: IBinder, appName: String) {
        this.binder = binder
        this.appName = appName
    }

    fun detach() {
        binder = null
        appName = null
        sliderHeld = false
        inkExpected = false
        onReleased = null
    }

    fun blocked(): Boolean = stylusHeld || rubberActive || sliderHeld || postedSeq != handledSeq

    private fun shouldBlock(): Boolean = blocked() || !inkExpected

    

    
    fun onStylus(pressed: Boolean, near: Boolean) {
        val blocks = pressed && (!near || stylusNearBlocks)
        if (blocks == stylusHeld) return
        stylusHeld = blocks
        if (blocks) blockNow("stylus near=$near")
    }

    fun onRubber(active: Boolean) {
        if (active == rubberActive) return
        rubberActive = active
        if (active) blockNow("rubber")
    }

    
    fun penSwitchPosted(): Int = ++postedSeq

    

    fun onSliderGesture(gesture: String, side: Int) {
        if (side != sliderToolSide) return
        when (gesture) {
            "twoDown" -> if (!sliderHeld) {
                sliderHeld = true
                blockNow("slider-two-down side=$side")
            }
            "twoUp" -> sliderHeld = false
        }
    }

    fun resetInputs() {
        stylusHeld = false
        rubberActive = false
        sliderHeld = false
        handledSeq = postedSeq
    }

    

    fun penSwitchHandled(seq: Int) {
        val was = blocked()
        if (seq - handledSeq > 0) handledSeq = seq
        if (was && !blocked()) onReleased?.invoke()
    }

    
    fun reblock(reason: String, delays: LongArray) {
        for (delay in delays) schedule(delay, "$reason+${delay}ms")
    }

    fun describe(): String =
        "stylus=$stylusHeld rubber=$rubberActive slider=$sliderHeld pending=${postedSeq - handledSeq} ink=$inkExpected"

    private fun blockNow(reason: String) = schedule(0L, reason)

    private fun schedule(delayMs: Long, reason: String) {
        if (binder == null) return
        val task = Runnable {
            val b = binder ?: return@Runnable
            val app = appName ?: return@Runnable
            if (!shouldBlock()) return@Runnable
            try {
                synchronized(writeLock) {
                    if (!shouldBlock()) return@Runnable
                    DrawPathClient.disableAll(b, app)
                }
                Log.i(TAG, "blocked reason=$reason ${describe()}")
            } catch (error: Throwable) {
                Log.w(TAG, "block failed reason=$reason", error)
            }
        }
        if (delayMs <= 0L) executor.execute(task) else executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    }
}
