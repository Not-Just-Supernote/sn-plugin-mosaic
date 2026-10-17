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
    // motion：画面跟着手移动的手势，选择运动配方；Regal 由宿主管理。
    // 卡片在交互期间保留原来的底色。
    enum class Reason(
        val einkMode: Int?, val flat: Boolean, val suspendFog: Boolean,
        val motion: Boolean = false,
    ) {
        PAN_ZOOM(MosaicEinkRefreshModule.MODE_A2, flat = true, suspendFog = true, motion = true),

        // Notipal 浮窗在白板上被拖动：与双指平移相同的显示，被浮窗让出的区域按 A2 快刷。
        INKLING_DRAG(MosaicEinkRefreshModule.MODE_A2, flat = true, suspendFog = true),

        // 拖动、改尺寸和旋转共用 A2 与运动配方，预览保留字迹。
        TRANSFORM(MosaicEinkRefreshModule.MODE_A2, flat = false, suspendFog = false, motion = true),

        TOOL(MosaicEinkRefreshModule.MODE_DEFAULT, flat = false, suspendFog = false),

        LASSO(MosaicEinkRefreshModule.MODE_DEFAULT, flat = false, suspendFog = false),

        SCREEN_TOOL(MosaicEinkRefreshModule.MODE_DEFAULT, flat = false, suspendFog = true),

        PEN_BUTTON(null, flat = false, suspendFog = true),

        SLIDER(null, flat = false, suspendFog = true),
    }

    data class State(
        val flatCards: Boolean,
        val hideTemplate: Boolean,
        val suspendFog: Boolean,
        val einkMode: Int?,
        val movingCards: Boolean = false,
    ) {
        companion object { val IDLE = State(false, false, false, null) }
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
            if (eink == null || MosaicEinkRefreshModule.priority(m) > MosaicEinkRefreshModule.priority(eink)) eink = m
        }
        val moving = isBoardSurface() && (active + lingering).any { it.motion || it == Reason.INKLING_DRAG }
        return State(flat, flat && isBoardSurface(), fog, eink, moving)
    }

    private fun commit(why: String, force: Boolean = false) {
        val next = derive()
        val prev = applied
        if (next == prev && !force) return
        applied = next
        val looksChanged = force || next.flatCards != prev.flatCards ||
            next.hideTemplate != prev.hideTemplate || next.movingCards != prev.movingCards
        val einkChanged = force || next.einkMode != prev.einkMode

        val mode = next.einkMode
        if (einkChanged || next.movingCards != prev.movingCards) resetGen++
        val leavingMotion = prev.movingCards && !next.movingCards
        // Release A2 while the BW frame is still displayed; the next static
        // DD/E0 frame is then drawn with the host's restored mode.
        if (leavingMotion) {
            if (mode == null) resetEink() else MosaicEinkRefreshModule.applyNative(mode, einkOwner)
        }
        if (looksChanged) content.setGesturePresentation(next.flatCards, next.hideTemplate, next.movingCards)
        if (einkChanged && !leavingMotion) {
            if (mode != null && next.movingCards && !prev.movingCards) {
                // First present the BW recipe under the host mode, then enter A2.
                val gen = resetGen
                content.runWhenGestureFrameDrawn(Runnable {
                    if (gen == resetGen && applied.einkMode == mode && applied.movingCards) {
                        MosaicEinkRefreshModule.applyNative(mode, einkOwner)
                        Log.i(TAG, "A2 after moving recipe ready")
                    }
                })
            } else if (mode == null) resetEink()
            else MosaicEinkRefreshModule.applyNative(mode, einkOwner)
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
    }
}
