<script setup lang="ts">
import { tUi } from '../i18n'

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Check, Connection, Plus, Refresh, Search, Warning } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorText, http, normalizeRelease, payload } from '../api'
import { hasMenu } from '../auth'
import ServiceIdentity from '../components/ServiceIdentity.vue'
import CommitHash from '../components/CommitHash.vue'
import { cancellableTask, cancellationLabel, cancellationPrompt } from '../taskCancellation'
import { useQueue } from '../composables/useQueue'
import { clampHistoryPage, filterHistory, historyVersions, initialEnvironment, rememberEnvironment, validEnvironment, validServiceId } from '../releaseHistory'
import type { HistoryEntry, HistoryService, ReleaseEnvironment, ReleaseHistory } from '../releaseHistory'
import { activityTime, completionTime, environmentLabel, shortCommit, statusLabel, statusType, timeParts } from '../ui'

const route = useRoute(), router = useRouter()
let preferenceStorage: Storage | undefined
try { preferenceStorage = window.localStorage } catch { /* Storage may be unavailable. */ }
const environment = ref<ReleaseEnvironment>(initialEnvironment(route.query.environment, preferenceStorage))
const serviceId = ref(validServiceId(route.query.serviceId)), artifactId = ref(''), status = ref(''), keyword = ref(''), page = ref(1)
const data = ref<ReleaseHistory>({ environment: environment.value, services: [], records: [] })
const loading = ref(false), error = ref(''), cancelling = ref(''), cancelRequested = ref(new Set<string>())
const { refreshQueue, positionText } = useQueue()
const environments: ReleaseEnvironment[] = ['dev', 'test', 'prod']
const statuses = ['PENDING', 'RUNNING', 'WAITING_CONFIRMATION', 'SUCCESS', 'FAILED', 'CANCELLED']
const canReadQueue = computed(() => hasMenu('builds') || hasMenu('dashboard') || hasMenu('release'))
const filtered = computed(() => filterHistory(data.value.records, environment.value, { serviceId: serviceId.value, artifactId: artifactId.value, status: status.value, keyword: keyword.value }))
const paged = computed(() => filtered.value.slice((page.value - 1) * 12, page.value * 12))
const versions = computed(() => historyVersions(data.value.records, environment.value, serviceId.value))
const selectedService = computed(() => data.value.services.find(item => String(item.serviceId) === serviceId.value))
let request = 0, controller: AbortController | undefined, timer: number | undefined, disposed = false

