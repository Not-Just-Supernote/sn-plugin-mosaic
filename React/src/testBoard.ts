// testBoard.ts — Sparse test canvas with scattered card clusters for development

import type { CanvasData, Card } from './types'
import { SIZE_PRESETS } from './types'

export const SPARSE_TEST_CANVAS_ID = 'canvas-sparse-test'
export const SPARSE_TEST_CANVAS_NAME = 'Sparse Test'

function makeCard(id: string, content: string, x: number, y: number, now: string, zIndex = 0): Card {
  return {
    id,
    content,
    x,
    y,
    width: null,
    height: null,
    color: null,
    bgColor: null,
    textColor: null,
    sizePreset: 'default',
    tags: [],
    zIndex,
    sourceType: 'manual',
    createdAt: now,
  }
}

/** Create a canvas with several card clusters spread far apart for testing sparse navigation. */
export function createSparseTestCanvasData(now: string): CanvasData {
  const gap = SIZE_PRESETS.default.width + 30
  const cards: Record<string, Card> = {}

  // Cluster 1: origin
  const c1 = [
    makeCard('st-1a', 'Cluster 1 — Card A', 60, 60, now, 1),
    makeCard('st-1b', 'Cluster 1 — Card B', 60 + gap, 60, now, 2),
    makeCard('st-1c', 'Cluster 1 — Card C', 60, 60 + gap, now, 3),
  ]

  // Cluster 2: far right
  const c2 = [
    makeCard('st-2a', 'Cluster 2 — Card A', 3000, 60, now, 4),
    makeCard('st-2b', 'Cluster 2 — Card B', 3000 + gap, 60, now, 5),
  ]

  // Cluster 3: far below
  const c3 = [
    makeCard('st-3a', 'Cluster 3 — Card A', 60, 3000, now, 6),
    makeCard('st-3b', 'Cluster 3 — Card B', 60 + gap, 3000, now, 7),
  ]

  // Cluster 4: diagonal
  const c4 = [
    makeCard('st-4a', 'Cluster 4 — Card A', 5000, 5000, now, 8),
  ]

  for (const card of [...c1, ...c2, ...c3, ...c4]) {
    cards[card.id] = card
  }

  return {
    cards,
    connections: [
      { id: 'st-conn-1', fromCardId: 'st-1a', toCardId: 'st-1b', color: '#6BA5E7', label: '' },
      { id: 'st-conn-2', fromCardId: 'st-2a', toCardId: 'st-2b', color: '#6BA5E7', label: '' },
    ],
    viewport: { panX: 0, panY: 0, scale: 1 },
  }
}
