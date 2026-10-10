// liquidEffects.ts — Liquid adhesion visual effect computations (pure geometry, zero React)
//
// The connector is a faithful port of LiquidText 2.5.34's rectangular branch
// (LTConnectorView VariantB), recovered by ARM64 decompilation with all
// constants read from the binary (see connector-algorithm-confirmed.md):
//   footprints: center rays along ±perpendicular, intersected with the rect
//               boundary and scaled by shrink = 1 − clamp(gap/80, 0.01, 0.4)
//               (LT: FindEdgesOfRect — pure projection, no edge
//               classification; endpoints always strictly inside the card)
//   anchors:    both walls on one edge → that edge's corner insets (r=15);
//               walls on different edges → each endpoint slides along its edge
//               from the shared corner with a min(1, 5t) ramp, wrapping the
//               rounded corner over an r·π/2 arc (LT: MoveToEndOfEdges +
//               TransitionRegionFunction) — continuous at every mode switch
//   walls:      two cubics whose controls interpolate along their own wall
//               chords, with rounded endpoint caps kept inside the footprint
//               intervals so the filled neck remains a single simple band

import { type Card, type Connection } from './types'
import { resolveCardSize } from './cardGeometry'

// ============================================
// Types
// ============================================

export interface Point { x: number; y: number }

export type LiquidState = 'fused' | 'bridging' | 'stretching' | 'disconnected'

export interface LiquidPairState {
  cardAId: string
  cardBId: string
  state: LiquidState
  edgeDist: number
  overlapArea: number
  tension: number // 0..1 normalized stretch
  color: string
}

export interface Cluster {
  id: string
  cardIds: string[]
  hull: Point[]
  smoothPath: string
  color: string
}

export interface CardRect {
  id: string
  x: number
  y: number
  width: number
  height: number
  color?: string | null
}

// ============================================
// Tunable Constants
// ============================================

export const LIQUID_THRESHOLDS = {
  OVERLAP_THRESHOLD: 20,   // px of overlap to trigger fusion
  BRIDGE_MAX: 120,         // px edge distance where bridge effect activates
  STRETCH_MAX: 160,        // px edge distance where the neck snaps
  CLUSTER_PROXIMITY: 60,   // px edge distance to consider cards clustered
  CLUSTER_MIN_CARDS: 3,    // minimum cards in a cluster
  CLUSTER_PADDING: 24,     // px padding around cluster hull
} as const

// Constants read directly from LiquidText 2.5.34's ARM64 binary literal pools
// (LTConnectorView VariantB). Evidence: connector-algorithm-confirmed.md.
const NECK = {
  // Default corner radius, from BuildQuadrilateralObject (rotation≈0 → 15.0).
  CORNER_RADIUS: 15,
  // Footprint shrink schedule: shrink = 1 − clamp(gap/K3, lo, hi).
  // gap ≤ 0.8px → 0.99 (full edge); gap ≥ 32px → 0.60 (floor).
  FOOTPRINT_DISTANCE: 80,   // K3
  SHRINK_CLAMP_MIN: 0.01,
  SHRINK_CLAMP_MAX: 0.4,
  // Wall control interpolation schedule; the upper bound keeps the cubic
  // controls inside the wall chord even for a large card size mismatch.
  HANDLE_DISTANCE: 100,     // K2
  HANDLE_SCALE_NEAR: 0.25,
  // Corner-transition ramp: min(1, RAMP·t), t = exit position along the edge
  // from the shared corner. Continuous branch switching (TransitionRegion).
  TRANSITION_RAMP: 5,
  INSET: 2,
} as const

// ============================================
// LiquidText rectangular connector geometry (VariantB)
// ============================================

function rectCenter(r: CardRect): Point {
  return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
}

function normalize(v: Point): Point {
  const length = Math.hypot(v.x, v.y)
  return length > 1e-7 ? { x: v.x / length, y: v.y / length } : { x: 0, y: 0 }
}

function sub(a: Point, b: Point): Point {
  return { x: a.x - b.x, y: a.y - b.y }
}

function dist(a: Point, b: Point): number {
  return Math.hypot(a.x - b.x, a.y - b.y)
}

function samePoint(a: Point, b: Point): boolean {
  return Math.abs(a.x - b.x) < 1e-7 && Math.abs(a.y - b.y) < 1e-7
}

// Quad winding from LT's BuildQuadrilateralObject: TL→TR→BR→BL (clockwise in
// y-down screen coordinates). Edge ids: 0 top, 1 right, 2 bottom, 3 left.
type RectEdgeId = 0 | 1 | 2 | 3

