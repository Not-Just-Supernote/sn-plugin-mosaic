import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AppState,
  Alert,
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
  ensureNoteShotPermissions,
  insertCaptureIntoNote,
  setBoardVisible,
  type NoteShotMeta,
} from './noteBridge';
import { t } from './i18n';
import { EngineSyncSession } from './engineSync';
import { createNeckSession } from './liquidNecks';
import {
  cardFromCommand,
  connectionFromCommand,
  strokeFromCommand,
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
  createEmptyBoardMeta,
  DEFAULT_VIEWPORT,
  type BoardDoc,
  type BoardDocMeta,
  type InkStroke,
  type WhiteboardAnchor,
} from './React/src/boardFormat';
import { viewportWorldRect } from './React/src/spatialIndex';
import { createSharedBoard } from './React/src/sharedBoard';
import { loadSyncConfig, saveSyncConfig } from './syncConfig';
import type { Card, CanvasData, Connection, Viewport } from './React/src/types';
import { BOARD_SYNC_ENABLED, BoardSyncClient } from './React/src/boardSyncClient';



const SAVE_DEBOUNCE_MS = 800;
const PEN_WIDTH_PX = 2;
const INBOX_POLL_MS = 800;

const DEFAULT_ZOOM = 0.7 + (1.4 - 0.7) * (150 - 100) / 100;

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

