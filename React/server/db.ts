/**
 * SQLite 数据层 — 板块增量归档系统
 * 使用 Node 22+ 内置 node:sqlite（零依赖，无需 npm install）
 */

import { DatabaseSync, type SQLInputValue } from 'node:sqlite'
import path from 'path'
import { mkdirSync } from 'fs'

// ============================================
// Types — Card Box
// ============================================

export interface BoxRow {
  id: string
  name: string
  description: string
  created_at: string
  updated_at: string
}

export interface BoxCardRow {
  id: string
  box_id: string
  title: string | null
  content: string | null
  preview: string | null
  source_type: string | null
  source_label: string | null
  source_url: string | null
  tags: string // JSON array
  author: string | null
  preferred_width: number | null
  preferred_height: number | null
  created_at: string
  updated_at: string
}

// ============================================
// Types — Community
// ============================================

export interface CommunityRow {
  id: string
  platform: string
  name: string
  url: string
  post_count: number
  last_sync_at: string | null
  newest_cursor: string | null
  oldest_cursor: string | null
  backfill_done: number
  created_at: string
}

export interface PostRow {
  id: string
  community_id: string
  platform: string
  title: string | null
  author: string | null
  body: string | null
  permalink: string | null
  score: number | null
  upvote_ratio: number | null
  comment_count: number | null
  flair: string | null
  created_utc: number | null
  fetched_at: string
  updated_at: string
  comments_refreshed_at: string | null
}

export interface CommentRow {
  id: string
  post_id: string
  parent_id: string | null
  author: string | null
  body: string | null
  score: number | null
  depth: number | null
  is_submitter: number | null
  created_utc: number | null
  permalink: string | null
  fetched_at: string
  updated_at: string
  deleted: number
}

export interface NewPost {
  id: string
  title?: string | null
  author?: string | null
  body?: string | null
  permalink?: string | null
  score?: number | null
  upvoteRatio?: number | null
  commentCount?: number | null
  flair?: string | null
  createdUtc?: number | null
}

export interface NewComment {
  id: string
  parentId?: string | null
  author?: string | null
  body?: string | null
  score?: number | null
  depth?: number | null
  isSubmitter?: boolean | null
  createdUtc?: number | null
  permalink?: string | null
}

// ============================================
// 初始化 & 迁移
// ============================================

const DATA_DIR = path.resolve(process.cwd(), 'data')
const DB_PATH = path.join(DATA_DIR, 'community.db')

