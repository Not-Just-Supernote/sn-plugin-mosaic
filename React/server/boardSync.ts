import type { Server as HttpServer } from 'node:http'
import { WebSocket, WebSocketServer, type RawData } from 'ws'
import { getDb } from './db.js'
import {
  CARD_DEFAULTS,
  createEmptyBoardMeta,
  decodeBoard,
  encodeBoard,
  type BoardDoc,
  type InkStroke,
} from '../src/boardFormat.ts'
import { base64ToBytes, bytesToBase64 } from '../src/base64.ts'
import { generateId } from '../src/id.ts'
import { resolveCardSize } from '../src/cardGeometry.ts'
import type {
  BoardClientMessage,
  BoardMutationDomain,
  BoardServerMessage,
  BoardSnapshotMessage,
  ParentMergeMessage,
} from '../src/syncProtocol.ts'

interface SharedBoardRow {
  board_id: string
  revision: number
  board_blob: Uint8Array
}

interface InkBoxRow {
  id: string
  source_board_id: string
  source_card_id: string
  source_card_content: string
  width: number
  height: number
  bounds: string
  stroke_count: number
  strokes_blob: Uint8Array
  reason: string
  created_at: string
}

export type InkBoxListItem = Omit<InkBoxRow, 'strokes_blob' | 'bounds'> & { bounds: number[] }

const clientsByBoard = new Map<string, Set<WebSocket>>()

function decodeBase64Board(value: string): BoardDoc {
  return decodeBoard(base64ToBytes(value))
}

function encodeBase64Board(doc: BoardDoc): string {
  return bytesToBase64(encodeBoard(doc))
}

function readBoard(boardId: string): { revision: number; doc: BoardDoc } | null {
  const row = getDb().prepare('SELECT board_id, revision, board_blob FROM shared_boards WHERE board_id = ?')
    .get(boardId) as SharedBoardRow | undefined
  if (row === undefined) return null
  return { revision: row.revision, doc: decodeBoard(new Uint8Array(row.board_blob)) }
}

function createBoard(boardId: string, doc: BoardDoc): { revision: number; doc: BoardDoc } {
  getDb().prepare('INSERT INTO shared_boards (board_id, revision, board_blob) VALUES (?, 0, ?)')
    .run(boardId, Buffer.from(encodeBoard(doc)))
  return { revision: 0, doc }
}

function emptyBoard(boardId: string): BoardDoc {
  return {
    v: 1,
    meta: { ...createEmptyBoardMeta({ name: 'Mosaic Board' }), id: boardId },
    cards: [],
    connections: [],
    ink: [],
    blobs: {},
  }
}

function writeBoard(boardId: string, revision: number, doc: BoardDoc): void {
  getDb().prepare(`
    UPDATE shared_boards
    SET revision = ?, board_blob = ?, updated_at = datetime('now')
    WHERE board_id = ?
  `).run(revision, Buffer.from(encodeBoard(doc)), boardId)
}

function snapshotMessage(
  boardId: string,
  revision: number,
  doc: BoardDoc,
  sourceClientId?: string,
  operationId?: string,
  domain?: BoardMutationDomain,
): BoardSnapshotMessage {
  return {
    type: 'board.snapshot',
    boardId,
    revision,
    boardBase64: encodeBase64Board(doc),
    sourceClientId,
    operationId,
    domain,
  }
}

function send(socket: WebSocket, message: BoardServerMessage): void {
  if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message))
}

function broadcast(boardId: string, message: BoardServerMessage): void {
  for (const socket of clientsByBoard.get(boardId) ?? []) send(socket, message)
}

function joinBoard(socket: WebSocket, boardId: string): void {
  const clients = clientsByBoard.get(boardId) ?? new Set<WebSocket>()
  clients.add(socket)
  clientsByBoard.set(boardId, clients)
}

function applyDomain(current: BoardDoc, incoming: BoardDoc, domain: BoardMutationDomain): BoardDoc {
  const now = new Date().toISOString()
  // import：一端（插件局域网连接时、网页导入 JSON 时）把整份白板作为权威推上来，同 document 整份替换。
  if (domain === 'document' || domain === 'import') return { ...incoming, meta: { ...incoming.meta, updatedAt: now } }
  if (domain === 'structure') {
    return {
      ...current,
      cards: incoming.cards,
      connections: incoming.connections,
      meta: { ...current.meta, updatedAt: now },
    }
  }
  // 视口是各端本机状态，不进共享文档。
  if (domain === 'viewport') return current
  return { ...current, ink: incoming.ink, meta: { ...current.meta, updatedAt: now } }
}

function strokeBounds(strokes: InkStroke[]): [number, number, number, number] {
  if (strokes.length === 0) return [0, 0, 0, 0]
  return strokes.reduce<[number, number, number, number]>((bounds, stroke) => [
    Math.min(bounds[0], stroke.bounds[0]),
    Math.min(bounds[1], stroke.bounds[1]),
    Math.max(bounds[2], stroke.bounds[2]),
    Math.max(bounds[3], stroke.bounds[3]),
  ], [strokes[0].bounds[0], strokes[0].bounds[1], strokes[0].bounds[2], strokes[0].bounds[3]])
}

