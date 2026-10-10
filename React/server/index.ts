import express, { type Request, type Response } from 'express'
import cors from 'cors'
import multer from 'multer'
import { promises as fs } from 'fs'
import path from 'path'
import { createServer } from 'http'
import { transcribe, readManifest, deleteRecord, type TranscribeResult, loginRequired } from './transcribe.js'
import { crawlTiebaPost, extractPostId, saveTiebaRecord, readTiebaManifest, deleteTiebaRecord } from './tieba.js'
import { resolveSourceUrl } from './source-resolver.js'
import { syncRedditCommunity, refreshPostComments, isSyncBusy } from './community.js'
import { getCommunity, listCommunities, communityId, getPostsPage, getPost, getPostComments,
  listBoxes, getBox, createBox, getOrCreateInboxBox, updateBox, deleteBox,
  listBoxCards, getBoxCard, createBoxCard, updateBoxCard, deleteBoxCard, searchBoxCards } from './db.js'
import { archiveInkBoxItem, attachBoardSync, deleteInkBoxItem, getInkBoxItem, listInkBoxItems } from './boardSync.js'
import {
  deleteVideoAnalysisRecord,
  getActiveVideoAnalysisJob,
  getVideoAnalysisJob,
  listVideoAnalysisHistory,
  startYoutubeAnalysis,
} from './videoAnalysis.js'

const app = express()
const PORT = 3791

// ============================================
// 中间件配置
// ============================================

app.use(cors())

// ── 白板资源（图片卡片像素 / 笔记预览 PNG）：插件局域网同步时上传、网页按引用名取 ──
// 与 mosaic-sync-server.mjs 的 /api/board/assets/<boardId>/<ref> 同协议（PUT 体为 { pngBase64 } 或裸 image/png）。
// 必须注册在全局 express.json() 之前：它默认 100KB 上限，会先把大图拒掉。
const BOARD_ASSETS_DIR = path.resolve(process.cwd(), 'data', 'board-assets')
await fs.mkdir(BOARD_ASSETS_DIR, { recursive: true })
const BOARD_ASSET_ID = /^[a-zA-Z0-9_-]{1,96}$/
const BOARD_ASSET_REF = /^[a-zA-Z0-9_-]{1,96}\.png$/
const MAX_BOARD_ASSET_BYTES = 2 * 1024 * 1024
function boardAssetFile(req: Request): string | null {
  const { boardId, ref } = req.params
  if (!BOARD_ASSET_ID.test(boardId) || !BOARD_ASSET_REF.test(ref)) return null
  return path.join(BOARD_ASSETS_DIR, `${boardId}-${ref}`)
}
app.get('/api/board/assets/:boardId/:ref', async (req: Request, res: Response) => {
  const file = boardAssetFile(req)
  if (file === null) { res.status(404).json({ code: 'route' }); return }
  try {
    const bytes = await fs.readFile(file)
    res.set({ 'Content-Type': 'image/png', 'Cache-Control': 'no-cache' }).send(bytes)
  } catch { res.status(404).json({ code: 'asset' }) }
})
app.put(
  '/api/board/assets/:boardId/:ref',
  express.raw({ type: 'image/png', limit: MAX_BOARD_ASSET_BYTES }),
  express.json({ limit: Math.ceil(MAX_BOARD_ASSET_BYTES * 4 / 3) + 4096 }),
  async (req: Request, res: Response) => {
    const file = boardAssetFile(req)
    if (file === null) { res.status(404).json({ code: 'route' }); return }
    const bytes = Buffer.isBuffer(req.body) ? req.body : Buffer.from(String(req.body?.pngBase64 ?? ''), 'base64')
    if (bytes.length < 8 || bytes.length > MAX_BOARD_ASSET_BYTES || bytes.readUInt32BE(0) !== 0x89504e47) {
      res.status(400).json({ code: 'png' })
      return
    }
    await fs.writeFile(file + '.tmp', bytes)
    await fs.rename(file + '.tmp', file)
    res.json({ ok: true })
  },
)

app.use(express.json())

// 确保 uploads 目录存在
const UPLOADS_DIR = path.resolve(process.cwd(), 'uploads')
await fs.mkdir(UPLOADS_DIR, { recursive: true })

