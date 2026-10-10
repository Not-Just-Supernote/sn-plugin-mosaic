package me.laumss.mosaic

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.Log
import com.facebook.react.bridge.ReactContext
import com.ratta.supernote.pluginlib.modules.PluginModule
import org.json.JSONObject
import java.io.File


object NoteLinks {
    private const val TAG = "MosaicNoteLinks"
    private const val STORE_FILENAME = "note-shots.json"

    
    private const val CORNER_LEN = 44f
    private const val CORNER_STROKE = 5f
    private const val CORNER_OFFSET = 4f
    private const val LABEL_SIZE = 20f
    private const val LABEL_INSET_X = 52f
    private const val LABEL_INSET_Y = 8f
    private const val LABEL_PAD_X = 10f
    private const val ARROW = 12f
    private const val ARROW_GAP = 6f
    private const val LABEL_MAX_CHARS = 16
    private const val INK = 0xFF111111.toInt()

    class Link(
        val shot: NoteShotExport.Shot,
        val notePath: String,
        
        val page: Int,
        val updatedAt: String,
    ) {
        val id: String get() = shot.id
        val noteName: String get() = File(notePath).nameWithoutExtension
        
        val recorded: RectF? = run {
            var out: RectF? = null
            for (b in shot.cards.values + shot.strokes.values) out = out?.apply { union(b) } ?: RectF(b)
            out
        }
    }

    class Frame(val id: String, val rect: RectF, val label: String, val badge: RectF) {
        
        val extent: RectF = RectF(rect).apply {
            val out = CORNER_OFFSET + CORNER_STROKE + 2f
            inset(-out, -out)
            union(badge)
        }

        fun sameAs(other: Frame): Boolean = rect == other.rect && label == other.label
    }

    private val lock = Any()
    private var links: List<Link> = emptyList()
    private var stamp: Pair<Long, Long>? = null
    
    private var active = true
    
    private val reach = HashMap<String, RectF>()

    
    @Volatile
    var frames: List<Frame> = emptyList()
        private set

    private val measurePaint = TextPaint().apply {
        textSize = LABEL_SIZE
        isFakeBoldText = true
    }
    
