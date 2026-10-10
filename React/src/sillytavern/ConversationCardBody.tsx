import React from 'react'
import type { Card, ConversationBlock } from '../types'
import { estimateConversationBlockHeight } from './conversationGeometry'
import { resolveCardSize } from '../cardGeometry'

function BlockText({ block }: { block: ConversationBlock }) {
  if (!block.diff?.length) return <>{block.text}</>
  return (
    <>
      {block.diff.map((span, index) => {
        if (span.kind === 'added') return <mark key={index} className="conversation-diff-added">{span.text}</mark>
        if (span.kind === 'removed') return <del key={index} className="conversation-diff-removed">{span.text}</del>
        return <React.Fragment key={index}>{span.text}</React.Fragment>
      })}
    </>
  )
}

export default function ConversationCardBody({ card }: { card: Card }) {
  const conversation = card.conversation
  if (!conversation) return null
  const width = resolveCardSize(card).width
  const replaced = new Set(conversation.replacedBlockIds ?? [])
  const sourceCount = conversation.sourceFiles.length

  return (
    <div className={`conversation-card-content ${conversation.segmentKind}`}>
      <div className="conversation-card-header">
        <div>
          <strong>{conversation.segmentKind === 'backbone' ? '公共对话骨架' : '差异分支'}</strong>
          <span>{conversation.blocks.length} 段</span>
        </div>
        <span className="conversation-source-count">{sourceCount} 个来源</span>
      </div>
      <div className="conversation-block-list">
        {conversation.blocks.map(block => {
          const blockSources = block.sourceFiles?.length ?? sourceCount
          return (
            <section
              key={block.id}
              data-conversation-block-id={block.id}
              className={[
                'conversation-block',
                `role-${block.role}`,
                replaced.has(block.id) ? 'has-branch' : '',
              ].filter(Boolean).join(' ')}
              style={{ minHeight: estimateConversationBlockHeight(block, width) }}
            >
              <div className="conversation-block-meta">
                <span className="conversation-role">{block.role === 'user' ? 'User' : block.role === 'assistant' ? block.name || 'Assistant' : 'System'}</span>
                {block.model && <span title={block.model}>{block.model}</span>}
                {blockSources > 1 && <span className="conversation-shared-count">共享 ×{blockSources}</span>}
                {replaced.has(block.id) && <span className="conversation-branch-marker">此处有变化</span>}
              </div>
              <div className="conversation-block-text"><BlockText block={block} /></div>
            </section>
          )
        })}
      </div>
    </div>
  )
}

