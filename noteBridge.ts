import { NativeModules } from 'react-native';
import RNFS from 'react-native-fs';
import {
  NativePluginManager,
  PluginCommAPI,
  PluginFileAPI,
  PluginManager,
} from 'sn-plugin-lib';



const LOG = '[MosaicNoteShot]';
export const RESTORE_TARGET_EVENT = 'MosaicRestoreTarget';

const TYPE_PICTURE = 200;

const MAIN_LAYER = 0;
const FILE_READ_PERMISSION = 'plugin.permission.FILE:READ';
const FILE_WRITE_PERMISSION = 'plugin.permission.FILE:WRITE';
const GRANTED_PERMISSION_STATUSES = new Set([1, 2]);

const USERDATA_MARKER = 'mosaicShot';
const STORE_FILENAME = 'note-shots.json';

const MAX_RECORDS = 300;

const MAX_VERSIONS = 12;

const PAGE_MARGIN = 48;

const LASSO_PAD = 4;

const RENDER_TIMEOUT_MS = 4000;

const SHOT_DIR_FRAGMENT = '/EXPORT/mosaic/shots/';

type MosaicNoteShotNativeModule = {
  takePendingRestore(): Promise<string | null>;
  
  pictureSignature?(path: string, aspect: number): Promise<{ width: number; height: number; sig: string } | null>;
};

type MosaicBoardEngineShotModule = {
  focusNoteShot?(viewTag: number, shotJson: string): void;
  renderNoteShot?(viewTag: number, shotJson: string): Promise<any>;
};

const MosaicNoteShot = NativeModules.MosaicNoteShot as MosaicNoteShotNativeModule | undefined;
const MosaicBoardEngine = NativeModules.MosaicBoardEngine as MosaicBoardEngineShotModule | undefined;

export type ShotRect = { x: number; y: number; w: number; h: number };

export type ShotBox = [number, number, number, number];

export type ShotXYWH = [number, number, number, number];


export interface NoteShotVersion {
  
  w: number;
  h: number;
  sig: string;
  
  hs: ShotXYWH;
  
  r: ShotXYWH;
  f: string;
}


export interface NoteShotRecord {
  id: string;
  
  rect: ShotRect;
  
  cards: Record<string, ShotBox>;
  strokes: Record<string, ShotBox>;
  
  fingerprint: string;
  
  pxPerWorld: number;
  
  versions: NoteShotVersion[];
  notePath: string;
  
  page: number;
  createdAt: string;
  updatedAt: string;
}


export interface NoteShotPng {
  path: string;
  width: number;
  height: number;
  rect: ShotRect;
  
  hotspot: ShotRect;
  cards: Record<string, ShotBox>;
  strokes: Record<string, ShotBox>;
  fingerprint: string;
  pxPerWorld: number;
}


export interface NoteShotTarget {
  id: string;
  
  rect: ShotRect | null;
  
  fingerprint: string | null;
  notePath: string;
  page: number;
  
  num: number;
  
  adopt: boolean;
}

export type NoteShotInsertResult = 'ok' | 'not-note' | 'permission' | 'failed';

type PageRect = { left: number; top: number; right: number; bottom: number };
type PageSize = { width: number; height: number };
type ShotStore = { v: 1; shots: Record<string, NoteShotRecord> };

let storeQueue: Promise<unknown> = Promise.resolve();



async function ensurePermission(permission: string, description: string): Promise<boolean> {
  try {
    const currentStatus = await PluginManager.hasPermission(permission);
    if (GRANTED_PERMISSION_STATUSES.has(currentStatus)) {
      console.log(`${LOG} permission ready name=${permission} status=${currentStatus}`);
      return true;
    }
    const requestedStatus = await PluginManager.requestPermission(permission, description);
    const granted = GRANTED_PERMISSION_STATUSES.has(requestedStatus);
    console.log(`${LOG} permission result name=${permission} status=${requestedStatus} granted=${granted}`);
    return granted;
  } catch (err) {
    console.log(`${LOG} permission request failed name=${permission}: ${err}`);
    return false;
  }
}