function edgeCorners(r: CardRect, edge: RectEdgeId): { start: Point; end: Point } {
  const tl = { x: r.x, y: r.y }
  const tr = { x: r.x + r.width, y: r.y }
  const br = { x: r.x + r.width, y: r.y + r.height }
  const bl = { x: r.x, y: r.y + r.height }
  switch (edge) {
    case 0: return { start: tl, end: tr }
    case 1: return { start: tr, end: br }
    case 2: return { start: br, end: bl }
    case 3: return { start: bl, end: tl }
  }
}

interface WallExit {
  point: Point
  edge: RectEdgeId
  s: number // ray parameter at the exit (distance from `from`, dir is unit)
}

/**
 * First exit of the ray `from + s·dir` (s > 0) through the rectangle, via the
 * slab method, reporting which edge it crosses and the exit parameter.
 * `from` must be strictly inside; `dir` must be unit length. Continuous in
 * both — corner crossings included (an exact corner hit is measure-zero and
 * picks either edge).
 */
function rayExit(r: CardRect, from: Point, dir: Point): WallExit {
  let s = Infinity
  let edge: RectEdgeId = 0
  if (dir.x > 1e-9) {
    const t = (r.x + r.width - from.x) / dir.x
    if (t < s) { s = t; edge = 1 }
  } else if (dir.x < -1e-9) {
    const t = (r.x - from.x) / dir.x
    if (t < s) { s = t; edge = 3 }
  }
  if (dir.y > 1e-9) {
    const t = (r.y + r.height - from.y) / dir.y
    if (t < s) { s = t; edge = 2 }
  } else if (dir.y < -1e-9) {
    const t = (r.y - from.y) / dir.y
    if (t < s) { s = t; edge = 0 }
  }
  return { point: { x: from.x + dir.x * s, y: from.y + dir.y * s }, edge, s }
}

interface WallAnchor {
  point: Point
  dir: Point // unit direction the wall's handle pushes along
}

/**
 * MoveToEndOfEdges + TransitionRegionFunction (LT 0x181a1f0f8 / 0x181a1f770).
 *
 * One wall's attachment on one card. `wrapEnd` fixes which corner this wall
 * wraps and which corner is its own-side home — LT's caller passes each
 * wall's edge in a fixed winding (VariantB call sites, ARM64 decompile):
 * quad A: min wall wraps the edge's END, max wall its START; quad B:
 * min wall wraps START, max wall wraps END. The assignment is independent of
 * the axis direction, so the sameEdge↔corner transition is continuous by
 * construction (the arc's θ=0 position/dir on one edge equals the slide
 * branch's start on the next).
 *
 * - Both walls exit the same edge: the anchor is the home corner's inset by
 *   the corner radius; the handle direction runs across the edge toward the
 *   other wall's anchor (the concave taffy fillet).
 * - Walls on different edges: the anchor slides along this wall's exit edge
 *   from the wrap corner toward its home inset, driven by the exit's
 *   position with LT's min(1, 5t) ramp; exits within the quarter-arc zone
 *   (r·π/2) wrap the endpoint around the rounded corner, rotating the handle
 *   direction from the adjacent edge's tangent to this edge's. Both modes
 *   agree at every switch point, so the silhouette never jumps.
 */
function wallAnchor(r: CardRect, exit: WallExit, sameEdge: boolean, wrapEnd: boolean): WallAnchor {
  const radius = Math.min(NECK.CORNER_RADIUS, r.width / 2, r.height / 2)
  const { start, end } = edgeCorners(r, exit.edge)
  const edgeLen = dist(start, end)

  // own = this wall's home corner (its same-edge inset side); other = the
  // corner the neck wraps around in a corner configuration.
  const own = wrapEnd ? end : start
  const other = wrapEnd ? start : end
  const uOwnInto = normalize(sub(own, other)) // from wrap corner toward home corner

  if (sameEdge) {
    return {
      point: { x: own.x - uOwnInto.x * radius, y: own.y - uOwnInto.y * radius },
      dir: { x: -uOwnInto.x, y: -uOwnInto.y },
    }
  }

  const t = dist(exit.point, other) / edgeLen
  const blend = Math.min(1, NECK.TRANSITION_RAMP * t)
  // Switch directly onto the destination edge. This removes the transient
  // quarter-circle state and lets the neck itself form the rounded transition.
  const d = radius + (edgeLen - 2 * radius) * blend
  return {
    point: { x: other.x + uOwnInto.x * d, y: other.y + uOwnInto.y * d },
    dir: { x: -uOwnInto.x, y: -uOwnInto.y },
  }
}

