import { firefox, webkit } from 'playwright'
import { promises as fs } from 'fs'
import path from 'path'
import os from 'os'

const platform = os.platform()
const isWindows = platform === 'win32'
const browserType = isWindows ? firefox : webkit
const browserName = isWindows ? 'Firefox' : 'Safari (WebKit)'

const USER_DATA_DIR = path.resolve(process.cwd(), 'playwright/.reddit-auth')
const STORAGE_STATE_FILE = path.resolve(process.cwd(), 'playwright/reddit-storage-state.json')

async function setupRedditLogin() {
  console.log(`🔐 Reddit 登录设置 (${browserName})\n`)
  console.log(`   检测到操作系统: ${platform} → 使用 ${browserName}\n`)

  try {
    await fs.rm(USER_DATA_DIR, { recursive: true, force: true })
  } catch {}
  await fs.mkdir(USER_DATA_DIR, { recursive: true })

  console.log('1️⃣  启动浏览器...')
  const context = await browserType.launchPersistentContext(USER_DATA_DIR, {
    headless: false,
    viewport: { width: 1280, height: 800 },
  })

  const pages = context.pages()
  const page = pages.length > 0 ? pages[0] : await context.newPage()

  console.log('2️⃣  访问 Reddit 登录页...')
  await page.goto('https://www.reddit.com/login', { waitUntil: 'domcontentloaded' })

  console.log('\n✅ 浏览器已就绪！')
  console.log(`   请在打开的 ${browserName} 窗口中手动登录 Reddit。`)
  console.log('   登录成功后，回到这里按回车键保存 Cookies。\n')

  await new Promise<void>((resolve) => {
    process.stdin.once('data', () => resolve())
  })

  console.log('\n⏳ 保存状态...')
  const state = await context.storageState({ path: STORAGE_STATE_FILE })
  console.log(`   已保存 storageState: ${state.cookies.length} 个 Cookies, ${state.origins.length} 个 origins`)
  console.log(`   状态文件: ${STORAGE_STATE_FILE}`)

  await page.waitForTimeout(2000)
  await context.close()

  console.log('🎉 Reddit 登录状态已保存，抓取时会自动使用。')
  process.exit(0)
}

setupRedditLogin().catch(console.error)