export async function ensureArchivePermissions(readReason: string, writeReason: string): Promise<boolean> {
  if (!(await ensurePermission(FILE_READ_PERMISSION, readReason))) return false;
  return ensurePermission(FILE_WRITE_PERMISSION, writeReason);
}

export async function ensureNoteShotPermissions(): Promise<boolean> {
  if (!(await ensurePermission(FILE_READ_PERMISSION, '读取白板截图并插入当前笔记'))) return false;
  return ensurePermission(FILE_WRITE_PERMISSION, '将白板截图写入当前笔记');
}



async function resolveStorePath(): Promise<string> {
  return `${await NativePluginManager.getPluginDirPath()}/${STORE_FILENAME}`;
}

async function readStoreNow(): Promise<ShotStore> {
  try {
    const path = await resolveStorePath();
    if (!(await RNFS.exists(path))) return { v: 1, shots: {} };
    const parsed = JSON.parse(await RNFS.readFile(path, 'utf8')) as ShotStore;
    if (parsed?.v !== 1 || typeof parsed.shots !== 'object' || parsed.shots === null) return { v: 1, shots: {} };
    return parsed;
  } catch (err) {
    console.log(`${LOG} store read reset: ${err}`);
    return { v: 1, shots: {} };
  }
}

async function writeStoreNow(store: ShotStore): Promise<void> {
  const records = Object.values(store.shots)
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    .slice(0, MAX_RECORDS);
  const shots: Record<string, NoteShotRecord> = {};
  for (const record of records) shots[record.id] = record;
  const path = await resolveStorePath();
  const tmpPath = `${path}.tmp`;
  await RNFS.writeFile(tmpPath, JSON.stringify({ v: 1, shots }), 'utf8');
  if (await RNFS.exists(path)) await RNFS.unlink(path);
  await RNFS.moveFile(tmpPath, path);
}

async function updateStore(mutate: (store: ShotStore) => void): Promise<void> {
  const operation = storeQueue.then(async () => {
    const store = await readStoreNow();
    mutate(store);
    await writeStoreNow(store);
  });
  storeQueue = operation.catch(() => {});
  await operation;
}

async function getShotRecord(id: string): Promise<NoteShotRecord | null> {
  await storeQueue.catch(() => {});
  return (await readStoreNow()).shots[id] ?? null;
}

async function saveShotRecord(
  id: string,
  notePath: string,
  page: number,
  png: NoteShotPng,
  version: NoteShotVersion | null,
): Promise<void> {
  const now = new Date().toISOString();
  await updateStore(store => {
    const previous = store.shots[id];
    const versions = (previous?.versions ?? []).filter(v => v.sig !== version?.sig);
    if (version !== null) versions.push(version);
    store.shots[id] = {
      id,
      rect: png.rect,
      cards: png.cards,
      strokes: png.strokes,
      fingerprint: png.fingerprint,
      pxPerWorld: png.pxPerWorld,
      versions: versions.slice(-MAX_VERSIONS),
      notePath,
      page,
      createdAt: previous?.createdAt ?? now,
      updatedAt: now,
    };
  });
}


function shotJsonForNative(
  id: string,
  record: NoteShotRecord | null,
  fallbackRect: ShotRect | null,
  fingerprint?: string | null,
): string {
  return JSON.stringify({
    id,
    rect: record?.rect ?? fallbackRect,
    cards: record?.cards ?? {},
    strokes: record?.strokes ?? {},
    fingerprint: fingerprint || record?.fingerprint || '',
    pxPerWorld: record?.pxPerWorld ?? 0,
  });
}

async function pictureSignature(
  path: unknown,
  aspect = 0,
): Promise<{ width: number; height: number; sig: string } | null> {
  if (typeof path !== 'string' || !path || MosaicNoteShot?.pictureSignature === undefined) return null;
  try {
    const result = await MosaicNoteShot.pictureSignature(path, Number.isFinite(aspect) && aspect > 0 ? aspect : 0);
    return result && result.width > 0 && result.height > 0 && typeof result.sig === 'string' ? result : null;
  } catch (err) {
    console.log(`${LOG} picture signature failed path=${path}: ${err}`);
    return null;
  }
}


