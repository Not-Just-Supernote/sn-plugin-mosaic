package me.laumss.mosaic

import android.os.Handler
import android.util.Log


class BoardPresentation(
    private val handler: Handler,
    private val content: BoardContentView,
    
    private val isBoardSurface: () -> Boolean,
    private val einkOwner: String,
    private val settleMs: Long,
) {
    
    enum class Reason(val einkMode: Int?, val flat: Boolean, val suspendFog: Boolean) {
        
        
        PAN_ZOOM(MosaicEinkRefreshModule.MODE_A2, flat = true, suspendFog = true),
        
        TRANSFORM(MosaicEinkRefreshModule.MODE_DEFAULT, flat = true, suspendFog = false),
        
        TOOL(MosaicEinkRefreshModule.MODE_DEFAULT, flat = false, suspendFog = false),
        
        LASSO(MosaicEinkRefreshModule.MODE_DEFAULT, flat = false, suspendFog = false),
        
        SCREEN_TOOL(MosaicEinkRefreshModule.MODE_DEFAULT, flat = false, suspendFog = true),
        
        PEN_BUTTON(null, flat = false, suspendFog = true),
        
        SLIDER(null, flat = false, suspendFog = true),
    }

    data class State(
        val flatCards: Boolean,
        
        val darkCards: Boolean,
        val hideTemplate: Boolean,
        val suspendFog: Boolean,
        val einkMode: Int?,
    ) {
        companion object { val IDLE = State(false, false, false, false, null) }
    }

    private val active = LinkedHashSet<Reason>()
    private val lingering = LinkedHashSet<Reason>()
    private var applied = State.IDLE
    private val settleTask = Runnable { settle() }
    
    private var resetGen = 0

    
    var onStateChanged: ((State) -> Unit)? = null

    val state: State get() = applied

    
    val isFlatActive: Boolean get() = applied.flatCards

    fun acquire(reason: Reason) {
        handler.removeCallbacks(settleTask)
        lingering.remove(reason)
        if (active.add(reason)) commit("acquire:$reason")
        if (lingering.isNotEmpty()) handler.postDelayed(settleTask, settleMs)
    }

    fun release(reason: Reason, linger: Boolean = true) {
        val wasActive = active.remove(reason)
        if (linger) {
            
            if (!wasActive) return
            lingering.add(reason)
            handler.removeCallbacks(settleTask)
            handler.postDelayed(settleTask, settleMs)
        } else if (wasActive || lingering.remove(reason)) {
            commit("release:$reason")
        }
    }

    
    fun set(reason: Reason, on: Boolean, linger: Boolean = true) {
        if (on) { if (reason !in active) acquire(reason) } else release(reason, linger)
    }

    
    fun refresh(why: String) = commit("refresh:$why")

    
    fun reset(why: String) {
        handler.removeCallbacks(settleTask)
        active.clear()
        lingering.clear()
        commit("reset:$why", force = true)
    }

    private fun settle() {
        if (lingering.isEmpty()) return
        lingering.clear()
        commit("settle")
    }

    private fun derive(): State {
        var flat = false
        var fog = false
        var eink: Int? = null
        for (r in active + lingering) {
            flat = flat || r.flat
            fog = fog || r.suspendFog
            val m = r.einkMode ?: continue
            if (eink == null || m == MosaicEinkRefreshModule.MODE_A2) eink = m
        }
        val dark = flat || eink == MosaicEinkRefreshModule.MODE_A2
        return State(flat, dark, flat && isBoardSurface(), fog, eink)
    }

    private fun commit(why: String, force: Boolean = false) {
        val next = derive()
        val prev = applied
        if (next == prev && !force) return
        applied = next
        val looksChanged = force || next.flatCards != prev.flatCards || next.darkCards != prev.darkCards ||
            next.hideTemplate != prev.hideTemplate
        val einkChanged = force || next.einkMode != prev.einkMode
        
        val mode = next.einkMode
        if (einkChanged && mode != null) MosaicEinkRefreshModule.applyNative(mode, einkOwner)
        val leavingDark = looksChanged && prev.darkCards && !next.darkCards && !force
        if (looksChanged) content.setGesturePresentation(next.flatCards, next.darkCards, next.hideTemplate)
        if (einkChanged) resetGen++
        if (einkChanged && next.einkMode == null) {
            if (leavingDark) {
                
                
                val gen = resetGen
                val resetOnce = Runnable {
                    if (gen != resetGen || applied.einkMode != null) return@Runnable
                    resetGen++
                    resetEink()
                    Log.i(TAG, "eink reset after looks restored")
                }
                content.runWhenSettled(resetOnce)
                handler.postDelayed(resetOnce, RESET_DEFER_MAX_MS)
            } else {
                resetEink()
            }
        }
        if (force || next.suspendFog != prev.suspendFog) content.setSuspendTranslucent(next.suspendFog)
        if (looksChanged) onStateChanged?.invoke(next)
        Log.i(TAG, "$why active=$active lingering=$lingering -> $next")
    }

    
    private fun resetEink() {
        MosaicEinkRefreshModule.resetNative(einkOwner)
    }

    private companion object {
        const val TAG = "MosaicPresentation"
        
        const val RESET_DEFER_MAX_MS = 400L
    }
}
