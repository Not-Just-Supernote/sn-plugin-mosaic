import { NativeEventEmitter, NativeModules } from 'react-native';
import {
  computeStrokeBounds,
  unpackStrokePoints,
  CARD_DEFAULTS,
  CONNECTION_DEFAULT_COLOR,
  STROKE_ENCODING_F32X3,
  type InkStroke,
  type WhiteboardAnchor,
} from './React/src/boardFormat';
import { base64ToBytes } from './React/src/base64';
import type { Card, Connection, Viewport } from './React/src/types';



export const BOARD_COMMAND_EVENT = 'MosaicBoardCommand';

export type BoardCommand =
  | { type: 'strokeUpsert'; id: string; space: string; width: number; color: number; points: string }
  | { type: 'strokesRemove'; ids: string[] }
  | { type: 'cardUpsert'; id: string; x: number; y: number; width: number; height: number; zIndex: number; kind: string; bgColor?: string; textColor?: string }
  | { type: 'cardsRemove'; ids: string[] }
  | { type: 'connectionAdd'; id: string; from: string; to: string }
  | { type: 'connectionsRemove'; ids: string[] }
  | { type: 'whiteboardUpsert'; id: string; name: string; x: number; y: number; width: number; height: number }
  | { type: 'whiteboardsRemove'; ids: string[] }
  | { type: 'viewport'; panX: number; panY: number; scale: number }
  | { type: 'action'; name: 'close' | 'sync' }
  | { type: 'action'; name: 'touchEnabled'; value: boolean }
  
  | {
      type: 'action';
      name: 'captureReady';
      path: string;
      wbId: string;
      wbName: string;
      
      rect: { x: number; y: number; w: number; h: number };
      
      hotspot: { x: number; y: number; w: number; h: number };
    };

export function subscribeBoardCommands(handler: (ops: BoardCommand[]) => void): () => void {
  const module = NativeModules.MosaicBoardEngine;
  if (module === undefined) return () => {};
  const emitter = new NativeEventEmitter(module);
  const subscription = emitter.addListener(BOARD_COMMAND_EVENT, (payload: { ops?: BoardCommand[] }) => {
    if (Array.isArray(payload?.ops) && payload.ops.length > 0) handler(payload.ops);
  });
  return () => subscription.remove();
}


export function strokeFromCommand(op: Extract<BoardCommand, { type: 'strokeUpsert' }>): InkStroke {
  const points = base64ToBytes(op.points);
  return {
    id: op.id,
    space: op.space,
    width: op.width,
    color: op.color >>> 0,
    encoding: STROKE_ENCODING_F32X3,
    points,
    bounds: computeStrokeBounds(unpackStrokePoints(points)),
    createdAt: new Date().toISOString(),
  };
}


export function cardFromCommand(
  op: Extract<BoardCommand, { type: 'cardUpsert' }>,
  existing: Card | undefined,
): Card {
  
  const colors = {
    bgColor: op.bgColor ? op.bgColor : CARD_DEFAULTS.bgColor,
    textColor: op.textColor ? op.textColor : CARD_DEFAULTS.textColor,
  };
  const geometry = {
    x: op.x,
    y: op.y,
    width: op.width,
    height: op.height,
    zIndex: op.zIndex,
  };
  if (existing !== undefined) return { ...existing, ...geometry, ...colors };
  const card: Card = {
    ...CARD_DEFAULTS,
    ...geometry,
    ...colors,
    id: op.id,
    content: '',
    tags: [],
    sourceType: 'manual',
    createdAt: new Date().toISOString(),
  };
  if (op.kind !== '' && op.kind !== 'text') card.kind = op.kind as Card['kind'];
  return card;
}

export function connectionFromCommand(op: Extract<BoardCommand, { type: 'connectionAdd' }>): Connection {
  return {
    id: op.id,
    fromCardId: op.from,
    toCardId: op.to,
    color: CONNECTION_DEFAULT_COLOR,
    label: '',
  };
}

export function whiteboardFromCommand(
  op: Extract<BoardCommand, { type: 'whiteboardUpsert' }>,
): WhiteboardAnchor {
  return { id: op.id, name: op.name, x: op.x, y: op.y, width: op.width, height: op.height };
}

export function viewportFromCommand(op: Extract<BoardCommand, { type: 'viewport' }>): Viewport {
  return { panX: op.panX, panY: op.panY, scale: op.scale };
}
