export function environmentLabel(value?: unknown) { return ({ dev: '开发', test: '预生产', prod: '生产' } as Record<string, string>)[String(value)] || String(value || '—') }
export function statusLabel(value?: unknown) { return ({ SUCCESS: '成功', RUNNING: '执行中', FAILED: '失败', PENDING: '排队中', WAITING: '待执行', WAITING_CONFIRMATION: '待确认发布', CANCELLED: '已取消', ROLLED_BACK: '已回滚' } as Record<string, string>)[String(value).toUpperCase()] || '待读取' }
export function statusType(value?: unknown): 'success' | 'danger' | 'primary' | 'info' { const status = String(value).toUpperCase(); return status === 'SUCCESS' ? 'success' : status === 'FAILED' ? 'danger' : status === 'RUNNING' ? 'primary' : 'info' }
export function formatTime(value?: unknown) { if (!value) return '—'; const date = new Date(String(value)); return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-') }
export function shortCommit(value?: unknown) { return String(value || '').trim().slice(0, 8) || '—' }
export function timeParts(value?: unknown) {
  if (!value) return { date: '—', time: '', full: '未记录时间' }
  const date = new Date(String(value))
  if (Number.isNaN(date.getTime())) return { date: String(value), time: '', full: String(value) }
  const pad = (part: number) => String(part).padStart(2, '0')
  const day = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`
  return { date: day, time, full: `${day} ${time}:${pad(date.getSeconds())}` }
}
export function completionTime(task: { finishedAt?: string; startedAt?: string; createdAt?: string }) {
  if (task.finishedAt) return { value: task.finishedAt, label: '发布完成' }
  if (task.startedAt) return { value: task.startedAt, label: '开始于' }
  return { value: task.createdAt, label: '创建于' }
}
// Match ReleaseHistoryService.activityTime, including legacy records and pending tasks.
export function activityTime(task: { status?: string; finishedAt?: string; startedAt?: string; createdAt?: string; updatedAt?: string }) {
  if (task.finishedAt && !['PENDING', 'RUNNING'].includes(task.status || '')) return { value: task.finishedAt, label: '完成' }
  const values = [{ value: task.createdAt, label: '创建' }, { value: task.startedAt, label: '开始' }, { value: task.updatedAt, label: '更新' }]
  return values.reduce((latest, item) => item.value && (!latest.value || item.value > latest.value) ? item : latest, values[0]!)
}
export function newRequestId() { if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID(); const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16)); bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128; const hex = Array.from(bytes, x => x.toString(16).padStart(2, '0')).join(''); return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}` }
export function windowsCommand(value?: string) { return /(?:\b(?:cmd(?:\.exe)?|mvn\.cmd|gradlew\.bat)\b|[a-z]:[\\/])/i.test(value || '') }
