package me.laumss.mosaic

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
    fun open(dir: String, ref: String, create: Boolean, done: (Result<ScrollingDocument>) -> Unit) {
        require(ref.matches(Regex("note-[a-zA-Z0-9-]+")))
        directory = File(dir)
        io.execute {
            val result = runCatching {
                val file = File(directory,"$ref.mnote")
                if (create) ScrollingDocument(ref,emptyList(),0f).also { TchFile.write(file,it); NotePreview.write(File(directory,"$ref.png"),it) }
                else TchFile.read(file,ref)
            }
            ui.post { result.onSuccess { document=it }; done(result) }
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
        io.execute {
            val result = runCatching {
                TchFile.write(File(directory,"${snapshot.ref}.mnote"),snapshot)
                if (preview) NotePreview.write(File(directory,"${snapshot.ref}.png"),snapshot)
                Log.i("MosaicNote","saved ref=${snapshot.ref} strokes=${snapshot.strokes.size} height=${snapshot.contentHeight} preview=$preview")
                snapshot
            }
            ui.post { done(result) }
        }
    }
    fun clear() { pending?.let(ui::removeCallbacks); pending=null; document=null }
    fun previewPath(ref: String) = File(directory,"$ref.png").absolutePath
}


object MosaicSession {
    private const val PREFS = "mosaic_session"
    private const val KEY_NOTE_REF = "noteRef"
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
