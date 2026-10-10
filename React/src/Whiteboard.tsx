// Whiteboard.tsx — 白板主体（画布 + 卡片 + 连线 + 工具栏 + 小地图 + 交互逻辑）
import React, { useState, useRef, useCallback, useEffect, useMemo, useSyncExternalStore } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkHardBreaks from './remarkHardBreaks'
import { Save, Download, Upload, RotateCcw, RotateCw, Search, Grid3x3, Plus, Maximize2, Pencil, Eye, X, Eraser, RefreshCw, ZoomIn, ZoomOut, Image as ImageIcon, FileText } from 'lucide-react'
import { useStore, canvasStore, canvasActions, selectionStore, selectionActions, panelStore, panelActions, parentCardStore, parentCardActions } from './store'
import { CanvasManager, createCard, createLiquidTextDemoData, findFreePosition, generateId } from './services'
import type { Card, Connection, CanvasData, CanvasInfo, ParentCard, TextSegment } from './types'
import { SIZE_PRESETS } from './types'
import type { SizePresetKey } from './types'
import { LiquidBackground, LiquidOverlay } from './LiquidLayer'
import { detectClusters, type Cluster } from './liquidEffects'
import {
  createSparseTestCanvasData,
  SPARSE_TEST_CANVAS_ID,
  SPARSE_TEST_CANVAS_NAME,
} from './testBoard'
import {
  buildChunkIndex,
  detectRegions,
  findRegionJumps,
  panToCenterRect,
  queryRect,
  viewportWorldRect,
  CHUNK_SIZE,
  MAX_ISLAND_GAP_CHUNKS,
  type CanvasRegion,
} from './spatialIndex'
import { resolveCardSize } from './cardGeometry'
import { bytesToBase64 } from './base64'
import { BOARD_SYNC_ENABLED, BoardSyncClient, type SyncConnectionState } from './boardSyncClient'
import { downloadSyncLogs, recordSyncLog } from './syncLog'
import { LOCAL_ROOM_ID, boardAssetRef, boardAssetUrl, createSharedBoard, parseSharedBoard, sharedBoardLink } from './sharedBoard'
import BoundaryEditor from './BoundaryEditor'
import InkBoxPanel from './InkBoxPanel'
import {
  unpackStrokePoints, packStrokePoints, computeStrokeBounds,
  STROKE_ENCODING_F32X3,
  type BoardDoc, type InkStroke, type StrokePoint,
} from './boardFormat'
import { addParentDraftToCanvas, saveToBox } from './boxService'
import { InkIndex } from './inkIndex'
import ConversationCardBody from './sillytavern/ConversationCardBody'
import ConversationBranchLayer from './sillytavern/ConversationBranchLayer'
import { downloadPenEventLog, recordPenDecision, recordPenDiagnostic } from './penEventLog'

// ============================
// Color palette for card customization
// ============================
const BG_COLORS = [
  { label: 'Default', value: null },
  { label: 'Coral', value: '#FFE8E5' },
  { label: 'Peach', value: '#FFF4E8' },
  { label: 'Yellow', value: '#FFF9E3' },
  { label: 'Mint', value: '#E8F8F5' },
  { label: 'Sky', value: '#E8F2FF' },
  { label: 'Lavender', value: '#F3EFFF' },
  { label: 'Rose', value: '#FFE8F2' },
  { label: 'Cream', value: '#FAF7F0' },
]
const BG_COLORS_LIGHT = BG_COLORS
const TEXT_COLORS = [
  { label: 'Default', value: null },
  { label: 'Coral', value: '#E87B7B' },
  { label: 'Amber', value: '#E8A05D' },
  { label: 'Gold', value: '#E8C563' },
  { label: 'Sage', value: '#7BC78E' },
  { label: 'Sky', value: '#6BA5E7' },
  { label: 'Lavender', value: '#A78BE7' },
  { label: 'Rose', value: '#E77BA5' },
  { label: 'Slate', value: '#6B7280' },
]

// Edge-to-edge distance between two cards (0 when overlapping)
function getEdgeDistance(a: Card, b: Card): number {
  const aSize = resolveCardSize(a)
  const bSize = resolveCardSize(b)
  const aW = aSize.width, aH = aSize.height
  const bW = bSize.width, bH = bSize.height
  const dx = Math.max(0, Math.max(a.x - (b.x + bW), b.x - (a.x + aW)))
  const dy = Math.max(0, Math.max(a.y - (b.y + bH), b.y - (a.y + aH)))
  return Math.hypot(dx, dy)
}

// Keep web card drops aligned with the native bridge placement. A connection
// is created only when the dragged card can settle on one of the four
// axis-aligned bridge positions; a diagonal near-corner connection would feed
// the liquid geometry an unstable attachment pair.
const CARD_CONNECT_DISTANCE = 40
const CARD_SNAP_DISTANCE = 56
const CARD_BRIDGE_GAP = 18
const CARD_MIN_OVERLAP = 48

function intervalOverlap(a0: number, a1: number, b0: number, b1: number): number {
  return Math.max(0, Math.min(a1, b1) - Math.max(a0, b0))
}

function findCardSnap(
  cards: Iterable<Card>,
  card: Card,
  x: number,
  y: number,
): { x: number; y: number; targetId: string | null } {
  const size = resolveCardSize(card)
  let nearest: Card | null = null
  let nearestDist = Number.POSITIVE_INFINITY
  for (const other of cards) {
    if (other.id === card.id) continue
    const d = getEdgeDistance({ ...card, x, y }, other)
    if (d >= CARD_CONNECT_DISTANCE || d >= nearestDist) continue
    nearest = other
    nearestDist = d
  }
  if (!nearest) return { x, y, targetId: null }

  let snapX = x
  let snapY = y
  let snapMove = CARD_SNAP_DISTANCE
  let snapped = false
  const consider = (candidateX: number, candidateY: number) => {
    const move = Math.hypot(candidateX - x, candidateY - y)
    if (move <= snapMove) {
      snapMove = move
      snapX = candidateX
      snapY = candidateY
      snapped = true
    }
  }
  const verticalOverlap = intervalOverlap(y, y + size.height, nearest.y, nearest.y + resolveCardSize(nearest).height)
  if (verticalOverlap >= CARD_MIN_OVERLAP) {
    const otherSize = resolveCardSize(nearest)
    consider(nearest.x - size.width - CARD_BRIDGE_GAP, y)
    consider(nearest.x + otherSize.width + CARD_BRIDGE_GAP, y)
  }
  const otherSize = resolveCardSize(nearest)
  const horizontalOverlap = intervalOverlap(x, x + size.width, nearest.x, nearest.x + otherSize.width)
  if (horizontalOverlap >= CARD_MIN_OVERLAP) {
    consider(x, nearest.y - size.height - CARD_BRIDGE_GAP)
    consider(x, nearest.y + otherSize.height + CARD_BRIDGE_GAP)
  }
  return snapped ? { x: snapX, y: snapY, targetId: nearest.id } : { x, y, targetId: null }
}

function connectedCardIds(cardId: string, cards: Record<string, Card>, connections: Connection[]): string[] {
  const seen = new Set<string>([cardId])
  const queue = [cardId]
  while (queue.length > 0) {
    const current = queue.shift()!
    for (const connection of connections) {
      if (connection.locked !== true) continue
      const next = connection.fromCardId === current
        ? connection.toCardId
        : connection.toCardId === current ? connection.fromCardId : null
      if (next && cards[next] && !seen.has(next)) {
        seen.add(next)
        queue.push(next)
      }
    }
  }
  return [...seen]
}

function cardResizeLocked(cardId: string, cards: Record<string, Card>, connections: Connection[]): boolean {
  const component = new Set(connectedCardIds(cardId, cards, connections))
  return connections.some(connection =>
    connection.locked === true && component.has(connection.fromCardId) && component.has(connection.toCardId),
  )
}
const SPARSE_PAN_MARGIN = CHUNK_SIZE * MAX_ISLAND_GAP_CHUNKS
const PRESET_KEYS: SizePresetKey[] = ['default', 'tall', 'wide', 'large']

function welcomeCardWidth(cards: Record<string, Card>): number {
  const welcome = Object.values(cards).find(card => card.content.startsWith('Welcome to LiquidText'))
  return welcome ? resolveCardSize(welcome).width : SIZE_PRESETS.default.width
}
// PalettePanel Ball/16 default: raw thickness 400 -> 4 world pixels.
const PEN_WIDTH = 4
const INK_ARGB_BLACK = 0xFF000000 >>> 0
const EMPTY_INK: InkStroke[] = []
const ERASER_RADIUS_PX = 14
// 与插件 BoardGeometry 同源的缩放锚点：10%=0.36666667，100%=0.8，200%=2.0。
// 10%..100% 线性插值；100%..200% 等比插值，避免从 100% 起步时的突跳。
const MIN_CANVAS_SCALE = 0.36666667
const NORMAL_CANVAS_SCALE = 0.8
const MAX_CANVAS_SCALE = 2
const DEFAULT_CANVAS_SCALE = scaleForZoomPercent(75)
const WHEEL_ZOOM_STEP = 1.1

function scaleForZoomPercent(percent: number): number {
  return percent <= 100
    ? MIN_CANVAS_SCALE + ((percent - 10) / 90) * (NORMAL_CANVAS_SCALE - MIN_CANVAS_SCALE)
    : NORMAL_CANVAS_SCALE * Math.pow(MAX_CANVAS_SCALE / NORMAL_CANVAS_SCALE, Math.min(1, Math.max(0, (percent - 100) / 100)))
}

function zoomPercentForScale(scale: number): number {
  const clamped = Math.min(MAX_CANVAS_SCALE, Math.max(MIN_CANVAS_SCALE, scale))
  return clamped <= NORMAL_CANVAS_SCALE
    ? 10 + ((clamped - MIN_CANVAS_SCALE) / (NORMAL_CANVAS_SCALE - MIN_CANVAS_SCALE)) * 90
    : 100 + (Math.log(clamped / NORMAL_CANVAS_SCALE) / Math.log(MAX_CANVAS_SCALE / NORMAL_CANVAS_SCALE)) * 100
}

function clampScale(scale: number | undefined): number {
  if (scale === undefined || !Number.isFinite(scale) || scale <= 0) return DEFAULT_CANVAS_SCALE
  return Math.min(MAX_CANVAS_SCALE, Math.max(MIN_CANVAS_SCALE, scale))
}

function removeCardsWithRelationships(data: CanvasData, cardIds: Iterable<string>): CanvasData {
  const removing = new Set(cardIds)
  const cards = { ...data.cards }
  for (const cardId of removing) {
    const removed = data.cards[cardId]
    if (!removed) continue
    if (removed.role === 'parent') {
      for (const childId of removed.childIds ?? []) {
        if (removing.has(childId)) continue
        const child = cards[childId]
        if (!child || child.parentId !== removed.id) continue
        const { parentId: _parentId, role: _role, ...independentCard } = child
        cards[childId] = independentCard as Card
      }
    } else if (removed.parentId && !removing.has(removed.parentId)) {
      const parent = cards[removed.parentId]
      if (parent?.role === 'parent') {
        cards[parent.id] = {
          ...parent,
          childIds: (parent.childIds ?? []).filter(id => id !== removed.id),
          extractedRanges: (parent.extractedRanges ?? []).filter(range => range.childCardId !== removed.id),
        }
      }
    }
  }
  for (const cardId of removing) delete cards[cardId]
  return {
    ...data,
    cards,
    connections: data.connections.filter(connection => (
      !removing.has(connection.fromCardId) && !removing.has(connection.toCardId)
    )),
  }
}

type ActivePenStroke = {
  id: string
  space: string
  points: StrokePoint[]
}

function inkColor(argb: number): string {
  const value = argb >>> 0
  const alpha = ((value >>> 24) & 0xff) / 255
  return `rgba(${(value >>> 16) & 0xff}, ${(value >>> 8) & 0xff}, ${value & 0xff}, ${alpha})`
}

/** Keep the web mirror on the same drawPath width domain as Mosaic native. */
function drawPathStrokeWidth(stroke: InkStroke): number {
  return stroke.drawPathWidth !== undefined && stroke.drawPathWidth > 0
    ? stroke.drawPathWidth / 100
    : stroke.width
}

function isPenSideButton(e: React.PointerEvent): boolean {
  return e.button === 2 || e.button === 5 || (e.buttons & 2) !== 0 || (e.buttons & 32) !== 0
}

function pointSegmentDistanceSquared(
  point: { x: number; y: number },
  start: StrokePoint,
  end: StrokePoint,
): number {
  const dx = end.x - start.x
  const dy = end.y - start.y
  const lengthSquared = dx * dx + dy * dy
  if (lengthSquared === 0) return (point.x - start.x) ** 2 + (point.y - start.y) ** 2
  const t = Math.max(0, Math.min(1, ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared))
  const nearestX = start.x + t * dx
  const nearestY = start.y + t * dy
  return (point.x - nearestX) ** 2 + (point.y - nearestY) ** 2
}

function strokeTouchesEraser(
  stroke: InkStroke,
  world: { x: number; y: number },
  cards: Record<string, Card>,
  radius: number,
): boolean {
  let point = world
  if (stroke.space.startsWith('card:')) {
    const card = cards[stroke.space.slice(5)]
    if (!card) return false
    point = { x: world.x - card.x, y: world.y - card.y }
  }
  const [left, top, right, bottom] = stroke.bounds
  const hitRadius = radius + drawPathStrokeWidth(stroke) / 2
  if (point.x < left - hitRadius || point.x > right + hitRadius ||
      point.y < top - hitRadius || point.y > bottom + hitRadius) return false
  const points = unpackStrokePoints(stroke.points)
  if (points.length === 1) {
    return (point.x - points[0].x) ** 2 + (point.y - points[0].y) ** 2 <= hitRadius ** 2
  }
  for (let i = 1; i < points.length; i++) {
    if (pointSegmentDistanceSquared(point, points[i - 1], points[i]) <= hitRadius ** 2) return true
  }
  return false
}

/** 每条笔迹的 polyline points 字符串只算一次（笔迹对象不可变，按对象缓存）。 */
const strokePointsCache = new WeakMap<InkStroke, string>()

function strokePointsAttr(stroke: InkStroke): string {
  let cached = strokePointsCache.get(stroke)
  if (cached === undefined) {
    cached = unpackStrokePoints(stroke.points).map(point => `${point.x},${point.y}`).join(' ')
    strokePointsCache.set(stroke, cached)
  }
  return cached
}

/**
 * 已落盘笔迹层。笔迹不进白板组件的 React 状态：本层自己订阅 CanvasManager 的笔迹变化，
 * 写一笔 / 擦一笔只有本层重渲染。canvas 空间一份（卡片之下）、card 空间一份（卡片之上）。
 * 卡片附着笔迹按卡片分组，用 <g transform> 平移到卡片位置，points 字符串与卡片位置无关。
 */
