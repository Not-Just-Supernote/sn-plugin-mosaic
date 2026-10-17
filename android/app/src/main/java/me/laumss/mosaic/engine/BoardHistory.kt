package me.laumss.mosaic.engine

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
class BoardHistory(private val limit: Int = MAX_UNDO_STACK) {
    companion object {
        const val MAX_UNDO_STACK = 200
    }

    sealed class Diff {
        class Stroke(val before: BoardEngine.StrokeRec?, val after: BoardEngine.StrokeRec?) : Diff()
        class Card(val before: BoardEngine.CardRec?, val after: BoardEngine.CardRec?) : Diff()
        class Connection(val before: BoardEngine.ConnectionRec?, val after: BoardEngine.ConnectionRec?) : Diff()
    }

    class Change(val label: String) {
        val diffs = ArrayList<Diff>()
        val isEmpty: Boolean get() = diffs.isEmpty()

        fun stroke(before: BoardEngine.StrokeRec?, after: BoardEngine.StrokeRec?) = apply { diffs.add(Diff.Stroke(before, after)) }
        fun card(before: BoardEngine.CardRec?, after: BoardEngine.CardRec?) = apply { diffs.add(Diff.Card(before, after)) }
        fun connection(before: BoardEngine.ConnectionRec?, after: BoardEngine.ConnectionRec?) = apply { diffs.add(Diff.Connection(before, after)) }

        fun absorb(other: Change) = apply { diffs.addAll(other.diffs) }
    }

    private val undo = ArrayList<Change>()
    private val redo = ArrayList<Change>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun push(change: Change) {
        if (change.isEmpty) return
        undo.add(change)
        if (undo.size > limit) undo.removeAt(0)
        redo.clear()
    }

    fun popUndo(): Change? {
        val change = undo.removeLastOrNull() ?: return null
        redo.add(change)
        return change
    }

    fun popRedo(): Change? {
        val change = redo.removeLastOrNull() ?: return null
        undo.add(change)
        return change
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }
}