function archiveCardInk(boardId: string, cardId: string, content: string, width: number, height: number, strokes: InkStroke[], archiveId?: string): void {
  const now = new Date().toISOString()
  const strokesDoc: BoardDoc = {
    v: 1,
    meta: { ...createEmptyBoardMeta({ name: 'Archived ink' }), id: boardId, updatedAt: now },
    cards: [],
    connections: [],
    ink: strokes,
    blobs: {},
  }
  getDb().prepare(`
    INSERT INTO ink_box_items (
      id, source_board_id, source_card_id, source_card_content, width, height,
      bounds, stroke_count, strokes_blob, reason
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'child-merge')
    ON CONFLICT(id) DO UPDATE SET
      source_card_content = excluded.source_card_content,
      width = excluded.width,
      height = excluded.height,
      bounds = excluded.bounds,
      stroke_count = excluded.stroke_count,
      strokes_blob = excluded.strokes_blob
  `).run(
    archiveId ?? 'inkbox-' + generateId(),
    boardId,
    cardId,
    content,
    width,
    height,
    JSON.stringify(strokeBounds(strokes)),
    strokes.length,
    Buffer.from(encodeBoard(strokesDoc)),
  )
}

/** 母卡合并当前走本地路径（见 Whiteboard.tsx commitParentCard），不依赖已禁用的 WS 协同；
 *  归档到笔迹盒是这条路径里唯一必须落服务端的一步，单独暴露成普通 REST 调用。 */
export function archiveInkBoxItem(payload: {
  archiveId?: string
  boardId: string
  cardId: string
  content: string
  width: number
  height: number
  strokes: Array<Omit<InkStroke, 'points'> & { points: string }>
}): void {
  const strokes: InkStroke[] = payload.strokes.map(stroke => ({
    ...stroke,
    points: base64ToBytes(stroke.points),
  }))
  archiveCardInk(payload.boardId, payload.cardId, payload.content, payload.width, payload.height, strokes, payload.archiveId)
}

function mergeParentCards(message: ParentMergeMessage): { revision: number; doc: BoardDoc; archivedInkCount: number } {
  const state = readBoard(message.boardId)
  if (state === null) throw new Error('共享白板需要先建立连接')
  const selectedIds = new Set(message.childIds.filter(id => id !== message.parentCard.id))
  const selectedCards = state.doc.cards.filter(card => selectedIds.has(card.id))
  const strokesByCard = new Map<string, InkStroke[]>()
  for (const stroke of state.doc.ink) {
    if (!stroke.space.startsWith('card:')) continue
    const cardId = stroke.space.slice(5)
    if (!selectedIds.has(cardId)) continue
    const strokes = strokesByCard.get(cardId) ?? []
    strokes.push(stroke)
    strokesByCard.set(cardId, strokes)
  }

  const db = getDb()
  db.exec('BEGIN IMMEDIATE')
  try {
    if (message.inkPolicy === 'archive') {
      for (const card of selectedCards) {
        const strokes = strokesByCard.get(card.id) ?? []
        if (strokes.length === 0) continue
        const size = resolveCardSize(card)
        archiveCardInk(message.boardId, card.id, card.content, size.width, size.height, strokes)
      }
    }
    const nextRevision = state.revision + 1
    const nextDoc: BoardDoc = {
      ...state.doc,
      cards: [
        ...state.doc.cards.filter(card => !selectedIds.has(card.id) && card.id !== message.parentCard.id),
        message.parentCard,
      ],
      connections: state.doc.connections.filter(connection => (
        !selectedIds.has(connection.fromCardId) && !selectedIds.has(connection.toCardId)
      )),
      ink: state.doc.ink.filter(stroke => (
        !stroke.space.startsWith('card:') || !selectedIds.has(stroke.space.slice(5))
      )),
      meta: { ...state.doc.meta, updatedAt: new Date().toISOString() },
    }
    writeBoard(message.boardId, nextRevision, nextDoc)
    db.exec('COMMIT')
    return {
      revision: nextRevision,
      doc: nextDoc,
      archivedInkCount: [...strokesByCard.values()].reduce((total, strokes) => total + strokes.length, 0),
    }
  } catch (error) {
    db.exec('ROLLBACK')
    throw error
  }
}

