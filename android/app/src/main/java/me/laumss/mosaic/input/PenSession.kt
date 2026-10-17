package me.laumss.mosaic.input

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
import android.graphics.RectF

/** What the pen is doing between down and up. */
internal sealed class PenSession {
    class Write(
        val id: String,
        val space: String,
        val cardId: String?,
        val cardX: Float,
        val cardY: Float,
    ) : PenSession() {
        val points = FloatArrayList()
        val pressures = FloatArrayList()
        var nibAngle = Float.NaN

        var frameLastMeaningfulX = 0f
        var frameLastMeaningfulY = 0f
        var frameCandidate: RectF? = null
        var frameRecognizedX = 0f
        var frameRecognizedY = 0f
        var frameEvaluations = 0
        var continuousWrite = false
        // Set once the stroke leaves a note page; later points are dropped.
        var clipped = false

        var convertFromIndex = -1

        var rawMinX = Float.MAX_VALUE
        var rawMinY = Float.MAX_VALUE
        var rawMaxX = -Float.MAX_VALUE
        var rawMaxY = -Float.MAX_VALUE

        fun raw(x: Float, y: Float) {
            if (x < rawMinX) rawMinX = x; if (x > rawMaxX) rawMaxX = x
            if (y < rawMinY) rawMinY = y; if (y > rawMaxY) rawMaxY = y
        }
    }

    class Erase(val change: BoardHistory.Change) : PenSession() {
        var pending = BoardHistory.Change("erase")
        val pendingIds = HashSet<String>()
    }

    class Lasso : PenSession() {
        val pointsPx = FloatArrayList()
    }

    class Shape(val kind: Shapes.Kind, val anchorX: Float, val anchorY: Float) : PenSession() {
        var curX = anchorX
        var curY = anchorY
    }

    object Consumed : PenSession()

    class OpenNote(val ref: String, val downX: Float, val downY: Float, val shotId: String? = null) : PenSession() {
        var moved = false
    }
}
