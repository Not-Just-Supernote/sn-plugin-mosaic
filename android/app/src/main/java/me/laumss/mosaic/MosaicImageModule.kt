package me.laumss.mosaic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Point
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import android.view.WindowManager
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt


class MosaicImageModule(
    reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "MosaicImage"
        private const val TEMP_SUFFIX = ".tmp"
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }

    override fun getName(): String = "MosaicImage"

    
    @ReactMethod
    fun importImage(source: String, destPath: String, maxLongSide: Int, promise: Promise) {
        Thread {
            try {
                val startedAt = System.currentTimeMillis()
                val limit = if (maxLongSide > 0) maxLongSide else displayLongSide()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openSource(source).use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw IllegalArgumentException("undecodable image: $source")
                }
                val rotation = readExifRotation(source)
                
                var sample = 1
                val longSide = max(bounds.outWidth, bounds.outHeight)
                while (longSide / (sample * 2) >= limit) sample *= 2
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = openSource(source).use { BitmapFactory.decodeStream(it, null, options) }
                    ?: throw IllegalArgumentException("decode failed: $source")
                val oriented = applyRotation(decoded, rotation)
                val scaled = scaleToFit(oriented, limit)
                val width = scaled.width
                val height = scaled.height
                val gray = toGray(scaled)
                if (scaled !== oriented) scaled.recycle()
                if (oriented !== decoded) oriented.recycle()
                decoded.recycle()

                val png = encodeGrayPng(gray, width, height)
                val dest = File(destPath)
                dest.parentFile?.mkdirs()
                val temp = File(destPath + TEMP_SUFFIX)
                FileOutputStream(temp).use { it.write(png) }
                if (dest.exists()) dest.delete()
                if (!temp.renameTo(dest)) throw IllegalStateException("rename failed: $destPath")

                Log.i(
                    TAG,
                    "import ok ${bounds.outWidth}x${bounds.outHeight} -> ${width}x$height sample=$sample rotation=$rotation " +
                        "bytes=${png.size} ms=${System.currentTimeMillis() - startedAt} -> $destPath",
                )
                val result = Arguments.createMap().apply {
                    putInt("width", width)
                    putInt("height", height)
                    putInt("bytes", png.size)
                }
                promise.resolve(result)
            } catch (error: Throwable) {
                Log.e(TAG, "import failed source=$source", error)
                File(destPath + TEMP_SUFFIX).delete()
                promise.reject("IMAGE_IMPORT_FAILED", error)
            }
        }.start()
    }

    private fun openSource(source: String): InputStream {
        if (source.startsWith("content://") || source.startsWith("file://")) {
            return reactApplicationContext.contentResolver.openInputStream(Uri.parse(source))
                ?: throw IllegalArgumentException("cannot open: $source")
        }
        return FileInputStream(File(source))
    }

    private fun displayLongSide(): Int {
        return try {
            val windowManager = reactApplicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val size = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            max(size.x, size.y).coerceAtLeast(1)
        } catch (error: Throwable) {
            Log.w(TAG, "display size unavailable, using 2560", error)
            2560
        }
    }

    private fun readExifRotation(source: String): Int {
        return try {
            openSource(source).use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (error: Throwable) {
            
            0
        }
    }

    private fun applyRotation(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleToFit(bitmap: Bitmap, limit: Int): Bitmap {
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide <= limit) return bitmap
        val ratio = limit.toFloat() / longSide
        val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    
    private fun toGray(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val gray = ByteArray(width * height)
        val row = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            val base = y * width
            for (x in 0 until width) {
                val pixel = row[x]
                val alpha = pixel ushr 24
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                var luma = (r * 77 + g * 150 + b * 29) shr 8
                
                if (alpha < 255) luma = (luma * alpha + 255 * (255 - alpha)) / 255
                gray[base + x] = luma.toByte()
            }
        }
        return gray
    }

    

    private fun encodeGrayPng(gray: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream(gray.size / 2 + 64)
        out.write(PNG_SIGNATURE)

        val ihdr = ByteArray(13)
        writeIntBE(ihdr, 0, width)
        writeIntBE(ihdr, 4, height)
        ihdr[8] = 8 
        ihdr[9] = 0 
        ihdr[10] = 0 
        ihdr[11] = 0 
        ihdr[12] = 0 
        writeChunk(out, "IHDR", ihdr)

        val deflated = ByteArrayOutputStream(gray.size / 2 + 64)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        DeflaterOutputStream(deflated, deflater, 64 * 1024).use { stream ->
            val previous = ByteArray(width)
            val current = ByteArray(width)
            val filtered = ByteArray(width)
            val best = ByteArray(width)
            for (y in 0 until height) {
                System.arraycopy(gray, y * width, current, 0, width)
                val filterType = chooseFilter(current, if (y == 0) null else previous, filtered, best)
                stream.write(filterType)
                stream.write(best, 0, width)
                System.arraycopy(current, 0, previous, 0, width)
            }
        }
        deflater.end()
        writeChunk(out, "IDAT", deflated.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    
    private fun chooseFilter(row: ByteArray, prior: ByteArray?, scratch: ByteArray, best: ByteArray): Int {
        val width = row.size
        var bestType = 0
        var bestScore = Long.MAX_VALUE
        for (type in 0..4) {
            if (prior == null && (type == 2 || type == 3 || type == 4)) continue
            var score = 0L
            for (x in 0 until width) {
                val raw = row[x].toInt() and 0xff
                val left = if (x > 0) row[x - 1].toInt() and 0xff else 0
                val up = if (prior != null) prior[x].toInt() and 0xff else 0
                val upLeft = if (prior != null && x > 0) prior[x - 1].toInt() and 0xff else 0
                val predicted = when (type) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    else -> paeth(left, up, upLeft)
                }
                val value = (raw - predicted) and 0xff
                scratch[x] = value.toByte()
                score += abs(value.toByte().toInt())
                if (score >= bestScore) break
            }
            if (score < bestScore) {
                bestScore = score
                bestType = type
                System.arraycopy(scratch, 0, best, 0, width)
            }
        }
        return bestType
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val length = ByteArray(4)
        writeIntBE(length, 0, data.size)
        out.write(length)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val crcBytes = ByteArray(4)
        writeIntBE(crcBytes, 0, crc.value.toInt())
        out.write(crcBytes)
    }

    private fun writeIntBE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
