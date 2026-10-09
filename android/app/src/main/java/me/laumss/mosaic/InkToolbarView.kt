package me.laumss.mosaic

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout


class InkToolbar(context: android.content.Context) : LinearLayout(context) {
    var onPenStyle: (PenStyle, Float) -> Unit = { _, _ -> }
    
    var onMarkerInk: (MarkerInk) -> Unit = {}
    
    var onTool: (String) -> Unit = {}
    var onMore: () -> Unit = {}
    
    var onSelectBelow: () -> Unit = {}
    
    var onShape: (cellLeft: Int, cellWidth: Int) -> Unit = { _, _ -> }
    
    var onNibWidthRequest: (cellLeft: Int, cellWidth: Int) -> Unit = { _, _ -> }

    private fun widthsFor(style: PenStyle): FloatArray =
        when (style) {
            PenStyle.BRUSH -> PenPopup.BRUSH_WIDTHS
            PenStyle.MARKER -> PenPopup.MARKER_WIDTHS
            else -> PenPopup.WIDTHS
        }
    private fun labelsFor(style: PenStyle): Array<String> =
        when (style) {
            PenStyle.BRUSH -> PenPopup.BRUSH_LABELS
            PenStyle.MARKER -> PenPopup.MARKER_LABELS
            else -> PenPopup.LABELS
        }

    companion object {
        
        val NIBS = listOf(PenStyle.PEN, PenStyle.BRUSH, PenStyle.MARKER)
        
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
    private lateinit var moreCell: IconCell
    private lateinit var selectBelowCell: IconCell
    private lateinit var shapeCell: IconCell
    private var selectedStyle = PenStyle.PEN
    
    private val widthIndexByStyle = HashMap<PenStyle, Int>().apply { NIBS.forEach { put(it, PenPopup.defaultIndex(it)) } }
    private var selectBelowActive = false
    
    private var activeTool: String? = null
    private val density get() = resources.displayMetrics.density

    
    fun cancelTransientPresses() {
        styleCells.values.forEach { it.cancelPress() }
        toolCells.values.forEach { it.cancelPress() }
        moreCell.cancelPress()
        selectBelowCell.cancelPress()
        shapeCell.cancelPress()
    }

    
    private val selectedWidthIndex: Int get() = widthIndexByStyle[selectedStyle] ?: PenPopup.DEFAULT_INDEX
    val selectedWidth: Float get() = widthsFor(selectedStyle)[selectedWidthIndex]
    val widthCount: Int get() = widthsFor(selectedStyle).size
    val widthIndex: Int get() = selectedWidthIndex
    
    val markerSelected: Boolean get() = selectedStyle == PenStyle.MARKER
    
    var markerInk: MarkerInk = MarkerInk.BLACK
        private set

    init {
        orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE)
        
        moreCell = IconCell(context, { c,s,p -> ToolIcons.more(c,s,p) }, invertOnPress = false) { onMore() }
        add(moreCell)
        selectBelowCell = IconCell(context, { c,s,p -> ToolIcons.marker(c,s,p) }, invertOnPress = false, invertOnActive = false) { onSelectBelow() }
        selectBelowCell.visibility = View.GONE
        add(selectBelowCell)
        NIBS.forEach { style ->
            
            val cell = IconCell(context, { c,s,p -> when (style) { PenStyle.BRUSH -> ToolIcons.tech(c,s,p); PenStyle.MARKER -> ToolIcons.tech(c,s,p); else -> ToolIcons.pen(c,s,p) } }, nibBadge = {
                when {
                    activeTool != null || selectedStyle != style -> ""
                    style == PenStyle.MARKER -> markerInk.badge
                    else -> labelsFor(style)[widthIndexByStyle[style] ?: PenPopup.DEFAULT_INDEX]
                }
            }) {
                
                if (activeTool == null && selectedStyle == style) {
                    styleCells[style]?.let { onNibWidthRequest(it.left, it.width) }
                    return@IconCell
                }
                selectedStyle = style
                returnToWrite()
                refreshSelection()
                onPenStyle(style, widthsFor(style)[widthIndexByStyle[style] ?: PenPopup.DEFAULT_INDEX])
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


    
    fun setWidthIndex(index: Int) {
        val widths = widthsFor(selectedStyle)
        if (index !in widths.indices || index == selectedWidthIndex) return
        widthIndexByStyle[selectedStyle] = index
        returnToWrite()
        refreshSelection()
        
        styleCells.values.forEach { it.invalidate() }
        android.util.Log.i("MosaicInkToolbar", "width selected style=${selectedStyle.name} index=$index stdWidth=${widths[index]}")
        onPenStyle(selectedStyle, widths[index])
    }

    
    fun setMarkerInk(ink: MarkerInk) {
        if (ink == markerInk) return
        markerInk = ink
        returnToWrite()
        refreshSelection()
        
        styleCells[PenStyle.MARKER]?.invalidate()
        android.util.Log.i("MosaicInkToolbar", "marker ink selected ink=${ink.name}")
        onMarkerInk(ink)
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
        private var downY = 0f
        
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.STROKE
            strokeWidth=iconStrokePx(resources.displayMetrics.density)
            strokeCap=Paint.Cap.ROUND
            strokeJoin=Paint.Join.ROUND
        }
        private val badgeText=Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText=true; textAlign=Paint.Align.RIGHT }
        private val badgeTri=Paint(Paint.ANTI_ALIAS_FLAG)

        fun cancelPress() {
            if (!down) return
            down = false
            invalidate()
        }
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
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    down = true
                    downY = e.y
                    invalidate()
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    
                    
                    
                    if (down && e.y - downY > 8f) {
                        down = false
                        invalidate()
                    }
                    return true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val wasDown = down
                    down = false
                    invalidate()
                    if (wasDown && e.x >= 0 && e.y >= 0 && e.x < width && e.y < height) click()
                    return true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    down = false
                    invalidate()
                    return true
                }
            }
            return true
        }
    }
}
