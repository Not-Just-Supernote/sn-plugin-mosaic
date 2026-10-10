/**
 * Reddit 抓取 — Playwright 真浏览器实现
 * 直接用 Chromium 访问 Reddit 公开 .json 端点，带完整 TLS/HTTP2/浏览器指纹，
 * 比裸 requests（Python 桥接）更不易被 403 拦截。
 *
 * 代理：设置环境变量 REDDIT_PROXY（如 socks5://127.0.0.1:1080）；不设则直连。
 * 历史：早期为 Python/ScrapiReddit 桥接实现，2026-07 移除；扁平化输出格式沿用其 flatten_* 约定。
 */

import { firefox, webkit, type Browser, type BrowserContext, type Page } from 'playwright'
import { promises as fs } from 'fs'
import os from 'os'
import path from 'path'

// 浏览器选择：与 transcribe.ts 保持一致，Windows 用 Firefox，macOS 用 WebKit，避免额外下载 Chromium
const isWindows = os.platform() === 'win32'
const browserType = isWindows ? firefox : webkit
const browserName = isWindows ? 'Firefox' : 'WebKit'

// ============================================
// Types（与原 Python flatten_post_record / flatten_comments 输出保持一致）
// ============================================

export interface FlatRedditPost {
  post_id: string
  title: string | null
  author: string | null
  subreddit: string
  created_utc: number | null
  created_iso: string | null
  score: number | null
  upvote_ratio: number | null
  num_comments: number | null
  permalink: string | null
  url: string | null
  selftext: string | null
  link_flair_text: string | null
  over_18: boolean | null
}

export interface FlatRedditComment {
  post_id: string
  comment_id: string
  parent_id: string | null
  author: string | null
  created_utc: number | null
  created_iso: string | null
  score: number | null
  depth: number
  body: string | null
  permalink: string | null
  is_submitter: boolean | null
}

export interface RedditPage {
  posts: FlatRedditPost[]
  scanned: number
  after: string | null
  hasMore: boolean
}

export interface RedditCommentsResult {
  post: FlatRedditPost | null
  comments: FlatRedditComment[]
  count: number
}

// ============================================
// 浏览器管理（惰性单例）
// ============================================

const BASE_URL = 'https://www.reddit.com'
const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
const FETCH_DELAY_MS = 1500 // 相邻请求最小间隔
const RATE_LIMIT_WAIT_MS = 60_000 // 429 等待
const MAX_RETRIES = 3

let browser: Browser | null = null
let context: BrowserContext | null = null
let page: Page | null = null

async function ensurePage(): Promise<Page> {
  // 探活：browser 崩溃/被关后重建
  if (browser && !browser.isConnected()) {
    browser = null
    context = null
    page = null
  }
  if (page && !page.isClosed()) return page

  if (!browser) {
    console.log(`[Reddit] 启动 ${browserName}（使用系统代理）`)
    browser = await browserType.launch({
      headless: true,
    })
  }
  if (!context) {
    const storageStateFile = path.resolve(process.cwd(), 'playwright/reddit-storage-state.json')
    let storageState: string | undefined
    try {
      await fs.access(storageStateFile)
      storageState = storageStateFile
      console.log('[Reddit] 已加载登录状态')
    } catch {
      // 未登录状态，继续匿名访问
    }
    context = await browser.newContext({
      userAgent: USER_AGENT,
      viewport: { width: 1280, height: 800 },
      locale: 'en-US',
      ...(storageState ? { storageState } : {}),
    })
  }
  page = await context.newPage()
  return page
}

export async function closeRedditBrowser(): Promise<void> {
  if (browser) {
    await browser.close().catch(() => {})
    browser = null
    context = null
    page = null
  }
}

// ============================================
// JSON 抓取（串行 + 限速 + 429 重试）
// ============================================

let fetchChain: Promise<unknown> = Promise.resolve()
let lastFetchAt = 0

function fetchRedditJson(url: string): Promise<any> {
  // 串行队列：同步任务与评论刷新可能并发，共用一个 page 必须排队
  const task = fetchChain.then(() => doFetch(url))
  fetchChain = task.catch(() => {})
  return task
}

