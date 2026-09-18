import { DeviceEventEmitter, NativeModules } from 'react-native';
import RNFS from 'react-native-fs';
import {
  PluginCommAPI,
  PluginFileAPI,
  PluginManager,
  PluginNoteAPI,
  type Rect,
} from 'sn-plugin-lib';

const LOG = '[MosaicNoteShot]';
export const NOTE_SHOT_USERDATA_PREFIX = 'MOSAIC:';
export const RESTORE_TARGET_EVENT = 'MosaicRestoreTarget';
const BOARD_VISIBILITY_EVENT = 'MosaicBoardVisibility';

const TYPE_PICTURE = 200;
const FILE_READ_PERMISSION = 'plugin.permission.FILE:READ';
const FILE_WRITE_PERMISSION = 'plugin.permission.FILE:WRITE';
const GRANTED_PERMISSION_STATUSES = new Set([1, 2]);
const TAP_MAX_DISTANCE_PX = 16;
const TAP_MAX_DURATION_MS = 450;
const TAP_HIT_PAD_PX = 8;
const FINGER_TOOL_TYPE = 1;
const REGISTRY_FILENAME = 'mosaic-note-shot-links.json';
const MAX_REGISTRY_LINKS = 512;

type MosaicNoteShotNativeModule = {
  takePendingRestore(): Promise<string | null>;
};

const MosaicNoteShot = NativeModules.MosaicNoteShot as MosaicNoteShotNativeModule | undefined;

export interface NoteShotMeta {
  v: 1;
  wbId: string;
  wbName: string;
  
  rect: { x: number; y: number; w: number; h: number };
  
  hotspot?: { x: number; y: number; w: number; h: number };
  capturedAt: string;
}

type NoteShotLink = {
  id: string;
  notePath: string;
  page: number;
  pngBasename: string;
  baselinePictureKeys: string[];
  elementKey?: string;
  
  insertedBasename?: string;
  
  insertedUuid?: string;
  meta: NoteShotMeta;
  createdAt: string;
};

type NoteShotRegistry = {
  v: 1;
  links: NoteShotLink[];
};

type PictureRecord = {
  element: any;
  key: string;
  basename: string;
};

let registryPathPromise: Promise<string> | null = null;
let registryQueue: Promise<unknown> = Promise.resolve();

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

export async function ensureNoteShotPermissions(): Promise<boolean> {
  const readGranted = await ensurePermission(
    FILE_READ_PERMISSION,
    '读取白板截图并插入当前笔记',
  );
  if (!readGranted) return false;
  return ensurePermission(
    FILE_WRITE_PERMISSION,
    '将白板截图写入当前笔记',
  );
}

function decodeNoteShotMeta(userData: unknown): NoteShotMeta | null {
  if (typeof userData !== 'string' || !userData.startsWith(NOTE_SHOT_USERDATA_PREFIX)) return null;
  return parseNoteShotMeta(userData.slice(NOTE_SHOT_USERDATA_PREFIX.length));
}

function parseNoteShotMeta(json: string): NoteShotMeta | null {
  try {
    const parsed = JSON.parse(json) as NoteShotMeta;
    if (parsed?.v !== 1 || typeof parsed.wbId !== 'string' || typeof parsed.rect?.w !== 'number') return null;
    return parsed;
  } catch {
    return null;
  }
}

