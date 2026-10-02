package me.laumss.mosaic

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import android.os.Build
import android.util.Log
import kotlin.math.roundToInt


object DrawPathClient {
    private const val TAG = "MosaicDrawPath"

    
    private val SERVICE_NAME_CANDIDATES = arrayOf("service_myservice", "service.myservice")

    
    private const val INTERFACE_TOKEN = "android.demo.IMyService"
    private const val WRITE_INFO_TRANSACTION = 0
    private const val DISABLE_AREA_TRANSACTION = 1
    private const val PEN_INFO_TRANSACTION = 2
    
    private const val SHIFT_INFO_TRANSACTION = 3
    
    private const val SYNC_BACKGROUND_TRANSACTION = 6
    
    private const val ADDRESS_MODE_TRANSACTION = 10
    const val PRIME_SIZE = 18888
    const val DISABLE_SIZE = 19999

    
    val MOSAIC_APP_NAME: String = BuildConfig.APPLICATION_ID

    
    const val PEN_TYPE_NEEDLE = 10
    const val PEN_TYPE_MARKER = 11
    const val PEN_TYPE_BRUSH = 15
    const val PEN_TYPE_PRESSURE = 16

    
    private data class PaletteTable(val needle: IntArray, val marker: Int)

    private val paletteTable = when {
        Build.BOARD == "A5X2" -> PaletteTable(
            needle = intArrayOf(200, 300, 400, 500, 600, 700, 900, 1000, 1100, 1200, 1800, 2400),
            marker = 3800,
        )
        Build.MODEL == "Supernote A5 X" -> PaletteTable(
            needle = intArrayOf(100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1500, 2000),
            marker = 3000,
        )
        Build.MODEL == "Supernote A6 X" || Build.MODEL == "Supernote Nomad" -> PaletteTable(
            needle = intArrayOf(200, 300, 400, 500, 700, 800, 1000, 1100, 1200, 1300, 1800, 2400),
            marker = 3800,
        )
        else -> PaletteTable(
            needle = intArrayOf(200, 300, 400, 500, 600, 700, 900, 1000, 1100, 1200, 1800, 2400),
            marker = 3800,
        )
    }

    
    private fun paletteSteps(style: PenStyle): IntArray = when (style) {
        PenStyle.MARKER -> intArrayOf(paletteTable.marker)
        PenStyle.NEEDLE -> paletteTable.needle
        PenStyle.BRUSH -> paletteTable.needle
        else -> paletteTable.needle.copyOfRange(2, paletteTable.needle.size)
    }

    private fun nearestPaletteWidth(style: PenStyle, raw: Int): Int {
        val steps = paletteSteps(style)
        return steps.minByOrNull { kotlin.math.abs(it - raw) } ?: steps[0]
    }

    
    private const val NOTE_CALLIGRAPHY_START = 4

    
    fun noteCalligraphyThickness(drawPathWidth: Int): Int {
        val steps = paletteTable.needle.copyOfRange(NOTE_CALLIGRAPHY_START, paletteTable.needle.size)
        return steps.minByOrNull { kotlin.math.abs(it - drawPathWidth) } ?: steps[0]
    }

    
    fun widthArgument(style: PenStyle, stdWidth: Float): Int =
        nearestPaletteWidth(style, (stdWidth.coerceIn(1f, 40f) * 100f).roundToInt())

    fun widthArgumentFromStdHundredths(stdWidth: Int, style: PenStyle = PenStyle.PEN): Int =
        nearestPaletteWidth(style, stdWidth.coerceIn(50, 4000))

    
    fun liveWidthArgumentFromStdHundredths(stdWidth: Int, style: PenStyle = PenStyle.PEN): Int {
        val rawWidth = widthArgumentFromStdHundredths(stdWidth, style)
        return when (style) {
            PenStyle.PEN -> rawWidth * LIVE_PEN_WIDTH_SCALE
            PenStyle.BRUSH -> (rawWidth * BRUSH_LIVE_WIDTH_SCALE).roundToInt()
            else -> rawWidth
        }
    }

    
    private const val ABS_PRESSURE_MAX_A5X2 = 4095
    private const val LAMY_BAD_PRESSURE = 6991
    private const val LAMY_LINE_TYPE_PEN = 0
    private const val LAMY_PEN_OFFSET = 45
    private const val LAMY_PEN_FACTOR_0 = -615
    private const val LAMY_PEN_FACTOR_1 = 183
    private const val LAMY_SCALE_TO_PIXELS_PEN = 0.004f
    private const val MARKER_CANVAS_CALIBRATION = 3.4f
    
    
    
