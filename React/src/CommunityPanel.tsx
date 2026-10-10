// CommunityPanel.tsx — 板块增量归档面板（Reddit 板块 + 贴吧单帖统一入口）
import React, { useState, useCallback, useEffect } from 'react'
import type { Card, CommunityInfo, CommunitySyncResult, CommunityPost, CommunityComment } from './types'
import { downloadTextFile } from './services'

type PanelStatus = 'idle' | 'resolving' | 'error'
type SyncDirection = 'latest' | 'backfill'

interface ResolvedSource {
  platform: 'reddit' | 'tieba'
  type: 'subreddit' | 'post'
  name?: string
  postId?: string
  url: string
}

interface CommunityPanelProps {
  onCreateCard: (content: string, sourceType: Card['sourceType'], label: string) => void
  onOpenEditor: (text: string, source: { type: Card['sourceType']; label: string }) => void
  onTiebaUrl: (url: string) => void
}

function formatTime(iso: string | null): string {
  if (!iso) return '从未'
  return iso.replace('T', ' ').slice(0, 16)
}

function formatUtc(utc: number | null): string {
  if (!utc) return ''
  return new Date(utc * 1000).toISOString().slice(0, 10)
}

function formatUtcTime(utc: number | null): string {
  if (!utc) return ''
  return new Date(utc * 1000).toISOString().replace('T', ' ').slice(0, 16)
}

// ============================================
// 评论楼层化（贴吧式展示）
// 顶层评论按时间排序编楼号；楼中楼跟在所属楼层后面，标注"回复 @谁"
// ============================================

interface ThreadedComment extends CommunityComment {
  floor: number | null // 顶层楼号；楼中楼为 null
  replyToAuthor: string | null // 楼中楼回复对象
  threadDepth: number // 展示缩进层级（0 = 顶层）
}

function buildThread(comments: CommunityComment[]): ThreadedComment[] {
  const byId = new Map(comments.map(c => [c.id, c]))
  const childrenOf = new Map<string, CommunityComment[]>()
  const topLevel: CommunityComment[] = []

  for (const c of comments) {
    const pid = c.parent_id || ''
    // parent_id 形如 t1_xxx（回复评论）或 t3_xxx（回复帖子本体）
    if (pid.startsWith('t1_') && byId.has(pid.slice(3))) {
      const parentId = pid.slice(3)
      if (!childrenOf.has(parentId)) childrenOf.set(parentId, [])
      childrenOf.get(parentId)!.push(c)
    } else {
      topLevel.push(c)
    }
  }

  const byTime = (a: CommunityComment, b: CommunityComment) =>
    (a.created_utc ?? 0) - (b.created_utc ?? 0)
  topLevel.sort(byTime)

  const out: ThreadedComment[] = []
  const walk = (c: CommunityComment, threadDepth: number, replyTo: string | null, floor: number | null) => {
    out.push({ ...c, floor, replyToAuthor: replyTo, threadDepth })
    const kids = (childrenOf.get(c.id) ?? []).sort(byTime)
    for (const k of kids) walk(k, threadDepth + 1, c.author, null)
  }
  topLevel.forEach((c, i) => walk(c, 0, null, i + 1))
  return out
}

/**
 * 清洗爬取文本：
 * Reddit selftext 常含零宽字符（&#x200B;）、nbsp、以及只有空格的"伪空行"，
 * 逐行视图里会显示成大量多余空行。
 */
function cleanBody(text: string): string {
  return text
    .replace(/[\u200b\u200c\u200d\u200e\u200f\ufeff]/g, '') // 零宽字符
    .replace(/\u00a0/g, ' ') // nbsp → 普通空格
    .split('\n')
    .map(l => l.replace(/\s+$/, '')) // 行尾空白（含纯空格行 → 空行）
    .join('\n')
    .replace(/\n{3,}/g, '\n\n') // 连续空行压缩为一个
    .trim()
}