/** Pull a boundary point slightly inside the card so the seam hides under the card body. */
function insetTowardCenter(r: CardRect, p: Point): Point {
  const center = rectCenter(r)
  const inward = normalize(sub(center, p))
  return { x: p.x + inward.x * NECK.INSET, y: p.y + inward.y * NECK.INSET }
}

// ============================================
// Math helpers
// ============================================

export function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t
}

export function clamp(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v))
}

// ============================================
// Card geometry helpers
// ============================================

export function toCardRect(card: Card): CardRect {
  const size = resolveCardSize(card)
  return {
    id: card.id,
    x: card.x,
    y: card.y,
    width: size.width,
    height: size.height,
    color: card.color,
  }
}

// ============================================
// Edge distance (signed: negative = overlap)
// ============================================

export function getSignedEdgeDistance(a: CardRect, b: CardRect): number {
  const dx = Math.max(0, Math.max(a.x - (b.x + b.width), b.x - (a.x + a.width)))
  const dy = Math.max(0, Math.max(a.y - (b.y + b.height), b.y - (a.y + a.height)))
  if (dx > 0 || dy > 0) return Math.hypot(dx, dy)

  // Overlapping: return negative overlap amount
  const overlapX = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x)
  const overlapY = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y)
  return -Math.min(overlapX, overlapY)
}

export function getOverlapArea(a: CardRect, b: CardRect): number {
  const overlapX = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x)
  const overlapY = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y)
  if (overlapX <= 0 || overlapY <= 0) return 0
  return overlapX * overlapY
}

// ============================================
// Neck path (the single connector between a card pair)
// ============================================

/**
 * Build one closed connector as a faithful port of LiquidText's rectangular
 * branch (LTConnectorView VariantB, constants from the 2.5.34 ARM64 binary):
 *
 *   1. Footprint endpoints: center rays along ±perpendicular of the
 *      inter-center axis, intersected with the rect boundary and scaled by
 *      1 − clamp(gap/80, 0.01, 0.4) (FindEdgesOfRect — pure projection, no
 *      edge classification; endpoints always lie strictly inside the card).
 *   2. Wall exits: each wall line (guide A → guide B) is ray-cast out of both
 *      card bodies; the exits decide per card whether both walls sit on one
 *      edge (wide facing attachment) or wrap a corner (MoveToEndOfEdges).
 *   3. Anchors: same-edge → each wall takes its home-corner inset (r=15);
 *      corner → endpoint slides from
 *      its wrap corner along its edge on a min(1, 5t) ramp, wrapping the
 *      rounded corner over an r·π/2 arc (TransitionRegionFunction). Home/wrap
 *      corners follow LT's caller winding (quad A: min→end, max→start;
 *      quad B: min→start, max→end) — continuous at every switch point.
 *   4. Walls: controls interpolate along the corresponding endpoint chord;
 *      both cards are sorted in the same perpendicular order before the
 *      cubic walls and endpoint caps are emitted.
 */
