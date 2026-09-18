package me.laumss.mosaic

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import android.util.Log


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

    
    const val PEN_TYPE_TECHNICAL = 10
    
    const val PEN_COLOR_BLACK = 0
    const val PEN_COLOR_WHITE = 0xfe

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
