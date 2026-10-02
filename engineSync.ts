import { NativeModules } from 'react-native';
import {
  STROKE_ENCODING_F32X3,
  type InkStroke,
  type WhiteboardAnchor,
} from './React/src/boardFormat';
import { bytesToBase64 } from './React/src/base64';
import { resolveCardSize } from './React/src/cardGeometry';
import type { Card, Connection, Viewport } from './React/src/types';
import { imagePathFor } from './imageStore';
import { notePathFor } from './noteStore';



const PROTOCOL_VERSION = 5;

const OP_ADD_STROKE = 1;
const OP_REMOVE_STROKES = 2;
const OP_UPSERT_CARD = 3;
const OP_REMOVE_CARDS = 4;

const OP_SET_WHITEBOARDS = 6;
const OP_SET_CONNECTIONS = 8;

type EngineModule = {
  applyOps(base64: string): void;
  setViewport(panX: number, panY: number, scale: number): void;
  
  setViewportIfUnset(panX: number, panY: number, scale: number): void;
  clearScene(): void;
  
  documentReplaced(viewTag: number): void;
  undo(viewTag: number): void;
  redo(viewTag: number): void;
  
  selectCards(viewTag: number, ids: string[]): void;
  
  invalidateImages(paths: string[]): void;
};

const MosaicBoardEngine = NativeModules.MosaicBoardEngine as EngineModule | undefined;

export function engineAvailable(): boolean {
  return MosaicBoardEngine !== undefined;
}

class OpsWriter {
  private buffer: ArrayBuffer;
  private view: DataView;
  private bytes: Uint8Array;
  private offset = 0;
  private opCount = 0;

  constructor(initialCapacity = 16 * 1024) {
    this.buffer = new ArrayBuffer(initialCapacity);
    this.view = new DataView(this.buffer);
    this.bytes = new Uint8Array(this.buffer);
    this.u16(PROTOCOL_VERSION);
    this.u32(0);
  }

  get count(): number {
    return this.opCount;
  }

  beginOp(type: number) {
    this.u8(type);
    this.opCount += 1;
  }

  u8(value: number) {
    this.ensure(1);
    this.view.setUint8(this.offset, value);
    this.offset += 1;
  }

  u16(value: number) {
    this.ensure(2);
    this.view.setUint16(this.offset, value, true);
    this.offset += 2;
  }

  u32(value: number) {
    this.ensure(4);
    this.view.setUint32(this.offset, value >>> 0, true);
    this.offset += 4;
  }

  i32(value: number) {
    this.ensure(4);
    this.view.setInt32(this.offset, value | 0, true);
    this.offset += 4;
  }

  f32(value: number) {
    this.ensure(4);
    this.view.setFloat32(this.offset, value, true);
    this.offset += 4;
  }

  
  raw(data: Uint8Array) {
    this.ensure(data.byteLength);
    this.bytes.set(data, this.offset);
    this.offset += data.byteLength;
  }

  str(value: string) {
    const encoded = utf8Encode(value);
    const length = Math.min(encoded.length, 0xffff);
    this.u16(length);
    this.ensure(length);
    this.bytes.set(encoded.subarray(0, length), this.offset);
    this.offset += length;
  }

  finish(): string {
    this.view.setUint32(2, this.opCount, true);
    return bytesToBase64(new Uint8Array(this.buffer, 0, this.offset));
  }

  private ensure(extra: number) {
    if (this.offset + extra <= this.buffer.byteLength) return;
    let capacity = this.buffer.byteLength * 2;
    while (capacity < this.offset + extra) capacity *= 2;
    const next = new ArrayBuffer(capacity);
    new Uint8Array(next).set(new Uint8Array(this.buffer, 0, this.offset));
    this.buffer = next;
    this.view = new DataView(next);
    this.bytes = new Uint8Array(next);
  }
}


function utf8Encode(text: string): Uint8Array {
  const out: number[] = [];
  for (let index = 0; index < text.length; index += 1) {
    let code = text.charCodeAt(index);
    if (code >= 0xd800 && code <= 0xdbff && index + 1 < text.length) {
      const low = text.charCodeAt(index + 1);
      if (low >= 0xdc00 && low <= 0xdfff) {
        code = 0x10000 + ((code - 0xd800) << 10) + (low - 0xdc00);
        index += 1;
      }
    }
    if (code < 0x80) {
      out.push(code);
    } else if (code < 0x800) {
      out.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
    } else if (code < 0x10000) {
      out.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    } else {
      out.push(
        0xf0 | (code >> 18),
        0x80 | ((code >> 12) & 0x3f),
        0x80 | ((code >> 6) & 0x3f),
        0x80 | (code & 0x3f),
      );
    }
  }
  return Uint8Array.from(out);
}

