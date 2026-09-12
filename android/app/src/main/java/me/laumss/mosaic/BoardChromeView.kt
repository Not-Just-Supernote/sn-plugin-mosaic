package me.laumss.mosaic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale


class BoardChromeView(context: Context) : FrameLayout(context) {

    companion object {
        private const val INK = 0xFF111111.toInt()
        private const val MUTED = 0xFF555555.toInt()
        private const val REGION_JUMP_SIZE_DP = 72f
        private const val REGION_JUMP_MARGIN_DP = 24f
        private const val MENU_SIZE_DP = 52f
        private const val MENU_MARGIN_DP = 24f
        
        private const val TOUCH_SIZE_DP = 52f
        
        private const val FLOAT_ICON_STROKE = 3f

        
        private const val FINGER_VIEWBOX = 48f
        private const val FINGER_HAND =
            "m 19.7812,40.7711 c -5.723,-1.787 -10.5000276,-7.9 -12.8870276,-11.482 -0.23652,-0.3715 -0.3943,-0.7875 -0.46358,-1.2224 -0.06928,-0.4349 -0.04859,-0.8794 0.0608,-1.306 0.10939,-0.4266 0.30514,-0.8262 0.57515,-1.1741 0.27001,-0.3479 0.60853,-0.6367 0.99463,-0.8485 0.93179,-0.5997 2.0272276,-0.8945 3.1341276,-0.8432 1.107,0.0512 2.1705,0.4459 3.0429,1.1292 l 1.905,1.589 v -12.923 c 0,-1.4 1.356,-2.375 3.028,-2.375 0.6827,-0.0467 1.3598,0.1504 1.9108,0.5562 0.551,0.4058 0.9402,0.994 1.0982,1.6598 -0.021,-0.123 0,0 0.019,0.128 -0.006,-0.155 0,8.579 0,8.673 V 8.483106 c 0.0723,-0.7341972 0.4302,-1.4105948 0.9967,-1.8833444 0.5664,-0.47271 1.296,-0.70388 2.0313,-0.64366 0.7353,-0.06022 1.4648,0.17095 2.0312,0.64366 0.5665,0.4727496 0.9245,1.1491472 0.9968,1.8833444 V 23.0711 c 0,0 0,-10.216 0,-10.236 0.0789,-0.7294 0.4396,-1.399 1.0053,-1.8661 0.5657,-0.467 1.2916,-0.6945 2.0227,-0.6339 0.7353,-0.0602 1.4648,0.1709 2.0312,0.6437 0.5665,0.4727 0.9245,1.1491 0.9968,1.8833 v 11.449 c 0,-0.042 0,-4.8 0.032,-5.082 0.05,-1.094 1.374,-2.159 3,-2.159 0.7353,-0.0602 1.4648,0.1709 2.0312,0.6437 0.5665,0.4727 0.9245,1.1491 0.9968,1.8833 v 13.454 c 0.019,1.1072 -0.36,2.1845 -1.068,3.036 -1.6427,2.0541 -3.7744,3.6632 -6.2,4.68 -4.239,1.516 -8.03,1.652 -13.322,0.004 z"
        private const val FINGER_SLASH = "M 9.3700024,13.955 43.37,41.955"

        
        private const val COPY_TO_VIEWBOX = 48f
        private const val COPY_TO_CARD_BACK =
            "M43 14H15C14.4477 14 14 14.4477 14 15V43C14 43.5523 14.4477 44 15 44H43C43.5523 44 44 43.5523 44 43V15C44 14.4477 43.5523 14 43 14Z"
        private const val COPY_TO_CARD_FRONT =
            "M33 4H5C4.44772 4 4 4.44772 4 5V33C4 33.5523 4.44772 34 5 34H33C33.5523 34 34 33.5523 34 33V5C34 4.44772 33.5523 4 33 4Z"
        private val COPY_TO_WHITEBOARD = arrayOf(
            "M27 18.3V4L6 4V26H20.3182",
            "M25 18H42V43H20V24",
            "M14 32H6V43H14V32Z",
            "M42 4H34V12H42V4Z",
        )
    }

