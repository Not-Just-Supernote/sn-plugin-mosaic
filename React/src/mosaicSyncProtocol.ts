import type { Card } from './types'

export const MOSAIC_SYNC_PROTOCOL = 2 as const

export type SyncOrigin = 'plugin' | 'web'
export type SyncMessageType =
  | 'auth'
  | 'join'
  | 'resume'
  | 'transaction'
  | 'ack'
  | 'conflict'
  | 'snapshot'
  | 'error'

export interface SyncEnvelope {
  protocol: typeof MOSAIC_SYNC_PROTOCOL
  type: SyncMessageType
  requestId: string
}

export interface SyncAuthMessage extends SyncEnvelope {
  type: 'auth'
  deviceId: string
  ticket: string
}

export interface SyncJoinMessage extends SyncEnvelope {
  type: 'join'
  boardId: string
  deviceId: string
  seed?: string
}

export interface SyncResumeMessage extends SyncEnvelope {
  type: 'resume'
  boardId: string
  deviceId: string
  revision: number
}

export interface SyncTransactionMessage extends SyncEnvelope {
  type: 'transaction'
  boardId: string
  deviceId: string
  transactionId: string
  operationId: string
  clientSeq: number
  baseRevision: number
  origin: SyncOrigin
  operations: SyncOperation[]
}

export type SyncOperation =
  | { kind: 'card.create'; card: Card }
  | { kind: 'card.patch'; id: string; fields: Record<string, unknown>; expected: Record<string, number> }
  | { kind: 'card.delete'; id: string; generation: number }
  | { kind: 'connection.upsert'; connection: Record<string, unknown> }
  | { kind: 'connection.delete'; id: string; generation: number }
  | { kind: 'stroke.add'; stroke: Record<string, unknown> }
  | { kind: 'stroke.delete'; id: string; generation: number }
  | { kind: 'whiteboard.upsert'; whiteboard: Record<string, unknown> }
  | { kind: 'whiteboard.delete'; id: string; generation: number }

export interface SyncAckMessage extends SyncEnvelope {
  type: 'ack'
  boardId: string
  operationId: string
  revision: number
  duplicate: boolean
}

export interface SyncConflictMessage extends SyncEnvelope {
  type: 'conflict'
  boardId: string
  operationId: string
  revision: number
  fields: Array<{ entity: string; id: string; field: string; local: unknown; remote: unknown }>
}

export type MosaicSyncMessage =
  | SyncAuthMessage
  | SyncJoinMessage
  | SyncResumeMessage
  | SyncTransactionMessage
  | SyncAckMessage
  | SyncConflictMessage

const ID_PATTERN = /^[a-zA-Z0-9:_-]{1,160}$/

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function requireString(value: unknown, name: string): string {
  if (typeof value !== 'string' || !ID_PATTERN.test(value)) throw new Error(`${name} 格式无效`)
  return value
}

function requireInteger(value: unknown, name: string, min = 0): number {
  if (!Number.isSafeInteger(value) || (value as number) < min) throw new Error(`${name} 格式无效`)
  return value as number
}

export function validateSyncMessage(value: unknown): asserts value is MosaicSyncMessage {
  if (!isRecord(value)) throw new Error('同步消息必须是对象')
  if (value.protocol !== MOSAIC_SYNC_PROTOCOL) throw new Error('同步协议版本不匹配')
  const type = requireString(value.type, 'type') as SyncMessageType
  requireString(value.requestId, 'requestId')

  if (type === 'auth') {
    requireString(value.deviceId, 'deviceId')
    if (typeof value.ticket !== 'string' || value.ticket.length < 16 || value.ticket.length > 4096) {
      throw new Error('ticket 格式无效')
    }
    return
  }

  if (type === 'join' || type === 'resume' || type === 'transaction' || type === 'ack' || type === 'conflict') {
    requireString(value.boardId, 'boardId')
  }
  if (type === 'join' || type === 'resume' || type === 'transaction') requireString(value.deviceId, 'deviceId')
  if (type === 'resume') requireInteger(value.revision, 'revision')
  if (type === 'transaction') {
    requireString(value.transactionId, 'transactionId')
    requireString(value.operationId, 'operationId')
    requireInteger(value.clientSeq, 'clientSeq', 1)
    requireInteger(value.baseRevision, 'baseRevision')
    if (value.origin !== 'plugin' && value.origin !== 'web') throw new Error('origin 格式无效')
    if (!Array.isArray(value.operations) || value.operations.length === 0 || value.operations.length > 512) {
      throw new Error('operations 数量无效')
    }
  }
  if (type === 'ack') {
    requireString(value.operationId, 'operationId')
    requireInteger(value.revision, 'revision')
    if (typeof value.duplicate !== 'boolean') throw new Error('duplicate 格式无效')
  }
  if (type === 'conflict') {
    requireString(value.operationId, 'operationId')
    requireInteger(value.revision, 'revision')
    if (!Array.isArray(value.fields) || value.fields.length > 512) throw new Error('冲突字段数量无效')
  }
}

export function parseSyncMessage(raw: string): MosaicSyncMessage {
  if (raw.length > 512 * 1024) throw new Error('同步消息超过 512 KiB 限制')
  const value: unknown = JSON.parse(raw)
  validateSyncMessage(value)
  return value
}
