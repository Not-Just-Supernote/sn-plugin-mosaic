import type { InkStroke } from './boardFormat'
import type { Card } from './types'

/**
 * 橡皮命中测试用的笔迹索引。
 *
 * canvas 空间笔迹按 512 世界单位网格登记（按包围盒覆盖的格子）；
 * card 空间笔迹按所属卡片聚合，并维护每张卡的局部包围盒并集。卡片移动不需要
 * 更新索引：查询时用卡片当前位置换算。
 *
 * 语义与旧的 findStrokesAtPoint 相同：命中 = 点落在笔迹包围盒外扩 radius 的范围内。
 */

const CELL = 512

/** 与 InkStroke.bounds 同型：[left, top, right, bottom]。 */
type Bounds = InkStroke['bounds']

type CardBucket = {
  strokes: Map<string, InkStroke>
  /** 局部坐标并集包围盒，删除后置 null 待重算。 */
  union: Bounds | null
}

function cellKey(cx: number, cy: number): string {
  return `${cx},${cy}`
}

export class InkIndex {
  private cells = new Map<string, Set<string>>()
  private canvasStrokes = new Map<string, InkStroke>()
  private cardBuckets = new Map<string, CardBucket>()

  rebuild(strokes: InkStroke[]) {
    this.cells.clear()
    this.canvasStrokes.clear()
    this.cardBuckets.clear()
    for (const stroke of strokes) this.add(stroke)
  }

  add(stroke: InkStroke) {
    if (stroke.space === 'canvas') {
      this.canvasStrokes.set(stroke.id, stroke)
      this.forEachCell(stroke.bounds, key => {
        let set = this.cells.get(key)
        if (set === undefined) {
          set = new Set()
          this.cells.set(key, set)
        }
        set.add(stroke.id)
      })
      return
    }
    if (!stroke.space.startsWith('card:')) return
    const cardId = stroke.space.slice(5)
    let bucket = this.cardBuckets.get(cardId)
    if (bucket === undefined) {
      bucket = { strokes: new Map(), union: null }
      this.cardBuckets.set(cardId, bucket)
    }
    bucket.strokes.set(stroke.id, stroke)
    if (bucket.union !== null) bucket.union = unionBounds(bucket.union, stroke.bounds)
  }

  remove(id: string) {
    const canvasStroke = this.canvasStrokes.get(id)
    if (canvasStroke !== undefined) {
      this.canvasStrokes.delete(id)
      this.forEachCell(canvasStroke.bounds, key => {
        const set = this.cells.get(key)
        if (set === undefined) return
        set.delete(id)
        if (set.size === 0) this.cells.delete(key)
      })
      return
    }
    for (const [cardId, bucket] of this.cardBuckets) {
      if (!bucket.strokes.delete(id)) continue
      if (bucket.strokes.size === 0) this.cardBuckets.delete(cardId)
      else bucket.union = null
      return
    }
  }

  hitTest(worldPoint: { x: number; y: number }, radius: number, cards: Card[]): InkStroke[] {
    const hits: InkStroke[] = []
    const seen = new Set<string>()
    const minCx = Math.floor((worldPoint.x - radius) / CELL)
    const maxCx = Math.floor((worldPoint.x + radius) / CELL)
    const minCy = Math.floor((worldPoint.y - radius) / CELL)
    const maxCy = Math.floor((worldPoint.y + radius) / CELL)
    for (let cy = minCy; cy <= maxCy; cy += 1) {
      for (let cx = minCx; cx <= maxCx; cx += 1) {
        const set = this.cells.get(cellKey(cx, cy))
        if (set === undefined) continue
        for (const id of set) {
          if (seen.has(id)) continue
          seen.add(id)
          const stroke = this.canvasStrokes.get(id)
          if (stroke !== undefined && boundsContain(stroke.bounds, worldPoint.x, worldPoint.y, radius)) {
            hits.push(stroke)
          }
        }
      }
    }
    if (this.cardBuckets.size === 0) return hits
    for (const card of cards) {
      const bucket = this.cardBuckets.get(card.id)
      if (bucket === undefined) continue
      const localX = worldPoint.x - card.x
      const localY = worldPoint.y - card.y
      if (bucket.union === null) bucket.union = computeUnion(bucket.strokes)
      if (bucket.union === null || !boundsContain(bucket.union, localX, localY, radius)) continue
      for (const stroke of bucket.strokes.values()) {
        if (boundsContain(stroke.bounds, localX, localY, radius)) hits.push(stroke)
      }
    }
    return hits
  }

  private forEachCell(bounds: Bounds, visit: (key: string) => void) {
    const minCx = Math.floor(bounds[0] / CELL)
    const maxCx = Math.floor(bounds[2] / CELL)
    const minCy = Math.floor(bounds[1] / CELL)
    const maxCy = Math.floor(bounds[3] / CELL)
    for (let cy = minCy; cy <= maxCy; cy += 1) {
      for (let cx = minCx; cx <= maxCx; cx += 1) visit(cellKey(cx, cy))
    }
  }
}

function boundsContain(bounds: Bounds, x: number, y: number, radius: number): boolean {
  return x >= bounds[0] - radius
    && x <= bounds[2] + radius
    && y >= bounds[1] - radius
    && y <= bounds[3] + radius
}

function unionBounds(a: Bounds, b: Bounds): Bounds {
  return [
    Math.min(a[0], b[0]),
    Math.min(a[1], b[1]),
    Math.max(a[2], b[2]),
    Math.max(a[3], b[3]),
  ] as Bounds
}

function computeUnion(strokes: Map<string, InkStroke>): Bounds | null {
  let union: Bounds | null = null
  for (const stroke of strokes.values()) {
    union = union === null ? [...stroke.bounds] as Bounds : unionBounds(union, stroke.bounds)
  }
  return union
}
