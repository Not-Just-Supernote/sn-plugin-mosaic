// BoundaryEditor.tsx — 混合渲染：div 逐行 MD 渲染（边界标记）+ textarea（编辑模式）
import React, { useState, useMemo, useCallback, useRef, useEffect } from 'react'
import ReactDOM from 'react-dom'
import type { Boundary, TextSegment } from './types'
import { downloadTextFile } from './services'

// ============================================
// 逐行轻量 Markdown 渲染
// 每行独立解析和 memo，不用整篇 react-markdown：
// - 保持行结构 → 边界标记逐行对齐、换行天然保留（不会把无空格换行揉在一起）
// - 长文本高性能：只有该行文本变化才重新解析
// 支持：标题、引用、分割线、粗体、斜体、行内代码、删除线、链接/图片、代码围栏
// ============================================

const INLINE_RE = /(`[^`]+`|\*\*[^*]+\*\*|\*[^*]+\*|~~[^~]+~~|!?\[[^\]]*\]\([^)]+\))/g

function renderInline(text: string): React.ReactNode {
  const parts: React.ReactNode[] = []
  let last = 0
  let k = 0
  let m: RegExpExecArray | null
  const re = new RegExp(INLINE_RE.source, 'g')
  while ((m = re.exec(text))) {
    if (m.index > last) parts.push(text.slice(last, m.index))
    const tok = m[0]
    if (tok.startsWith('`')) {
      parts.push(<code key={k++} className="mdl-code">{tok.slice(1, -1)}</code>)
    } else if (tok.startsWith('**')) {
      parts.push(<strong key={k++}>{renderInline(tok.slice(2, -2))}</strong>)
    } else if (tok.startsWith('~~')) {
      parts.push(<del key={k++}>{tok.slice(2, -2)}</del>)
    } else if (tok.startsWith('*')) {
      parts.push(<em key={k++}>{tok.slice(1, -1)}</em>)
    } else {
      const lm = tok.match(/^(!?)\[([^\]]*)\]\(([^)]+)\)$/)
      if (lm) {
        parts.push(
          <a key={k++} className="mdl-link" href={lm[3]} target="_blank" rel="noreferrer" title={lm[3]}
            onClick={e => e.stopPropagation()}>
            {lm[1] ? '🖼 ' : ''}{lm[2] || lm[3]}
          </a>
        )
      } else {
        parts.push(tok)
      }
    }
    last = m.index + tok.length
  }
  if (last < text.length) parts.push(text.slice(last))
  return parts.length > 0 ? parts : '\u00A0'
}

