import type { Card, CardAnchor, ConversationBlock } from '../types'
import { resolveCardSize } from '../cardGeometry'

export const CONVERSATION_CARD_WIDTH = 680
export const CONVERSATION_BRANCH_WIDTH = 560
export const CONVERSATION_CARD_HEADER = 38
export const CONVERSATION_CARD_FOOTER = 18
export const CONVERSATION_BLOCK_GAP = 8

function estimatedLineCount(text: string, width: number): number {
  const columns = Math.max(18, Math.floor((width - 44) / 7.2))
  return Math.max(1, text.split('\n').reduce((sum, line) => (
    sum + Math.max(1, Math.ceil(Array.from(line).length / columns))
  ), 0))
}

export function estimateConversationBlockHeight(block: Pick<ConversationBlock, 'text'>, width: number): number {
  return 30 + estimatedLineCount(block.text, width) * 21
}

export function estimateConversationCardHeight(blocks: ConversationBlock[], width: number): number {
  const body = blocks.reduce((sum, block) => (
    sum + estimateConversationBlockHeight(block, width) + CONVERSATION_BLOCK_GAP
  ), 0)
  return Math.max(120, CONVERSATION_CARD_HEADER + body + CONVERSATION_CARD_FOOTER)
}

export function getConversationBlockOffset(card: Card, blockId: string): { top: number; height: number } | null {
  const blocks = card.conversation?.blocks
  if (!blocks) return null
  const { width } = resolveCardSize(card)
  let top = CONVERSATION_CARD_HEADER + CONVERSATION_BLOCK_GAP
  for (const block of blocks) {
    const height = estimateConversationBlockHeight(block, width)
    if (block.id === blockId) return { top, height }
    top += height + CONVERSATION_BLOCK_GAP
  }
  return null
}

export function resolveCardAnchorPoint(card: Card, anchor: CardAnchor | undefined): { x: number; y: number } {
  const size = resolveCardSize(card)
  const edge = anchor?.edge ?? 'right'
  const x = edge === 'left' ? card.x : card.x + size.width

  if (!anchor?.blockId) {
    const boundary = anchor?.boundary ?? 'center'
    const ratio = boundary === 'top' ? 0 : boundary === 'bottom' ? 1 : 0.5
    return { x, y: card.y + size.height * ratio }
  }

  const block = getConversationBlockOffset(card, anchor.blockId)
  if (!block) return { x, y: card.y + size.height / 2 }
  const ratio = anchor.boundary === 'top' ? 0 : anchor.boundary === 'bottom' ? 1 : 0.5
  return { x, y: card.y + block.top + block.height * ratio }
}
