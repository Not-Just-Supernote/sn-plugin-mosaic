import { createServer } from 'node:http'
import { randomBytes, createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync, renameSync, existsSync } from 'node:fs'
import { DatabaseSync } from 'node:sqlite'
import { WebSocketServer } from 'ws'

const port = Number(process.env.PORT || 3792)
const dataDir = process.env.MOSAIC_SYNC_DATA || '/var/lib/mosaic-sync'
const maxBytes = 4 * 1024 * 1024
// 资源（图片卡片像素 / 笔记卡片预览 PNG）：不进文档，按 房间/引用名 单独存放。
const assetDir = dataDir + '/assets'
const maxAssetBytes = 2 * 1024 * 1024
const maxAssetBytesPerRoom = 64 * 1024 * 1024
const ASSET_REF = /^[a-zA-Z0-9_-]{1,96}\.png$/
mkdirSync(dataDir, { recursive: true })
mkdirSync(assetDir, { recursive: true })
const database = new DatabaseSync(dataDir + '/boards.db')
database.exec('CREATE TABLE IF NOT EXISTS rooms (id TEXT PRIMARY KEY, revision INTEGER NOT NULL, board BLOB NOT NULL)')
database.exec('CREATE TABLE IF NOT EXISTS assets (room_id TEXT NOT NULL, ref TEXT NOT NULL, bytes INTEGER NOT NULL, etag TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY (room_id, ref))')
const members = new Map()
const rates = new Map()
const origins = new Set((process.env.MOSAIC_SYNC_ORIGINS || 'https://re.yalums.top,http://localhost:3790,http://127.0.0.1:3790').split(','))

function log(event, details = {}) { console.log(JSON.stringify({ time: new Date().toISOString(), event, ...details })) }
function roomTag(id) { return createHash('sha256').update(id).digest('hex').slice(0, 12) }
function allowedOrigin(request) { return !request.headers.origin || origins.has(request.headers.origin) }
function validId(id) { return typeof id === 'string' && /^[a-f0-9]{48}$/.test(id) }
function rateLimit(key) {
  const now = Date.now()
  for (const [address, counter] of rates) if (counter.until < now) rates.delete(address)
  const counter = rates.get(key) || { count: 0, until: now + 60000 }
  if (rates.size >= 1024 && !rates.has(key)) return false
  counter.count++
  rates.set(key, counter)
  return counter.count <= 10
}
function decodePayload(payload) {
  if (typeof payload !== 'string' || payload.length > maxBytes || !/^[A-Za-z0-9+/]*={0,2}$/.test(payload)) throw new Error('payload')
  const bytes = Buffer.from(payload, 'base64')
  if (bytes.length < 12 || bytes.subarray(0, 6).toString() !== 'MOSBRD') throw new Error('document')
  return bytes
}
function room(id) { return database.prepare('SELECT revision, board FROM rooms WHERE id = ?').get(id) }

