// LiquidLayer.tsx — LiquidText-style card bodies + explicit liquid neck connectors.
//
// Architectural model: the SVG layer renders the visual BODY of every card.
// DOM cards above are content-only (transparent background).
//
// NO gooey/metaball filter. Cards are crisp rounded rects; each connected pair
// gets ONE explicit concave-bezier neck path (buildNeckPath). A per-component
// drop-shadow filter shades the union silhouette (cards + necks together), so
// the neck/card seam never shows an interior shadow line.
import React, { useMemo, useRef, useEffect } from 'react'
import type { Card, Connection } from './types'
import {
  computeAllLiquidPairs,
  toCardRect,
  LIQUID_THRESHOLDS,
  buildNeckPath,
  type LiquidPairState,
  type Cluster,
} from './liquidEffects'

// LiquidText cards are planar paper panels.  The screenshot silhouette has
// effectively square outer corners; the one-pixel radius only keeps the SVG
// edge antialiased on fractional zoom levels.
const CARD_RADIUS = 1

// Hysteresis: once adhesion breaks, cards must come this close to re-engage.
// User requirement: only reconnect when actually touching/overlapping.
// The native bridge settles cards 18px apart. Reconnection therefore uses the
// same 40px gate as card snapping; a 2px gate would leave a settled pair
// visually detached after it had once been stretched beyond the neck range.
const RECONNECT_THRESHOLD = 40

// Soft drop shadow applied per connected-component group. Because the filter
// wraps the whole group, the shadow follows the union silhouette and interior
// card/neck overlaps stay invisible. SourceGraphic itself is NOT blurred.
const ShadowFilter: React.FC = React.memo(() => (
  <defs>
    <filter id="liquid-card-shadow" x="-16%" y="-16%" width="132%" height="132%"
            colorInterpolationFilters="sRGB">
      <feDropShadow dx="0" dy="3" stdDeviation="15" floodColor="rgba(60,65,70,0.19)" />
    </filter>
  </defs>
))

// ============================================
// Connected components (Union-Find on the connections graph)
// ============================================

/**
 * Partition all cards into connected components via the connections graph.
 * Each component becomes one shadow group so its union silhouette (cards +
 * necks) casts a single coherent shadow. Isolated cards form singleton
 * components and render through the same pipeline for visual consistency.
 */
function getConnectedComponents(
  cards: Record<string, Card>,
  connections: Connection[]
): string[][] {
  const cardIds = Object.keys(cards)
  if (cardIds.length === 0) return []

  const parent = new Map<string, string>()
  for (const id of cardIds) parent.set(id, id)

  const find = (x: string): string => {
    let root = x
    while (parent.get(root) !== root) root = parent.get(root)!
    // Path compression
    let cur = x
    while (parent.get(cur) !== root) {
      const next = parent.get(cur)!
      parent.set(cur, root)
      cur = next
    }
    return root
  }

  const union = (a: string, b: string) => {
    const ra = find(a)
    const rb = find(b)
    if (ra !== rb) parent.set(ra, rb)
  }

  for (const c of connections) {
    if (cards[c.fromCardId] && cards[c.toCardId]) {
      union(c.fromCardId, c.toCardId)
    }
  }

  const groups = new Map<string, string[]>()
  for (const id of cardIds) {
    const root = find(id)
    if (!groups.has(root)) groups.set(root, [])
    groups.get(root)!.push(id)
  }
  return Array.from(groups.values())
}

interface NeckShape {
  path: string
  fill: string
}

// ============================================
// Background Layer (rendered in the connections SVG, behind real cards)
// ============================================

