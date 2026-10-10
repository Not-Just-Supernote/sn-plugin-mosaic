import React, { useMemo } from 'react'
import type { Card, Connection } from '../types'
import { resolveCardAnchorPoint } from './conversationGeometry'

function buildCurve(from: { x: number; y: number }, to: { x: number; y: number }): string {
  const horizontal = Math.max(70, Math.abs(to.x - from.x) * 0.45)
  const direction = to.x >= from.x ? 1 : -1
  return `M ${from.x} ${from.y} C ${from.x + horizontal * direction} ${from.y}, ${to.x - horizontal * direction} ${to.y}, ${to.x} ${to.y}`
}

export default function ConversationBranchLayer({ cards, connections }: {
  cards: Record<string, Card>
  connections: Connection[]
}) {
  const paths = useMemo(() => connections.flatMap(connection => {
    if (connection.kind !== 'conversation-branch' && connection.kind !== 'conversation-return') return []
    const fromCard = cards[connection.fromCardId]
    const toCard = cards[connection.toCardId]
    if (!fromCard || !toCard) return []
    const from = resolveCardAnchorPoint(fromCard, connection.fromAnchor)
    const to = resolveCardAnchorPoint(toCard, connection.toAnchor)
    return [{ connection, from, to, d: buildCurve(from, to) }]
  }), [cards, connections])

  if (paths.length === 0) return null

  return (
    <svg className="whiteboard-connections conversation-branch-svg" aria-hidden="true">
      {paths.map(({ connection, from, to, d }) => (
        <g key={connection.id} className={connection.kind === 'conversation-return' ? 'conversation-return-link' : 'conversation-branch-link'}>
          <path d={d} />
          <circle cx={from.x} cy={from.y} r="4" />
          <circle cx={to.x} cy={to.y} r="3" />
        </g>
      ))}
    </svg>
  )
}

