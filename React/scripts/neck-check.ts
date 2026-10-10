// Headless structural check for the native-style M-C-L-C-Z connector path.
import { buildNeckPath, type CardRect } from '../src/liquidEffects'

interface Point { x: number; y: number }

const rect = (id: string, x: number, y: number, w: number, h: number): CardRect =>
  ({ id, x, y, width: w, height: h })

function endpoint(token: string): Point | null {
  const nums = token.slice(1).match(/-?\d+(?:\.\d+)?(?:e[+-]?\d+)?/gi)?.map(Number) ?? []
  return nums.length >= 2 ? { x: nums[nums.length - 2], y: nums[nums.length - 1] } : null
}

function boundaryArc(r: CardRect, p: Point): number {
  const x = Math.max(r.x, Math.min(r.x + r.width, p.x))
  const y = Math.max(r.y, Math.min(r.y + r.height, p.y))
  const distances = [
    Math.abs(p.y - r.y),
    Math.abs(p.x - (r.x + r.width)),
    Math.abs(p.y - (r.y + r.height)),
    Math.abs(p.x - r.x),
  ]
  const side = distances.indexOf(Math.min(...distances))
  if (side === 0) return x - r.x
  if (side === 1) return r.width + y - r.y
  if (side === 2) return r.width + r.height + r.x + r.width - x
  return 2 * r.width + r.height + r.y + r.height - y
}

function boundarySpan(r: CardRect, p: Point, q: Point): number {
  const perimeter = 2 * (r.width + r.height)
  const delta = Math.abs(boundaryArc(r, p) - boundaryArc(r, q))
  return Math.min(delta, perimeter - delta)
}

/** Extract A-low, B-low, B-high and A-high from the four-cubic closed path. */
function footprints(d: string, a: CardRect, b: CardRect) {
  const tokens = d.match(/[MCLZ][^MCLZ]*/g) ?? []
  if (tokens.length !== 5 || tokens.map(token => token[0]).join('') !== 'MCCCC') {
    throw new Error(`unexpected path topology: ${tokens.map(token => token[0]).join('')}`)
  }
  const aLow = endpoint(tokens[0])
  const bLow = endpoint(tokens[1])
  const bHigh = endpoint(tokens[2])
  const aHigh = endpoint(tokens[3])
  if (!aLow || !bLow || !bHigh || !aHigh) throw new Error('missing path endpoint')
  return {
    a: boundarySpan(a, aLow, aHigh),
    b: boundarySpan(b, bLow, bHigh),
  }
}

const cases: [string, CardRect, CardRect][] = [
  ['big over small (aligned)',   rect('a', 0, 0, 320, 280), rect('b', 100, 300, 120, 100)],
  ['big over small (left edge)', rect('a', 0, 0, 320, 280), rect('b', 0, 300, 120, 100)],
  ['equal, stacked',             rect('a', 0, 0, 320, 280), rect('b', 0, 300, 320, 280)],
  ['equal, side by side',        rect('a', 0, 0, 320, 280), rect('b', 340, 0, 320, 280)],
  ['offset stack (75% shift)',   rect('a', 0, 0, 320, 280), rect('b', 240, 300, 320, 280)],
  ['pure diagonal',              rect('a', 0, 0, 320, 280), rect('b', 350, 310, 320, 280)],
  ['diagonal, small card',       rect('a', 0, 0, 320, 280), rect('b', 350, 310, 120, 100)],
  ['near-touching stack',        rect('a', 0, 0, 320, 280), rect('b', 0, 285, 320, 280)],
  ['far stack (near snap)',      rect('a', 0, 0, 320, 280), rect('b', 0, 430, 320, 280)],
]

let bad = 0
for (const [name, a, b] of cases) {
  const d = buildNeckPath(a, b, 160)
  if (!d) { console.log(`${name.padEnd(28)} → NULL (no neck)`); bad++; continue }
  let spans: { a: number; b: number }
  try {
    spans = footprints(d, a, b)
  } catch (error) {
    console.log(`${name.padEnd(28)} → ${String(error)}`)
    bad++
    continue
  }

  // Same-edge attachments span at most one edge; corner configurations
  // legitimately wrap two adjacent edges near the shared corner (the LT
  // crescent), so the sanity ceiling is one full adjacent-edge pair.
  const maxA = a.width + a.height
  const maxB = b.width + b.height
  const invalid = spans.a < 12 || spans.b < 12 || spans.a > maxA || spans.b > maxB
  const flag = invalid ? '  ⚠ INVALID' : ''
  console.log(
    `${name.padEnd(28)} → footprint A ${spans.a.toFixed(0).padStart(3)}px, ` +
    `B ${spans.b.toFixed(0).padStart(3)}px${flag}`)
  if (invalid) bad++
  if (/NaN|Infinity/.test(d)) { console.log('   !! non-finite coords'); bad++ }
}
console.log(bad === 0 ? '\nAll cases produced a proportionate neck.' : `\n${bad} problem case(s).`)