    private const val BRUSH_NOTE_ALIGN = 300f / 500f
    private const val BRUSH_LIVE_WIDTH_SCALE = 1.7f * BRUSH_NOTE_ALIGN
    private const val BRUSH_CANVAS_WIDTH_SCALE = 6f * BRUSH_NOTE_ALIGN
    private const val LAMY_MIN_X1000 = 1500
    private const val DRAW_PATH_MIN_WIDTH = 4
    private const val LIVE_PEN_WIDTH_SCALE = 4

    private fun lamyX1000Width(lineType: Int, pressureRaw: Int, baseWidth: Float): Int {
        
        
        require(lineType == LAMY_LINE_TYPE_PEN) { "Unsupported Lamy line type: $lineType" }
        val pressure = pressureRaw.coerceIn(0, ABS_PRESSURE_MAX_A5X2)
        if (pressure >= LAMY_BAD_PRESSURE) return LAMY_MIN_X1000

        val pressureIndex = (pressure / 10).coerceIn(0, 699)
        val belowOffset = pressureIndex < LAMY_PEN_OFFSET
        val tableIndex = if (belowOffset) LAMY_PEN_OFFSET else pressureIndex
        
        val logIndex = (1000.0 * kotlin.math.ln((tableIndex + 1).toDouble())).roundToInt()
        
        
        val factorTerm = (logIndex * LAMY_PEN_FACTOR_1 + LAMY_PEN_FACTOR_0 * 1000) / 100
        val value = baseWidth * 296.20255f * factorTerm.toFloat() / 1000f -
            if (belowOffset) 1f else 0f
        val rounded = value.roundToInt()
        return rounded.coerceIn(LAMY_MIN_X1000, (baseWidth * 1000f).toInt())
    }

    private fun drawPathPressureWidth(
        drawPathWidth: Int,
        pressure01: Float,
    ): Float {
        val pressureRaw = (pressure01 * ABS_PRESSURE_MAX_A5X2).roundToInt()
            .coerceIn(0, ABS_PRESSURE_MAX_A5X2)
        
        val baseWidth = (drawPathWidth * 0.01f).roundToInt().coerceAtLeast(1).toFloat()
        val lamy = lamyX1000Width(LAMY_LINE_TYPE_PEN, pressureRaw, baseWidth)
        return (lamy * LAMY_SCALE_TO_PIXELS_PEN).roundToInt()
            .coerceAtLeast(DRAW_PATH_MIN_WIDTH).toFloat()
    }

    fun nativePressureWidthUnits(drawPathType: Int, drawPathWidth: Int, pressure01: Float): Float {
        require(drawPathWidth > 0) { "drawPath width is unavailable" }
        require(pressure01.isFinite() && pressure01 in 0f..1f) {
            "drawPath pressure sample is invalid: $pressure01"
        }
        if (drawPathType == PEN_TYPE_MARKER) {
            
            return drawPathWidth * 0.254f / 100f * MARKER_CANVAS_CALIBRATION
        }
        if (drawPathType == PEN_TYPE_BRUSH) {
            
            return drawPathWidth * BRUSH_CANVAS_WIDTH_SCALE * 0.254f / 100f
        }
        if (drawPathType == PEN_TYPE_PRESSURE) {
            return drawPathPressureWidth(drawPathWidth, pressure01)
        }
        
        return drawPathWidth * 0.254f / 100f
    }
    
    const val PEN_COLOR_BLACK = 0
    const val PEN_COLOR_WHITE = 0xfe
    const val PEN_COLOR_LIGHT_GRAY = -0x66
    const val PEN_COLOR_DARK_GRAY = -0x65

