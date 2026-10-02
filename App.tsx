import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AppState,
  Alert,
  DeviceEventEmitter,
  Dimensions,
  NativeModules,
  StatusBar,
  StyleSheet,
  ToastAndroid,
  View,
  findNodeHandle,
} from 'react-native';
import { NativePluginManager, NativeUIUtils, PluginManager } from 'sn-plugin-lib';
import MosaicBoardViewNative from './MosaicBoardViewNative';
import { loadBoard, saveBoard } from './boardStore';
import {
  clippedWhiteboardIds,
  ensureArchivePermissions,
  ensureNoteShotPermissions,
  insertCaptureIntoNote,
  removeCaptureFromNote,
  setBoardVisible,
  type NoteShotMeta,
} from './noteBridge';
import { t } from './i18n';
import { EngineSyncSession } from './engineSync';
import { recognizeLassoText } from './recognizeLasso';
import { DOC_TEXT_EVENT, consumePendingDocText } from './docSelectionBridge';
import {
  cardFromCommand,
  connectionFromCommand,
  strokeFromCommand,
  strokesFromBatch,
  subscribeBoardCommands,
  viewportFromCommand,
  whiteboardFromCommand,
  type BoardCommand,
} from './boardCommands';
import {
  collectImageRefs,
  createImageCard,
  ensureImageDir,
  imageAvailable,
  imageCardSizeFor,
  importImage,
  pruneOrphanImages,
} from './imageStore';
import {
  MOSAIC_INBOX_DIR,
  claimPendingMosaicImageCard,
  completePendingMosaicImageCard,
  ensureMosaicImportPermissions,
  ensureSyncNetworkPermission,
  releasePendingMosaicImageCard,
} from './mosaicImportBridge';
import {
  canvasDataToBoard,
  CARD_DEFAULTS,
  createEmptyBoardMeta,
  DEFAULT_VIEWPORT,
  type BoardDoc,
  type BoardDocMeta,
  type InkStroke,
  type WhiteboardAnchor,
} from './React/src/boardFormat';
import { generateId } from './React/src/id';
import { resolveCardSize } from './React/src/cardGeometry';
import { viewportWorldRect } from './React/src/spatialIndex';
import {
  LOCAL_ROOM_ID,
  SYNC_SITE,
  createSharedBoard,
  localSyncSite,
  sharedBoardLink,
  siteSyncEndpoint,
} from './React/src/sharedBoard';
import { AssetSync } from './assetSync';
import { loadSyncConfig, saveSyncConfig } from './syncConfig';
import { SIZE_PRESETS, type Card, type CanvasData, type Connection, type Viewport } from './React/src/types';
import type { BoardMutationDomain } from './React/src/syncProtocol';
import { ensureNoteDir } from './noteStore';
import { BOARD_SYNC_ENABLED, BoardSyncClient, type SyncConnectionState } from './React/src/boardSyncClient';
import { plainSocketFactory } from './rawNet';
import { restoreMosaicArchive, saveMosaicArchive } from './archiveStore';



const SAVE_DEBOUNCE_MS = 800;
const LARGE_INK_PERSIST_DEBOUNCE_MS = 1200;
const PEN_WIDTH_PX = 2;
const INBOX_POLL_MS = 800;

const CLOSE_SAVE_TIMEOUT_MS = 2000;

const CLOSE_LOCK_STALE_MS = 2500;

const DEFAULT_ZOOM = 0.36666667 + ((75 - 10) / 90) * (0.8 - 0.36666667);

const LEGACY_SAMPLE_CARD_IDS = new Set([
  'test-center',
  'test-left',
  'test-right',
  'test-up',
  'test-down',
  'test-large',
  'test-far-a',
  'test-far-b',
]);

type MosaicHandwritingModule = {
  attachInput(deviceType: number): Promise<boolean>;
  detachInput(): Promise<boolean>;
  lockStatusBar(lock: boolean): Promise<boolean>;
};

const MosaicHandwriting = NativeModules.MosaicHandwriting as MosaicHandwritingModule | undefined;

type BoardModel = {
  cards: Card[];
  connections: Connection[];
  ink: InkStroke[];
  whiteboards: WhiteboardAnchor[];
  viewport: Viewport;
};

function emptyModel(): BoardModel {
  return { cards: [], connections: [], ink: [], whiteboards: [], viewport: { ...DEFAULT_VIEWPORT, scale: DEFAULT_ZOOM } };
}

function removeLegacySampleCards(doc: BoardDoc): BoardDoc {
  const cards = doc.cards.filter(card => !LEGACY_SAMPLE_CARD_IDS.has(card.id));
  if (cards.length === doc.cards.length) return doc;
  const cardIds = new Set(cards.map(card => card.id));
  return {
    ...doc,
    cards,
    connections: doc.connections.filter(connection => (
      cardIds.has(connection.fromCardId) && cardIds.has(connection.toCardId)
    )),
    ink: doc.ink.filter(stroke => {
      const cardId = stroke.space.startsWith('card:') ? stroke.space.slice('card:'.length) : null;
      return cardId === null || cardIds.has(cardId);
    }),
  };
}


function displayWhiteboardName(name: string | undefined): string {
  if (!name) return t('whiteboard');
  const match = /^(?:白板|Whiteboard) (\d+)$/.exec(name);
  return match === null ? name : t('whiteboardName', { number: Number(match[1]) });
}

function toCanvasData(cards: Card[], connections: Connection[], viewport: Viewport): CanvasData {
  const record: CanvasData['cards'] = {};
  for (const card of cards) record[card.id] = card;
  return { cards: record, connections, viewport };
}

function nextZIndex(cards: Card[]): number {
  return cards.reduce((highest, card) => Math.max(highest, card.zIndex), 0) + 1;
}

