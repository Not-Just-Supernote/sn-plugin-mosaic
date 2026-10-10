// App.tsx — 布局壳（左: 卡片盒、中: 白板、右: 卡片创建）
import React, { useEffect } from 'react'
import { useStore, panelStore, panelActions } from './store'
import Whiteboard from './Whiteboard'
import CardCreation from './CardCreation'
import CardBoxPanel from './CardBoxPanel'
import { installPenEventLogging } from './penEventLog'

const App: React.FC = () => {
  const { rightCollapsed, leftCollapsed } = useStore(panelStore)

  useEffect(() => {
    const uninstallPenLogger = installPenEventLogging()
    const blockContextMenu = (event: MouseEvent) => event.preventDefault()
    const blockBrowserZoomWheel = (event: WheelEvent) => {
      if (event.ctrlKey || event.metaKey) event.preventDefault()
    }
    const blockBrowserZoomKeys = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && ['+', '-', '=', '0'].includes(event.key)) event.preventDefault()
    }
    document.addEventListener('contextmenu', blockContextMenu)
    document.addEventListener('wheel', blockBrowserZoomWheel, { passive: false })
    document.addEventListener('keydown', blockBrowserZoomKeys)
    return () => {
      uninstallPenLogger()
      document.removeEventListener('contextmenu', blockContextMenu)
      document.removeEventListener('wheel', blockBrowserZoomWheel)
      document.removeEventListener('keydown', blockBrowserZoomKeys)
    }
  }, [])

  return (
    <div className="app-container">
      {/* Center: Whiteboard (always full-screen, panels float above) */}
      <Whiteboard />

      {/* Left floating panel: Card Box */}
      <div className={`floating-panel left ${leftCollapsed ? 'collapsed' : ''}`}>
        <div className="panel-header">
          <span className="panel-header-title">CARD BOX</span>
          <button className="panel-header-close" onClick={panelActions.toggleLeft}>✕</button>
        </div>
        <div className="creation-content">
          <CardBoxPanel />
        </div>
      </div>

      {/* Right floating panel: Card Creation */}
      <div className={`floating-panel right ${rightCollapsed ? 'collapsed' : ''}`}>
        <CardCreation />
      </div>

      {/* Panel toggle buttons */}
      <button
        className={`panel-toggle left ${leftCollapsed ? '' : 'open'}`}
        onClick={panelActions.toggleLeft}
        title="Card Box"
      >
        {leftCollapsed ? '▸' : '◂'}
      </button>
      <button
        className={`panel-toggle right ${rightCollapsed ? '' : 'open'}`}
        onClick={panelActions.toggleRight}
        title="Card Creation"
      >
        {rightCollapsed ? '◂' : '▸'}
      </button>
    </div>
  )
}

export default App
