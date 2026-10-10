/**
 * 板块同步编排 — 增量抓取、去重、游标管理
 */

import {
  getCommunity,
  upsertCommunity,
  postExists,
  insertPostsBatch,
  updateCursor,
  markBackfillDone,
  touchCommunitySync,
  startSyncLog,
  finishSyncLog,
  upsertCommentsBatch,
  getPost,
  getDb,
  type NewPost,
  type CommunityRow,
} from './db.js'
import { fetchRedditPage, fetchRedditComments, type FlatRedditPost } from './reddit.js'

// ============================================
// Types
// ============================================

export interface SyncResult {
  success: boolean
  scanned: number
  newCount: number
  skipped: number
  cumulative: number
  backfillDone: boolean
  error?: string
}

export interface CommentRefreshResult {
  success: boolean
  added: number
  updated: number
  total: number
  error?: string
}

// 同一时间只允许一个同步任务（Reddit 限速考虑）
let syncBusy = false

export function isSyncBusy(): boolean {
  return syncBusy
}

// ============================================
// 帖子字段映射
// ============================================

function toNewPost(p: FlatRedditPost): NewPost {
  return {
    id: p.post_id,
    title: p.title,
    author: p.author,
    body: p.selftext,
    permalink: p.permalink,
    score: p.score,
    upvoteRatio: p.upvote_ratio,
    commentCount: p.num_comments,
    flair: p.link_flair_text,
    createdUtc: p.created_utc,
  }
}

// ============================================
// 同步（latest / backfill）
// ============================================

const MAX_PAGES = 20 // 单次同步页数上限（2000 条），防失控
const KNOWN_PAGES_TO_STOP = 2 // latest 方向：连续 N 页全部已知则停止

export async function syncRedditCommunity(
  subredditName: string,
  direction: 'latest' | 'backfill',
  targetNew: number = 100
): Promise<SyncResult> {
  if (syncBusy) {
    return { success: false, scanned: 0, newCount: 0, skipped: 0, cumulative: 0, backfillDone: false, error: '已有同步任务进行中' }
  }
  syncBusy = true

  const community = upsertCommunity('reddit', subredditName, `https://www.reddit.com/r/${subredditName}/`)
  const logId = startSyncLog(community.id, direction)

  let scanned = 0
  let newCount = 0
  let skipped = 0
  let backfillDone = false

  try {
    // latest 从首页开始（不用 cursor，保证不漏新帖）；backfill 从历史 cursor 继续
    let after: string | null = direction === 'backfill' ? community.oldest_cursor : null
    let consecutiveKnownPages = 0

    for (let page = 0; page < MAX_PAGES; page++) {
      const result = await fetchRedditPage(subredditName, 'new', after)
      scanned += result.scanned

      let pageNew = 0
      const freshPosts: NewPost[] = []
      for (const p of result.posts) {
        if (postExists(p.post_id)) {
          skipped++
        } else {
          freshPosts.push(toNewPost(p))
          pageNew++
        }
      }
      newCount += insertPostsBatch(community.id, 'reddit', freshPosts)

      // backfill 方向持续推进历史 cursor
      if (direction === 'backfill' && result.after) {
        updateCursor(community.id, 'backfill', result.after)
      }

      // 终止条件
      if (!result.hasMore) {
        if (direction === 'backfill') {
          markBackfillDone(community.id)
          backfillDone = true
        }
        break
      }
      if (newCount >= targetNew) break

      if (direction === 'latest') {
        consecutiveKnownPages = pageNew === 0 ? consecutiveKnownPages + 1 : 0
        if (consecutiveKnownPages >= KNOWN_PAGES_TO_STOP) break // 已追上本地进度
      }

      after = result.after
    }

    // latest 首次全量抓完后，历史 cursor 也应初始化（首次同步同时是 backfill 起点）
    if (direction === 'latest' && !community.oldest_cursor && after) {
      updateCursor(community.id, 'backfill', after)
    }

    touchCommunitySync(community.id)
    finishSyncLog(logId, { scanned, newCount, skipped }, 'done')

    const updated = getCommunity(community.id)!
    return {
      success: true,
      scanned,
      newCount,
      skipped,
      cumulative: updated.post_count,
      backfillDone: backfillDone || !!updated.backfill_done,
    }
  } catch (error) {
    finishSyncLog(logId, { scanned, newCount, skipped }, 'error')
    return {
      success: false,
      scanned,
      newCount,
      skipped,
      cumulative: getCommunity(community.id)?.post_count ?? 0,
      backfillDone,
      error: error instanceof Error ? error.message : '同步失败',
    }
  } finally {
    syncBusy = false
  }
}

// ============================================
// 评论刷新（增量）
// ============================================

export async function refreshPostComments(postId: string): Promise<CommentRefreshResult> {
  const post = getPost(postId)
  if (!post) {
    return { success: false, added: 0, updated: 0, total: 0, error: '帖子不存在' }
  }

  const community = getCommunity(post.community_id)
  if (!community) {
    return { success: false, added: 0, updated: 0, total: 0, error: '板块记录不存在' }
  }

  try {
    const result = await fetchRedditComments(community.name, postId)

    const { added, updated } = upsertCommentsBatch(postId, result.comments.map(c => ({
      id: c.comment_id,
      parentId: c.parent_id,
      author: c.author,
      body: c.body,
      score: c.score,
      depth: c.depth,
      isSubmitter: c.is_submitter,
      createdUtc: c.created_utc,
      permalink: c.permalink,
    })))

    // 顺带更新帖子分数/评论数
    if (result.post) {
      getDb().prepare(`
        UPDATE posts SET score = ?, comment_count = ?, updated_at = datetime('now') WHERE id = ?
      `).run(result.post.score ?? null, result.post.num_comments ?? null, postId)
    }

    return { success: true, added, updated, total: result.count }
  } catch (error) {
    return {
      success: false,
      added: 0,
      updated: 0,
      total: 0,
      error: error instanceof Error ? error.message : '评论刷新失败',
    }
  }
}