function normalizeEntry(entry: HistoryEntry): HistoryEntry { return { ...entry, task: normalizeRelease(entry.task) } }
function syncSelectionQuery() { void router.replace({ query: { ...route.query, environment: environment.value, serviceId: serviceId.value || undefined } }) }
function resetFilters(updateRoute = true) { serviceId.value = ''; artifactId.value = ''; status.value = ''; keyword.value = ''; page.value = 1; if (updateRoute) syncSelectionQuery() }
function selectService(value: string, updateRoute = true) { serviceId.value = value; artifactId.value = ''; page.value = 1; if (updateRoute) syncSelectionQuery() }
function selectEnvironment(value: ReleaseEnvironment) { environment.value = value; syncSelectionQuery() }
function versionLabel(entry: HistoryEntry) { return entry.artifactVersion || (entry.task.artifactId ? tUi('制品 #{0}', entry.task.artifactId) : tUi('未记录包版本')) }
function scopeLabel(service: HistoryService) {
  if (service.serviceType === 'FLUTTER_ANDROID') return tUi('App 安装包交付，以外部发布确认结果为准')
  const entry = service.latestSuccessful
  return tUi('{0} · 当前配置 {1} 个节点', entry?.targetScopeKnown ? tUi('本次发布 {0} 个节点', entry.targetNodeCount) : tUi('历史目标范围未记录'), service.nodeCount)
}
function createRelease() {
  router.push({ path: '/release/new', query: { environment: environment.value, ...(serviceId.value ? { serviceId: serviceId.value } : {}) } })
}
function openRuntime(id?: number | string) {
  router.push({ path: '/runtime', query: { environment: environment.value, ...(id ? { serviceId: String(id) } : {}) } })
}
async function load(silent = false) {
  if (disposed) return
  const current = ++request, requestedEnvironment = environment.value
  controller?.abort(); controller = new AbortController()
  if (timer) window.clearTimeout(timer)
  if (!silent) loading.value = true
  try {
    const result = payload<ReleaseHistory>(await http.get('/releases/history', { params: { environment: requestedEnvironment }, signal: controller.signal }), { environment: requestedEnvironment, services: [], records: [] })
    if (disposed || current !== request || requestedEnvironment !== environment.value) return
    if (result.environment !== requestedEnvironment || !Array.isArray(result.services) || !Array.isArray(result.records)) throw new Error(tUi('发布记录格式异常，请刷新重试'))
    data.value = { ...result, records: result.records.map(normalizeEntry), services: result.services.map(service => ({ ...service, latestSuccessful: service.latestSuccessful ? normalizeEntry(service.latestSuccessful) : null, latestAttempt: service.latestAttempt ? normalizeEntry(service.latestAttempt) : null })) }
    cancelRequested.value=new Set([...cancelRequested.value].filter(id=>data.value.records.some(entry=>String(entry.task.id)===id&&['PENDING','RUNNING'].includes(String(entry.task.status)))))
    if (serviceId.value && !data.value.services.some(service => String(service.serviceId) === serviceId.value)) selectService('')
    if (artifactId.value && !versions.value.some(version => version.id === artifactId.value)) artifactId.value = ''
    page.value = clampHistoryPage(page.value, filtered.value.length)
    error.value = ''
    if (canReadQueue.value) void refreshQueue()
  } catch (reason) {
    if (!disposed && current === request) error.value = errorText(reason, tUi('暂时无法读取发布记录'))
  } finally {
    if (!disposed && current === request) { loading.value = false; timer = window.setTimeout(() => load(true), 5000) }
  }
}
async function cancel(entry: HistoryEntry) {
  if (cancelling.value || cancelRequested.value.has(String(entry.task.id)) || !cancellableTask(entry.task,false)) return
  cancelling.value = String(entry.task.id)
  try { await ElMessageBox.confirm(cancellationPrompt(entry.task.status), `${cancellationLabel(entry.task.status)} #${entry.task.id}`, { confirmButtonText: cancellationLabel(entry.task.status), cancelButtonText: tUi('继续执行'), type: 'warning' }) }
  catch { cancelling.value = ''; return }
  try { const result=payload<{message?:string}>(await http.post(`/releases/${entry.task.id}/cancel`),{}); cancelRequested.value.add(String(entry.task.id)); ElMessage.success(result.message ? tUi(result.message) : tUi('取消请求已提交，正在等待任务状态更新')); await load(true) }
  catch (reason) { ElMessage.error(errorText(reason)) }
  finally { cancelling.value = '' }
}
watch(environment, value => {
  rememberEnvironment(value, preferenceStorage); resetFilters(false)
  data.value = { environment: value, services: [], records: [] }; error.value = ''; void load()
}, { flush: 'sync' })
watch(() => [route.query.environment, route.query.serviceId], () => {
  const requested = validEnvironment(route.query.environment)
  if (requested && requested !== environment.value) environment.value = requested
  const requestedService = validServiceId(route.query.serviceId)
  if (requestedService !== serviceId.value) selectService(requestedService, false)
}, { flush: 'post' })
watch([artifactId, status, keyword], () => { page.value = 1 })
watch(filtered, value => { page.value = clampHistoryPage(page.value, value.length) })
onMounted(() => { rememberEnvironment(environment.value, preferenceStorage); void load() })
onBeforeUnmount(() => { disposed = true; ++request; controller?.abort(); if (timer) window.clearTimeout(timer) })
</script>