export const LiquidBackground: React.FC<{
  cards: Record<string, Card>
  connections: Connection[]
  scale: number
  clusters: Cluster[]
  selectedCardIds?: string[]
}> = React.memo(({ cards, connections, selectedCardIds }) => {
  const pairs = useMemo(
    () => computeAllLiquidPairs(cards, connections),
    [cards, connections]
  )

  // Hysteresis state: which pairs were adhering in the previous render, and
  // which cards we had already seen (a pair is only subject to the "must touch
  // to re-adhere" rule once we've watched both of its cards on screen).
  const prevActiveKeysRef = useRef<Set<string>>(new Set())
  const prevCardIdsRef = useRef<Set<string>>(new Set())

  // Apply hysteresis: once a user pulls a pair apart, it must come back to
  // touching before it re-adheres. Crucially this only applies to pairs we have
  // actually WATCHED separate — a pair is "known" only if BOTH its cards were
  // present in the previous render.
  //
  // Scoping it that way is what makes load-time adhesion work. Cards arrive
  // asynchronously (canvas read from localStorage, import, canvas switch), so
  // the first render with content always sees pairs it has never seen before.
  // Treating those as "new pairs that must touch" left every restored canvas
  // with zero necks until the user manually nudged cards together.
  const activePairs: LiquidPairState[] = useMemo(() => {
    const prevKeys = prevActiveKeysRef.current
    const prevCardIds = prevCardIdsRef.current

    return pairs.filter(p => {
      if (p.state === 'disconnected') return false
      const key = [p.cardAId, p.cardBId].sort().join('|')
      if (prevKeys.has(key)) return true          // already adhering — keep it

      // Unseen card on either side (first paint, load, import, canvas switch):
      // adhere per natural distance rather than demanding contact.
      const known = prevCardIds.has(p.cardAId) && prevCardIds.has(p.cardBId)
      if (!known) return true

      return p.edgeDist <= RECONNECT_THRESHOLD    // watched it break — must touch
    })
  }, [pairs])

  // Sync hysteresis refs after render
  useEffect(() => {
    const newKeys = new Set<string>()
    for (const p of activePairs) {
      newKeys.add([p.cardAId, p.cardBId].sort().join('|'))
    }
    prevActiveKeysRef.current = newKeys
    prevCardIdsRef.current = new Set(Object.keys(cards))
  }, [activePairs, cards])

  // Connected components (each becomes one shadow group)
  const components = useMemo(
    () => getConnectedComponents(cards, connections),
    [cards, connections]
  )

  // Card id → component index (for routing neck paths)
  const cardToComponentIdx = useMemo(() => {
    const map = new Map<string, number>()
    components.forEach((ids, idx) => {
      for (const id of ids) map.set(id, idx)
    })
    return map
  }, [components])

  // Neck paths grouped by component index
  const necksPerComponent = useMemo<NeckShape[][]>(() => {
    const result: NeckShape[][] = components.map(() => [])
    const MAX_GAP = LIQUID_THRESHOLDS.STRETCH_MAX

    for (const pair of activePairs) {
      const idx = cardToComponentIdx.get(pair.cardAId)
      if (idx === undefined) continue
      const cardA = cards[pair.cardAId]
      const cardB = cards[pair.cardBId]
      if (!cardA || !cardB) continue
      const path = buildNeckPath(toCardRect(cardA), toCardRect(cardB), MAX_GAP)
      if (!path) continue
      // Use card A's color for the neck fill (rare to mix colors via connection)
      result[idx].push({ path, fill: '#FFFFFF' })
    }
    return result
  }, [activePairs, components, cardToComponentIdx, cards])

  if (components.length === 0) return null

  const selectedSet = new Set(selectedCardIds || [])

  return (
    <g className="liquid-layer liquid-background">
      <ShadowFilter />
      {components.map((cardIds, idx) => (
        <g key={`comp-${idx}`} filter="url(#liquid-card-shadow)">
          {/* Liquid necks — drawn first so card bodies cover the inset seam */}
          {necksPerComponent[idx].map((n, i) => (
            <path key={`n-${i}`} d={n.path} fill={n.fill} />
          ))}
          {/* Card bodies — crisp rounded rects, NOT filtered/blurred */}
          {cardIds.map(id => {
            const card = cards[id]
            if (!card) return null
            const rect = toCardRect(card)
            return (
              <rect
                key={`cs-${id}`}
                x={rect.x}
                y={rect.y}
                width={rect.width}
                height={rect.height}
                rx={CARD_RADIUS}
                ry={CARD_RADIUS}
                fill={card.bgColor || '#FFFFFF'}
              />
            )
          })}
        </g>
      ))}

      {/* Selection ring overlay — sharp, outside any filter */}
      {selectedSet.size > 0 && (
        <g className="liquid-selection-overlay">
          {Array.from(selectedSet).map(id => {
            const card = cards[id]
            if (!card) return null
            const rect = toCardRect(card)
            const PAD = 3
            return (
              <rect
                key={`sel-${id}`}
                x={rect.x - PAD}
                y={rect.y - PAD}
                width={rect.width + PAD * 2}
                height={rect.height + PAD * 2}
                rx={CARD_RADIUS + PAD}
                ry={CARD_RADIUS + PAD}
                fill="none"
                stroke="var(--accent-primary)"
                strokeWidth={2}
                strokeOpacity={0.85}
                pointerEvents="none"
              />
            )
          })}
        </g>
      )}
    </g>
  )
})

// ============================================
// Overlay (unused — kept for API compatibility)
// ============================================

export const LiquidOverlay: React.FC<{
  cards: Record<string, Card>
  connections: Connection[]
  scale: number
}> = React.memo(() => null)

// ============================================
// Utility
// ============================================

export function getLiquidPairKeys(
  cards: Record<string, Card>,
  connections: Connection[]
): Set<string> {
  const pairs = computeAllLiquidPairs(cards, connections)
  const keys = new Set<string>()
  for (const p of pairs) {
    if (p.state !== 'disconnected') {
      keys.add([p.cardAId, p.cardBId].sort().join('|'))
    }
  }
  return keys
}

export { computeAllLiquidPairs }