// ── 按域合并 ─────────────────────────────────────────────────────────────────
// 客户端每次都发整份文档，但只声明自己改动的域：structure（cards/connections）
// 或 ink（笔迹）。以前服务器不看域、整份覆盖——插件写字与网页移卡同时发生时后到的一份会把
// 另一份的改动抹掉。这里不解码内容（boardFormat 在 TS 里，单文件部署拿不到），只在容器里的
// MessagePack 顶层 map 上做键级拼接：取来稿为底，把不属于该域的键换回服务器现存的值。
// document / import 仍整份替换；viewport 不改内容（两端都不吃远端视口）。
const MAGIC = Buffer.from('MOSBRD')
const STRUCTURE_KEYS = ['cards', 'connections']
const INK_KEYS = ['ink']
const CRC_TABLE = new Int32Array(256)
for (let n = 0; n < 256; n++) {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  CRC_TABLE[n] = c
}
function crc32(bytes) {
  let crc = -1
  for (let i = 0; i < bytes.length; i++) crc = CRC_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8)
  return (crc ^ -1) >>> 0
}
/** 跳过 pos 处的一个 MessagePack 值，返回其结束偏移。 */
function skipValue(buf, pos) {
  const b = buf[pos]
  if (b <= 0x7f || b >= 0xe0 || b === 0xc0 || b === 0xc2 || b === 0xc3) return pos + 1
  if (b >= 0x80 && b <= 0x8f) return skipPairs(buf, pos + 1, b & 0x0f)
  if (b >= 0x90 && b <= 0x9f) return skipItems(buf, pos + 1, b & 0x0f)
  if (b >= 0xa0 && b <= 0xbf) return pos + 1 + (b & 0x1f)
  switch (b) {
    case 0xc4: return pos + 2 + buf[pos + 1]
    case 0xc5: return pos + 3 + buf.readUInt16BE(pos + 1)
    case 0xc6: return pos + 5 + buf.readUInt32BE(pos + 1)
    case 0xc7: return pos + 3 + buf[pos + 1]
    case 0xc8: return pos + 4 + buf.readUInt16BE(pos + 1)
    case 0xc9: return pos + 6 + buf.readUInt32BE(pos + 1)
    case 0xca: case 0xd2: case 0xce: return pos + 5
    case 0xcb: case 0xd3: case 0xcf: return pos + 9
    case 0xcc: case 0xd0: return pos + 2
    case 0xcd: case 0xd1: return pos + 3
    case 0xd4: return pos + 3
    case 0xd5: return pos + 4
    case 0xd6: return pos + 6
    case 0xd7: return pos + 10
    case 0xd8: return pos + 18
    case 0xd9: return pos + 2 + buf[pos + 1]
    case 0xda: return pos + 3 + buf.readUInt16BE(pos + 1)
    case 0xdb: return pos + 5 + buf.readUInt32BE(pos + 1)
    case 0xdc: return skipItems(buf, pos + 3, buf.readUInt16BE(pos + 1))
    case 0xdd: return skipItems(buf, pos + 5, buf.readUInt32BE(pos + 1))
    case 0xde: return skipPairs(buf, pos + 3, buf.readUInt16BE(pos + 1))
    case 0xdf: return skipPairs(buf, pos + 5, buf.readUInt32BE(pos + 1))
    default: throw new Error('msgpack')
  }
}
function skipItems(buf, pos, count) { for (let i = 0; i < count; i++) pos = skipValue(buf, pos); return pos }
function skipPairs(buf, pos, count) { return skipItems(buf, pos, count * 2) }
function readKey(buf, pos) {
  const b = buf[pos]
  let len, start
  if (b >= 0xa0 && b <= 0xbf) { len = b & 0x1f; start = pos + 1 }
  else if (b === 0xd9) { len = buf[pos + 1]; start = pos + 2 }
  else if (b === 0xda) { len = buf.readUInt16BE(pos + 1); start = pos + 3 }
  else if (b === 0xdb) { len = buf.readUInt32BE(pos + 1); start = pos + 5 }
  else throw new Error('msgpack-key')
  return { key: buf.toString('utf8', start, start + len), end: start + len }
}
/** 容器 → 顶层 map 的 [key, valueBytes] 有序列表。 */
function topLevelEntries(container) {
  if (!container.subarray(0, 6).equals(MAGIC)) throw new Error('document')
  const payloadLength = container.readUInt32LE(8)
  const payload = container.subarray(12, 12 + payloadLength)
  if (crc32(payload) !== container.readUInt32LE(12 + payloadLength)) throw new Error('crc')
  const b = payload[0]
  let count, pos
  if (b >= 0x80 && b <= 0x8f) { count = b & 0x0f; pos = 1 }
  else if (b === 0xde) { count = payload.readUInt16BE(1); pos = 3 }
  else if (b === 0xdf) { count = payload.readUInt32BE(1); pos = 5 }
  else throw new Error('msgpack-root')
  const entries = []
  for (let i = 0; i < count; i++) {
    const { key, end } = readKey(payload, pos)
    const valueEnd = skipValue(payload, end)
    entries.push([key, payload.subarray(end, valueEnd)])
    pos = valueEnd
  }
  return { version: container[6], flags: container[7], entries }
}
function encodeKey(key) {
  const utf8 = Buffer.from(key, 'utf8')
  if (utf8.length < 32) return Buffer.concat([Buffer.from([0xa0 | utf8.length]), utf8])
  if (utf8.length < 256) return Buffer.concat([Buffer.from([0xd9, utf8.length]), utf8])
  const head = Buffer.alloc(3); head[0] = 0xda; head.writeUInt16BE(utf8.length, 1)
  return Buffer.concat([head, utf8])
}
function buildContainer(version, flags, entries) {
  const parts = []
  if (entries.length < 16) parts.push(Buffer.from([0x80 | entries.length]))
  else { const head = Buffer.alloc(3); head[0] = 0xde; head.writeUInt16BE(entries.length, 1); parts.push(head) }
  for (const [key, value] of entries) { parts.push(encodeKey(key)); parts.push(value) }
  const payload = Buffer.concat(parts)
  const container = Buffer.alloc(12 + payload.length + 4)
  MAGIC.copy(container, 0)
  container[6] = version
  container[7] = flags
  container.writeUInt32LE(payload.length, 8)
  payload.copy(container, 12)
  container.writeUInt32LE(crc32(payload), 12 + payload.length)
  return container
}
/**
 * 把 incoming 按 domain 合到 current 上。structure：来稿为底，笔迹换回现存；ink：来稿为底，
 * 卡片/连接/白板换回现存。合并失败（旧格式/损坏）退回整份替换，行为与以前一致。
 */