export default function App(): React.JSX.Element | null {
  const [pluginPermissionsReady, setPluginPermissionsReady] = useState<boolean | null>(null);
  
  const [deviceType, setDeviceType] = useState<number | null>(null);
  const [touchEnabled, setTouchEnabled] = useState(true);
  const [loaded, setLoaded] = useState(false);

  const boardRef = useRef<React.ComponentRef<typeof MosaicBoardViewNative>>(null);
  const modelRef = useRef<BoardModel>(emptyModel());
  const metaRef = useRef<BoardDocMeta>(createEmptyBoardMeta({ name: 'Mosaic Board' }));
  const extrasRef = useRef<Pick<BoardDoc, 'blobs' | 'ext'>>({ blobs: {} });
  const loadedRef = useRef(false);
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const importBusyRef = useRef(false);
  const captureInsertBusyRef = useRef(false);
  const closedRef = useRef(false);
  const syncClientRef = useRef<BoardSyncClient | null>(null);
  const applyingRemoteRef = useRef(false);
  const syncImportBusyRef = useRef(false);
  const pendingSyncImportRef = useRef<BoardDoc | null>(null);
  const syncBoardIdRef = useRef('');
  const engineSyncRef = useRef<EngineSyncSession | null>(null);
  if (engineSyncRef.current === null) engineSyncRef.current = new EngineSyncSession();
  const engineSync = engineSyncRef.current;
  const neckSessionRef = useRef(createNeckSession());

  

  const currentDocument = useCallback((): BoardDoc => {
    const model = modelRef.current;
    return canvasDataToBoard(
      toCanvasData(model.cards, model.connections, model.viewport),
      { ...metaRef.current, viewport: model.viewport, updatedAt: new Date().toISOString() },
      { ...extrasRef.current, ink: model.ink, whiteboards: model.whiteboards },
    );
  }, []);

  const flushSave = useCallback((): Promise<void> => {
    if (saveTimerRef.current !== null) {
      clearTimeout(saveTimerRef.current);
      saveTimerRef.current = null;
    }
    if (!loadedRef.current) return Promise.resolve();
    const document = currentDocument();
    metaRef.current = document.meta;
    return saveBoard(document).catch(error => {
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

  
  const pushNecks = useCallback(() => {
    const model = modelRef.current;
    engineSync.syncNecks(neckSessionRef.current.compute(model.cards, model.connections));
  }, [engineSync]);

  
  const pushFullScene = useCallback(() => {
    const model = modelRef.current;
    engineSync.reset();
    neckSessionRef.current.reset();
    engineSync.syncCards(model.cards);
    engineSync.syncConnections(model.connections);
    engineSync.syncInk(model.ink);
    engineSync.syncWhiteboards(model.whiteboards, wb => displayWhiteboardName(wb.name));
    pushNecks();
    engineSync.setViewport(model.viewport);
    engineSync.documentReplaced(findNodeHandle(boardRef.current));
  }, [engineSync, pushNecks]);

  const applyRemoteDocument = useCallback((document: BoardDoc) => {
    applyingRemoteRef.current = true;
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
    void saveBoard(document).finally(() => {
      applyingRemoteRef.current = false;
    });
  }, [pushFullScene]);

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

  

  const closeBoard = useCallback((source: string) => {
    if (closedRef.current) return;
    closedRef.current = true;
    console.log(`[MosaicBoard] close source=${source}`);
    MosaicHandwriting?.detachInput().catch(() => {});
    MosaicHandwriting?.lockStatusBar(false).catch(() => {});
    void flushSave().finally(() => {
      setBoardVisible(false, source);
      PluginManager.closePluginView().catch((error) => {
        console.log(`[MosaicBoard] closePluginView failed source=${source}: ${error}`);
        closedRef.current = false;
      });
    });
  }, [flushSave]);

  

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
      if (ok) closeBoard('capture');
    } catch (error) {
      console.log(`[MosaicNoteShot] insert flow failed: ${String(error)}`);
    } finally {
      captureInsertBusyRef.current = false;
    }
  }, [closeBoard]);

  

  const applyCommands = useCallback((ops: BoardCommand[]) => {
    const model = modelRef.current;
    let structureChanged = false;
    let changed = false;
    for (const op of ops) {
      switch (op.type) {
        case 'strokeUpsert': {
          const stroke = strokeFromCommand(op);
          const index = model.ink.findIndex(s => s.id === stroke.id);
          if (index >= 0) model.ink[index] = stroke; else model.ink.push(stroke);
          engineSync.acknowledgeStroke(stroke);
          changed = true;
          break;
        }
        case 'strokesRemove': {
          const ids = new Set(op.ids);
          model.ink = model.ink.filter(s => !ids.has(s.id));
          engineSync.acknowledgeStrokesRemoved(op.ids);
          changed = true;
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
          model.cards = model.cards.filter(c => !ids.has(c.id));
          model.connections = model.connections.filter(c => !ids.has(c.fromCardId) && !ids.has(c.toCardId));
          model.ink = model.ink.filter(s => !(s.space.startsWith('card:') && ids.has(s.space.slice(5))));
          engineSync.acknowledgeCardsRemoved(op.ids);
          structureChanged = true;
          break;
        }
        case 'connectionAdd': {
          if (!model.connections.some(c => c.id === op.id)) model.connections.push(connectionFromCommand(op));
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
          break;
        }
        case 'whiteboardsRemove': {
          const ids = new Set(op.ids);
          model.whiteboards = model.whiteboards.filter(w => !ids.has(w.id));
          changed = true;
          break;
        }
        case 'viewport':
          model.viewport = viewportFromCommand(op);
          changed = true;
          break;
        case 'action':
          if (op.name === 'close') closeBoard('toolbar');
          else if (op.name === 'touchEnabled') setTouchEnabled(op.value);
          else if (op.name === 'captureReady') void insertCapturedShot(op);
          else if (op.name === 'sync') {
            syncClientRef.current?.publish('document', currentDocument());
            if (syncBoardIdRef.current) {
              const link = `https://re.yalums.top/whiteboard/#board=${syncBoardIdRef.current}`;
              Alert.alert('Mosaic 同步', `请在网页端打开此共享链接\n${link}`);
            } else Alert.alert('Mosaic 同步', '正在建立共享白板，请稍后再次点击同步');
          }
          break;
        default:
          console.log(`[MosaicBoardCommand] unknown op ${JSON.stringify(op)}`);
      }
    }
    if (structureChanged) {
      
      engineSync.syncCards(model.cards);
      engineSync.syncConnections(model.connections);
      pushNecks();
    }
    if (structureChanged || changed) scheduleSave();
    if ((structureChanged || changed) && !applyingRemoteRef.current) {
      syncClientRef.current?.publish('document', currentDocument());
    }
  }, [closeBoard, currentDocument, engineSync, insertCapturedShot, pushNecks, scheduleSave]);

  useEffect(() => subscribeBoardCommands(applyCommands), [applyCommands]);

  

  const importPendingImageCard = useCallback(async () => {
    if (!loadedRef.current || importBusyRef.current) return;
    importBusyRef.current = true;
    let claim: Awaited<ReturnType<typeof claimPendingMosaicImageCard>> = null;
    let committed = false;
    try {
      claim = await claimPendingMosaicImageCard();
      if (claim === null) return;
      if (!imageAvailable()) throw new Error('MosaicImage native module unavailable');
      console.log(`[MosaicImport] claimed request=${claim.request.id} source=${claim.request.imagePath}`);
      const imported = await importImage(claim.request.imagePath);
      const model = modelRef.current;
      const viewport = model.viewport;
      
      const { width: screenW, height: screenH } = Dimensions.get('window');
      const visible = viewportWorldRect(viewport.panX, viewport.panY, viewport.scale, screenW, screenH);
      const size = imageCardSizeFor(imported);
      const created = createImageCard({
        imageRef: imported.imageRef,
        natural: imported,
        x: visible.x + (visible.width - size.width) / 2,
        y: visible.y + (visible.height - size.height) / 2,
        zIndex: nextZIndex(model.cards),
        content: '',
      });
      model.cards = [...model.cards, created];
      engineSync.syncCards(model.cards);
      pushNecks();
      committed = true;
      scheduleSave();
      if (!applyingRemoteRef.current) syncClientRef.current?.publish('document', currentDocument());
      await completePendingMosaicImageCard(claim);
      console.log(`[MosaicImport] inserted card=${created.id} request=${claim.request.id}`);
    } catch (error) {
      if (claim !== null && !committed) await releasePendingMosaicImageCard(claim);
      console.log(`[MosaicImport] failed: ${String(error)}`);
    } finally {
      importBusyRef.current = false;
    }
  }, [currentDocument, engineSync, pushNecks, scheduleSave]);

  

  useEffect(() => {
    let cancelled = false;
    void ensureMosaicImportPermissions().then(ready => {
      if (cancelled) return;
      setPluginPermissionsReady(ready);
      if (ready) return;
      ToastAndroid.show(t('mosaicPermissionNeeded'), ToastAndroid.LONG);
      closeBoard('permission-denied');
    });
    return () => {
      cancelled = true;
    };
  }, [closeBoard]);

  useEffect(() => {
    if (pluginPermissionsReady !== true) return;
    StatusBar.setHidden(true, 'none');
    setBoardVisible(true, 'permissions-ready');
    return () => {
      StatusBar.setHidden(false, 'none');
      setBoardVisible(false, 'unmount');
    };
  }, [pluginPermissionsReady]);

  
  useEffect(() => {
    let cancelled = false;
    Promise.all([
      loadBoard().catch(error => {
        console.log(`[MosaicBoard] load failed: ${String(error)}`);
        return null;
      }),
      ensureImageDir().catch(error => {
        console.log(`[MosaicImage] image dir unavailable: ${String(error)}`);
        return null;
      }),
      PluginManager.getDeviceType().catch(() => -1),
    ]).then(([doc, , type]) => {
      if (cancelled) return;
      setDeviceType(type);
      const cleaned = doc === null ? null : removeLegacySampleCards(doc);
      if (cleaned !== null) {
        metaRef.current = cleaned.meta;
        extrasRef.current = { blobs: cleaned.blobs, ext: cleaned.ext };
        modelRef.current = {
          cards: cleaned.cards,
          connections: cleaned.connections,
          ink: cleaned.ink,
          whiteboards: cleaned.whiteboards,
          viewport: { ...cleaned.meta.viewport, scale: DEFAULT_ZOOM },
        };
        
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
        + `whiteboards=${modelRef.current.whiteboards.length} deviceType=${type}`,
      );
    });
    return () => {
      cancelled = true;
    };
  }, [pushFullScene]);

  useEffect(() => {
    if (!BOARD_SYNC_ENABLED || !loaded) return;
    let cancelled = false;
    const client = new BoardSyncClient();
    syncClientRef.current = client;
    void (async () => {
      try {
        if (!(await ensureSyncNetworkPermission())) throw new Error('网络权限未授予');
        const config = await loadSyncConfig();
        let boardId = config.boardId;
        if (!boardId) {
          boardId = await createSharedBoard(currentDocument());
          await saveSyncConfig({ serverUrl: 'https://re.yalums.top/whiteboard/', boardId });
          console.log(`[MosaicSync] room.created board=${boardId}`);
        }
        syncBoardIdRef.current = boardId;
        if (cancelled) return;
        const endpoint = 'wss://re.yalums.top/whiteboard/ws/board';
        client.connect(boardId, currentDocument(), (document, _revision, domain) => {
          if (domain === 'import') {
            void confirmRemoteImport(document);
            return;
          }
          applyRemoteDocument(document);
        }, endpoint, state => {
          console.log(`[MosaicSync] state=${state} board=${boardId}`);
        });
      } catch (error) {
        console.log(`[MosaicSync] setup.error ${String(error)}`);
      }
    })();
    return () => {
      cancelled = true;
      client.close();
      if (syncClientRef.current === client) syncClientRef.current = null;
    };
  }, [applyRemoteDocument, confirmRemoteImport, currentDocument, loaded]);

  
  const attachNativeInput = useCallback(() => {
    if (pluginPermissionsReady !== true || deviceType === null) return;
    MosaicHandwriting?.attachInput(deviceType).catch(() => {});
    
    MosaicHandwriting?.lockStatusBar(false).catch(() => {});
  }, [deviceType, pluginPermissionsReady]);

  useEffect(() => {
    attachNativeInput();
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') attachNativeInput();
    });
    return () => subscription.remove();
  }, [attachNativeInput]);

  
  useEffect(() => {
    if (!loaded || pluginPermissionsReady !== true) return;
    console.log(`[MosaicImport] polling inbox=${MOSAIC_INBOX_DIR}`);
    void importPendingImageCard();
    const timer = setInterval(() => {
      void importPendingImageCard();
    }, INBOX_POLL_MS);
    return () => clearInterval(timer);
  }, [importPendingImageCard, loaded, pluginPermissionsReady]);

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

  if (pluginPermissionsReady !== true) return null;

  return (
    <View style={styles.root}>
      <MosaicBoardViewNative
        ref={boardRef}
        style={styles.board}
        penWidth={PEN_WIDTH_PX * 100}
        deviceType={deviceType ?? -1}
        touchEnabled={touchEnabled}
      />
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
