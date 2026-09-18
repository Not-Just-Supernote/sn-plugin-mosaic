import RNFS from 'react-native-fs';
import type { Card } from './React/src/types';
import { imagePathFor } from './imageStore';
import { notePathFor } from './noteStore';



const ASSET_SYNC_DEBOUNCE_MS = 1500;
const MAX_ASSET_BYTES = 2 * 1024 * 1024;

const MISSING_RETRY_MS = 30000;


export function assetRefFor(card: Card): string | null {
  if (card.kind === 'image') return card.imageRef ?? null;
  if (card.kind === 'note') return card.noteRef ? `${card.noteRef}.png` : null;
  return null;
}


function localPathFor(card: Card): string {
  if (card.kind === 'image') return imagePathFor(card.imageRef);
  if (card.kind === 'note') return notePathFor(card.noteRef);
  return '';
}

export class AssetSync {
  
  private uploaded = new Map<string, number>();
  
  private missingAt = new Map<string, number>();
  private timer: ReturnType<typeof setTimeout> | null = null;
  private busy = false;
  private queued: { boardId: string; cards: Card[] } | null = null;

  
  constructor(
    private readonly site: string,
    private readonly onDownloaded: (paths: string[]) => void,
  ) {}

  schedule(boardId: string, cards: Card[]) {
    if (!boardId) return;
    this.queued = { boardId, cards };
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.run();
    }, ASSET_SYNC_DEBOUNCE_MS);
  }

  dispose() {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
    this.queued = null;
  }

  private async run() {
    if (this.busy) return;
    const job = this.queued;
    this.queued = null;
    if (job === null) return;
    this.busy = true;
    const now = Date.now();
    let uploaded = 0;
    const downloadedPaths: string[] = [];
    try {
      
      const seen = new Set<string>();
      for (const card of job.cards) {
        const ref = assetRefFor(card);
        const path = localPathFor(card);
        if (ref === null || path === '' || seen.has(ref)) continue;
        seen.add(ref);
        const stat = await RNFS.stat(path).catch(() => null);
        const hasLocal = stat !== null && stat.isFile() && Number(stat.size) > 0;
        if (hasLocal) {
          const size = Number(stat!.size);
          if (size > MAX_ASSET_BYTES) { continue; }
          const mtime = new Date(stat!.mtime as unknown as string | number | Date).getTime();
          if (this.uploaded.get(ref) === mtime) continue;
          if (await this.upload(job.boardId, ref, path)) {
            this.uploaded.set(ref, mtime);
            uploaded += 1;
          }
        } else {
          const lastMiss = this.missingAt.get(ref);
          if (lastMiss !== undefined && now - lastMiss < MISSING_RETRY_MS) continue;
          if (await this.download(job.boardId, ref, path)) {
            downloadedPaths.push(path);
            this.missingAt.delete(ref);
          } else {
            this.missingAt.set(ref, now);
          }
        }
      }
      if (uploaded > 0) console.log(`[MosaicAsset] uploaded=${uploaded} board=${job.boardId.slice(0, 8)}`);
      if (downloadedPaths.length > 0) {
        console.log(`[MosaicAsset] downloaded=${downloadedPaths.length} board=${job.boardId.slice(0, 8)}`);
        this.onDownloaded(downloadedPaths);
      }
    } catch (error) {
      console.log(`[MosaicAsset] sync failed: ${String(error)}`);
    } finally {
      this.busy = false;
      
      const next = this.queued as { boardId: string; cards: Card[] } | null;
      if (next !== null) this.schedule(next.boardId, next.cards);
    }
  }

  private async upload(boardId: string, ref: string, path: string): Promise<boolean> {
    try {
      const pngBase64 = await RNFS.readFile(path, 'base64');
      const response = await fetch(`${this.site}api/board/assets/${boardId}/${encodeURIComponent(ref)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ pngBase64 }),
      });
      if (!response.ok) {
        console.log(`[MosaicAsset] upload failed ref=${ref} http=${response.status}`);
        return false;
      }
      return true;
    } catch (error) {
      console.log(`[MosaicAsset] upload error ref=${ref}: ${String(error)}`);
      return false;
    }
  }

  private async download(boardId: string, ref: string, path: string): Promise<boolean> {
    const tmp = `${path}.download`;
    try {
      const url = `${this.site}api/board/assets/${boardId}/${encodeURIComponent(ref)}`;
      const { promise } = RNFS.downloadFile({ fromUrl: url, toFile: tmp });
      const result = await promise;
      if (result.statusCode !== 200) {
        await RNFS.unlink(tmp).catch(() => {});
        return false;
      }
      
      await RNFS.moveFile(tmp, path);
      return true;
    } catch (error) {
      await RNFS.unlink(tmp).catch(() => {});
      console.log(`[MosaicAsset] download error ref=${ref}: ${String(error)}`);
      return false;
    }
  }
}
