import { encodeBoard, type BoardDoc } from './boardFormat'
import { bytesToBase64 } from './base64'

export const SYNC_SITE = 'https://re.yalums.top/whiteboard/'
export const ROOM_ID_PATTERN = /^[a-f0-9]{48}$/
/**
 * 局域网同步的固定房间：电脑上跑本地 React（npm start），插件填电脑地址即连，不建房、不用共享码。
 * 网页端打开 `http://localhost:3790/#board=mosaic-local` 进入同一房间（单独的「Mosaic 设备」画布）。
 */
export const LOCAL_ROOM_ID = 'mosaic-local'

/**
 * 插件里填的电脑地址 → 本地站点根（末尾带 /）。接受 `192.168.1.5`、`192.168.1.5:3790`、
 * `http://192.168.1.5:3790/` 等写法；不写端口默认 Vite 的 3790（/api、/ws 由它转给 3791）。
 */
export function localSyncSite(address: string): string {
  const trimmed = address.trim().replace(/^[a-z]+:\/\//i, '').replace(/\/.*$/, '')
  if (!/^[a-zA-Z0-9.-]+(:\d{1,5})?$/.test(trimmed)) throw new Error('电脑地址格式需要像 192.168.1.5:3790')
  return `http://${trimmed.includes(':') ? trimmed : `${trimmed}:3790`}/`
}

/** 站点根（http(s)://host/prefix/）→ 白板 WebSocket 地址。 */
export function siteSyncEndpoint(site: string): string {
  return `${site.replace(/^http/, 'ws')}ws/board`
}

export function sharedBoardLink(boardId: string): string {
  if (!ROOM_ID_PATTERN.test(boardId)) throw new Error('共享码格式需要 48 位十六进制字符')
  return `${SYNC_SITE}#board=${boardId}`
}

export function parseSharedBoard(value: string): string {
  const match = /(?:^|#|&)board=([a-z0-9-]+)(?:&|$)/.exec(value.trim())
  const boardId = match?.[1] ?? value.trim()
  if (boardId === LOCAL_ROOM_ID) return boardId
  if (!ROOM_ID_PATTERN.test(boardId)) throw new Error('请粘贴完整共享链接或共享码')
  return boardId
}

export function webSyncEndpoint(): string {
  const location = (globalThis as unknown as { location?: { pathname: string; protocol: string; host: string } }).location
  if (!location) throw new Error('网页同步需要浏览器环境')
  const prefix = location.pathname.startsWith('/whiteboard') ? '/whiteboard' : ''
  return `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}${prefix}/ws/board`
}

/**
 * 图片卡片像素 / 笔记卡片预览的资源地址（与 `/ws/board` 同一服务、同一前缀）。
 * `version` 只是缓存钥匙：每收到一次快照就换一个，让浏览器重新校验（笔记预览会被插件反复重绘）。
 */
export function boardAssetUrl(boardId: string, ref: string, version: number): string {
  const location = (globalThis as unknown as { location?: { pathname: string; origin: string } }).location
  if (!location) throw new Error('网页同步需要浏览器环境')
  const prefix = location.pathname.startsWith('/whiteboard') ? '/whiteboard' : ''
  return `${location.origin}${prefix}/api/board/assets/${boardId}/${encodeURIComponent(ref)}?v=${version}`
}

/** 卡片 → 服务器资源引用名（与插件 assetSync.assetRefFor 一致）。 */
export function boardAssetRef(card: { kind?: string; imageRef?: string; noteRef?: string }): string | null {
  if (card.kind === 'image') return card.imageRef ?? null
  if (card.kind === 'note') return card.noteRef ? `${card.noteRef}.png` : null
  return null
}

export async function createSharedBoard(doc: BoardDoc, site = SYNC_SITE): Promise<string> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 15000)
  try {
  const response = await fetch(`${site}api/board/rooms`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ boardBase64: bytesToBase64(encodeBoard(doc)) }),
    signal: controller.signal,
  })
  if (!response.ok) throw new Error(`共享服务 HTTP ${response.status}`)
  const result = await response.json() as { boardId: string }
  return parseSharedBoard(result.boardId)
  } finally { clearTimeout(timeout) }
}
