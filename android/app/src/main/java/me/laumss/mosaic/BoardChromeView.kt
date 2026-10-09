package me.laumss.mosaic

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.content.res.ColorStateList
import android.widget.TextView
import kotlin.math.min

class BoardChromeView(context: Context) : FrameLayout(context) {

    companion object {
        private const val INK = 0xFF111111.toInt()
        private const val MUTED = 0xFF555555.toInt()
        private const val REGION_JUMP_SIZE_DP = 72f
        private const val REGION_JUMP_MARGIN_DP = 24f
        private const val TOUCH_TOGGLE_SIZE_DP = 64f
        private const val TOUCH_TOGGLE_MARGIN_DP = 12f
        
        private const val TOP_PULL_START_PX = 120f
        
        private const val TOP_PULL_STEAL_PX = 60f
        
        private const val TOOLBAR_H_DP = InkToolbar.CELL_H_DP
        private const val TOOLBAR_CELL_W_DP = InkToolbar.CELL_W_DP
        
        private const val MENU_SCREEN_MARGIN_DP = 20f
        
        private const val LINKS_GAP_DP = 16f
        
        private const val LINKS_MIN_HEIGHT_DP = 150f
        
        private const val MENU_EXTRA_WIDTH_DP = 40f
        
        private const val CLOSE_RIGHT_MARGIN_DP = 28f
        private const val CLOSE_BUTTON_SCALE = 0.65f * 1.10f
        private const val CLOSE_STROKE_SCALE = 0.65f
        
        private const val WIDTH_POPUP_CELL_DP = 56f
        private const val WIDTH_POPUP_PAD_DP = 8f
        private const val WIDTH_POPUP_BORDER_DP = 2f
        
        private const val POPUP_TITLE_SP = 11f
        
        private const val POPUP_GAP_DP = 4f
        private const val POPUP_ROW_TOP_DP = 5f
    }

    interface Listener {
        fun onOpenMenu()
        fun onSaveArchive()
        fun onLoadArchive()
        
        fun onSelectBelow()
        
        fun onSync(enable: Boolean, address: String)
        fun onToggleTranslucent()
        
        fun onTranslucentLevel(level: Int)
        fun onZoomStep(direction: Int)
        fun onZoomReset()
        fun onToggleTouch()
        fun onDeleteSelectedCard()
        fun onToggleCardAccent()
        fun onApplySizeLevel(level: BoardGeometry.SizeLevel)
        fun onDeleteLassoSelection()
        fun onClose()
        fun onJumpToRegion(region: SparseNavigation.Region)
        
        fun onOpenNoteCard(cardId: String)
        
        fun onLocateNoteCard(cardId: String)
        
        fun onLocateNoteLink(shotId: String)
        fun onSwitcherDismissed()
        
        fun onPenStyle(style: PenStyle, width: Float)
        
        fun onMarkerInk(ink: MarkerInk)
        
        fun onConvertCardToNote()
        fun onToggleEraser()
        fun onToggleLasso()
        
        fun onShapeSelected(kind: Shapes.Kind)
        
        fun onSelectWriteTool()
        
        fun onTemplateSelected(template: BackgroundTemplate)
        fun onUndo()
        fun onRedo()
        
        fun onPenBlockChanged()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    private val zoomOut = textButton("－", 22f, bordered = false)
    private val zoomReadout = textButton("100%", 15f, bordered = false)
    private val zoomIn = textButton("＋", 22f, bordered = false)
    private val undoButton = GlyphButton(context, { c, s, p -> ToolIcons.undo(c, s, p) }, { listener?.onUndo() })
    private val redoButton = GlyphButton(context, { c, s, p -> ToolIcons.redo(c, s, p) }, { listener?.onRedo() })
    private val historyBar = LinearLayout(context)
    private val lassoEditButton = textButton("", 17f).apply { alpha = 0.35f; isEnabled = false }
    private val lassoDeleteButton = textButton("", 17f)
    
    private val syncAddressInput = EditText(context).apply {
        isSingleLine = true
        textSize = 15f
        setTextColor(INK)
        setHintTextColor(MUTED)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_DONE
        setPadding(dp(10f), 0, dp(10f), 0)
        background = borderDrawable(Color.WHITE)
    }
    private val syncButton = textButton("", 15f)
    private val syncStatus = TextView(context).apply {
        textSize = 14f
        setTextColor(MUTED)
        gravity = Gravity.CENTER_VERTICAL
    }
    private var syncEnabled = false
    private var syncState = "off"
    
    private val translucentButton = textButton("", 17f).apply { alpha = 0.5f }
    
    private val translucentSlider = SeekBar(context).apply {
        max = (TranslucentStore.MAX_LEVEL - TranslucentStore.MIN_LEVEL) / TranslucentStore.STEP
        progressTintList = ColorStateList.valueOf(INK)
        progressBackgroundTintList = ColorStateList.valueOf(MUTED)
        thumbTintList = ColorStateList.valueOf(INK)
    }
    private val translucentLevelText = TextView(context).apply {
        textSize = 14f
        setTextColor(INK)
        gravity = Gravity.CENTER_VERTICAL
        minWidth = dp(40f)
    }
    
    private val translucentLevelGroup = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val inkToolbar = InkToolbar(context)
    private val inkToolbarScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        setBackgroundColor(Color.WHITE)
    }
    
    private val toolbarBand = View(context).apply { setBackgroundColor(Color.WHITE) }

    
    private val closeButton = GlyphButton(
        context,
        { c, s, p -> ToolIcons.close(c, s, p) },
        { listener?.onClose() },
        useToolbarGlyphSize = true,
        strokeScale = CLOSE_STROKE_SCALE,
        invertOnPress = false,
    )
    private val touchToggle = GlyphButton(
        context,
        { c, s, p -> ToolIcons.touch(c, s, p, touchEnabled) },
        { hidePopups(); listener?.onToggleTouch() },
        useToolbarGlyphSize = true,
        invertOnPress = false,
        transparent = true,
    )
    private val widthPopup = WidthPopupView(context)
    
