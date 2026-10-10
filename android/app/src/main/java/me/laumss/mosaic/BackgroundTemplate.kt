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



object TranslucentStore {
    private const val PREFS = "mosaic_translucent"
    
    private const val KEY_LEVEL = "level_v2"
    const val MIN_LEVEL = 0
    const val MAX_LEVEL = 100
    const val STEP = 5
    const val MIN_SEE_THROUGH = 15f
    const val MAX_SEE_THROUGH = 40f
    
    const val DEFAULT_LEVEL = 15

    fun seeThroughPercent(level: Int): Float =
        MIN_SEE_THROUGH + (MAX_SEE_THROUGH - MIN_SEE_THROUGH) * level.coerceIn(MIN_LEVEL, MAX_LEVEL) / MAX_LEVEL

    fun level(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LEVEL, DEFAULT_LEVEL).coerceIn(MIN_LEVEL, MAX_LEVEL)

    fun setLevel(context: Context, level: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LEVEL, level.coerceIn(MIN_LEVEL, MAX_LEVEL)).apply()
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
    private const val LINE_W = 0.9f
    private const val DOT_R = 1.5f
    private const val CROSS_W = 0.9f

    fun draw(canvas: Canvas, world: RectF, template: BackgroundTemplate, pageWidth: Float) {
        if (template.style == TemplateStyle.NONE) return
        val bounded = pageWidth > 0f
        val k = if (bounded) ScrollingDocument.TEMPLATE_PITCH_SCALE else 1f
        val pitch = template.spacing.pitch * k
        if (pitch <= 0f) return
        val dotR = DOT_R * k
        
        
        
        val linePaint = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = LINE_W * k }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; style = Paint.Style.FILL }
        val crossPaint = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = CROSS_W * k }

        
        
        val rowLeft = world.left
        val rowRight = world.right
        if (rowRight <= rowLeft) return

        // On a page, the first row sits one side margin below the header divider.
        val rowPhase = if (bounded) pageMargin(pageWidth, pitch) else 0f
        val firstRow = rowPhase + floor((world.top - rowPhase) / pitch) * pitch

        when (template.style) {
            TemplateStyle.LINES -> {
                var y = firstRow
                while (y <= world.bottom) {
                    canvas.drawLine(rowLeft, y, rowRight, y, linePaint)
                    y += pitch
                }
            }
            TemplateStyle.DOTS -> {
                var y = firstRow
                while (y <= world.bottom) {
                    forEachCol(world, pitch, bounded, pageWidth) { x -> canvas.drawCircle(x, y, dotR, dotPaint) }
                    y += pitch
                }
            }
            TemplateStyle.CROSS -> {
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

    // Columns are centred on the page with at least half a pitch to each edge.
    private fun pageMargin(pageWidth: Float, pitch: Float): Float {
        val gaps = floor((pageWidth - pitch) / pitch).coerceAtLeast(0f)
        return (pageWidth - gaps * pitch) / 2f
    }

    private inline fun forEachCol(world: RectF, pitch: Float, bounded: Boolean, pageWidth: Float, action: (Float) -> Unit) {
        val phase = if (bounded) pageMargin(pageWidth, pitch) else 0f
        val left = if (bounded) maxOf(world.left, phase) else world.left
        val right = if (bounded) minOf(world.right, pageWidth - phase) else world.right
        val start = floor((left - phase) / pitch).toInt()
        val end = ceil((right - phase) / pitch).toInt()
        for (i in start..end) {
            val x = phase + i * pitch
            if (x >= left - 0.01f && x <= right + 0.01f) action(x)
        }
    }
}
