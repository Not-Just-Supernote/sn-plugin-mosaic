package me.laumss.mosaic

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.graphics.Paint
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.floor

/** Native controls and isolated card rendering, synchronized with the desktop web panel. */
@SuppressLint("ViewConstructor")
class EinkLabView(
    context: Context,
    private val renderer: BoardContentView,
    private val syncBackground: () -> Boolean,
    private val onClose: () -> Unit,
) : LinearLayout(context) {
    companion object {
        private const val OWNER = "eink-lab"
        private val MOVING_RECIPE = CardGrayRecipe.MOVING
        private val STATIC_RECIPE = CardGrayRecipe.DEFAULT
    }
    private val mode = MosaicEinkRefreshModule.MODE_A2
    private var localRefresh = false
    private var localRefreshOk: Boolean? = null
    private var localRefreshCount = 0
    private var localRefreshDetail = "waiting"
    private var whiteTestResult = "waiting"
    private val whiteTestStatus = TextView(context).apply {
        text = "松手后自动处理旧位置 · 也可手动对照"
        textSize = 13f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
    }
    private var regalPolicy = "off"
    private var motion = "move"
    private var durationMs = 6_000L
    private var cycles = 3
    private val recipe = STATIC_RECIPE
    private var running = false
    private var blank = false
    private var moved = false
    private var startedAt = 0L
    private var progress = 0f
    private var modeOk = false
    private var effectiveMode: Int? = null
    private var regalOk = false
    private var regalOn = true
    private var fullRefreshOk: Boolean? = null
    private var disposed = false
    @Volatile private var snapshot = "{}"
    private val surface = SampleView(context)
    private val grayRestore = EinkGrayRestore(surface, "lab", eligible = { !disposed && !running && !blank })
    private val status = TextView(context).apply {
        textSize = 14f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
    }
    private val connectionStatus = TextView(context).apply {
        text = "正在连接电脑网页…"
        textSize = 14f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
    }
    private val controls = Controls()
    private val client = EinkLabClient(
        onConfig = { controls.configure(it) },
        onCommand = { controls.command(it) },
        report = { snapshot },
        onConnection = { connected -> post {
            if (!disposed) connectionStatus.text = if (connected) "电脑网页已连接 · 127.0.0.1:18765" else "等待电脑网页 · 可直接使用下方原生控制"
        } },
    )
    private val frame = object : Runnable {
        override fun run() {
            if (!running || disposed) return
            val elapsed = SystemClock.uptimeMillis() - startedAt
            if (elapsed >= durationMs) { progress = 0f; stop(); return }
            if (!moved) { moved = true; applyHardware() }
            val phase = elapsed.toFloat() / durationMs * cycles
            progress = 1f - abs(2f * (phase % 1f) - 1f)
            surface.invalidate()
            postDelayed(this, 33L)
        }
    }

    init {
        android.util.Log.i("MosaicEinkLab", "open transport=native-http build=20261016v moving=A2 idle=DD-E0-76 cleanup=automatic-black-white uid=${android.os.Process.myUid()}")
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        val header = LinearLayout(context)
        fun headerButton(text: String, action: () -> Unit) = TextView(context).apply {
            this.text = text; textSize = 16f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            setOnClickListener { action() }
        }
        header.addView(headerButton("诊断开/关") {
            localRefresh = !localRefresh; if (!localRefresh) surface.cancelRegionRefresh()
            report(); publishConfig()
        }, LayoutParams(dp(110), dp(44)))
        header.addView(status, LayoutParams(0, dp(44), 1f))
        header.addView(headerButton("返回白板") { onClose() }, LayoutParams(dp(110), dp(44)))
        addView(header)
        addView(connectionStatus, LayoutParams(LayoutParams.MATCH_PARENT, dp(34)))
        addView(TextView(context).apply {
            text = "运动：黑白 56/64 · 静止：DD＋E0，B 76%"
            textSize = 14f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(32)))
        fun row(vararg buttons: Pair<String, () -> Unit>) {
            val line = LinearLayout(context)
            for ((label, action) in buttons) line.addView(headerButton(label, action), LayoutParams(0, dp(44), 1f))
            addView(line)
        }
        row("Regal 开" to { stop(); regalPolicy = "on"; applyHardware(); publishConfig() },
            "Regal 关" to { stop(); regalPolicy = "off"; applyHardware(); publishConfig() },
            "Regal 跟随" to { stop(); regalPolicy = "gesture"; applyHardware(); publishConfig() })
        row("移动" to { stop(); motion = "move"; report(); publishConfig() },
            "调整长宽" to { stop(); motion = "resize"; report(); publishConfig() },
            "开始" to { controls.command("run") }, "停止" to { controls.command("stop") },
            "白底观察" to { controls.command("blank") }, "整屏清残影" to { controls.command("clear") })
        val testRow = LinearLayout(context)
        testRow.addView(headerButton("黑色回白对照") { surface.prepareWhiteTest() }, LayoutParams(dp(145), dp(44)))
        testRow.addView(whiteTestStatus, LayoutParams(0, dp(44), 1f))
        addView(testRow)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        post { if (!disposed) { applyHardware(); report(); client.start() } }
    }

    private inner class Controls {
        fun configure(json: String) {
            if (json.length > 4096) return
            val value = runCatching { JSONObject(json) }.getOrNull() ?: return
            post {
                if (disposed) return@post
                if (sameConfiguration(value)) return@post
                val previous = configuration()
                val motionSettingsChanged = listOf("mode", "seconds", "cycles").any {
                    previous.optDouble(it) != value.optDouble(it)
                } || listOf("regal", "motion").any { previous.optString(it) != value.optString(it) }
                if (motionSettingsChanged) { stop(); surface.cancelRegionRefresh() }
                localRefresh = value.optInt("localRefresh", 0) == 1
                if (!localRefresh) surface.cancelRegionRefresh()
                regalPolicy = value.optString("regal", "off").takeIf { it in listOf("on", "off", "gesture") } ?: "off"
                motion = value.optString("motion", "move").takeIf { it in listOf("move", "resize") } ?: "move"
                durationMs = (value.optDouble("seconds", 6.0).takeIf { it.isFinite() } ?: 6.0).coerceIn(1.0, 60.0).times(1000).toLong()
                cycles = value.optInt("cycles", 3).coerceIn(1, 30)
                if (!running) blank = false
                if (motionSettingsChanged) { progress = 0f; applyHardware() }
                surface.invalidate()
                report()
                android.util.Log.i("MosaicEinkLab", "configured mode=$mode regal=$regalPolicy motion=$motion pixels=${recipe.brightPixels}")
            }
        }
        fun command(action: String) {
            post {
                if (disposed) return@post
                when (action) {
                    "run" -> start()
                    "stop" -> stop()
                    "blank" -> { stop(); surface.cancelRegionRefresh(); blank = true; surface.invalidate(); report() }
                    "show" -> { surface.cancelRegionRefresh(); blank = false; surface.invalidate(); report() }
                    "black-white-test" -> surface.prepareWhiteTest()
                    "clear" -> {
                        stop()
                        surface.cancelRegionRefresh()
                        fullRefreshOk = MosaicEinkRefreshModule.requestFullRefresh(this@EinkLabView, "eink-lab")
                        if (fullRefreshOk == true) grayRestore.request("lab-full-refresh")
                        post { applyHardware(); report() }
                    }
                }
            }
        }
    }

    private fun configuration() = JSONObject().apply {
        put("low", "#%06X".format(recipe.low and 0xFFFFFF)); put("high", "#%06X".format(recipe.high and 0xFFFFFF))
        put("brightPixels", recipe.brightPixels); put("mode", mode); put("regal", regalPolicy)
        put("motion", motion); put("seconds", durationMs / 1000.0); put("cycles", cycles)
        put("localRefresh", if (localRefresh) 1 else 0)
    }
    private fun publishConfig() { client.publish(configuration().toString()) }
    private fun sameConfiguration(value: JSONObject): Boolean {
        val current = configuration()
        return listOf("low", "high", "regal", "motion").all {
            current.optString(it).equals(value.optString(it), ignoreCase = true)
        } && listOf("brightPixels", "mode", "seconds", "cycles", "localRefresh").all {
            current.optDouble(it) == value.optDouble(it)
        }
    }

    private fun applyHardware() {
        val result: (Boolean, Int?) -> Unit = { ok, effective ->
            modeOk = ok
            effectiveMode = effective
            // Mode changes can also enable Regal in the host; apply the selected policy last.
            setRegal(regalPolicy != "off" && !(regalPolicy == "gesture" && running && moved))
            report()
        }
        if (running && moved) MosaicEinkRefreshModule.applyLabMode(mode, OWNER, result)
        else MosaicEinkRefreshModule.releaseLabMode(OWNER, result)
    }
    private fun setRegal(enabled: Boolean) {
        regalOn = enabled
        regalOk = EinkRegal.setForLab(context, enabled)
    }
    private fun start() {
        grayRestore.cancel("motion-start")
        removeCallbacks(frame)
        surface.cancelRegionRefresh()
        blank = false; progress = 0f; moved = false; running = true
        startedAt = SystemClock.uptimeMillis()
        localRefreshCount = 0; localRefreshOk = null
        applyHardware()
        surface.invalidate()
        // Keep the first stationary frame in the beginning state, matching the board gesture.
        postDelayed(frame, 33L)
        report()
    }
    private fun stop() {
        val cleanOldPosition = running && moved
        removeCallbacks(frame)
        running = false
        applyHardware()
        if (cleanOldPosition) surface.queueMotionCleanup()
        surface.invalidate()
        report()
    }
    private fun report() {
        snapshot = JSONObject().apply {
            put("mode", mode); put("effectiveMode", effectiveMode ?: JSONObject.NULL); put("modeOk", modeOk)
            put("modePolicy", "motion-a2-host-auto-black-white")
            put("whiteTestResult", whiteTestResult)
            put("driverResult", localRefreshDetail)
            put("cleanupPhase", surface.cleanupPhase)
            put("recipePolicy", "moving-bw56-idle-dd-e0-76")
            put("requestedMode", if (running && moved) mode else JSONObject.NULL)
            put("regalPolicy", regalPolicy); put("regalOn", regalOn); put("regalOk", regalOk)
            put("running", running); put("blank", blank); put("fullRefreshOk", fullRefreshOk ?: JSONObject.NULL)
            put("low", "#%06X".format(recipe.low and 0xFFFFFF)); put("high", "#%06X".format(recipe.high and 0xFFFFFF))
            put("brightPixels", recipe.brightPixels); put("renderer", "Mosaic native")
            val active = if (running && moved) MOVING_RECIPE else recipe
            put("recipePhase", if (running && moved) "moving" else "idle")
            put("displayLow", "#%06X".format(active.low and 0xFFFFFF))
            put("displayHigh", "#%06X".format(active.high and 0xFFFFFF))
            put("displayBrightPixels", active.brightPixels)
            put("displaySampleCount", active.tileSize * active.tileSize)
            put("localRefresh", localRefresh); put("localRefreshOk", localRefreshOk ?: JSONObject.NULL)
            put("localRefreshCount", localRefreshCount)
            put("transport", "native-http")
        }.toString()
        val statusText = "${if (running && moved) "移动 A2" else "停止·宿主模式"} · Regal ${if (regalOn) "开" else "关"} · 只读诊断${if (localRefresh) "开" else "关"}"
        if (status.text.toString() != statusText) status.text = statusText
    }
    fun dispose() {
        if (disposed) return
        grayRestore.cancel("dispose")
        disposed = true
        running = false
        removeCallbacks(frame)
        surface.cancelRegionRefresh()
        EinkRegal.setForLab(context, true)
        MosaicEinkRefreshModule.resetNative(OWNER)
        client.close()
    }
    fun cancelAutomaticCleanup(reason: String) { surface.cancelAutomaticCleanup(reason) }
    fun afterHostRefresh() {
        cancelAutomaticCleanup("host-refresh")
        post { if (!disposed) { applyHardware(); surface.invalidate(); grayRestore.request("manual-key") } }
    }
    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!disposed && visibility != View.VISIBLE) { stop(); surface.cancelRegionRefresh(); setRegal(true); report() }
        else if (!disposed && isAttachedToWindow) applyHardware()
    }

    private inner class SampleView(context: Context) : View(context) {
        private var downX = 0f
        private var downProgress = 0f
        private var lastDrawnBounds: Rect? = null
        private val movementTrail = Region()
        private var cleanupAfterDraw = false
        private val automaticCleanup = EinkTrailCleanup(this, "lab-card") { result ->
            if (!disposed) {
                localRefreshCount++
                localRefreshDetail = result
                localRefreshOk = null
                whiteTestStatus.text = "旧位置已提交黑色回白 · $localRefreshCount 次"
                if (localRefresh) EinkRegionDriver.probe(this, movementTrail)
                report()
            }
        }
        private var testA: Rect? = null
        private var testB: Rect? = null
        private var testPrepared = false
        private var testScheduled = false
        private val testPaint = Paint().apply { color = Color.BLACK; strokeWidth = 1f; textSize = 24f }
        private val whiteTestTask = Runnable {
            testScheduled = false
            val region = testB
            if (!disposed && !running && region != null) {
                whiteTestResult = EinkRegionDriver.testBlackWhite(this, region)
                whiteTestStatus.text = when {
                    whiteTestResult.startsWith("transition:") -> "黑→白测试已返回，请比较 A / B；两段状态已记录"
                    whiteTestResult.startsWith("skip:") -> "测试区仍有内容，请重新移动后再试"
                    else -> "本次请求受阻；详情已记录"
                }
                report()
            }
        }
        private val whiteTestAfterFrame = Runnable {
            if (!disposed && testScheduled) postDelayed(whiteTestTask, 220L)
        }
        fun prepareWhiteTest() {
            if (running || disposed || testScheduled || testPrepared) return
            cancelAutomaticCleanup("manual-comparison")
            val exposed = Region(movementTrail)
            lastDrawnBounds?.let { exposed.op(it, Region.Op.DIFFERENCE) }
            exposed.op(Rect(0, 0, width, height), Region.Op.INTERSECT)
            val iterator = RegionIterator(exposed)
            val item = Rect()
            var best: Rect? = null
            while (iterator.next(item)) {
                if (best == null || item.width().toLong() * item.height() > best.width().toLong() * best.height()) best = Rect(item)
            }
            val bounds = best
            if (bounds == null) {
                whiteTestStatus.text = "先移动卡片，再松手测试"
                return
            }
            val origin = IntArray(2); getLocationOnScreen(origin)
            bounds.offset(origin[0], origin[1]); bounds.inset(16, 32)
            bounds.left = (bounds.left + 7) and -8; bounds.top = (bounds.top + 7) and -8
            bounds.right = bounds.right and -8; bounds.bottom = bounds.bottom and -8
            val cellW = min(160, ((bounds.width() - 16) / 2) and -8)
            val cellH = min(160, bounds.height() and -8)
            if (cellW < 32 || cellH < 32) {
                whiteTestStatus.text = "请把卡片移动远一些，留出对照区域"
                return
            }
            val top = bounds.top + ((bounds.height() - cellH) / 2 and -8)
            testA = Rect(bounds.left, top, bounds.left + cellW, top + cellH)
            testB = Rect(bounds.left + cellW + 16, top, bounds.left + cellW * 2 + 16, top + cellH)
            whiteTestResult = "prepared: A=$testA B=$testB"
            whiteTestStatus.text = "B 将短暂显示黑色，再恢复白色"
            testPrepared = true
            invalidate(); report()
        }
        private fun drawTestMarkers(canvas: Canvas) {
            if (testA == null) return
            val origin = IntArray(2); getLocationOnScreen(origin)
            listOf("A" to testA, "B" to testB).forEach { (label, physical) ->
                physical ?: return@forEach
                val local = Rect(physical).apply { offset(-origin[0], -origin[1]); inset(-4, -4) }
                testPaint.style = Paint.Style.STROKE
                canvas.drawRect(local, testPaint)
                testPaint.style = Paint.Style.FILL
                canvas.drawText(label, local.left.toFloat(), local.top - 6f, testPaint)
            }
            if (testPrepared) {
                testPrepared = false; testScheduled = true
                postOnAnimation(whiteTestAfterFrame)
            }
        }
        val cleanupPhase: String get() = automaticCleanup.phase
        fun queueMotionCleanup() { cleanupAfterDraw = true }
        fun cancelAutomaticCleanup(reason: String) {
            cleanupAfterDraw = false
            automaticCleanup.cancel(reason)
        }
        fun cancelRegionRefresh() {
            cancelAutomaticCleanup("scene-change")
            removeCallbacks(whiteTestTask); removeCallbacks(whiteTestAfterFrame)
            movementTrail.setEmpty(); testA = null; testB = null; testPrepared = false; testScheduled = false
            whiteTestResult = "waiting"
            whiteTestStatus.text = "松手后自动处理旧位置 · 也可手动对照"
        }
        private fun refreshOldBounds(rect: Rect, current: Rect? = null) {
            val exposed = Region(rect)
            current?.let { exposed.op(it, Region.Op.DIFFERENCE) }
            movementTrail.op(exposed, Region.Op.UNION)
        }
        private fun afterSceneDrawn() {
            if (!cleanupAfterDraw) return
            cleanupAfterDraw = false
            val trail = Region(movementTrail)
            automaticCleanup.schedule {
                val exposed = Region(trail)
                if (running || disposed || blank) exposed.setEmpty()
                else {
                    lastDrawnBounds?.let { exposed.op(it, Region.Op.DIFFERENCE) }
                    exposed.op(Rect(0, 0, width, height), Region.Op.INTERSECT)
                }
                exposed
            }
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            if (width <= 0 || height <= 0) return
            if (blank) {
                lastDrawnBounds?.let { refreshOldBounds(it) }; lastDrawnBounds = null
                afterSceneDrawn()
                drawTestMarkers(canvas)
                return
            }
            val density = resources.displayMetrics.density
            val save = canvas.save()
            canvas.scale(density, density)
            val w = width / density
            val h = height / density
            val cardW = min(190f, w * 0.34f)
            val cardH = min(170f, h * 0.5f)
            val x = 22f + if (motion == "move") progress * (w - cardW * 2f - 75f).coerceAtLeast(10f) else 0f
            val y = (h - cardH) * 0.35f
            val extra = if (motion == "resize") progress * min(90f, w * 0.15f) else 0f
            val first = BoardEngine.CardRec("lab-a", x, y, cardW + extra, cardH + extra * 0.4f, 0, content = "Mosaic\n灰阶与残影测试", fontScale = 0.8f)
            val second = BoardEngine.CardRec("lab-b", x + cardW + extra + 28f, y + 30f, cardW, cardH, 1, content = "卡片与连接桥\nABCDEFG 012345", fontScale = 0.8f)
            val activeRecipe = if (running && moved) MOVING_RECIPE else recipe
            renderer.drawEinkLabScene(canvas, RectF(0f, 0f, w, h), listOf(first, second), activeRecipe)
            canvas.restoreToCount(save)
            val union = first.rect().apply { union(second.rect()); inset(-16f, -16f) }
            val currentBounds = Rect(floor(union.left * density).toInt(), floor(union.top * density).toInt(),
                ceil(union.right * density).toInt(), ceil(union.bottom * density).toInt())
            val previousBounds = lastDrawnBounds
            lastDrawnBounds = currentBounds
            if (previousBounds != null && previousBounds != currentBounds) refreshOldBounds(previousBounds, currentBounds)
            afterSceneDrawn()
            drawTestMarkers(canvas)
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grayRestore.cancel("touch-down")
                    stop(); cancelRegionRefresh(); blank = false; moved = false; running = true
                    downX = event.x; downProgress = progress
                    applyHardware(); report()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && abs(event.x - downX) > 1f) {
                        moved = true
                        applyHardware()
                    }
                    progress = (downProgress + (event.x - downX) / (width * 0.5f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stop()
            }
            return true
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