    interface Listener {
        fun onOpenMenu()
        fun onSync()
        fun onToggleTranslucent()
        fun onZoomStep(direction: Int)
        fun onZoomReset()
        fun onToggleTouch()
        fun onSetWhiteboard()
        fun onDeleteWhiteboard()
        fun onDeleteSelectedCard()
        fun onToggleCardAccent()
        fun onApplySizeLevel(level: BoardGeometry.SizeLevel)
        fun onDeleteLassoSelection()
        fun onClose()
        fun onJumpToRegion(region: SparseNavigation.Region)
        fun onNavigateWhiteboard(id: String)
        
        fun onCaptureWhiteboard(id: String)
        fun onSwitcherDismissed()
    }

    var listener: Listener? = null

    private val zh = Locale.getDefault().language.startsWith("zh")
    private fun t(zhText: String, enText: String) = if (zh) zhText else enText

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    private val menuButton = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageTintList = null
        clearColorFilter()
        contentDescription = t("菜单", "Menu")
        background = null
        isClickable = true
        isFocusable = true
    }
    private val zoomOut = textButton("－", 22f, bordered = false)
    private val zoomReadout = textButton("100%", 15f, bordered = false)
    private val zoomIn = textButton("＋", 22f, bordered = false)
    
    private val touchButton = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageTintList = null
        clearColorFilter()
        contentDescription = t("手触", "Touch")
        background = null
        isClickable = true
        isFocusable = true
    }
    private val setWhiteboardButton = textButton(t("设白板", "Set Board"), 17f)
    private val deleteWhiteboardButton = textButton(t("删白板", "Delete Board"), 17f)
    private val deleteCardButton = textButton(t("删除卡片", "Delete Card"), 17f)
    private val accentCardButton = textButton(t("强调色", "Accent"), 17f)
    private val sizeLevelButtons = ArrayList<TextView>()
    private val lassoEditButton = textButton(t("编辑", "Edit"), 17f).apply { alpha = 0.35f; isEnabled = false }
    private val lassoDeleteButton = textButton(t("删除卡片", "Delete Card"), 17f)
    private val closePluginButton = textButton(t("关闭插件", "Close"), 17f)
    private val syncButton = textButton(t("同步", "Sync"), 17f)
    
    private val translucentButton = textButton(t("半透明", "Translucent"), 17f).apply { alpha = 0.5f }

    private val toolsRow = LinearLayout(context)
    private val regionJumpViews = HashMap<SparseNavigation.Direction, RegionJumpView>()

    private val switcherRoot = FrameLayout(context)
    private val switcherPanel = LinearLayout(context)
    private val switcherList = LinearLayout(context)
    private val switcherEmpty = TextView(context)

    private var touchEnabled = false
    private var translucentActive = false

    init {
        buildMenuButton()
        buildTouchButton()
        buildSwitcher()
        isClickable = false
        setWillNotDraw(true)
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

    private fun buildMenuButton() {
        val size = dp(MENU_SIZE_DP)
        menuButton.setImageDrawable(rasterizeCopyToIcon(size))
        addView(menuButton, LayoutParams(size, size, Gravity.START or Gravity.BOTTOM).apply {
            leftMargin = dp(MENU_MARGIN_DP)
            bottomMargin = dp(MENU_MARGIN_DP)
        })
        menuButton.setOnClickListener { listener?.onOpenMenu() }
    }

    
    private fun buildTouchButton() {
        val size = dp(TOUCH_SIZE_DP)
        touchButton.setImageDrawable(rasterizeFingerIcon(size, touchEnabled))
        addView(touchButton, LayoutParams(size, size, Gravity.START or Gravity.BOTTOM).apply {
            leftMargin = dp(MENU_MARGIN_DP)
            bottomMargin = dp(MENU_MARGIN_DP) + dp(MENU_SIZE_DP) + dp(12f)
        })
        touchButton.setOnClickListener { listener?.onToggleTouch() }
    }

    
    private fun rasterizeFingerIcon(sizePx: Int, enabled: Boolean): BitmapDrawable {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(sizePx / FINGER_VIEWBOX, sizePx / FINGER_VIEWBOX)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = INK
            strokeWidth = FLOAT_ICON_STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val hand = SvgPathParser.parse(FINGER_HAND)
        canvas.drawPath(hand, fill)
        canvas.drawPath(hand, stroke)
        if (!enabled) canvas.drawPath(SvgPathParser.parse(FINGER_SLASH), stroke)
        return BitmapDrawable(resources, bmp)
    }

    
    private fun rasterizeCopyToIcon(sizePx: Int): BitmapDrawable {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(sizePx / COPY_TO_VIEWBOX, sizePx / COPY_TO_VIEWBOX)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = INK
            strokeWidth = FLOAT_ICON_STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val back = SvgPathParser.parse(COPY_TO_CARD_BACK)
        val front = SvgPathParser.parse(COPY_TO_CARD_FRONT)
        canvas.drawPath(back, fill)
        canvas.drawPath(back, stroke)
        canvas.drawPath(front, fill)
        canvas.drawPath(front, stroke)
        canvas.save()
        canvas.translate(8f, 8f)
        canvas.scale(0.4583f, 0.4583f)
        for (data in COPY_TO_WHITEBOARD) {
            canvas.drawPath(SvgPathParser.parse(data), stroke)
        }
        canvas.restore()
        return BitmapDrawable(resources, bmp)
    }

    
    fun toolbarHeightPx(): Int = 0

    fun menuButtonRectPx(out: Rect): Rect {
        val size = dp(MENU_SIZE_DP)
        val margin = dp(MENU_MARGIN_DP)
        val left = margin
        val top = if (height > 0) height - margin - size else margin
        out.set(left, top, left + size, top + size)
        return out
    }

    fun setZoom(scale: Float) {
        zoomReadout.text = "${Math.round(BoardGeometry.zoomPercentForScale(scale))}%"
        val atMin = scale <= BoardGeometry.ZOOM_LEVELS.first() + 1e-6f
        val atMax = scale >= BoardGeometry.ZOOM_LEVELS.last() - 1e-6f
        zoomOut.alpha = if (atMin) 0.3f else 1f
        zoomOut.isEnabled = !atMin
        zoomIn.alpha = if (atMax) 0.3f else 1f
        zoomIn.isEnabled = !atMax
    }

    fun setTouchEnabled(enabled: Boolean) {
        if (touchEnabled == enabled) return
        touchEnabled = enabled
        touchButton.setImageDrawable(rasterizeFingerIcon(dp(TOUCH_SIZE_DP), enabled))
    }

    
    fun setTranslucentActive(active: Boolean) {
        if (translucentActive == active) return
        translucentActive = active
        translucentButton.background = borderDrawable(if (active) INK else Color.WHITE)
        translucentButton.setTextColor(if (active) Color.WHITE else INK)
    }

    fun setMode(
        currentWhiteboard: Boolean,
        selectedCard: Boolean,
        selectedCardColored: Boolean,
        sizeLevels: List<BoardGeometry.SizeLevel>,
        lassoCards: Boolean,
    ) {
        val plain = !selectedCard && !lassoCards
        setWhiteboardButton.visibility = if (plain && !currentWhiteboard) View.VISIBLE else View.GONE
        deleteWhiteboardButton.visibility = if (plain && currentWhiteboard) View.VISIBLE else View.GONE
        deleteCardButton.visibility = if (selectedCard) View.VISIBLE else View.GONE
        accentCardButton.visibility = if (selectedCard) View.VISIBLE else View.GONE
        
        accentCardButton.text = if (selectedCardColored) t("默认卡", "Default") else t("强调色", "Accent")
        lassoEditButton.visibility = if (lassoCards) View.VISIBLE else View.GONE
        lassoDeleteButton.visibility = if (lassoCards) View.VISIBLE else View.GONE

        for (b in sizeLevelButtons) toolsRow.removeView(b)
        sizeLevelButtons.clear()
        if (selectedCard) {
            val insertAt = toolsRow.indexOfChild(accentCardButton) + 1
            sizeLevels.forEachIndexed { index, level ->
                val label = when {
                    level.factor < 1f -> t("½ 邻卡", "½ neighbor")
                    level.factor < 3f -> t("2× 邻卡", "2× neighbor")
                    else -> t("4× 邻卡", "4× neighbor")
                }
                val b = textButton(label, 17f)
                b.setOnClickListener { listener?.onApplySizeLevel(level) }
                toolsRow.addView(
                    b,
                    insertAt + index,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48f)).apply { marginEnd = dp(10f) },
                )
                sizeLevelButtons.add(b)
            }
        }
    }

    fun toolbarContains(xPx: Float, yPx: Float): Boolean = menuButtonContains(xPx, yPx)

    private fun menuButtonContains(xPx: Float, yPx: Float): Boolean {
        if (menuButton.visibility != View.VISIBLE || switcherOpen) return false
        return xPx >= menuButton.left && xPx <= menuButton.right &&
            yPx >= menuButton.top && yPx <= menuButton.bottom
    }

    private fun touchButtonContains(xPx: Float, yPx: Float): Boolean {
        if (touchButton.visibility != View.VISIBLE || switcherOpen) return false
        return xPx >= touchButton.left && xPx <= touchButton.right &&
            yPx >= touchButton.top && yPx <= touchButton.bottom
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
        val menuReserve = dp(MENU_SIZE_DP) + dp(MENU_MARGIN_DP) * 2
        return when (dir) {
            SparseNavigation.Direction.LEFT -> LayoutParams(size, size, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = margin }
            SparseNavigation.Direction.RIGHT -> LayoutParams(size, size, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = margin }
            SparseNavigation.Direction.UP -> LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = margin }
            SparseNavigation.Direction.DOWN -> LayoutParams(size, size, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = margin }
        }.also {
            
            if (dir == SparseNavigation.Direction.DOWN) it.leftMargin = menuReserve
        }
    }

    fun regionJumpContains(xPx: Float, yPx: Float): Boolean {
        for (v in regionJumpViews.values) {
            if (xPx >= v.left && xPx <= v.right && yPx >= v.top && yPx <= v.bottom) return true
        }
        return false
    }

    

    private fun buildSwitcher() {
        switcherRoot.setBackgroundColor(0x33000000)
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
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(context).apply {
                text = t("菜单", "Menu")
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(INK)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            closePluginButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48f)).apply { marginEnd = dp(10f) },
        )
        val dismiss = textButton(t("关闭", "Close"), 17f)
        dismiss.setOnClickListener { listener?.onSwitcherDismissed() }
        header.addView(dismiss, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48f)))
        switcherPanel.addView(header)

        val zoomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = borderDrawable(Color.WHITE)
            setPadding(dp(2f), 0, dp(2f), 0)
        }
        zoomReadout.minWidth = dp(58f)
        zoomBar.addView(zoomOut, LinearLayout.LayoutParams(dp(40f), dp(38f)))
        zoomBar.addView(zoomReadout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38f)))
        zoomBar.addView(zoomIn, LinearLayout.LayoutParams(dp(40f), dp(38f)))

        val toolsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        toolsRow.orientation = LinearLayout.HORIZONTAL
        toolsRow.gravity = Gravity.CENTER_VERTICAL
        toolsRow.addView(zoomBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10f) })
        for (button in listOf(syncButton, translucentButton, setWhiteboardButton, deleteWhiteboardButton, deleteCardButton, accentCardButton, lassoEditButton, lassoDeleteButton)) {
            toolsRow.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48f)).apply { marginEnd = dp(10f) })
        }
        toolsScroll.addView(toolsRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        switcherPanel.addView(toolsScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12f)
        })

        zoomOut.setOnClickListener { listener?.onZoomStep(-1) }
        zoomReadout.setOnClickListener { listener?.onZoomReset() }
        zoomIn.setOnClickListener { listener?.onZoomStep(1) }
        syncButton.setOnClickListener { listener?.onSync() }
        translucentButton.setOnClickListener { listener?.onToggleTranslucent() }
        setWhiteboardButton.setOnClickListener { listener?.onSetWhiteboard() }
        deleteWhiteboardButton.setOnClickListener { listener?.onDeleteWhiteboard() }
        deleteCardButton.setOnClickListener { listener?.onDeleteSelectedCard() }
        accentCardButton.setOnClickListener { listener?.onToggleCardAccent() }
        lassoDeleteButton.setOnClickListener { listener?.onDeleteLassoSelection() }
        closePluginButton.setOnClickListener { listener?.onClose() }

        switcherPanel.addView(TextView(context).apply {
            text = t("白板", "Whiteboards")
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            setPadding(0, dp(16f), 0, dp(8f))
        })

        switcherEmpty.text = t("暂无白板，请先「设白板」", "No whiteboards yet. Use “Set Board” first.")
        switcherEmpty.textSize = 16f
        switcherEmpty.setTextColor(MUTED)
        switcherPanel.addView(switcherEmpty)

        val scroll = ScrollView(context)
        switcherList.orientation = LinearLayout.VERTICAL
        scroll.addView(switcherList, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        switcherPanel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })

        setMode(currentWhiteboard = false, selectedCard = false, selectedCardColored = false, sizeLevels = emptyList(), lassoCards = false)
        setZoom(BoardGeometry.DEFAULT_ZOOM)
    }

    val switcherOpen: Boolean get() = switcherRoot.visibility == View.VISIBLE

    fun showSwitcher(whiteboards: List<BoardEngine.WhiteboardRec>) {
        switcherList.removeAllViews()
        switcherEmpty.visibility = if (whiteboards.isEmpty()) View.VISIBLE else View.GONE
        for (wb in whiteboards) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10f), 0, dp(10f))
                isClickable = true
                setOnClickListener { listener?.onNavigateWhiteboard(wb.id) }
            }
            val name = displayName(wb.name)
            val thumb = TextView(context).apply {
                text = name.take(2)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(INK)
                gravity = Gravity.CENTER
                background = borderDrawable(Color.WHITE, 2f, 6f)
            }
            row.addView(thumb, LinearLayout.LayoutParams(dp(56f), dp(56f)).apply { marginEnd = dp(14f) })
            val meta = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            meta.addView(TextView(context).apply {
                text = name
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(INK)
            })
            meta.addView(TextView(context).apply {
                text = "(${Math.round(wb.x)}, ${Math.round(wb.y)}) ${Math.round(wb.width)}×${Math.round(wb.height)}"
                textSize = 13f
                setTextColor(MUTED)
            })
            row.addView(meta, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val capture = textButton(t("截图入笔记", "Snapshot to note"), 15f)
            capture.contentDescription = t("截图入笔记", "Snapshot into note")
            capture.setOnClickListener { listener?.onCaptureWhiteboard(wb.id) }
            row.addView(capture, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48f)).apply { marginStart = dp(10f) })
            switcherList.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val maxHeight = (height * 0.72f).toInt().coerceAtLeast(dp(240f))
        (switcherPanel.layoutParams as LayoutParams).height = maxHeight
        menuButton.visibility = View.GONE
        switcherRoot.visibility = View.VISIBLE
        bringChromeToFront()
    }

    fun hideSwitcher() {
        switcherRoot.visibility = View.GONE
        menuButton.visibility = View.VISIBLE
    }

    private fun bringChromeToFront() {
        if (!switcherOpen) {
            menuButton.bringToFront()
            touchButton.bringToFront()
        }
        if (switcherOpen && indexOfChild(switcherRoot) != childCount - 1) switcherRoot.bringToFront()
    }

    private fun displayName(name: String): String {
        val number = BoardGeometry.defaultWhiteboardNumber(name)
        return if (number == null) name else t("白板 $number", "Whiteboard $number")
    }

    fun whiteboardDisplayName(name: String): String = displayName(name).ifEmpty { t("白板", "Whiteboard") }

    fun newWhiteboardName(existing: Collection<BoardEngine.WhiteboardRec>): String {
        val n = BoardGeometry.nextWhiteboardNumber(existing)
        return t("白板 $n", "Whiteboard $n")
    }

    
    fun consumesPoint(xPx: Float, yPx: Float): Boolean =
        switcherOpen || menuButtonContains(xPx, yPx) || touchButtonContains(xPx, yPx) || regionJumpContains(xPx, yPx)
}
