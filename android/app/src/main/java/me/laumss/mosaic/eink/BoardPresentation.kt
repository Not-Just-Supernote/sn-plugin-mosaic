package me.laumss.mosaic.eink

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
import android.os.Handler
import android.util.Log

class BoardPresentation internal constructor(
    private val handler: Handler,
    private val content: BoardContentView,

    private val isBoardSurface: () -> Boolean,
    private val einkOwner: String,
    private val settleMs: Long,
    private val lassoAuto: LassoAutoRefresh,
) {
    // motion：画面跟着手移动的手势，选择运动配方；最终帧的 UI Regal 由 CardTrailCleanup 请求开启。
    // 卡片在交互期间保留原来的底色。
    enum class Reason(
        val einkMode: Int?, val flat: Boolean, val suspendFog: Boolean,
        val motion: Boolean = false,
    ) {
        PAN_ZOOM(MosaicEinkRefreshModule.MODE_A2, flat = true, suspendFog = true, motion = true),

        // Notipal 浮窗在白板上被拖动：与双指平移相同的显示，被浮窗让出的区域按 A2 快刷。
        INKLING_DRAG(MosaicEinkRefreshModule.MODE_A2, flat = true, suspendFog = true),

        // 套索下的卡片拖动、改尺寸：不设视图刷新模式，沿用套索的 FullUiAuto，
        // 按下开 Regal、开始移动关 Regal（LassoAutoRefresh）。松手立即恢复静态外观。
        CARD_ADJUST(null, flat = false, suspendFog = false, motion = true),

        // 旋转和纯笔迹变换：同上，松手后延迟恢复。
        TRANSFORM(null, flat = false, suspendFog = false, motion = true),

        TOOL(MosaicEinkRefreshModule.MODE_DEFAULT, flat = false, suspendFog = false),

        // 套索工具或套索选区在用：整段开 FullUiAuto（见 LassoAutoRefresh），不设视图刷新模式。
        LASSO(null, flat = false, suspendFog = false),

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
        // 卡片用黑白网点配方。运动时开启；运动结束后若仍处于 A2，继续保持，
        // 直到 A2 释放后才换回静态灰色，不在 A2 下画静态灰色（A2 只有黑白，灰色会被画错）。
        val bwCards: Boolean = movingCards,
    ) {
        companion object { val IDLE = State(false, false, false, null) }
    }

    private val active = LinkedHashSet<Reason>()
    private val lingering = LinkedHashSet<Reason>()
    private var applied = State.IDLE
    private val settleTask = Runnable { settle() }

    private var resetGen = 0

    var onStateChanged: ((State) -> Unit)? = null
    var onA2MotionSettled: (() -> Unit)? = null

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
        lassoAuto.release("reset:$why")
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
        val bw = moving || (applied.bwCards && eink == MosaicEinkRefreshModule.MODE_A2 && isBoardSurface())
        return State(flat, flat && isBoardSurface(), fog, eink, moving, bw)
    }

    private fun commit(why: String, force: Boolean = false) {
        // LASSO sets no view mode, so holding it may leave the derived state unchanged.
        lassoAuto.hold(Reason.LASSO in active, why)
        val next = derive()
        val prev = applied
        if (next == prev && !force) return
        applied = next
        val looksChanged = force || next.flatCards != prev.flatCards ||
            next.hideTemplate != prev.hideTemplate || next.bwCards != prev.bwCards
        val einkChanged = force || next.einkMode != prev.einkMode

        val mode = next.einkMode
        if (einkChanged || next.bwCards != prev.bwCards) resetGen++
        val leavingBw = prev.bwCards && !next.bwCards
        // Release A2 while the BW frame is still displayed; the next static
        // gray frame is then drawn with the restored mode. Ending a card drag
        // while lasso still holds A2 keeps the BW recipe (see bwCards).
        if (leavingBw) {
            if (mode == null) resetEink() else MosaicEinkRefreshModule.applyNative(mode, einkOwner)
        }
        if (prev.einkMode == MosaicEinkRefreshModule.MODE_A2 && leavingBw) onA2MotionSettled?.invoke()
        if (looksChanged) content.setGesturePresentation(next.flatCards, next.hideTemplate, next.bwCards)
        if (einkChanged && !leavingBw) {
            if (mode != null && next.bwCards && !prev.bwCards) {
                // First present the BW recipe under the host mode, then enter A2.
                val gen = resetGen
                content.runWhenGestureFrameDrawn(Runnable {
                    if (gen == resetGen && applied.einkMode == mode && applied.bwCards) {
                        MosaicEinkRefreshModule.applyNative(mode, einkOwner)
                        Log.i(TAG, "mode=$mode after moving recipe ready")
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
