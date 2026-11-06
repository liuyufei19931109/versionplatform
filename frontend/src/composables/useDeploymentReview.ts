import { computed, onScopeDispose, ref, watch, type Ref } from 'vue'
import { errorText, http, payload } from '../api'

export type Environment = 'dev' | 'test' | 'prod'

export interface ReleaseStamp {
  taskId: number
  artifactId: number
  buildTaskId: number | null
  actor: string
  at: string
  status: string
  serverIds: number[]
  scopeKnown: boolean
}

export interface HistoryItem {
  artifactId: number
  environment: Environment
  latestSuccess: ReleaseStamp | null
  latestAttempt: ReleaseStamp
}

export interface ReviewTarget {
  serviceId: number
  artifactId: number
  environment: string
  serverIds: number[]
}

export interface DeploymentReview {
  action: 'ALLOW' | 'CONFIRM' | 'ACTIVE'
  confirmationToken: string | null
  nodes: {
    serverId: number
    state: 'FIRST' | 'CHANGE' | 'REPEAT' | 'ROLLBACK' | 'UNKNOWN'
    latestSuccess: ReleaseStamp | null
    latestAttempt?: ReleaseStamp | null
  }[]
  activeTasks: ReleaseStamp[]
}

export function useArtifactHistory(serviceId: Readonly<Ref<number | null>>) {
  const entries = ref<HistoryItem[]>([])
  const loading = ref(false)
  const error = ref('')
  let controller: AbortController | undefined

  async function refresh() {
    controller?.abort()
    entries.value = []
    error.value = ''
    const id = serviceId.value
    if (!id) { loading.value = false; return }
    const current = new AbortController()
    controller = current
    loading.value = true
    try {
      const response = await http.get('/releases/artifact-history', { params: { serviceId: id }, signal: current.signal })
      if (current.signal.aborted) return
      const result = payload<HistoryItem[] | null>(response, null)
      if (!Array.isArray(result)) throw new Error('发布历史格式异常')
      entries.value = result
    } catch (reason) {
      if (!current.signal.aborted) error.value = errorText(reason, '暂时无法读取发布历史')
    } finally {
      if (!current.signal.aborted) loading.value = false
    }
  }

  const byArtifact = computed(() => {
    const map = new Map<number, HistoryItem[]>()
    for (const item of entries.value) {
      const items = map.get(item.artifactId) || []
      items.push(item)
      map.set(item.artifactId, items)
    }
    return map
  })

  watch(serviceId, refresh, { immediate: true, flush: 'sync' })
  onScopeDispose(() => controller?.abort())
  return { byArtifact, loading, error, refresh }
}

function validReview(value: DeploymentReview | null, expectedIds: number[] = []): value is DeploymentReview {
  if (!value || !['ALLOW', 'CONFIRM', 'ACTIVE'].includes(value.action)) return false
  if (!Array.isArray(value.nodes) || !Array.isArray(value.activeTasks)) return false
  const received = new Set(value.nodes.map(node => node.serverId))
  if (!expectedIds.length || received.size !== expectedIds.length || !expectedIds.every(id => received.has(id))) return false
  if (value.nodes.some(node => !['FIRST', 'CHANGE', 'REPEAT', 'ROLLBACK', 'UNKNOWN'].includes(node.state))) return false
  if (value.action === 'CONFIRM' && !value.confirmationToken) return false
  return value.action !== 'ACTIVE' || value.activeTasks.length > 0
}

export function reviewConfirmationLabel(review: DeploymentReview | null) {
  if (review?.nodes.some(node => node.state === 'ROLLBACK')) return '确认切回此版本'
  if (review?.nodes.some(node => node.state === 'REPEAT')) return '确认再次发布'
  return '确认发布到所选节点'
}

export function useDeploymentReview(target: Readonly<Ref<ReviewTarget | null>>) {
  const result = ref<DeploymentReview | null>(null)
  const loading = ref(false)
  const error = ref('')
  let controller: AbortController | undefined

  const key = computed(() => {
    const value = target.value
    if (!value || !value.serverIds.length) return ''
    return [value.serviceId, value.artifactId, value.environment, ...[...value.serverIds].sort((a, b) => a - b)].join(':')
  })

  async function refresh() {
    controller?.abort()
    result.value = null
    error.value = ''
    const value = target.value
    if (!value || !key.value) { loading.value = false; return }
    const current = new AbortController()
    controller = current
    loading.value = true
    try {
      const response = await http.get('/releases/preflight', {
        params: { ...value, serverIds: value.serverIds.join(',') }, signal: current.signal,
      })
      if (current.signal.aborted) return
      const check = payload<DeploymentReview | null>(response, null)
      if (!validReview(check, value.serverIds)) throw new Error('未能核对所选节点的发布情况，请重新核对')
      result.value = check
    } catch (reason) {
      if (!current.signal.aborted) error.value = errorText(reason, '暂时无法核对所选节点的发布情况')
    } finally {
      if (!current.signal.aborted) loading.value = false
    }
  }

  function applyConflict(reason: unknown) {
    const response = (reason as { response?: { status: number; data?: { code?: string; preflight?: DeploymentReview } } }).response
    if (response?.status !== 409 || !['DEPLOY_REVIEW_REQUIRED', 'DEPLOY_ACTIVE'].includes(response.data?.code || '')) return false
    controller?.abort()
    loading.value = false
    const check = response.data?.preflight ?? null
    result.value = validReview(check, target.value?.serverIds) ? check : null
    error.value = result.value ? '' : '目标状态已变化，请重新核对'
    return true
  }

  watch(key, refresh, { immediate: true, flush: 'sync' })
  onScopeDispose(() => controller?.abort())
  return { key, result, loading, error, refresh, applyConflict }
}

