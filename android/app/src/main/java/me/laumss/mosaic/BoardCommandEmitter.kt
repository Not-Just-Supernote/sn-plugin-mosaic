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
        
        
        
        if (!reactContext.hasActiveReactInstance()) {
            Log.w(TAG, "emit skipped: react instance inactive ops=${ops.size()}")
            return
        }
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
                
                
                
                putInt("pen", rec.penStyle)
                putInt("drawPathWidth", rec.drawPathWidth)
                putDouble("sampleScale", rec.sampleScale.toDouble())
                putString("points", packPoints(rec.points, rec.pressures))
                
                
                
                putMap("bounds", Arguments.createMap().apply {
                    putDouble("left", rec.bounds.left.toDouble())
                    putDouble("top", rec.bounds.top.toDouble())
                    putDouble("right", rec.bounds.right.toDouble())
                    putDouble("bottom", rec.bounds.bottom.toDouble())
                })
            },
        )
    }

    
    fun strokeUpsertBatch(records: Collection<BoardEngine.StrokeRec>) {
        if (records.isEmpty()) return
        val startedAt = System.nanoTime()
        val values = records.toList()
        var totalPoints = 0
        for (rec in values) totalPoints += rec.points.size / 2
        val pointBuffer = ByteBuffer.allocate(totalPoints * 12).order(ByteOrder.LITTLE_ENDIAN)
        val ids = Arguments.createArray()
        val spaces = Arguments.createArray()
        val widths = Arguments.createArray()
        val colors = Arguments.createArray()
        val pens = Arguments.createArray()
        val drawPathWidths = Arguments.createArray()
        val sampleScales = Arguments.createArray()
        val bounds = Arguments.createArray()
        val pointOffsets = Arguments.createArray()
        val pointCounts = Arguments.createArray()
        var byteOffset = 0
        for (rec in values) {
            ids.pushString(rec.id)
            spaces.pushString(rec.space)
            widths.pushDouble(rec.width.toDouble())
            colors.pushDouble((rec.color.toLong() and 0xffffffffL).toDouble())
            pens.pushInt(rec.penStyle)
            drawPathWidths.pushInt(rec.drawPathWidth)
            sampleScales.pushDouble(rec.sampleScale.toDouble())
            bounds.pushDouble(rec.bounds.left.toDouble())
            bounds.pushDouble(rec.bounds.top.toDouble())
            bounds.pushDouble(rec.bounds.right.toDouble())
            bounds.pushDouble(rec.bounds.bottom.toDouble())
            pointOffsets.pushInt(byteOffset)
            pointCounts.pushInt(rec.points.size / 2)
            var point = 0
            while (point * 2 + 1 < rec.points.size) {
                pointBuffer.putFloat(rec.points[point * 2])
                pointBuffer.putFloat(rec.points[point * 2 + 1])
                pointBuffer.putFloat(rec.pressures.getOrNull(point) ?: 1f)
                point++
                byteOffset += 12
            }
        }
        val payload = Base64.encodeToString(pointBuffer.array(), Base64.NO_WRAP)
        op(
            Arguments.createMap().apply {
                putString("type", "strokeBatch")
                putArray("ids", ids)
                putArray("spaces", spaces)
                putArray("widths", widths)
                putArray("colors", colors)
                putArray("pens", pens)
                putArray("drawPathWidths", drawPathWidths)
                putArray("sampleScales", sampleScales)
                putArray("bounds", bounds)
                putArray("pointOffsets", pointOffsets)
                putArray("pointCounts", pointCounts)
                putString("points", payload)
            },
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
        Log.i(TAG, "[MosaicBridgePerf] strokeBatch count=${values.size} points=$totalPoints " +
            "payloadBytes=${pointBuffer.array().size} encodeMs=${"%.2f".format(elapsedMs)}")
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
                
                
                putString("content", rec.content)
                putString("noteRef", rec.noteRef)
                putString("bgColor", rec.bgColor)
                putString("textColor", rec.textColor)
                putString("title", rec.title)
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
                putBoolean("locked", rec.locked)
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

    

    
    var viewMetricsDp: (() -> FloatArray)? = null

    fun viewport(panX: Float, panY: Float, scale: Float) {
        val metrics = viewMetricsDp?.invoke()
        op(
            Arguments.createMap().apply {
                putString("type", "viewport")
                putDouble("panX", panX.toDouble())
                putDouble("panY", panY.toDouble())
                putDouble("scale", scale.toDouble())
                if (metrics != null && metrics.size >= 3) {
                    putDouble("viewW", metrics[0].toDouble())
                    putDouble("viewH", metrics[1].toDouble())
                    putDouble("topInset", metrics[2].toDouble())
                }
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
