package me.laumss.mosaic

import android.util.Log
import com.facebook.react.bridge.ReactContext
import com.ratta.supernote.plugincommon.data.note.Layer
import com.ratta.supernote.pluginlib.api.HostCommonAPI
import com.ratta.supernote.pluginlib.api.HostDataCacheAPI
import com.ratta.supernote.pluginlib.core.PluginAppAPI
import com.ratta.supernote.pluginlib.modules.PluginModule


object DrawPathContextClient {
    private const val TAG = "MosaicDrawPathContext"

    data class Snapshot(
        val notePath: String,
        val sdkPage: Int,
        val hostPage: Int,
        val layerId: Int,
    )

    fun refresh(reactContext: ReactContext, callback: (Snapshot?) -> Unit) {
        val app = pluginApp(reactContext)
        val host = try { HostCommonAPI.getInstance() } catch (error: Throwable) {
            Log.w(TAG, "HostCommonAPI lookup failed", error)
            null
        }
        val notePath = try {
            HostDataCacheAPI.getInstance()?.currentFilePath
                ?.takeIf { it.isNotBlank() }
        } catch (error: Throwable) {
            Log.w(TAG, "current note path lookup failed", error)
            null
        }

        if (app == null || host == null || notePath == null) {
            Log.i(
                TAG,
                "context unavailable app=${app != null} host=${host != null} " +
                    "path=${notePath != null}",
            )
            callback(null)
            return
        }

        try {
            host.getCurrentPageNum(app) { pageResponse ->
                val sdkPage = if (pageResponse.isSuccess) {
                    pageResponse.getResult(Number::class.java)?.toInt()
                        ?: (pageResponse.getResult() as? Number)?.toInt()
                } else {
                    null
                }
                if (sdkPage == null || sdkPage < 0) {
                    Log.i(TAG, "current page unavailable success=${pageResponse.isSuccess}")
                    callback(null)
                    return@getCurrentPageNum
                }

                try {
                    
                    host.getLayers(app, notePath, sdkPage) { layerResponse ->
                        val layerId = if (layerResponse.isSuccess) {
                            currentLayerId(layerResponse.getResult())
                        } else {
                            null
                        }
                        if (layerId == null) {
                            Log.i(
                                TAG,
                                "current layer unavailable page=$sdkPage " +
                                    "success=${layerResponse.isSuccess}",
                            )
                            callback(null)
                            return@getLayers
                        }
                        val hostPage = sdkPage + 1
                        val snapshot = Snapshot(notePath, sdkPage, hostPage, layerId)
                        Log.i(
                            TAG,
                            "context resolved path=$notePath sdkPage=$sdkPage " +
                                "hostPage=$hostPage layer=$layerId",
                        )
                        callback(snapshot)
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "getLayers failed page=$sdkPage", error)
                    callback(null)
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "getCurrentPageNum failed", error)
            callback(null)
        }
    }

    private fun pluginApp(reactContext: ReactContext): PluginAppAPI? = try {
        reactContext.getNativeModule(PluginModule::class.java)?.pluginApp
    } catch (error: Throwable) {
        Log.w(TAG, "plugin app lookup failed", error)
        null
    }

    private fun currentLayerId(result: Any?): Int? {
        val layers = result as? List<*> ?: return null
        val typedLayers = layers.filterIsInstance<Layer>()
        
        
        
        
        
        return typedLayers.firstOrNull { it.isCurrentLayer }?.layerId
            ?: typedLayers.firstOrNull { it.layerId == 0 }?.layerId
    }
}
