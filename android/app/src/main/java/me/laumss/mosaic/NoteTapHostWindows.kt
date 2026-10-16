package me.laumss.mosaic

import android.util.Log
import android.view.View

/** Read-only host visibility checks; preserves other plugins' window listeners. */
internal object NoteTapHostWindows {
    private val managerClass by lazy {
        Class.forName("com.ratta.supernote.pluginhost.manager.PluginManager")
    }
    private val getManager by lazy { managerClass.getMethod("getInstance") }
    private val isShowing by lazy { managerClass.getMethod("isShowPluginView") }
    private val windowClass by lazy { Class.forName("android.view.WindowManagerGlobal") }
    private val getWindows by lazy { windowClass.getMethod("getInstance") }
    private val views by lazy { windowClass.getDeclaredField("mViews").apply { isAccessible = true } }
    @Volatile private var failureLogged = false

    fun blocksNoteTap(): Boolean = try {
        val manager = getManager.invoke(null)
        if (manager == null || isShowing.invoke(manager) != false) {
            true
        } else {
            // Dialogs can outlive the main plugin surface. A focused host-process
            // window owns the touch even while the underlying Note Activity stays top.
            val roots = (views.get(getWindows.invoke(null)) as? List<*>)?.toList()
                ?: error("Host window snapshot unavailable")
            roots.filterIsInstance<View>().any { it.isShown && it.hasWindowFocus() }
        }
    } catch (error: Throwable) {
        if (!failureLogged) {
            failureLogged = true
            Log.w("MosaicNoteShotNative", "note tap visibility unavailable: ${error.message}")
        }
        true
    }
}