    data class DisableArea(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    @SuppressLint("PrivateApi")
    fun getBinder(): IBinder? {
        for (name in SERVICE_NAME_CANDIDATES) {
            val binder = try {
                val serviceManager = Class.forName("android.os.ServiceManager")
                val getService = serviceManager.getMethod("getService", String::class.java)
                getService.invoke(null, name) as? IBinder
            } catch (error: Throwable) {
                
                
                Log.w(TAG, "getBinder reflection failed for $name: ${error.message}")
                continue
            }
            if (binder != null) return binder
        }
        return null
    }

    fun disableAll(binder: IBinder, appName: String) {
        sendDisableAreas(binder, appName, listOf(DisableArea(0, 0, PRIME_SIZE, PRIME_SIZE)))
        
        
        sendDisableAreas(binder, appName, listOf(DisableArea(0, 0, DISABLE_SIZE, DISABLE_SIZE)))
        
        
        sendDisableAreas(binder, appName, emptyList())
    }

    
    
    
    fun configure(
        binder: IBinder,
        appName: String,
        penType: Int,
        penWidth: Int,
        penColor: Int,
        areas: List<DisableArea>,
    ) {
        sendDisableAreas(binder, appName, listOf(DisableArea(0, 0, PRIME_SIZE, PRIME_SIZE)))
        
        
        sendDisableAreas(binder, appName, emptyList())
        sendPenInfo(binder, appName, penType, penWidth, penColor)
        sendIdentityAddressMode(binder, appName)
        if (areas.isNotEmpty()) sendDisableAreas(binder, appName, areas)
    }

    
    fun sendStylusCalibration(binder: IBinder, appName: String, diffX: Int, diffY: Int, leftHand: Boolean) {
        val labeled = appName + if (leftHand) "_LeftHand" else "_RightHand"
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeString(labeled)
            data.writeInt(diffX)
            data.writeInt(diffY)
            val accepted = binder.transact(SHIFT_INFO_TRANSACTION, data, reply, 0)
            val response = readReply(reply, "sendStylusCalibration")
            Log.i(TAG, "sendStylusCalibration app=$labeled diff=($diffX,$diffY) transact=$accepted reply=$response")
        } catch (error: Throwable) {
            Log.w(TAG, "sendStylusCalibration failed app=$labeled", error)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    
    fun sendIdentityAddressMode(binder: IBinder, appName: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeString(appName)
            data.writeInt(0) 
            data.writeInt(0) 
            data.writeInt(0) 
            data.writeInt(0) 
            data.writeInt(0) 
            data.writeInt(0) 
            data.writeInt(0) 
            data.writeFloat(1.0f) 
            val accepted = binder.transact(ADDRESS_MODE_TRANSACTION, data, reply, 0)
            val response = readReply(reply, "sendAddressMode")
            Log.i(TAG, "sendAddressMode app=$appName identity transact=$accepted reply=$response")
        } catch (error: Throwable) {
            Log.w(TAG, "sendAddressMode failed app=$appName", error)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    
    fun release(binder: IBinder, appName: String) {
        sendDisableAreas(binder, appName, listOf(DisableArea(0, 0, PRIME_SIZE, PRIME_SIZE)))
    }

    
    fun sendWriteInfo(binder: IBinder, appName: String, pageNum: Int, layer: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeString(appName)
            data.writeInt(pageNum)
            data.writeInt(layer)
            val accepted = binder.transact(WRITE_INFO_TRANSACTION, data, reply, 0)
            val response = readReply(reply, "sendWriteInfo")
            Log.i(
                TAG,
                "sendWriteInfo app=$appName page=$pageNum layer=$layer " +
                    "transact=$accepted reply=$response",
            )
            if (!accepted) throw IllegalStateException("drawPath rejected write info")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    
    fun syncBackground(binder: IBinder, appName: String, isA6X2: Boolean) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeString(appName)
            data.writeInt(255)
            data.writeInt(if (isA6X2) 1 else 0)
            val accepted = binder.transact(SYNC_BACKGROUND_TRANSACTION, data, reply, 0)
            val response = readReply(reply, "syncBackground")
            if (!accepted) throw IllegalStateException("drawPath rejected background sync")
            Log.i(TAG, "syncBackground app=$appName a6x2=$isA6X2 reply=$response")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun sendDisableAreas(binder: IBinder, appName: String, areas: List<DisableArea>) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeString(appName)
            data.writeInt(areas.size)
            for (area in areas) {
                data.writeInt(area.left)
                data.writeInt(area.top)
                data.writeInt(area.width)
                data.writeInt(area.height)
                data.writeInt(0)
            }
            val accepted = binder.transact(DISABLE_AREA_TRANSACTION, data, reply, 0)
            val response = readReply(reply, "sendDisableAreas")
            if (!accepted) {
                throw IllegalStateException("drawPath rejected disable areas")
            }
            Log.i(
                TAG,
                "sendDisableAreas app=$appName count=${areas.size} areas=$areas " +
                    "reply=$response",
            )
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun sendPenInfo(
        binder: IBinder,
        appName: String,
        type: Int,
        width: Int,
        color: Int,
    ) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeString(appName)
            data.writeInt(type)
            data.writeInt(width)
            data.writeInt(color)
            val accepted = binder.transact(PEN_INFO_TRANSACTION, data, reply, 0)
            val response = readReply(reply, "sendPenInfo")
            if (!accepted) {
                throw IllegalStateException("drawPath rejected pen info")
            }
            Log.i(
                TAG,
                "sendPenInfo app=$appName type=$type width=$width color=$color " +
                    "reply=$response",
            )
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun readReply(reply: Parcel, operation: String): String? {
        return try {
            
            
            reply.setDataPosition(0)
            reply.readString().also { value ->
                Log.d(TAG, "$operation reply-read=$value")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "$operation reply-read failed: ${error.message}")
            null
        }
    }
}
