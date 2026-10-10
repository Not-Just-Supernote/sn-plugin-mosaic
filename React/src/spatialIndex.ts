// spatialIndex.ts — Chunk-based spatial index for canvas culling, region detection,
// and sparse-canvas navigation (shared with Mosaic).
//
// Cards are bucketed into a grid of CHUNK_SIZE (512) world units. Regions are
// contiguous groups of occupied chunks (allowing MAX_ISLAND_GAP_CHUNKS gaps).
// constrainPanToRegion keeps the viewport within the active region plus its
// nearest neighbors, with directional overscroll for inter-island jumping.

export const CHUNK_SIZE = 512
export const MAX_ISLAND_GAP_CHUNKS = 3

// ── Types ──

export interface Rect {
  x: number
  y: number
  width: number
  height: number
}

export type NavDirection = 'left' | 'right' | 'up' | 'down'

export interface CanvasRegion {
  bounds: Rect
  cardIds: string[]
  chunks: Set<string>
}

export type DirectionalRegions = Partial<Record<NavDirection, CanvasRegion>>

export interface ChunkIndex {
  /** Map from "cx,cy" chunk key to array of card ids occupying that chunk. */
  chunks: Map<string, string[]>
  /** Map from card id to its world rect. */
  rects: Map<string, Rect>
}

// ── Chunk helpers ──

function chunkKey(cx: number, cy: number): string { return `${cx},${cy}` }

function worldToChunk(coord: number): number { return Math.floor(coord / CHUNK_SIZE) }

/** Iterate chunk coordinates a rect touches. */
function* rectChunks(x: number, y: number, w: number, h: number) {
  const cx0 = worldToChunk(x)
  const cy0 = worldToChunk(y)
  const cx1 = worldToChunk(x + w - 1)
  const cy1 = worldToChunk(y + h - 1)
  for (let cy = cy0; cy <= cy1; cy++) {
    for (let cx = cx0; cx <= cx1; cx++) {
      yield { cx, cy }
    }
  }
}

// ── Build ──

export function buildChunkIndex(
  items: Array<{ id: string; x: number; y: number; width: number; height: number }>,
): ChunkIndex {
  const chunks = new Map<string, string[]>()
  const rects = new Map<string, Rect>()
  for (const item of items) {
    rects.set(item.id, { x: item.x, y: item.y, width: item.width, height: item.height })
    for (const { cx, cy } of rectChunks(item.x, item.y, item.width, item.height)) {
      const key = chunkKey(cx, cy)
      const bucket = chunks.get(key)
      if (bucket) bucket.push(item.id)
      else chunks.set(key, [item.id])
    }
  }
  return { chunks, rects }
}

// ── Query ──

/** Return card ids whose chunk buckets intersect the given world rect. */
export function queryRect(index: ChunkIndex, rect: Rect): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  for (const { cx, cy } of rectChunks(rect.x, rect.y, rect.width, rect.height)) {
    const bucket = index.chunks.get(chunkKey(cx, cy))
    if (!bucket) continue
    for (const id of bucket) {
      if (seen.has(id)) continue
      seen.add(id)
      result.push(id)
    }
  }
  return result
}

// ── Viewport ↔ world ──

export function viewportWorldRect(
  panX: number,
  panY: number,
  scale: number,
  viewportWidth: number,
  viewportHeight: number,
  expand = 0,
): Rect {
  const s = scale || 1
  const worldW = viewportWidth / s + expand * 2
  const worldH = viewportHeight / s + expand * 2
  return {
    x: -panX / s - expand,
    y: -panY / s - expand,
    width: worldW,
    height: worldH,
  }
}

export function panToCenterRect(
  rect: Rect,
  viewportWidth: number,
  viewportHeight: number,
  scale: number,
): { panX: number; panY: number } {
  const cx = rect.x + rect.width / 2
  const cy = rect.y + rect.height / 2
  return {
    panX: -(cx - viewportWidth / scale / 2) * scale,
    panY: -(cy - viewportHeight / scale / 2) * scale,
  }
}

// ── Region detection (connected-component over occupied chunks with gap tolerance) ──

