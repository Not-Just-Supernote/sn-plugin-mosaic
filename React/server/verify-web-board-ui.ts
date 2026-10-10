import { chromium, type Browser, type Page } from 'playwright'
import WebSocket from 'ws'
import {
  CARD_DEFAULTS,
  createEmptyBoardMeta,
  decodeBoard,
  encodeBoard,
  packStrokePoints,
  type BoardDoc,
} from '../src/mosaic/boardFormat.ts'
import { base64ToBytes, bytesToBase64 } from '../src/mosaic/base64.ts'
import { getDb } from './db.ts'

const WEB_URL = 'http://localhost:3790'
const BOARD_SOCKET_URL = 'ws://127.0.0.1:3791/ws/board'
const boardId = `web-ui-smoke-${Date.now()}`
const now = new Date().toISOString()

const meta = createEmptyBoardMeta('Web UI smoke')
meta.id = boardId

const seed: BoardDoc = {
  v: 1,
  meta,
  cards: [
    {
      ...CARD_DEFAULTS,
      id: 'card-alpha',
      content: 'Alpha one\nAlpha two',
      x: 80,
      y: 120,
      width: 280,
      height: 180,
      tags: [],
      sourceType: 'manual',
      createdAt: now,
    },
    {
      ...CARD_DEFAULTS,
      id: 'card-beta',
      content: 'Beta one\nBeta two',
      x: 430,
      y: 120,
      width: 280,
      height: 180,
      tags: [],
      sourceType: 'manual',
      createdAt: now,
    },
  ],
  connections: [],
  ink: [{
    id: 'stroke-alpha',
    space: 'card:card-alpha',
    pen: 0,
    sampleScale: 1,
    color: 0xff000000,
    width: 2,
    bounds: [20, 20, 100, 80],
    encoding: 0,
    points: packStrokePoints([
      { x: 20, y: 20, p: 1 },
      { x: 60, y: 50, p: 1 },
      { x: 100, y: 80, p: 1 },
    ]),
    createdAt: now,
  }],
  blobs: {},
}

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message)
}

function joinBoard(seedDoc: BoardDoc): Promise<{ revision: number; doc: BoardDoc }> {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(BOARD_SOCKET_URL)
    const timer = setTimeout(() => {
      socket.close()
      reject(new Error('board.join timeout'))
    }, 5000)
    socket.once('open', () => socket.send(JSON.stringify({
      type: 'board.join',
      boardId,
      clientId: `ui-smoke-reader-${Date.now()}`,
      boardBase64: bytesToBase64(encodeBoard(seedDoc)),
    })))
    socket.once('error', reject)
    socket.on('message', raw => {
      const message = JSON.parse(raw.toString())
      if (message.type !== 'board.snapshot') return
      clearTimeout(timer)
      socket.close()
      resolve({
        revision: message.revision,
        doc: decodeBoard(base64ToBytes(message.boardBase64)),
      })
    })
  })
}

async function waitForRemoteBoard(
  predicate: (doc: BoardDoc) => boolean,
  timeoutMs = 6000,
): Promise<{ revision: number; doc: BoardDoc }> {
  const deadline = Date.now() + timeoutMs
  let latest = await joinBoard(seed)
  while (!predicate(latest.doc) && Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 120))
    latest = await joinBoard(seed)
  }
  assert(predicate(latest.doc), `remote board predicate timed out at revision ${latest.revision}`)
  return latest
}

function publishRemoteCard(state: { revision: number; doc: BoardDoc }): Promise<void> {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(BOARD_SOCKET_URL)
    const operationId = `ui-smoke-remote-${Date.now()}`
    const timer = setTimeout(() => {
      socket.close()
      reject(new Error('remote structure mutation timeout'))
    }, 5000)
    socket.once('open', () => socket.send(JSON.stringify({
      type: 'board.join',
      boardId,
      clientId: 'ui-smoke-remote',
      boardBase64: bytesToBase64(encodeBoard(state.doc)),
    })))
    socket.once('error', reject)
    socket.on('message', raw => {
      const message = JSON.parse(raw.toString())
      if (message.type !== 'board.snapshot') return
      if (message.operationId === operationId) {
        clearTimeout(timer)
        socket.close()
        resolve()
        return
      }
      const current = decodeBoard(base64ToBytes(message.boardBase64))
      socket.send(JSON.stringify({
        type: 'board.mutate',
        boardId,
        clientId: 'ui-smoke-remote',
        operationId,
        baseRevision: message.revision,
        domain: 'structure',
        boardBase64: bytesToBase64(encodeBoard({
          ...current,
          cards: [...current.cards, {
            ...CARD_DEFAULTS,
            id: 'card-remote',
            content: '远端卡片',
            x: 780,
            y: 160,
            width: 260,
            height: 160,
            tags: [],
            sourceType: 'manual',
            createdAt: new Date().toISOString(),
          }],
        })),
      }))
    })
  })
}

