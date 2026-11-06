<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { errorText, http, payload, rows } from '../api'
import { authUser } from '../auth'
import { shanghaiToday, versionStatusLabel, versionStatusType } from '../versions'
import type { ReleaseVersion, VersionCandidate, VersionPreview } from '../versions'
import ServiceIdentity from './ServiceIdentity.vue'
import CommitHash from './CommitHash.vue'

const props = defineProps<{ modelValue: boolean; version?: ReleaseVersion | null; retryServiceIds?: number[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; created: [version: ReleaseVersion] }>()
const candidates = ref<VersionCandidate[]>([]), selectedIds = ref<number[]>([]), search = ref(''), loading = ref(false), previewing = ref(false), submitting = ref(false), error = ref(''), preview = ref<VersionPreview | null>(null)
const selectedDate = ref(shanghaiToday())
let generation = 0, previewGeneration = 0, previewController: AbortController | undefined
const retrying = computed(() => Boolean(props.retryServiceIds?.length))
const title = computed(() => retrying.value ? '核对并重试分支' : props.version ? '补充服务模块' : '创建版本')
const date = computed(() => props.version?.versionDate || selectedDate.value)
const branch = computed(() => preview.value?.branch || props.version?.branch || `release${date.value.replace(/-/g, '')}`)
const validDate = computed(() => Boolean(props.version) || /^\d{4}-\d{2}-\d{2}$/.test(selectedDate.value) && selectedDate.value >= shanghaiToday())
const filtered = computed(() => candidates.value.filter(item => `${item.serviceName} ${item.serviceKey}`.toLowerCase().includes(search.value.trim().toLowerCase())))
const readyCount = computed(() => preview.value?.items.filter(item => item.status === 'READY').length || 0)
const skippedCount = computed(() => (preview.value?.items.length || 0) - readyCount.value)
const busy = computed(() => loading.value || previewing.value || submitting.value)
function included(item: VersionCandidate) { return !retrying.value && Boolean(props.version?.items.some(existing => existing.serviceId === item.serviceId)) }
function selectable(item: VersionCandidate) { return item.hasRepository && !included(item) && (!retrying.value || props.retryServiceIds?.includes(item.serviceId)) }
function close() { if (!busy.value) emit('update:modelValue', false) }
function updateOpen(value: boolean) { if (!busy.value) emit('update:modelValue', value) }
function disabledDate(value: Date) {
  const day = `${String(value.getFullYear()).padStart(4, '0')}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`
  return day < shanghaiToday() || value.getFullYear() > 9999
}
function invalidatePreview() { previewGeneration++; previewController?.abort(); previewController = undefined; previewing.value = false; preview.value = null; error.value = '' }
async function load() {
  const request = ++generation
  invalidatePreview(); selectedDate.value = props.version?.versionDate || shanghaiToday()
  candidates.value = []; selectedIds.value = []; search.value = ''; error.value = ''; loading.value = true
  try {
    const response = await http.get('/versions/candidates')
    if (request !== generation) return
    candidates.value = rows<VersionCandidate>(response)
    if (retrying.value) selectedIds.value = candidates.value.filter(item => selectable(item)).map(item => item.serviceId)
  } catch (e) { if (request === generation) error.value = errorText(e, '读取服务失败，请重新打开窗口') }
  finally { if (request === generation) loading.value = false }
}
async function readPreview() {
  if (busy.value || !authUser.value?.admin || !selectedIds.value.length || !validDate.value) return
  const request = ++previewGeneration, requestedDate = date.value, controller = new AbortController()
  previewController = controller
  previewing.value = true; error.value = ''
  try {
    const value = payload<VersionPreview>(await http.post('/versions/preview', { serviceIds: [...selectedIds.value], ...(props.version ? { versionId: props.version.id } : { versionDate: requestedDate }) }, { timeout: 180000, signal: controller.signal }), {} as VersionPreview)
    if (request !== previewGeneration || !props.modelValue) return
    if (!value.previewId || value.versionDate !== requestedDate || value.branch !== `release${requestedDate.replace(/-/g, '')}`) throw new Error('版本日期或分支预览不一致，请重新核对')
    preview.value = value
  } catch (e) { if (request === previewGeneration) { preview.value = null; error.value = errorText(e, '读取 master 失败，请重试') } }
  finally { if (request === previewGeneration) { previewing.value = false; previewController = undefined } }
}
async function confirm() {
  if (busy.value || !preview.value?.canConfirm || !authUser.value?.admin) return
  submitting.value = true; error.value = ''
  try {
    const version = payload<ReleaseVersion>(await http.post('/versions', { previewId: preview.value.previewId }), {} as ReleaseVersion)
    if (!version.id) throw new Error('提交结果暂时无法确认，请先刷新版本列表核对')
    emit('created', version); emit('update:modelValue', false); ElMessage.success('已提交分支创建，执行结果会自动更新')
  } catch (e) { error.value = errorText(e, '创建版本失败，请重试'); }
  finally { submitting.value = false }
}
watch([selectedDate, selectedIds], invalidatePreview, { deep: true, flush: 'sync' })
watch(() => props.modelValue, value => { if (value && authUser.value?.admin) load(); else { generation++; invalidatePreview(); } })
onBeforeUnmount(() => { generation++; invalidatePreview() })
</script>

<template>
  <el-dialog :model-value="modelValue" :title="title" width="820px" class="version-create-dialog" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="updateOpen">
    <div class="version-dialog-identity"><div><label for="planned-version-date">计划版本日期 · 北京时间</label><b v-if="props.version">{{date}}</b><el-date-picker v-else id="planned-version-date" v-model="selectedDate" type="date" value-format="YYYY-MM-DD" format="YYYY-MM-DD" :clearable="false" :editable="false" :disabled="loading||submitting" :disabled-date="disabledDate" aria-label="计划版本日期"/><small v-if="props.version">沿用本版本日期，不可修改</small></div><div><span>对应 release 分支</span><strong>{{branch}}</strong></div></div>
    <p class="version-dialog-hint">{{retrying?'重新读取远程状态和 master 基线，仅重试本次选中的模块。':props.version?'补充的服务将基于各自当前最新 master 创建分支。':'选择今天或未来日期；同一日期共用一个版本，可继续补充服务。'}} 该日期用于版本和分支命名；确认后立即创建分支，不是定时发布。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="version-dialog-error"/>
    <div v-if="!preview" v-loading="loading||previewing" :element-loading-text="previewing?'正在读取各仓库的 master，请稍候…':'读取服务模块…'" class="version-selection">
      <el-input v-model="search" :prefix-icon="Search" placeholder="搜索服务名称或标识" clearable :disabled="busy" aria-label="搜索服务模块"/>
      <el-checkbox-group v-model="selectedIds" class="version-candidates" :disabled="busy" aria-label="选择版本服务">
        <el-checkbox v-for="item in filtered" :key="item.serviceId" :value="item.serviceId" :disabled="!selectable(item)"><ServiceIdentity :service="item"/><span class="version-candidate-note">{{included(item)?'已在此版本':!item.hasRepository?'未配置仓库':!item.enabled?'尚未启用':''}}</span></el-checkbox>
      </el-checkbox-group>
      <div v-if="!loading&&!filtered.length" class="version-candidates-empty">{{candidates.length?'没有匹配的模块':'暂无可选择的服务模块，请先完成服务的 Git 配置。'}}</div>
    </div>
    <div v-else class="version-preview">
      <div class="version-preview-title"><b>核对创建基线</b><span>按以下 master 提交创建，执行前会再次核对</span></div>
      <p class="version-preview-date">已锁定版本日期 <b>{{preview.versionDate}}</b> · {{preview.branch}}</p>
      <article v-for="item in preview.items" :key="item.serviceId" class="version-preview-row"><ServiceIdentity :service="item"/><div class="version-preview-baseline"><span>基准分支 <b>master</b></span><CommitHash :value="item.baselineSha"/><small v-if="item.baselineMessage" :title="item.baselineMessage">{{item.baselineMessage}}</small></div><div class="version-preview-state"><el-tag :type="versionStatusType(item.status)" size="small" disable-transitions>{{versionStatusLabel(item.status)}}</el-tag><p v-if="item.message">{{item.message}}</p></div></article>
      <p class="version-preview-note">{{readyCount?`将为 ${readyCount} 个模块创建分支。`:'没有需要新建的分支。'}}<span v-if="skippedCount">其他 {{skippedCount}} 个模块将保存核对结果；已创建的分支保留，冲突或错误不会执行 Git 创建。</span></p>
    </div>
    <template #footer><div class="version-dialog-footer"><span>{{preview?'创建分支不会自动打包或发布':`已选择 ${selectedIds.length} 个模块`}}</span><div><el-button :disabled="busy" @click="close">取消</el-button><el-button v-if="preview" :disabled="busy" @click="invalidatePreview">返回选择</el-button><el-button v-if="!preview" type="primary" :loading="previewing" :disabled="loading||!selectedIds.length||!validDate||!authUser?.admin" @click="readPreview">核对 master 基线</el-button><el-button v-else type="primary" :loading="submitting" :disabled="!preview.canConfirm||!authUser?.admin" @click="confirm">{{readyCount?'确认创建分支':'确认关联服务'}}</el-button></div></div></template>
  </el-dialog>
</template>

<style scoped>
.version-dialog-identity label{color:#74849a;font-size:12px}.version-dialog-identity small{font-size:11px;color:#8a98ab}.version-dialog-identity :deep(.el-date-editor){width:100%;max-width:250px}.version-preview-date{padding:10px 12px;background:#f5f8fd;border-radius:6px;color:#52647c;font-size:12px;line-height:1.7;overflow-wrap:anywhere}.version-preview-date b{color:#354b68;font-weight:600}
.version-preview-state :deep(.el-tag--success){--el-tag-text-color:#28734f;--el-tag-bg-color:#edf7f1;--el-tag-border-color:#cfe7d8}.version-preview-state :deep(.el-tag--danger){--el-tag-text-color:#a84040;--el-tag-bg-color:#fff1f1;--el-tag-border-color:#f2d6d6}.version-preview-state :deep(.el-tag--warning){--el-tag-text-color:#8d6028;--el-tag-bg-color:#fff5e4;--el-tag-border-color:#f0dfbc}
.version-dialog-identity{display:grid;grid-template-columns:1fr 1fr;gap:20px;background:#f5f8fd;border:1px solid #e1e8f4;border-radius:9px;padding:17px 20px}.version-dialog-identity>div{display:grid;gap:8px}.version-dialog-identity span{color:#74849a;font-size:12px}.version-dialog-identity b{font-size:22px;color:#233149;font-weight:650}.version-dialog-identity strong{font-size:16px;color:#416bdc;font-weight:600;align-self:center;overflow-wrap:anywhere}.version-dialog-hint{font-size:12px;line-height:1.8;color:#74849a;margin:15px 0 18px}.version-dialog-error{margin-bottom:16px}.version-selection{min-height:160px}.version-candidates{display:grid;margin-top:12px;max-height:340px;overflow:auto}.version-candidates :deep(.el-checkbox){display:flex;height:auto;min-height:72px;margin:0;padding:13px 8px;border-bottom:1px solid #edf1f6}.version-candidates :deep(.el-checkbox__label){display:flex;align-items:center;gap:16px;flex:1;min-width:0;padding-left:13px}.version-candidates :deep(.el-checkbox__label>.service-identity){flex:1}.version-candidate-note{font-size:11px;color:#8a98ab;white-space:nowrap}.version-candidates :deep(.is-disabled .service-identity){opacity:.55}.version-candidates-empty{padding:30px 15px;text-align:center;color:#74849a;font-size:12px}.version-preview-title{display:flex;gap:15px;align-items:center;justify-content:space-between;margin-bottom:8px}.version-preview-title>b{font-size:13px;color:#354b68}.version-preview-title>span{font-size:11px;color:#74849a}.version-preview{max-height:420px;overflow:auto}.version-preview-row{display:grid;grid-template-columns:minmax(165px,1.1fr) minmax(140px,1fr) minmax(140px,.9fr);align-items:center;gap:15px;padding:18px 0;border-bottom:1px solid #edf1f6}.version-preview-baseline{display:flex;align-items:center;flex-wrap:wrap;gap:3px;font-size:11px;color:#74849a;min-width:0}.version-preview-baseline>b{font-weight:500}.version-preview-baseline>small{flex-basis:100%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:11px;color:#7f8da2}.version-preview-state p{margin:6px 0 0;color:#8a7866;font-size:11px;line-height:1.65;overflow-wrap:anywhere}.version-preview-note{font-size:12px;line-height:1.8;color:#5978af;margin:15px 0 0}.version-dialog-footer{display:flex;align-items:center;justify-content:space-between;gap:15px}.version-dialog-footer>span{font-size:12px;color:#74849a}.version-dialog-footer>div{display:flex;gap:8px}
@media(max-width:650px){.version-dialog-identity{grid-template-columns:1fr;gap:15px}.version-dialog-identity b{font-size:20px}.version-preview-title{align-items:flex-start;flex-direction:column;gap:6px}.version-preview-row{grid-template-columns:1fr;gap:10px}.version-preview-baseline{padding-left:44px}.version-preview-state{padding-left:44px}.version-dialog-footer{align-items:flex-start;flex-direction:column}.version-dialog-footer>div{display:flex;flex-wrap:wrap;justify-content:flex-end;width:100%}.version-candidates :deep(.el-checkbox__label){flex-wrap:wrap;gap:7px}.version-candidate-note{padding-left:44px}}
</style>
