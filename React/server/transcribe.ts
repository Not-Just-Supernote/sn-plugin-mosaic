import { webkit, firefox, type BrowserContext, type Page } from 'playwright'
import { promises as fs } from 'fs'
import path from 'path'
import os from 'os'
import mammoth from 'mammoth'

// ============================================
// 操作系统检测：Mac 用 Safari(webkit)，Windows 用 Firefox
// ============================================
const platform = os.platform()
const isWindows = platform === 'win32'
const browserType = isWindows ? firefox : webkit
const browserName = isWindows ? 'Firefox' : 'Safari (WebKit)'

// ============================================
// DOM 选择器配置
// ============================================
const SELECTORS = {
  // 登录态检测：用户头像
  loginIndicator: '.ant-avatar',

  // 主页"上传音视频"卡片（第一步）
  createButton: '.sc-jbJECb.sc-gsHvIN.dKUWR.eUJhGd.ButtonList',

  // 弹出框中的"上传本地音视频文件"选项（第二步）
  uploadLocalOption: '.sc-kvtFaN.eQiApA',

  // 文件上传输入框
  fileInput: 'input[type="file"]',

  // "开始转写"按钮
  startTranscriptionButton: 'button.ant-btn.ant-btn-primary',

  // 右上角状态面板
  statusPanel: '.sc-kSsbVf.dgnAcp',

  // 状态列表项
  statusItemError: 'li.error',
  statusItemProcessing: 'li.processing',
  statusItemSuccess: 'li.success',

  // 导出按钮
  exportButton: '.fileActionItem',

  // 导出到本地按钮
  exportToLocalButton: 'button.ant-btn.ant-btn-default.PanelFooterCompo_DownloadBtn',

  // 导出对话框中的"发言人"复选框
  speakerCheckbox: 'label.ant-checkbox-wrapper:has-text("发言人") .ant-checkbox-input',

  // 导出对话框中的"时间戳"复选框
  timestampCheckbox: 'label.ant-checkbox-wrapper:has-text("时间戳") .ant-checkbox-input',

  // 我的记录页面的文件卡片
  myRecordsCard: '.sc-gVJvzJ.esgUqP.groupCards',

  // 转录结果容器
  resultContainer: '.editorContent',

  // 转录结果段落
  resultParagraphs: '.tingwu2_paragraphStyled',
}

// 状态文本匹配规则
const STATUS_PATTERNS = {
  processing: /转写中|等待转写|上传中/,
  completed: /已完成|完成|成功/,
  failed: /转写失败|失败/,
}

// 通义听悟网址
const TINGWU_URL = 'https://tingwu.aliyun.com'

// 浏览器用户数据目录
const USER_DATA_DIR = path.resolve(process.cwd(), 'playwright/.auth')

// 超时配置（毫秒）
const TIMEOUTS = {
  navigation: 30000,      // 页面导航
  element: 30000,         // 元素查找
  login: 60000,           // 等待用户登录
  upload: 120000,         // 文件上传
  transcription: 600000,  // 转录完成（10分钟）
  poll: 5000,             // 轮询间隔
}

export interface TranscribeResult {
  success: boolean
  text: string           // 完整转录文本
  segments: string[]     // 按段落拆分
  recordId?: string      // manifest 记录 ID
  error?: string
}

export interface TranscriptionRecord {
  id: string
  originalFilename: string
  docxFilename: string
  text: string
  segments: string[]
  createdAt: string
}

interface TranscriptionManifest {
  records: TranscriptionRecord[]
}

// ============================================
// Manifest 管理
// ============================================

const DOWNLOADS_DIR = path.resolve(process.cwd(), 'downloads')
const MANIFEST_PATH = path.join(DOWNLOADS_DIR, 'manifest.json')

function generateRecordId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 6)
}

export async function readManifest(): Promise<TranscriptionManifest> {
  try {
    const content = await fs.readFile(MANIFEST_PATH, 'utf-8')
    return JSON.parse(content)
  } catch {
    return { records: [] }
  }
}

