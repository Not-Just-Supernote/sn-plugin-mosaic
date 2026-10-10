import WebSocket from 'ws'
import { CARD_DEFAULTS, createEmptyBoardMeta, decodeBoard, encodeBoard, packStrokePoints } from '../src/mosaic/boardFormat.ts'
import { base64ToBytes, bytesToBase64 } from '../src/mosaic/base64.ts'
import { getDb } from './db.ts'

const boardId = 'smoke-' + Date.now()
const meta = createEmptyBoardMeta('sync smoke')
meta.id = boardId
const seed = { v: 1, meta, cards: [], connections: [], ink: [], blobs: {} }
const socket = new WebSocket('ws://127.0.0.1:3791/ws/board')
const now = new Date().toISOString()
const firstCard = { ...CARD_DEFAULTS, id: 'card-a', tags: [], content: 'A', sourceType: 'manual' as const, createdAt: now }
const secondCard = { ...CARD_DEFAULTS, id: 'card-b', tags: [], content: 'B', x: 400, sourceType: 'manual' as const, createdAt: now }
const parentCard = {
  ...CARD_DEFAULTS,
  id: 'parent-ab',
  tags: [],
  content: 'A\n\nB',
  x: 200,
  y: 300,
  width: 420,
  height: 300,
  role: 'parent' as const,
  childIds: [],
  extractedRanges: [],
  sourceType: 'manual' as const,
  createdAt: now,
}
const stroke = {
  id: 'stroke-a',
  space: 'card:card-a',
  pen: 0,
  sampleScale: 1,
  color: 0xff000000,
  width: 2,
  bounds: [10, 10, 40, 40] as [number, number, number, number],
  encoding: 0,
  points: packStrokePoints([{ x: 10, y: 10, p: 1 }, { x: 40, y: 40, p: 1 }]),
  createdAt: now,
}
let restoreStarted = false

function cleanup(): void {
  const db = getDb()
  db.prepare('DELETE FROM ink_box_items WHERE source_board_id = ?').run(boardId)
  db.prepare('DELETE FROM shared_boards WHERE board_id = ?').run(boardId)
}

const timeout = setTimeout(() => {
  cleanup()
  console.error('sync smoke timeout')
  process.exit(1)
}, 8000)

socket.on('open', () => socket.send(JSON.stringify({
  type: 'board.join',
  boardId,
  clientId: 'smoke-client',
  boardBase64: bytesToBase64(encodeBoard(seed)),
})))

socket.on('message', async raw => {
  const message = JSON.parse(raw.toString())
  if (message.type === 'parent.merge.result' && !restoreStarted) {
    restoreStarted = true
    const listResponse = await fetch(`http://127.0.0.1:3791/api/ink-box/${boardId}`)
    const list = await listResponse.json() as { items: Array<{ id: string }> }
    if (list.items.length !== 1) throw new Error(`expected one ink-box item, got ${list.items.length}`)
    await fetch(`http://127.0.0.1:3791/api/ink-box/item/${list.items[0].id}/restore`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ x: 80, y: 160 }),
    })
    return
  }
  if (message.type !== 'board.snapshot') return
  if (message.revision === 0) {
    socket.send(JSON.stringify({
      type: 'board.mutate',
      boardId,
      clientId: 'smoke-client',
      operationId: 'smoke-op',
      baseRevision: 0,
      domain: 'structure',
      boardBase64: bytesToBase64(encodeBoard({ ...seed, cards: [firstCard, secondCard] })),
    }))
    return
  }
  if (message.revision === 1) {
    socket.send(JSON.stringify({
      type: 'board.mutate',
      boardId,
      clientId: 'smoke-client',
      operationId: 'smoke-ink',
      baseRevision: 1,
      domain: 'ink',
      boardBase64: bytesToBase64(encodeBoard({ ...seed, ink: [stroke] })),
    }))
    return
  }
  if (message.revision === 2) {
    socket.send(JSON.stringify({
      type: 'parent.merge',
      boardId,
      clientId: 'smoke-client',
      operationId: 'smoke-merge',
      childIds: ['card-a', 'card-b'],
      inkPolicy: 'archive',
      parentCard,
    }))
    return
  }
  if (message.revision === 3) {
    const merged = decodeBoard(base64ToBytes(message.boardBase64))
    if (merged.cards.length !== 1 || merged.cards[0]?.id !== parentCard.id || merged.cards[0]?.role !== 'parent') {
      throw new Error(`parent merge result mismatch cards=${merged.cards.length}`)
    }
    return
  }
  if (message.revision === 4) {
    const restored = decodeBoard(base64ToBytes(message.boardBase64))
    if (restored.cards.length !== 2 || restored.ink.length !== 1) {
      throw new Error(`restore result mismatch cards=${restored.cards.length} ink=${restored.ink.length}`)
    }
    clearTimeout(timeout)
    console.log(`board sync, parent merge and ink restore passed at revision ${message.revision}`)
    cleanup()
    socket.close()
  }
})
