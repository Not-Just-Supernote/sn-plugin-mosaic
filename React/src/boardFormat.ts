// boardFormat.ts — Mosaic board binary container format (MessagePack payload)
//
// Container: "MOSBRD" magic(6) + formatVersion u8(1) + flags u8(1)
//          + payload length u32 LE + MessagePack payload + CRC32(payload) u32 LE
//
// BoardDoc: { v, meta, cards[], connections[], ink[], blobs{}, ext? }
// Sparse encoding: fields equal to defaults are omitted on write.

import type { Card, Connection, Viewport, CanvasData } from './types'
import { crc32 } from './crc32'
import { decodeUtf8, encodeUtf8 } from './utf8'

// ── Types ──

export interface StrokePoint {
  x: number
  y: number
  p: number // pressure 0..1
}

export interface InkStroke {
  id: string
  space: string       // 'canvas' | 'card:<id>' | 'connection:<id>'
  /** drawPath penType: 10 needle, 11 marker, 15 fixed brush, 16 ink pressure. */
  pen: number
  /** PalettePanel raw thickness integer sent to drawPath transaction 2. */
  drawPathWidth?: number
  /** physical-pixel/world-unit scale captured at pen down */
  sampleScale: number
  width: number
  color: number       // ARGB u32
  encoding?: number
  points: Uint8Array  // packed LE Float32 triples (x,y,pressure)
  bounds: number[]    // [minX, minY, maxX, maxY]
  createdAt?: string
}

export interface BoardDocMeta {
  id: string
  name: string
  createdAt: string
  updatedAt: string
  viewport: Viewport
  ext?: Record<string, unknown>
}

export interface BoardDoc {
  v: number
  meta: BoardDocMeta
  cards: Card[]
  connections: Connection[]
  ink: InkStroke[]
  blobs: Record<string, Uint8Array>
  ext?: Record<string, unknown>
}

// ── Constants ──

export const STROKE_ENCODING_F32X3 = 1

export const BOARD_STORAGE_B64_PREFIX = 'MOSBRD:'

/** PalettePanel/PenSizeSpec width snapping for the Web side of the shared board. */
export function canonicalDrawPathWidth(pen: number, width: number): number {
  const steps = pen === 11
    ? [3800]
    : pen === 10 || pen === 15
      ? [200, 300, 400, 500, 600, 700, 900, 1000, 1100, 1200, 1800, 2400]
      : [400, 500, 600, 700, 900, 1000, 1100, 1200, 1800, 2400]
  const raw = Math.round(Math.max(1, Math.min(40, Number.isFinite(width) ? width : 4)) * 100)
  return steps.reduce((best, value) => Math.abs(value - raw) < Math.abs(best - raw) ? value : best, steps[0])
}

export const CARD_DEFAULTS: Omit<Card, 'id' | 'content' | 'sourceType' | 'createdAt' | 'tags'> = {
  x: 0,
  y: 0,
  width: null,
  height: null,
  color: '#ffffff',
  bgColor: '#ffffff',
  textColor: '#111111',
  sizePreset: 'default',
  zIndex: 0,
}

// The native plugin uses the same monochrome visual language as the whiteboard:
// white cards, dark text, and neutral connector geometry.
export const CONNECTION_DEFAULT_COLOR = '#111111'

export const DEFAULT_VIEWPORT: Viewport = { panX: 60, panY: 20, scale: 1 }

// ── Stroke point packing ──

export function packStrokePoints(points: StrokePoint[]): Uint8Array {
  const buffer = new ArrayBuffer(points.length * 12)
  const view = new DataView(buffer)
  for (let i = 0; i < points.length; i++) {
    const offset = i * 12
    view.setFloat32(offset, points[i].x, true)
    view.setFloat32(offset + 4, points[i].y, true)
    view.setFloat32(offset + 8, points[i].p, true)
  }
  return new Uint8Array(buffer)
}

export function unpackStrokePoints(data: Uint8Array): StrokePoint[] {
  const count = Math.floor(data.length / 12)
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength)
  const points: StrokePoint[] = new Array(count)
  for (let i = 0; i < count; i++) {
    const offset = i * 12
    points[i] = {
      x: view.getFloat32(offset, true),
      y: view.getFloat32(offset + 4, true),
      p: view.getFloat32(offset + 8, true),
    }
  }
  return points
}

