package me.laumss.mosaic

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt


class ClipboardStrokeIo {
    companion object {
        private const val TAG = "MosaicClipboardIo"
        
        private const val NOTE_COLOR_WHITE = 0xFE
        
        /** SDK 185 Stroke.penType for calligraphy (same as Notipal). */
        private const val NOTE_PEN_TYPE_CALLIGRAPHY = 15
        // Blank space left between stacked pages before the note is compacted.
        private const val NOTE_PAGE_GAP = 48f
    }

    data class PasteTransform(
        val viewCx: Float,
        val viewCy: Float,
        val bboxCx: Float,
        val bboxCy: Float,
        val worldScale: Float,
        val sampleScale: Float,
        
        val screenShortPx: Float,
    )

    data class PasteStroke(
        val penStyle: Int,
        val width: Float,
        val points: FloatArray,
        val pressures: FloatArray,
        val drawPathWidth: Int = 0,
        
        val color: Int = BoardEngine.StrokeRec.INK_BLACK,
        val sampleScale: Float = 1f,
        
        val noteWhite: Boolean = false,
        val nibAngle: Float = Float.NaN,
        val angles: IntArray = IntArray(0),
        val contourOffsets: IntArray = IntArray(0),
    )

    data class PasteBatch(
        val strokes: List<PasteStroke>,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
        
        val pageScale: Float,
    )

    
    data class ExportStroke(
        val penStyle: Int,
        val width: Float,
        val points: FloatArray,
        val pressures: FloatArray,
        val offsetX: Float,
        val offsetY: Float,
        val drawPathWidth: Int = 0,
        val color: Int = BoardEngine.StrokeRec.INK_BLACK,
        val sampleScale: Float = 1f,
        val nibAngle: Float = Float.NaN,
        val angles: IntArray = IntArray(0),
        val contourOffsets: IntArray = IntArray(0),
    )

    
    class NoteImport(
        val strokes: List<BoardEngine.StrokeRec>,
        val pageCount: Int,
    )

