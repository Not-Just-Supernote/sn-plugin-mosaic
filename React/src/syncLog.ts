export type SyncLogDetails = Partial<Record<'revision' | 'bytes' | 'cards' | 'ink' | 'code' | 'delayMs' | 'domain' | 'state' | 'message', string | number>>
const entries: string[] = []
const storageKey = 'mosaic-sync-log-v1'
let restored = false
const runtime = globalThis as unknown as Record<string, any>

export function getSyncLogs(): string[] {
  if (!restored) {
    restored = true
    try {
      const saved: unknown = JSON.parse(runtime.sessionStorage?.getItem(storageKey) ?? '[]')
      if (Array.isArray(saved)) entries.push(...saved.filter(item => typeof item === 'string' && item.length <= 1024).slice(-300))
    } catch {}
  }
  return [...entries]
}

export function recordSyncLog(event: string, details: SyncLogDetails = {}): void {
  getSyncLogs()
  const line = JSON.stringify({ time: new Date().toISOString(), event, ...details })
  entries.push(line)
  if (entries.length > 300) entries.splice(0, entries.length - 300)
  console.info('[MosaicSync]', line)
  try { runtime.sessionStorage?.setItem(storageKey, JSON.stringify(entries)) } catch {}
}

export function downloadSyncLogs(): void {
  if (!runtime.document || !runtime.URL || !runtime.Blob) return
  const url = runtime.URL.createObjectURL(new runtime.Blob([getSyncLogs().join('\n')], { type: 'text/plain;charset=utf-8' }))
  const anchor = runtime.document.createElement('a')
  anchor.href = url
  anchor.download = 'mosaic-sync-log.txt'
  anchor.click()
  setTimeout(() => runtime.URL.revokeObjectURL(url), 1000)
}
