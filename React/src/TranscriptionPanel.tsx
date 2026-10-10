import React, { useCallback, useEffect, useRef, useState } from 'react'
import type { Card, TranscriptionRecord } from './types'
import HistoryList from './HistoryList'

type View = 'menu' | 'tingwu' | 'youtube' | 'bilibili'
type TranscriptionStatus = 'idle' | 'uploading' | 'transcribing' | 'waiting-login' | 'done' | 'error'

interface TranscriptionFileItem {
  file: File
  status: TranscriptionStatus
  error?: string
  text?: string
  segments?: string[]
}

interface VideoAnalysisPart {
  index: number
  start: number
  end: number
  markdown: string
  transcriptChars: number
}

interface VideoAnalysisRecord {
  id: string
  title: string
  url: string
  prompt: string
  includeTranscript: boolean
  segmentSeconds: number
  createdAt: string
  completedAt: string
  duration: number
  markdown: string
  parts: VideoAnalysisPart[]
}

interface VideoAnalysisJob {
  id: string
  stage: 'queued' | 'reading-metadata' | 'downloading' | 'subtitles' | 'segmenting' | 'analyzing' | 'completed' | 'error'
  message: string
  progress: number
  currentPart: number
  totalParts: number
  title?: string
  markdown?: string
  parts?: VideoAnalysisPart[]
  error?: string
}

interface ParentItem {
  content: string
  sourceType: Card['sourceType']
  sourceLabel?: string
}

interface TranscriptionPanelProps {
  onOpenEditor: (text: string, source: { type: Card['sourceType']; label: string }) => void
  onAddAsParents: (items: ParentItem[]) => void | Promise<number>
}

const DEFAULT_VIDEO_PROMPT = `请结合视频画面与可用字幕，整理本片段的关键信息，并用结构清晰的 Markdown 输出：
- 准确记录画面中可见的文字、数值、界面项目和设备名称
- 提炼讲解者的核心观点、操作步骤、对比结论和注意事项
- 字幕可能有识别错误，应以画面和上下文相互校正
- 无法辨认的细节请明确说明，不要猜测`

const VIDEO_ANALYSIS_STORAGE_KEY = 'wbai_video_analysis_state'

interface PersistedVideoAnalysisState {
  view?: View
  youtubeUrl?: string
  prompt?: string
  includeTranscript?: boolean
  segmentSeconds?: number
  jobId?: string
}

function loadPersistedVideoAnalysisState(): PersistedVideoAnalysisState {
  try {
    return JSON.parse(localStorage.getItem(VIDEO_ANALYSIS_STORAGE_KEY) || '{}') as PersistedVideoAnalysisState
  } catch {
    return {}
  }
}

