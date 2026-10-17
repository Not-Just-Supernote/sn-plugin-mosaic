package me.laumss.mosaic.note

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.render.*
import android.util.Log
import java.io.File
import org.json.JSONObject

/** 文件转换入口独立于窗口；Notipal 在打开 Mosaic 前调用已安装插件中的此入口。 */
object PreparedNoteImport {
    fun directory(source: File) = File(source.parentFile, source.nameWithoutExtension + ".prepared")

    /** Versioned JDK-only boundary for the producer in Notipal; all calls arrive on one consumer thread. */
    @JvmStatic
    fun openPageSession(sourcePath: String, progress: java.util.function.IntConsumer,
                        cancelled: java.util.function.BooleanSupplier): java.util.function.Consumer<Map<String, Any>> {
        val source = File(sourcePath)
        require(source.name.startsWith("inkling-note-") && source.extension == "pages")
        val dir = directory(source)
        check(!dir.exists() && dir.mkdirs())
        val strokes = ArrayList<BoardEngine.StrokeRec>()
        val parser = ClipboardStrokeIo()
        var top = 0f
        var pages = 0
        var complete = false
        val started = android.os.SystemClock.elapsedRealtime()
        return java.util.function.Consumer { message ->
            val op = message.getValue("op")
            if (op == "abort") {
                strokes.clear(); dir.deleteRecursively(); complete = true
            } else {
                check(!complete)
                if (cancelled.asBoolean) throw java.io.InterruptedIOException("cancelled")
                when (op) {
                    "page" -> {
                        require((message.getValue("index") as Number).toInt() == pages)
                        val before = strokes.size
                        val pageStarted = android.os.SystemClock.elapsedRealtime()
                        top = parser.appendNativePage(message, top, strokes)
                        pages++
                        Log.i("MosaicImportPrepare", "[NOTE-PIPE] converted page=$pages strokes=${strokes.size - before} total=${strokes.size} elapsedMs=${android.os.SystemClock.elapsedRealtime() - pageStarted}")
                    }
                    "finish" -> {
                        require(pages > 0)
                        val doc = ScrollingDocument("note-prepared", ScrollingDocument.compact(strokes, owned = true), 0f)
                        progress.accept(0)
                        val saved = android.os.SystemClock.elapsedRealtime()
                        TchFile.write(File(dir, "document.mnote"), doc)
                        Log.i("MosaicImportPrepare", "[NOTE-PIPE] saved strokes=${strokes.size} elapsedMs=${android.os.SystemClock.elapsedRealtime() - saved}")
                        NotePreview.write(File(dir, "preview"), doc, null) { tile, total ->
                            if (cancelled.asBoolean) throw java.io.InterruptedIOException("cancelled")
                            progress.accept(tile * 100 / total.coerceAtLeast(1))
                        }
                        if (cancelled.asBoolean) throw java.io.InterruptedIOException("cancelled")
                        File(dir, "ready.json").writeText(JSONObject().put("pages", pages)
                            .put("strokes", strokes.size).put("tiles", doc.tileCount).toString())
                        Log.i("MosaicImportPrepare", "[NOTE-PIPE] ready pages=$pages strokes=${strokes.size} tiles=${doc.tileCount} elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
                        strokes.clear(); complete = true
                    }
                    else -> error("Unknown page session operation: $op")
                }
            }
        }
    }

    @JvmStatic
    fun prepare(sourcePath: String) = prepare(sourcePath, java.util.function.IntConsumer {}, java.util.function.BooleanSupplier { false })

    @JvmStatic
    fun prepare(sourcePath: String, progress: java.util.function.IntConsumer, cancelled: java.util.function.BooleanSupplier) {
        val source = File(sourcePath)
        require(source.isFile && source.name.startsWith("inkling-note-") && source.extension == "pages")
        val dir = directory(source)
        check(!dir.exists()) { "Note preparation already exists" }
        check(dir.mkdirs())
        try {
            val imported = ClipboardStrokeIo().parseNoteFile(source, { progress.accept(it * 60 / 100) }, { cancelled.asBoolean })
            val doc = ScrollingDocument("note-prepared", imported.strokes, 0f)
            TchFile.write(File(dir, "document.mnote"), doc)
            NotePreview.write(File(dir, "preview"), doc, null) { tile, total ->
                if (cancelled.asBoolean) throw java.io.InterruptedIOException("cancelled")
                progress.accept(60 + tile * 40 / total.coerceAtLeast(1))
            }
            val manifest = JSONObject().put("pages", imported.pageCount).put("strokes", doc.strokes.size)
                .put("tiles", doc.tileCount)
            File(dir, "ready.json").writeText(manifest.toString())
            Log.i("MosaicImportPrepare", "ready pages=${imported.pageCount} strokes=${doc.strokes.size} tiles=${doc.tileCount}")
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun read(source: File): ClipboardStrokeIo.NoteImport? {
        val dir = directory(source)
        if (!dir.exists()) return null
        val meta = JSONObject(File(dir, "ready.json").readText())
        val doc = TchFile.read(File(dir, "document.mnote"), "note-prepared")
        check(doc.strokes.size == meta.getInt("strokes"))
        check(doc.tileCount == meta.getInt("tiles"))
        val preview = File(dir, "preview")
        repeat(doc.tileCount) { check(File(preview, "$it.png").isFile) }
        return ClipboardStrokeIo.NoteImport(doc.strokes, meta.getInt("pages"), preview)
    }

    fun hasPendingRequest(): Boolean = File("/sdcard/EXPORT/mosaic/inbox").listFiles().orEmpty().any { file ->
        (file.name.endsWith(".json") || file.name.endsWith(".json.processing")) &&
            runCatching { JSONObject(file.readText()).optString("type") == "note" }.getOrDefault(false)
    }
}