export default function App(): React.JSX.Element {
  
  const [importPermissionsReady, setImportPermissionsReady] = useState(false);
  
  const [deviceType, setDeviceType] = useState<number | null>(null);
  const [touchEnabled, setTouchEnabled] = useState(true);
  const [loaded, setLoaded] = useState(false);
  const [notesDirectory, setNotesDirectory] = useState('');
  
  const [syncAddress, setSyncAddress] = useState<string | null>(null);
  
  const lastLocalAddressRef = useRef('');

  const boardRef = useRef<React.ComponentRef<typeof MosaicBoardViewNative>>(null);
  const modelRef = useRef<BoardModel>(emptyModel());
  const metaRef = useRef<BoardDocMeta>(createEmptyBoardMeta({ name: 'Mosaic Board' }));
  const extrasRef = useRef<Pick<BoardDoc, 'blobs' | 'ext'>>({ blobs: {} });
  const loadedRef = useRef(false);
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const deferredInkPersistenceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const importBusyRef = useRef(false);
  const captureInsertBusyRef = useRef(false);
  const recognizeBusyRef = useRef(false);
  
  const mosaicSurfaceRef = useRef<'board' | 'note'>('board');
  const mosaicVisibleRef = useRef(false);
  
  const pendingDocTextRef = useRef<string | null>(null);
  
  const closingRef = useRef(false);
  
  const closingSinceRef = useRef(0);
  
  const closedRef = useRef(false);
  const syncClientRef = useRef<BoardSyncClient | null>(null);
  const applyingRemoteRef = useRef(false);
  const syncImportBusyRef = useRef(false);
  const pendingSyncImportRef = useRef<BoardDoc | null>(null);
  const syncBoardIdRef = useRef('');
  
  const syncAnnounceRef = useRef(false);
  const engineSyncRef = useRef<EngineSyncSession | null>(null);
  if (engineSyncRef.current === null) engineSyncRef.current = new EngineSyncSession();
  const engineSync = engineSyncRef.current;
  
  const viewMetricsRef = useRef<{ viewW: number; viewH: number; topInset: number } | null>(null);
  
  const assetSyncRef = useRef<AssetSync | null>(null);
  if (assetSyncRef.current === null) {
    assetSyncRef.current = new AssetSync(SYNC_SITE, paths => engineSync.invalidateImages(paths));
  }
  const isMosaicBoardSurface = useCallback(() => {
    
    
    try {
      const native = (NativeModules.MosaicBoardEngine as any)?.isBoardSurface;
      if (typeof native === 'function') return native() === true;
      const current = (NativeModules.MosaicBoardEngine as any)?.getCurrentSurface?.();
      if (current === 'board' || current === 'note') mosaicSurfaceRef.current = current;
    } catch (_) {}
    return mosaicVisibleRef.current && mosaicSurfaceRef.current === 'board';
  }, []);
  const scheduleAssetSync = useCallback(() => {
    const boardId = syncBoardIdRef.current;
    if (!boardId) return;
    assetSyncRef.current?.schedule(boardId, modelRef.current.cards);
  }, []);

  

  
  const currentDocument = useCallback((): BoardDoc => {
    const model = modelRef.current;
    return canvasDataToBoard(
      toCanvasData(model.cards, [...model.connections], model.viewport),
      { ...metaRef.current, viewport: model.viewport, updatedAt: new Date().toISOString() },
      { ...extrasRef.current, ink: [...model.ink], whiteboards: [...model.whiteboards] },
    );
  }, []);

  const flushSave = useCallback((): Promise<void> => {
    if (saveTimerRef.current !== null) {
      clearTimeout(saveTimerRef.current);
      saveTimerRef.current = null;
    }
    if (!loadedRef.current) return Promise.resolve();
    const startedAt = Date.now();
    const document = currentDocument();
    metaRef.current = document.meta;
    return saveBoard(document).then(() => {
      const ms = Date.now() - startedAt;
      
      if (ms > 300) console.log(`[MosaicBoard] slow save ms=${ms} ink=${document.ink.length} cards=${document.cards.length}`);
    }).catch(error => {
      console.log(`[MosaicBoard] save failed: ${String(error)}`);
    });
  }, [currentDocument]);

  
  const scheduleSave = useCallback(() => {
    if (!loadedRef.current) return;
    if (saveTimerRef.current !== null) clearTimeout(saveTimerRef.current);
    saveTimerRef.current = setTimeout(() => {
      saveTimerRef.current = null;
      void flushSave();
    }, SAVE_DEBOUNCE_MS);
  }, [flushSave]);

  
  const deferInkPersistence = useCallback((domain: BoardMutationDomain) => {
    if (saveTimerRef.current !== null) {
      clearTimeout(saveTimerRef.current);
      saveTimerRef.current = null;
    }
    if (deferredInkPersistenceRef.current !== null) {
      clearTimeout(deferredInkPersistenceRef.current);
    }
    deferredInkPersistenceRef.current = setTimeout(() => {
      deferredInkPersistenceRef.current = null;
      void (async () => {
        if (!loadedRef.current) return;
        await flushSave();
        if (!applyingRemoteRef.current) {
          syncClientRef.current?.publish(domain, currentDocument());
        }
      })();
    }, LARGE_INK_PERSIST_DEBOUNCE_MS);
  }, [currentDocument, flushSave]);

  
  const pushFullScene = useCallback(() => {
    const model = modelRef.current;
    engineSync.reset();
    engineSync.syncCards(model.cards);
    engineSync.syncConnections(model.connections);
    engineSync.syncInk(model.ink);
    engineSync.syncWhiteboards(model.whiteboards, wb => displayWhiteboardName(wb.name));
    
    
    engineSync.documentReplaced(findNodeHandle(boardRef.current));
  }, [engineSync]);

  
  const applyRemoteDocument = useCallback((document: BoardDoc, domain?: BoardMutationDomain) => {
    applyingRemoteRef.current = true;
    
    
    
    
    const model = modelRef.current;
    const viewport = model.viewport;
    if (domain === 'structure' || domain === 'ink') {
      if (domain === 'structure') {
        model.cards = document.cards;
        model.connections = document.connections;
        model.whiteboards = document.whiteboards;
        
        const cardIds = new Set(model.cards.map(card => card.id));
        model.ink = model.ink.filter(s => !s.space.startsWith('card:') || cardIds.has(s.space.slice(5)));
        engineSync.syncCards(model.cards);
        engineSync.syncConnections(model.connections);
        engineSync.syncWhiteboards(model.whiteboards, wb => displayWhiteboardName(wb.name));
        engineSync.syncInk(model.ink);
      } else {
        model.ink = document.ink;
        engineSync.syncInk(model.ink);
      }
      metaRef.current = { ...metaRef.current, updatedAt: document.meta.updatedAt, viewport };
      
      if (domain === 'structure') scheduleAssetSync();
      void saveBoard(currentDocument()).finally(() => {
        applyingRemoteRef.current = false;
      });
      return;
    }
    const merged: BoardDoc = { ...document, meta: { ...document.meta, viewport } };
    metaRef.current = merged.meta;
    extrasRef.current = { blobs: merged.blobs, ext: merged.ext };
    modelRef.current = {
      cards: merged.cards,
      connections: merged.connections,
      ink: merged.ink,
      whiteboards: merged.whiteboards,
      viewport,
    };
    pushFullScene();
    
    scheduleAssetSync();
    void saveBoard(merged).finally(() => {
      applyingRemoteRef.current = false;
    });
  }, [currentDocument, engineSync, pushFullScene, scheduleAssetSync]);

  const confirmRemoteImport = useCallback(async (document: BoardDoc) => {
    pendingSyncImportRef.current = document;
    if (syncImportBusyRef.current) return;
    syncImportBusyRef.current = true;
    try {
      while (pendingSyncImportRef.current) {
        const incoming = pendingSyncImportRef.current;
        pendingSyncImportRef.current = null;
        let accepted = false;
        try {
          accepted = await NativeUIUtils.showRattaDialog(
            t('syncImportAsk'),
            t('syncImportNo'),
            t('syncImportYes'),
            false,
          );
        } catch (error) {
          console.log(`[MosaicSync] import.dialog.error ${String(error)}`);
        }
        if (pendingSyncImportRef.current) continue;
        if (accepted) {
          console.log(`[MosaicSync] import.accepted cards=${incoming.cards.length} ink=${incoming.ink.length}`);
          applyRemoteDocument(incoming);
        } else {
          console.log('[MosaicSync] import.rejected');
          syncClientRef.current?.publish('document', currentDocument());
        }
      }
    } finally {
      syncImportBusyRef.current = false;
    }
  }, [applyRemoteDocument, currentDocument]);

  
  const pushSyncState = useCallback((enabled: boolean, state: SyncConnectionState | 'off') => {
    const viewTag = findNodeHandle(boardRef.current);
    const engine = NativeModules.MosaicBoardEngine as
      | { setSyncState?: (tag: number, enabled: boolean, state: string, address: string) => void }
      | undefined;
    if (viewTag !== null && engine?.setSyncState) engine.setSyncState(viewTag, enabled, state, lastLocalAddressRef.current);
  }, []);

  
  const setSyncTarget = useCallback(async (enable: boolean, address: string) => {
    const localAddress = address.trim();
    if (enable && localAddress) {
      try {
        localSyncSite(localAddress);
      } catch (error) {
        Alert.alert(t('sync'), error instanceof Error ? error.message : String(error));
        return;
      }
    }
    const config = await loadSyncConfig();
    if (enable) lastLocalAddressRef.current = localAddress;
    await saveSyncConfig({ ...config, enabled: enable, localAddress: lastLocalAddressRef.current });
    syncAnnounceRef.current = enable && !localAddress;
    console.log(`[MosaicSync] target enable=${enable} address=${localAddress || 'remote'}`);
    setSyncAddress(enable ? localAddress : null);
  }, []);

  

  
  const closeBoard = useCallback((source: string) => {
    const startedAt = Date.now();
    
    
    
    
    if (closingRef.current) {
      const stuckMs = startedAt - closingSinceRef.current;
      if (stuckMs < CLOSE_LOCK_STALE_MS) {
        console.log(`[MosaicBoard] close ignored source=${source} alreadyClosing stuckMs=${stuckMs}`);
        return;
      }
      console.log(`[MosaicBoard] close lock stale ${stuckMs}ms source=${source} -> forcing new attempt`);
    }
    closingRef.current = true;
    closingSinceRef.current = startedAt;
    
    
    const deferredInk = deferredInkPersistenceRef.current !== null;
    if (deferredInk && deferredInkPersistenceRef.current !== null) {
      clearTimeout(deferredInkPersistenceRef.current);
      deferredInkPersistenceRef.current = null;
    }
    const dirty = saveTimerRef.current !== null || deferredInk;
    console.log(`[MosaicBoard] close source=${source} dirty=${dirty}`);
    MosaicHandwriting?.detachInput().catch(() => {});
    MosaicHandwriting?.lockStatusBar(false).catch(() => {});
    const saved = (dirty ? flushSave() : Promise.resolve()).then(() => {
      closedRef.current = true;
    });
    let deadlineHit = false;
    const deadline = new Promise<void>(resolve => {
      setTimeout(() => { deadlineHit = true; resolve(); }, CLOSE_SAVE_TIMEOUT_MS);
    });
    void Promise.race([saved, deadline])
      .then(() => {
        console.log(`[MosaicBoard] close dispatch source=${source} waitedMs=${Date.now() - startedAt} deadlineHit=${deadlineHit}`);
        setBoardVisible(false, source);
        return PluginManager.closePluginView();
      })
      .catch((error) => {
        console.log(`[MosaicBoard] closePluginView failed source=${source}: ${error}`);
      })
      .finally(() => {
        closingRef.current = false;
      });
  }, [flushSave]);

  

  
  const pushClippedWhiteboards = useCallback(async () => {
    try {
      const ids = await clippedWhiteboardIds();
      const viewTag = findNodeHandle(boardRef.current);
      const engine = NativeModules.MosaicBoardEngine as
        | { setClippedWhiteboards?: (tag: number, ids: string[]) => void }
        | undefined;
      if (viewTag !== null && engine?.setClippedWhiteboards) engine.setClippedWhiteboards(viewTag, ids);
    } catch (error) {
      console.log(`[MosaicNoteShot] push clipped failed: ${String(error)}`);
    }
  }, []);

  
  const recognizeLassoToCard = useCallback(async () => {
    if (recognizeBusyRef.current) return;
    recognizeBusyRef.current = true;
    try {
      const text = await recognizeLassoText();
      if (!text) return;
      const viewTag = findNodeHandle(boardRef.current);
      const engine = NativeModules.MosaicBoardEngine as
        | { createRecognizedTextCard?: (tag: number, text: string) => void }
        | undefined;
      if (viewTag !== null && engine?.createRecognizedTextCard) engine.createRecognizedTextCard(viewTag, text);
    } catch (error) {
      console.log(`[MosaicRecognize] flow failed: ${String(error)}`);
    } finally {
      recognizeBusyRef.current = false;
    }
  }, []);

  
  const insertTextCard = useCallback((rawText: string) => {
    const text = rawText.trim();
    if (!text) return;
    if (!isMosaicBoardSurface()) {
      
      
      pendingDocTextRef.current = text;
      if (loadedRef.current) consumePendingDocText();
      console.log('[MosaicDocText] deferred while surface=note');
      return;
    }
    if (!loadedRef.current) { pendingDocTextRef.current = text; return; }
    
    const viewTag = findNodeHandle(boardRef.current);
    const engine = NativeModules.MosaicBoardEngine as
      | { insertDocTextCard?: (tag: number, text: string) => void }
      | undefined;
    if (viewTag !== null && engine?.insertDocTextCard) {
      engine.insertDocTextCard(viewTag, text);
      console.log(`[MosaicDocText] sent to native chars=${text.length}`);
      return;
    }
    const model = modelRef.current;
    const viewport = model.viewport;
    const { width: screenW, height: screenH } = Dimensions.get('window');
    const visible = viewportWorldRect(viewport.panX, viewport.panY, viewport.scale, screenW, screenH);
    const created: Card = {
      ...CARD_DEFAULTS,
      id: 'card-' + generateId(),
      kind: 'text',
      content: text,
      tags: [],
      sourceType: 'manual',
      createdAt: new Date().toISOString(),
      zIndex: nextZIndex(model.cards),
      x: 0,
      y: 0,
    };
    const size = resolveCardSize(created);
    created.x = visible.x + (visible.width - size.width) / 2;
    created.y = visible.y + (visible.height - size.height) / 2;
    model.cards = [...model.cards, created];
    engineSync.syncCards(model.cards);
    scheduleSave();
    if (!applyingRemoteRef.current) syncClientRef.current?.publish('structure', currentDocument());
    console.log(`[MosaicDocText] inserted text card=${created.id} chars=${text.length}`);
  }, [currentDocument, engineSync, isMosaicBoardSurface, scheduleSave]);

  const insertCapturedShot = useCallback(async (op: Extract<BoardCommand, { name: 'captureReady' }>) => {
    if (captureInsertBusyRef.current) return;
    captureInsertBusyRef.current = true;
    try {
      const granted = await ensureNoteShotPermissions();
      if (!granted) {
        console.log(`[MosaicNoteShot] insert paused while file permission awaits approval wb=${op.wbId}`);
        return;
      }
      const meta: NoteShotMeta = {
        v: 1,
        wbId: op.wbId,
        wbName: op.wbName,
        rect: op.rect,
        hotspot: op.hotspot,
        capturedAt: new Date().toISOString(),
      };
      const ok = await insertCaptureIntoNote(op.path, meta);
      console.log(`[MosaicNoteShot] flow done insert=${ok ? 'ok' : 'failed'} wb=${op.wbId} png=${op.path}`);
      if (ok) {
        void pushClippedWhiteboards();
        closeBoard('capture');
      }
    } catch (error) {
      console.log(`[MosaicNoteShot] insert flow failed: ${String(error)}`);
    } finally {
      captureInsertBusyRef.current = false;
    }
  }, [closeBoard, pushClippedWhiteboards]);

  

  const applyCommands = useCallback((ops: BoardCommand[]) => {
    const batchStartedAt = Date.now();
    const model = modelRef.current;
    let structureChanged = false;
    let changed = false;
    
    
    let inkChanged = false;
    let anchorsChanged = false;
    const inkIndex = new Map<string, number>();
    model.ink.forEach((stroke, index) => inkIndex.set(stroke.id, index));
    
    
    const removedInkIds = new Set<string>();
    let removedInkRequested = 0;
    
    
    let closeRequested = false;
    let batchStrokeCount = 0;
    for (const op of ops) {
      switch (op.type) {
        case 'strokeUpsert': {
          batchStrokeCount += 1;
          const stroke = strokeFromCommand(op);
          const index = inkIndex.get(stroke.id);
          if (index !== undefined) model.ink[index] = stroke;
          else {
            inkIndex.set(stroke.id, model.ink.length);
            model.ink.push(stroke);
          }
          removedInkIds.delete(stroke.id);
          engineSync.acknowledgeStroke(stroke);
          changed = true;
          inkChanged = true;
          break;
        }
        case 'strokeBatch': {
          batchStrokeCount += op.ids.length;
          const strokes = strokesFromBatch(op);
          for (const stroke of strokes) {
            const index = inkIndex.get(stroke.id);
            if (index !== undefined) model.ink[index] = stroke;
            else {
              inkIndex.set(stroke.id, model.ink.length);
              model.ink.push(stroke);
            }
            removedInkIds.delete(stroke.id);
          }
          engineSync.acknowledgeStrokes(strokes);
          if (strokes.length > 0) {
            changed = true;
            inkChanged = true;
          }
          break;
        }
        case 'strokesRemove': {
          removedInkRequested += op.ids.length;
          for (const id of op.ids) removedInkIds.add(id);
          engineSync.acknowledgeStrokesRemoved(op.ids);
          changed = true;
          inkChanged = true;
          break;
        }
        case 'cardUpsert': {
          const index = model.cards.findIndex(c => c.id === op.id);
          const card = cardFromCommand(op, index >= 0 ? model.cards[index] : undefined);
          if (index >= 0) model.cards[index] = card; else model.cards.push(card);
          engineSync.acknowledgeCard(card);
          structureChanged = true;
          break;
        }
        case 'cardsRemove': {
          const ids = new Set(op.ids);
          const removedConnectionIds = new Set(model.connections
            .filter(connection => ids.has(connection.fromCardId) || ids.has(connection.toCardId))
            .map(connection => connection.id));
          model.cards = model.cards.filter(c => !ids.has(c.id));
          model.connections = model.connections.filter(c => !ids.has(c.fromCardId) && !ids.has(c.toCardId));
          const inkBefore = model.ink.length;
          model.ink = model.ink.filter(s =>
            !(s.space.startsWith('card:') && ids.has(s.space.slice(5))) &&
            !(s.space.startsWith('connection:') && removedConnectionIds.has(s.space.slice('connection:'.length))),
          );
          inkIndex.clear();
          model.ink.forEach((stroke, index) => inkIndex.set(stroke.id, index));
          
          if (model.ink.length !== inkBefore) inkChanged = true;
          engineSync.acknowledgeCardsRemoved(op.ids);
          structureChanged = true;
          break;
        }
        case 'connectionAdd': {
          const index = model.connections.findIndex(c => c.id === op.id)
          const connection = connectionFromCommand(op)
          if (index >= 0) model.connections[index] = { ...model.connections[index], locked: connection.locked === true || model.connections[index].locked === true }
          else model.connections.push(connection)
          structureChanged = true;
          break;
        }
        case 'connectionsRemove': {
          const ids = new Set(op.ids);
          model.connections = model.connections.filter(c => !ids.has(c.id));
          structureChanged = true;
          break;
        }
        case 'whiteboardUpsert': {
          const wb = whiteboardFromCommand(op);
          const index = model.whiteboards.findIndex(w => w.id === wb.id);
          if (index >= 0) model.whiteboards[index] = wb; else model.whiteboards.push(wb);
          changed = true;
          anchorsChanged = true;
          break;
        }
        case 'whiteboardsRemove': {
          const ids = new Set(op.ids);
          model.whiteboards = model.whiteboards.filter(w => !ids.has(w.id));
          changed = true;
          anchorsChanged = true;
          break;
        }
        case 'viewport':
          model.viewport = viewportFromCommand(op);
          if (op.viewW !== undefined && op.viewH !== undefined) {
            viewMetricsRef.current = { viewW: op.viewW, viewH: op.viewH, topInset: op.topInset ?? 0 };
          }
          changed = true;
          break;
        case 'action':
          if (op.name === 'close') {
            
            const queueMs = typeof op.emittedAt === 'number' ? Date.now() - op.emittedAt : -1;
            console.log(`[MosaicBoard] close action received from native queueMs=${queueMs}`);
            closeRequested = true;
          }
          else if (op.name === 'touchEnabled') setTouchEnabled(op.value);
          else if (op.name === 'captureReady') void insertCapturedShot(op);
          else if (op.name === 'removeClip') {
            const wbId = op.wbId;
            void removeCaptureFromNote(wbId)
              .catch(error => console.log(`[MosaicNoteShot] removeClip failed: ${String(error)}`))
              .finally(() => { void pushClippedWhiteboards(); });
          }
          else if (op.name === 'recognizeLasso') void recognizeLassoToCard();
          else if (op.name === 'saveArchive') {
            void (async () => {
              if (!(await ensureArchivePermissions(t('archivePermissionRead'), t('archivePermissionWrite')))) {
                throw new Error(t('archivePermissionDenied'));
              }
              await flushSave();
              return saveMosaicArchive();
            })()
              .then(path => ToastAndroid.show(t('archiveSaved', { path }), ToastAndroid.LONG))
              .catch(error => ToastAndroid.show(t('archiveSaveFailed', { error: String(error) }), ToastAndroid.LONG));
          }
          else if (op.name === 'loadArchive') {
            void (async () => {
              
              let confirmed = false;
              try {
                confirmed = await NativeUIUtils.showRattaDialog(
                  t('archiveLoadAsk'), t('archiveLoadCancel'), t('archiveLoadConfirm'), false,
                );
              } catch (error) {
                console.log(`[MosaicArchive] confirm dialog failed: ${String(error)}`);
              }
              if (!confirmed) return false;
              if (!(await ensureArchivePermissions(t('archivePermissionRead'), t('archivePermissionWrite')))) {
                throw new Error(t('archivePermissionDenied'));
              }
              await restoreMosaicArchive();
              return true;
            })()
              .then(restored => (restored ? loadBoard() : undefined))
              .then(document => {
                if (document === undefined) return;
                if (document === null) throw new Error(t('archiveInvalid'));
                metaRef.current = document.meta;
                extrasRef.current = { blobs: document.blobs, ext: document.ext };
                modelRef.current = {
                  cards: document.cards,
                  connections: document.connections,
                  ink: document.ink,
                  whiteboards: document.whiteboards,
                  viewport: document.meta.viewport,
                };
                pushFullScene();
                setLoaded(true);
                loadedRef.current = true;
                ToastAndroid.show(t('archiveLoaded'), ToastAndroid.LONG);
              })
              .catch(error => ToastAndroid.show(t('archiveLoadFailed', { error: String(error) }), ToastAndroid.LONG));
          }
          else if (op.name === 'insertTextCard') {
            if (!isMosaicBoardSurface()) {
              pendingDocTextRef.current = op.text;
              console.log('[MosaicBoard] deferred text card while surface=note');
              break;
            }
            
            
            const created: Card = {
              ...CARD_DEFAULTS,
              tags: [],
              id: 'card-' + generateId(),
              kind: 'text',
              content: op.text,
              x: op.x,
              y: op.y,
              width: op.width ?? SIZE_PRESETS.default.width,
              height: op.height ?? SIZE_PRESETS.default.height,
              zIndex: nextZIndex(model.cards),
              sourceType: op.source === 'doc' ? 'manual' : 'transcription',
              createdAt: new Date().toISOString(),
            };
            model.cards.push(created);
            structureChanged = true;
            console.log(`[MosaicBoard] inserted text card=${created.id} at world=(${op.x},${op.y}) len=${op.text.length}`);
          }
          else if (op.name === 'sync') {
            setSyncTarget(op.enable, op.address ?? '').catch(error => console.log(`[MosaicSync] target.error ${String(error)}`));
          }
          break;
        default:
          console.log(`[MosaicBoardCommand] unknown op ${JSON.stringify(op)}`);
      }
    }
    if (removedInkIds.size > 0) {
      model.ink = model.ink.filter(stroke => !removedInkIds.has(stroke.id));
    }
    if (removedInkRequested > 128 || batchStrokeCount > 128 && inkChanged) {
      console.log(`[MosaicBoard] ink batch upserts=${batchStrokeCount} ` +
        `removeRequested=${removedInkRequested} remaining=${model.ink.length}`);
    }
    if (structureChanged) {
      
      engineSync.syncCards(model.cards);
      engineSync.syncConnections(model.connections);
    }
    const structure = structureChanged || anchorsChanged;
    const domain = structure && inkChanged ? 'document' : structure ? 'structure' : inkChanged ? 'ink' : null;
    
    
    
    const deferLargeInk = domain === 'ink' && !structureChanged &&
      (batchStrokeCount > 128 || deferredInkPersistenceRef.current !== null);
    if (structureChanged || changed) {
      if (deferLargeInk) deferInkPersistence('ink');
      else scheduleSave();
    }
    if (!applyingRemoteRef.current) {
      
      if (domain !== null && !deferLargeInk) syncClientRef.current?.publish(domain, currentDocument());
      
      
      scheduleAssetSync();
    }
    
    const batchMs = Date.now() - batchStartedAt;
    if (batchMs > 100) console.log(`[MosaicBoard] slow command batch ms=${batchMs} ops=${ops.map(op => op.type === 'action' ? `action:${op.name}` : op.type).join(',')}`);
    if (closeRequested) closeBoard('toolbar');
  }, [closeBoard, currentDocument, deferInkPersistence, engineSync, flushSave, insertCapturedShot, isMosaicBoardSurface, pushClippedWhiteboards, pushFullScene, recognizeLassoToCard, scheduleAssetSync, scheduleSave, setSyncTarget]);

  useEffect(() => subscribeBoardCommands(applyCommands), [applyCommands]);

  
  useEffect(() => {
    if (!loaded) return;
    void pushClippedWhiteboards();
  }, [loaded, pushClippedWhiteboards]);

  
  useEffect(() => {
    const sub = DeviceEventEmitter.addListener(DOC_TEXT_EVENT, (text: string) => insertTextCard(text));
    return () => sub.remove();
  }, [insertTextCard]);

  useEffect(() => {
    if (!loaded) return;
    if (!isMosaicBoardSurface()) return;
    const pending = consumePendingDocText() ?? pendingDocTextRef.current;
    pendingDocTextRef.current = null;
    if (pending) insertTextCard(pending);
  }, [isMosaicBoardSurface, loaded, insertTextCard]);

  

  const importPendingImageCard = useCallback(async () => {
    if (!loadedRef.current || importBusyRef.current || !isMosaicBoardSurface()) return;
    importBusyRef.current = true;
    let claim: Awaited<ReturnType<typeof claimPendingMosaicImageCard>> = null;
    let committed = false;
    try {
      claim = await claimPendingMosaicImageCard();
      if (claim === null) return;
      if (!imageAvailable()) throw new Error('MosaicImage native module unavailable');
      console.log(`[MosaicImport] claimed request=${claim.request.id} source=${claim.request.imagePath}`);
      const imported = await importImage(claim.request.imagePath);
      if (!isMosaicBoardSurface()) {
        await releasePendingMosaicImageCard(claim);
        claim = null;
        console.log('[MosaicImport] deferred claimed image after surface switched to note');
        return;
      }
      const model = modelRef.current;
      const viewport = model.viewport;
      const { width: screenW, height: screenH } = Dimensions.get('window');
      
      
      const metrics = viewMetricsRef.current;
      const viewW = metrics?.viewW ?? screenW;
      const viewH = metrics?.viewH ?? screenH;
      const topInsetWorld = (metrics?.topInset ?? 0) / (viewport.scale || 1);
      const visible = viewportWorldRect(viewport.panX, viewport.panY, viewport.scale, viewW, viewH);
      const size = imageCardSizeFor(imported);
      const created = createImageCard({
        imageRef: imported.imageRef,
        natural: imported,
        x: visible.x + (visible.width - size.width) / 2,
        y: visible.y + topInsetWorld + (visible.height - topInsetWorld - size.height) / 2,
        zIndex: nextZIndex(model.cards),
        content: '',
      });

      model.cards = [...model.cards, created];
      engineSync.syncCards(model.cards);
      
      engineSync.selectCards(findNodeHandle(boardRef.current), [created.id]);
      committed = true;
      scheduleSave();
      if (!applyingRemoteRef.current) syncClientRef.current?.publish('structure', currentDocument());
      scheduleAssetSync();
      await completePendingMosaicImageCard(claim);
      console.log(`[MosaicImport] inserted card=${created.id} request=${claim.request.id}`);
    } catch (error) {
      if (claim !== null && !committed) await releasePendingMosaicImageCard(claim);
      console.log(`[MosaicImport] failed: ${String(error)}`);
    } finally {
      importBusyRef.current = false;
    }
  }, [currentDocument, engineSync, isMosaicBoardSurface, scheduleAssetSync, scheduleSave]);

  
  
  useEffect(() => {
    const sub = DeviceEventEmitter.addListener('MosaicBoardVisibility', (event: any) => {
      const next = event?.surface === 'note' ? 'note' : 'board';
      const was = mosaicSurfaceRef.current;
      mosaicVisibleRef.current = event?.visible === true;
      mosaicSurfaceRef.current = mosaicVisibleRef.current ? next : 'board';
      if (!mosaicVisibleRef.current || mosaicSurfaceRef.current !== 'board' || was === 'board') return;
      const pending = pendingDocTextRef.current;
      pendingDocTextRef.current = null;
      if (pending && loadedRef.current) insertTextCard(pending);
      else if (pending) pendingDocTextRef.current = pending;
      void importPendingImageCard();
    });
    return () => sub.remove();
  }, [importPendingImageCard, insertTextCard]);

  
  
  
  useEffect(() => {
    mosaicVisibleRef.current = true;
    
    
    
    
    
    if (loadedRef.current && mosaicSurfaceRef.current === 'board') {
      const pending = consumePendingDocText() ?? pendingDocTextRef.current;
      pendingDocTextRef.current = null;
      if (pending) insertTextCard(pending);
    }
    return () => {
      mosaicVisibleRef.current = false;
    };
  }, [insertTextCard]);

  

  
  
  
  
  useEffect(() => {
    let cancelled = false;
    void ensureMosaicImportPermissions().then(ready => {
      if (cancelled) return;
      setImportPermissionsReady(ready);
      if (!ready) ToastAndroid.show(t('mosaicPermissionNeeded'), ToastAndroid.LONG);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    StatusBar.setHidden(true, 'none');
    setBoardVisible(true, 'mounted');
    return () => {
      StatusBar.setHidden(false, 'none');
      setBoardVisible(false, 'unmount');
    };
  }, []);

  
  
  useEffect(() => {
    let cancelled = false;
    const loadStartedAt = Date.now();
    void loadBoard().catch(error => {
      console.log(`[MosaicBoard] load failed: ${String(error)}`);
      return null;
    }).then(doc => {
      if (cancelled) return;
      const cleaned = doc === null ? null : removeLegacySampleCards(doc);
      if (cleaned !== null) {
        metaRef.current = cleaned.meta;
        extrasRef.current = { blobs: cleaned.blobs, ext: cleaned.ext };
        modelRef.current = {
          cards: cleaned.cards,
          connections: cleaned.connections,
          ink: cleaned.ink,
          whiteboards: cleaned.whiteboards,
          viewport: cleaned.meta.viewport,
        };
        
        
        engineSync.setViewportIfUnset(cleaned.meta.viewport);
        
        pruneOrphanImages(collectImageRefs(cleaned.cards)).catch(() => {});
        if (cleaned !== doc) void saveBoard(cleaned).catch(() => {});
      } else {
        metaRef.current = createEmptyBoardMeta({ name: 'Mosaic Board' });
        extrasRef.current = { blobs: {} };
        modelRef.current = emptyModel();
      }
      pushFullScene();
      loadedRef.current = true;
      setLoaded(true);
      console.log(
        `[MosaicBoard] loaded cards=${modelRef.current.cards.length} ink=${modelRef.current.ink.length} `
        + `whiteboards=${modelRef.current.whiteboards.length} deviceType=pending `
        + `loadMs=${Date.now() - loadStartedAt}`,
      );
    });
    
    
    
    void Promise.all([
      ensureImageDir().catch(error => {
        console.log(`[MosaicImage] image dir unavailable: ${String(error)}`);
        return null;
      }),
      ensureNoteDir().catch(error => {
        console.log(`[MosaicNote] note dir unavailable: ${String(error)}`);
        return null;
      }),
      PluginManager.getDeviceType().catch(() => -1),
    ]).then(([imageDir, noteDir, type]) => {
      if (cancelled) return;
      setDeviceType(type);
      if (noteDir) setNotesDirectory(noteDir);
      
      
      
      if ((imageDir || noteDir) && loadedRef.current) {
        engineSync.refreshAssetCardPaths(modelRef.current.cards);
      }
      console.log(`[MosaicBoard] runtime deviceType=${type} imageDir=${imageDir ? 'ready' : 'missing'} noteDir=${noteDir ? 'ready' : 'missing'}`);
    });
    return () => {
      cancelled = true;
    };
  }, [pushFullScene]);

  
  useEffect(() => {
    if (!BOARD_SYNC_ENABLED || !loaded) return;
    loadSyncConfig()
      .then(config => {
        lastLocalAddressRef.current = config.localAddress;
        setSyncAddress(config.enabled ? config.localAddress : null);
      })
      .catch(error => console.log(`[MosaicSync] config.error ${String(error)}`));
  }, [loaded]);

  useEffect(() => {
    if (!BOARD_SYNC_ENABLED || !loaded) return;
    if (syncAddress === null) {
      pushSyncState(false, 'off');
      return;
    }
    const local = syncAddress !== '';
    let cancelled = false;
    BoardSyncClient.socketFactory = plainSocketFactory;
    const client = new BoardSyncClient();
    syncClientRef.current = client;
    pushSyncState(true, 'connecting');
    void (async () => {
      try {
        if (!(await ensureSyncNetworkPermission())) throw new Error('网络权限未授予');
        
        
        let site = SYNC_SITE;
        let boardId = LOCAL_ROOM_ID;
        if (local) site = localSyncSite(syncAddress);
        else {
          const config = await loadSyncConfig();
          boardId = config.boardId;
          if (!boardId) {
            boardId = await createSharedBoard(currentDocument());
            await saveSyncConfig({ ...config, serverUrl: SYNC_SITE, boardId });
            console.log(`[MosaicSync] room.created board=${boardId}`);
          }
        }
        if (cancelled) return;
        syncBoardIdRef.current = boardId;
        assetSyncRef.current?.setSite(site);
        if (syncAnnounceRef.current) {
          syncAnnounceRef.current = false;
          Alert.alert(t('sync'), `${t('syncLinkHint')}\n${sharedBoardLink(boardId)}`);
        }
        const endpoint = siteSyncEndpoint(site);
        console.log(`[MosaicSync] connect endpoint=${endpoint} board=${boardId}`);
        
        
        const seed: BoardDoc = local
          ? { v: 1, meta: createEmptyBoardMeta({ name: 'Mosaic Board' }), cards: [], connections: [], ink: [], whiteboards: [], blobs: {} }
          : currentDocument();
        client.connect(boardId, seed, (document, _revision, domain) => {
          if (domain === 'import') {
            void confirmRemoteImport(document);
            return;
          }
          applyRemoteDocument(document, domain);
        }, endpoint, state => {
          console.log(`[MosaicSync] state=${state} board=${boardId}`);
          if (!cancelled) pushSyncState(true, state);
          
          if (state === 'connected') scheduleAssetSync();
        });
        
        
        if (local) {
          const own = currentDocument();
          if (own.cards.length > 0 || own.ink.length > 0 || own.whiteboards.length > 0) {
            client.protectLocalUntilAck();
            client.publish('import', own);
          } else console.log('[MosaicSync] local board empty, adopting room content');
        }
      } catch (error) {
        console.log(`[MosaicSync] setup.error ${String(error)}`);
        if (!cancelled) pushSyncState(true, 'error');
      }
    })();
    return () => {
      cancelled = true;
      client.close();
      assetSyncRef.current?.dispose();
      if (syncClientRef.current === client) syncClientRef.current = null;
      syncBoardIdRef.current = '';
    };
  }, [applyRemoteDocument, confirmRemoteImport, currentDocument, loaded, pushSyncState, scheduleAssetSync, syncAddress]);

  
  const attachNativeInput = useCallback(() => {
    if (deviceType === null) return;
    MosaicHandwriting?.attachInput(deviceType).catch(() => {});
    
    MosaicHandwriting?.lockStatusBar(false).catch(() => {});
  }, [deviceType]);

  useEffect(() => {
    attachNativeInput();
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') attachNativeInput();
    });
    return () => subscription.remove();
  }, [attachNativeInput]);

  
  
  useEffect(() => {
    if (!loaded || !importPermissionsReady) return;
    console.log(`[MosaicImport] polling inbox=${MOSAIC_INBOX_DIR}`);
    void importPendingImageCard();
    const timer = setInterval(() => {
      void importPendingImageCard();
    }, INBOX_POLL_MS);
    const sub = DeviceEventEmitter.addListener('MosaicInboxReady', () => {
      void importPendingImageCard();
    });
    return () => {
      clearInterval(timer);
      sub.remove();
    };
  }, [importPendingImageCard, loaded, importPermissionsReady]);

  useEffect(() => () => {
    if (saveTimerRef.current !== null) {
      clearTimeout(saveTimerRef.current);
      saveTimerRef.current = null;
    }
    if (loadedRef.current && !closedRef.current) {
      const document = currentDocument();
      saveBoard(document).catch(() => {});
    }
    MosaicHandwriting?.detachInput().catch(() => {});
    MosaicHandwriting?.lockStatusBar(false).catch(() => {});
    NativePluginManager.invalidatePluginView();
  }, [currentDocument]);


  return (
    <View style={styles.root}>
      {importPermissionsReady ? (
        <MosaicBoardViewNative
          ref={boardRef}
          style={styles.board}
          deviceType={deviceType ?? -1}
          touchEnabled={touchEnabled}
          notesDirectory={notesDirectory}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#ffffff',
  },
  board: {
    flex: 1,
  },
});
