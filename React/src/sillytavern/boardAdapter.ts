import type {
  Card,
  CardAnchor,
  Connection,
  ConversationBlock,
  ConversationDiffSpan,
} from '../types'
import { CARD_DEFAULTS } from '../boardFormat'
import { generateId } from '../id'
import { resolveCardSize } from '../cardGeometry'
import {
  CONVERSATION_BRANCH_WIDTH,
  CONVERSATION_CARD_WIDTH,
  estimateConversationCardHeight,
  resolveCardAnchorPoint,
} from './conversationGeometry'
import {
  conversationBlockFingerprint,
  normalizeChatFileName,
  normalizeConversationText,
  parseSillyTavernFiles,
  splitConversationParagraphs,
  type ParsedSillyTavernFile,
  type SillyTavernInputFile,
} from './parser'

type EditKind = 'equal' | 'delete' | 'insert'

interface SequenceEdit<T> {
  kind: EditKind
  value: T
  baseIndex?: number
  variantIndex?: number
}

interface DifferenceHunk<T> {
  baseStart: number
  baseEnd: number
  variantStart: number
  variantEnd: number
  baseValues: T[]
  variantValues: T[]
}

interface BlockLocation {
  cardId: string
  blockId: string
}

export interface SillyTavernBoardBuildOptions {
  originX?: number
  originY?: number
  existingCards?: Card[]
}

export interface SillyTavernBoardBuildResult {
  cards: Record<string, Card>
  connections: Connection[]
  warnings: string[]
  stats: {
    files: number
    backbones: number
    branchCards: number
    differenceHunks: number
    swipeBranches: number
  }
}

function mapValue(map: Map<number, number>, key: number, fallback = Number.NEGATIVE_INFINITY): number {
  return map.has(key) ? map.get(key)! : fallback
}

/** Myers 最短编辑脚本；对长对话保持 O((N+M)D) 时间与 O((N+M)D) 回溯空间。 */
export function diffSequence<T>(base: T[], variant: T[], equal: (a: T, b: T) => boolean): SequenceEdit<T>[] {
  const n = base.length
  const m = variant.length
  const max = n + m
  let frontier = new Map<number, number>([[1, 0]])
  const trace: Map<number, number>[] = []

  for (let distance = 0; distance <= max; distance++) {
    trace.push(new Map(frontier))
    const next = new Map(frontier)
    for (let diagonal = -distance; diagonal <= distance; diagonal += 2) {
      const down = diagonal === -distance || (
        diagonal !== distance && mapValue(frontier, diagonal - 1) < mapValue(frontier, diagonal + 1)
      )
      let x = down ? mapValue(frontier, diagonal + 1, 0) : mapValue(frontier, diagonal - 1, 0) + 1
      let y = x - diagonal
      while (x < n && y < m && equal(base[x], variant[y])) {
        x++
        y++
      }
      next.set(diagonal, x)
      if (x >= n && y >= m) return backtrackDiff(base, variant, trace, distance)
    }
    frontier = next
  }
  return []
}

function backtrackDiff<T>(base: T[], variant: T[], trace: Map<number, number>[], finalDistance: number): SequenceEdit<T>[] {
  const reversed: SequenceEdit<T>[] = []
  let x = base.length
  let y = variant.length

  for (let distance = finalDistance; distance >= 0; distance--) {
    const frontier = trace[distance]
    const diagonal = x - y
    const down = diagonal === -distance || (
      diagonal !== distance && mapValue(frontier, diagonal - 1) < mapValue(frontier, diagonal + 1)
    )
    const previousDiagonal = down ? diagonal + 1 : diagonal - 1
    const previousX = mapValue(frontier, previousDiagonal, 0)
    const previousY = previousX - previousDiagonal

    while (x > previousX && y > previousY) {
      reversed.push({ kind: 'equal', value: base[x - 1], baseIndex: x - 1, variantIndex: y - 1 })
      x--
      y--
    }
    if (distance === 0) break
    if (x === previousX) {
      reversed.push({ kind: 'insert', value: variant[y - 1], variantIndex: y - 1 })
      y--
    } else {
      reversed.push({ kind: 'delete', value: base[x - 1], baseIndex: x - 1 })
      x--
    }
  }
  return reversed.reverse()
}

