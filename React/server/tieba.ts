/**
 * 百度贴吧帖子爬虫 — 通过 Python 子进程调用 aiotieba
 * cheerio 方案已被百度 403 封杀，改用 tieba_bridge.py 桥接
 */

import { spawn } from 'child_process'
import path from 'path'
import { promises as fs } from 'fs'

// ============================================
// Types
// ============================================

export interface TiebaPost {
  floor: number
  username: string
  content: string
  score: number
}

export interface CrawlResult {
  success: boolean
  title: string
  posts: TiebaPost[]
  total: number
  filtered: number
  error?: string
}

export interface TiebaRecord {
  id: string
  title: string
  url: string
  posts: TiebaPost[]
  total: number
  filtered: number
  createdAt: string
}

interface TiebaManifest {
  records: TiebaRecord[]
}

// ============================================
// History manifest
// ============================================

const DATA_DIR = path.resolve(process.cwd(), 'downloads')
const TIEBA_MANIFEST_PATH = path.join(DATA_DIR, 'tieba_manifest.json')

function generateRecordId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 6)
}

export async function readTiebaManifest(): Promise<TiebaManifest> {
  try {
    const content = await fs.readFile(TIEBA_MANIFEST_PATH, 'utf-8')
    return JSON.parse(content)
  } catch {
    return { records: [] }
  }
}

async function writeTiebaManifest(manifest: TiebaManifest): Promise<void> {
  await fs.mkdir(DATA_DIR, { recursive: true })
  await fs.writeFile(TIEBA_MANIFEST_PATH, JSON.stringify(manifest, null, 2), 'utf-8')
}

export async function saveTiebaRecord(url: string, result: CrawlResult): Promise<TiebaRecord> {
  const manifest = await readTiebaManifest()
  const record: TiebaRecord = {
    id: generateRecordId(),
    title: result.title,
    url,
    posts: result.posts,
    total: result.total,
    filtered: result.filtered,
    createdAt: new Date().toISOString(),
  }
  manifest.records.unshift(record)
  await writeTiebaManifest(manifest)
  return record
}

export async function deleteTiebaRecord(id: string): Promise<boolean> {
  const manifest = await readTiebaManifest()
  const idx = manifest.records.findIndex(r => r.id === id)
  if (idx === -1) return false
  manifest.records.splice(idx, 1)
  await writeTiebaManifest(manifest)
  return true
}

// ============================================
// URL 解析
// ============================================

export function extractPostId(url: string): string | null {
  const match = url.match(/(?:tieba\.baidu\.com\/p\/)?(\d{5,})/)
  return match ? match[1] : null
}

// ============================================
// 主入口 — 调用 Python 子进程
// ============================================

export async function crawlTiebaPost(postId: string): Promise<CrawlResult> {
  const bridgePath = path.resolve(import.meta.dirname, 'tieba_bridge.py')

  return new Promise((resolve) => {
    const chunks: Buffer[] = []
    const errChunks: Buffer[] = []

    const pythonBin = process.platform === 'win32' ? 'python' : 'python3'
    const proc = spawn(pythonBin, [bridgePath, postId], {
      timeout: 10 * 60 * 1000, // 10 分钟超时
    })

    proc.stdout.on('data', (data: Buffer) => chunks.push(data))
    proc.stderr.on('data', (data: Buffer) => {
      errChunks.push(data)
      // 把 stderr 转发到 Node 控制台方便调试
      process.stderr.write(data)
    })

    proc.on('close', (code) => {
      const stdout = Buffer.concat(chunks).toString('utf-8').trim()

      if (!stdout) {
        const stderr = Buffer.concat(errChunks).toString('utf-8').trim()
        resolve({
          success: false,
          title: '',
          posts: [],
          total: 0,
          filtered: 0,
          error: stderr || `Python 进程退出码 ${code}，无输出`,
        })
        return
      }

      try {
        const data = JSON.parse(stdout)
        resolve({
          success: data.success ?? false,
          title: data.title ?? '',
          posts: (data.posts ?? []) as TiebaPost[],
          total: data.total ?? 0,
          filtered: data.filtered ?? 0,
          error: data.error,
        })
      } catch {
        resolve({
          success: false,
          title: '',
          posts: [],
          total: 0,
          filtered: 0,
          error: `解析 Python 输出失败: ${stdout.slice(0, 200)}`,
        })
      }
    })

    proc.on('error', (err) => {
      resolve({
        success: false,
        title: '',
        posts: [],
        total: 0,
        filtered: 0,
        error: `无法启动 Python: ${err.message}。请确认已安装 python3 和 aiotieba (pip install aiotieba>=4.4.9)`,
      })
    })
  })
}
