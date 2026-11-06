import type { ServiceType } from './types'

export type CompletionState = 'OPEN' | 'COMPLETED'
export interface CompletionCounts { total: number; published: number; excluded: number; unconfirmed: number }
export interface CompletionItem {
  serviceId: number
  serviceName: string
  serviceKey: string
  serviceType: ServiceType
  branch: string
  outcome: 'PUBLISHED' | 'EXCLUDED' | 'UNCONFIRMED'
  message?: string
  reason?: string
  taskId?: number
  artifactId?: number
  commitId?: string
  taskStatus?: string
  finishedAt?: string
  serverIds?: number[]
  scopeComplete?: boolean
  detailPath?: string
}
export interface CompletionInput { environment: string; changes: string; remaining: string; exclusions: { serviceId: number; reason: string }[] }
export interface CompletionOptions { versionId: number; versionDate: string; branch: string; environment: string; items: CompletionItem[]; counts: CompletionCounts }
export interface CompletionPreview extends CompletionOptions { fingerprint: string; summary: string }
export interface PublicVersionSummary {
  id: number
  versionId: number
  versionDate: string
  state: CompletionState
  environment: string
  changes: string
  remaining: string
  summary: string
  actor: string
  createdAt: string
  completedBy: string
  completedAt: string
  counts: CompletionCounts
}
export interface VersionCompletion extends Omit<PublicVersionSummary, 'state'> {
  eventType: 'COMPLETE' | 'EDIT' | 'REOPEN'
  reason?: string
  items: CompletionItem[]
}
export interface CompletionDetail { versionId: number; state: CompletionState; current: VersionCompletion | null; history: VersionCompletion[] }

export function completionOutcomeLabel(item: CompletionItem) {
  if (item.outcome === 'PUBLISHED') return '已发布'
  if (item.outcome === 'EXCLUDED') return '本次不发布'
  if (item.taskStatus === 'FAILED') return '发布失败 · 待确认'
  if (item.taskStatus === 'RUNNING' || item.taskStatus === 'PENDING') return '发布处理中 · 待确认'
  return '待确认'
}
export function completionOutcomeType(item: CompletionItem): 'success' | 'info' | 'danger' | 'warning' {
  return item.outcome === 'PUBLISHED' ? 'success' : item.outcome === 'EXCLUDED' ? 'info' : item.taskStatus === 'FAILED' ? 'danger' : 'warning'
}
export function completionInputKey(input: CompletionInput) {
  return JSON.stringify({ ...input, exclusions: [...input.exclusions].sort((a, b) => a.serviceId - b.serviceId) })
}
