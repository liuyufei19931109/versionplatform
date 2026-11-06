import { tUi } from '../i18n'
import { useAppScope } from './useAppScope'
import { computed, ref } from 'vue'
import { http, payload } from '../api'

export interface QueueTask { taskId: number | string; taskKind: string; serviceId: number | string; position: number; waitingReason?: string }
export interface QueueState { runningTaskId?: number | string; runningTaskKind?: string; runningServiceId?: number | string; pendingCount: number; pendingTasks: QueueTask[]; capacity: number; runningRuntimeOperation?: string; pendingRuntimeOperations?: number; cloudRunningTasks?: QueueTask[]; cloudConcurrency?: number }
export function queueWaitText(reason?: string): string {
  if (!reason) return ''
  const slots = /^等待云构建名额（(\d+)\/(\d+) 已占用）$/.exec(reason)
  return slots ? tUi('等待云构建名额（{0}/{1} 已占用）', slots[1], slots[2]) : tUi(reason)
}

function runtimeOperationText(label: string): string {
  const merge = /^生产代码合回 #(\d+)$/.exec(label)
  if (merge) return tUi('生产代码合回 #{0}', merge[1])
  if (label.startsWith('更新配置并重启 ')) return tUi('更新配置并重启 {0}', label.slice('更新配置并重启 '.length))
  if (label.startsWith('小程序 ')) return tUi('小程序 {0}', label.slice('小程序 '.length))
  return tUi(label)
}

export function useQueue() {
  const { apps, api } = useAppScope()
  const queue = ref<QueueState | null>(null)
  async function refreshQueue() { try { queue.value = payload<QueueState>(await http.get(api('/builds/queue')), { pendingCount: 0, pendingTasks: [], capacity: 100 }) } catch { queue.value = null } }
  const summary = computed(() => !queue.value ? tUi('正在读取构建与发布队列') : tUi('{0}{1} · {2} 个任务排队', queue.value.cloudRunningTasks?.length ? tUi('{0} 个云构建进行中 · ', queue.value.cloudRunningTasks.length) : '', queue.value.runningRuntimeOperation ? (apps?tUi(queue.value.runningRuntimeOperation):tUi('{0} 正在执行', runtimeOperationText(queue.value.runningRuntimeOperation))) : queue.value.runningTaskId ? tUi('任务 #{0} 正在执行', queue.value.runningTaskId) : tUi('本机执行器空闲'), queue.value.pendingCount + (queue.value.pendingRuntimeOperations || 0)))
  function positionText(id: unknown) {
    if (!queue.value) return tUi('等待调度 · 正在核对队列')
    const task = queue.value.pendingTasks?.find(item => String(item.taskId) === String(id))
    return queueWaitText(task?.waitingReason) || tUi('等待执行状态更新')
  }
  return { queue, summary, refreshQueue, positionText }
}
