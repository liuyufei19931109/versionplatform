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
export function operationStage(stage: string) { return ({ WAITING: '等待串行执行', CHECK_TARGET: '核对目标容器', RESTART_CONTAINER: '重启容器', RELOAD_CONFIGURATION: '加载配置并重建服务', HEALTH_CHECK: '等待健康检查', COMPLETE: '操作完成', CANCELLED: '已中止' } as Record<string,string>)[stage] || stage }
export function operationStatus(status: RuntimeOperation['status']) { return ({ PENDING: '排队中', RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败', CANCELLED: '已取消' })[status] }
export type RuntimeTone = 'success' | 'warning' | 'danger' | 'primary' | 'info'

export function runtimeStatus(row: RuntimeRow, stale = row.stale): { label: string; tone: RuntimeTone; detail: string } {
  if (row.checkState === 'NOT_CONFIGURED') return { label: '未配置', tone: 'info', detail: '尚未绑定目标节点' }
  if (row.checkState === 'DISABLED') return { label: '已停用', tone: 'info', detail: '服务、环境或节点未启用' }
  if (row.checkState === 'UNCHECKED') return { label: '未检查', tone: 'info', detail: '点击检查获取运行状态' }
  if (row.checkState === 'CHECKING') return { label: '检查中', tone: 'primary', detail: '正在依次读取目标节点' }
  if (row.checkState === 'ERROR') return { label: '检查失败', tone: 'danger', detail: '展开详情查看原因' }
  if (stale) return { label: '结果已过期', tone: 'info', detail: '请重新检查当前状态' }
  if (!row.containers.length) return { label: '未发现容器', tone: 'warning', detail: '目标配置未找到对应容器' }
  if (row.containers.some(c => c.state !== 'running')) return { label: '未全部运行', tone: 'danger', detail: '存在未运行或暂停的容器' }
  if (row.containers.some(c => c.health === 'unhealthy')) return { label: '健康异常', tone: 'danger', detail: 'Docker 健康检查未通过' }
  if (row.containers.some(c => c.health === 'starting')) return { label: '启动检查中', tone: 'warning', detail: 'Docker 正在等待健康检查' }
  if (row.containers.every(c => c.health === 'healthy')) return { label: '运行健康', tone: 'success', detail: 'Docker 健康检查通过' }
  return { label: '运行中', tone: 'primary', detail: '未配置或未获取完整健康检查' }
}

export function imageLabel(reference?: string | null) {
  if (!reference) return '—'
  return reference.slice(reference.lastIndexOf('/') + 1)
}

export function bytesLabel(value: number | null | undefined, unlimited = false) {
  if (value === null || value === undefined || !Number.isFinite(value)) return '未获取'
  if (value === 0 && unlimited) return '未设置限制'
  if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(2)} GiB`
  if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(0)} MiB`
  if (value >= 1024) return `${(value / 1024).toFixed(0)} KiB`
  return `${value} B`
}

export function usableUrls(urls: string[]) {
  return urls.filter(value => { try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password } catch { return false } })
}
