// diagonal-neck-preview.ts — side-by-side SVG: current buildNeckPath vs a
// pure-projection footprint variant (LT FindEdgesOfRect: endpoints = center ±
// shrink·s·perp with s = actual ray-exit distance, no corner snap).
// Run from server/:  npx tsx ../scripts/diagonal-neck-preview.ts
import { buildNeckPath } from '../src/liquidEffects'
import { writeFileSync } from 'node:fs'

// ---- copied internals from liquidEffects.ts (kept in sync manually) ----
interface P { x: number; y: number }
interface R { id: string; x: number; y: number; width: number; height: number }

const NECK = {
  CORNER_RADIUS: 15,
  FOOTPRINT_DISTANCE: 80,
  SHRINK_CLAMP_MIN: 0.01,
  SHRINK_CLAMP_MAX: 0.4,
  HANDLE_DISTANCE: 100,
  HANDLE_SCALE_NEAR: 0.25,
  HANDLE_SCALE_FAR: 1,
  TRANSITION_RAMP: 5,
  INSET: 2,
  MIN_GAP: 2,
} as const

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v))
const lerp = (a: number, b: number, t: number) => a + (b - a) * t
const dot = (a: P, b: P) => a.x * b.x + a.y * b.y
const sub = (a: P, b: P) => ({ x: a.x - b.x, y: a.y - b.y })
const dist = (a: P, b: P) => Math.hypot(a.x - b.x, a.y - b.y)
const normalize = (v: P): P => {
  const l = Math.hypot(v.x, v.y)
  return l > 1e-7 ? { x: v.x / l, y: v.y / l } : { x: 0, y: 0 }
}
const samePoint = (a: P, b: P) => Math.abs(a.x - b.x) < 1e-7 && Math.abs(a.y - b.y) < 1e-7
const rectCenter = (r: R): P => ({ x: r.x + r.width / 2, y: r.y + r.height / 2 })