const WebInkLayer = React.memo(({ canvasId, cards, space }: {
  canvasId: string | null
  cards?: Record<string, Card>
  space: 'canvas' | 'card' | 'connection'
}) => {
  const strokes = useSyncExternalStore(
    CanvasManager.subscribeInk,
    () => (canvasId ? CanvasManager.getCanvasInk(canvasId) : EMPTY_INK),
  )
  if (space === 'canvas') {
    return (
      <svg className="web-ink-layer canvas" width="1" height="1" overflow="visible">
        {strokes.map(stroke => (stroke.space === 'canvas' ? (
          <polyline
            key={stroke.id}
            points={strokePointsAttr(stroke)}
            fill="none"
            stroke={inkColor(stroke.color)}
            strokeWidth={drawPathStrokeWidth(stroke)}
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        ) : null))}
      </svg>
    )
  }
  if (space === 'connection') {
    return (
      <svg className="web-ink-layer connection" width="1" height="1" overflow="visible">
        {strokes.map(stroke => (stroke.space.startsWith('connection:') ? (
          <polyline
            key={stroke.id}
            points={strokePointsAttr(stroke)}
            fill="none"
            stroke={inkColor(stroke.color)}
            strokeWidth={drawPathStrokeWidth(stroke)}
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        ) : null))}
      </svg>
    )
  }
  const cardMap = cards ?? {}
  const byCard = new Map<string, InkStroke[]>()
  for (const stroke of strokes) {
    if (!stroke.space.startsWith('card:')) continue
    const cardId = stroke.space.slice(5)
    if (!cardMap[cardId]) continue
    const list = byCard.get(cardId)
    if (list === undefined) byCard.set(cardId, [stroke])
    else list.push(stroke)
  }
  return (
    <svg className="web-ink-layer card" width="1" height="1" overflow="visible">
      {[...byCard].map(([cardId, list]) => {
        const card = cardMap[cardId]
        const size = resolveCardSize(card)
        const clipId = `ink-clip-${cardId}`
        return (
          <g key={cardId} transform={`translate(${card.x} ${card.y})`}>
            <clipPath id={clipId}>
              <rect x={0} y={0} width={size.width} height={size.height} />
            </clipPath>
            <g clipPath={`url(#${clipId})`}>
              {list.map(stroke => (
                <polyline
                  key={stroke.id}
                  points={strokePointsAttr(stroke)}
                  fill="none"
                  stroke={inkColor(stroke.color)}
                  strokeWidth={drawPathStrokeWidth(stroke)}
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              ))}
            </g>
          </g>
        )
      })}
    </svg>
  )
})


// ============================
// Markdown components for card preview
// ============================
const cardMarkdownComponents = {
  p: ({ children }: any) => <p style={{ margin: '0 0 0.5em' }}>{children}</p>,
  h1: ({ children }: any) => <h1 className="card-heading card-heading-1">{children}</h1>,
  h2: ({ children }: any) => <h2 className="card-heading card-heading-2">{children}</h2>,
  h3: ({ children }: any) => <h3 className="card-heading card-heading-3">{children}</h3>,
  h4: ({ children }: any) => <h3 className="card-heading card-heading-3">{children}</h3>,
  h5: ({ children }: any) => <h3 className="card-heading card-heading-3">{children}</h3>,
  h6: ({ children }: any) => <h3 className="card-heading card-heading-3">{children}</h3>,
  strong: ({ children }: any) => <strong>{children}</strong>,
  em: ({ children }: any) => <em>{children}</em>,
  // react-markdown v9+ 不再传 inline 参数：块级代码位于 <pre> 内且 children 含换行，行内代码则否
  code: ({ children }: any) => (typeof children === 'string' && !children.includes('\n'))
    ? <code className="inline-code">{children}</code>
    : <code>{children}</code>,
  pre: ({ children }: any) => <div style={{ margin: '0.3em 0' }}>{children}</div>,
  // Native editor's "black line" format is stored as a Markdown blockquote;
  // keep the same inverted treatment in the web board.
  blockquote: ({ children }: any) => <div style={{ background: '#000', color: '#fff', borderRadius: 4, padding: '3px 8px', margin: '0.3em 0' }}>{children}</div>,
  a: ({ href, children }: any) => <a href={href} target="_blank" rel="noopener noreferrer" style={{ color: 'var(--accent-primary)' }}>{children}</a>,
  ul: ({ children }: any) => <ul style={{ margin: '0.2em 0', paddingLeft: 16 }}>{children}</ul>,
  ol: ({ children }: any) => <ol style={{ margin: '0.2em 0', paddingLeft: 16 }}>{children}</ol>,
  li: ({ children }: any) => <li style={{ margin: '0.1em 0' }}>{children}</li>,
}

/** Connection ink lives in world coordinates and follows its connected group. */
function connectionInkAtPoint(
  world: { x: number; y: number },
  cards: Record<string, Card>,
  connections: Connection[],
): string | null {
  for (const connection of connections) {
    const a = cards[connection.fromCardId]
    const b = cards[connection.toCardId]
    if (!a || !b) continue
    const as = resolveCardSize(a)
    const bs = resolveCardSize(b)
    const left = Math.min(a.x, b.x) - 24
    const right = Math.max(a.x + as.width, b.x + bs.width) + 24
    const top = Math.min(a.y, b.y) - 24
    const bottom = Math.max(a.y + as.height, b.y + bs.height) + 24
    if (world.x >= left && world.x <= right && world.y >= top && world.y <= bottom) {
      const inA = world.x >= a.x && world.x <= a.x + as.width && world.y >= a.y && world.y <= a.y + as.height
      const inB = world.x >= b.x && world.x <= b.x + bs.width && world.y >= b.y && world.y <= b.y + bs.height
      if (!inA && !inB) return connection.id
    }
  }
  return null
}

function shiftWorldInk(stroke: InkStroke, dx: number, dy: number): InkStroke {
  if (dx === 0 && dy === 0) return stroke
  const points = unpackStrokePoints(stroke.points).map(point => ({ ...point, x: point.x + dx, y: point.y + dy }))
  return {
    ...stroke,
    points: packStrokePoints(points),
    bounds: [stroke.bounds[0] + dx, stroke.bounds[1] + dy, stroke.bounds[2] + dx, stroke.bounds[3] + dy],
  }
}

// ============================
// Liquid canvas layer
// ============================
// Connections still carry the liquid attachment state, but the former cyan
// connector strokes, clickable paths, labels, and rubber-band previews are gone.
const CanvasLiquidSVG = React.memo(({ connections, cards, scale, clusters, selectedCardIds }: {
  connections: Connection[]
  cards: Record<string, Card>
  scale: number
  clusters: Cluster[]
  selectedCardIds: string[]
}) => (
  <svg className="whiteboard-connections canvas-connections-svg">
    <LiquidBackground cards={cards} connections={connections} scale={scale} clusters={clusters} selectedCardIds={selectedCardIds} />
  </svg>
))

const DemoFlowchart = React.memo(() => (
  <div className="liquidtext-demo-flowchart" aria-label="Stem cell cartilage regeneration flowchart">
    <div className="demo-flow-node primary">Stem Cell<br />Cartilage<br />Regeneration</div>
    <div className="demo-flow-line vertical" />
    <div className="demo-flow-row"><span className="demo-flow-node">Good candidate?</span><span className="demo-flow-node">Diagnosis</span></div>
    <div className="demo-flow-line vertical short" />
    <div className="demo-flow-row"><span className="demo-flow-node success">Treat</span><span className="demo-flow-node warning">Review history</span><span className="demo-flow-node danger">Do not use</span></div>
    <div className="demo-flow-caption">Figure 1. Recommended flowchart for Stem Cell Cartilage Regeneration Therapy</div>
  </div>
))

// ============================
// WhiteboardCard sub-component
// ============================
/** 图片 / 笔记卡片的像素：有共享资源地址就显示，取不到（未上传 / 未共享）退回占位。 */
const CardAssetBody = React.memo(({ card, assetUrl }: { card: Card; assetUrl: string | null }) => {
  const [failed, setFailed] = useState(false)
  useEffect(() => { setFailed(false) }, [assetUrl])
  if (assetUrl && !failed) {
    return (
      <img
        className={`whiteboard-card-asset ${card.kind === 'note' ? 'note' : 'image'}`}
        src={assetUrl}
        alt={card.kind === 'note' ? (card.title || '笔记卡片') : (card.content || '图片卡片')}
        draggable={false}
        onError={() => setFailed(true)}
      />
    )
  }
  return (
    <div className="whiteboard-card-asset-placeholder">
      {card.kind === 'image' ? <ImageIcon size={22} /> : <FileText size={22} />}
      <span>{card.kind === 'note' ? (card.title || '笔记卡片') : (card.content || '图片卡片')}</span>
      <small>{assetUrl ? '设备端尚未上传' : (card.kind === 'note' ? '笔记预览需要共享白板' : '图片需要共享白板')}</small>
    </div>
  )
})

const WhiteboardCard = React.memo(({ card, selected, cardSelected, resizeLocked, scale, editing, assetUrl, onMouseDown, onToggleSelect, onSelect, onDoubleClick, onRemove, onResizeStart, onSizeChange, onContextMenu, onEditSave, onExpandDetail }: {
  card: Card
  selected: boolean
  cardSelected: boolean
  resizeLocked: boolean
  scale: number
  editing: boolean
  /** 图片 / 笔记预览的共享资源地址（非共享白板或非资源卡为 null）。 */
  assetUrl?: string | null
  onMouseDown: (e: React.MouseEvent, cardId: string) => void
  onToggleSelect: (cardId: string) => void
  onSelect: (cardId: string) => void
  onDoubleClick: (cardId: string) => void
  onRemove: (cardId: string) => void
  onResizeStart: (e: React.MouseEvent, cardId: string, handle: string) => void
  onSizeChange: (cardId: string, preset: SizePresetKey) => void
  onContextMenu: (e: React.MouseEvent, cardId: string) => void
  onEditSave: (cardId: string, newContent: string) => void
  onExpandDetail: (cardId: string) => void
}) => {
  const [hovered, setHovered] = useState(false)
  const [editText, setEditText] = useState('')
  const editRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (editing) {
      setEditText(card.content || '')
      setTimeout(() => editRef.current?.focus(), 50)
    }
  }, [editing, card.content])

  const preset = SIZE_PRESETS[(card.sizePreset || 'default') as SizePresetKey] || SIZE_PRESETS.default
  const { width: cardWidth, height: cardHeight } = resolveCardSize(card)

  // 卡片预览量跟随实际面积，增高或增宽后自然展示更多正文。
  const displayText = useMemo(() => {
    const columns = Math.max(12, Math.floor((cardWidth - 48) / 8))
    const rows = Math.max(3, Math.floor((cardHeight - 52) / 21))
    const budget = Math.max(180, Math.ceil(columns * rows * 1.5))
    return (card.content || '').slice(0, budget)
  }, [card.content, cardHeight, cardWidth])

  const handleMouseDown = (e: React.MouseEvent) => {
    if (editing) return
    if (e.shiftKey) {
      e.stopPropagation()
      onToggleSelect(card.id)
    } else if (e.detail === 2) {
      e.stopPropagation()
      onDoubleClick(card.id)
    } else {
      onMouseDown(e, card.id)
    }
  }

  const handleEditKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      onEditSave(card.id, editText)
    }
    if (e.key === 'Escape') {
      onEditSave(card.id, card.content || '')
    }
    e.stopPropagation()
  }

  const accentColor = card.color || 'var(--accent-primary)'

  // Zoom-dependent detail level
  const detailLevel = scale >= NORMAL_CANVAS_SCALE ? 'full' : scale > MIN_CANVAS_SCALE ? 'compact' : 'minimal'

  // Minimal render at very low zoom
  if (detailLevel === 'minimal') {
    return (
      <div
        className={`whiteboard-card minimal ${selected ? 'selected' : ''} ${card.parentId ? 'movement-locked' : ''}`}
        style={{
          left: card.x, top: card.y, width: cardWidth, height: cardHeight,
          // Visual body + selection ring rendered by SVG LiquidLayer below.
        }}
        onMouseDown={handleMouseDown}
        onContextMenu={(e) => { e.preventDefault(); onContextMenu(e, card.id) }}
      >
        <div className="whiteboard-card-minimal-initial" style={{
          background: 'var(--bg-tertiary)',
          color: 'var(--text-secondary)',
        }}>{(card.content || '').slice(0, 2)}</div>
      </div>
    )
  }

  return (
    <div
      className={[
        'whiteboard-card',
        card.id.startsWith('demo-') ? 'liquidtext-demo-card' : '',
        card.id === 'demo-welcome' ? 'liquidtext-demo-welcome' : '',
        card.id === 'demo-procedure' || card.id === 'demo-finding' || card.id === 'demo-important' || card.id === 'demo-after' ? 'liquidtext-demo-emphasis' : '',
        selected ? 'selected' : '',
        cardSelected ? 'card-selected' : '',
        card.role === 'parent' ? 'role-parent' : '',
        card.parentId ? 'role-child' : '',
        card.parentId ? 'movement-locked' : '',
        editing ? 'editing' : '',
      ].filter(Boolean).join(' ')}
      style={{
        left: card.x,
        top: card.y,
        width: cardWidth,
        height: cardHeight,
        display: 'flex',
        flexDirection: 'column',
        // Card visual body is rendered by SVG LiquidLayer below; DOM stays transparent.
        // bgColor/selected feedback are routed through the SVG layer.
        // --card-bg is consumed by the body-overflow fade gradient — must match SVG fill.
        ...(card.bgColor ? { '--card-bg': card.bgColor } as React.CSSProperties : {}),
        color: card.textColor || undefined,
      }}
      onMouseDown={handleMouseDown}
      title={card.parentId ? '母卡存在时，子卡位置固定' : undefined}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onContextMenu={(e) => { e.preventDefault(); onContextMenu(e, card.id) }}
    >
      {/* Color stripe */}
      <div className="whiteboard-card-stripe" style={{ backgroundColor: accentColor }} />

      {/* Source badge */}
      {card.role === 'parent' && detailLevel === 'full' && (
        <div className="canvas-card-role-badge parent">
          母卡 · {card.childIds?.length ?? 0} 张子卡
        </div>
      )}
      {card.parentId && detailLevel === 'full' && (
        <div className="canvas-card-role-badge child">来自母卡 · 位置固定</div>
      )}

      {/* Content */}
      {editing ? (
        <div className="whiteboard-card-body editing" style={{ flex: 1 }}>
          <textarea
            ref={editRef}
            className="whiteboard-card-edit-textarea"
            value={editText}
            onChange={(e) => setEditText(e.target.value)}
            onKeyDown={handleEditKeyDown}
            onBlur={() => onEditSave(card.id, editText)}
            onMouseDown={(e) => e.stopPropagation()}
          />
        </div>
      ) : (
        <div className="whiteboard-card-body" style={{ overflow: 'hidden', flex: 1, color: card.textColor || undefined }}>
          {detailLevel === 'compact' ? (
            <span className="whiteboard-card-compact-text">{displayText.slice(0, 60)}{displayText.length > 60 ? '...' : ''}</span>
          ) : card.id === 'demo-flowchart' ? (
            <DemoFlowchart />
          ) : card.kind === 'image' || card.kind === 'note' ? (
            // 像素不在文档里（插件 images/ 目录、原生笔记预览）：走共享白板的资源接口取。
            <CardAssetBody card={card} assetUrl={assetUrl ?? null} />
          ) : card.kind === 'conversation' && card.conversation ? (
            <ConversationCardBody card={card} />
          ) : (
            <ReactMarkdown remarkPlugins={[remarkGfm, remarkHardBreaks]} components={cardMarkdownComponents}>
              {displayText}
            </ReactMarkdown>
          )}
        </div>
      )}

      {/* Footer tags */}
      {detailLevel === 'full' && card.tags && card.tags.length > 0 && (
        <div className="whiteboard-card-footer">
          {card.tags.map(tag => <span key={tag} className="whiteboard-card-tag">{tag}</span>)}
        </div>
      )}

      {/* Resize handles */}
      {hovered && !editing && !card.parentId && !resizeLocked && (
        <>
          <div className="canvas-card-resize-handle top"
            onMouseDown={(e) => { e.stopPropagation(); onResizeStart(e, card.id, 'top') }} />
          <div className="canvas-card-resize-handle bottom"
            onMouseDown={(e) => { e.stopPropagation(); onResizeStart(e, card.id, 'bottom') }} />
          <div className="canvas-card-resize-handle left"
            onMouseDown={(e) => { e.stopPropagation(); onResizeStart(e, card.id, 'left') }} />
          <div className="canvas-card-resize-handle right"
            onMouseDown={(e) => { e.stopPropagation(); onResizeStart(e, card.id, 'right') }} />
          <div className="canvas-card-resize-handle corner-tl"
            onMouseDown={(e) => { e.stopPropagation(); onResizeStart(e, card.id, 'corner-tl') }} />
          <div className="canvas-card-resize-handle corner-tr"
            onMouseDown={(e) => { e.stopPropagation(); onResizeStart(e, card.id, 'corner-tr') }} />
          <div className="canvas-card-resize-handle corner-bl"
            onMouseDown={(e) => { e.stopPropagation(); onResizeStart(e, card.id, 'corner-bl') }} />
          <div className="canvas-card-resize-handle corner-br"
            onMouseDown={(e) => { e.stopPropagation(); onResizeStart(e, card.id, 'corner-br') }} />
        </>
      )}

      {/* Selection checkmark */}
      {selected && (
        <div className="whiteboard-card-check" style={{ background: accentColor }}>✓</div>
      )}
    </div>
  )
}, (prev, next) => (
  prev.card === next.card &&
  prev.selected === next.selected &&
  prev.cardSelected === next.cardSelected &&
  prev.resizeLocked === next.resizeLocked &&
  prev.scale === next.scale &&
  prev.editing === next.editing &&
  prev.assetUrl === next.assetUrl
))

