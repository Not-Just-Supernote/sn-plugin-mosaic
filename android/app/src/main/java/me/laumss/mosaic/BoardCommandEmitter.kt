package me.laumss.mosaic

import android.util.Base64
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.nio.ByteBuffer
import java.nio.ByteOrder


class BoardCommandEmitter(private val reactContext: ReactContext) {

    companion object {
        private const val TAG = "MosaicBoardCommand"
        const val EVENT_NAME = "MosaicBoardCommand"
    }

    private var batch: WritableArray? = null
    private var depth = 0

    fun begin() {
        if (depth == 0) batch = Arguments.createArray()
        depth++
    }

    fun commit() {
        if (depth == 0) return
        depth--
        if (depth > 0) return
        val ops = batch ?: return
        batch = null
        if (ops.size() == 0) return
        send(ops)
    }

    
    private fun op(map: WritableMap) {
        val current = batch
        if (current != null) {
            current.pushMap(map)
        } else {
            send(Arguments.createArray().apply { pushMap(map) })
        }
    }

    private fun send(ops: WritableArray) {
        try {
            val payload = Arguments.createMap().apply { putArray("ops", ops) }
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(EVENT_NAME, payload)
        } catch (error: Throwable) {
            Log.w(TAG, "emit failed", error)
        }
    }

    

    fun strokeUpsert(rec: BoardEngine.StrokeRec) {
        op(
            Arguments.createMap().apply {
                putString("type", "strokeUpsert")
                putString("id", rec.id)
                putString("space", rec.space)
                putDouble("width", rec.width.toDouble())
                putDouble("color", (rec.color.toLong() and 0xffffffffL).toDouble())
                putString("points", packPoints(rec.points, rec.pressures))
            },
        )
    }

    fun strokesRemove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        op(
            Arguments.createMap().apply {
                putString("type", "strokesRemove")
                putArray("ids", stringArray(ids))
            },
        )
    }

    fun action(name: String) {
        op(Arguments.createMap().apply {
            putString("type", "action")
            putString("name", name)
        })
    }

    

    fun cardUpsert(rec: BoardEngine.CardRec) {
        op(
            Arguments.createMap().apply {
                putString("type", "cardUpsert")
                putString("id", rec.id)
                putDouble("x", rec.x.toDouble())
                putDouble("y", rec.y.toDouble())
                putDouble("width", rec.width.toDouble())
                putDouble("height", rec.height.toDouble())
                putInt("zIndex", rec.zIndex)
                putString("kind", rec.kind)
                putString("bgColor", rec.bgColor)
                putString("textColor", rec.textColor)
            },
        )
    }

    fun cardsRemove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        op(
            Arguments.createMap().apply {
                putString("type", "cardsRemove")
                putArray("ids", stringArray(ids))
            },
        )
    }

    fun connectionAdd(rec: BoardEngine.ConnectionRec) {
        op(
            Arguments.createMap().apply {
                putString("type", "connectionAdd")
                putString("id", rec.id)
                putString("from", rec.fromId)
                putString("to", rec.toId)
            },
        )
    }

    fun connectionsRemove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        op(
            Arguments.createMap().apply {
                putString("type", "connectionsRemove")
                putArray("ids", stringArray(ids))
            },
        )
    }

    

    fun whiteboardUpsert(rec: BoardEngine.WhiteboardRec) {
        op(
            Arguments.createMap().apply {
                putString("type", "whiteboardUpsert")
                putString("id", rec.id)
                putString("name", rec.name)
                putDouble("x", rec.x.toDouble())
                putDouble("y", rec.y.toDouble())
                putDouble("width", rec.width.toDouble())
                putDouble("height", rec.height.toDouble())
            },
        )
    }

    fun whiteboardsRemove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        op(
            Arguments.createMap().apply {
                putString("type", "whiteboardsRemove")
                putArray("ids", stringArray(ids))
            },
        )
    }

    fun viewport(panX: Float, panY: Float, scale: Float) {
        op(
            Arguments.createMap().apply {
                putString("type", "viewport")
                putDouble("panX", panX.toDouble())
                putDouble("panY", panY.toDouble())
                putDouble("scale", scale.toDouble())
            },
        )
    }

    
    fun action(name: String, args: (WritableMap.() -> Unit)? = null) {
        op(
            Arguments.createMap().apply {
                putString("type", "action")
                putString("name", name)
                args?.invoke(this)
            },
        )
    }

    

    private fun stringArray(ids: Collection<String>): WritableArray =
        Arguments.createArray().apply { for (id in ids) pushString(id) }

    
    private fun packPoints(points: FloatArray, pressures: FloatArray?): String {
        val count = points.size / 2
        val buf = ByteBuffer.allocate(count * 12).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            buf.putFloat(points[i * 2])
            buf.putFloat(points[i * 2 + 1])
            buf.putFloat(pressures?.getOrNull(i) ?: 1f)
        }
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }
}
