import { tUi } from './i18n'
export type CodeBuildStatus = 'UNKNOWN' | 'PENDING_BUILD' | 'BUILDING' | 'BUILT' | 'BUILD_FAILED'
export type CodeUpdateStatus = CodeBuildStatus | 'NO_UPDATES' | 'BRANCH_DELETED' | 'SERVICE_CHANGED' | 'SYNC_ERROR'
export interface CodeEnvironmentUpdate { environmentName: string; status: CodeBuildStatus; buildTaskId?: number; artifactId?: number }
export interface VersionCodeUpdate {
  serviceId: number; branch: string; status: CodeUpdateStatus; headSha?: string; title?: string; actor?: string
  buildStatus?: CodeBuildStatus; baselineSha?: string; buildTaskId?: number; artifactId?: number
  updatedAt?: string; checkedAt?: string; eventType?: string; eventUrl?: string; message?: string
  environments?: CodeEnvironmentUpdate[]
}
export interface VersionCodeUpdates { enabled: boolean; items: VersionCodeUpdate[] }

const labels: Record<CodeUpdateStatus, string> = {
  get UNKNOWN() { return tUi('尚未核对') }, get NO_UPDATES() { return tUi('暂无新提交') }, get PENDING_BUILD() { return tUi('有新提交，待打包') }, get BUILDING() { return tUi('正在打包') }, get BUILT() { return tUi('最新提交已打包') }, get BUILD_FAILED() { return tUi('最新构建失败') },
  get BRANCH_DELETED() { return tUi('分支已删除') }, get SERVICE_CHANGED() { return tUi('仓库配置已变更') }, get SYNC_ERROR() { return tUi('代码核对失败') },
}
export function codeStatus(value: unknown): CodeUpdateStatus { return typeof value === 'string' && Object.prototype.hasOwnProperty.call(labels, value) ? value as CodeUpdateStatus : 'UNKNOWN' }
export function codeStatusLabel(value: unknown) { return labels[codeStatus(value)] }
export function codeBuildStatus(value: unknown): CodeBuildStatus { return typeof value === 'string' && ['PENDING_BUILD', 'BUILDING', 'BUILT', 'BUILD_FAILED'].includes(value) ? value as CodeBuildStatus : 'UNKNOWN' }
export function codeBuildStatusLabel(value: unknown) { return codeBuildStatus(value) === 'PENDING_BUILD' ? tUi('尚未打包') : codeStatusLabel(codeBuildStatus(value)) }
export function codeStatusType(value: unknown): 'info' | 'warning' | 'primary' | 'success' | 'danger' {
  return ({ UNKNOWN: 'info', NO_UPDATES: 'info', PENDING_BUILD: 'warning', BUILDING: 'primary', BUILT: 'success', BUILD_FAILED: 'danger', BRANCH_DELETED: 'danger', SERVICE_CHANGED: 'warning', SYNC_ERROR: 'warning' } as const)[codeStatus(value)]
}
export function codeEventLabel(value?: string) { return value === 'MERGED' ? tUi('合并更新') : value === 'PUSH' ? tUi('代码提交') : value === 'SYNC' ? tUi('分支同步') : tUi('代码更新') }
export function codeBuildBlocked(value?: VersionCodeUpdate) { return value?.status === 'BRANCH_DELETED' || value?.status === 'SERVICE_CHANGED' }

// Only link to a GitHub PR or commit page. Never navigate to an arbitrary webhook-supplied URL.
export function githubEventUrl(value?: string): string | undefined {
  if (!value) return undefined
  try {
    const url = new URL(value)
    if (url.protocol !== 'https:' || url.hostname !== 'github.com' || url.port || url.username || url.password || url.search || url.hash) return undefined
    const match = /^\/([A-Za-z0-9][A-Za-z0-9-]*)\/([A-Za-z0-9_.-]+)\/(pull\/[1-9]\d*|commit\/[0-9a-fA-F]{40})$/.exec(url.pathname)
    if (!match || match[2] === '.' || match[2] === '..') return undefined
    return `https://github.com/${match[1]}/${match[2]}/${match[3]}`
  } catch { return undefined }
}

export function normalizeCodeUpdates(value: unknown): VersionCodeUpdates {
  if (!value || typeof value !== 'object' || typeof (value as VersionCodeUpdates).enabled !== 'boolean' || !Array.isArray((value as VersionCodeUpdates).items)) throw new Error(tUi('代码动态返回格式不完整，请稍后重新读取'))
  const source = value as VersionCodeUpdates
  if (!source.enabled) return { enabled: false, items: [] }
  const text = (v: unknown, size = 500) => typeof v === 'string' ? v.slice(0, size) : undefined
  const id = (v: unknown) => typeof v === 'number' && Number.isSafeInteger(v) && v > 0 ? v : undefined
  const sha = (v: unknown) => typeof v === 'string' && /^[0-9a-fA-F]{7,64}$/.test(v) ? v : undefined
  return { enabled: true, items: source.items.filter(item => item && Number.isSafeInteger(item.serviceId) && item.serviceId > 0 && typeof item.branch === 'string').map(item => ({
    // Legacy PENDING_BUILD only establishes that no artifact exists, not that the watched branch changed.
    serviceId: item.serviceId, branch: item.branch, status: item.status === 'PENDING_BUILD' && item.buildStatus == null ? 'UNKNOWN' : codeStatus(item.status),
    buildStatus: codeBuildStatus(item.buildStatus ?? item.status), baselineSha: sha(item.baselineSha), headSha: sha(item.headSha),
    buildTaskId: id(item.buildTaskId), artifactId: id(item.artifactId),
    title: text(item.title), actor: text(item.actor, 100), updatedAt: text(item.updatedAt, 50), checkedAt: text(item.checkedAt, 50),
    eventType: text(item.eventType, 30), eventUrl: githubEventUrl(item.eventUrl), message: text(item.message),
    environments: Array.isArray(item.environments) ? item.environments.filter(env => env && ['dev', 'test', 'prod'].includes(env.environmentName)).map(env => ({ environmentName: env.environmentName, status: codeBuildStatus(env.status), buildTaskId: id(env.buildTaskId), artifactId: id(env.artifactId) })) : [],
  })) }
}
