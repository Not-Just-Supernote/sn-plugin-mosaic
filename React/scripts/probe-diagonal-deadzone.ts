// probe-diagonal-deadzone.ts — diagonal near configs: edgeDist vs support-frame gap.
import { buildNeckPath, getSignedEdgeDistance } from '../src/liquidEffects'

const A = { id: 'A', x: 0, y: 300, width: 320, height: 280 }
// B up-right of A, varying vertical offset around the "dead" configuration
for (let dy = -80; dy <= 120; dy += 20) {
  const B = { id: 'B', x: 380, y: 300 - 280 - 60 + dy, width: 320, height: 280 }
  const edge = getSignedEdgeDistance(A, B)
  const p = buildNeckPath(A, B, 160)
  console.log(`dy=${String(dy).padStart(4)}  edgeDist=${edge.toFixed(0).padStart(5)}  neck=${p ? 'OK' : 'NULL'}`)
}
