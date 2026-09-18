package me.laumss.mosaic

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout


class InkToolbar(context: android.content.Context) : LinearLayout(context) {
    var onPenStyle: (PenStyle, Float) -> Unit = { _, _ -> }
    
    var onTool: (String) -> Unit = {}
    var onUndo: () -> Unit = {}
    var onRedo: () -> Unit = {}
    var onToggleTouch: () -> Unit = {}
    var onMore: () -> Unit = {}
    
    var onSelectBelow: () -> Unit = {}
    
    var onShape: (cellLeft: Int, cellWidth: Int) -> Unit = { _, _ -> }
    
    var onNibWidthRequest: (cellLeft: Int, cellWidth: Int) -> Unit = { _, _ -> }

    private val widths = PenPopup.WIDTHS

    companion object {
        
        val NIBS = listOf(PenStyle.PEN, PenStyle.PENCIL, PenStyle.FIXED)
        
        const val BASE_CELL_W_DP = 96f
        const val BASE_CELL_H_DP = 104f
        const val TOOLBAR_SIZE_SCALE = 0.8f
        
        const val TOOLBAR_BUTTON_SCALE = 0.9f
        const val CELL_W_DP = BASE_CELL_W_DP * TOOLBAR_SIZE_SCALE
        const val CELL_H_DP = BASE_CELL_H_DP * TOOLBAR_SIZE_SCALE
        
        const val GLYPH_FILL_OF_CELL = 0.618f

        fun glyphSizeInCell(viewWidth: Int, viewHeight: Int): Float =
            minOf(viewWidth, viewHeight) * GLYPH_FILL_OF_CELL

        const val ICON_STROKE_SCALE = 0.6f

        fun iconStrokePx(density: Float): Float = 4.4f * density * ICON_STROKE_SCALE
    }

    private val styleCells = LinkedHashMap<PenStyle, IconCell>()
    private val toolCells = LinkedHashMap<String, IconCell>()
    private lateinit var touchCell: IconCell
    private lateinit var moreCell: IconCell
    private lateinit var selectBelowCell: IconCell
    private lateinit var shapeCell: IconCell
    private var selectedStyle = PenStyle.PEN
    
    private val widthIndexByStyle = HashMap<PenStyle, Int>().apply { NIBS.forEach { put(it, PenPopup.DEFAULT_INDEX) } }
    private var touchEnabled = false
    private var selectBelowActive = false
    
    private var activeTool: String? = null
    private val density get() = resources.displayMetrics.density

    
    private val selectedWidthIndex: Int get() = widthIndexByStyle[selectedStyle] ?: PenPopup.DEFAULT_INDEX
    val selectedWidth: Float get() = widths[selectedWidthIndex]
    val widthIndex: Int get() = selectedWidthIndex

    init {
        orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE)
        