function mergeByDomain(current, incoming, domain) {
  const keep = domain === 'structure' ? INK_KEYS : domain === 'ink' ? STRUCTURE_KEYS : null
  if (keep === null) return incoming
  try {
    const base = topLevelEntries(incoming)
    const existing = topLevelEntries(Buffer.from(current))
    const kept = new Map(existing.entries.filter(([key]) => keep.includes(key)))
    const merged = base.entries.filter(([key]) => !keep.includes(key))
    // 现存值按原顺序补回；现存没有该键（例如没有笔迹）时来稿的那份也不保留。
    for (const key of keep) if (kept.has(key)) merged.push([key, kept.get(key)])
    return buildContainer(base.version, base.flags, merged)
  } catch (error) {
    log('merge.fallback', { domain, reason: error.message })
    return incoming
  }
}
function send(socket, message) {
  if (socket.readyState !== 1) return
  if (socket.bufferedAmount > maxBytes * 2) { socket.close(1013); return }
  socket.send(JSON.stringify(message))
}
function snapshot(id, record, clientId, operationId, domain) {
  return { type: 'board.snapshot', boardId: id, revision: record.revision,
    boardBase64: Buffer.from(record.board).toString('base64'), sourceClientId: clientId, operationId, domain }
}
function checkQuota(bytes, previous = 0) {
  const total = database.prepare('SELECT COALESCE(SUM(length(board)),0) AS total FROM rooms').get().total
  if (total - previous + bytes > 128 * 1024 * 1024) throw new Error('quota')
}
function reply(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
  response.end(JSON.stringify(body))
}
async function readBody(request, response, limit) {
  const chunks = []
  let size = 0
  for await (const chunk of request) {
    size += chunk.length
    if (size > limit) { reply(response, 413, { code: 'size' }); return null }
    chunks.push(chunk)
  }
  return Buffer.concat(chunks)
}
function assetPath(roomId, ref) { return `${assetDir}/${roomId}-${ref}` }
/** GET/PUT /api/board/assets/<roomId>/<ref>：插件把图片卡片 PNG、笔记预览 PNG 传上来，网页按引用名取。 */
async function handleAsset(request, response, roomId, ref) {
  if (!validId(roomId) || !ASSET_REF.test(ref) || !room(roomId)) { reply(response, 404, { code: 'route' }); return }
  const record = database.prepare('SELECT bytes, etag FROM assets WHERE room_id = ? AND ref = ?').get(roomId, ref)
  if (request.method === 'GET') {
    const file = assetPath(roomId, ref)
    if (!record || !existsSync(file)) { reply(response, 404, { code: 'asset' }); return }
    if (request.headers['if-none-match'] === record.etag) { response.writeHead(304, { ETag: record.etag }); response.end(); return }
    response.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': record.bytes, ETag: record.etag, 'Cache-Control': 'no-cache' })
    response.end(readFileSync(file))
    return
  }
  if (request.method !== 'PUT') { reply(response, 405, { code: 'method' }); return }
  const body = await readBody(request, response, Math.ceil(maxAssetBytes * 4 / 3) + 4096)
  if (body === null) return
  // 插件端 RN fetch 发不了二进制体，走 JSON base64；也接受裸 image/png。
  const bytes = (request.headers['content-type'] || '').startsWith('image/png')
    ? body
    : Buffer.from(JSON.parse(body.toString()).pngBase64 || '', 'base64')
  if (bytes.length < 8 || bytes.length > maxAssetBytes || bytes.readUInt32BE(0) !== 0x89504e47) { reply(response, 400, { code: 'png' }); return }
  const used = database.prepare('SELECT COALESCE(SUM(bytes),0) AS total FROM assets WHERE room_id = ?').get(roomId).total
  if (used - (record ? record.bytes : 0) + bytes.length > maxAssetBytesPerRoom) { reply(response, 413, { code: 'quota' }); return }
  const etag = '"' + createHash('sha256').update(bytes).digest('hex').slice(0, 32) + '"'
  if (record && record.etag === etag) { reply(response, 200, { etag, unchanged: true }); return }
  const file = assetPath(roomId, ref)
  writeFileSync(file + '.tmp', bytes)
  renameSync(file + '.tmp', file)
  database.prepare('INSERT INTO assets VALUES (?, ?, ?, ?, ?) ON CONFLICT(room_id, ref) DO UPDATE SET bytes = excluded.bytes, etag = excluded.etag, updated_at = excluded.updated_at')
    .run(roomId, ref, bytes.length, etag, new Date().toISOString())
  log('asset.put', { room: roomTag(roomId), ref, bytes: bytes.length })
  reply(response, 200, { etag })
}
const httpServer = createServer(async (request, response) => {
  if (request.method === 'GET' && request.url === '/health') { reply(response, 200, { status: 'ok' }); return }
  const asset = /^\/api\/board\/assets\/([a-f0-9]{48})\/([^/?#]+)$/.exec(request.url || '')
  if (asset) {
    if (!allowedOrigin(request)) { reply(response, 403, { code: 'origin' }); return }
    try { await handleAsset(request, response, asset[1], decodeURIComponent(asset[2])) }
    catch (error) { log('asset.error', { reason: error.message }); if (!response.headersSent) reply(response, 400, { code: 'asset' }) }
    return
  }
  if (request.method !== 'POST' || request.url !== '/api/board/rooms') { reply(response, 404, { code: 'route' }); return }
  if (!allowedOrigin(request)) { reply(response, 403, { code: 'origin' }); return }
  if (!rateLimit(request.headers['x-real-ip'] || request.socket.remoteAddress)) { reply(response, 429, { code: 'rate' }); return }
  try {
    const body = await readBody(request, response, maxBytes + 128)
    if (body === null) return
    const bytes = decodePayload(JSON.parse(body.toString()).boardBase64)
    const count = database.prepare('SELECT COUNT(*) AS count FROM rooms').get().count
    if (count >= 256) throw new Error('quota')
    checkQuota(bytes.length)
    const boardId = randomBytes(24).toString('hex')
    database.prepare('INSERT INTO rooms VALUES (?, 0, ?)').run(boardId, bytes)
    log('room.created', { room: roomTag(boardId), bytes: bytes.length })
    reply(response, 201, { boardId })
  } catch { log('room.create.error'); if (!response.headersSent) reply(response, 400, { code: 'payload_or_quota' }) }
})
httpServer.requestTimeout = 20000
httpServer.headersTimeout = 15000

const websocketServer = new WebSocketServer({ noServer: true, maxPayload: maxBytes + 2048, perMessageDeflate: false })
websocketServer.on('connection', socket => {
  let joinedId = ''
  let messages = 0
  let since = Date.now()
  let lastOperation = ''
  const joinTimeout = setTimeout(() => socket.close(1008), 15000)
  socket.alive = true
  socket.on('pong', () => { socket.alive = true })
  socket.on('error', () => log('socket.error'))
  socket.on('message', raw => {
    try {
      if (Date.now() - since > 5000) { since = Date.now(); messages = 0 }
      if (++messages > 60) { socket.close(1008); return }
      const message = JSON.parse(raw.toString())
      if (!validId(message.boardId)) throw new Error('room')
      if (message.type === 'board.join') {
        const record = room(message.boardId)
        if (!record || joinedId) throw new Error('room')
        joinedId = message.boardId
        clearTimeout(joinTimeout)
        const clients = members.get(joinedId) || new Set()
        clients.add(socket); members.set(joinedId, clients)
        send(socket, snapshot(joinedId, record))
        log('board.join', { room: roomTag(joinedId), revision: record.revision })
        return
      }
      if (message.type !== 'board.mutate' || message.boardId !== joinedId) throw new Error('membership')
      const record = room(joinedId)
      if (!record) throw new Error('room')
      if (typeof message.operationId !== 'string' || message.operationId.length > 160) throw new Error('operation')
      if (message.operationId === lastOperation) { send(socket, snapshot(joinedId, record, message.clientId, message.operationId)); return }
      if (message.baseRevision !== record.revision) {
        send(socket, snapshot(joinedId, record))
        log('board.conflict', { room: roomTag(joinedId), revision: record.revision })
        return
      }
      if (message.domain === 'viewport') {
        // 视口是各端本机状态，不进共享文档：只回一份现状当 ack。
        send(socket, snapshot(joinedId, record, message.clientId, message.operationId))
        return
      }
      const incoming = decodePayload(message.boardBase64)
      const bytes = mergeByDomain(record.board, incoming, message.domain)
      checkQuota(bytes.length, record.board.length)
      const revision = record.revision + 1
      database.prepare('UPDATE rooms SET revision=?, board=? WHERE id=?').run(revision, bytes, joinedId)
      lastOperation = message.operationId
      // 域随快照广播出去：另一端据此只把该域合进本地（structure 不动本地笔迹、ink 不动本地卡片）。
      const domain = ['structure', 'ink', 'import'].includes(message.domain) ? message.domain : 'document'
      const update = snapshot(joinedId, { revision, board: bytes }, message.clientId, message.operationId, domain)
      for (const client of members.get(joinedId) || []) send(client, update)
      log('board.mutate', { room: roomTag(joinedId), revision, bytes: bytes.length, domain })
    } catch {
      send(socket, { type: 'board.error', code: 'invalid_request', message: '请检查共享码、快照大小和连接状态' })
      log('message.error')
    }
  })
  socket.on('close', () => {
    clearTimeout(joinTimeout)
    const clients = members.get(joinedId)
    clients?.delete(socket)
    if (clients?.size === 0) members.delete(joinedId)
    log('socket.close')
  })
})
httpServer.on('upgrade', (request, socket, head) => {
  if (request.url !== '/ws/board' || !allowedOrigin(request) || websocketServer.clients.size >= 32) { socket.destroy(); return }
  websocketServer.handleUpgrade(request, socket, head, client => websocketServer.emit('connection', client))
})
const heartbeat = setInterval(() => {
  for (const socket of websocketServer.clients) {
    if (!socket.alive) { socket.terminate(); continue }
    socket.alive = false
    socket.ping()
  }
}, 30000)
heartbeat.unref()
httpServer.listen(port, '127.0.0.1', () => log('server.ready', { port }))