function writeStroke(writer: OpsWriter, stroke: InkStroke) {
  writer.beginOp(OP_ADD_STROKE);
  writer.str(stroke.id);
  writer.str(stroke.space);
  writer.f32(stroke.width);
  writer.u32(stroke.color);
  
  writer.u16(stroke.pen);
  writer.f32(stroke.sampleScale);
  writer.u32(stroke.drawPathWidth ?? Math.round(Math.max(0.5, Math.min(40, stroke.width)) * 100));
  const points = stroke.points;
  
  
  
  
  if ((stroke.encoding === undefined || stroke.encoding === STROKE_ENCODING_F32X3)
      && points.byteLength % 12 === 0) {
    writer.u32(points.byteLength / 12);
    writer.raw(points);
    return;
  }
  const count = Math.floor(points.byteLength / 12);
  writer.u32(count);
  const view = new DataView(points.buffer, points.byteOffset, points.byteLength);
  for (let index = 0; index < count; index += 1) {
    const offset = index * 12;
    writer.f32(view.getFloat32(offset, true));
    writer.f32(view.getFloat32(offset + 4, true));
    writer.f32(view.getFloat32(offset + 8, true));
  }
}

function writeCard(writer: OpsWriter, card: Card) {
  const size = resolveCardSize(card);
  writer.beginOp(OP_UPSERT_CARD);
  writer.str(card.id);
  writer.f32(card.x);
  writer.f32(card.y);
  writer.f32(size.width);
  writer.f32(size.height);
  writer.i32(card.zIndex);
  writer.str(card.kind ?? 'text');
  writer.str(card.content ?? '');
  
  
  
  writer.str(card.kind === 'image' ? imagePathFor(card.imageRef) : card.kind === 'note' ? notePathFor(card.noteRef) : '');
  writer.str(card.kind === 'note' ? (card.noteRef ?? '') : '');
  
  writer.str(card.bgColor ?? '');
  writer.str(card.textColor ?? '');
  
  writer.str(card.title ?? '');
}

function writeIdList(writer: OpsWriter, op: number, ids: string[]) {
  writer.beginOp(op);
  writer.u32(ids.length);
  for (const id of ids) writer.str(id);
}

export class EngineSyncSession {
  private lastInk = new Map<string, InkStroke>();
  private lastCards = new Map<string, Card>();
  private lastWhiteboardsSignature = '';
  private lastConnectionsSignature = '';

  
  reset() {
    this.lastInk = new Map();
    this.lastCards = new Map();
    this.lastWhiteboardsSignature = '';
    this.lastConnectionsSignature = '';
    MosaicBoardEngine?.clearScene();
  }

  setViewport(viewport: Viewport) {
    MosaicBoardEngine?.setViewport(viewport.panX, viewport.panY, viewport.scale || 1);
  }

  
  setViewportIfUnset(viewport: Viewport) {
    MosaicBoardEngine?.setViewportIfUnset?.(viewport.panX, viewport.panY, viewport.scale || 1);
  }

  documentReplaced(viewTag: number | null) {
    if (viewTag !== null) MosaicBoardEngine?.documentReplaced(viewTag);
  }

  selectCards(viewTag: number | null, ids: string[]) {
    if (viewTag !== null && ids.length > 0) MosaicBoardEngine?.selectCards?.(viewTag, ids);
  }

  
  invalidateImages(paths: string[]) {
    if (paths.length > 0) MosaicBoardEngine?.invalidateImages?.(paths);
  }

  
  refreshAssetCardPaths(cards: Card[]) {
    if (MosaicBoardEngine === undefined) return;
    const assetCards = cards.filter(card =>
      (card.kind === 'note' && Boolean(card.noteRef)) ||
      (card.kind === 'image' && Boolean(card.imageRef)),
    );
    if (assetCards.length === 0) return;
    const writer = new OpsWriter();
    for (const card of assetCards) writeCard(writer, card);
    MosaicBoardEngine.applyOps(writer.finish());
    for (const card of assetCards) this.lastCards.set(card.id, card);
    this.invalidateImages(
      assetCards
        .map(card => card.kind === 'note' ? notePathFor(card.noteRef) : imagePathFor(card.imageRef))
        .filter(path => path.length > 0),
    );
  }

  