/** 帖子 + 楼层化回复 → 纯文本（编辑器/卡片/导出用）
 * 格式：楼层头单独一行，内容另起；顶层楼之间用 --- 分隔 */
function formatPostThreadText(post: CommunityPost, threaded: ThreadedComment[]): string {
  const lines: string[] = [`【${post.title}】 by ${post.author} · ▲${post.score ?? 0}`]
  const body = post.body ? cleanBody(post.body) : ''
  if (body) lines.push('', body)

  if (threaded.length > 0) {
    // 按顶层楼分块
    const blocks: string[] = []
    let cur: string[] = []
    for (const c of threaded) {
      if (c.floor !== null) {
        if (cur.length) blocks.push(cur.join('\n'))
        cur = [`#${c.floor}楼 ${c.author}：`, cleanBody(c.body ?? '')]
      } else {
        const indent = '  '.repeat(c.threadDepth)
        cur.push('', `${indent}↳ ${c.author} 回复 ${c.replyToAuthor}：`, cleanBody(c.body ?? ''))
      }
    }
    if (cur.length) blocks.push(cur.join('\n'))

    lines.push('', `── 回复（${threaded.length} 条）──`, '', blocks.join('\n\n---\n\n'))
  }
  return lines.join('\n')
}

const CommunityPanel: React.FC<CommunityPanelProps> = ({ onCreateCard, onOpenEditor, onTiebaUrl }) => {
  const [url, setUrl] = useState('')
  const [status, setStatus] = useState<PanelStatus>('idle')
  const [error, setError] = useState('')

  // 当前板块
  const [community, setCommunity] = useState<CommunityInfo | null>(null)
  const [resolvedSub, setResolvedSub] = useState<string | null>(null)

  // 同步
  const [syncing, setSyncing] = useState(false)
  const [targetNew, setTargetNew] = useState(100)
  const [syncResult, setSyncResult] = useState<CommunitySyncResult | null>(null)

  // 帖子列表
  const [posts, setPosts] = useState<CommunityPost[]>([])
  const [postsTotal, setPostsTotal] = useState(0)
  const [postsPage, setPostsPage] = useState(1)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [expandedPost, setExpandedPost] = useState<string | null>(null)
  const [comments, setComments] = useState<CommunityComment[]>([])
  const [refreshingComments, setRefreshingComments] = useState(false)

  // 已归档板块列表
  const [communities, setCommunities] = useState<CommunityInfo[]>([])

  const POSTS_PER_PAGE = 30

  // ── 板块列表 ──
  const loadCommunities = useCallback(async () => {
    try {
      const res = await fetch('/api/community/list')
      const data = await res.json()
      if (data.success) setCommunities(data.communities)
    } catch { /* 静默 */ }
  }, [])

  useEffect(() => { loadCommunities() }, [loadCommunities])

  // ── 帖子加载 ──
  const loadPosts = useCallback(async (communityId: string, page: number) => {
    try {
      const res = await fetch(`/api/community/${encodeURIComponent(communityId)}/posts?page=${page}&limit=${POSTS_PER_PAGE}`)
      const data = await res.json()
      if (data.success) {
        setPosts(data.posts)
        setPostsTotal(data.total)
        setPostsPage(page)
      }
    } catch { /* 静默 */ }
  }, [])

  const selectCommunity = useCallback(async (c: CommunityInfo) => {
    setCommunity(c)
    setResolvedSub(c.name)
    setSyncResult(null)
    setSelected(new Set())
    setExpandedPost(null)
    await loadPosts(c.id, 1)
  }, [loadPosts])

  // ── URL 识别 ──
  const handleResolve = useCallback(async () => {
    if (!url.trim()) return
    setStatus('resolving')
    setError('')
    setSyncResult(null)

    try {
      const res = await fetch('/api/community/resolve', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url }),
      })
      const data = await res.json()

      if (!data.success) {
        setError(data.error || '无法识别的链接')
        setStatus('error')
        return
      }

      const resolved: ResolvedSource = data.resolved

      if (resolved.platform === 'tieba') {
        // 贴吧帖子 → 委托给现有爬取流程
        setStatus('idle')
        onTiebaUrl(url)
        return
      }

      if (resolved.platform === 'reddit' && resolved.type === 'subreddit') {
        setResolvedSub(resolved.name!)
        if (data.community) {
          setCommunity(data.community)
          await loadPosts(data.community.id, 1)
        } else {
          setCommunity(null)
          setPosts([])
          setPostsTotal(0)
        }
        setStatus('idle')
        return
      }

      if (resolved.platform === 'reddit' && resolved.type === 'post') {
        setError('Reddit 单帖：请先归档所在板块，再从帖子列表刷新回复')
        setStatus('error')
        return
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '识别失败')
      setStatus('error')
    }
  }, [url, onTiebaUrl, loadPosts])

  // ── 同步 ──
  const handleSync = useCallback(async (direction: SyncDirection) => {
    if (!resolvedSub || syncing) return
    setSyncing(true)
    setSyncResult(null)
    setError('')

    try {
      const res = await fetch(`/api/community/${encodeURIComponent(resolvedSub)}/sync`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ direction, targetNew }),
      })
      const result: CommunitySyncResult = await res.json()
      setSyncResult(result)

      if (result.success) {
        const id = `reddit:${resolvedSub}`
        const statusRes = await fetch(`/api/community/${encodeURIComponent(id)}/status`)
        const statusData = await statusRes.json()
        if (statusData.success) setCommunity(statusData.community)
        await loadPosts(id, 1)
        loadCommunities()
      } else {
        setError(result.error || '同步失败')
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '同步失败')
    } finally {
      setSyncing(false)
    }
  }, [resolvedSub, syncing, targetNew, loadPosts, loadCommunities])

  // ── 评论 ──
  const fetchLocalComments = useCallback(async (postId: string): Promise<CommunityComment[]> => {
    const res = await fetch(`/api/community/post/${encodeURIComponent(postId)}/comments`)
    const data = await res.json()
    return data.success ? data.comments : []
  }, [])

  const refreshRemoteComments = useCallback(async (postId: string): Promise<boolean> => {
    const res = await fetch(`/api/community/post/${encodeURIComponent(postId)}/refresh-comments`, { method: 'POST' })
    const data = await res.json()
    if (!data.success) {
      setError(data.error || '评论刷新失败')
      return false
    }
    return true
  }, [])

  const handleRefreshComments = useCallback(async (postId: string) => {
    setRefreshingComments(true)
    setError('')
    try {
      if (await refreshRemoteComments(postId)) {
        setComments(await fetchLocalComments(postId))
        // 更新列表里该帖的评论刷新时间标记
        setPosts(prev => prev.map(p =>
          p.id === postId ? { ...p, comments_refreshed_at: new Date().toISOString() } : p
        ))
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '评论刷新失败')
    } finally {
      setRefreshingComments(false)
    }
  }, [refreshRemoteComments, fetchLocalComments])

  const handleExpandPost = useCallback(async (post: CommunityPost) => {
    if (expandedPost === post.id) {
      setExpandedPost(null)
      setComments([])
      return
    }
    setExpandedPost(post.id)
    setComments([])
    try {
      const local = await fetchLocalComments(post.id)
      if (local.length === 0 && !post.comments_refreshed_at) {
        // 首次展开且从未抓过 → 自动抓取，无需手动点"刷新回复"
        await handleRefreshComments(post.id)
      } else {
        setComments(local)
      }
    } catch { /* 静默 */ }
  }, [expandedPost, fetchLocalComments, handleRefreshComments])

  // ── 选择 & 卡片 ──
  const toggleSelect = useCallback((id: string) => {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }, [])

  const handleCreateCards = useCallback(() => {
    for (const id of selected) {
      const post = posts.find(p => p.id === id)
      if (!post) continue
      const content = post.body ? `${post.title}\n\n${cleanBody(post.body)}` : (post.title || '')
      onCreateCard(content, 'reddit-post', `r/${resolvedSub} · ${post.title?.slice(0, 40) || post.id}`)
    }
    setSelected(new Set())
  }, [selected, posts, resolvedSub, onCreateCard])

  // 选中帖子 → 各自的"正文 + 楼层化回复"文本段。未抓过回复的帖子先自动抓取。
  const [preparingSelection, setPreparingSelection] = useState<string | null>(null)

  const buildSectionsForSelected = useCallback(async (): Promise<string[]> => {
    const selectedPosts = posts.filter(p => selected.has(p.id))
    const sections: string[] = []

    try {
      for (const post of selectedPosts) {
        let local = await fetchLocalComments(post.id)
        if (local.length === 0 && !post.comments_refreshed_at) {
          setPreparingSelection(`正在抓取回复：${post.title?.slice(0, 30) ?? post.id}`)
          if (await refreshRemoteComments(post.id)) {
            local = await fetchLocalComments(post.id)
            setPosts(prev => prev.map(p =>
              p.id === post.id ? { ...p, comments_refreshed_at: new Date().toISOString() } : p
            ))
          }
        }
        sections.push(formatPostThreadText(post, buildThread(local)))
      }
    } finally {
      setPreparingSelection(null)
    }
    return sections
  }, [posts, selected, fetchLocalComments, refreshRemoteComments])

  const handleOpenEditorWithSelected = useCallback(async () => {
    const sections = await buildSectionsForSelected()
    onOpenEditor(sections.join('\n\n════════\n\n'), { type: 'reddit-post', label: `r/${resolvedSub}` })
  }, [buildSectionsForSelected, resolvedSub, onOpenEditor])

  const handleExportSelected = useCallback(async () => {
    const sections = await buildSectionsForSelected()
    const ts = new Date().toISOString().slice(0, 16).replace(/[T:]/g, '-')
    downloadTextFile(`r_${resolvedSub}_导出_${ts}.md`, sections.join('\n\n---\n\n'))
  }, [buildSectionsForSelected, resolvedSub])

  const totalPages = Math.max(1, Math.ceil(postsTotal / POSTS_PER_PAGE))

  return (
    <div className="tieba-area">
      {/* URL 输入 */}
      <input
        className="tieba-url-input"
        type="text"
        placeholder="粘贴 Reddit 板块 / 贴吧帖子链接..."
        value={url}
        onChange={(e) => setUrl(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            handleResolve()
          }
        }}
      />
      <div className="tieba-config">
        <button
          className="add-card-btn"
          onClick={handleResolve}
          disabled={!url.trim() || status === 'resolving'}
        >
          识别链接
        </button>
      </div>

      {error && (
        <div style={{ marginTop: 8, fontSize: 12, color: 'var(--error-color)' }}>❌ {error}</div>
      )}

      {/* 板块仪表盘 */}
      {resolvedSub && (
        <div style={{ marginTop: 12, padding: 10, border: '1px solid var(--border-color)', borderRadius: 6 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <strong>r/{resolvedSub}</strong>
            <span style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>
              {community ? `本地已知 ${community.post_count} 条` : '尚未归档'}
            </span>
          </div>
          <div style={{ fontSize: 11, color: 'var(--text-tertiary)', marginTop: 4 }}>
            上次同步：{formatTime(community?.last_sync_at ?? null)}
            {community?.backfill_done ? ' · 历史已抓完' : ''}
          </div>

          <div style={{ display: 'flex', gap: 8, marginTop: 10, alignItems: 'center' }}>
            <label style={{ fontSize: 11 }}>
              目标新帖数
              <input
                type="number"
                min={10}
                max={1000}
                value={targetNew}
                onChange={(e) => setTargetNew(Math.max(1, Number(e.target.value) || 100))}
                style={{ width: 60, marginLeft: 6 }}
              />
            </label>
          </div>
          <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
            <button className="add-card-btn" style={{ flex: 1 }} disabled={syncing} onClick={() => handleSync('latest')}>
              获取最新
            </button>
            <button
              className="add-card-btn"
              style={{ flex: 1 }}
              disabled={syncing || !!community?.backfill_done}
              onClick={() => handleSync('backfill')}
            >
              向历史回溯
            </button>
          </div>

          {syncing && (
            <div className="transcription-status" style={{ marginTop: 10 }}>
              <div className="status-spinner" />
              正在同步 r/{resolvedSub}，请稍候...
            </div>
          )}

          {syncResult?.success && (
            <div style={{ marginTop: 8, fontSize: 12 }}>
              扫描 {syncResult.scanned} 条 · 新增 {syncResult.newCount} 条 · 跳过已知 {syncResult.skipped} 条 · 本地累计 {syncResult.cumulative} 条
            </div>
          )}
        </div>
      )}

      {/* 帖子列表 */}
      {posts.length > 0 && (
        <>
          <div className="tieba-result-header" style={{ marginTop: 12 }}>
            <strong>已归档帖子</strong>
            <span style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>
              共 {postsTotal} 条 · 第 {postsPage}/{totalPages} 页
            </span>
          </div>

          <div className="tieba-post-list">
            {posts.map((post) => (
              <div key={post.id} className={`tieba-post-item ${selected.has(post.id) ? 'selected' : ''}`}>
                <input
                  type="checkbox"
                  checked={selected.has(post.id)}
                  onChange={() => toggleSelect(post.id)}
                />
                <div className="tieba-post-body">
                  <div className="tieba-post-header">
                    <span className="tieba-username">{post.author}</span>
                    <span style={{ fontSize: 10, color: 'var(--text-tertiary)' }}>
                      ▲{post.score ?? 0} · {post.comment_count ?? 0}评论 · {formatUtc(post.created_utc)}
                    </span>
                  </div>
                  <div
                    className="tieba-post-content"
                    style={{ cursor: 'pointer' }}
                    onClick={() => handleExpandPost(post)}
                    title="点击展开/收起"
                  >
                    <strong>{post.title}</strong>
                    {expandedPost !== post.id && post.body
                      ? ` — ${post.body.slice(0, 80)}${post.body.length > 80 ? '...' : ''}`
                      : ''}
                  </div>

                  {expandedPost === post.id && (
                    <div style={{ marginTop: 6, fontSize: 12 }}>
                      {post.body && <div style={{ whiteSpace: 'pre-wrap', marginBottom: 8 }}>{cleanBody(post.body)}</div>}
                      <button
                        className="add-card-btn"
                        disabled={refreshingComments}
                        onClick={() => handleRefreshComments(post.id)}
                      >
                        {refreshingComments
                          ? '抓取回复中...'
                          : post.comments_refreshed_at ? '刷新回复（增量）' : '抓取回复'}
                      </button>

                      {!refreshingComments && comments.length === 0 && post.comments_refreshed_at && (
                        <div style={{ marginTop: 6, fontSize: 11, color: 'var(--text-tertiary)' }}>暂无回复</div>
                      )}

                      {comments.length > 0 && (
                        <div style={{ marginTop: 8, maxHeight: 320, overflowY: 'auto' }}>
                          {buildThread(comments).map(c => (
                            <div
                              key={c.id}
                              style={{
                                marginLeft: c.threadDepth * 14,
                                padding: '4px 6px',
                                borderLeft: c.threadDepth > 0
                                  ? '2px solid var(--border-color)'
                                  : '2px solid var(--accent-color, #6BA5E7)',
                                marginBottom: 4,
                                fontSize: 11,
                              }}
                            >
                              <div style={{ color: 'var(--text-tertiary)', display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                                {c.floor !== null ? (
                                  <span style={{ fontWeight: 600 }}>#{c.floor}楼</span>
                                ) : (
                                  <span>↳ 回复 {c.replyToAuthor}</span>
                                )}
                                <span style={{ fontWeight: c.floor !== null ? 600 : 400 }}>{c.author}</span>
                                <span>▲{c.score ?? 0}</span>
                                <span>{formatUtcTime(c.created_utc ?? null)}</span>
                                <button
                                  style={{ fontSize: 10 }}
                                  onClick={() =>
                                    onCreateCard(
                                      `${c.author}${c.replyToAuthor ? ` 回复 ${c.replyToAuthor}` : ''}：\n${cleanBody(c.body || '')}`,
                                      'reddit-comment',
                                      `r/${resolvedSub} · ${post.title?.slice(0, 30)} ${c.floor !== null ? `#${c.floor}楼` : '楼中楼'}`
                                    )
                                  }
                                >
                                  +卡片盒
                                </button>
                              </div>
                              <div style={{ whiteSpace: 'pre-wrap', marginTop: 2 }}>{cleanBody(c.body || '')}</div>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>

          {/* 分页 */}
          {totalPages > 1 && (
            <div style={{ display: 'flex', gap: 8, marginTop: 8, justifyContent: 'center', fontSize: 12 }}>
              <button disabled={postsPage <= 1} onClick={() => community && loadPosts(community.id, postsPage - 1)}>上一页</button>
              <button disabled={postsPage >= totalPages} onClick={() => community && loadPosts(community.id, postsPage + 1)}>下一页</button>
            </div>
          )}

          {/* 多选状态栏 */}
          {selected.size > 0 && (
            <div
              style={{
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                marginTop: 8, padding: '4px 8px', fontSize: 12,
                background: 'var(--bg-tertiary, #f0f0f0)', borderRadius: 4,
              }}
            >
              <span>已选 <strong>{selected.size}</strong> 条</span>
              <button style={{ fontSize: 11 }} onClick={() => setSelected(new Set())}>清空多选</button>
            </div>
          )}

          {/* 批量操作 */}
          <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
            <button className="add-card-btn" style={{ flex: 1 }} disabled={selected.size === 0} onClick={handleCreateCards}>
              存到卡片盒 ({selected.size})
            </button>
            <button
              className="add-card-btn"
              style={{ flex: 1 }}
              disabled={selected.size === 0 || preparingSelection !== null}
              onClick={handleOpenEditorWithSelected}
            >
              {preparingSelection !== null ? '准备中...' : '打开编辑器（含回复）'}
            </button>
            <button
              className="add-card-btn"
              style={{ flex: 1 }}
              disabled={selected.size === 0 || preparingSelection !== null}
              onClick={handleExportSelected}
              title="导出所选帖子（含回复）为 Markdown 文件"
            >
              导出所选 (.md)
            </button>
          </div>
          {preparingSelection && (
            <div className="transcription-status" style={{ marginTop: 8, fontSize: 11 }}>
              <div className="status-spinner" />
              {preparingSelection}
            </div>
          )}
        </>
      )}

      {/* 已归档板块 */}
      {communities.length > 0 && (
        <div style={{ marginTop: 16 }}>
          <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6 }}>已归档板块</div>
          {communities.map(c => (
            <div
              key={c.id}
              onClick={() => selectCommunity(c)}
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                padding: '6px 8px',
                fontSize: 12,
                cursor: 'pointer',
                borderRadius: 4,
                background: community?.id === c.id ? 'var(--bg-tertiary, #eee)' : 'transparent',
              }}
            >
              <span>{c.platform === 'reddit' ? `r/${c.name}` : c.name}</span>
              <span style={{ color: 'var(--text-tertiary)' }}>{c.post_count} 条 · {formatTime(c.last_sync_at)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export default React.memo(CommunityPanel)