export function computeStrokeBounds(points: StrokePoint[]): number[] {
  if (points.length === 0) return [0, 0, 0, 0]
  let minX = points[0].x, minY = points[0].y
  let maxX = minX, maxY = minY
  for (let i = 1; i < points.length; i++) {
    const p = points[i]
    if (p.x < minX) minX = p.x
    if (p.y < minY) minY = p.y
    if (p.x > maxX) maxX = p.x
    if (p.y > maxY) maxY = p.y
  }
  return [minX, minY, maxX, maxY]
}

// ── Minimal MessagePack codec (subset needed for BoardDoc) ──

class MsgPackWriter {
  private buf: Uint8Array
  private view: DataView
  private pos = 0

  constructor(capacity = 64 * 1024) {
    this.buf = new Uint8Array(capacity)
    this.view = new DataView(this.buf.buffer)
  }

  private ensure(n: number) {
    if (this.pos + n <= this.buf.length) return
    let cap = this.buf.length * 2
    while (cap < this.pos + n) cap *= 2
    const next = new Uint8Array(cap)
    next.set(this.buf)
    this.buf = next
    this.view = new DataView(this.buf.buffer)
  }

  bytes(): Uint8Array { return this.buf.subarray(0, this.pos) }

  writeNil() { this.ensure(1); this.buf[this.pos++] = 0xc0 }

  writeBool(v: boolean) { this.ensure(1); this.buf[this.pos++] = v ? 0xc3 : 0xc2 }

  writeInt(v: number) {
    if (v >= 0) {
      if (v < 128) { this.ensure(1); this.buf[this.pos++] = v }
      else if (v < 256) { this.ensure(2); this.buf[this.pos++] = 0xcc; this.buf[this.pos++] = v }
      else if (v < 65536) { this.ensure(3); this.buf[this.pos++] = 0xcd; this.view.setUint16(this.pos, v); this.pos += 2 }
      else { this.ensure(5); this.buf[this.pos++] = 0xce; this.view.setUint32(this.pos, v); this.pos += 4 }
    } else {
      if (v >= -32) { this.ensure(1); this.buf[this.pos++] = (v & 0xff) }
      else if (v >= -128) { this.ensure(2); this.buf[this.pos++] = 0xd0; this.view.setInt8(this.pos, v); this.pos += 1 }
      else if (v >= -32768) { this.ensure(3); this.buf[this.pos++] = 0xd1; this.view.setInt16(this.pos, v); this.pos += 2 }
      else { this.ensure(5); this.buf[this.pos++] = 0xd2; this.view.setInt32(this.pos, v); this.pos += 4 }
    }
  }

  writeFloat(v: number) {
    this.ensure(9); this.buf[this.pos++] = 0xcb; this.view.setFloat64(this.pos, v); this.pos += 8
  }

  writeNumber(v: number) {
    if (Number.isInteger(v) && v >= -2147483648 && v <= 4294967295) this.writeInt(v)
    else this.writeFloat(v)
  }

  writeStr(s: string) {
    const encoded = encodeUtf8(s)
    const len = encoded.length
    if (len < 32) { this.ensure(1 + len); this.buf[this.pos++] = 0xa0 | len }
    else if (len < 256) { this.ensure(2 + len); this.buf[this.pos++] = 0xd9; this.buf[this.pos++] = len }
    else if (len < 65536) { this.ensure(3 + len); this.buf[this.pos++] = 0xda; this.view.setUint16(this.pos, len); this.pos += 2 }
    else { this.ensure(5 + len); this.buf[this.pos++] = 0xdb; this.view.setUint32(this.pos, len); this.pos += 4 }
    this.buf.set(encoded, this.pos); this.pos += len
  }

  writeBin(data: Uint8Array) {
    const len = data.length
    if (len < 256) { this.ensure(2 + len); this.buf[this.pos++] = 0xc4; this.buf[this.pos++] = len }
    else if (len < 65536) { this.ensure(3 + len); this.buf[this.pos++] = 0xc5; this.view.setUint16(this.pos, len); this.pos += 2 }
    else { this.ensure(5 + len); this.buf[this.pos++] = 0xc6; this.view.setUint32(this.pos, len); this.pos += 4 }
    this.buf.set(data, this.pos); this.pos += len
  }

