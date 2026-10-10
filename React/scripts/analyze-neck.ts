// analyze-neck.ts — sweep card B around card A in 1px steps and detect
// discontinuities in buildNeckPath output (point jumps + handle-direction flips).
// Run from server/:  npx tsx ../scripts/analyze-neck.ts [gallery]
import { buildNeckPath, getSignedEdgeDistance, type CardRect, type Point } from '../src/liquidEffects'
import { writeFileSync } from 'node:fs'

const KEYS = ['aLow', 'cpALow', 'cpBLow', 'bLow', 'bHigh', 'cpBHigh', 'cpAHigh', 'aHigh'] as const
type NeckPts = Record<(typeof KEYS)[number], Point>

function parseNeck(d: string): NeckPts {
  const nums = d.match(/-?\d+\.?\d*(?:e[+-]?\d+)?/gi)!.map(Number)
  const out = {} as NeckPts
  KEYS.forEach((k, i) => (out[k] = { x: nums[i * 2], y: nums[i * 2 + 1] }))
  return out
}

interface Jump {
  label: string
  step: number
  jump: number
  micro: number // max movement within the same interval re-sampled 50×
  worstKey: string
  dist: number
  flips: string[]
  a: CardRect
  b: CardRect
}

function handleDot(p: NeckPts, lowKey: 'aLow' | 'bLow' | 'aHigh' | 'bHigh', cpKey: keyof NeckPts, otherKey: keyof NeckPts): number {
  const handle = { x: p[cpKey].x - p[lowKey].x, y: p[cpKey].y - p[lowKey].y }
  const chord = { x: p[otherKey].x - p[lowKey].x, y: p[otherKey].y - p[lowKey].y }
  return handle.x * chord.x + handle.y * chord.y
}

/**
 * Re-sample the flagged 1px transition 50× finer. A true discontinuity keeps
 * its magnitude; a steep-but-continuous gradient (LT's 5× transition ramp)
 * shrinks ~50×.
 */
function microJump(mkB: (t: number) => CardRect, a: CardRect, step: number): number {
  let prev: NeckPts | null = null
  let worst = 0
  for (let i = 0; i <= 50; i++) {
    const d = buildNeckPath(a, mkB(step - 1 + i / 50), 160)
    if (!d) { prev = null; continue }
    const pts = parseNeck(d)
    if (prev) {
      for (const k of KEYS) {
        const m = Math.hypot(pts[k].x - prev[k].x, pts[k].y - prev[k].y)
        if (m > worst) worst = m
      }
    }
    prev = pts
  }
  return Math.round(worst * 100) / 100
}

function sweep(label: string, mkB: (t: number) => CardRect, a: CardRect, steps: number, jumps: Jump[]) {
  let prev: NeckPts | null = null
  let prevDots: number[] | null = null
  for (let t = 0; t <= steps; t++) {
    const b = mkB(t)
    const d = buildNeckPath(a, b, 160)
    if (!d) { prev = null; prevDots = null; continue }
    const pts = parseNeck(d)
    const dots = [
      handleDot(pts, 'aLow', 'cpALow', 'aHigh'),
      handleDot(pts, 'aHigh', 'cpAHigh', 'aLow'),
      handleDot(pts, 'bLow', 'cpBLow', 'bHigh'),
      handleDot(pts, 'bHigh', 'cpBHigh', 'bLow'),
    ]
    if (prev && prevDots) {
      let worst = 0, worstKey = ''
      for (const k of KEYS) {
        const m = Math.hypot(pts[k].x - prev[k].x, pts[k].y - prev[k].y)
        if (m > worst) { worst = m; worstKey = k }
      }
      const flips: string[] = []
      const names = ['aLow', 'aHigh', 'bLow', 'bHigh']
      dots.forEach((s, i) => {
        if (s * prevDots![i] < 0) flips.push(names[i])
      })
      if (worst > 4 || flips.length > 0) {
        jumps.push({
          label, step: t, jump: Math.round(worst * 10) / 10, micro: microJump(mkB, a, t),
          worstKey,
          dist: Math.round(getSignedEdgeDistance(a, b) * 10) / 10,
          flips, a, b,
        })
      }
    }
    prev = pts
    prevDots = dots
  }
}

const A: CardRect = { id: 'A', x: 0, y: 0, width: 320, height: 280 }
const jumps: Jump[] = []

