import { decodeBoard, encodeBoard, type BoardDoc } from './boardFormat'
import type { Card } from './types'
import { base64ToBytes, bytesToBase64 } from './base64'
import { crc32 } from './crc32'
import { generateId } from './id'
import type { BoardMutationDomain, BoardServerMessage, ParentMergeResultMessage } from './syncProtocol'
import { recordSyncLog } from './syncLog'
import { webSyncEndpoint } from './sharedBoard'

export const BOARD_SYNC_ENABLED = true
export type SyncConnectionState = 'idle' | 'connecting' | 'connected' | 'error' | 'closed' | 'conflict'
type SnapshotHandler = (doc: BoardDoc, revision: number, domain?: BoardMutationDomain) => void

/**
 * 文档内容签名（只用于本地相等判断，不上网）。抹掉 meta 里的身份/时间/视口后编码，
 * 再取「长度 + CRC32 + FNV-1a」——两遍 32 位散列对几百 KB 的容器只有几十毫秒；
 * 之前是整份 base64 字符串（编码 + 逐字符拼接），在 Hermes 上单次就要走秒级，
 * 而 publish 每次改动都算两遍，是 JS 线程被占满、关闭命令排不上队的主因。
 */
function fingerprint(doc: BoardDoc): string {
  const bytes = encodeBoard({ ...doc, meta: { ...doc.meta, id: '', name: '', createdAt: '', updatedAt: '', viewport: { panX: 0, panY: 0, scale: 1 } } })
  let fnv = 0x811c9dc5
  for (let i = 0; i < bytes.length; i++) {
    fnv ^= bytes[i]
    fnv = Math.imul(fnv, 0x01000193)
  }
  return `${bytes.length}:${crc32(bytes).toString(16)}:${(fnv >>> 0).toString(16)}`
}

/**
 * 两次尚未发出的 publish 合并成一份时的域：每份 doc 都是整份快照，域只是告诉服务器
 * "这次只认哪些键"。不同域叠在一起就得升成 document（整份替换），否则先排队的那个域
 * 的改动会被服务器按后一个域的合并规则丢掉；import 带本地保护，永远优先。
 */
function mergeDomains(a: BoardMutationDomain, b: BoardMutationDomain): BoardMutationDomain {
  if (a === b) return a
  if (a === 'import' || b === 'import') return 'import'
  if (a === 'viewport') return b
  if (b === 'viewport') return a
  return 'document'
}

/** 同一份 doc 对象只算一次签名（publish 与 flush 会先后看同一对象）。 */
const signatureCache = new WeakMap<BoardDoc, string>()
function signatureOf(doc: BoardDoc): string {
  let signature = signatureCache.get(doc)
  if (signature === undefined) {
    signature = fingerprint(doc)
    signatureCache.set(doc, signature)
  }
  return signature
}

export class BoardSyncClient {
  /** 插件端注入：ws:// 局域网地址走原生 Socket（系统网络栈禁明文）；返回 null 则用全局 WebSocket。网页端不设置。 */
  static socketFactory: ((endpoint: string) => WebSocket | null) | null = null
  readonly clientId = 'device-' + generateId()
  private socket: WebSocket | null = null
  private boardId = ''
  private revision = 0
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private watchdog: ReturnType<typeof setTimeout> | null = null
  private reconnectDelay = 800
  private closed = true
  private joined = false
  private conflicted = false
  private endpoint = ''
  private onSnapshot: SnapshotHandler | null = null
  private onState: ((state: SyncConnectionState) => void) | null = null
  private accepted = ''
  private pending: BoardDoc | null = null
  private pendingDomain: BoardMutationDomain = 'document'
  private inFlight: { id: string; doc: BoardDoc; domain: BoardMutationDomain } | null = null
  private protectLocal = false
  // 流水线：已发出但尚未 ack 的自有操作及其乐观 revision。都是全量累进快照，
  // 用 baseRevision 递增让服务器顺序接受，无需逐条等 RTT。
  private outstanding = new Map<string, { revision: number; signature: string; domain: BoardMutationDomain }>()
  private nextBaseRevision = 0
  /** 最近一次真正发出的快照（流水线中断线时用它重发）。 */
  private lastSent: BoardDoc | null = null

  connect(boardId: string, seed: BoardDoc, onSnapshot: SnapshotHandler, endpoint?: string, onState?: (state: SyncConnectionState) => void): void {
    this.close()
    this.boardId = boardId
    this.revision = 0
    this.nextBaseRevision = 0
    this.accepted = signatureOf(seed)
    this.pending = null
    this.pendingDomain = 'document'
    this.inFlight = null
    this.outstanding.clear()
    this.lastSent = null
    this.closed = false
    this.conflicted = false
    this.protectLocal = false
    this.endpoint = endpoint ?? webSyncEndpoint()
    this.onSnapshot = onSnapshot
    this.onState = onState ?? null
    this.openSocket()
  }

