import { spawn } from 'node:child_process'
import { promises as fs } from 'node:fs'
import path from 'node:path'

const STEPFUN_CHAT_URL = process.env.STEP_API_URL ?? 'https://api.stepfun.com/v1/chat/completions'
const STEPFUN_FILES_URL = process.env.STEP_FILES_URL ?? 'https://api.stepfun.com/v1/files'
const STEP_API_KEY = process.env.STEP_API_KEY ?? '3WTRM23trpxcyAw9XO0cokarqBc92sZV03z7RY6Xzdu2015cOJuz3MJAyJhwx5JGh'
const DATA_DIR = path.resolve(process.cwd(), 'video-analysis')
const JOBS_DIR = path.join(DATA_DIR, 'jobs')
const HISTORY_PATH = path.join(DATA_DIR, 'manifest.json')

export type VideoAnalysisStage =
  | 'queued'
  | 'reading-metadata'
  | 'downloading'
  | 'subtitles'
  | 'segmenting'
  | 'analyzing'
  | 'completed'
  | 'error'

export interface VideoAnalysisOptions {
  url: string
  prompt: string
  includeTranscript: boolean
  segmentSeconds: number
}

export interface VideoAnalysisPart {
  index: number
  start: number
  end: number
  markdown: string
  transcriptChars: number
}

export interface VideoAnalysisRecord extends VideoAnalysisOptions {
  id: string
  platform: 'youtube'
  title: string
  createdAt: string
  completedAt: string
  duration: number
  markdown: string
  parts: VideoAnalysisPart[]
}

export interface VideoAnalysisJob extends VideoAnalysisOptions {
  id: string
  platform: 'youtube'
  stage: VideoAnalysisStage
  message: string
  progress: number
  currentPart: number
  totalParts: number
  title?: string
  createdAt: string
  completedAt?: string
  duration?: number
  markdown?: string
  parts?: VideoAnalysisPart[]
  error?: string
}

interface VideoAnalysisManifest {
  records: VideoAnalysisRecord[]
}

interface YoutubeInfo {
  title?: string
  duration?: number
  language?: string
  automatic_captions?: Record<string, unknown>
  subtitles?: Record<string, unknown>
}

const jobs = new Map<string, VideoAnalysisJob>()

function generateId(): string {
  return `video-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`
}

function updateJob(id: string, patch: Partial<VideoAnalysisJob>): void {
  const job = jobs.get(id)
  if (job) jobs.set(id, { ...job, ...patch })
}

async function readHistory(): Promise<VideoAnalysisManifest> {
  try {
    return JSON.parse(await fs.readFile(HISTORY_PATH, 'utf8')) as VideoAnalysisManifest
  } catch {
    return { records: [] }
  }
}

async function writeHistory(manifest: VideoAnalysisManifest): Promise<void> {
  await fs.mkdir(DATA_DIR, { recursive: true })
  await fs.writeFile(HISTORY_PATH, JSON.stringify(manifest, null, 2), 'utf8')
}

function runCommand(command: string, args: string[], cwd?: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    let stdout = ''
    let stderr = ''
    child.stdout.setEncoding('utf8')
    child.stderr.setEncoding('utf8')
    child.stdout.on('data', chunk => { stdout += chunk })
    child.stderr.on('data', chunk => {
      stderr += chunk
      if (stderr.length > 24000) stderr = stderr.slice(-24000)
    })
    child.on('error', error => reject(new Error(`无法启动 ${command}: ${error.message}`)))
    child.on('close', code => {
      if (code === 0) resolve(stdout)
      else reject(new Error(`${command} 执行失败 (${code})\n${stderr.slice(-4000)}`))
    })
  })
}

const ytDlpBaseArgs = (client: string) => [
  '--no-playlist',
  '--js-runtimes', 'node',
  '--extractor-args', `youtube:player_client=${client}`,
  '--socket-timeout', '30',
  '--retries', '50',
  '--fragment-retries', '50',
]

async function readYoutubeInfo(url: string): Promise<YoutubeInfo> {
  let lastError: unknown
  for (const client of ['tv_embedded', 'web_embedded']) {
    try {
      const output = await runCommand('yt-dlp', [
        ...ytDlpBaseArgs(client),
        '--skip-download',
        '--dump-single-json',
        url,
      ])
      return JSON.parse(output) as YoutubeInfo
    } catch (error) {
      lastError = error
    }
  }
  throw lastError instanceof Error ? lastError : new Error('无法读取 YouTube 视频信息')
}

