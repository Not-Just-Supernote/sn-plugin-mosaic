// 编解码验证：crc32 标准向量、round-trip 深比较、CRC 检错、笔迹打包、ext 前向兼容
// 运行：npx tsx scripts/verify-board-codec.ts（或 server/node_modules/.bin/tsx）
import assert from 'node:assert/strict'
import { crc32 } from '../src/mosaic/crc32'
import { bytesToBase64, base64ToBytes } from '../src/mosaic/base64'
import {
  encodeBoard, decodeBoard, canvasDataToBoard, boardToCanvasData,
  packStrokePoints, unpackStrokePoints, computeStrokeBounds,
  BOARD_STORAGE_B64_PREFIX,
} from '../src/mosaic/boardFormat'
import type { CanvasData, Card } from '../src/types'

// 1. CRC32 标准向量 "123456789" → 0xCBF43926
assert.equal(crc32(new TextEncoder().encode('123456789')), 0xcbf43926, 'crc32 标准向量')

// 2. base64 往返 + 魔数前缀
const sample = new TextEncoder().encode('MOSBRD 白板白板 ✓')
assert.deepEqual(base64ToBytes(bytesToBase64(sample)), sample, 'base64 往返')
assert.equal(bytesToBase64(new TextEncoder().encode('MOSBRD')), BOARD_STORAGE_B64_PREFIX, '魔数前缀')

// 3. 全字段卡片 round-trip
const card: Card = {
  id: 'card-abc123',
  content: '# 标题\n正文 **加粗**',
  x: 120.5, y: -40.25,
  width: 456, height: null,
  color: '#ff0000', bgColor: null, textColor: '#111111',
  sizePreset: 'wide',
  tags: ['摘录', ' LiquidText '],
  zIndex: 7,
  sourceType: 'reddit-comment',
  sourceLabel: 'r/test',
  createdAt: '2026-08-01T00:00:00.000Z',
  kind: 'image',
  ext: { futureField: { nested: [1, 2, 3] } },
}
const plain: Card = {
  id: 'card-plain', content: '', x: 0, y: 0, width: null, height: null,
  color: null, bgColor: null, textColor: null, sizePreset: 'default',
  tags: [], zIndex: 0, sourceType: 'manual', createdAt: '2026-08-01T01:00:00.000Z',
}
const data: CanvasData = {
  cards: { [card.id]: card, [plain.id]: plain },
  connections: [{ id: 'conn-1', fromCardId: card.id, toCardId: plain.id, color: '#6BA5E7', label: '' }],
  viewport: { panX: -320.5, panY: 88, scale: 1.4 },
}
const meta = {
  id: 'canvas-x', name: '测试板', createdAt: '2026-08-01T00:00:00.000Z',
  updatedAt: '2026-08-01T02:00:00.000Z', viewport: data.viewport,
}
const doc = canvasDataToBoard(data, meta, { ext: { topLevelFuture: 'yes' } })
const bytes = encodeBoard(doc)
const doc2 = decodeBoard(bytes)
const data2 = boardToCanvasData(doc2)
assert.deepEqual(data2, data, 'CanvasData round-trip')
assert.equal(doc2.meta.name, '测试板', 'meta 往返')
assert.deepEqual(doc2.ext, { topLevelFuture: 'yes' }, '顶层 ext 往返')

// 4. ext 原位恢复：编码后的未知键应在再次编码时回到原位（顶层/卡片级）
const recoded = decodeBoard(encodeBoard(doc2))
assert.deepEqual(recoded.ext, { topLevelFuture: 'yes' }, 'ext 二次编码保持')
assert.deepEqual(recoded.cards.find(c => c.id === card.id)?.ext, card.ext, '卡片 ext 二次编码保持')

// 5. CRC 检错：翻转一字节必须抛错
const corrupted = bytes.slice()
corrupted[corrupted.length - 6] ^= 0xff
assert.throws(() => decodeBoard(corrupted), /CRC/, 'CRC 检错')
const truncated = bytes.slice(0, bytes.length - 10)
assert.throws(() => decodeBoard(truncated), /长度|过短/, '截断检错')

// 6. 笔迹打包（f32 可表示值，避免精度噪声）
const pts = [
  { x: 0.5, y: -12.25, p: 0.75 },
  { x: 1024.5, y: 3.5, p: 1 },
  { x: -0.125, y: 88.75, p: 0.25 },
]
const packed = packStrokePoints(pts)
assert.equal(packed.length, 36, '打包长度')
assert.deepEqual(unpackStrokePoints(packed), pts, '笔迹点往返')
assert.deepEqual(computeStrokeBounds(pts), [-0.125, -12.25, 1024.5, 88.75], '笔迹 bounds')

// 7. 含笔迹与 blob 的文档 round-trip（v1 预留通道已可完整往返）
const inkDoc = canvasDataToBoard(data, meta, {
  ink: [{
    id: 'stroke-1', space: 'card:card-abc123', pen: 0, sampleScale: 1, color: 0xff000000, width: 2.5,
    bounds: computeStrokeBounds(pts), encoding: 0, points: packed, createdAt: '2026-08-01T03:00:00.000Z',
  }],
  blobs: { 'img-1': new Uint8Array([1, 2, 3, 250]) },
})
const inkDoc2 = decodeBoard(encodeBoard(inkDoc))
assert.equal(inkDoc2.ink.length, 1, 'ink 数量')
assert.deepEqual(inkDoc2.ink[0].points, packed, 'ink points 往返')
assert.equal(inkDoc2.ink[0].space, 'card:card-abc123', 'ink space')
assert.deepEqual(Array.from(inkDoc2.blobs['img-1']), [1, 2, 3, 250], 'blob 往返')

// 8. 稀疏编码收益：默认卡片不应写出可省略字段（体积检查，非严格断言）
const sparseBytes = encodeBoard(canvasDataToBoard({ cards: { p: plain }, connections: [], viewport: meta.viewport }, meta))
const jsonText = JSON.stringify({ cards: { p: plain }, connections: [], viewport: meta.viewport })
console.log(`体积对比：容器 ${sparseBytes.length}B vs JSON ${jsonText.length}B`)
assert.ok(sparseBytes.length < jsonText.length, '容器应小于等价 JSON')

console.log('✓ 全部通过（8 组断言）')