// Multer 文件上传配置（保留原始文件名和扩展名）
const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    cb(null, UPLOADS_DIR)
  },
  filename: (req, file, cb) => {
    // 修复中文文件名编码（multer 默认用 latin1 解码）
    const originalName = Buffer.from(file.originalname, 'latin1').toString('utf8')
    const ext = path.extname(originalName)
    const basename = path.basename(originalName, ext)
    const filename = `${basename}-${Date.now()}${ext}`
    cb(null, filename)
  }
})

const upload = multer({
  storage: storage,
  limits: {
    fileSize: 500 * 1024 * 1024, // 500MB
  },
  fileFilter: (req, file, cb) => {
    // 只接受音视频文件
    const allowedMimes = [
      'audio/',
      'video/',
      'application/octet-stream', // 某些浏览器上传音视频可能是这个类型
    ]
    
    const isAllowed = allowedMimes.some(mime => file.mimetype.startsWith(mime))
    
    if (isAllowed) {
      cb(null, true)
    } else {
      cb(new Error('只支持音频或视频文件'))
    }
  }
})

// ============================================
// 并发控制
// ============================================

let isBusy = false

// ============================================
// API 路由
// ============================================

// 健康检查
app.get('/api/status', (req: Request, res: Response) => {
  res.json({
    status: 'ok',
    busy: isBusy,
    loginRequired,
    timestamp: new Date().toISOString()
  })
})

// 转录接口
app.post('/api/transcribe', upload.single('file'), async (req: Request, res: Response) => {
  // 检查并发
  if (isBusy) {
    return res.status(503).json({
      success: false,
      error: '服务器正在处理其他转录任务，请稍后重试'
    })
  }
  
  // 检查文件
  if (!req.file) {
    return res.status(400).json({
      success: false,
      error: '未上传文件'
    })
  }
  
  const uploadedFilePath = req.file.path
  const originalFileName = Buffer.from(req.file.originalname, 'latin1').toString('utf8')

  console.log(`[Server] 收到转录请求: ${originalFileName} (${(req.file.size / 1024 / 1024).toFixed(2)} MB)`)
  
  // 设置超时（15分钟）
  req.setTimeout(15 * 60 * 1000)
  res.setTimeout(15 * 60 * 1000)
  
  isBusy = true
  
  try {
    // 调用转录服务
    const result: TranscribeResult = await transcribe(uploadedFilePath, originalFileName)
    
    // 返回结果
    res.json(result)
    
    console.log(`[Server] 转录完成: ${result.success ? '成功' : '失败'}`)
    
  } catch (error) {
    console.error('[Server] 转录错误:', error)
    res.status(500).json({
      success: false,
      text: '',
      segments: [],
      error: error instanceof Error ? error.message : '未知错误'
    })
  } finally {
    // 清理临时文件
    try {
      await fs.unlink(uploadedFilePath)
      console.log(`[Server] 已删除临时文件: ${uploadedFilePath}`)
    } catch (err) {
      console.error('[Server] 删除临时文件失败:', err)
    }
    
    isBusy = false
  }
})

// ============================================
// 视频解析（YouTube + StepFun）
// ============================================

app.post('/api/video-analysis/youtube', (req: Request, res: Response) => {
  try {
    const job = startYoutubeAnalysis(req.body ?? {})
    res.status(202).json({ success: true, job })
  } catch (error) {
    res.status(400).json({
      success: false,
      error: error instanceof Error ? error.message : '无法创建视频解析任务',
    })
  }
})

app.get('/api/video-analysis/jobs/:id', (req: Request, res: Response) => {
  const job = getVideoAnalysisJob(req.params.id)
  if (!job) return res.status(404).json({ success: false, error: '任务不存在或服务已重启' })
  res.json({ success: true, job })
})

app.get('/api/video-analysis/active', (_req: Request, res: Response) => {
  res.json({ success: true, job: getActiveVideoAnalysisJob() })
})

app.get('/api/video-analysis/history', async (_req: Request, res: Response) => {
  try {
    res.json({ success: true, records: await listVideoAnalysisHistory() })
  } catch (error) {
    res.status(500).json({ success: false, records: [], error: '读取视频解析历史失败' })
  }
})