  publish(domain: BoardMutationDomain, doc: BoardDoc): void {
    if (this.closed || domain === 'viewport') return
    if (domain === 'import') this.protectLocal = true
    // 没连上 / 没 join / 冲突中：现在什么都发不出去，只记住最新一份，签名等 flush 时再算
    //（重连拿到 snapshot 后 flush 会用 accepted 去重）。这是每次改动的热路径——App 每批
    // 命令都会 publish，服务器不可达时不能再为它整份编码。
    if (!this.joined || this.conflicted || this.socket?.readyState !== 1) {
      this.pendingDomain = this.pending ? mergeDomains(this.pendingDomain, domain) : domain
      this.pending = doc
      return
    }
    const signature = signatureOf(doc)
    if (signature === (this.pending ? signatureOf(this.pending) : this.inFlight ? signatureOf(this.inFlight.doc) : this.accepted)) return
    this.pendingDomain = this.pending ? mergeDomains(this.pendingDomain, domain) : domain
    this.pending = doc
    recordSyncLog('publish.queued', { domain, cards: doc.cards.length, ink: doc.ink.length })
    this.flush()
  }

  protectLocalUntilAck(): void {
    this.protectLocal = true
    this.conflicted = false
  }

  retry(): void {
    this.conflicted = false
    if (!this.socket || this.socket.readyState > 1) {
      if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
      this.openSocket()
    } else this.flush()
  }

  mergeParentCards(_childIds: string[], _inkPolicy: 'archive' | 'discard', _parentCard: Card): Promise<ParentMergeResultMessage> {
    return Promise.reject(new Error('母卡合并请使用画布本地操作'))
  }

  close(): void {
    this.closed = true
    this.joined = false
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    if (this.watchdog) clearTimeout(this.watchdog)
    this.reconnectTimer = null
    this.watchdog = null
    const socket = this.socket
    this.socket = null
    socket?.close()
  }

  private openSocket(): void {
    if (this.closed) return
    this.joined = false
    this.setState('connecting')
    recordSyncLog('connect.start')
    const socket = BoardSyncClient.socketFactory?.(this.endpoint) ?? new WebSocket(this.endpoint)
    this.socket = socket
    this.watchdog = setTimeout(() => {
      if (this.socket !== socket) return
      recordSyncLog('socket.timeout')
      socket.close()
    }, 15000)
    socket.onopen = () => {
      if (this.socket !== socket) return
      recordSyncLog('socket.open')
      socket.send(JSON.stringify({ type: 'board.join', boardId: this.boardId, clientId: this.clientId, boardBase64: '' }))
    }
    socket.onmessage = event => {
      if (this.socket !== socket) return
      try { this.handleMessage(event.data) }
      catch { recordSyncLog('message.error', { code: 'invalid_snapshot' }); this.setState('error'); socket.close() }
    }
    socket.onerror = event => {
      if (this.socket !== socket) return
      const reason = String((event as { message?: unknown }).message ?? '').slice(0, 160)
      recordSyncLog('socket.error', { code: 'transport', message: reason })
      this.setState('error')
    }
    socket.onclose = event => {
      if (this.socket !== socket || this.closed) return
      if (this.watchdog) clearTimeout(this.watchdog)
      this.joined = false
      this.socket = null
      // 断线后重连：outstanding 的乐观 revision 全部作废，重连拿到服务器 snapshot 后重发。
      // 快照是累进的，最后发出的那份就包含之前所有改动；但域要把在途各条的域并起来，
      // 否则只按最后一条的域重发，服务器按域合并时会把更早那条改的另一域丢掉。
      let domain: BoardMutationDomain | null = this.pending ? this.pendingDomain : null
      const fold = (next: BoardMutationDomain) => { domain = domain === null ? next : mergeDomains(domain, next) }
      if (this.inFlight) fold(this.inFlight.domain)
      for (const op of this.outstanding.values()) fold(op.domain)
      this.pending ??= this.inFlight?.doc ?? (this.outstanding.size > 0 ? this.lastSent : null)
      if (domain !== null) this.pendingDomain = domain
      this.inFlight = null
      this.outstanding.clear()
      this.setState('error')
      recordSyncLog('socket.close', { code: event.code, delayMs: this.reconnectDelay })
      this.reconnectTimer = setTimeout(() => this.openSocket(), this.reconnectDelay)
      this.reconnectDelay = Math.min(this.reconnectDelay * 2, 8000)
    }
  }

