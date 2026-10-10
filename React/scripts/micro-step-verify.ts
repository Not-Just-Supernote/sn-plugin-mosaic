// micro-step-verify.ts — re-sample each flagged 1px transition in 1000
// micro-steps. True discontinuity: jump persists at micro scale. Steep but
// continuous gradient: micro jumps shrink ~1000×.
// Run from server/:  npx tsx ../scripts/micro-step-verify.ts
import { buildNeckPath, type CardRect, type Point } from '../src/liquidEffects'

const KEYS = ['aLow', 'cpALow', 'cpBLow', 'bLow', 'bHigh', 'cpBHigh', 'cpAHigh', 'aHigh'] as const
type NeckPts = Record<(typeof KEYS)[number], Point>
function parseNeck(d: string): NeckPts {
  const nums = d.match(/-?\d+\.?\d*(?:e[+-]?\d+)?/gi)!.map(Number)
  const out = {} as NeckPts
  KEYS.forEach((k, i) => (out[k] = { x: nums[i * 2], y: nums[i * 2 + 1] }))
  return out
}

const A: CardRect = { id: 'A', x: 0, y: 0, width: 320, height: 280 }
const CASES: { label: string; mkB: (t: number) => CardRect; step: number }[] = [
  { label: 'H yTop=-40 (cpALow 11.4)', mkB: t => ({ id: 'B', x: 240 + t, y: -40, width: 200, height: 130 }), step: 135 },
  { label: 'H yTop=-20 (cpALow 9.7)', mkB: t => ({ id: 'B', x: 240 + t, y: -20, width: 200, height: 130 }), step: 126 },
  { label: 'H yTop=50 (cpBLow 6.4)', mkB: t => ({ id: 'B', x: 240 + t, y: 50, width: 200, height: 130 }), step: 111 },
  { label: 'H yTop=70 (cpBHigh 5.3)', mkB: t => ({ id: 'B', x: 240 + t, y: 70, width: 200, height: 130 }), step: 109 },
  { label: 'V xLeft=-40 (cpAHigh 5.6)', mkB: t => ({ id: 'B', x: -40, y: 200 + t, width: 200, height: 130 }), step: 150 },
  { label: 'V xLeft=160 (cpALow 5.6)', mkB: t => ({ id: 'B', x: 160, y: 200 + t, width: 200, height: 130 }), step: 150 },
]

const MICRO = 1000
for (const c of CASES) {
  let prev: NeckPts | null = null
  let worst = 0
  let worstKey = ''
  for (let i = 0; i <= MICRO; i++) {
    const t = c.step - 1 + i / MICRO
    const d = buildNeckPath(A, c.mkB(t), 160)
    if (!d) { prev = null; continue }
    const pts = parseNeck(d)
    if (prev) {
      for (const k of KEYS) {
        const m = Math.hypot(pts[k].x - prev[k].x, pts[k].y - prev[k].y)
        if (m > worst) { worst = m; worstKey = k }
      }
    }
    prev = pts
  }
  console.log(`${c.label.padEnd(34)} max micro-step movement (0.001px): ${worst.toFixed(3)}px (${worstKey})`)
}
