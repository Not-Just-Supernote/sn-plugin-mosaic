package me.laumss.mosaic

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs


object TchRaster {
    data class Record(val x: Int, val y: Int, val pressure: Float)
    private val PRESS_MAP = intArrayOf(70,9,96,10,1000,25,1500,37,2000,56,2500,90,3000,140,3500,213,4096,350)
    private const val WIDTH_RATE = 16384f
    private const val MAX_POINT_SPEED = 4000
    private const val MIN_DRAW_PP = 10
    private const val SPEED_NUMERATOR = 200
    private const val ENGINE_MOVE_THRESHOLD_PX = 4
    private const val SAMPLE_PERIOD_MS = 2
    private const val PEN_DOWN_GAP_MS = 4

    data class Run(val path: Path, val width: Float)
    fun runs(points: FloatArray, pressures: FloatArray, sampleScale: Float, width: Float, style: PenStyle): List<Run> {
        val n = points.size / 2
        if (n == 0) return emptyList()
        
        
        
        if (Shapes.kindOf(points, pressures) != null) {
            val w = if (style.isConstantWidth) engineWidths(listOf(Record(0, 0, Shapes.PRESSURE)), width, style)[0] else width * 0.5f
            return listOf(Run(BoardEngine.buildPolylinePath(points), maxOf(1f, w) / sampleScale))
        }
        
        val widths = engineWidths(List(n) { i -> Record((points[2*i]*sampleScale).toInt(), (points[2*i+1]*sampleScale).toInt(), pressures[i]) }, width, style)
        val runs = ArrayList<Run>()
        var path = Path().apply { moveTo(points[0], points[1]) }
        var runWidth = widths[0]
        var lastX = points[0]; var lastY = points[1]
        var midX = lastX; var midY = lastY
        for (i in 1 until n) {
            val x = points[2*i]; val y = points[2*i+1]
            val mx = (lastX+x)/2f; val my = (lastY+y)/2f
            if (widths[i] != runWidth) {
                runs.add(Run(path, maxOf(1f, runWidth)/sampleScale))
                path = Path().apply { moveTo(midX, midY) }; runWidth = widths[i]
            }
            path.quadTo(lastX, lastY, mx, my)
            midX=mx; midY=my; lastX=x; lastY=y
        }
        path.lineTo(lastX + if (n == 1) 0.01f else 0f, lastY)
        runs.add(Run(path, maxOf(1f, runWidth)/sampleScale))
        return runs
    }
    fun draw(canvas: Canvas, stroke: BoardEngine.StrokeRec, paint: Paint, color: Int = stroke.color) {
        paint.color = color
        
        paint.shader = if (stroke.penStyle == PenStyle.MARKER.objType) markerShader else null
        for (run in stroke.runs) { paint.strokeWidth = run.width; canvas.drawPath(run.path, paint) }
        paint.shader = null
    }
    private val markerShader by lazy {
        
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ALPHA_8)
        val alpha = byteArrayOf(
            (-1).toByte(), 0x00, 0x00, 0x00,
            0x00, 0x00, (-1).toByte(), 0x00,
            0x00, 0x00, 0x00, (-1).toByte(),
            0x00, (-1).toByte(), 0x00, 0x00,
        )
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(alpha))
        BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    fun engineWidths(
        pts: List<Record>,
        stdWidth: Float,
        style: PenStyle,
    ): FloatArray {
        val out = FloatArray(pts.size)

        
        
        
        
        if (style.isConstantWidth) {
            val w = quantise(
                if (style == PenStyle.MARKER) ((stdWidth * style.constantScale) + 0.5f).toInt().toFloat()
                else stdWidth * style.constantScale
            )
            return FloatArray(pts.size) { w }
        }

        val ppMap = style.ppMap!!
        val speedMap = style.speedMap

        var havePrev = false      
        var havePrev2 = false     
        var prevX = 0
        var prevY = 0
        var prevGapMs = 0
        var prevRawPp = 0
        var prev2RawPp = 0
        
        
        
        var prevSpeed = -1
        var prev2Speed = -1
        var keptX = 0
        var keptY = 0
        var skipped = 0
        var current = 0f
        for (i in pts.indices) {
            val p = pts[i]
            val x = p.x
            val y = p.y

            
            
            
            val kept = !havePrev ||
                abs(x - keptX) >= ENGINE_MOVE_THRESHOLD_PX ||
                abs(y - keptY) >= ENGINE_MOVE_THRESHOLD_PX
            if (!kept) {
                skipped++
                out[i] = current
                continue
            }

            val gapMs = if (havePrev) (skipped + 1) * SAMPLE_PERIOD_MS else PEN_DOWN_GAP_MS
            val rawPp = mapValue((4095f * p.pressure).toInt(), PRESS_MAP)

            val r: Float
            
            
            
            
            var speedX = -1
            if (rawPp < MIN_DRAW_PP) {
                r = 0.5f
            } else {
                var sRate = 128
                var pp = rawPp
                
                
                
                if (havePrev && speedMap != null) {
                    
                    
                    val dx = (x - prevX).toLong()
                    val dy = (y - prevY).toLong()
                    val dist = dx * dx + dy * dy
                    var speed2 = (dist * SPEED_NUMERATOR / (gapMs + prevGapMs)).toInt()
                    if (speed2 > MAX_POINT_SPEED) speed2 = MAX_POINT_SPEED

                    
                    
                    speedX = if (havePrev2) {
                        ((speed2 * 3) + (prevSpeed * 3) + (prev2Speed * 2)) shr 3
                    } else {
                        (speed2 + prevSpeed) shr 1
                    }
                    sRate = mapValue(speedX, speedMap)

                    
                    
                    
                    pp = if (havePrev2) {
                        ((rawPp * 4) + (prevRawPp * 2) + (prev2RawPp * 2)) shr 3
                    } else {
                        ((rawPp * 5) + (prevRawPp * 3)) shr 3
                    }
                }
                r = mapValue(pp, ppMap) * sRate * stdWidth / WIDTH_RATE
            }

            current = quantise(r)
            out[i] = current

            prev2Speed = prevSpeed; prevSpeed = speedX
            prev2RawPp = prevRawPp; prevRawPp = rawPp
            havePrev2 = havePrev; havePrev = true
            prevX = x; prevY = y; prevGapMs = gapMs
            keptX = x; keptY = y
            skipped = 0
        }
        return out
    }

    
    private fun quantise(r: Float): Float {
        
        val floored = if (r < 1.5f) 1.0f else r
        
        val ir = (floored + 0.5f).toInt()
        
        
        return if (ir < 2) 0f else ir.toFloat()
    }

    
    private fun mapValue(key: Int, map: IntArray): Int {
        if (key <= map[0]) return map[1]
        var i = 2
        while (i < map.size) {
            if (key <= map[i]) {
                val p0 = map[i - 1]
                val span = map[i + 1] - p0
                val dpx = map[i] - map[i - 2]
                return p0 + (((key - map[i - 2]) * span) + (dpx shr 1)) / dpx
            }
            i += 2
        }
        return map[map.size - 1]
    }
}
