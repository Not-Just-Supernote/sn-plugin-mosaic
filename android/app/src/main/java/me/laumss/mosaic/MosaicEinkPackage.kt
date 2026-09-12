package me.laumss.mosaic

import android.util.Log
import com.facebook.react.ReactPackage
import com.facebook.react.ViewManagerOnDemandReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

class MosaicEinkPackage : ReactPackage, ViewManagerOnDemandReactPackage {
    companion object {
        private const val TAG = "MosaicEinkPackage"
    }

    override fun createNativeModules(
        reactContext: ReactApplicationContext,
    ): List<NativeModule> = listOf(
        MosaicEinkRefreshModule(reactContext),
        MosaicHandwritingModule(reactContext),
        MosaicNoteShotModule(reactContext),
        MosaicBoardEngineModule(reactContext),
        MosaicImageModule(reactContext),
    )

    override fun createViewManagers(
        reactContext: ReactApplicationContext,
    ): List<ViewManager<*, *>> {
        Log.i(TAG, "registering ${MosaicBoardViewManager.REACT_NAME} eagerly")
        return listOf(MosaicBoardViewManager())
    }

    override fun getViewManagerNames(
        reactContext: ReactApplicationContext,
    ): Collection<String> = setOf(MosaicBoardViewManager.REACT_NAME)

    override fun createViewManager(
        reactContext: ReactApplicationContext,
        viewManagerName: String,
    ): ViewManager<*, *>? {
        if (viewManagerName != MosaicBoardViewManager.REACT_NAME) return null
        Log.i(TAG, "creating ${MosaicBoardViewManager.REACT_NAME} on demand")
        return MosaicBoardViewManager()
    }
}
