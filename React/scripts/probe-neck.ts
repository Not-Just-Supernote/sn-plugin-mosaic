// Probe: dump per-wall internals of buildNeckPath around a jump to find the
// mechanism. Run: cd server && npx tsx ../scripts/probe-neck.ts
import { buildNeckPath, getSignedEdgeDistance } from '../src/liquidEffects'

const A = { id: 'a', x: 0, y: 0, width: 320, height: 280 }
// Reproduce the analyzer's "H yTop=-110" sweep: B right of A (above its top),
// stepping right through x = 240+t.
function bAt(step: number) {
  return { id: 'b', x: 240 + step, y: -110, width: 200, height: 130 }
}

for (const step of [121, 123, 125, 127]) {
  const b = bAt(step)
  const path = buildNeckPath(A as any, b as any, 160)
  const gap = getSignedEdgeDistance(A as any, b as any)
  const nums = path ? path.match(/-?\d+\.?\d*/g)!.map(Number) : []
  // Path: M aLow C cpALow cpBLow bLow L bHigh C cpBHigh cpAHigh aHigh
  const pts = []
  for (let i = 0; i + 1 < nums.length; i += 2) pts.push({ x: +nums[i].toFixed(1), y: +nums[i + 1].toFixed(1) })
  console.log(`step=${step} gap=${gap.toFixed(0)}`)
  console.log(`  aLow=${JSON.stringify(pts[0])} cpALow=${JSON.stringify(pts[1])}`)
  console.log(`  cpBLow=${JSON.stringify(pts[2])} bLow=${JSON.stringify(pts[3])}`)
  console.log(`  bHigh=${JSON.stringify(pts[4])} cpBHigh=${JSON.stringify(pts[5])}`)
  console.log(`  cpAHigh=${JSON.stringify(pts[6])} aHigh=${JSON.stringify(pts[7])}`)
}