export function collectDifferenceHunks<T>(edits: SequenceEdit<T>[]): DifferenceHunk<T>[] {
  const hunks: DifferenceHunk<T>[] = []
  let baseCursor = 0
  let variantCursor = 0
  let current: DifferenceHunk<T> | null = null

  const flush = () => {
    if (current) hunks.push(current)
    current = null
  }

  for (const edit of edits) {
    if (edit.kind === 'equal') {
      flush()
      baseCursor++
      variantCursor++
      continue
    }
    if (!current) {
      current = {
        baseStart: baseCursor,
        baseEnd: baseCursor,
        variantStart: variantCursor,
        variantEnd: variantCursor,
        baseValues: [],
        variantValues: [],
      }
    }
    if (edit.kind === 'delete') {
      current.baseValues.push(edit.value)
      baseCursor++
      current.baseEnd = baseCursor
    } else {
      current.variantValues.push(edit.value)
      variantCursor++
      current.variantEnd = variantCursor
    }
  }
  flush()
  return hunks
}

function inlineDiff(base: string, variant: string): ConversationDiffSpan[] {
  // 超长段落采用公共前后缀，避免完全改写时产生巨量 Myers 回溯状态。
  if (base.length + variant.length > 6000) {
    let prefix = 0
    const maxPrefix = Math.min(base.length, variant.length)
    while (prefix < maxPrefix && base[prefix] === variant[prefix]) prefix++
    let suffix = 0
    while (
      suffix < base.length - prefix && suffix < variant.length - prefix &&
      base[base.length - 1 - suffix] === variant[variant.length - 1 - suffix]
    ) suffix++
    const spans: ConversationDiffSpan[] = []
    if (prefix > 0) spans.push({ text: variant.slice(0, prefix), kind: 'same' })
    const removed = base.slice(prefix, base.length - suffix)
    const added = variant.slice(prefix, variant.length - suffix)
    if (removed) spans.push({ text: removed, kind: 'removed' })
    if (added) spans.push({ text: added, kind: 'added' })
    if (suffix > 0) spans.push({ text: variant.slice(variant.length - suffix), kind: 'same' })
    return spans
  }
  const edits = diffSequence(Array.from(base), Array.from(variant), (a, b) => a === b)
  const spans: ConversationDiffSpan[] = []
  for (const edit of edits) {
    const kind: ConversationDiffSpan['kind'] = edit.kind === 'equal'
      ? 'same'
      : edit.kind === 'insert' ? 'added' : 'removed'
    const last = spans[spans.length - 1]
    if (last?.kind === kind) last.text += edit.value
    else spans.push({ text: edit.value, kind })
  }
  return spans
}

function blockMarkdown(blocks: ConversationBlock[]): string {
  return blocks.map(block => {
    const label = block.role === 'user' ? 'User' : block.role === 'assistant' ? block.name || 'Assistant' : 'System'
    return `### ${label}\n\n${block.text}`
  }).join('\n\n---\n\n')
}

function uniquePush(target: string[] | undefined, value: string): string[] {
  const next = target ? [...target] : []
  if (!next.includes(value)) next.push(value)
  return next
}

function addSourceToBlock(block: ConversationBlock, sourceFile: string) {
  block.sourceFiles = uniquePush(block.sourceFiles, sourceFile)
}

function addBranchAnchor(card: Card, anchor: NonNullable<NonNullable<Card['conversation']>['branchAnchors']>[number]) {
  if (!card.conversation) return
  const anchors = card.conversation.branchAnchors ?? []
  if (!anchors.some(item => item.branchCardId === anchor.branchCardId)) anchors.push(anchor)
  card.conversation.branchAnchors = anchors
}

function addReplacedBlock(card: Card, blockId: string) {
  if (!card.conversation) return
  card.conversation.replacedBlockIds = uniquePush(card.conversation.replacedBlockIds, blockId)
}

function createConversationCard(
  id: string,
  blocks: ConversationBlock[],
  segmentKind: 'backbone' | 'branch',
  sourceFiles: string[],
  branchPath: string[],
  x: number,
  y: number,
  parentCardId?: string,
): Card {
  const width = segmentKind === 'backbone' ? CONVERSATION_CARD_WIDTH : CONVERSATION_BRANCH_WIDTH
  return {
    ...CARD_DEFAULTS,
    id,
    content: blockMarkdown(blocks),
    x,
    y,
    width,
    height: estimateConversationCardHeight(blocks, width),
    color: segmentKind === 'backbone' ? '#6BA5E7' : '#F59E0B',
    bgColor: '#FFFFFF',
    tags: segmentKind === 'backbone' ? ['SillyTavern', '公共骨架'] : ['SillyTavern', '差异分支'],
    zIndex: segmentKind === 'backbone' ? 1 : 2,
    sourceType: 'sillytavern',
    sourceLabel: sourceFiles.join(' · '),
    createdAt: new Date().toISOString(),
    kind: 'conversation',
    conversation: {
      format: 'sillytavern',
      segmentKind,
      segmentId: id,
      sourceFiles: [...sourceFiles],
      blocks,
      branchPath,
      parentCardId,
    },
  }
}