function formatDuration(seconds: number): string {
  const value = Math.max(0, Math.floor(seconds || 0))
  const hours = Math.floor(value / 3600)
  const minutes = Math.floor((value % 3600) / 60)
  const secs = value % 60
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, '0')}:${String(secs).padStart(2, '0')}`
    : `${minutes}:${String(secs).padStart(2, '0')}`
}

const TranscriptionPanel: React.FC<TranscriptionPanelProps> = ({ onOpenEditor, onAddAsParents }) => {
  const persisted = useRef(loadPersistedVideoAnalysisState()).current
  const [view, setView] = useState<View>(persisted.view ?? 'menu')

  const [files, setFiles] = useState<TranscriptionFileItem[]>([])
  const fileInputRef = useRef<HTMLInputElement>(null)

  const [youtubeUrl, setYoutubeUrl] = useState(persisted.youtubeUrl ?? '')
  const [prompt, setPrompt] = useState(persisted.prompt ?? DEFAULT_VIDEO_PROMPT)
  const [includeTranscript, setIncludeTranscript] = useState(persisted.includeTranscript ?? true)
  const [segmentSeconds, setSegmentSeconds] = useState(persisted.segmentSeconds ?? 300)
  const [videoJob, setVideoJob] = useState<VideoAnalysisJob | null>(() => persisted.jobId ? {
    id: persisted.jobId,
    stage: 'queued',
    message: '正在恢复视频解析任务…',
    progress: 0,
    currentPart: 0,
    totalParts: 0,
  } : null)
  const [videoError, setVideoError] = useState('')
  const [videoHistoryKey, setVideoHistoryKey] = useState(0)

  const updateFile = useCallback((index: number, patch: Partial<TranscriptionFileItem>) => {
    setFiles(previous => previous.map((item, itemIndex) => itemIndex === index ? { ...item, ...patch } : item))
  }, [])

  const handleFiles = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    const selected = Array.from(event.target.files ?? [])
    if (selected.length) {
      setFiles(previous => [...previous, ...selected.map(file => ({ file, status: 'idle' as const }))])
    }
    event.target.value = ''
  }, [])

  const handleTranscribe = useCallback(async (index: number) => {
    const item = files[index]
    if (!item || item.status !== 'idle') return
    updateFile(index, { status: 'uploading', error: undefined })
    let loginPollTimer: ReturnType<typeof setInterval> | null = null
    try {
      try {
        const status = await fetch('/api/status', { signal: AbortSignal.timeout(3000) })
        if (!status.ok) throw new Error()
      } catch {
        throw new Error('转录服务器未启动，请先运行：cd server && npm start')
      }

      const form = new FormData()
      form.append('file', new File([item.file], item.file.name, {
        type: item.file.type || 'application/octet-stream',
      }))
      updateFile(index, { status: 'transcribing' })
      loginPollTimer = setInterval(async () => {
        try {
          const response = await fetch('/api/status', { signal: AbortSignal.timeout(2000) })
          const data = await response.json()
          setFiles(previous => previous.map((current, itemIndex) => {
            if (itemIndex !== index) return current
            if (data.loginRequired && (current.status === 'transcribing' || current.status === 'uploading')) {
              return { ...current, status: 'waiting-login' }
            }
            if (!data.loginRequired && current.status === 'waiting-login') {
              return { ...current, status: 'transcribing' }
            }
            return current
          }))
        } catch { /* backend status polling is best-effort */ }
      }, 3000)

      const response = await fetch('/api/transcribe', { method: 'POST', body: form })
      const data = await response.json()
      if (!response.ok || !data.success) throw new Error(data.error || '转录失败')
      updateFile(index, { status: 'done', text: data.text, segments: data.segments || [] })
    } catch (error) {
      const message = error instanceof Error ? error.message : '未知错误'
      updateFile(index, {
        status: 'error',
        error: message.includes('did not match the expected pattern')
          ? '无法连接转录服务器，请确认后端已启动（cd server && npm start）'
          : message,
      })
    } finally {
      if (loginPollTimer) clearInterval(loginPollTimer)
    }
  }, [files, updateFile])

  useEffect(() => {
    try {
      localStorage.setItem(VIDEO_ANALYSIS_STORAGE_KEY, JSON.stringify({
        view,
        youtubeUrl,
        prompt,
        includeTranscript,
        segmentSeconds,
        jobId: videoJob?.id,
      } satisfies PersistedVideoAnalysisState))
    } catch {
      // The in-memory state still survives ordinary panel and tab switches.
    }
  }, [view, youtubeUrl, prompt, includeTranscript, segmentSeconds, videoJob?.id])

  useEffect(() => {
    if (persisted.jobId) return
    void (async () => {
      try {
        const response = await fetch('/api/video-analysis/active')
        const data = await response.json()
        if (response.ok && data.success && data.job) setVideoJob(data.job as VideoAnalysisJob)
      } catch { /* no active remote task to recover */ }
    })()
  }, [persisted.jobId])

  useEffect(() => {
    if (!videoJob || videoJob.stage === 'completed' || videoJob.stage === 'error') return
    let cancelled = false
    const poll = async () => {
      try {
        const response = await fetch(`/api/video-analysis/jobs/${videoJob.id}`)
        const data = await response.json()
        if (response.status === 404) {
          const activeResponse = await fetch('/api/video-analysis/active')
          const activeData = await activeResponse.json()
          if (cancelled) return
          if (activeResponse.ok && activeData.success && activeData.job) {
            setVideoJob(activeData.job as VideoAnalysisJob)
          } else {
            setVideoJob(null)
            setVideoError('原任务已不在运行；已完成的结果仍可在视频解析历史中打开。')
          }
          return
        }
        if (!response.ok || !data.success) throw new Error(data.error || '读取任务状态失败')
        if (cancelled) return
        const next = data.job as VideoAnalysisJob
        setVideoJob(next)
        if (next.stage === 'completed') setVideoHistoryKey(value => value + 1)
        if (next.stage === 'error') setVideoError(next.error || '视频解析失败')
      } catch (error) {
        if (!cancelled) setVideoError(error instanceof Error ? error.message : '读取任务状态失败')
      }
    }
    void poll()
    const timer = window.setInterval(() => void poll(), 2000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [videoJob?.id, videoJob?.stage])

  const startYoutube = useCallback(async () => {
    if (!youtubeUrl.trim() || videoJob && !['completed', 'error'].includes(videoJob.stage)) return
    setVideoError('')
    setVideoJob(null)
    try {
      const response = await fetch('/api/video-analysis/youtube', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          url: youtubeUrl.trim(),
          prompt: prompt.trim(),
          includeTranscript,
          segmentSeconds,
        }),
      })
      const data = await response.json()
      if (!response.ok || !data.success) throw new Error(data.error || '无法创建视频解析任务')
      setVideoJob(data.job)
    } catch (error) {
      setVideoError(error instanceof Error ? error.message : '无法创建视频解析任务')
    }
  }, [youtubeUrl, prompt, includeTranscript, segmentSeconds, videoJob])

  const openVideoRecord = useCallback((record: Pick<VideoAnalysisRecord, 'markdown' | 'title'>) => {
    onOpenEditor(record.markdown, { type: 'youtube', label: record.title })
  }, [onOpenEditor])

  if (view === 'menu') {
    return (
      <div className="transcription-service-menu">
        <button className="transcription-service-card" onClick={() => setView('tingwu')}>
          <span className="transcription-service-icon">听</span>
          <span className="transcription-service-copy">
            <strong>通义听悟语音识别</strong>
            <small>上传本地音频或视频，获取分段转录与历史记录</small>
          </span>
          <span className="transcription-service-arrow">›</span>
        </button>
        <button className="transcription-service-card" onClick={() => setView('youtube')}>
          <span className="transcription-service-icon">YT</span>
          <span className="transcription-service-copy">
            <strong>YouTube 视频解析</strong>
            <small>输入链接，下载 720p 视频并按片段交给 AI 分析</small>
          </span>
          <span className="transcription-service-arrow">›</span>
        </button>
        <button className="transcription-service-card disabled" disabled>
          <span className="transcription-service-icon">B</span>
          <span className="transcription-service-copy">
            <strong>哔哩哔哩视频解析</strong>
            <small>接口尚未准备好</small>
          </span>
          <span className="transcription-service-badge">敬请期待</span>
        </button>
      </div>
    )
  }

  if (view === 'tingwu') {
    return (
      <div className="transcription-subpanel">
        <div className="transcription-subheader">
          <button onClick={() => setView('menu')}>←</button>
          <div><strong>通义听悟语音识别</strong><small>上传文件并管理转录历史</small></div>
        </div>
        <div className="file-select-zone" onClick={() => fileInputRef.current?.click()}>
          <input ref={fileInputRef} type="file" accept="audio/*,video/*" multiple onChange={handleFiles} />
          <span>点击添加音频或视频文件（可多选）</span>
        </div>

        {files.length > 0 && <div className="transcription-file-list">
          {files.map((item, index) => (
            <div key={`${item.file.name}-${index}`} className="transcription-file-item">
              <div className="transcription-file-header">
                <strong title={item.file.name}>{item.file.name}</strong>
                <span>{(item.file.size / 1024 / 1024).toFixed(1)} MB</span>
                {item.status === 'idle' && <button onClick={() => setFiles(previous => previous.filter((_, i) => i !== index))}>✕</button>}
              </div>
              {item.status === 'idle' && <button className="add-card-btn" onClick={() => void handleTranscribe(index)}>上传并转录</button>}
              {(item.status === 'uploading' || item.status === 'transcribing') && <div className="transcription-status"><div className="status-spinner" />{item.status === 'uploading' ? '上传中…' : '转录中，请稍候…'}</div>}
              {item.status === 'waiting-login' && <div className="transcription-status warning"><div className="status-spinner" />请在浏览器中登录通义听悟，登录后自动继续…</div>}
              {item.status === 'error' && <div className="transcription-error"><span>❌ {item.error}</span><button className="add-card-btn" onClick={() => updateFile(index, { status: 'idle', error: undefined })}>重试</button></div>}
              {item.status === 'done' && item.text && <div className="transcription-complete"><span>✓ 转录完成（{item.segments?.length || 0} 段）</span><button className="add-card-btn" onClick={() => onOpenEditor(item.text!, { type: 'transcription', label: item.file.name })}>打开编辑器</button></div>}
            </div>
          ))}
        </div>}

        <HistoryList<TranscriptionRecord>
          title="转录历史"
          endpoint="/api/transcriptions"
          getName={record => record.originalFilename}
          deleteConfirmText="确认删除此转录记录？文件将一并删除。"
          onLoadRecord={record => onOpenEditor(record.text, { type: 'transcription', label: record.originalFilename })}
          onAddAsCards={record => void onAddAsParents(record.segments.map((segment, index) => ({
            content: segment,
            sourceType: 'transcription',
            sourceLabel: `${record.originalFilename} 段落 ${index + 1}`,
          })))}
        />
      </div>
    )
  }

  if (view === 'bilibili') return null

  const jobRunning = !!videoJob && !['completed', 'error'].includes(videoJob.stage)
  return (
    <div className="transcription-subpanel video-analysis-panel">
      <div className="transcription-subheader">
        <button onClick={() => setView('menu')}>←</button>
        <div><strong>YouTube 视频解析</strong><small>720p 下载、字幕对齐与 StepFun 分段分析</small></div>
      </div>

      <label className="video-analysis-field">
        <span>YouTube 链接</span>
        <input type="url" value={youtubeUrl} onChange={event => setYoutubeUrl(event.target.value)} placeholder="https://youtu.be/…" disabled={jobRunning} />
      </label>
      <label className="video-analysis-field">
        <span>发送给 AI 的提示词</span>
        <textarea value={prompt} onChange={event => setPrompt(event.target.value)} rows={9} disabled={jobRunning} />
      </label>
      <div className="video-analysis-options">
        <label><input type="checkbox" checked={includeTranscript} onChange={event => setIncludeTranscript(event.target.checked)} disabled={jobRunning} />附带抓取到的视频字幕</label>
        <label>每段 <input type="number" min={60} max={900} step={30} value={segmentSeconds} onChange={event => setSegmentSeconds(Number(event.target.value))} disabled={jobRunning} /> 秒</label>
      </div>
      <button className="add-card-btn" disabled={!youtubeUrl.trim() || jobRunning} onClick={() => void startYoutube()}>
        {jobRunning ? '正在解析…' : '开始解析 YouTube 视频'}
      </button>

      {videoJob && <div className={`video-analysis-progress ${videoJob.stage}`}>
        <div className="video-analysis-progress-header">
          <strong>{videoJob.title || '正在准备视频'}</strong>
          <span>{videoJob.progress}%</span>
        </div>
        <div className="video-analysis-progress-track"><i style={{ width: `${videoJob.progress}%` }} /></div>
        <div className="video-analysis-progress-message">
          {jobRunning && <div className="status-spinner" />}
          <span>{videoJob.message}{videoJob.totalParts > 0 ? `（${videoJob.currentPart}/${videoJob.totalParts}）` : ''}</span>
        </div>
        {videoJob.stage === 'completed' && videoJob.markdown && <div className="video-analysis-result-actions">
          <button className="add-card-btn" onClick={() => openVideoRecord({ markdown: videoJob.markdown!, title: videoJob.title || 'YouTube 视频' })}>打开分析结果</button>
          <button className="add-card-btn secondary" onClick={() => void onAddAsParents((videoJob.parts || []).map(part => ({
            content: part.markdown,
            sourceType: 'youtube',
            sourceLabel: `${videoJob.title || 'YouTube 视频'} · 第 ${part.index} 段`,
          })))}>按片段添加母卡</button>
        </div>}
      </div>}
      {videoError && <div className="transcription-error video-analysis-error">❌ {videoError}</div>}

      <HistoryList<VideoAnalysisRecord>
        key={videoHistoryKey}
        title="视频解析历史"
        endpoint="/api/video-analysis/history"
        getName={record => record.title}
        getDateSuffix={record => `· ${formatDuration(record.duration)}`}
        deleteConfirmText="确认删除此视频解析记录？"
        onLoadRecord={openVideoRecord}
        onAddAsCards={record => void onAddAsParents(record.parts.map(part => ({
          content: part.markdown,
          sourceType: 'youtube',
          sourceLabel: `${record.title} · 第 ${part.index} 段`,
        })))}
      />
    </div>
  )
}

export default TranscriptionPanel