export async function consumeNativePendingRestore(): Promise<NoteShotMeta | null> {
  if (MosaicNoteShot === undefined) return null;
  try {
    const json = await MosaicNoteShot.takePendingRestore();
    return typeof json === 'string' ? parseNoteShotMeta(json) : null;
  } catch (err) {
    console.log(`${LOG} native pending restore read failed: ${err}`);
    return null;
  }
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

const delay = (ms: number): Promise<void> => new Promise<void>(resolve => setTimeout(resolve, ms));

function pathBasename(path: string): string {
  const index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
  return index >= 0 ? path.slice(index + 1) : path;
}

function pictureKey(element: any): string {
  if (typeof element?.numInPage === 'number') return `n:${element.numInPage}`;
  return `p:${pathBasename(String(element?.picture?.picturePath ?? 'unknown'))}`;
}

function pictureRecords(elements: any[]): PictureRecord[] {
  return elements
    .filter(element => element?.type === TYPE_PICTURE)
    .map(element => ({
      element,
      key: pictureKey(element),
      basename: pathBasename(String(element?.picture?.picturePath ?? '')),
    }));
}

async function resolveRegistryPath(): Promise<string> {
  if (registryPathPromise === null) {
    registryPathPromise = (async () => {
      return `${RNFS.DocumentDirectoryPath}/${REGISTRY_FILENAME}`;
    })();
  }
  return registryPathPromise;
}

async function readRegistryNow(): Promise<NoteShotRegistry> {
  try {
    const path = await resolveRegistryPath();
    if (!(await RNFS.exists(path))) return { v: 1, links: [] };
    const parsed = JSON.parse(await RNFS.readFile(path, 'utf8')) as NoteShotRegistry;
    if (parsed?.v !== 1 || !Array.isArray(parsed.links)) return { v: 1, links: [] };
    return parsed;
  } catch (err) {
    console.log(`${LOG} registry read reset: ${err}`);
    return { v: 1, links: [] };
  }
}

async function writeRegistryNow(registry: NoteShotRegistry): Promise<void> {
  const path = await resolveRegistryPath();
  const tmpPath = `${path}.tmp`;
  const links = [...registry.links]
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
    .slice(-MAX_REGISTRY_LINKS);
  await RNFS.writeFile(tmpPath, JSON.stringify({ v: 1, links }), 'utf8');
  if (await RNFS.exists(path)) await RNFS.unlink(path);
  await RNFS.moveFile(tmpPath, path);
}

async function readRegistry(): Promise<NoteShotRegistry> {
  await registryQueue.catch(() => {});
  return readRegistryNow();
}

async function updateRegistry(
  mutate: (registry: NoteShotRegistry) => void | Promise<void>,
): Promise<void> {
  const operation = registryQueue.then(async () => {
    const registry = await readRegistryNow();
    await mutate(registry);
    await writeRegistryNow(registry);
  });
  registryQueue = operation.catch(() => {});
  await operation;
}

async function pagePictureKeys(filePath: string, page: number): Promise<string[]> {
  try {
    const response: any = await PluginFileAPI.getElements(page, filePath);
    if (!response?.success) return [];
    const elements: any[] = response.result ?? [];
    try {
      return pictureRecords(elements).map(record => record.key);
    } finally {
      for (const element of elements) {
        try { element.recycle?.(); } catch {  }
      }
    }
  } catch (err) {
    console.log(`${LOG} baseline read skipped: ${err}`);
    return [];
  }
}

async function registerPendingLink(
  notePath: string,
  page: number,
  pngPath: string,
  baselinePictureKeys: string[],
  meta: NoteShotMeta,
): Promise<string> {
  const id = `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
  await updateRegistry(registry => {
    registry.links.push({
      id,
      notePath,
      page,
      pngBasename: pathBasename(pngPath),
      baselinePictureKeys,
      meta,
      createdAt: meta.capturedAt,
    });
  });
  console.log(`${LOG} registry pending id=${id} page=${page} baseline=${baselinePictureKeys.length} wb=${meta.wbId}`);
  return id;
}


async function bindInsertedShot(
  filePath: string,
  page: number,
  baselinePictureKeys: string[],
  linkId: string,
): Promise<void> {
  try {
    const res: any = await PluginFileAPI.getElements(page, filePath);
    if (!res?.success) return;
    const elements: any[] = res.result ?? [];
    try {
      const added = pictureRecords(elements).filter(picture => !baselinePictureKeys.includes(picture.key));
      const chosen = added.length > 0 ? added[added.length - 1] : undefined;
      if (chosen === undefined) return;
      const uuid = typeof chosen.element?.uuid === 'string' ? chosen.element.uuid : undefined;
      await updateRegistry(latest => {
        const link = latest.links.find(candidate => candidate.id === linkId);
        if (link === undefined) return;
        link.insertedBasename = chosen.basename;
        link.elementKey = chosen.key;
        if (uuid !== undefined) link.insertedUuid = uuid;
      });
      console.log(`${LOG} registry bound-insert id=${linkId} basename=${chosen.basename} uuid=${uuid ?? 'n/a'}`);
    } finally {
      for (const element of elements) {
        try { element.recycle?.(); } catch {  }
      }
    }
  } catch (err) {
    console.log(`${LOG} bind-insert failed: ${err}`);
  }
}

async function registryMetaByPicture(
  filePath: string,
  page: number,
  pictures: PictureRecord[],
): Promise<Map<string, NoteShotMeta>> {
  const registry = await readRegistry();
  const pageLinks = registry.links
    .filter(link => link.notePath === filePath && link.page === page)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  const resolved = new Map<string, NoteShotMeta>();
  const claimed = new Set<string>();
  const bindings = new Map<string, string>();

  for (const link of pageLinks) {
    if (link.elementKey === undefined) continue;
    const picture = pictures.find(candidate => candidate.key === link.elementKey);
    if (picture === undefined) continue;
    resolved.set(picture.key, link.meta);
    claimed.add(picture.key);
  }

  for (const link of pageLinks) {
    if (link.elementKey !== undefined) continue;
    const available = pictures.filter(picture => (
      !claimed.has(picture.key) && !link.baselinePictureKeys.includes(picture.key)
    ));
    const basenameMatches = available.filter(picture => picture.basename === link.pngBasename);
    const match = basenameMatches.length === 1
      ? basenameMatches[0]
      : available.length === 1
        ? available[0]
        : undefined;
    if (match === undefined) continue;
    resolved.set(match.key, link.meta);
    claimed.add(match.key);
    bindings.set(link.id, match.key);
  }

  if (bindings.size > 0) {
    await updateRegistry(latest => {
      for (const link of latest.links) {
        const key = bindings.get(link.id);
        if (key !== undefined) link.elementKey = key;
      }
    });
    console.log(`${LOG} registry bound page=${page} count=${bindings.size}`);
  }

  return resolved;
}


export async function insertCaptureIntoNote(pngPath: string, meta: NoteShotMeta): Promise<boolean> {
  const startedAt = Date.now();
  const target = await currentFileAndPage();
  if (target === null) return false;
  const { filePath, page } = target;
  const baselinePictureKeys = await pagePictureKeys(filePath, page);
  console.log(`${LOG} insert begin file=${filePath} page=${page} png=${pngPath} wb=${meta.wbId} baseline=${baselinePictureKeys.length}`);

  try {
    const clearRes: any = await PluginCommAPI.setLassoBoxState(2);
    console.log(`${LOG} pre-clear lasso box res=${JSON.stringify(clearRes)}`);
  } catch (err) {
    console.log(`${LOG} pre-clear lasso box skipped: ${err}`);
  }

  const insertRes: any = await PluginNoteAPI.insertImage(pngPath);
  console.log(`${LOG} insertImage res=${JSON.stringify(insertRes)}`);
  if (!insertRes?.success) {
    console.log(`${LOG} insertImage FAILED`);
    return false;
  }

  let linkId: string;
  try {
    linkId = await registerPendingLink(filePath, page, pngPath, baselinePictureKeys, meta);
  } catch (err) {
    console.log(`${LOG} registry pending write failed: ${err}`);
    return false;
  }

  await delay(300);
  await bindInsertedShot(filePath, page, baselinePictureKeys, linkId);
  await logPageElements(filePath, page);
  console.log(`${LOG} insert ok in ${Date.now() - startedAt}ms wb=${meta.wbId}`);
  return true;
}


async function locateNoteShotPicture(
  link: NoteShotLink,
): Promise<{ page: number; numInPage: number } | null> {
  const notePath = link.notePath;
  const wantBasename = link.insertedBasename ?? link.pngBasename;
  const wantUuid = link.insertedUuid;
  let totalPages = 0;
  try {
    const res: any = await PluginFileAPI.getNoteTotalPageNum(notePath);
    if (res?.success && typeof res.result === 'number') totalPages = res.result;
  } catch (err) {
    console.log(`${LOG} total pages read failed: ${err}`);
  }
  if (totalPages <= 0) return null;
  for (let page = 0; page < totalPages; page++) {
    let elements: any[] = [];
    try {
      const res: any = await PluginFileAPI.getElements(page, notePath);
      if (!res?.success) continue;
      elements = res.result ?? [];
      for (const picture of pictureRecords(elements)) {
        if (typeof picture.element?.numInPage !== 'number') continue;
        const uuidHit = wantUuid !== undefined && picture.element?.uuid === wantUuid;
        const basenameHit = picture.basename === wantBasename;
        if (uuidHit || basenameHit) {
          return { page, numInPage: picture.element.numInPage };
        }
      }
    } catch (err) {
      console.log(`${LOG} locate scan failed page=${page}: ${err}`);
    } finally {
      for (const element of elements) {
        try { element.recycle?.(); } catch {  }
      }
    }
  }
  return null;
}


export async function removeCaptureFromNote(wbId: string): Promise<boolean> {
  const granted = await ensureNoteShotPermissions();
  if (!granted) {
    console.log(`${LOG} removeClip paused while file permission awaits approval wb=${wbId}`);
    return false;
  }
  const target = await currentFileAndPage();
  const currentPath = target?.filePath ?? null;
  const registry = await readRegistry();
  const links = registry.links
    .filter(link => link.meta.wbId === wbId && (currentPath === null || link.notePath === currentPath))
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  if (links.length === 0) {
    console.log(`${LOG} removeClip no link wb=${wbId} note=${currentPath}`);
    return false;
  }
  let deletedAny = false;
  const clearedIds = new Set<string>();
  for (const link of links) {
    const located = await locateNoteShotPicture(link);
    if (located !== null) {
      try {
        const res: any = await PluginFileAPI.deleteElements(link.notePath, located.page, [located.numInPage]);
        if (res?.success && res.result === true) {
          deletedAny = true;
          console.log(`${LOG} removeClip deleted wb=${wbId} note=${link.notePath} page=${located.page} num=${located.numInPage}`);
        } else {
          console.log(`${LOG} removeClip delete failed res=${JSON.stringify(res)}`);
        }
      } catch (err) {
        console.log(`${LOG} removeClip delete error: ${err}`);
      }
    } else {
      console.log(`${LOG} removeClip picture already gone wb=${wbId} basename=${link.pngBasename}`);
    }
    clearedIds.add(link.id);
  }
  if (clearedIds.size > 0) {
    await updateRegistry(latest => {
      latest.links = latest.links.filter(link => !clearedIds.has(link.id));
    });
  }
  if (deletedAny) {
    try { await PluginCommAPI.reloadFile(); } catch (err) { console.log(`${LOG} removeClip reload skipped: ${err}`); }
  }
  return deletedAny;
}


export async function clippedWhiteboardIds(): Promise<string[]> {
  const target = await currentFileAndPage();
  const currentPath = target?.filePath ?? null;
  const registry = await readRegistry();
  const ids = new Set<string>();
  for (const link of registry.links) {
    if (currentPath === null || link.notePath === currentPath) ids.add(link.meta.wbId);
  }
  return [...ids];
}

async function logPageElements(filePath: string, page: number): Promise<void> {
  try {
    const pageRes: any = await PluginCommAPI.getCurrentPageNum();
    const res: any = await PluginFileAPI.getElements(page, filePath);
    if (!res?.success) {
      console.log(`${LOG} dump getElements failed res=${JSON.stringify(res)}`);
      return;
    }
    const elements: any[] = res.result ?? [];
    const summary = elements
      .map(element => element?.type === TYPE_PICTURE
        ? `pic#${element.numInPage}@${JSON.stringify(element.picture?.rect)}f=${pathBasename(String(element.picture?.picturePath ?? '?'))}`
        : `t${element?.type}#${element?.numInPage}`)
      .join(', ');
    console.log(`${LOG} dump insertPage=${page} currentPage=${pageRes?.result} count=${elements.length} [${summary}]`);
    for (const element of elements) {
      try { element.recycle?.(); } catch {  }
    }
  } catch (err) {
    console.log(`${LOG} dump failed: ${err}`);
  }
}

