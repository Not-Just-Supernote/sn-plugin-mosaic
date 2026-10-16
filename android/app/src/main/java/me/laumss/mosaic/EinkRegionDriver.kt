package me.laumss.mosaic

import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.util.Log
import android.view.Surface
import android.view.View

internal object EinkRegionDriver {
    var lastResult: String = "waiting"
        private set
    private val library = runCatching { System.loadLibrary("mosaic_eink_region") }
    private external fun probeNative(rects: IntArray): String
    private external fun testWhiteNative(rect: IntArray): String
    private external fun testBlackWhiteNative(rect: IntArray): String
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

    fun testBlackWhite(view: View, physicalRect: Rect): String {
        if (!view.isAttachedToWindow || view.display?.rotation != Surface.ROTATION_0) return "skip: view or rotation"
        return try {
            library.getOrThrow()
            // The bounded pair runs within this explicit manual action so a new
            // scene/input command cannot intervene between black and white.
            val result = testBlackWhiteNative(intArrayOf(physicalRect.left, physicalRect.top, physicalRect.right, physicalRect.bottom))
            Log.i("MosaicEinkLab", "black-white test rect=$physicalRect result=$result")
            result
        } catch (error: Throwable) {
            Log.w("MosaicEinkLab", "black-white test unavailable", error)
            "error: ${error.message}"
        }
    }

    fun testWhite(view: View, physicalRect: Rect): String {
        if (!view.isAttachedToWindow || view.display?.rotation != Surface.ROTATION_0) return "skip: view or rotation"
        return try {
            library.getOrThrow()
            val result = testWhiteNative(intArrayOf(physicalRect.left, physicalRect.top, physicalRect.right, physicalRect.bottom))
            Log.i("MosaicEinkLab", "white-tag test rect=$physicalRect result=$result")
            result
        } catch (error: Throwable) {
            Log.w("MosaicEinkLab", "white-tag test unavailable", error)
            "error: ${error.message}"
        }
    }

    fun probe(view: View, exposed: Region): Boolean {
        if (!view.isAttachedToWindow || view.display?.rotation != Surface.ROTATION_0 || exposed.isEmpty) {
            lastResult = "skip: view, orientation or region guard"
            Log.w("MosaicEinkLab", "driver-probe skipped: view, orientation or region guard")
            return false
        }
        return try {
            library.getOrThrow()
            val origin = IntArray(2)
            view.getLocationOnScreen(origin)
            val iterator = RegionIterator(exposed)
            val rect = Rect()
            val values = ArrayList<Int>()
            while (iterator.next(rect)) {
                rect.offset(origin[0], origin[1])
                values.add(rect.left); values.add(rect.top); values.add(rect.right); values.add(rect.bottom)
            }
            val result = probeNative(values.toIntArray())
            lastResult = result
            Log.i("MosaicEinkLab", "driver-probe origin=${origin.toList()} rects=$values result=$result")
            false // Read-only probe; the hardware cleanup experiment is withdrawn.
        } catch (error: Throwable) {
            lastResult = "error: ${error.message}"
            Log.w("MosaicEinkLab", "driver-probe unavailable", error)
            false
        }
    }
}