export async function writeManifest(manifest: TranscriptionManifest): Promise<void> {
  await fs.mkdir(DOWNLOADS_DIR, { recursive: true })
  await fs.writeFile(MANIFEST_PATH, JSON.stringify(manifest, null, 2), 'utf-8')
}

export async function deleteRecord(id: string): Promise<boolean> {
  const manifest = await readManifest()
  const idx = manifest.records.findIndex(r => r.id === id)
  if (idx === -1) return false

  const record = manifest.records[idx]
  // 删除 docx 文件
  try {
    await fs.unlink(path.join(DOWNLOADS_DIR, record.docxFilename))
  } catch { /* 文件可能已不存在 */ }

  manifest.records.splice(idx, 1)
  await writeManifest(manifest)
  return true
}

let browserContext: BrowserContext | null = null
let isFirstRun = true

// 登录等待状态（供外部轮询）
export let loginRequired = false

const STORAGE_STATE_FILE = path.resolve(process.cwd(), 'playwright/storage-state.json')

// ============================================
// 浏览器管理
// ============================================

async function ensureBrowserContext(): Promise<BrowserContext> {
  // 检测已有 context 是否还活着（Firefox 在最后一个页面关闭后会自动关闭 context）
  if (browserContext) {
    try {
      // 尝试读取 pages()，如果 context 已关闭会抛异常
      browserContext.pages()
    } catch {
      console.log('[Transcribe] 检测到浏览器 context 已关闭，重新启动...')
      browserContext = null
    }
  }

  if (browserContext) return browserContext

  // 确保用户数据目录存在
  await fs.mkdir(USER_DATA_DIR, { recursive: true })

  console.log(`[Transcribe] 启动 ${browserName} 浏览器...`)
  console.log(`[Transcribe] 检测到操作系统: ${platform} → 使用 ${browserName}`)

  // 根据操作系统选择浏览器引擎，使用持久化上下文确保 cookies 持久化
  browserContext = await browserType.launchPersistentContext(USER_DATA_DIR, {
    headless: false,
    viewport: { width: 1280, height: 800 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  })

  // 从 storageState 恢复登录状态（cookies + localStorage）
  try {
    const stateContent = await fs.readFile(STORAGE_STATE_FILE, 'utf-8')
    const state = JSON.parse(stateContent)
    if (state.cookies?.length > 0) {
      await browserContext.addCookies(state.cookies)
      console.log(`[Transcribe] ✓ 已从 storageState 注入 ${state.cookies.length} 个 Cookies`)
    }
  } catch (e) {
    console.log('[Transcribe] ⚠ 未找到 storageState 文件，请先运行 npm run setup-login')
  }

  console.log(`[Transcribe] ${browserName} 浏览器已启动（使用持久化上下文）`)
  return browserContext
}

async function saveStorageState(): Promise<void> {
  if (!browserContext) return
  try {
    const cookies = await browserContext.cookies()
    const state = { cookies }
    await fs.mkdir(path.dirname(STORAGE_STATE_FILE), { recursive: true })
    await fs.writeFile(STORAGE_STATE_FILE, JSON.stringify(state, null, 2), 'utf-8')
    console.log(`[Transcribe] ✓ 已保存 ${cookies.length} 个 Cookies 到 storageState`)
  } catch (e) {
    console.error('[Transcribe] 保存 storageState 失败:', e)
  }
}

async function closeBrowser() {
  if (browserContext) {
    await browserContext.close()
    browserContext = null
    console.log('[Transcribe] 浏览器已关闭（登录状态已自动保存）')
  }
}

// ============================================
// 从"我的记录"页面查找并访问文件
// ============================================

async function findFileInMyRecords(page: Page, fileName: string): Promise<boolean> {
  try {
    console.log(`[Transcribe] 在"我的记录"中查找文件: ${fileName}`)

    // 访问"我的记录"页面
    await page.goto('https://tingwu.aliyun.com/folders/0', {
      waitUntil: 'domcontentloaded',
      timeout: TIMEOUTS.navigation
    })

    // 等待文件卡片加载
    await page.waitForSelector(SELECTORS.myRecordsCard, {
      timeout: TIMEOUTS.element
    })

    await page.waitForTimeout(1000)

    // 查找包含文件名的卡片
    const cards = await page.$$(SELECTORS.myRecordsCard)

    for (const card of cards) {
      const text = await card.textContent()
      if (text?.includes(fileName)) {
        console.log('[Transcribe] ✓ 找到文件，点击进入详情页')
        await card.click()
        await page.waitForTimeout(2000)
        return true
      }
    }

    console.log('[Transcribe] ⚠ 未找到文件')
    return false

  } catch (error) {
    console.error('[Transcribe] 查找文件失败:', error)
    return false
  }
}

// ============================================
// 登录检测与处理
// ============================================

async function checkAndWaitForLogin(page: Page): Promise<void> {
  console.log('[Transcribe] 检测登录状态...')

  // 等待页面完全加载
  await page.waitForTimeout(3000)

  // 定义一组可能的登录成功标志
  const successSelectors = [
    SELECTORS.loginIndicator,              // 头像
    SELECTORS.createButton,                // 新建按钮/上传卡片
    'text=上传音视频',                      // 页面上的关键文本
    'text=新建',                            // 新建按钮文本
    '.ant-avatar',                         // 通用头像类
    '[class*="Avatar"]',                   // 包含 Avatar 的类
    'img[alt*="头像"]'                      // 头像图片
  ]

  const isLoggedIn = async () => {
    try {
      await Promise.any(successSelectors.map(selector =>
        page.waitForSelector(selector, { timeout: 3000 }).then(() => selector)
      ))
      return true
    } catch {
      const url = page.url()
      return url.includes('/home') || url.includes('/manage')
    }
  }

  if (await isLoggedIn()) {
    console.log('[Transcribe] ✓ 已登录')
    loginRequired = false
    isFirstRun = false
    await saveStorageState()
    return
  }

  // 未登录：通知前端，等待用户登录（最多 5 分钟）
  console.log('[Transcribe] ⚠ 未登录，等待用户在浏览器中完成登录（最多 5 分钟）...')
  loginRequired = true

  const deadline = Date.now() + 5 * 60 * 1000
  while (Date.now() < deadline) {
    await page.waitForTimeout(3000)
    if (await isLoggedIn()) {
      console.log('[Transcribe] ✓ 登录成功，继续转录')
      loginRequired = false
      isFirstRun = false
      await saveStorageState()
      return
    }
  }

  loginRequired = false
  throw new Error('登录超时（5 分钟内未完成登录），请重试')
}

// ============================================
// 转录核心流程
// ============================================

export async function transcribe(filePath: string, originalFileName?: string): Promise<TranscribeResult> {
  let page: Page | null = null

  try {
    // 1. 启动浏览器
    const context = await ensureBrowserContext()

    // 关键修复：复用 context 中已有的第一个页面，避免开两个窗口
    const pages = context.pages()
    page = pages.length > 0 ? pages[0] : await context.newPage()

    // 2. 访问通义听悟
    const currentUrl = page.url()

    // 如果已经在目标页面，就不需要重新加载
    if (!currentUrl.includes('tingwu.aliyun.com')) {
      console.log('[Transcribe] 访问通义听悟...')
      await page.goto(TINGWU_URL, {
        waitUntil: 'domcontentloaded',
        timeout: TIMEOUTS.navigation
      })
    } else {
      console.log(`[Transcribe] 已在页面: ${currentUrl}，无需跳转`)
      // 稍微刷新一下确保状态更新，但不要全量加载
      await page.reload({ waitUntil: 'domcontentloaded' })
    }

    // 打印当前 URL 和页面标题用于调试
    const pageTitle = await page.title()
    console.log(`[Transcribe] 当前页面: ${page.url()}`)
    console.log(`[Transcribe] 页面标题: ${pageTitle}`)

    // 3. 检查登录状态
    await checkAndWaitForLogin(page)

    // 4. 点击"上传音视频"卡片
    console.log('[Transcribe] 点击"上传音视频"卡片...')
    await page.click(SELECTORS.createButton, { timeout: TIMEOUTS.element })
    await page.waitForTimeout(1000) // 等待弹窗出现

    // 5. 点击"上传本地音视频文件"选项
    console.log('[Transcribe] 选择"上传本地音视频文件"...')
    await page.click(SELECTORS.uploadLocalOption, { timeout: TIMEOUTS.element })
    await page.waitForTimeout(500)

    // 6. 上传文件
    console.log('[Transcribe] 上传文件...')
    const fileInputElement = await page.waitForSelector(SELECTORS.fileInput, {
      state: 'attached',  // 只需要元素存在，不需要可见
      timeout: TIMEOUTS.element
    })
    await fileInputElement.setInputFiles(filePath)
    console.log('[Transcribe] 文件已选择')

    // 提取文件名（用于后续查找）
    const fileName = path.basename(filePath, path.extname(filePath))

    // 7. 点击"开始转写"
    console.log('[Transcribe] 点击"开始转写"...')
    await page.click(SELECTORS.startTranscriptionButton, {
      timeout: TIMEOUTS.element
    })

    // 8. 等待转录完成
    console.log('[Transcribe] 等待转录完成...')
    const displayName = originalFileName
      ? path.basename(originalFileName, path.extname(originalFileName))
      : fileName
    const result = await waitForTranscriptionComplete(page, fileName, displayName)

    return result

  } catch (error) {
    console.error('[Transcribe] 错误:', error)
    return {
      success: false,
      text: '',
      segments: [],
      error: error instanceof Error ? error.message : String(error)
    }
  } finally {
    // 保存最新 cookies（刷新过期时间）
    await saveStorageState()
    // 导航到空白页而不是关闭，避免 Firefox 在最后一个页面关闭时自动销毁 context
    if (page) {
      try {
        await page.goto('about:blank', { waitUntil: 'commit', timeout: 5000 })
      } catch {
        // 如果导航失败就关闭，下次会重建 context
        try { await page.close() } catch { /* ignore */ }
        browserContext = null
      }
    }
  }
}

// ============================================
// 等待转录完成（通过 URL 跳转 + 状态检查）
// ============================================

async function waitForTranscriptionComplete(page: Page, fileName: string, originalFileName: string): Promise<TranscribeResult> {
  const startTime = Date.now()
  const maxWaitTime = TIMEOUTS.transcription

  // 轮询检查状态面板
  while (true) {
    // 检查是否超时
    if (Date.now() - startTime > maxWaitTime) {
      throw new Error(`转录超时（超过 ${maxWaitTime / 60000} 分钟）`)
    }

    try {
      // 检查是否有错误状态
      const errorItem = await page.$(SELECTORS.statusItemError)
      if (errorItem) {
        const errorText = await errorItem.textContent()
        throw new Error(`转录失败: ${errorText?.trim() || '未知错误'}`)
      }

      // 检查状态面板文本
      const statusPanel = await page.$(SELECTORS.statusPanel)
      if (statusPanel) {
        const statusText = await statusPanel.textContent()
        console.log(`[Transcribe] 当前状态: ${statusText?.trim()}`)

        // 检查是否失败
        if (statusText && STATUS_PATTERNS.failed.test(statusText)) {
          throw new Error(`转录失败: ${statusText.trim()}`)
        }

        // 检查是否还在转写中
        if (statusText && STATUS_PATTERNS.processing.test(statusText)) {
          // 继续等待
          await page.waitForTimeout(TIMEOUTS.poll)
          continue
        }
      }

      // 如果状态面板消失，说明可能完成了
      if (!statusPanel) {
        console.log('[Transcribe] 状态面板消失，检查转录是否完成...')

        // 去"我的记录"页面查找文件
        const found = await findFileInMyRecords(page, fileName)

        if (found) {
          // 已经在详情页，直接导出
          console.log('[Transcribe] ✓ 转录完成')
          return await exportTranscriptionResult(page, originalFileName)
        } else {
          // 没找到文件，继续等待
          console.log('[Transcribe] 文件尚未出现，继续等待...')
          await page.waitForTimeout(TIMEOUTS.poll)
          continue
        }
      }

      // 等待后继续轮询
      await page.waitForTimeout(TIMEOUTS.poll)

    } catch (error) {
      if (error instanceof Error && error.message.includes('转录失败')) {
        throw error
      }
      if (error instanceof Error && error.message.includes('转录超时')) {
        throw error
      }
      // 其他错误继续轮询
      await page.waitForTimeout(TIMEOUTS.poll)
    }
  }
}

// ============================================
// 导出转录结果为 docx
// ============================================

async function exportTranscriptionResult(page: Page, originalFileName: string): Promise<TranscribeResult> {
  try {
    // 1. 点击导出按钮
    console.log('[Transcribe] 点击导出按钮...')
    await page.click(SELECTORS.exportButton, { timeout: TIMEOUTS.element })
    await page.waitForTimeout(1000)

    // 2. 等待导出对话框
    await page.waitForSelector(SELECTORS.exportToLocalButton, {
      timeout: TIMEOUTS.element
    })

    // 2.5 调试：打印导出对话框的 DOM 结构
    console.log('[Transcribe] === 导出对话框 DOM 调试 ===')
    const dialogHTML = await page.evaluate(() => {
      // 尝试找到 Ant Design 的 Modal / Drawer
      const modal = document.querySelector('.ant-modal-content')
        || document.querySelector('.ant-drawer-content')
        || document.querySelector('[class*="Panel"]')
        || document.querySelector('[class*="Export"]')
        || document.querySelector('[class*="export"]')
      if (modal) return modal.innerHTML
      // fallback: 找所有 checkbox
      const checkboxes = document.querySelectorAll('input[type="checkbox"], [class*="checkbox"], [class*="Checkbox"]')
      if (checkboxes.length > 0) {
        return Array.from(checkboxes).map(el => {
          const parent = el.closest('label') || el.parentElement
          return parent ? parent.outerHTML : el.outerHTML
        }).join('\n---\n')
      }
      return 'NO MODAL OR CHECKBOX FOUND'
    })
    console.log(dialogHTML)
    console.log('[Transcribe] === DOM 调试结束 ===')

    // 3. 监听下载事件
    const downloadPromise = page.waitForEvent('download', {
      timeout: 60000
    })

    // 4. 点击"导出到本地"
    console.log('[Transcribe] 点击"导出到本地"...')
    await page.click(SELECTORS.exportToLocalButton)

    // 5. 等待下载完成
    const download = await downloadPromise
    const fileName = download.suggestedFilename()

    // 6. 保存文件
    const downloadPath = path.resolve(process.cwd(), 'downloads')
    await fs.mkdir(downloadPath, { recursive: true })
    const filePath = path.join(downloadPath, fileName)
    await download.saveAs(filePath)

    console.log(`[Transcribe] ✓ 文件已导出: ${filePath}`)

    // 解析 docx 提取文本
    console.log('[Transcribe] 解析 docx 内容...')
    const mammothResult = await mammoth.extractRawText({ path: filePath })
    const fullText = mammothResult.value

    // 按段落拆分
    const segments = fullText
      .split(/\n\n+/)
      .map((s: string) => s.trim())
      .filter((s: string) => s.length > 0)

    console.log(`[Transcribe] ✓ 提取到 ${segments.length} 个段落`)

    // 写入 manifest
    const manifest = await readManifest()
    const record: TranscriptionRecord = {
      id: generateRecordId(),
      originalFilename: originalFileName,
      docxFilename: fileName,
      text: fullText,
      segments,
      createdAt: new Date().toISOString(),
    }
    manifest.records.push(record)
    await writeManifest(manifest)

    return {
      success: true,
      text: fullText,
      segments,
      recordId: record.id,
    }

  } catch (error) {
    throw new Error(`导出失败: ${error instanceof Error ? error.message : String(error)}`)
  }
}

// ============================================
// 优雅关闭
// ============================================

process.on('SIGINT', async () => {
  console.log('\n[Transcribe] 收到退出信号，关闭浏览器...')
  await closeBrowser()
  process.exit(0)
})

process.on('SIGTERM', async () => {
  console.log('\n[Transcribe] 收到退出信号，关闭浏览器...')
  await closeBrowser()
  process.exit(0)
})