  writeArrayHeader(len: number) {
    if (len < 16) { this.ensure(1); this.buf[this.pos++] = 0x90 | len }
    else if (len < 65536) { this.ensure(3); this.buf[this.pos++] = 0xdc; this.view.setUint16(this.pos, len); this.pos += 2 }
    else { this.ensure(5); this.buf[this.pos++] = 0xdd; this.view.setUint32(this.pos, len); this.pos += 4 }
  }

  writeMapHeader(len: number) {
    if (len < 16) { this.ensure(1); this.buf[this.pos++] = 0x80 | len }
    else if (len < 65536) { this.ensure(3); this.buf[this.pos++] = 0xde; this.view.setUint16(this.pos, len); this.pos += 2 }
    else { this.ensure(5); this.buf[this.pos++] = 0xdf; this.view.setUint32(this.pos, len); this.pos += 4 }
  }

  writeAny(v: unknown): void {
    if (v === null || v === undefined) { this.writeNil(); return }
    if (typeof v === 'boolean') { this.writeBool(v); return }
    if (typeof v === 'number') { this.writeNumber(v); return }
    if (typeof v === 'string') { this.writeStr(v); return }
    if (v instanceof Uint8Array) { this.writeBin(v); return }
    if (Array.isArray(v)) {
      this.writeArrayHeader(v.length)
      for (const item of v) this.writeAny(item)
      return
    }
    if (typeof v === 'object') {
      const entries = Object.entries(v as Record<string, unknown>)
      this.writeMapHeader(entries.length)
      for (const [key, val] of entries) { this.writeStr(key); this.writeAny(val) }
    }
  }
}

class MsgPackReader {
  private view: DataView
  private bytes: Uint8Array
  private pos = 0

  constructor(data: Uint8Array) {
    this.bytes = data
    this.view = new DataView(data.buffer, data.byteOffset, data.byteLength)
  }

  read(): unknown {
    const b = this.bytes[this.pos++]
    // positive fixint
    if (b <= 0x7f) return b
    // fixmap
    if ((b & 0xf0) === 0x80) return this.readMap(b & 0x0f)
    // fixarray
    if ((b & 0xf0) === 0x90) return this.readArray(b & 0x0f)
    // fixstr
    if ((b & 0xe0) === 0xa0) return this.readStr(b & 0x1f)
    // negative fixint
    if (b >= 0xe0) return b - 256
    switch (b) {
      case 0xc0: return null
      case 0xc2: return false
      case 0xc3: return true
      case 0xc4: { const len = this.bytes[this.pos++]; return this.readBin(len) }
      case 0xc5: { const len = this.view.getUint16(this.pos); this.pos += 2; return this.readBin(len) }
      case 0xc6: { const len = this.view.getUint32(this.pos); this.pos += 4; return this.readBin(len) }
      case 0xca: { const v = this.view.getFloat32(this.pos); this.pos += 4; return v }
      case 0xcb: { const v = this.view.getFloat64(this.pos); this.pos += 8; return v }
      case 0xcc: return this.bytes[this.pos++]
      case 0xcd: { const v = this.view.getUint16(this.pos); this.pos += 2; return v }
      case 0xce: { const v = this.view.getUint32(this.pos); this.pos += 4; return v }
      case 0xd0: { const v = this.view.getInt8(this.pos); this.pos += 1; return v }
      case 0xd1: { const v = this.view.getInt16(this.pos); this.pos += 2; return v }
      case 0xd2: { const v = this.view.getInt32(this.pos); this.pos += 4; return v }
      case 0xd9: { const len = this.bytes[this.pos++]; return this.readStr(len) }
      case 0xda: { const len = this.view.getUint16(this.pos); this.pos += 2; return this.readStr(len) }
      case 0xdb: { const len = this.view.getUint32(this.pos); this.pos += 4; return this.readStr(len) }
      case 0xdc: { const len = this.view.getUint16(this.pos); this.pos += 2; return this.readArray(len) }
      case 0xdd: { const len = this.view.getUint32(this.pos); this.pos += 4; return this.readArray(len) }
      case 0xde: { const len = this.view.getUint16(this.pos); this.pos += 2; return this.readMap(len) }
      case 0xdf: { const len = this.view.getUint32(this.pos); this.pos += 4; return this.readMap(len) }
      default: throw new Error(`Unknown msgpack byte: 0x${b.toString(16)}`)
    }
  }

