package me.laumss.mosaic.engine

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
object SelectionActions {
    fun canInsertNoteImage(fromLasso: Boolean, cardIds: Collection<String>, strokeIds: Collection<String>,
                           strokes: Collection<BoardEngine.StrokeRec>): Boolean {
        if (!fromLasso) return false
        val cards = cardIds.toSet()
        val selected = strokeIds.toSet()
        return strokes.any { stroke ->
            (stroke.id in selected || stroke.cardId in cards) && !stroke.isShape && !stroke.isThread &&
                stroke.penStyle != PenStyle.FILLED_SHAPE.objType
        }
    }
}
