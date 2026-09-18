package me.laumss.mosaic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor


enum class TemplateStyle { NONE, DOTS, LINES, CROSS }


enum class TemplateSpacing(val pitch: Float) {
    NARROW(36f),
    MEDIUM(56f),
    WIDE(84f),
}


data class BackgroundTemplate(
    val style: TemplateStyle = TemplateStyle.NONE,
    val spacing: TemplateSpacing = TemplateSpacing.MEDIUM,
) {
    val isBlank: Boolean get() = style == TemplateStyle.NONE

    fun encode(): String = "${style.name}:${spacing.name}"

    companion object {
        val BLANK = BackgroundTemplate()

        fun decode(raw: String?): BackgroundTemplate {
            if (raw.isNullOrBlank()) return BLANK
            val parts = raw.split(":")
            val style = parts.getOrNull(0)?.let { s -> runCatching { TemplateStyle.valueOf(s) }.getOrNull() } ?: return BLANK
            val spacing = parts.getOrNull(1)?.let { s -> runCatching { TemplateSpacing.valueOf(s) }.getOrNull() } ?: TemplateSpacing.MEDIUM
            return BackgroundTemplate(style, spacing)
        }
    }
}


object TemplateStore {
    private const val PREFS = "mosaic_templates"
    private const val KEY_BOARD = "board"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun board(context: Context): BackgroundTemplate =
        BackgroundTemplate.decode(prefs(context).getString(KEY_BOARD, null))

    fun setBoard(context: Context, template: BackgroundTemplate) {
        prefs(context).edit().putString(KEY_BOARD, template.encode()).apply()
    }

    fun note(context: Context, ref: String): BackgroundTemplate =
        if (ref.isBlank()) BackgroundTemplate.BLANK
        else BackgroundTemplate.decode(prefs(context).getString("note:$ref", null))

    fun setNote(context: Context, ref: String, template: BackgroundTemplate) {
        if (ref.isBlank()) return
        prefs(context).edit().putString("note:$ref", template.encode()).apply()
    }
}


object TemplatePaper {
    private val linePaint = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; style = Paint.Style.FILL }
    private val crossPaint = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE }

    private const val LINE_W = 0.9f
    private const val DOT_R = 1.5f
    private const val CROSS_W = 0.9f

    fun draw(canvas: Canvas, world: RectF, template: BackgroundTemplate, pageWidth: Float) {
        if (template.style == TemplateStyle.NONE) return
        val pitch = template.spacing.pitch
        if (pitch <= 0f) return
        val bounded = pageWidth > 0f

        
        
        val rowLeft = world.left
        val rowRight = world.right
        if (rowRight <= rowLeft) return

        val firstRow = floor(world.top / pitch) * pitch

        when (template.style) {
            TemplateStyle.LINES -> {
                linePaint.strokeWidth = LINE_W
                var y = firstRow
                while (y <= world.bottom) {
                    canvas.drawLine(rowLeft, y, rowRight, y, linePaint)
                    y += pitch
                }
            }
            TemplateStyle.DOTS -> {
                var y = firstRow
                while (y <= world.bottom) {
                    forEachCol(world, pitch, bounded, pageWidth) { x -> canvas.drawCircle(x, y, DOT_R, dotPaint) }
                    y += pitch
                }
            }
            TemplateStyle.CROSS -> {
                crossPaint.strokeWidth = CROSS_W
                val half = pitch * 0.16f
                var y = firstRow
                while (y <= world.bottom) {
                    forEachCol(world, pitch, bounded, pageWidth) { x ->
                        canvas.drawLine(x - half, y, x + half, y, crossPaint)
                        canvas.drawLine(x, y - half, x, y + half, crossPaint)
                    }
                    y += pitch
                }
            }
            TemplateStyle.NONE -> {}
        }
    }

    private inline fun forEachCol(world: RectF, pitch: Float, bounded: Boolean, pageWidth: Float, action: (Float) -> Unit) {
        
        
        val phase = if (bounded) (pageWidth - (pageWidth / pitch).toInt() * pitch) / 2f else 0f
        val start = floor((world.left - phase) / pitch).toInt()
        val end = ceil((world.right - phase) / pitch).toInt()
        for (i in start..end) {
            val x = phase + i * pitch
            if (x >= world.left && x <= world.right) action(x)
        }
    }
}