  private readStr(len: number): string {
    const slice = this.bytes.subarray(this.pos, this.pos + len)
    this.pos += len
    return decodeUtf8(slice)
  }

  private readBin(len: number): Uint8Array {
    const slice = this.bytes.slice(this.pos, this.pos + len)
    this.pos += len
    return slice
  }

  private readArray(len: number): unknown[] {
    const arr: unknown[] = new Array(len)
    for (let i = 0; i < len; i++) arr[i] = this.read()
    return arr
  }

  private readMap(len: number): Record<string, unknown> {
    const obj: Record<string, unknown> = {}
    for (let i = 0; i < len; i++) {
      const key = this.read() as string
      obj[key] = this.read()
    }
    return obj
  }
}

// ── Container encode/decode ──

const MAGIC = new Uint8Array([0x4d, 0x4f, 0x53, 0x42, 0x52, 0x44]) // "MOSBRD"
const FORMAT_VERSION = 1

function encodeCard(card: Card): Record<string, unknown> {
  const obj: Record<string, unknown> = { id: card.id }
  if (card.content) obj.content = card.content
  obj.x = card.x
  obj.y = card.y
  if (card.width !== null) obj.width = card.width
  if (card.height !== null) obj.height = card.height
  if (card.color !== null) obj.color = card.color
  if (card.bgColor !== null) obj.bgColor = card.bgColor
  if (card.textColor !== null) obj.textColor = card.textColor
  if (card.sizePreset !== 'default') obj.sizePreset = card.sizePreset
  if (card.tags && card.tags.length > 0) obj.tags = card.tags
  if (card.zIndex !== 0) obj.zIndex = card.zIndex
  obj.sourceType = card.sourceType
  if (card.sourceLabel !== undefined) obj.sourceLabel = card.sourceLabel
  obj.createdAt = card.createdAt
  if (card.kind && card.kind !== 'text') obj.kind = card.kind
  if (card.imageRef) obj.imageRef = card.imageRef
  if (card.noteRef) obj.noteRef = card.noteRef
  if (card.title) obj.title = card.title
  if (card.conversation) obj.conversation = card.conversation
  if (card.role) obj.role = card.role
  if (card.parentId) obj.parentId = card.parentId
  if (card.childIds && card.childIds.length > 0) obj.childIds = card.childIds
  if (card.extractedRanges && card.extractedRanges.length > 0) obj.extractedRanges = card.extractedRanges
  if (card.ext) obj.ext = card.ext
  return obj
}

function decodeCard(obj: Record<string, unknown>): Card {
  return {
    ...CARD_DEFAULTS,
    id: obj.id as string,
    content: (obj.content as string) ?? '',
    x: (obj.x as number) ?? 0,
    y: (obj.y as number) ?? 0,
    width: (obj.width as number | null) ?? null,
    height: (obj.height as number | null) ?? null,
    color: (obj.color as string | null) ?? null,
    bgColor: (obj.bgColor as string | null) ?? null,
    textColor: (obj.textColor as string | null) ?? null,
    sizePreset: (obj.sizePreset as Card['sizePreset']) ?? 'default',
    tags: (obj.tags as string[]) ?? [],
    zIndex: (obj.zIndex as number) ?? 0,
    sourceType: (obj.sourceType as Card['sourceType']) ?? 'manual',
    sourceLabel: obj.sourceLabel as string | undefined,
    createdAt: (obj.createdAt as string) ?? new Date().toISOString(),
    kind: obj.kind as Card['kind'],
    imageRef: obj.imageRef as string | undefined,
    noteRef: obj.noteRef as string | undefined,
    title: obj.title as string | undefined,
    conversation: obj.conversation as Card['conversation'],
    role: obj.role as Card['role'],
    parentId: obj.parentId as string | undefined,
    childIds: obj.childIds as string[] | undefined,
    extractedRanges: obj.extractedRanges as Card['extractedRanges'],
    ext: obj.ext as Record<string, unknown> | undefined,
  }
}

