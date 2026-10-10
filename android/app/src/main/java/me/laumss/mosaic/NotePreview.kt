package me.laumss.mosaic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AtomicFile
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.ceil


object NotePreview {

    fun write(file: File, doc: ScrollingDocument) {
        val width = ceil(doc.contentWidth.toDouble()).toInt(); val height = ceil(doc.contentHeight.toDouble()).toInt()
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try {
            val out = DataOutputStream(BufferedOutputStream(stream, 64 * 1024))
            out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
            fun chunk(type: String, bytes: ByteArray) {
                val tag = type.toByteArray(Charsets.US_ASCII)
                out.writeInt(bytes.size); out.write(tag); out.write(bytes)
                val crc = CRC32(); crc.update(tag); crc.update(bytes); out.writeInt(crc.value.toInt())
            }
            
            val header = ByteArrayOutputStream().also { b -> DataOutputStream(b).apply { writeInt(width); writeInt(height); write(byteArrayOf(8,4,0,0,0)) } }.toByteArray()
            chunk("IHDR", header)
            val idat = object : OutputStream() {
                private val buffer = ByteArrayOutputStream(65536)
                private fun drain() { if (buffer.size() > 0) { chunk("IDAT", buffer.toByteArray()); buffer.reset() } }
                override fun write(value: Int) { buffer.write(value); if (buffer.size() >= 65536) drain() }
                override fun write(b: ByteArray, off: Int, len: Int) { buffer.write(b, off, len); if (buffer.size() >= 65536) drain() }
                override fun close() = drain()
            }
            val bitmap = Bitmap.createBitmap(width, 256, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            val pixels = IntArray(width); val row = ByteArray(1 + width * 2)
            val deflater = Deflater(6)
            try {
                DeflaterOutputStream(idat, deflater).use { compressed ->
                    var top = 0
                    while (top < height) {
                        val count = minOf(256, height-top); val canvas = Canvas(bitmap)
                        bitmap.eraseColor(0)
                        canvas.translate(0f, -top.toFloat())
                        val visible = doc.strokes.filter { s ->
                            s.bounds.bottom + s.width >= top && s.bounds.top - s.width < top + count
                        }
                        TchRaster.drawLayer(canvas, visible, paint)
                        for (y in 0 until count) {
                            bitmap.getPixels(pixels,0,width,0,y,width,1)
                            row[0]=0
                            var i = 1
                            for (x in 0 until width) {
                                val p = pixels[x]
                                val a = (p ushr 24) and 255
                                val r = (p ushr 16) and 255
                                val g = (p ushr 8) and 255
                                val b = p and 255
                                row[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
                                row[i + 1] = a.toByte()
                                i += 2
                            }
                            compressed.write(row)
                        }
                        top += count
                    }
                }
            } finally { bitmap.recycle(); deflater.end() }
            chunk("IEND", byteArrayOf()); out.flush(); atomic.finishWrite(stream)
        } catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
}