  acknowledgeStroke(stroke: InkStroke) {
    this.lastInk.set(stroke.id, stroke);
  }

  acknowledgeStrokes(strokes: InkStroke[]) {
    for (const stroke of strokes) this.lastInk.set(stroke.id, stroke);
  }

  acknowledgeStrokesRemoved(ids: string[]) {
    for (const id of ids) this.lastInk.delete(id);
  }

  acknowledgeCard(card: Card) {
    this.lastCards.set(card.id, card);
  }

  acknowledgeCardsRemoved(ids: string[]) {
    for (const id of ids) this.lastCards.delete(id);
  }

  

  
  syncInk(next: InkStroke[]) {
    if (MosaicBoardEngine === undefined) return;
    const writer = new OpsWriter();
    const nextIds = new Set<string>();
    let changedCount = 0;
    let changedPointBytes = 0;
    for (const stroke of next) {
      nextIds.add(stroke.id);
      if (this.lastInk.get(stroke.id) !== stroke) {
        writeStroke(writer, stroke);
        changedCount += 1;
        changedPointBytes += stroke.points.byteLength;
      }
    }
    const removed: string[] = [];
    for (const id of this.lastInk.keys()) {
      if (!nextIds.has(id)) removed.push(id);
    }
    if (removed.length > 0) writeIdList(writer, OP_REMOVE_STROKES, removed);
    if (writer.count > 0) {
      const encodeStartedAt = Date.now();
      const payload = writer.finish();
      const encodeMs = Date.now() - encodeStartedAt;
      const applyStartedAt = Date.now();
      MosaicBoardEngine.applyOps(payload);
      const applyMs = Date.now() - applyStartedAt;
      if (changedCount >= 64 || changedPointBytes >= 256 * 1024) {
        console.log(
          `[MosaicEngineSync] ink transfer ops=${writer.count} changed=${changedCount} `
          + `removed=${removed.length} pointsBytes=${changedPointBytes} `
          + `base64Bytes=${payload.length} encodeMs=${encodeMs} applyMs=${applyMs}`,
        );
      }
    }
    this.lastInk = new Map(next.map(stroke => [stroke.id, stroke]));
  }

  
  syncCards(next: Card[]) {
    if (MosaicBoardEngine === undefined) return;
    const writer = new OpsWriter();
    const nextIds = new Set<string>();
    for (const card of next) {
      nextIds.add(card.id);
      if (this.lastCards.get(card.id) !== card) writeCard(writer, card);
    }
    const removed: string[] = [];
    for (const id of this.lastCards.keys()) {
      if (!nextIds.has(id)) removed.push(id);
    }
    if (removed.length > 0) writeIdList(writer, OP_REMOVE_CARDS, removed);
    if (writer.count > 0) MosaicBoardEngine.applyOps(writer.finish());
    this.lastCards = new Map(next.map(card => [card.id, card]));
  }

  
  syncConnections(connections: Connection[]) {
    if (MosaicBoardEngine === undefined) return;
    const signature = connections.map(c => `${c.id}:${c.fromCardId}:${c.toCardId}:${c.locked === true ? 1 : 0}`).join(';');
    if (signature === this.lastConnectionsSignature) return;
    this.lastConnectionsSignature = signature;
    const writer = new OpsWriter();
    writer.beginOp(OP_SET_CONNECTIONS);
    writer.u32(connections.length);
    for (const c of connections) {
      writer.str(c.id);
      writer.str(c.fromCardId);
      writer.str(c.toCardId);
      writer.u8(c.locked === true ? 1 : 0);
    }
    MosaicBoardEngine.applyOps(writer.finish());
  }

  
  syncWhiteboards(whiteboards: WhiteboardAnchor[], names: (wb: WhiteboardAnchor) => string) {
    if (MosaicBoardEngine === undefined) return;
    const signature = whiteboards
      .map(wb => `${wb.id}:${names(wb)}:${wb.x}:${wb.y}:${wb.width}:${wb.height}`)
      .join(';');
    if (signature === this.lastWhiteboardsSignature) return;
    this.lastWhiteboardsSignature = signature;
    const writer = new OpsWriter();
    writer.beginOp(OP_SET_WHITEBOARDS);
    writer.u32(whiteboards.length);
    for (const wb of whiteboards) {
      writer.str(wb.id);
      writer.str(names(wb));
      writer.f32(wb.x);
      writer.f32(wb.y);
      writer.f32(wb.width);
      writer.f32(wb.height);
      writer.u8(0);
    }
    MosaicBoardEngine.applyOps(writer.finish());
  }
}
