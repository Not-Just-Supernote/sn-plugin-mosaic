package me.laumss.mosaic

import android.content.Intent
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod


class MosaicHandwritingModule(
    reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "MosaicHandwriting"
    }

    private var inputReader: InputReader? = null
    private var sliderReader: SliderReader? = null
    private var refreshKeyReader: RefreshKeyReader? = null

    override fun getName(): String = "MosaicHandwriting"

    @ReactMethod
    fun attachInput(deviceType: Int, promise: Promise) {
        try {
            
            
            inputReader = InputReader.ensureBoardReader(reactApplicationContext)
            Log.i(TAG, "attachInput: native board reader reused")
            if (refreshKeyReader == null) {
                
                val refresh = RefreshKeyReader()
                refresh.start()
                refreshKeyReader = refresh
            }
            if (sliderReader == null && SliderReader.isSupportedDevice(deviceType)) {
                val slider = SliderReader()
                slider.start()
                sliderReader = slider
                Log.i(TAG, "attachInput: slider started deviceType=$deviceType")
            }
            promise.resolve(true)
        } catch (e: Throwable) {
            Log.e(TAG, "attachInput failed", e)
            promise.reject("ATTACH_INPUT_FAILED", e)
        }
    }

    @ReactMethod
    fun detachInput(promise: Promise) {
        InputReader.releaseBoardReader()
        inputReader = null
        sliderReader?.stop()
        sliderReader = null
        refreshKeyReader?.stop()
        refreshKeyReader = null
        InputArbiter.reset()
        
        
        Log.i(TAG, "detachInput: reader + slider stopped")
        promise.resolve(true)
    }

    
    @ReactMethod
    fun readGestureSettings(promise: Promise) {
        try {
            val resolved = GestureSettings.read(reactApplicationContext)
            val result = Arguments.createMap()
            for ((key, value) in resolved.raw) result.putString(key, value)
            val sources = Arguments.createMap()
            for ((key, value) in resolved.sources) sources.putString(key, value)
            result.putMap("_sources", sources)
            result.putString("_resolved", resolved.describe())
            promise.resolve(result)
        } catch (e: Throwable) {
            Log.e(TAG, "readGestureSettings failed", e)
            promise.reject("READ_GESTURE_SETTINGS_FAILED", e)
        }
    }

    @ReactMethod
    fun lockStatusBar(lock: Boolean, promise: Promise) {
        try {
            val intent = Intent("com.ratta.supernote.launcher.BroadcastReceiver.slidebarstatusbar")
            intent.putExtra("lockStatusbar", lock)
            reactApplicationContext.sendBroadcast(intent)
            Log.i(TAG, "lockStatusBar: lock=$lock broadcast sent")
            promise.resolve(true)
        } catch (e: Throwable) {
            Log.e(TAG, "lockStatusBar failed", e)
            promise.reject("LOCK_STATUSBAR_FAILED", e)
        }
    }

}
