import type { InkStroke } from './React/src/boardFormat';
import type { Card, Connection, Viewport } from './React/src/types';
import {
  cardFromCommand,
  connectionFromCommand,
  strokeFromCommand,
  strokesFromBatch,
  type BoardCommand,
} from './boardCommands';
import type { EngineSyncSession } from './engineSync';

/** The board as JS keeps it: what is saved, synced, and pushed back to Kotlin. */
export type BoardModel = {
  cards: Card[];
  connections: Connection[];
  ink: InkStroke[];
  viewport: Viewport;
};

/**
 * Applies one batch of Kotlin's scene commands (strokes, cards, connections) to the model and acknowledges
 * them to engineSync. Removed strokes are dropped together in finish().
 */
export class ModelBatch {
  changed = false;
  structureChanged = false;
  inkChanged = false;
  batchStrokeCount = 0;
  removedInkRequested = 0;
  private readonly inkIndex = new Map<string, number>();
  private readonly removedInkIds = new Set<string>();

  constructor(private readonly model: BoardModel, private readonly engineSync: EngineSyncSession) {
    model.ink.forEach((stroke, index) => this.inkIndex.set(stroke.id, index));
  }

  /** Applies op if it edits the scene; returns false for any other command. */
  apply(op: BoardCommand): boolean {
    const model = this.model;
    switch (op.type) {
      case 'strokeUpsert': {
        this.batchStrokeCount += 1;
        const stroke = strokeFromCommand(op);
        this.putStroke(stroke);
        this.engineSync.acknowledgeStroke(stroke);
        this.changed = true;
        this.inkChanged = true;
        return true;
      }
      case 'strokeBatch': {
        this.batchStrokeCount += op.ids.length;
        const strokes = strokesFromBatch(op);
        for (const stroke of strokes) this.putStroke(stroke);
        this.engineSync.acknowledgeStrokes(strokes);
        if (strokes.length > 0) {
          this.changed = true;
          this.inkChanged = true;
        }
        return true;
      }
      case 'strokesRemove': {
        this.removedInkRequested += op.ids.length;
        for (const id of op.ids) this.removedInkIds.add(id);
        this.engineSync.acknowledgeStrokesRemoved(op.ids);
        this.changed = true;
        this.inkChanged = true;
        return true;
      }
      case 'cardUpsert': {
        const index = model.cards.findIndex(c => c.id === op.id);
        const card = cardFromCommand(op, index >= 0 ? model.cards[index] : undefined);
        if (index >= 0) model.cards[index] = card; else model.cards.push(card);
        this.engineSync.acknowledgeCard(card);
        this.structureChanged = true;
        return true;
      }
      case 'cardsRemove': {
        const ids = new Set(op.ids);
        const removedConnectionIds = new Set(model.connections
          .filter(connection => ids.has(connection.fromCardId) || ids.has(connection.toCardId))
          .map(connection => connection.id));
        model.cards = model.cards.filter(c => !ids.has(c.id));
        model.connections = model.connections.filter(c => !ids.has(c.fromCardId) && !ids.has(c.toCardId));
        const inkBefore = model.ink.length;
        // Ink on the removed cards and on their connections goes with them.
        model.ink = model.ink.filter(s =>
          !(s.space.startsWith('card:') && ids.has(s.space.slice(5))) &&
          !(s.space.startsWith('connection:') && removedConnectionIds.has(s.space.slice('connection:'.length))),
        );
        this.inkIndex.clear();
        model.ink.forEach((stroke, index) => this.inkIndex.set(stroke.id, index));
        if (model.ink.length !== inkBefore) this.inkChanged = true;
        this.engineSync.acknowledgeCardsRemoved(op.ids);
        this.structureChanged = true;
        return true;
      }
      case 'connectionAdd': {
        const index = model.connections.findIndex(c => c.id === op.id);
        const connection = connectionFromCommand(op);
        if (index >= 0) model.connections[index] = { ...model.connections[index], locked: connection.locked === true };
        else model.connections.push(connection);
        this.structureChanged = true;
        return true;
      }
      case 'connectionsRemove': {
        const ids = new Set(op.ids);
        model.connections = model.connections.filter(c => !ids.has(c.id));
        this.structureChanged = true;
        return true;
      }
      default:
        return false;
    }
  }

  /** Drops the strokes removed in this batch; call once after the last command. */
  finish(): void {
    if (this.removedInkIds.size > 0) {
      this.model.ink = this.model.ink.filter(stroke => !this.removedInkIds.has(stroke.id));
    }
  }

  private putStroke(stroke: InkStroke): void {
    const index = this.inkIndex.get(stroke.id);
    if (index !== undefined) this.model.ink[index] = stroke;
    else {
      this.inkIndex.set(stroke.id, this.model.ink.length);
      this.model.ink.push(stroke);
    }
    this.removedInkIds.delete(stroke.id);
  }
}
