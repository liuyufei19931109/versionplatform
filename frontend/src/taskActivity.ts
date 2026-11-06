import { tUi } from './i18n'
export interface ActivityTask {
  key: string
  eventKey: string
  id: number | null
  kind: string
  status: string
  visible: boolean
  mine: boolean
  serviceName: string
  serviceId: number | null
  serviceType: string
  createdBy: string
  branch: string
  environment: string
  createdAt: string
  startedAt: string
  finishedAt: string
  stageLabel: string
  position: number | null
  detailPath: string
  cancelPath: string
  canCancel: boolean
  errorSummary: string
  executionMode: 'CLOUD' | 'LOCAL' | 'UNKNOWN'
  waitingReason: string
}

export interface TaskActivity {
  serverTime: string
  running: ActivityTask | null
  runningTasks: ActivityTask[]
  cloudRunningCount: number
  cloudConcurrency: number
  pending: ActivityTask[]
  runtimePending: ActivityTask[]
  pendingCount: number
  pendingRuntimeCount: number
  recent: ActivityTask[]
}

export function taskDetailPath(value: unknown) {
  const path = String(value || '')
  if (/^\/miniprograms\?jobId=[1-9]\d*$/.test(path)) return path
  return /^(?:\/(?:apps\/)?(?:builds|releases)\/[1-9]\d*|\/runtime\?operationId=[1-9]\d*(?:&serviceId=[1-9]\d*)?)$/.test(path) ? path : ''
}

