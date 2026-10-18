package me.laumss.mosaic

import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
import android.util.Log
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import java.lang.ref.WeakReference

class MosaicEinkRefreshModule(
    reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    companion object {
        private const val TAG = "MosaicEinkRefresh"
        const val MODE_DTH = 5

        const val MODE_DEFAULT = 7

        const val MODE_A2 = 4

        @Volatile private var instance: WeakReference<MosaicEinkRefreshModule>? = null

        @JvmStatic
        fun priority(mode: Int): Int = when (mode) {
            MODE_A2 -> 2
            MODE_DTH -> 1
            else -> 0
        }

        @JvmStatic
        fun applyNative(mode: Int, owner: String) {
            val module = instance?.get() ?: return
            runOnUiImmediate { module.applyOwned(mode, owner) }
        }

        @JvmStatic
        fun resetNative(owner: String) {
            val module = instance?.get() ?: return
            runOnUiImmediate { module.resetOwned(owner) }
        }

        /** Movement cleanup must not override an active input owner's fast mode. */
        fun hasFastModeRequest(): Boolean {
            val module = instance?.get() ?: return false
            return module.effectiveMode() in listOf(MODE_A2, MODE_DTH) ||
                module.activeMode in listOf(MODE_A2, MODE_DTH)
        }

        /** Document HandWriteClient.setRegalStatus: UI-auto Regal, not the master switch. */
        fun requestUiRegal(view: View, enabled: Boolean, reason: String): Boolean {
            if (!view.isAttachedToWindow || !view.isShown || !view.hasWindowFocus() ||
                view.windowVisibility != View.VISIBLE) return false
            return try {
                val manager = view.context.getSystemService("eink") ?: return false
                manager.javaClass.getMethod(
                    "sendHwcCmd", Int::class.javaPrimitiveType, IntArray::class.java,
                ).invoke(manager, 1020, intArrayOf(9, if (enabled) 1 else 0))
                Log.i(TAG, "UI Regal requested enabled=$enabled reason=$reason cmd=1020 args=9,${if (enabled) 1 else 0}")
                true
            } catch (error: Exception) {
                Log.w(TAG, "UI Regal request failed reason=$reason", error)
                false
            }
        }

        private fun runOnUiImmediate(block: () -> Unit) {
            if (UiThreadUtil.isOnUiThread()) block() else UiThreadUtil.runOnUiThread(block)
        }

        @JvmStatic
        fun requestFullRefresh(view: View, reason: String): Boolean {
            val root = view.rootView
            val method = root.javaClass.methods.firstOrNull { it.name == "forceEinkFullUpdate" && it.parameterTypes.isEmpty() }
            if (method == null) {
                Log.w(TAG, "full refresh unavailable reason=$reason: no forceEinkFullUpdate on ${root.javaClass.name}")
                return false
            }
            try {
                method.invoke(root)
            } catch (e: Throwable) {
                Log.w(TAG, "full refresh failed reason=$reason: ${e.cause?.message ?: e.message}")
                return false
            }
            view.postInvalidateOnAnimation()
            root.postInvalidateOnAnimation()
            Log.i(TAG, "full refresh requested reason=$reason via=root.forceEinkFullUpdate")
            return true
        }

        /** One submission with explicit gray conversion, leaving gesture ownership intact. */
        fun requestGrayFrame(view: View, reason: String): Boolean {
            if (!view.isAttachedToWindow || !view.isShown) return false
            return try {
                val root = View::class.java.getMethod("getViewRootImpl").invoke(view) ?: return false
                val rect = Rect()
                if (!view.getGlobalVisibleRect(rect)) return false
                // addViewDirtyRectX consumes surface coordinates. Global visible rect is
                // rooted in rootView; convert its origin to this window's coordinates.
                val origin = IntArray(2)
                view.rootView.getLocationInWindow(origin)
                rect.offset(origin[0], origin[1])
                root.javaClass.getMethod(
                    "addViewDirtyRectX", View::class.java, Rect::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                ).invoke(root, view, rect, MODE_DEFAULT, 3, true)
                // The firmware entry queues explicit metadata; invalidate schedules its frame.
                view.invalidate()
                Log.i(TAG, "gray frame queued reason=$reason rect=$rect disp=7 data=3")
                true
            } catch (error: Exception) {
                Log.w(TAG, "gray frame request failed reason=$reason", error)
                false
            }
        }
    }

    private var activeMode: Int? = null
    private var modeView: WeakReference<View>? = null

    private val requests = LinkedHashMap<String, Int>()

    init {
        instance = WeakReference(this)
    }

    // A2 > DTH > 其余；同级取最近的请求。
    private fun effectiveMode(): Int? {
        var best: Int? = null
        for (m in requests.values) if (best == null || priority(m) >= priority(best)) best = m
        return best
    }

    private fun applyOwned(mode: Int, owner: String): Boolean {
        requests.remove(owner)
        requests[owner] = mode

        return applyRefreshMode(effectiveMode())
    }

    private fun resetOwned(owner: String): Boolean {
        if (requests.remove(owner) == null) {
            Log.i(TAG, "reset skipped owner=$owner (no request) others=${requests.keys}")
            return true
        }
        val next = effectiveMode()
        if (next != activeMode) return applyRefreshMode(next)
        Log.i(TAG, "reset owner=$owner kept mode=${next ?: "reset"} held by ${requests.keys}")
        return true
    }

    override fun getName(): String = "MosaicEinkRefresh"

    @ReactMethod
    fun setRefreshMode(mode: Int, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            if (mode != MODE_DTH && mode != MODE_DEFAULT && mode != MODE_A2) {
                Log.w(TAG, "unsupported E-ink mode=$mode")
                promise.resolve(false)
                return@runOnUiThread
            }
            requests.remove("js")
            requests["js"] = mode
            promise.resolve(applyRefreshMode(effectiveMode()))
        }
    }

    @ReactMethod
    fun resetRefreshMode(promise: Promise) {
        UiThreadUtil.runOnUiThread {
            requests.remove("js")
            val next = effectiveMode()
            promise.resolve(if (next != activeMode) applyRefreshMode(next) else true)
        }
    }

    private fun applyRefreshMode(mode: Int?): Boolean {
        val reassert = mode != null && mode == activeMode

        val target = if (mode != null) {
            findPluginContainer()
                ?: reactApplicationContext.currentActivity?.window?.decorView
        } else {
            modeView?.get()
                ?: findPluginContainer()
                ?: reactApplicationContext.currentActivity?.window?.decorView
        }

        if (target == null) {
            Log.w(TAG, "E-ink target view unavailable mode=${mode ?: "reset"}")
            return false
        }

        return try {
            if (mode != null) {
                View::class.java
                    .getMethod("setEinkUpdateMode", Int::class.javaPrimitiveType)
                    .invoke(target, mode)
                modeView = WeakReference(target)
            } else {
                View::class.java
                    .getMethod("resetEinkUpdateMode")
                    .invoke(target)
                target.postInvalidateOnAnimation()
                modeView = null
            }
            activeMode = mode
            Log.i(TAG, "refreshMode=${mode ?: "reset"} reassert=$reassert view=${target.javaClass.simpleName} owners=$requests")
            true
        } catch (e: Exception) {
            Log.w(TAG, "eink call failed mode=${mode ?: "reset"}: ${e.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun findPluginContainer(): ViewGroup? {
        return try {
            val manager = reactApplicationContext.catalystInstance
                ?.getNativeModule("NativePluginManager") ?: return null
            val pluginAppField = manager.javaClass.declaredFieldOrNull("pluginApp") ?: return null
            pluginAppField.isAccessible = true
            val pluginApp = pluginAppField.get(manager) ?: return null
            val pluginViewField = pluginApp.javaClass.declaredFieldOrNull("pluginView") ?: return null
            pluginViewField.isAccessible = true
            pluginViewField.get(pluginApp) as? ViewGroup
        } catch (e: Exception) {
            Log.w(TAG, "plugin container lookup failed: ${e.message}")
            null
        }
    }

    private fun Class<*>.declaredFieldOrNull(name: String): java.lang.reflect.Field? {
        var type: Class<*>? = this
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == name }?.let { return it }
            type = type.superclass
        }
        return null
    }

    override fun invalidate() {
        if (instance?.get() === this) instance = null
        UiThreadUtil.runOnUiThread {
            requests.clear()
            applyRefreshMode(null)
        }
        super.invalidate()
    }
}