function signatureDistance(a: string, b: string): number {
  if (!a || a.length !== b.length) return -1;
  let bits = 0;
  for (let i = 0; i < a.length; i++) {
    let x = parseInt(a[i], 16) ^ parseInt(b[i], 16);
    while (x) { bits += x & 1; x >>= 1; }
  }
  return bits;
}


async function versionOf(
  png: NoteShotPng,
  signature?: { width: number; height: number; sig: string } | null,
): Promise<NoteShotVersion | null> {
  const sig = signature ?? await pictureSignature(png.path, png.width / png.height);
  if (sig === null) return null;
  return {
    w: sig.width,
    h: sig.height,
    sig: sig.sig,
    hs: [round4(png.hotspot.x), round4(png.hotspot.y), round4(png.hotspot.w), round4(png.hotspot.h)],
    r: [Math.round(png.rect.x), Math.round(png.rect.y), Math.round(png.rect.w), Math.round(png.rect.h)],
    f: png.fingerprint,
  };
}



function round4(value: number): number {
  return Math.round(value * 10000) / 10000;
}


function encodeUserData(id: string, png: NoteShotPng): string {
  const { hotspot, rect } = png;
  return JSON.stringify({
    [USERDATA_MARKER]: 1,
    id,
    hs: [round4(hotspot.x), round4(hotspot.y), round4(hotspot.w), round4(hotspot.h)],
    r: [Math.round(rect.x), Math.round(rect.y), Math.round(rect.w), Math.round(rect.h)],
    f: png.fingerprint,
  });
}

function shotIdOf(userData: unknown): string | null {
  if (typeof userData !== 'string' || !userData.includes(USERDATA_MARKER)) return null;
  try {
    const parsed = JSON.parse(userData);
    return parsed?.[USERDATA_MARKER] && typeof parsed.id === 'string' && parsed.id ? parsed.id : null;
  } catch {
    return null;
  }
}



function readRect(value: any): ShotRect | null {
  const x = Number(value?.x), y = Number(value?.y), w = Number(value?.w), h = Number(value?.h);
  return [x, y, w, h].every(Number.isFinite) && w > 0 && h > 0 ? { x, y, w, h } : null;
}


export function noteShotPngFrom(value: any): NoteShotPng | null {
  const rect = readRect(value?.rect);
  const hotspot = readRect(value?.hotspot);
  const width = Number(value?.width);
  const height = Number(value?.height);
  if (typeof value?.path !== 'string' || rect === null || hotspot === null || !(width > 0) || !(height > 0)) return null;
  let anchors: any = {};
  try {
    anchors = typeof value.anchors === 'string' ? JSON.parse(value.anchors) : {};
  } catch {
    anchors = {};
  }
  return {
    path: value.path,
    width,
    height,
    rect,
    hotspot,
    cards: anchors?.cards ?? {},
    strokes: anchors?.strokes ?? {},
    fingerprint: typeof value.fingerprint === 'string' ? value.fingerprint : '',
    pxPerWorld: Number(value.pxPerWorld) > 0 ? Number(value.pxPerWorld) : 0,
  };
}



async function currentFileAndPage(): Promise<{ filePath: string; page: number } | null> {
  const fileRes: any = await PluginCommAPI.getCurrentFilePath();
  const pageRes: any = await PluginCommAPI.getCurrentPageNum();
  if (!fileRes?.success || typeof fileRes.result !== 'string') {
    console.log(`${LOG} getCurrentFilePath failed res=${JSON.stringify(fileRes)}`);
    return null;
  }
  if (!pageRes?.success || typeof pageRes.result !== 'number') {
    console.log(`${LOG} getCurrentPageNum failed res=${JSON.stringify(pageRes)}`);
    return null;
  }
  return { filePath: fileRes.result, page: pageRes.result };
}