async function doFetch(url: string): Promise<any> {
  const p = await ensurePage()

  for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    // 限速
    const wait = lastFetchAt + FETCH_DELAY_MS - Date.now()
    if (wait > 0) await new Promise(r => setTimeout(r, wait))
    lastFetchAt = Date.now()

    const resp = await p.goto(url, { waitUntil: 'commit', timeout: 60_000 })
    if (!resp) throw new Error(`无响应: ${url}`)

    const status = resp.status()
    if (status === 429) {
      console.warn(`[Reddit] 429 限速，等待 ${RATE_LIMIT_WAIT_MS / 1000}s 后重试 (${attempt}/${MAX_RETRIES})`)
      await new Promise(r => setTimeout(r, RATE_LIMIT_WAIT_MS))
      continue
    }
    if (status === 403) {
      throw new Error(`Reddit 403 拦截: ${url}。可尝试设置 REDDIT_PROXY 环境变量走代理`)
    }
    if (status >= 400) {
      if (attempt < MAX_RETRIES) {
        await new Promise(r => setTimeout(r, 2000 * attempt))
        continue
      }
      throw new Error(`HTTP ${status}: ${url}`)
    }

    const body = await resp.text()
    try {
      return JSON.parse(body)
    } catch {
      throw new Error(`响应不是 JSON（可能被反爬页面拦截）: ${body.slice(0, 120)}`)
    }
  }
  throw new Error(`重试 ${MAX_RETRIES} 次后仍失败: ${url}`)
}

// ============================================
// 数据扁平化（对应 ScrapiReddit flatten_*）
// ============================================

function toIso(utc: number | null | undefined): string | null {
  if (utc == null) return null
  return new Date(utc * 1000).toISOString()
}

function flattenPost(subreddit: string, d: any): FlatRedditPost {
  let permalink: string | null = d.permalink ?? null
  if (permalink && !permalink.startsWith('http')) permalink = `${BASE_URL}${permalink}`
  return {
    post_id: d.id,
    title: d.title ?? null,
    author: d.author ?? null,
    subreddit: d.subreddit ?? subreddit,
    created_utc: d.created_utc ?? null,
    created_iso: toIso(d.created_utc),
    score: d.score ?? null,
    upvote_ratio: d.upvote_ratio ?? null,
    num_comments: d.num_comments ?? null,
    permalink,
    url: d.url_overridden_by_dest ?? d.url ?? null,
    selftext: d.selftext ?? null,
    link_flair_text: d.link_flair_text ?? null,
    over_18: d.over_18 ?? null,
  }
}

function flattenCommentTree(
  nodes: any[],
  postId: string,
  depth: number,
  records: FlatRedditComment[]
): void {
  for (const node of nodes) {
    if (!node || node.kind !== 't1') continue
    const d = node.data ?? {}
    let permalink: string | null = d.permalink ?? null
    if (permalink && !permalink.startsWith('http')) permalink = `${BASE_URL}${permalink}`
    records.push({
      post_id: postId,
      comment_id: d.id,
      parent_id: d.parent_id ?? null,
      author: d.author ?? null,
      created_utc: d.created_utc ?? null,
      created_iso: toIso(d.created_utc),
      score: d.score ?? null,
      depth,
      body: d.body ?? null,
      permalink,
      is_submitter: d.is_submitter ?? null,
    })
    const replies = d.replies
    if (replies && typeof replies === 'object') {
      const children = replies.data?.children ?? []
      flattenCommentTree(children, postId, depth + 1, records)
    }
  }
}

// ============================================
// 公开 API（签名与原 Python 桥接版一致）
// ============================================

/** 抓取 subreddit listing 的一页（≤100 条），分页由调用方通过 after 控制 */
export async function fetchRedditPage(
  subreddit: string,
  sort: string = 'new',
  after?: string | null,
  limit: number = 100
): Promise<RedditPage> {
  const params = new URLSearchParams({ limit: String(Math.min(limit, 100)), raw_json: '1' })
  if (after) params.set('after', after)
  const url = `${BASE_URL}/r/${subreddit}/${sort}/.json?${params}`

  const data = await fetchRedditJson(url)
  const listing = data?.data ?? {}
  const children: any[] = listing.children ?? []
  const nextAfter: string | null = listing.after ?? null

  const posts = children
    .filter(c => c?.kind === 't3')
    .map(c => flattenPost(subreddit, c.data ?? {}))

  return {
    posts,
    scanned: posts.length,
    after: nextAfter,
    hasMore: !!nextAfter,
  }
}

/** 抓取单帖完整评论树 */
export async function fetchRedditComments(
  subreddit: string,
  postId: string,
  limit: number = 500
): Promise<RedditCommentsResult> {
  const params = new URLSearchParams({ limit: String(Math.min(limit, 500)), raw_json: '1' })
  const url = `${BASE_URL}/r/${subreddit}/comments/${postId}/.json?${params}`

  const data = await fetchRedditJson(url)
  if (!Array.isArray(data) || data.length < 2) {
    throw new Error('帖子响应格式异常')
  }

  const postChildren: any[] = data[0]?.data?.children ?? []
  const post = postChildren.length > 0 ? flattenPost(subreddit, postChildren[0].data ?? {}) : null

  const commentChildren: any[] = data[1]?.data?.children ?? []
  const comments: FlatRedditComment[] = []
  flattenCommentTree(commentChildren, postId, 0, comments)

  return { post, comments, count: comments.length }
}
