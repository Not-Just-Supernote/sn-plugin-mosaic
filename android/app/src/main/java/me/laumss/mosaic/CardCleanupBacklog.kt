package me.laumss.mosaic

import android.graphics.Region

/** Screen positions remain owed until a matching batch finishes its white phase. */
internal class CardCleanupBacklog {
    data class Ticket(val generation: Long, val region: Region)
    private val region = Region()
    private var generation = 0L
    val isEmpty: Boolean get() = region.isEmpty

    fun add(oldPositions: Region) {
        if (oldPositions.isEmpty) return
        region.op(oldPositions, Region.Op.UNION)
        generation++
    }

    fun capture() = Ticket(generation, Region(region))

    fun complete(ticket: Ticket): Boolean {
        if (ticket.generation != generation) return false
        clear()
        return true
    }

    fun clear() {
        region.setEmpty()
        generation++
    }

    companion object {
        fun whiteCompleted(result: String): Boolean {
            if (!result.startsWith("released:")) return false
            val fields = Regex("(pixels|whiteRc|historyWhite|busy)=(-?\\d+)")
                .findAll(result).associate { it.groupValues[1] to it.groupValues[2].toLongOrNull() }
            val pixels = fields["pixels"] ?: return false
            return pixels > 0 && fields["whiteRc"] in listOf(0L, 17L) &&
                fields["busy"] == 0L && fields["historyWhite"] == pixels
        }
    }
}
