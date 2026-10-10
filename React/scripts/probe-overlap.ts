// probe-overlap.ts — does buildNeckPath handle overlapping cards (negative gap)?
import { buildNeckPath, getSignedEdgeDistance } from '../src/liquidEffects'

const A = { id: 'A', x: 0, y: 165, width: 625, height: 340 }
const cases: [string, typeof A][] = [
  ['screenshot-ish overlap', { id: 'B', x: 455, y: 0, width: 710, height: 335 }],
  ['smaller overlap', { id: 'B', x: 500, y: 30, width: 710, height: 335 }],
  ['touching', { id: 'B', x: 630, y: -10, width: 710, height: 335 }],
  ['small gap', { id: 'B', x: 640, y: -20, width: 710, height: 335 }],
  ['sized like presets', { id: 'B', x: 250, y: 100, width: 320, height: 280 }],
]
for (const [label, b] of cases) {
  const dist = getSignedEdgeDistance(A, b)
  const p = buildNeckPath(A, b, 160)
  console.log(`${label.padEnd(24)} edgeDist=${dist.toFixed(1).padStart(8)}  path=${p ? 'OK len=' + p.length : 'NULL'}`)
}