function chooseSubtitleLanguage(info: YoutubeInfo): string | null {
  const tracks = { ...info.subtitles, ...info.automatic_captions }
  const languages = Object.keys(tracks).filter(language => language !== 'live_chat')
  if (!languages.length) return null
  const preferred = [info.language, 'zh-Hans', 'zh-Hant', 'zh', 'en-orig', 'en'].filter(Boolean) as string[]
  return preferred.find(language => languages.includes(language)) ?? languages[0]
}

async function downloadYoutubeVideo(url: string, workDir: string): Promise<string> {
  let lastError: unknown
  for (const client of ['tv_embedded', 'web_embedded']) {
    try {
      await runCommand('yt-dlp', [
        ...ytDlpBaseArgs(client),
        '--continue',
        '-f', 'bv*[height=720][vcodec^=avc1]/bv*[height=720]',
        '-o', path.join(workDir, 'source.%(ext)s'),
        url,
      ])
      const files = await fs.readdir(workDir)
      const video = files.find(name => /^source\.(mp4|mkv|webm)$/i.test(name))
      if (!video) throw new Error('yt-dlp 完成后未找到视频文件')
      return path.join(workDir, video)
    } catch (error) {
      lastError = error
    }
  }
  throw lastError instanceof Error ? lastError : new Error('720p 视频下载失败')
}

async function downloadSubtitles(url: string, language: string, workDir: string): Promise<string | null> {
  for (const client of ['tv_embedded', 'web_embedded']) {
    try {
      await runCommand('yt-dlp', [
        ...ytDlpBaseArgs(client),
        '--skip-download',
        '--write-subs',
        '--write-auto-subs',
        '--sub-langs', language,
        '--sub-format', 'srt',
        '--convert-subs', 'srt',
        '-o', path.join(workDir, 'captions.%(ext)s'),
        url,
      ])
      const files = await fs.readdir(workDir)
      const subtitle = files.find(name => /^captions(?:\..+)?\.srt$/i.test(name))
      return subtitle ? path.join(workDir, subtitle) : null
    } catch {
      // Some clients expose the video but not the caption endpoint; try the next one.
    }
  }
  return null
}

async function probeDuration(filePath: string): Promise<number> {
  const output = await runCommand('ffprobe', [
    '-v', 'error',
    '-show_entries', 'format=duration',
    '-of', 'default=noprint_wrappers=1:nokey=1',
    filePath,
  ])
  const duration = Number(output.trim())
  if (!Number.isFinite(duration)) throw new Error(`无法读取媒体时长: ${filePath}`)
  return duration
}

async function splitVideo(videoPath: string, segmentSeconds: number, workDir: string): Promise<string[]> {
  const segmentPattern = path.join(workDir, 'segment_%03d.mp4')
  await runCommand('ffmpeg', [
    '-y', '-v', 'error',
    '-i', videoPath,
    '-map', '0:v:0',
    '-an',
    '-c:v', 'copy',
    '-f', 'segment',
    '-segment_time', String(segmentSeconds),
    '-reset_timestamps', '1',
    segmentPattern,
  ])
  return (await fs.readdir(workDir))
    .filter(name => /^segment_\d+\.mp4$/i.test(name))
    .sort()
    .map(name => path.join(workDir, name))
}

function parseSrtTime(value: string): number {
  const match = value.match(/(\d{2}):(\d{2}):(\d{2})[,\.](\d{3})/)
  if (!match) return 0
  return Number(match[1]) * 3600 + Number(match[2]) * 60 + Number(match[3]) + Number(match[4]) / 1000
}

async function sliceSrt(filePath: string | null, start: number, end: number): Promise<string> {
  if (!filePath) return ''
  const text = await fs.readFile(filePath, 'utf8')
  const output: string[] = []
  const seen = new Set<string>()
  for (const block of text.replace(/\r/g, '').split(/\n\n+/)) {
    const lines = block.split('\n')
    const timingIndex = lines.findIndex(line => line.includes('-->'))
    if (timingIndex < 0) continue
    const [from, to] = lines[timingIndex].split('-->').map(value => value.trim())
    if (parseSrtTime(to) <= start || parseSrtTime(from) >= end) continue
    for (const rawLine of lines.slice(timingIndex + 1)) {
      const line = rawLine.replace(/<[^>]+>/g, '').trim()
      if (line && !seen.has(line)) {
        seen.add(line)
        output.push(line)
      }
    }
  }
  return output.join('\n')
}

