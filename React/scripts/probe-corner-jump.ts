// probe-corner-jump.ts — dump internals around a detected jump.
// Case: analyze-neck "H yTop=-10" sweep, aLow jumps 154.8px at step=119.
// Run from server/:  npx tsx ../scripts/probe-corner-jump.ts
import { type CardRect } from '../src/liquidEffects'

interface P { x: number; y: number }
const dot = (a: P, b: P) => a.x * b.x + a.y * b.y
const sub = (a: P, b: P) => ({ x: a.x - b.x, y: a.y - b.y })
const dist = (a: P, b: P) => Math.hypot(a.x - b.x, a.y - b.y)
const normalize = (v: P): P => {
  const l = Math.hypot(v.x, v.y)
  return l > 1e-7 ? { x: v.x / l, y: v.y / l } : { x: 0, y: 0 }
}
type EdgeId = 0 | 1 | 2 | 3
const EDGE_NAME = ['top', 'right', 'bottom', 'left']
function edgeCorners(r: CardRect, edge: EdgeId): { start: P; end: P } {
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
function rayExit(r: CardRect, from: P, dir: P): { point: P; edge: EdgeId; s: number } {
  let s = Infinity
  let edge: EdgeId = 0
  if (dir.x > 1e-9) { const t = (r.x + r.width - from.x) / dir.x; if (t < s) { s = t; edge = 1 } }
  else if (dir.x < -1e-9) { const t = (r.x - from.x) / dir.x; if (t < s) { s = t; edge = 3 } }
  if (dir.y > 1e-9) { const t = (r.y + r.height - from.y) / dir.y; if (t < s) { s = t; edge = 2 } }
  else if (dir.y < -1e-9) { const t = (r.y - from.y) / dir.y; if (t < s) { s = t; edge = 0 } }
  return { point: { x: from.x + dir.x * s, y: from.y + dir.y * s }, edge, s }
}

const A: CardRect = { id: 'a', x: 0, y: 0, width: 320, height: 280 }
const NECK = { CORNER_RADIUS: 15, FOOTPRINT_DISTANCE: 80, SHRINK_CLAMP_MIN: 0.01, SHRINK_CLAMP_MAX: 0.4 } as const
const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v))

for (const step of [117, 118, 119, 120, 121]) {
  const b: CardRect = { id: 'b', x: 240 + step, y: -10, width: 200, height: 130 }
  const centerA = { x: A.x + A.width / 2, y: A.y + A.height / 2 }
  const centerB = { x: b.x + b.width / 2, y: b.y + b.height / 2 }
  const axis = normalize(sub(centerB, centerA))
  const perpendicular = { x: -axis.y, y: axis.x }
  const supportRadius = (r: CardRect, dir: P) => Math.abs(dir.x) * r.width / 2 + Math.abs(dir.y) * r.height / 2
  const gap = Math.max(0, dot(sub(centerB, centerA), axis) - supportRadius(A, axis) - supportRadius(b, axis))
  const shrink = 1 - clamp(gap / NECK.FOOTPRINT_DISTANCE, NECK.SHRINK_CLAMP_MIN, NECK.SHRINK_CLAMP_MAX)
  const guide = (r: CardRect, center: P, side: -1 | 1): P => {
    const dir = { x: perpendicular.x * side, y: perpendicular.y * side }
    const s = rayExit(r, center, dir).s * shrink
    return { x: center.x + dir.x * s, y: center.y + dir.y * s }
  }
  const gAL = guide(A, centerA, -1), gAH = guide(A, centerA, 1)
  const gBL = guide(b, centerB, -1), gBH = guide(b, centerB, 1)
  const wL = normalize(sub(gBL, gAL)), wH = normalize(sub(gBH, gAH))
  const eAL = rayExit(A, gAL, wL), eAH = rayExit(A, gAH, wH)
  const eBL = rayExit(b, gBL, { x: -wL.x, y: -wL.y }), eBH = rayExit(b, gBH, { x: -wH.x, y: -wH.y })
  const f = (p: P) => `(${p.x.toFixed(1)},${p.y.toFixed(1)})`
  console.log(`step=${step} gap=${gap.toFixed(1)} shrink=${shrink.toFixed(2)} axis=(${axis.x.toFixed(3)},${axis.y.toFixed(3)})`)
  console.log(`  A: exitLow=${EDGE_NAME[eAL.edge]}${f(eAL.point)}  exitHigh=${EDGE_NAME[eAH.edge]}${f(eAH.point)}  sameEdge=${eAL.edge === eAH.edge}`)
  console.log(`  B: exitLow=${EDGE_NAME[eBL.edge]}${f(eBL.point)}  exitHigh=${EDGE_NAME[eBH.edge]}${f(eBH.point)}  sameEdge=${eBL.edge === eBH.edge}`)
  // own/other assignment for the low wall on A
  const sideLow = { x: -perpendicular.x, y: -perpendicular.y }
  for (const [label, ex] of [['low', eAL], ['high', eAH]] as const) {
    const { start, end } = edgeCorners(A, ex.edge)
    const sd = label === 'low' ? sideLow : perpendicular
    const own = dot(start, sd) >= dot(end, sd) ? 'start' : 'end'
    const t = own === 'start' ? dist(ex.point, end) / dist(start, end) : dist(ex.point, start) / dist(start, end)
    console.log(`    A ${label}: edge=${EDGE_NAME[ex.edge]} own=${own} t=${t.toFixed(3)}`)
  }
}