function cardsOverlap(a: Card, b: Card, padding = 64): boolean {
  const as = resolveCardSize(a)
  const bs = resolveCardSize(b)
  return !(
    a.x + as.width + padding <= b.x || b.x + bs.width + padding <= a.x ||
    a.y + as.height + padding <= b.y || b.y + bs.height + padding <= a.y
  )
}

function placeBranchCard(card: Card, sourceCard: Card, sourceAnchor: CardAnchor, occupied: Card[]) {
  const sourceSize = resolveCardSize(sourceCard)
  const anchor = resolveCardAnchorPoint(sourceCard, sourceAnchor)
  card.x = sourceCard.x + sourceSize.width + 120
  card.y = Math.max(20, anchor.y - 28)
  let lane = 0
  while (occupied.some(other => cardsOverlap(card, other)) && lane < 80) {
    lane++
    card.x = sourceCard.x + sourceSize.width + 120 + lane * (CONVERSATION_BRANCH_WIDTH + 120)
  }
}

function bestImplicitParent(file: ParsedSillyTavernFile, candidates: ParsedSillyTavernFile[]): ParsedSillyTavernFile | undefined {
  if (!file.parentName) return undefined
  let best: { file: ParsedSillyTavernFile; prefix: number } | undefined
  for (const candidate of candidates) {
    if (candidate === file) continue
    const max = Math.min(candidate.blocks.length, file.blocks.length)
    let prefix = 0
    while (prefix < max && conversationBlockFingerprint(candidate.blocks[prefix]) === conversationBlockFingerprint(file.blocks[prefix])) prefix++
    if (prefix > 0 && (!best || prefix > best.prefix)) best = { file: candidate, prefix }
  }
  return best?.file
}

function resolveParentFiles(files: ParsedSillyTavernFile[]): Map<string, ParsedSillyTavernFile> {
  const byName = new Map<string, ParsedSillyTavernFile>()
  for (const file of files) {
    byName.set(normalizeChatFileName(file.name), file)
    byName.set(normalizeChatFileName(file.baseName), file)
  }
  const parents = new Map<string, ParsedSillyTavernFile>()
  for (const file of files) {
    const explicit = file.parentName ? byName.get(normalizeChatFileName(file.parentName)) : undefined
    const parent = explicit ?? bestImplicitParent(file, files)
    if (parent && parent !== file) parents.set(file.id, parent)
  }
  return parents
}

function orderFiles(files: ParsedSillyTavernFile[], parents: Map<string, ParsedSillyTavernFile>): ParsedSillyTavernFile[] {
  const result: ParsedSillyTavernFile[] = []
  const visiting = new Set<string>()
  const visited = new Set<string>()
  const visit = (file: ParsedSillyTavernFile) => {
    if (visited.has(file.id)) return
    if (visiting.has(file.id)) {
      parents.delete(file.id)
      return
    }
    visiting.add(file.id)
    const parent = parents.get(file.id)
    if (parent) visit(parent)
    visiting.delete(file.id)
    visited.add(file.id)
    result.push(file)
  }
  files.forEach(visit)
  return result
}

function anchorsForHunk(
  baseLength: number,
  hunk: DifferenceHunk<ConversationBlock>,
  locations: Array<BlockLocation | undefined>,
): { start?: BlockLocation; startBoundary: CardAnchor['boundary']; end?: BlockLocation; endBoundary: CardAnchor['boundary']; rejoins: boolean } {
  const startAtEnd = hunk.baseStart >= baseLength
  const start = startAtEnd ? locations[baseLength - 1] : locations[hunk.baseStart]
  const end = hunk.baseEnd < baseLength ? locations[hunk.baseEnd] : locations[baseLength - 1]
  return {
    start,
    startBoundary: startAtEnd ? 'bottom' : 'top',
    end,
    endBoundary: hunk.baseEnd < baseLength ? 'top' : 'bottom',
    rejoins: hunk.baseEnd < baseLength,
  }
}