<template>
  <div class="release-history-page">
    <div class="page-head history-head"><div><h2>{{ tUi('发版记录') }}</h2><p>{{ tUi('按环境查看服务的发布版本与每一次部署记录。') }}</p></div><el-button v-if="hasMenu('release')" type="primary" :icon="Plus" @click="createRelease">{{ tUi('新建发版') }}</el-button></div>
    <div class="history-environment-bar"><div class="history-environments" role="group" :aria-label="tUi('选择发布环境')"><button v-for="item in environments" :key="item" type="button" :class="{active: environment === item, production: item === 'prod'}" :data-environment="item" :aria-pressed="environment === item" @click="selectEnvironment(item)"><span class="history-env-dot" aria-hidden="true"></span>{{environmentLabel(item)}}</button></div><span class="history-environment-hint">{{ tUi('当前查看') }} <b>{{environmentLabel(environment)}}</b> {{ tUi('环境的发布结果') }}</span></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="history-error"><template #default>{{ tUi('当前环境可能保留上次读取的记录，请刷新后确认。') }}</template></el-alert>

    <section class="history-services" aria-labelledby="service-version-heading" v-loading="loading && !data.services.length">
      <div class="history-section-title"><div><h3 id="service-version-heading">{{ tUi('最近成功发布版本') }}</h3><span>{{ tUi('{0} 个服务 · 以平台成功记录为准', data.services.length) }}</span></div><el-button v-if="hasMenu('runtime')" link type="primary" :icon="Connection" @click="openRuntime()">{{ tUi('查看运行状态') }}</el-button></div>
      <div v-if="data.services.length" class="history-service-grid">
        <article v-for="service in data.services" :key="service.serviceId" class="history-service-card" :class="{'is-selected':serviceId === String(service.serviceId),'needs-attention':service.latestAttempt?.task.status === 'FAILED'}">
          <button type="button" class="history-service-select" :aria-pressed="serviceId === String(service.serviceId)" :aria-label="tUi('筛选{0}的发布记录', service.serviceName)" @click="selectService(serviceId === String(service.serviceId) ? '' : String(service.serviceId))">
            <div class="history-service-heading"><ServiceIdentity :service="service"/><el-icon v-if="serviceId === String(service.serviceId)" class="history-selected-icon"><Check/></el-icon></div>
            <template v-if="service.latestSuccessful">
              <div class="history-branch"><span>{{ tUi('发布分支') }}</span><strong :title="service.latestSuccessful.task.branch">{{service.latestSuccessful.task.branch || tUi('未记录分支')}}</strong></div>
              <div class="history-completion"><span>{{completionTime(service.latestSuccessful.task).label}}</span><time :datetime="completionTime(service.latestSuccessful.task).value" :title="timeParts(completionTime(service.latestSuccessful.task).value).full"><b>{{timeParts(completionTime(service.latestSuccessful.task).value).time}}</b><span>{{timeParts(completionTime(service.latestSuccessful.task).value).date}}</span></time></div>
            </template>
            <div v-else class="history-no-version"><span>{{ tUi('暂无成功发布') }}</span><small>{{!service.enabled ? tUi('此服务已停用') : !service.configured ? tUi('此环境未配置或未启用') : tUi('首次发布成功后，将在这里展示版本')}}</small></div>
          </button>
          <div v-if="service.latestSuccessful" class="history-card-meta"><div><CommitHash :value="service.latestSuccessful.task.commitId"/><span>{{service.latestSuccessful.buildTaskId ? tUi('构建 #{0}', service.latestSuccessful.buildTaskId) : versionLabel(service.latestSuccessful)}}</span></div><div><span>{{ tUi('发布人 {0}', service.latestSuccessful.task.createdBy || '—') }}</span><el-button link type="primary" @click="router.push(`/releases/${service.latestSuccessful.task.id}`)">{{ tUi('发布 #{0}', service.latestSuccessful.task.id) }}</el-button></div><p>{{scopeLabel(service)}}</p></div>
          <div class="history-service-foot"><span v-if="service.latestAttempt?.task.status === 'FAILED'" class="history-attempt-warning"><el-icon><Warning/></el-icon>{{ tUi('最近发布失败，请核对运行状态') }}</span><span v-else-if="service.latestAttempt?.task.status === 'RUNNING'" class="history-attempt-progress">{{ tUi('有发布正在执行') }}</span><span v-else-if="service.latestAttempt?.task.status === 'PENDING'" class="history-attempt-progress">{{ tUi('有发布正在排队') }}</span><span v-else-if="service.latestAttempt?.task.status === 'WAITING_CONFIRMATION'" class="history-attempt-progress">{{ tUi('待确认 App 发布结果') }}</span><span v-else-if="!service.enabled">{{ tUi('服务已停用') }}</span><span v-else-if="!service.configured">{{ tUi('环境未配置或未启用') }}</span><span v-else class="history-attempt-success">{{service.latestSuccessful ? tUi('最近发布成功') : tUi('等待首次发布')}}</span><el-button v-if="hasMenu('runtime') && service.serviceType!=='FLUTTER_ANDROID'" link type="primary" @click="openRuntime(service.serviceId)" :aria-label="tUi('查看{0}的运行状态', service.serviceName)">{{ tUi('运行状态') }}<el-icon><ArrowRight/></el-icon></el-button></div>
        </article>
      </div>
      <div v-else-if="!loading" class="history-no-services">{{error ? tUi('服务版本暂时无法读取') : tUi('这个环境暂无服务配置或发布记录')}}</div>
      <p class="history-version-note">{{ tUi('版本代表一次成功发布的制品；各节点实际运行情况请在运行总览中检查。') }}</p>
    </section>

    <section class="panel history-records">
      <div class="history-section-title history-records-title"><div><h3>{{selectedService ? tUi('{0} · 发布历史', selectedService.serviceName) : tUi('全部服务 · 发布历史')}}</h3><span>{{ tUi('{0}环境', environmentLabel(environment)) }}</span></div><el-button text :icon="Refresh" :loading="loading" @click="load()">{{ tUi('刷新') }}</el-button></div>
      <div class="history-filters">
        <el-select :model-value="serviceId" clearable :placeholder="tUi('全部服务')" :aria-label="tUi('筛选服务')" @update:model-value="selectService(String($event || ''))"><el-option :label="tUi('全部服务')" value=""/><el-option v-for="service in data.services" :key="service.serviceId" :value="String(service.serviceId)" :label="service.serviceName"/></el-select>
        <el-select v-model="artifactId" clearable filterable :disabled="!serviceId" :placeholder="serviceId ? tUi('全部包版本') : tUi('先选择服务，再筛选版本')" :aria-label="tUi('筛选包版本')" class="history-version-filter"><el-option v-for="version in versions" :key="version.id" :value="version.id" :label="`${version.label} · ${version.buildTaskId ? tUi('构建 #{0}', version.buildTaskId) : tUi('制品 #{0}', version.id)}`"><div class="history-version-option"><span>{{version.label}}</span><small>{{shortCommit(version.commitId)}} · {{version.buildTaskId ? tUi('构建 #{0}', version.buildTaskId) : tUi('制品 #{0}', version.id)}}</small></div></el-option></el-select>
        <el-select v-model="status" clearable :placeholder="tUi('全部状态')" :aria-label="tUi('筛选发布状态')"><el-option v-for="item in statuses" :key="item" :value="item" :label="statusLabel(item)"/></el-select>
        <el-input v-model="keyword" clearable :prefix-icon="Search" :placeholder="tUi('搜索任务、分支、Commit')" :aria-label="tUi('搜索发布记录')"/>
      </div>
      <el-table v-loading="loading" :data="paged" class="clickable-table history-table mobile-replacement-table" @row-click="(entry: HistoryEntry) => router.push(`/releases/${entry.task.id}`)">
        <el-table-column :label="tUi('时间 / 任务')" width="162"><template #default="{row}"><div class="record-time" :title="timeParts(activityTime(row.task).value).full"><b>{{timeParts(activityTime(row.task).value).time || '—'}}</b><span>{{timeParts(activityTime(row.task).value).date}}</span><small>{{activityTime(row.task).label}} · #{{row.task.id}}</small></div></template></el-table-column>
        <el-table-column :label="tUi('服务')" min-width="205"><template #default="{row}"><ServiceIdentity :service="row.task"/></template></el-table-column>
        <el-table-column :label="tUi('分支 / 版本')" min-width="210"><template #default="{row}"><b class="record-branch" :title="row.task.branch">{{row.task.branch || tUi('未记录分支')}}</b><div class="history-table-version-meta" :title="row.imageReference || versionLabel(row)"><CommitHash :value="row.task.commitId"/><span v-if="row.buildTaskId">{{ tUi('构建 #{0}', row.buildTaskId) }}</span></div></template></el-table-column>
        <el-table-column :label="tUi('发布结果')" min-width="145"><template #default="{row}"><el-tag :type="statusType(row.task.status)" size="small">{{statusLabel(row.task.status)}}</el-tag><small v-if="row.task.status === 'PENDING'" class="table-secondary record-queue-note" :title="positionText(row.task.id)">{{positionText(row.task.id)}}</small><small v-else class="table-secondary">{{row.task.deliveryType==='MOBILE'?tUi('App 安装包交付'):row.targetScopeKnown ? tUi('{0} 个目标节点', row.targetNodeCount) : tUi('目标范围未记录')}}</small></template></el-table-column>
        <el-table-column :label="tUi('发布人')" width="105"><template #default="{row}">{{row.task.createdBy || '—'}}</template></el-table-column>
        <el-table-column :label="tUi('操作')" width="186" fixed="right"><template #default="{row}"><el-button link type="primary" @click.stop="router.push(`/releases/${row.task.id}`)">{{ tUi('详情') }}</el-button><el-button v-if="hasMenu('release') && cancellableTask(row.task,false)" link type="danger" :loading="cancelling === String(row.task.id)" :disabled="cancelRequested.has(String(row.task.id))" @click.stop="cancel(row)">{{cancelRequested.has(String(row.task.id))?tUi('已请求中止'):cancellationLabel(row.task.status)}}</el-button></template></el-table-column>
        <template #empty><div class="empty"><div><span class="empty-symbol">◷</span><strong>{{error ? tUi('暂时无法读取记录') : serviceId || artifactId || status || keyword ? tUi('没有匹配的发布记录') : tUi('这个环境暂无发布记录')}}</strong><p>{{error ? tUi('请稍后刷新重试。') : tUi('可调整筛选条件，或选择其他环境查看。')}}</p><el-button v-if="serviceId || artifactId || status || keyword" link type="primary" @click="resetFilters()">{{ tUi('查看全部服务记录') }}</el-button></div></div></template>
      </el-table>
      <div class="mobile-record-list" v-loading="loading">
        <article v-for="entry in paged" :key="entry.task.id" class="mobile-record">
          <div class="mobile-record-top"><time :title="timeParts(activityTime(entry.task).value).full"><b>{{timeParts(activityTime(entry.task).value).time || '—'}}</b><span>{{timeParts(activityTime(entry.task).value).date}} {{activityTime(entry.task).label}}</span></time><el-tag :type="statusType(entry.task.status)" size="small">{{statusLabel(entry.task.status)}}</el-tag></div>
          <ServiceIdentity :service="entry.task"/>
          <div class="mobile-record-branch"><span>{{ tUi('分支') }}</span><b :title="entry.task.branch">{{entry.task.branch || tUi('未记录分支')}}</b></div>
          <div class="mobile-record-version"><CommitHash :value="entry.task.commitId"/><span>{{entry.buildTaskId ? tUi('构建 #{0}', entry.buildTaskId) : versionLabel(entry)}}</span></div>
          <p v-if="entry.task.status === 'PENDING'" class="mobile-record-note">{{positionText(entry.task.id)}}</p>
          <div class="mobile-record-foot"><span>#{{entry.task.id}} · {{entry.task.createdBy || '—'}}</span><div><el-button v-if="hasMenu('release') && cancellableTask(entry.task,false)" link type="danger" :loading="cancelling === String(entry.task.id)" :disabled="cancelRequested.has(String(entry.task.id))" @click="cancel(entry)">{{cancelRequested.has(String(entry.task.id))?tUi('已请求中止'):cancellationLabel(entry.task.status)}}</el-button><el-button link type="primary" @click="router.push(`/releases/${entry.task.id}`)">{{ tUi('查看详情') }}</el-button></div></div>
        </article>
        <div v-if="!paged.length" class="empty"><div><strong>{{error ? tUi('暂时无法读取记录') : tUi('没有匹配的发布记录')}}</strong><p>{{error ? tUi('请稍后刷新重试。') : tUi('可调整筛选条件，或选择其他环境查看。')}}</p><el-button v-if="serviceId || artifactId || status || keyword" link type="primary" @click="resetFilters()">{{ tUi('查看全部服务记录') }}</el-button></div></div>
      </div>
      <div class="table-footer"><span>{{ tUi('共 {0} 条记录', filtered.length) }}<el-button v-if="serviceId" link type="primary" @click="selectService('')">{{ tUi('全部服务') }}</el-button></span><el-pagination v-if="filtered.length" v-model:current-page="page" :total="filtered.length" :page-size="12" layout="prev, pager, next"/></div>
    </section>
  </div>
