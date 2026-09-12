package me.laumss.mosaic

import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

class MosaicBoardViewManager : SimpleViewManager<MosaicBoardView>() {

    companion object {
        const val REACT_NAME = "MosaicBoardView"
    }

    override fun getName(): String = REACT_NAME

    override fun createViewInstance(reactContext: ThemedReactContext): MosaicBoardView =
        MosaicBoardView(reactContext as ReactContext)

    @ReactProp(name = "penWidth")
    fun setPenWidth(view: MosaicBoardView, value: Int) {
        if (view.penWidth == value) return
        view.penWidth = value
        view.onPropsChanged()
    }

    
    @ReactProp(name = "deviceType", defaultInt = -1)
    fun setDeviceType(view: MosaicBoardView, value: Int) {
        view.deviceType = value
    }

    
    @ReactProp(name = "touchEnabled", defaultBoolean = true)
    fun setTouchEnabled(view: MosaicBoardView, value: Boolean) {
        view.touchEnabled = value
    }
}
