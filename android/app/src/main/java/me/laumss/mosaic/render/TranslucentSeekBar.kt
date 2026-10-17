package me.laumss.mosaic.render

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.SeekBar
import kotlin.math.roundToInt

/** Hardcopy HcWidgets slider graphics, sized for Mosaic's existing menu row. */
internal class TranslucentSeekBar(context: Context) : SeekBar(context) {
    private var tracking = false
    private val refreshOwner = "translucent-slider-${System.identityHashCode(this)}"

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    init {
        splitTrack = false
        progressTintList = null
        progressBackgroundTintList = null
        thumbTintList = null
        background = null
        val track = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(dp(2f / 1.875f), Color.BLACK)
            cornerRadius = dp(8f / 1.875f).toFloat()
        }
        progressDrawable = LayerDrawable(arrayOf(track)).apply {
            setId(0, android.R.id.background)
            setLayerHeight(0, dp(9f))
            setLayerGravity(0, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
        }
        thumb = thumbDrawable(false)
    }

    private fun thumbDrawable(changing: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (changing) Color.BLACK else Color.WHITE)
        if (!changing) setStroke(dp(2f / 1.875f), Color.BLACK)
        val size = dp(26f)
        setSize(size, size)
    }

    fun setTracking(changing: Boolean) {
        if (tracking == changing) return
        tracking = changing
        if (changing) {
            // Hardcopy's slider mode uses the same native value (4).
            MosaicEinkRefreshModule.applyNative(MosaicEinkRefreshModule.MODE_A2, refreshOwner)
        } else {
            MosaicEinkRefreshModule.resetNative(refreshOwner)
        }
        thumb = thumbDrawable(changing)
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) setTracking(false)
    }

    override fun onDetachedFromWindow() {
        setTracking(false)
        super.onDetachedFromWindow()
    }
}