</template>

<style scoped>
.record-queue-note{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%}
.release-history-page{color:#203149}.history-head{margin-bottom:24px}.history-environment-bar{display:flex;align-items:center;gap:18px;margin-bottom:24px}.history-environments{display:flex;gap:4px;padding:4px;background:#e8edf4;border:1px solid #dce3ec;border-radius:9px}.history-environments button{min-width:108px;padding:10px 21px;border:0;border-radius:6px;background:transparent;color:#607087;font:inherit;font-size:13px;cursor:pointer}.history-environments button.active{color:#315fce;background:#fff;font-weight:600;box-shadow:0 1px 4px #20314910}.history-environments button.active.production{color:#bd4653}.history-environments button:focus-visible,.history-service-select:focus-visible{outline:2px solid #315fce;outline-offset:2px}.history-environment-hint{font-size:12px;color:#607087}.history-environment-hint b{font-weight:500}.history-error{margin-bottom:20px}
.history-section-title{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:15px}.history-section-title>div{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap}.history-section-title h3{margin:0;color:#203149;font-size:16px;font-weight:600}.history-section-title span{font-size:12px;color:#607087}.history-service-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(285px,1fr));gap:14px}.history-service-card{display:flex;flex-direction:column;min-width:0;background:#fff;border:1px solid #dce3ec;border-radius:12px;overflow:hidden}.history-service-card.is-selected{border-color:#315fce;box-shadow:0 0 0 1px #315fce}.history-service-select{display:block;width:100%;padding:16px 18px 12px;border:0;background:transparent;color:inherit;font:inherit;text-align:left;cursor:pointer}.history-service-select:hover{background:#f8faff}.history-service-heading{display:flex;align-items:center;gap:8px;margin-bottom:13px}.history-selected-icon{color:#315fce;flex-shrink:0}.history-branch{display:grid;gap:4px}.history-branch>span,.history-completion>span{font-size:11px;color:#607087}.history-branch strong{font-size:14px;font-weight:650;color:#203149;line-height:1.5;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.history-completion{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-top:12px;padding-top:10px;border-top:1px solid #edf1f6}.history-completion time{display:flex;align-items:baseline;gap:10px;font-variant-numeric:tabular-nums}.history-completion time b{font-size:20px;font-weight:600;letter-spacing:-.4px;color:#203149}.history-completion time span{font-size:12px;color:#60748e}.history-card-meta{padding:0 18px 12px;margin-top:auto}.history-card-meta>div{display:flex;align-items:center;justify-content:space-between;gap:8px;min-width:0;font-size:12px;color:#607087}.history-card-meta>div+div{margin-top:6px}.history-card-meta>div>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.history-card-meta p{margin:8px 0 0;font-size:11px;color:#607087;line-height:1.6}.history-card-meta .el-button{font-size:12px;font-weight:400;height:auto}.history-no-version{min-height:88px;display:flex;flex-direction:column;justify-content:center;gap:10px;font-size:15px;color:#203149}.history-no-version small{font-size:12px;color:#607087;line-height:1.6}.history-service-foot{margin-top:auto;display:flex;align-items:center;justify-content:space-between;gap:8px;padding:10px 18px;border-top:1px solid #e8edf4;background:#fafbfd;font-size:11px;color:#607087;min-height:39px}.history-service-foot .el-button{padding:0;height:auto;font-size:11px}.history-service-foot .el-icon{margin-left:4px}.history-attempt-warning{display:flex;align-items:center;gap:4px;color:#b37519}.history-attempt-warning>.el-icon{margin:0}.history-attempt-progress{color:#315fce}.history-attempt-success{color:#168269}.history-version-note{margin:13px 1px 28px;color:#607087;font-size:12px;line-height:1.7}.history-no-services{padding:36px 20px;text-align:center;border:1px dashed #d7e1ed;border-radius:10px;color:#607087;background:#ffffff80;font-size:13px}.history-records{padding:24px!important}.history-records-title{margin-bottom:20px}.history-filters{display:grid;grid-template-columns:minmax(145px,.8fr) minmax(215px,1.3fr) 125px minmax(205px,1.2fr);gap:10px;margin-bottom:20px}.history-filters .el-select{width:100%}.history-version-option{display:flex;align-items:center;gap:15px;justify-content:space-between}.history-version-option>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.history-version-option small{font-size:11px;color:#607087;flex-shrink:0}.history-table-version-meta{display:flex;align-items:center;gap:10px;margin-top:5px;font-size:11px;color:#607087}.history-table :deep(.el-table__cell){padding-top:13px;padding-bottom:13px}.table-footer>span{display:flex;align-items:center;gap:16px}.table-footer .el-button{font-size:12px}

.history-env-dot{display:inline-block;width:6px;height:6px;margin-right:8px;border-radius:50%;background:#8090a7;vertical-align:middle}.history-environments button[data-environment="dev"] .history-env-dot{background:#315fce}.history-environments button[data-environment="test"] .history-env-dot{background:#b37519}.history-environments button[data-environment="prod"] .history-env-dot{background:#bd4653}.history-environments button.active[data-environment="test"]{color:#966018}.history-service-card.needs-attention:not(.is-selected){border-top:3px solid #bd4653}.history-service-card.needs-attention .history-attempt-warning{color:#bd4653}.history-service-heading :deep(.service-name){font-weight:700}.history-records .record-branch{color:#203149;font-weight:650}.history-records .record-time b{color:#203149}.history-section-title h3{letter-spacing:-.2px}.history-environment-hint b{font-weight:650;color:#203149}
@media(min-width:1650px){.history-service-grid{grid-template-columns:repeat(auto-fill,minmax(310px,1fr))}}
@media(max-width:1150px){.history-filters{grid-template-columns:minmax(150px,1fr) minmax(210px,1.3fr)}.history-service-grid{grid-template-columns:repeat(auto-fill,minmax(270px,1fr))}.history-environment-hint{display:none}}
@media(max-width:650px){.history-environment-bar{display:block}.history-environments{width:100%}.history-environments button{flex:1;min-width:0;padding:10px}.history-service-grid{grid-template-columns:1fr}.history-service-heading{margin-bottom:16px}.history-service-select{padding:18px 18px 14px}.history-card-meta{padding:0 18px 14px}.history-service-foot{padding:11px 18px}.history-section-title{gap:10px;align-items:flex-start}.history-section-title>div{gap:5px}.history-section-title span{display:block;width:100%}.history-section-title>.el-button{font-size:12px;flex-shrink:0}.history-records{padding:16px!important}.history-filters{grid-template-columns:1fr;gap:10px}.history-records .table-footer{gap:12px;align-items:flex-start;flex-direction:column}}
</style>