app.delete('/api/video-analysis/history/:id', async (req: Request, res: Response) => {
  try {
    if (!await deleteVideoAnalysisRecord(req.params.id)) {
      return res.status(404).json({ success: false, error: '记录不存在' })
    }
    res.json({ success: true })
  } catch {
    res.status(500).json({ success: false, error: '删除失败' })
  }
})

// ============================================
// 转录历史接口
// ============================================

// 获取转录历史列表
app.get('/api/transcriptions', async (req: Request, res: Response) => {
  try {
    const manifest = await readManifest()
    res.json({ records: manifest.records })
  } catch (error) {
    res.status(500).json({ records: [], error: '读取历史记录失败' })
  }
})

// 删除转录记录
app.delete('/api/transcriptions/:id', async (req: Request, res: Response) => {
  try {
    const deleted = await deleteRecord(req.params.id)
    if (deleted) {
      res.json({ success: true })
    } else {
      res.status(404).json({ success: false, error: '记录不存在' })
    }
  } catch (error) {
    res.status(500).json({ success: false, error: '删除失败' })
  }
})

// ============================================
// 贴吧爬取接口
// ============================================

app.post('/api/tieba/crawl', async (req: Request, res: Response) => {
  const { url } = req.body

  if (!url) {
    return res.status(400).json({ success: false, error: '请提供贴吧链接' })
  }

  const postId = extractPostId(url)
  if (!postId) {
    return res.status(400).json({ success: false, error: '无法从链接中提取帖子 ID，请检查链接格式' })
  }

  console.log(`[Server] 收到贴吧爬取请求: postId=${postId}`)

  // 设置超时（10分钟，自动翻页可能较慢）
  req.setTimeout(10 * 60 * 1000)
  res.setTimeout(10 * 60 * 1000)

  try {
    const result = await crawlTiebaPost(postId)

    // 成功时自动保存到历史
    if (result.success) {
      try {
        const record = await saveTiebaRecord(url, result)
        console.log(`[Server] 贴吧记录已保存: ${record.id}`)
      } catch (e) {
        console.error('[Server] 保存贴吧记录失败:', e)
      }
    }

    res.json(result)
    console.log(`[Server] 贴吧爬取完成: ${result.filtered}/${result.total} 楼层`)
  } catch (error) {
    console.error('[Server] 贴吧爬取错误:', error)
    res.status(500).json({
      success: false,
      title: '',
      posts: [],
      total: 0,
      filtered: 0,
      error: error instanceof Error ? error.message : '爬取失败',
    })
  }
})

// ============================================
// 贴吧历史接口
// ============================================

// 获取贴吧爬取历史
app.get('/api/tieba/history', async (req: Request, res: Response) => {
  try {
    const manifest = await readTiebaManifest()
    res.json({ records: manifest.records })
  } catch (error) {
    res.status(500).json({ records: [], error: '读取贴吧历史失败' })
  }
})

// 删除贴吧历史记录
app.delete('/api/tieba/history/:id', async (req: Request, res: Response) => {
  try {
    const deleted = await deleteTiebaRecord(req.params.id)
    if (deleted) {
      res.json({ success: true })
    } else {
      res.status(404).json({ success: false, error: '记录不存在' })
    }
  } catch (error) {
    res.status(500).json({ success: false, error: '删除失败' })
  }
})

// ============================================
// 板块归档接口（Reddit / 社区）
// ============================================

// 统一 URL 识别（Reddit 板块 / Reddit 单帖 / 贴吧帖子）
app.post('/api/community/resolve', (req: Request, res: Response) => {
  const { url } = req.body
  if (!url) {
    return res.status(400).json({ success: false, error: '请提供链接' })
  }

  const resolved = resolveSourceUrl(url)
  if (!resolved) {
    return res.json({ success: false, error: '无法识别的链接格式' })
  }

  // 板块类：附带本地已知状态
  let community = null
  if (resolved.platform === 'reddit' && resolved.type === 'subreddit') {
    community = getCommunity(communityId('reddit', resolved.name))
  }

  res.json({ success: true, resolved, community })
})

// 板块状态
app.get('/api/community/:id/status', (req: Request, res: Response) => {
  const community = getCommunity(req.params.id)
  if (!community) {
    return res.status(404).json({ success: false, error: '板块不存在' })
  }
  res.json({ success: true, community, syncBusy: isSyncBusy() })
})

