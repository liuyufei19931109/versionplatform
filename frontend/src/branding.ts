import { tUi } from './i18n'
import { ref } from 'vue'

const logoChangedKey = 'release_branding_updated'
const maxUploadBytes = 256 * 1024
const maxStoredBytes = 10 * 1024 * 1024
const maxSide = 1024
const pngSignature = [137, 80, 78, 71, 13, 10, 26, 10]
export const platformLogo = ref('')
export const logoUnavailable = ref(false)
let requestId = 0

export function previewPng(file: Blob): Promise<string> {
  return decodePng(file, maxUploadBytes)
}

async function decodePng(file: Blob, maximumBytes: number): Promise<string> {
  if (!file.size || file.size > maximumBytes) throw new Error(tUi('PNG 文件须大于 0 字节，且不能超过 ') + maximumBytes / 1024 + ' KiB')
  const header = new Uint8Array(await file.slice(0, 24).arrayBuffer())
  if (header.length < 24 || !pngSignature.every((value, i) => header[i] === value)
      || String.fromCharCode(...header.slice(12, 16)) !== 'IHDR') throw new Error(tUi('请选择有效的 PNG 图片'))
  const view = new DataView(header.buffer)
  const width = view.getUint32(16), height = view.getUint32(20)
  if (!width || !height || width > maxSide || height > maxSide) throw new Error(tUi('图片宽度和高度均须在 1 到 1024 像素之间'))
  const url = URL.createObjectURL(new Blob([file], { type: 'image/png' }))
  const image = new Image()
  image.src = url
  try {
    await image.decode()
    return url
  } catch {
    URL.revokeObjectURL(url)
    throw new Error(tUi('无法读取这张 PNG 图片'))
  }
}

function showLogo(url: string) {
  const previous = platformLogo.value
  platformLogo.value = url
  const favicon = document.querySelector<HTMLLinkElement>('#platform-favicon')
  if (favicon) favicon.href = url || 'data:,'
  if (previous) URL.revokeObjectURL(previous)
}

export async function refreshLogo() {
  const revision = ++requestId
  let url = ''
  let failed = false
  try {
    const response = await fetch('/api/branding/logo', {
      cache: 'no-store', credentials: 'omit', mode: 'same-origin', redirect: 'error',
    })
    if (response.status !== 204) {
      if (response.status !== 200 || response.headers.get('content-type')?.split(';')[0].trim() !== 'image/png') {
        throw new Error(tUi('无法读取平台 Logo'))
      }
      url = await decodePng(await response.blob(), maxStoredBytes)
    }
  } catch { failed = true }
  if (revision !== requestId) {
    if (url) URL.revokeObjectURL(url)
    return
  }
  logoUnavailable.value = failed
  showLogo(url)
}

export function notifyLogoChange() {
  // Other tabs only receive an invalidation signal. The server is the source of truth.
  try { localStorage.setItem(logoChangedKey, crypto.randomUUID?.() || String(Date.now())) } catch { /* Storage may be unavailable. */ }
  return refreshLogo()
}

export function listenForLogoChanges() {
  const visible = () => { if (document.visibilityState === 'visible') void refreshLogo() }
  const changed = (event: StorageEvent) => { if (event.key === logoChangedKey) void refreshLogo() }
  const restored = (event: PageTransitionEvent) => { if (event.persisted) void refreshLogo() }
  document.addEventListener('visibilitychange', visible)
  window.addEventListener('storage', changed)
  window.addEventListener('pageshow', restored)
  void refreshLogo()
  return () => {
    document.removeEventListener('visibilitychange', visible)
    window.removeEventListener('storage', changed)
    window.removeEventListener('pageshow', restored)
    ++requestId
    showLogo('')
  }
}
