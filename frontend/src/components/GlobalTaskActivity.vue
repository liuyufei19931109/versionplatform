<script setup lang="ts">
import { computed, h, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Clock, RefreshRight } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import type { NotificationHandle } from 'element-plus'
import { errorText, http } from '../api'
import { environmentLabel, formatTime, statusLabel } from '../ui'
import { activityElapsed, activityKind } from '../taskActivity'
import type { ActivityTask } from '../taskActivity'
import { useTaskActivity } from '../composables/useTaskActivity'

const router = useRouter(), open = ref(false), cancelling = ref(''), cancelled = ref(new Set<string>())
const notices = new Set<NotificationHandle>()
const { available, feed, loading, error, now, refresh, scope } = useTaskActivity(completed)
const runningTasks = computed(() => feed.value?.runningTasks || [])
const running = computed(() => feed.value?.running)
const pendingCount = computed(() => (feed.value?.pendingCount || 0) + (feed.value?.pendingRuntimeCount || 0))
const activeSummary = computed(() => {
  const names = runningTasks.value.filter(task => task.visible).map(task => task.serviceName)
  if (!names.length) return '任务详情按查看权限展示'
  return names.slice(0, 2).join('、') + (names.length > 2 ? ` 等 ${names.length} 个服务任务` : '')
})
const title = computed(() => {
  if (error.value && !feed.value) return '任务状态暂不可用'
  if (!feed.value) return '正在读取任务状态'
  if (runningTasks.value.length > 1) return `${runningTasks.value.length} 个任务进行中`
  if (running.value) return running.value.visible ? `${activityKind(running.value)}中` : '其他任务执行中'
  return pendingCount.value ? '等待调度' : '当前空闲'
})
const sections = computed(() => [
  { key: 'running', title: '正在执行', rows: runningTasks.value, note: '查看当前步骤，区分云端编译与本机后续处理。' },
  { key: 'pending', title: '等待开始', rows: feed.value?.pending || [], note: '按提交顺序展示；云构建与本机任务分别等待资源，不代表全局启动顺序。' },
  { key: 'runtime', title: '等待运行操作', rows: feed.value?.runtimePending || [], note: '重启、代码合并等等待本机执行资源，不代表与打包任务的先后顺序。' },
  { key: 'recent', title: '我的最近结果', rows: (feed.value?.recent || []).slice(0, 5), note: '最近 24 小时内完成的 5 个任务。' },
].filter(section => section.rows.length))

