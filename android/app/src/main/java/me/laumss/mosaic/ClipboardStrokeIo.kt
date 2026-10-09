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


class ClipboardStrokeIo {
    companion object {
        private const val TAG = "MosaicClipboardIo"
        
        private const val NOTE_COLOR_WHITE = 0xFE
        
        private const val NOTE_PEN_TYPE_CALLIGRAPHY = 14
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

    
    fun submitExport(
        target: File,
        strokes: List<ExportStroke>,
        density: Float,
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
            val encoded = encodeExport(strokes, density)
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
                DrawPathClient.PEN_TYPE_NEEDLE -> PenStyle.BRUSH.objType
                DrawPathClient.PEN_TYPE_MARKER -> PenStyle.MARKER.objType
                else -> PenStyle.PEN.objType
            }
        }
        return runCatching { PenStyle.normalizeStoredType(obj.optInt("penStyle", PenStyle.PEN.objType)) }
            .getOrDefault(PenStyle.PEN.objType)
    }

    private fun parsePaste(text: String, transform: PasteTransform): PasteBatch? {
        val root = JSONObject(text)
        val array = root.optJSONArray("strokes") ?: return null
        if (array.length() == 0) return null

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        val pageStrokes = ArrayList<TripleStroke>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val pts = obj.optJSONArray("pts") ?: continue
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
                minX = minOf(minX, x); minY = minOf(minY, y)
                maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
                valid++
            }
            if (valid == 0) continue
            val penStyle = pastePenStyle(obj)
            val width = obj.optDouble("width", BoardInteractionController.PEN_WIDTH_DP.toDouble()).toFloat()
                .takeIf { it.isFinite() } ?: BoardInteractionController.PEN_WIDTH_DP
            if (index == 0) {
                Log.i(TAG, "[CLIP-DBG] parsed first stroke sdkPenType=${obj.optInt("sdkPenType", -1)} " +
                    "penStyle=$penStyle width=$width drawPathWidth=${obj.optInt("drawPathWidth", 0)} " +
                    "penColor=${obj.optInt("penColor", 0)} ink=${Integer.toHexString(noteInkColor(obj, penStyle))}")
            }
            pageStrokes.add(
                TripleStroke(
                    penStyle,
                    width,
                    pagePoints.copyOf(valid * 2),
                    pressures.copyOf(valid),
                    
                    
                    obj.optInt("drawPathWidth", 0).takeIf { it > 0 }
                        ?: DrawPathClient.widthArgument(PenStyle.fromObjType(penStyle), width),
                    noteInkColor(obj, penStyle),
                    
                    penStyle != PenStyle.MARKER.objType && !obj.has("drawPathWidth") &&
                        obj.optInt("penColor", 0) == NOTE_COLOR_WHITE,
                ),
            )
        }
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
                transform.sampleScale / pageScale,
                source.noteWhite,
            )
        }
        return PasteBatch(strokes, minX, minY, maxX, maxY, pageScale)
    }

    
    private fun notePageScale(root: JSONObject, strokes: JSONArray, screenShortPx: Float): Float {
        val page = root.optJSONObject("pageSize") ?: return 1f
        val pageShort = minOf(page.optDouble("width", 0.0), page.optDouble("height", 0.0)).toFloat()
        if (!(pageShort > 0f) || !(screenShortPx > 0f)) return 1f
        for (index in 0 until strokes.length()) {
            if ((strokes.optJSONObject(index)?.optInt("drawPathWidth", 0) ?: 0) > 0) return 1f
        }
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
    )

    
    private fun noteInkColor(obj: JSONObject, penStyle: Int): Int {
        val noteColor = obj.optInt("penColor", 0)
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

    private fun encodeExport(strokes: List<ExportStroke>, density: Float): String {
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
                .put("pts", pts)
            
            
            if (stroke.penStyle == PenStyle.MARKER.objType) {
                json.put("penColor", MarkerInk.fromArgb(stroke.color).noteColor)
            } else if (stroke.color != BoardEngine.StrokeRec.INK_BLACK) {
                json.put("penColor", NOTE_COLOR_WHITE)
            }
            array.put(json)
        }
        return JSONObject().put("v", 1).put("unit", "px").put("strokes", array).toString()
    }
}