        moreCell = IconCell(context, { c,s,p -> ToolIcons.more(c,s,p) }, invertOnPress = false) { onMore() }
        add(moreCell)
        selectBelowCell = IconCell(context, { c,s,p -> ToolIcons.marker(c,s,p) }, invertOnPress = false, invertOnActive = false) { onSelectBelow() }
        selectBelowCell.visibility = View.GONE
        add(selectBelowCell)
        NIBS.forEach { style ->
            
            val cell = IconCell(context, { c,s,p -> when (style) { PenStyle.BRUSH -> ToolIcons.brush(c,s,p); PenStyle.FIXED -> ToolIcons.tech(c,s,p); PenStyle.PENCIL -> ToolIcons.pencil(c,s,p); else -> ToolIcons.pen(c,s,p) } }, nibBadge = { if (activeTool == null && selectedStyle == style) PenPopup.LABELS[widthIndexByStyle[style] ?: PenPopup.DEFAULT_INDEX] else "" }) {
                
                if (activeTool == null && selectedStyle == style) {
                    styleCells[style]?.let { onNibWidthRequest(it.left, it.width) }
                    return@IconCell
                }
                selectedStyle = style
                returnToWrite()
                refreshSelection()
                onPenStyle(style, widths[selectedWidthIndex])
            }
            styleCells[style] = cell
            add(cell)
        }
        toolCells["eraser"] = IconCell(context, { c,s,p -> ToolIcons.eraseStroke(c,s,p) }) { onTool("eraser") }
        toolCells["lasso"] = IconCell(context, { c,s,p -> ToolIcons.lasso(c,s,p) }) { onTool("lasso") }
        toolCells.values.forEach { add(it) }
        
        
        val shape = IconCell(context, { c,s,p -> ToolIcons.shape(c,s,p) }) { onShape(shapeCell.left, shapeCell.width) }
        shapeCell = shape
        toolCells["shape"] = shape
        add(shape)
        add(IconCell(context, { c,s,p -> ToolIcons.undo(c,s,p) }) { onUndo() })
        add(IconCell(context, { c,s,p -> ToolIcons.redo(c,s,p) }) { onRedo() })
        touchCell = IconCell(context, { c,s,p -> ToolIcons.touch(c,s,p,touchEnabled) }) { onToggleTouch() }
        add(touchCell)
        refreshSelection()
    }

    
    fun setTool(eraser: Boolean, lasso: Boolean, shape: Boolean = false) {
        activeTool = when {
            eraser -> "eraser"
            lasso -> "lasso"
            shape -> "shape"
            else -> null
        }
        refreshSelection()
    }

    fun setTouchEnabled(enabled: Boolean) {
        if (touchEnabled == enabled) return
        touchEnabled = enabled
        touchCell.invalidate()
    }

    
    fun setWidthIndex(index: Int) {
        if (index !in widths.indices || index == selectedWidthIndex) return
        widthIndexByStyle[selectedStyle] = index
        returnToWrite()
        refreshSelection()
        
        styleCells.values.forEach { it.invalidate() }
        onPenStyle(selectedStyle, widths[index])
    }

    
    fun setMoreVisible(visible: Boolean) {
        moreCell.visibility = if (visible) View.VISIBLE else View.GONE
        selectBelowCell.visibility = if (visible) View.GONE else View.VISIBLE
    }

    
    fun setSelectBelowActive(active: Boolean) {
        if (selectBelowActive == active) return
        selectBelowActive = active
        selectBelowCell.active = active
        refreshSelection()
    }

    
    fun moreCellRightPx(): Int = when {
        moreCell.visibility == View.VISIBLE -> moreCell.right
        selectBelowCell.visibility == View.VISIBLE -> selectBelowCell.right
        else -> width
    }

    
    fun lassoCellRightPx(): Int = toolCells["lasso"]?.right ?: moreCellRightPx()

    
    private fun returnToWrite() {
        if (activeTool == null) return
        onTool("write")
    }

    private fun refreshSelection() {
        
        val write = activeTool == null && !selectBelowActive
        
        styleCells.forEach { (style, cell) -> cell.active = write && style == selectedStyle; cell.invalidate() }
        toolCells.forEach { (tool, cell) -> cell.active = !selectBelowActive && tool == activeTool }
    }

    
    private fun add(cell: IconCell) {
        val w = (CELL_W_DP * TOOLBAR_BUTTON_SCALE * density).toInt()
        val h = (CELL_H_DP * TOOLBAR_BUTTON_SCALE * density).toInt()
        addView(cell, LayoutParams(w, h).apply { gravity = android.view.Gravity.CENTER_VERTICAL })
    }

    private class IconCell(
        context: android.content.Context,
        private val glyph: (Canvas,Float,Paint)->Unit,
        
        private val nibBadge: (() -> String)? = null,
        
        private val invertOnPress: Boolean = true,
        
        private val invertOnActive: Boolean = true,
        private val click: ()->Unit,
    ): View(context) {
        
        var active = false
            set(value) { if (field != value) { field = value; invalidate() } }
        private var down=false
        
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.STROKE
            strokeWidth=iconStrokePx(resources.displayMetrics.density)
            strokeCap=Paint.Cap.ROUND
            strokeJoin=Paint.Join.ROUND
        }
        private val badgeText=Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText=true; textAlign=Paint.Align.RIGHT }
        private val badgeTri=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas){
            
            
            val inverted = (active && invertOnActive) || (down && invertOnPress)
            c.drawColor(if(inverted) Color.BLACK else Color.WHITE)
            val fg = if(inverted) Color.WHITE else Color.BLACK
            paint.color=fg
            
            val s = glyphSizeInCell(width, height)
            c.save(); c.translate((width-s)/2f,(height-s)/2f); glyph(c,s,paint); c.restore()
            nibBadge?.let { drawNibBadge(c, it(), fg) }
        }
        
        private fun drawNibBadge(c: Canvas, label: String, fg: Int){
            val d = resources.displayMetrics.density
            val pad = 2f * d
            
            val tri = minOf(width, height) * 0.14f
            badgeTri.color = fg
            c.save()
            c.translate(width - pad - tri, height - pad - tri)
            ToolIcons.caret(c, tri, badgeTri)
            c.restore()
            if (label.isEmpty()) return
            
            badgeText.color = fg
            badgeText.textSize = minOf(width, height) * 0.13f
            
            val tx = width - pad - tri - 3f * d
            val ty = height - pad - badgeText.descent()
            c.drawText(label, tx, ty, badgeText)
        }
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean { when(e.actionMasked){android.view.MotionEvent.ACTION_DOWN->{down=true;invalidate();return true};android.view.MotionEvent.ACTION_UP->{down=false;invalidate();if(e.x>=0&&e.y>=0&&e.x<width&&e.y<height)click();return true};android.view.MotionEvent.ACTION_CANCEL->{down=false;invalidate();return true}};return true }
    }
}