export function taskCancelPath(value: unknown) {
  const path = String(value || '').replace(/^\/api\//, '/')
  if (/^\/miniprograms\/jobs\/[1-9]\d*\/cancel$/.test(path)) return path
  return /^\/(?:apps\/)?(?:builds|releases)\/[1-9]\d*\/cancel$/.test(path) || /^\/runtime\/restarts\/[1-9]\d*\/cancel$/.test(path) ? path : ''
}

function normalizeTask(value: unknown): ActivityTask | null {
  if (!value || typeof value !== 'object') return null
  const raw = value as Partial<ActivityTask> & { hidden?: boolean; taskKind?: string; currentStepLabel?: string; queuePosition?: number }
  if (typeof raw.key !== 'string' || !raw.key) return null
  const visible = raw.hidden === false
  const status = raw.taskKind === 'MINIPROGRAM' && raw.status === 'RECONCILED' ? 'RECONCILED' : ['PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED', 'ROLLED_BACK', 'WAITING_CONFIRMATION'].includes(String(raw.status)) ? String(raw.status) : 'UNKNOWN'
  return {
    key: raw.key, eventKey: visible ? String(raw.eventKey || '') : '', id: visible && Number.isSafeInteger(raw.id) ? Number(raw.id) : null,
    kind: visible ? String(raw.taskKind || '') : 'OTHER', status, visible,
    mine: visible && raw.mine === true, serviceName: visible ? String(raw.serviceName || tUi('未命名服务')) : tUi('其他任务'),
    serviceId: visible && Number.isSafeInteger(raw.serviceId) ? Number(raw.serviceId) : null,
    serviceType: visible ? String(raw.serviceType || '') : '', createdBy: visible ? String(raw.createdBy || '') : '',
    branch: visible ? String(raw.branch || '') : '', environment: visible ? String(raw.environment || '') : '',
    createdAt: visible ? String(raw.createdAt || '') : '', startedAt: visible ? String(raw.startedAt || '') : '',
    finishedAt: visible ? String(raw.finishedAt || '') : '', stageLabel: visible ? String(raw.currentStepLabel || '') : '',
    position: Number.isSafeInteger(raw.queuePosition) && Number(raw.queuePosition) > 0 ? Number(raw.queuePosition) : null,
    detailPath: visible ? taskDetailPath(raw.detailPath) : '',
    cancelPath: visible && status === 'PENDING' && raw.canCancel ? taskCancelPath(raw.cancelPath) : '',
    canCancel: visible && status === 'PENDING' && raw.canCancel === true && !!taskCancelPath(raw.cancelPath),
    errorSummary: visible ? String(raw.errorSummary || '') : '',
    executionMode: visible && (raw.executionMode === 'CLOUD' || raw.executionMode === 'LOCAL') ? raw.executionMode : 'UNKNOWN',
    waitingReason: visible && status === 'PENDING' ? String(raw.waitingReason || '') : '',
  }
}

export function normalizeTaskActivity(value: unknown): TaskActivity {
  const raw = value as (Partial<TaskActivity> & { recentCompletions?: unknown[] }) | null
  if (!raw || typeof raw !== 'object' || typeof raw.serverTime !== 'string' || !Number.isFinite(Date.parse(raw.serverTime)) || !Array.isArray(raw.pending) || !Array.isArray(raw.runtimePending) || !Array.isArray(raw.recentCompletions) || !('running' in raw) || !Number.isSafeInteger(raw.pendingCount) || Number(raw.pendingCount) < 0) throw new Error(tUi('任务状态返回不完整'))
  const running = raw.running == null ? null : normalizeTask(raw.running)
  if (raw.running && !running) throw new Error(tUi('执行中任务返回不完整'))
  return {
    serverTime: raw.serverTime, running,
    runningTasks: Array.isArray(raw.runningTasks) ? raw.runningTasks.map(normalizeTask).filter((item): item is ActivityTask => !!item) : running ? [running] : [],
    cloudRunningCount: Math.max(0, Number(raw.cloudRunningCount) || 0),
    cloudConcurrency: Math.max(0, Number(raw.cloudConcurrency) || 0),
    pending: raw.pending.map(normalizeTask).filter((item): item is ActivityTask => !!item),
    runtimePending: raw.runtimePending.map(normalizeTask).filter((item): item is ActivityTask => !!item),
    pendingCount: Number(raw.pendingCount), pendingRuntimeCount: Math.max(0, Number(raw.pendingRuntimeCount) || 0),
    recent: raw.recentCompletions.map(normalizeTask).filter((item): item is ActivityTask => !!item && item.visible && item.mine),
  }
}

export function activityKind(task: Pick<ActivityTask, 'kind' | 'serviceType' | 'visible'>) {
  if (!task.visible) return tUi('任务')
  if (task.kind === 'MINIPROGRAM') return tUi('小程序构建')
  if (task.kind === 'BUILD') return task.serviceType === 'FLUTTER_ANDROID' ? tUi('App 构建') : tUi('打包')
  if (task.kind === 'DEPLOY' || task.kind === 'RELEASE') return task.serviceType === 'FLUTTER_ANDROID' ? tUi('App 交付') : tUi('发布')
  return ({ RESTART: tUi('更新配置并重启'), MERGE: tUi('代码合并'), RUNTIME: tUi('运行操作') } as Record<string, string>)[task.kind] || tUi('任务')
}

export function activityElapsed(task: Pick<ActivityTask, 'startedAt' | 'finishedAt'>, now = Date.now()) {
  const start = Date.parse(task.startedAt), finish = task.finishedAt ? Date.parse(task.finishedAt) : now
  if (!Number.isFinite(start) || !Number.isFinite(finish)) return ''
  const seconds = Math.max(0, Math.floor((finish - start) / 1000))
  if (seconds < 60) return tUi('{0} 秒', seconds)
  if (seconds < 3600) return tUi('{0} 分 {1} 秒', Math.floor(seconds / 60), seconds % 60)
  return tUi('{0} 小时 {1} 分', Math.floor(seconds / 3600), Math.floor(seconds % 3600 / 60))
}

export function completionKey(task: ActivityTask) { return task.eventKey || `${task.key}:${task.status}:${task.finishedAt}` }

export interface ActivityNoticeLedger { baseline: number; seen: string[] }
export function readActivityLedger(value: string | null): ActivityNoticeLedger | null {
  try {
    const raw = JSON.parse(value || '') as ActivityNoticeLedger
    return Number.isFinite(raw.baseline) && Array.isArray(raw.seen) ? { baseline: raw.baseline, seen: raw.seen.filter(item => typeof item === 'string').slice(-256) } : null
  } catch { return null }
}

export function unseenCompletions(tasks: ActivityTask[], ledger: ActivityNoticeLedger) {
  const seen = new Set(ledger.seen)
  return tasks.filter(task => task.visible && task.mine && (['SUCCESS', 'FAILED', 'CANCELLED', 'ROLLED_BACK'].includes(task.status) || (task.kind === 'MINIPROGRAM' && task.status === 'UNKNOWN')) && Date.parse(task.finishedAt) >= ledger.baseline && !seen.has(completionKey(task))).sort((a, b) => Date.parse(a.finishedAt) - Date.parse(b.finishedAt))
}
