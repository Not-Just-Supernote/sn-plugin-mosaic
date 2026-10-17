package me.laumss.mosaic.engine

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
/** A bridge binds its cards for as long as it contains ink. Call under BoardEngine.lock. */
internal object ConnectionBindings {
    fun refresh(
        connections: MutableMap<String, BoardEngine.ConnectionRec>,
        strokes: Collection<BoardEngine.StrokeRec>,
    ): Set<String> {
        val inked = strokes.asSequence().filter { it.points.size >= 2 }
            .mapNotNull { it.connectionId }.toHashSet()
        val changed = LinkedHashSet<String>()
        for ((id, connection) in connections) {
            val locked = id in inked
            if (connection.locked == locked) continue
            connections[id] = connection.withLocked(locked)
            changed.add(id)
        }
        return changed
    }

    fun expand(
        cardIds: Collection<String>,
        existingCards: Set<String>,
        connections: Collection<BoardEngine.ConnectionRec>,
    ): List<String> {
        val seen = cardIds.filterTo(LinkedHashSet()) { it in existingCards }
        val queue = ArrayDeque(seen)
        val neighbors = HashMap<String, MutableList<String>>()
        for (connection in connections) {
            if (!connection.locked) continue
            neighbors.getOrPut(connection.fromId) { ArrayList() }.add(connection.toId)
            neighbors.getOrPut(connection.toId) { ArrayList() }.add(connection.fromId)
        }
        while (queue.isNotEmpty()) {
            for (next in neighbors[queue.removeFirst()].orEmpty()) {
                if (next in existingCards && seen.add(next)) queue.add(next)
            }
        }
        return seen.toList()
    }
}