export function buildSillyTavernBoard(
  inputs: SillyTavernInputFile[],
  options: SillyTavernBoardBuildOptions = {},
): SillyTavernBoardBuildResult {
  const parsedFiles = parseSillyTavernFiles(inputs)
  const warnings = parsedFiles.flatMap(file => file.warnings.map(warning => `${file.name}: ${warning}`))
  const cards: Record<string, Card> = {}
  const connections: Connection[] = []
  const occupied = [...(options.existingCards ?? [])]
  const parents = resolveParentFiles(parsedFiles)
  const ordered = orderFiles(parsedFiles, parents)
  const fileLocations = new Map<string, Array<BlockLocation | undefined>>()
  const branchCardsByKey = new Map<string, Card>()
  let rootCount = 0
  let differenceHunks = 0
  let swipeBranches = 0

  const originX = options.originX ?? 80
  let nextRootY = options.originY ?? 80

  const materializeHunks = (
    baseBlocks: ConversationBlock[],
    variantBlocks: ConversationBlock[],
    baseLocations: Array<BlockLocation | undefined>,
    sourceLabel: string,
    branchPath: string[],
  ): Array<BlockLocation | undefined> => {
    const edits = diffSequence(baseBlocks, variantBlocks, (a, b) => (
      conversationBlockFingerprint(a) === conversationBlockFingerprint(b)
    ))
    const variantLocations: Array<BlockLocation | undefined> = new Array(variantBlocks.length)

    for (const edit of edits) {
      if (edit.kind !== 'equal' || edit.baseIndex === undefined || edit.variantIndex === undefined) continue
      const location = baseLocations[edit.baseIndex]
      variantLocations[edit.variantIndex] = location
      if (!location) continue
      const card = cards[location.cardId]
      const block = card?.conversation?.blocks.find(item => item.id === location.blockId)
      if (block) addSourceToBlock(block, sourceLabel)
      if (card?.conversation) {
        card.conversation.sourceFiles = uniquePush(card.conversation.sourceFiles, sourceLabel)
        card.sourceLabel = card.conversation.sourceFiles.join(' · ')
      }
    }

    const hunks = collectDifferenceHunks(edits)
    differenceHunks += hunks.length
    for (const hunk of hunks) {
      const anchors = anchorsForHunk(baseBlocks.length, hunk, baseLocations)
      if (!anchors.start) {
        warnings.push(`${sourceLabel}: 差异片段缺少父卡片锚点`)
        continue
      }
      const sourceCard = cards[anchors.start.cardId]
      if (!sourceCard) continue

      const displayBlocks: ConversationBlock[] = hunk.variantValues.length > 0
        ? hunk.variantValues.map((block, index) => {
          const baseBlock = hunk.baseValues[Math.min(index, Math.max(0, hunk.baseValues.length - 1))]
          return {
            ...block,
            sourceFiles: uniquePush(block.sourceFiles, sourceLabel),
            diff: inlineDiff(baseBlock?.text ?? '', block.text),
          }
        })
        : [{
          id: `${sourceLabel}:deleted:${hunk.baseStart}-${hunk.baseEnd}`,
          role: hunk.baseValues[0]?.role ?? 'system',
          name: hunk.baseValues[0]?.name ?? 'System',
          text: `该分支省略 ${hunk.baseValues.length} 段`,
          messageIndex: hunk.baseValues[0]?.messageIndex ?? -1,
          paragraphIndex: 0,
          sourceFiles: [sourceLabel],
          diff: hunk.baseValues.flatMap((block, index) => [
            ...(index > 0 ? [{ text: '\n\n', kind: 'removed' as const }] : []),
            { text: block.text, kind: 'removed' as const },
          ]),
        }]

      const endKey = anchors.end ? `${anchors.end.cardId}:${anchors.end.blockId}:${anchors.endBoundary}` : 'end'
      const key = [
        `${anchors.start.cardId}:${anchors.start.blockId}:${anchors.startBoundary}`,
        endKey,
        ...displayBlocks.map(conversationBlockFingerprint),
      ].join('|')
      let branchCard = branchCardsByKey.get(key)
      if (!branchCard) {
        const cardId = `card-st-${generateId()}`
        branchCard = createConversationCard(
          cardId,
          displayBlocks,
          'branch',
          [sourceLabel],
          branchPath,
          0,
          0,
          sourceCard.id,
        )
        const sourceAnchor: CardAnchor = {
          blockId: anchors.start.blockId,
          edge: 'right',
          boundary: anchors.startBoundary,
        }
        placeBranchCard(branchCard, sourceCard, sourceAnchor, occupied)
        cards[cardId] = branchCard
        occupied.push(branchCard)
        branchCardsByKey.set(key, branchCard)

        connections.push({
          id: `conn-st-${generateId()}`,
          fromCardId: sourceCard.id,
          toCardId: cardId,
          color: '#F59E0B',
          label: sourceLabel,
          kind: 'conversation-branch',
          fromAnchor: sourceAnchor,
          toAnchor: { edge: 'left', boundary: 'top' },
        })
        if (anchors.rejoins && anchors.end && cards[anchors.end.cardId]) {
          connections.push({
            id: `conn-st-${generateId()}`,
            fromCardId: cardId,
            toCardId: anchors.end.cardId,
            color: '#F59E0B',
            label: sourceLabel,
            kind: 'conversation-return',
            fromAnchor: { edge: 'left', boundary: 'bottom' },
            toAnchor: { blockId: anchors.end.blockId, edge: 'right', boundary: anchors.endBoundary },
          })
        }
        addBranchAnchor(sourceCard, {
          branchCardId: cardId,
          startBlockId: anchors.start.blockId,
          endBlockId: anchors.end?.blockId,
          sourceFiles: [sourceLabel],
        })
      } else if (branchCard.conversation) {
        branchCard.conversation.sourceFiles = uniquePush(branchCard.conversation.sourceFiles, sourceLabel)
        branchCard.sourceLabel = branchCard.conversation.sourceFiles.join(' · ')
        branchCard.conversation.blocks.forEach(block => addSourceToBlock(block, sourceLabel))
      }

      hunk.variantValues.forEach((_, index) => {
        const variantIndex = hunk.variantStart + index
        const displayBlock = branchCard!.conversation!.blocks[Math.min(index, branchCard!.conversation!.blocks.length - 1)]
        variantLocations[variantIndex] = { cardId: branchCard!.id, blockId: displayBlock.id }
      })
      for (let index = hunk.baseStart; index < hunk.baseEnd; index++) {
        const location = baseLocations[index]
        const card = location ? cards[location.cardId] : undefined
        if (card && location) addReplacedBlock(card, location.blockId)
      }
    }
    return variantLocations
  }

  const materializeSwipes = (file: ParsedSillyTavernFile, locations: Array<BlockLocation | undefined>, branchPath: string[]) => {
    for (const message of file.messages) {
      if (message.swipes.length <= 1) continue
      const range = file.messageBlockRanges[message.index]
      if (!range) continue
      const selectedBlocks = file.blocks.slice(range.start, range.end)
      const selectedLocations = locations.slice(range.start, range.end)
      for (const swipe of message.swipes) {
        if (swipe.index === message.selectedSwipeIndex) continue
        const paragraphs = splitConversationParagraphs(swipe.text)
        const alternateBlocks: ConversationBlock[] = (paragraphs.length ? paragraphs : ['（空消息）']).map((text, paragraphIndex) => ({
          id: `${file.id}:m${message.index}:sw${swipe.index}:p${paragraphIndex}`,
          role: message.role,
          name: message.name,
          text,
          messageIndex: message.index,
          paragraphIndex,
          timestamp: swipe.timestamp,
          model: swipe.model,
          swipeIndex: swipe.index,
          selectedSwipe: false,
        }))
        materializeHunks(
          selectedBlocks,
          alternateBlocks,
          selectedLocations,
          `${file.name} · swipe ${swipe.index + 1}/${message.swipes.length}`,
          [...branchPath, `swipe-${message.index}-${swipe.index}`],
        )
        swipeBranches++
      }
    }
  }

  for (const file of ordered) {
    const parent = parents.get(file.id)
    if (!parent || !fileLocations.has(parent.id)) {
      const blocks = file.blocks.map(block => ({ ...block, sourceFiles: [file.name] }))
      const cardId = `card-st-${generateId()}`
      const rootCard = createConversationCard(
        cardId,
        blocks,
        'backbone',
        [file.name],
        [file.name],
        originX,
        nextRootY,
      )
      while (occupied.some(card => cardsOverlap(rootCard, card))) rootCard.y += 180
      cards[cardId] = rootCard
      occupied.push(rootCard)
      const locations = blocks.map(block => ({ cardId, blockId: block.id }))
      fileLocations.set(file.id, locations)
      nextRootY = rootCard.y + resolveCardSize(rootCard).height + 260
      rootCount++
      materializeSwipes(file, locations, [file.name])
      continue
    }

    const parentLocations = fileLocations.get(parent.id)!
    const locations = materializeHunks(
      parent.blocks,
      file.blocks,
      parentLocations,
      file.name,
      [parent.name, file.name],
    )
    fileLocations.set(file.id, locations)
    materializeSwipes(file, locations, [parent.name, file.name])
  }

  return {
    cards,
    connections,
    warnings,
    stats: {
      files: parsedFiles.length,
      backbones: rootCount,
      branchCards: Object.values(cards).filter(card => card.conversation?.segmentKind === 'branch').length,
      differenceHunks,
      swipeBranches,
    },
  }
}

export function fingerprintConversationBlocks(blocks: ConversationBlock[]): string[] {
  return blocks.map(block => `${block.role}:${normalizeConversationText(block.text)}`)
}