    private val widthPopupRoot = FrameLayout(context).apply {
        visibility = View.GONE
        isClickable = true
        setOnClickListener { hideWidthPopup() }
    }
    private val templatePopup = TemplatePopupView(context)
    
    private val templatePopupRoot = FrameLayout(context).apply {
        visibility = View.GONE
        isClickable = true
        setOnClickListener { hideTemplatePopup() }
    }
    private val shapePopup = ShapePopupView(context)
    
    private val shapePopupRoot = FrameLayout(context).apply {
        visibility = View.GONE
        isClickable = true
        setOnClickListener { hideShapePopup() }
    }

    private val toolsRow = LinearLayout(context)
    private val zoomBar = LinearLayout(context)
    private val regionJumpViews = HashMap<SparseNavigation.Direction, RegionJumpView>()

    private val switcherRoot = FrameLayout(context)
    private val switcherPanel = LinearLayout(context)
    
    private val switcherNotesTitle = TextView(context)
    private val switcherNotesList = LinearLayout(context)
    private val switcherNotesEmpty = TextView(context)
    private lateinit var switcherNotesScroll: ScrollView
    
    private val linksPanel = LinearLayout(context)
    private val linksTitle = TextView(context)
    private val linksList = LinearLayout(context)
    private val linksScroll = ScrollView(context)

    private var touchEnabled = false
    private var translucentActive = false

    
    private var pullTrackStartY = Float.NaN
    
    private var pullStealing = false

    init {
        buildSwitcher()
        
        
        
        
        
        
        inkToolbar.onPenStyle = { style, width -> listener?.onPenStyle(style, width); hidePopups() }
        inkToolbar.onMarkerInk = { ink -> listener?.onMarkerInk(ink); hidePopups() }
        inkToolbar.onTool = { tool ->
            hidePopups()
            when (tool) {
                "eraser" -> listener?.onToggleEraser()
                "lasso" -> listener?.onToggleLasso()
                else -> listener?.onSelectWriteTool()
            }
        }
        inkToolbar.onMore = { hidePopups(); listener?.onOpenMenu() }
        inkToolbar.onSelectBelow = { hidePopups(); listener?.onSelectBelow() }
        inkToolbar.onNibWidthRequest = { cellLeft, cellWidth -> hideTemplatePopup(); hideShapePopup(); showWidthPopup(cellLeft, cellWidth) }
        inkToolbar.onShape = { cellLeft, cellWidth -> hideWidthPopup(); hideTemplatePopup(); showShapePopup(cellLeft, cellWidth) }
        
        
        val barH = dp(TOOLBAR_H_DP)
        inkToolbarScroll.addView(inkToolbar, ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, barH))
        
        val closeW = dp(TOOLBAR_CELL_W_DP * CLOSE_BUTTON_SCALE)
        val closeH = dp(TOOLBAR_H_DP * CLOSE_BUTTON_SCALE)
        val closeTop = (barH - closeH) / 2
        val closeReserve = TOOLBAR_CELL_W_DP * CLOSE_BUTTON_SCALE + CLOSE_RIGHT_MARGIN_DP
        