const MdLine = React.memo<{ text: string; inFence: boolean }>(({ text, inFence }) => {
  if (inFence) return <span className="mdl-fence">{text || '\u00A0'}</span>
  if (!text) return <>{'\u00A0'}</>

  const h = text.match(/^(#{1,6})\s+(.*)$/)
  if (h) {
    const level = Math.min(h[1].length, 3)
    return <span className={`mdl-h mdl-h${level}`}>{renderInline(h[2])}</span>
  }
  const q = text.match(/^(\s*>\s?)(.*)$/)
  if (q) return <span className="mdl-quote">{renderInline(q[2] || '')}</span>
  if (/^\s*(-{3,}|\*{3,})\s*$/.test(text)) return <span className="mdl-hr">{'─'.repeat(24)}</span>

  // 无序列表：- / * / + 前缀 → 圆点
  const ul = text.match(/^(\s*)[-*+]\s+(.*)$/)
  if (ul) return <span className="mdl-li">{ul[1]}<span className="mdl-bullet">•</span> {renderInline(ul[2])}</span>
  // 有序列表：数字标记加粗，内容走内联解析
  const ol = text.match(/^(\s*)(\d+[.)])\s+(.*)$/)
  if (ol) return <span className="mdl-li">{ol[1]}<span className="mdl-bullet">{ol[2]}</span> {renderInline(ol[3])}</span>

  return <>{renderInline(text)}</>
})

interface BoundaryEditorProps {
  initialText: string
  mode?: 'collection' | 'parent-split'
  extractedRanges?: Array<{ startLine: number; endLine: number }>
  onCreateCards: (segments: TextSegment[]) => void
  onTextChange?: (text: string) => void
  onSave?: () => void
  saving?: boolean
  saved?: boolean
  createDisabled?: boolean
  createDisabledReason?: string
  onClose: () => void
}

function extractSegments(bs: Boundary[], lines: string[]): TextSegment[] {
  const sorted = [...bs].sort((a, b) => a.lineIndex - b.lineIndex)
  const segs: TextSegment[] = []
  let i = 0
  while (i < sorted.length) {
    if (sorted[i].type === 'upper') {
      let found = false
      for (let j = i + 1; j < sorted.length; j++) {
        if (sorted[j].type === 'lower') {
          const t = lines.slice(sorted[i].lineIndex, sorted[j].lineIndex + 1).join('\n').trim()
          if (t) segs.push({ content: t, startLine: sorted[i].lineIndex, endLine: sorted[j].lineIndex })
          i = j + 1
          found = true
          break
        }
      }
      if (!found) i++
    } else {
      i++
    }
  }
  return segs
}

function computeKeptLines(bs: Boundary[]): Set<number> {
  const sorted = [...bs].sort((a, b) => a.lineIndex - b.lineIndex)
  const kept = new Set<number>()
  let i = 0
  while (i < sorted.length) {
    if (sorted[i].type === 'upper') {
      let found = false
      for (let j = i + 1; j < sorted.length; j++) {
        if (sorted[j].type === 'lower') {
          for (let l = sorted[i].lineIndex; l <= sorted[j].lineIndex; l++) kept.add(l)
          i = j + 1
          found = true
          break
        }
      }
      if (!found) i++
    } else {
      i++
    }
  }
  return kept
}

const BoundaryEditor: React.FC<BoundaryEditorProps> = ({
  initialText,
  mode = 'collection',
  extractedRanges = [],
  onCreateCards,
  onTextChange,
  onSave,
  saving = false,
  saved = false,
  createDisabled = false,
  createDisabledReason,
  onClose,
}) => {
  const [text, setText] = useState(initialText)
  const [boundaries, setBoundaries] = useState<Boundary[]>([])
  const [brushType, setBrushType] = useState<'upper' | 'lower'>('upper')
  const [editing, setEditing] = useState(false)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const linesRef = useRef<HTMLDivElement>(null)

  const lines = useMemo(() => text.split('\n'), [text])

  // 代码围栏内的行：整行按等宽原样展示，不做内联解析
  const fenceLines = useMemo(() => {
    const s = new Set<number>()
    let open = false
    lines.forEach((l, i) => {
      if (/^\s*(```|~~~)/.test(l)) { s.add(i); open = !open }
      else if (open) s.add(i)
    })
    return s
  }, [lines])

  const kept = useMemo(() => computeKeptLines(boundaries), [boundaries])
  const segs = useMemo(() => extractSegments(boundaries, lines).filter(segment => (
    extractedRanges.every(range => segment.endLine < range.startLine || segment.startLine > range.endLine)
  )), [boundaries, extractedRanges, lines])

  const hasUnpairedUpper = useMemo(() => {
    const sorted = [...boundaries].sort((a, b) => a.lineIndex - b.lineIndex)
    let i = 0
    while (i < sorted.length) {
      if (sorted[i].type === 'upper') {
        let found = false
        for (let j = i + 1; j < sorted.length; j++) {
          if (sorted[j].type === 'lower') { i = j + 1; found = true; break }
        }
        if (!found) return true
      } else i++
    }
    return false
  }, [boundaries])

  const handleGutterClick = useCallback((idx: number) => {
    if (editing) return
    if (extractedRanges.some(range => idx >= range.startLine && idx <= range.endLine)) return
    const ex = boundaries.find(b => b.lineIndex === idx)
    if (ex) {
      setBoundaries(p => p.filter(b => b.lineIndex !== idx))
      // 取消标记后，笔刷恢复为被取消的类型：重新标记时不会错位成另一种边界
      setBrushType(ex.type)
      return
    }
    if (brushType === 'lower' && !hasUnpairedUpper) return
    setBoundaries(p => [...p, { type: brushType, lineIndex: idx }].sort((a, b) => a.lineIndex - b.lineIndex))
    setBrushType(brushType === 'upper' ? 'lower' : 'upper')
  }, [boundaries, brushType, extractedRanges, hasUnpairedUpper, editing])

  // Enter edit mode — position textarea over the lines view
  const enterEditMode = useCallback(() => {
    setEditing(true)
    setTimeout(() => {
      if (textareaRef.current && linesRef.current) {
        textareaRef.current.scrollTop = linesRef.current.scrollTop
        textareaRef.current.focus()
      }
    }, 0)
  }, [])

  // Exit edit mode — sync scroll back
  const exitEditMode = useCallback(() => {
    setEditing(false)
    setTimeout(() => {
      if (textareaRef.current && linesRef.current) {
        linesRef.current.scrollTop = textareaRef.current.scrollTop
      }
    }, 0)
  }, [])

  // Handle text changes in textarea, adjust boundaries
  const handleTextChange = useCallback((e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const newText = e.target.value
    const newLines = newText.split('\n')
    const oldLineCount = lines.length
    const delta = newLines.length - oldLineCount

    if (delta !== 0 && boundaries.length > 0) {
      const cursor = e.target.selectionStart
      const editLine = newText.slice(0, cursor).split('\n').length - 1
      setBoundaries(prev => prev
        .map(b => b.lineIndex > editLine ? { ...b, lineIndex: b.lineIndex + delta } : b)
        .filter(b => b.lineIndex >= 0 && b.lineIndex < newLines.length))
    }
    setText(newText)
    onTextChange?.(newText)
  }, [lines.length, boundaries.length, onTextChange])

  // Escape key exits edit mode
  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        if (editing) exitEditMode()
        else onClose()
      }
    }
    window.addEventListener('keydown', handleKey)
    return () => window.removeEventListener('keydown', handleKey)
  }, [editing, exitEditMode, onClose])

  const editorContent = (
    <div className="be-overlay" onMouseDown={e => { if (e.target === e.currentTarget) onClose() }}>
      <div className="be-container">
        {/* Left: editor */}
        <div className="be-editor-panel">
          <div className="be-toolbar">
            <div className="be-brush-group">
              {editing ? (
                <button className="be-brush-btn be-brush-editing active" onClick={exitEditMode}>
                  退出编辑
                </button>
              ) : (
                <>
                  <button className={`be-brush-btn be-brush-upper ${brushType === 'upper' ? 'active' : ''}`}
                    onClick={() => setBrushType('upper')}>▎上边界</button>
                  <button className={`be-brush-btn be-brush-lower ${brushType === 'lower' ? 'active' : ''}`}
                    onClick={() => setBrushType('lower')}>▎下边界</button>
                </>
              )}
            </div>
            {!editing && <span className="be-toolbar-hint">双击文本进入编辑模式</span>}
            <div className="be-toolbar-actions">
              {onSave && (
                <button className="be-save-parent" disabled={saving || saved} onClick={onSave}>
                  {saving ? '保存中…' : saved ? '已保存' : '保存母卡'}
                </button>
              )}
              <button
                onClick={() => downloadTextFile(`编辑器导出_${new Date().toISOString().slice(0, 16).replace(/[T:]/g, '-')}.md`, text)}
                title="导出当前全文（含编辑模式的手动修改）"
              >
                导出全文
              </button>
              <button onClick={() => setBoundaries([])}>清除</button>
              <button onClick={onClose} aria-label="关闭" title="关闭">×</button>
            </div>
          </div>

          {/* Editor area */}
          <div className="be-editor-wrap">
            {/* Line-based view (boundary marking mode, 逐行 MD 渲染) */}
            <div
              className={`be-lines-view${editing ? ' hidden' : ''}`}
              ref={linesRef}
              onDoubleClick={enterEditMode}
            >
              {lines.map((line, idx) => {
                const b = boundaries.find(b => b.lineIndex === idx)
                const isKept = kept.has(idx)
                const isExtracted = extractedRanges.some(range => idx >= range.startLine && idx <= range.endLine)
                const gutterCls = `be-gutter-marker${b ? ` ${b.type}` : ''}${isKept && !b ? ' kept' : ''}`
                const lineCls = `be-line-text${isKept ? ' kept' : ''}${isExtracted ? ' extracted' : ''}${b ? ` boundary-${b.type}` : ''}`
                return (
                  <div key={idx} className="be-line-row">
                    <div className={gutterCls} onClick={() => handleGutterClick(idx)}>
                      {b ? (b.type === 'upper' ? '▲' : '▼') : '·'}
                    </div>
                    <div className={lineCls}><MdLine text={line} inFence={fenceLines.has(idx)} /></div>
                  </div>
                )
              })}
            </div>

            {/* Textarea (edit mode) */}
            <textarea
              ref={textareaRef}
              className={`be-textarea${editing ? ' visible' : ''}`}
              value={text}
              onChange={handleTextChange}
              spellCheck={false}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
            />
          </div>
        </div>

        {/* Right: card preview */}
        <div className="be-card-panel">
          <div className="be-card-panel-header">
            <span className="be-left-title">卡片预览</span>
            <span className="be-left-count">{segs.length} 个片段</span>
          </div>
          <div className="be-card-list">
            {segs.length === 0 ? (
              <div className="be-card-empty">
                在左侧编辑器中用边界标记选取文本片段，预览将显示在这里
              </div>
            ) : (
              segs.map((s, i) => (
                <div key={i} className="be-card-item">
                  <span className="be-card-label">#{i + 1}</span>
                  <span className="be-card-text">{s.content.length > 200 ? s.content.slice(0, 200) + '…' : s.content}</span>
                </div>
              ))
            )}
          </div>
          <button
            className="be-create-btn"
            disabled={segs.length === 0 || createDisabled}
            onClick={() => onCreateCards(segs)}
          >
            {mode === 'parent-split' ? '拆出' : '创建'} {segs.length} 张卡片
          </button>
          {createDisabled && createDisabledReason && (
            <div className="be-create-disabled-reason">{createDisabledReason}</div>
          )}
          <button
            className="be-create-btn"
            style={{ marginTop: 6 }}
            disabled={segs.length === 0}
            onClick={() => downloadTextFile(
              `卡片导出_${new Date().toISOString().slice(0, 16).replace(/[T:]/g, '-')}.md`,
              segs.map((s, i) => `## 片段 ${i + 1}\n\n${s.content}`).join('\n\n---\n\n')
            )}
            title="只导出已标记的片段（即将创建的卡片内容）"
          >
            导出 {segs.length} 个片段
          </button>
        </div>
      </div>
    </div>
  )

  return ReactDOM.createPortal(editorContent, document.body)
}

export default BoundaryEditor