async function waitForCount(page: Page, selector: string, count: number): Promise<void> {
  const deadline = Date.now() + 6000
  while (Date.now() < deadline) {
    if (await page.locator(selector).count() === count) return
    await page.waitForTimeout(50)
  }
  throw new Error(`locator count timed out: ${selector} expected ${count}`)
}

let browser: Browser | undefined
const pageErrors: string[] = []
let boxCardId = ''

try {
  await joinBoard(seed)
  const boxContent = `Box mother ${boardId}`
  const boxResponse = await fetch('http://127.0.0.1:3791/api/box/inbox/cards', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content: boxContent, preview: boxContent, sourceType: 'manual' }),
  })
  const boxPayload = await boxResponse.json() as { card: { id: string } }
  boxCardId = boxPayload.card.id

  browser = await chromium.launch({ headless: true, channel: 'msedge' })
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const page = await context.newPage()
  page.on('pageerror', error => pageErrors.push(error.message))
  await page.addInitScript(({ activeBoardId }) => {
    const timestamp = new Date().toISOString()
    localStorage.setItem('wbai_canvases', JSON.stringify({
      canvasList: [{
        id: activeBoardId,
        name: 'UI Smoke',
        createdAt: timestamp,
        updatedAt: timestamp,
      }],
      activeCanvasId: activeBoardId,
    }))
  }, { activeBoardId: boardId })

  await page.goto(WEB_URL, { waitUntil: 'networkidle' })
  await waitForCount(page, '.whiteboard-card', 2)

  const directParentText = `Direct parent ${boardId}`
  await page.locator('button.panel-toggle.right').click()
  await page.locator('.clipboard-paste-area').fill(directParentText)
  await page.getByRole('button', { name: '添加母卡到白板' }).click()
  await page.locator('.be-overlay').waitFor({ state: 'visible' })
  await waitForCount(page, '.draft-parent-card', 1)
  await waitForCount(page, '.whiteboard-card', 3)
  await waitForRemoteBoard(doc => doc.cards.length === 2 && doc.cards.every(card => card.content !== directParentText))
  await page.getByRole('button', { name: '关闭' }).click()
  await waitForCount(page, '.whiteboard-card', 2)

  const boxItem = page.locator('.card-box-item').filter({ hasText: boxContent })
  await boxItem.getByRole('button', { name: '整理为母卡' }).click()
  await page.locator('.be-overlay').waitFor({ state: 'visible' })
  assert(await page.locator('.be-create-btn').first().isDisabled(), 'box parent split should wait for save')
  await page.getByRole('button', { name: '关闭' }).click()
  await boxItem.waitFor({ state: 'visible' })

  await boxItem.getByRole('button', { name: '整理为母卡' }).click()
  await page.getByRole('button', { name: '保存母卡' }).click()
  await boxItem.waitFor({ state: 'detached' })
  await waitForCount(page, '.whiteboard-card', 3)
  const boxParent = await waitForRemoteBoard(doc => (
    doc.cards.length === 3
    && doc.cards.some(card => card.content === boxContent && card.role === 'parent')
  ))
  await page.getByRole('button', { name: '关闭' }).click()

  await page.locator('button.panel-toggle.left').click()
  await page.locator('button.panel-toggle.right').click()

  await page.locator('.whiteboard-card').nth(0).click({ modifiers: ['Shift'] })
  await page.locator('.whiteboard-card').nth(1).click({ modifiers: ['Shift'] })
  await waitForCount(page, '.whiteboard-card.selected', 2)
  await page.getByRole('button', { name: '合并为母卡' }).click()
  await page.locator('.be-overlay').waitFor({ state: 'visible' })
  await waitForCount(page, '.whiteboard-card', 4)
  await waitForRemoteBoard(doc => doc.cards.length === 3 && doc.ink.length === 1)
  await page.getByRole('button', { name: '关闭' }).click()
  await waitForCount(page, '.whiteboard-card', 3)

  await page.locator('.whiteboard-card').nth(0).click({ modifiers: ['Shift'] })
  await page.locator('.whiteboard-card').nth(1).click({ modifiers: ['Shift'] })
  await page.getByRole('button', { name: '合并为母卡' }).click()
  assert(await page.locator('.be-create-btn').first().isDisabled(), 'merged parent split should wait for save')
  await page.getByRole('button', { name: '保存母卡' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '归档到笔迹盒并继续' }).click()
  await waitForCount(page, '.whiteboard-card', 2)
  await waitForCount(page, '.be-create-disabled-reason', 0)
  await waitForCount(page, '.canvas-card-role-badge.parent', 2)

  const gutters = page.locator('.be-gutter-marker')
  assert(await gutters.count() === 7, 'parent card line count mismatch')
  await gutters.nth(0).click()
  await gutters.nth(1).click()
  assert(await page.locator('.be-create-btn').first().isEnabled(), 'saved parent split should accept marked ranges')
  await page.locator('.be-create-btn').first().click()
  await waitForCount(page, '.whiteboard-card', 3)
  await waitForCount(page, '.be-line-text.extracted', 2)

  await gutters.nth(5).click()
  await gutters.nth(6).click()
  await page.locator('.be-create-btn').first().click()
  await waitForCount(page, '.whiteboard-card', 4)
  await waitForCount(page, '.be-line-text.extracted', 4)

  await page.locator('.be-toolbar-actions').getByRole('button', { name: '关闭' }).click()
  const webPublished = await waitForRemoteBoard(doc => {
    const mergedParent = doc.cards.find(card => card.role === 'parent' && card.content.includes('Alpha one'))
    const children = doc.cards.filter(card => card.parentId === mergedParent?.id)
    return doc.cards.length === 4
      && doc.ink.length === 0
      && mergedParent?.childIds?.length === 2
      && mergedParent.extractedRanges?.length === 2
      && children.length === 2
  })
  assert(webPublished.revision > boxParent.revision, 'parent split revision should advance')

  await page.getByRole('button', { name: '笔迹盒' }).click()
  await waitForCount(page, '.ink-box-item', 1)
  await waitForCount(page, '.ink-box-item svg polyline', 1)
  await page.locator('.ink-box-item').getByRole('button', { name: '放到白板' }).click()
  await waitForCount(page, '.ink-box-item', 0)
  await waitForCount(page, '.whiteboard-card', 5)
  await page.locator('.ink-box-header').getByRole('button', { name: '关闭' }).click()

  const restored = await waitForRemoteBoard(doc => doc.cards.length === 5 && doc.ink.length === 1)
  assert(restored.revision > webPublished.revision, 'restore revision did not advance')

  await publishRemoteCard(restored)
  await waitForCount(page, '.whiteboard-card', 6)
  await page.locator('.whiteboard-card').filter({ hasText: '远端卡片' }).waitFor({ state: 'visible' })

  await page.reload({ waitUntil: 'networkidle' })
  await waitForCount(page, '.canvas-card-role-badge.parent', 2)
  await waitForCount(page, '.canvas-card-role-badge.child', 2)

  assert(pageErrors.length === 0, `page errors: ${pageErrors.join(' | ')}`)
  console.log('transactional parent drafts, box origin, split, ink restore and bidirectional sync passed')
} finally {
  await browser?.close()
  const db = getDb()
  if (boxCardId) db.prepare('DELETE FROM box_cards WHERE id = ?').run(boxCardId)
  db.prepare('DELETE FROM ink_box_items WHERE source_board_id = ?').run(boardId)
  db.prepare('DELETE FROM shared_boards WHERE board_id = ?').run(boardId)
}