function stepFunAuthorization(): string {
  if (!STEP_API_KEY) throw new Error('服务器未配置 STEP_API_KEY')
  return `Bearer ${STEP_API_KEY}`
}

async function uploadStepFunFile(filePath: string): Promise<string> {
  const bytes = await fs.readFile(filePath)
  const form = new FormData()
  form.append('purpose', 'storage')
  form.append('file', new Blob([bytes], { type: 'video/mp4' }), path.basename(filePath))
  const response = await fetch(STEPFUN_FILES_URL, {
    method: 'POST',
    headers: { Authorization: stepFunAuthorization() },
    body: form,
    signal: AbortSignal.timeout(10 * 60 * 1000),
  })
  const body = await response.text()
  if (!response.ok) throw new Error(`StepFun 文件上传失败 (${response.status}): ${body.slice(0, 800)}`)
  const data = JSON.parse(body) as { id?: string }
  if (!data.id) throw new Error('StepFun 文件上传响应中缺少 file id')
  return data.id
}

function formatTime(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds))
  const hours = Math.floor(whole / 3600)
  const minutes = Math.floor((whole % 3600) / 60)
  const secs = whole % 60
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, '0')}:${String(secs).padStart(2, '0')}`
    : `${minutes}:${String(secs).padStart(2, '0')}`
}

async function analyzeSegment(
  filePath: string,
  options: VideoAnalysisOptions,
  title: string,
  index: number,
  total: number,
  start: number,
  end: number,
  transcript: string,
): Promise<string> {
  const fileId = await uploadStepFunFile(filePath)
  const transcriptSection = options.includeTranscript
    ? `\n下面是本片段对应的自动字幕。字幕可能有识别或断句错误，请结合画面校正：\n---\n${transcript || '（本片段没有可用字幕）'}\n---\n`
    : '\n本次分析未附带语音字幕，请仅依据视频画面。\n'
  const instruction = `${options.prompt.trim() || '请结合视频画面整理本片段的关键信息，准确记录可见文字、数据、操作步骤和结论，并用结构清晰的 Markdown 输出。无法辨认的内容请明确说明，不要猜测。'}

视频标题：${title}
这是第 ${index + 1}/${total} 个片段，时间范围 ${formatTime(start)}–${formatTime(end)}。${transcriptSection}`
  const response = await fetch(STEPFUN_CHAT_URL, {
    method: 'POST',
    headers: {
      Authorization: stepFunAuthorization(),
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: 'step-3.7-flash',
      messages: [{
        role: 'user',
        content: [
          { type: 'video_url', video_url: { url: `stepfile://${fileId}` } },
          { type: 'text', text: instruction },
        ],
      }],
    }),
    signal: AbortSignal.timeout(15 * 60 * 1000),
  })
  const body = await response.text()
  if (!response.ok) throw new Error(`StepFun 分析失败 (${response.status}): ${body.slice(0, 1000)}`)
  const data = JSON.parse(body) as { choices?: Array<{ message?: { content?: string } }> }
  const markdown = data.choices?.[0]?.message?.content
  if (!markdown) throw new Error('StepFun 响应中没有分析内容')
  return markdown
}

