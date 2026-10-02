package me.laumss.mosaic

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean


class MosaicPermissionModule(context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    companion object {
        private const val TAG = "MosaicPermission"
        private const val PLUGIN_ID = "mosaic20260730ab"
        private const val HOST_MANAGER = "com.ratta.supernote.pluginhost.manager.PluginManager"
        private const val CALLBACK = "com.ratta.supernote.pluginhost.security.PluginPermissionManager\$PermissionCallback"
        private val PERMISSIONS = listOf(
            "plugin.permission.FILE:READ",
            "plugin.permission.FILE:WRITE",
            "plugin.permission.FILE:DELETE",
            "plugin.permission.INTERNET",
        )
    }

    override fun getName(): String = "MosaicPermission"

    @ReactMethod
    fun requestPermissions(promise: Promise) {
        Handler(Looper.getMainLooper()).post {
            requestAt(0, promise)
        }
    }

    private fun requestAt(index: Int, promise: Promise) {
        if (index >= PERMISSIONS.size) {
            promise.resolve(true)
            return
        }
        requestDirect(PERMISSIONS[index]) { granted ->
            if (!granted) promise.resolve(false) else requestAt(index + 1, promise)
        }
    }

    private fun requestDirect(permission: String, result: (Boolean) -> Unit) {
        try {
            val managerClass = Class.forName(HOST_MANAGER)
            val manager = managerClass.getMethod("getInstance").invoke(null)
            val pluginApp = managerClass.getMethod("getPluginApp", String::class.java)
                .invoke(manager, PLUGIN_ID) ?: error("PluginApp unavailable")
            val has = managerClass.methods.firstOrNull {
                it.name == "hasPermission" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == String::class.java
            }?.invoke(manager, pluginApp, permission) as? Number
            if (has != null && has.toInt() > 0) {
                Log.i(TAG, "permission already granted perm=$permission status=$has")
                result(true)
                return
            }
            val request = managerClass.methods.firstOrNull {
                it.name == "requestPermission" && it.parameterTypes.size == 4 &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == String::class.java
            } ?: error("host requestPermission unavailable")
            val callbackLoader = reactApplicationContext.classLoader
            val callbackType = Class.forName(CALLBACK, false, callbackLoader)
            val finished = AtomicBoolean(false)
            val callback = Proxy.newProxyInstance(callbackLoader, arrayOf(callbackType)) { _, method, args ->
                if (method.name == "onResult") {
                    val status = (args?.firstOrNull() as? Number)?.toInt() ?: 0
                    Log.i(TAG, "permission result perm=$permission status=$status")
                    if (finished.compareAndSet(false, true)) result(status > 0)
                } else if (method.name == "onError") {
                    Log.i(TAG, "permission error perm=$permission")
                    if (finished.compareAndSet(false, true)) result(false)
                }
                null
            }
            val description = "Mosaic 需要访问白板文件与同步服务"
            Log.i(TAG, "requestPermission plugin=$PLUGIN_ID perm=$permission hasDesc=${description.isNotEmpty()}")
            request.invoke(manager, pluginApp, permission, description, callback)
        } catch (error: Throwable) {
            Log.e(TAG, "requestPermission failed perm=$permission", error)
            result(false)
        }
    }
}
