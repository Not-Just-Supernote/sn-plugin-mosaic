// scan-diagonal-deadzone.ts — map (dx, dy) beyond A's top-right corner where
// edgeDist <= 160 (LiquidLayer gate passes) but buildNeckPath returns NULL.
import { buildNeckPath, getSignedEdgeDistance } from '../src/liquidEffects'

const A = { id: 'A', x: 0, y: 0, width: 320, height: 280 }
let dead = 0
let total = 0
for (let dx = 0; dx <= 170; dx += 10) {
  let row = ''
  for (let dy = 0; dy <= 170; dy += 10) {
    const B = { id: 'B', x: 320 + dx, y: -280 - dy, width: 320, height: 280 }
    const edge = getSignedEdgeDistance(A, B)
    if (edge > 160) { row += ' ·'; continue } // both sides skip: far
    total++
    const p = buildNeckPath(A, B, 160)
    if (p) { row += ' O' } else { row += ' X'; dead++ }
  }
  console.log(`dx=${String(dx).padStart(3)} ${row}`)
}
console.log(`\nedge<=160 configs: ${total}, NULL necks: ${dead}`)
console.log('O=renders  X=DEAD (gate passes, path null)  ·=beyond 160')
