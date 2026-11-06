<script setup lang="ts">
import CommitHash from './CommitHash.vue'
import { cancellableTask, cancellationLabel, cancellationPrompt } from '../taskCancellation'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorText, http, normalizeRelease, payload, rows } from '../api'
import { environmentLabel, statusLabel, statusType, timeParts } from '../ui'
import { useQueue } from '../composables/useQueue'
import { hasMenu } from '../auth'
import type { Release } from '../types'
import ServiceIdentity from './ServiceIdentity.vue'

const props = defineProps<{ kind: 'builds' | 'releases' }>()
const router = useRouter(), list = ref<Release[]>([]), loading = ref(false), error = ref(''), keyword = ref(''), filter = ref(''), page = ref(1), cancelling = ref(''), cancelRequested = ref(new Set<string>())
const { refreshQueue, positionText } = useQueue()
const isBuild = computed(() => props.kind === 'builds'), title = computed(() => isBuild.value ? '打包' : '发版')
const createPath = computed(() => isBuild.value ? '/build/new' : '/release/new')
const filtered = computed(() => list.value.filter(item => (!filter.value || item.status === filter.value) && (!keyword.value || `${item.id} ${item.serviceName} ${item.serviceKey} ${item.branch} ${item.commitId}`.toLowerCase().includes(keyword.value.toLowerCase()))))
const paged = computed(() => filtered.value.slice((page.value - 1) * 12, page.value * 12))
const hasLoaded = ref(false)
const statusCounts = computed(() => [
  { value: '', label: '全部记录', count: list.value.length },
  { value: 'RUNNING', label: '执行中', count: list.value.filter(task => task.status === 'RUNNING').length },
  { value: 'PENDING', label: '排队中', count: list.value.filter(task => task.status === 'PENDING').length },
  { value: 'FAILED', label: '失败', count: list.value.filter(task => task.status === 'FAILED').length },
])
function cloudBuild(task: Release) { return [task.mobileBuildEngine, task.frontendBuildEngine, task.backendBuildEngine].includes('GITHUB') }
let timer: number | undefined, disposed = false, fetching = false
async function load(silent = false) {
  if (fetching) return
  fetching = true; if (!silent) loading.value = true
  try { const [result] = await Promise.all([http.get(`/${props.kind}`), refreshQueue()]); list.value = rows<Record<string, unknown>>(result.data).map(normalizeRelease); hasLoaded.value = true; cancelRequested.value=new Set([...cancelRequested.value].filter(id=>list.value.some(task=>String(task.id)===id&&['PENDING','RUNNING'].includes(String(task.status))))); error.value = ''; page.value = Math.min(page.value, Math.max(1, Math.ceil(filtered.value.length / 12))) }
  catch (value) { error.value = errorText(value, '暂时无法读取任务记录') }
  finally { fetching = false; loading.value = false; if (timer) window.clearTimeout(timer); if (!disposed) timer = window.setTimeout(() => load(true), 5000) }
}
async function cancel(task: Release) {
  if (cancelling.value || cancelRequested.value.has(String(task.id)) || !cancellableTask(task,isBuild.value)) return
  cancelling.value = String(task.id)
  try { await ElMessageBox.confirm(cancellationPrompt(task.status), `${cancellationLabel(task.status)} #${task.id}`, { confirmButtonText: cancellationLabel(task.status), cancelButtonText: '继续执行', type: 'warning' }) }
  catch { cancelling.value = ''; return }
  try { const result=payload<{message?:string}>(await http.post(`/${props.kind}/${task.id}/cancel`),{}); cancelRequested.value.add(String(task.id)); ElMessage.success(result.message || '取消请求已提交，正在等待任务状态更新'); await load(true) } catch (value) { ElMessage.error(errorText(value)) } finally { cancelling.value = '' }
}
onMounted(() => load()); onBeforeUnmount(() => { disposed = true; if (timer) window.clearTimeout(timer) })
</script>