let boardVisible = false;
let pendingRestore: NoteShotMeta | null = null;
let tapBusy = false;
let motionSeen = false;
let tapDown: { x: number; y: number; toolType: number; at: number } | null = null;

export function setBoardVisible(visible: boolean, source = 'app'): void {
  if (boardVisible === visible) {
    console.log(`${LOG} boardVisible=${visible} source=${source} unchanged`);
    return;
  }
  boardVisible = visible;
  console.log(`${LOG} boardVisible=${visible} source=${source}`);
}

export function attachBoardVisibilityListener(): void {
  DeviceEventEmitter.addListener(BOARD_VISIBILITY_EVENT, (event: any) => {
    setBoardVisible(event?.visible === true, 'native-view');
  });
  console.log(`${LOG} board visibility listener registered`);
}

export function consumePendingRestore(): NoteShotMeta | null {
  const target = pendingRestore;
  pendingRestore = null;
  return target;
}

function rectContains(rect: Rect, x: number, y: number, pad: number): boolean {
  return x >= rect.left - pad && x <= rect.right + pad && y >= rect.top - pad && y <= rect.bottom + pad;
}

function noteShotHotspotRect(rect: Rect, meta: NoteShotMeta): Rect {
  const hotspot = meta.hotspot ?? { x: 0, y: 0, w: 0.36, h: 0.08 };
  const width = rect.right - rect.left;
  const height = rect.bottom - rect.top;
  return {
    left: rect.left + width * hotspot.x,
    top: rect.top + height * hotspot.y,
    right: rect.left + width * (hotspot.x + hotspot.w),
    bottom: rect.top + height * (hotspot.y + hotspot.h),
  };
}