export function buildNeckPath(a: CardRect, b: CardRect, maxDist: number): string | null {
  const centerA = rectCenter(a)
  const centerB = rectCenter(b)
  const axis = normalize(sub(centerB, centerA))
  if (axis.x === 0 && axis.y === 0) return null
  const perpendicular = { x: -axis.y, y: axis.x }

  // QuadMetric measures the shortest distance between the actual rectangle
  // edges (ARM64 connector report, section 3). The support-axis projection
  // can be zero even with a visible gap when differently sized cards move
  // sideways; using the real gap keeps shrink and curvature consistent.
  const gap = Math.max(0, getSignedEdgeDistance(a, b))
  if (gap > maxDist) return null

  // 1. Footprint endpoints (LT FindEdgesOfRect — pure projection, no edge
  //    classification): the ±perpendicular ray from the center intersects the
  //    rect boundary at ray parameter s; the endpoint sits on that ray at
  //    s·shrink. Always strictly inside the card (shrink < 1), and continuous
  //    in the axis direction because rayExit's parameter is continuous —
  //    corner crossings included.
  const shrink = 1 - clamp(
    gap / NECK.FOOTPRINT_DISTANCE,
    NECK.SHRINK_CLAMP_MIN,
    NECK.SHRINK_CLAMP_MAX,
  )
  const guide = (r: CardRect, center: Point, side: -1 | 1): Point => {
    const dir = { x: perpendicular.x * side, y: perpendicular.y * side }
    const s = rayExit(r, center, dir).s * shrink
    return { x: center.x + dir.x * s, y: center.y + dir.y * s }
  }
  const guideALow = guide(a, centerA, -1)
  const guideAHigh = guide(a, centerA, 1)
  const guideBLow = guide(b, centerB, -1)
  const guideBHigh = guide(b, centerB, 1)

  // 2. Wall exits: cast each wall line out of both card bodies.
  const wallLowDir = normalize(sub(guideBLow, guideALow))
  const wallHighDir = normalize(sub(guideBHigh, guideAHigh))
  if ((wallLowDir.x === 0 && wallLowDir.y === 0) || (wallHighDir.x === 0 && wallHighDir.y === 0)) {
    return null
  }
  const exitALow = rayExit(a, guideALow, wallLowDir)
  const exitBLow = rayExit(b, guideBLow, { x: -wallLowDir.x, y: -wallLowDir.y })
  const exitAHigh = rayExit(a, guideAHigh, wallHighDir)
  const exitBHigh = rayExit(b, guideBHigh, { x: -wallHighDir.x, y: -wallHighDir.y })

  // 3. Anchors per wall per card (MoveToEndOfEdges + TransitionRegion).
  //    Wrap-corner winding from LT's VariantB call sites: on quad A the min
  //    wall wraps its edge's end and the max wall its start; on quad B the
  //    reverse. Continuous across sameEdge↔corner switches by construction.
  const sameEdgeA = exitALow.edge === exitAHigh.edge
  const sameEdgeB = exitBLow.edge === exitBHigh.edge
  const anchorALow = wallAnchor(a, exitALow, sameEdgeA, false)
  const anchorAHigh = wallAnchor(a, exitAHigh, sameEdgeA, true)
  const anchorBLow = wallAnchor(b, exitBLow, sameEdgeB, true)
  const anchorBHigh = wallAnchor(b, exitBHigh, sameEdgeB, false)

  const aLow = insetTowardCenter(a, anchorALow.point)
  const aHigh = insetTowardCenter(a, anchorAHigh.point)
  const bLow = insetTowardCenter(b, anchorBLow.point)
  const bHigh = insetTowardCenter(b, anchorBHigh.point)

  // Corner transitions can swap the two attachment points. Keep both cards
  // in the same perpendicular order so the two walls never cross.
  const orderByPerpendicular = (p: Point, q: Point): [Point, Point] =>
    p.x * perpendicular.x + p.y * perpendicular.y <= q.x * perpendicular.x + q.y * perpendicular.y
      ? [p, q]
      : [q, p]
  const [aLowFinal, aHighFinal] = orderByPerpendicular(aLow, aHigh)
  const [bLowFinal, bHighFinal] = orderByPerpendicular(bLow, bHigh)

  // 4. Walls: interpolate controls along each wall's own chord. Edge-tangent
  // controls can point away from the other card at a corner; once the card
  // body covers the seams that creates a loop or a pinched waist.
  const wallT = lerp(
    NECK.HANDLE_SCALE_NEAR,
    0.42,
    Math.min(1, gap / NECK.HANDLE_DISTANCE),
  )

  const cpALow = { x: lerp(aLowFinal.x, bLowFinal.x, wallT), y: lerp(aLowFinal.y, bLowFinal.y, wallT) }
  const cpBLow = { x: lerp(bLowFinal.x, aLowFinal.x, wallT), y: lerp(bLowFinal.y, aLowFinal.y, wallT) }
  const cpAHigh = { x: lerp(aHighFinal.x, bHighFinal.x, wallT), y: lerp(aHighFinal.y, bHighFinal.y, wallT) }
  const cpBHigh = { x: lerp(bHighFinal.x, aHighFinal.x, wallT), y: lerp(bHighFinal.y, aHighFinal.y, wallT) }

  // Path topology (LT's VariantB): move → cubic wall → softly rounded chord
  // across the second footprint → cubic wall back → close. The small cap keeps
  // the two wall tangents flowing through the card edge instead of making a
  // visible right-angle corner when two cards are closely stacked.
  const chordA = dist(aHighFinal, aLowFinal)
  const chordB = dist(bHighFinal, bLowFinal)
  const wallLowLength = dist(aLowFinal, bLowFinal)
  const wallHighLength = dist(aHighFinal, bHighFinal)
  const wallTotal = Math.max(1e-6, wallLowLength + wallHighLength)
  // At a shallow angle the two walls have visibly different pull lengths.
  // Let the longer pull flatten its side while the shorter pull keeps a
  // fuller arc, matching LiquidText's asymmetric oval transition.
  const lowRoundness = 0.78 + 0.44 * (wallHighLength / wallTotal)
  const highRoundness = 0.78 + 0.44 * (wallLowLength / wallTotal)
  const capLow = Math.min(NECK.CORNER_RADIUS * 1.35 * lowRoundness, chordB * 0.32)
  const capHigh = Math.min(NECK.CORNER_RADIUS * 1.35 * highRoundness, chordB * 0.32)
  // The cap must stay in the interval between its two footprint endpoints.
  // Using the wall tangent here sends both controls away from the interval
  // for a straight side-by-side pair; the visible remainder then becomes a
  // loop or a pinched waist after the card bodies cover the seams.
  const bCapDir = normalize(sub(bHighFinal, bLowFinal))
  const capCpLow = { x: bLowFinal.x + bCapDir.x * capLow, y: bLowFinal.y + bCapDir.y * capLow }
  const capCpHigh = { x: bHighFinal.x - bCapDir.x * capHigh, y: bHighFinal.y - bCapDir.y * capHigh }
  const aCap = Math.min(NECK.CORNER_RADIUS * 1.35, chordA * 0.32)
  const aCapDir = normalize(sub(aLowFinal, aHighFinal))
  const aCapCpHigh = { x: aHighFinal.x + aCapDir.x * aCap, y: aHighFinal.y + aCapDir.y * aCap }
  const aCapCpLow = { x: aLowFinal.x - aCapDir.x * aCap, y: aLowFinal.y - aCapDir.y * aCap }
  return [
    `M ${aLowFinal.x} ${aLowFinal.y}`,
    `C ${cpALow.x} ${cpALow.y}, ${cpBLow.x} ${cpBLow.y}, ${bLowFinal.x} ${bLowFinal.y}`,
    `C ${capCpLow.x} ${capCpLow.y}, ${capCpHigh.x} ${capCpHigh.y}, ${bHighFinal.x} ${bHighFinal.y}`,
    `C ${cpBHigh.x} ${cpBHigh.y}, ${cpAHigh.x} ${cpAHigh.y}, ${aHighFinal.x} ${aHighFinal.y}`,
    `C ${aCapCpHigh.x} ${aCapCpHigh.y}, ${aCapCpLow.x} ${aCapCpLow.y}, ${aLowFinal.x} ${aLowFinal.y}`,
  ].join(' ')
}

