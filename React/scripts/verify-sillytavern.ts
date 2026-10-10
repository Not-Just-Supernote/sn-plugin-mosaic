import assert from 'node:assert/strict'
import { buildSillyTavernBoard, collectDifferenceHunks, diffSequence } from '../src/sillytavern/boardAdapter.ts'
import { canvasDataToBoard, decodeBoard, encodeBoard } from '../src/mosaic/boardFormat.ts'

function jsonl(header: Record<string, unknown>, messages: Record<string, unknown>[]): string {
  return [header, ...messages].map(value => JSON.stringify(value)).join('\n')
}

const edits = diffSequence(
  ['a', 'b', 'c', 'd', 'e', 'f'],
  ['a', 'B', 'c', 'd', 'E', 'f'],
  (a, b) => a === b,
)
const hunks = collectDifferenceHunks(edits)
assert.deepEqual(hunks.map(hunk => [hunk.baseStart, hunk.baseEnd, hunk.variantStart, hunk.variantEnd]), [
  [1, 2, 1, 2],
  [4, 5, 4, 5],
])

const insertDeleteHunks = collectDifferenceHunks(diffSequence(
  ['a', 'b', 'c', 'd'],
  ['a', 'x', 'b', 'd'],
  (a, b) => a === b,
))
assert.deepEqual(insertDeleteHunks.map(hunk => [
  hunk.baseStart,
  hunk.baseEnd,
  hunk.variantStart,
  hunk.variantEnd,
]), [
  [1, 1, 1, 2],
  [2, 3, 3, 3],
])

const main = jsonl(
  { chat_metadata: { title: 'Main' }, user_name: 'unused', character_name: 'unused' },
  [
    { name: 'User', is_user: true, mes: 'A\n\nB\n\nC\n\nD\n\nE\n\nF', send_date: '2026-08-06T00:00:00Z' },
    {
      name: 'Assistant',
      is_user: false,
      mes: 'Selected answer',
      swipes: ['Selected answer', 'Alternate answer'],
      swipe_id: 0,
      swipe_info: [{ extra: { model: 'model-a' } }, { extra: { model: 'model-b' } }],
    },
  ],
)
const branch = jsonl(
  { chat_metadata: { main_chat: 'main' }, user_name: 'unused', character_name: 'unused' },
  [
    { name: 'User', is_user: true, mes: 'A\n\nB changed\n\nC\n\nD\n\nE changed\n\nF', send_date: '2026-08-06T00:00:00Z' },
    { name: 'Assistant', is_user: false, mes: 'Selected answer' },
  ],
)

const result = buildSillyTavernBoard([
  { name: 'main.jsonl', text: main },
  { name: 'main - Branch #1.jsonl', text: branch },
])

assert.equal(result.stats.files, 2)
assert.equal(result.stats.backbones, 1)
assert.equal(result.stats.differenceHunks, 3) // B、E 与一个 swipe
assert.equal(result.stats.swipeBranches, 1)
assert.equal(result.stats.branchCards, 3)

const root = Object.values(result.cards).find(card => card.conversation?.segmentKind === 'backbone')
assert(root?.conversation)
assert.equal(root.conversation.blocks.length, 7)
assert.equal(root.conversation.blocks[0].sourceFiles?.length, 2)
assert(root.conversation.replacedBlockIds?.length)

const branchCards = Object.values(result.cards).filter(card => card.conversation?.segmentKind === 'branch')
assert(branchCards.some(card => card.conversation?.blocks.some(block => block.text === 'B changed')))
assert(branchCards.some(card => card.conversation?.blocks.some(block => block.text === 'E changed')))
assert(branchCards.some(card => card.conversation?.blocks.some(block => block.text === 'Alternate answer')))

const doc = canvasDataToBoard({
  cards: result.cards,
  connections: result.connections,
  viewport: { panX: 60, panY: 20, scale: 1 },
}, {
  id: 'test',
  name: 'test',
  createdAt: '2026-08-06T00:00:00Z',
  updatedAt: '2026-08-06T00:00:00Z',
  viewport: { panX: 60, panY: 20, scale: 1 },
})
const decoded = decodeBoard(encodeBoard(doc))
assert.equal(decoded.cards.length, Object.keys(result.cards).length)
assert(decoded.cards.some(card => card.kind === 'conversation' && card.conversation?.blocks.length))
assert(decoded.connections.some(connection => connection.kind === 'conversation-branch' && connection.fromAnchor?.blockId))

console.log(JSON.stringify({ ok: true, stats: result.stats, connections: result.connections.length }))