// 所有已归档板块
app.get('/api/community/list', (req: Request, res: Response) => {
  res.json({ success: true, communities: listCommunities() })
})

// 板块同步（latest = 获取最新 / backfill = 向历史回溯）
app.post('/api/community/:name/sync', async (req: Request, res: Response) => {
  const { direction = 'latest', targetNew = 100 } = req.body
  const name = req.params.name

  if (direction !== 'latest' && direction !== 'backfill') {
    return res.status(400).json({ success: false, error: 'direction 必须是 latest 或 backfill' })
  }

  console.log(`[Server] 板块同步: r/${name} direction=${direction} targetNew=${targetNew}`)
  req.setTimeout(10 * 60 * 1000)
  res.setTimeout(10 * 60 * 1000)

  const result = await syncRedditCommunity(name, direction, Number(targetNew))
  res.json(result)
  console.log(`[Server] 同步完成: 扫描${result.scanned} 新增${result.newCount} 跳过${result.skipped}`)
})

// 板块帖子分页
app.get('/api/community/:id/posts', (req: Request, res: Response) => {
  const page = Math.max(1, Number(req.query.page) || 1)
  const limit = Math.min(100, Number(req.query.limit) || 50)
  const { posts, total } = getPostsPage(req.params.id, page, limit)
  res.json({ success: true, posts, total, page, limit })
})

// 单帖详情
app.get('/api/community/post/:postId', (req: Request, res: Response) => {
  const post = getPost(req.params.postId)
  if (!post) {
    return res.status(404).json({ success: false, error: '帖子不存在' })
  }
  res.json({ success: true, post })
})

// 帖子评论列表（本地）
app.get('/api/community/post/:postId/comments', (req: Request, res: Response) => {
  const post = getPost(req.params.postId)
  if (!post) {
    return res.status(404).json({ success: false, error: '帖子不存在' })
  }
  res.json({ success: true, comments: getPostComments(req.params.postId) })
})

// 刷新帖子评论（增量拉取远端）
app.post('/api/community/post/:postId/refresh-comments', async (req: Request, res: Response) => {
  console.log(`[Server] 刷新评论: ${req.params.postId}`)
  req.setTimeout(5 * 60 * 1000)
  res.setTimeout(5 * 60 * 1000)

  const result = await refreshPostComments(req.params.postId)
  res.json(result)
  console.log(`[Server] 评论刷新完成: 新增${result.added} 更新${result.updated}`)
})

// ============================================
// 卡片盒接口
// ============================================

// 默认收集箱（划选摘录的统一入口）
app.get('/api/box/inbox', (_req: Request, res: Response) => {
  const box = getOrCreateInboxBox()
  res.json({ success: true, box, cards: listBoxCards(box.id) })
})

app.post('/api/box/inbox/cards', (req: Request, res: Response) => {
  const box = getOrCreateInboxBox()
  const card = createBoxCard(box.id, req.body)
  res.json({ success: true, box, card })
})

// 盒子列表
app.get('/api/box/list', (_req: Request, res: Response) => {
  res.json({ success: true, boxes: listBoxes() })
})

// 创建盒子
app.post('/api/box/create', (req: Request, res: Response) => {
  const { name, description } = req.body
  if (!name) return res.status(400).json({ success: false, error: '请提供盒子名称' })
  const box = createBox(name, description)
  res.json({ success: true, box })
})

// 更新盒子
app.put('/api/box/:id', (req: Request, res: Response) => {
  const box = updateBox(req.params.id, req.body)
  if (!box) return res.status(404).json({ success: false, error: '盒子不存在' })
  res.json({ success: true, box })
})

// 删除盒子
app.delete('/api/box/:id', (req: Request, res: Response) => {
  if (!deleteBox(req.params.id)) return res.status(404).json({ success: false, error: '盒子不存在' })
  res.json({ success: true })
})

// 盒子内卡片列表
app.get('/api/box/:id/cards', (req: Request, res: Response) => {
  if (!getBox(req.params.id)) return res.status(404).json({ success: false, error: '盒子不存在' })
  const { source_type, tag, q } = req.query as Record<string, string>
  const cards = listBoxCards(req.params.id, { sourceType: source_type, tag, q })
  res.json({ success: true, cards })
})

