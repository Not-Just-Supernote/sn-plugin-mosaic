import { decodeBoard, encodeBoard, type BoardDoc } from './React/src/boardFormat';
import { base64ToBytes, bytesToBase64 } from './React/src/base64';
import { generateId } from './React/src/id';
import type { BoardClientMessage, BoardMutationDomain, BoardServerMessage } from './React/src/syncProtocol';

type SnapshotHandler = (doc: BoardDoc, revision: number) => void;

function encodeBase64(doc: BoardDoc): string {
  return bytesToBase64(encodeBoard(doc));
}

function fingerprint(doc: BoardDoc, domain: BoardMutationDomain): string {
  if (domain === 'structure') return JSON.stringify([doc.cards, doc.connections]);
  if (domain === 'viewport') return JSON.stringify(doc.meta.viewport);
  if (domain === 'ink') return encodeBase64({ ...doc, cards: [], connections: [], blobs: {} });
  return encodeBase64(doc);
}

function webSocketUrl(serverUrl: string): string {
  const normalized = serverUrl.trim().replace(/\/$/, '');
  const socketBase = normalized.replace(/^http:/, 'ws:').replace(/^https:/, 'wss:');
  return `${socketBase}/ws/board`;
}

export class MosaicBoardSyncClient {
  readonly clientId = 'mosaic-' + generateId();
  private socket: WebSocket | null = null;
  private boardId = '';
  private revision = 0;
  private seed: BoardDoc | null = null;
  private endpoint = '';
  private closed = false;
  private reconnectDelay = 800;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private onSnapshot: SnapshotHandler | null = null;
  private fingerprints = new Map<BoardMutationDomain, string>();

  connect(serverUrl: string, boardId: string, seed: BoardDoc, onSnapshot: SnapshotHandler): void {
    this.close();
    this.closed = false;
    this.endpoint = webSocketUrl(serverUrl);
    this.boardId = boardId;
    this.seed = seed;
    this.onSnapshot = onSnapshot;
    this.open();
  }

  publish(domain: BoardMutationDomain, doc: BoardDoc): void {
    const nextFingerprint = fingerprint(doc, domain);
    if (this.fingerprints.get(domain) === nextFingerprint) return;
    if (this.socket?.readyState !== WebSocket.OPEN) return;
    this.fingerprints.set(domain, nextFingerprint);
    this.send({
      type: 'board.mutate',
      boardId: this.boardId,
      clientId: this.clientId,
      operationId: 'op-' + generateId(),
      baseRevision: this.revision,
      domain,
      boardBase64: encodeBase64(doc),
    });
  }

  close(): void {
    this.closed = true;
    if (this.reconnectTimer !== null) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.socket?.close();
    this.socket = null;
  }

  private open(): void {
    if (this.closed || this.seed === null) return;
    const socket = new WebSocket(this.endpoint);
    this.socket = socket;
    socket.onopen = () => {
      this.reconnectDelay = 800;
      this.send({
        type: 'board.join',
        boardId: this.boardId,
        clientId: this.clientId,
        boardBase64: encodeBase64(this.seed!),
      });
    };
    socket.onmessage = event => this.handleMessage(String(event.data));
    socket.onclose = () => {
      if (this.closed) return;
      this.reconnectTimer = setTimeout(() => this.open(), this.reconnectDelay);
      this.reconnectDelay = Math.min(this.reconnectDelay * 2, 8000);
    };
  }

  private handleMessage(raw: string): void {
    const message = JSON.parse(raw) as BoardServerMessage;
    if (message.type !== 'board.snapshot') return;
    const doc = decodeBoard(base64ToBytes(message.boardBase64));
    this.revision = message.revision;
    this.seed = doc;
    this.fingerprints.set('structure', fingerprint(doc, 'structure'));
    this.fingerprints.set('viewport', fingerprint(doc, 'viewport'));
    this.fingerprints.set('ink', fingerprint(doc, 'ink'));
    this.fingerprints.set('document', fingerprint(doc, 'document'));
    this.onSnapshot?.(doc, message.revision);
  }

  private send(message: BoardClientMessage): void {
    if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(message));
  }
}
