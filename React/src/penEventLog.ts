const MAX_PEN_LOG_LINES = 1200
const penLogLines: string[] = []
let lastMoveSignature = ''
let lastMoveAt = 0

function append(entry: Record<string, unknown>): void {
  const line = JSON.stringify({ time: new Date().toISOString(), ...entry })
  penLogLines.push(line)
  if (penLogLines.length > MAX_PEN_LOG_LINES) penLogLines.splice(0, penLogLines.length - MAX_PEN_LOG_LINES)
  console.info('[WebPenLog]', line)
}

export function recordPenDiagnostic(label: string, details: Record<string, unknown> = {}): void {
  append({ source: 'app', label, ...details })
}

export function recordPenDecision(
  label: string,
  event: React.PointerEvent,
  details: Record<string, unknown> = {},
): void {
  append({
    source: 'whiteboard',
    label,
    eventType: event.type,
    pointerType: event.pointerType,
    pointerId: event.pointerId,
    button: event.button,
    buttons: event.buttons,
    pressure: event.pressure,
    isPrimary: event.isPrimary,
    clientX: event.clientX,
    clientY: event.clientY,
    ...details,
  })
}

function recordRawEvent(event: Event): void {
  if (event instanceof PointerEvent) {
    const relevant = event.pointerType === 'pen' || event.button === 2 || event.button === 5 || (event.buttons & 34) !== 0
    if (!relevant) return
    const signature = `${event.type}:${event.pointerType}:${event.pointerId}:${event.button}:${event.buttons}:${event.pressure}`
    const now = performance.now()
    if ((event.type === 'pointermove' || event.type === 'pointerrawupdate') && signature === lastMoveSignature && now - lastMoveAt < 80) return
    lastMoveSignature = signature
    lastMoveAt = now
    append({
      source: 'browser',
      eventType: event.type,
      pointerType: event.pointerType,
      pointerId: event.pointerId,
      button: event.button,
      buttons: event.buttons,
      pressure: event.pressure,
      tangentialPressure: event.tangentialPressure,
      tiltX: event.tiltX,
      tiltY: event.tiltY,
      twist: event.twist,
      width: event.width,
      height: event.height,
      isPrimary: event.isPrimary,
      clientX: event.clientX,
      clientY: event.clientY,
      defaultPrevented: event.defaultPrevented,
      target: event.target instanceof Element ? `${event.target.tagName}.${event.target.className}` : String(event.target),
    })
    return
  }
  if (event instanceof MouseEvent && (event.button === 2 || event.buttons === 2 || event.type === 'contextmenu' || event.type === 'auxclick')) {
    append({
      source: 'browser',
      eventType: event.type,
      button: event.button,
      buttons: event.buttons,
      clientX: event.clientX,
      clientY: event.clientY,
      defaultPrevented: event.defaultPrevented,
      target: event.target instanceof Element ? `${event.target.tagName}.${event.target.className}` : String(event.target),
    })
  }
}

export function installPenEventLogging(): () => void {
  const eventTypes = ['pointerdown', 'pointermove', 'pointerrawupdate', 'pointerup', 'pointercancel', 'mousedown', 'mouseup', 'auxclick', 'contextmenu']
  for (const type of eventTypes) document.addEventListener(type, recordRawEvent, true)
  recordPenDiagnostic('logger-installed', { userAgent: navigator.userAgent, devicePixelRatio: window.devicePixelRatio })
  return () => {
    for (const type of eventTypes) document.removeEventListener(type, recordRawEvent, true)
  }
}

export function downloadPenEventLog(): void {
  recordPenDiagnostic('log-export', { lines: penLogLines.length })
  const blob = new Blob([penLogLines.join('\n') + '\n'], { type: 'application/x-ndjson;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `whiteboard-pen-${new Date().toISOString().replace(/[:.]/g, '-')}.log`
  anchor.click()
  URL.revokeObjectURL(url)
}
