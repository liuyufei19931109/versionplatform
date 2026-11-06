<script setup lang="ts">
import { tUi } from '../i18n'

import CommitHash from '../components/CommitHash.vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Clock, Connection, Document, MoreFilled, Refresh, RefreshRight, Search, Warning } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorText, http, payload } from '../api'
import { hasMenu } from '../auth'
import { ownsTask, cancellationLabel, cancellationPrompt } from '../taskCancellation'
import ServiceIdentity from '../components/ServiceIdentity.vue'
import RuntimeRestartDialog from '../components/RuntimeRestartDialog.vue'
import RuntimeOperationDetail from '../components/RuntimeOperationDetail.vue'
import { bytesLabel, imageLabel, operationStage, operationStatus, runtimeStatus, usableUrls } from '../runtime'
import type { RuntimeContainer, RuntimeOperation, RuntimeRow, RuntimeSnapshot } from '../runtime'
import { environmentLabel, formatTime } from '../ui'
import { validEnvironment, validServiceId } from '../releaseHistory'

const router = useRouter(), route = useRoute()
const linkedOperationId = computed(() => {
  const value = route.query.operationId
  return typeof value === 'string' && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value)) ? value : undefined
})
function closeLinkedOperation() { const query = { ...route.query }; delete query.operationId; void router.replace({ path: route.path, query }) }
const snapshot = ref<RuntimeSnapshot>({ rows: [], checking: false, serverTime: '' })
const loading = ref(false), submitting = ref(''), error = ref(''), environment = ref(validEnvironment(route.query.environment) || 'all'), search = ref(''), selectedKey = ref('')
const serviceId = ref(validServiceId(route.query.serviceId))
const serviceOptions = computed(() => [...new Map(snapshot.value.rows.map(row => [String(row.serviceId), { id: String(row.serviceId), name: row.serviceName }])).values()])
const now = ref(Date.now())
let poll: number | undefined, clock: number | undefined, disposed = false, offset = 0, inFlight = false
const environments = [{ key: 'all', get label() { return tUi('全部环境') } }, { key: 'dev', get label() { return tUi('开发') } }, { key: 'test', get label() { return tUi('预生产') } }, { key: 'prod', get label() { return tUi('生产') } }]
const filtered = computed(() => snapshot.value.rows.filter(row => (environment.value === 'all' || row.environmentName === environment.value) && (!serviceId.value || String(row.serviceId) === serviceId.value) && `${row.serviceName} ${row.serviceKey} ${row.serverName} ${row.host}`.toLowerCase().includes(search.value.trim().toLowerCase())))
const selected = computed(() => snapshot.value.rows.find(row => row.key === selectedKey.value))
function openLogs(row: RuntimeRow) { void router.push({ path: '/runtime/logs', query: { environmentId: String(row.environmentId), serverId: String(row.serverId) } }) }
const drawerOpen = computed({ get: () => Boolean(selected.value), set: value => { if (!value) selectedKey.value = '' } })
const serviceCount = computed(() => new Set(snapshot.value.rows.map(row => row.serviceId)).size)
const boundCount = computed(() => snapshot.value.rows.filter(row => row.serverId !== null).length)
const checkedCount = computed(() => snapshot.value.rows.filter(row => row.checkState === 'READY' && !isStale(row)).length)
const attentionCount = computed(() => snapshot.value.rows.filter(row => row.checkState === 'ERROR' || (!isStale(row) && row.checkState === 'READY' && (row.comparison === 'DIFFERENT' || ['danger', 'warning'].includes(runtimeStatus(row, false).tone)))).length)
const checkingCount = computed(() => snapshot.value.rows.filter(row => row.checkState === 'CHECKING').length)
const eligibleCount = computed(() => snapshot.value.rows.filter(canCheck).length)
const restartDialog = ref(false), restartRow = ref<RuntimeRow | null>(null)
const activeOperations = ref<RuntimeOperation[]>([]), operationHistory = ref<RuntimeOperation[]>([])
const historyLoading = ref(false), historyError = ref(''), cancelling = ref<number|null>(null), cancelRequested = ref(new Set<number>())
let historyRequest = 0