function go(task: ActivityTask) { if (task.detailPath) { open.value = false; void router.push(task.detailPath) } }
function viewLatest() { open.value = true; void refresh() }
function resultLabel(task: ActivityTask) { return task.kind === 'MINIPROGRAM' && task.status === 'RECONCILED' ? '已核对' : task.kind === 'MINIPROGRAM' && task.status === 'UNKNOWN' ? '微信结果待核对' : statusLabel(task.status) }
function modeLabel(task: ActivityTask) { return task.executionMode === 'CLOUD' ? '云构建任务' : task.executionMode === 'LOCAL' ? '本机任务' : '' }
function waitElapsed(task: ActivityTask) { return activityElapsed({ startedAt: task.createdAt, finishedAt: '' }, now.value) }
function completed(tasks: ActivityTask[]) {
  if (!tasks.length) return
  const last = tasks[tasks.length - 1]!, single = tasks.length === 1
  const failed = tasks.some(task => task.status === 'FAILED' || (task.kind === 'MINIPROGRAM' && task.status === 'UNKNOWN'))
  const message = h('div', { class: 'task-completion-notice', 'data-testid': 'task-completion-notice' }, [
    h('p', single ? last.errorSummary || `${last.serviceName} · ${resultLabel(last)}` : tasks.slice(-3).map(task => `${task.serviceName} ${resultLabel(task)}`).join('；')),
    h('button', { type: 'button', onClick: () => { handle.close(); single && last.detailPath ? go(last) : viewLatest() } }, single && last.detailPath ? '查看任务' : '查看我的结果'),
  ])
  const handle = ElNotification({
    title: single ? `你的${activityKind(last)}${last.kind === 'MINIPROGRAM' ? ' · ' : ''}${resultLabel(last)}` : `你的 ${tasks.length} 个任务已有结果`,
    message, type: failed ? 'warning' : tasks.every(task => task.status === 'SUCCESS') ? 'success' : 'info',
    duration: 10_000, position: 'bottom-right', customClass: 'task-completion-toast', onClose: () => notices.delete(handle),
  })
  notices.add(handle)
}
async function cancel(task: ActivityTask) {
  if (!task.canCancel || task.status !== 'PENDING' || !task.cancelPath || cancelling.value || cancelled.value.has(task.key) || error.value) return
  const initialScope = scope.value
  cancelling.value = task.key
  try {
    await ElMessageBox.confirm(`“${task.serviceName}”尚未开始。确认取消这次${activityKind(task)}排队？`, '取消排队', { confirmButtonText: '取消排队', cancelButtonText: '继续等待', type: 'warning' })
    if (initialScope !== scope.value) return
    await http.post(task.cancelPath)
    if (initialScope !== scope.value) return
    cancelled.value.add(task.key); ElMessage.success('取消请求已提交'); await refresh()
  } catch (reason) {
    if (reason !== 'cancel' && reason !== 'close' && initialScope === scope.value) ElMessage.error(errorText(reason, '取消失败，请刷新任务状态后重试'))
  } finally { cancelling.value = '' }
}
watch(scope, () => { open.value = false; cancelled.value = new Set(); notices.forEach(notice => notice.close()); notices.clear() })
watch(feed, value => { cancelled.value = new Set([...cancelled.value].filter(key => [...(value?.pending || []), ...(value?.runtimePending || [])].some(task => task.key === key))) })
onBeforeUnmount(() => { notices.forEach(notice => notice.close()); notices.clear() })
</script>

