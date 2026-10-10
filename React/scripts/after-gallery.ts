// after-gallery.ts — render the new buildNeckPath for structural-check cases.
// Run from server/:  npx tsx ../scripts/after-gallery.ts
import { buildNeckPath } from '../src/liquidEffects'
import { writeFileSync } from 'node:fs'

interface R { x: number; y: number; width: number; height: number }
const card = (r: R) => `<rect x="${r.x}" y="${r.y}" width="${r.width}" height="${r.height}" rx="12" fill="#ffffff" stroke="#8899aa" stroke-width="1"/>`
const cases: [string, R, R][] = [
  ['big over small (aligned)', { x: 0, y: 0, width: 320, height: 280 }, { x: 100, y: 300, width: 120, height: 100 }],
  ['pure diagonal', { x: 0, y: 0, width: 320, height: 280 }, { x: 350, y: 310, width: 320, height: 280 }],
  ['diagonal small', { x: 0, y: 0, width: 320, height: 280 }, { x: 350, y: 310, width: 120, height: 100 }],
  ['stacked', { x: 0, y: 0, width: 320, height: 280 }, { x: 0, y: 300, width: 320, height: 280 }],
  ['side by side', { x: 0, y: 0, width: 320, height: 280 }, { x: 340, y: 0, width: 320, height: 280 }],
  ['offset 75pct', { x: 0, y: 0, width: 320, height: 280 }, { x: 240, y: 300, width: 320, height: 280 }],
]
let cells = ''
cases.forEach(([label, a, b], i) => {
  const d = buildNeckPath(a as any, b as any, 160)
  const ox = (i % 3) * 640 + 20
  const oy = Math.floor(i / 3) * 500 + 40
  cells += `<g transform="translate(${ox},${oy})">
    <text x="0" y="-16" font-size="16" font-family="sans-serif">${label}</text>
    ${d ? `<path d="${d}" fill="#ffffff" stroke="#c33" stroke-width="1.5"/>` : '<text x="100" y="140" font-size="14">NULL</text>'}
    ${card(a)}${card(b)}
  </g>`
})
writeFileSync(
  '../scripts/after-gallery.svg',
  `<svg xmlns="http://www.w3.org/2000/svg" width="1920" height="1000" style="background:#b9c8de">${cells}</svg>`,
)
console.log('wrote scripts/after-gallery.svg')
