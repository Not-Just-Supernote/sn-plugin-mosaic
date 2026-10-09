package me.laumss.mosaic

import android.util.Log
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

        private const val EINK_SERVICE = "eink"

        @Volatile private var instance: WeakReference<MosaicEinkRefreshModule>? = null

        
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

        
        private fun runOnUiImmediate(block: () -> Unit) {
            if (UiThreadUtil.isOnUiThread()) block() else UiThreadUtil.runOnUiThread(block)
        }

        @Volatile private var einkApisLogged = false

        
        @JvmStatic
        fun requestFullRefresh(view: View, reason: String): Boolean {
            val manager = try {
                view.context.getSystemService(EINK_SERVICE)
            } catch (e: Throwable) {
                null
            }
            logEinkApisOnce(manager)
            if (manager == null) {
                Log.w(TAG, "full refresh unavailable reason=$reason: no $EINK_SERVICE service")
                return false
            }
            val method = when {
                invokeEink(manager, "sendOneFullFrame") -> "sendOneFullFrame"
                invokeEink(manager, "screenRefresh", true, 1) -> "screenRefresh"
                else -> null
            }
            if (method == null) {
                Log.w(TAG, "full refresh unavailable reason=$reason: ${manager.javaClass.name} has no refresh method")
                return false
            }
            
            view.postInvalidateOnAnimation()
            view.rootView.postInvalidateOnAnimation()
            Log.i(TAG, "full refresh requested reason=$reason via=$method")
            return true
        }

        private fun invokeEink(manager: Any, name: String, vararg args: Any): Boolean {
            val type = manager.javaClass
            val method = (type.methods.asSequence() + type.declaredMethods.asSequence())
                .firstOrNull { it.name == name && it.parameterTypes.size == args.size }
                ?: return false
            return try {
                method.isAccessible = true
                method.invoke(manager, *args)
                true
            } catch (e: Throwable) {
                Log.w(TAG, "eink $name failed: ${e.cause?.message ?: e.message}")
                false
            }
        }

        
        private fun logEinkApisOnce(manager: Any?) {
            if (einkApisLogged) return
            einkApisLogged = true
            val pattern = Regex("(?i)eink|epd|refresh|fullframe")
            val viewApis = View::class.java.methods.map { it.name }.filter { pattern.containsMatchIn(it) }.distinct().sorted()
            val managerApis = manager?.javaClass?.methods?.map { it.name }?.distinct()?.sorted()
            Log.i(TAG, "eink apis view=$viewApis manager=${manager?.javaClass?.name} methods=$managerApis")
        }
    }

    private var activeMode: Int? = null
    private var modeView: WeakReference<View>? = null
    
    private val requests = LinkedHashMap<String, Int>()

    init {
        instance = WeakReference(this)
    }

    private fun effectiveMode(): Int? =
        if (requests.containsValue(MODE_A2)) MODE_A2 else requests.values.lastOrNull()

    private fun applyOwned(mode: Int, owner: String) {
        requests.remove(owner)
        requests[owner] = mode
        
        applyRefreshMode(effectiveMode())
    }

    private fun resetOwned(owner: String) {
        if (requests.remove(owner) == null) {
            Log.i(TAG, "reset skipped owner=$owner (no request) others=${requests.keys}")
            return
        }
        val next = effectiveMode()
        if (next != activeMode) applyRefreshMode(next)
        else Log.i(TAG, "reset owner=$owner kept mode=${next ?: "reset"} held by ${requests.keys}")
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
            Log.i(TAG, "refreshMode=${mode ?: "reset"} reassert=$reassert view=${target.javaClass.simpleName}")
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
