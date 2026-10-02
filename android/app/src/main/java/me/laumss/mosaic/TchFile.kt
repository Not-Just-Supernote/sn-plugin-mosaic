package me.laumss.mosaic

import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File


object TchFile {
    private val MAGIC_V1 = byteArrayOf(77, 79, 83, 78, 79, 84, 69, 1)
    private val MAGIC = byteArrayOf(77, 79, 83, 78, 79, 84, 69, 2)
    fun read(file: File, ref: String): ScrollingDocument = DataInputStream(AtomicFile(file).openRead()).use { input ->
        val header = ByteArray(MAGIC.size); input.readFully(header)
        val v2 = header.contentEquals(MAGIC)
        require(v2 || header.contentEquals(MAGIC_V1)) { "Unsupported note format" }
        val scroll = input.readFloat(); require(scroll.isFinite() && scroll >= 0f)
        val count = input.readInt(); require(count >= 0)
        val strokes = ArrayList<BoardEngine.StrokeRec>()
        repeat(count) {
            val id = input.readUTF(); val width = input.readFloat(); val color = input.readInt()
            val pen = input.readUnsignedShort(); PenStyle.fromObjType(pen)
            val scale = input.readFloat()
            val drawPathWidth = if (v2) input.readInt() else DrawPathClient.widthArgument(PenStyle.fromObjType(pen), width)
            val originY = input.readFloat()
            require(width.isFinite() && width > 0 && scale.isFinite() && scale > 0 && originY.isFinite())
            val n = input.readInt(); require(n > 0 && n <= 1_000_000)
            val points = FloatArray(n*2); val pressure = FloatArray(n)
            repeat(n) { i ->
                points[2*i] = input.readFloat(); points[2*i+1] = originY + input.readFloat(); pressure[i] = input.readFloat()
                require(points[2*i].isFinite() && points[2*i+1].isFinite() && pressure[i].isFinite())
            }
            strokes.add(BoardEngine.StrokeRec(id, "canvas", width, color, points, pressure, pen, scale, drawPathWidth))
        }
        require(input.read() == -1) { "Trailing note bytes" }
        ScrollingDocument(ref, strokes, scroll)
    }
    fun write(file: File, doc: ScrollingDocument) {
        require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try {
            val data = DataOutputStream(output)
            data.write(MAGIC); data.writeFloat(doc.scrollY); data.writeInt(doc.strokes.size)
            for (s in doc.strokes) {
                data.writeUTF(s.id); data.writeFloat(s.width); data.writeInt(s.color)
                data.writeShort(s.penStyle); data.writeFloat(s.sampleScale); data.writeInt(s.drawPathWidth); data.writeFloat(s.bounds.top)
                data.writeInt(s.points.size/2)
                for (i in s.pressures.indices) {
                    data.writeFloat(s.points[2*i]); data.writeFloat(s.points[2*i+1]-s.bounds.top); data.writeFloat(s.pressures[i])
                }
            }
            data.flush(); atomic.finishWrite(output)
        } catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
}
