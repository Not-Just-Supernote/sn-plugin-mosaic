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

        class RefreshProbe(
            val name: String,
            val quietService: Boolean = false,
            internal val run: (View, Any?) -> Boolean,
        )

        private val probes = listOf(
            RefreshProbe("view.forceEinkFullUpdate") { view, _ -> callAuto(view, "forceEinkFullUpdate") },
            RefreshProbe("root.forceEinkFullUpdate") { view, _ -> callAuto(view.rootView, "forceEinkFullUpdate") },
            RefreshProbe("manager.screenRefresh") { _, manager -> callAuto(manager, "screenRefresh", 2) },
            RefreshProbe("view.refreshCurrentView") { view, _ -> callAuto(view, "refreshCurrentView") },
            RefreshProbe("quiet-service+sendOneFullFrame", quietService = true) { _, manager ->
                callAuto(manager, "sendOneFullFrame")
            },
            RefreshProbe("force+sendOneFullFrame") { view, manager ->
                val forced = callAuto(view, "forceEinkFullUpdate")
                callAuto(manager, "sendOneFullFrame") || forced
            },
        )

        private var probeCursor = 0

        @JvmStatic
        fun nextFullRefreshProbe(): RefreshProbe = synchronized(probes) { probes[probeCursor++ % probes.size] }

        @JvmStatic
        fun requestFullRefresh(view: View, reason: String, probe: RefreshProbe): Boolean {
            val manager = try {
                view.context.getSystemService(EINK_SERVICE)
            } catch (e: Throwable) {
                null
            }
            logEinkApisOnce(manager)
            val before = einkState(view, manager)
            val ok = try {
                probe.run(view, manager)
            } catch (e: Throwable) {
                Log.w(TAG, "full refresh probe ${probe.name} failed: ${e.cause?.message ?: e.message}")
                false
            }
            view.postInvalidateOnAnimation()
            view.rootView.postInvalidateOnAnimation()
            val number = probes.indexOf(probe) + 1
            Log.i(TAG, "full refresh probe=$number/${probes.size} ${probe.name} reason=$reason ok=$ok before=[$before]")
            return ok
        }

        private fun callAuto(target: Any?, name: String, preferParams: Int = -1): Boolean {
            if (target == null) return false
            val type = target.javaClass
            val found = (type.methods.asSequence() + type.declaredMethods.asSequence()).filter { it.name == name }.toList()
            val method = found.firstOrNull { it.parameterTypes.size == preferParams }
                ?: found.minByOrNull { it.parameterTypes.size }
            if (method == null) {
                Log.w(TAG, "probe call ${type.simpleName}.$name missing")
                return false
            }
            val args = method.parameterTypes.map { defaultArg(it) }.toTypedArray()
            return try {
                method.isAccessible = true
                val result = method.invoke(target, *args)
                Log.i(TAG, "probe call ${type.simpleName}.$name(${args.joinToString()}) -> $result")
                true
            } catch (e: Throwable) {
                Log.w(TAG, "probe call ${type.simpleName}.$name failed: ${e.cause?.message ?: e.message}")
                false
            }
        }

        private fun defaultArg(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> true
            Int::class.javaPrimitiveType -> 1
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            String::class.java -> ""
            else -> null
        }

        private fun callResult(target: Any?, name: String): Any? {
            if (target == null) return null
            return try {
                target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.invoke(target)
            } catch (e: Throwable) {
                "err:${e.cause?.message ?: e.message}"
            }
        }

        private fun einkState(view: View, manager: Any?): String =
            "viewMode=${callResult(view, "getEinkUpdateMode")} final=${callResult(view, "getEinkFinalUpdateMode")} " +
                "modeSet=${callResult(view, "isEinkModeSet")} a2Gate=${callResult(view, "getEinkA2Gate")} " +
                "managerMode=${callResult(manager, "getMode")}"

        private fun logEinkApisOnce(manager: Any?) {
            if (einkApisLogged) return
            einkApisLogged = true
            val pattern = Regex("(?i)eink|epd|refresh|fullframe")
            val viewMethods = View::class.java.methods.filter { pattern.containsMatchIn(it.name) }
            val managerMethods = manager?.javaClass?.methods?.filter { it.declaringClass != Any::class.java } ?: emptyList()
            Log.i(TAG, "eink apis manager=${manager?.javaClass?.name}")
            (viewMethods + managerMethods).map { it.toGenericString() }.distinct().sorted().chunked(5)
                .forEach { Log.i(TAG, "eink signatures $it") }
            val constantPattern = Regex("(?i)eink|epd|a2|gc16|regal|dither|fullframe|update_mode|refresh_mode")
            val constants = ArrayList<String>()
            for (type in listOfNotNull(View::class.java, manager?.javaClass)) {
                for (field in type.fields) {
                    if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    if (field.type != Int::class.javaPrimitiveType || !constantPattern.containsMatchIn(field.name)) continue
                    constants.add("${type.simpleName}.${field.name}=${field.getInt(null)}")
                }
            }
            constants.sorted().chunked(8).forEach { Log.i(TAG, "eink constants $it") }
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