function encodeConnection(conn: Connection): Record<string, unknown> {
  const obj: Record<string, unknown> = {
    id: conn.id,
    fromCardId: conn.fromCardId,
    toCardId: conn.toCardId,
  }
  if (conn.color !== CONNECTION_DEFAULT_COLOR) obj.color = conn.color
  if (conn.label) obj.label = conn.label
  if (conn.locked) obj.locked = true
  if (conn.kind && conn.kind !== 'default') obj.kind = conn.kind
  if (conn.fromAnchor) obj.fromAnchor = conn.fromAnchor
  if (conn.toAnchor) obj.toAnchor = conn.toAnchor
  if (conn.ext) obj.ext = conn.ext
  return obj
}

function decodeConnection(obj: Record<string, unknown>): Connection {
  return {
    id: obj.id as string,
    fromCardId: obj.fromCardId as string,
    toCardId: obj.toCardId as string,
    color: (obj.color as string) ?? CONNECTION_DEFAULT_COLOR,
    label: (obj.label as string) ?? '',
    locked: obj.locked === true,
    kind: obj.kind as Connection['kind'],
    fromAnchor: obj.fromAnchor as Connection['fromAnchor'],
    toAnchor: obj.toAnchor as Connection['toAnchor'],
    ext: obj.ext as Record<string, unknown> | undefined,
  }
}

function encodeStroke(stroke: InkStroke): Record<string, unknown> {
  const obj: Record<string, unknown> = {
    id: stroke.id,
    space: stroke.space,
    width: stroke.width,
    color: stroke.color,
    encoding: STROKE_ENCODING_F32X3,
    points: stroke.points,
    bounds: stroke.bounds,
  }
  obj.pen = stroke.pen
  obj.drawPathWidth = stroke.drawPathWidth !== undefined && stroke.drawPathWidth > 0
    ? stroke.drawPathWidth
    : canonicalDrawPathWidth(stroke.pen, stroke.width)
  obj.sampleScale = stroke.sampleScale
  if (stroke.createdAt !== undefined) obj.createdAt = stroke.createdAt
  return obj
}

function decodeStroke(obj: Record<string, unknown>): InkStroke {
  const pen = normalizeDrawPathPenType(obj.pen as number)
  if (pen === null || typeof obj.sampleScale !== 'number' || !(obj.sampleScale > 0)) {
    throw new Error('Unsupported stroke schema: pen and sampleScale are required')
  }
  return {
    id: obj.id as string,
    space: obj.space as string,
    pen,
    drawPathWidth: typeof obj.drawPathWidth === 'number' && obj.drawPathWidth > 0
      ? obj.drawPathWidth
      : canonicalDrawPathWidth(pen, obj.width as number),
    sampleScale: obj.sampleScale as number,
    width: obj.width as number,
    color: obj.color as number,
    encoding: obj.encoding as number | undefined,
    points: obj.points as Uint8Array,
    bounds: obj.bounds as number[],
    createdAt: obj.createdAt as string | undefined,
  }
}

function normalizeDrawPathPenType(value: number): number | null {
  if (!Number.isInteger(value)) return null
  switch (value) {
    case 0: return 16
    case 10: return 15
    case 14: return 15
    case 17: return 11
    case 11:
    case 15:
    case 16:
    case 18: return value
    default: return null
  }
}

export function encodeBoard(doc: BoardDoc): Uint8Array {
  const writer = new MsgPackWriter()
  const payload: Record<string, unknown> = {
    v: doc.v,
    meta: {
      id: doc.meta.id,
      name: doc.meta.name,
      createdAt: doc.meta.createdAt,
      updatedAt: doc.meta.updatedAt,
      viewport: doc.meta.viewport,
      ...(doc.meta.ext ? { ext: doc.meta.ext } : {}),
    },
    cards: doc.cards.map(encodeCard),
    connections: doc.connections.map(encodeConnection),
    ink: doc.ink.map(encodeStroke),
  }
  const blobEntries = Object.entries(doc.blobs)
  if (blobEntries.length > 0) payload.blobs = doc.blobs
  if (doc.ext) payload.ext = doc.ext
  writer.writeAny(payload)
  const msgpackBytes = writer.bytes()

  const checksum = crc32(msgpackBytes)

  // Container: magic(6) + version(1) + flags(1) + payloadLen(4) + payload + crc(4)
  const container = new Uint8Array(6 + 1 + 1 + 4 + msgpackBytes.length + 4)
  const containerView = new DataView(container.buffer)
  container.set(MAGIC, 0)
  container[6] = FORMAT_VERSION
  container[7] = 0 // flags
  containerView.setUint32(8, msgpackBytes.length, true)
  container.set(msgpackBytes, 12)
  containerView.setUint32(12 + msgpackBytes.length, checksum, true)

  return container
}

