<script setup lang="ts">
import { tUi } from '../i18n'

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Plus, Refresh, Calendar, ArrowLeft } from '@element-plus/icons-vue'
import { authUser } from '../auth'
import { errorText, http, payload, rows } from '../api'
import { canAddVersionServices, versionBusy, versionSummary } from '../versions'
import type { ReleaseVersion, VersionItem } from '../versions'
import VersionServiceList from '../components/VersionServiceList.vue'
import VersionCreateDialog from '../components/VersionCreateDialog.vue'
import VersionLinkDialog from '../components/VersionLinkDialog.vue'
import VersionCompletionDialog from '../components/VersionCompletionDialog.vue'
import VersionCompletionPanel from '../components/VersionCompletionPanel.vue'
import type { CompletionDetail } from '../versionCompletion'

const route = useRoute(), router = useRouter(), versions = ref<ReleaseVersion[]>([]), selected = ref<ReleaseVersion | null>(null), loading = ref(false), error = ref(''), dialog = ref(false), dialogVersion = ref<ReleaseVersion | null>(null), retryIds = ref<number[]>([])
const completion = ref<CompletionDetail | null>(null), completionLoading = ref(false), completionError = ref(''), completionDialog = ref(false), completionAction = ref<'complete'|'edit'|'reopen'>('complete')
const completionStatesError = ref('')
const linkDialog = ref(false), linkItem = ref<VersionItem | null>(null)
let timer: number | undefined, disposed = false, request = 0, completionRequest = 0, queryHandled = false
const failedItems = computed(() => selected.value?.items.filter(item => ['FAILED','CONFLICT','UNKNOWN'].includes(item.status)) || [])
function openCreate() { if (!authUser.value?.admin) return; dialogVersion.value = null; retryIds.value = []; dialog.value = true }
function openAdd() { if (!authUser.value?.admin || !selected.value || !canAddVersionServices(selected.value)) return; dialogVersion.value = selected.value; retryIds.value = []; dialog.value = true }
function retry(item?: VersionItem) { if (!authUser.value?.admin || !selected.value || versionBusy(selected.value)) return; dialogVersion.value = selected.value; retryIds.value = item ? [item.serviceId] : failedItems.value.map(value => value.serviceId); dialog.value = true }
function openLink(item: VersionItem) { if (!authUser.value?.admin || !selected.value || versionBusy(selected.value)) return; linkItem.value = item; linkDialog.value = true }
async function linked() { linkDialog.value = false; await load(true) }
function openCompletion(action: 'complete'|'edit'|'reopen' = 'complete') { if (!authUser.value?.admin || !selected.value || !completion.value || versionBusy(selected.value)) return; completionAction.value = action; completionDialog.value = true }
async function loadCompletion(versionId = selected.value?.id) {
  if (!versionId) return
  const current = ++completionRequest
  completionLoading.value = true
  try {
    const value = payload<CompletionDetail>(await http.get(`/versions/${versionId}/completion`), {} as CompletionDetail)
    if (current !== completionRequest || disposed || selected.value?.id !== versionId) return
    if (value.versionId !== versionId || !['OPEN','COMPLETED'].includes(value.state)) throw new Error(tUi('版本收尾记录不完整，请重新读取'))
    completion.value = value; completionError.value = ''
    versions.value = versions.value.map(item => item.id === versionId ? { ...item, lifecycleState: value.state } : item)
  } catch (failure) { if (current === completionRequest && !disposed) completionError.value = errorText(failure, tUi('读取版本收尾记录失败')) }
  finally { if (current === completionRequest && !disposed) completionLoading.value = false }
}
async function completionSaved(detail: CompletionDetail) {
  completionDialog.value = false
  if (selected.value?.id === detail.versionId) { completion.value = detail; completionError.value = '' }
  await load(true)
}
async function load(silent = false) {
  const current = ++request
  if (!silent) loading.value = true
  try {
    const [versionResult, stateResult] = await Promise.allSettled([http.get('/versions'), http.get('/versions/completion-states')])
    if (versionResult.status === 'rejected') throw versionResult.reason
    const states = stateResult.status === 'fulfilled' ? new Map(rows<{versionId:number;state:'OPEN'|'COMPLETED'}>(stateResult.value).map(item => [item.versionId,item.state])) : null
    const list = rows<ReleaseVersion>(versionResult.value).map(item => ({ ...item, lifecycleState: states ? states.get(item.id) || 'OPEN' as const : undefined }))
    if (current !== request || disposed) return
    versions.value = list
    completionStatesError.value = states ? '' : tUi('部分版本的收尾状态暂时无法读取；请刷新后核对。')
    const id = String(route.params.id || '')
    let detail: ReleaseVersion | null = id ? list.find(item => String(item.id) === id) || null : list[0] || null
    if (id) detail = payload<ReleaseVersion>(await http.get(`/versions/${encodeURIComponent(id)}`), {} as ReleaseVersion)
    if (current !== request || disposed) return
    selected.value = detail; error.value = ''
    if (detail?.id) await loadCompletion(detail.id)
    if (!queryHandled) { queryHandled = true; if (route.query.create === '1') openCreate(); else if (route.query.add === '1') openAdd(); }
  } catch (e) { if (current === request && !disposed) { error.value = errorText(e, tUi('读取版本失败')); selected.value = null } }
  finally { if (current === request && !disposed) { loading.value = false; if (timer) window.clearTimeout(timer); timer = window.setTimeout(() => load(true), versionBusy(selected.value) ? 2500 : 20000) } }
}
async function created(version: ReleaseVersion) { dialog.value = false; if (String(route.params.id) !== String(version.id)) await router.push(`/versions/${version.id}`); else await load() }
watch(() => route.params.id, () => { selected.value = null; completion.value = null; completionError.value = ''; completionDialog.value = false; linkDialog.value = false; linkItem.value = null; completionRequest++; load() })
onMounted(() => load())
onBeforeUnmount(() => { disposed = true; request++; completionRequest++; if (timer) window.clearTimeout(timer) })
</script>

