// scan-deadzone-mixed.ts — dead-zone map for UNEQUAL card sizes.
// edgeDist (hypot, LiquidLayer gate) <= 160 but buildNeckPath NULL = dead cell.
import { buildNeckPath, getSignedEdgeDistance, type CardRect } from '../src/liquidEffects'

const pairs: [string, CardRect, (dx: number, dy: number) => CardRect][] = [
  ['A 320x280 / B 500x360', { id: 'A', x: 0, y: 0, width: 320, height: 280 },
    (dx, dy) => ({ id: 'B', x: 320 + dx, y: -360 - dy, width: 500, height: 360 })],
  ['A 320x280 / B 440x280', { id: 'A', x: 0, y: 0, width: 320, height: 280 },
    (dx, dy) => ({ id: 'B', x: 320 + dx, y: -280 - dy, width: 440, height: 280 })],
  ['A 320x280 / B 320x400', { id: 'A', x: 0, y: 0, width: 320, height: 280 },
    (dx, dy) => ({ id: 'B', x: 320 + dx, y: -400 - dy, width: 320, height: 400 })],
]
for (const [label, a, mkB] of pairs) {
  let dead = 0, total = 0
  const cells: string[] = []
  for (let dx = 0; dx <= 170; dx += 10) {
    let row = ''
    for (let dy = 0; dy <= 170; dy += 10) {
      const b = mkB(dx, dy)
      const edge = getSignedEdgeDistance(a, b)
      if (edge > 160) { row += ' ·'; continue }
      total++
      if (buildNeckPath(a, b, 160)) row += ' O'
      else { row += ' X'; dead++ }
    }
    cells.push(`dx=${String(dx).padStart(3)} ${row}`)
  }
  console.log(`\n== ${label} ==  edge<=160: ${total}, DEAD: ${dead}`)
  console.log(cells.join('\n'))
}