async function handleNoteTap(x: number, y: number, toolType: number): Promise<void> {
  if (tapBusy) return;
  tapBusy = true;
  const startedAt = Date.now();
  try {
    const target = await currentFileAndPage();
    if (target === null) return;
    const { filePath, page } = target;
    const elementsRes: any = await PluginFileAPI.getElements(page, filePath);
    if (!elementsRes?.success) {
      console.log(`${LOG} tap getElements failed res=${JSON.stringify(elementsRes)}`);
      return;
    }
    const elements: any[] = elementsRes.result ?? [];
    const pictures = pictureRecords(elements);
    const registryMeta = await registryMetaByPicture(filePath, page, pictures);
    let hit: NoteShotMeta | null = null;
    let shotCount = 0;
    try {
      for (const picture of pictures) {
        const meta = decodeNoteShotMeta(picture.element.userData) ?? registryMeta.get(picture.key) ?? null;
        if (meta === null) continue;
        shotCount++;
        if (hit !== null || !picture.element.picture?.rect) continue;
        const screenRect = picture.element.picture.rect as Rect;
        const hotspotRect = noteShotHotspotRect(screenRect, meta);
        const contains = rectContains(hotspotRect, x, y, TAP_HIT_PAD_PX);
        console.log(`${LOG} tap test wb=${meta.wbId} key=${picture.key} hotspot=(${hotspotRect.left.toFixed(0)},${hotspotRect.top.toFixed(0)},${hotspotRect.right.toFixed(0)},${hotspotRect.bottom.toFixed(0)}) tap=(${x.toFixed(0)},${y.toFixed(0)}) hit=${contains}`);
        if (contains) hit = meta;
      }
    } finally {
      for (const element of elements) {
        try { element.recycle?.(); } catch {  }
      }
    }

    console.log(`${LOG} tap scan page=${page} tool=${toolType} pictures=${pictures.length} shots=${shotCount} hit=${hit?.wbId ?? 'none'} in ${Date.now() - startedAt}ms`);
    if (hit === null) return;

    pendingRestore = hit;
    DeviceEventEmitter.emit(RESTORE_TARGET_EVENT);
    const showRes: any = await PluginManager.showPluginView();
    console.log(`${LOG} showPluginView res=${JSON.stringify(showRes)}`);
  } catch (err) {
    console.log(`${LOG} tap handling failed: ${err}`);
  } finally {
    tapBusy = false;
  }
}