<template>
  <div class="page-head"><div><h2>{{ tUi('版本管理') }}</h2><p>{{ tUi('按日期组织服务模块，从各仓库的 master 统一创建 release 分支。') }}</p></div><div class="actions"><el-button :icon="Refresh" :loading="loading" @click="load()">{{ tUi('刷新') }}</el-button><el-button v-if="authUser?.admin" type="primary" :icon="Plus" @click="openCreate">{{ tUi('创建版本') }}</el-button></div></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="versions-error"/>
  <el-alert v-if="completionStatesError" :title="completionStatesError" type="warning" :closable="false" show-icon class="versions-error"/>
  <div v-if="versions.length" class="versions-layout">
    <aside class="version-index panel" :aria-label="tUi('版本列表')"><div class="version-index-heading"><span>{{ tUi('版本日期') }}</span><small>{{ tUi('北京时间') }}</small></div><button v-for="version in versions" :key="version.id" type="button" class="version-index-item" :class="{active:selected?.id===version.id}" :aria-current="selected?.id===version.id?'page':undefined" @click="router.push(`/versions/${version.id}`)"><div class="version-index-date"><time>{{version.versionDate}}</time><el-tag v-if="version.lifecycleState==='COMPLETED'" type="success" size="small" effect="plain">{{ tUi('已完成') }}</el-tag></div><span>{{version.branch}}</span><small>{{versionSummary(version)}}</small></button></aside>
    <section v-if="selected" class="panel version-detail">
      <div class="version-detail-top"><div><span class="version-detail-label">{{ tUi('版本日期') }}</span><h3>{{selected.versionDate}}</h3></div><div class="actions"><el-button v-if="authUser?.admin&&failedItems.length" :disabled="versionBusy(selected)" @click="retry()">{{ tUi('重试失败模块') }}</el-button><el-button v-if="authUser?.admin&&canAddVersionServices(selected)" :icon="Plus" :disabled="versionBusy(selected)" @click="openAdd">{{ tUi('补充服务') }}</el-button><el-button v-if="authUser?.admin&&completion?.state==='OPEN'" type="primary" :disabled="versionBusy(selected)||completionLoading" @click="openCompletion()">{{ tUi('完成版本') }}</el-button></div></div>
      <div class="version-detail-meta"><strong>{{selected.branch}}</strong><el-tag v-if="completion" :type="completion.state==='COMPLETED'?'success':'info'" size="small" effect="plain">{{completion.state==='COMPLETED'?tUi('已完成'):tUi('进行中')}}</el-tag><span>{{ tUi('{0} 个模块', selected.items.length) }}</span><span>{{ tUi('创建人 {0}', selected.createdBy) }}</span><span>{{versionSummary(selected)}}</span></div>
      <div v-if="completionError" class="completion-load-error" role="alert"><span>{{completionError}}</span><el-button link type="primary" :loading="completionLoading" @click="loadCompletion()">{{ tUi('重新读取收尾记录') }}</el-button></div>
      <VersionCompletionPanel v-if="completion?.current" :detail="completion" :services="selected?.items" :disabled="versionBusy(selected)||completionLoading" @edit="openCompletion('edit')" @reopen="openCompletion('reopen')"/>
      <p class="version-detail-note">{{ tUi('版本分支用于后续开发与打包。打包时会读取该分支最新提交，并继续校验 master 基线。') }}</p>
      <VersionServiceList :key="selected.id+':'+selected.successCount" :items="selected.items" :version-id="selected.id" :busy="versionBusy(selected)" @retry="retry" @link="openLink"/>
      <div class="version-detail-foot"><span>{{ tUi('分支创建结果与实际部署状态独立记录。') }}</span><span v-if="authUser?.admin&&!canAddVersionServices(selected)">{{ tUi('历史版本可查看或重试已有模块；新增模块请创建版本。') }}</span></div>
    </section>
    <section v-else class="panel version-empty-detail"><p>{{loading?tUi('正在读取版本详情…'):tUi('请选择可查看的版本')}}</p><el-button v-if="route.params.id" :icon="ArrowLeft" @click="router.push('/versions')">{{ tUi('返回最近版本') }}</el-button></section>
  </div>
  <section v-else-if="!error" class="panel version-empty"><span class="version-empty-icon"><el-icon><Calendar/></el-icon></span><h3>{{loading?tUi('正在读取版本…'):tUi('从一个版本开始安排本轮开发')}}</h3><p>{{authUser?.admin?tUi('选择本轮参与的后端、前端或 App，平台会从各仓库最新 master 创建所选日期的 release 分支。'):tUi('尚无可查看的版本。admin 创建版本后，你可以在这里查看已授权的服务和分支。')}}</p><el-button v-if="authUser?.admin&&!loading" type="primary" :icon="Plus" @click="openCreate">{{ tUi('创建版本') }}</el-button></section>
  <VersionCreateDialog v-if="authUser?.admin" v-model="dialog" :version="dialogVersion" :retry-service-ids="retryIds" @created="created"/>
  <VersionLinkDialog v-if="authUser?.admin" v-model="linkDialog" :version="selected" :item="linkItem" @linked="linked"/>
  <VersionCompletionDialog v-if="authUser?.admin" v-model="completionDialog" :version="selected" :completion="completion" :action="completionAction" @saved="completionSaved" @refresh="loadCompletion()"/>
