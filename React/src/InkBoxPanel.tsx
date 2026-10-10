import React, { useCallback, useEffect, useState } from 'react'
import { decodeBoard, unpackStrokePoints, type BoardDoc } from './boardFormat'
import { base64ToBytes } from './base64'
import { CanvasManager, createCard, generateId } from './services'
import { canvasActions } from './store'

interface InkBoxItem {
  id: string
  source_card_content: string
  width: number
  height: number
  bounds: number[]
  stroke_count: number
  created_at: string
}

export default function InkBoxPanel({
  boardId,
  restorePosition,
  onClose,
}: {
  boardId: string
  restorePosition: { x: number; y: number }
  onClose: () => void
}) {
  const [items, setItems] = useState<InkBoxItem[]>([])
  const [previews, setPreviews] = useState<Record<string, BoardDoc>>({})

  const load = useCallback(async () => {
    const response = await fetch(`/api/ink-box/${encodeURIComponent(boardId)}`)
    const payload = await response.json()
    const nextItems = (payload.items ?? []) as InkBoxItem[]
    setItems(nextItems)
    const detailEntries = await Promise.all(nextItems.map(async item => {
      const detailResponse = await fetch(`/api/ink-box/item/${encodeURIComponent(item.id)}`)
      const detail = await detailResponse.json()
      return [item.id, decodeBoard(base64ToBytes(detail.boardBase64))] as const
    }))
    setPreviews(Object.fromEntries(detailEntries))
  }, [boardId])

  useEffect(() => {
    void load()
  }, [load])

  const restore = async (itemId: string) => {
    const item = items.find(candidate => candidate.id === itemId)
    const preview = previews[itemId]
    if (!item || !preview) return

    // Web 白板以 localStorage BoardDoc 为权威；恢复直接写回本地文档，不经过已禁用的 WS 共享板。
    const currentDoc = CanvasManager.getCanvasDocument(boardId)
    const highestZ = currentDoc.cards.reduce((value, card) => Math.max(value, card.zIndex), 0)
    const cardId = 'card-' + generateId()
    const restoredStrokes = preview.ink.map(stroke => ({
      ...stroke,
      id: 'stroke-' + generateId(),
      space: `card:${cardId}`,
    }))
    const nextDoc = {
      ...currentDoc,
      cards: [...currentDoc.cards, {
        id: cardId,
        ...createCard(item.source_card_content, 'manual', {
          x: restorePosition.x,
          y: restorePosition.y,
          width: item.width,
          height: item.height,
          zIndex: highestZ + 1,
          kind: 'ink',
        }),
      }],
      ink: [...currentDoc.ink, ...restoredStrokes],
    }
    CanvasManager.saveCanvasDocument(boardId, nextDoc)
    const verified = CanvasManager.getCanvasDocument(boardId)
    const verifiedStrokeIds = new Set(verified.ink.map(stroke => stroke.id))
    if (!verified.cards.some(card => card.id === cardId)
      || restoredStrokes.some(stroke => !verifiedStrokeIds.has(stroke.id))) {
      throw new Error('浏览器未能持久化恢复内容，归档条目已保留')
    }
    canvasActions.setCanvasData(CanvasManager.getCanvasData(boardId))

    const response = await fetch(`/api/ink-box/item/${encodeURIComponent(itemId)}`, { method: 'DELETE' })
    if (!response.ok) throw new Error('笔迹已经恢复，但无法从笔迹盒移除归档条目')
    await load()
  }

  return (
    <div className="ink-box-overlay" onMouseDown={event => { if (event.target === event.currentTarget) onClose() }}>
      <section className="ink-box-panel">
        <header className="ink-box-header">
          <div>
            <h2>笔迹盒</h2>
            <span>{items.length} 组归档笔迹</span>
          </div>
          <button onClick={onClose}>关闭</button>
        </header>
        <div className="ink-box-grid">
          {items.map(item => {
            const preview = previews[item.id]
            const bounds = item.bounds.length === 4 ? item.bounds : [0, 0, item.width, item.height]
            const viewWidth = Math.max(1, bounds[2] - bounds[0])
            const viewHeight = Math.max(1, bounds[3] - bounds[1])
            return (
              <article className="ink-box-item" key={item.id}>
                <svg viewBox={`${bounds[0]} ${bounds[1]} ${viewWidth} ${viewHeight}`} preserveAspectRatio="xMidYMid meet">
                  {preview?.ink.map(stroke => (
                    <polyline
                      key={stroke.id}
                      points={unpackStrokePoints(stroke.points).map(point => `${point.x},${point.y}`).join(' ')}
                      fill="none"
                      stroke="currentColor"
                      strokeWidth={stroke.width}
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  ))}
                </svg>
                <div className="ink-box-item-info">
                  <strong>{item.source_card_content.split('\n').find(line => line.trim()) || '笔迹卡'}</strong>
                  <span>{item.stroke_count} 笔 · {Math.round(item.width)}×{Math.round(item.height)}</span>
                </div>
                <button onClick={() => void restore(item.id)}>放到白板</button>
              </article>
            )
          })}
          {items.length === 0 && <div className="ink-box-empty">归档笔迹会显示在这里</div>}
        </div>
      </section>
    </div>
  )
}
