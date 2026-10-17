package me.laumss.mosaic.note

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.render.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

class NoteController {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "MosaicNoteIO") }
    var document: ScrollingDocument? = null
        private set
    private lateinit var directory: File
    private var pending: Runnable? = null
    private var previewed: Map<String, BoardEngine.StrokeRec> = emptyMap()
    fun discardCopiedAssets(dir: String, refs: Collection<String>) {
        io.execute {
            for (ref in refs) {
                require(ref.matches(Regex("note-[a-zA-Z0-9-]+")))
                File(dir, "$ref.mnote").delete()
                File(dir, ref).deleteRecursively()
            }
        }
    }
    fun copyAssets(dir: String, refs: Map<String, String>, done: (Result<Unit>) -> Unit) {
        val root = File(dir)
        io.execute {
            val created = ArrayList<File>()
            val result = runCatching {
                for ((source, target) in refs) {
                    require(source.matches(Regex("note-[a-zA-Z0-9-]+")) && target.matches(Regex("note-[a-zA-Z0-9-]+")))
                    val file = File(root, "$target.mnote")
                    val preview = File(root, target)
                    require(!file.exists() && !preview.exists())
                    created.add(file); created.add(preview)
                    File(root, "$source.mnote").copyTo(file)
                    val originalPreview = File(root, source)
                    if (originalPreview.isDirectory) check(originalPreview.copyRecursively(preview))
                }
            }
            if (result.isFailure) created.forEach { it.deleteRecursively() }
            ui.post { done(result) }
        }
    }
    fun open(dir: String, ref: String, create: Boolean, done: (Result<ScrollingDocument>) -> Unit) {
        require(ref.matches(Regex("note-[a-zA-Z0-9-]+")))
        directory = File(dir)
        io.execute {
            val result = runCatching {
                val file = File(directory,"$ref.mnote")
                if (create) ScrollingDocument(ref,emptyList(),0f).also { TchFile.write(file,it); NotePreview.write(File(directory,ref),it,null) }
                else TchFile.read(file,ref)
            }
            ui.post { result.onSuccess { document=it; previewed=it.strokes.associateBy { s -> s.id } }; done(result) }
        }
    }

    fun create(dir: String, doc: ScrollingDocument, preparedPreview: File? = null, done: (Result<ScrollingDocument>) -> Unit) {
        require(doc.ref.matches(Regex("note-[a-zA-Z0-9-]+")))
        val target = File(dir)
        io.execute {
            val result = runCatching {
                TchFile.write(File(target,"${doc.ref}.mnote"),doc)
                if (preparedPreview == null) {
                    NotePreview.write(File(target,doc.ref),doc,null)
                } else {
                    val preview = File(target, doc.ref)
                    check(preview.mkdirs() || preview.isDirectory)
                    repeat(doc.tileCount) { tile -> File(preparedPreview, "$tile.png").copyTo(File(preview, "$tile.png")) }
                }
                Log.i("MosaicNote","created ref=${doc.ref} strokes=${doc.strokes.size} height=${doc.contentHeight} tiles=${doc.tileCount}")
                doc
            }
            ui.post { done(result) }
        }
    }
    fun changed(strokes: List<BoardEngine.StrokeRec>) {
        document?.strokes = strokes
        pending?.let(ui::removeCallbacks)
        pending = Runnable { pending=null; flush(false) { result -> result.onFailure { Log.e("MosaicNote", "save failed",it) } } }.also { ui.postDelayed(it,800) }
    }
    fun flush(preview: Boolean, done: (Result<ScrollingDocument>) -> Unit) {
        pending?.let(ui::removeCallbacks); pending=null
        val snapshot = document!!.snapshot()
        // Diffed against the last written thumbnails; a flush queued before that write lands only redraws more tiles.
        val dirty = if (preview) ScrollingDocument.dirtyTiles(previewed, snapshot.strokes) else emptySet()
        io.execute {
            val result = runCatching {
                TchFile.write(File(directory,"${snapshot.ref}.mnote"),snapshot)
                val tiles = if (preview) NotePreview.write(File(directory,snapshot.ref),snapshot,dirty) else emptyList()
                Log.i("MosaicNote","saved ref=${snapshot.ref} strokes=${snapshot.strokes.size} height=${snapshot.contentHeight} tiles=${snapshot.tileCount} preview=$preview redrawn=$tiles")
                snapshot
            }
            ui.post {
                if (preview && result.isSuccess && document?.ref == snapshot.ref) previewed = snapshot.strokes.associateBy { it.id }
                done(result)
            }
        }
    }
    fun clear() { pending?.let(ui::removeCallbacks); pending=null; document=null; previewed=emptyMap() }
    fun previewPath(ref: String) = File(directory,ref).absolutePath
}

object MosaicSession {
    private const val PREFS = "mosaic_session"
    private const val KEY_NOTE_REF = "noteRef"
    private const val KEY_NOTES_DIR = "notesDir"
    private const val KEY_PAN_X = "panX"
    private const val KEY_PAN_Y = "panY"
    private const val KEY_SCALE = "scale"

    class Viewport(val panX: Float, val panY: Float, val scale: Float)

    fun saveNoteRef(context: android.content.Context, ref: String?) {
        val editor = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
        if (ref == null) editor.remove(KEY_NOTE_REF) else editor.putString(KEY_NOTE_REF, ref)
        editor.apply()
    }

    fun savedNoteRef(context: android.content.Context): String? =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_NOTE_REF, null)

    // The notes folder JS last reported, so a saved note can reopen before JS reports it again.
    fun saveNotesDirectory(context: android.content.Context, path: String) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(KEY_NOTES_DIR, path).apply()
    }

    fun savedNotesDirectory(context: android.content.Context): String? =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_NOTES_DIR, null)?.takeIf { java.io.File(it).isDirectory }

    fun saveViewport(context: android.content.Context, panX: Float, panY: Float, scale: Float) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putFloat(KEY_PAN_X, panX).putFloat(KEY_PAN_Y, panY).putFloat(KEY_SCALE, scale).apply()
    }

    fun savedViewport(context: android.content.Context): Viewport? {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SCALE)) return null
        val scale = prefs.getFloat(KEY_SCALE, 0f)
        if (!(scale > 0f)) return null
        return Viewport(prefs.getFloat(KEY_PAN_X, 0f), prefs.getFloat(KEY_PAN_Y, 0f), scale)
    }
}

object WhiteboardSceneGate {
    private val ui = Handler(Looper.getMainLooper())
    private var held = false
    private val pending = ArrayList<() -> Unit>()
    fun hold() { check(!held); held=true }
    fun dispatch(action: () -> Unit) { ui.post { if (held) pending.add(action) else action() } }
    fun release() { held=false; val work=pending.toList(); pending.clear(); work.forEach { it() } }
}
