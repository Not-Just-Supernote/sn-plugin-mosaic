package me.laumss.mosaic

import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
import android.os.SystemClock
import android.util.Log

/** Closes the plugin through the host when JS has not closed it in time after a close request. */
internal class CloseWatchdog(private val view: MosaicBoardView) {
    companion object {
        private const val CLOSE_WATCHDOG_MS = 3000L
        private const val CLOSE_WATCHDOG_NOTE_SHOT_MS = 8000L
        private const val HOST_PLUGIN_MANAGER_MODULE = "NativePluginManager"
    }

    private val fireTask = Runnable {
        Log.i(LogTags.BOARD_VIEW, "[CloseWatchdog] fired attached=${view.attached} sinceArmMs=${SystemClock.uptimeMillis() - armedUptime}")
        if (!view.attached) return@Runnable
        Log.w(LogTags.BOARD_VIEW, "[CloseWatchdog] JS did not close within ${delayMs}ms; forcing host closePluginView")
        forceHostClose("watchdog")
    }

    private var armedUptime = 0L

    private var armedWall = 0L

    private var delayMs = CLOSE_WATCHDOG_MS

    private val heartbeat = object : Runnable {
        override fun run() {
            val up = SystemClock.uptimeMillis() - armedUptime
            val wall = System.currentTimeMillis() - armedWall
            Log.i(LogTags.BOARD_VIEW, "[CloseWatchdog] tick uptimeMs=$up wallMs=$wall drift=${wall - up} attached=${view.attached}")
            if (view.attached) view.mainHandler.postDelayed(this, 1000L)
        }
    }

    fun arm(reason: String, noteShotSession: Boolean = false) {
        if (!view.attached) return
        armedUptime = SystemClock.uptimeMillis()
        armedWall = System.currentTimeMillis()
        delayMs = if (noteShotSession) CLOSE_WATCHDOG_NOTE_SHOT_MS else CLOSE_WATCHDOG_MS
        view.mainHandler.removeCallbacks(fireTask)
        view.mainHandler.removeCallbacks(heartbeat)
        view.mainHandler.postDelayed(fireTask, delayMs)
        view.mainHandler.postDelayed(heartbeat, 1000L)
        Log.i(LogTags.BOARD_VIEW, "[CloseWatchdog] armed reason=$reason delay=${delayMs}ms")
    }

    private fun forceHostClose(reason: String) {
        try {
            val module = view.reactContext.catalystInstance?.getNativeModule(HOST_PLUGIN_MANAGER_MODULE)
            if (module == null) {
                Log.w(LogTags.BOARD_VIEW, "[CloseWatchdog] $HOST_PLUGIN_MANAGER_MODULE unavailable reason=$reason")
                return
            }
            val methods = module.javaClass.methods.filter { it.name == "closePluginView" }
            methods.firstOrNull { it.parameterTypes.isEmpty() }?.let {
                it.invoke(module)
                Log.i(LogTags.BOARD_VIEW, "[CloseWatchdog] closePluginView() forced reason=$reason")
                return
            }
            methods.firstOrNull { it.parameterTypes.size == 1 }?.let {
                it.invoke(module, com.facebook.react.bridge.PromiseImpl(null, null))
                Log.i(LogTags.BOARD_VIEW, "[CloseWatchdog] closePluginView(promise) forced reason=$reason")
                return
            }
            Log.w(LogTags.BOARD_VIEW, "[CloseWatchdog] no closePluginView method on host module reason=$reason")
        } catch (error: Throwable) {
            Log.w(LogTags.BOARD_VIEW, "[CloseWatchdog] force close failed reason=$reason", error)
        }
    }
}
