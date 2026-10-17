package me.laumss.mosaic.inkling

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

object InklingLink {
    private const val TAG = "MosaicInklingLink"

    const val ACTION_BOARD_STATE = "me.laumss.mosaic.BOARD_STATE"

    const val ACTION_TOOLBAR_RECT = "me.laumss.mosaic.INKLING_TOOLBAR_RECT"

    const val ACTION_OVERLAY_RECTS = "me.laumss.mosaic.INKLING_OVERLAY_RECTS"

    /** Notipal 浮窗拖动开始 / 结束，extra `active`。 */
    const val ACTION_OVERLAY_DRAG = "me.laumss.mosaic.INKLING_OVERLAY_DRAG"

    const val ACTION_REQUEST = "me.laumss.mosaic.INKLING_REQUEST"
    const val ACTION_PALETTE_RESULT = "me.laumss.mosaic.PALETTE_RESULT"

    const val SURFACE_BOARD = "board"
    const val SURFACE_NOTE = "note"

    const val CLIP_DIR = "/sdcard/EXPORT/mosaic"

    const val CLIP_LASSO_FILE = "$CLIP_DIR/clip_lasso.json"

    const val PASTE_STROKES_FILE = "$CLIP_DIR/paste_strokes.json"

    interface Host {
        fun onInklingToolbarRectChanged()

        fun onInklingCloseRequested()

        fun onInklingPasteStrokesRequested()

        fun onInklingClearSelectionRequested(delete: Boolean)

        fun onInklingTextCardRequested(text: String, anchorScreenX: Int, anchorScreenY: Int)

        fun onInklingInboxReady()

        /** 调色盘读取选区；data 为非空时按冻结的选区应用笔形。 */
        fun onInklingPaletteRequested(requestId: String, data: String?)

        fun onInklingSurfaceChanged(surface: String, noteRef: String?)

        /** Notipal 浮窗拖动开始 / 结束。 */
        fun onInklingOverlayDragChanged(active: Boolean)
    }

    @Volatile private var toolbarRect: Rect? = null
    @Volatile private var overlayRects: List<Rect> = emptyList()
    @Volatile private var boardVisible = false
    @Volatile private var surface = SURFACE_BOARD
    @Volatile private var noteRef: String? = null
    // 笔粗细 / 图形 / 模板二级菜单或「更多」菜单开着：Notipal 收起它的浮窗。随 BOARD_STATE 的 chromeMenu 发出。
    @Volatile private var chromeMenuOpen = false
    @Volatile private var chromeMenuPublished = false
    private val mainHandler = Handler(Looper.getMainLooper())
    // 换菜单时先关后开发生在同一次点击里，合并到下一轮再发，Notipal 不会看到中间的「已关闭」。
    private val publishChromeMenu = Runnable {
        if (boardVisible && chromeMenuOpen != chromeMenuPublished) publishState("chrome-menu")
    }

