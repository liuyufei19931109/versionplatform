<script setup lang="ts">
import { tUi } from '../i18n'

import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Bell, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorText, http, rows } from '../api'
import { formatTime } from '../ui'

interface NotificationRecord {
  id: number | string
  status: 'PENDING' | 'SENDING' | 'SENT' | 'FAILED'
  attempts: number
  createdAt?: string
  sentAt?: string
  lastError?: string
}
const props = defineProps<{ taskId: string; taskStatus?: string; canRetry: boolean }>()
const records = ref<NotificationRecord[]>([]), loading = ref(false), error = ref(''), retrying = ref<number | string | null>(null)
const terminal = computed(() => ['SUCCESS', 'FAILED'].includes(props.taskStatus || ''))
const active = computed(() => records.value.some(record => ['PENDING', 'SENDING'].includes(record.status)))
const labels = { get PENDING() { return tUi('待发送') }, get SENDING() { return tUi('发送中') }, get SENT() { return tUi('已发送') }, get FAILED() { return tUi('发送失败') } }
const tagTypes = { PENDING: 'info', SENDING: 'primary', SENT: 'success', FAILED: 'danger' } as const
let timer: number | undefined, disposed = false, fetching = false, reloadRequested = false, remainingPolls = 0, generation = 0
let completionChecks: number[] = []
function clearTimer() { if (timer) window.clearTimeout(timer); timer = undefined }
function schedule() {
  clearTimer()
  if (disposed || !terminal.value) return
  // A retried task can still return the previous attempt's terminal notification
  // before its new outbox row is committed. Give every completion two short checks.
  const completionDelay = completionChecks.shift()
  if (completionDelay !== undefined) {
    timer = window.setTimeout(() => load(), completionDelay)
  } else if (remainingPolls > 0 && (active.value || !records.value.length || error.value)) {
    // Longer delivery retries remain bounded to twenty checks over five minutes.
    remainingPolls -= 1
    timer = window.setTimeout(() => load(), 15000)
  }
}
async function load() {
  if (disposed) return
  if (fetching) { reloadRequested = true; return }
  fetching = true; loading.value = true
  const requestGeneration = generation
  try {
    const result = await http.get(`/releases/${props.taskId}/notifications`)
    if (!disposed && requestGeneration === generation) { records.value = rows<NotificationRecord>(result); error.value = '' }
  } catch (value) { if (!disposed && requestGeneration === generation) error.value = errorText(value, tUi('暂时无法读取通知状态')) }
  finally {
    fetching = false; loading.value = false
    // Do not lose a task-id/status change while a request for the old task is in flight.
    if (!disposed && reloadRequested) { reloadRequested = false; void load() }
    else schedule()
  }
}
function refresh() { remainingPolls = 20; void load() }
async function retry(record: NotificationRecord) {
  if (retrying.value !== null || !props.canRetry || record.status !== 'FAILED') return
  retrying.value = record.id
  try { await ElMessageBox.confirm(tUi('将重新发送这条发版结果通知到企业微信群，不会重新执行发版。'), tUi('重新发送通知'), { confirmButtonText: tUi('重新发送'), cancelButtonText: tUi('取消'), type: 'info' }) }
  catch { retrying.value = null; return }
  try {
    await http.post(`/releases/${props.taskId}/notifications/${record.id}/retry`)
    ElMessage.success(tUi('通知已重新加入发送队列'))
    remainingPolls = 20
    await load()
  } catch (value) { ElMessage.error(errorText(value)) }
  finally { retrying.value = null }
}
watch(() => [props.taskId, props.taskStatus], ([taskId], previous) => {
  if (taskId !== previous?.[0]) { generation += 1; records.value = []; error.value = '' }
  remainingPolls = terminal.value ? 20 : 0
  completionChecks = terminal.value ? [2000, 5000] : []
  clearTimer()
  void load()
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; clearTimer() })
</script>

<template>
  <section class="panel release-notifications">
    <div class="panel-title"><div class="notification-heading"><el-icon><Bell /></el-icon><h3>{{ tUi('群通知') }}</h3><span>{{ tUi('企业微信') }}</span></div><el-button link :icon="Refresh" :loading="loading" @click="refresh">{{ tUi('刷新状态') }}</el-button></div>
    <div v-if="error" class="notification-state-error">{{ error }}<small>{{ tUi('通知状态读取失败不影响发版结果。') }}</small></div>
    <div v-else-if="!records.length" class="notification-empty">{{ loading ? tUi('正在读取通知状态…') : terminal ? tUi('尚未生成群通知。已启用且符合通知范围的新发版会自动发送，历史记录不补发。') : tUi('任务完成后，将按配置发送发版结果通知。') }}</div>
    <div v-else class="notification-records">
      <div v-for="record in records" :key="record.id" class="notification-record">
        <div class="notification-result"><el-tag :type="tagTypes[record.status] || 'info'" size="small">{{ labels[record.status] || record.status }}</el-tag><span v-if="record.status === 'SENT'">{{ formatTime(record.sentAt) }}</span><span v-else>{{ tUi('创建于 {0}', formatTime(record.createdAt)) }}</span><small>{{ tUi('已尝试 {0} 次', record.attempts || 0) }}</small></div>
        <p v-if="record.lastError && record.status !== 'SENT'" class="notification-error">{{ record.lastError }}</p>
        <el-button v-if="record.status === 'FAILED' && canRetry" link type="primary" :loading="retrying === record.id" :disabled="retrying !== null && retrying !== record.id" @click="retry(record)">{{ tUi('重新发送通知') }}</el-button>
      </div>
    </div>
    <p class="notification-note">{{ tUi('通知独立发送，发送失败不会改变发版结果。') }}</p>
  </section>
</template>

<style scoped>
.release-notifications{margin-bottom:20px}.notification-heading{display:flex;align-items:center;gap:9px}.notification-heading>.el-icon{color:#1b9766;font-size:18px}.notification-heading>span{font-size:12px;color:#98a2b3;margin-left:5px}.notification-empty,.notification-state-error{color:#667085;font-size:13px;line-height:1.8}.notification-state-error{color:#b54708}.notification-state-error small{display:block;color:#98a2b3}.notification-records{display:grid;gap:12px}.notification-record{padding:13px 16px;background:#f8fafc;border:1px solid #eef1f5;border-radius:10px}.notification-result{display:flex;align-items:center;gap:14px;flex-wrap:wrap;font-size:12px;color:#667085}.notification-result small{color:#98a2b3}.notification-error{font-size:12px;color:#b54708;margin:9px 0 4px;line-height:1.6;overflow-wrap:anywhere}.notification-note{font-size:12px;color:#98a2b3;margin:13px 0 0}
</style>
