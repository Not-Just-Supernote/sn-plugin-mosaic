import type { Card, Connection, CanvasData, CanvasInfo, CanvasRegistry } from './types'
import { SIZE_PRESETS } from './types'
import {
  encodeBoard,
  decodeBoard,
  canvasDataToBoard,
  boardToCanvasData,
  CARD_DEFAULTS,
  BOARD_STORAGE_B64_PREFIX,
  type BoardDoc,
  type InkStroke,
} from './boardFormat'
import { bytesToBase64, base64ToBytes } from './base64'
import { generateId } from './id'
import { resolveCardSize } from './cardGeometry'

// 沿用原 services 的导出，调用方（Whiteboard.tsx 等）无需改动
export { generateId } from './id'

// ============================================
// 文本文件导出（浏览器下载）
// ============================================

export function downloadTextFile(filename: string, content: string): void {
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

// ============================================
// Storage Manager (localStorage, simplified)
// ============================================

const STORAGE_PREFIX = 'wbai_'

export const StorageManager = {
  get<T>(key: string, defaultValue: T): T {
    try {
      const raw = localStorage.getItem(STORAGE_PREFIX + key)
      return raw ? JSON.parse(raw) : defaultValue
    } catch { return defaultValue }
  },
  set(key: string, value: unknown) {
    try { localStorage.setItem(STORAGE_PREFIX + key, JSON.stringify(value)) } catch { /* ignore */ }
  },
  remove(key: string) {
    localStorage.removeItem(STORAGE_PREFIX + key)
  },
}

// ============================================
// Card Factory & 旧数据迁移
// ============================================

/** 网格放置常量（新卡自动排布用，与卡片渲染尺寸 SIZE_PRESETS 无关） */
const PLACE_W = SIZE_PRESETS.default.width
const PLACE_H = SIZE_PRESETS.default.height
const PLACE_GAP = 30
const PLACE_COLS = 3
const PLACE_START = 60

/** 在已有卡片间找一个不重叠的网格空位；候选尺寸可由动态摘录卡传入 */
export function findFreePosition(
  existingCards: Card[],
  candidateWidth: number = PLACE_W,
  candidateHeight: number = PLACE_H,
): { x: number; y: number } {
  let x = PLACE_START, y = PLACE_START
  if (existingCards.length === 0) return { x, y }
  const col = existingCards.length % PLACE_COLS
  const row = Math.floor(existingCards.length / PLACE_COLS)
  x = PLACE_START + col * (PLACE_W + PLACE_GAP)
  y = PLACE_START + row * (PLACE_H + PLACE_GAP)
  let attempts = 0
  while (attempts < 30) {
    const overlaps = existingCards.some(c => {
      const { width: cw, height: ch } = resolveCardSize(c)
      return !(x + candidateWidth + 10 < c.x || x > c.x + cw + 10 || y + candidateHeight + 10 < c.y || y > c.y + ch + 10)
    })
    if (!overlaps) break
    x += PLACE_W + PLACE_GAP
    if (x > PLACE_START + (PLACE_COLS - 1) * (PLACE_W + PLACE_GAP)) {
      x = PLACE_START
      y += PLACE_H + PLACE_GAP
    }
    attempts++
  }
  return { x, y }
}

/** 统一卡片构造：默认值集中在共享格式模块的 CARD_DEFAULTS，调用方只传差异项 */
export function createCard(
  content: string,
  sourceType: Card['sourceType'],
  overrides: Partial<Omit<Card, 'id' | 'content' | 'sourceType'>> = {}
): Omit<Card, 'id'> {
  return {
    ...CARD_DEFAULTS,
    tags: [],
    content,
    sourceType,
    sourceLabel: undefined,
    createdAt: new Date().toISOString(),
    ...overrides,
  }
}

/** 旧版卡片（fullContent/fullPreview/preview 三级 + sender 字段）读取时就地迁移 */
function migrateCanvasData(data: CanvasData): CanvasData {
  for (const card of Object.values(data.cards)) {
    const legacy = card as Card & { fullContent?: string; fullPreview?: string; preview?: string; sender?: string; senderLabel?: string }
    if (card.content === undefined) {
      card.content = legacy.fullContent || legacy.fullPreview || legacy.preview || ''
    }
    delete legacy.fullContent
    delete legacy.fullPreview
    delete legacy.preview
    delete legacy.sender
    delete legacy.senderLabel
  }
  return data
}

// ============================================
// Canvas Manager (localStorage-persistent)
// ============================================

const CANVASES_KEY = 'canvases'
const CANVAS_PREFIX = 'canvas_'

/**
 * CanvasData 暂不直接暴露笔迹/附件；读到互通文档后暂存在此，任何后续保存都原样带回，
 * 避免旧版 Web 在移动一张卡片时抹掉 Mosaic 写入的笔迹、图片或未知扩展字段。
 */
interface CanvasBinaryExtras {
  ink: InkStroke[]
  blobs: Record<string, Uint8Array>
  ext?: Record<string, unknown>
  metaExt?: Record<string, unknown>
}

const canvasBinaryExtras = new Map<string, CanvasBinaryExtras>()

/**
 * 笔迹以内存（canvasBinaryExtras.ink）为准：写一笔 / 擦一笔只改内存并通知订阅者，
 * 落盘防抖 INK_PERSIST_DEBOUNCE_MS 后整板编码一次。之前每笔都是"解码整板 → 编码整板 →
 * 再解码整板 → 整个白板组件重渲染"，橡皮每个 pointermove 也走一遍，笔迹一多就卡。
 */
const EMPTY_INK: InkStroke[] = []
const INK_PERSIST_DEBOUNCE_MS = 800
const inkListeners = new Set<() => void>()
const pendingInkPersist = new Map<string, ReturnType<typeof setTimeout>>()

function notifyInk(): void {
  inkListeners.forEach(listener => listener())
}

function cancelInkPersist(canvasId: string): void {
  const timer = pendingInkPersist.get(canvasId)
  if (timer !== undefined) clearTimeout(timer)
  pendingInkPersist.delete(canvasId)
}

function scheduleInkPersist(canvasId: string): void {
  cancelInkPersist(canvasId)
  pendingInkPersist.set(canvasId, setTimeout(() => persistInk(canvasId), INK_PERSIST_DEBOUNCE_MS))
}

function persistInk(canvasId: string): void {
  cancelInkPersist(canvasId)
  if (!canvasBinaryExtras.has(canvasId)) return
  CanvasManager.saveCanvasData(canvasId, CanvasManager.getCanvasData(canvasId))
}

function emptyCanvasData(): CanvasData {
  return { cards: {}, connections: [], viewport: { panX: 60, panY: 20, scale: 1.0 } }
}

/** Reference board used by the first Canvas 1, matching the LiquidText demo
 * arrangement shown in the product captures.  New user-created canvases stay
 * empty; this seed only gives the initial board a useful visual baseline. */
export function createLiquidTextDemoData(now: string): CanvasData {
  const card = (
    id: string,
    content: string,
    x: number,
    y: number,
    width: number,
    height: number,
    bgColor: string | null = null,
  ): Card => ({
    id, content, x, y, width, height,
    color: null, bgColor, textColor: '#3f3b2e', sizePreset: 'default',
    tags: [], zIndex: 0, sourceType: 'manual', createdAt: now,
  })

  const cards: Record<string, Card> = {
    welcome: card('demo-welcome',
      '# Welcome to LiquidText\n\nThis is a very simplified medical litigation project.\nIn this scenario, our client had a novel stem cell\nprocedure to relieve her arthritis--but it didn\'t\nwork, and we want to find out why.', 24, 20, 598, 166),
    candidate: card('demo-candidate', 'Was our client even a good candidate\nfor the procedure? Sort of...', 27, 186, 388, 63, '#ff9995'),
    candidateQuote: card('demo-candidate-quote', 'While most adults are good candidates for\nstem cell cartilage regeneration\n-ASCA Best Practices, p.2', 31, 266, 385, 93),
    flowchart: card('demo-flowchart', 'Figure 1. Recommended flowchart for Stem Cell Cartilage Regeneration Therapy', 33, 644, 358, 281),
    clientFactor: card('demo-client-factor', 'Looks like our client was in the closely\nmonitor category. Key factor:\n\n➤  - Client\'s high blood pressure', 34, 966, 379, 115),
    stemCell: card('demo-stem-cell', 'The key properties of a stem cell were first defined by Ernest McCulloch and James Till at the University of Toronto in the early 1960s. They discovered the blood-forming stem cell, the hematopoietic stem cell (HSC), through their pioneering work in mice. McCulloch and Till began a series of experiments in which bone marrow cells were', 0, 1095, 684, 160),
    procedure: card('demo-procedure', 'How did the surgeon perform the procedure?\nCorrectly, or so it seems at first...', 670, 190, 503, 72, '#9db6ff'),
    quote1: card('demo-quote-1', 'We take the stem cell   19  culture we received as part of\nthe treatment package and grow it.\n-Surgeon Deposition Transcript, p.3', 689, 278, 477, 125),
    quote2: card('demo-quote-2', 'Yes, we did a blood pressure and blood sugar check and\nrechecked   30  her liver function and white blood cell\ncounts\n-Surgeon Deposition Transcript, p.3', 689, 422, 480, 145),
    quote3: card('demo-quote-3', 'surgery. We performed an arthroscopic incision   40  on Ms.\nMcCaffrey\'s left hip. We broke through the layers of the\njoint:  the bursa and the synovial cavity\n-Surgeon Deposition Transcript, p.3', 690, 567, 479, 143),
    quoteSpacer: card('demo-quote-spacer', 'to insert a dissolving joint   44  spacer\n-Surgeon Deposition Transcript, p.3', 690, 729, 478, 106),
    quote4: card('demo-quote-4', 'Matrix gel, we let the gel set for 15 minutes\n-Surgeon Deposition Transcript, p.4', 676, 856, 497, 108, '#ff9995'),
    quote5: card('demo-quote-5', 'we dried the bone fascia.\n-Surgeon Deposition Transcript, p.4', 680, 985, 498, 106),
    finding: card('demo-finding', 'What did we find? The surgeon made a\nmistake, and didn\'t follow the procedure\nindicated by the manufacturer.', 1410, 390, 437, 90, '#f28af0'),
    important: card('demo-important', 'IMPORTANT The surgeon\'s\ntestimony does not match\nthe nurse\'s', 1412, 509, 273, 89, '#ff9995'),
    after: card('demo-after', 'After about 10 minutes, we noticed a\nvery small bone spur on Ms.\nMcCaffrey\'s femur, at which point\nwe removed the ICM matrix gel', 1410, 630, 343, 129, '#27f239'),
    argument: card('demo-argument', 'Overall Argument:\n\n➤  The surgeon discovered a bone spur\n    on client\n\n➤  Surgeon wanted to remove it, but\n    first mistakenly removed the ICM\n    matrix gel before it cured.\n\n➤  Removing the ICM Matrix gel\n    prematurely is known from the Phase\n    III trials to cause failure.\n\n➤  Surgeon was negligent, did not read\n    the manual for the therapy, which\n    clearly states it must be untouched for\n    15 mins.\n\n(Tip: Tap the red bullet circles to follow\nthe InkLink and see the reference)', 1410, 882, 340, 580),
  }

  cards.candidateQuote.sourceLabel = '-ASCA Best Practices, p.2'
  cards.candidateQuote.content = cards.candidateQuote.content.split('\n-ASCA')[0]
  cards.stemCell.sourceLabel = '-ASCA Best Practices, p.1'
  for (const key of ['quote1', 'quote2', 'quote3', 'quoteSpacer', 'quote4', 'quote5']) {
    const excerpt = cards[key]
    const [content, source] = excerpt.content.split('\n-Surgeon')
    excerpt.content = content
    excerpt.sourceLabel = source ? `-Surgeon${source}` : undefined
    excerpt.tags = []
  }

  const link = (id: string, fromCardId: string, toCardId: string): Connection => ({
    id, fromCardId, toCardId, color: '#6b88bd', label: '',
  })
  const cardsById = Object.fromEntries(Object.values(cards).map(item => [item.id, item])) as Record<string, Card>
  return {
    cards: cardsById,
    connections: [
      link('demo-link-welcome', cards.welcome.id, cards.candidate.id),
      link('demo-link-candidate', cards.candidate.id, cards.candidateQuote.id),
      link('demo-link-procedure', cards.procedure.id, cards.quote1.id),
      link('demo-link-quotes-1-2', cards.quote1.id, cards.quote2.id),
      link('demo-link-quotes-2-3', cards.quote2.id, cards.quote3.id),
      link('demo-link-quotes-3-spacer', cards.quote3.id, cards.quoteSpacer.id),
      link('demo-link-quotes-spacer-4', cards.quoteSpacer.id, cards.quote4.id),
      link('demo-link-quotes-4-5', cards.quote4.id, cards.quote5.id),
    ],
    viewport: { panX: 0, panY: 0, scale: 1 },
  }
}

export const CanvasManager = {
  getRegistry(): CanvasRegistry {
    return StorageManager.get<CanvasRegistry>(CANVASES_KEY, { canvasList: [], activeCanvasId: null })
  },

  saveRegistry(registry: CanvasRegistry) {
    StorageManager.set(CANVASES_KEY, registry)
  },

  getCanvasList(): CanvasInfo[] {
    return this.getRegistry().canvasList
  },

  getActiveCanvasId(): string | null {
    const reg = this.getRegistry()
    if (reg.activeCanvasId && !reg.canvasList.find(c => c.id === reg.activeCanvasId)) {
      reg.activeCanvasId = reg.canvasList.length > 0 ? reg.canvasList[0].id : null
      this.saveRegistry(reg)
    }
    return reg.activeCanvasId
  },

  setActiveCanvasId(id: string) {
    const reg = this.getRegistry()
    reg.activeCanvasId = id
    this.saveRegistry(reg)
  },

  createCanvas(name: string): string {
    const reg = this.getRegistry()
    const id = 'canvas-' + generateId()
    const now = new Date().toISOString()
    reg.canvasList.push({ id, name, createdAt: now, updatedAt: now })
    reg.activeCanvasId = id
    this.saveRegistry(reg)
    this.saveCanvasData(id, emptyCanvasData())
    return id
  },

  renameCanvas(id: string, newName: string) {
    const reg = this.getRegistry()
    const canvas = reg.canvasList.find(c => c.id === id)
    if (canvas) {
      canvas.name = newName
      canvas.updatedAt = new Date().toISOString()
      this.saveRegistry(reg)
    }
  },

  deleteCanvas(id: string): string | null {
    const reg = this.getRegistry()
    reg.canvasList = reg.canvasList.filter(c => c.id !== id)
    StorageManager.remove(CANVAS_PREFIX + id)
    cancelInkPersist(id)
    canvasBinaryExtras.delete(id)
    if (reg.activeCanvasId === id) {
      reg.activeCanvasId = reg.canvasList.length > 0 ? reg.canvasList[0].id : null
    }
    this.saveRegistry(reg)
    return reg.activeCanvasId
  },

  getCanvasData(canvasId: string): CanvasData {
    const raw = StorageManager.get<string | CanvasData | null>(CANVAS_PREFIX + canvasId, null)
    if (raw === null) {
      canvasBinaryExtras.delete(canvasId)
      return emptyCanvasData()
    }
    // 新格式：base64 文本封装的二进制容器（Mosaic 互通格式）
    if (typeof raw === 'string') {
      {
        try {
          const payload = raw.startsWith(BOARD_STORAGE_B64_PREFIX)
            ? raw.slice(BOARD_STORAGE_B64_PREFIX.length)
            : raw
          const doc = decodeBoard(base64ToBytes(payload))
          // 已载入的画布不用存储里的旧笔迹覆盖内存（内存里可能有还没落盘的新笔迹）。
          if (!canvasBinaryExtras.has(canvasId)) {
            canvasBinaryExtras.set(canvasId, {
              ink: doc.ink,
              blobs: doc.blobs,
              ext: doc.ext,
              metaExt: doc.meta.ext,
            })
          }
          return boardToCanvasData(doc)
        } catch {
          canvasBinaryExtras.delete(canvasId)
          return emptyCanvasData()
        }
      }
    }
    // 旧格式：JSON 对象 —— 就地迁移并以新格式回写
    canvasBinaryExtras.delete(canvasId)
    const migrated = migrateCanvasData(raw)
    this.saveCanvasData(canvasId, migrated)
    return migrated
  },

  saveCanvasData(canvasId: string, data: CanvasData) {
    const reg = this.getRegistry()
    const canvas = reg.canvasList.find(c => c.id === canvasId)
    const now = new Date().toISOString()
    if (canvas) {
      canvas.updatedAt = now
      this.saveRegistry(reg)
    }
    const extras = canvasBinaryExtras.get(canvasId)
    const doc = canvasDataToBoard(data, {
      id: canvasId,
      name: canvas?.name ?? canvasId,
      createdAt: canvas?.createdAt ?? now,
      updatedAt: now,
      viewport: data.viewport,
      ext: extras?.metaExt,
    }, extras)
    StorageManager.set(CANVAS_PREFIX + canvasId, BOARD_STORAGE_B64_PREFIX + bytesToBase64(encodeBoard(doc)))
  },

  composeCanvasDocument(canvasId: string, data: CanvasData): BoardDoc {
    const reg = this.getRegistry()
    const canvas = reg.canvasList.find(c => c.id === canvasId)
    const now = new Date().toISOString()
    const extras = canvasBinaryExtras.get(canvasId)
    return canvasDataToBoard(data, {
      id: canvasId,
      name: canvas?.name ?? canvasId,
      createdAt: canvas?.createdAt ?? now,
      updatedAt: now,
      viewport: data.viewport,
      ext: extras?.metaExt,
    }, extras)
  },

  getCanvasDocument(canvasId: string): BoardDoc {
    const data = this.getCanvasData(canvasId)
    return this.composeCanvasDocument(canvasId, data)
  },

  /** 同一画布未变化时返回同一数组引用（useSyncExternalStore 依赖这一点）。 */
  getCanvasInk(canvasId: string): InkStroke[] {
    if (!canvasBinaryExtras.has(canvasId)) this.getCanvasData(canvasId)
    return canvasBinaryExtras.get(canvasId)?.ink ?? EMPTY_INK
  },

  /** 订阅笔迹/附加数据变化：只有对应图层重渲染，白板组件不动。 */
  subscribeInk(listener: () => void): () => void {
    inkListeners.add(listener)
    return () => { inkListeners.delete(listener) }
  },

  ensureExtras(canvasId: string): CanvasBinaryExtras {
    if (!canvasBinaryExtras.has(canvasId)) this.getCanvasData(canvasId)
    let extras = canvasBinaryExtras.get(canvasId)
    if (extras === undefined) {
      extras = { ink: [], blobs: {} }
      canvasBinaryExtras.set(canvasId, extras)
    }
    return extras
  },

  addInkStroke(canvasId: string, stroke: InkStroke) {
    const extras = this.ensureExtras(canvasId)
    extras.ink = [...extras.ink, stroke]
    scheduleInkPersist(canvasId)
    notifyInk()
  },

  removeInkStrokes(canvasId: string, ids: Iterable<string>) {
    const removing = new Set(ids)
    if (removing.size === 0) return
    const extras = this.ensureExtras(canvasId)
    const next = extras.ink.filter(stroke => !removing.has(stroke.id))
    if (next.length === extras.ink.length) return
    extras.ink = next
    scheduleInkPersist(canvasId)
    notifyInk()
  },

  setCanvasInk(canvasId: string, ink: InkStroke[]) {
    const extras = this.ensureExtras(canvasId)
    extras.ink = ink
    scheduleInkPersist(canvasId)
    notifyInk()
  },

  /** 把内存里还没落盘的笔迹立刻写入（页面隐藏 / 卸载前调用）。 */
  flushInkPersist() {
    for (const canvasId of [...pendingInkPersist.keys()]) persistInk(canvasId)
  },

  saveCanvasDocument(canvasId: string, doc: BoardDoc): CanvasData {
    cancelInkPersist(canvasId)
    canvasBinaryExtras.set(canvasId, {
      ink: doc.ink,
      blobs: doc.blobs,
      ext: doc.ext,
      metaExt: doc.meta.ext,
    })
    StorageManager.set(CANVAS_PREFIX + canvasId, BOARD_STORAGE_B64_PREFIX + bytesToBase64(encodeBoard(doc)))
    notifyInk()
    return boardToCanvasData(doc)
  },

  addCard(canvasId: string, cardData: Omit<Card, 'id'>): string {
    const data = this.getCanvasData(canvasId)
    const id = 'card-' + generateId()
    data.cards[id] = { id, ...cardData } as Card
    this.saveCanvasData(canvasId, data)
    return id
  },

  updateCardPosition(canvasId: string, cardId: string, x: number, y: number) {
    const data = this.getCanvasData(canvasId)
    if (data.cards[cardId]) {
      data.cards[cardId].x = x
      data.cards[cardId].y = y
      this.saveCanvasData(canvasId, data)
    }
  },

  updateCard(canvasId: string, cardId: string, updates: Partial<Card>) {
    const data = this.getCanvasData(canvasId)
    if (data.cards[cardId]) {
      data.cards[cardId] = { ...data.cards[cardId], ...updates }
      this.saveCanvasData(canvasId, data)
    }
  },

  removeCard(canvasId: string, cardId: string) {
    const data = this.getCanvasData(canvasId)
    delete data.cards[cardId]
    data.connections = data.connections.filter(
      c => c.fromCardId !== cardId && c.toCardId !== cardId
    )
    this.saveCanvasData(canvasId, data)
  },

  addConnection(canvasId: string, fromCardId: string, toCardId: string, color = '#6BA5E7'): string | null {
    const data = this.getCanvasData(canvasId)
    const exists = data.connections.some(
      c => (c.fromCardId === fromCardId && c.toCardId === toCardId) ||
           (c.fromCardId === toCardId && c.toCardId === fromCardId)
    )
    if (exists) return null
    const id = 'conn-' + generateId()
    data.connections.push({ id, fromCardId, toCardId, color, label: '' })
    this.saveCanvasData(canvasId, data)
    return id
  },

  removeConnection(canvasId: string, connId: string) {
    const data = this.getCanvasData(canvasId)
    data.connections = data.connections.filter(c => c.id !== connId)
    this.saveCanvasData(canvasId, data)
  },

  updateConnection(canvasId: string, connId: string, updates: Partial<Connection>) {
    const data = this.getCanvasData(canvasId)
    const idx = data.connections.findIndex(c => c.id === connId)
    if (idx !== -1) {
      data.connections[idx] = { ...data.connections[idx], ...updates }
      this.saveCanvasData(canvasId, data)
    }
  },

  saveViewport(canvasId: string, panX: number, panY: number, scale: number) {
    const data = this.getCanvasData(canvasId)
    data.viewport = { panX, panY, scale }
    this.saveCanvasData(canvasId, data)
  },

  ensureDefaultCanvas(defaultName = 'Canvas 1'): string {
    const reg = this.getRegistry()
    if (reg.canvasList.length === 0) {
      return this.createCanvas(defaultName)
    }
    if (!reg.activeCanvasId) {
      reg.activeCanvasId = reg.canvasList[0].id
      this.saveRegistry(reg)
    }
    return reg.activeCanvasId!
  },

  exportAllData() {
    const registry = this.getRegistry()
    const canvasDataMap: Record<string, CanvasData> = {}
    registry.canvasList.forEach(canvas => {
      canvasDataMap[canvas.id] = this.getCanvasData(canvas.id)
    })
    return { registry, canvasDataMap }
  },

  importAllData(exported: { registry: CanvasRegistry; canvasDataMap: Record<string, CanvasData> }) {
    if (!exported?.registry) return
    StorageManager.set(CANVASES_KEY, exported.registry)
    if (exported.canvasDataMap) {
      Object.entries(exported.canvasDataMap).forEach(([canvasId, data]) => {
        canvasBinaryExtras.delete(canvasId)
        this.saveCanvasData(canvasId, migrateCanvasData(data))
      })
    }
  },
}

// ============================================
// Group Selection (BFS connected-component)
// ============================================

export function getConnectedGroup(startCardId: string, connections: Connection[]): Set<string> {
  const visited = new Set<string>()
  const queue = [startCardId]
  while (queue.length > 0) {
    const current = queue.shift()!
    if (visited.has(current)) continue
    visited.add(current)
    for (const conn of connections) {
      if (conn.fromCardId === current && !visited.has(conn.toCardId)) queue.push(conn.toCardId)
      if (conn.toCardId === current && !visited.has(conn.fromCardId)) queue.push(conn.fromCardId)
    }
  }
  return visited
}
