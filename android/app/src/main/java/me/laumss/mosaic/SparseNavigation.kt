package me.laumss.mosaic

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min


object SparseNavigation {

    const val CHUNK_SIZE = 512f
    const val MAX_ISLAND_GAP_CHUNKS = 3
    
    const val PAN_MARGIN = CHUNK_SIZE * MAX_ISLAND_GAP_CHUNKS
    
    const val JUMP_TRIGGER_PX = 96f

    enum class Direction { LEFT, RIGHT, UP, DOWN }

    class Region(
        
        val bounds: RectF,
        val cardIds: List<String>,
    )

    class Neighbors(
        val left: Region? = null,
        val right: Region? = null,
        val up: Region? = null,
        val down: Region? = null,
    ) {
        operator fun get(direction: Direction): Region? = when (direction) {
            Direction.LEFT -> left
            Direction.RIGHT -> right
            Direction.UP -> up
            Direction.DOWN -> down
        }

        fun any(): Boolean = left != null || right != null || up != null || down != null

        companion object {
            val NONE = Neighbors()
        }
    }

    class Overscroll(val direction: Direction, val distancePx: Float)

    class PanResult(val panX: Float, val panY: Float, val overscroll: Overscroll?)

    class RegionJump(val direction: Direction, val region: Region, val gapWorld: Float)

    class Item(val id: String, val x: Float, val y: Float, val width: Float, val height: Float)

    

