package me.laumss.mosaic

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF


object ToolIcons {
    private fun u(v: Float, s: Float) = v / 24f * s
    private fun fill(p: Paint) = Paint(p).apply { style = Paint.Style.FILL }
    private fun dashed(p: Paint, s: Float) = Paint(p).apply { pathEffect = DashPathEffect(floatArrayOf(u(3.2f,s),u(3f,s)),0f) }
    private fun barrel(c: Canvas,s: Float,p: Paint) { c.drawRoundRect(RectF(u(9.5f,s),u(3.5f,s),u(14.5f,s),u(15.5f,s)),u(1.3f,s),u(1.3f,s),p) }
    private fun rotated(c: Canvas,s: Float, block: () -> Unit) { c.save(); c.rotate(45f,s/2f,s/2f); block(); c.restore() }
    fun pen(c: Canvas,s: Float,p: Paint) = rotated(c,s) { barrel(c,s,p); c.drawPath(Path().apply { moveTo(u(9.5f,s),u(15.5f,s)); lineTo(u(14.5f,s),u(15.5f,s)); lineTo(u(12f,s),u(20.5f,s)); close() },fill(p)) }
    fun pencil(c: Canvas,s: Float,p: Paint) { rotated(c,s) { barrel(c,s,p); val dot=fill(p); for(i in 0..3) c.drawCircle(u(10.2f+i,s),u(17.2f,s),u(.65f,s),dot); c.drawPath(Path().apply { moveTo(u(9.5f,s),u(15.5f,s)); lineTo(u(14.5f,s),u(15.5f,s)); lineTo(u(12f,s),u(20.5f,s)); close() },fill(p)) } }
    fun marker(c: Canvas,s: Float,p: Paint) { rotated(c,s) { barrel(c,s,p); val hatch=Paint(p).apply { pathEffect=DashPathEffect(floatArrayOf(u(1.2f,s),u(1.2f,s)),0f) }; c.drawLine(u(10f,s),u(6f,s),u(14f,s),u(14f,s),hatch); c.drawLine(u(12f,s),u(5f,s),u(14f,s),u(9f,s),hatch) } }
    fun brush(c: Canvas,s: Float,p: Paint) = rotated(c,s) { barrel(c,s,p); c.drawPath(Path().apply { moveTo(u(9.6f,s),u(15.5f,s)); cubicTo(u(9.6f,s),u(18.6f,s),u(11.3f,s),u(21.4f,s),u(12f,s),u(22.2f,s)); cubicTo(u(12.7f,s),u(21.4f,s),u(14.4f,s),u(18.6f,s),u(14.4f,s),u(15.5f,s)); close() },fill(p)) }
    fun tech(c: Canvas,s: Float,p: Paint) = rotated(c,s) { barrel(c,s,p); c.drawLine(u(12f,s),u(15.5f,s),u(12f,s),u(20.8f,s),p) }
    fun eraseStroke(c: Canvas,s: Float,p: Paint) { c.save(); c.rotate(-45f,u(12f,s),u(10.5f,s)); c.drawRoundRect(RectF(u(6.5f,s),u(7.75f,s),u(17.5f,s),u(13.25f,s)),u(.6f,s),u(.6f,s),p); c.drawLine(u(10.2f,s),u(7.75f,s),u(10.2f,s),u(13.25f,s),p); c.restore(); c.drawLine(u(3f,s),u(20f,s),u(8.5f,s),u(20f,s),p); c.drawLine(u(15.5f,s),u(20f,s),u(21f,s),u(20f,s),p) }
    fun lasso(c: Canvas,s: Float,p: Paint) { c.drawOval(RectF(u(3.5f,s),u(4.2f,s),u(19.5f,s),u(16.2f,s)),dashed(p,s)); c.drawPath(Path().apply { moveTo(u(17.3f,s),u(14.4f,s)); cubicTo(u(19.9f,s),u(16.1f,s),u(19.4f,s),u(19.2f,s),u(16.6f,s),u(21f,s)) },p) }
    fun undo(c: Canvas,s: Float,p: Paint) = arrow(c,s,p,false)
    fun redo(c: Canvas,s: Float,p: Paint) = arrow(c,s,p,true)
    private fun arrow(c: Canvas,s: Float,p: Paint,flip: Boolean) { c.save(); if(flip){c.translate(s,0f);c.scale(-1f,1f)}; val r=u(5f,s); c.drawPath(Path().apply { moveTo(u(18.4f,s),u(18f,s)); lineTo(u(18.4f,s),u(11f,s)); arcTo(RectF(u(13.4f,s)-r,u(11f,s)-r,u(13.4f,s)+r,u(11f,s)+r),0f,-180f,false); lineTo(u(8.4f,s),u(17f,s)) },p); c.drawLine(u(5.2f,s),u(13.8f,s),u(8.4f,s),u(17f,s),p); c.drawLine(u(8.4f,s),u(17f,s),u(11.6f,s),u(13.8f,s),p); c.restore() }

    
    fun close(c: Canvas,s: Float,p: Paint){ c.drawCircle(u(12f,s),u(12f,s),u(8.6f,s),p); c.drawLine(u(8.7f,s),u(8.7f,s),u(15.3f,s),u(15.3f,s),p); c.drawLine(u(15.3f,s),u(8.7f,s),u(8.7f,s),u(15.3f,s),p) }

    
    fun more(c: Canvas,s: Float,p: Paint){ val f=fill(p); for(i in 0..2) c.drawCircle(u(12f,s),u(6.5f+5.5f*i,s),u(1.55f,s),f) }

    
    fun template(c: Canvas,s: Float,p: Paint){ c.drawRoundRect(RectF(u(5f,s),u(3f,s),u(19f,s),u(21f,s)),u(1.5f,s),u(1.5f,s),p); val f=fill(p); for(r in 0..2) for(col in 0..2) c.drawCircle(u(8f+col*4f,s),u(8f+r*4f,s),u(.9f,s),f) }

    
    fun shape(c: Canvas,s: Float,p: Paint){ c.drawPath(Path().apply { moveTo(u(9f,s),u(3.5f,s)); lineTo(u(15.5f,s),u(14f,s)); lineTo(u(2.5f,s),u(14f,s)); close() },p); c.drawCircle(u(15.5f,s),u(15.5f,s),u(5.2f,s),p) }
    
