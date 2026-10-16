package me.laumss.mosaic

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Six seconds per candidate, with manual controls for comparing the same gesture. */
class CardGrayTrialView(context: Context) : LinearLayout(context) {
    companion object {
        // Ten spatial mixtures strictly inside the old #DEDEDE–#E0E0E0 interval.
        val LEVELS: List<Int> = List(10) { ((it + 1) * 64f / 11f).toInt() }
        private const val INTERVAL_MS = 6_000L
    }

    var onColor: ((Int, Int) -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    private var index = 0
    private var running = false
    private var deadline = 0L
    private var remaining = INTERVAL_MS
    private var finished = false
    private val readout = TextView(context).apply {
        textSize = 16f
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
    }
    private val toggle = button("暂停") { toggleRunning() }
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            if (now >= deadline) {
                if (index == LEVELS.lastIndex) {
                    running = false
                    finished = true
                    remaining = 0L
                    render()
                    return
                }
                index++
                remaining = INTERVAL_MS
                deadline = now + remaining
                applyColor()
            }
            render()
            postDelayed(this, minOf(1_000L, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)))
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1), Color.BLACK) }
        addView(readout, LayoutParams(LayoutParams.MATCH_PARENT, dp(36)))
        val controls = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (control in listOf(button("上一色") { step(-1) }, toggle,
            button("下一色") { step(1) }, button("恢复默认") { close() })) {
            controls.addView(control, LayoutParams(0, dp(44), 1f))
        }
        addView(controls)
        visibility = View.GONE
    }

    fun start() {
        removeCallbacks(ticker)
        index = 0
        finished = false
        remaining = INTERVAL_MS
        running = true
        deadline = SystemClock.uptimeMillis() + remaining
        visibility = View.VISIBLE
        applyColor()
        render()
        postDelayed(ticker, 1_000L)
    }

    private fun toggleRunning() {
        removeCallbacks(ticker)
        if (running) {
            remaining = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
            running = false
        } else {
            if (finished) { start(); return }
            running = true
            deadline = SystemClock.uptimeMillis() + remaining
            post(ticker)
        }
        render()
    }

    private fun step(delta: Int) {
        removeCallbacks(ticker)
        running = false
        finished = false
        index = (index + delta).coerceIn(0, LEVELS.lastIndex)
        remaining = INTERVAL_MS
        applyColor()
        render()
    }

    private fun applyColor() {
        onColor?.invoke(Color.rgb(222, 222, 222), LEVELS[index])
    }

    private fun render() {
        val brightPercent = LEVELS[index] * 100 / 64
        val seconds = ((if (running) deadline - SystemClock.uptimeMillis() else remaining) + 999L)
            .coerceAtLeast(0L) / 1_000L
        val state = when { finished -> "已完成"; running -> "${seconds}秒"; else -> "已暂停" }
        readout.text = "细分 ${index + 1}/${LEVELS.size}  E0占${brightPercent}%  $state"
        toggle.text = when { finished -> "重播"; running -> "暂停"; else -> "继续" }
    }

    fun close() {
        running = false
        removeCallbacks(ticker)
        visibility = View.GONE
        onColor?.invoke(BoardContentView.CARD_FILL_COLOR, BoardContentView.CARD_GRAY_BRIGHT_PIXELS)
        onClosed?.invoke()
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    private fun button(label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
