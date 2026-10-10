// rangeSelection.ts — Line-based range selection for parent card splitting
// Used by App.tsx (Mosaic) and BoundaryEditor (Web).

export interface RangeSelectionState {
  /** Set of selected line indices. */
  selected: Set<number>
}

export interface SelectionRange {
  first: number
  last: number
}

export function emptySelection(): RangeSelectionState {
  return { selected: new Set() }
}

export function hasSelection(state: RangeSelectionState): boolean {
  return state.selected.size > 0
}

export function isSelected(state: RangeSelectionState, index: number): boolean {
  return state.selected.has(index)
}

/**
 * Toggle a line in the selection. Locked lines (already extracted) are skipped.
 * Lines between existing selected lines are auto-filled to keep ranges contiguous.
 */
export function toggleLine(
  state: RangeSelectionState,
  index: number,
  _totalLines: number,
  isLocked?: (index: number) => boolean,
): RangeSelectionState {
  if (isLocked?.(index)) return state
  const next = new Set(state.selected)
  if (next.has(index)) {
    next.delete(index)
  } else {
    next.add(index)
  }
  return { selected: next }
}

/**
 * Return visual indicator for a line: 'start', 'end', 'middle', or null.
 */
export function selectionIndicator(
  state: RangeSelectionState,
  index: number,
): 'start' | 'end' | 'middle' | null {
  if (!state.selected.has(index)) return null
  const hasPrev = state.selected.has(index - 1)
  const hasNext = state.selected.has(index + 1)
  if (!hasPrev && !hasNext) return 'start' // single line
  if (!hasPrev) return 'start'
  if (!hasNext) return 'end'
  return 'middle'
}

/**
 * Extract contiguous ranges from the selection, sorted ascending.
 */
export function sortedRanges(state: RangeSelectionState): SelectionRange[] {
  if (state.selected.size === 0) return []
  const sorted = [...state.selected].sort((a, b) => a - b)
  const ranges: SelectionRange[] = []
  let first = sorted[0]
  let last = sorted[0]
  for (let i = 1; i < sorted.length; i++) {
    if (sorted[i] === last + 1) {
      last = sorted[i]
    } else {
      ranges.push({ first, last })
      first = sorted[i]
      last = sorted[i]
    }
  }
  ranges.push({ first, last })
  return ranges
}