    fun shapeLine(c: Canvas,s: Float,p: Paint){ c.drawLine(u(4.5f,s),u(19.5f,s),u(19.5f,s),u(4.5f,s),p) }
    
    fun shapeRect(c: Canvas,s: Float,p: Paint){ c.drawRect(RectF(u(4f,s),u(6f,s),u(20f,s),u(18f,s)),p) }
    
    fun shapeTriangle(c: Canvas,s: Float,p: Paint){ c.drawPath(Path().apply { moveTo(u(12f,s),u(4.5f,s)); lineTo(u(20f,s),u(19f,s)); lineTo(u(4f,s),u(19f,s)); close() },p) }
    
    fun shapeEllipse(c: Canvas,s: Float,p: Paint){ c.drawOval(RectF(u(4f,s),u(5.5f,s),u(20f,s),u(18.5f,s)),p) }

    
    private fun page(c: Canvas,s: Float,p: Paint)= c.drawRoundRect(RectF(u(4f,s),u(3f,s),u(20f,s),u(21f,s)),u(1.5f,s),u(1.5f,s),p)
    
    fun templateNone(c: Canvas,s: Float,p: Paint)= page(c,s,p)
    
    fun templateDots(c: Canvas,s: Float,p: Paint){ page(c,s,p); val f=fill(p); for(r in 0..2) for(col in 0..2) c.drawCircle(u(7f+col*5f,s),u(7f+r*5f,s),u(.9f,s),f) }
    
    fun templateLines(c: Canvas,s: Float,p: Paint){ page(c,s,p); for(r in 0..2) c.drawLine(u(7f,s),u(8f+r*4.5f,s),u(17f,s),u(8f+r*4.5f,s),p) }
    
    fun templateCross(c: Canvas,s: Float,p: Paint){ page(c,s,p); val h=u(1.2f,s); for(r in 0..2) for(col in 0..2){ val x=u(7f+col*5f,s); val y=u(7f+r*5f,s); c.drawLine(x-h,y,x+h,y,p); c.drawLine(x,y-h,x,y+h,p) } }
    
    fun templateSpacing(c: Canvas,s: Float,p: Paint,gapU: Float){ var y=u(6f,s); val bottom=u(18f,s); val left=u(6f,s); val right=u(18f,s); while(y<=bottom){ c.drawLine(left,y,right,y,p); y+=u(gapU,s) } }

    
    fun nibWidth(c: Canvas,s: Float,p: Paint,strokePx: Float){ val q=Paint(p).apply{ strokeWidth=strokePx }; c.drawLine(u(7f,s),u(17f,s),u(17f,s),u(7f,s),q) }

    
    fun nibDot(c: Canvas,s: Float,p: Paint,radiusU: Float){ c.drawCircle(u(12f,s),u(12f,s),u(radiusU,s),fill(p)) }

    
    fun caret(c: Canvas,s: Float,p: Paint){
        val path=Path().apply{ moveTo(0f,s); lineTo(s,s); lineTo(s,0f); close() }
        c.drawPath(path, fill(p))
    }

    
    