export function detectRegions(index: ChunkIndex): CanvasRegion[] {
  if (index.chunks.size === 0) return []

  // Parse all occupied chunk coordinates
  const occupied = new Map<string, { cx: number; cy: number }>()
  for (const key of index.chunks.keys()) {
    const [cxStr, cyStr] = key.split(',')
    occupied.set(key, { cx: parseInt(cxStr), cy: parseInt(cyStr) })
  }

  // BFS with gap tolerance
  const visited = new Set<string>()
  const regions: CanvasRegion[] = []
  const gap = MAX_ISLAND_GAP_CHUNKS

  for (const startKey of occupied.keys()) {
    if (visited.has(startKey)) continue
    const regionChunks = new Set<string>()
    const cardIdSet = new Set<string>()
    const queue = [startKey]
    visited.add(startKey)

    while (queue.length > 0) {
      const key = queue.shift()!
      regionChunks.add(key)
      const bucket = index.chunks.get(key)
      if (bucket) for (const id of bucket) cardIdSet.add(id)

      const { cx, cy } = occupied.get(key) ?? (() => {
        const [a, b] = key.split(',')
        return { cx: parseInt(a), cy: parseInt(b) }
      })()

      // Search within gap radius for other occupied chunks
      for (let dy = -gap; dy <= gap; dy++) {
        for (let dx = -gap; dx <= gap; dx++) {
          if (dx === 0 && dy === 0) continue
          const nk = chunkKey(cx + dx, cy + dy)
          if (!visited.has(nk) && occupied.has(nk)) {
            visited.add(nk)
            queue.push(nk)
          }
        }
      }
    }

    // Compute bounds from actual card rects in this region
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
    for (const id of cardIdSet) {
      const r = index.rects.get(id)!
      if (r.x < minX) minX = r.x
      if (r.y < minY) minY = r.y
      if (r.x + r.width > maxX) maxX = r.x + r.width
      if (r.y + r.height > maxY) maxY = r.y + r.height
    }

    regions.push({
      bounds: { x: minX, y: minY, width: maxX - minX, height: maxY - minY },
      cardIds: [...cardIdSet],
      chunks: regionChunks,
    })
  }

  return regions
}

// ── Active region (the one most overlapping the current viewport) ──

function rectsOverlap(a: Rect, b: Rect): number {
  const ox = Math.max(0, Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x))
  const oy = Math.max(0, Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y))
  return ox * oy
}

export function findActiveRegion(regions: CanvasRegion[], viewport: Rect): CanvasRegion | null {
  let best: CanvasRegion | null = null
  let bestArea = 0
  for (const region of regions) {
    const area = rectsOverlap(region.bounds, viewport)
    if (area > bestArea) { best = region; bestArea = area }
  }
  // If no overlap, find nearest
  if (!best && regions.length > 0) {
    let bestDist = Infinity
    const vcx = viewport.x + viewport.width / 2
    const vcy = viewport.y + viewport.height / 2
    for (const region of regions) {
      const rcx = region.bounds.x + region.bounds.width / 2
      const rcy = region.bounds.y + region.bounds.height / 2
      const dist = Math.hypot(vcx - rcx, vcy - rcy)
      if (dist < bestDist) { best = region; bestDist = dist }
    }
  }
  return best
}

// ── Directional neighbors ──

export function findDirectionalRegionNeighbors(
  regions: CanvasRegion[],
  active: CanvasRegion,
): DirectionalRegions {
  const result: DirectionalRegions = {}
  const ac = {
    x: active.bounds.x + active.bounds.width / 2,
    y: active.bounds.y + active.bounds.height / 2,
  }

  for (const region of regions) {
    if (region === active) continue
    const rc = {
      x: region.bounds.x + region.bounds.width / 2,
      y: region.bounds.y + region.bounds.height / 2,
    }
    const dx = rc.x - ac.x
    const dy = rc.y - ac.y

    // Determine primary direction
    let dir: NavDirection
    if (Math.abs(dx) > Math.abs(dy)) {
      dir = dx < 0 ? 'left' : 'right'
    } else {
      dir = dy < 0 ? 'up' : 'down'
    }

    const existing = result[dir]
    if (!existing) {
      result[dir] = region
    } else {
      // Keep nearest
      const existingC = {
        x: existing.bounds.x + existing.bounds.width / 2,
        y: existing.bounds.y + existing.bounds.height / 2,
      }
      if (Math.hypot(dx, dy) < Math.hypot(existingC.x - ac.x, existingC.y - ac.y)) {
        result[dir] = region
      }
    }
  }

  return result
}

// ── Region jumps (off-screen regions with gap indicators) ──

export interface RegionJump {
  direction: NavDirection
  region: CanvasRegion
  gap: number // world units to the region
}

