import {
  buildNeckPath,
  getSignedEdgeDistance,
  LIQUID_THRESHOLDS,
  type CardRect,
} from './React/src/liquidEffects';
import { resolveCardSize } from './React/src/cardGeometry';
import type { Card, Connection } from './React/src/types';



const RECONNECT_THRESHOLD = 2;

export type NeckPath = { id: string; path: string };

export type NeckSession = {
  compute(cards: Card[], connections: Connection[]): NeckPath[];
  reset(): void;
};

export function createNeckSession(): NeckSession {
  const prevActivePairKeys = new Set<string>();
  const seenCardIds = new Set<string>();

  return {
    compute(cards, connections) {
      const rects = new Map<string, CardRect>();
      for (const card of cards) {
        const size = resolveCardSize(card);
        rects.set(card.id, {
          id: card.id,
          x: card.x,
          y: card.y,
          width: size.width,
          height: size.height,
        });
      }

      const necks: NeckPath[] = [];
      const nextPairKeys = new Set<string>();
      for (const connection of connections) {
        const a = rects.get(connection.fromCardId);
        const b = rects.get(connection.toCardId);
        if (!a || !b) continue;
        const edgeDist = getSignedEdgeDistance(a, b);
        if (edgeDist > LIQUID_THRESHOLDS.STRETCH_MAX) continue;
        const pairKey = [a.id, b.id].sort().join('|');
        
        if (
          !prevActivePairKeys.has(pairKey)
          && seenCardIds.has(a.id)
          && seenCardIds.has(b.id)
          && edgeDist > RECONNECT_THRESHOLD
        ) {
          continue;
        }
        const path = buildNeckPath(a, b, LIQUID_THRESHOLDS.STRETCH_MAX);
        if (path) {
          necks.push({ id: connection.id, path });
          nextPairKeys.add(pairKey);
        }
      }

      prevActivePairKeys.clear();
      for (const key of nextPairKeys) prevActivePairKeys.add(key);
      for (const card of cards) seenCardIds.add(card.id);
      return necks;
    },
    reset() {
      prevActivePairKeys.clear();
      seenCardIds.clear();
    },
  };
}