// 添加卡片到盒子
app.post('/api/box/:id/cards', (req: Request, res: Response) => {
  if (!getBox(req.params.id)) return res.status(404).json({ success: false, error: '盒子不存在' })
  const card = createBoxCard(req.params.id, req.body)
  res.json({ success: true, card })
})

// 单卡片详情
app.get('/api/box/card/:cardId', (req: Request, res: Response) => {
  const card = getBoxCard(req.params.cardId)
  if (!card) return res.status(404).json({ success: false, error: '卡片不存在' })
  res.json({ success: true, card })
})

// 更新卡片
app.put('/api/box/card/:cardId', (req: Request, res: Response) => {
  const card = updateBoxCard(req.params.cardId, req.body)
  if (!card) return res.status(404).json({ success: false, error: '卡片不存在' })
  res.json({ success: true, card })
})

// 删除卡片
app.delete('/api/box/card/:cardId', (req: Request, res: Response) => {
  if (!deleteBoxCard(req.params.cardId)) return res.status(404).json({ success: false, error: '卡片不存在' })
  res.json({ success: true })
})

// 跨盒搜索
app.get('/api/box/search', (req: Request, res: Response) => {
  const { q, box_id } = req.query as Record<string, string>
  if (!q) return res.status(400).json({ success: false, error: '请提供搜索关键词 q' })
  const cards = searchBoxCards(q, box_id)
  res.json({ success: true, cards })
})

app.get('/api/ink-box/:boardId', (req: Request, res: Response) => {
  res.json({ success: true, items: listInkBoxItems(req.params.boardId) })
})

app.get('/api/ink-box/item/:itemId', (req: Request, res: Response) => {
  res.json({ success: true, ...getInkBoxItem(req.params.itemId) })
})

app.delete('/api/ink-box/item/:itemId', (req: Request, res: Response) => {
  if (!deleteInkBoxItem(req.params.itemId)) return res.status(404).json({ success: false, error: '笔迹盒条目不存在' })
  res.json({ success: true })
})

app.post('/api/ink-box/archive', (req: Request, res: Response) => {
  try {
    archiveInkBoxItem(req.body)
    res.json({ success: true })
  } catch (error) {
    res.status(400).json({ success: false, error: error instanceof Error ? error.message : String(error) })
  }
})

// ============================================
// 错误处理
// ============================================

app.use((err: any, req: Request, res: Response, next: any) => {
  console.error('[Server] 错误:', err)
  
  if (err instanceof multer.MulterError) {
    if (err.code === 'LIMIT_FILE_SIZE') {
      return res.status(400).json({
        success: false,
        error: '文件过大，最大支持 500MB'
      })
    }
  }
  
  res.status(500).json({
    success: false,
    error: err.message || '服务器错误'
  })
})

// ============================================
// 启动服务器
// ============================================

const server = createServer(app)
attachBoardSync(server)

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[Server] 转录服务器已启动`)
  console.log(`[Server] 监听端口: ${PORT}`)
  console.log(`[Server] 上传目录: ${UPLOADS_DIR}`)
  console.log(`[Server] API 端点:`)
  console.log(`  - GET    http://localhost:${PORT}/api/status`)
  console.log(`  - POST   http://localhost:${PORT}/api/transcribe`)
  console.log(`  - GET    http://localhost:${PORT}/api/transcriptions`)
  console.log(`  - DELETE http://localhost:${PORT}/api/transcriptions/:id`)
  console.log(`  - POST   http://localhost:${PORT}/api/tieba/crawl`)
  console.log(`  - GET    http://localhost:${PORT}/api/tieba/history`)
  console.log(`  - DELETE http://localhost:${PORT}/api/tieba/history/:id`)
  console.log(`  - POST   http://localhost:${PORT}/api/community/resolve`)
  console.log(`  - POST   http://localhost:${PORT}/api/community/:name/sync`)
  console.log(`  - GET    http://localhost:${PORT}/api/community/:id/posts`)
  console.log(`  - POST   http://localhost:${PORT}/api/community/post/:postId/refresh-comments`)
})