function supportedTapTool(toolType: unknown): toolType is number {
  return toolType === FINGER_TOOL_TYPE;
}


export function attachNoteTapListener(): void {
  try {
    PluginManager.registerMotionListener(1, {
      onMsg: (event: any) => {
        if (!motionSeen) {
          motionSeen = true;
          console.log(`${LOG} first motion event action=${event?.action} tool=${event?.toolType} pointers=${event?.pointerCount}`);
        }
        if (boardVisible) return;
        const action = event?.action;
        if (action === 0) {
          if (supportedTapTool(event?.toolType) && event?.pointerCount === 1) {
            tapDown = {
              x: event?.x ?? 0,
              y: event?.y ?? 0,
              toolType: event.toolType,
              at: Date.now(),
            };
          } else {
            tapDown = null;
          }
          return;
        }
        if (action === 3) {
          tapDown = null;
          return;
        }
        if (action !== 1) return;

        const down = tapDown;
        tapDown = null;
        if (down === null || event?.pointerCount !== 1 || event?.toolType !== down.toolType) return;
        const x = event?.x ?? 0;
        const y = event?.y ?? 0;
        if (Date.now() - down.at > TAP_MAX_DURATION_MS) return;
        if (Math.hypot(x - down.x, y - down.y) > TAP_MAX_DISTANCE_PX) return;
        void handleNoteTap(x, y, down.toolType);
      },
    });
    console.log(`${LOG} note tap listener registered tools=finger`);
  } catch (err) {
    console.log(`${LOG} registerMotionListener failed: ${err}`);
  }
}
