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

    
    @ReactMethod(isBlockingSynchronousMethod = true)
    fun getCurrentSurface(): String = InklingLink.currentSurface()

    
    @ReactMethod(isBlockingSynchronousMethod = true)
    fun isBoardSurface(): Boolean = InklingLink.isBoardSurface()

    
    @ReactMethod
    fun applyOps(base64: String) {
        WhiteboardSceneGate.dispatch { BoardEngine.applyOps(base64) }
    }

    
    @ReactMethod
    fun setViewport(panX: Double, panY: Double, scale: Double) {
        WhiteboardSceneGate.dispatch { BoardEngine.setViewport(panX.toFloat(), panY.toFloat(), scale.toFloat()) }
    }

    
    @ReactMethod
    fun setViewportIfUnset(panX: Double, panY: Double, scale: Double) {
        val ctx = reactApplicationContext
        if (MosaicSession.savedViewport(ctx) != null) return
        WhiteboardSceneGate.dispatch {
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
        WhiteboardSceneGate.dispatch { BoardEngine.clearScene() }
    }

    
    @ReactMethod
    fun documentReplaced(viewTag: Int) {
        withView(viewTag) { it.controller.onDocumentReplaced() }
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
        withView(viewTag, onMissing = { promise.resolve(false) }) { view ->
            view.controller.importNoteCard(path, title) { result ->
                result.fold({ promise.resolve(it) }, { promise.reject("NOTE_IMPORT", it.message, it) })
            }
        }
    }

    
    @ReactMethod
    fun invalidateImages(paths: ReadableArray) {
        val list = ArrayList<String>()
        for (i in 0 until paths.size()) paths.getString(i)?.let { list.add(it) }
        WhiteboardSceneGate.dispatch { BoardEngine.invalidateImages(list) }
    }

    
    @ReactMethod
    fun addListener(eventName: String) = Unit

    @ReactMethod
    fun removeListeners(count: Int) = Unit

    private fun withView(viewTag: Int, onMissing: (() -> Unit)? = null, block: (MosaicBoardView) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            val view = try {
                reactApplicationContext.getNativeModule(UIManagerModule::class.java)?.resolveView(viewTag)
            } catch (_: Throwable) { null }
            val board = view as? MosaicBoardView
            if (board == null) onMissing?.invoke() else WhiteboardSceneGate.dispatch { block(board) }
        }
    }
}
