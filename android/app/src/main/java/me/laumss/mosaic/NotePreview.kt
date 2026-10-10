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


// The thumbnail is a column of PNG tiles: notes/<ref>/<tile>.png, each 480x640 note units.
object NotePreview {
    private const val PX_PER_UNIT = 3f
    private val TILE_W = (ScrollingDocument.WIDTH * PX_PER_UNIT).toInt()
    private val TILE_H = (ScrollingDocument.TILE_HEIGHT * PX_PER_UNIT).toInt()
    private val TILE_FILE = Regex("(\\d+)\\.png")

    fun tilePath(dir: String, tile: Int): String = "$dir/$tile.png"

    // Writes the tiles in [dirty] plus any tile file that is missing; null rewrites every tile.
    // Tile files past the end of the note are removed. Returns the tiles written.
    fun write(dir: File, doc: ScrollingDocument, dirty: Set<Int>?): List<Int> {
        require(dir.isDirectory || dir.mkdirs())
        val count = doc.tileCount
        dir.listFiles()?.forEach { f ->
            val tile = TILE_FILE.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
            if (tile != null && tile >= count) { f.delete(); CardImageCache.invalidate(f.absolutePath) }
        }
        val tiles = (0 until count).filter { dirty == null || it in dirty || !File(dir, "$it.png").isFile }
        if (tiles.isEmpty()) return tiles
        val bitmap = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        try {
            for (tile in tiles) {
                val top = tile * ScrollingDocument.TILE_HEIGHT
                val bottom = top + ScrollingDocument.TILE_HEIGHT
                val visible = doc.strokes.filter { s ->
                    val pad = ScrollingDocument.inkPad(s)
                    s.bounds.bottom + pad >= top && s.bounds.top - pad < bottom
                }
                bitmap.eraseColor(0)
                val canvas = Canvas(bitmap)
                canvas.scale(PX_PER_UNIT, PX_PER_UNIT)
                canvas.translate(0f, -top)
                TchRaster.drawLayer(canvas, visible, paint)
                val file = File(dir, "$tile.png")
                writePng(file, bitmap)
                CardImageCache.invalidate(file.absolutePath)
            }
        } finally { bitmap.recycle() }
        return tiles
    }

    private fun writePng(file: File, bitmap: Bitmap) {
        val width = bitmap.width; val height = bitmap.height
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
            val pixels = IntArray(width); val row = ByteArray(1 + width * 2)
            val deflater = Deflater(6)
            try {
                DeflaterOutputStream(idat, deflater).use { compressed ->
                    for (y in 0 until height) {
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
                }
            } finally { deflater.end() }
            chunk("IEND", byteArrayOf()); out.flush(); atomic.finishWrite(stream)
        } catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
}
