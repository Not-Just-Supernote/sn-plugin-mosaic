package me.laumss.mosaic.eink

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.util.Log
import android.view.Surface
import android.view.View

internal object EinkRegionDriver {
    private val library = runCatching { System.loadLibrary("mosaic_eink_region") }
    private external fun beginBlackBatchNative(rects: IntArray): Long
    private external fun finishBlackBatchNative(token: Long): String
    private external fun batchBusyNative(token: Long): Int
    private external fun completeBatchNative(token: Long): String
    private external fun batchResultNative(): String
    private external fun displayBusyNative(): Int

    /** Read-only panel state: 0 idle, 1 busy, -1 unsupported/unavailable. */
    fun displayBusy(): Int = try {
        library.getOrThrow()
        displayBusyNative()
    } catch (_: Throwable) { -1 }

    var waitingForPreviousFrame = false
        private set

    fun beginBlackBatch(view: View, localRegion: Region, owner: String): Long {
        waitingForPreviousFrame = false
        if (!view.isAttachedToWindow || !view.isShown || view.display?.rotation != Surface.ROTATION_0) return 0L
        return try {
            library.getOrThrow()
            val origin = IntArray(2); view.getLocationOnScreen(origin)
            val clipped = Region(localRegion).apply { op(Rect(0, 0, view.width, view.height), Region.Op.INTERSECT) }
            clipped.translate(origin[0], origin[1])
            clipped.op(Rect(0, 0, 1920, 2560), Region.Op.INTERSECT)
            val values = ArrayList<Int>()
            val iterator = RegionIterator(clipped)
            val rect = Rect()
            while (iterator.next(rect)) {
                values.add(rect.left); values.add(rect.top); values.add(rect.right); values.add(rect.bottom)
                if (values.size > 16384) {
                    Log.i("MosaicEinkCleanup", "skip owner=$owner fragmented region")
                    return 0L
                }
            }
            val token = beginBlackBatchNative(values.toIntArray())
            val result = batchResultNative()
            waitingForPreviousFrame = result.startsWith("wait:")
            Log.i("MosaicEinkCleanup", "owner=$owner $result")
            token
        } catch (error: Throwable) {
            Log.w("MosaicEinkCleanup", "begin owner=$owner", error)
            0L
        }
    }

    fun finishBlackBatch(token: Long, owner: String): String = try {
        library.getOrThrow()
        finishBlackBatchNative(token).also { Log.i("MosaicEinkCleanup", "owner=$owner $it") }
    } catch (error: Throwable) {
        Log.w("MosaicEinkCleanup", "finish owner=$owner", error)
        "error: ${error.message}"
    }

    fun batchBusy(token: Long): Int = try { batchBusyNative(token) } catch (error: Throwable) {
        Log.w("MosaicEinkCleanup", "poll token=$token", error)
        -1
    }

    fun completeBatch(token: Long, owner: String): String = try {
        completeBatchNative(token).also { Log.i("MosaicEinkCleanup", "owner=$owner $it") }
    } catch (error: Throwable) {
        Log.w("MosaicEinkCleanup", "complete owner=$owner", error)
        "error: ${error.message}"
    }
}
