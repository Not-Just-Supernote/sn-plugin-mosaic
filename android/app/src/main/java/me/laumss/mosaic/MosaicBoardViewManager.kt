package me.laumss.mosaic

import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
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

    @ReactProp(name = "deviceType", defaultInt = -1)
    fun setDeviceType(view: MosaicBoardView, value: Int) {
        view.deviceType = value
    }

    @ReactProp(name = "notesDirectory")
    fun setNotesDirectory(view: MosaicBoardView, value: String?) { view.notesDirectory = value ?: "" }

    @ReactProp(name = "touchEnabled", defaultBoolean = true)
    fun setTouchEnabled(view: MosaicBoardView, value: Boolean) {
        view.touchEnabled = value
    }
}
