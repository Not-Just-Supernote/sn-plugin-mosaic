package me.laumss.mosaic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


object NoteShotExport {

    
    const val CONTENT_PADDING = 32f

    
    private const val MIN_WIDTH_DP = 180f
    
    private const val MAX_PX_PER_DP = 4f

    
    private const val STRIP_DP = 44f
    private const val TAG_MARGIN_DP = 4f
    private const val TAG_HEIGHT_DP = 36f
    private const val TAG_PADDING_X_DP = 12f
    private const val TAG_TEXT_SP = 17f
    private const val TAG_GAP_DP = 8f
    private const val TAG_ARROW_DP = 11f
    private const val TAG_ARROW_STROKE_DP = 2.2f
    private const val TAG_RADIUS_DP = 6f
    private const val TAG_TEXT = "Mosaic"
    private const val TAG_COLOR = 0xFF111111.toInt()

    
    class Shot(
        val id: String,
        
        val rect: RectF?,
        
        val cards: Map<String, RectF>,
        val strokes: Map<String, RectF>,
        val fingerprint: String,
        
        val pxPerWorld: Float,
    ) {
        companion object {
            fun parse(json: String): Shot? {
                return try {
                    val o = JSONObject(json)
                    val id = o.optString("id")
                    if (id.isEmpty()) return null
                    val rect = o.optJSONObject("rect")?.let { r ->
                        val x = r.optDouble("x", Double.NaN).toFloat()
                        val y = r.optDouble("y", Double.NaN).toFloat()
                        val w = r.optDouble("w", 0.0).toFloat()
                        val h = r.optDouble("h", 0.0).toFloat()
                        if (x.isFinite() && y.isFinite() && w > 0f && h > 0f) RectF(x, y, x + w, y + h) else null
                    }
                    Shot(
                        id,
                        rect,
                        readBoxes(o.optJSONObject("cards")),
                        readBoxes(o.optJSONObject("strokes")),
                        o.optString("fingerprint"),
                        o.optDouble("pxPerWorld", 0.0).toFloat(),
                    )
                } catch (_: Throwable) {
                    null
                }
            }

            private fun readBoxes(o: JSONObject?): Map<String, RectF> {
                if (o == null) return emptyMap()
                val out = LinkedHashMap<String, RectF>()
                val keys = o.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val a = o.optJSONArray(id) ?: continue
                    if (a.length() < 4) continue
                    out[id] = RectF(
                        a.optDouble(0).toFloat(), a.optDouble(1).toFloat(),
                        a.optDouble(2).toFloat(), a.optDouble(3).toFloat(),
                    )
                }
                return out
            }
        }
    }

    
    class Anchored(val region: RectF, val cards: Map<String, RectF>, val strokes: Map<String, RectF>) {
        
        fun anchorsJson(): String =
            JSONObject().put("cards", boxesJson(cards)).put("strokes", boxesJson(strokes)).toString()

        private fun boxesJson(boxes: Map<String, RectF>): JSONObject {
            val o = JSONObject()
            for ((id, r) in boxes) {
                o.put(id, JSONArray().put(round1(r.left)).put(round1(r.top)).put(round1(r.right)).put(round1(r.bottom)))
            }
            return o
        }
    }

    
    class Layout(val region: RectF, val pxPerWorld: Float, val stripPx: Int, val widthPx: Int, val heightPx: Int)

    
    class Hotspot(val x: Float, val y: Float, val w: Float, val h: Float)

    
    fun capture(cardIds: Collection<String>, strokeIds: Collection<String>): Anchored? {
        val cards = LinkedHashMap<String, RectF>()
        val strokes = LinkedHashMap<String, RectF>()
        var bounds: RectF? = null
        for (id in cardIds) {
            val b = cardBounds(id) ?: continue
            cards[id] = b
            bounds = bounds?.apply { union(b) } ?: RectF(b)
        }
        for (id in strokeIds) {
            val b = strokeBounds(id) ?: continue
            strokes[id] = b
            bounds = bounds?.apply { union(b) } ?: RectF(b)
        }
        val content = bounds ?: return null
        return Anchored(padded(content), cards, strokes)
    }

    
    fun resolve(shot: Shot): Anchored? {
        val dxs = ArrayList<Float>()
        val dys = ArrayList<Float>()
        val nowCards = LinkedHashMap<String, RectF>()
        val nowStrokes = LinkedHashMap<String, RectF>()
        var oldUnion: RectF? = null
        for ((id, old) in shot.cards) {
            oldUnion = oldUnion?.apply { union(old) } ?: RectF(old)
            val now = cardBounds(id) ?: continue
            nowCards[id] = now
            dxs.add(now.centerX() - old.centerX())
            dys.add(now.centerY() - old.centerY())
        }
        for ((id, old) in shot.strokes) {
            oldUnion = oldUnion?.apply { union(old) } ?: RectF(old)
            val now = strokeBounds(id) ?: continue
            nowStrokes[id] = now
            dxs.add(now.centerX() - old.centerX())
            dys.add(now.centerY() - old.centerY())
        }
        val base = shot.rect ?: oldUnion?.let { padded(it) } ?: return null
        if (dxs.isEmpty()) return Anchored(RectF(base), emptyMap(), emptyMap())
        val frame = RectF(base).apply { offset(median(dxs), median(dys)) }
        val region = RectF(frame)
        val keptCards = LinkedHashMap<String, RectF>()
        val keptStrokes = LinkedHashMap<String, RectF>()
        for ((id, now) in nowCards) {
            if (!RectF.intersects(now, frame)) continue
            keptCards[id] = now
            region.union(padded(now))
        }
        for ((id, now) in nowStrokes) {
            if (!RectF.intersects(now, frame)) continue
            keptStrokes[id] = now
            region.union(padded(now))
        }
        return Anchored(region, keptCards, keptStrokes)
    }

    
    fun anchorExtent(shot: Shot): RectF? {
        var out: RectF? = null
        for (id in shot.cards.keys) {
            val b = cardBounds(id) ?: continue
            out = out?.apply { union(b) } ?: b
        }
        for (id in shot.strokes.keys) {
            val b = strokeBounds(id) ?: continue
            out = out?.apply { union(b) } ?: b
        }
        return out
    }

    
    fun fingerprint(region: RectF): String {
        val sb = StringBuilder(4096)
        sb.append(round1(region.left)).append(',').append(round1(region.top)).append(',')
            .append(round1(region.right)).append(',').append(round1(region.bottom))
        val images = ArrayList<String>()
        synchronized(BoardEngine.lock) {
            val r = RectF()
            for (card in BoardEngine.cardsByZ) {
                if (!RectF.intersects(card.rect(r), region)) continue
                sb.append("|c:").append(card.id).append(':')
                    .append(card.x).append(',').append(card.y).append(',')
                    .append(card.width).append(',').append(card.height).append(',')
                    .append(card.zIndex).append(',').append(card.kind).append(',')
                    .append(card.content.hashCode()).append(',').append(card.bgColor).append(',')
                    .append(card.textColor).append(',').append(card.title.hashCode())
                if (card.imagePath.isNotEmpty()) images.add(card.imagePath)
                BoardEngine.cardStrokes[card.id]?.forEach { appendStroke(sb, it) }
            }
            val canvas = ArrayList<BoardEngine.StrokeRec>()
            BoardEngine.queryCanvasStrokes(region, canvas)
            
            canvas.sortBy { it.id }
            for (s in canvas) appendStroke(sb, s)
        }
        for (path in images) sb.append("|i:").append(path).append(':').append(File(path).lastModified())
        val digest = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    
    fun layout(region: RectF, pxPerWorld: Float, density: Float, maxWidthPx: Float, maxHeightPx: Float): Layout {
        val w = max(1f, region.width())
        val h = max(1f, region.height())
        val stripPx = ceil(STRIP_DP * density).toInt()
        val maxScale = MAX_PX_PER_DP * density
        var scale = if (pxPerWorld > 0f) min(pxPerWorld, maxScale) else density
        val minWidth = MIN_WIDTH_DP * density
        if (w * scale < minWidth) scale = min(minWidth / w, maxScale)
        val fit = min(maxWidthPx / w, max(1f, maxHeightPx - stripPx) / h)
        if (scale > fit) scale = fit
        val widthPx = ceil(w * scale).toInt().coerceAtLeast(1)
        val heightPx = ceil(h * scale).toInt().coerceAtLeast(1) + stripPx
        return Layout(RectF(region), scale, stripPx, widthPx, heightPx)
    }

    
    fun drawTag(canvas: Canvas, density: Float, widthPx: Int, heightPx: Int): Hotspot {
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = TAG_TEXT_SP * density
            isFakeBoldText = true
        }
        val margin = TAG_MARGIN_DP * density
        val tagHeight = TAG_HEIGHT_DP * density
        val padX = TAG_PADDING_X_DP * density
        val gap = TAG_GAP_DP * density
        val arrow = TAG_ARROW_DP * density
        val tagWidth = min(padX + text.measureText(TAG_TEXT) + gap + arrow + padX, max(1f, widthPx - 2f * margin))
        val rect = RectF(margin, margin, margin + tagWidth, margin + tagHeight)
        val radius = TAG_RADIUS_DP * density
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = TAG_COLOR
        })
        val baseline = rect.centerY() - (text.ascent() + text.descent()) / 2f
        canvas.drawText(TAG_TEXT, rect.left + padX, baseline, text)
        
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = TAG_ARROW_STROKE_DP * density
            strokeCap = Paint.Cap.ROUND
        }
        val ax = rect.right - padX - arrow
        val ay = rect.centerY() - arrow / 2f
        canvas.drawLine(ax, ay + arrow, ax + arrow, ay, stroke)
        canvas.drawLine(ax + arrow * 0.35f, ay, ax + arrow, ay, stroke)
        canvas.drawLine(ax + arrow, ay, ax + arrow, ay + arrow * 0.65f, stroke)
        val w = widthPx.toFloat().coerceAtLeast(1f)
        val h = heightPx.toFloat().coerceAtLeast(1f)
        return Hotspot(rect.left / w, rect.top / h, rect.width() / w, rect.height() / h)
    }

    
    fun writeGrayPng(bitmap: Bitmap, file: File) {
        val width = bitmap.width
        val height = bitmap.height
        val idat = ByteArrayOutputStream(width * height / 8 + 1024)
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            DeflaterOutputStream(idat, deflater, 64 * 1024).use { zlib ->
                val pixels = IntArray(width)
                var previous = ByteArray(width)
                var current = ByteArray(width)
                val line = ByteArray(width + 1)
                for (y in 0 until height) {
                    bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                    for (x in 0 until width) current[x] = luma(pixels[x]).toByte()
                    if (y == 0) {
                        line[0] = 0
                        System.arraycopy(current, 0, line, 1, width)
                    } else {
                        line[0] = 2
                        for (x in 0 until width) line[x + 1] = (current[x] - previous[x]).toByte()
                    }
                    zlib.write(line)
                    val swap = previous
                    previous = current
                    current = swap
                }
            }
        } finally {
            deflater.end()
        }
        val header = ByteArrayOutputStream(13)
        DataOutputStream(header).apply {
            writeInt(width)
            writeInt(height)
            write(byteArrayOf(8, 0, 0, 0, 0)) 
        }
        DataOutputStream(BufferedOutputStream(FileOutputStream(file), 64 * 1024)).use { out ->
            out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            writeChunk(out, "IHDR", header.toByteArray())
            writeChunk(out, "IDAT", idat.toByteArray())
            writeChunk(out, "IEND", ByteArray(0))
        }
    }

    private fun writeChunk(out: DataOutputStream, type: String, data: ByteArray) {
        val tag = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(data.size)
        out.write(tag)
        out.write(data)
        val crc = CRC32()
        crc.update(tag)
        crc.update(data)
        out.writeInt(crc.value.toInt())
    }

    
    private fun luma(argb: Int): Int {
        val a = (argb ushr 24) and 0xff
        val y = (((argb shr 16) and 0xff) * 299 + ((argb shr 8) and 0xff) * 587 + (argb and 0xff) * 114) / 1000
        return (y * a + 255 * (255 - a)) / 255
    }

    private fun cardBounds(id: String): RectF? = BoardEngine.cards[id]?.rect(RectF())

    
    private fun strokeBounds(id: String): RectF? {
        val s = BoardEngine.strokes[id] ?: return null
        val out = RectF(s.bounds)
        val half = max(1f, s.width / 2f)
        out.inset(-half, -half)
        val cardId = s.cardId ?: return out
        val card = BoardEngine.cards[cardId] ?: return null
        out.offset(card.x, card.y)
        return out
    }

    private fun appendStroke(sb: StringBuilder, s: BoardEngine.StrokeRec) {
        val b = s.bounds
        sb.append("|s:").append(s.id).append(':')
            .append(b.left).append(',').append(b.top).append(',').append(b.right).append(',').append(b.bottom).append(',')
            .append(s.color).append(',').append(s.penStyle).append(',').append(s.width).append(',').append(s.space)
    }

    private fun padded(r: RectF): RectF =
        RectF(r.left - CONTENT_PADDING, r.top - CONTENT_PADDING, r.right + CONTENT_PADDING, r.bottom + CONTENT_PADDING)

    private fun median(values: MutableList<Float>): Float {
        values.sort()
        val n = values.size
        return if (n % 2 == 1) values[n / 2] else (values[n / 2 - 1] + values[n / 2]) / 2f
    }

    private fun round1(v: Float): Double = (v * 10f).roundToInt() / 10.0
}
