package me.laumss.mosaic

import android.os.Handler
import android.os.Looper
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.uimanager.UIManagerModule


class MosaicBoardEngineModule(
    reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "MosaicBoardEngine"

    
    @ReactMethod
    fun applyOps(base64: String) {
        BoardEngine.applyOps(base64)
    }

    
    @ReactMethod
    fun setViewport(panX: Double, panY: Double, scale: Double) {
        BoardEngine.setViewport(panX.toFloat(), panY.toFloat(), scale.toFloat())
    }

    
    @ReactMethod
    fun clearScene() {
        BoardEngine.clearScene()
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
    fun addListener(eventName: String) = Unit

    @ReactMethod
    fun removeListeners(count: Int) = Unit

    private fun withView(viewTag: Int, block: (MosaicBoardView) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            val view = try {
                reactApplicationContext.getNativeModule(UIManagerModule::class.java)?.resolveView(viewTag)
            } catch (_: Throwable) { null }
            (view as? MosaicBoardView)?.let(block)
        }
    }
}