const MIGRATIONS: string[] = [
  // v1: 初始 schema
  `
  CREATE TABLE communities (
    id                TEXT PRIMARY KEY,
    platform          TEXT NOT NULL,
    name              TEXT NOT NULL,
    url               TEXT NOT NULL,
    post_count        INTEGER NOT NULL DEFAULT 0,
    last_sync_at      TEXT,
    newest_cursor     TEXT,
    oldest_cursor     TEXT,
    backfill_done     INTEGER NOT NULL DEFAULT 0,
    created_at        TEXT NOT NULL DEFAULT (datetime('now'))
  );

  CREATE TABLE posts (
    id                TEXT PRIMARY KEY,
    community_id      TEXT NOT NULL REFERENCES communities(id),
    platform          TEXT NOT NULL,
    title             TEXT,
    author            TEXT,
    body              TEXT,
    permalink         TEXT,
    score             INTEGER,
    upvote_ratio      REAL,
    comment_count     INTEGER,
    flair             TEXT,
    created_utc       REAL,
    fetched_at        TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at        TEXT NOT NULL DEFAULT (datetime('now')),
    comments_refreshed_at TEXT
  );
  CREATE INDEX idx_posts_community ON posts(community_id);
  CREATE INDEX idx_posts_created ON posts(community_id, created_utc DESC);

  CREATE TABLE comments (
    id                TEXT PRIMARY KEY,
    post_id           TEXT NOT NULL REFERENCES posts(id),
    parent_id         TEXT,
    author            TEXT,
    body              TEXT,
    score             INTEGER,
    depth             INTEGER,
    is_submitter      INTEGER,
    created_utc       REAL,
    permalink         TEXT,
    fetched_at        TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at        TEXT NOT NULL DEFAULT (datetime('now')),
    deleted           INTEGER NOT NULL DEFAULT 0
  );
  CREATE INDEX idx_comments_post ON comments(post_id);

  CREATE TABLE sync_log (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    community_id      TEXT NOT NULL,
    direction         TEXT NOT NULL,
    scanned           INTEGER DEFAULT 0,
    new_count         INTEGER DEFAULT 0,
    skipped           INTEGER DEFAULT 0,
    started_at        TEXT NOT NULL DEFAULT (datetime('now')),
    finished_at       TEXT,
    status            TEXT NOT NULL DEFAULT 'running'
  );
  `,

  // v2: 卡片盒
  `
  CREATE TABLE boxes (
    id                TEXT PRIMARY KEY,
    name              TEXT NOT NULL,
    description       TEXT NOT NULL DEFAULT '',
    created_at        TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at        TEXT NOT NULL DEFAULT (datetime('now'))
  );

  CREATE TABLE box_cards (
    id                TEXT PRIMARY KEY,
    box_id            TEXT NOT NULL REFERENCES boxes(id) ON DELETE CASCADE,
    title             TEXT,
    content           TEXT,
    preview           TEXT,
    source_type       TEXT,
    source_label      TEXT,
    source_url        TEXT,
    tags              TEXT NOT NULL DEFAULT '[]',
    author            TEXT,
    created_at        TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at        TEXT NOT NULL DEFAULT (datetime('now'))
  );
  CREATE INDEX idx_box_cards_box ON box_cards(box_id);
  CREATE INDEX idx_box_cards_source ON box_cards(source_type);
  `,

  // v3: 摘录在来源中的视觉尺寸，放入白板时作为推荐尺寸
  `
  ALTER TABLE box_cards ADD COLUMN preferred_width REAL;
  ALTER TABLE box_cards ADD COLUMN preferred_height REAL;
  `,

  // v4: 局域网共享白板与笔迹盒
  `
  CREATE TABLE shared_boards (
    board_id            TEXT PRIMARY KEY,
    revision            INTEGER NOT NULL DEFAULT 0,
    board_blob          BLOB NOT NULL,
    updated_at          TEXT NOT NULL DEFAULT (datetime('now'))
  );

  CREATE TABLE ink_box_items (
    id                  TEXT PRIMARY KEY,
    source_board_id     TEXT NOT NULL,
    source_card_id      TEXT NOT NULL,
    source_card_content TEXT NOT NULL DEFAULT '',
    width               REAL NOT NULL,
    height              REAL NOT NULL,
    bounds              TEXT NOT NULL,
    stroke_count        INTEGER NOT NULL,
    strokes_blob        BLOB NOT NULL,
    reason              TEXT NOT NULL,
    created_at          TEXT NOT NULL DEFAULT (datetime('now'))
  );
  CREATE INDEX idx_ink_box_board ON ink_box_items(source_board_id, created_at DESC);
  `,
]

let db: DatabaseSync | null = null

export function getDb(): DatabaseSync {
  if (db) return db
  mkdirSync(DATA_DIR, { recursive: true })
  db = new DatabaseSync(DB_PATH)
  db.exec('PRAGMA journal_mode = WAL')
  db.exec('PRAGMA foreign_keys = ON')

  const row = db.prepare('PRAGMA user_version').get() as { user_version: number }
  let version = row.user_version
  while (version < MIGRATIONS.length) {
    db.exec(MIGRATIONS[version])
    version++
    db.exec(`PRAGMA user_version = ${version}`)
    console.log(`[DB] 迁移至 schema v${version}`)
  }
  return db
}

// ============================================
// Communities
// ============================================

export function communityId(platform: string, name: string): string {
  return `${platform}:${name}`
}

export function upsertCommunity(platform: string, name: string, url: string): CommunityRow {
  const d = getDb()
  const id = communityId(platform, name)
  d.prepare(`
    INSERT INTO communities (id, platform, name, url)
    VALUES (?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET url = excluded.url
  `).run(id, platform, name, url)
  return getCommunity(id)!
}

