import { webkit, firefox } from 'playwright'
import { promises as fs } from 'fs'
import path from 'path'
import os from 'os'

// 操作系统检测：Mac 用 Safari(webkit)，Windows 用 Firefox
const platform = os.platform()
const isWindows = platform === 'win32'
const browserType = isWindows ? firefox : webkit
const browserName = isWindows ? 'Firefox' : 'Safari (WebKit)'

const USER_DATA_DIR = path.resolve(process.cwd(), 'playwright/.auth')
const STORAGE_STATE_FILE = path.resolve(process.cwd(), 'playwright/storage-state.json')

async function setupLogin() {
  console.log(`🔐 通义听悟登录设置 (${browserName})\n`)
  console.log(`   检测到操作系统: ${platform} → 使用 ${browserName}\n`)

  // 清理旧数据，确保干净环境
  try {
    await fs.rm(USER_DATA_DIR, { recursive: true, force: true })
  } catch {}
  await fs.mkdir(USER_DATA_DIR, { recursive: true })

  console.log('1️⃣  启动浏览器...')
  const context = await browserType.launchPersistentContext(USER_DATA_DIR, {
    headless: false,
    viewport: { width: 1280, height: 800 },
  })

  // 注意：launchPersistentContext 默认会打开一个空白页，直接使用它，不要新开
  const pages = context.pages()
  const page = pages.length > 0 ? pages[0] : await context.newPage()

  console.log('2️⃣  访问通义听悟...')
  await page.goto('https://tingwu.aliyun.com/home', { waitUntil: 'domcontentloaded' })

  console.log('\n✅ 浏览器已就绪！')
  console.log(`   请在打开的 ${browserName} 窗口中手动登录。`)
  console.log('   登录完成后，确保能看到主页（有"上传音视频"等选项）。')
  console.log('   然后回到这里按回车键。\n')

  await new Promise<void>((resolve) => {
    process.stdin.once('data', () => resolve())
  })

  console.log('\n⏳ 保存状态...')

  // 使用 storageState 保存完整登录状态（cookies + localStorage）
  // 保存到 profile 目录之外，避免被 Firefox profile 管理干扰
  const state = await context.storageState({ path: STORAGE_STATE_FILE })
  console.log(`   已保存 storageState: ${state.cookies.length} 个 Cookies, ${state.origins.length} 个 origins`)
  console.log(`   状态文件: ${STORAGE_STATE_FILE}`)

  // 等待数据落盘
  await page.waitForTimeout(2000)

  await context.close()

  console.log('🎉 设置完成！')
  process.exit(0)
}

setupLogin().catch(console.error)
