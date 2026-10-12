package me.laumss.mosaic

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
