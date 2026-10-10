import type { ConversationBlock, ConversationRole } from '../types'

export interface SillyTavernInputFile {
  name: string
  text: string
}

export interface SillyTavernSwipe {
  index: number
  text: string
  model?: string
  timestamp?: string
}

export interface SillyTavernMessage {
  index: number
  name: string
  role: ConversationRole
  text: string
  timestamp?: string
  model?: string
  selectedSwipeIndex: number
  swipes: SillyTavernSwipe[]
  extraBranches: string[]
}

export interface ParsedSillyTavernFile {
  id: string
  name: string
  baseName: string
  parentName?: string
  metadata: Record<string, unknown>
  messages: SillyTavernMessage[]
  blocks: ConversationBlock[]
  messageBlockRanges: Array<{ start: number; end: number }>
  warnings: string[]
}

type JsonRecord = Record<string, unknown>

function asRecord(value: unknown): JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as JsonRecord
    : {}
}

function stringValue(value: unknown): string | undefined {
  if (typeof value === 'string' && value.trim()) return value
  if (typeof value === 'number') return String(value)
  return undefined
}

export function normalizeChatFileName(name: string): string {
  return name.trim().replace(/\.jsonl$/i, '').toLocaleLowerCase()
}

export function normalizeConversationText(text: string): string {
  return text.normalize('NFKC').replace(/\r\n?/g, '\n').replace(/\s+/g, ' ').trim()
}

export function splitConversationParagraphs(text: string): string[] {
  const normalized = text.replace(/\r\n?/g, '\n').trim()
  if (!normalized) return []
  return normalized.split(/\n\s*\n+/).map(part => part.trim()).filter(Boolean)
}

export function conversationBlockFingerprint(block: Pick<ConversationBlock, 'role' | 'text'>): string {
  return `${block.role}\u0000${normalizeConversationText(block.text)}`
}

function createBlocks(fileId: string, message: SillyTavernMessage): ConversationBlock[] {
  const paragraphs = splitConversationParagraphs(message.text)
  const parts = paragraphs.length ? paragraphs : ['（空消息）']
  return parts.map((text, paragraphIndex) => ({
    id: `${fileId}:m${message.index}:p${paragraphIndex}`,
    role: message.role,
    name: message.name,
    text,
    messageIndex: message.index,
    paragraphIndex,
    timestamp: message.timestamp,
    model: message.model,
    swipeIndex: message.selectedSwipeIndex,
    selectedSwipe: message.swipes.length > 1,
  }))
}

function parseMessage(raw: JsonRecord, index: number): SillyTavernMessage | null {
  const name = stringValue(raw.name) ?? (raw.is_user ? 'User' : 'Assistant')
  const role: ConversationRole = raw.is_system === true
    ? 'system'
    : raw.is_user === true ? 'user' : 'assistant'
  const extra = asRecord(raw.extra)
  const swipeInfo = Array.isArray(raw.swipe_info) ? raw.swipe_info.map(asRecord) : []
  const rawSwipes = Array.isArray(raw.swipes) ? raw.swipes.map(value => String(value ?? '')) : []
  let selectedSwipeIndex = Number.isInteger(raw.swipe_id) ? Number(raw.swipe_id) : 0
  if (selectedSwipeIndex < 0) selectedSwipeIndex = 0

  const mes = typeof raw.mes === 'string' ? raw.mes : ''
  if (rawSwipes.length === 0) rawSwipes.push(mes)
  if (selectedSwipeIndex >= rawSwipes.length) selectedSwipeIndex = 0
  if (mes && rawSwipes[selectedSwipeIndex] !== mes) rawSwipes[selectedSwipeIndex] = mes
  const text = rawSwipes[selectedSwipeIndex] ?? mes

  const swipes = rawSwipes.map((swipeText, swipeIndex) => {
    const info = swipeInfo[swipeIndex] ?? {}
    const swipeExtra = asRecord(info.extra)
    return {
      index: swipeIndex,
      text: swipeText,
      model: stringValue(swipeExtra.model) ?? stringValue(extra.model),
      timestamp: stringValue(info.send_date) ?? stringValue(raw.send_date),
    }
  })

  const branches = Array.isArray(extra.branches)
    ? extra.branches.map(String).filter(Boolean)
    : []

  return {
    index,
    name,
    role,
    text,
    timestamp: stringValue(raw.send_date),
    model: swipes[selectedSwipeIndex]?.model ?? stringValue(extra.model),
    selectedSwipeIndex,
    swipes,
    extraBranches: branches,
  }
}

export function parseSillyTavernJsonl(input: SillyTavernInputFile, fileIndex = 0): ParsedSillyTavernFile {
  const warnings: string[] = []
  const records: JsonRecord[] = []
  input.text.split(/\r?\n/).forEach((line, lineIndex) => {
    if (!line.trim()) return
    try {
      const parsed = JSON.parse(line)
      if (typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)) {
        records.push(parsed as JsonRecord)
      } else {
        warnings.push(`第 ${lineIndex + 1} 行不是对象`)
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      warnings.push(`第 ${lineIndex + 1} 行解析失败：${message}`)
    }
  })

  const headerIndex = records.findIndex(record => Object.prototype.hasOwnProperty.call(record, 'chat_metadata'))
  const header = headerIndex >= 0 ? records[headerIndex] : {}
  const metadata = asRecord(header.chat_metadata)
  const messageRecords = records.filter((_, index) => index !== headerIndex)
  const messages = messageRecords.flatMap((record, index) => {
    const parsed = parseMessage(record, index)
    return parsed ? [parsed] : []
  })

  const baseName = input.name.replace(/\.jsonl$/i, '')
  const fileId = `st-${fileIndex}-${normalizeChatFileName(baseName).replace(/[^a-z0-9_-]+/gi, '-') || 'chat'}`
  const blocks: ConversationBlock[] = []
  const messageBlockRanges: Array<{ start: number; end: number }> = []
  for (const message of messages) {
    const start = blocks.length
    blocks.push(...createBlocks(fileId, message))
    messageBlockRanges.push({ start, end: blocks.length })
  }

  return {
    id: fileId,
    name: input.name,
    baseName,
    parentName: stringValue(metadata.main_chat),
    metadata,
    messages,
    blocks,
    messageBlockRanges,
    warnings,
  }
}

export function parseSillyTavernFiles(inputs: SillyTavernInputFile[]): ParsedSillyTavernFile[] {
  return inputs.map(parseSillyTavernJsonl)
}

