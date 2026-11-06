import { useAppScope } from './useAppScope'
import { computed, ref } from 'vue'
import { http, payload } from '../api'

export interface QueueTask { taskId: number | string; taskKind: string; serviceId: number | string; position: number; waitingReason?: string }
export interface QueueState { runningTaskId?: number | string; runningTaskKind?: string; runningServiceId?: number | string; pendingCount: number; pendingTasks: QueueTask[]; capacity: number; runningRuntimeOperation?: string; pendingRuntimeOperations?: number; cloudRunningTasks?: QueueTask[]; cloudConcurrency?: number }
export function useQueue() {
  const { apps, api } = useAppScope()
  const queue = ref<QueueState | null>(null)
  async function refreshQueue() { try { queue.value = payload<QueueState>(await http.get(api('/builds/queue')), { pendingCount: 0, pendingTasks: [], capacity: 100 }) } catch { queue.value = null } }
  const summary = computed(() => !queue.value ? '正在读取构建与发布队列' : `${queue.value.cloudRunningTasks?.length ? `${queue.value.cloudRunningTasks.length} 个云构建进行中 · ` : ''}${queue.value.runningRuntimeOperation ? (apps?queue.value.runningRuntimeOperation:`${queue.value.runningRuntimeOperation} 正在执行`) : queue.value.runningTaskId ? `任务 #${queue.value.runningTaskId} 正在执行` : '本机执行器空闲'} · ${queue.value.pendingCount + (queue.value.pendingRuntimeOperations || 0)} 个任务排队`)
  function positionText(id: unknown) {
    if (!queue.value) return '等待调度 · 正在核对队列'
    const task = queue.value.pendingTasks?.find(item => String(item.taskId) === String(id))
    return task?.waitingReason || '等待执行状态更新'
  }
  return { queue, summary, refreshQueue, positionText }
}