function handleMessage(socket: WebSocket, raw: RawData): void {
  let message: BoardClientMessage
  try {
    message = JSON.parse(raw.toString()) as BoardClientMessage
  } catch {
    send(socket, { type: 'board.error', message: '消息格式需要使用 JSON' })
    return
  }

  try {
    if (message.type === 'board.join') {
      // 客户端 join 不带种子（boardBase64 为空，见 boardSyncClient.openSocket）：房间不存在就建空白板，
      // 内容由随后的 import / document 推上来。局域网同步用固定房间（sharedBoard.LOCAL_ROOM_ID），不需要先建房。
      const state = readBoard(message.boardId) ?? createBoard(
        message.boardId,
        message.boardBase64 ? decodeBase64Board(message.boardBase64) : emptyBoard(message.boardId),
      )
      joinBoard(socket, message.boardId)
      send(socket, snapshotMessage(message.boardId, state.revision, state.doc))
      return
    }

    if (message.type === 'board.mutate') {
      const state = readBoard(message.boardId)
      if (state === null) throw new Error('共享白板需要先建立连接')
      const incoming = decodeBase64Board(message.boardBase64)
      const nextDoc = applyDomain(state.doc, incoming, message.domain)
      const nextRevision = state.revision + 1
      writeBoard(message.boardId, nextRevision, nextDoc)
      // 域随快照下发：客户端据此只把该域合进本地（与 mosaic-sync-server.mjs 一致）。
      broadcast(message.boardId, snapshotMessage(
        message.boardId,
        nextRevision,
        nextDoc,
        message.clientId,
        message.operationId,
        message.domain,
      ))
      return
    }

    const result = mergeParentCards(message)
    send(socket, {
      type: 'parent.merge.result',
      operationId: message.operationId,
      revision: result.revision,
      archivedInkCount: result.archivedInkCount,
    })
    broadcast(message.boardId, snapshotMessage(
      message.boardId,
      result.revision,
      result.doc,
      message.clientId,
      message.operationId,
    ))
  } catch (error) {
    send(socket, {
      type: 'board.error',
      message: error instanceof Error ? error.message : String(error),
      operationId: 'operationId' in message ? message.operationId : undefined,
    })
  }
}

export function attachBoardSync(server: HttpServer): void {
  const webSocketServer = new WebSocketServer({ server, path: '/ws/board', maxPayload: 64 * 1024 * 1024 })
  webSocketServer.on('connection', socket => {
    socket.on('message', raw => handleMessage(socket, raw))
    socket.on('close', () => {
      for (const [boardId, clients] of clientsByBoard) {
        clients.delete(socket)
        if (clients.size === 0) clientsByBoard.delete(boardId)
      }
    })
  })
}

export function listInkBoxItems(boardId: string): InkBoxListItem[] {
  const rows = getDb().prepare(`
    SELECT id, source_board_id, source_card_id, source_card_content, width, height,
           bounds, stroke_count, reason, created_at
    FROM ink_box_items WHERE source_board_id = ? ORDER BY created_at DESC
  `).all(boardId) as unknown as Array<Omit<InkBoxRow, 'strokes_blob'>>
  return rows.map(row => ({ ...row, bounds: JSON.parse(row.bounds) as number[] }))
}

export function deleteInkBoxItem(itemId: string): boolean {
  return getDb().prepare('DELETE FROM ink_box_items WHERE id = ?').run(itemId).changes > 0
}

export function getInkBoxItem(itemId: string): { item: InkBoxListItem; boardBase64: string } {
  const row = getDb().prepare('SELECT * FROM ink_box_items WHERE id = ?').get(itemId) as InkBoxRow | undefined
  if (row === undefined) throw new Error('笔迹盒条目需要刷新')
  const { strokes_blob: strokesBlob, bounds, ...item } = row
  return {
    item: { ...item, bounds: JSON.parse(bounds) as number[] },
    boardBase64: bytesToBase64(new Uint8Array(strokesBlob)),
  }
}

export function restoreInkBoxItem(itemId: string, x: number, y: number): BoardSnapshotMessage {
  const row = getDb().prepare('SELECT * FROM ink_box_items WHERE id = ?').get(itemId) as InkBoxRow | undefined
  if (row === undefined) throw new Error('笔迹盒条目需要刷新')
  const state = readBoard(row.source_board_id)
  if (state === null) throw new Error('共享白板需要先建立连接')
  const archived = decodeBoard(new Uint8Array(row.strokes_blob))
  const cardId = 'card-' + generateId()
  const strokes = archived.ink.map(stroke => ({
    ...stroke,
    id: 'stroke-' + generateId(),
    space: `card:${cardId}`,
  }))
  const now = new Date().toISOString()
  const nextDoc: BoardDoc = {
    ...state.doc,
    cards: [...state.doc.cards, {
      ...CARD_DEFAULTS,
      id: cardId,
      content: row.source_card_content,
      x,
      y,
      width: row.width,
      height: row.height,
      tags: [],
      zIndex: state.doc.cards.reduce((highest, card) => Math.max(highest, card.zIndex), 0) + 1,
      sourceType: 'manual',
      createdAt: now,
      kind: 'ink',
    }],
    ink: [...state.doc.ink, ...strokes],
    meta: { ...state.doc.meta, updatedAt: now },
  }
  const revision = state.revision + 1
  const db = getDb()
  db.exec('BEGIN IMMEDIATE')
  try {
    writeBoard(row.source_board_id, revision, nextDoc)
    db.prepare('DELETE FROM ink_box_items WHERE id = ?').run(itemId)
    db.exec('COMMIT')
  } catch (error) {
    db.exec('ROLLBACK')
    throw error
  }
  const snapshot = snapshotMessage(row.source_board_id, revision, nextDoc)
  broadcast(row.source_board_id, snapshot)
  return snapshot
}