export function getCommunity(id: string): CommunityRow | null {
  const d = getDb()
  return (d.prepare('SELECT * FROM communities WHERE id = ?').get(id) as CommunityRow | undefined) ?? null
}

export function listCommunities(): CommunityRow[] {
  const d = getDb()
  return d.prepare('SELECT * FROM communities ORDER BY last_sync_at DESC').all() as unknown as CommunityRow[]
}

export function updateCursor(id: string, direction: 'latest' | 'backfill', cursor: string | null): void {
  const d = getDb()
  const col = direction === 'latest' ? 'newest_cursor' : 'oldest_cursor'
  d.prepare(`UPDATE communities SET ${col} = ? WHERE id = ?`).run(cursor, id)
}

export function markBackfillDone(id: string): void {
  getDb().prepare('UPDATE communities SET backfill_done = 1 WHERE id = ?').run(id)
}

export function touchCommunitySync(id: string): void {
  const d = getDb()
  d.prepare(`
    UPDATE communities SET
      last_sync_at = datetime('now'),
      post_count = (SELECT COUNT(*) FROM posts WHERE community_id = ?)
    WHERE id = ?
  `).run(id, id)
}

// ============================================
// Posts
// ============================================

export function postExists(id: string): boolean {
  const d = getDb()
  return !!d.prepare('SELECT 1 FROM posts WHERE id = ?').get(id)
}

/** 批量插入，重复 id 自动跳过。返回实际插入数量。 */
export function insertPostsBatch(communityIdVal: string, platform: string, posts: NewPost[]): number {
  const d = getDb()
  const stmt = d.prepare(`
    INSERT OR IGNORE INTO posts
      (id, community_id, platform, title, author, body, permalink, score, upvote_ratio, comment_count, flair, created_utc)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `)
  let inserted = 0
  d.exec('BEGIN')
  try {
    for (const p of posts) {
      const result = stmt.run(
        p.id, communityIdVal, platform,
        p.title ?? null, p.author ?? null, p.body ?? null, p.permalink ?? null,
        p.score ?? null, p.upvoteRatio ?? null, p.commentCount ?? null,
        p.flair ?? null, p.createdUtc ?? null
      )
      inserted += Number(result.changes)
    }
    d.exec('COMMIT')
  } catch (e) {
    d.exec('ROLLBACK')
    throw e
  }
  return inserted
}

export function getPostsPage(communityIdVal: string, page: number, limit: number): { posts: PostRow[]; total: number } {
  const d = getDb()
  const total = (d.prepare('SELECT COUNT(*) AS c FROM posts WHERE community_id = ?').get(communityIdVal) as { c: number }).c
  const posts = d.prepare(`
    SELECT * FROM posts WHERE community_id = ?
    ORDER BY created_utc DESC
    LIMIT ? OFFSET ?
  `).all(communityIdVal, limit, (page - 1) * limit) as unknown as PostRow[]
  return { posts, total }
}

export function getPost(id: string): PostRow | null {
  return (getDb().prepare('SELECT * FROM posts WHERE id = ?').get(id) as PostRow | undefined) ?? null
}

// ============================================
// Comments
// ============================================

/** 增量写入评论：新评论插入，已有评论更新分数/内容；返回 { added, updated }。 */
export function upsertCommentsBatch(postId: string, comments: NewComment[]): { added: number; updated: number } {
  const d = getDb()
  const insertStmt = d.prepare(`
    INSERT INTO comments (id, post_id, parent_id, author, body, score, depth, is_submitter, created_utc, permalink)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET
      body = excluded.body,
      score = excluded.score,
      updated_at = datetime('now')
  `)
  const existsStmt = d.prepare('SELECT 1 FROM comments WHERE id = ?')

  let added = 0
  let updated = 0
  d.exec('BEGIN')
  try {
    for (const c of comments) {
      const existed = !!existsStmt.get(c.id)
      insertStmt.run(
        c.id, postId, c.parentId ?? null, c.author ?? null, c.body ?? null,
        c.score ?? null, c.depth ?? null, c.isSubmitter ? 1 : 0,
        c.createdUtc ?? null, c.permalink ?? null
      )
      if (existed) updated++
      else added++
    }
    // 更新帖子的评论刷新时间
    d.prepare(`UPDATE posts SET comments_refreshed_at = datetime('now') WHERE id = ?`).run(postId)
    d.exec('COMMIT')
  } catch (e) {
    d.exec('ROLLBACK')
    throw e
  }
  return { added, updated }
}