    private fun chunkOf(coord: Float): Int = floor(coord / CHUNK_SIZE).toInt()

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
    private fun keyX(key: Long): Int = (key shr 32).toInt()
    private fun keyY(key: Long): Int = key.toInt()

    
    fun detectRegions(items: List<Item>): List<Region> {
        if (items.isEmpty()) return emptyList()
        val occupied = HashMap<Long, MutableList<Item>>()
        for (item in items) {
            val cx0 = chunkOf(item.x)
            val cy0 = chunkOf(item.y)
            val cx1 = chunkOf(item.x + item.width - 1f)
            val cy1 = chunkOf(item.y + item.height - 1f)
            for (cy in cy0..cy1) for (cx in cx0..cx1) {
                occupied.getOrPut(key(cx, cy)) { ArrayList(2) }.add(item)
            }
        }

        val visited = HashSet<Long>()
        val regions = ArrayList<Region>()
        val gap = MAX_ISLAND_GAP_CHUNKS
        val queue = ArrayDeque<Long>()

        for (start in occupied.keys) {
            if (!visited.add(start)) continue
            val cardIds = LinkedHashSet<String>()
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            queue.clear()
            queue.addLast(start)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                occupied[current]?.forEach { item ->
                    if (cardIds.add(item.id)) {
                        minX = min(minX, item.x)
                        minY = min(minY, item.y)
                        maxX = max(maxX, item.x + item.width)
                        maxY = max(maxY, item.y + item.height)
                    }
                }
                val cx = keyX(current)
                val cy = keyY(current)
                for (dy in -gap..gap) for (dx in -gap..gap) {
                    if (dx == 0 && dy == 0) continue
                    val neighbor = key(cx + dx, cy + dy)
                    if (occupied.containsKey(neighbor) && visited.add(neighbor)) queue.addLast(neighbor)
                }
            }
            regions.add(Region(RectF(minX, minY, maxX, maxY), cardIds.toList()))
        }
        return regions
    }

    

    private fun overlapArea(a: RectF, b: RectF): Float {
        val ox = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val oy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        return ox * oy
    }

    
    fun findActiveRegion(regions: List<Region>, viewportWorld: RectF): Region? {
        var best: Region? = null
        var bestArea = 0f
        for (region in regions) {
            val area = overlapArea(region.bounds, viewportWorld)
            if (area > bestArea) {
                best = region
                bestArea = area
            }
        }
        if (best == null && regions.isNotEmpty()) {
            var bestDist = Float.MAX_VALUE
            val vcx = viewportWorld.centerX()
            val vcy = viewportWorld.centerY()
            for (region in regions) {
                val d = hypot(vcx - region.bounds.centerX(), vcy - region.bounds.centerY())
                if (d < bestDist) {
                    best = region
                    bestDist = d
                }
            }
        }
        return best
    }

    
    fun findNeighbors(regions: List<Region>, active: Region): Neighbors {
        val acx = active.bounds.centerX()
        val acy = active.bounds.centerY()
        val best = arrayOfNulls<Region>(4)
        val bestDist = FloatArray(4) { Float.MAX_VALUE }
        for (region in regions) {
            if (region === active) continue
            val dx = region.bounds.centerX() - acx
            val dy = region.bounds.centerY() - acy
            val dir = if (abs(dx) > abs(dy)) {
                if (dx < 0) Direction.LEFT else Direction.RIGHT
            } else {
                if (dy < 0) Direction.UP else Direction.DOWN
            }
            val d = hypot(dx, dy)
            if (d < bestDist[dir.ordinal]) {
                bestDist[dir.ordinal] = d
                best[dir.ordinal] = region
            }
        }
        return Neighbors(
            left = best[Direction.LEFT.ordinal],
            right = best[Direction.RIGHT.ordinal],
            up = best[Direction.UP.ordinal],
            down = best[Direction.DOWN.ordinal],
        )
    }

    
    fun findRegionJumps(regions: List<Region>, viewportWorld: RectF, margin: Float): List<RegionJump> {
        val expanded = RectF(viewportWorld).apply { inset(-margin, -margin) }
        val jumps = arrayOfNulls<RegionJump>(4)
        val vcx = viewportWorld.centerX()
        val vcy = viewportWorld.centerY()
        for (region in regions) {
            if (overlapArea(expanded, region.bounds) > 0f) continue
            val dx = region.bounds.centerX() - vcx
            val dy = region.bounds.centerY() - vcy
            val dir: Direction
            val gap: Float
            if (abs(dx) > abs(dy)) {
                dir = if (dx < 0) Direction.LEFT else Direction.RIGHT
                gap = abs(dx) - viewportWorld.width() / 2f - region.bounds.width() / 2f
            } else {
                dir = if (dy < 0) Direction.UP else Direction.DOWN
                gap = abs(dy) - viewportWorld.height() / 2f - region.bounds.height() / 2f
            }
            val existing = jumps[dir.ordinal]
            if (existing == null || gap < existing.gapWorld) {
                jumps[dir.ordinal] = RegionJump(dir, region, max(0f, gap))
            }
        }
        return jumps.filterNotNull()
    }

    

    fun viewportWorldRect(panX: Float, panY: Float, scale: Float, viewW: Float, viewH: Float, out: RectF): RectF {
        val s = if (scale > 0f) scale else 1f
        val left = -panX / s
        val top = -panY / s
        out.set(left, top, left + viewW / s, top + viewH / s)
        return out
    }

    
    fun panToCenterRect(rect: RectF, viewW: Float, viewH: Float, scale: Float): FloatArray {
        val cx = rect.centerX()
        val cy = rect.centerY()
        return floatArrayOf(
            -(cx - viewW / scale / 2f) * scale,
            -(cy - viewH / scale / 2f) * scale,
        )
    }

    

    
    fun constrainPan(
        panX: Float,
        panY: Float,
        scale: Float,
        viewW: Float,
        viewH: Float,
        active: Region,
        neighbors: Neighbors,
        margin: Float = PAN_MARGIN,
    ): PanResult {
        val s = if (scale > 0f) scale else 1f
        val worldW = viewW / s
        val worldH = viewH / s
        val worldX = -panX / s
        val worldY = -panY / s
        val b = active.bounds
        val minWorldX = b.left - margin
        val maxWorldX = b.right + margin - worldW
        val minWorldY = b.top - margin
        val maxWorldY = b.bottom + margin - worldH

        var clampedX = worldX
        var clampedY = worldY
        var dir: Direction? = null
        var dist = 0f

        if (maxWorldX < minWorldX) {
            
            
            clampedX = worldX.coerceIn(maxWorldX, minWorldX)
        } else if (worldX < minWorldX) {
            clampedX = minWorldX
            val d = (minWorldX - worldX) * s
            if (neighbors.left != null && d > dist) { dir = Direction.LEFT; dist = d }
        } else if (worldX > maxWorldX) {
            clampedX = maxWorldX
            val d = (worldX - maxWorldX) * s
            if (neighbors.right != null && d > dist) { dir = Direction.RIGHT; dist = d }
        }
        if (maxWorldY < minWorldY) {
            
            clampedY = worldY.coerceIn(maxWorldY, minWorldY)
        } else if (worldY < minWorldY) {
            clampedY = minWorldY
            val d = (minWorldY - worldY) * s
            if (neighbors.up != null && d > dist) { dir = Direction.UP; dist = d }
        } else if (worldY > maxWorldY) {
            clampedY = maxWorldY
            val d = (worldY - maxWorldY) * s
            if (neighbors.down != null && d > dist) { dir = Direction.DOWN; dist = d }
        }

        return PanResult(
            panX = -clampedX * s,
            panY = -clampedY * s,
            overscroll = dir?.let { Overscroll(it, dist) },
        )
    }
}
