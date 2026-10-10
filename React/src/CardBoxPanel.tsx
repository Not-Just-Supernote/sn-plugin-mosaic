// CardBoxPanel.tsx — 左侧边栏：服务端默认收集箱。内容先作为 Web 母卡草稿打开，
// 保存母卡时再从收集箱取出。
import React, { useCallback, useEffect, useState } from 'react'
import type { Card } from './types'
import { addCardToCanvas } from './boxService'
import { useStore, canvasStore, parentCardStore, parentCardActions } from './store'

export interface BoxCardItem {
  id: string
  title: string | null
  content: string | null
  preview: string | null
  source_type: string | null
  source_label: string | null
  preferred_width: number | null
  preferred_height: number | null
  created_at: string
}

const CARD_SOURCE_TYPES = new Set<Card['sourceType']>([
  'import', 'clipboard', 'manual', 'transcription', 'youtube', 'tieba', 'reddit-post', 'reddit-comment', 'sillytavern',
])

function sourceTypeOf(value: string | null): Card['sourceType'] {
  return value && CARD_SOURCE_TYPES.has(value as Card['sourceType'])
    ? value as Card['sourceType']
    : 'manual'
}

const CardBoxPanel: React.FC = () => {
  const [cards, setCards] = useState<BoxCardItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const { activeCanvasId } = useStore(canvasStore)
  const { cards: parentCards } = useStore(parentCardStore)
  const draftParents = parentCards.filter(card => card.status === 'draft' && card.canvasId === activeCanvasId)

  const loadInbox = useCallback(async () => {
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 4000)
    try {
      const response = await fetch('/api/box/inbox', { signal: controller.signal })
      const raw = await response.text()
      let data: { success?: boolean; error?: string; cards?: BoxCardItem[] } = {}
      if (raw.trim()) {
        try {
          data = JSON.parse(raw) as typeof data
        } catch {
          throw new Error(`卡片盒服务返回了无效响应（HTTP ${response.status}）`)
        }
      }
      if (!response.ok || !data.success) throw new Error(data.error || '卡片盒加载失败')
      setCards(data.cards || [])
      setError('')
    } catch (err) {
      setError(err instanceof DOMException && err.name === 'AbortError'
        ? '收集箱请求超时，请检查后端 3791 端口。'
        : err instanceof Error ? err.message : '收集箱加载失败')
    } finally {
      window.clearTimeout(timeout)
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadInbox()
    const reload = () => loadInbox()
    window.addEventListener('box-card-added', reload)
    window.addEventListener('box-card-changed', reload)
    return () => {
      window.removeEventListener('box-card-added', reload)
      window.removeEventListener('box-card-changed', reload)
    }
  }, [loadInbox])

  const deleteCard = useCallback(async (id: string) => {
    const response = await fetch(`/api/box/card/${id}`, { method: 'DELETE' })
    if (response.ok) setCards(current => current.filter(card => card.id !== id))
  }, [])

  const openAsCard = useCallback((card: BoxCardItem) => {
    const content = card.content || ''
    if (!content) return
    const dimensions = card.preferred_width && card.preferred_height
      ? { width: card.preferred_width, height: card.preferred_height }
      : undefined
    addCardToCanvas(
      content,
      sourceTypeOf(card.source_type),
      card.source_label || undefined,
      dimensions,
    )
  }, [])

  if (!loading && !error && !cards.length && !draftParents.length) {
    return <div className="card-box-empty">采集内容会先进入这里，可直接添加为普通卡片。</div>
  }

  return (
    <div className="card-box-list">
      <div className="card-box-summary">CARD Box · {cards.length + draftParents.length} 张卡片</div>
      {draftParents.length > 0 && (
        <>
          <div className="card-box-section-label">母卡草稿</div>
          {draftParents.map(card => (
            <article className="card-box-item parent-draft" key={card.id}>
              <div className="card-box-item-content">{card.content.slice(0, 240)}</div>
              <div className="card-box-item-meta">
                <span>{card.sourceLabel || '白板合并'}</span>
                <span>{Math.round(card.width)} × {Math.round(card.height)}</span>
              </div>
              <div className="card-box-item-actions">
                <button className="add-card-btn" onClick={() => parentCardActions.open(card.id)}>继续编辑</button>
                <button className="card-box-delete" onClick={() => parentCardActions.remove(card.id)}>删除</button>
              </div>
            </article>
          ))}
        </>
      )}
      {loading && <div className="card-box-empty">正在加载收集箱...</div>}
      {error && <div className="card-box-empty error">{error}</div>}
      {cards.map(card => {
        const dimensions = card.preferred_width && card.preferred_height
          ? { width: card.preferred_width, height: card.preferred_height }
          : undefined
        return (
          <article className="card-box-item" key={card.id}>
            <div className="card-box-item-content">{card.preview || (card.content || '').slice(0, 180)}</div>
            <div className="card-box-item-meta">
              <span>{card.source_label || card.source_type || '摘录'}</span>
              {dimensions && <span>{Math.round(dimensions.width)} × {Math.round(dimensions.height)}</span>}
            </div>
            <div className="card-box-item-actions">
              <button
                className="add-card-btn"
                disabled={!card.content}
                onClick={() => openAsCard(card)}
              >
                添加卡片
              </button>
              <button className="card-box-delete" onClick={() => deleteCard(card.id)}>删除</button>
            </div>
          </article>
        )
      })}
    </div>
  )
}

export default CardBoxPanel