</template>

<style scoped>
.version-index-date{display:flex;gap:8px;align-items:center;justify-content:space-between;flex-wrap:wrap}.version-index-date :deep(.el-tag){font-size:10px;height:20px;padding:0 5px}.completion-load-error{display:flex;align-items:center;justify-content:space-between;gap:15px;margin:16px 0;padding:10px 13px;border:1px solid #f0dfce;border-radius:7px;background:#fffaf3;font-size:12px;line-height:1.7;color:#9a774d}.version-detail-top>.actions{flex-wrap:wrap;justify-content:flex-end}
.versions-error{margin-bottom:20px}.versions-layout{display:grid;grid-template-columns:215px minmax(0,1fr);gap:22px;align-items:start}.version-index.panel{padding:14px 11px}.version-index-heading{display:flex;justify-content:space-between;gap:8px;padding:5px 10px 14px;font-size:12px;color:#52647c}.version-index-heading small{font-size:11px;color:#8a98ab}.version-index-item{display:grid;width:100%;gap:7px;background:transparent;border:1px solid transparent;border-radius:8px;padding:15px 12px;text-align:left;font-family:inherit;cursor:pointer}.version-index-item+button{margin-top:5px}.version-index-item time{color:#354b68;font-size:16px;font-weight:600;font-variant-numeric:tabular-nums}.version-index-item>span{font-size:12px;color:#74849a;overflow-wrap:anywhere}.version-index-item>small{font-size:11px;color:#8a98ab;line-height:1.5}.version-index-item.active{background:#eef3fd;border-color:#dce6fa}.version-index-item.active time{color:#416bdc}.version-index-item:hover{border-color:#dce6fa}.version-index-item:focus-visible{outline:2px solid #416bdc;outline-offset:2px}.version-detail.panel{margin:0;padding:24px}.version-detail-top{display:flex;align-items:center;justify-content:space-between;gap:20px}.version-detail-label{font-size:12px;color:#74849a}.version-detail-top h3{margin:7px 0 0;font-size:29px;font-weight:650;color:#233149;font-variant-numeric:tabular-nums;letter-spacing:-.6px}.version-detail-meta{display:flex;align-items:center;gap:17px;flex-wrap:wrap;margin-top:18px;font-size:12px;color:#74849a}.version-detail-meta strong{font-size:15px;font-weight:600;color:#416bdc;overflow-wrap:anywhere}.version-detail-note{padding:12px 0;margin:10px 0 14px;font-size:12px;line-height:1.8;color:#74849a}.version-detail-foot{display:flex;flex-wrap:wrap;gap:7px;justify-content:space-between;margin-top:16px;padding-top:16px;border-top:1px solid #edf1f6;color:#8a98ab;font-size:11px;line-height:1.8}.version-empty{text-align:center;padding:64px 30px}.version-empty-icon{display:grid;place-items:center;width:56px;height:56px;margin:0 auto 18px;background:#edf3fd;border-radius:13px;color:#6484c7;font-size:28px}.version-empty h3{font-size:18px;color:#354b68;font-weight:600}.version-empty p{max-width:480px;margin:12px auto 23px;color:#74849a;font-size:13px;line-height:1.9}.version-empty-detail p{color:#74849a;font-size:13px;line-height:1.8}
@media(max-width:1260px){.versions-layout{grid-template-columns:1fr}.version-index.panel{display:flex;gap:8px;overflow:auto}.version-index-heading{display:none}.version-index-item{min-width:180px;width:auto;flex-shrink:0}.version-index-item+button{margin-top:0}}
@media(max-width:650px){.version-detail.panel{padding:18px}.version-detail-top{align-items:flex-start;flex-direction:column}.version-detail-top h3{font-size:26px}.version-detail-meta{gap:10px}.version-detail-meta strong{flex-basis:100%}.version-index-item{min-width:160px;padding:12px 10px}.version-empty{padding:35px 20px}}
</style>