async function executeJob(id: string): Promise<void> {
  const initial = jobs.get(id)
  if (!initial) return
  const workDir = path.join(JOBS_DIR, id)
  try {
    await fs.mkdir(workDir, { recursive: true })
    updateJob(id, { stage: 'reading-metadata', message: '正在读取 YouTube 视频信息…', progress: 2 })
    const info = await readYoutubeInfo(initial.url)
    const title = info.title?.trim() || 'YouTube 视频'
    const duration = Number(info.duration) || 0
    updateJob(id, { title, duration, stage: 'downloading', message: '正在下载 720p 视频…', progress: 8 })

    const videoPath = await downloadYoutubeVideo(initial.url, workDir)
    let subtitlePath: string | null = null
    if (initial.includeTranscript) {
      updateJob(id, { stage: 'subtitles', message: '正在获取自动字幕…', progress: 28 })
      const language = chooseSubtitleLanguage(info)
      if (language) subtitlePath = await downloadSubtitles(initial.url, language, workDir)
    }

    updateJob(id, { stage: 'segmenting', message: '正在按时长切分视频…', progress: 34 })
    const segmentPaths = await splitVideo(videoPath, initial.segmentSeconds, workDir)
    if (!segmentPaths.length) throw new Error('视频切分后没有生成任何片段')
    updateJob(id, { stage: 'analyzing', totalParts: segmentPaths.length, message: `准备分析 ${segmentPaths.length} 个片段…`, progress: 38 })

    const segmentDurations: number[] = []
    for (const segmentPath of segmentPaths) segmentDurations.push(await probeDuration(segmentPath))

    const parts: VideoAnalysisPart[] = []
    let cursor = 0
    for (let index = 0; index < segmentPaths.length; index++) {
      const start = cursor
      const end = Math.min(duration || Number.POSITIVE_INFINITY, start + segmentDurations[index])
      const transcript = initial.includeTranscript ? await sliceSrt(subtitlePath, start, end) : ''
      updateJob(id, {
        currentPart: index + 1,
        message: `正在上传并分析第 ${index + 1}/${segmentPaths.length} 段…`,
        progress: Math.round(38 + (index / segmentPaths.length) * 58),
      })
      const markdown = await analyzeSegment(
        segmentPaths[index], initial, title, index, segmentPaths.length, start, end, transcript,
      )
      parts.push({ index: index + 1, start, end, markdown, transcriptChars: transcript.length })
      cursor = end
    }

    const fullMarkdown = parts
      .map(part => `# 第 ${part.index} 部分（${formatTime(part.start)}–${formatTime(part.end)}）\n\n${part.markdown}`)
      .join('\n\n---\n\n')
    const completedAt = new Date().toISOString()
    const record: VideoAnalysisRecord = {
      id,
      platform: 'youtube',
      url: initial.url,
      prompt: initial.prompt,
      includeTranscript: initial.includeTranscript,
      segmentSeconds: initial.segmentSeconds,
      title,
      createdAt: initial.createdAt,
      completedAt,
      duration: duration || cursor,
      markdown: fullMarkdown,
      parts,
    }
    const manifest = await readHistory()
    manifest.records.unshift(record)
    await writeHistory(manifest)
    updateJob(id, {
      stage: 'completed',
      message: '分析完成',
      progress: 100,
      currentPart: segmentPaths.length,
      totalParts: segmentPaths.length,
      completedAt,
      duration: record.duration,
      markdown: fullMarkdown,
      parts,
    })
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    console.error(`[VideoAnalysis] ${id} failed:`, error)
    updateJob(id, { stage: 'error', message: '分析失败', error: message })
  } finally {
    try { await fs.rm(workDir, { recursive: true, force: true }) } catch { /* ignore cleanup errors */ }
  }
}

export function startYoutubeAnalysis(input: Partial<VideoAnalysisOptions>): VideoAnalysisJob {
  const activeJob = Array.from(jobs.values()).find(job => job.stage !== 'completed' && job.stage !== 'error')
  if (activeJob) throw new Error(`已有视频解析任务正在进行：${activeJob.message}`)

  const url = input.url?.trim() ?? ''
  let hostname = ''
  try { hostname = new URL(url).hostname.toLowerCase() } catch { /* validation below */ }
  if (hostname !== 'youtu.be' && hostname !== 'youtube.com' && !hostname.endsWith('.youtube.com')) {
    throw new Error('请输入有效的 YouTube 链接')
  }
  const segmentSeconds = Math.max(60, Math.min(900, Math.round(Number(input.segmentSeconds) || 300)))
  const job: VideoAnalysisJob = {
    id: generateId(),
    platform: 'youtube',
    url,
    prompt: input.prompt?.trim() ?? '',
    includeTranscript: input.includeTranscript !== false,
    segmentSeconds,
    stage: 'queued',
    message: '任务已创建',
    progress: 0,
    currentPart: 0,
    totalParts: 0,
    createdAt: new Date().toISOString(),
  }
  jobs.set(job.id, job)
  void executeJob(job.id)
  return job
}

export function getVideoAnalysisJob(id: string): VideoAnalysisJob | null {
  return jobs.get(id) ?? null
}

export function getActiveVideoAnalysisJob(): VideoAnalysisJob | null {
  return Array.from(jobs.values()).find(job => job.stage !== 'completed' && job.stage !== 'error') ?? null
}

export async function listVideoAnalysisHistory(): Promise<VideoAnalysisRecord[]> {
  return (await readHistory()).records
}

export async function deleteVideoAnalysisRecord(id: string): Promise<boolean> {
  const manifest = await readHistory()
  const next = manifest.records.filter(record => record.id !== id)
  if (next.length === manifest.records.length) return false
  await writeHistory({ records: next })
  return true
}