// ============================================
// Convex Hull (Andrew's Monotone Chain)
// ============================================

export function convexHull(points: Point[]): Point[] {
  const pts = [...points].sort((a, b) => a.x - b.x || a.y - b.y)
  if (pts.length <= 2) return pts

  const cross = (o: Point, a: Point, b: Point) =>
    (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

  const lower: Point[] = []
  for (const p of pts) {
    while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], p) <= 0)
      lower.pop()
    lower.push(p)
  }

  const upper: Point[] = []
  for (let i = pts.length - 1; i >= 0; i--) {
    const p = pts[i]
    while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], p) <= 0)
      upper.pop()
    upper.push(p)
  }

  return [...lower.slice(0, -1), ...upper.slice(0, -1)]
}

// ============================================
// Smooth path from convex hull (Catmull-Rom -> Cubic Bezier)
// ============================================

export function hullToSmoothPath(hull: Point[]): string {
  if (hull.length < 3) return ''
  const n = hull.length
  let d = `M ${hull[0].x} ${hull[0].y}`

  for (let i = 0; i < n; i++) {
    const p0 = hull[(i - 1 + n) % n]
    const p1 = hull[i]
    const p2 = hull[(i + 1) % n]
    const p3 = hull[(i + 2) % n]

    // Catmull-Rom to cubic bezier control points
    const cp1 = {
      x: p1.x + (p2.x - p0.x) / 6,
      y: p1.y + (p2.y - p0.y) / 6,
    }
    const cp2 = {
      x: p2.x - (p3.x - p1.x) / 6,
      y: p2.y - (p3.y - p1.y) / 6,
    }

    d += ` C ${cp1.x} ${cp1.y}, ${cp2.x} ${cp2.y}, ${p2.x} ${p2.y}`
  }

  return d + ' Z'
}

