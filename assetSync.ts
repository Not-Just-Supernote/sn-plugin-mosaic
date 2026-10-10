import RNFS from 'react-native-fs';
import type { Card } from './React/src/types';
import { imagePathFor } from './imageStore';
import { noteAssetRef, noteTilePath, notePathFor } from './noteStore';
import { plainHttp } from './rawNet';
import { bytesToBase64 } from './React/src/base64';
import { encodeUtf8 } from './React/src/utf8';



const ASSET_SYNC_DEBOUNCE_MS = 1500;
const MAX_ASSET_BYTES = 2 * 1024 * 1024;

const MISSING_RETRY_MS = 30000;

const MAX_NOTE_TILES = 1000;
const NOTE_TILE_FILE = /^(\d+)\.png$/;

type FileSync = 'uploaded' | 'downloaded' | 'none';


async function localNoteTiles(dir: string): Promise<number[]> {
  const entries = await RNFS.readDir(dir).catch(() => []);
  const tiles: number[] = [];
  for (const entry of entries) {
    const match = entry.isFile() ? NOTE_TILE_FILE.exec(entry.name) : null;
    if (match) tiles.push(Number(match[1]));
  }
  return tiles.sort((a, b) => a - b);
}

export class AssetSync {
  
  private uploaded = new Map<string, number>();
  
  private missingAt = new Map<string, number>();
  private timer: ReturnType<typeof setTimeout> | null = null;
  private busy = false;
  private queued: { boardId: string; cards: Card[] } | null = null;

  
  constructor(
    private site: string,
    private readonly onDownloaded: (paths: string[]) => void,
  ) {}

  
  setSite(site: string) {
    if (site === this.site) return;
    this.site = site;
    this.uploaded.clear();
    this.missingAt.clear();
  }

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
        if (card.kind === 'note' && card.noteRef) {
          const noteRef = card.noteRef;
          const dir = notePathFor(noteRef);
          if (dir === '' || seen.has(dir)) continue;
          seen.add(dir);
          const tiles = await localNoteTiles(dir);
          if (tiles.length > 0) {
            for (const tile of tiles) {
              if (await this.syncFile(job.boardId, noteAssetRef(noteRef, tile), noteTilePath(dir, tile), now) === 'uploaded') uploaded += 1;
            }
          } else {
            // Tile count is unknown remotely, so pull tiles in order until one is missing.
            await RNFS.mkdir(dir).catch(() => {});
            let fetched = 0;
            while (fetched < MAX_NOTE_TILES
              && await this.syncFile(job.boardId, noteAssetRef(noteRef, fetched), noteTilePath(dir, fetched), now) === 'downloaded') {
              fetched += 1;
            }
            if (fetched > 0) downloadedPaths.push(dir);
          }
          continue;
        }
        if (card.kind !== 'image' || !card.imageRef) continue;
        const ref = card.imageRef;
        const path = imagePathFor(ref);
        if (path === '' || seen.has(ref)) continue;
        seen.add(ref);
        const result = await this.syncFile(job.boardId, ref, path, now);
        if (result === 'uploaded') uploaded += 1;
        else if (result === 'downloaded') downloadedPaths.push(path);
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

  private async syncFile(boardId: string, ref: string, path: string, now: number): Promise<FileSync> {
    const stat = await RNFS.stat(path).catch(() => null);
    const hasLocal = stat !== null && stat.isFile() && Number(stat.size) > 0;
    if (hasLocal) {
      const size = Number(stat!.size);
      if (size > MAX_ASSET_BYTES) return 'none';
      const mtime = new Date(stat!.mtime as unknown as string | number | Date).getTime();
      if (this.uploaded.get(ref) === mtime) return 'none';
      if (!(await this.upload(boardId, ref, path))) return 'none';
      this.uploaded.set(ref, mtime);
      return 'uploaded';
    }
    const lastMiss = this.missingAt.get(ref);
    if (lastMiss !== undefined && now - lastMiss < MISSING_RETRY_MS) return 'none';
    if (await this.download(boardId, ref, path)) {
      this.missingAt.delete(ref);
      return 'downloaded';
    }
    this.missingAt.set(ref, now);
    return 'none';
  }

  private async upload(boardId: string, ref: string, path: string): Promise<boolean> {
    try {
      const pngBase64 = await RNFS.readFile(path, 'base64');
      const url = `${this.site}api/board/assets/${boardId}/${encodeURIComponent(ref)}`;
      const body = JSON.stringify({ pngBase64 });
      let status: number;
      const raw = await plainHttp('PUT', url, { contentType: 'application/json', bodyBase64: bytesToBase64(encodeUtf8(body)) });
      if (raw !== null) status = raw.status;
      else {
        const response = await fetch(url, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body });
        status = response.status;
      }
      if (status < 200 || status >= 300) {
        console.log(`[MosaicAsset] upload failed ref=${ref} http=${status}`);
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
      const raw = await plainHttp('GET', url);
      if (raw !== null) {
        if (raw.status !== 200) return false;
        await RNFS.writeFile(tmp, raw.bodyBase64, 'base64');
      } else {
        const { promise } = RNFS.downloadFile({ fromUrl: url, toFile: tmp });
        const result = await promise;
        if (result.statusCode !== 200) {
          await RNFS.unlink(tmp).catch(() => {});
          return false;
        }
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