// Horizontal sweeps: B (200×130) crosses A's right edge at many heights.
for (let yTop = -110; yTop <= 260; yTop += 10) {
  sweep(`H yTop=${yTop}`, t => ({ id: 'B', x: 240 + t, y: yTop, width: 200, height: 130 }), A, 360, jumps)
}
// Vertical sweeps: B crosses A's bottom edge at many x offsets.
for (let xLeft = -180; xLeft <= 300; xLeft += 10) {
  sweep(`V xLeft=${xLeft}`, t => ({ id: 'B', x: xLeft, y: 200 + t, width: 200, height: 130 }), A, 320, jumps)
}
// Diagonal approach: B moves down-right past A's bottom-right corner.
for (let k = 0; k <= 300; k += 10) {
  sweep(`D off=${k}`, t => ({ id: 'B', x: 240 + t, y: 200 + k * 0.3 + t * 0.5, width: 200, height: 130 }), A, 200, jumps)
}

// A flagged transition is a TRUE discontinuity only if its magnitude survives
// 50× subdivision; otherwise it's a steep-but-continuous ramp traversal
// (LT's min(1, 5t) transition ramp amplifies exit movement near corners).
const trueJumps = jumps.filter(j => j.micro > j.jump * 0.2)
const gradients = jumps.filter(j => j.micro <= j.jump * 0.2)
console.log(`flagged transitions: ${jumps.length} — steep-continuous: ${gradients.length}, TRUE JUMPS: ${trueJumps.length}`)
const grouped = new Map<string, Jump[]>()
for (const j of trueJumps) {
  if (!grouped.has(j.label)) grouped.set(j.label, [])
  grouped.get(j.label)!.push(j)
}
for (const [label, js] of grouped) {
  const worst = js.reduce((x, y) => (x.jump > y.jump ? x : y))
  console.log(
    `${label.padEnd(18)} jumps=${String(js.length).padStart(3)}  worst=${String(worst.jump).padStart(6)}px ` +
    `(micro ${worst.micro}px) at step=${worst.step} (${worst.worstKey}) edgeDist=${worst.dist}` +
    (worst.flips.length ? ` FLIPS:${worst.flips.join(',')}` : ''),
  )
}

// Fixed frames at the worst baseline discontinuities, for before/after comparison.
// (sweep, param, step) recorded from the baseline run on the discrete-branch model.
const COMPARE: { label: string; mkB: (t: number) => CardRect; step: number }[] = [
  { label: 'V xLeft=-10', mkB: t => ({ id: 'B', x: -10, y: 200 + t, width: 200, height: 130 }), step: 93 },
  { label: 'V xLeft=50', mkB: t => ({ id: 'B', x: 50, y: 200 + t, width: 200, height: 130 }), step: 84 },
  { label: 'V xLeft=40', mkB: t => ({ id: 'B', x: 40, y: 200 + t, width: 200, height: 130 }), step: 86 },
  { label: 'H yTop=60', mkB: t => ({ id: 'B', x: 240 + t, y: 60, width: 200, height: 130 }), step: 103 },
  { label: 'H yTop=-40', mkB: t => ({ id: 'B', x: 240 + t, y: -40, width: 200, height: 130 }), step: 110 },
  { label: 'V xLeft=0', mkB: t => ({ id: 'B', x: 0, y: 200 + t, width: 200, height: 130 }), step: 91 },
]

if (process.argv.includes('gallery')) {
  const cells: string[] = []
  COMPARE.forEach((c, gi) => {
    for (const dt of [-2, -1, 0, 1, 2]) {
      const b = c.mkB(c.step + dt)
      const d = buildNeckPath(A, b, 160)
      const ox = (gi % 2) * 5 * 430 + (dt + 2) * 430
      const oy = Math.floor(gi / 2) * 380
      cells.push(`<g transform="translate(${ox}, ${oy + 30})">
        <rect x="${A.x}" y="${A.y}" width="${A.width}" height="${A.height}" rx="8" fill="#dfe9f7" stroke="#99a"/>
        ${d ? `<path d="${d}" fill="#dfe9f7" stroke="#c33" stroke-width="1.5"/>` : '<text x="150" y="140">NULL</text>'}
        <rect x="${b.x}" y="${b.y}" width="${b.width}" height="${b.height}" rx="8" fill="#dfe9f7" stroke="#99a"/>
        <text x="0" y="-8" font-size="14">${c.label} step=${c.step + dt}${dt === 0 ? ' (was worst)' : ''}</text>
      </g>`)
    }
  })
  writeFileSync(
    '../scripts/neck-jump-gallery.svg',
    `<svg xmlns="http://www.w3.org/2000/svg" width="${2 * 5 * 430}" height="${3 * 380}" style="background:#fff">${cells.join('')}</svg>`,
  )
  console.log('gallery → scripts/neck-jump-gallery.svg')
}
