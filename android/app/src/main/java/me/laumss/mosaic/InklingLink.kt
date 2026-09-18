package me.laumss.mosaic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.util.Log


object InklingLink {

    private const val TAG = "MosaicInklingLink"

    
    const val ACTION_BOARD_STATE = "me.laumss.mosaic.BOARD_STATE"

    
    const val ACTION_TOOLBAR_RECT = "me.laumss.mosaic.INKLING_TOOLBAR_RECT"

    
    const val ACTION_OVERLAY_RECTS = "me.laumss.mosaic.INKLING_OVERLAY_RECTS"
    
    
    const val ACTION_REQUEST = "me.laumss.mosaic.INKLING_REQUEST"

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
    }

    @Volatile private var toolbarRect: Rect? = null
    @Volatile private var overlayRects: List<Rect> = emptyList()
    @Volatile private var boardVisible = false
    @Volatile private var surface = SURFACE_BOARD
    @Volatile private var noteRef: String? = null

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
                    ACTION_REQUEST -> onRequest(intent)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_TOOLBAR_RECT)
            addAction(ACTION_OVERLAY_RECTS)
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
            toolbarRect = null
            overlayRects = emptyList()
        }
        publishState(reason)
    }

    fun setSurface(name: String, ref: String?, reason: String) {
        val next = if (name == SURFACE_NOTE) SURFACE_NOTE else SURFACE_BOARD
        if (surface == next && noteRef == ref) return
        surface = next
        noteRef = ref
        publishState(reason)
    }

    fun currentSurface(): String = surface

    fun currentNoteRef(): String? = noteRef

    private fun publishState(reason: String) {
        val ctx = appContext ?: return
        try {
            ctx.sendBroadcast(
                Intent(ACTION_BOARD_STATE).apply {
                    setPackage(ctx.packageName)
                    putExtra("visible", boardVisible)
                    putExtra("surface", surface)
                    putExtra("noteRef", noteRef)
                    putExtra("reason", reason)
                },
            )
            Log.i(TAG, "state published visible=$boardVisible surface=$surface noteRef=$noteRef reason=$reason")
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

    private fun onRequest(intent: Intent) {
        when (val cmd = intent.getStringExtra("cmd")) {
            "close" -> host?.onInklingCloseRequested()
            "sync" -> publishState("sync-request")
            "paste_strokes" -> host?.onInklingPasteStrokesRequested()
            "clear_selection" -> host?.onInklingClearSelectionRequested(delete = false)
            "delete_selection" -> host?.onInklingClearSelectionRequested(delete = true)
            "text_card" -> {
                val text = intent.getStringExtra("text") ?: ""
                val x = intent.getIntExtra("anchorScreenX", 0)
                val y = intent.getIntExtra("anchorScreenY", 0)
                if (text.isNotEmpty()) host?.onInklingTextCardRequested(text, x, y)
            }
            "inbox" -> host?.onInklingInboxReady()
            else -> Log.i(TAG, "unknown request cmd=$cmd")
        }
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
