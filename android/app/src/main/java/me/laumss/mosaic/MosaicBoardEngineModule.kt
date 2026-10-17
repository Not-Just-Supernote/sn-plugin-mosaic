package me.laumss.mosaic

import android.os.Handler
import android.os.Looper
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.uimanager.UIManagerModule

class MosaicBoardEngineModule(
    reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    override fun getName(): String = "MosaicBoardEngine"

    // JS replaces the whole scene as clearScene, a run of applyOps, then documentReplaced. Those calls are gathered
    // here and run as one UI step, so no frame rasters the empty board between the clear and the new content.
    private val replaceLock = Any()
    private var replacing: ArrayList<() -> Unit>? = null
    private val main = Handler(Looper.getMainLooper())
    // JS skips documentReplaced while the board view is not mounted yet; the gathered scene still goes in then.
    private val replaceTimeout = Runnable { flushReplace(null) }

    private fun enqueue(action: () -> Unit) {
        synchronized(replaceLock) {
            replacing?.let { it.add(action); return }
        }
        WhiteboardSceneGate.dispatch(action)
    }

    private fun flushReplace(viewTag: Int?): Boolean {
        val batch = synchronized(replaceLock) { replacing.also { replacing = null } } ?: return false
        main.removeCallbacks(replaceTimeout)
        WhiteboardSceneGate.dispatch {
            batch.forEach { it() }
            SceneCache.jsSceneApplied()
            viewTag?.let { resolveView(it) }?.controller?.onDocumentReplaced()
        }
        return true
    }

    private fun resolveView(viewTag: Int): MosaicBoardView? = try {
        reactApplicationContext.getNativeModule(UIManagerModule::class.java)?.resolveView(viewTag) as? MosaicBoardView
    } catch (_: Throwable) { null }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun getCurrentSurface(): String = InklingLink.currentSurface()

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun isBoardSurface(): Boolean = InklingLink.isBoardSurface()

    @ReactMethod
    fun applyOps(base64: String) {
        enqueue { BoardEngine.applyOps(base64) }
    }

    @ReactMethod
    fun setViewport(panX: Double, panY: Double, scale: Double) {
        enqueue { BoardEngine.setViewport(panX.toFloat(), panY.toFloat(), scale.toFloat()) }
    }

    @ReactMethod
    fun setViewportIfUnset(panX: Double, panY: Double, scale: Double) {
        val ctx = reactApplicationContext
        if (MosaicSession.savedViewport(ctx) != null) return
        enqueue {
            BoardEngine.setViewport(panX.toFloat(), panY.toFloat(), scale.toFloat())
            MosaicSession.saveViewport(ctx, BoardEngine.panX, BoardEngine.panY, BoardEngine.scale)
        }
    }

    @ReactMethod
    fun selectCards(viewTag: Int, ids: ReadableArray) {
        val list = ArrayList<String>()
        for (i in 0 until ids.size()) ids.getString(i)?.let { list.add(it) }
        withView(viewTag) { it.controller.selectCards(list) }
    }

    @ReactMethod
    fun clearScene() {
        SceneCache.jsReplaceStarted()
        flushReplace(null)
        synchronized(replaceLock) { replacing = arrayListOf({ BoardEngine.clearScene() }) }
        main.postDelayed(replaceTimeout, REPLACE_TIMEOUT_MS)
    }

    @ReactMethod
    fun documentReplaced(viewTag: Int) {
        if (flushReplace(viewTag)) return
        withView(viewTag) {
            SceneCache.jsSceneApplied()
            it.controller.onDocumentReplaced()
        }
    }

    @ReactMethod
    fun undo(viewTag: Int) {
        withView(viewTag) { it.controller.undo() }
    }

    @ReactMethod
    fun redo(viewTag: Int) {
        withView(viewTag) { it.controller.redo() }
    }

    @ReactMethod
    fun focusNoteShot(viewTag: Int, shotJson: String) {
        withView(viewTag) { it.controller.focusNoteShot(shotJson) }
    }

    @ReactMethod
    fun reloadNoteLinks(viewTag: Int) {
        withView(viewTag) { it.reloadNoteLinks() }
    }

    @ReactMethod
    fun renderNoteShot(viewTag: Int, shotJson: String, promise: Promise) {
        withView(viewTag, onMissing = { promise.resolve(null) }) { view ->
            view.controller.renderNoteShot(shotJson) { result -> promise.resolve(result) }
        }
    }

    @ReactMethod
    fun setSyncState(viewTag: Int, enabled: Boolean, state: String, address: String) {
        withView(viewTag) { it.controller.setSyncState(enabled, state, address) }
    }

    @ReactMethod
    fun createRecognizedTextCard(viewTag: Int, text: String) {
        withView(viewTag) { it.controller.createRecognizedTextCard(text) }
    }

    @ReactMethod
    fun insertDocTextCard(viewTag: Int, text: String) {
        withView(viewTag) { it.controller.insertDocTextCard(text) }
    }

    @ReactMethod
    fun importNoteCard(viewTag: Int, path: String, title: String, promise: Promise) {
        withView(viewTag, onMissing = { promise.resolve(false) }, waitForBoard = false) { view ->
            view.controller.importNoteCard(path, title) { result ->
                result.fold({ promise.resolve(it) }, { promise.reject("NOTE_IMPORT", it.message, it) })
            }
        }
    }

    @ReactMethod
    fun invalidateImages(paths: ReadableArray) {
        val list = ArrayList<String>()
        for (i in 0 until paths.size()) paths.getString(i)?.let { list.add(it) }
        enqueue { BoardEngine.invalidateImages(list) }
    }

    @ReactMethod
    fun addListener(eventName: String) = Unit

    @ReactMethod
    fun removeListeners(count: Int) = Unit

    private fun withView(viewTag: Int, onMissing: (() -> Unit)? = null, waitForBoard: Boolean = true, block: (MosaicBoardView) -> Unit) {
        main.post {
            val board = resolveView(viewTag)
            if (board == null) onMissing?.invoke()
            else if (waitForBoard) WhiteboardSceneGate.dispatch { block(board) }
            else block(board)
        }
    }

    private companion object {
        // Long enough for JS to encode a large board between the clear and the last applyOps.
        const val REPLACE_TIMEOUT_MS = 3000L
    }
}