export function getPostComments(postId: string): CommentRow[] {
  return getDb().prepare(
    'SELECT * FROM comments WHERE post_id = ? ORDER BY created_utc ASC'
  ).all(postId) as unknown as CommentRow[]
}

// ============================================
// Sync log
// ============================================

export function startSyncLog(communityIdVal: string, direction: string): number {
  const result = getDb().prepare(
    'INSERT INTO sync_log (community_id, direction) VALUES (?, ?)'
  ).run(communityIdVal, direction)
  return Number(result.lastInsertRowid)
}

export function finishSyncLog(logId: number, stats: { scanned: number; newCount: number; skipped: number }, status: 'done' | 'error' = 'done'): void {
  getDb().prepare(`
    UPDATE sync_log SET scanned = ?, new_count = ?, skipped = ?, finished_at = datetime('now'), status = ?
    WHERE id = ?
  `).run(stats.scanned, stats.newCount, stats.skipped, status, logId)
}

// ============================================
// Card Box — Boxes
// ============================================

function genId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
}

export function listBoxes(): (BoxRow & { card_count: number })[] {
  return getDb().prepare(`
    SELECT b.*, COALESCE(c.cnt, 0) AS card_count
    FROM boxes b
    LEFT JOIN (SELECT box_id, COUNT(*) AS cnt FROM box_cards GROUP BY box_id) c ON c.box_id = b.id
    ORDER BY b.updated_at DESC
  `).all() as unknown as (BoxRow & { card_count: number })[]
}

export function getBox(id: string): BoxRow | null {
  return (getDb().prepare('SELECT * FROM boxes WHERE id = ?').get(id) as BoxRow | undefined) ?? null
}

export function createBox(name: string, description = ''): BoxRow {
  const id = 'box_' + genId()
  getDb().prepare('INSERT INTO boxes (id, name, description) VALUES (?, ?, ?)').run(id, name, description)
  return getBox(id)!
}

/** 统一采集入口；固定 ID 避免按名称误认用户自己创建的盒子 */
export function getOrCreateInboxBox(): BoxRow {
  const existing = getBox('box_inbox')
  if (existing) return existing
  getDb().prepare(`
    INSERT INTO boxes (id, name, description)
    VALUES ('box_inbox', '收集箱', '划选文字与快速采集内容的默认入口')
  `).run()
  return getBox('box_inbox')!
}

export function updateBox(id: string, fields: { name?: string; description?: string }): BoxRow | null {
  const d = getDb()
  const sets: string[] = []
  const vals: SQLInputValue[] = []
  if (fields.name !== undefined) { sets.push('name = ?'); vals.push(fields.name) }
  if (fields.description !== undefined) { sets.push('description = ?'); vals.push(fields.description) }
  if (sets.length === 0) return getBox(id)
  sets.push("updated_at = datetime('now')")
  vals.push(id)
  d.prepare(`UPDATE boxes SET ${sets.join(', ')} WHERE id = ?`).run(...vals)
  return getBox(id)
}

export function deleteBox(id: string): boolean {
  const r = getDb().prepare('DELETE FROM boxes WHERE id = ?').run(id)
  return Number(r.changes) > 0
}

// ============================================
// Card Box — Cards
// ============================================

export interface NewBoxCard {
  title?: string | null
  content?: string | null
  preview?: string | null
  sourceType?: string | null
  sourceLabel?: string | null
  sourceUrl?: string | null
  tags?: string[]
  author?: string | null
  preferredWidth?: number | null
  preferredHeight?: number | null
}