  private handleMessage(raw: string): void {
    const message = JSON.parse(raw) as BoardServerMessage
    if (message.type === 'board.error') {
      recordSyncLog('server.error', { code: message.code ?? 'server' })
      this.setState('error')
      return
    }
    if (message.type !== 'board.snapshot' || message.boardId !== this.boardId) return
    if (!Number.isSafeInteger(message.revision) || message.revision < this.revision) return
    const doc = decodeBoard(base64ToBytes(message.boardBase64))
    const signature = signatureOf(doc)
    const own = !!message.operationId && message.sourceClientId === this.clientId && this.outstanding.has(message.operationId)
    const ownAck = own || (!!message.operationId && message.operationId === this.inFlight?.id && message.sourceClientId === this.clientId)
    this.revision = message.revision
    this.joined = true
    this.reconnectDelay = 800
    if (this.watchdog) clearTimeout(this.watchdog)
    recordSyncLog('snapshot.received', { revision: message.revision, cards: doc.cards.length, ink: doc.ink.length, domain: message.domain ?? '' })
    if (own) {
      // 流水线中某条自有操作的 ack。移除即可；后续更晚的自有 op 会带来更新的 accepted。
      this.outstanding.delete(message.operationId!)
      if (this.outstanding.size === 0) this.accepted = signature
      this.protectLocal = false
      this.setState('connected')
      recordSyncLog('publish.ack', { revision: this.revision, domain: 'pipelined' })
      this.flush()
      return
    }
    if (ownAck) {
      this.accepted = signature
      this.inFlight = null
      this.protectLocal = false
      this.setState('connected')
      recordSyncLog('publish.ack', { revision: this.revision })
      this.flush()
      return
    }
    if (this.outstanding.size > 0) {
      // 外部快照抢先推进了 revision，我们在途的乐观 baseRevision 全部作废（服务器已按
      // baseRevision 拒收）。快照是累进的：把最后发出的那份连同在途各域并成 pending 重放，
      // 否则这些改动就随流水线一起静默丢了。
      let domain: BoardMutationDomain | null = this.pending ? this.pendingDomain : null
      for (const op of this.outstanding.values()) domain = domain === null ? op.domain : mergeDomains(domain, op.domain)
      this.pending ??= this.lastSent
      if (domain !== null) this.pendingDomain = domain
      this.outstanding.clear()
      recordSyncLog('pipeline.reset', { revision: this.revision })
    }
    const local = this.pending ?? this.inFlight?.doc ?? null
    // 服务器按域合并之后，另一端改的是 structure / ink 之一而我们改的也是可合并域时，
    // 不再算冲突：先把对方那一域合进本地（onSnapshot 带 domain），再把本地快照按自己的域重发，
    // 服务器只认我们域里的键，两边改动都保得住。document / import 仍走下面的冲突判定。
    const mergeable = (message.domain === 'structure' || message.domain === 'ink')
      && (!local || this.pendingDomain === 'structure' || this.pendingDomain === 'ink')
      && !this.protectLocal
    if (mergeable) {
      this.accepted = signature
      this.onSnapshot?.(doc, message.revision, message.domain)
      this.setState('connected')
      this.flush()
      return
    }
    if (this.protectLocal && local) {
      if (signatureOf(local) === signature) {
        this.accepted = signature
        this.pending = null
        this.inFlight = null
        this.protectLocal = false
        this.setState('connected')
        recordSyncLog('publish.ack', { revision: this.revision, domain: 'held' })
        return
      }
      this.pending = local
      this.inFlight = null
      this.conflicted = false
      recordSyncLog('snapshot.held', { revision: this.revision })
      this.setState('connected')
      this.flush()
      return
    }
    if (local && signature !== this.accepted) {
      if (signatureOf(local) === signature) {
        this.pending = null
        this.inFlight = null
      } else {
        this.pending = local
        this.inFlight = null
        this.conflicted = true
        this.setState('conflict')
        recordSyncLog('publish.conflict', { revision: this.revision })
        return
      }
    }
    this.accepted = signature
    if (!this.pending && !this.inFlight) this.onSnapshot?.(doc, message.revision, message.domain)
    this.setState('connected')
    this.flush()
  }

  private flush(): void {
    if (this.closed || !this.joined || this.conflicted || !this.pending || this.socket?.readyState !== 1) return
    // import 域走严格单飞（依赖 protectLocal + ack 确认，不能流水线）。
    // 其余累进快照走流水线：不等 ack，用 nextBaseRevision 递增连发，服务器顺序接受。
    const pipelined = this.pendingDomain !== 'import' && !this.protectLocal
    if (this.inFlight && !pipelined) return
    if (pipelined && this.inFlight) this.inFlight = null
    const doc = this.pending
    const domain = this.pendingDomain
    this.pending = null
    const signature = signatureOf(doc)
    if (signature === this.accepted) return
    const operationId = 'op-' + generateId()
    // 乐观 baseRevision：从最新已知 revision 起，随每条在途消息单调递增。
    if (this.outstanding.size === 0) this.nextBaseRevision = this.revision
    const baseRevision = this.nextBaseRevision++
    try {
      this.socket.send(JSON.stringify({ type: 'board.mutate', boardId: this.boardId, clientId: this.clientId,
        operationId, baseRevision, domain, boardBase64: bytesToBase64(encodeBoard(doc)) }))
      recordSyncLog('publish.sent', { revision: baseRevision, cards: doc.cards.length, ink: doc.ink.length, domain })
      this.lastSent = doc
      if (pipelined) this.outstanding.set(operationId, { revision: baseRevision, signature, domain })
      else this.inFlight = { id: operationId, doc, domain }
    } catch {
      this.pending = doc
      this.nextBaseRevision = baseRevision
      this.inFlight = null
      this.setState('error')
      this.socket.close()
    }
  }

  private setState(state: SyncConnectionState): void {
    this.onState?.(state)
    recordSyncLog('connection.state', { state })
  }
}