function activeOperation(row: RuntimeRow) { return activeOperations.value.find(operation => operation.environmentId === row.environmentId && operation.serverId === row.serverId) }
function canRestart(row: RuntimeRow) { return hasMenu('release') && row.enabled && row.serverId !== null && row.checkState === 'READY' && !isStale(row) && row.containers.length > 0 && row.containers.every(container => ['running','exited','created'].includes(container.state) && Boolean(container.imageId)) && !activeOperation(row) }
function openRestart(row: RuntimeRow) { if (!canRestart(row)) return; restartRow.value = row; restartDialog.value = true }
function runtimeCommand(row: RuntimeRow,command: unknown) { if (command === 'restart') openRestart(row) }
function operationTone(status: RuntimeOperation['status']) { return status === 'SUCCESS' ? 'success' : status === 'FAILED' ? 'danger' : status === 'RUNNING' ? 'primary' : 'info' }
function canCancel(operation?: RuntimeOperation) { return Boolean(operation && hasMenu('release') && ownsTask(operation) && ['PENDING','RUNNING'].includes(operation.status)) }
async function cancelRestart(operation?: RuntimeOperation) {
  if (!operation || !canCancel(operation) || cancelling.value || cancelRequested.value.has(operation.id)) return
  cancelling.value=operation.id
  try { await ElMessageBox.confirm(cancellationPrompt(operation.status), tUi('{0} · 重启 #{1}', cancellationLabel(operation.status), operation.id), {confirmButtonText:cancellationLabel(operation.status),cancelButtonText:tUi('继续执行'),type:'warning'}) }
  catch {cancelling.value=null;return}
  try {const result=payload<{message?:string}>(await http.post(`/runtime/restarts/${operation.id}/cancel`),{});cancelRequested.value.add(operation.id);ElMessage.success(result.message ? tUi(result.message) : tUi('中止请求已提交，正在等待状态更新'));await load(true);await loadHistory()}
  catch(reason){ElMessage.error(errorText(reason))}
  finally {cancelling.value=null}
}
async function loadHistory() {
  const row = selected.value, current = ++historyRequest
  if (!row?.serverId) { operationHistory.value = []; historyError.value = ''; historyLoading.value = false; return }
  historyLoading.value = true
  try {
    const data = payload<RuntimeOperation[]>(await http.get('/runtime/restarts',{params:{environmentId:row.environmentId,serverId:row.serverId}}),[])
    if (!disposed && current === historyRequest) { operationHistory.value = data; historyError.value = '' }
  } catch (reason) { if (!disposed && current === historyRequest) historyError.value = errorText(reason,tUi('无法读取操作记录')) }
  finally { if (!disposed && current === historyRequest) historyLoading.value = false }
}
function restartSubmitted(operation: RuntimeOperation) {
  if (!activeOperations.value.some(value => value.id === operation.id)) activeOperations.value.push(operation)
  selectedKey.value = `${operation.environmentId}:${operation.serverId}`
  loadHistory(); load(true); schedule()
}

function isStale(row: RuntimeRow) {
  const checked = row.checkedAt ? new Date(row.checkedAt).getTime() : NaN
  return row.stale || (Number.isFinite(checked) && now.value + offset - checked > 5 * 60 * 1000)
}
function status(row: RuntimeRow) { return runtimeStatus(row, isStale(row)) }
function canCheck(row: RuntimeRow) { return row.enabled && row.serverId !== null && !['NOT_CONFIGURED', 'DISABLED', 'CHECKING'].includes(row.checkState) }
function checkedLabel(row: RuntimeRow) {
  if (!row.checkedAt) return tUi('尚未检查')
  const seconds = Math.max(0, Math.floor((now.value + offset - new Date(row.checkedAt).getTime()) / 1000))
  if (!Number.isFinite(seconds)) return formatTime(row.checkedAt)
  if (seconds < 60) return tUi('刚刚检查')
  if (seconds < 3600) return tUi('{0} 分钟前检查', Math.floor(seconds / 60))
  if (seconds < 86400) return tUi('{0} 小时前检查', Math.floor(seconds / 3600))
  return formatTime(row.checkedAt)
}
function actualImage(row: RuntimeRow) {
  const images = [...new Set(row.containers.map(container => container.imageReference).filter(Boolean))]
  return images.length > 1 ? tUi('{0} 个不同镜像', images.length) : imageLabel(images[0])
}
function comparisonLabel(row: RuntimeRow) {
  if (row.comparison === 'DIFFERENT') return tUi('与发布记录不一致')
  if (row.comparison === 'MATCH') return row.comparisonBasis === 'DIGEST' ? tUi('镜像摘要一致') : tUi('镜像标签一致')
  return row.expected ? tUi('暂无法比较') : tUi('暂无成功发布记录')
}
function containerState(container: RuntimeContainer) { return ({ running: tUi('运行中'), exited: tUi('已退出'), created: tUi('已创建'), restarting: tUi('重启中'), paused: tUi('已暂停'), dead: tUi('已终止'), removing: tUi('删除中') } as Record<string, string>)[container.state] || container.state || tUi('未知') }
function containerHealth(container: RuntimeContainer) { return ({ healthy: tUi('健康'), unhealthy: tUi('异常'), starting: tUi('检查中'), none: tUi('未配置'), '': tUi('未配置') } as Record<string, string>)[container.health || ''] || tUi('未获取') }
function accept(data: RuntimeSnapshot) {
  snapshot.value = data
  const serverTime = new Date(data.serverTime).getTime()
  if (Number.isFinite(serverTime)) offset = serverTime - Date.now()
  now.value = Date.now()
}
function schedule() {
  if (poll) window.clearTimeout(poll)
  if (!disposed && (snapshot.value.checking || activeOperations.value.length) && !error.value) poll = window.setTimeout(() => load(true), 2000)
}
async function load(silent = false) {
  if (inFlight || disposed) return
  inFlight = true; if (!silent) loading.value = true
  try {
    const [runtimeResponse,operationResponse] = await Promise.all([http.get('/runtime'),http.get('/runtime/restarts/active')])
    const data = payload<RuntimeSnapshot>(runtimeResponse, { rows: [], checking: false, serverTime: '' })
    if (disposed) return
    if (!Array.isArray(data.rows)) throw new Error(tUi('运行数据格式异常，请稍后刷新'))
    const active = payload<RuntimeOperation[]>(operationResponse,[])
    const finished = activeOperations.value.filter(operation => !active.some(value => value.id === operation.id))
    activeOperations.value = active
    cancelRequested.value=new Set([...cancelRequested.value].filter(id=>active.some(operation=>operation.id===id)))
    accept(data); error.value = ''
    for (const operation of finished) {
      try {
        const refreshed = payload<RuntimeSnapshot>(await http.post('/runtime/check',{environmentId:operation.environmentId,serverId:operation.serverId}),data)
        if (!disposed) accept(refreshed)
      } catch { /* Operation history retains the outcome even if refreshing the snapshot fails. */ }
    }
    if (selected.value) await loadHistory()
  } catch (reason) { if (!disposed) error.value = errorText(reason, tUi('无法读取运行状态，请刷新重试')) }
  finally { inFlight = false; loading.value = false; schedule() }
}
async function check(row?: RuntimeRow) {
  if (submitting.value || inFlight) return
  submitting.value = row?.key || 'all'
  if (poll) window.clearTimeout(poll)
  try {
    const data = payload<RuntimeSnapshot>(await http.post('/runtime/check', row ? { environmentId: row.environmentId, serverId: row.serverId } : {}), { rows: [], checking: false, serverTime: '' })
    if (disposed) return
    accept(data); error.value = ''
    if (!data.checking) ElMessage.info(tUi('没有新增检查任务，请查看节点提示或稍后重试'))
  } catch (reason) { if (!disposed) { error.value = errorText(reason, tUi('无法发起检查，请稍后重试')); ElMessage.error(error.value) } }
  finally { submitting.value = ''; schedule() }
}
watch(() => [route.query.environment, route.query.serviceId], () => { environment.value = validEnvironment(route.query.environment) || 'all'; serviceId.value = validServiceId(route.query.serviceId); selectedKey.value = '' })
watch(selectedKey, () => { operationHistory.value = []; loadHistory() })
onMounted(() => { load(); clock = window.setInterval(() => { now.value = Date.now() }, 15000) })
onBeforeUnmount(() => { disposed = true; if (poll) window.clearTimeout(poll); if (clock) window.clearInterval(clock) })
</script>