    private const val FINGER_HAND =
        "m 19.7812,40.7711 c -5.723,-1.787 -10.5000276,-7.9 -12.8870276,-11.482 -0.23652,-0.3715 -0.3943,-0.7875 -0.46358,-1.2224 -0.06928,-0.4349 -0.04859,-0.8794 0.0608,-1.306 0.10939,-0.4266 0.30514,-0.8262 0.57515,-1.1741 0.27001,-0.3479 0.60853,-0.6367 0.99463,-0.8485 0.93179,-0.5997 2.0272276,-0.8945 3.1341276,-0.8432 1.107,0.0512 2.1705,0.4459 3.0429,1.1292 l 1.905,1.589 v -12.923 c 0,-1.4 1.356,-2.375 3.028,-2.375 0.6827,-0.0467 1.3598,0.1504 1.9108,0.5562 0.551,0.4058 0.9402,0.994 1.0982,1.6598 -0.021,-0.123 0,0 0.019,0.128 -0.006,-0.155 0,8.579 0,8.673 V 8.483106 c 0.0723,-0.7341972 0.4302,-1.4105948 0.9967,-1.8833444 0.5664,-0.47271 1.296,-0.70388 2.0313,-0.64366 0.7353,-0.06022 1.4648,0.17095 2.0312,0.64366 0.5665,0.4727496 0.9245,1.1491472 0.9968,1.8833444 V 23.0711 c 0,0 0,-10.216 0,-10.236 0.0789,-0.7294 0.4396,-1.399 1.0053,-1.8661 0.5657,-0.467 1.2916,-0.6945 2.0227,-0.6339 0.7353,-0.0602 1.4648,0.1709 2.0312,0.6437 0.5665,0.4727 0.9245,1.1491 0.9968,1.8833 v 11.449 c 0,-0.042 0,-4.8 0.032,-5.082 0.05,-1.094 1.374,-2.159 3,-2.159 0.7353,-0.0602 1.4648,0.1709 2.0312,0.6437 0.5665,0.4727 0.9245,1.1491 0.9968,1.8833 v 13.454 c 0.019,1.1072 -0.36,2.1845 -1.068,3.036 -1.6427,2.0541 -3.7744,3.6632 -6.2,4.68 -4.239,1.516 -8.03,1.652 -13.322,0.004 z"
    private const val FINGER_SLASH = "M 9.3700024,13.955 43.37,41.955"
    private val fingerHand by lazy { SvgPathParser.parse(FINGER_HAND) }
    private val fingerSlash by lazy { SvgPathParser.parse(FINGER_SLASH) }

    
    fun touch(c: Canvas,s: Float,p: Paint,enabled: Boolean){
        val bg = if (p.color == Color.WHITE) Color.BLACK else Color.WHITE
        
        
        val k = s / 48f
        val stroke = Paint(p).apply { strokeWidth = p.strokeWidth / k }
        c.save(); c.scale(k,k)
        c.drawPath(fingerHand, Paint(stroke).apply { style=Paint.Style.FILL; color=bg })
        c.drawPath(fingerHand, stroke)
        if (!enabled) c.drawPath(fingerSlash, stroke)
        c.restore()
    }

    
    
    private const val CLIP_ARC_A =
        "M16.5688 12.129L18.0228 10.478L19.4778 8.83195C20.4634 7.71322 21.6599 6.79992 22.999 6.14436C24.338 5.48881 25.7933 5.10389 27.2813 5.01164C28.7694 4.9194 30.261 5.12165 31.6708 5.60681C33.0805 6.09197 34.3807 6.8505 35.4968 7.83895C36.0731 8.34857 36.5965 8.91512 37.0588 9.52995C37.95 10.7137 38.5994 12.0613 38.9698 13.496C39.3343 14.908 39.4253 16.3767 39.2378 17.823C38.946 20.0654 37.9888 22.1688 36.4898 23.862L35.0358 25.509L33.5808 27.155"
    private const val CLIP_ARC_B =
        "M27.7639 33.7409L26.3099 35.3879L24.8549 37.0339C23.3764 38.7044 21.439 39.9028 19.2841 40.4797C17.1292 41.0566 14.8522 40.9864 12.7369 40.2779C12.0302 40.0411 11.3488 39.7346 10.7029 39.3629C10.0369 38.9801 9.41133 38.5308 8.83588 38.0219C8.26002 37.5133 7.73673 36.9481 7.27388 36.3349C5.70314 34.247 4.90183 31.6807 5.00552 29.07C5.1092 26.4593 6.11151 23.9646 7.84288 22.0079L9.29688 20.3609L10.7519 18.7149"
    private const val CLIP_DIAG = "M27.0668 18.588L19.9958 25.659"
    private const val CLIP_BAR = "M32.8289 38.2419H44.8289"
    private val clipArcA by lazy { SvgPathParser.parse(CLIP_ARC_A) }
    private val clipArcB by lazy { SvgPathParser.parse(CLIP_ARC_B) }
    private val clipDiag by lazy { SvgPathParser.parse(CLIP_DIAG) }
    private val clipBar by lazy { SvgPathParser.parse(CLIP_BAR) }

    
    fun clip(c: Canvas,s: Float,p: Paint,removable: Boolean){
        val k = s / 48f
        val stroke = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = p.strokeWidth / k }
        c.save(); c.scale(k,k)
        c.drawPath(clipArcA, stroke)
        c.drawPath(clipArcB, stroke)
        c.drawPath(clipDiag, stroke)
        if (removable) c.drawPath(clipBar, stroke)
        c.restore()
    }
}
