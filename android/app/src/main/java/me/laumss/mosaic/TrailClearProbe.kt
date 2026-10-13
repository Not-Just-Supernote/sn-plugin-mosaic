package me.laumss.mosaic

import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock

/** 卡片手势丢弃的那一笔系统笔迹，坐标是 MosaicBoardView 里的像素。 */
class DiscardedTrail(
    val path: Path,
    val widthPx: Float,
    val color: Int,
    val bounds: RectF,
    val pointCount: Int,
)

/**
 * 实验：画框、拉出新卡片、连线、方括号扩卡这些手势抬笔后，系统笔迹用哪种办法能立刻清掉。
 * 每次手势按顺序换下一种，打开白板后从第 1 种开始。日志前缀 [TrailProbe]。
 * 除了 SYNC_NOW，其余办法都把背景同步推迟到清除动作之后，免得系统服务把还没清掉的轨迹记进背景。
 */
enum class TrailClearStrategy(val label: String) {
    /** 在轨迹上用同色重画一遍，停 0.4 秒再擦掉，让面板把这些像素刷回去。 */
    OVERDRAW("overdraw-400ms"),
    /** 抬笔 0.6 秒后全屏刷新，看系统服务是不是抬笔后又补画了一次。 */
    FULL_LATE("full-refresh-600ms"),
    /** 抬笔立刻让系统笔迹服务同步背景。 */
    SYNC_NOW("sync-now"),
    /** 抬笔立刻关掉再打开系统笔迹服务。 */
    REARM_NOW("rearm-now"),
    /** 新卡片画好就全屏刷新，和之前的做法一样，只是不先同步背景。 */
    FULL_SETTLE("full-refresh-at-settle"),
    /** 只重画内容层，作对照。 */
    REDRAW_ONLY("redraw-only"),
}

class TrailProbeRun(val number: Int, val strategy: TrailClearStrategy, val gesture: String) {
    private val startedAt = SystemClock.uptimeMillis()
    var settledLogged = false
    var hoverLogged = false
    var penDownLogged = false

    fun elapsedMs(): Long = SystemClock.uptimeMillis() - startedAt

    /** 只记录手势后这段时间里的事，之后的同步、悬停和这次实验无关。 */
    fun recent(): Boolean = elapsedMs() < RECENT_MS

    fun describe(): String =
        "try=$number strategy=${strategy.ordinal + 1}/${TrailClearStrategy.values().size} ${strategy.label}"

    companion object {
        private const val RECENT_MS = 10_000L
    }
}
