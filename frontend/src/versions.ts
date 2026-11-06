import { tUi } from './i18n'
import type { ServiceType } from './types'
import { hasMenu } from './auth'

export interface VersionCandidate {
  displayIcon?: string
  serviceId: number
  serviceName: string
  serviceKey: string
  serviceType: ServiceType
  enabled: boolean
  hasRepository: boolean
}
export interface VersionItem {
  displayIcon?: string
  id?: number
  serviceId: number
  serviceName: string
  serviceKey: string
  serviceType: ServiceType
  branch: string
  baselineSha?: string
  baselineShortSha?: string
  baselineMessage?: string
  status: string
  errorCode?: string
  message?: string
  createdAt?: string
  updatedAt?: string
}
export interface ReleaseVersion {
  id: number
  versionDate: string
  branch: string
  createdBy: string
  createdAt: string
  status: string
  totalCount: number
  successCount: number
  failedCount: number
  runningCount: number
  canAppend: boolean
  lifecycleState?: 'OPEN' | 'COMPLETED'
  items: VersionItem[]
}
export interface VersionPreview {
  previewId: string
  versionId?: number
  versionDate: string
  branch: string
  expiresAt: string
  items: VersionItem[]
  canConfirm: boolean
}
export interface VersionLinkPreview {
  previewId: string
  versionId: number
  branch: string
  baselineSha: string
  baselineMessage?: string
  headSha: string
  expiresAt: string
  services: Pick<VersionItem, 'serviceId' | 'serviceName' | 'serviceKey' | 'serviceType'>[]
}

export function versionStatusLabel(status: string) {
  return ({ PENDING: tUi('等待创建'), RUNNING: tUi('创建中'), SUCCESS: tUi('已就绪'), FAILED: tUi('创建失败'), CONFLICT: tUi('分支冲突'), UNKNOWN: tUi('待核对'), READY: tUi('可以创建'), ALREADY_CREATED: tUi('已就绪，保留原分支'), ERROR: tUi('无法创建'), PARTIAL: tUi('部分失败'), PARTIAL_SUCCESS: tUi('部分失败'), EMPTY: tUi('暂无模块') } as Record<string, string>)[status] || status || tUi('待核对')
}
export function versionStatusType(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (['SUCCESS', 'ALREADY_CREATED', 'READY'].includes(status)) return 'success'
  if (['FAILED', 'CONFLICT', 'ERROR'].includes(status)) return 'danger'
  if (['UNKNOWN', 'PARTIAL', 'PARTIAL_SUCCESS'].includes(status)) return 'warning'
  return ['RUNNING', 'PENDING'].includes(status) ? 'primary' : 'info'
}
export function versionBusy(version?: ReleaseVersion | null) {
  return Boolean(version?.items.some(item => ['PENDING', 'RUNNING'].includes(item.status)))
}
export function versionSummary(version: ReleaseVersion) {
  if (!version.items.length) return tUi('没有可查看的模块')
  const done = version.items.filter(item => item.status === 'SUCCESS').length
  const failed = version.items.filter(item => ['FAILED', 'CONFLICT', 'UNKNOWN'].includes(item.status)).length
  if (versionBusy(version)) return tUi('{0} / {1} 个模块已就绪', done, version.items.length)
  return failed ? tUi('{0} 个已就绪，{1} 个需处理', done, failed) : tUi('{0} 个模块已就绪', done)
}
export function versionBuildRoute(item: VersionItem) {
  if (item.status !== 'SUCCESS') return undefined
  if (item.serviceType === 'FLUTTER_ANDROID') return hasMenu('apps') ? { path: `/apps/${item.serviceId}/build`, query: { branch: item.branch } } : undefined
  return hasMenu('builds') ? { path: '/build/new', query: { serviceId: String(item.serviceId), branch: item.branch } } : undefined
}
export function shanghaiToday() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
}
export function canAddVersionServices(version: ReleaseVersion) { return version.canAppend === true }
