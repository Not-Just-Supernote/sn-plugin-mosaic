// CardCreation.tsx — 卡片创建面板（手动采集 + 文本选取 + 贴吧爬取）
// 所有入口统一在 Web 白板上创建母卡草稿，保存后进入共享白板。
import React, { useState, useRef, useCallback, useEffect } from 'react'
import { createPortal } from 'react-dom'
import { canvasActions, canvasStore, panelActions } from './store'
import { addCardToCanvas, addParentDraftToCanvas } from './boxService'
import { CanvasManager } from './services'
import type { Card, TiebaRecord, TiebaPost } from './types'
import { buildSillyTavernBoard } from './sillytavern/boardAdapter'
import BoundaryEditor from './BoundaryEditor'
import HistoryList from './HistoryList'
import CommunityPanel from './CommunityPanel'
import TranscriptionPanel from './TranscriptionPanel'
import mammoth from 'mammoth'
import * as pdfjsLib from 'pdfjs-dist'

pdfjsLib.GlobalWorkerOptions.workerSrc = new URL('pdfjs-dist/build/pdf.worker.mjs', import.meta.url).toString()

type TabType = 'manual' | 'conversation' | 'transcription' | 'community'
type TiebaStatus = 'idle' | 'crawling' | 'done' | 'error'

interface SelectionAction {
  x: number
  y: number
  text: string
  sourceType: Card['sourceType']
  sourceLabel: string
  width: number
  height: number
}

function measureSelectionCard(range: Range, container: HTMLElement): { width: number; height: number } {
  const rects = Array.from(range.getClientRects()).filter(rect => rect.width > 0.5 && rect.height > 0.5)
  if (!rects.length) return { width: 240, height: 96 }

  const style = window.getComputedStyle(container)
  const sourceFontSize = Number.parseFloat(style.fontSize) || 13
  const sourceLineHeight = Number.parseFloat(style.lineHeight) || sourceFontSize * 1.7
  const cardFontSize = 12.5
  const cardLineHeight = cardFontSize * 1.7
  const widthScale = cardFontSize / sourceFontSize
  const heightScale = cardLineHeight / sourceLineHeight
  const widestLine = Math.max(...rects.map(rect => rect.width))
  const selectionTop = Math.min(...rects.map(rect => rect.top))
  const selectionBottom = Math.max(...rects.map(rect => rect.bottom))

  return {
    width: Math.round(Math.max(160, Math.min(500, widestLine * widthScale + 24))),
    height: Math.round(Math.max(72, Math.min(440, (selectionBottom - selectionTop) * heightScale + 36))),
  }
}