async function pageSizeOf(notePath: string, page: number): Promise<PageSize | null> {
  try {
    const res: any = await PluginFileAPI.getPageSize(notePath, page);
    if (res?.success && res.result?.width > 0 && res.result?.height > 0) {
      return { width: res.result.width, height: res.result.height };
    }
    console.log(`${LOG} getPageSize failed res=${JSON.stringify(res)}`);
  } catch (err) {
    console.log(`${LOG} getPageSize threw: ${err}`);
  }
  return null;
}


async function findShotElement(
  notePath: string,
  page: number,
  shotId: string,
  num?: number,
  adopt = false,
): Promise<{ element: any; release: () => void } | null> {
  
  if (typeof num === 'number' && num > 0) {
    const single = await readSingleElement(PluginFileAPI.getElement(notePath, page, num));
    const singleId = single !== null ? shotIdOf(single.userData) : null;
    if (single !== null && single.type === TYPE_PICTURE && (singleId === shotId || (adopt && singleId === null))) {
      return { element: single, release: () => recycleElement(single) };
    }
    if (single !== null) recycleElement(single);
  }
  const res: any = await PluginFileAPI.getElements(page, notePath);
  if (!res?.success) {
    console.log(`${LOG} getElements failed page=${page} res=${JSON.stringify(res)}`);
    return null;
  }
  const elements: any[] = res.result ?? [];
  const release = () => {
    for (const element of elements) recycleElement(element);
  };
  for (let i = elements.length - 1; i >= 0; i--) {
    const element = elements[i];
    if (element?.type === TYPE_PICTURE && shotIdOf(element.userData) === shotId) return { element, release };
  }
  release();
  return null;
}

function recycleElement(element: any): void {
  try { element?.recycle?.(); } catch {  }
}

async function readSingleElement(request: Promise<unknown>): Promise<any | null> {
  try {
    const res: any = await request;
    return res?.success && res.result ? res.result : null;
  } catch (err) {
    console.log(`${LOG} single element read failed: ${err}`);
    return null;
  }
}


async function findInsertedShot(
  notePath: string,
  page: number,
  shotId: string,
): Promise<{ element: any; release: () => void } | null> {
  const last = await readSingleElement(PluginFileAPI.getLastElement());
  if (last !== null && last.type === TYPE_PICTURE && shotIdOf(last.userData) === shotId) {
    return { element: last, release: () => recycleElement(last) };
  }
  if (last !== null) recycleElement(last);
  return findShotElement(notePath, page, shotId);
}

function toPageRect(rect: any): PageRect | null {
  const left = Math.round(Number(rect?.left)), top = Math.round(Number(rect?.top));
  const right = Math.round(Number(rect?.right)), bottom = Math.round(Number(rect?.bottom));
  return [left, top, right, bottom].every(Number.isFinite) && right > left && bottom > top
    ? { left, top, right, bottom }
    : null;
}


function hostKeepsOwnCopy(storedPath: unknown): boolean {
  return typeof storedPath === 'string' && storedPath.length > 0 && !storedPath.includes(SHOT_DIR_FRAGMENT);
}

async function deleteFile(path: string): Promise<void> {
  try {
    if (await RNFS.exists(path)) await RNFS.unlink(path);
  } catch (err) {
    console.log(`${LOG} delete ${path} failed: ${err}`);
  }
}

const clamp = (value: number, lo: number, hi: number): number => (hi < lo ? lo : Math.min(hi, Math.max(lo, value)));


function placeInPage(png: NoteShotPng, screen: PageSize, page: PageSize): PageRect {
  const k = screen.width > 0 && screen.height > 0 ? Math.min(page.width / screen.width, page.height / screen.height) : 1;
  let w = png.width * k;
  let h = png.height * k;
  const s = Math.min(1, (page.width - 2 * PAGE_MARGIN) / w, (page.height - 2 * PAGE_MARGIN) / h);
  w = Math.max(1, Math.round(w * s));
  h = Math.max(1, Math.round(h * s));
  const left = Math.round((page.width - w) / 2);
  const top = Math.round((page.height - h) / 2);
  return { left, top, right: left + w, bottom: top + h };
}


