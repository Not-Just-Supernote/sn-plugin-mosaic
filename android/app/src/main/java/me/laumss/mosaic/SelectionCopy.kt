package me.laumss.mosaic

import java.io.File

/** Builds a single undoable duplicate while preserving stroke data and internal ownership. */
object SelectionCopy {
    class Plan(val change: BoardHistory.Change, val cardIds: List<String>, val strokeIds: List<String>,
               val noteRefs: Map<String, String>)

    fun build(cardIds: List<String>, strokeIds: List<String>, scene: BoardEngine.SceneSnapshot,
              offset: Float, notesDirectory: String, newId: (String) -> String): Plan {
        val change = BoardHistory.Change("selection-copy")
        val originals = scene.cards.associateBy { it.id }
        val selectedCards = cardIds.toSet()
        val copiedCards = LinkedHashMap<String, BoardEngine.CardRec>()
        val noteRefs = LinkedHashMap<String, String>()
        var z = (scene.cards.maxOfOrNull { it.zIndex } ?: 0) + 1
        for (card in scene.cards.filter { it.id in selectedCards }.sortedBy { it.zIndex }) {
            val noteRef = if (card.kind == "note") noteRefs.getOrPut(card.noteRef) { newId("note-") } else card.noteRef
            val imagePath = if (card.kind == "note") File(notesDirectory, noteRef).absolutePath else card.imagePath
            val copy = BoardEngine.CardRec(newId("card-"), card.x + offset, card.y + offset,
                card.width, card.height, z++, card.kind, card.content, imagePath, noteRef,
                card.bgColor, card.textColor, card.title)
            copiedCards[card.id] = copy
            change.card(null, copy)
        }
        val copiedConnections = LinkedHashMap<String, BoardEngine.ConnectionRec>()
        for (connection in scene.connections) {
            val from = copiedCards[connection.fromId] ?: continue
            val to = copiedCards[connection.toId] ?: continue
            val copy = BoardEngine.ConnectionRec(newId("connection-"), from.id, to.id, connection.locked)
            copiedConnections[connection.id] = copy
            change.connection(null, copy)
        }
        val selectedStrokes = strokeIds.toSet()
        val copiedStrokeIds = ArrayList<String>()
        for (stroke in scene.strokes) {
            val copiedCard = stroke.cardId?.let { copiedCards[it] }
            val copiedConnection = stroke.connectionId?.let { copiedConnections[it] }
            val ends = stroke.threadEnds
            val copiedThread = ends != null && ends.first in copiedCards && ends.second in copiedCards
            if (stroke.id !in selectedStrokes && copiedCard == null && copiedConnection == null && !copiedThread) continue
            val sourceCard = stroke.cardId?.let { originals[it] }
            val translated = if (copiedCard != null || sourceCard != null) {
                stroke.translated(offset, offset, sourceCard, copiedCard ?: sourceCard)
            } else stroke.translatedWorld(offset, offset)
            val space = when {
                copiedThread -> BoardEngine.THREAD_SPACE + copiedCards.getValue(ends!!.first).id + "|" + copiedCards.getValue(ends.second).id
                copiedConnection != null -> "connection:${copiedConnection.id}"
                stroke.connectionId != null || stroke.isThread -> "canvas"
                else -> translated.space
            }
            val copy = BoardEngine.StrokeRec(newId("stroke-"), space, stroke.width, stroke.color,
                translated.points.copyOf(), stroke.pressures.copyOf(), stroke.penStyle, stroke.sampleScale,
                stroke.drawPathWidth, stroke.nibAngle, stroke.angles.copyOf())
            change.stroke(null, copy)
            if (!copy.isThread) copiedStrokeIds.add(copy.id)
        }
        return Plan(change, copiedCards.values.map { it.id }, copiedStrokeIds, noteRefs)
    }
}
