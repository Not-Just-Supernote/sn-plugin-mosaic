package me.laumss.mosaic.inkling

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.modules.core.DeviceEventManagerModule

/** Requests from Notipal (Inkling in code), moved onto the main thread and handed to the board. */
internal class InklingCallbacks(private val view: MosaicBoardView) : InklingLink.Host {
    companion object {
        private const val INBOX_READY_EVENT = "MosaicInboxReady"
    }

    override fun onInklingToolbarRectChanged() {
        if (!view.attached) return
        view.mainHandler.post {
            if (!view.attached) return@post
            view.drawPath.rearmDrawPath("inkling-toolbar-rect")
        }
    }

    override fun onInklingOverlayDragChanged(active: Boolean) {
        if (!view.attached) return
        view.mainHandler.post {
            if (!view.attached) return@post
            view.controller.inkling.setOverlayDrag(active, "inkling")
        }
    }

    override fun onInklingCloseRequested() {
        if (!view.attached) return
        view.mainHandler.post {
            if (!view.attached) return@post
            view.controller.notes.closeForInkling()
        }
    }

    override fun onInklingPasteStrokesRequested() {
        if (!view.attached) return
        view.mainHandler.post {
            if (!view.attached) return@post
            if (!InklingLink.isInkSurface()) {
                Log.i(LogTags.BOARD_VIEW, "inkling paste deferred surface=${InklingLink.currentSurface()}")
                return@post
            }
            view.controller.inkling.handlePasteStrokes()
        }
    }

    override fun onInklingClearSelectionRequested(delete: Boolean) {
        if (!view.attached) return
        view.mainHandler.post {
            if (!view.attached) return@post
            if (!InklingLink.isInkSurface()) {
                Log.i(LogTags.BOARD_VIEW, "inkling clear-selection deferred surface=${InklingLink.currentSurface()} delete=$delete")
                return@post
            }
            view.controller.inkling.clearSelection(delete)
        }
    }

    override fun onInklingTextCardRequested(text: String, anchorScreenX: Int, anchorScreenY: Int) {
        if (!view.attached) return
        view.mainHandler.post {
            if (!view.attached) return@post
            if (!InklingLink.isBoardSurface()) {
                Log.i(LogTags.BOARD_VIEW, "inkling text-card deferred surface=${InklingLink.currentSurface()}")
                return@post
            }
            view.controller.inkling.insertTextCard(text, anchorScreenX, anchorScreenY)
        }
    }

    override fun onInklingPaletteRequested(requestId: String, data: String?) {
        view.mainHandler.post {
            val result = if (view.attached && InklingLink.isBoardSurface()) {
                try {
                    if (data == null) view.controller.inkling.paletteSelectionInfo()
                    else view.controller.inkling.applyPaletteSelection(org.json.JSONObject(data))
                } catch (error: Exception) {
                    Log.w(LogTags.BOARD_VIEW, "palette request failed", error)
                    org.json.JSONObject().put("success", false).put("error", "apply_failed")
                }
            } else org.json.JSONObject().put("success", false).put("error", "surface_changed")
            InklingLink.replyPalette(requestId, result)
        }
    }

    override fun onInklingInboxReady() {
        if (!view.attached) return
        view.mainHandler.post {
            if (!view.attached) return@post
            view.controller.notes.preparePendingNoteImport()
            if (!InklingLink.isBoardSurface()) {
                Log.i(LogTags.BOARD_VIEW, "inkling inbox notification deferred surface=${InklingLink.currentSurface()}")
                return@post
            }
            view.reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(INBOX_READY_EVENT, null)
        }
    }

    override fun onInklingSurfaceChanged(surface: String, noteRef: String?) {
        if (!view.attached) return
        view.mainHandler.post {
            if (!view.attached) return@post

            val data = Arguments.createMap().apply {
                putBoolean("visible", true)
                putString("surface", surface)
                noteRef?.let { putString("noteRef", it) } ?: putNull("noteRef")
            }
            view.reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(MosaicBoardView.BOARD_VISIBILITY_EVENT, data)
            Log.i(LogTags.BOARD_VIEW, "surface event surface=$surface noteRef=$noteRef")
        }
    }
}