<template>
  <div class="page-head"><div><h2>{{title}}记录</h2><p>{{isBuild ? '从代码版本到可发布制品，完整记录每一次构建。' : '跟踪每一次部署，查看目标环境、执行步骤与结果。'}}</p></div><el-button v-if="isBuild || hasMenu('release')" type="primary" :icon="Plus" @click="router.push(createPath)">新建{{title}}</el-button></div>
  <section class="panel records-panel task-records">
    <div class="record-status-tabs" role="group" :aria-label="`${title}状态快捷筛选`"><button v-for="item in statusCounts" :key="item.value" type="button" :class="{active: filter === item.value, danger:item.value==='FAILED'}" :aria-pressed="filter === item.value" @click="filter=item.value;page=1"><span>{{item.label}}</span><b>{{hasLoaded ? item.count : '—'}}</b></button><span class="records-live-note">每 5 秒更新</span></div>
    <div class="panel-title"><div class="table-filters"><el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索服务、分支或任务编号" @input="page = 1"/><el-select v-model="filter" clearable placeholder="全部状态" @change="page = 1"><el-option v-for="value in ['PENDING','RUNNING','SUCCESS','FAILED','CANCELLED']" :key="value" :value="value" :label="statusLabel(value)"/></el-select></div><el-button text :icon="Refresh" :loading="loading" @click="load()">刷新</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon style="margin-bottom:16px"/>
    <el-table v-loading="loading" :data="paged" class="clickable-table mobile-replacement-table" @row-click="(row: Release) => router.push(`/${kind}/${row.id}`)">
      <el-table-column label="创建时间 / 任务" width="144"><template #default="{row}"><div class="record-time" :title="timeParts(row.createdAt).full"><b>{{timeParts(row.createdAt).time || '—'}}</b><span>{{timeParts(row.createdAt).date}}</span><small>#{{row.id}}</small></div></template></el-table-column>
      <el-table-column label="服务" min-width="205"><template #default="{row}"><ServiceIdentity :service="row"/></template></el-table-column><el-table-column label="分支 / Commit" min-width="205"><template #default="{row}"><b class="record-branch" :title="row.branch">{{row.branch || '未记录分支'}}</b><CommitHash :value="row.commitId"/></template></el-table-column>
      <el-table-column v-if="!isBuild" label="环境" width="94"><template #default="{row}"><el-tag :type="row.environment === 'prod' ? 'danger' : row.environment === 'test' ? 'warning' : 'info'" effect="plain" size="small">{{environmentLabel(row.environment)}}</el-tag></template></el-table-column>
      
      <el-table-column :label="isBuild ? '状态 / 构建方式' : '状态'" min-width="145"><template #default="{row}"><el-tag :type="statusType(row.status)" size="small">{{statusLabel(row.status)}}</el-tag><small v-if="row.status === 'PENDING'" class="table-secondary record-queue-note" :title="positionText(row.id)">{{positionText(row.id)}}</small><small v-if="isBuild" class="record-engine" :class="{cloud:cloudBuild(row)}">{{cloudBuild(row)?'GitHub Actions':'本机构建'}}</small></template></el-table-column>
      <el-table-column label="发起人" width="80"><template #default="{row}">{{row.createdBy || '—'}}</template></el-table-column>
      
      <el-table-column label="操作" width="158" fixed="right"><template #default="{row}"><el-button link type="primary" @click.stop="router.push(`/${kind}/${row.id}`)">详情</el-button><el-button v-if="(isBuild || hasMenu('release')) && cancellableTask(row,isBuild)" link type="danger" :loading="cancelling === String(row.id)" :disabled="cancelRequested.has(String(row.id))" @click.stop="cancel(row)">{{cancelRequested.has(String(row.id))?'已请求中止':cancellationLabel(row.status)}}</el-button></template></el-table-column>
      <template #empty><div class="empty"><div><span class="empty-symbol">◷</span><strong>{{error ? '暂时无法读取记录' : keyword || filter ? '没有找到匹配的任务' : `暂无${title}记录`}}</strong><p>{{error ? '稍后刷新重试。' : keyword || filter ? '调整搜索内容或状态筛选。' : `新建${title}后，可以在这里查看执行进度。`}}</p></div></div></template>
    </el-table>
    <div class="mobile-record-list" v-loading="loading">
      <article v-for="task in paged" :key="task.id" class="mobile-record">
        <div class="mobile-record-top"><time :title="timeParts(task.createdAt).full"><b>{{timeParts(task.createdAt).time || '—'}}</b><span>{{timeParts(task.createdAt).date}} 创建</span></time><el-tag :type="statusType(task.status)" size="small">{{statusLabel(task.status)}}</el-tag></div>
        <ServiceIdentity :service="task"/>
        <div class="mobile-record-branch"><span>分支</span><b :title="task.branch">{{task.branch || '未记录分支'}}</b></div>
        <div class="mobile-record-version"><CommitHash :value="task.commitId"/><span v-if="!isBuild">{{environmentLabel(task.environment)}}环境</span><span v-else class="record-engine" :class="{cloud:cloudBuild(task)}">{{cloudBuild(task)?'GitHub Actions':'本机构建'}}</span></div>
        <p v-if="task.status === 'PENDING'" class="mobile-record-note">{{positionText(task.id)}}</p>
        <div class="mobile-record-foot"><span>#{{task.id}} · {{task.createdBy || '—'}}</span><div><el-button v-if="(isBuild || hasMenu('release')) && cancellableTask(task,isBuild)" link type="danger" :loading="cancelling === String(task.id)" :disabled="cancelRequested.has(String(task.id))" @click="cancel(task)">{{cancelRequested.has(String(task.id))?'已请求中止':cancellationLabel(task.status)}}</el-button><el-button link type="primary" @click="router.push(`/${kind}/${task.id}`)">查看详情</el-button></div></div>
      </article>
      <div v-if="!paged.length" class="empty"><div><strong>{{error ? '暂时无法读取记录' : `暂无${title}记录`}}</strong><p>{{keyword || filter ? '调整搜索内容或状态筛选。' : `新建${title}后，可以在这里查看执行进度。`}}</p></div></div>
    </div>
    <div v-if="filtered.length" class="table-footer"><span>共 {{filtered.length}} 条记录</span><el-pagination v-model:current-page="page" :total="filtered.length" :page-size="12" layout="prev, pager, next" /></div>
  </section>
