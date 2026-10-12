package me.laumss.mosaic

import android.content.Context
import android.util.Log
import java.io.File

// Kotlin's own copy of the last board scene, written when the board closes and drawn at the next launch while JS is
// still reading board.mosaic. JS stays the source of truth: its scene replaces the cached one as soon as it arrives.
object SceneCache {
    private const val TAG = "MosaicSceneCache"
    private const val FILE_NAME = "board-scene.ops"

    // Bumped each time JS starts replacing the scene, so a cache read that finishes afterwards is dropped.
    @Volatile
    var jsGeneration = 0
        private set

    // Set once JS has pushed a scene in this process; before that the board may show only the cache.
    @Volatile
    var jsSceneArrived = false
        private set

    // Called on the UI thread right after JS's scene is applied.
    var onJsSceneApplied: (() -> Unit)? = null

    fun jsReplaceStarted() {
        jsGeneration++
    }

    fun jsSceneApplied() {
        jsSceneArrived = true
        onJsSceneApplied?.invoke()
    }

    fun save(context: Context, scene: BoardEngine.SceneSnapshot) {
        val dir = context.filesDir
        Thread({
            try {
                val bytes = BoardEngine.encodeSceneOps(scene)
                val tmp = File(dir, "$FILE_NAME.tmp")
                tmp.writeBytes(bytes)
                val file = File(dir, FILE_NAME)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
                Log.i(TAG, "saved cards=${scene.cards.size} strokes=${scene.strokes.size} bytes=${bytes.size}")
            } catch (error: Throwable) {
                Log.w(TAG, "save failed", error)
            }
        }, "MosaicSceneCacheSave").start()
    }

    // Reads the cache off the UI thread; onLoaded runs on that thread with the bytes, or is not called.
    fun load(context: Context, onLoaded: (ByteArray) -> Unit) {
        val file = File(context.filesDir, FILE_NAME)
        Thread({
            val bytes = try {
                if (file.isFile) file.readBytes() else null
            } catch (error: Throwable) {
                Log.w(TAG, "load failed", error)
                null
            }
            if (bytes != null && bytes.size > 6) onLoaded(bytes)
        }, "MosaicSceneCacheLoad").start()
    }
}