<template>
  <div class="runtime-page">
    <RuntimeOperationDetail :operation-id="linkedOperationId" @close="closeLinkedOperation"/>
    <div class="page-head runtime-head"><div><h2>{{ tUi('运行总览') }}</h2><p>{{ tUi('查看每个环境实际运行的镜像、容器健康与最近发布版本。') }}</p></div><div class="actions"><el-button :icon="Refresh" :loading="loading" :disabled="Boolean(submitting)" @click="load()">{{ tUi('刷新结果') }}</el-button><el-button type="primary" :icon="Connection" :loading="submitting === 'all'" :disabled="snapshot.checking || !eligibleCount || Boolean(submitting) || loading" @click="check()">{{ tUi('检查全部') }}</el-button></div></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="runtime-error"><template #default>{{ tUi('当前页面可能保留上次读取的结果，点击“刷新结果”重试。') }}</template></el-alert>
    <div class="runtime-metrics">
      <div><span>{{ tUi('管理服务') }}</span><b>{{serviceCount}}<small>{{ tUi('个') }}</small></b></div>
      <div><span>{{ tUi('已绑定节点') }}</span><b>{{boundCount}}<small>{{ tUi('个服务 / 环境组合') }}</small></b></div>
      <div><span>{{ tUi('有效检查结果') }}</span><b>{{checkedCount}}<small>{{ tUi('条') }}</small></b></div>
      <div :class="{'needs-attention':attentionCount > 0}"><span>{{ tUi('需要关注') }}</span><b>{{attentionCount}}<small>{{ tUi('条') }}</small></b></div>
    </div>
    <div class="runtime-notice" :class="{'is-checking':snapshot.checking}"><el-icon><Clock v-if="snapshot.checking"/><Connection v-else/></el-icon><span v-if="snapshot.checking">{{ tUi('正在依次检查 {0} 个服务节点，完成后自动更新结果。', checkingCount) }}</span><span v-else>{{ tUi('按需读取容器状态，检查结果 5 分钟内有效。点击检查不会发布服务或修改目标配置。') }}</span></div>
    <section class="runtime-panel" v-loading="loading && !snapshot.rows.length">
      <div class="runtime-toolbar"><div class="environment-tabs" role="group" :aria-label="tUi('筛选环境')"><button v-for="item in environments" :key="item.key" :class="{active:environment === item.key}" :aria-pressed="environment === item.key" @click="environment = item.key">{{item.label}}<span>{{snapshot.rows.filter(row => item.key === 'all' || row.environmentName === item.key).length}}</span></button></div><el-select v-model="serviceId" clearable :placeholder="tUi('全部服务')" :aria-label="tUi('筛选服务')" style="width:190px;flex-shrink:0"><el-option v-for="service in serviceOptions" :key="service.id" :label="service.name" :value="service.id"/></el-select><el-input v-model="search" :prefix-icon="Search" clearable :placeholder="tUi('搜索服务或节点')" :aria-label="tUi('搜索服务或节点')" class="runtime-search"/></div>
      <div class="runtime-table-head" aria-hidden="true"><span>{{ tUi('服务 / 环境节点') }}</span><span>{{ tUi('实际运行状态') }}</span><span>{{ tUi('版本对照') }}</span><span>{{ tUi('最近检查') }}</span></div>
      <article v-for="row in filtered" :key="row.key" class="runtime-row" :class="{'runtime-row--attention':row.checkState === 'ERROR' || (!isStale(row) && row.comparison === 'DIFFERENT'), 'runtime-row--inactive':['NOT_CONFIGURED','DISABLED'].includes(row.checkState)}">
        <div class="runtime-service"><ServiceIdentity :service="row"/><div class="runtime-node"><span class="environment-chip" :class="`environment-chip--${row.environmentName}`">{{environmentLabel(row.environmentName)}}</span><span :title="[row.serverName, row.host].filter(Boolean).join(' · ')">{{row.serverId === null ? tUi('未绑定节点') : row.serverName || row.host}}</span></div></div>
        <div class="runtime-state"><el-tag :type="status(row).tone" effect="light" round>{{status(row).label}}</el-tag><small>{{status(row).detail}}</small><small v-if="row.containers.length > 1">{{ tUi('共 {0} 个容器', row.containers.length) }}</small></div>
        <div class="runtime-versions"><div><span>{{ tUi('平台发布') }}</span><b :title="row.expected?.imageReference || ''">{{row.expected ? imageLabel(row.expected.imageReference) : tUi('暂无成功发布记录')}}</b></div><div><span>{{ tUi('实际镜像') }}</span><b :title="row.containers.map(c => c.imageReference).join('\n')">{{row.containers.length ? actualImage(row) : row.checkState === 'READY' ? tUi('未发现容器') : tUi('尚未获取')}}</b></div><span v-if="row.checkState === 'READY' && !isStale(row) && row.expected" class="comparison-note" :class="{'is-different':row.comparison === 'DIFFERENT'}"><el-icon v-if="row.comparison === 'DIFFERENT'"><Warning/></el-icon>{{comparisonLabel(row)}}</span><span v-else-if="isStale(row) && row.containers.length" class="comparison-note">{{ tUi('以上为上次检查结果') }}</span></div>
        <div class="runtime-actions"><span class="runtime-check-time" :title="formatTime(row.checkedAt)">{{checkedLabel(row)}}</span><div><el-button link type="primary" :disabled="!canCheck(row) || Boolean(submitting) || loading || Boolean(activeOperation(row))" :loading="submitting === row.key" @click="check(row)">{{ tUi('检查') }}</el-button><el-button v-if="row.serverId" link @click="openLogs(row)">{{ tUi('日志') }}</el-button><el-button link @click="selectedKey = row.key">{{ tUi('详情') }}<el-icon><ArrowRight/></el-icon></el-button><el-dropdown v-if="hasMenu('release')" trigger="click" @command="runtimeCommand(row, $event)"><el-button link :icon="MoreFilled" :aria-label="tUi('{0}更多操作', row.serviceName)"/><template #dropdown><el-dropdown-menu><el-dropdown-item command="restart" :disabled="!canRestart(row)" :icon="RefreshRight">{{ tUi('更新配置并重启') }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></div>
        <div v-if="row.message" class="runtime-row-message" :class="{'is-error':row.checkState === 'ERROR'}">{{row.message}}</div>
        <div v-if="activeOperation(row)" class="runtime-operation-progress"><el-icon><Clock/></el-icon><span>{{ tUi('重启 #{0} · {1}', activeOperation(row)?.id, operationStage(activeOperation(row)?.currentStage || 'WAITING')) }}</span><el-button v-if="canCancel(activeOperation(row))" link type="danger" :loading="cancelling === activeOperation(row)?.id" :disabled="cancelRequested.has(activeOperation(row)!.id)" @click="cancelRestart(activeOperation(row))">{{cancelRequested.has(activeOperation(row)!.id)?tUi('已请求中止'):cancellationLabel(activeOperation(row)?.status)}}</el-button><el-button link type="primary" @click="selectedKey = row.key">{{ tUi('查看记录') }}</el-button></div>
      </article>
      <div v-if="!filtered.length && !loading" class="runtime-empty"><el-icon><Connection/></el-icon><b>{{snapshot.rows.length ? tUi('没有匹配的服务') : error ? tUi('暂时无法读取服务') : tUi('还没有配置服务环境')}}</b><p>{{snapshot.rows.length ? tUi('调整环境筛选或搜索内容后重试。') : error ? tUi('请刷新结果后重试。') : tUi('先在服务管理中添加服务并绑定环境节点。')}}</p><el-button v-if="!snapshot.rows.length && hasMenu('services') && !error" @click="router.push('/services')">{{ tUi('前往服务管理') }}</el-button></div>
      <div class="runtime-table-foot"><span>{{ tUi('共 {0} 个服务节点', filtered.length) }}</span><span>{{ tUi('检查记录反映采集时刻，健康状态来自 Docker。') }}</span></div>
    </section>

    <el-drawer v-model="drawerOpen" :size="'min(680px, 100vw)'" :title="tUi('运行详情')" class="runtime-drawer" destroy-on-close>
      <template v-if="selected">
        <ServiceIdentity :service="selected" :large="true"/>
        <div class="runtime-detail-node"><span class="environment-chip" :class="`environment-chip--${selected.environmentName}`">{{environmentLabel(selected.environmentName)}}</span><span>{{selected.serverName || tUi('未绑定节点')}}</span><code v-if="selected.host">{{selected.host}}</code></div>
        <div class="runtime-detail-status"><el-tag :type="status(selected).tone" round>{{status(selected).label}}</el-tag><span>{{checkedLabel(selected)}}</span><el-button link type="primary" :disabled="!canCheck(selected) || Boolean(submitting) || Boolean(activeOperation(selected))" @click="check(selected)">{{ tUi('重新检查') }}</el-button></div>
        <div v-if="selected.serverId" class="runtime-restart-action"><el-button :icon="Document" @click="openLogs(selected)">{{ tUi('查看服务日志') }}</el-button><span>{{ tUi('实时跟踪或搜索该服务的应用日志，也可查看容器标准输出。') }}</span></div>
        <div v-if="hasMenu('release')" class="runtime-restart-action"><el-button :icon="RefreshRight" :disabled="!canRestart(selected)" @click="openRestart(selected)">{{ tUi('更新配置并重启') }}</el-button><span>{{activeOperation(selected)?operationStage(activeOperation(selected)?.currentStage || 'WAITING'):tUi('加载服务器最新配置，保留当前镜像版本，完成后检查运行状态。')}}</span></div>
        <el-alert v-if="selected.message" :title="selected.message" :type="selected.checkState === 'ERROR' ? 'error' : 'info'" :closable="false" show-icon class="runtime-detail-message"/>
        <el-alert v-if="isStale(selected) && selected.containers.length" :title="tUi('以下容器数据已过期，请重新检查后判断当前运行情况。')" type="warning" :closable="false" show-icon class="runtime-detail-message"/>
        <section class="runtime-detail-section"><h3>{{ tUi('平台最近成功发布') }}</h3><template v-if="selected.expected"><dl class="runtime-facts"><dt>{{ tUi('制品镜像') }}</dt><dd><code>{{selected.expected.imageReference || tUi('未记录')}}</code></dd><dt>{{ tUi('代码版本') }}</dt><dd><CommitHash :value="selected.expected.commitId"/><small>{{ tUi('来自该次构建记录，并非从运行容器读取。') }}</small></dd><dt>{{ tUi('发布完成') }}</dt><dd>{{formatTime(selected.expected.deployedAt)}} <el-button v-if="hasMenu('release') || hasMenu('releases')" link type="primary" @click="router.push(`/releases/${selected.expected.taskId}`)">#{{selected.expected.taskId}}</el-button></dd><dt>{{ tUi('镜像对照') }}</dt><dd>{{isStale(selected) || selected.checkState !== 'READY' ? tUi('请取得有效检查结果后比较') : comparisonLabel(selected)}}<small v-if="selected.comparison === 'MATCH' && selected.comparisonBasis !== 'DIGEST'">{{ tUi('仅镜像标签相同；同名标签可能被重新推送，不代表镜像内容已验证。') }}</small></dd></dl></template><p v-else class="runtime-muted">{{ tUi('这个节点还没有平台记录的成功发布；实际容器仍可独立检查。') }}</p></section>
        <section class="runtime-detail-section runtime-operation-history"><div class="runtime-operation-heading"><h3>{{ tUi('操作记录') }} <span>{{ tUi('最近 50 条') }}</span></h3><el-button link :loading="historyLoading" @click="loadHistory">{{ tUi('刷新') }}</el-button></div><el-alert v-if="historyError" :title="historyError" type="error" :closable="false"/><p v-else-if="!operationHistory.length" class="runtime-muted">{{historyLoading?tUi('正在读取操作记录…'):tUi('这个节点还没有重启记录。')}}</p><article v-for="operation in operationHistory" :key="operation.id" class="runtime-operation-item"><div><b>{{ tUi('重启 #{0}', operation.id) }}</b><el-tag size="small" :type="operationTone(operation.status)">{{operationStatus(operation.status)}}</el-tag></div><p>{{operation.createdBy}} · {{formatTime(operation.createdAt)}}</p><p v-if="operation.status === 'RUNNING' || operation.status === 'PENDING'">{{operationStage(operation.currentStage)}}</p><p v-if="cancelRequested.has(operation.id) && ['PENDING','RUNNING'].includes(operation.status)" class="runtime-operation-error">{{ tUi('已请求中止，等待当前命令退出；请在结束后检查运行状态。') }}</p><el-button v-if="canCancel(operation)" link type="danger" :loading="cancelling === operation.id" :disabled="cancelRequested.has(operation.id)" @click="cancelRestart(operation)">{{cancelRequested.has(operation.id)?tUi('已请求中止'):cancellationLabel(operation.status)}}</el-button><p v-if="operation.status === 'CANCELLED'" class="runtime-operation-error">{{ tUi('操作已取消；已执行的远端操作不会自动撤销，请检查运行状态。') }}</p><p v-if="operation.errorMessage" class="runtime-operation-error">{{operation.errorMessage}}</p><p v-if="operation.finishedAt">{{ tUi('完成于 {0}', formatTime(operation.finishedAt)) }}</p><el-collapse><el-collapse-item :title="tUi('查看步骤日志')" :name="operation.id"><pre>{{operation.logText || tUi('暂无日志')}}</pre></el-collapse-item></el-collapse></article></section>
        <section class="runtime-detail-section"><h3>{{ tUi('端口映射地址') }}</h3><div v-if="usableUrls(selected.accessUrls).length" class="runtime-access"><a v-for="url in usableUrls(selected.accessUrls)" :key="url" :href="url" target="_blank" rel="noopener noreferrer">{{url}}<el-icon><ArrowRight/></el-icon></a><p>{{ tUi('根据容器端口映射生成，未验证公网、防火墙及页面可用性。') }}</p></div><p v-else class="runtime-muted">{{selected.containers.length ? tUi('未发现可用的访问地址，服务可能仅供内部网络访问。') : tUi('检查容器后展示可识别的端口映射地址。')}}</p></section>
        <section class="runtime-detail-section"><h3>{{ tUi('实际容器') }} <span>{{selected.containers.length}}</span></h3><div v-if="!selected.containers.length" class="runtime-muted">{{selected.checkState === 'READY' ? tUi('目标配置下未发现容器。') : tUi('暂无容器数据。')}}</div><article v-for="container in selected.containers" :key="container.id" class="runtime-container"><div class="runtime-container-head"><b>{{container.name}}</b><span>{{containerState(container)}} · {{containerHealth(container)}}</span></div><dl class="runtime-facts"><dt>{{ tUi('运行镜像') }}</dt><dd><code>{{container.imageReference}}</code></dd><dt>{{ tUi('镜像 ID') }}</dt><dd><code>{{container.imageId || tUi('未获取')}}</code></dd><dt v-if="container.repoDigests?.length">{{ tUi('镜像摘要') }}</dt><dd v-if="container.repoDigests?.length"><code v-for="digest in container.repoDigests" :key="digest">{{digest}}</code></dd><dt>{{ tUi('容器内存') }}</dt><dd><strong>{{bytesLabel(container.memoryUsageBytes)}}</strong><span class="runtime-memory-divider"> / </span>{{bytesLabel(container.memoryLimitBytes, true)}}<small>{{ tUi('当前用量 / 容器限制。容器内存包含 Java 堆外内存及其他进程。') }}</small></dd><dt>{{ tUi('端口映射') }}</dt><dd><template v-if="container.ports?.length"><code v-for="(port, index) in container.ports" :key="index">{{port.hostIp || tUi('默认接口')}}:{{port.hostPort}} → {{port.containerPort}}/{{port.protocol}}</code></template><span v-else>{{ tUi('无宿主机端口映射') }}</span></dd></dl><div v-if="selected.serviceType === 'BACKEND' || container.javaOptions?.length" class="runtime-java"><h4>{{ tUi('Java 启动配置') }}</h4><div v-if="container.javaOptions?.length" class="runtime-java-options"><code v-for="option in container.javaOptions" :key="option">{{option}}</code></div><p v-else>{{ tUi('未获取到 Java 启动参数。') }}</p><small v-if="container.javaOptionsSource">{{ tUi('来源：{0}', container.javaOptionsSource) }}</small><small>{{ tUi('仅展示读取到的启动配置，不是构建日志中的 JAVA_TOOL_OPTIONS，也不是实测最大堆；未显式设置 Xmx 时，JVM 可能按容器内存自动计算。') }}</small></div></article></section>
      </template>
    </el-drawer>
    <RuntimeRestartDialog v-model="restartDialog" :row="restartRow" @submitted="restartSubmitted" @uncertain="load(true)"/>
  </div>