</template>

<style scoped>
.record-queue-note{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%}
.record-status-tabs{display:flex;align-items:center;gap:25px;border-bottom:1px solid #dce3ec;margin:-3px 0 20px;padding:0;min-width:0}.record-status-tabs button{display:flex;align-items:center;gap:8px;position:relative;border:0;background:none;color:#607087;font:inherit;font-size:13px;padding:0 0 17px;cursor:pointer;white-space:nowrap}.record-status-tabs button b{font-size:12px;font-weight:600;padding:2px 7px;border-radius:5px;background:#edf1f6;color:#607087;font-variant-numeric:tabular-nums}.record-status-tabs button.active{color:#315fce;font-weight:600}.record-status-tabs button.active:after{content:'';position:absolute;height:3px;left:0;right:0;bottom:-1px;background:#315fce;border-radius:2px 2px 0 0}.record-status-tabs button.active b{background:#eaf0ff;color:#315fce}.record-status-tabs button.danger.active{color:#bd4653}.record-status-tabs button.danger.active:after{background:#bd4653}.record-status-tabs button.danger.active b{background:#fff0f1;color:#bd4653}.record-status-tabs button:focus-visible{outline:2px solid #315fce;outline-offset:3px}.records-live-note{margin-left:auto;padding-bottom:17px;font-size:11px;color:#607087;white-space:nowrap}.records-live-note:before{content:'';display:inline-block;width:5px;height:5px;border-radius:50%;background:#168269;vertical-align:middle;margin-right:6px}.record-engine{display:block;margin-top:6px;font-size:11px;line-height:1.5;color:#607087;font-weight:500}.record-engine.cloud{color:#315fce}.record-engine:before{content:'';display:inline-block;width:5px;height:5px;border-radius:50%;background:currentColor;vertical-align:middle;margin-right:6px}.task-records :deep(.record-branch){font-weight:650;color:#203149}.task-records :deep(.record-time b){color:#203149}.task-records :deep(.el-table__cell){padding-top:13px;padding-bottom:13px}.mobile-record-version .record-engine{margin:0}
@media(max-width:680px){.record-status-tabs{gap:16px;overflow:auto;margin-bottom:16px}.record-status-tabs button{gap:5px;font-size:12px;padding-bottom:14px}.record-status-tabs button b{padding:1px 5px}.records-live-note{display:none}.task-records>.panel-title{gap:10px;align-items:flex-start}.task-records .table-filters{flex:1;flex-wrap:wrap}.task-records .table-filters>.el-input{width:100%}.task-records .table-filters>.el-select{width:100%}}
</style>