<template>
  <div v-if="available" class="global-task-activity" :class="{ 'is-running': runningTasks.length && !error, 'is-stale': error }" data-testid="global-task-activity">
    <button type="button" class="activity-trigger" :aria-expanded="open" aria-haspopup="dialog" aria-label="查看全平台任务与排队情况" @click="viewLatest">
      <span class="activity-dot" aria-hidden="true" />
      <span class="activity-bar-content">
        <span class="activity-bar-main">
          <b>{{ title }}</b>
          <strong v-if="runningTasks.length === 1 && running?.visible" :title="running.serviceName">{{ running.serviceName }}</strong>
          <span v-if="runningTasks.length === 1 && running?.visible" class="activity-bar-actor">{{ running.createdBy || '系统' }} 发起</span>
          <span v-if="!runningTasks.length && feed && !error" class="activity-idle-detail">{{ pendingCount ? '等待可用执行资源' : '暂无进行中的任务' }}</span>
        </span>
        <span class="activity-bar-sub">
          <span v-if="error">{{ error }}</span>
          <span v-else-if="runningTasks.length > 1" :title="activeSummary">{{ activeSummary }}</span>
          <template v-else-if="running?.visible"><span>{{ running.stageLabel || '正在准备任务' }}</span><span v-if="activityElapsed(running, now)" class="activity-elapsed">已运行 {{ activityElapsed(running, now) }}</span></template>
          <span v-else-if="running">当前任务详情不在你的查看权限内</span>
        </span>
      </span>
      <span class="activity-bar-end">
        <span v-if="feed?.cloudConcurrency" class="activity-cloud" title="名额从任务开始保留到产物回传、镜像等后续处理完成">云构建 <b>{{ feed.cloudRunningCount }}/{{ feed.cloudConcurrency }}</b></span>
        <span v-if="feed" class="activity-pending" :class="{ 'has-pending': pendingCount }">排队 <b>{{ pendingCount }}</b></span>
        <span class="activity-open-label">查看任务</span><el-icon><ArrowRight /></el-icon>
      </span>
    </button>
  </div>

  <el-drawer v-model="open" title="任务动态" size="min(640px, 100vw)" class="task-activity-drawer" :destroy-on-close="true">
    <template #header><div class="activity-panel-heading"><h2>任务与排队</h2><p>{{ feed?.cloudConcurrency ? '云构建可以并行，本机处理与发布逐个执行' : '任务依次执行，状态自动更新' }}</p></div></template>
    <div v-if="feed" class="activity-overview" aria-label="任务数量概览">
      <div><span>进行中</span><strong>{{ runningTasks.length }}</strong></div>
      <div><span>排队</span><strong>{{ pendingCount }}</strong></div>
      <div v-if="feed.cloudConcurrency"><span>云构建名额</span><strong>{{ feed.cloudRunningCount }}<small> / {{ feed.cloudConcurrency }}</small></strong></div>
    </div>
    <p v-if="feed?.cloudConcurrency" class="activity-capacity-note">云构建名额包含产物回传、镜像处理等后续步骤，完成后释放。</p>
    <div class="activity-panel-toolbar"><span>{{ feed ? `更新于 ${formatTime(feed.serverTime)}` : '正在读取最新状态' }}</span><button type="button" :disabled="loading" @click="refresh"><el-icon><RefreshRight /></el-icon>刷新</button></div>
    <div v-if="error" class="activity-panel-error" role="status"><b>{{ error }}</b><p>恢复连接后会自动更新。当前状态不能用于判断任务是否仍在执行。</p></div>
    <div v-if="!feed" class="activity-panel-empty"><el-icon :size="25"><Clock /></el-icon><strong>{{ error ? '暂时无法读取任务' : '正在读取任务…' }}</strong><p v-if="error">点击刷新重试，正在执行的任务不会因此停止。</p></div>
    <template v-else>
      <div v-if="!runningTasks.length && !pendingCount" class="activity-panel-idle"><span class="activity-idle-dot"/><div><b>当前空闲</b><p>没有正在执行或排队的任务。</p></div></div>
      <section v-for="section in sections" :key="section.key" class="activity-section" :data-section="section.key">
        <header><h3>{{ section.title }}<span class="activity-section-count">{{ section.rows.length }}</span></h3><p v-if="section.note">{{ section.note }}</p></header>
        <ol class="activity-task-list">
          <li v-for="task in section.rows" :key="task.key" class="activity-task" :class="{ 'is-mine': task.mine, 'is-failed': task.status === 'FAILED', 'is-hidden': !task.visible }" :data-task-key="task.key">
            <div class="activity-task-heading">
              <div class="activity-task-identity"><strong>{{ task.visible ? task.serviceName : '其他任务' }}</strong><span v-if="task.mine" class="activity-mine">我发起的</span></div>
              <span class="activity-status" :class="`activity-state-${task.status.toLowerCase()}`">{{ task.status === 'PENDING' ? '排队中' : resultLabel(task) }}</span>
            </div>
            <template v-if="task.visible">
              <div class="activity-task-meta">
                <span class="activity-task-kind">{{ activityKind(task) }}<span v-if="task.id"> #{{ task.id }}</span></span>
                <span v-if="modeLabel(task)" class="activity-mode" :class="{ 'is-cloud': task.executionMode === 'CLOUD' }">{{ modeLabel(task) }}</span>
                <span v-if="task.environment && task.environment !== 'build'" class="activity-environment" :class="`env-${task.environment}`">{{ environmentLabel(task.environment) }}</span>
                <span class="activity-task-actor"><b>{{ task.createdBy || '系统' }}</b> 发起</span>
              </div>
              <div v-if="task.branch" class="activity-task-branch"><span>分支</span><b>{{ task.branch }}</b></div>
              <div v-if="section.key === 'running'" class="activity-task-stage"><span><i aria-hidden="true"/>{{ task.stageLabel || '正在准备执行' }}</span><time v-if="activityElapsed(task, now)">已运行 {{ activityElapsed(task, now) }}</time></div>
              <div v-if="task.status === 'PENDING'" class="activity-task-wait"><span>{{ task.waitingReason || '等待调度，开始后自动更新' }}</span><time v-if="waitElapsed(task)">已等待 {{ waitElapsed(task) }}</time></div>
              <p v-if="task.status === 'FAILED'" class="activity-task-failure">{{ task.errorSummary || `${task.stageLabel || activityKind(task)}失败，查看任务定位原因。` }}</p>
              <div class="activity-task-foot"><time :title="formatTime(task.finishedAt || task.createdAt)">{{ task.finishedAt ? `${formatTime(task.finishedAt)} 完成` : `${formatTime(task.createdAt)} 提交` }}</time><div class="activity-task-actions"><button v-if="task.canCancel" type="button" class="activity-cancel" :disabled="!!cancelling || cancelled.has(task.key) || !!error" @click="cancel(task)">{{ cancelled.has(task.key) ? '已请求取消' : cancelling === task.key ? '处理中…' : '取消排队' }}</button><button v-if="task.detailPath" type="button" @click="go(task)">{{ task.status === 'FAILED' ? '查看失败原因' : '查看任务' }}</button></div></div>
            </template>
            <p v-else class="activity-hidden-note">当前任务详情不在你的查看权限内。</p>
          </li>
        </ol>
      </section>
      <div v-if="!feed.recent.length" class="activity-no-results">你最近 24 小时还没有完成的任务。</div>
    </template>
  </el-drawer>