// ============================================
// Cluster Detection (Union-Find on proximity graph)
// ============================================

export function detectClusters(cards: Record<string, Card>): Cluster[] {
  const cardList = Object.values(cards)
  const n = cardList.length
  if (n < LIQUID_THRESHOLDS.CLUSTER_MIN_CARDS) return []

  const rects = cardList.map(toCardRect)

  // Union-Find
  const parent = new Map<string, string>()

  function find(id: string): string {
    if (!parent.has(id)) parent.set(id, id)
    if (parent.get(id) !== id) parent.set(id, find(parent.get(id)!))
    return parent.get(id)!
  }

  function union(a: string, b: string) {
    const ra = find(a), rb = find(b)
    if (ra !== rb) parent.set(ra, rb)
  }

  // Build adjacency
  for (let i = 0; i < n; i++) {
    for (let j = i + 1; j < n; j++) {
      const dist = getSignedEdgeDistance(rects[i], rects[j])
      if (dist < LIQUID_THRESHOLDS.CLUSTER_PROXIMITY) {
        union(rects[i].id, rects[j].id)
      }
    }
  }

  // Group by root
  const groups = new Map<string, CardRect[]>()
  for (const rect of rects) {
    const root = find(rect.id)
    if (!groups.has(root)) groups.set(root, [])
    groups.get(root)!.push(rect)
  }

  const clusters: Cluster[] = []
  const PAD = LIQUID_THRESHOLDS.CLUSTER_PADDING

  for (const [rootId, clusterCards] of groups) {
    if (clusterCards.length < LIQUID_THRESHOLDS.CLUSTER_MIN_CARDS) continue

    // Padded convex hull of all card corners
    const points: Point[] = []
    for (const card of clusterCards) {
      points.push(
        { x: card.x - PAD, y: card.y - PAD },
        { x: card.x + card.width + PAD, y: card.y - PAD },
        { x: card.x + card.width + PAD, y: card.y + card.height + PAD },
        { x: card.x - PAD, y: card.y + card.height + PAD },
      )
    }

    const hull = convexHull(points)
    const smoothPath = hullToSmoothPath(hull)
    const color = clusterCards[0].color || '#6BA5E7'

    clusters.push({
      id: 'cluster-' + rootId,
      cardIds: clusterCards.map(c => c.id),
      hull,
      smoothPath,
      color,
    })
  }

  return clusters
}

// ============================================
// Batch computation: all liquid pair states
// ============================================

export function computeAllLiquidPairs(
  cards: Record<string, Card>,
  connections: Connection[]
): LiquidPairState[] {
  const pairs: LiquidPairState[] = []

  // Only process connected pairs (no proximity merging for unconnected cards)
  const processedKeys = new Set<string>()

  for (const conn of connections) {
    const pairKey = [conn.fromCardId, conn.toCardId].sort().join('|')
    if (processedKeys.has(pairKey)) continue
    processedKeys.add(pairKey)

    const cardA = cards[conn.fromCardId]
    const cardB = cards[conn.toCardId]
    if (!cardA || !cardB) continue

    const rectA = toCardRect(cardA)
    const rectB = toCardRect(cardB)

    const signedDist = getSignedEdgeDistance(rectA, rectB)

    let state: LiquidState
    if (signedDist < -LIQUID_THRESHOLDS.OVERLAP_THRESHOLD) {
      state = 'fused'
    } else if (signedDist < LIQUID_THRESHOLDS.BRIDGE_MAX) {
      state = 'bridging'
    } else if (signedDist < LIQUID_THRESHOLDS.STRETCH_MAX) {
      state = 'stretching'
    } else {
      state = 'disconnected'
    }

    if (state === 'disconnected') continue

    const overlapArea = getOverlapArea(rectA, rectB)
    const tension = state === 'stretching'
      ? clamp(Math.max(0, signedDist) / LIQUID_THRESHOLDS.STRETCH_MAX, 0, 1)
      : state === 'bridging'
        ? clamp(Math.max(0, signedDist) / LIQUID_THRESHOLDS.BRIDGE_MAX, 0, 1)
        : 0
    const color = conn.color || '#6BA5E7'

    pairs.push({
      cardAId: conn.fromCardId,
      cardBId: conn.toCardId,
      state,
      edgeDist: signedDist,
      overlapArea,
      tension,
      color,
    })
  }

  return pairs
}