type EdgeId = 0 | 1 | 2 | 3
function edgeCorners(r: R, edge: EdgeId): { start: P; end: P } {
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

/** rayExit extended: also returns the ray parameter s (LT's FindEdgesOfRect t). */
function rayExitT(r: R, from: P, dir: P): { point: P; edge: EdgeId; s: number } {
  let s = Infinity
  let edge: EdgeId = 0
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

function adjacentEdgeDir(r: R, edge: EdgeId, shared: P): P {
  for (const e of [0, 1, 2, 3] as EdgeId[]) {
    if (e === edge) continue
    const { start, end } = edgeCorners(r, e)
    if (samePoint(start, shared)) return normalize(sub(end, start))
    if (samePoint(end, shared)) return normalize(sub(start, end))
  }
  return { x: 0, y: 0 }
}

function wallAnchor(r: R, exit: { point: P; edge: EdgeId }, sameEdge: boolean, sideDir: P) {
  const radius = Math.min(NECK.CORNER_RADIUS, r.width / 2, r.height / 2)
  const arcLen = radius * Math.PI / 2
  const { start, end } = edgeCorners(r, exit.edge)
  const edgeLen = dist(start, end)
  const own = dot(start, sideDir) >= dot(end, sideDir) ? start : end
  const other = own === start ? end : start
  const uOwnInto = normalize(sub(own, other))
  if (sameEdge) {
    return {
      point: { x: own.x - uOwnInto.x * radius, y: own.y - uOwnInto.y * radius },
      dir: { x: -uOwnInto.x, y: -uOwnInto.y },
    }
  }
  const t = dist(exit.point, other) / edgeLen
  const blend = Math.min(1, NECK.TRANSITION_RAMP * t)
  const slide = (edgeLen + arcLen - 2 * radius) * blend
  if (slide >= arcLen) {
    const d = Math.min(slide - arcLen + radius, edgeLen - radius)
    return {
      point: { x: other.x + uOwnInto.x * d, y: other.y + uOwnInto.y * d },
      dir: { x: -uOwnInto.x, y: -uOwnInto.y },
    }
  }
  const theta = slide / arcLen
  const uAdjInto = adjacentEdgeDir(r, exit.edge, other)
  const center = {
    x: other.x + radius * (uOwnInto.x + uAdjInto.x),
    y: other.y + radius * (uOwnInto.y + uAdjInto.y),
  }
  const cos = Math.cos(theta * Math.PI / 2)
  const sin = Math.sin(theta * Math.PI / 2)
  const point = {
    x: center.x - radius * (uOwnInto.x * cos + uAdjInto.x * sin),
    y: center.y - radius * (uOwnInto.y * cos + uAdjInto.y * sin),
  }
  const dir = normalize({
    x: uAdjInto.x * (1 - theta) - uOwnInto.x * theta,
    y: uAdjInto.y * (1 - theta) - uOwnInto.y * theta,
  })
  return { point, dir }
}

function insetTowardCenter(r: R, p: P): P {
  const c = rectCenter(r)
  const inward = normalize(sub(c, p))
  return { x: p.x + inward.x * NECK.INSET, y: p.y + inward.y * NECK.INSET }
}

// ---- pure-projection variant of buildNeckPath (only the footprint differs) ----
function buildNeckPathPure(a: R, b: R, maxDist: number): string | null {
  const centerA = rectCenter(a)
  const centerB = rectCenter(b)
  const axis = normalize(sub(centerB, centerA))
  if (axis.x === 0 && axis.y === 0) return null
  const perpendicular = { x: -axis.y, y: axis.x }
  const supportRadius = (r: R, dir: P) => Math.abs(dir.x) * r.width / 2 + Math.abs(dir.y) * r.height / 2
  const gap = Math.max(0, dot(sub(centerB, centerA), axis) - supportRadius(a, axis) - supportRadius(b, axis))
  if (gap <= NECK.MIN_GAP || gap > maxDist) return null

  const shrink = 1 - clamp(gap / NECK.FOOTPRINT_DISTANCE, NECK.SHRINK_CLAMP_MIN, NECK.SHRINK_CLAMP_MAX)

  // FindEdgesOfRect: footprint endpoint = center + dir · (ray-exit distance) · shrink. No snap.
  const guide = (r: R, center: P, side: -1 | 1): P => {
    const dir = { x: perpendicular.x * side, y: perpendicular.y * side }
    const s = rayExitT(r, center, dir).s
    return { x: center.x + dir.x * s * shrink, y: center.y + dir.y * s * shrink }
  }
  const guideALow = guide(a, centerA, -1)
  const guideAHigh = guide(a, centerA, 1)
  const guideBLow = guide(b, centerB, -1)
  const guideBHigh = guide(b, centerB, 1)

  const wallLowDir = normalize(sub(guideBLow, guideALow))
  const wallHighDir = normalize(sub(guideBHigh, guideAHigh))
  if ((wallLowDir.x === 0 && wallLowDir.y === 0) || (wallHighDir.x === 0 && wallHighDir.y === 0)) return null
  const exitALow = rayExitT(a, guideALow, wallLowDir)
  const exitBLow = rayExitT(b, guideBLow, { x: -wallLowDir.x, y: -wallLowDir.y })
  const exitAHigh = rayExitT(a, guideAHigh, wallHighDir)
  const exitBHigh = rayExitT(b, guideBHigh, { x: -wallHighDir.x, y: -wallHighDir.y })

  const sideLow = { x: -perpendicular.x, y: -perpendicular.y }
  const sameEdgeA = exitALow.edge === exitAHigh.edge
  const sameEdgeB = exitBLow.edge === exitBHigh.edge
  const anchorALow = wallAnchor(a, exitALow, sameEdgeA, sideLow)
  const anchorAHigh = wallAnchor(a, exitAHigh, sameEdgeA, perpendicular)
  const anchorBLow = wallAnchor(b, exitBLow, sameEdgeB, sideLow)
  const anchorBHigh = wallAnchor(b, exitBHigh, sameEdgeB, perpendicular)

  const aLow = insetTowardCenter(a, anchorALow.point)
  const aHigh = insetTowardCenter(a, anchorAHigh.point)
  const bLow = insetTowardCenter(b, anchorBLow.point)
  const bHigh = insetTowardCenter(b, anchorBHigh.point)

  const chordA = dist(aHigh, aLow)
  const chordB = dist(bHigh, bLow)
  const handle = lerp(NECK.HANDLE_SCALE_NEAR, NECK.HANDLE_SCALE_FAR, Math.min(1, gap / NECK.HANDLE_DISTANCE)) * (chordA + chordB) / 4

  const cpALow = { x: aLow.x + anchorALow.dir.x * handle, y: aLow.y + anchorALow.dir.y * handle }
  const cpBLow = { x: bLow.x + anchorBLow.dir.x * handle, y: bLow.y + anchorBLow.dir.y * handle }
  const cpAHigh = { x: aHigh.x + anchorAHigh.dir.x * handle, y: aHigh.y + anchorAHigh.dir.y * handle }
  const cpBHigh = { x: bHigh.x + anchorBHigh.dir.x * handle, y: bHigh.y + anchorBHigh.dir.y * handle }

  return [
    `M ${aLow.x} ${aLow.y}`,
    `C ${cpALow.x} ${cpALow.y}, ${cpBLow.x} ${cpBLow.y}, ${bLow.x} ${bLow.y}`,
    `L ${bHigh.x} ${bHigh.y}`,
    `C ${cpBHigh.x} ${cpBHigh.y}, ${cpAHigh.x} ${cpAHigh.y}, ${aHigh.x} ${aHigh.y}`,
    'Z',
  ].join(' ')
}

// ---- frames: (label, A, B, viewBox of the neck region) ----
const frames: { label: string; a: R; b: R; vb: string }[] = [
  { label: 'diag-g42', a: { id: 'a', x: 0, y: 0, width: 320, height: 280 }, b: { id: 'b', x: 350, y: 310, width: 320, height: 280 }, vb: '140 140 660 560' },
  { label: 'diag-g71', a: { id: 'a', x: 0, y: 0, width: 320, height: 280 }, b: { id: 'b', x: 370, y: 330, width: 320, height: 280 }, vb: '120 100 740 660' },
  { label: 'diag-wide', a: { id: 'a', x: 0, y: 0, width: 320, height: 280 }, b: { id: 'b', x: 420, y: 380, width: 320, height: 280 }, vb: '80 60 900 800' },
  { label: 'diag-shallow', a: { id: 'a', x: 0, y: 0, width: 320, height: 280 }, b: { id: 'b', x: 430, y: 250, width: 320, height: 280 }, vb: '160 60 820 660' },
  { label: 'vertical-ref', a: { id: 'a', x: 0, y: 0, width: 320, height: 280 }, b: { id: 'b', x: 40, y: 320, width: 320, height: 280 }, vb: '-40 140 480 520' },
  { label: 'horizontal-ref', a: { id: 'a', x: 0, y: 0, width: 320, height: 280 }, b: { id: 'b', x: 360, y: -40, width: 320, height: 280 }, vb: '160 -140 660 560' },
]

const card = (r: R) => `<rect x="${r.x}" y="${r.y}" width="${r.width}" height="${r.height}" rx="12" fill="#ffffff" stroke="#8899aa" stroke-width="1"/>`
for (const f of frames) {
  const cur = buildNeckPath(f.a as any, f.b as any, 160)
  const pure = buildNeckPathPure(f.a, f.b, 160)
  writeFileSync(
    `../scripts/diag-${f.label}.svg`,
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${f.vb}" width="1320" style="background:#b9c8de">
      <g>${cur ? `<path d="${cur}" fill="#ffffff" stroke="#c33" stroke-width="2"/>` : ''}${card(f.a)}${card(f.b)}</g>
      <g transform="translate(660, 0)">${pure ? `<path d="${pure}" fill="#ffffff" stroke="#2a7" stroke-width="2"/>` : ''}${card(f.a)}${card(f.b)}</g>
    </svg>`,
  )
}
console.log('wrote scripts/diag-*.svg')