    private var host: Host? = null
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    fun attach(context: Context, host: Host) {
        this.host = host
        val app = context.applicationContext
        appContext = app
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_TOOLBAR_RECT -> onToolbarRect(intent)
                    ACTION_OVERLAY_RECTS -> onOverlayRects(intent)
                    ACTION_OVERLAY_DRAG -> onOverlayDrag(intent)
                    ACTION_REQUEST -> onRequest(intent)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_TOOLBAR_RECT)
            addAction(ACTION_OVERLAY_RECTS)
            addAction(ACTION_OVERLAY_DRAG)
            addAction(ACTION_REQUEST)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(r, filter)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "receiver registration failed", error)
            return
        }
        receiver = r
        Log.i(TAG, "inkling link attached")
    }

    fun detach(host: Host) {
        if (this.host !== host) return
        this.host = null

        surface = SURFACE_BOARD
        noteRef = null
        setBoardVisible(false, "detach")
        val app = appContext
        val r = receiver
        if (app != null && r != null) {
            try {
                app.unregisterReceiver(r)
            } catch (_: Throwable) {
            }
        }
        receiver = null
        toolbarRect = null
        overlayRects = emptyList()
        Log.i(TAG, "inkling link detached")
    }

    fun setBoardVisible(visible: Boolean, reason: String) {
        if (boardVisible == visible) return
        boardVisible = visible

        if (!visible) {
            surface = SURFACE_BOARD
            noteRef = null
            toolbarRect = null
            overlayRects = emptyList()
            chromeMenuOpen = false
        }
        publishState(reason)
    }

    fun setSurface(name: String, ref: String?, reason: String) {
        val next = if (name == SURFACE_NOTE) SURFACE_NOTE else SURFACE_BOARD
        if (surface == next && noteRef == ref) return
        surface = next
        noteRef = ref
        // 白板关着时（关闭后在后台恢复上次的笔记）只记下，下次 setBoardVisible(true) 一起发。
        if (boardVisible) publishState(reason)
    }

    /** Mosaic 自己的菜单开关，在主线程调用。 */
    fun setChromeMenuOpen(open: Boolean) {
        chromeMenuOpen = open
        mainHandler.removeCallbacks(publishChromeMenu)
        mainHandler.post(publishChromeMenu)
    }

    fun currentSurface(): String = surface

    fun currentNoteRef(): String? = noteRef

    fun isBoardSurface(): Boolean = boardVisible && surface == SURFACE_BOARD

    // Clipboard strokes work on the board and inside a note card.
    fun isInkSurface(): Boolean = boardVisible && (surface == SURFACE_BOARD || surface == SURFACE_NOTE)

    private fun publishState(reason: String) {
        val ctx = appContext ?: return
        try {
            ctx.sendBroadcast(
                Intent(ACTION_BOARD_STATE).apply {
                    setPackage(ctx.packageName)
                    putExtra("visible", boardVisible)
                    putExtra("surface", surface)
                    putExtra("noteRef", noteRef)
                    putExtra("chromeMenu", boardVisible && chromeMenuOpen)
                    putExtra("reason", reason)
                },
            )
            chromeMenuPublished = boardVisible && chromeMenuOpen
            Log.i(TAG, "state published visible=$boardVisible surface=$surface noteRef=$noteRef " +
                "chromeMenu=$chromeMenuPublished reason=$reason")
        } catch (error: Throwable) {
            Log.e(TAG, "state publish failed", error)
        }
    }

    private fun onToolbarRect(intent: Intent) {
        val next = if (intent.getBooleanExtra("present", false)) {
            Rect(
                intent.getIntExtra("left", 0),
                intent.getIntExtra("top", 0),
                intent.getIntExtra("right", 0),
                intent.getIntExtra("bottom", 0),
            ).takeIf { !it.isEmpty }
        } else {
            null
        }
        if (next == toolbarRect) return
        toolbarRect = next
        Log.i(TAG, "toolbar rect=$next")
        host?.onInklingToolbarRectChanged()
    }

    private fun onOverlayRects(intent: Intent) {
        val count = intent.getIntExtra("count", 0)
        val next = if (count <= 0) {
            emptyList()
        } else {
            val l = intent.getIntArrayExtra("left")
            val t = intent.getIntArrayExtra("top")
            val r = intent.getIntArrayExtra("right")
            val b = intent.getIntArrayExtra("bottom")
            if (l == null || t == null || r == null || b == null ||
                l.size < count || t.size < count || r.size < count || b.size < count
            ) {
                emptyList()
            } else {
                (0 until count)
                    .map { Rect(l[it], t[it], r[it], b[it]) }
                    .filter { !it.isEmpty }
            }
        }
        if (next == overlayRects) return
        overlayRects = next
        Log.i(TAG, "overlay rects=$next")

        host?.onInklingToolbarRectChanged()
    }

    private fun onOverlayDrag(intent: Intent) {
        val active = intent.getBooleanExtra("active", false)
        Log.i(TAG, "overlay drag active=$active")
        host?.onInklingOverlayDragChanged(active)
    }

    private fun onRequest(intent: Intent) {
        when (val cmd = intent.getStringExtra("cmd")) {
            "palette_info", "palette_apply" -> {
                val id = intent.getStringExtra("requestId") ?: return
                val currentHost = host
                if (isBoardSurface() && currentHost != null) {
                    currentHost.onInklingPaletteRequested(id,
                        if (cmd == "palette_apply") intent.getStringExtra("data") ?: "{}" else null)
                } else replyPalette(id, org.json.JSONObject().put("success", false).put("error", "surface_changed"))
            }
            "close" -> host?.onInklingCloseRequested()
            "sync" -> publishState("sync-request")
            "paste_strokes" -> {
                if (isInkSurface()) host?.onInklingPasteStrokesRequested()
                else Log.i(TAG, "paste ignored surface=$surface visible=$boardVisible")
            }
            "clear_selection" -> {
                if (isInkSurface()) host?.onInklingClearSelectionRequested(delete = false)
                else Log.i(TAG, "clear-selection ignored surface=$surface visible=$boardVisible")
            }
            "delete_selection" -> {
                if (isInkSurface()) host?.onInklingClearSelectionRequested(delete = true)
                else Log.i(TAG, "delete-selection ignored surface=$surface visible=$boardVisible")
            }
            "text_card" -> {
                val text = intent.getStringExtra("text") ?: ""
                val x = intent.getIntExtra("anchorScreenX", 0)
                val y = intent.getIntExtra("anchorScreenY", 0)
                if (text.isNotEmpty() && isBoardSurface()) host?.onInklingTextCardRequested(text, x, y)
                else if (text.isNotEmpty()) Log.i(TAG, "text-card ignored surface=$surface visible=$boardVisible")
            }
            "inbox" -> {
                if (isBoardSurface()) host?.onInklingInboxReady()
                else Log.i(TAG, "inbox notification deferred surface=$surface visible=$boardVisible")
            }
            else -> Log.i(TAG, "unknown request cmd=$cmd")
        }
    }

    fun replyPalette(requestId: String, result: org.json.JSONObject) {
        val ctx = appContext ?: return
        ctx.sendBroadcast(Intent(ACTION_PALETTE_RESULT).apply {
            setPackage(ctx.packageName)
            putExtra("requestId", requestId)
            putExtra("result", result.toString())
        })
    }

    fun toolbarRect(): Rect? = toolbarRect

    fun toolbarDisableArea(): DrawPathClient.DisableArea? {
        val r = toolbarRect ?: return null
        return DrawPathClient.DisableArea(r.left, r.top, r.width(), r.height())
    }

    fun overlayDisableAreas(): List<DrawPathClient.DisableArea> =
        overlayRects.map { DrawPathClient.DisableArea(it.left, it.top, it.width(), it.height()) }

    fun consumesScreenPoint(screenX: Float, screenY: Float): Boolean {
        toolbarRect?.let {
            if (screenX >= it.left && screenX < it.right && screenY >= it.top && screenY < it.bottom) {
                return true
            }
        }

        for (r in overlayRects) {
            if (screenX >= r.left && screenX < r.right && screenY >= r.top && screenY < r.bottom) {
                return true
            }
        }
        return false
    }
}