export function decodeBoard(data: Uint8Array): BoardDoc {
  // Check magic
  for (let i = 0; i < MAGIC.length; i++) {
    if (data[i] !== MAGIC[i]) throw new Error('Invalid board magic')
  }
  const version = data[6]
  if (version > FORMAT_VERSION) {
    // Forward compat: try to decode anyway
  }
  // const flags = data[7]
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength)
  const payloadLen = view.getUint32(8, true)
  const payload = data.subarray(12, 12 + payloadLen)
  const storedCrc = view.getUint32(12 + payloadLen, true)
  const computedCrc = crc32(payload)
  if (storedCrc !== computedCrc) throw new Error('CRC mismatch')

  const reader = new MsgPackReader(payload)
  const raw = reader.read() as Record<string, unknown>

  const metaRaw = (raw.meta ?? {}) as Record<string, unknown>
  const viewportRaw = (metaRaw.viewport ?? DEFAULT_VIEWPORT) as Viewport

  // Collect unknown top-level keys into ext. `whiteboards` (the retired anchor regions) is
  // listed as known so old files drop it instead of carrying it along in ext.
  const knownKeys = new Set(['v', 'meta', 'cards', 'connections', 'ink', 'whiteboards', 'blobs', 'ext'])
  const unknownExt: Record<string, unknown> = {}
  for (const key of Object.keys(raw)) {
    if (!knownKeys.has(key)) unknownExt[key] = raw[key]
  }
  const ext = { ...(raw.ext as Record<string, unknown> | undefined), ...unknownExt }

  return {
    v: (raw.v as number) ?? 1,
    meta: {
      id: (metaRaw.id as string) ?? '',
      name: (metaRaw.name as string) ?? '',
      createdAt: (metaRaw.createdAt as string) ?? '',
      updatedAt: (metaRaw.updatedAt as string) ?? '',
      viewport: viewportRaw,
      ext: metaRaw.ext as Record<string, unknown> | undefined,
    },
    cards: ((raw.cards ?? []) as Record<string, unknown>[]).map(decodeCard),
    connections: ((raw.connections ?? []) as Record<string, unknown>[]).map(decodeConnection),
    ink: ((raw.ink ?? []) as Record<string, unknown>[]).map(decodeStroke),
    blobs: (raw.blobs ?? {}) as Record<string, Uint8Array>,
    ext: Object.keys(ext).length > 0 ? ext : undefined,
  }
}

// ── CanvasData ↔ BoardDoc interop ──

export function canvasDataToBoard(
  data: CanvasData,
  meta: {
    id: string
    name: string
    createdAt: string
    updatedAt: string
    viewport: Viewport
    ext?: Record<string, unknown>
  },
  extras?: {
    ink?: InkStroke[]
    blobs?: Record<string, Uint8Array>
    ext?: Record<string, unknown>
    metaExt?: Record<string, unknown>
  },
): BoardDoc {
  return {
    v: 1,
    meta: {
      ...meta,
      ext: extras?.metaExt ?? meta.ext,
    },
    cards: Object.values(data.cards),
    connections: data.connections,
    ink: extras?.ink ?? [],
    blobs: extras?.blobs ?? {},
    ext: extras?.ext,
  }
}

export function boardToCanvasData(doc: BoardDoc): CanvasData {
  const cards: Record<string, Card> = {}
  for (const card of doc.cards) cards[card.id] = card
  return {
    cards,
    connections: doc.connections,
    viewport: doc.meta.viewport,
  }
}

export function createEmptyBoardMeta(overrides?: Partial<BoardDocMeta>): BoardDocMeta {
  const now = new Date().toISOString()
  return {
    id: '',
    name: '',
    createdAt: now,
    updatedAt: now,
    viewport: DEFAULT_VIEWPORT,
    ...overrides,
  }
}
