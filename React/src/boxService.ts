// boxService.ts — 统一采集管线：一切内容先进入服务端卡片盒（默认收集箱），
// 从卡片盒显式“放到白板”时才复制为画布卡片。
import type { Card } from './types'
import { canvasStore, canvasActions, panelActions, parentCardActions, parentCardStore } from './store'
import { CanvasManager, createCard, findFreePosition, generateId } from './services'

export interface CardDimensions {
  width: number
  height: number
}

export interface ParentDraftOptions {
  sourceBoxCardId?: string
  sourceChildIds?: string[]
}

// ── 内容比例测量：无选区几何时，按文本量推算卡片尺寸 ──
// 与卡片渲染字体对齐（12.5px / 行高 1.7），CJK 按全宽、ASCII 按 0.55 宽折算。
const CARD_FONT_SIZE = 12.5
const CARD_LINE_HEIGHT = CARD_FONT_SIZE * 1.7
const CARD_PAD_X = 24
const CARD_PAD_Y = 36

function lineUnits(line: string): number {
  let units = 0
  for (const ch of line) units += ch.charCodeAt(0) > 0xff ? 1 : 0.55
  return Math.max(units, 1)
}

/** 按内容推算卡片尺寸：短文本小而紧凑，长文本更宽更高（上限内滚动）。 */
export function measureContentCard(content: string): CardDimensions {
  const units = content.split('\n').map(lineUnits)
  const maxLineUnits = Math.max(...units, 1)
  // 宽度先尝试容纳最长行（不折行），长文则到上限后靠折行增高
  const width = Math.round(Math.max(200, Math.min(460, maxLineUnits * CARD_FONT_SIZE + CARD_PAD_X)))
  const unitsPerLine = Math.max(1, Math.floor((width - CARD_PAD_X) / CARD_FONT_SIZE))
  const wrappedLines = units.reduce((sum, u) => sum + Math.ceil(u / unitsPerLine), 0)
  const height = Math.round(Math.max(72, Math.min(440, wrappedLines * CARD_LINE_HEIGHT + CARD_PAD_Y)))
  return { width, height }
}

/** 保存内容到服务端默认收集箱。成功返回 true，并广播 box-card-added 事件。 */
export async function saveToBox(
  content: string,
  sourceType: Card['sourceType'],
  sourceLabel?: string,
  dimensions?: CardDimensions,
  tags: string[] = [],
): Promise<boolean> {
  const text = content.trim()
  if (!text) return false
  const dims = dimensions ?? measureContentCard(text)
  try {
    const response = await fetch('/api/box/inbox/cards', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        content: text,
        preview: text.slice(0, 180),
        sourceType,
        sourceLabel: sourceLabel || null,
        preferredWidth: dims.width,
        preferredHeight: dims.height,
        tags,
      }),
    })
    const data = await response.json()
    if (!response.ok || !data.success) return false
    window.dispatchEvent(new CustomEvent('box-card-added'))
    return true
  } catch {
    return false
  }
}

/** 在 Web 白板上建立母卡草稿；保存后再进入共享 BoardDoc。 */
export function addParentDraftToCanvas(
  content: string,
  sourceType: Card['sourceType'],
  sourceLabel?: string,
  dimensions?: CardDimensions,
  options: ParentDraftOptions = {},
): boolean {
  const text = content.trim()
  const { activeCanvasId } = canvasStore.getState()
  if (!activeCanvasId || !text) return false
  const measured = dimensions ?? measureContentCard(text)
  const width = Math.max(360, Math.min(620, measured.width))
  const height = Math.max(220, Math.min(520, measured.height))
  const sharedCards = Object.values(CanvasManager.getCanvasData(activeCanvasId).cards)
  const draftCards = parentCardStore.getState().cards
    .filter(card => card.status === 'draft' && card.canvasId === activeCanvasId)
    .map(card => ({
      id: card.id,
      content: card.content,
      x: card.x,
      y: card.y,
      width: card.width,
      height: card.height,
      color: null,
      bgColor: null,
      textColor: null,
      sizePreset: 'default' as const,
      tags: [],
      zIndex: 0,
      sourceType: card.sourceType ?? 'manual' as const,
      createdAt: card.createdAt,
    }))
  const position = findFreePosition([...sharedCards, ...draftCards], width, height)
  parentCardActions.add({
    id: 'parent-' + generateId(),
    canvasId: activeCanvasId,
    content: text,
    createdAt: new Date().toISOString(),
    status: 'draft',
    sourceChildIds: options.sourceChildIds ?? [],
    sourceBoxCardId: options.sourceBoxCardId,
    sourceType,
    sourceLabel,
    x: position.x,
    y: position.y,
    width,
    height,
    extractedRanges: [],
  })
  panelActions.setLeft(false)
  return true
}

/** 把内容作为卡片放入当前活动画布（网格放置 + 碰撞检测）。无活动画布时返回 false。 */
export function addCardToCanvas(
  content: string,
  sourceType: Card['sourceType'],
  sourceLabel?: string,
  dimensions?: CardDimensions,
): boolean {
  const { activeCanvasId } = canvasStore.getState()
  if (!activeCanvasId || !content.trim()) return false
  const dims = dimensions ?? measureContentCard(content)
  const currentData = canvasStore.getState().canvasData
  const existingCards = Object.values(currentData.cards)
  const { x, y } = findFreePosition(existingCards, dims.width, dims.height)
  const id = 'card-' + generateId()
  const nextData = {
    ...currentData,
    cards: {
      ...currentData.cards,
      [id]: Object.assign(createCard(content, sourceType, {
      x, y,
      width: dims.width,
      height: dims.height,
      zIndex: existingCards.length,
      sourceLabel: sourceLabel || undefined,
      }), { id }) as Card,
    },
  }
  CanvasManager.saveCanvasData(activeCanvasId, nextData)
  canvasActions.setCanvasData(nextData)
  return true
}
