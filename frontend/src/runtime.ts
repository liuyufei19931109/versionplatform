import { tUi } from './i18n'
export interface RuntimeContainer {
  id: string
  name: string
  state: string
  health: string
  imageReference: string
  imageId: string
  repoDigests: string[]
  ports: { hostIp: string; hostPort: string | number; containerPort: string | number; protocol: string }[]
  memoryLimitBytes: number | null
  memoryUsageBytes: number | null
  javaOptions: string[]
  javaOptionsSource: string
}

export interface RuntimeRow {
  key: string
  environmentId: number
  serviceId: number
  serviceName: string
  serviceKey: string
  serviceType: 'FRONTEND' | 'BACKEND'
  environmentName: string
  serverId: number | null
  serverName: string
  host: string
  enabled: boolean
  checkState: 'NOT_CONFIGURED' | 'DISABLED' | 'UNCHECKED' | 'CHECKING' | 'READY' | 'ERROR'
  checkedAt: string | null
  stale: boolean
  message: string
  expected: {
    taskId: number
    artifactId: number
    imageReference: string
    imageDigest: string | null
    commitId: string | null
    version: string | null
    deployedAt: string | null
  } | null
  comparison: 'UNKNOWN' | 'MATCH' | 'DIFFERENT'
  comparisonBasis?: 'DIGEST' | 'TAG' | null
  containers: RuntimeContainer[]
  accessUrls: string[]
}

export interface RuntimeSnapshot { rows: RuntimeRow[]; checking: boolean; serverTime: string }
export interface RuntimeRestartPreview {
  token: string; environmentId: number; serviceId: number; serviceName: string; serviceKey: string
  environmentName: string; serverId: number; serverName: string; host: string; expiresAt: string
  containers: { id: string; name: string; state: string; imageReference: string; imageId: string }[]
}
export interface RuntimeOperation {
  id: number; environmentId: number; serviceId: number; serverId: number; serviceName: string
  serviceKey: string; environmentName: string; serverName: string; host: string; createdBy: string
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'; currentStage: string; logText: string
  errorMessage: string | null; createdAt: string; startedAt: string | null; finishedAt: string | null
}
export function operationStage(stage: string) { return ({ WAITING: tUi('等待串行执行'), CHECK_TARGET: tUi('核对目标容器'), RESTART_CONTAINER: tUi('重启容器'), RELOAD_CONFIGURATION: tUi('加载配置并重建服务'), HEALTH_CHECK: tUi('等待健康检查'), COMPLETE: tUi('操作完成'), CANCELLED: tUi('已中止') } as Record<string,string>)[stage] || stage }
export function operationStatus(status: RuntimeOperation['status']) { return ({ PENDING: tUi('排队中'), RUNNING: tUi('执行中'), SUCCESS: tUi('成功'), FAILED: tUi('失败'), CANCELLED: tUi('已取消') })[status] }
export type RuntimeTone = 'success' | 'warning' | 'danger' | 'primary' | 'info'

export function runtimeStatus(row: RuntimeRow, stale = row.stale): { label: string; tone: RuntimeTone; detail: string } {
  if (row.checkState === 'NOT_CONFIGURED') return { label: tUi('未配置'), tone: 'info', detail: tUi('尚未绑定目标节点') }
  if (row.checkState === 'DISABLED') return { label: tUi('已停用'), tone: 'info', detail: tUi('服务、环境或节点未启用') }
  if (row.checkState === 'UNCHECKED') return { label: tUi('未检查'), tone: 'info', detail: tUi('点击检查获取运行状态') }
  if (row.checkState === 'CHECKING') return { label: tUi('检查中'), tone: 'primary', detail: tUi('正在依次读取目标节点') }
  if (row.checkState === 'ERROR') return { label: tUi('检查失败'), tone: 'danger', detail: tUi('展开详情查看原因') }
  if (stale) return { label: tUi('结果已过期'), tone: 'info', detail: tUi('请重新检查当前状态') }
  if (!row.containers.length) return { label: tUi('未发现容器'), tone: 'warning', detail: tUi('目标配置未找到对应容器') }
  if (row.containers.some(c => c.state !== 'running')) return { label: tUi('未全部运行'), tone: 'danger', detail: tUi('存在未运行或暂停的容器') }
  if (row.containers.some(c => c.health === 'unhealthy')) return { label: tUi('健康异常'), tone: 'danger', detail: tUi('Docker 健康检查未通过') }
  if (row.containers.some(c => c.health === 'starting')) return { label: tUi('启动检查中'), tone: 'warning', detail: tUi('Docker 正在等待健康检查') }
  if (row.containers.every(c => c.health === 'healthy')) return { label: tUi('运行健康'), tone: 'success', detail: tUi('Docker 健康检查通过') }
  return { label: tUi('运行中'), tone: 'primary', detail: tUi('未配置或未获取完整健康检查') }
}

export function imageLabel(reference?: string | null) {
  if (!reference) return '—'
  return reference.slice(reference.lastIndexOf('/') + 1)
}

export function bytesLabel(value: number | null | undefined, unlimited = false) {
  if (value === null || value === undefined || !Number.isFinite(value)) return tUi('未获取')
  if (value === 0 && unlimited) return tUi('未设置限制')
  if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(2)} GiB`
  if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(0)} MiB`
  if (value >= 1024) return `${(value / 1024).toFixed(0)} KiB`
  return `${value} B`
}

export function usableUrls(urls: string[]) {
  return urls.filter(value => { try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password } catch { return false } })
}