const CardCreation: React.FC = () => {
  const [activeTab, setActiveTab] = useState<TabType>('manual')

  // Manual tab: file import and clipboard paste
  const [importText, setImportText] = useState('')
  const [importFileName, setImportFileName] = useState('')
  const [dragover, setDragover] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [clipboardText, setClipboardText] = useState('')

  // SillyTavern conversation import
  const [conversationFiles, setConversationFiles] = useState<File[]>([])
  const [conversationBusy, setConversationBusy] = useState(false)
  const [conversationResult, setConversationResult] = useState('')
  const conversationFileInputRef = useRef<HTMLInputElement>(null)

  // Selection action button
  const [selectionBtn, setSelectionBtn] = useState<SelectionAction | null>(null)
  const textContentRef = useRef<HTMLDivElement>(null)
  const clipboardContentRef = useRef<HTMLDivElement>(null)

  // Tieba tab state
  const [tiebaUrl, setTiebaUrl] = useState('')
  const [tiebaStatus, setTiebaStatus] = useState<TiebaStatus>('idle')
  const [tiebaPosts, setTiebaPosts] = useState<TiebaPost[]>([])
  const [tiebaTitle, setTiebaTitle] = useState('')
  const [tiebaError, setTiebaError] = useState('')
  const [tiebaSelected, setTiebaSelected] = useState<Set<number>>(new Set())

  // ── Add-card helper ──
  const [boxNotice, setBoxNotice] = useState('')
  const boxNoticeTimer = useRef<number | undefined>(undefined)
  const addAllAsParents = useCallback(async (
    items: { content: string; sourceType: Card['sourceType']; sourceLabel?: string }[],
  ) => {
    let saved = 0
    for (const item of items) {
      if (addCardToCanvas(item.content, item.sourceType, item.sourceLabel)) saved++
    }
    window.clearTimeout(boxNoticeTimer.current)
    setBoxNotice(saved === items.length
      ? `已添加 ${saved} 张卡片到白板`
      : `已添加 ${saved}/${items.length} 张卡片`)
    boxNoticeTimer.current = window.setTimeout(() => setBoxNotice(''), 1800)
    return saved
  }, [])

  // ── Editor state (shared across tabs) ──
  const [editorText, setEditorText] = useState('')
  const [editorSource, setEditorSource] = useState<{ type: Card['sourceType']; label: string }>({ type: 'import', label: '' })
  const [showEditor, setShowEditor] = useState(false)

  // ── File import ──
  const [importLoading, setImportLoading] = useState(false)

  const handleFileRead = useCallback(async (file: File) => {
    const ext = file.name.split('.').pop()?.toLowerCase() || ''
    if (!['txt', 'md', 'docx', 'pdf'].includes(ext)) {
      alert('支持 .txt、.md、.docx、.pdf 文件')
      return
    }
    setImportLoading(true)
    setImportFileName(file.name)
    try {
      let text = ''
      if (ext === 'txt' || ext === 'md') {
        text = await file.text()
      } else if (ext === 'docx') {
        const buf = await file.arrayBuffer()
        const result = await mammoth.extractRawText({ arrayBuffer: buf })
        text = result.value
      } else if (ext === 'pdf') {
        const buf = await file.arrayBuffer()
        const pdf = await pdfjsLib.getDocument({ data: buf }).promise
        const pages: string[] = []
        for (let i = 1; i <= pdf.numPages; i++) {
          const page = await pdf.getPage(i)
          const content = await page.getTextContent()
          pages.push(content.items.map((item: any) => item.str).join(' '))
        }
        text = pages.join('\n\n')
      }
      setImportText(text)
    } catch (e) {
      alert('文件读取失败: ' + (e instanceof Error ? e.message : '未知错误'))
    } finally {
      setImportLoading(false)
    }
  }, [])

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    setDragover(false)
    const file = e.dataTransfer.files[0]
    if (file) handleFileRead(file)
  }, [handleFileRead])

  const handleFileChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (file) handleFileRead(file)
  }, [handleFileRead])

  const handleConversationFiles = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = Array.from(e.target.files ?? []).filter(file => file.name.toLowerCase().endsWith('.jsonl'))
    setConversationFiles(selected)
    setConversationResult('')
    e.target.value = ''
  }, [])

  const handleConversationImport = useCallback(async () => {
    if (conversationFiles.length === 0) return
    setConversationBusy(true)
    setConversationResult('')
    try {
      const inputs = await Promise.all(conversationFiles.map(async file => ({ name: file.name, text: await file.text() })))
      const canvasId = canvasStore.getState().activeCanvasId ?? CanvasManager.ensureDefaultCanvas('Canvas 1')
      const current = CanvasManager.getCanvasData(canvasId)
      const existingCards = Object.values(current.cards)
      const furthestRight = existingCards.reduce((max, card) => {
        const width = card.width ?? 520
        return Math.max(max, card.x + width)
      }, -160)
      const result = buildSillyTavernBoard(inputs, {
        originX: Math.max(80, furthestRight + 240),
        originY: 80,
        existingCards,
      })
      const next = {
        ...current,
        cards: { ...current.cards, ...result.cards },
        connections: [...current.connections, ...result.connections],
      }
      CanvasManager.saveCanvasData(canvasId, next)
      canvasActions.setActiveCanvas(canvasId)
      canvasActions.setCanvasData(CanvasManager.getCanvasData(canvasId))
      canvasActions.setCanvasList(CanvasManager.getCanvasList())
      setConversationResult(
        `已导入 ${result.stats.files} 个文件：${result.stats.backbones} 张公共骨架，${result.stats.branchCards} 张差异分支` +
        (result.warnings.length ? `；${result.warnings.length} 条解析提示` : ''),
      )
    } catch (error) {
      setConversationResult(`导入失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setConversationBusy(false)
    }
  }, [conversationFiles])

  // ── Text selection → source-shaped excerpt card ──
  const handleTextMouseUp = useCallback((
    e: React.MouseEvent<HTMLDivElement>,
    sourceType: Card['sourceType'],
    sourceLabel: string,
  ) => {
    const selection = window.getSelection()
    if (!selection || selection.isCollapsed || !selection.toString().trim()) {
      setSelectionBtn(null)
      return
    }

    const range = selection.getRangeAt(0)
    const container = e.currentTarget
    if (!container.contains(range.commonAncestorContainer)) {
      setSelectionBtn(null)
      return
    }

    const text = selection.toString().trim()
    if (text.length < 2) { setSelectionBtn(null); return }
    const rect = range.getBoundingClientRect()
    const dimensions = measureSelectionCard(range, container)
    setSelectionBtn({
      x: Math.max(8, Math.min(window.innerWidth - 108, rect.right + 8)),
      y: Math.max(8, rect.top - 34),
      text,
      sourceType,
      sourceLabel,
      ...dimensions,
    })
  }, [])

  const handleCreateFromSelection = useCallback(async () => {
    if (!selectionBtn) return
    const ok = addCardToCanvas(selectionBtn.text, selectionBtn.sourceType, selectionBtn.sourceLabel, {
      width: selectionBtn.width,
      height: selectionBtn.height,
    })
    window.clearTimeout(boxNoticeTimer.current)
    setBoxNotice(ok ? '已添加卡片到白板' : '卡片内容需要补充')
    boxNoticeTimer.current = window.setTimeout(() => setBoxNotice(''), 1800)
    setSelectionBtn(null)
    window.getSelection()?.removeAllRanges()
  }, [selectionBtn])

  // Hide selection btn on click elsewhere
  useEffect(() => {
    const handleClick = (e: MouseEvent) => {
      const target = e.target as HTMLElement
      if (!target.closest('.selection-action-btn')) {
        // Check if selection still exists
        const sel = window.getSelection()
        if (!sel || sel.isCollapsed) setSelectionBtn(null)
      }
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [])

  // ── Save all from import ──
  const handleCreateAllFromImport = useCallback(() => {
    if (!importText.trim()) return
    // Split by double newlines into paragraphs
    const paragraphs = importText.split(/\n\n+/).filter(p => p.trim())
    addAllAsParents(paragraphs.map((p, i) => ({
      content: p.trim(),
      sourceType: 'import' as const,
      sourceLabel: importFileName || `paragraph ${i + 1}`,
    })))
    setImportText('')
    setImportFileName('')
  }, [importText, importFileName, addAllAsParents])

  // ── Save from clipboard ──
  const handleCreateFromClipboard = useCallback(() => {
    if (!clipboardText.trim()) return
    addAllAsParents([{ content: clipboardText.trim(), sourceType: 'clipboard', sourceLabel: 'clipboard' }])
    setClipboardText('')
  }, [clipboardText, addAllAsParents])

  // ── Tieba crawl ──
  const handleTiebaCrawl = useCallback(async (urlOverride?: string) => {
    const crawlUrl = (urlOverride ?? tiebaUrl).trim()
    if (!crawlUrl) return
    if (urlOverride) setTiebaUrl(urlOverride)

    setTiebaStatus('crawling')
    setTiebaError('')
    setTiebaPosts([])
    setTiebaTitle('')

    try {
      // 预检：确认后端可达
      try {
        await fetch('/api/status', { signal: AbortSignal.timeout(3000) })
      } catch {
        throw new Error('后端服务未启动，请先运行：cd server && npm start')
      }

      const response = await fetch('/api/tieba/crawl', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: crawlUrl }),
      })

      const data = await response.json()

      if (!response.ok || !data.success) {
        throw new Error(data.error || '爬取失败')
      }

      setTiebaTitle(data.title)
      setTiebaPosts(data.posts)
      // 默认全选
      setTiebaSelected(new Set(data.posts.map((_: TiebaPost, i: number) => i)))
      setTiebaStatus('done')
    } catch (error) {
      const msg = error instanceof Error ? error.message : '未知错误'
      const userMsg = msg.includes('did not match the expected pattern')
        ? '后端服务未启动，请先运行：cd server && npm start'
        : msg
      setTiebaError(userMsg)
      setTiebaStatus('error')
    }
  }, [tiebaUrl])

  const handleTiebaToggleSelect = useCallback((index: number) => {
    setTiebaSelected(prev => {
      const next = new Set(prev)
      if (next.has(index)) next.delete(index)
      else next.add(index)
      return next
    })
  }, [])

  const handleTiebaSelectAll = useCallback(() => {
    if (tiebaSelected.size === tiebaPosts.length) {
      setTiebaSelected(new Set())
    } else {
      setTiebaSelected(new Set(tiebaPosts.map((_, i) => i)))
    }
  }, [tiebaSelected.size, tiebaPosts])

  const handleTiebaRetry = useCallback(() => {
    setTiebaStatus('idle')
    setTiebaError('')
  }, [])

  const handleTiebaDeletePost = useCallback((index: number) => {
    setTiebaPosts(prev => prev.filter((_, i) => i !== index))
    setTiebaSelected(prev => {
      const next = new Set<number>()
      for (const idx of prev) {
        if (idx < index) next.add(idx)
        else if (idx > index) next.add(idx - 1)
      }
      return next
    })
  }, [])

  return (
    <>
      {/* Panel header */}
      <div className="panel-header">
        <span className="panel-header-title">CARD CREATION</span>
        <button className="panel-header-close" onClick={panelActions.toggleRight}>✕</button>
      </div>

      {/* Tabs */}
      <div className="creation-tabs">
        {([['manual', 'Manual'], ['conversation', 'Conversation'], ['transcription', 'Transcription'], ['community', 'Community']] as [TabType, string][]).map(([tab, label]) => (
          <button key={tab} className={`creation-tab ${activeTab === tab ? 'active' : ''}`}
            onClick={() => setActiveTab(tab)}>
            {label}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="creation-content">
        {/* Manual tab: file import and clipboard paste */}
        {activeTab === 'manual' && (
          <>
            <div
              className={`file-drop-zone ${dragover ? 'dragover' : ''}`}
              onDragOver={(e) => { e.preventDefault(); setDragover(true) }}
              onDragLeave={() => setDragover(false)}
              onDrop={handleDrop}
              onClick={() => fileInputRef.current?.click()}
            >
              <input ref={fileInputRef} type="file" accept=".txt,.md,.docx,.pdf" onChange={handleFileChange} />
              {importLoading
                ? <span>读取中...</span>
                : importFileName
                  ? <span>已加载: <strong>{importFileName}</strong></span>
                  : <span>拖放或点击选择文件（.txt .md .docx .pdf）</span>
              }
            </div>

            {importText && (
              <>
                <div
                  ref={textContentRef}
                  className="text-content-area"
                  onMouseUp={(e) => handleTextMouseUp(e, 'import', importFileName || 'text selection')}
                >
                  {importText}
                </div>
                <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
                  <button className="add-card-btn" onClick={() => {
                    setEditorText(importText)
                    setEditorSource({ type: 'import', label: importFileName || '' })
                    setShowEditor(true)
                  }}>
                    打开编辑器
                  </button>
                  <button className="add-card-btn" onClick={handleCreateAllFromImport}>
                    添加卡片
                  </button>
                  <button className="add-card-btn" onClick={() => addParentDraftToCanvas(importText, 'import', importFileName || undefined)}>
                    添加母卡
                  </button>
                </div>
              </>
            )}

            <textarea
              className="clipboard-paste-area"
              placeholder="粘贴文本（Ctrl+V / Cmd+V）..."
              value={clipboardText}
              onChange={(e) => setClipboardText(e.target.value)}
            />

            <div style={{ display: 'flex', gap: 8 }}>
              <button className="add-card-btn" onClick={handleCreateFromClipboard}
                disabled={!clipboardText.trim()}>
                添加卡片
              </button>
              <button className="add-card-btn" onClick={() => addParentDraftToCanvas(clipboardText, 'clipboard', 'clipboard')}
                disabled={!clipboardText.trim()}>
                添加母卡
              </button>
            </div>
          </>
        )}

        {/* SillyTavern JSONL: common backbone + paragraph-level difference branches */}
        {activeTab === 'conversation' && (
          <div className="conversation-import-area">
            <div
              className="file-drop-zone"
              onClick={() => conversationFileInputRef.current?.click()}
            >
              <input
                ref={conversationFileInputRef}
                type="file"
                accept=".jsonl,application/jsonl"
                multiple
                onChange={handleConversationFiles}
              />
              <span>
                {conversationFiles.length > 0
                  ? `已选择 ${conversationFiles.length} 个 JSONL 文件`
                  : '选择 SillyTavern 主对话与 Branch JSONL（支持多选）'}
              </span>
            </div>
            {conversationFiles.length > 0 && (
              <div className="conversation-import-files">
                {conversationFiles.map(file => <span key={`${file.name}-${file.size}`}>{file.name}</span>)}
              </div>
            )}
            <button
              className="add-card-btn"
              style={{ width: '100%', marginTop: 10 }}
              disabled={conversationFiles.length === 0 || conversationBusy}
              onClick={() => void handleConversationImport()}
            >
              {conversationBusy ? '正在对齐公共段落与差异片段…' : '生成对话分支卡片'}
            </button>
            {conversationResult && <div className="conversation-import-result">{conversationResult}</div>}
            <p className="conversation-import-help">
              公共内容保留在长卡片中；任意位置出现的连续变化会从对应段落锚点伸出分支卡片，并在下一段公共内容处汇回。
            </p>
          </div>
        )}

        {/* Keep long-running transcription/video tasks mounted while another tab is open. */}
        <div className={activeTab === 'transcription' ? '' : 'creation-tab-pane-hidden'}>
          <TranscriptionPanel
            onOpenEditor={(text, source) => {
              setEditorText(text)
              setEditorSource(source)
              setShowEditor(true)
            }}
            onAddAsParents={addAllAsParents}
          />
        </div>

        {/* Community tab（Reddit 板块归档 + 贴吧单帖，统一 URL 入口） */}
        {activeTab === 'community' && (
          <div className="tieba-area">
            <CommunityPanel
              onCreateCard={(content, sourceType, label) =>
                addAllAsParents([{ content, sourceType, sourceLabel: label }])}
              onOpenEditor={(text, source) => {
                setEditorText(text)
                setEditorSource(source)
                setShowEditor(true)
              }}
              onTiebaUrl={(tiebaLink) => handleTiebaCrawl(tiebaLink)}
            />

            {/* Crawling status */}
            {tiebaStatus === 'crawling' && (
              <div className="transcription-status" style={{ marginTop: 10 }}>
                <div className="status-spinner" />
                正在爬取，请稍候...
              </div>
            )}

            {/* Error */}
            {tiebaStatus === 'error' && (
              <div className="transcription-error" style={{ marginTop: 10 }}>
                <div style={{ color: 'var(--error-color)', marginBottom: 8 }}>
                  ❌ {tiebaError}
                </div>
                <button className="add-card-btn" onClick={handleTiebaRetry}>
                  重试
                </button>
              </div>
            )}

            {/* Results */}
            {tiebaStatus === 'done' && tiebaPosts.length > 0 && (
              <>
                <div className="tieba-result-header">
                  <strong>{tiebaTitle}</strong>
                  <span style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>
                    {tiebaPosts.length} 条高价值楼层
                  </span>
                </div>

                <div className="tieba-post-list">
                  {tiebaPosts.map((post, i) => (
                    <label key={i} className={`tieba-post-item ${tiebaSelected.has(i) ? 'selected' : ''}`}>
                      <input
                        type="checkbox"
                        checked={tiebaSelected.has(i)}
                        onChange={() => handleTiebaToggleSelect(i)}
                      />
                      <div className="tieba-post-body">
                        <div className="tieba-post-header">
                          <span className="tieba-floor">#{post.floor}楼</span>
                          <span className="tieba-username">{post.username}</span>
                          <button
                            className="tieba-post-delete"
                            title="删除此楼层"
                            onClick={(e) => { e.preventDefault(); e.stopPropagation(); handleTiebaDeletePost(i) }}
                          >✕</button>
                        </div>
                        <div className="tieba-post-content">
                          {post.content.slice(0, 120)}{post.content.length > 120 ? '...' : ''}
                        </div>
                      </div>
                    </label>
                  ))}
                </div>

                <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
                  <button className="add-card-btn" style={{ flex: 1 }} onClick={handleTiebaSelectAll}>
                    {tiebaSelected.size === tiebaPosts.length ? '取消全选' : '全选'}
                  </button>
                  <button
                    className="add-card-btn"
                    style={{ flex: 1 }}
                    disabled={tiebaSelected.size === 0}
                    onClick={() => {
                      const combined = Array.from(tiebaSelected)
                        .sort((a, b) => a - b)
                        .map(idx => tiebaPosts[idx])
                        .filter(Boolean)
                        .map(p => `#${p.floor}楼 ${p.username}\n${p.content}`)
                        .join('\n\n')
                      addCardToCanvas(combined, 'tieba', tiebaTitle)
                    }}
                  >
                    添加卡片
                  </button>
                </div>
              </>
            )}

            {tiebaStatus === 'done' && tiebaPosts.length === 0 && (
              <div style={{ marginTop: 10, fontSize: 12, color: 'var(--text-tertiary)' }}>
                未找到高价值楼层，请检查链接或增加页数后重试。
              </div>
            )}

            {/* 爬取历史 */}
            <HistoryList<TiebaRecord>
              title="爬取历史"
              endpoint="/api/tieba/history"
              getName={(record) => record.title}
              getDateSuffix={(record) => `· ${record.filtered}楼`}
              deleteConfirmText="确认删除此爬取记录？"
              onLoadRecord={(record) => {
                setTiebaTitle(record.title)
                setTiebaPosts(record.posts)
                setTiebaSelected(new Set(record.posts.map((_, i) => i)))
                setTiebaStatus('done')
              }}
              onAddAsCards={(record) => {
                addAllAsParents(record.posts.map((post) => ({
                  content: post.content,
                  sourceType: 'tieba' as const,
                  sourceLabel: `${record.title} #${post.floor}楼`,
                })))
              }}
            />
          </div>
        )}
      </div>

      {/* Selection controls escape the transformed side panel so viewport coordinates stay exact. */}
      {selectionBtn && createPortal(
        <button
          className="selection-action-btn"
          style={{ left: selectionBtn.x, top: selectionBtn.y }}
          onClick={handleCreateFromSelection}
        >
          将选区添加为母卡
        </button>,
        document.body,
      )}
      {boxNotice && createPortal(<div className="card-box-notice">{boxNotice}</div>, document.body)}

      {/* Unified BoundaryEditor portal */}
      {showEditor && (
        <BoundaryEditor
          initialText={editorText}
          onCreateCards={(segments) => {
            addAllAsParents(segments.map((segment, i) => ({
              content: segment.content,
              sourceType: editorSource.type,
              sourceLabel: editorSource.label || `片段 ${i + 1}`,
            })))
            setShowEditor(false)
            setEditorText('')
          }}
          onClose={() => {
            setShowEditor(false)
            setEditorText('')
          }}
        />
      )}
    </>
  )
}

export default CardCreation
