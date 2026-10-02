package me.laumss.mosaic

import android.content.Context
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout


class CardTextEditor(
    context: Context,
    initial: String,
    private val dark: Boolean,
    
    private val fill: Int = if (dark) Color.BLACK else BoardContentView.CARD_FILL_COLOR,
    
    private val contentScale: Float = 1f,
    
    private val headerMode: Boolean = false,
    private val cardFrame: (availableBottom: Int) -> RectF,
    
    private val onKeyboard: (obscuredTop: Int?) -> Unit,
    private val onChange: (String) -> Unit,
    private val onFinish: () -> Unit,
) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(n: Float) = (n * d + 0.5f).toInt()
    private val document = CardTextFormat.Document(initial)
    private var styling = false
    private var closed = false
    private var keyboardSeen = false
    private var textScale = 1f
    private val caretPaint = Paint()
    private val input = object : EditText(context) {
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val layout = layout ?: return
            val offset = selectionStart
            if (!isFocused || offset < 0 || offset != selectionEnd) return
            val line = layout.getLineForOffset(offset)
            val metrics = paint.fontMetrics
            val baseline = extendedPaddingTop + layout.getLineBaseline(line).toFloat()
            val x = compoundPaddingLeft + layout.getPrimaryHorizontal(offset) - scrollX
            val w = maxOf(2f, 1.5f * d)
            caretPaint.color = currentTextColor
            canvas.drawRect(x, baseline + metrics.ascent, x + w, baseline + metrics.descent, caretPaint)
        }
        override fun onSelectionChanged(start: Int, end: Int) {
            super.onSelectionChanged(start, end)
            if (!styling) post { if (!closed) { if (headerMode) renderHeaderStyles() else updateButtons() } }
        }
        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onFinish()
                return true
            }
            return super.onKeyPreIme(keyCode, event)
        }
    }
    private val buttons = mutableMapOf<String, FormatIcon>()
    
    private val dock = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(11f), dp(9f), dp(11f), dp(9f)) 
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(dp(1f), 0xffcccccc.toInt())
            cornerRadius = 28f * d
        }
        elevation = 4f * d
    }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { positionOnCanvas() }

    init {
        
        input.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            setTextColor(if (dark) Color.WHITE else Color.BLACK)
            setBackgroundColor(fill)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, CardTextFormat.BODY_SIZE * d)
            includeFontPadding = false
            isCursorVisible = false
            setLineSpacing(0f, CardTextFormat.WEB.lineMul)
            setText(if (headerMode) initial else document.text())
            setSelection(length())
            if (headerMode) filters = arrayOf(headerLineFilter())
            isVerticalScrollBarEnabled = true
        }
        addView(input, LayoutParams(1, 1))
        fun tool(kind: String, label: String) {
            val icon = FormatIcon(context, kind).apply {
                contentDescription = label
                isClickable = true
                isFocusable = true
                
                isFocusableInTouchMode = false
                setOnClickListener {
                    document.toggle(input.selectionStart.coerceAtLeast(0), kind)
                    renderStyles()
                    updateButtons()
                    onChange(document.markdown())
                }
            }
            buttons[kind] = icon
            dock.addView(icon, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply {
                leftMargin = dp(2f); rightMargin = dp(2f)
            })
        }
        tool("heading", "标题")
        tool("bold", "粗体")
        tool("italic", "斜体")
        tool("black", "黑色背景")
        dock.addView(View(context).apply { setBackgroundColor(0xffcccccc.toInt()) },
            LinearLayout.LayoutParams(dp(1f), dp(24f)).apply { leftMargin = dp(6f); rightMargin = dp(6f) })
        tool("outdent", "减少缩进")
        tool("indent", "增加缩进")
        addView(dock, LayoutParams(LayoutParams.WRAP_CONTENT, dp(58f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(24f)
        })
        if (headerMode) dock.visibility = View.GONE
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!styling && !headerMode) document.replace(start, before, s?.subSequence(start, start + count)?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {
                if (styling) return
                if (headerMode) {
                    renderHeaderStyles()
                    onChange(input.text.toString())
                } else {
                    renderStyles()
                    updateButtons()
                    onChange(document.markdown())
                }
                
                post { if (!closed) positionOnCanvas() }
            }
        })
        if (headerMode) renderHeaderStyles() else { renderStyles(); updateButtons() }
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        
        if (event.actionMasked == MotionEvent.ACTION_UP) onFinish()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (context as? Activity)?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        setOnApplyWindowInsetsListener { _, insets ->
            post { if (!closed) positionOnCanvas() }
            insets
        }
        if (Build.VERSION.SDK_INT >= 23) requestApplyInsets()
        post {
            if (closed || !isAttachedToWindow) return@post
            positionOnCanvas()
            input.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun release() {
        if (closed) return
        closed = true
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(windowToken, 0)
        input.clearFocus()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun positionOnCanvas() {
        if (closed || width == 0 || height == 0) return
        val ime = keyboardInsetPx()
        val bottom = (height - ime).coerceIn(0, height)
        val keyboard = ime > dp(120f)
        if (keyboard) keyboardSeen = true
        else if (keyboardSeen) { onFinish(); return }
        val dockParams = dock.layoutParams as LayoutParams
        val margin = height - bottom + dp(24f)
        if (dockParams.bottomMargin != margin) {
            dockParams.bottomMargin = margin
            dock.layoutParams = dockParams
        }
        val available = (bottom - dp(58f + 24f + 16f)).coerceAtLeast(1)
        onKeyboard(if (keyboard) available else null)
        if (closed) return
        val rect = cardFrame(available)
        if (closed) return
        val params = input.layoutParams as LayoutParams
        val w = rect.width().toInt().coerceAtLeast(1)
        val h = rect.height().toInt().coerceAtLeast(1)
        if (params.leftMargin != rect.left.toInt() || params.topMargin != rect.top.toInt() || params.width != w || params.height != h) {
            params.leftMargin = rect.left.toInt(); params.topMargin = rect.top.toInt()
            params.width = w; params.height = h
            input.layoutParams = params
        }
        val scale = BoardEngine.scale * d * contentScale
        if (textScale != scale) { textScale = scale; if (headerMode) renderHeaderStyles() else renderStyles() }
        CardTextFormat.WEB.let { m ->
            input.setPadding((m.padX * scale).toInt(), (m.padTop * scale).toInt(), (m.padX * scale).toInt(), (m.padBottom * scale).toInt())
        }
    }

    
    private fun keyboardInsetPx(): Int {
        
        
        val resizedBySystem = rootView.height - height > dp(120f)
        if (resizedBySystem) return 0
        if (Build.VERSION.SDK_INT >= 30) {
            val ime = rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
            if (ime > dp(40f)) return ime
        }
        val visible = Rect()
        getWindowVisibleDisplayFrame(visible)
        val location = IntArray(2)
        getLocationOnScreen(location)
        return (location[1] + rootView.height - visible.bottom).coerceAtLeast(0)
            .coerceAtMost(height)
    }

    private fun updateButtons() {
        val style = document.lines[document.rowAt(input.selectionStart)].style
        buttons.forEach { (kind, button) -> button.active = style.active(kind) }
    }

    
    private fun renderStyles() {
        styling = true
        try {
            val editable = input.text
            editable.getSpans(0, editable.length, EditorSpan::class.java).forEach { editable.removeSpan(it) }
            var start = 0
            for (line in document.lines) {
                val end = start + line.text.length
                val style = line.style
                if (end > start) {
                    fun span(value: EditorSpan) = editable.setSpan(value, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    span(Size((CardTextFormat.textSize(style.heading) * textScale).toInt()))
                    val face = (if (style.bold || style.heading > 0) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0)
                    span(Face(face))
                    
                    if (style.black) { span(BlackLine(if (dark) Color.WHITE else Color.BLACK)); span(Ink(if (dark) Color.BLACK else Color.WHITE)) }
                    span(Indent((style.indent.length * CardTextFormat.BODY_SIZE * 0.25f * textScale).toInt()))
                }
                start = end + 1
            }
            val current = document.lines[document.rowAt(input.selectionStart)].style
            input.setTextSize(TypedValue.COMPLEX_UNIT_PX, CardTextFormat.textSize(current.heading) * textScale)
        } finally { styling = false }
    }
    
    
    private fun headerLineFilter() = InputFilter { source, start, end, dest, dstart, dend ->
        val inserted = source.subSequence(start, end)
        if (inserted.indexOf('\n') < 0) return@InputFilter null
        val rest = dest.subSequence(0, dstart).toString() + dest.subSequence(dend, dest.length)
        var allowed = if (rest.contains('\n')) 0 else 1
        val kept = StringBuilder()
        for (c in inserted) {
            if (c != '\n') kept.append(c) else if (allowed > 0) { kept.append(c); allowed-- }
        }
        kept
    }

    
    private fun renderHeaderStyles() {
        styling = true
        try {
            val editable = input.text
            editable.getSpans(0, editable.length, EditorSpan::class.java).forEach { editable.removeSpan(it) }
            val split = editable.indexOf('\n').let { if (it < 0) editable.length else it }
            val titleSize = CardTextFormat.textSize(1) * textScale
            val bodySize = CardTextFormat.BODY_SIZE * textScale
            if (split > 0) {
                editable.setSpan(Size(titleSize.toInt()), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                editable.setSpan(Face(Typeface.BOLD), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (split + 1 < editable.length) {
                editable.setSpan(Size(bodySize.toInt()), split + 1, editable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val onTitle = input.selectionStart <= split
            input.setTextSize(TypedValue.COMPLEX_UNIT_PX, if (onTitle) titleSize else bodySize)
        } finally { styling = false }
    }

    private interface EditorSpan
    private class Size(size: Int) : AbsoluteSizeSpan(size), EditorSpan
    private class Face(style: Int) : StyleSpan(style), EditorSpan
    private class Ink(color: Int) : ForegroundColorSpan(color), EditorSpan
    private class Indent(width: Int) : LeadingMarginSpan.Standard(width), EditorSpan
    private class BlackLine(private val fill: Int) : LineBackgroundSpan, EditorSpan {
        override fun drawBackground(c: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
                                    text: CharSequence, start: Int, end: Int, lineNumber: Int) {
            val color = p.color
            p.color = fill
            c.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), p)
            p.color = color
        }
    }

    private class FormatIcon(context: Context, private val kind: String) : View(context) {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val asset = when (kind) {
            "heading" -> resources.getDrawable(R.drawable.card_format_heading, context.theme)
            "black" -> resources.getDrawable(R.drawable.card_format_black, context.theme)
            else -> null
        }
        var active = false
            set(value) { field = value; isSelected = value; invalidate() }
        override fun onDraw(canvas: Canvas) {
            val save = canvas.save()
            canvas.scale(width / 40f, height / 40f)
            if (active || isPressed) {
                pen.color = Color.BLACK; pen.style = Paint.Style.FILL
                canvas.drawCircle(20f, 20f, 20f, pen)
            }
            val color = if (active || isPressed) Color.WHITE else Color.BLACK
            if (asset != null) {
                asset.setTint(color); asset.setBounds(9, 9, 31, 31); asset.draw(canvas)
            } else {
                canvas.translate(9f, 9f) 
                pen.color = color; pen.style = Paint.Style.STROKE; pen.strokeWidth = 2f
                pen.strokeCap = Paint.Cap.ROUND; pen.strokeJoin = Paint.Join.ROUND
                path.reset()
                when (kind) {
                    "bold" -> {
                        path.moveTo(5f, 3f); path.lineTo(12f, 3f)
                        path.cubicTo(19f, 3f, 19f, 11f, 12f, 11f)
                        path.lineTo(5f, 11f); path.lineTo(12f, 11f)
                        path.cubicTo(20f, 11f, 20f, 19f, 12f, 19f)
                        path.lineTo(5f, 19f); path.close()
                    }
                    "italic" -> {
                        path.moveTo(9f, 3f); path.lineTo(18f, 3f)
                        path.moveTo(14f, 3f); path.lineTo(8f, 19f)
                        path.moveTo(4f, 19f); path.lineTo(13f, 19f)
                    }
                    else -> {
                        for (y in listOf(3f, 19f)) { path.moveTo(2f, y); path.lineTo(20f, y) }
                        for (y in listOf(8f, 14f)) { path.moveTo(12f, y); path.lineTo(20f, y) }
                        val a = if (kind == "indent") 3f else 7f
                        val b = if (kind == "indent") 7f else 3f
                        path.moveTo(a, 7f); path.lineTo(b, 11f); path.lineTo(a, 15f)
                    }
                }
                canvas.drawPath(path, pen)
            }
            canvas.restoreToCount(save)
        }
    }
}
