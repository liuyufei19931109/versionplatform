// Share the existing wrap preference between task output and service logs.
export function readLogWrap(): boolean {
  try { return localStorage.getItem('containerLogs.wrap') !== 'false' }
  catch { return true }
}

export function saveLogWrap(enabled: boolean) {
  try { localStorage.setItem('containerLogs.wrap', String(enabled)) }
  catch { /* Storage may be disabled by the browser. */ }
}

export async function copyLogText(text: string) {
  if (navigator.clipboard?.writeText) {
    try { await navigator.clipboard.writeText(text); return }
    catch { /* Restricted Clipboard API implementations can use selection copy. */ }
  }
  const previous = document.activeElement as HTMLElement | null
  const area = document.createElement('textarea')
  area.value = text
  area.readOnly = true
  area.style.cssText = 'position:fixed;left:-9999px;top:0'
  document.body.appendChild(area)
  try {
    area.select()
    if (!document.execCommand('copy')) throw new Error('Clipboard unavailable')
  } finally {
    area.remove()
    previous?.focus({ preventScroll: true })
  }
}