</template>

<style>
.task-activity-drawer .el-drawer__header{margin-bottom:0;padding:24px 26px 20px;border-bottom:1px solid #e3e9f2}
.task-activity-drawer .el-drawer__body{padding:0 26px 24px;background:#fff}
.task-completion-notice p{margin:0 0 8px;font-size:12px;line-height:1.7;overflow-wrap:anywhere}
.task-completion-notice button{border:0;padding:0;background:none;color:#416bdc;font:inherit;font-size:12px;cursor:pointer}
.task-completion-toast.el-notification{max-width:calc(100vw - 32px)}
@media(max-width:480px){.task-activity-drawer .el-drawer__header{padding:20px 18px 16px}.task-activity-drawer .el-drawer__body{padding:0 18px 24px}}
</style>
<style scoped>
.global-task-activity{position:sticky;top:var(--platform-header-height);z-index:14;background:#fafbfd;border-bottom:1px solid #e3e9f2;color:#607088}
.global-task-activity.is-running{background:#f1f5ff}.global-task-activity.is-stale{background:#fff8eb;color:#996617}
.activity-trigger{width:100%;min-height:58px;display:flex;align-items:center;gap:12px;padding:10px 32px;border:0;text-align:left;background:transparent;color:inherit;font:inherit;font-size:13px;cursor:pointer}
.activity-trigger:hover{background:#416bdc06}.activity-trigger:focus-visible{outline:2px solid #416bdc;outline-offset:-3px}
.activity-dot{width:8px;height:8px;flex:0 0 8px;border-radius:50%;background:#a9b4c5}
.is-running .activity-dot{background:#416bdc;box-shadow:0 0 0 4px #416bdc12}.is-stale .activity-dot{background:#c18c38;box-shadow:none}
.activity-bar-content{flex:1;min-width:0}.activity-bar-main{display:flex;align-items:center;gap:14px;min-width:0}
.activity-bar-main>b{font-weight:650;color:#47618c;flex-shrink:0}.activity-bar-main>strong{font-weight:750;color:#24344d;max-width:300px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.activity-bar-actor{white-space:nowrap;color:#65758c}.activity-idle-detail{color:#8591a3;font-size:12px}
.activity-bar-sub{display:flex;gap:14px;font-size:12px;line-height:1.5;margin-top:3px;color:#677f9f;min-width:0}
.activity-bar-sub:empty{display:none}.activity-bar-sub>span:first-child{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.activity-elapsed{flex-shrink:0;font-variant-numeric:tabular-nums}
.activity-bar-end{display:flex;align-items:center;gap:15px;white-space:nowrap;flex-shrink:0}
.activity-cloud{font-size:12px;color:#64799c}.activity-cloud b{font-variant-numeric:tabular-nums;color:#416bdc;margin-left:3px}
.activity-pending{display:inline-flex;align-items:center;gap:7px;padding:5px 10px;border-radius:5px;background:#e8edf5;font-size:12px;color:#607088}
.activity-pending.has-pending{background:#fff0d8;color:#936217}.activity-pending b{font-size:14px;font-variant-numeric:tabular-nums}
.activity-open-label{font-size:12px;color:#416bdc}
.activity-panel-heading h2{font-size:21px;font-weight:750;color:#24344d;margin:0}
.activity-panel-heading p{font-size:13px;line-height:1.6;margin:7px 0 0;color:#728096}
.activity-overview{display:flex;align-items:center;gap:0;padding:20px 0 14px}
.activity-overview>div{flex:1;display:flex;align-items:baseline;gap:12px;padding:0 16px;border-left:1px solid #e3e9f2}
.activity-overview>div:first-child{padding-left:0;border:0}.activity-overview>div:last-child{padding-right:0;flex:1.3}
.activity-overview span{font-size:12px;color:#64748c;white-space:nowrap}.activity-overview strong{font-size:25px;font-weight:750;line-height:1.2;color:#24344d;font-variant-numeric:tabular-nums}.activity-overview small{font-size:14px;color:#8390a3;font-weight:400}
.activity-capacity-note{font-size:11px;line-height:1.6;color:#8190a4;margin:0 0 8px}
.activity-panel-toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:12px 0 18px;color:#8390a3;font-size:11px}
.activity-panel-toolbar button{display:flex;align-items:center;gap:5px;border:0;background:none;color:#416bdc;cursor:pointer;font:inherit;font-size:12px}
.activity-panel-toolbar button:disabled{opacity:.5;cursor:wait}
.activity-panel-error{padding:12px 14px;background:#fff8eb;border:1px solid #f0e1c7;border-radius:6px;font-size:13px;color:#996617;margin-bottom:18px}
.activity-panel-error p{font-size:12px;line-height:1.6;margin:6px 0 0}
.activity-panel-empty{display:flex;align-items:center;flex-direction:column;text-align:center;gap:14px;color:#77869c;padding:50px 20px}.activity-panel-empty p{line-height:1.7;font-size:12px;margin:0}
.activity-panel-idle{display:flex;align-items:center;gap:12px;padding:18px 0 26px}.activity-idle-dot{width:8px;height:8px;background:#23795a;border-radius:50%}.activity-panel-idle b{font-size:14px;color:#23795a}.activity-panel-idle p{font-size:12px;color:#73877f;margin:6px 0 0}
.activity-section{margin-bottom:28px}.activity-section>header{padding-bottom:12px}.activity-section h3{font-size:15px;font-weight:700;color:#30445f;margin:0;display:flex;align-items:center;gap:8px}
.activity-section-count{font-size:12px;font-weight:500;background:#edf1f7;color:#64748c;min-width:21px;padding:2px 5px;text-align:center;border-radius:4px;font-variant-numeric:tabular-nums}
.activity-section header p{font-size:12px;line-height:1.6;margin:7px 0 0;color:#7a889c}
.activity-task-list{list-style:none;margin:0;padding:0;border-top:1px solid #e3e9f2}
.activity-task{padding:16px 0;border-bottom:1px solid #e3e9f2}.activity-task-heading{display:flex;align-items:flex-start;gap:10px}
.activity-task-identity{display:flex;align-items:center;flex-wrap:wrap;flex:1;gap:6px 8px;min-width:0}.activity-task-identity>strong{color:#24344d;font-size:16px;line-height:1.5;font-weight:750;overflow-wrap:anywhere}
.activity-mine{font-size:11px;white-space:nowrap;color:#416bdc;background:#eef3ff;padding:2px 6px;border-radius:4px}
.activity-status{font-size:12px;line-height:24px;color:#7a889c;white-space:nowrap;font-weight:550}.activity-status.activity-state-running{color:#416bdc}.activity-status.activity-state-pending{color:#996617}.activity-status.activity-state-success{color:#23795a}.activity-status.activity-state-failed{color:#ba4850}
.activity-task-meta{display:flex;align-items:center;flex-wrap:wrap;gap:6px 10px;margin-top:6px;font-size:12px;color:#718097;line-height:1.6}
.activity-task-kind{color:#5a6b84}.activity-task-kind>span{color:#8b97aa}.activity-task-actor{margin-left:auto}.activity-task-actor b{font-weight:600;color:#51637e}
.activity-mode{border-left:1px solid #d8e0ed;padding-left:10px;color:#64748c}.activity-mode.is-cloud{color:#416bdc}
.activity-environment{font-size:11px;padding:0 5px;border-radius:3px;background:#edf1f7}.activity-environment.env-prod{color:#b64c57;background:#fff1f2}.activity-environment.env-test{color:#996617;background:#fff5e6}
.activity-task-branch{display:flex;gap:9px;margin-top:7px;font-size:12px;line-height:1.6;min-width:0}.activity-task-branch>span{color:#8995a6;white-space:nowrap}.activity-task-branch>b{font-weight:500;color:#526782;overflow-wrap:anywhere;min-width:0}
.activity-task-stage,.activity-task-wait{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:9px 11px;margin-top:10px;font-size:12px;line-height:1.5;border-radius:4px;background:#f1f5ff;color:#4267a6}
.activity-task-stage>span{display:flex;align-items:center;gap:8px;min-width:0;overflow-wrap:anywhere}.activity-task-stage i{width:5px;height:5px;flex:0 0 5px;border-radius:50%;background:#416bdc}
.activity-task-stage time,.activity-task-wait time{white-space:nowrap;font-variant-numeric:tabular-nums;font-size:11px;flex-shrink:0}
.activity-task-wait{background:#fff8eb;color:#996617}.activity-task-failure{font-size:12px;line-height:1.7;padding:9px 11px;background:#fff2f2;color:#b14b53;margin:10px 0 0;border-radius:4px;overflow-wrap:anywhere}
.activity-task-foot{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-top:10px}.activity-task-foot>time{font-size:11px;color:#8a96a7;line-height:1.6}
.activity-task-actions{display:flex;justify-content:flex-end;align-items:center;gap:15px;flex-shrink:0}.activity-task-actions button{background:none;border:0;padding:3px 0;font:inherit;font-size:12px;color:#416bdc;cursor:pointer}.activity-task-actions button:focus-visible,.activity-panel-toolbar button:focus-visible{outline:2px solid #416bdc;outline-offset:3px}.activity-task-actions .activity-cancel{color:#8a7580}.activity-task-actions button:disabled{color:#aab3c2;cursor:default}
.activity-hidden-note,.activity-no-results{font-size:12px;color:#8a96a7;line-height:1.7;margin:8px 0 0}.activity-no-results{padding:12px 0}.activity-task.is-hidden .activity-task-identity>strong{color:#75849a;font-weight:550}
@media(max-width:1260px){.activity-trigger{padding-left:24px;padding-right:24px}.activity-open-label{display:none}.activity-bar-main{gap:10px}.activity-bar-main>strong{max-width:180px}.activity-bar-end{gap:10px}}
@media(max-width:900px){.activity-bar-main{flex-wrap:wrap;gap:3px 10px}.activity-bar-main>strong{max-width:225px}.activity-idle-detail{display:none}}
@media(max-width:720px){.global-task-activity{top:60px}.activity-trigger{padding:10px 16px;gap:10px}.activity-bar-main>b,.activity-bar-main>strong,.activity-bar-actor{font-size:12px}.activity-bar-main>strong{max-width:170px}.activity-bar-sub{gap:8px;font-size:11px;flex-wrap:wrap}.activity-bar-end{gap:7px}.activity-cloud{display:none}.activity-pending{padding:5px 8px;font-size:11px}.activity-elapsed{display:none}}
@media(max-width:480px){.activity-overview>div{gap:7px;padding:0 10px}.activity-overview strong{font-size:23px}.activity-overview span{font-size:11px}.activity-task-identity>strong{font-size:15px}.activity-task-actor{margin-left:0}.activity-task-foot{align-items:flex-end}.activity-task-actions{gap:12px}.activity-task-stage,.activity-task-wait{flex-wrap:wrap;gap:4px}.activity-panel-heading h2{font-size:20px}}
</style>
