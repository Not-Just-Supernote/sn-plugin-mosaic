package me.laumss.mosaic.note

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.render.*
import android.util.AtomicFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

object TchFile {
    private val MAGIC = byteArrayOf(77, 79, 83, 78, 79, 84, 69, 3)
    private const val IO_BUFFER = 64 * 1024
    fun read(file: File, ref: String): ScrollingDocument = DataInputStream(BufferedInputStream(AtomicFile(file).openRead(), IO_BUFFER)).use { input ->
        val header = ByteArray(MAGIC.size); input.readFully(header)
        val version = header.last().toInt()
        require(header.take(7) == MAGIC.take(7) && version in 2..3) { "Unsupported note format" }
        val scroll = input.readFloat(); require(scroll.isFinite() && scroll >= 0f)
        val count = input.readInt(); require(count >= 0)
        val strokes = ArrayList<BoardEngine.StrokeRec>()
        repeat(count) {
            val id = input.readUTF(); val width = input.readFloat(); val color = input.readInt()
            val pen = input.readUnsignedShort(); PenStyle.fromObjType(pen)
            val scale = input.readFloat()
            val drawPathWidth = input.readInt()
            val originY = input.readFloat()
            val nibAngle = if (version >= 3) input.readFloat() else Float.NaN
            val angles = if (version >= 3) {
                val size = input.readInt(); require(size in 0..2_000_000 && size % 2 == 0)
                IntArray(size) { input.readInt() }
            } else IntArray(0)
            val contourOffsets = if (version >= 3) {
                val size = input.readInt(); require(size in 0..1_000_000)
                IntArray(size) { input.readInt() }
            } else IntArray(0)
            require(width.isFinite() && width > 0 && scale.isFinite() && scale > 0 && originY.isFinite())
            val n = input.readInt(); require(n > 0 && n <= 1_000_000)
            val points = FloatArray(n*2); val pressure = FloatArray(n)
            repeat(n) { i ->
                points[2*i] = input.readFloat(); points[2*i+1] = originY + input.readFloat(); pressure[i] = input.readFloat()
                require(points[2*i].isFinite() && points[2*i+1].isFinite() && pressure[i].isFinite())
            }
            strokes.add(BoardEngine.StrokeRec(id, "canvas", width, color, points, pressure, pen, scale, drawPathWidth, nibAngle, angles, contourOffsets))
        }
        require(input.read() == -1) { "Trailing note bytes" }
        ScrollingDocument(ref, strokes, scroll)
    }
    fun write(file: File, doc: ScrollingDocument) {
        require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try {
            val data = DataOutputStream(BufferedOutputStream(output, IO_BUFFER))
            data.write(MAGIC); data.writeFloat(doc.scrollY); data.writeInt(doc.strokes.size)
            for (s in doc.strokes) {
                data.writeUTF(s.id); data.writeFloat(s.width); data.writeInt(s.color)
                data.writeShort(s.penStyle); data.writeFloat(s.sampleScale); data.writeInt(s.drawPathWidth); data.writeFloat(s.bounds.top)
                data.writeFloat(s.nibAngle)
                data.writeInt(s.angles.size); for (angle in s.angles) data.writeInt(angle)
                data.writeInt(s.contourOffsets.size); for (end in s.contourOffsets) data.writeInt(end)
                data.writeInt(s.points.size/2)
                for (i in s.pressures.indices) {
                    data.writeFloat(s.points[2*i]); data.writeFloat(s.points[2*i+1]-s.bounds.top); data.writeFloat(s.pressures[i])
                }
            }
            data.flush(); atomic.finishWrite(output)
        } catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
}
