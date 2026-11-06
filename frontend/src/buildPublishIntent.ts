import { authUser } from './auth'

export interface BuildPublishTarget { environment: 'dev' | 'test' | 'prod'; serverIds: number[] }
export interface BuildAutoPublish { mode: 'AUTO'; environment: 'dev' | 'test'; serverIds: number[]; status: 'WAITING_BUILD' | 'READY' | 'QUEUED' | 'LINKED_EXISTING' | 'BLOCKED' | 'CANCELLED'; deploymentTaskId?: number | null; message?: string }
export interface BuildPublishIntent { version: 2; createdAt: number; mode: 'CONFIRM' | 'BUILD_ONLY'; target?: BuildPublishTarget }
const prefix = 'release:build-publish:'
const memory = new Map<string, BuildPublishIntent>()
const lifetime = 24 * 60 * 60 * 1000
function key(taskId: string | number) {
  const username = authUser.value?.username
  return username && /^\d+$/.test(String(taskId)) ? `${prefix}${encodeURIComponent(username)}:${taskId}` : ''
}
function validTarget(target: unknown): target is BuildPublishTarget {
  if (!target || typeof target !== 'object') return false
  const value = target as BuildPublishTarget
  return ['dev', 'test', 'prod'].includes(value.environment) && Array.isArray(value.serverIds) && value.serverIds.length > 0 && value.serverIds.every(id => Number.isSafeInteger(id) && id > 0) && new Set(value.serverIds).size === value.serverIds.length
}
function parse(value: unknown): BuildPublishIntent | undefined {
  if (typeof value === 'string') { try { value = JSON.parse(value) } catch { return undefined } }
  if (typeof value === 'number') value = { version: 2, createdAt: value, mode: 'CONFIRM' }
  if (!value || typeof value !== 'object') return undefined
  const item = value as BuildPublishIntent
  if (item.version !== 2 || !['CONFIRM', 'BUILD_ONLY'].includes(item.mode) || !Number.isFinite(item.createdAt) || item.createdAt <= 0 || Date.now() < item.createdAt || Date.now() - item.createdAt >= lifetime || item.target !== undefined && (!validTarget(item.target) || item.mode !== 'CONFIRM')) return undefined
  return { version: 2, createdAt: item.createdAt, mode: item.mode, ...(item.target ? { target: { environment: item.target.environment, serverIds: [...item.target.serverIds] } } : {}) }
}
function read(taskId: string | number) {
  const name = key(taskId)
  if (!name) return undefined
  try { const stored = sessionStorage.getItem(name); if (stored !== null) return parse(stored) } catch { /* This tab can retain its choice in memory. */ }
  return parse(memory.get(name))
}
function save(taskId: string | number, record: BuildPublishIntent) {
  const name = key(taskId)
  if (!name) return
  memory.set(name, record)
  try { sessionStorage.setItem(name, JSON.stringify(record)) } catch { /* No background or cross-tab delivery is implied. */ }
}
// null records an explicit build-only choice; undefined preserves the old generic post-build offer.
export function rememberBuildPublish(taskId: string | number, target?: BuildPublishTarget | null) {
  if (target != null && !validTarget(target)) { forgetBuildPublish(taskId); return }
  save(taskId, { version: 2, createdAt: Date.now(), mode: target === null ? 'BUILD_ONLY' : 'CONFIRM', ...(target ? { target: { environment: target.environment, serverIds: [...target.serverIds] } } : {}) })
}
export function renewBuildPublish(taskId: string | number) {
  const record = read(taskId)
  if (record) save(taskId, { ...record, createdAt: Date.now() })
  else forgetBuildPublish(taskId)
}
export function forgetBuildPublish(taskId: string | number) {
  const name = key(taskId)
  if (!name) return
  memory.delete(name)
  try { sessionStorage.removeItem(name) } catch { /* Storage can be disabled. */ }
}
export function consumeBuildPublishIntent(taskId: string | number) {
  const record = read(taskId)
  forgetBuildPublish(taskId)
  return record
}
export function consumeBuildPublish(taskId: string | number) { return consumeBuildPublishIntent(taskId)?.mode === 'CONFIRM' }