export function findRegionJumps(
  regions: CanvasRegion[],
  viewport: Rect,
  margin: number,
): RegionJump[] {
  const jumps: RegionJump[] = []
  const expanded: Rect = {
    x: viewport.x - margin,
    y: viewport.y - margin,
    width: viewport.width + margin * 2,
    height: viewport.height + margin * 2,
  }

  for (const region of regions) {
    // Skip regions that are visible or very close
    if (rectsOverlap(expanded, region.bounds) > 0) continue

    const rcx = region.bounds.x + region.bounds.width / 2
    const rcy = region.bounds.y + region.bounds.height / 2
    const vcx = viewport.x + viewport.width / 2
    const vcy = viewport.y + viewport.height / 2
    const dx = rcx - vcx
    const dy = rcy - vcy

    let dir: NavDirection
    let gap: number
    if (Math.abs(dx) > Math.abs(dy)) {
      dir = dx < 0 ? 'left' : 'right'
      gap = Math.abs(dx) - viewport.width / 2 - region.bounds.width / 2
    } else {
      dir = dy < 0 ? 'up' : 'down'
      gap = Math.abs(dy) - viewport.height / 2 - region.bounds.height / 2
    }

    // Keep only the nearest per direction
    const existing = jumps.find(j => j.direction === dir)
    if (!existing) {
      jumps.push({ direction: dir, region, gap: Math.max(0, gap) })
    } else if (gap < existing.gap) {
      existing.region = region
      existing.gap = Math.max(0, gap)
    }
  }

  return jumps
}

// ── Constrain pan to active region ──

export function constrainPanToRegion(
  panX: number,
  panY: number,
  scale: number,
  viewportWidth: number,
  viewportHeight: number,
  activeRegion: CanvasRegion,
  neighbors: DirectionalRegions,
  margin: number,
): { panX: number; panY: number; overscroll: { direction: NavDirection; distancePx: number } | null } {
  const s = scale || 1
  // World-space viewport
  const worldW = viewportWidth / s
  const worldH = viewportHeight / s
  const worldX = -panX / s
  const worldY = -panY / s

  // Allowed region in world space (the active region padded by margin in chunk units)
  const pad = margin
  const bounds = activeRegion.bounds
  const minWorldX = bounds.x - pad
  const maxWorldX = bounds.x + bounds.width + pad - worldW
  const minWorldY = bounds.y - pad
  const maxWorldY = bounds.y + bounds.height + pad - worldH

  let clampedX = worldX
  let clampedY = worldY
  let overscrollDir: NavDirection | null = null
  let overscrollDist = 0

  if (maxWorldX < minWorldX) {
    // Content+margin narrower than viewport: valid range collapses to
    // [maxWorldX, minWorldX] (endpoints swapped). Clamp to the nearest end —
    // never snap to center every frame, which rubber-bands the pan mid-drag.
    clampedX = Math.min(minWorldX, Math.max(maxWorldX, worldX))
  } else if (worldX < minWorldX) {
    clampedX = minWorldX
    const dist = (minWorldX - worldX) * s
    if (neighbors.left && dist > overscrollDist) {
      overscrollDir = 'left'
      overscrollDist = dist
    }
  } else if (worldX > maxWorldX) {
    clampedX = maxWorldX
    const dist = (worldX - maxWorldX) * s
    if (neighbors.right && dist > overscrollDist) {
      overscrollDir = 'right'
      overscrollDist = dist
    }
  }

  if (maxWorldY < minWorldY) {
    // Content+margin shorter than viewport: valid range collapses to
    // [maxWorldY, minWorldY] (endpoints swapped). Clamp to nearest end.
    clampedY = Math.min(minWorldY, Math.max(maxWorldY, worldY))
  } else if (worldY < minWorldY) {
    clampedY = minWorldY
    const dist = (minWorldY - worldY) * s
    if (neighbors.up && dist > overscrollDist) {
      overscrollDir = 'up'
      overscrollDist = dist
    }
  } else if (worldY > maxWorldY) {
    clampedY = maxWorldY
    const dist = (worldY - maxWorldY) * s
    if (neighbors.down && dist > overscrollDist) {
      overscrollDir = 'down'
      overscrollDist = dist
    }
  }

  return {
    panX: -clampedX * s,
    panY: -clampedY * s,
    overscroll: overscrollDir ? { direction: overscrollDir, distancePx: overscrollDist } : null,
  }
}