function refitInPage(old: PageRect, ratio: number, png: NoteShotPng, page: PageSize): PageRect {
  const aspect = png.height / Math.max(1, png.width);
  let w = (old.right - old.left) * (Number.isFinite(ratio) && ratio > 0 ? ratio : 1);
  let h = w * aspect;
  const s = Math.min(1, (page.width - 2 * PAGE_MARGIN) / w, (page.height - 2 * PAGE_MARGIN) / h);
  w = Math.max(1, Math.round(w * s));
  h = Math.max(1, Math.round(h * s));
  const left = Math.round(clamp(old.left, 0, page.width - w));
  const top = Math.round(clamp(old.top, 0, page.height - h));
  return { left, top, right: left + w, bottom: top + h };
}


async function selectInsertedPicture(rect: PageRect, page: PageSize): Promise<void> {
  const lassoRect = {
    left: Math.max(0, rect.left - LASSO_PAD),
    top: Math.max(0, rect.top - LASSO_PAD),
    right: Math.min(Math.floor(page.width), rect.right + LASSO_PAD),
    bottom: Math.min(Math.floor(page.height), rect.bottom + LASSO_PAD),
  };
  try {
    const res: any = await PluginCommAPI.lassoElements(lassoRect);
    const countsRes: any = await PluginCommAPI.getLassoElementTypeCounts();
    const counts: Record<string, unknown> | null = countsRes?.success ? countsRes.result : null;
    const others = counts === null
      ? -1
      : Object.keys(counts).filter(key => key !== 'bitmapNum').reduce((sum, key) => sum + (Number(counts[key]) || 0), 0);
    console.log(`${LOG} lasso inserted picture res=${JSON.stringify(res)} counts=${JSON.stringify(counts)}`);
    if (res?.success && res.result === true && counts?.bitmapNum === 1 && others === 0) return;
    const clearRes: any = await PluginCommAPI.setLassoBoxState(2);
    console.log(`${LOG} lasso cleared (picture not alone) res=${JSON.stringify(clearRes)}`);
  } catch (err) {
    console.log(`${LOG} lasso inserted picture failed: ${err}`);
  }
}