const DraftParentCard = React.memo(({ card, onOpen, onRemove }: {
  card: ParentCard
  onOpen: (id: string) => void
  onRemove: (id: string) => void
}) => (
  <article
    className="whiteboard-card draft-parent-card"
    style={{ left: card.x, top: card.y, width: card.width, height: card.height }}
    onMouseDown={event => event.stopPropagation()}
    onDoubleClick={event => {
      event.stopPropagation()
      onOpen(card.id)
    }}
  >
    <div className="draft-parent-card-header">
      <span>草稿母卡</span>
      <button
        type="button"
        aria-label="撤销草稿母卡"
        title="撤销草稿"
        onClick={event => {
          event.stopPropagation()
          onRemove(card.id)
        }}
      >
        <X size={11} />
      </button>
    </div>
    {card.sourceLabel && <div className="draft-parent-card-source">{card.sourceLabel}</div>}
    <div className="draft-parent-card-body">
      <ReactMarkdown remarkPlugins={[remarkGfm, remarkHardBreaks]} components={cardMarkdownComponents}>
        {card.content}
      </ReactMarkdown>
    </div>
    <div className="draft-parent-card-footer">双击继续编辑 · 保存后进入 Web 白板</div>
  </article>
))

// ============================
// CanvasSelector sub-component
// ============================
const CanvasSelector = React.memo(({ canvasList, activeCanvasId, onSwitch, onCreate, onRename, onDelete }: {
  canvasList: CanvasInfo[]
  activeCanvasId: string | null
  onSwitch: (id: string) => void
  onCreate: (name: string) => void
  onRename: (id: string, name: string) => void
  onDelete: (id: string) => void
}) => {
  const [open, setOpen] = useState(false)
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const dropdownRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const activeCanvas = canvasList.find(c => c.id === activeCanvasId)

  useEffect(() => {
    const handleClick = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setOpen(false)
        setRenamingId(null)
      }
    }
    if (open) {
      document.addEventListener('mousedown', handleClick)
      return () => document.removeEventListener('mousedown', handleClick)
    }
  }, [open])

  useEffect(() => {
    if (renamingId && inputRef.current) {
      inputRef.current.focus()
      inputRef.current.select()
    }
  }, [renamingId])

  const handleCreate = () => {
    const name = `Canvas ${canvasList.length + 1}`
    onCreate(name)
    setOpen(false)
  }

  const handleRenameSubmit = (id: string) => {
    if (renameValue.trim()) onRename(id, renameValue.trim())
    setRenamingId(null)
  }

  return (
    <div className="canvas-selector" ref={dropdownRef}>
      <button className="canvas-selector-trigger" onClick={() => setOpen(!open)}>
        <span className="canvas-selector-name">{activeCanvas?.name || 'Canvas'}</span>
        <span className="canvas-selector-arrow">{open ? '▴' : '▾'}</span>
      </button>
      {open && (
        <div className="canvas-selector-dropdown">
          {canvasList.map(canvas => (
            <div key={canvas.id}
              className={`canvas-selector-item ${canvas.id === activeCanvasId ? 'active' : ''}`}
              onClick={() => { if (renamingId !== canvas.id) { onSwitch(canvas.id); setOpen(false) } }}
            >
              {renamingId === canvas.id ? (
                <input ref={inputRef} className="canvas-selector-rename-input" value={renameValue}
                  onChange={(e) => setRenameValue(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') handleRenameSubmit(canvas.id); if (e.key === 'Escape') setRenamingId(null) }}
                  onBlur={() => handleRenameSubmit(canvas.id)}
                  onClick={(e) => e.stopPropagation()} />
              ) : (
                <>
                  <span className="canvas-selector-item-name"
                    onDoubleClick={(e) => { e.stopPropagation(); setRenamingId(canvas.id); setRenameValue(canvas.name) }}>
                    {canvas.name}
                  </span>
                  <div className="canvas-selector-item-actions">
                    <button className="canvas-selector-item-btn"
                      onClick={(e) => { e.stopPropagation(); setRenamingId(canvas.id); setRenameValue(canvas.name) }}
                      title="Rename">✏️</button>
                    {canvasList.length > 1 && (
                      <button className="canvas-selector-item-btn delete"
                        onClick={(e) => { e.stopPropagation(); if (window.confirm('Delete this canvas?')) onDelete(canvas.id) }}
                        title="Delete">✕</button>
                    )}
                  </div>
                </>
              )}
            </div>
          ))}
          <div className="canvas-selector-create" onClick={handleCreate}>+ New Canvas</div>
        </div>
      )}
    </div>
  )
})

// ============================
// MiniMap sub-component
// ============================
const MINIMAP_COLORS = ['#6BA5E7', '#E8913A', '#8BC78B', '#D47DB6', '#E0D85A']

const MiniMap = React.memo(({ positions, pan, scale, viewW, viewH, onJump }: {
  positions: Record<string, { x: number; y: number; width: number; height: number }>
  pan: { x: number; y: number }
  scale: number
  viewW: number
  viewH: number
  onJump: (cx: number, cy: number) => void
}) => {
  const mapW = 180, mapH = 120
  const allPos = Object.values(positions)
  if (allPos.length === 0) return null

  const allX = allPos.map(p => p.x)
  const allY = allPos.map(p => p.y)
  const minX = Math.min(...allX) - 50
  const maxX = Math.max(...allPos.map(p => p.x + p.width)) + 50
  const minY = Math.min(...allY) - 50
  const maxY = Math.max(...allPos.map(p => p.y + p.height)) + 50
  const worldW = maxX - minX
  const worldH = maxY - minY
  const s = Math.min(mapW / worldW, mapH / worldH)

  const vpX = (-pan.x / scale - minX) * s
  const vpY = (-pan.y / scale - minY) * s
  const vpW = (viewW / scale) * s
  const vpH = (viewH / scale) * s

  const handleClick = (e: React.MouseEvent) => {
    const rect = e.currentTarget.getBoundingClientRect()
    const cx = (e.clientX - rect.left) / s + minX
    const cy = (e.clientY - rect.top) / s + minY
    onJump(cx, cy)
  }

  const cardIds = Object.keys(positions)
  return (
    <div className="whiteboard-minimap" onClick={handleClick}>
      <svg width={mapW} height={mapH}>
        {cardIds.map((id, i) => {
          const p = positions[id]
          if (!p) return null
          return (
            <rect key={id} x={(p.x - minX) * s} y={(p.y - minY) * s}
              width={p.width * s} height={p.height * s}
              fill={MINIMAP_COLORS[i % MINIMAP_COLORS.length]} fillOpacity={0.5} rx={1} />
          )
        })}
        <rect x={vpX} y={vpY} width={vpW} height={vpH}
          fill="none" stroke="var(--accent-primary)" strokeWidth={1.5} strokeOpacity={0.7} rx={2} />
      </svg>
    </div>
  )
})

// ============================
// Context Menu
// ============================
interface ContextMenuState {
  x: number
  y: number
  cardId: string
}

// ============================
// CardDetailModal sub-component
// ============================
const CardDetailModal = ({ card, onClose, onSave }: { card: Card; onClose: () => void; onSave: (cardId: string, content: string) => void }) => {
  const [isEditing, setIsEditing] = useState(false)
  const [editContent, setEditContent] = useState(card.content || '')

  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        if (isEditing) { setIsEditing(false) } else { onClose() }
      }
    }
    window.addEventListener('keydown', handleKey)
    return () => window.removeEventListener('keydown', handleKey)
  }, [onClose, isEditing])

  const handleSave = () => {
    onSave(card.id, editContent)
    setIsEditing(false)
  }

  return (
    <div className="card-detail-overlay" onClick={onClose}>
      <div className="card-detail-dialog" onClick={(e) => e.stopPropagation()}>
        <div className="card-detail-header">
          <div className="whiteboard-card-sender">
            <span className="whiteboard-card-name">{card.sourceLabel || card.sourceType}</span>
            {card.tags && card.tags.length > 0 && (
              <div style={{ display: 'flex', gap: 4, marginLeft: 8 }}>
                {card.tags.map(tag => <span key={tag} className="whiteboard-card-tag">{tag}</span>)}
              </div>
            )}
          </div>
          <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
            <button className="card-detail-mode-btn" onClick={() => { if (isEditing) handleSave(); else setIsEditing(true) }}
              title={isEditing ? 'Preview' : 'Edit'}>
              {isEditing ? <Eye size={14} /> : <Pencil size={14} />}
            </button>
            <button className="card-detail-close" onClick={onClose}><X size={16} /></button>
          </div>
        </div>
        <div className="card-detail-body">
          {isEditing ? (
            <textarea
              className="card-detail-edit-textarea"
              value={editContent}
              onChange={(e) => setEditContent(e.target.value)}
              autoFocus
            />
          ) : (
            <ReactMarkdown remarkPlugins={[remarkGfm, remarkHardBreaks]}>{editContent}</ReactMarkdown>
          )}
        </div>
        {isEditing && (
          <div className="card-detail-footer">
            <span className="card-detail-hint">Editing Markdown source</span>
            <button className="card-detail-save-btn" onClick={handleSave}>Save</button>
          </div>
        )}
      </div>
    </div>
  )
}

// 临时测试 Canvas：固定 ID 防止每次启动重复创建；不改变当前 activeCanvasId。
function ensureSparseTestCanvas(): void {
  const registry = CanvasManager.getRegistry()
  if (registry.canvasList.some(canvas => canvas.id === SPARSE_TEST_CANVAS_ID)) return

  const now = new Date().toISOString()
  registry.canvasList.push({
    id: SPARSE_TEST_CANVAS_ID,
    name: SPARSE_TEST_CANVAS_NAME,
    createdAt: now,
    updatedAt: now,
  })
  CanvasManager.saveRegistry(registry)
  CanvasManager.saveCanvasData(SPARSE_TEST_CANVAS_ID, createSparseTestCanvasData(now))
}

const LOCAL_DEVICE_CANVAS_NAME = 'Mosaic 设备'

/**
 * `#board=mosaic-local`（局域网同步，见 sharedBoard.LOCAL_ROOM_ID）：插件连上后会把整份白板推过来
 * 覆盖房间内容，所以用一张专用画布接，不碰用户别的画布。没有就建，并设为当前画布。
 */
function ensureLocalDeviceCanvas(): string {
  const existing = CanvasManager.getCanvasList().find(canvas => canvas.name === LOCAL_DEVICE_CANVAS_NAME)
  if (!existing) return CanvasManager.createCanvas(LOCAL_DEVICE_CANVAS_NAME)
  CanvasManager.setActiveCanvasId(existing.id)
  return existing.id
}

function isLocalRoomHash(): boolean {
  try { return parseSharedBoard(window.location.hash.slice(1)) === LOCAL_ROOM_ID } catch { return false }
}

