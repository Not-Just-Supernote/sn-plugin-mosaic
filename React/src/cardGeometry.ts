// cardGeometry.ts — Card size resolution (shared with Mosaic)

import type { Card } from './types'
import { SIZE_PRESETS } from './types'

/** Resolve a card's effective pixel size: custom width/height take priority, then sizePreset. */
export function resolveCardSize(card: Card): { width: number; height: number } {
  const preset = SIZE_PRESETS[card.sizePreset] ?? SIZE_PRESETS.default
  if (card.kind === 'note') {
    // Note previews use a fixed readable width; native NoteDocument reports height
    // through the card record as content grows.
    return { width: card.width ?? 480, height: card.height ?? 320 }
  }
  return {
    width: card.width ?? preset.width,
    height: card.height ?? preset.height,
  }
}

/** 文本卡片最小边：手绘矩形建卡（cardFrameRecognizer）落地时的下限。 */
export const MIN_CARD_SIZE = 80
/** 图片卡片最小边 = 文本卡片最小边 × 3，避免图片缩到无法辨认。 */
export const MIN_IMAGE_CARD_SIZE = MIN_CARD_SIZE * 3
export const MAX_CARD_SIZE = 2000

/** 按卡片种类取最小边（图片卡片 240，其余 80）。 */
export function minCardSize(kind: Card['kind'] | undefined): number {
  return kind === 'image' ? MIN_IMAGE_CARD_SIZE : MIN_CARD_SIZE
}

/** Clamp a card size to valid rendering bounds; `kind` selects the lower bound. */
export function clampCardSize(
  width: number,
  height: number,
  kind?: Card['kind'],
): { width: number; height: number } {
  const min = minCardSize(kind)
  return {
    width: Math.round(Math.max(min, Math.min(MAX_CARD_SIZE, width))),
    height: Math.round(Math.max(min, Math.min(MAX_CARD_SIZE, height))),
  }
}