</template>

<style scoped>
.runtime-operation-progress{grid-column:1/-1;display:flex;align-items:center;gap:8px;border-radius:7px;padding:9px 12px;background:#edf3ff;color:#5071a8;font-size:11px}.runtime-operation-progress .el-button{font-size:11px}.runtime-operation-progress>span{margin-right:auto}.runtime-restart-action{display:flex;align-items:center;gap:13px;margin:0 0 20px}.runtime-restart-action span{font-size:11px;line-height:1.7;color:#8b98ab}.runtime-operation-heading{display:flex;align-items:baseline;justify-content:space-between;gap:12px}.runtime-operation-heading .el-button{font-size:11px}.runtime-operation-item{padding:15px 17px;border:1px solid #e5eaf1;border-radius:9px;background:#fafcfe;margin-top:12px}.runtime-operation-item>div:first-child{display:flex;align-items:center;justify-content:space-between;gap:12px}.runtime-operation-item b{font-size:12px;font-weight:500;color:#526985}.runtime-operation-item p{font-size:11px;color:#8795a8;line-height:1.7;margin:9px 0}.runtime-operation-item .runtime-operation-error{color:#b3695f}.runtime-operation-item pre{white-space:pre-wrap;overflow-wrap:anywhere;margin:0;font:11px/1.9 ui-monospace,Consolas,monospace;color:#6a7f9c}.runtime-operation-item :deep(.el-collapse){border:0}.runtime-operation-item :deep(.el-collapse-item__header){background:transparent;border:0;height:32px;font-size:11px;color:#6582aa}.runtime-operation-item :deep(.el-collapse-item__wrap){background:transparent;border:0}.runtime-operation-item :deep(.el-collapse-item__content){padding:8px 0 0}
.runtime-page{--runtime-muted:#7e8a9d;--runtime-border:#e5eaf1;color:#34445d}.runtime-head{margin-bottom:24px}.runtime-head p{max-width:620px}.runtime-error{margin-bottom:20px}.runtime-metrics{display:grid;grid-template-columns:repeat(4,1fr);margin-bottom:18px;border:1px solid var(--runtime-border);border-radius:13px;background:#fff;overflow:hidden}.runtime-metrics>div{padding:19px 25px;position:relative}.runtime-metrics>div+div:before{content:'';position:absolute;left:0;top:22%;height:56%;width:1px;background:#e9edf3}.runtime-metrics span{display:block;font-size:12px;color:#718099}.runtime-metrics b{display:block;font-size:28px;font-weight:620;line-height:1.4;margin-top:7px;color:#2d405f}.runtime-metrics small{font-weight:400;font-size:11px;color:#8793a6;margin-left:9px}.runtime-metrics .needs-attention b{color:#bf743d}.runtime-notice{display:flex;align-items:center;gap:9px;color:#7f8da2;font-size:12px;line-height:1.6;margin:0 1px 17px}.runtime-notice>.el-icon{color:#6683b3;font-size:16px;flex:none}.runtime-notice.is-checking{color:#4a71bd}.runtime-panel{background:#fff;border:1px solid var(--runtime-border);border-radius:14px;overflow:hidden;box-shadow:0 6px 25px #273d5d03}.runtime-toolbar{padding:20px 23px;display:flex;align-items:center;justify-content:space-between;gap:20px}.environment-tabs{display:flex;gap:4px;padding:4px;border-radius:9px;background:#f4f6fa}.environment-tabs button{background:none;border:0;border-radius:6px;padding:8px 13px;color:#7d8ca2;font:inherit;font-size:12px;cursor:pointer;white-space:nowrap}.environment-tabs button.active{color:#355da8;background:#fff;box-shadow:0 1px 5px #18325c0c;font-weight:600}.environment-tabs button span{margin-left:7px;font-size:10px;opacity:.75}.environment-tabs button:focus-visible{outline:2px solid #668ae3;outline-offset:2px}.runtime-search{max-width:235px}.runtime-table-head,.runtime-row{display:grid;grid-template-columns:minmax(220px,1.35fr) minmax(140px,.85fr) minmax(220px,1.45fr) 136px;gap:18px}.runtime-table-head{padding:13px 25px;background:#f8fafc;border-top:1px solid #f1f3f7;border-bottom:1px solid var(--runtime-border);color:#8a97aa;font-size:11px}.runtime-row{padding:23px 25px;border-bottom:1px solid #eaf0f6;align-items:start}.runtime-row:last-of-type{border-bottom:0}.runtime-row--attention{background:#fffdf9}.runtime-row--inactive{background:#fdfefe}.runtime-node{display:flex;align-items:center;gap:9px;margin-top:13px;margin-left:44px;font-size:11px;color:#8792a3;min-width:0}.runtime-node>span:last-child{white-space:nowrap;text-overflow:ellipsis;overflow:hidden}.environment-chip{display:inline-block;flex-shrink:0;border-radius:4px;background:#f0f3f8;color:#6c7f9e;padding:2px 6px;font-size:10px;line-height:1.6}.environment-chip--test{background:#f1effa;color:#8471a9}.environment-chip--prod{background:#fff3e8;color:#ad7c4d}.runtime-state{display:flex;flex-direction:column;gap:9px;align-items:flex-start}.runtime-state :deep(.el-tag){font-size:11px;--el-tag-border-color:transparent;padding:0 10px}.runtime-state>small{font-size:10px;line-height:1.5;color:#8a95a6}.runtime-versions{min-width:0;padding-top:2px}.runtime-versions>div{display:flex;align-items:baseline;gap:12px;min-width:0;line-height:1.6;margin-bottom:6px}.runtime-versions>div>span{color:#929dad;font-size:10px;flex:none;width:42px}.runtime-versions b{font:11px/1.6 ui-monospace,"Cascadia Code",Consolas,monospace;color:#5d6d86;font-weight:400;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.comparison-note{display:flex;align-items:center;gap:5px;color:#8995a7;font-size:10px;line-height:1.6;margin:8px 0 0 54px}.comparison-note.is-different{color:#b87b40}.runtime-actions{display:flex;flex-direction:column;align-items:flex-end;gap:11px}.runtime-check-time{font-size:10px;line-height:2;color:#98a3b3}.runtime-actions>div{display:flex;align-items:center;gap:12px}.runtime-actions :deep(.el-button){margin:0;font-size:11px;font-weight:400}.runtime-actions :deep(.el-button:not(.el-button--primary)){color:#78879e}.runtime-actions .el-icon{margin-left:3px;font-size:11px}.runtime-row-message{grid-column:1/-1;font-size:11px;color:#8894a5;line-height:1.7;overflow-wrap:anywhere;margin-top:-3px;padding-left:44px}.runtime-row-message.is-error{color:#b46c62}.runtime-table-foot{display:flex;justify-content:space-between;gap:15px;padding:15px 25px;border-top:1px solid var(--runtime-border);font-size:10px;color:#9ba6b6}.runtime-empty{min-height:260px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:35px;color:#8a97aa}.runtime-empty>.el-icon{font-size:28px;margin-bottom:15px;color:#aab9cf}.runtime-empty>b{font-size:14px;font-weight:500;color:#667993}.runtime-empty>p{font-size:12px;margin:11px 0 18px}.runtime-detail-node{display:flex;gap:9px;align-items:center;flex-wrap:wrap;margin:18px 0;color:#7f8da2;font-size:12px}.runtime-detail-node code{font-size:11px;color:#96a1b1}.runtime-detail-status{display:flex;align-items:center;gap:12px;margin-bottom:20px}.runtime-detail-status>span:not(.el-tag){font-size:11px;color:#929dad}.runtime-detail-status .el-button{margin-left:auto}.runtime-detail-message{margin-bottom:14px}.runtime-detail-section{border-top:1px solid var(--runtime-border);padding:23px 0 0;margin-top:23px}.runtime-detail-section h3{font-size:14px;font-weight:600;margin:0 0 20px;color:#3d506b}.runtime-detail-section h3>span{font-size:11px;font-weight:400;margin-left:5px;color:#8a97aa}.runtime-facts{display:grid;grid-template-columns:76px minmax(0,1fr);gap:14px 12px;margin:0;font-size:12px;line-height:1.7}.runtime-facts dt{color:#8a96a7}.runtime-facts dd{margin:0;color:#5a6c86;overflow-wrap:anywhere;min-width:0}.runtime-facts dd>code{display:block;font-size:11px;font-family:ui-monospace,"Cascadia Code",Consolas,monospace}.runtime-facts dd>small{display:block;margin-top:5px;font-size:10px;line-height:1.7;color:#9ba6b5}.runtime-facts dd .el-button{margin-left:8px;font-size:11px}.runtime-muted{font-size:12px;line-height:1.8;color:#91a0b3;margin:0}.runtime-access{display:flex;flex-direction:column;gap:10px}.runtime-access>a{display:flex;align-items:center;gap:8px;overflow-wrap:anywhere;color:#547cbf;text-decoration:none;font:12px/1.7 ui-monospace,monospace}.runtime-access>p{font-size:10px;line-height:1.7;color:#97a3b3;margin:0}.runtime-container{border:1px solid var(--runtime-border);border-radius:10px;background:#fafcfe;padding:17px 18px;margin-top:14px}.runtime-container-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:19px;padding-bottom:14px;border-bottom:1px solid #e9eef5;flex-wrap:wrap}.runtime-container-head>b{font:12px/1.7 ui-monospace,monospace;color:#4b607d;overflow-wrap:anywhere}.runtime-container-head>span{font-size:10px;color:#8494aa}.runtime-memory-divider{color:#b3bccb;margin:0 5px}.runtime-java{margin-top:21px;padding-top:16px;border-top:1px solid #e7edf5}.runtime-java h4{margin:0 0 11px;font-size:12px;font-weight:500;color:#5c6f8c}.runtime-java-options{display:flex;gap:7px;flex-wrap:wrap;margin-bottom:10px}.runtime-java-options code{padding:4px 6px;border-radius:5px;background:#edf2f9;color:#627998;font-size:10px;overflow-wrap:anywhere}.runtime-java>p{font-size:11px;color:#8a99ad}.runtime-java>small{display:block;font-size:10px;line-height:1.8;color:#95a1b2;margin-top:5px}
@media(max-width:1250px){.runtime-table-head,.runtime-row{grid-template-columns:minmax(190px,1.3fr) minmax(120px,.8fr) minmax(200px,1.4fr) 108px;gap:12px}.runtime-row,.runtime-table-head{padding-left:19px;padding-right:19px}.runtime-metrics>div{padding:18px}.runtime-metrics small{display:block;margin:0;font-size:10px}.runtime-toolbar{padding:17px}.environment-tabs button{padding:8px 10px}}
@media(max-width:1000px){.runtime-table-head{display:none}.runtime-row{grid-template-columns:minmax(180px,1fr) minmax(190px,1fr);gap:17px;padding:22px}.runtime-service{grid-column:1;grid-row:1}.runtime-state{grid-column:2;grid-row:1;align-items:flex-end}.runtime-state small{text-align:right}.runtime-versions{grid-column:1;grid-row:2}.runtime-actions{grid-column:2;grid-row:2;justify-content:center}.runtime-row-message{grid-row:3;padding-left:0;margin-top:0}.runtime-toolbar{flex-wrap:wrap}.runtime-search{max-width:100%}.runtime-node{margin-left:0}.runtime-table-foot{flex-direction:column;gap:7px}}
@media(max-width:600px){.runtime-head{align-items:flex-start;gap:20px}.runtime-head .actions{width:100%;justify-content:flex-start}.runtime-metrics{grid-template-columns:repeat(2,1fr)}.runtime-metrics>div{padding:15px 18px}.runtime-metrics>div:nth-child(3):before{display:none}.runtime-metrics>div:nth-child(n+3){border-top:1px solid #eef1f6}.runtime-metrics b{font-size:25px}.runtime-notice{align-items:flex-start;font-size:11px}.runtime-notice>.el-icon{margin-top:2px}.environment-tabs{width:100%;justify-content:space-between;gap:0}.environment-tabs button{padding:8px 7px;font-size:11px}.environment-tabs button span{margin-left:4px}.runtime-row{grid-template-columns:minmax(0,1fr) 115px;gap:16px 12px;padding:20px 17px}.runtime-versions{grid-column:1/-1;grid-row:2}.runtime-actions{grid-column:1/-1;grid-row:3;flex-direction:row;justify-content:space-between;align-items:center}.runtime-state small{font-size:9px}.runtime-row-message{grid-row:4}.runtime-detail-node{gap:7px}.runtime-facts{grid-template-columns:66px minmax(0,1fr);gap:11px}.runtime-container{padding:15px 12px}}
.runtime-state>small,.runtime-check-time,.comparison-note{font-size:11px}.runtime-versions b{font-size:12px}.runtime-facts{font-size:13px;gap:16px 12px}.runtime-facts dt{color:#72839b}.runtime-facts dd{color:#52647c}.runtime-facts dd>small{font-size:11px;color:#7f8fa6}.runtime-container-head>b{font-size:13px}.runtime-operation-item b{font-size:13px}.runtime-operation-item p{font-size:12px;color:#708199}.runtime-detail-section h3{font-size:15px}.runtime-node{font-size:12px}
</style>