        addView(toolbarBand, LayoutParams(LayoutParams.MATCH_PARENT, barH, Gravity.TOP))
        addView(inkToolbarScroll, LayoutParams(LayoutParams.MATCH_PARENT, barH, Gravity.TOP).apply {
            rightMargin = dp(closeReserve)
        })
        addView(closeButton, LayoutParams(closeW, closeH, Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(CLOSE_RIGHT_MARGIN_DP)
            topMargin = closeTop
        })
        addView(touchToggle, LayoutParams(dp(TOUCH_TOGGLE_SIZE_DP), dp(TOUCH_TOGGLE_SIZE_DP), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(TOUCH_TOGGLE_MARGIN_DP)
            bottomMargin = dp(TOUCH_TOGGLE_MARGIN_DP)
        })
        widthPopupRoot.addView(
            widthPopup,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START),
        )
        addView(widthPopupRoot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.TOP).apply {
            topMargin = barH
        })
        templatePopupRoot.addView(
            templatePopup,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START),
        )
        
        addView(templatePopupRoot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.TOP))
        shapePopupRoot.addView(
            shapePopup,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START),
        )
        addView(shapePopupRoot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.TOP).apply {
            topMargin = barH
        })
        isClickable = false
        setWillNotDraw(true)
    }

    
    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                pullStealing = false
                
                pullTrackStartY =
                    if (!blocksPen && ev.getToolType(0) != android.view.MotionEvent.TOOL_TYPE_STYLUS
                        && ev.getToolType(0) != android.view.MotionEvent.TOOL_TYPE_ERASER
                        && ev.y < TOP_PULL_START_PX
                    ) ev.y else Float.NaN
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val start = pullTrackStartY
                if (!start.isNaN() && ev.y - start > 8f) {
                    
                    
                    inkToolbar.cancelTransientPresses()
                }
                if (!start.isNaN() && ev.y - start > TOP_PULL_STEAL_PX) {
                    pullTrackStartY = Float.NaN
                    pullStealing = true
                    return true 
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                pullTrackStartY = Float.NaN
        }
        return super.onInterceptTouchEvent(ev)
    }

    
    
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (pullStealing) {
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP
                || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            ) pullStealing = false
            return true
        }
        return super.onTouchEvent(ev)
    }

    private fun textButton(text: String, sizeSp: Float, bordered: Boolean = true): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(INK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(48f)
            includeFontPadding = false
            if (bordered) {
                setPadding(dp(14f), 0, dp(14f), 0)
                background = borderDrawable(fill = Color.WHITE)
            } else {
                minWidth = dp(40f)
            }
            isClickable = true
            isFocusable = true
        }

    private fun borderDrawable(fill: Int, strokeDp: Float = 2f, radiusDp: Float = 0f) =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(strokeDp), INK)
            cornerRadius = radiusDp * density
        }

    
    private var toolbarHiddenByUser = false
    
    private var toolbarHiddenByMenu = false
    
    private var cachedSwitcherWidthPx = 0

    
    val toolbarShown: Boolean get() = !toolbarHiddenByUser && !toolbarHiddenByMenu

    private fun applyToolbarVisibility() {
        val vis = if (toolbarShown) View.VISIBLE else View.GONE
        toolbarBand.visibility = vis
        inkToolbarScroll.visibility = vis
        closeButton.visibility = vis
    }

    
    fun toggleToolbar() {
        if (toolbarHiddenByMenu) return
        toolbarHiddenByUser = !toolbarHiddenByUser
        if (toolbarHiddenByUser) hidePopups()
        applyToolbarVisibility()
        listener?.onPenBlockChanged()
    }

    
    fun toolbarHeightPx(): Int = if (toolbarShown) dp(TOOLBAR_H_DP) else 0

    
    fun cancelTransientPresses() = inkToolbar.cancelTransientPresses()

    fun setNoteMode(active: Boolean) {
        
        inkToolbar.setMoreVisible(!active)
        if (active) { hideSwitcher(); regionJumpViews.values.forEach { it.visibility = View.GONE } }
        else regionJumpViews.values.forEach { it.visibility = View.VISIBLE }
        hidePopups()
        bringChromeToFront()
    }

    fun setZoom(scale: Float) {
        val text = "${Math.round(BoardGeometry.zoomPercentForScale(scale))}%"
        
        if (zoomReadout.text.toString() != text) zoomReadout.text = text
        val atMin = scale <= BoardGeometry.ZOOM_LEVELS.first() + 1e-6f
        val atMax = scale >= BoardGeometry.ZOOM_LEVELS.last() - 1e-6f
        zoomOut.alpha = if (atMin) 0.3f else 1f
        zoomOut.isEnabled = !atMin
        zoomIn.alpha = if (atMax) 0.3f else 1f
        zoomIn.isEnabled = !atMax
    }

    
    fun setInkTool(eraser: Boolean, lasso: Boolean, shape: Boolean = false) = inkToolbar.setTool(eraser, lasso, shape)

    
    fun setSelectBelowActive(active: Boolean) = inkToolbar.setSelectBelowActive(active)

    fun setTouchEnabled(enabled: Boolean) {
        if (touchEnabled == enabled) return
        touchEnabled = enabled
        touchToggle.invalidate()
    }

    
    fun setSyncState(enabled: Boolean, state: String, address: String) {
        syncEnabled = enabled
        syncState = state
        
        if (!syncAddressInput.hasFocus() && syncAddressInput.text.toString() != address) syncAddressInput.setText(address)
        applySyncButton()
    }

    private fun applySyncButton() {
        syncAddressInput.hint = MosaicStrings.t(MosaicStrings.Key.syncAddressHint)
        syncAddressInput.isEnabled = !syncEnabled
        syncButton.text = MosaicStrings.t(if (syncEnabled) MosaicStrings.Key.syncDisconnect else MosaicStrings.Key.syncConnect)
        syncButton.background = borderDrawable(if (syncEnabled) INK else Color.WHITE)
        syncButton.setTextColor(if (syncEnabled) Color.WHITE else INK)
        syncStatus.text = when {
            !syncEnabled -> ""
            syncState == "connected" -> MosaicStrings.t(MosaicStrings.Key.syncConnected)
            syncState == "connecting" || syncState == "idle" -> MosaicStrings.t(MosaicStrings.Key.syncConnecting)
            else -> MosaicStrings.t(MosaicStrings.Key.syncOffline)
        }
    }

    private fun hideSyncKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(syncAddressInput.windowToken, 0)
        syncAddressInput.clearFocus()
    }

    fun setTranslucentActive(active: Boolean) {
        if (translucentActive == active) return
        translucentActive = active
        translucentButton.background = borderDrawable(if (active) INK else Color.WHITE)
        translucentButton.setTextColor(if (active) Color.WHITE else INK)
        applyTranslucentSliderEnabled()
    }

    fun setTranslucentLevel(level: Int) {
        translucentSlider.progress = (level - TranslucentStore.MIN_LEVEL) / TranslucentStore.STEP
        translucentLevelText.text = "${levelOf(translucentSlider.progress)}%"
    }

    private fun levelOf(progress: Int): Int = TranslucentStore.MIN_LEVEL + progress * TranslucentStore.STEP

    private fun applyTranslucentSliderEnabled() {
        translucentSlider.isEnabled = translucentActive
        translucentLevelGroup.alpha = if (translucentActive) 1f else 0.4f
    }

    @Suppress("UNUSED_PARAMETER")
    fun setMode(
        selectedCard: Boolean,
        selectedCardColored: Boolean,
        sizeLevels: List<BoardGeometry.SizeLevel>,
        lassoCards: Boolean,
        selectedCardKind: String? = null,
    ) {
        lassoEditButton.visibility = if (lassoCards) View.VISIBLE else View.GONE
        lassoDeleteButton.visibility = if (lassoCards) View.VISIBLE else View.GONE
        val showGlobalTools = !selectedCard
        translucentButton.visibility = if (showGlobalTools) View.VISIBLE else View.GONE
        translucentLevelGroup.visibility = translucentButton.visibility
    }

    

    private inner class RegionJumpView(val direction: SparseNavigation.Direction) : View(context) {
        var region: SparseNavigation.Region? = null
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = INK; strokeWidth = 2f * density }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = INK }
        private val arrowText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = 32f * density; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        private val countText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f * density; isFakeBoldText = true; textAlign = Paint.Align.CENTER }

        init {
            isClickable = true
            setOnClickListener { region?.let { listener?.onJumpToRegion(it) } }
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            canvas.drawCircle(r, r, r - border.strokeWidth, fill)
            canvas.drawCircle(r, r, r - border.strokeWidth, border)
            val arrow = when (direction) {
                SparseNavigation.Direction.LEFT -> "←"
                SparseNavigation.Direction.RIGHT -> "→"
                SparseNavigation.Direction.UP -> "↑"
                SparseNavigation.Direction.DOWN -> "↓"
            }
            val count = region?.cardIds?.size ?: 0
            val countW = maxOf(22f * density, countText.measureText(count.toString()) + 8f * density)
            val gap = 5f * density
            val arrowW = arrowText.measureText(arrow)
            val total = arrowW + gap + countW
            val startX = r - total / 2f
            canvas.drawText(arrow, startX + arrowW / 2f, r + arrowText.textSize * 0.36f, arrowText)
            val bx = startX + arrowW + gap
            val by = r - 11f * density
            canvas.drawRoundRect(bx, by, bx + countW, by + 22f * density, 11f * density, 11f * density, badge)
            canvas.drawText(count.toString(), bx + countW / 2f, r + countText.textSize * 0.36f, countText)
        }
    }

    fun setRegionJumps(jumps: List<SparseNavigation.RegionJump>) {
        val wanted = HashMap<SparseNavigation.Direction, SparseNavigation.Region>()
        for (j in jumps) wanted[j.direction] = j.region
        for (dir in SparseNavigation.Direction.values()) {
            val region = wanted[dir]
            val existing = regionJumpViews[dir]
            if (region == null) {
                if (existing != null) {
                    removeView(existing)
                    regionJumpViews.remove(dir)
                }
                continue
            }
            val view = existing ?: RegionJumpView(dir).also {
                regionJumpViews[dir] = it
                addView(it, regionJumpParams(dir))
            }
            if (view.region !== region) {
                view.region = region
                view.invalidate()
            }
        }
        bringChromeToFront()
    }

    private fun regionJumpParams(dir: SparseNavigation.Direction): LayoutParams {
        val size = dp(REGION_JUMP_SIZE_DP)
        val margin = dp(REGION_JUMP_MARGIN_DP)
        return when (dir) {
            SparseNavigation.Direction.LEFT -> LayoutParams(size, size, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = margin }
            SparseNavigation.Direction.RIGHT -> LayoutParams(size, size, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = margin }
            
            SparseNavigation.Direction.UP -> LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = toolbarHeightPx() + margin }
            SparseNavigation.Direction.DOWN -> LayoutParams(size, size, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = margin }
        }
    }

    fun regionJumpContains(xPx: Float, yPx: Float): Boolean {
        for (v in regionJumpViews.values) {
            if (xPx >= v.left && xPx <= v.right && yPx >= v.top && yPx <= v.bottom) return true
        }
        return false
    }

    

    private fun buildSwitcher() {
        switcherRoot.setBackgroundColor(Color.TRANSPARENT)
        switcherRoot.visibility = View.GONE
        switcherRoot.setOnClickListener { listener?.onSwitcherDismissed() }
        addView(switcherRoot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        switcherPanel.orientation = LinearLayout.VERTICAL
        switcherPanel.setPadding(dp(24f), dp(16f), dp(24f), dp(16f))
        switcherPanel.isClickable = true
        switcherPanel.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(dp(2f), INK)
        }
        
        switcherRoot.addView(
            switcherPanel,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(MENU_SCREEN_MARGIN_DP)
                topMargin = dp(MENU_SCREEN_MARGIN_DP)
            },
        )
        buildLinksPanel()

        zoomBar.orientation = LinearLayout.HORIZONTAL
        zoomBar.gravity = Gravity.CENTER_VERTICAL
        zoomBar.background = borderDrawable(Color.WHITE)
        zoomBar.setPadding(dp(2f), 0, dp(2f), 0)
        zoomReadout.minWidth = dp(58f)
        zoomBar.addView(zoomOut, LinearLayout.LayoutParams(dp(40f), dp(38f)))
        zoomBar.addView(zoomReadout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38f)))
        zoomBar.addView(zoomIn, LinearLayout.LayoutParams(dp(40f), dp(38f)))

        historyBar.orientation = LinearLayout.HORIZONTAL
        historyBar.gravity = Gravity.CENTER_VERTICAL
        historyBar.background = borderDrawable(Color.WHITE)
        historyBar.setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
        historyBar.addView(undoButton, LinearLayout.LayoutParams(dp(38f), dp(34f)))
        historyBar.addView(redoButton, LinearLayout.LayoutParams(dp(38f), dp(34f)))

        val toolsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        toolsRow.orientation = LinearLayout.HORIZONTAL
        toolsRow.gravity = Gravity.CENTER_VERTICAL
        for (button in listOf(translucentButton, lassoEditButton, lassoDeleteButton)) {
            toolsRow.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48f)).apply { marginEnd = dp(10f) })
        }
        translucentLevelGroup.addView(translucentSlider, LinearLayout.LayoutParams(dp(180f), dp(48f)))
        translucentLevelGroup.addView(translucentLevelText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48f)))
        toolsRow.addView(
            translucentLevelGroup,
            toolsRow.indexOfChild(translucentButton) + 1,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48f)).apply { marginEnd = dp(10f) },
        )
        
        translucentSlider.setOnTouchListener { view, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        translucentSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                translucentLevelText.text = "${levelOf(progress)}%"
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            
            override fun onStopTrackingTouch(bar: SeekBar) {
                listener?.onTranslucentLevel(levelOf(bar.progress))
            }
        })
        setTranslucentLevel(TranslucentStore.DEFAULT_LEVEL)
        applyTranslucentSliderEnabled()
        toolsScroll.addView(toolsRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        zoomOut.setOnClickListener { listener?.onZoomStep(-1) }
        zoomReadout.setOnClickListener { listener?.onZoomReset() }
        zoomIn.setOnClickListener { listener?.onZoomStep(1) }
        syncButton.setOnClickListener {
            hideSyncKeyboard()
            listener?.onSync(!syncEnabled, syncAddressInput.text.toString().trim())
        }
        syncAddressInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) hideSyncKeyboard()
            false
        }
        translucentButton.setOnClickListener { listener?.onToggleTranslucent() }
        lassoDeleteButton.setOnClickListener { listener?.onDeleteLassoSelection() }

        
        val menuHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, dp(8f))
        }
        menuHeader.addView(
            zoomBar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        menuHeader.addView(
            historyBar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12f) },
        )
        val saveArchiveButton = textButton(MosaicStrings.t(MosaicStrings.Key.archiveSave), 15f)
        saveArchiveButton.setOnClickListener { listener?.onSaveArchive() }
        val loadArchiveButton = textButton(MosaicStrings.t(MosaicStrings.Key.archiveLoad), 15f)
        loadArchiveButton.setOnClickListener { listener?.onLoadArchive() }
        val archiveRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8f))
        }
        archiveRow.addView(saveArchiveButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f)))
        archiveRow.addView(loadArchiveButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f)).apply { marginStart = dp(8f) })
        menuHeader.addView(
            View(context),
            LinearLayout.LayoutParams(0, 0, 1f),
        )
        val templateMenuButton = textButton(MosaicStrings.t(MosaicStrings.Key.template), 15f)
        templateMenuButton.setOnClickListener { toggleMenuTemplatePopup() }
        menuHeader.addView(
            templateMenuButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f)),
        )
        switcherPanel.addView(menuHeader)
        switcherPanel.addView(archiveRow)

        
        val syncRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(8f))
        }
        syncRow.addView(syncAddressInput, LinearLayout.LayoutParams(0, dp(40f), 1f))
        syncRow.addView(syncButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f)).apply { marginStart = dp(8f) })
        syncRow.addView(syncStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f)).apply { marginStart = dp(10f) })
        switcherPanel.addView(syncRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        
        switcherNotesTitle.textSize = 18f
        switcherNotesTitle.typeface = Typeface.DEFAULT_BOLD
        switcherNotesTitle.setTextColor(INK)
        switcherNotesTitle.setPadding(0, dp(16f), 0, dp(8f))
        switcherPanel.addView(switcherNotesTitle)

        switcherNotesEmpty.textSize = 16f
        switcherNotesEmpty.setTextColor(MUTED)
        switcherPanel.addView(switcherNotesEmpty)

        switcherNotesScroll = ScrollView(context)
        switcherNotesList.orientation = LinearLayout.VERTICAL
        switcherNotesScroll.addView(
            switcherNotesList,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        switcherPanel.addView(
            switcherNotesScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
        )

        switcherPanel.addView(
            toolsScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12f)
            },
        )

        applyMenuRowStrings()
        setMode(selectedCard = false, selectedCardColored = false, sizeLevels = emptyList(), lassoCards = false)
        setZoom(BoardGeometry.DEFAULT_ZOOM)
    }

    
    private fun applyMenuRowStrings() {
        applySyncButton()
        translucentButton.text = MosaicStrings.t(MosaicStrings.Key.translucent)
        lassoEditButton.text = MosaicStrings.t(MosaicStrings.Key.edit)
        lassoDeleteButton.text = MosaicStrings.t(MosaicStrings.Key.deleteCard)
        switcherNotesTitle.text = MosaicStrings.t(MosaicStrings.Key.notes)
        switcherNotesEmpty.text = MosaicStrings.t(MosaicStrings.Key.notesEmpty)
        linksTitle.text = MosaicStrings.t(MosaicStrings.Key.noteLinks)
        zoomOut.contentDescription = MosaicStrings.t(MosaicStrings.Key.zoomOut)
        zoomIn.contentDescription = MosaicStrings.t(MosaicStrings.Key.zoomIn)
        zoomReadout.contentDescription = MosaicStrings.t(MosaicStrings.Key.zoomDefault)
    }

    val switcherOpen: Boolean get() = switcherRoot.visibility == View.VISIBLE

    fun showSwitcher(noteCards: List<BoardEngine.CardRec>, links: List<NoteLinks.Link>) {
        showNoteCards(noteCards)
        showNoteLinks(links)
        applyMenuRowStrings()
        hidePopups()
        
        cachedSwitcherWidthPx = switcherPanelWidthPx()
        toolbarHiddenByMenu = true
        applyToolbarVisibility()
        
        layoutSwitcherPanel()
        switcherRoot.visibility = View.VISIBLE
        bringChromeToFront()
        listener?.onPenBlockChanged()
    }

    
    private fun showNoteCards(noteCards: List<BoardEngine.CardRec>) {
        switcherNotesList.removeAllViews()
        switcherNotesEmpty.visibility = if (noteCards.isEmpty()) View.VISIBLE else View.GONE
        val thumbSize = dp(56f)
        noteCards.forEachIndexed { index, card ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10f), 0, dp(10f))
                isClickable = true
                isLongClickable = true
                setOnClickListener { listener?.onOpenNoteCard(card.id) }
                setOnLongClickListener {
                    listener?.onLocateNoteCard(card.id)
                    true
                }
            }
            val name = card.title.ifBlank { MosaicStrings.noteName(index + 1) }
            val preview = if (card.imagePath.isNotEmpty()) CardImageCache.get(card.imagePath, thumbSize, alpha = true) else null
            val thumbView: View = if (preview != null) {
                ImageView(context).apply {
                    setImageBitmap(preview)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = borderDrawable(Color.WHITE, 2f, 6f)
                }
            } else {
                TextView(context).apply {
                    text = name.take(2)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(INK)
                    gravity = Gravity.CENTER
                    background = borderDrawable(Color.WHITE, 2f, 6f)
                }
            }
            row.addView(thumbView, LinearLayout.LayoutParams(thumbSize, thumbSize).apply { marginEnd = dp(14f) })
            val meta = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            meta.addView(TextView(context).apply {
                text = name
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(INK)
            })
            meta.addView(TextView(context).apply {
                text = "(${Math.round(card.x)}, ${Math.round(card.y)})"
                textSize = 13f
                setTextColor(MUTED)
            })
            row.addView(meta, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            switcherNotesList.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun buildLinksPanel() {
        linksPanel.orientation = LinearLayout.VERTICAL
        linksPanel.setPadding(dp(24f), dp(12f), dp(24f), dp(12f))
        linksPanel.isClickable = true
        linksPanel.visibility = View.GONE
        linksPanel.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(dp(2f), INK)
        }
        linksTitle.textSize = 18f
        linksTitle.typeface = Typeface.DEFAULT_BOLD
        linksTitle.setTextColor(INK)
        linksTitle.setPadding(0, dp(4f), 0, dp(4f))
        linksPanel.addView(linksTitle)
        linksList.orientation = LinearLayout.VERTICAL
        linksScroll.addView(
            linksList,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        linksPanel.addView(
            linksScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        switcherRoot.addView(
            linksPanel,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START),
        )
    }

    
    private fun showNoteLinks(links: List<NoteLinks.Link>) {
        linksList.removeAllViews()
        linksPanel.visibility = if (links.isEmpty()) View.GONE else View.VISIBLE
        val thumbSize = dp(56f)
        for (link in links) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10f), 0, dp(10f))
                isClickable = true
                setOnClickListener { listener?.onLocateNoteLink(link.id) }
            }
            val thumb = TextView(context).apply {
                text = if (link.page >= 0) "P${link.page + 1}" else "↗"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(INK)
                gravity = Gravity.CENTER
                background = borderDrawable(Color.WHITE, 2f, 6f)
            }
            row.addView(thumb, LinearLayout.LayoutParams(thumbSize, thumbSize).apply { marginEnd = dp(14f) })
            val meta = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            meta.addView(TextView(context).apply {
                text = NoteLinks.displayName(link)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(INK)
                isSingleLine = true
            })
            meta.addView(TextView(context).apply {
                text = MosaicStrings.noteLinkDetail(link.page, link.updatedAt)
                textSize = 13f
                setTextColor(MUTED)
                isSingleLine = true
            })
            row.addView(meta, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            linksList.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (switcherOpen) layoutSwitcherPanel()
    }

    
    private fun layoutSwitcherPanel() {
        if (width <= 0 || height <= 0) return
        val margin = dp(MENU_SCREEN_MARGIN_DP)
        
        val panelWidth = if (cachedSwitcherWidthPx > 0) cachedSwitcherWidthPx else switcherPanelWidthPx()
        val showLinks = linksPanel.visibility == View.VISIBLE
        val gap = dp(LINKS_GAP_DP)
        val available = (height - 2 * margin).coerceAtLeast(dp(160f))
        var linksNatural = 0
        if (showLinks) {
            val scrollLp = linksScroll.layoutParams as LinearLayout.LayoutParams
            scrollLp.weight = 0f
            scrollLp.height = LinearLayout.LayoutParams.WRAP_CONTENT
            linksScroll.layoutParams = scrollLp
            linksPanel.measure(
                MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            linksNatural = linksPanel.measuredHeight
        }
        
        val maxHeight = if (showLinks) {
            (available - gap - min(linksNatural, dp(LINKS_MIN_HEIGHT_DP))).coerceAtLeast(dp(160f))
        } else {
            available
        }
        val lp = switcherPanel.layoutParams as LayoutParams
        lp.width = panelWidth
        
        val notesLp = switcherNotesScroll.layoutParams as LinearLayout.LayoutParams
        notesLp.weight = 0f
        notesLp.height = LinearLayout.LayoutParams.WRAP_CONTENT
        switcherNotesScroll.layoutParams = notesLp
        switcherPanel.measure(
            MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val natural = switcherPanel.measuredHeight
        lp.height = if (natural <= maxHeight) {
            LayoutParams.WRAP_CONTENT
        } else {
            
            notesLp.weight = 1f
            notesLp.height = 0
            switcherNotesScroll.layoutParams = notesLp
            maxHeight
        }
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = margin
        lp.topMargin = margin
        switcherPanel.layoutParams = lp
        switcherPanel.requestLayout()
        if (showLinks) {
            val mainHeight = min(natural, maxHeight)
            layoutLinksPanel(panelWidth, margin + mainHeight + gap, available - mainHeight - gap, linksNatural)
        }
    }

    
    private fun layoutLinksPanel(panelWidth: Int, top: Int, room: Int, natural: Int) {
        val lp = linksPanel.layoutParams as LayoutParams
        lp.width = panelWidth
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = dp(MENU_SCREEN_MARGIN_DP)
        lp.topMargin = top
        val scrollLp = linksScroll.layoutParams as LinearLayout.LayoutParams
        if (natural <= room) {
            lp.height = LayoutParams.WRAP_CONTENT
        } else {
            scrollLp.weight = 1f
            scrollLp.height = 0
            lp.height = room.coerceAtLeast(dp(80f))
        }
        linksScroll.layoutParams = scrollLp
        linksPanel.layoutParams = lp
        linksPanel.requestLayout()
    }

    private fun switcherPanelWidthPx(): Int {
        val scrollX = inkToolbarScroll.scrollX
        val base = inkToolbarScroll.left + inkToolbar.left - scrollX
        
        val lassoRight = base + inkToolbar.lassoCellRightPx()
        val margin = dp(MENU_SCREEN_MARGIN_DP)
        
        val extra = dp(MENU_EXTRA_WIDTH_DP)
        return (lassoRight - margin + extra).coerceIn(dp(160f), (width - 2 * margin).coerceAtLeast(dp(160f)))
    }

    fun hideSwitcher() {
        if (syncAddressInput.hasFocus()) hideSyncKeyboard()
        switcherRoot.visibility = View.GONE
        hidePopups()
        
        if (toolbarHiddenByMenu) {
            toolbarHiddenByMenu = false
            applyToolbarVisibility()
            listener?.onPenBlockChanged()
        }
    }

    private fun bringChromeToFront() {
        if (switcherOpen && indexOfChild(switcherRoot) != childCount - 1) switcherRoot.bringToFront()
    }

    
    fun consumesPoint(xPx: Float, yPx: Float): Boolean =
        blocksPen || yPx < toolbarHeightPx() || regionJumpContains(xPx, yPx) || touchToggleContains(xPx, yPx)

    private fun touchToggleContains(xPx: Float, yPx: Float): Boolean =
        touchToggle.visibility == View.VISIBLE &&
            xPx >= touchToggle.left && xPx <= touchToggle.right && yPx >= touchToggle.top && yPx <= touchToggle.bottom

    fun touchToggleBounds(): android.graphics.Rect? =
        if (touchToggle.visibility != View.VISIBLE || touchToggle.width == 0) null
        else android.graphics.Rect(touchToggle.left, touchToggle.top, touchToggle.right, touchToggle.bottom)

    
    val blocksPen: Boolean get() = switcherOpen || widthPopupRoot.visibility == View.VISIBLE ||
        templatePopupRoot.visibility == View.VISIBLE || shapePopupRoot.visibility == View.VISIBLE

    private fun hidePopups() { hideWidthPopup(); hideTemplatePopup(); hideShapePopup() }

    
    fun setCurrentTemplate(template: BackgroundTemplate) = templatePopup.setSelected(template)

    
    fun setCurrentShape(kind: Shapes.Kind?) = shapePopup.setSelected(kind)

    private fun showShapePopup(cellLeft: Int, cellWidth: Int) {
        if (shapePopupRoot.visibility == View.VISIBLE) { hideShapePopup(); return }
        shapePopup.measure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height.coerceAtLeast(1), MeasureSpec.AT_MOST),
        )
        val popupWidth = shapePopup.measuredWidth
        val anchorLeft = cellLeft - inkToolbarScroll.scrollX
        val left = anchorLeft.coerceIn(0, (width - popupWidth).coerceAtLeast(0))
        (shapePopup.layoutParams as LayoutParams).leftMargin = left
        shapePopupRoot.visibility = View.VISIBLE
        shapePopupRoot.bringToFront()
        shapePopup.requestLayout()
        listener?.onPenBlockChanged()
    }

    private fun hideShapePopup() {
        if (shapePopupRoot.visibility != View.VISIBLE) return
        shapePopupRoot.visibility = View.GONE
        listener?.onPenBlockChanged()
    }

    
    private fun toggleMenuTemplatePopup() {
        if (templatePopupRoot.visibility == View.VISIBLE) { hideTemplatePopup(); return }
        templatePopup.measure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height.coerceAtLeast(1), MeasureSpec.AT_MOST),
        )
        val popupWidth = templatePopup.measuredWidth
        val margin = dp(MENU_SCREEN_MARGIN_DP)
        val panelWidth = if (cachedSwitcherWidthPx > 0) cachedSwitcherWidthPx else switcherPanelWidthPx()
        
        val border = dp(WIDTH_POPUP_BORDER_DP)
        val left = (margin + panelWidth - border).coerceIn(0, (width - popupWidth).coerceAtLeast(0))
        val lp = templatePopup.layoutParams as LayoutParams
        lp.leftMargin = left
        lp.topMargin = margin
        templatePopup.layoutParams = lp
        templatePopupRoot.visibility = View.VISIBLE
        templatePopupRoot.bringToFront()
        templatePopup.requestLayout()
        listener?.onPenBlockChanged()
    }

    private fun hideTemplatePopup() {
        if (templatePopupRoot.visibility != View.VISIBLE) return
        templatePopupRoot.visibility = View.GONE
        listener?.onPenBlockChanged()
    }

    private fun showWidthPopup(cellLeft: Int, cellWidth: Int) {
        if (widthPopupRoot.visibility == View.VISIBLE) { hideWidthPopup(); return }
        
        
        
        
        
        
        if (inkToolbar.markerSelected) {
            widthPopup.showMarkerInks(inkToolbar.markerInk)
        } else {
            widthPopup.setWidthCount(inkToolbar.widthCount)
            widthPopup.setSelected(inkToolbar.widthIndex)
        }
        widthPopup.measure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height.coerceAtLeast(1), MeasureSpec.AT_MOST),
        )
        
        val popupWidth = widthPopup.measuredWidth
        val anchorLeft = cellLeft - inkToolbarScroll.scrollX
        val left = anchorLeft.coerceIn(0, (width - popupWidth).coerceAtLeast(0))
        (widthPopup.layoutParams as LayoutParams).leftMargin = left
        widthPopupRoot.visibility = View.VISIBLE
        widthPopupRoot.bringToFront()
        widthPopup.requestLayout()
        listener?.onPenBlockChanged()
    }

    private fun hideWidthPopup() {
        if (widthPopupRoot.visibility != View.VISIBLE) return
        widthPopupRoot.visibility = View.GONE
        listener?.onPenBlockChanged()
    }

    
    private class GlyphButton(
        context: android.content.Context,
        private val glyph: (Canvas, Float, Paint) -> Unit,
        private val click: () -> Unit,
        private val useToolbarGlyphSize: Boolean = false,
        private val strokeScale: Float = 1f,
        
        private val invertOnPress: Boolean = true,
        private val transparent: Boolean = false,
    ) : View(context) {
        var active = false
            set(value) { if (field != value) { field = value; invalidate() } }
        private var down = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private fun strokePx(): Float =
            InkToolbar.iconStrokePx(resources.displayMetrics.density) * strokeScale

        override fun onDraw(c: Canvas) {
            val inverted = active || (down && invertOnPress)
            if (inverted || !transparent) c.drawColor(if (inverted) Color.BLACK else Color.WHITE)
            paint.strokeWidth = strokePx()
            paint.color = if (inverted) Color.WHITE else Color.BLACK
            val s = if (useToolbarGlyphSize) {
                InkToolbar.glyphSizeInCell(width, height)
            } else {
                width * 0.72f
            }
            c.save(); c.translate((width - s) / 2f, (height - s) / 2f); glyph(c, s, paint); c.restore()
        }

        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { down = true; invalidate() }
                android.view.MotionEvent.ACTION_UP -> {
                    down = false; invalidate()
                    if (e.x >= 0 && e.y >= 0 && e.x < width && e.y < height) click()
                }
                android.view.MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
            }
            return true
        }
    }

    
    private inner class WidthPopupView(context: android.content.Context) : LinearLayout(context) {
        private val cells = ArrayList<GlyphButton>()
        private val widthRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private val inkRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private val inkCells = LinkedHashMap<MarkerInk, GlyphButton>()
        
        private val dotRadii = FloatArray(PenPopup.MAX_WIDTH_COUNT)

        init {
            orientation = VERTICAL
            isClickable = true
            val row = widthRow
            val cellPx = dp(WIDTH_POPUP_CELL_DP)
            updateDotRadii(inkToolbar.widthCount)
            (0 until PenPopup.MAX_WIDTH_COUNT).forEach { i ->
                val cell = GlyphButton(
                    context,
                    { c, s, p -> ToolIcons.nibDot(c, s, p, dotRadii[i]) },
                    click = {
                        inkToolbar.setWidthIndex(i)
                        hideWidthPopup()
                    },
                )
                cells.add(cell)
                row.addView(cell, LinearLayout.LayoutParams(cellPx, cellPx).apply { marginStart = if (i == 0) 0 else dp(POPUP_GAP_DP) })
            }
            addView(row, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            MarkerInk.entries.forEachIndexed { i, ink ->
                val cell = GlyphButton(
                    context,
                    { c, s, p -> ToolIcons.colorSwatch(c, s, p, ink.argb) },
                    click = {
                        inkToolbar.setMarkerInk(ink)
                        hideWidthPopup()
                    },
                )
                inkCells[ink] = cell
                inkRow.addView(cell, LinearLayout.LayoutParams(cellPx, cellPx).apply { marginStart = if (i == 0) 0 else dp(POPUP_GAP_DP) })
            }
            inkRow.visibility = View.GONE
            addView(inkRow, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        fun setSelected(index: Int) = cells.forEachIndexed { i, cell -> cell.active = i == index }

        
        private fun updateDotRadii(count: Int) {
            val step = if (count > 1) minOf(1.5f, 13.5f / (count - 1)) else 0f
            for (i in dotRadii.indices) dotRadii[i] = 1.7f + i * step
        }

        fun setWidthCount(count: Int) {
            widthRow.visibility = View.VISIBLE
            inkRow.visibility = View.GONE
            updateDotRadii(count)
            cells.forEachIndexed { i, cell ->
                cell.visibility = if (i < count) View.VISIBLE else View.GONE
                cell.invalidate()
            }
        }

        fun showMarkerInks(selected: MarkerInk) {
            widthRow.visibility = View.GONE
            inkRow.visibility = View.VISIBLE
            inkCells.forEach { (ink, cell) -> cell.active = ink == selected }
        }
    }

    
    private inner class TemplatePopupView(context: android.content.Context) : LinearLayout(context) {
        private val styleCells = LinkedHashMap<TemplateStyle, GlyphButton>()
        private val spacingCells = LinkedHashMap<TemplateSpacing, GlyphButton>()
        private var current = BackgroundTemplate.BLANK

        init {
            orientation = VERTICAL
            isClickable = true
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.TRANSPARENT)
            addView(buildStyleRow())
            addView(buildSpacingRow(), rowParams(POPUP_ROW_TOP_DP))
        }

        private fun rowParams(topDp: Float) =
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(topDp) }

        private fun buildStyleRow(): LinearLayout {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val cellPx = dp(WIDTH_POPUP_CELL_DP)
            val entries = listOf<Pair<TemplateStyle, (Canvas, Float, Paint) -> Unit>>(
                TemplateStyle.NONE to { c, s, p -> ToolIcons.templateNone(c, s, p) },
                TemplateStyle.DOTS to { c, s, p -> ToolIcons.templateDots(c, s, p) },
                TemplateStyle.LINES to { c, s, p -> ToolIcons.templateLines(c, s, p) },
                TemplateStyle.CROSS to { c, s, p -> ToolIcons.templateCross(c, s, p) },
            )
            entries.forEachIndexed { i, (style, glyph) ->
                val cell = GlyphButton(context, glyph, click = { selectStyle(style) })
                styleCells[style] = cell
                row.addView(cell, LinearLayout.LayoutParams(cellPx, cellPx).apply { marginStart = if (i == 0) 0 else dp(POPUP_GAP_DP) })
            }
            return row
        }

        private fun buildSpacingRow(): LinearLayout {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val cellPx = dp(WIDTH_POPUP_CELL_DP)
            val entries = listOf(
                TemplateSpacing.NARROW to 3f,
                TemplateSpacing.MEDIUM to 5f,
                TemplateSpacing.WIDE to 8f,
            )
            entries.forEachIndexed { i, (spacing, gapU) ->
                val cell = GlyphButton(context, { c, s, p -> ToolIcons.templateSpacing(c, s, p, gapU) }, click = { selectSpacing(spacing) })
                spacingCells[spacing] = cell
                row.addView(cell, LinearLayout.LayoutParams(cellPx, cellPx).apply { marginStart = if (i == 0) 0 else dp(POPUP_GAP_DP) })
            }
            return row
        }

        private fun selectStyle(style: TemplateStyle) {
            current = current.copy(style = style); refresh(); listener?.onTemplateSelected(current)
        }

        private fun selectSpacing(spacing: TemplateSpacing) {
            current = current.copy(spacing = spacing); refresh(); listener?.onTemplateSelected(current)
        }

        fun setSelected(template: BackgroundTemplate) { current = template; refresh() }

        private fun refresh() {
            styleCells.forEach { (style, cell) -> cell.active = style == current.style }
            spacingCells.forEach { (spacing, cell) -> cell.active = spacing == current.spacing }
        }
    }

    
    private inner class ShapePopupView(context: android.content.Context) : LinearLayout(context) {
        private val cells = LinkedHashMap<Shapes.Kind, GlyphButton>()
        private var current: Shapes.Kind? = null

        init {
            orientation = VERTICAL
            isClickable = true
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.TRANSPARENT)
            val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val cellPx = dp(WIDTH_POPUP_CELL_DP)
            val entries = listOf<Pair<Shapes.Kind, (Canvas, Float, Paint) -> Unit>>(
                Shapes.Kind.LINE to { c, s, p -> ToolIcons.shapeLine(c, s, p) },
                Shapes.Kind.RECT to { c, s, p -> ToolIcons.shapeRect(c, s, p) },
                Shapes.Kind.TRIANGLE to { c, s, p -> ToolIcons.shapeTriangle(c, s, p) },
                Shapes.Kind.ELLIPSE to { c, s, p -> ToolIcons.shapeEllipse(c, s, p) },
            )
            entries.forEachIndexed { i, (kind, glyph) ->
                val cell = GlyphButton(context, glyph, click = { hideShapePopup(); listener?.onShapeSelected(kind) })
                cells[kind] = cell
                row.addView(cell, LinearLayout.LayoutParams(cellPx, cellPx).apply { marginStart = if (i == 0) 0 else dp(POPUP_GAP_DP) })
            }
            addView(row, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        fun setSelected(kind: Shapes.Kind?) {
            current = kind
            cells.forEach { (k, cell) -> cell.active = k == current }
        }
    }
}
