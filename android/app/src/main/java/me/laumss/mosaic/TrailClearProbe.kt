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
 * 实验第二轮：画框、拉出新卡片、连线、方括号扩卡这些手势抬笔后，系统笔迹怎样才能清掉。
 * 第一轮里只有一次清掉了：那次整屏刷新在抬笔后 144ms（其余都在 65 到 131ms，没清掉），
 * 而且紧跟在一次"关掉再打开系统笔迹服务"之后。这一轮分开验证时间和服务状态。
 * 每次手势按顺序换下一种，打开白板后从第 1 种开始。日志前缀 [TrailProbe]。
 * 背景同步一律等清除动作做完后再发。
 */
enum class TrailClearStrategy(val label: String, val refreshDelayMs: Long) {
    /** 抬笔 0.3 秒后整屏刷新。 */
    FULL_300("full-refresh+300ms", 300L),
    /** 抬笔 0.6 秒后整屏刷新。 */
    FULL_600("full-refresh+600ms", 600L),
    /** 抬笔 1 秒后整屏刷新。 */
    FULL_1000("full-refresh+1000ms", 1000L),
    /** 抬笔立刻关掉再打开系统笔迹服务，新卡片画好后整屏刷新。 */
    REARM_THEN_FULL("rearm+full-refresh-at-settle", 0L),
    /** 不刷新：在轨迹上用同色重画一遍，停 0.4 秒再擦掉。 */
    OVERDRAW("overdraw-400ms", -1L),
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