    private val pasteExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MosaicClipboardPaste").apply { isDaemon = true }
    }
    private val exportExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MosaicClipboardExport").apply { isDaemon = true }
    }

    fun submitPaste(
        source: File,
        transform: PasteTransform,
        callback: (Result<PasteBatch?>) -> Unit,
    ): Future<*> = pasteExecutor.submit {
        var claimed: File? = null
        try {
            val claim = File(source.parentFile, ".${source.name}.reading-${UUID.randomUUID()}")
            claimed = if (source.renameTo(claim)) claim else source
            val text = claimed.takeIf { it.exists() }?.readText(Charsets.UTF_8)
            val parsed = if (text.isNullOrBlank()) null else parsePaste(text, transform)
            callback(Result.success(parsed))
        } catch (error: Throwable) {
            callback(Result.failure(error))
        } finally {
            claimed?.let { runCatching { if (it.exists()) it.delete() } }
        }
    }

    
    fun submitNoteImport(source: File, callback: (Result<NoteImport>) -> Unit): Future<*> = pasteExecutor.submit {
        callback(runCatching { parseNotePages(source.readText(Charsets.UTF_8)) })
    }

    
    fun submitExport(
        target: File,
        strokes: List<ExportStroke>,
        density: Float,
        screenShortPx: Float,
        isCurrent: () -> Boolean,
        callback: (Result<Int>) -> Unit = {},
    ): Future<*> = exportExecutor.submit {
        try {
            if (!isCurrent()) return@submit
            if (strokes.isEmpty()) {
                delete(target)
                callback(Result.success(0))
                return@submit
            }
            val encoded = encodeExport(strokes, density, screenShortPx)
            if (!isCurrent()) return@submit
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.tmp-${UUID.randomUUID()}")
            try {
                FileOutputStream(tmp).use { stream ->
                    stream.write(encoded.toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
                if (!isCurrent()) return@submit
                
                
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
                callback(Result.success(strokes.size))
            } finally {
                runCatching { if (tmp.exists()) tmp.delete() }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "clipboard export failed", error)
            callback(Result.failure(error))
        }
    }

    fun submitDelete(target: File, isCurrent: () -> Boolean): Future<*> = exportExecutor.submit {
        if (isCurrent()) delete(target)
    }

    private fun delete(file: File) {
        runCatching { if (file.exists()) file.delete() }
            .onFailure { Log.w(TAG, "clipboard delete failed path=${file.path}", it) }
    }

    
    private fun pastePenStyle(obj: JSONObject): Int {
        if (obj.has("sdkPenType")) {
            return when (obj.optInt("sdkPenType")) {
                DrawPathClient.PEN_TYPE_NEEDLE,
                DrawPathClient.PEN_TYPE_PRESSURE -> PenStyle.BRUSH.objType
                DrawPathClient.PEN_TYPE_MARKER -> PenStyle.MARKER.objType
                else -> PenStyle.PEN.objType
            }
        }
        return runCatching { PenStyle.normalizeStoredType(obj.optInt("penStyle", PenStyle.PEN.objType)) }
            .getOrDefault(PenStyle.PEN.objType)
    }

    /** Raw SDK X/Y pairs, retained independently of the rendered nib direction. */
    private fun pasteAngles(obj: JSONObject): IntArray {
        val values = obj.optJSONArray("angles") ?: obj.optJSONArray("tilt") ?: return IntArray(0)
        require(values.length() % 2 == 0) { "Incomplete clipboard angle pairs" }
        return IntArray(values.length()) { index ->
            val value = values.getDouble(index)
            require(value.isFinite() && value == value.toInt().toDouble()) { "Invalid clipboard angle at $index" }
            value.toInt()
        }
    }

    /** Mosaic exports carry nibAngle; note strokes carry raw Element.angles. */
    private fun pasteNibAngle(obj: JSONObject, angles: IntArray): Float {
        val nib = obj.optDouble("nibAngle", Double.NaN).toFloat()
        if (nib.isFinite()) return nib
        if (angles.size < 2) return Float.NaN
        return InputReader.calligraphyNibAngleForTilt(angles[0], angles[1])
    }

    private fun parsePaste(text: String, transform: PasteTransform): PasteBatch? {
        val root = JSONObject(text)
        val array = root.optJSONArray("strokes") ?: return null
        if (array.length() == 0) return null

        val bounds = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val pageStrokes = parsePageStrokes(array, bounds, clipboardCoordinateSpace(root, array) == "note-page-px")
        val minX = bounds[0]
        val minY = bounds[1]
        val maxX = bounds[2]
        val maxY = bounds[3]
        if (!minX.isFinite() || !maxX.isFinite()) return null
        
        
        
        val pageScale = notePageScale(root, array, transform.screenShortPx)
        val scale = transform.worldScale * pageScale
        val strokes = pageStrokes.map { source ->
            val points = FloatArray(source.points.size)
            for (point in source.pressures.indices) {
                points[point * 2] = transform.viewCx + (source.points[point * 2] - (minX + maxX) / 2f) * scale
                points[point * 2 + 1] = transform.viewCy + (source.points[point * 2 + 1] - (minY + maxY) / 2f) * scale
            }
            PasteStroke(
                source.penStyle,
                source.width * scale,
                points,
                source.pressures,
                source.drawPathWidth,
                source.color,
                source.sampleScale * transform.sampleScale / pageScale,
                source.noteWhite,
                source.nibAngle,
                source.angles,
                source.contourOffsets,
            )
        }
        return PasteBatch(strokes, minX, minY, maxX, maxY, pageScale)
    }

    
    private fun parsePageStrokes(array: JSONArray, bounds: FloatArray, noteSource: Boolean = false): List<TripleStroke> {
        var minX = bounds[0]
        var minY = bounds[1]
        var maxX = bounds[2]
        var maxY = bounds[3]
        val pageStrokes = ArrayList<TripleStroke>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val contours = obj.optJSONArray("contours")
            val ends = ArrayList<Int>()
            val pts = if (contours != null) (obj.optJSONArray("pts") ?: JSONArray()).apply {
                require(length() % 3 == 0) { "Invalid source samples" }
                var vertices = length() / 3
                ends.add(vertices)
                for (ringIndex in 0 until contours.length()) {
                    val ring = contours.getJSONArray(ringIndex)
                    require(ring.length() >= 2 && ring.length() % 2 == 0) { "Invalid note contour" }
                    for (at in 0 until ring.length() step 2) {
                        val x = ring.getDouble(at); val y = ring.getDouble(at + 1)
                        require(x.isFinite() && y.isFinite()) { "Invalid contour coordinate" }
                        put(x); put(y); put(1.0); vertices++
                    }
                    ends.add(vertices)
                }
            } else obj.optJSONArray("pts") ?: continue
            val count = pts.length() / 3
            if (count < 1) continue
            val pagePoints = FloatArray(count * 2)
            val pressures = FloatArray(count)
            var valid = 0
            for (point in 0 until count) {
                val at = point * 3
                val x = pts.optDouble(at, Double.NaN).toFloat()
                val y = pts.optDouble(at + 1, Double.NaN).toFloat()
                if (!x.isFinite() || !y.isFinite()) continue
                pagePoints[valid * 2] = x
                pagePoints[valid * 2 + 1] = y
                val rawPressure = pts.optDouble(at + 2, 1.0).toFloat()
                pressures[valid] = if (rawPressure.isFinite()) rawPressure.coerceIn(0f, 1f) else 1f
                if (contours == null || point >= ends.first()) {
                    minX = minOf(minX, x); minY = minOf(minY, y)
                    maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
                }
                valid++
            }
            if (valid == 0) continue
            val penStyle = pastePenStyle(obj)
            val width = obj.optDouble("width", BoardInteractionController.PEN_WIDTH_DP.toDouble()).toFloat()
                .takeIf { it.isFinite() } ?: BoardInteractionController.PEN_WIDTH_DP
            val rawWidth = obj.optInt("drawPathWidth", 0).takeIf { it > 0 }
                ?: (width * 100f).roundToInt().coerceAtLeast(1)
            val calligraphy = noteSource && obj.optInt("sdkPenType", -1) == NOTE_PEN_TYPE_CALLIGRAPHY
            val drawPathWidth = when {
                calligraphy -> DrawPathClient.mosaicCalligraphyThickness(rawWidth)
                noteSource || obj.has("drawPathWidth") -> rawWidth
                else -> DrawPathClient.widthArgument(PenStyle.fromObjType(penStyle), width)
            }
            if (index == 0) {
                Log.i(TAG, "[CLIP-DBG] parsed first stroke sdkPenType=${obj.optInt("sdkPenType", -1)} " +
                    "penStyle=$penStyle width=$width sourceWidth=$rawWidth drawPathWidth=$drawPathWidth " +
                    "penColor=${obj.optInt("penColor", 0)} ink=${Integer.toHexString(noteInkColor(obj, penStyle))}")
            }
            val angles = pasteAngles(obj)
            pageStrokes.add(
                TripleStroke(
                    penStyle,
                    if (calligraphy) drawPathWidth / 100f else width,
                    pagePoints.copyOf(valid * 2),
                    pressures.copyOf(valid),
                    
                    
                    drawPathWidth,
                    noteInkColor(obj, penStyle),
                    
                    penStyle != PenStyle.MARKER.objType && noteSource &&
                        obj.optInt("penColor", 0) == NOTE_COLOR_WHITE,
                    obj.optDouble("sampleScale", 1.0).toFloat()
                        .takeIf { it.isFinite() && it > 0f } ?: 1f,
                    pasteNibAngle(obj, angles),
                    angles,
                    ends.toIntArray(),
                ),
            )
        }
        bounds[0] = minX
        bounds[1] = minY
        bounds[2] = maxX
        bounds[3] = maxY
        return pageStrokes
    }

    // Pages are scaled to the note width and stacked with NOTE_PAGE_GAP between them; blank runs are then
    // collapsed the same way a note is on close, so empty page areas do not make the card long.
    private fun parseNotePages(text: String): NoteImport {
        val pages = JSONObject(text).optJSONArray("pages") ?: error("note import has no pages")
        val strokes = ArrayList<BoardEngine.StrokeRec>()
        var top = 0f
        var pageCount = 0
        for (index in 0 until pages.length()) {
            val page = pages.optJSONObject(index) ?: continue
            val size = page.optJSONObject("pageSize") ?: continue
            val pageWidth = size.optDouble("width", 0.0).toFloat()
            val pageHeight = size.optDouble("height", 0.0).toFloat()
            if (!(pageWidth > 0f) || !(pageHeight > 0f)) continue
            if (pageCount > 0) top += NOTE_PAGE_GAP
            val scale = ScrollingDocument.WIDTH / pageWidth
            val sources = page.optJSONArray("strokes")
                ?.let { parsePageStrokes(it, floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY), noteSource = true) }
                .orEmpty()
            if (page.has("strokeCount")) require(sources.size == page.getInt("strokeCount")) { "Incomplete note import page $index" }
            for (source in sources) {
                val points = FloatArray(source.points.size)
                for (point in source.pressures.indices) {
                    points[point * 2] = source.points[point * 2] * scale
                    points[point * 2 + 1] = top + source.points[point * 2 + 1] * scale
                }
                strokes.add(BoardEngine.StrokeRec(
                    UUID.randomUUID().toString(), "canvas", source.width * scale, source.color,
                    points, source.pressures, source.penStyle, source.sampleScale / scale, source.drawPathWidth,
                    source.nibAngle,
                    source.angles,
                    source.contourOffsets,
                ))
            }
            top += pageHeight * scale
            pageCount++
        }
        require(pageCount > 0) { "note import has no valid pages" }
        return NoteImport(ScrollingDocument.compact(strokes), pageCount)
    }

    
    private fun clipboardCoordinateSpace(root: JSONObject, strokes: JSONArray): String =
        root.optString("coordinateSpace").ifBlank {
            // Legacy native exports preserve these SDK fields; pen width is independent of coordinates.
            if ((0 until strokes.length()).any { index ->
                strokes.optJSONObject(index)?.let { it.has("sdkThickness") || it.has("sourcePenType") } == true
            }) "note-page-px" else "mosaic-px"
        }

    private fun notePageScale(root: JSONObject, strokes: JSONArray, screenShortPx: Float): Float {
        val coordinateSpace = clipboardCoordinateSpace(root, strokes)
        if (coordinateSpace == "mosaic-px") return 1f
        require(coordinateSpace == "note-page-px") { "Unknown clipboard coordinates: $coordinateSpace" }
        val page = root.optJSONObject("pageSize") ?: return 1f
        val pageShort = minOf(page.optDouble("width", 0.0), page.optDouble("height", 0.0)).toFloat()
        if (!(pageShort > 0f) || !(screenShortPx > 0f)) return 1f
        return screenShortPx / pageShort
    }

    private data class TripleStroke(
        val penStyle: Int,
        val width: Float,
        val points: FloatArray,
        val pressures: FloatArray,
        val drawPathWidth: Int,
        val color: Int,
        val noteWhite: Boolean,
        val sampleScale: Float,
        val nibAngle: Float,
        val angles: IntArray,
        val contourOffsets: IntArray,
    )

    
    private fun noteInkColor(obj: JSONObject, penStyle: Int): Int {
        val noteColor = obj.optInt("penColor", 0)
        if (obj.has("contours")) {
            val gray = if ((noteColor and 0xFF) == NOTE_COLOR_WHITE) 255 else noteColor and 0xFF
            return 0xFF000000.toInt() or (gray * 0x010101)
        }
        if (penStyle == PenStyle.MARKER.objType) {
            return MarkerInk.fromNoteColor(noteColor).argb
        }
        if (!obj.has("sdkPenType") && !obj.has("penColor")) {
            return BoardEngine.StrokeRec.INK_BLACK
        }
        return when (noteColor and 0xFF) {
            NOTE_COLOR_WHITE -> BoardEngine.StrokeRec.INK_WHITE
            0x9D -> MarkerInk.DARK_GRAY.argb
            0xC9, 0xCA -> MarkerInk.LIGHT_GRAY.argb
            else -> BoardEngine.StrokeRec.INK_BLACK
        }
    }

    private fun encodeExport(strokes: List<ExportStroke>, density: Float, screenShortPx: Float): String {
        val array = JSONArray()
        for (stroke in strokes) {
            val pts = JSONArray()
            var point = 0
            while (point * 2 + 1 < stroke.points.size) {
                pts.put(((stroke.points[point * 2] + stroke.offsetX) * density).toDouble())
                pts.put(((stroke.points[point * 2 + 1] + stroke.offsetY) * density).toDouble())
                pts.put((stroke.pressures.getOrNull(point) ?: 1f).toDouble())
                point++
            }
            
            
            
            val sdkPenType = when (stroke.penStyle) {
                PenStyle.BRUSH.objType -> DrawPathClient.PEN_TYPE_NEEDLE
                PenStyle.MARKER.objType -> DrawPathClient.PEN_TYPE_MARKER
                else -> NOTE_PEN_TYPE_CALLIGRAPHY
            }
            val noteThickness = if (sdkPenType == NOTE_PEN_TYPE_CALLIGRAPHY) {
                DrawPathClient.noteCalligraphyThickness(stroke.drawPathWidth)
            } else {
                stroke.drawPathWidth
            }
            val json = JSONObject()
                .put("penStyle", stroke.penStyle)
                .put("sdkPenType", sdkPenType)
                
                .put("width", (noteThickness / 100f).toDouble())
                .put("drawPathWidth", stroke.drawPathWidth)
                // Convert capture pixels per world unit to capture pixels per exported pixel.
                .put("sampleScale", (stroke.sampleScale / density).toDouble())
                .put("pts", pts)
            if (stroke.nibAngle.isFinite()) json.put("nibAngle", stroke.nibAngle.toDouble())
            if (stroke.contourOffsets.isNotEmpty()) {
                ContourGeometry.validate(stroke.contourOffsets, stroke.points.size / 2)
                val contours = JSONArray()
                var start = stroke.contourOffsets.first()
                json.put("pts", JSONArray().apply { for (i in 0 until start * 3) put(pts.get(i)) })
                for (end in stroke.contourOffsets.drop(1)) {
                    val ring = JSONArray()
                    for (index in start until end) {
                        ring.put((stroke.points[index * 2] + stroke.offsetX) * density)
                        ring.put((stroke.points[index * 2 + 1] + stroke.offsetY) * density)
                    }
                    contours.put(ring); start = end
                }
                json.put("geometry", "contours").put("contours", contours)
            }
            if (stroke.angles.isNotEmpty()) {
                json.put("angles", JSONArray().apply { for (angle in stroke.angles) put(angle) })
                if (stroke.angles.size >= 2) {
                    json.put("tilt", JSONArray().put(stroke.angles[0]).put(stroke.angles[1]))
                }
            }
            
            
            if (stroke.contourOffsets.isNotEmpty()) {
                val gray = stroke.color and 0xFF
                json.put("penColor", if (gray == 255) NOTE_COLOR_WHITE else gray)
            } else if (stroke.penStyle == PenStyle.MARKER.objType) {
                json.put("penColor", MarkerInk.fromArgb(stroke.color).noteColor)
            } else if (stroke.color != BoardEngine.StrokeRec.INK_BLACK) {
                json.put("penColor", NOTE_COLOR_WHITE)
            }
            array.put(json)
        }
        return JSONObject().put("v", 2).put("unit", "px")
            .put("coordinateSpace", "mosaic-px").put("screenShortPx", screenShortPx.toDouble())
            .put("strokes", array).toString()
    }
}
