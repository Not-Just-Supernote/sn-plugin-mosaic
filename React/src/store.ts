import { useSyncExternalStore } from 'react'
import type { Store, CanvasData, CanvasInfo, ParentCard } from './types'

// ============================================
// Generic Store Factory
// ============================================

export function createStore<T extends object>(initialState: T): Store<T> {
  let state = { ...initialState }
  const listeners = new Set<() => void>()

  return {
    getState: () => state,
    setState: (partial) => {
      const nextState = typeof partial === 'function' ? partial(state) : partial
      state = { ...state, ...nextState }
      listeners.forEach(listener => listener())
    },
    subscribe: (listener) => {
      listeners.add(listener)
      return () => { listeners.delete(listener) }
    },
  }
}

// Generic hook to subscribe to any store
export function useStore<T extends object>(store: Store<T>): T {
  return useSyncExternalStore(store.subscribe, store.getState)
}

// ============================================
// Canvas Store
// ============================================

interface CanvasStoreState {
  activeCanvasId: string | null
  canvasData: CanvasData
  canvasList: CanvasInfo[]
}

export const canvasStore = createStore<CanvasStoreState>({
  activeCanvasId: null,
  canvasData: { cards: {}, connections: [], viewport: { panX: 60, panY: 20, scale: 1.0 } },
  canvasList: [],
})

export const canvasActions = {
  setActiveCanvas(id: string | null) {
    canvasStore.setState({ activeCanvasId: id })
  },
  setCanvasData(data: CanvasData) {
    canvasStore.setState({ canvasData: data })
  },
  setCanvasList(list: CanvasInfo[]) {
    canvasStore.setState({ canvasList: list })
  },
  updateCards(cards: CanvasData['cards']) {
    canvasStore.setState(s => ({
      canvasData: { ...s.canvasData, cards },
    }))
  },
  updateConnections(connections: CanvasData['connections']) {
    canvasStore.setState(s => ({
      canvasData: { ...s.canvasData, connections },
    }))
  },
  updateViewport(viewport: CanvasData['viewport']) {
    canvasStore.setState(s => ({
      canvasData: { ...s.canvasData, viewport },
    }))
  },
}

// ============================================
// Panel Store
// ============================================

interface PanelStoreState {
  rightCollapsed: boolean
  leftCollapsed: boolean
}

export const panelStore = createStore<PanelStoreState>({
  rightCollapsed: true,
  leftCollapsed: true,
})

export const panelActions = {
  toggleRight() {
    panelStore.setState(s => ({ rightCollapsed: !s.rightCollapsed }))
  },
  setRight(collapsed: boolean) {
    panelStore.setState({ rightCollapsed: collapsed })
  },
  toggleLeft() {
    panelStore.setState(s => ({ leftCollapsed: !s.leftCollapsed }))
  },
  setLeft(collapsed: boolean) {
    panelStore.setState({ leftCollapsed: collapsed })
  },
}

// ============================================
// Selection Store
// ============================================

interface SelectionStoreState {
  selectedCardIds: string[]
  lassoActive: boolean
  lassoRect: { x: number; y: number; w: number; h: number } | null
}

export const selectionStore = createStore<SelectionStoreState>({
  selectedCardIds: [],
  lassoActive: false,
  lassoRect: null,
})

export const selectionActions = {
  setSelected(ids: string[]) {
    selectionStore.setState({ selectedCardIds: ids })
  },
  toggleCard(id: string) {
    selectionStore.setState(s => {
      const has = s.selectedCardIds.includes(id)
      return {
        selectedCardIds: has
          ? s.selectedCardIds.filter(c => c !== id)
          : [...s.selectedCardIds, id],
      }
    })
  },
  clearSelection() {
    selectionStore.setState({ selectedCardIds: [], lassoActive: false, lassoRect: null })
  },
  setLasso(active: boolean, rect: SelectionStoreState['lassoRect'] = null) {
    selectionStore.setState({ lassoActive: active, lassoRect: rect })
  },
}

interface ParentCardStoreState {
  cards: ParentCard[]
  activeParentCardId: string | null
}

const PARENT_CARDS_STORAGE_KEY = 'wbai_parent_cards'

function loadParentCards(): ParentCard[] {
  if (typeof localStorage === 'undefined') return []
  try {
    const stored = JSON.parse(localStorage.getItem(PARENT_CARDS_STORAGE_KEY) || '[]') as ParentCard[]
    const registry = JSON.parse(localStorage.getItem('wbai_canvases') || '{}') as { activeCanvasId?: string }
    const legacyCanvasId = typeof registry.activeCanvasId === 'string' ? registry.activeCanvasId : ''
    return Array.isArray(stored) ? stored.map(card => ({
      ...card,
      canvasId: typeof card.canvasId === 'string' ? card.canvasId : legacyCanvasId,
      status: card.status === 'draft' ? 'draft' : 'saved',
      sourceChildIds: Array.isArray(card.sourceChildIds) ? card.sourceChildIds : [],
      extractedRanges: Array.isArray(card.extractedRanges) ? card.extractedRanges : [],
      x: Number.isFinite(card.x) ? card.x : 80,
      y: Number.isFinite(card.y) ? card.y : 140,
      width: Number.isFinite(card.width) ? card.width : 420,
      height: Number.isFinite(card.height) ? card.height : 300,
    })) : []
  } catch {
    return []
  }
}

function persistParentCards(cards: ParentCard[]): void {
  if (typeof localStorage === 'undefined') return
  try {
    localStorage.setItem(PARENT_CARDS_STORAGE_KEY, JSON.stringify(cards))
  } catch {
    // 当前会话中的 store 继续作为母卡工作副本。
  }
}

function updateParentCards(
  updater: (state: ParentCardStoreState) => ParentCardStoreState,
): void {
  parentCardStore.setState(state => {
    const next = updater(state)
    persistParentCards(next.cards)
    return next
  })
}

export const parentCardStore = createStore<ParentCardStoreState>({
  cards: loadParentCards(),
  activeParentCardId: null,
})

export const parentCardActions = {
  add(card: ParentCard) {
    updateParentCards(state => ({ cards: [...state.cards, card], activeParentCardId: card.id }))
  },
  open(id: string) {
    parentCardStore.setState({ activeParentCardId: id })
  },
  close() {
    // 关闭编辑器只收起界面；草稿已经持续写入 localStorage，必须显式删除才移除。
    parentCardStore.setState({ activeParentCardId: null })
  },
  updateContent(id: string, content: string) {
    updateParentCards(state => ({
      cards: state.cards.map(card => card.id === id ? { ...card, content } : card),
      activeParentCardId: state.activeParentCardId,
    }))
  },
  addExtraction(id: string, startLine: number, endLine: number, childCardId: string) {
    updateParentCards(state => ({
      cards: state.cards.map(card => card.id === id
        ? { ...card, extractedRanges: [...card.extractedRanges, { startLine, endLine, childCardId }] }
        : card),
      activeParentCardId: state.activeParentCardId,
    }))
  },
  markSaved(id: string) {
    updateParentCards(state => ({
      cards: state.cards.map(card => card.id === id
        ? { ...card, status: 'saved', savedAt: new Date().toISOString() }
        : card),
      activeParentCardId: state.activeParentCardId,
    }))
  },
  remove(id: string) {
    updateParentCards(state => ({
      cards: state.cards.filter(card => card.id !== id),
      activeParentCardId: state.activeParentCardId === id ? null : state.activeParentCardId,
    }))
  },
}
