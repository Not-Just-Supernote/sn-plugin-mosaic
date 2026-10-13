package me.laumss.mosaic

import android.content.Context
import android.util.Log

/**
 * 墨水屏 regal：开着时屏幕按帧识别并清理残影，但刷新慢一些。
 * 系统 Note（HandWriteClient）的用法：平时开着；从静止到开始移动的那一下开着，移动过程中关掉，松手再打开。
 * 宿主开关 auto 时也会顺手打开它。
 *
 * 宿主进程里有 Note 的 HandWriteClient 时直接调它，宿主记的开关状态跟着变；否则走 EinkManager.sendHwcCmd。
 */
object EinkRegal {
    private const val TAG = "MosaicRegal"
    private const val HOST_CLIENT = "com.ratta.supernote.note.utils.HandWriteClient"
    private const val HWC_CMD = 1020
    private const val HWC_REGAL = 9

    // 这次手势里已经为移动关过 regal。
    private var closedForMove = false
    private var hostClient: Any? = null
    private var hostLookedUp = false
    private var failureLogged = false

    /** 按下、开始移动之前：打开，让从静止到移动的第一帧清掉残影。 */
    fun begin(context: Context, owner: String) {
        closedForMove = false
        send(context, true, "begin:$owner")
    }

    /** 真正开始移动：关掉，一次手势只关一次。 */
    fun moved(context: Context, owner: String) {
        if (closedForMove) return
        closedForMove = true
        send(context, false, "move:$owner")
    }

    /** 松手：重新打开，停下后的刷新清掉移动留下的残影。 */
    fun end(context: Context, owner: String) {
        closedForMove = false
        send(context, true, "end:$owner")
    }

    /** 插件关闭或手势被整体打断：如果移动中关过，恢复打开。 */
    fun restore(context: Context, why: String) {
        if (!closedForMove) return
        closedForMove = false
        send(context, true, "restore:$why")
    }

    private fun send(context: Context, enable: Boolean, tag: String) {
        val via = when {
            viaHost(context, enable, tag) -> "host"
            viaEink(context, enable) -> "eink"
            else -> return
        }
        Log.i(TAG, "regal=${if (enable) "open" else "close"} $tag via=$via")
    }

    private fun viaHost(context: Context, enable: Boolean, tag: String): Boolean {
        val client = hostClient(context) ?: return false
        return try {
            client.javaClass.getMethod("setRegalStatus", Boolean::class.javaPrimitiveType, String::class.java)
                .invoke(client, enable, "mosaic $tag")
            true
        } catch (e: Throwable) {
            logFailure("host setRegalStatus failed: ${e.cause?.message ?: e.message}")
            false
        }
    }

    private fun hostClient(context: Context): Any? {
        if (hostLookedUp) return hostClient
        hostLookedUp = true
        for (loader in listOf(context.applicationContext?.classLoader, context.classLoader)) {
            if (loader == null) continue
            try {
                val type = Class.forName(HOST_CLIENT, true, loader)
                hostClient = type.getMethod("getInstance").invoke(null)
                Log.i(TAG, "using host HandWriteClient")
                return hostClient
            } catch (e: Throwable) {
                Log.i(TAG, "host HandWriteClient unavailable: ${e.javaClass.simpleName}")
            }
        }
        return null
    }

    private fun viaEink(context: Context, enable: Boolean): Boolean {
        return try {
            val eink = context.getSystemService("eink") ?: run {
                logFailure("no eink service")
                return false
            }
            eink.javaClass.getMethod("sendHwcCmd", Int::class.javaPrimitiveType, IntArray::class.java)
                .invoke(eink, HWC_CMD, intArrayOf(HWC_REGAL, if (enable) 1 else 0))
            true
        } catch (e: Throwable) {
            logFailure("sendHwcCmd failed: ${e.cause?.message ?: e.message}")
            false
        }
    }

    private fun logFailure(message: String) {
        if (failureLogged) return
        failureLogged = true
        Log.w(TAG, message)
    }
}