// ============================
// Main Whiteboard Component
// ============================
const Whiteboard: React.FC = () => {
  const { canvasData, activeCanvasId, canvasList } = useStore(canvasStore)
  const { selectedCardIds } = useStore(selectionStore)
  const { cards: parentCards, activeParentCardId } = useStore(parentCardStore)

  // Local viewport state（本机视图状态，不参与同步：远端快照不改这里，见 boardSync 的 onSnapshot）
  const [pan, setPan] = useState({ x: 60, y: 20 })
  const [scale, setScale] = useState(DEFAULT_CANVAS_SCALE)
  // 最新 pan/scale 的 ref：滚轮监听与同步回调不重新绑定也能读到当前值。
  const viewRef = useRef({ pan, scale })
  viewRef.current = { pan, scale }

  // Interaction state
  const [dragging, setDragging] = useState<string | null>(null)
  const [panning, setPanning] = useState(false)
  const [viewportSize, setViewportSize] = useState({ width: 0, height: 0 })
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null)
  const [detailCardId, setDetailCardId] = useState<string | null>(null)
  const [activeSharedParentId, setActiveSharedParentId] = useState<string | null>(null)
  const [inkBoxOpen, setInkBoxOpen] = useState(false)
  const [eraserMode, setEraserMode] = useState(false)
  const [eraserCursor, setEraserCursor] = useState<{ x: number; y: number } | null>(null)
  const [pendingParentSave, setPendingParentSave] = useState<{ parent: ParentCard; inkCount: number } | null>(null)
  const [parentSaveBusy, setParentSaveBusy] = useState(false)

  // Resize state
  const [resizing, setResizing] = useState<string | null>(null)
  const resizeRef = useRef<{ cardId: string; handle: string; startX: number; startY: number; origW: number; origH: number; origX: number; origY: number } | null>(null)

  // Lasso state
  const [lassoStart, setLassoStart] = useState<{ x: number; y: number } | null>(null)
  const [lassoRect, setLassoRect] = useState<{ x: number; y: number; w: number; h: number } | null>(null)

  // Save indicator state
  const [saveStatus, setSaveStatus] = useState<'saved' | 'saving' | 'idle'>('idle')
  const [syncState, setSyncState] = useState<SyncConnectionState>('idle')
  // 共享白板 id（地址栏 #board=）：图片 / 笔记预览的资源地址据此拼；assetVersion 每收一次快照 +1，
  // 让 <img> 重新校验（笔记预览会被插件反复重绘）。
  const [sharedBoardId, setSharedBoardId] = useState<string | null>(null)
  const [assetVersion, setAssetVersion] = useState(0)

  // Edit state
  const [editingCardId, setEditingCardId] = useState<string | null>(null)

  // Search state
  const [searchOpen, setSearchOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')

  // Snap to grid
  const [snapToGrid, setSnapToGrid] = useState(false)

  // Liquid effect clusters
  const [liquidClusters, setLiquidClusters] = useState<Cluster[]>([])

  // Proximity auto-connect: nearest reachable bridge position while dragging
  const [proximityTarget, setProximityTarget] = useState<{ fromId: string; toId: string } | null>(null)

  // Undo/Redo
  const [undoStack, setUndoStack] = useState<any[]>([])
  const [redoStack, setRedoStack] = useState<any[]>([])

  // Import file ref
  const importFileRef = useRef<HTMLInputElement>(null)

  // Refs
  const dragRef = useRef<{ mx: number; my: number; ox: number; oy: number; groupIds: string[] } | null>(null)
  const panRef = useRef<{ mx: number; my: number; ox: number; oy: number } | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const transformLayerRef = useRef<HTMLDivElement>(null)
  const rafRef = useRef<number | null>(null)
  const syncTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const boardSyncRef = useRef<BoardSyncClient | null>(null)
  const pendingImportDocRef = useRef<BoardDoc | null>(null)
  // 地址栏 #board= 变化（「同步到设备」按钮写入）时重连同步，不必刷新页面。
  const [hashVersion, setHashVersion] = useState(0)
  useEffect(() => {
    const onHashChange = () => setHashVersion(v => v + 1)
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])
  const initializedCanvasRef = useRef<string | null>(null)
  const searchInputRef = useRef<HTMLInputElement>(null)
  const penStrokeRef = useRef<ActivePenStroke | null>(null)
  const penContactRef = useRef(false)
  const eraserModeRef = useRef(false)
  const eraserGestureRef = useRef(false)
  const erasedDuringGestureRef = useRef(false)
  // 在制笔迹：直接改 DOM 属性，不进 React 状态
  const livePolylineRef = useRef<SVGPolylineElement>(null)
  const livePointsRef = useRef('')
  const liveOffsetRef = useRef({ x: 0, y: 0 })
  // 橡皮命中索引；indexedInkRef 记录索引对应的笔迹数组引用，别处整体替换后下次橡皮时重建
  const inkIndexRef = useRef(new InkIndex())
  const indexedInkRef = useRef<InkStroke[] | null>(null)

  // Init: ensure default canvas
  useEffect(() => {
    // A one-shot URL flag gives local development a predictable way to clear
    // saved card boards before loading the reference demo.
    const resetRequested = new URLSearchParams(window.location.search).get('reset-demo') === '1'
    if (resetRequested) {
      for (const key of Object.keys(localStorage)) {
        if (key === 'wbai_canvases' || key === 'wbai_parent_cards' || key.startsWith('wbai_canvas_')) {
          localStorage.removeItem(key)
        }
      }
      localStorage.removeItem('liquidtext-demo-seeded-v1')
      window.history.replaceState({}, '', window.location.pathname)
    }
    const id = isLocalRoomHash() ? ensureLocalDeviceCanvas() : CanvasManager.ensureDefaultCanvas('Canvas 1')
    if (!resetRequested) ensureSparseTestCanvas()
    let data = CanvasManager.getCanvasData(id)
    const canvasInfo = CanvasManager.getCanvasList().find(canvas => canvas.id === id)
    const demoSeedKey = 'liquidtext-demo-seeded-v1'
    if (canvasInfo?.name === 'Canvas 1' && Object.keys(data.cards).length === 0) {
      data = createLiquidTextDemoData(new Date().toISOString())
      CanvasManager.saveCanvasData(id, data)
      localStorage.setItem(demoSeedKey, '1')
    }
    canvasActions.setActiveCanvas(id)
    canvasActions.setCanvasData(data)
    initializedCanvasRef.current = id
    canvasActions.setCanvasList(CanvasManager.getCanvasList())
    const vp = data.viewport || { panX: 60, panY: 20, scale: DEFAULT_CANVAS_SCALE }
    setPan({ x: vp.panX, y: vp.panY })
    setScale(clampScale(vp.scale))
    setLiquidClusters(detectClusters(data.cards))
  }, [])

  useEffect(() => {
    if (!BOARD_SYNC_ENABLED || !activeCanvasId) return
    const canvasId = activeCanvasId
    const roomId = (() => {
      try { return parseSharedBoard(window.location.hash.slice(1)) } catch { return canvasId }
    })()
    if (!window.location.hash.includes('board=')) return
    setSharedBoardId(roomId)
    const client = new BoardSyncClient()
    boardSyncRef.current = client
    const imported = pendingImportDocRef.current
    client.connect(roomId, imported ?? CanvasManager.getCanvasDocument(canvasId), (doc, _revision, domain) => {
      if (canvasStore.getState().activeCanvasId !== canvasId) return
      // 设备端整份推送（import）会覆盖当前画布：画布里已有内容且不是专用的设备画布时先确认，
      // 拒绝就把本机画布反推回去，避免用户自己的画布被一块空白板悄悄顶掉。
      if (domain === 'import') {
        const localDoc = CanvasManager.composeCanvasDocument(canvasId, canvasStore.getState().canvasData)
        const info = CanvasManager.getCanvasList().find(canvas => canvas.id === canvasId)
        const hasContent = localDoc.cards.length > 0 || localDoc.ink.length > 0
        if (hasContent && info?.name !== LOCAL_DEVICE_CANVAS_NAME && !window.confirm('设备端要用它的整份白板覆盖当前画布，是否接受？\n选「取消」会改为用当前画布覆盖设备端。')) {
          client.protectLocalUntilAck()
          client.publish('import', localDoc)
          return
        }
      }
      // 视口是各端自己的视图状态：插件每次改动都会把它当时的 pan/scale 写在 meta.viewport 里，
      // 直接 setPan 会让网页随插件一拖就跳，所以平移保持本机的（下面只单独跟缩放），
      // 落盘时也写回本机视口（否则刷新页面会落到插件的位置）。
      const { pan: localPan, scale: localScale } = viewRef.current
      const localViewport = { panX: localPan.x, panY: localPan.y, scale: localScale }
      // 带域的快照只合入那一域：插件写字（ink）不覆盖网页正在拖的卡片，插件移卡（structure）
      // 不覆盖网页刚画的笔迹。document / import 整份替换。
      const local = CanvasManager.composeCanvasDocument(canvasId, canvasStore.getState().canvasData)
      const merged: BoardDoc = domain === 'ink'
        ? { ...local, ink: doc.ink }
        : domain === 'structure'
          ? { ...local, cards: doc.cards, connections: doc.connections }
          : doc
      const data = CanvasManager.saveCanvasDocument(canvasId, { ...merged, meta: { ...merged.meta, viewport: localViewport } })
      canvasActions.setCanvasData(data)
      setLiquidClusters(detectClusters(data.cards))
      if (domain !== 'ink') setAssetVersion(v => v + 1)
      // 缩放跟随插件：插件每次发出的快照都带着它当时的缩放（meta.viewport.scale）。只跟缩放、
      // 不跟平移（两端屏幕尺寸不同，跟平移会跳），围绕本机画布中心缩放。
      const remoteScale = doc.meta.viewport?.scale
      if (remoteScale && Number.isFinite(remoteScale) && Math.abs(remoteScale / viewRef.current.scale - 1) > 0.005) {
        zoomAt(remoteScale)
      }
    }, undefined, state => {
      setSyncState(state)
      recordSyncLog('ui.state', { state })
    })
    if (imported) {
      client.protectLocalUntilAck()
      client.publish('import', imported)
      pendingImportDocRef.current = null
    }
    return () => {
      client.close()
      if (boardSyncRef.current === client) boardSyncRef.current = null
    }
  }, [activeCanvasId, hashVersion])

  useEffect(() => {
    if (!activeCanvasId) return
    if (initializedCanvasRef.current !== activeCanvasId) return
    const activeInfo = CanvasManager.getCanvasList().find(canvas => canvas.id === activeCanvasId)
    if (activeInfo?.name === 'Canvas 1' && Object.keys(canvasData.cards).length === 0) return
    const canvasId = activeCanvasId
    if (syncTimeoutRef.current) clearTimeout(syncTimeoutRef.current)
    syncTimeoutRef.current = setTimeout(() => {
      if (canvasStore.getState().activeCanvasId !== canvasId) return
      const current = canvasStore.getState().canvasData
      const saved = { ...current, viewport: { panX: pan.x, panY: pan.y, scale } }
      CanvasManager.saveCanvasData(canvasId, saved)
      boardSyncRef.current?.publish('structure', CanvasManager.composeCanvasDocument(canvasId, saved))
    }, 300)
    return () => {
      if (syncTimeoutRef.current) clearTimeout(syncTimeoutRef.current)
    }
  }, [activeCanvasId, canvasData, pan.x, pan.y, scale])

  // Observe container size
  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const observer = new ResizeObserver(entries => {
      const { width, height } = entries[0].contentRect
      setViewportSize({ width, height })
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  /** 围绕容器内某个屏幕点缩放：该点下的世界坐标保持不动。 */
  const zoomAt = useCallback((nextScaleRaw: number, clientX?: number, clientY?: number) => {
    const el = containerRef.current
    if (!el) return
    const { pan: currentPan, scale: currentScale } = viewRef.current
    const nextScale = clampScale(nextScaleRaw)
    if (nextScale === currentScale) return
    const rect = el.getBoundingClientRect()
    const ax = clientX === undefined ? rect.width / 2 : clientX - rect.left
    const ay = clientY === undefined ? rect.height / 2 : clientY - rect.top
    const worldX = (ax - currentPan.x) / currentScale
    const worldY = (ay - currentPan.y) / currentScale
    setScale(nextScale)
    setPan({ x: ax - worldX * nextScale, y: ay - worldY * nextScale })
  }, [])

  // 鼠标滚轮缩放（10%–200%）。React 的 onWheel 是 passive 的，preventDefault 拦不住页面滚动，
  // 这里直接挂原生监听。
  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      if (e.deltaY === 0) return
      e.preventDefault()
      // 触控板像素级 delta 很小、鼠标一格约 100：按 delta 大小平滑折算成倍率。
      const factor = Math.pow(WHEEL_ZOOM_STEP, -e.deltaY / 100)
      zoomAt(viewRef.current.scale * factor, e.clientX, e.clientY)
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [zoomAt])

  // Cleanup：只取消在制动画；画布内容由显式保存提交。
  useEffect(() => {
    return () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current)
    }
  }, [])

  // 页面隐藏 / 卸载前把防抖中的笔迹落盘
  useEffect(() => {
    const flush = () => CanvasManager.flushInkPersist()
    const onVisibility = () => { if (document.visibilityState === 'hidden') flush() }
    window.addEventListener('pagehide', flush)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.removeEventListener('pagehide', flush)
      document.removeEventListener('visibilitychange', onVisibility)
      flush()
    }
  }, [])

  // Undo helper
  const pushUndo = useCallback(() => {
    if (!activeCanvasId) return
    const snapshot = JSON.parse(JSON.stringify(canvasData))
    setUndoStack(prev => [...prev.slice(-49), snapshot])
    setRedoStack([])
  }, [activeCanvasId, canvasData])

  const handleUndo = useCallback(() => {
    if (undoStack.length === 0 || !activeCanvasId) return
    const prev = undoStack[undoStack.length - 1]
    setRedoStack(r => [...r, JSON.parse(JSON.stringify(canvasData))])
    setUndoStack(s => s.slice(0, -1))
    canvasActions.setCanvasData(prev)
    CanvasManager.saveCanvasData(activeCanvasId, prev)
  }, [undoStack, activeCanvasId, canvasData])

  const handleRedo = useCallback(() => {
    if (redoStack.length === 0 || !activeCanvasId) return
    const next = redoStack[redoStack.length - 1]
    setUndoStack(s => [...s, JSON.parse(JSON.stringify(canvasData))])
    setRedoStack(r => r.slice(0, -1))
    canvasActions.setCanvasData(next)
    CanvasManager.saveCanvasData(activeCanvasId, next)
  }, [redoStack, activeCanvasId, canvasData])

  // ── Canvas switching ──
  const switchCanvas = useCallback((canvasId: string) => {
    CanvasManager.setActiveCanvasId(canvasId)
    const newData = CanvasManager.getCanvasData(canvasId)
    canvasActions.setActiveCanvas(canvasId)
    canvasActions.setCanvasData(newData)
    setPan({ x: newData.viewport?.panX || 60, y: newData.viewport?.panY || 20 })
    setScale(clampScale(newData.viewport?.scale))
    selectionActions.clearSelection()
  }, [])

  const createCanvas = useCallback((name: string) => {
    const id = CanvasManager.createCanvas(name)
    canvasActions.setCanvasList(CanvasManager.getCanvasList())
    switchCanvas(id)
  }, [switchCanvas])

  const renameCanvas = useCallback((id: string, name: string) => {
    CanvasManager.renameCanvas(id, name)
    canvasActions.setCanvasList(CanvasManager.getCanvasList())
  }, [])

  const deleteCanvas = useCallback((id: string) => {
    const newActiveId = CanvasManager.deleteCanvas(id)
    canvasActions.setCanvasList(CanvasManager.getCanvasList())
    if (newActiveId) {
      switchCanvas(newActiveId)
    } else {
      const newId = CanvasManager.createCanvas('Canvas 1')
      canvasActions.setCanvasList(CanvasManager.getCanvasList())
      switchCanvas(newId)
    }
  }, [switchCanvas])

  // ── Viewport culling（分块索引；平移每帧只扫描相交区块，不再遍历全部卡片）──
  const navigationViewport = useMemo(() => {
    if (!viewportSize.width) return null
    return viewportWorldRect(pan.x, pan.y, scale, viewportSize.width, viewportSize.height)
  }, [pan, scale, viewportSize])

  const viewportBounds = useMemo(() => {
    if (!viewportSize.width) return null
    return viewportWorldRect(pan.x, pan.y, scale, viewportSize.width, viewportSize.height, 400)
  }, [pan, scale, viewportSize])

  const allCards = useMemo(() => Object.values(canvasData.cards || {}), [canvasData.cards])
  const draftParentCards = useMemo(
    () => parentCards.filter(card => card.status === 'draft' && card.canvasId === activeCanvasId),
    [activeCanvasId, parentCards],
  )
  const chunkIndex = useMemo(() => buildChunkIndex(allCards.map(card => ({
    id: card.id,
    x: card.x,
    y: card.y,
    ...resolveCardSize(card),
  }))), [allCards])

  const visibleCards = useMemo(() => {
    if (!viewportBounds) return allCards
    const cards = canvasData.cards || {}
    return queryRect(chunkIndex, viewportBounds)
      .map(id => cards[id])
      .filter((card): card is Card => card !== undefined)
  }, [allCards, canvasData.cards, chunkIndex, viewportBounds])

  // ── 稀疏画布区域：占用区块聚成内容岛，跨大片空白时给方向跳转而非连续拖动 ──
  const regions = useMemo(() => detectRegions(chunkIndex), [chunkIndex])

  const regionJumps = useMemo(() => {
    if (!navigationViewport || regions.length === 0) return []
    return findRegionJumps(regions, navigationViewport, SPARSE_PAN_MARGIN)
  }, [navigationViewport, regions])

  const jumpToRegion = useCallback((region: CanvasRegion) => {
    const { panX, panY } = panToCenterRect(
      region.bounds,
      viewportSize.width,
      viewportSize.height,
      scale,
    )
    setPan({ x: panX, y: panY })
  }, [scale, viewportSize])

  const visibleConnections = useMemo(() => {
    if (!viewportBounds) return canvasData.connections || []
    const visibleSet = new Set(visibleCards.map(c => c.id))
    return (canvasData.connections || []).filter(conn =>
      visibleSet.has(conn.fromCardId) || visibleSet.has(conn.toCardId)
    )
  }, [canvasData.connections, visibleCards, viewportBounds])

  const liquidConnections = useMemo(() => {
    const connections = visibleConnections.filter(connection => !connection.kind || connection.kind === 'default')
    // Show the same neck while a card is being brought into snap range.  The
    // synthetic edge is render-only and becomes persistent in handleUp once
    // the pointer is released, so the curve follows the card continuously.
    if (proximityTarget) {
      const exists = connections.some(connection =>
        (connection.fromCardId === proximityTarget.fromId && connection.toCardId === proximityTarget.toId) ||
        (connection.fromCardId === proximityTarget.toId && connection.toCardId === proximityTarget.fromId),
      )
      if (!exists && canvasData.cards[proximityTarget.fromId] && canvasData.cards[proximityTarget.toId]) {
        connections.push({
          id: `preview-${proximityTarget.fromId}-${proximityTarget.toId}`,
          fromCardId: proximityTarget.fromId,
          toCardId: proximityTarget.toId,
          color: '#6BA5E7',
          label: '',
        })
      }
    }
    return connections
  }, [visibleConnections, proximityTarget, canvasData.cards])

  /** 图片 / 笔记卡的共享资源地址；非共享白板或非资源卡为 null。 */
  const assetUrlFor = useCallback((card: Card): string | null => {
    if (!sharedBoardId) return null
    const ref = boardAssetRef(card)
    return ref === null ? null : boardAssetUrl(sharedBoardId, ref, assetVersion)
  }, [assetVersion, sharedBoardId])

  const cardPositions = useMemo(() => {
    const result: Record<string, { x: number; y: number; width: number; height: number }> = {}
    allCards.forEach(card => { result[card.id] = { x: card.x, y: card.y, ...resolveCardSize(card) } })
    draftParentCards.forEach(card => {
      result[card.id] = { x: card.x, y: card.y, width: card.width, height: card.height }
    })
    return result
  }, [allCards, draftParentCards])

  // ── Interaction handlers ──

  const handleResizeStart = useCallback((e: React.MouseEvent, cardId: string, handle: string) => {
    e.stopPropagation()
    const card = canvasData.cards[cardId]
    if (!card) return
    if (card.parentId && canvasData.cards[card.parentId]?.role === 'parent') return
    if (cardResizeLocked(cardId, canvasData.cards, canvasData.connections || [])) return
    setResizing(cardId)
    resizeRef.current = {
      cardId, handle,
      startX: e.clientX, startY: e.clientY,
      origW: resolveCardSize(card).width, origH: resolveCardSize(card).height,
      origX: card.x, origY: card.y,
    }
  }, [canvasData.cards])

  const handleCardDrag = useCallback((e: React.MouseEvent, cardId: string) => {
    e.stopPropagation()
    e.preventDefault()
    const card = canvasData.cards[cardId]
    if (!card) return
    // 裂出的子卡在母卡仍存在时保持原位；删除母卡会解除 parentId，随后可自由移动。
    if (card.parentId && canvasData.cards[card.parentId]?.role === 'parent') {
      selectionActions.setSelected([cardId])
      return
    }
    setDragging(cardId)
    dragRef.current = {
      mx: e.clientX,
      my: e.clientY,
      ox: card.x,
      oy: card.y,
      groupIds: connectedCardIds(cardId, canvasData.cards, canvasData.connections || []),
    }
  }, [canvasData.cards, canvasData.connections])

  const handlePanStart = useCallback((e: React.MouseEvent) => {
    // Close context menu on any click
    if (contextMenu) setContextMenu(null)

    const target = e.target as HTMLElement
    if (target === containerRef.current || target.dataset?.canvas) {
      e.preventDefault()
      // Alt+drag = lasso
      if (e.altKey) {
        const rect = containerRef.current!.getBoundingClientRect()
        const x = (e.clientX - rect.left - pan.x) / scale
        const y = (e.clientY - rect.top - pan.y) / scale
        setLassoStart({ x, y })
        setLassoRect({ x, y, w: 0, h: 0 })
        return
      }
      setPanning(true)
      panRef.current = { mx: e.clientX, my: e.clientY, ox: pan.x, oy: pan.y }
    }
  }, [contextMenu, pan, scale])

  const handleMove = useCallback((e: React.MouseEvent) => {
    // Lasso drag
    if (lassoStart) {
      const rect = containerRef.current!.getBoundingClientRect()
      const currentX = (e.clientX - rect.left - pan.x) / scale
      const currentY = (e.clientY - rect.top - pan.y) / scale
      const x = Math.min(lassoStart.x, currentX)
      const y = Math.min(lassoStart.y, currentY)
      const w = Math.abs(currentX - lassoStart.x)
      const h = Math.abs(currentY - lassoStart.y)
      setLassoRect({ x, y, w, h })
      return
    }

    // Resize
    if (resizing !== null && resizeRef.current) {
      const clientX = e.clientX
      const clientY = e.clientY
      if (rafRef.current) cancelAnimationFrame(rafRef.current)
      rafRef.current = requestAnimationFrame(() => {
        if (!resizeRef.current) return
        const r = resizeRef.current
        const dx = (clientX - r.startX) / scale
        const dy = (clientY - r.startY) / scale
        let newW = r.origW
        let newH = r.origH
        let newX = r.origX
        let newY = r.origY
        const h = r.handle
        // Right edge
        if (h === 'right' || h === 'corner-br' || h === 'corner-tr') newW = Math.max(180, r.origW + dx)
        // Left edge
        if (h === 'left' || h === 'corner-bl' || h === 'corner-tl') {
          newW = Math.max(180, r.origW - dx)
          newX = r.origX + (r.origW - newW)
        }
        // Bottom edge
        if (h === 'bottom' || h === 'corner-br' || h === 'corner-bl') newH = Math.max(80, r.origH + dy)
        // Top edge
        if (h === 'top' || h === 'corner-tl' || h === 'corner-tr') {
          newH = Math.max(80, r.origH - dy)
          newY = r.origY + (r.origH - newH)
        }
        const latestData = canvasStore.getState().canvasData
        const updatedCards = { ...latestData.cards }
        const card = updatedCards[r.cardId]
        if (!card) return
        updatedCards[r.cardId] = { ...card, width: newW, height: newH, x: newX, y: newY }
        canvasActions.setCanvasData({ ...latestData, cards: updatedCards })
      })
      return
    }

    // Card drag
    if (dragging !== null && dragRef.current) {
      const clientX = e.clientX
      const clientY = e.clientY
      if (rafRef.current) cancelAnimationFrame(rafRef.current)
      rafRef.current = requestAnimationFrame(() => {
        if (!dragRef.current) return
        const dx = (clientX - dragRef.current.mx) / scale
        const dy = (clientY - dragRef.current.my) / scale
        let newX = dragRef.current.ox + dx
        let newY = dragRef.current.oy + dy
        if (snapToGrid) {
          const GRID = 20
          newX = Math.round(newX / GRID) * GRID
          newY = Math.round(newY / GRID) * GRID
        }
        const latestData = canvasStore.getState().canvasData
        if (!latestData.cards[dragging]) return
        const groupIds = dragRef.current.groupIds
        const draggedCard = { ...latestData.cards[dragging], x: newX, y: newY }
        const snap = groupIds.length === 1
          ? findCardSnap(Object.values(latestData.cards), draggedCard, newX, newY)
          : { x: newX, y: newY, targetId: null }
        newX = snap.x
        newY = snap.y
        const updatedCards = { ...latestData.cards }
        const moveDx = newX - latestData.cards[dragging].x
        const moveDy = newY - latestData.cards[dragging].y
        for (const id of groupIds) {
          const groupCard = latestData.cards[id]
          if (!groupCard) continue
          updatedCards[id] = {
            ...groupCard,
            x: groupCard.x + moveDx,
            y: groupCard.y + moveDy,
          }
        }
        updatedCards[dragging] = { ...draggedCard, x: newX, y: newY }
        setProximityTarget(snap.targetId ? { fromId: dragging, toId: snap.targetId } : null)
        if (activeCanvasId && (moveDx !== 0 || moveDy !== 0) && groupIds.length > 1) {
          const groupSet = new Set(groupIds)
          const connectionIds = new Set((latestData.connections || [])
            .filter(connection => groupSet.has(connection.fromCardId) || groupSet.has(connection.toCardId))
            .map(connection => `connection:${connection.id}`))
          const ink = CanvasManager.getCanvasInk(activeCanvasId)
          if (ink.some(stroke => connectionIds.has(stroke.space))) {
            CanvasManager.setCanvasInk(activeCanvasId, ink.map(stroke =>
              connectionIds.has(stroke.space) ? shiftWorldInk(stroke, moveDx, moveDy) : stroke,
            ))
          }
        }
        canvasActions.setCanvasData({
          ...latestData,
          cards: updatedCards,
        })
      })
    } else if (panning && panRef.current) {
      const clientX = e.clientX
      const clientY = e.clientY
      if (rafRef.current) cancelAnimationFrame(rafRef.current)
      rafRef.current = requestAnimationFrame(() => {
        if (!panRef.current) return
        // 与插件白板一致：平移不设边界。
        setPan({
          x: panRef.current.ox + clientX - panRef.current.mx,
          y: panRef.current.oy + clientY - panRef.current.my,
        })
      })
    }
  }, [dragging, panning, resizing, scale, pan, lassoStart, canvasData, snapToGrid, viewportSize, activeCanvasId])

  const handleUp = useCallback(() => {
    // Lasso finish
    if (lassoStart && lassoRect && lassoRect.w > 5 && lassoRect.h > 5) {
      const selected: string[] = []
      allCards.forEach(card => {
        const { width: cw, height: ch } = resolveCardSize(card)
        if (card.x + cw > lassoRect.x && card.x < lassoRect.x + lassoRect.w &&
            card.y + ch > lassoRect.y && card.y < lassoRect.y + lassoRect.h) {
          selected.push(card.id)
        }
      })
      if (selected.length > 0) selectionActions.setSelected(selected)
    }
    setLassoStart(null)
    setLassoRect(null)

    if ((dragging !== null || resizing !== null) && activeCanvasId) {
      // Settle the final bridge position and persist the connection together;
      // CanvasManager.addConnection reads stale localStorage during a drag.
      if (dragging !== null) {
        const currentData = canvasStore.getState().canvasData
        const card = currentData.cards[dragging]
        if (card) {
          const groupIds = dragRef.current?.groupIds ?? [dragging]
          const snap = groupIds.length === 1
            ? findCardSnap(Object.values(currentData.cards), card, card.x, card.y)
            : { x: card.x, y: card.y, targetId: null }
          const cards = snap.targetId && (snap.x !== card.x || snap.y !== card.y)
            ? { ...currentData.cards, [dragging]: { ...card, x: snap.x, y: snap.y } }
            : currentData.cards
          let connections = currentData.connections || []
          if (snap.targetId) {
            const alreadyExists = connections.some(
              c => (c.fromCardId === dragging && c.toCardId === snap.targetId) ||
                   (c.fromCardId === snap.targetId && c.toCardId === dragging)
            )
            if (!alreadyExists) {
              const connId = 'conn-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6)
              connections = [...connections, {
                id: connId,
                fromCardId: dragging,
                toCardId: snap.targetId,
                color: '#6BA5E7',
                label: '',
              }]
            }
          }
          if (cards !== currentData.cards || connections !== currentData.connections) {
            canvasActions.setCanvasData({ ...currentData, cards, connections })
          }
        }
      }
    }
    // Recompute liquid clusters on drag/resize end
    if (dragging !== null || resizing !== null) {
      setLiquidClusters(detectClusters(canvasStore.getState().canvasData.cards))
    }
    setProximityTarget(null)

    setDragging(null)
    setResizing(null)
    setPanning(false)
    dragRef.current = null
    panRef.current = null
    resizeRef.current = null
  }, [dragging, resizing, panning, activeCanvasId, lassoStart, lassoRect, allCards, jumpToRegion])

  // ========== EMR pen ink capture ==========
  const screenToWorld = useCallback((clientX: number, clientY: number) => {
    const rect = transformLayerRef.current?.getBoundingClientRect()
    if (!rect) return { x: 0, y: 0 }
    return {
      x: (clientX - rect.left) / scale,
      y: (clientY - rect.top) / scale,
    }
  }, [scale])

  const updateEraserCursor = useCallback((e: React.PointerEvent, visible: boolean) => {
    if (!visible) {
      setEraserCursor(null)
      return
    }
    const rect = containerRef.current?.getBoundingClientRect()
    if (rect) setEraserCursor({ x: e.clientX - rect.left, y: e.clientY - rect.top })
  }, [])

  const beginLiveStroke = useCallback((point: StrokePoint, offsetX: number, offsetY: number) => {
    liveOffsetRef.current = { x: offsetX, y: offsetY }
    livePointsRef.current = `${point.x + offsetX},${point.y + offsetY}`
    livePolylineRef.current?.setAttribute('points', livePointsRef.current)
  }, [])

  const appendLiveStroke = useCallback((point: StrokePoint) => {
    const offset = liveOffsetRef.current
    livePointsRef.current += ` ${point.x + offset.x},${point.y + offset.y}`
    livePolylineRef.current?.setAttribute('points', livePointsRef.current)
  }, [])

  const clearLiveStroke = useCallback(() => {
    if (livePointsRef.current === '') return
    livePointsRef.current = ''
    livePolylineRef.current?.setAttribute('points', '')
  }, [])

  /** 橡皮命中：网格索引给出候选，再做精确的线段距离判定。 */
  const eraseAt = useCallback((world: { x: number; y: number }) => {
    if (!activeCanvasId) return
    const ink = CanvasManager.getCanvasInk(activeCanvasId)
    if (indexedInkRef.current !== ink) {
      inkIndexRef.current.rebuild(ink)
      indexedInkRef.current = ink
    }
    const cards = canvasStore.getState().canvasData.cards || {}
    const radius = ERASER_RADIUS_PX / scale
    const candidates = inkIndexRef.current.hitTest(world, radius, Object.values(cards))
    const hits = candidates.filter(stroke => strokeTouchesEraser(stroke, world, cards, radius))
    recordPenDiagnostic(hits.length === 0 ? 'erase-miss' : 'erase-hit', {
      worldX: world.x,
      worldY: world.y,
      radius,
      candidates: candidates.length,
      hits: hits.length,
      before: ink.length,
    })
    if (hits.length === 0) return
    erasedDuringGestureRef.current = true
    for (const stroke of hits) inkIndexRef.current.remove(stroke.id)
    CanvasManager.removeInkStrokes(activeCanvasId, hits.map(stroke => stroke.id))
    indexedInkRef.current = CanvasManager.getCanvasInk(activeCanvasId)
  }, [activeCanvasId, scale])

  const handlePenDown = useCallback((e: React.PointerEvent) => {
    e.preventDefault()
    e.stopPropagation()
    e.currentTarget.setPointerCapture?.(e.pointerId)
    penContactRef.current = true
    const world = screenToWorld(e.clientX, e.clientY)
    const erasing = eraserModeRef.current || isPenSideButton(e)
    recordPenDecision('down-decision', e, {
      sideButton: isPenSideButton(e),
      eraserMode: eraserModeRef.current,
      erasing,
      penContact: penContactRef.current,
    })
    if (erasing) {
      penStrokeRef.current = null
      clearLiveStroke()
      eraserGestureRef.current = true
      erasedDuringGestureRef.current = false
      updateEraserCursor(e, true)
      eraseAt(world)
      return
    }
    const cards = canvasData.cards || {}
    let hitCard: Card | null = null
    let topZ = -1
    for (const card of Object.values(cards)) {
      const size = resolveCardSize(card)
      if (world.x >= card.x && world.x <= card.x + size.width &&
          world.y >= card.y && world.y <= card.y + size.height &&
          (card.zIndex ?? 0) > topZ) {
        hitCard = card
        topZ = card.zIndex ?? 0
      }
    }
    const connectionId = hitCard ? null : connectionInkAtPoint(world, cards, canvasData.connections || [])
    const space = hitCard ? `card:${hitCard.id}` : connectionId ? `connection:${connectionId}` : 'canvas'
    const point: StrokePoint = hitCard
      ? { x: world.x - hitCard.x, y: world.y - hitCard.y, p: e.pressure }
      : { x: world.x, y: world.y, p: e.pressure }
    penStrokeRef.current = {
      id: 'stroke-' + generateId(),
      space,
      points: [point],
    }
    beginLiveStroke(point, hitCard?.x ?? 0, hitCard?.y ?? 0)
  }, [beginLiveStroke, canvasData.cards, canvasData.connections, clearLiveStroke, eraseAt, screenToWorld, updateEraserCursor])

  const handlePenMove = useCallback((e: React.PointerEvent) => {
    const sideButton = isPenSideButton(e)
    const erasing = eraserModeRef.current || eraserGestureRef.current || sideButton
    if (sideButton || eraserGestureRef.current) {
      recordPenDecision('move-eraser-decision', e, {
        sideButton,
        eraserMode: eraserModeRef.current,
        eraserGesture: eraserGestureRef.current,
        penContact: penContactRef.current,
        erasing,
      })
    }
    updateEraserCursor(e, erasing)
    if (erasing) {
      if (!eraserGestureRef.current) {
        eraserGestureRef.current = true
        erasedDuringGestureRef.current = false
      }
      if (penStrokeRef.current) {
        penStrokeRef.current = null
        clearLiveStroke()
      }
      if (penContactRef.current || e.pressure > 0 || (e.buttons & 1) !== 0) {
        eraseAt(screenToWorld(e.clientX, e.clientY))
      }
      return
    }
    const stroke = penStrokeRef.current
    if (!stroke) return
    const world = screenToWorld(e.clientX, e.clientY)
    const cards = canvasData.cards || {}
    const cardId = stroke.space.startsWith('card:') ? stroke.space.slice(5) : null
    const card = cardId ? cards[cardId] : null
    const point: StrokePoint = card
      ? { x: world.x - card.x, y: world.y - card.y, p: e.pressure }
      : { x: world.x, y: world.y, p: e.pressure }
    stroke.points.push(point)
    appendLiveStroke(point)
  }, [appendLiveStroke, canvasData.cards, clearLiveStroke, eraseAt, screenToWorld, updateEraserCursor])

  const handlePenUp = useCallback((e: React.PointerEvent) => {
    recordPenDecision('up-decision', e, {
      penContact: penContactRef.current,
      eraserGesture: eraserGestureRef.current,
      liveStroke: penStrokeRef.current !== null,
    })
    if (e.type !== 'pointercancel' && e.buttons !== 0) return
    penContactRef.current = false
    if (eraserGestureRef.current) {
      eraserGestureRef.current = false
      penStrokeRef.current = null
      clearLiveStroke()
      updateEraserCursor(e, eraserModeRef.current)
      if (erasedDuringGestureRef.current && activeCanvasId) {
        boardSyncRef.current?.publish(
          'ink',
          CanvasManager.composeCanvasDocument(activeCanvasId, canvasStore.getState().canvasData),
        )
      }
      erasedDuringGestureRef.current = false
      return
    }
    const stroke = penStrokeRef.current
    penStrokeRef.current = null
    clearLiveStroke()
    if (!stroke || stroke.points.length < 2 || !activeCanvasId) return
    const committed: InkStroke = {
      id: stroke.id,
      space: stroke.space,
      pen: 16,
      drawPathWidth: Math.round(PEN_WIDTH * 100),
      sampleScale: 1,
      color: INK_ARGB_BLACK,
      width: PEN_WIDTH,
      bounds: computeStrokeBounds(stroke.points),
      encoding: STROKE_ENCODING_F32X3,
      points: packStrokePoints(stroke.points),
      createdAt: new Date().toISOString(),
    }
    CanvasManager.addInkStroke(activeCanvasId, committed)
    if (committed.space.startsWith('connection:')) {
      const connectionId = committed.space.slice('connection:'.length)
      const current = canvasStore.getState().canvasData
      const connections = current.connections.map(connection =>
        connection.id === connectionId && !connection.locked ? { ...connection, locked: true } : connection,
      )
      if (connections.some((connection, index) => connection !== current.connections[index])) {
        canvasActions.setCanvasData({ ...current, connections })
      }
    }
    if (indexedInkRef.current !== null) {
      inkIndexRef.current.add(committed)
      indexedInkRef.current = CanvasManager.getCanvasInk(activeCanvasId)
    }
    boardSyncRef.current?.publish(
      'ink',
      CanvasManager.composeCanvasDocument(activeCanvasId, canvasStore.getState().canvasData),
    )
  }, [activeCanvasId, clearLiveStroke, updateEraserCursor])

  const toggleEraserMode = useCallback(() => {
    const next = !eraserModeRef.current
    eraserModeRef.current = next
    setEraserMode(next)
    if (!next) setEraserCursor(null)
  }, [])

  // ── Export / Import ──
  const handleExport = useCallback(() => {
    const data = CanvasManager.exportAllData()
    const json = JSON.stringify(data, null, 2)
    const blob = new Blob([json], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `whiteboard-${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    URL.revokeObjectURL(url)
  }, [])

  const handleImport = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = () => {
      try {
        const parsed = JSON.parse(reader.result as string)
        CanvasManager.importAllData(parsed)
        const reg = CanvasManager.getRegistry()
        canvasActions.setCanvasList(reg.canvasList)
        const aid = reg.activeCanvasId || (reg.canvasList[0]?.id)
        if (aid) {
          const previousId = canvasStore.getState().activeCanvasId
          const data = CanvasManager.getCanvasData(aid)
          const document = CanvasManager.composeCanvasDocument(aid, data)
          const shared = window.location.hash.includes('board=')
          if (shared && aid === previousId) {
            boardSyncRef.current?.protectLocalUntilAck()
            boardSyncRef.current?.publish('import', document)
            pendingImportDocRef.current = null
            recordSyncLog('import.publish', { cards: document.cards.length, ink: document.ink.length })
          } else if (shared) {
            pendingImportDocRef.current = document
            recordSyncLog('import.publish', { cards: document.cards.length, ink: document.ink.length })
          }
          canvasActions.setActiveCanvas(aid)
          canvasActions.setCanvasData(data)
          setPan({ x: data.viewport.panX, y: data.viewport.panY })
          setScale(clampScale(data.viewport.scale))
          setLiquidClusters(detectClusters(data.cards))
        }
      } catch (err) {
        console.error('Import failed:', err)
      }
    }
    reader.readAsText(file)
    e.target.value = ''
  }, [])

  // ── Card edit save ──
  const handleEditSave = useCallback((cardId: string, newContent: string) => {
    if (!activeCanvasId) return
    pushUndo()
    CanvasManager.updateCard(activeCanvasId, cardId, { content: newContent })
    canvasActions.setCanvasData(CanvasManager.getCanvasData(activeCanvasId))
    setEditingCardId(null)
  }, [activeCanvasId, pushUndo])

  // ── Card actions ──
  const handleRemoveCard = useCallback((cardId: string) => {
    if (!activeCanvasId) return
    pushUndo()
    const latest = canvasStore.getState().canvasData
    if (!latest.cards[cardId]) return
    const removedConnectionIds = new Set(
      latest.connections
        .filter(connection => connection.fromCardId === cardId || connection.toCardId === cardId)
        .map(connection => `connection:${connection.id}`),
    )
    const nextData = removeCardsWithRelationships(latest, [cardId])
    const ink = CanvasManager.getCanvasInk(activeCanvasId)
    CanvasManager.setCanvasInk(activeCanvasId, ink.filter(stroke => !removedConnectionIds.has(stroke.space)))
    CanvasManager.saveCanvasData(activeCanvasId, nextData)
    canvasActions.setCanvasData(nextData)
    selectionActions.setSelected(selectedCardIds.filter(id => id !== cardId))
  }, [activeCanvasId, selectedCardIds, pushUndo])

  const handleMoveCardToBox = useCallback(async (cardId: string) => {
    if (!activeCanvasId) return
    const card = canvasStore.getState().canvasData.cards[cardId]
    if (!card) return
    setContextMenu(null)
    const size = resolveCardSize(card)
    const content = card.content.trim() || card.sourceLabel?.trim() || '图片卡片'
    const saved = await saveToBox(content, card.sourceType, card.sourceLabel, size, card.tags)
    if (!saved) {
      window.alert('移动到 CARD Box 失败，请检查卡片盒服务。')
      return
    }
    const latest = canvasStore.getState().canvasData
    if (!latest.cards[cardId]) return
    pushUndo()
    const nextData = removeCardsWithRelationships(latest, [cardId])
    CanvasManager.saveCanvasData(activeCanvasId, nextData)
    canvasActions.setCanvasData(nextData)
    selectionActions.setSelected(selectedCardIds.filter(id => id !== cardId))
    panelActions.setLeft(false)
  }, [activeCanvasId, selectedCardIds, pushUndo])

  const handleSizeChange = useCallback((cardId: string, newPreset: SizePresetKey) => {
    if (!activeCanvasId) return
    const preset = SIZE_PRESETS[newPreset] || SIZE_PRESETS.default
    const latestData = canvasStore.getState().canvasData
    const cards = { ...latestData.cards }
    const card = cards[cardId]
    if (!card) return
    if (card.parentId && cards[card.parentId]?.role === 'parent') return
    const { width: oldW, height: oldH } = resolveCardSize(card)
    const newW = preset.width
    const newH = preset.height
    const dx = (oldW - newW) / 2
    const dy = (oldH - newH) / 2
    cards[cardId] = { ...card, sizePreset: newPreset, width: newW, height: preset.height, x: card.x + dx, y: card.y + dy }
    const newData = { ...latestData, cards }
    canvasActions.setCanvasData(newData)
  }, [activeCanvasId])

  const commitParentCard = useCallback(async (
    parent: ParentCard,
    inkPolicy: 'archive' | 'discard',
  ) => {
    setParentSaveBusy(true)
    try {
      if (!activeCanvasId || parent.canvasId !== activeCanvasId) throw new Error('请切换到草稿所属白板后保存')
      const canvasId = parent.canvasId
      const current = canvasStore.getState().canvasData
      const highestZ = Object.values(current.cards).reduce((value, card) => Math.max(value, card.zIndex), 0)
      const sharedParent: Card = {
        id: parent.id,
        ...createCard(parent.content, parent.sourceType ?? 'manual', {
          x: parent.x,
          y: parent.y,
          width: parent.width,
          height: parent.height,
          zIndex: highestZ + 1,
          sourceLabel: parent.sourceLabel,
          role: 'parent',
          childIds: [],
          extractedRanges: parent.extractedRanges,
        }),
      }

      // 合并本地完成（不依赖已禁用的 WS 协同，见 boardSyncClient.ts 的 BOARD_SYNC_ENABLED）。
      // 归档笔迹是唯一必须落服务端的一步，走独立 REST；归档失败时保留原卡与笔迹，避免数据丢失。
      const sourceIds = new Set(parent.sourceChildIds)
      const ink = CanvasManager.getCanvasInk(canvasId)
      const strokesByChild = new Map<string, typeof ink>()
      for (const stroke of ink) {
        if (!stroke.space.startsWith('card:')) continue
        const childId = stroke.space.slice(5)
        if (!sourceIds.has(childId)) continue
        const list = strokesByChild.get(childId) ?? []
        list.push(stroke)
        strokesByChild.set(childId, list)
      }
      if (inkPolicy === 'archive') {
        for (const [childId, strokes] of strokesByChild) {
          const childCard = current.cards[childId]
          if (!childCard) continue
          const size = resolveCardSize(childCard)
          const response = await fetch('/api/ink-box/archive', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              archiveId: `inkbox-${parent.id}-${childId}`,
              boardId: canvasId,
              cardId: childId,
              content: childCard.content,
              width: size.width,
              height: size.height,
              strokes: strokes.map(stroke => ({ ...stroke, points: bytesToBase64(stroke.points) })),
            }),
          })
          if (!response.ok) {
            const payload = await response.json().catch(() => ({}))
            throw new Error(payload.error || '笔迹归档失败，母卡尚未保存')
          }
        }
      }

      if (canvasStore.getState().activeCanvasId !== canvasId) {
        throw new Error('保存期间已切换白板；原卡片保持不变，请回到草稿所属白板重试')
      }
      const latest = canvasStore.getState().canvasData
      const nextData = {
        ...latest,
        cards: {
          ...Object.fromEntries(Object.entries(latest.cards).filter(([id]) => (
            !sourceIds.has(id) && id !== sharedParent.id
          ))),
          [sharedParent.id]: sharedParent,
        },
        connections: latest.connections.filter(connection => (
          !sourceIds.has(connection.fromCardId) && !sourceIds.has(connection.toCardId)
        )),
      }
      const removedStrokeIds = new Set(
        [...strokesByChild.values()].flat().map(stroke => stroke.id),
      )
      const nextInk = ink.filter(stroke => !removedStrokeIds.has(stroke.id))
      const nextDoc = {
        ...CanvasManager.composeCanvasDocument(canvasId, nextData),
        ink: nextInk,
      }
      CanvasManager.saveCanvasDocument(canvasId, nextDoc)
      const verified = CanvasManager.getCanvasDocument(canvasId)
      const verifiedCardIds = new Set(verified.cards.map(card => card.id))
      const verifiedStrokeIds = new Set(verified.ink.map(stroke => stroke.id))
      if (!verifiedCardIds.has(sharedParent.id)
        || [...sourceIds].some(id => verifiedCardIds.has(id))
        || [...removedStrokeIds].some(id => verifiedStrokeIds.has(id))) {
        throw new Error('浏览器未能持久化母卡，草稿与来源卡片已保留')
      }
      canvasActions.setCanvasData(CanvasManager.getCanvasData(canvasId))

      parentCardActions.remove(parent.id)
      setActiveSharedParentId(parent.id)
      selectionActions.clearSelection()
      setPendingParentSave(null)

      if (parent.sourceBoxCardId) {
        const response = await fetch(`/api/box/card/${encodeURIComponent(parent.sourceBoxCardId)}`, {
          method: 'DELETE',
        })
        if (!response.ok && response.status !== 404) {
          const payload = await response.json().catch(() => ({}))
          window.alert(payload.error || '母卡已经保存，请稍后整理卡片盒中的来源条目')
        } else {
          window.dispatchEvent(new CustomEvent('box-card-changed'))
        }
      }
    } catch (error) {
      window.alert(error instanceof Error ? error.message : String(error))
    } finally {
      setParentSaveBusy(false)
    }
  }, [activeCanvasId])

  const handleMergeAsParent = useCallback(() => {
    if (!activeCanvasId || selectedCardIds.length < 2) return
    const selectedCards = selectedCardIds
      .map(id => canvasStore.getState().canvasData.cards[id])
      .filter((card): card is Card => card !== undefined)
      .sort((a, b) => a.y - b.y || a.x - b.x)
    if (selectedCards.length < 2) return
    addParentDraftToCanvas(
      selectedCards.map(card => card.content).join('\n\n---\n\n'),
      selectedCards[0].sourceType,
      `${selectedCards.length} 张卡片合并`,
      undefined,
      { sourceChildIds: selectedCards.map(card => card.id) },
    )
    selectionActions.clearSelection()
  }, [activeCanvasId, selectedCardIds])

  const activeDraftParentCard = activeParentCardId === null
    ? null
    : draftParentCards.find(card => card.id === activeParentCardId) ?? null
  const activeSharedParentCard = activeSharedParentId === null
    ? null
    : canvasData.cards[activeSharedParentId]?.role === 'parent'
      ? canvasData.cards[activeSharedParentId]
      : null

  const saveActiveParent = useCallback(async () => {
    if (activeDraftParentCard === null) return
    const sourceIds = new Set(activeDraftParentCard.sourceChildIds)
    const ink = activeCanvasId ? CanvasManager.getCanvasInk(activeCanvasId) : EMPTY_INK
    const inkCount = ink.filter(stroke => (
      stroke.space.startsWith('card:') && sourceIds.has(stroke.space.slice(5))
    )).length
    if (inkCount > 0) {
      setPendingParentSave({ parent: activeDraftParentCard, inkCount })
      return
    }
    await commitParentCard(activeDraftParentCard, 'archive')
  }, [activeCanvasId, activeDraftParentCard, commitParentCard])

  const handleParentSplit = useCallback((segments: TextSegment[]) => {
    const sourceParent = activeSharedParentCard ?? activeDraftParentCard
    if (!activeCanvasId || sourceParent === null || segments.length === 0) return
    const latest = canvasStore.getState().canvasData
    const cards = { ...latest.cards }
    if (!cards[sourceParent.id]) {
      cards[sourceParent.id] = {
        id: sourceParent.id,
        ...createCard(sourceParent.content, sourceParent.sourceType ?? 'manual', {
          x: sourceParent.x, y: sourceParent.y, width: sourceParent.width, height: sourceParent.height,
          role: 'parent', sourceLabel: sourceParent.sourceLabel,
        }),
      }
    }
    const createdIds: string[] = []
    const extractedRanges = [...(sourceParent.extractedRanges ?? [])]
    let zIndex = Object.values(cards).reduce((highest, card) => Math.max(highest, card.zIndex), 0)
    for (const segment of segments) {
      const baseWidth = welcomeCardWidth(cards)
      const position = findFreePosition(Object.values(cards), baseWidth, SIZE_PRESETS.default.height)
      const id = 'card-' + generateId()
      zIndex += 1
      cards[id] = {
        id,
        ...createCard(segment.content, sourceParent.sourceType ?? 'manual', {
          ...position,
          width: baseWidth,
          zIndex,
          sourceLabel: sourceParent.sourceLabel,
        }),
      }
      createdIds.push(id)
      extractedRanges.push({
        startLine: segment.startLine,
        endLine: segment.endLine,
        childCardId: id,
      })
    }
    cards[sourceParent.id] = {
      ...cards[sourceParent.id],
      childIds: [...(cards[sourceParent.id].childIds ?? []), ...createdIds],
      extractedRanges,
    }
    const nextData = { ...latest, cards }
    CanvasManager.saveCanvasData(activeCanvasId, nextData)
    canvasActions.setCanvasData(nextData)
    selectionActions.setSelected(createdIds)
    parentCardActions.close()
    setActiveSharedParentId(null)
  }, [activeCanvasId, activeDraftParentCard, activeSharedParentCard])

  const handleSharedParentTextChange = useCallback((content: string) => {
    if (!activeCanvasId || activeSharedParentCard === null) return
    const latest = canvasStore.getState().canvasData
    const card = latest.cards[activeSharedParentCard.id]
    if (!card) return
    const nextData = {
      ...latest,
      cards: { ...latest.cards, [card.id]: { ...card, content } },
    }
    canvasActions.setCanvasData(nextData)
  }, [activeCanvasId, activeSharedParentCard])

  const toggleSelect = useCallback((cardId: string) => {
    selectionActions.toggleCard(cardId)
  }, [])

  const handleCardSelect = useCallback((cardId: string) => {
    selectionActions.setSelected([cardId])
  }, [])

  const handleCardDoubleClick = useCallback((cardId: string) => {
    const card = canvasStore.getState().canvasData.cards[cardId]
    if (card?.role === 'parent') {
      setActiveSharedParentId(cardId)
      return
    }
    setDetailCardId(cardId)
  }, [])

  const handleExpandDetail = useCallback((cardId: string) => {
    setDetailCardId(cardId)
  }, [])

  // Reset view
  const resetView = useCallback(() => {
    setPan({ x: 60, y: 20 })
    setScale(DEFAULT_CANVAS_SCALE)
  }, [])

  const resetToLiquidTextDemo = useCallback(() => {
    if (!activeCanvasId) return
    const data = createLiquidTextDemoData(new Date().toISOString())
    CanvasManager.saveCanvasData(activeCanvasId, data)
    canvasActions.setCanvasData(data)
    parentCardActions.close()
    setActiveSharedParentId(null)
    selectionActions.clearSelection()
  }, [activeCanvasId])

  // Jump to (minimap)
  const jumpTo = useCallback((cx: number, cy: number) => {
    const el = containerRef.current
    if (!el) return
    const rect = el.getBoundingClientRect()
    setPan({
      x: -(cx * scale - rect.width / 2),
      y: -(cy * scale - rect.height / 2),
    })
  }, [scale])

  // ── Context menu ──
  const handleCardContextMenu = useCallback((e: React.MouseEvent, cardId: string) => {
    setContextMenu({ x: e.clientX, y: e.clientY, cardId })
  }, [])

  // ── Keyboard shortcuts ──
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      const isMod = e.metaKey || e.ctrlKey
      // Don't handle shortcuts when editing text
      const tag = (e.target as HTMLElement)?.tagName
      const isInput = tag === 'INPUT' || tag === 'TEXTAREA'

      if (e.key === 'Escape') {
        if (editingCardId) { setEditingCardId(null) }
        else if (searchOpen) { setSearchOpen(false); setSearchQuery('') }
        else if (detailCardId) { setDetailCardId(null) }
        else if (contextMenu) { setContextMenu(null) }
        else { selectionActions.clearSelection() }
        return
      }

      if (isInput && !isMod) return

      // Ctrl/Cmd+S: Manual save
      if (isMod && e.key === 's') {
        e.preventDefault()
        if (activeCanvasId) {
          const current = canvasStore.getState().canvasData
          const saved = { ...current, viewport: { panX: pan.x, panY: pan.y, scale } }
          CanvasManager.saveCanvasData(activeCanvasId, saved)
          canvasActions.setCanvasData(saved)
          setSaveStatus('saved')
          setTimeout(() => setSaveStatus(s => s === 'saved' ? 'idle' : s), 2000)
        }
      }
      // Ctrl/Cmd+Z: Undo
      if (isMod && e.key === 'z' && !e.shiftKey) {
        e.preventDefault()
        handleUndo()
      }
      // Ctrl/Cmd+Shift+Z: Redo
      if (isMod && e.key === 'z' && e.shiftKey) {
        e.preventDefault()
        handleRedo()
      }
      // Ctrl/Cmd+F: Search
      if (isMod && e.key === 'f') {
        e.preventDefault()
        setSearchOpen(true)
        setTimeout(() => searchInputRef.current?.focus(), 50)
      }
      // Ctrl/Cmd+A: Select all
      if (isMod && e.key === 'a' && !isInput) {
        e.preventDefault()
        selectionActions.setSelected(allCards.map(c => c.id))
      }
      // Ctrl/Cmd+D: Duplicate selected
      if (isMod && e.key === 'd' && selectedCardIds.length > 0 && activeCanvasId) {
        e.preventDefault()
        pushUndo()
        selectedCardIds.forEach(id => {
          const card = canvasData.cards[id]
          if (card) {
            const {
              id: _, role: _role, parentId: _parentId, childIds: _childIds,
              extractedRanges: _extractedRanges, ...rest
            } = card
            CanvasManager.addCard(activeCanvasId, { ...rest, x: card.x + 30, y: card.y + 30 })
          }
        })
        canvasActions.setCanvasData(CanvasManager.getCanvasData(activeCanvasId))
      }
      // Delete/Backspace: Delete selected
      if ((e.key === 'Delete' || e.key === 'Backspace') && !isInput) {
        e.preventDefault()
        if (selectedCardIds.length > 0 && activeCanvasId) {
          pushUndo()
          const latest = canvasStore.getState().canvasData
          const nextData = removeCardsWithRelationships(latest, selectedCardIds)
          CanvasManager.saveCanvasData(activeCanvasId, nextData)
          canvasActions.setCanvasData(nextData)
          selectionActions.clearSelection()
        }
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [contextMenu, detailCardId, editingCardId, searchOpen, activeCanvasId, handleUndo, handleRedo, allCards, selectedCardIds, canvasData, pan, scale, pushUndo])

  // Close context menu on outside click
  useEffect(() => {
    if (!contextMenu) return
    const handleClick = () => setContextMenu(null)
    window.addEventListener('click', handleClick)
    return () => window.removeEventListener('click', handleClick)
  }, [contextMenu])

  // Search filtering
  const searchMatchIds = useMemo(() => {
    if (!searchQuery.trim()) return null
    const q = searchQuery.toLowerCase()
    return new Set(allCards.filter(c =>
      (c.content || '').toLowerCase().includes(q) ||
      (c.tags || []).some(t => t.toLowerCase().includes(q)) ||
      (c.sourceLabel || '').toLowerCase().includes(q)
    ).map(c => c.id))
  }, [searchQuery, allCards])

  // Handle card color change
  const handleCardColorChange = useCallback((cardId: string, field: 'bgColor' | 'textColor' | 'color', value: string | null) => {
    if (!activeCanvasId) return
    pushUndo()
    const latest = canvasStore.getState().canvasData
    const card = latest.cards[cardId]
    if (!card) return
    const nextData = { ...latest, cards: { ...latest.cards, [cardId]: { ...card, [field]: value } } }
    CanvasManager.saveCanvasData(activeCanvasId, nextData)
    canvasActions.setCanvasData(nextData)
  }, [activeCanvasId, pushUndo])

  const hasCards = allCards.length + draftParentCards.length > 0

  return (
    <div className="whiteboard-view">
      {/* Toolbar */}
      <div className="whiteboard-toolbar">
        {/* Canvas group */}
        <div className="toolbar-group">
          <span className="whiteboard-toolbar-title">WHITEBOARD</span>
          <CanvasSelector
            canvasList={canvasList}
            activeCanvasId={activeCanvasId}
            onSwitch={switchCanvas}
            onCreate={createCanvas}
            onRename={renameCanvas}
            onDelete={deleteCanvas}
          />
        </div>

        <div className="whiteboard-toolbar-divider" />

        {/* Edit group */}
        <div className="toolbar-group">
          <button className="toolbar-icon-btn" onClick={handleUndo} disabled={undoStack.length === 0} title="Undo (Ctrl+Z)">
            <RotateCcw size={14} />
          </button>
          <button className="toolbar-icon-btn" onClick={handleRedo} disabled={redoStack.length === 0} title="Redo (Ctrl+Shift+Z)">
            <RotateCw size={14} />
          </button>
          <button
            className={`toolbar-icon-btn ${eraserMode ? 'active' : ''}`}
            onClick={toggleEraserMode}
            title="笔迹橡皮（笔侧键按住可临时启用）"
            aria-pressed={eraserMode}
          >
            <Eraser size={14} />
          </button>
          {saveStatus === 'saving' && <span className="toolbar-save-status saving">Saving...</span>}
          {saveStatus === 'saved' && <span className="toolbar-save-status saved"><Save size={12} /> Saved</span>}
        </div>

        <div className="whiteboard-toolbar-divider" />

        {/* View group */}
        <div className="toolbar-group">
          <button className="toolbar-icon-btn" onClick={() => zoomAt(scale / WHEEL_ZOOM_STEP)} disabled={scale <= MIN_CANVAS_SCALE} title="Zoom out (wheel)">
            <ZoomOut size={14} />
          </button>
          <button className="whiteboard-toolbar-zoom" onClick={() => zoomAt(DEFAULT_CANVAS_SCALE)} title="Reset zoom to 75%">
            {Math.round(zoomPercentForScale(scale))}%
          </button>
          <button className="toolbar-icon-btn" onClick={() => zoomAt(scale * WHEEL_ZOOM_STEP)} disabled={scale >= MAX_CANVAS_SCALE} title="Zoom in (wheel)">
            <ZoomIn size={14} />
          </button>
          <button className="toolbar-icon-btn" onClick={resetView} title="Reset position">
            <Maximize2 size={14} />
          </button>
          <button className="toolbar-icon-btn" onClick={resetToLiquidTextDemo} title="Restore LiquidText demo">↺</button>
        </div>

        <div className="whiteboard-toolbar-divider" />

        {/* Actions group */}
        <div className="toolbar-group">
          <button className={`toolbar-icon-btn ${syncState === 'connected' ? 'active' : ''}`} onClick={() => {
            if (!activeCanvasId) return
            const document = CanvasManager.composeCanvasDocument(activeCanvasId, canvasData)
            // 本地开发（Vite）：同步对象是局域网里的设备，房间固定为 mosaic-local，不建线上共享房间。
            // 把当前画布整份推过去（设备端会弹确认）；还没进房间就先写地址栏，由连接 effect 带上这份文档。
            if (import.meta.env.DEV && (!window.location.hash.includes('board=') || isLocalRoomHash())) {
              if (!window.location.hash.includes('board=')) {
                pendingImportDocRef.current = document
                window.location.hash = `board=${LOCAL_ROOM_ID}`
              } else if (boardSyncRef.current) {
                boardSyncRef.current.protectLocalUntilAck()
                boardSyncRef.current.publish('import', document)
              }
              return
            }
            if (!window.location.hash.includes('board=')) {
              void createSharedBoard(document).then(id => { window.location.replace(sharedBoardLink(id)) }).catch(error => recordSyncLog('room.create.error', { code: String(error).slice(0, 120) }))
            } else boardSyncRef.current?.publish('document', document)
          }} title={import.meta.env.DEV ? `同步到设备（局域网）：${syncState}` : `同步白板：${syncState}`}>
            <RefreshCw size={14} />
          </button>
          <button className="toolbar-icon-btn" onClick={() => { if (!activeCanvasId) return; void createSharedBoard(CanvasManager.composeCanvasDocument(activeCanvasId, canvasData)).then(id => { const link = sharedBoardLink(id); window.history.replaceState({}, '', `${window.location.pathname}${window.location.search}#board=${id}`); window.prompt('共享白板链接', link) }).catch(error => recordSyncLog('room.create.error', { code: String(error).slice(0, 120) })) }} title="创建共享白板">共享</button>
          <button className="toolbar-icon-btn" onClick={downloadSyncLogs} title="下载同步日志">日志</button>
          <button className={`toolbar-icon-btn ${snapToGrid ? 'active' : ''}`}
            onClick={() => setSnapToGrid(!snapToGrid)} title="Snap to grid">
            <Grid3x3 size={14} />
          </button>
          <button className="toolbar-icon-btn" onClick={() => { setSearchOpen(!searchOpen); if (!searchOpen) setTimeout(() => searchInputRef.current?.focus(), 50) }}
            title="Search cards (Ctrl+F)">
            <Search size={14} />
          </button>
        </div>

        <div className="whiteboard-toolbar-divider" />

        {/* Import/Export */}
        <div className="toolbar-group">
          <button className="toolbar-icon-btn" onClick={handleExport} title="Export project">
            <Download size={14} />
          </button>
          <button className="toolbar-icon-btn" onClick={() => importFileRef.current?.click()} title="Import project">
            <Upload size={14} />
          </button>
          <input ref={importFileRef} type="file" accept=".json" style={{ display: 'none' }} onChange={handleImport} />
        </div>

        <div className="whiteboard-toolbar-spacer" />

        {selectedCardIds.length >= 2 && (
          <button className="toolbar-panel-btn active" onClick={() => void handleMergeAsParent()}>
            合并为母卡
          </button>
        )}
        <button className={`toolbar-panel-btn ${inkBoxOpen ? 'active' : ''}`} onClick={() => setInkBoxOpen(true)}>
          笔迹盒
        </button>
        <button className="toolbar-panel-btn" onClick={downloadPenEventLog} title="下载笔与侧键事件日志">
          笔日志
        </button>

        <span className="whiteboard-toolbar-count">
          {allCards.length > 0 && `${visibleCards.length !== allCards.length ? visibleCards.length + '/' : ''}${allCards.length} cards`}
        </span>

        <button
          className={`toolbar-panel-btn ${!panelStore.getState().rightCollapsed ? 'active' : ''}`}
          onClick={panelActions.toggleRight}
        >
          <Plus size={13} /> Card
        </button>
      </div>

      {/* Search bar */}
      {searchOpen && (
        <div className="whiteboard-search-bar">
          <Search size={14} />
          <input
            ref={searchInputRef}
            type="text"
            placeholder="Search cards by content, tags, source..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Escape') { setSearchOpen(false); setSearchQuery('') } }}
          />
          {searchMatchIds && <span className="search-count">{searchMatchIds.size} found</span>}
          <button className="search-close" onClick={() => { setSearchOpen(false); setSearchQuery('') }}><X size={14} /></button>
        </div>
      )}

      {/* Canvas */}
      <div
        ref={containerRef}
        data-canvas="true"
        className={`whiteboard-canvas-wrapper ${panning ? 'panning' : ''}`}
        onPointerDown={(e) => {
          if (e.pointerType === 'pen') { handlePenDown(e); return }
          handlePanStart(e as unknown as React.MouseEvent)
        }}
        onPointerMove={(e) => {
          if (penStrokeRef.current || e.pointerType === 'pen') { handlePenMove(e); return }
          handleMove(e as unknown as React.MouseEvent)
        }}
        onPointerUp={(e) => {
          if (penStrokeRef.current || e.pointerType === 'pen') { handlePenUp(e); return }
          handleUp()
        }}
        onPointerLeave={(e) => {
          if (penStrokeRef.current || e.pointerType === 'pen') { handlePenUp(e); return }
          handleUp()
        }}
        onPointerCancel={(e) => {
          if (penStrokeRef.current || eraserGestureRef.current || e.pointerType === 'pen') { handlePenUp(e); return }
          handleUp()
        }}
      >
        {/* Dot grid */}
        <div className="whiteboard-dot-grid" style={{
          backgroundSize: `${28 * scale}px ${28 * scale}px`,
          backgroundPosition: `${pan.x}px ${pan.y}px`,
        }} />

        {eraserCursor && (
          <div
            className="web-eraser-cursor"
            style={{
              left: eraserCursor.x,
              top: eraserCursor.y,
              width: ERASER_RADIUS_PX * 2,
              height: ERASER_RADIUS_PX * 2,
            }}
          />
        )}

        {/* Transform layer */}
        <div
          ref={transformLayerRef}
          className="whiteboard-transform-layer"
          data-canvas="true"
          style={{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${scale})` }}
         >
          <WebInkLayer canvasId={activeCanvasId} space="canvas" />
          {/* Liquid attachment layer; no visible card-to-card connector strokes. */}
          <CanvasLiquidSVG
            connections={liquidConnections}
            cards={canvasData.cards || {}}
            scale={scale}
            clusters={liquidClusters}
            selectedCardIds={selectedCardIds}
          />
          <WebInkLayer canvasId={activeCanvasId} space="connection" />
          <ConversationBranchLayer
            connections={visibleConnections}
            cards={canvasData.cards || {}}
          />

          {/* Cards */}
          {visibleCards.map(card => (
            <div key={card.id} style={{
              opacity: searchMatchIds && !searchMatchIds.has(card.id) ? 0.25 : 1,
              transition: 'opacity 0.2s',
            }}>
              <WhiteboardCard
                card={card}
                selected={selectedCardIds.includes(card.id)}
                cardSelected={false}
                resizeLocked={cardResizeLocked(card.id, canvasData.cards || {}, canvasData.connections || [])}
                scale={scale}
                editing={editingCardId === card.id}
                assetUrl={assetUrlFor(card)}
                onMouseDown={handleCardDrag}
                onToggleSelect={toggleSelect}
                onSelect={handleCardSelect}
                onDoubleClick={handleCardDoubleClick}
                onRemove={handleRemoveCard}
                onResizeStart={handleResizeStart}
                onSizeChange={handleSizeChange}
                onContextMenu={handleCardContextMenu}
                onEditSave={handleEditSave}
                onExpandDetail={handleExpandDetail}
              />
            </div>
          ))}
          {draftParentCards.map(card => (
            <DraftParentCard
              key={card.id}
              card={card}
              onOpen={parentCardActions.open}
              onRemove={parentCardActions.remove}
            />
          ))}
          <WebInkLayer canvasId={activeCanvasId} cards={canvasData.cards || {}} space="card" />

          {/* Liquid overlay (metaball fusion - renders ABOVE cards) */}
          <LiquidOverlay
            cards={canvasData.cards || {}}
            connections={liquidConnections}
            scale={scale}
          />

          {/* Live pen stroke (in-progress): 常驻 polyline，MOVE 直接改 points 属性 */}
          <svg className="web-ink-live" width="1" height="1" overflow="visible" style={{ pointerEvents: 'none' }}>
            <polyline
              ref={livePolylineRef}
              points=""
              fill="none"
              stroke="#000"
              strokeWidth={PEN_WIDTH}
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>

          {/* Lasso overlay */}
          {lassoRect && lassoRect.w > 2 && lassoRect.h > 2 && (
            <div className="lasso-overlay" style={{
              left: lassoRect.x, top: lassoRect.y,
              width: lassoRect.w, height: lassoRect.h,
            }} />
          )}
        </div>

        {/* 跨空白方向跳转：远处内容岛不铺连续区块，只在对应边缘给入口 */}
        {regionJumps.map(jump => (
          <button
            key={jump.direction}
            type="button"
            className={`region-jump region-jump-${jump.direction}`}
            onClick={() => jumpToRegion(jump.region)}
            title={`${jump.region.cardIds.length} cards · ${Math.round(jump.gap / CHUNK_SIZE)} chunks away`}
          >
            {jump.direction === 'left' ? '←' : jump.direction === 'right' ? '→' : jump.direction === 'up' ? '↑' : '↓'}
            <span>{jump.region.cardIds.length}</span>
          </button>
        ))}

        {/* Empty state */}
        {!hasCards && (
          <div className="whiteboard-empty-state" data-canvas="true">
            <div className="whiteboard-empty-hero">
              <div className="whiteboard-empty-icon-grid">
                <div className="empty-action-card" onClick={() => { panelActions.setRight(false) }}>
                  <Upload size={20} />
                  <span>Import File</span>
                  <small>.txt .md .docx .pdf</small>
                </div>
                <div className="empty-action-card" onClick={() => { panelActions.setRight(false) }}>
                  <Plus size={20} />
                  <span>Manual Create</span>
                  <small>Write a card</small>
                </div>
                <div className="empty-action-card" onClick={() => importFileRef.current?.click()}>
                  <Download size={20} />
                  <span>Import Project</span>
                  <small>From .json</small>
                </div>
              </div>
              <p className="whiteboard-empty-text">Start by adding cards to your whiteboard</p>
            </div>
          </div>
        )}
      </div>

      {/* Hints */}
      {hasCards && (
        <div className="whiteboard-hints">
          <span><b>Double-click</b> detail</span>
          <span><b>Shift+Click</b> select</span>
          <span><b>Alt+Drag</b> lasso</span>
          <span><b>Ctrl+Z/Y</b> undo/redo</span>
          <span><b>Ctrl+F</b> search</span>
          <span><b>Ctrl+S</b> save</span>
        </div>
      )}

      {/* MiniMap */}
      {hasCards && (
        <MiniMap
          positions={cardPositions}
          pan={pan}
          scale={scale}
          viewW={containerRef.current?.clientWidth || window.innerWidth}
          viewH={containerRef.current?.clientHeight || window.innerHeight}
          onJump={jumpTo}
        />
      )}

      {/* Context Menu */}
      {contextMenu && (
        <div className="context-menu" style={{ left: contextMenu.x, top: contextMenu.y }}
          onClick={(e) => e.stopPropagation()}>
          {canvasData.cards[contextMenu.cardId]?.kind !== 'conversation' && (
            <div className="context-menu-item" onClick={() => { setEditingCardId(contextMenu.cardId); setContextMenu(null) }}>
              Edit Card
            </div>
          )}
          <div className="context-menu-item" onClick={() => { setDetailCardId(contextMenu.cardId); setContextMenu(null) }}>
            Expand Detail
          </div>
          <div className="context-menu-item" onClick={() => void handleMoveCardToBox(contextMenu.cardId)}>
            移动到 CARD Box
          </div>
          <div className="context-menu-divider" />
          <div className="context-menu-label">Background Color</div>
          <div className="context-menu-colors">
            {BG_COLORS.map(c => (
              <div key={c.label} className={`context-menu-color-swatch ${canvasData.cards[contextMenu.cardId]?.bgColor === c.value ? 'active' : ''}`}
                style={{ backgroundColor: c.value || 'var(--card-bg)', border: !c.value ? '1px dashed var(--border-secondary)' : undefined }}
                title={c.label}
                onClick={() => { handleCardColorChange(contextMenu.cardId, 'bgColor', c.value); setContextMenu(null) }}
              />
            ))}
          </div>
          <div className="context-menu-label">Text Color</div>
          <div className="context-menu-colors">
            {TEXT_COLORS.map(c => (
              <div key={c.label} className={`context-menu-color-swatch ${canvasData.cards[contextMenu.cardId]?.textColor === c.value ? 'active' : ''}`}
                style={{ backgroundColor: c.value || 'var(--text-secondary)', border: !c.value ? '1px dashed var(--border-secondary)' : undefined }}
                title={c.label}
                onClick={() => { handleCardColorChange(contextMenu.cardId, 'textColor', c.value); setContextMenu(null) }}
              />
            ))}
          </div>
          <div className="context-menu-divider" />
          <div className="context-menu-item danger" onClick={() => { handleRemoveCard(contextMenu.cardId); setContextMenu(null) }}>
            Remove Card
          </div>
        </div>
      )}

      {/* Card Detail Modal */}
      {detailCardId && canvasData.cards[detailCardId] && (
        <CardDetailModal
          card={canvasData.cards[detailCardId]}
          onClose={() => setDetailCardId(null)}
          onSave={handleEditSave}
        />
      )}
      {activeDraftParentCard !== null && (
        <BoundaryEditor
          key={activeDraftParentCard.id}
          mode="parent-split"
          initialText={activeDraftParentCard.content}
          extractedRanges={activeDraftParentCard.extractedRanges}
          onTextChange={text => parentCardActions.updateContent(activeDraftParentCard.id, text)}
          onSave={() => void saveActiveParent()}
          saving={parentSaveBusy}
          createDisabled={false}
          onCreateCards={handleParentSplit}
          onClose={parentCardActions.close}
        />
      )}
      {activeDraftParentCard === null && activeSharedParentCard !== null && (
        <BoundaryEditor
          key={activeSharedParentCard.id}
          mode="parent-split"
          initialText={activeSharedParentCard.content}
          extractedRanges={activeSharedParentCard.extractedRanges}
          onTextChange={handleSharedParentTextChange}
          onCreateCards={handleParentSplit}
          onClose={() => setActiveSharedParentId(null)}
        />
      )}
      {pendingParentSave !== null && (
        <div className="parent-merge-overlay" onMouseDown={event => {
          if (event.target === event.currentTarget && !parentSaveBusy) setPendingParentSave(null)
        }}>
          <section className="parent-merge-dialog" role="dialog" aria-modal="true" aria-labelledby="parent-merge-title">
            <h2 id="parent-merge-title">处理附着笔迹</h2>
            <p>
              来源子卡包含 {pendingParentSave.inkCount} 条附着笔迹。
              请选择保存母卡时的处理方式；本次变化仅保存在 Web 白板，不会同步到插件端。
            </p>
            <div className="parent-merge-actions">
              <button
                className="primary"
                disabled={parentSaveBusy}
                onClick={() => void commitParentCard(pendingParentSave.parent, 'archive')}
              >
                归档到笔迹盒并继续
              </button>
              <button
                disabled={parentSaveBusy}
                onClick={() => void commitParentCard(pendingParentSave.parent, 'discard')}
              >
                清理笔迹并继续
              </button>
              <button disabled={parentSaveBusy} onClick={() => setPendingParentSave(null)}>
                返回
              </button>
            </div>
          </section>
        </div>
      )}
      {inkBoxOpen && activeCanvasId && (
        <InkBoxPanel
          boardId={activeCanvasId}
          restorePosition={{
            x: (-pan.x + (containerRef.current?.clientWidth ?? window.innerWidth) / 2) / scale,
            y: (-pan.y + (containerRef.current?.clientHeight ?? window.innerHeight) / 2) / scale,
          }}
          onClose={() => setInkBoxOpen(false)}
        />
      )}
    </div>
  )
}

export default Whiteboard