    private val cornerPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = INK
        strokeWidth = CORNER_STROKE
    }
    private val badgePaint = Paint().apply { color = INK }
    private val labelPaint = TextPaint().apply {
        textSize = LABEL_SIZE
        isFakeBoldText = true
        color = Color.WHITE
    }
    private val arrowPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.2f
        strokeCap = Paint.Cap.ROUND
    }

    fun storeFile(context: ReactContext): File? = try {
        context.getNativeModule(PluginModule::class.java)?.pluginApp?.pluginPath?.let { File(it, STORE_FILENAME) }
    } catch (error: Throwable) {
        Log.w(TAG, "plugin dir lookup failed: $error")
        null
    }

    
    fun reload(file: File): List<RectF> {
        synchronized(lock) {
            val nextStamp = file.lastModified() to file.length()
            if (nextStamp == stamp) return emptyList()
            stamp = nextStamp
            val loaded = ArrayList<Link>()
            try {
                val shots = if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)).optJSONObject("shots") else null
                if (shots != null) {
                    val ids = shots.keys()
                    while (ids.hasNext()) {
                        val o = shots.optJSONObject(ids.next()) ?: continue
                        val shot = NoteShotExport.Shot.parse(o.toString()) ?: continue
                        loaded.add(Link(shot, o.optString("notePath"), o.optInt("page", -1), o.optString("updatedAt")))
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "store read failed: $error")
            }
            loaded.sortByDescending { it.updatedAt }
            links = loaded
            reach.clear()
            Log.i(TAG, "links loaded count=${loaded.size}")
        }
        return refresh(null)
    }

    
    fun setActive(on: Boolean) {
        synchronized(lock) {
            if (active == on) return
            active = on
            reach.clear()
            if (!on) frames = emptyList()
        }
    }

    
    fun refresh(dirty: RectF?): List<RectF> = synchronized(lock) { refreshLocked(dirty) }

    private fun refreshLocked(dirty: RectF?): List<RectF> {
        if (!active || (links.isEmpty() && frames.isEmpty())) return emptyList()
        val previous = frames.associateBy { it.id }
        val next = ArrayList<Frame>(links.size)
        val changed = ArrayList<RectF>()
        synchronized(BoardEngine.lock) {
            for (link in links) {
                val prev = previous[link.id]
                val near = reach[link.id]
                if (prev != null && dirty != null && near != null && !RectF.intersects(near, dirty)) {
                    next.add(prev)
                    continue
                }
                val region = NoteShotExport.resolve(link.shot)?.region ?: continue
                val frame = frameOf(link, region)
                next.add(frame)
                reach[link.id] = RectF(frame.extent).apply {
                    link.recorded?.let { union(it) }
                    NoteShotExport.anchorExtent(link.shot)?.let { union(it) }
                }
                if (prev == null || !prev.sameAs(frame)) {
                    prev?.let { changed.add(RectF(it.extent)) }
                    changed.add(RectF(frame.extent))
                }
            }
        }
        val kept = next.mapTo(HashSet()) { it.id }
        for ((id, frame) in previous) if (id !in kept) changed.add(RectF(frame.extent))
        frames = next
        return changed
    }

    
    fun list(): List<Link> = synchronized(lock) { links }

    
    fun regionOf(id: String): RectF? = frames.firstOrNull { it.id == id }?.let { RectF(it.rect) }

    fun badgeAt(x: Float, y: Float, slop: Float, minSide: Float): String? {
        val shown = frames
        for (i in shown.indices.reversed()) {
            val badge = shown[i].badge
            val padX = maxOf(slop, (minSide - badge.width()) / 2f)
            val padY = maxOf(slop, (minSide - badge.height()) / 2f)
            if (x >= badge.left - padX && x <= badge.right + padX && y >= badge.top - padY && y <= badge.bottom + padY) return shown[i].id
        }
        return null
    }

    fun displayName(link: Link): String {
        val name = link.noteName.ifBlank { MosaicStrings.t(MosaicStrings.Key.noteLinkFallback) }
        return if (name.length > LABEL_MAX_CHARS) name.take(LABEL_MAX_CHARS - 1) + "…" else name
    }

    private fun frameOf(link: Link, region: RectF): Frame {
        val label = displayName(link)
        val left = region.left + LABEL_INSET_X
        val top = region.top + LABEL_INSET_Y
        val width = LABEL_PAD_X + ARROW + ARROW_GAP + measurePaint.measureText(label) + LABEL_PAD_X
        return Frame(link.id, RectF(region), label, RectF(left, top, left + width, top + LABEL_SIZE * 1.25f + 4f))
    }

    
    fun draw(canvas: Canvas, world: RectF) {
        for (frame in frames) {
            if (!RectF.intersects(frame.extent, world)) continue
            drawCorners(canvas, frame.rect)
            val badge = frame.badge
            canvas.drawRect(badge, badgePaint)
            
            val ax = badge.left + LABEL_PAD_X
            val ay = badge.centerY() - ARROW / 2f
            canvas.drawLine(ax, ay + ARROW, ax + ARROW, ay, arrowPaint)
            canvas.drawLine(ax + ARROW * 0.35f, ay, ax + ARROW, ay, arrowPaint)
            canvas.drawLine(ax + ARROW, ay, ax + ARROW, ay + ARROW * 0.65f, arrowPaint)
            canvas.drawText(frame.label, ax + ARROW + ARROW_GAP, badge.top + 2f - labelPaint.ascent(), labelPaint)
        }
    }

    private fun drawCorners(canvas: Canvas, frame: RectF) {
        val len = CORNER_LEN
        val half = CORNER_STROKE / 2f
        val left = frame.left - CORNER_OFFSET
        val top = frame.top - CORNER_OFFSET
        val right = frame.right + CORNER_OFFSET
        val bottom = frame.bottom + CORNER_OFFSET
        canvas.drawLine(left, top + half, left + len, top + half, cornerPaint)
        canvas.drawLine(left + half, top, left + half, top + len, cornerPaint)
        canvas.drawLine(right - len, top + half, right, top + half, cornerPaint)
        canvas.drawLine(right - half, top, right - half, top + len, cornerPaint)
        canvas.drawLine(left, bottom - half, left + len, bottom - half, cornerPaint)
        canvas.drawLine(left + half, bottom - len, left + half, bottom, cornerPaint)
        canvas.drawLine(right - len, bottom - half, right, bottom - half, cornerPaint)
        canvas.drawLine(right - half, bottom - len, right - half, bottom, cornerPaint)
    }
}