export async function insertNoteShot(
  shotId: string,
  png: NoteShotPng,
  screen: PageSize,
): Promise<NoteShotInsertResult> {
  const startedAt = Date.now();
  let keepPng = false;
  try {
    if (!(await ensureNoteShotPermissions())) return 'permission';
    const target = await currentFileAndPage();
    if (target === null) return 'failed';
    if (!target.filePath.toLowerCase().endsWith('.note')) {
      console.log(`${LOG} insert refused: not a note file=${target.filePath}`);
      return 'not-note';
    }
    const pageSize = await pageSizeOf(target.filePath, target.page);
    if (pageSize === null) return 'failed';
    const rect = placeInPage(png, screen, pageSize);

    const created: any = await PluginCommAPI.createElement(TYPE_PICTURE);
    if (!created?.success || !created.result) {
      console.log(`${LOG} createElement(picture) failed res=${JSON.stringify(created)}`);
      return 'failed';
    }
    const element: any = created.result;
    let inserted = false;
    try {
      element.pageNum = target.page;
      element.layerNum = MAIN_LAYER;
      element.picture = { picturePath: png.path, rect };
      element.userData = encodeUserData(shotId, png);
      const res: any = await PluginCommAPI.insertPageElements([element], target.page, MAIN_LAYER);
      console.log(`${LOG} insertPageElements res=${JSON.stringify(res)} page=${target.page} rect=${JSON.stringify(rect)} pageSize=${pageSize.width}x${pageSize.height} png=${png.width}x${png.height}`);
      inserted = res?.success === true && res.result === true;
    } finally {
      recycleElement(element);
    }
    if (!inserted) return 'failed';
    keepPng = true;

    
    
    let pictureRect = rect;
    let hostSignature: { width: number; height: number; sig: string } | null = null;
    try {
      const found = await findInsertedShot(target.filePath, target.page, shotId);
      if (found === null) {
        console.log(`${LOG} insert verify: picture with userData not found on page=${target.page}`);
      } else {
        try {
          const stored = found.element.picture;
          pictureRect = toPageRect(stored?.rect) ?? rect;
          keepPng = !hostKeepsOwnCopy(stored?.picturePath);
          const storedPath = typeof stored?.picturePath === 'string' ? stored.picturePath : '';
          const exists = storedPath ? await RNFS.exists(storedPath) : false;
          hostSignature = await pictureSignature(storedPath, png.width / png.height);
          console.log(`${LOG} insert verify num=${found.element.numInPage} rect=${JSON.stringify(stored?.rect)} path=${storedPath} exists=${exists} userData=${found.element.userData}`);
        } finally {
          found.release();
        }
      }
    } catch (err) {
      console.log(`${LOG} insert verify failed: ${err}`);
    }
    const ownSignature = await pictureSignature(png.path, png.width / png.height);
    console.log(`${LOG} insert signature own=${ownSignature?.width}x${ownSignature?.height} host=${hostSignature?.width}x${hostSignature?.height} distance=${ownSignature && hostSignature ? signatureDistance(ownSignature.sig, hostSignature.sig) : 'n/a'}`);
    await saveShotRecord(shotId, target.filePath, target.page, png, await versionOf(png, hostSignature ?? ownSignature));
    await selectInsertedPicture(pictureRect, pageSize);
    console.log(`${LOG} insert ok id=${shotId} note=${target.filePath} page=${target.page} in ${Date.now() - startedAt}ms`);
    return 'ok';
  } catch (err) {
    console.log(`${LOG} insert failed: ${err}`);
    return 'failed';
  } finally {
    if (!keepPng) await deleteFile(png.path);
  }
}


async function updateNoteShot(target: NoteShotTarget, record: NoteShotRecord | null, png: NoteShotPng): Promise<boolean> {
  const startedAt = Date.now();
  let keepPng = false;
  let staleOwnPng: string | null = null;
  try {
    const current = await currentFileAndPage();
    if (current === null || current.filePath !== target.notePath) {
      console.log(`${LOG} update skipped: current file=${current?.filePath} shot note=${target.notePath}`);
      return false;
    }
    const pageSize = await pageSizeOf(target.notePath, target.page);
    if (pageSize === null) return false;
    
    const found = await findShotElement(target.notePath, target.page, target.id, target.num, target.adopt);
    if (found === null) {
      console.log(`${LOG} update skipped: picture id=${target.id} num=${target.num} no longer on page=${target.page}`);
      return false;
    }
    let modified = false;
    try {
      const element = found.element;
      const old = toPageRect(element.picture?.rect);
      if (old === null) return false;
      const oldPath = element.picture?.picturePath;
      
      const previousWidth = target.rect?.w ?? record?.rect.w ?? png.rect.w;
      const next = refitInPage(old, previousWidth > 0 ? png.rect.w / previousWidth : 1, png, pageSize);
      element.picture = { picturePath: png.path, rect: next };
      
      element.userData = encodeUserData(target.id, png);
      
      
      element.pageNum = target.page;
      const layer = typeof element.layerNum === 'number' && element.layerNum >= 0 ? element.layerNum : MAIN_LAYER;
      element.layerNum = layer;
      const res: any = await PluginCommAPI.modifyPageElements([element], target.page, layer);
      modified = res?.success === true && Array.isArray(res.result) && res.result.length > 0;
      console.log(`${LOG} modifyPageElements res=${JSON.stringify(res)} id=${target.id} num=${target.num} adopt=${target.adopt} rect=${JSON.stringify(old)} -> ${JSON.stringify(next)}`);
      if (modified) {
        const ownCopy = hostKeepsOwnCopy(oldPath);
        keepPng = !ownCopy;
        
        if (!ownCopy && typeof oldPath === 'string' && oldPath !== png.path) staleOwnPng = oldPath;
      }
    } finally {
      found.release();
    }
    if (!modified) return false;
    
    let hostSignature: { width: number; height: number; sig: string } | null = null;
    try {
      const back = await findShotElement(target.notePath, target.page, target.id, target.num);
      if (back !== null) {
        try {
          hostSignature = await pictureSignature(back.element.picture?.picturePath, png.width / png.height);
        } finally {
          back.release();
        }
      }
    } catch (err) {
      console.log(`${LOG} update read-back failed id=${target.id}: ${err}`);
    }
    await saveShotRecord(target.id, target.notePath, target.page, png, await versionOf(png, hostSignature));
    console.log(`${LOG} update ok id=${target.id} page=${target.page} in ${Date.now() - startedAt}ms`);
    return true;
  } catch (err) {
    console.log(`${LOG} update failed id=${target.id}: ${err}`);
    return false;
  } finally {
    if (!keepPng) await deleteFile(png.path);
    if (staleOwnPng !== null) await deleteFile(staleOwnPng);
  }
}

