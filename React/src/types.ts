// ============================================
// Canvas / Whiteboard Types
// ============================================

export type ConversationRole = 'user' | 'assistant' | 'system'

export interface ConversationDiffSpan {
  text: string
  kind: 'same' | 'added' | 'removed'
}

export interface ConversationBlock {
  id: string
  role: ConversationRole
  name: string
  text: string
  messageIndex: number
  paragraphIndex: number
  timestamp?: string
  model?: string
  swipeIndex?: number
  selectedSwipe?: boolean
  sourceFiles?: string[]
  diff?: ConversationDiffSpan[]
}

export interface ConversationBranchAnchor {
  branchCardId: string
  startBlockId?: string
  endBlockId?: string
  sourceFiles: string[]
}

export interface ConversationCardData {
  format: 'sillytavern'
  segmentKind: 'backbone' | 'branch'
  segmentId: string
  sourceFiles: string[]
  blocks: ConversationBlock[]
  branchPath: string[]
  parentCardId?: string
  replacedBlockIds?: string[]
  branchAnchors?: ConversationBranchAnchor[]
}

export interface CardAnchor {
  blockId?: string
  edge: 'left' | 'right'
  boundary: 'top' | 'center' | 'bottom'
}

export interface Card {
  id: string
  content: string // Markdown 全文；卡片预览由渲染层截断派生
  x: number
  y: number
  width: number | null
  height: number | null
  color: string | null
  bgColor: string | null
  textColor: string | null
  sizePreset: 'default' | 'tall' | 'wide' | 'large'
  tags: string[]
  zIndex: number
  sourceType: 'import' | 'clipboard' | 'manual' | 'transcription' | 'youtube' | 'tieba' | 'reddit-post' | 'reddit-comment' | 'sillytavern'
  sourceLabel?: string
  createdAt: string
  /** 内容类型：text/image/note；note 由 noteRef 指向独立连续笔记文档。 */
  kind?: 'text' | 'image' | 'note' | 'ink' | 'conversation'
  /**
   * 图片卡片（kind='image'）的图片引用：插件图片目录下的文件名（不含路径），
   * 像素字节不进文档，由各端自己的图片目录按文件名解析。
   */
  imageRef?: string
  /** 独立笔记文档引用；像素预览由原生 NoteDocument 生成。 */
  noteRef?: string
  /** 笔记卡片标题（转笔记时由文字卡首行/标题行填充；更多菜单列表与笔记顶栏展示）。 */
  title?: string
  /** 对话卡片的结构化内容；content 同时保留 Markdown 降级文本。 */
  conversation?: ConversationCardData
  /** 白板关系角色；缺省卡片按独立子卡处理。 */
  role?: 'parent' | 'child'
  /** 子卡指向当前仍存在的母卡。 */
  parentId?: string
  /** 母卡当前生成并保留在白板上的子卡 ID。 */
  childIds?: string[]
  /** 母卡正文中已经拆出的区间。 */
  extractedRanges?: Array<{
    startLine: number
    endLine: number
    childCardId: string
  }>
  /** 互通格式前向兼容袋：解码时收容未知字段，回写时原样恢复；应用层不消费 */
  ext?: Record<string, unknown>
}

export interface Connection {
  id: string
  fromCardId: string
  toCardId: string
  color: string
  label: string
  /** Once connector ink exists, keep the connection relationship locked. */
  locked?: boolean
  /** 对话分支使用卡片内部段落锚点；普通连接缺省为 default。 */
  kind?: 'default' | 'conversation-branch' | 'conversation-return'
  fromAnchor?: CardAnchor
  toAnchor?: CardAnchor
  /** 互通格式前向兼容袋；应用层不消费 */
  ext?: Record<string, unknown>
}

export interface Viewport {
  panX: number
  panY: number
  scale: number
}

export interface CanvasData {
  cards: Record<string, Card>
  connections: Connection[]
  viewport: Viewport
}

export interface CanvasInfo {
  id: string
  name: string
  createdAt: string
  updatedAt: string
}

export interface CanvasRegistry {
  canvasList: CanvasInfo[]
  activeCanvasId: string | null
}

// ============================================
// Store Types
// ============================================

export interface Store<T extends object> {
  getState: () => T
  setState: (partial: Partial<T> | ((state: T) => Partial<T>)) => void
  subscribe: (listener: () => void) => () => void
}

// ============================================
// Size Presets
// ============================================

export const SIZE_PRESETS = {
  default: { width: 320, height: 280, label: 'S' },
  tall:    { width: 320, height: 440, label: 'M' },
  wide:    { width: 520, height: 280, label: 'W' },
  large:   { width: 520, height: 440, label: 'L' },
} as const

export type SizePresetKey = keyof typeof SIZE_PRESETS

// ============================================
// Transcription Types
// ============================================

export interface TranscriptionRecord {
  id: string
  originalFilename: string
  docxFilename: string
  text: string
  segments: string[]
  createdAt: string
}

export interface Boundary {
  type: 'upper' | 'lower'
  lineIndex: number
}

export interface TextSegment {
  content: string
  startLine: number
  endLine: number
}

export interface ParentCard {
  id: string
  canvasId: string
  content: string
  createdAt: string
  status: 'draft' | 'saved'
  savedAt?: string
  sourceChildIds: string[]
  sourceBoxCardId?: string
  sourceType?: Card['sourceType']
  sourceLabel?: string
  x: number
  y: number
  width: number
  height: number
  extractedRanges: Array<{
    startLine: number
    endLine: number
    childCardId: string
  }>
}

// ============================================
// Tieba Types
// ============================================

export interface TiebaPost {
  floor: number
  username: string
  content: string
  score: number
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

// ============================================
// Community (板块归档) Types
// ============================================

export interface CommunityInfo {
  id: string
  platform: string
  name: string
  url: string
  post_count: number
  last_sync_at: string | null
  backfill_done: number
}

export interface CommunitySyncResult {
  success: boolean
  scanned: number
  newCount: number
  skipped: number
  cumulative: number
  backfillDone: boolean
  error?: string
}

export interface CommunityPost {
  id: string
  community_id: string
  platform: string
  title: string | null
  author: string | null
  body: string | null
  permalink: string | null
  score: number | null
  comment_count: number | null
  flair: string | null
  created_utc: number | null
  comments_refreshed_at: string | null
}

export interface CommunityComment {
  id: string
  post_id: string
  parent_id: string | null
  author: string | null
  body: string | null
  score: number | null
  depth: number | null
  created_utc: number | null
}
