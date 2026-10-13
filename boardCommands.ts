import { NativeEventEmitter, NativeModules } from 'react-native';
import {
  computeStrokeBounds,
  unpackStrokePoints,
  CARD_DEFAULTS,
  CONNECTION_DEFAULT_COLOR,
  STROKE_ENCODING_F32X3,
  canonicalDrawPathWidth,
  readContourOffsets,
  type InkStroke,
} from './React/src/boardFormat';
import { base64ToBytes } from './React/src/base64';
import type { Card, Connection, Viewport } from './React/src/types';



export const BOARD_COMMAND_EVENT = 'MosaicBoardCommand';

export type BoardCommand =
  | {
      type: 'strokeUpsert'; id: string; space: string; width: number; color: number;
      
      pen: number; drawPathWidth?: number; sampleScale: number; nibAngle?: number; angles?: string; contourOffsets?: number[]; points: string;
      bounds?: { left: number; top: number; right: number; bottom: number };
    }
  | {
      
      type: 'strokeBatch'; ids: string[]; spaces: string[]; widths: number[]; colors: number[];
      pens: number[]; drawPathWidths: number[]; sampleScales: number[]; nibAngles?: (number | null)[]; angles?: string[]; contourOffsets?: number[][]; bounds: number[];
      pointOffsets: number[]; pointCounts: number[]; points: string;
    }
  | { type: 'strokesRemove'; ids: string[] }
  | { type: 'cardUpsert'; id: string; x: number; y: number; width: number; height: number; zIndex: number; kind: string; content?: string; noteRef?: string; imageRef?: string; bgColor?: string; textColor?: string; title?: string; fontScale?: number }
  | { type: 'cardsRemove'; ids: string[] }
  | { type: 'connectionAdd'; id: string; from: string; to: string; locked?: boolean }
  | { type: 'connectionsRemove'; ids: string[] }
  
  | { type: 'viewport'; panX: number; panY: number; scale: number; viewW?: number; viewH?: number; topInset?: number }
  
  | { type: 'action'; name: 'close'; emittedAt?: number }
  
  | { type: 'action'; name: 'sync'; enable: boolean; address: string }
  | { type: 'action'; name: 'touchEnabled'; value: boolean }
  
  | { type: 'action'; name: 'insertTextCard'; text: string; x: number; y: number; width?: number; height?: number; source?: 'inkling' | 'doc' }
  
  | { type: 'action'; name: 'recognizeLasso' }
  | { type: 'action'; name: 'deleteNoteShot'; shotId: string }
  | { type: 'action'; name: 'saveArchive' }
  | { type: 'action'; name: 'loadArchive' }
  
  | {
      type: 'action';
      name: 'noteShotReady';
      shotId: string;
      path: string;
      
      width: number;
      height: number;
      
      rect: { x: number; y: number; w: number; h: number };
      
      hotspot: { x: number; y: number; w: number; h: number };
      
      anchors: string;
      
      fingerprint: string;
      
      pxPerWorld: number;
      
      screenW: number;
      screenH: number;
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


function unpackAngles(encoded?: string): number[] | undefined {
  if (!encoded) return undefined;
  const bytes = base64ToBytes(encoded);
  if (bytes.byteLength % 8 !== 0) throw new Error('Invalid packed stroke angle pairs');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return Array.from({ length: bytes.byteLength / 4 }, (_, index) => view.getInt32(index * 4, true));
}

export function strokeFromCommand(op: Extract<BoardCommand, { type: 'strokeUpsert' }>): InkStroke {
  const pen = normalizeDrawPathPenType(op.pen)
  if (pen === null || !Number.isFinite(op.sampleScale) || op.sampleScale <= 0) {
    throw new Error('Unsupported stroke command: pen and sampleScale are required')
  }
  const points = base64ToBytes(op.points);
  const bounds = op.bounds;
  return {
    id: op.id,
    space: op.space,
    width: op.width,
    color: op.color >>> 0,
    pen,
    drawPathWidth: op.drawPathWidth !== undefined && op.drawPathWidth > 0
      ? op.drawPathWidth
      : canonicalDrawPathWidth(pen, op.width),
    sampleScale: op.sampleScale,
    nibAngle: typeof op.nibAngle === 'number' && Number.isFinite(op.nibAngle) ? op.nibAngle : undefined,
    angles: unpackAngles(op.angles),
    contourOffsets: readContourOffsets(op.contourOffsets, points.byteLength / 12),
    encoding: STROKE_ENCODING_F32X3,
    points,
    
    
    bounds: bounds && [bounds.left, bounds.top, bounds.right, bounds.bottom].every(Number.isFinite)
      ? [bounds.left, bounds.top, bounds.right, bounds.bottom]
      : computeStrokeBounds(unpackStrokePoints(points)),
    createdAt: new Date().toISOString(),
  };
}


export function strokesFromBatch(op: Extract<BoardCommand, { type: 'strokeBatch' }>): InkStroke[] {
  const count = Math.min(
    op.ids.length,
    op.spaces.length,
    op.widths.length,
    op.colors.length,
    op.pens.length,
    op.drawPathWidths.length,
    op.sampleScales.length,
    op.pointOffsets.length,
    op.pointCounts.length,
  );
  if (count <= 0) return [];
  const packed = base64ToBytes(op.points);
  const output: InkStroke[] = new Array(count);
  for (let index = 0; index < count; index += 1) {
    const offset = op.pointOffsets[index] | 0;
    const pointCount = op.pointCounts[index] | 0;
    const byteLength = pointCount * 12;
    if (offset < 0 || pointCount <= 0 || offset + byteLength > packed.byteLength) {
      throw new Error(`Invalid packed stroke range index=${index} offset=${offset} count=${pointCount}`);
    }
    const boundsOffset = index * 4;
    const bounds = op.bounds.length >= boundsOffset + 4
      ? [op.bounds[boundsOffset], op.bounds[boundsOffset + 1], op.bounds[boundsOffset + 2], op.bounds[boundsOffset + 3]]
      : undefined;
    const pen = normalizeDrawPathPenType(op.pens[index]);
    if (pen === null || !Number.isFinite(op.sampleScales[index]) || op.sampleScales[index] <= 0) {
      throw new Error(`Unsupported packed stroke index=${index}`);
    }
    output[index] = {
      id: op.ids[index],
      space: op.spaces[index],
      width: op.widths[index],
      color: op.colors[index] >>> 0,
      pen,
      drawPathWidth: op.drawPathWidths[index] > 0
        ? op.drawPathWidths[index]
        : canonicalDrawPathWidth(pen, op.widths[index]),
      sampleScale: op.sampleScales[index],
      nibAngle: typeof op.nibAngles?.[index] === 'number' && Number.isFinite(op.nibAngles[index])
        ? op.nibAngles[index] as number : undefined,
      angles: unpackAngles(op.angles?.[index]),
      contourOffsets: readContourOffsets(op.contourOffsets?.[index], pointCount),
      encoding: STROKE_ENCODING_F32X3,
      points: packed.subarray(offset, offset + byteLength),
      bounds: bounds && bounds.every(Number.isFinite) ? bounds : computeStrokeBounds(unpackStrokePoints(packed.subarray(offset, offset + byteLength))),
      createdAt: new Date().toISOString(),
    };
  }
  return output;
}


export function normalizeDrawPathPenType(value: number): number | null {
  if (!Number.isInteger(value)) return null
  switch (value) {
    case 0: return 16 
    case 10: return 15 
    case 14: return 15 
    case 17: return 11 
    case 11:
    case 15:
    case 16:
    case 18: return value
    default: return null
  }
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
  if (!op.kind) throw new Error('Card command kind is required')
  if (existing !== undefined) {
    return {
      ...existing,
      ...geometry,
      ...colors,
      kind: op.kind as Card['kind'],
      
      content: op.content ?? existing.content,
      noteRef: op.noteRef || undefined,
      imageRef: op.imageRef ?? existing.imageRef,
      title: op.title || undefined,
      ext: withFontScale(existing.ext, op.fontScale),
    }
  }
  const card: Card = {
    ...CARD_DEFAULTS,
    ...geometry,
    ...colors,
    id: op.id,
    content: op.content ?? '',
    tags: [],
    sourceType: 'manual',
    createdAt: new Date().toISOString(),
  };
  card.kind = op.kind as Card['kind'];
  if (op.noteRef) card.noteRef = op.noteRef;
  if (op.imageRef) card.imageRef = op.imageRef;
  if (op.title) card.title = op.title;
  const ext = withFontScale(undefined, op.fontScale);
  if (ext) card.ext = ext;
  return card;
}

// A text card's text size multiplier lives in card.ext.fontScale; 1 is the default and is not stored.
export function cardFontScale(card: Card): number {
  const value = Number(card.ext?.fontScale);
  return Number.isFinite(value) && value > 0 ? value : 1;
}

function withFontScale(
  ext: Record<string, unknown> | undefined,
  fontScale: number | undefined,
): Record<string, unknown> | undefined {
  if (fontScale === undefined || !Number.isFinite(fontScale)) return ext;
  const next: Record<string, unknown> = { ...(ext ?? {}) };
  if (Math.abs(fontScale - 1) < 1e-3) delete next.fontScale;
  else next.fontScale = fontScale;
  return Object.keys(next).length > 0 ? next : undefined;
}

export function connectionFromCommand(op: Extract<BoardCommand, { type: 'connectionAdd' }>): Connection {
  return {
    id: op.id,
    fromCardId: op.from,
    toCardId: op.to,
    color: CONNECTION_DEFAULT_COLOR,
    label: '',
    locked: op.locked === true,
  };
}

export function viewportFromCommand(op: Extract<BoardCommand, { type: 'viewport' }>): Viewport {
  return { panX: op.panX, panY: op.panY, scale: op.scale };
}