function withTimeout<T>(promise: Promise<T>, ms: number, label: string): Promise<T | null> {
  return new Promise(resolve => {
    const timer = setTimeout(() => {
      console.log(`${LOG} ${label} timed out after ${ms}ms`);
      resolve(null);
    }, ms);
    promise.then(
      value => { clearTimeout(timer); resolve(value); },
      err => { clearTimeout(timer); console.log(`${LOG} ${label} failed: ${err}`); resolve(null); },
    );
  });
}


export async function consumeNativePendingRestore(): Promise<NoteShotTarget | null> {
  if (MosaicNoteShot === undefined) return null;
  try {
    const json = await MosaicNoteShot.takePendingRestore();
    if (typeof json !== 'string') return null;
    const raw = JSON.parse(json);
    if (typeof raw?.id !== 'string' || !raw.id) return null;
    const r = Array.isArray(raw.r) && raw.r.length >= 4
      ? readRect({ x: raw.r[0], y: raw.r[1], w: raw.r[2], h: raw.r[3] })
      : null;
    return {
      id: raw.id,
      rect: r,
      fingerprint: typeof raw.f === 'string' && raw.f ? raw.f : null,
      notePath: typeof raw.notePath === 'string' ? raw.notePath : '',
      page: Number(raw.page),
      num: Number(raw.num),
      adopt: raw.adopt === true,
    };
  } catch (err) {
    console.log(`${LOG} native pending restore read failed: ${err}`);
    return null;
  }
}


export async function focusNoteShot(viewTag: number, target: NoteShotTarget): Promise<void> {
  const record = await getShotRecord(target.id);
  console.log(`${LOG} focus id=${target.id} record=${record !== null} note=${target.notePath} page=${target.page} num=${target.num} adopt=${target.adopt}`);
  MosaicBoardEngine?.focusNoteShot?.(viewTag, shotJsonForNative(target.id, record, target.rect));
}


export async function refreshNoteShot(viewTag: number, target: NoteShotTarget): Promise<void> {
  const engine = MosaicBoardEngine;
  if (engine?.renderNoteShot === undefined) return;
  const record = await getShotRecord(target.id);
  const result: any = await withTimeout(
    engine.renderNoteShot(viewTag, shotJsonForNative(target.id, record, target.rect, target.fingerprint)),
    RENDER_TIMEOUT_MS,
    'render',
  );
  if (result === null || result === undefined) {
    console.log(`${LOG} refresh id=${target.id}: no render`);
    return;
  }
  if (result.unchanged === true) {
    console.log(`${LOG} refresh id=${target.id}: content unchanged`);
    return;
  }
  const png = noteShotPngFrom(result);
  if (png === null) {
    console.log(`${LOG} refresh id=${target.id}: bad render payload`);
    return;
  }
  await updateNoteShot(target, record, png);
}
