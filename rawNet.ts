import { DeviceEventEmitter, NativeModules } from 'react-native';


type RawNetModule = {
  wsOpen(id: number, host: string, port: number, path: string): void;
  wsSend(id: number, text: string): void;
  wsClose(id: number): void;
  httpRequest(method: string, host: string, port: number, path: string, contentType: string | null, bodyBase64: string | null): Promise<{ status: number; bodyBase64: string }>;
};

const native = NativeModules.MosaicRawNet as RawNetModule | undefined;

type RawEvent = { id: number; type: 'open' | 'message' | 'error' | 'close'; data?: string; message?: string; code?: number };
const sockets = new Map<number, RawWebSocket>();
let nextId = 1;
let subscribed = false;

function ensureSubscribed() {
  if (subscribed) return;
  subscribed = true;
  DeviceEventEmitter.addListener('MosaicRawWs', (event: RawEvent) => sockets.get(event.id)?.dispatch(event));
}

function parsePlain(url: string, scheme: 'ws' | 'http'): { host: string; port: number; path: string } | null {
  const match = new RegExp(`^${scheme}://([^/:?#]+)(?::(\\d+))?([/?#].*)?$`, 'i').exec(url);
  if (!match) return null;
  return { host: match[1], port: match[2] ? Number(match[2]) : 80, path: match[3] || '/' };
}


export class RawWebSocket {
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onerror: ((event: { message: string }) => void) | null = null;
  onclose: ((event: { code: number }) => void) | null = null;
  private readonly id = nextId++;
  private closeRequested = false;

  constructor(host: string, port: number, path: string) {
    ensureSubscribed();
    sockets.set(this.id, this);
    native!.wsOpen(this.id, host, port, path);
  }

  send(text: string) {
    if (this.readyState !== 1) return;
    native!.wsSend(this.id, text);
  }

  close() {
    if (this.readyState >= 2) return;
    this.closeRequested = true;
    this.readyState = 2;
    native!.wsClose(this.id);
  }

  dispatch(event: RawEvent) {
    if (event.type === 'open') {
      if (this.closeRequested) { native!.wsClose(this.id); return; }
      this.readyState = 1;
      this.onopen?.();
    } else if (event.type === 'message') {
      this.onmessage?.({ data: event.data ?? '' });
    } else if (event.type === 'error') {
      this.onerror?.({ message: event.message ?? '' });
    } else {
      this.readyState = 3;
      sockets.delete(this.id);
      this.onclose?.({ code: event.code ?? 1006 });
    }
  }
}


export function plainSocketFactory(endpoint: string): WebSocket | null {
  if (!native) return null;
  const target = parsePlain(endpoint, 'ws');
  if (!target) return null;
  return new RawWebSocket(target.host, target.port, target.path) as unknown as WebSocket;
}


export async function plainHttp(
  method: 'GET' | 'PUT',
  url: string,
  options: { contentType?: string; bodyBase64?: string } = {},
): Promise<{ status: number; bodyBase64: string } | null> {
  if (!native) return null;
  const target = parsePlain(url, 'http');
  if (!target) return null;
  return native.httpRequest(method, target.host, target.port, target.path, options.contentType ?? null, options.bodyBase64 ?? null);
}