export function listBoxCards(boxId: string, opts?: { sourceType?: string; tag?: string; q?: string }): BoxCardRow[] {
  const d = getDb()
  const clauses = ['box_id = ?']
  const vals: SQLInputValue[] = [boxId]

  if (opts?.sourceType) { clauses.push('source_type = ?'); vals.push(opts.sourceType) }
  if (opts?.tag) { clauses.push("tags LIKE '%' || ? || '%'"); vals.push(opts.tag) }
  if (opts?.q) { clauses.push("(title LIKE '%' || ? || '%' OR content LIKE '%' || ? || '%')"); vals.push(opts.q, opts.q) }

  return d.prepare(`SELECT * FROM box_cards WHERE ${clauses.join(' AND ')} ORDER BY created_at DESC`)
    .all(...vals) as unknown as BoxCardRow[]
}

export function getBoxCard(id: string): BoxCardRow | null {
  return (getDb().prepare('SELECT * FROM box_cards WHERE id = ?').get(id) as BoxCardRow | undefined) ?? null
}

export function createBoxCard(boxId: string, card: NewBoxCard): BoxCardRow {
  const id = 'bc_' + genId()
  const tags = JSON.stringify(card.tags ?? [])
  getDb().prepare(`
    INSERT INTO box_cards (
      id, box_id, title, content, preview, source_type, source_label, source_url,
      tags, author, preferred_width, preferred_height
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(id, boxId, card.title ?? null, card.content ?? null, card.preview ?? null,
    card.sourceType ?? null, card.sourceLabel ?? null, card.sourceUrl ?? null, tags, card.author ?? null,
    card.preferredWidth ?? null, card.preferredHeight ?? null)
  // touch box
  getDb().prepare("UPDATE boxes SET updated_at = datetime('now') WHERE id = ?").run(boxId)
  return getBoxCard(id)!
}

export function updateBoxCard(id: string, fields: Partial<NewBoxCard>): BoxCardRow | null {
  const d = getDb()
  const sets: string[] = []
  const vals: SQLInputValue[] = []
  const map: Record<string, string> = {
    title: 'title', content: 'content', preview: 'preview',
    sourceType: 'source_type', sourceLabel: 'source_label', sourceUrl: 'source_url', author: 'author',
    preferredWidth: 'preferred_width', preferredHeight: 'preferred_height',
  }
  for (const [k, col] of Object.entries(map)) {
    if ((fields as any)[k] !== undefined) { sets.push(`${col} = ?`); vals.push((fields as any)[k]) }
  }
  if (fields.tags !== undefined) { sets.push('tags = ?'); vals.push(JSON.stringify(fields.tags)) }
  if (sets.length === 0) return getBoxCard(id)
  sets.push("updated_at = datetime('now')")
  vals.push(id)
  d.prepare(`UPDATE box_cards SET ${sets.join(', ')} WHERE id = ?`).run(...vals)
  // touch parent box
  const card = getBoxCard(id)
  if (card) d.prepare("UPDATE boxes SET updated_at = datetime('now') WHERE id = ?").run(card.box_id)
  return card
}

export function deleteBoxCard(id: string): boolean {
  const card = getBoxCard(id)
  const r = getDb().prepare('DELETE FROM box_cards WHERE id = ?').run(id)
  if (card) getDb().prepare("UPDATE boxes SET updated_at = datetime('now') WHERE id = ?").run(card.box_id)
  return Number(r.changes) > 0
}

export function searchBoxCards(q: string, boxId?: string): BoxCardRow[] {
  const d = getDb()
  const clauses = ["(title LIKE '%' || ? || '%' OR content LIKE '%' || ? || '%')"]
  const vals: SQLInputValue[] = [q, q]
  if (boxId) { clauses.push('box_id = ?'); vals.push(boxId) }
  return d.prepare(`SELECT * FROM box_cards WHERE ${clauses.join(' AND ')} ORDER BY created_at DESC LIMIT 100`)
    .all(...vals) as unknown as BoxCardRow[]
}
