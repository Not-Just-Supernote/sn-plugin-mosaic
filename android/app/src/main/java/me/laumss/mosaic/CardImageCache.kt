package me.laumss.mosaic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import kotlin.math.max


object CardImageCache {
    private const val TAG = "MosaicCardImage"
    private const val BUDGET_BYTES = 48L * 1024 * 1024
    
    private const val FAILURE_RETRY_MS = 5000L

    private class Entry(val bitmap: Bitmap, val sampleSize: Int)

    
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {}
    private val failedAt = HashMap<String, Long>()
    private var bytes = 0L

    
    fun get(path: String, targetLongPx: Int, alpha: Boolean = false): Bitmap? = synchronized(this) {
        if (path.isEmpty()) return null
        val existing = cache[path]
        if (existing != null) {
            val sharpEnough = existing.sampleSize == 1
                || max(existing.bitmap.width, existing.bitmap.height) >= targetLongPx
            val alphaOk = !alpha || existing.bitmap.hasAlpha()
            if (sharpEnough && alphaOk) return existing.bitmap
            cache.remove(path)
            bytes -= existing.bitmap.allocationByteCount
        }
        val now = System.currentTimeMillis()
        val lastFailure = failedAt[path]
        if (lastFailure != null && now - lastFailure < FAILURE_RETRY_MS) return null
        val decoded = decode(path, targetLongPx, alpha)
        if (decoded == null) {
            failedAt[path] = now
            return null
        }
        failedAt.remove(path)
        cache[path] = decoded
        bytes += decoded.bitmap.allocationByteCount
        evictLocked()
        decoded.bitmap
    }

    fun clear() = synchronized(this) {
        cache.clear()
        failedAt.clear()
        bytes = 0L
    }

    // A note card's path is its tile directory; invalidating it drops every tile under it.
    fun invalidate(path: String) = synchronized(this) {
        val prefix = "$path/"
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key == path || entry.key.startsWith(prefix)) {
                bytes -= entry.value.bitmap.allocationByteCount
                iterator.remove()
            }
        }
        failedAt.keys.removeAll { it == path || it.startsWith(prefix) }
    }

    private fun evictLocked() {
        val iterator = cache.entries.iterator()
        while (bytes > BUDGET_BYTES && cache.size > 1 && iterator.hasNext()) {
            val entry = iterator.next()
            bytes -= entry.value.bitmap.allocationByteCount
            iterator.remove()
        }
    }

    private fun decode(path: String, targetLongPx: Int, alpha: Boolean): Entry? {
        val file = File(path)
        if (!file.isFile) {
            Log.w(TAG, "missing image file: $path")
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "undecodable image: $path")
            return null
        }
        val longSide = max(bounds.outWidth, bounds.outHeight)
        val target = max(targetLongPx, 1)
        var sample = 1
        while (longSide / (sample * 2) >= target) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (alpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        }
        val bitmap = try {
            BitmapFactory.decodeFile(path, options)
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "decode OOM, dropping cache: $path", error)
            cache.clear()
            bytes = 0L
            null
        } ?: return null
        Log.i(TAG, "decoded ${file.name} ${bitmap.width}x${bitmap.height} sample=$sample target=$target cacheKb=${(bytes + bitmap.allocationByteCount) / 1024}")
        return Entry(bitmap, sample)
    }
}
