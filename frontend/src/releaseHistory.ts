import { tUi } from './i18n'
import type { Release } from './types'

export type ReleaseEnvironment = 'dev' | 'test' | 'prod'
export const historyEnvironmentKey = 'release-platform-history-environment'
export interface HistoryEntry {
  task: Release
  artifactVersion: string | null
  buildTaskId: number | string | null
  imageReference: string | null
  targetNodeCount: number
  targetScopeKnown: boolean
}
export interface HistoryService {
  serviceId: number | string
  serviceName: string
  serviceKey: string
  serviceType?: 'BACKEND' | 'FRONTEND' | 'FLUTTER_ANDROID'
  configured: boolean
  enabled: boolean
  nodeCount: number
  latestSuccessful: HistoryEntry | null
  latestAttempt: HistoryEntry | null
}
export interface ReleaseHistory {
  environment: ReleaseEnvironment
  services: HistoryService[]
  records: HistoryEntry[]
}
export interface HistoryFilters { serviceId: string; artifactId: string; status: string; keyword: string }
export function validEnvironment(value: unknown): ReleaseEnvironment | undefined {
  return value === 'dev' || value === 'test' || value === 'prod' ? value : undefined
}
export function validServiceId(value: unknown): string {
  return typeof value === 'string' && /^[1-9]\d*$/.test(value) ? value : ''
}
export function initialEnvironment(query: unknown, storage?: Pick<Storage, 'getItem'>): ReleaseEnvironment {
  const requested = validEnvironment(query)
  if (requested) return requested
  try { return validEnvironment(storage?.getItem(historyEnvironmentKey)) || 'test' } catch { return 'test' }
}
export function rememberEnvironment(value: ReleaseEnvironment, storage?: Pick<Storage, 'setItem'>) {
  try { storage?.setItem(historyEnvironmentKey, value) } catch { /* Private browsing may disallow storage. */ }
}
export function filterHistory(records: HistoryEntry[], environment: ReleaseEnvironment, filters: HistoryFilters) {
  const search = filters.keyword.trim().toLowerCase()
  return records.filter(({ task, artifactVersion, buildTaskId, imageReference }) =>
    task.environment === environment &&
    (!filters.serviceId || String(task.serviceId) === filters.serviceId) &&
    (!filters.artifactId || String(task.artifactId ?? '') === filters.artifactId) &&
    (!filters.status || task.status === filters.status) &&
    (!search || [task.id, task.serviceName, task.serviceKey, task.branch, task.commitId, artifactVersion, buildTaskId, imageReference]
      .some(value => String(value ?? '').toLowerCase().includes(search))),
  )
}
export function historyVersions(records: HistoryEntry[], environment: ReleaseEnvironment, serviceId: string) {
  if (!serviceId) return []
  const unique = new Map<string, { id: string; label: string; commitId: string; buildTaskId: number | string | null }>()
  for (const entry of records) {
    if (entry.task.environment !== environment || String(entry.task.serviceId) !== serviceId || entry.task.artifactId == null) continue
    const id = String(entry.task.artifactId)
    if (!unique.has(id)) unique.set(id, { id, label: entry.artifactVersion || tUi('制品 #{0}', id), commitId: entry.task.commitId || '', buildTaskId: entry.buildTaskId })
  }
  return [...unique.values()]
}
export function clampHistoryPage(page: number, recordCount: number, pageSize = 12) {
  return Math.min(Math.max(1, Number.isFinite(page) ? Math.floor(page) : 1), Math.max(1, Math.ceil(recordCount / pageSize)))
}
