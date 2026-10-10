// syncProtocol.ts — WebSocket sync protocol types (shared with Mosaic & server)

import type { Card } from './types'

export type BoardMutationDomain = 'structure' | 'viewport' | 'ink' | 'document' | 'import'

export type BoardClientMessage =
  | {
      type: 'board.join'
      boardId: string
      clientId: string
      boardBase64: string
    }
  | {
      type: 'board.mutate'
      boardId: string
      clientId: string
      operationId: string
      baseRevision: number
      domain: BoardMutationDomain
      boardBase64: string
    }
  | {
      type: 'parent.merge'
      boardId: string
      clientId: string
      operationId: string
      childIds: string[]
      inkPolicy: 'archive' | 'discard'
      parentCard: Card
    }

export type BoardServerMessage =
  | BoardSnapshotMessage
  | ParentMergeResultMessage
  | {
      type: 'board.error'
      code?: string
      operationId?: string
      message: string
    }

export interface ParentMergeResultMessage {
  type: 'parent.merge.result'
  operationId: string
  revision: number
  parentCard?: Card
  removedChildIds?: string[]
  archivedInkCount: number
}

export interface BoardSnapshotMessage {
  type: 'board.snapshot'
  boardId: string
  revision: number
  boardBase64: string
  sourceClientId?: string
  operationId?: string
  domain?: BoardMutationDomain
}

export interface ParentMergeMessage {
  type: 'parent.merge'
  boardId: string
  clientId: string
  operationId: string
  childIds: string[]
  inkPolicy: 'archive' | 'discard'
  parentCard: Card
}
