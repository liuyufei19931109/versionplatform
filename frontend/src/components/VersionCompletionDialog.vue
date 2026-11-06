<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorText, http, payload } from '../api'
import { authUser } from '../auth'
import type { ReleaseVersion } from '../versions'
import { environmentLabel, formatTime } from '../ui'
import { completionInputKey, completionOutcomeLabel, completionOutcomeType } from '../versionCompletion'
import type { CompletionDetail, CompletionInput, CompletionOptions, CompletionPreview } from '../versionCompletion'
import ServiceIdentity from './ServiceIdentity.vue'
import CommitHash from './CommitHash.vue'

const props = withDefaults(defineProps<{ modelValue: boolean; version: ReleaseVersion | null; completion: CompletionDetail | null; action?: 'complete' | 'edit' | 'reopen' }>(), { action: 'complete' })
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [detail: CompletionDetail]; refresh: [] }>()
const environment = ref('prod'), changes = ref(''), remaining = ref(''), summary = ref(''), reason = ref('')
const options = ref<CompletionOptions | null>(null), excluded = ref<number[]>([]), reasons = ref<Record<number, string>>({})
const preview = ref<CompletionPreview | null>(null), loading = ref(false), previewing = ref(false), submitting = ref(false), error = ref('')
const expectedId = ref<number | null>(null), stale = ref(false)
const now = ref(Date.now()), generatedAt = ref(0), generatedSummary = ref(''), replacing = ref(false)
let generation = 0, previewGeneration = 0, previewKey = '', previewController: AbortController | undefined, initializing = false
const clock = window.setInterval(() => { now.value = Date.now() }, 2000)
const busy = computed(() => loading.value || previewing.value || submitting.value || replacing.value)
const title = computed(() => props.action === 'edit' ? '编辑版本小结' : props.action === 'reopen' ? '重新打开版本' : '完成版本')
const input = computed<CompletionInput>(() => ({ environment: environment.value, changes: changes.value.trim(), remaining: remaining.value.trim(), exclusions: excluded.value.map(serviceId => ({ serviceId, reason: (reasons.value[serviceId] || '').trim() })) }))
const inputKey = computed(() => completionInputKey(input.value))
const validExclusions = computed(() => input.value.exclusions.every(item => item.reason.length > 0))
const expired = computed(() => Boolean(preview.value && generatedAt.value && now.value - generatedAt.value >= 10 * 60 * 1000))
const previewMatches = computed(() => Boolean(preview.value?.fingerprint && previewKey === inputKey.value && !stale.value && !expired.value))
const activeRelease = computed(() => options.value?.items.some(item => ['RUNNING','PENDING'].includes(item.taskStatus || '')) || false)
const canGenerate = computed(() => !busy.value && !activeRelease.value && Boolean(options.value?.items.length) && Boolean(changes.value.trim()) && validExclusions.value && (!options.value?.items.some(item => item.outcome !== 'PUBLISHED' && !excluded.value.includes(item.serviceId)) || Boolean(remaining.value.trim())))
const canSave = computed(() => !busy.value && Boolean(props.version?.id) && (props.action === 'reopen' ? Boolean(expectedId.value && reason.value.trim()) : props.action === 'edit' ? Boolean(expectedId.value && changes.value.trim() && summary.value.trim()) : previewMatches.value && Boolean(summary.value.trim())))
const visibleItems = computed(() => preview.value?.items || options.value?.items || [])
const unconfirmed = computed(() => options.value?.items.filter(item => item.outcome !== 'PUBLISHED' && !excluded.value.includes(item.serviceId)).length || 0)
function close(value = false) { if (!submitting.value&&!replacing.value) emit('update:modelValue', value) }
function invalidatePreview() { previewGeneration++; previewController?.abort(); previewController = undefined; preview.value = null; previewKey = ''; previewing.value = false; stale.value = false }
function invalidateInputPreview() { if (!initializing && props.action === 'complete') { invalidatePreview(); stale.value = Boolean(summary.value); error.value = '' } }
async function loadOptions() {
  if (!props.version || !props.modelValue || props.action !== 'complete') return
  const current = ++generation, versionId = props.version.id, requestedEnvironment = environment.value
  loading.value = true; error.value = ''; options.value = null
  try {
    const result = payload<CompletionOptions>(await http.get(`/versions/${versionId}/completion/options`, { params: { environment: requestedEnvironment } }), {} as CompletionOptions)
    if (current !== generation || !props.modelValue || props.version?.id !== versionId) return
    if (result.versionId !== versionId || result.environment !== requestedEnvironment || !Array.isArray(result.items)) throw new Error('版本发布情况与当前选择不一致，请重新读取')
    options.value = result
  } catch (failure) { if (current === generation) error.value = errorText(failure, '读取发布情况失败') }
  finally { if (current === generation) loading.value = false }
}
function toggleExcluded(serviceId: number, value: unknown) { excluded.value = value === true ? [...new Set([...excluded.value, serviceId])] : excluded.value.filter(id => id !== serviceId) }
function initialize() {
  generation++; invalidatePreview(); initializing = true
  options.value = null; excluded.value = []; reasons.value = {}; error.value = ''; loading.value = false
  const current = props.completion?.current
  expectedId.value = current?.id || null
  environment.value = props.action === 'complete' ? 'prod' : current?.environment || 'prod'
  changes.value = current?.changes || ''; remaining.value = current?.remaining || ''; summary.value = props.action === 'edit' ? current?.summary || '' : ''; reason.value = ''
  generatedAt.value = 0; generatedSummary.value = ''; replacing.value = false; initializing = false
  if (props.action === 'complete') loadOptions()
}
function changeEnvironment() { if (initializing) return; excluded.value = []; reasons.value = {}; invalidateInputPreview(); loadOptions() }
async function generate() {
  if (!canGenerate.value || !authUser.value?.admin || !props.version) return
  if (summary.value.trim() && summary.value !== generatedSummary.value) {
    const openedGeneration = generation
    replacing.value = true
    try { await ElMessageBox.confirm('重新生成会替换你编辑过的草稿。确认后可继续编辑新草稿。', '重新生成小结', { confirmButtonText: '重新生成', cancelButtonText: '保留草稿', type: 'warning', closeOnClickModal: false }) }
    catch { replacing.value = false; return }
    replacing.value = false
    if (openedGeneration !== generation || !props.modelValue || !canGenerate.value) return
  }
  const current = ++previewGeneration, versionId = props.version.id, requestedKey = inputKey.value, controller = new AbortController()
  previewController = controller; previewing.value = true; error.value = ''; stale.value = false
  try {
    const result = payload<CompletionPreview>(await http.post(`/versions/${versionId}/completion/preview`, input.value, { signal: controller.signal }), {} as CompletionPreview)
    if (current !== previewGeneration || !props.modelValue || props.version?.id !== versionId || inputKey.value !== requestedKey) return
    if (!result.fingerprint || result.versionId !== versionId || !Array.isArray(result.items)) throw new Error('未能生成有效的小结预览，请重新生成')
    preview.value = result; previewKey = requestedKey; summary.value = result.summary || ''; generatedSummary.value = summary.value; generatedAt.value = Date.now(); now.value = Date.now()
  } catch (failure) { if (current === previewGeneration) { preview.value = null; error.value = errorText(failure, '生成小结失败') } }
  finally { if (current === previewGeneration) { previewing.value = false; previewController = undefined } }
}
async function save() {
  if (!canSave.value || !authUser.value?.admin || !props.version) return
  const versionId = props.version.id, action = props.action, current = generation
  submitting.value = true; error.value = ''
  try {
    const url = `/versions/${versionId}/completion`
    const response = action === 'reopen' ? await http.post(`${url}/reopen`, { expectedId: expectedId.value, reason: reason.value.trim() })
      : action === 'edit' ? await http.put(url, { expectedId: expectedId.value, changes: changes.value.trim(), remaining: remaining.value.trim(), summary: summary.value.trim() })
      : await http.post(url, { ...input.value, summary: summary.value.trim(), fingerprint: preview.value?.fingerprint })
    const detail = payload<CompletionDetail>(response, {} as CompletionDetail)
    if (current !== generation || !props.modelValue || props.version?.id !== versionId) return
    if (detail.versionId !== versionId || !detail.current?.id || detail.state !== (action === 'reopen' ? 'OPEN' : 'COMPLETED')) throw new Error('提交结果暂时无法确认，请刷新版本详情核对')
    emit('saved', detail); emit('update:modelValue', false)
    ElMessage.success(action === 'reopen' ? '版本已重新打开' : action === 'edit' ? '版本小结已更新' : '版本已完成，小结已向全员展示')
  } catch (failure) {
    if (current !== generation) return
    error.value = errorText(failure, '保存版本小结失败，请刷新后核对')
    if (props.action === 'complete') { stale.value = true; preview.value = null }
    emit('refresh')
  } finally { if (current === generation) submitting.value = false }
}
watch(inputKey, invalidateInputPreview, { flush: 'sync' })
watch(environment, changeEnvironment, { flush: 'sync' })
watch([() => props.modelValue, () => props.version?.id, () => props.action], ([open]) => { if (open) initialize(); else { generation++; invalidatePreview(); loading.value = false; submitting.value = false } }, { immediate: true })
onBeforeUnmount(() => { generation++; invalidatePreview(); window.clearInterval(clock); if (replacing.value) ElMessageBox.close() })
</script>

<template>
  <el-dialog :model-value="modelValue" :title="title" width="900px" class="version-completion-dialog" :close-on-click-modal="false" :close-on-press-escape="!submitting" :show-close="!submitting" @update:model-value="close">
    <div class="completion-identity"><div><span>版本日期</span><b>{{version?.versionDate}}</b></div><strong>{{version?.branch}}</strong><el-tag v-if="action==='edit'" type="success" effect="plain" size="small">已完成</el-tag></div>
    <p class="completion-public-note">小结文字与发布数量对所有登录成员可见。服务、分支和发布记录明细仍按原权限查看。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="completion-alert"/>
    <template v-if="action==='reopen'"><p class="completion-reopen-note">重新打开后，版本回到“进行中”，已完成的小结会保留在历史记录中。后续可重新核对发布情况并完成版本。</p><el-form label-position="top"><el-form-item label="重新打开原因" required><el-input v-model="reason" type="textarea" :rows="4" :maxlength="1000" show-word-limit placeholder="例如：本轮需要补充一个模块，完成后重新收尾" aria-label="重新打开原因" :disabled="submitting"/></el-form-item></el-form></template>
    <template v-else>
      <el-form label-position="top" class="completion-form"><div class="completion-fields"><el-form-item label="收尾环境" required><el-select v-model="environment" :disabled="action==='edit'||busy" aria-label="收尾环境"><el-option label="生产环境" value="prod"/><el-option label="预生产环境" value="test"/><el-option label="开发环境" value="dev"/></el-select></el-form-item><div class="completion-environment-note">{{action==='edit'?'编辑文字会保留原发布快照；如需重新核对，请重新打开版本。':'默认按生产环境收尾；平台会核对该版本分支的实际发布记录。'}}</div></div><el-form-item label="本次更新" required><el-input v-model="changes" type="textarea" :rows="3" :maxlength="4000" show-word-limit placeholder="这一版做了什么？例如：新增搜索筛选，修复登录超时问题。" aria-label="本次更新" :disabled="submitting"/></el-form-item></el-form>
      <section v-if="action==='complete'" v-loading="loading" class="completion-module-section"><div class="completion-section-heading"><h4>核对模块发布情况</h4><span>{{environmentLabel(environment)}}环境 · {{options?.items.length ?? '—'}} 个模块</span></div><p class="completion-selection-hint">不准备在本轮上线的模块，请勾选“本次不发布”并填写原因。没有确认发布结果的模块会单独列为“待确认”。</p><div class="completion-modules"><article v-for="item in visibleItems" :key="item.serviceId" class="completion-module"><div class="completion-module-top"><ServiceIdentity :service="{...item,displayIcon:version?.items.find(service=>service.serviceId===item.serviceId)?.displayIcon}"/><el-tag :type="completionOutcomeType(item)" size="small" effect="plain">{{excluded.includes(item.serviceId)?'本次不发布':completionOutcomeLabel(item)}}</el-tag></div><div class="completion-module-meta"><strong>{{item.branch}}</strong><CommitHash :value="item.commitId"/><span v-if="item.taskId">发布 #{{item.taskId}}</span><span v-if="item.finishedAt">{{formatTime(item.finishedAt)}}</span></div><p v-if="item.message" class="completion-module-message">{{item.message}}</p><div v-if="item.outcome!=='PUBLISHED'" class="completion-exclusion"><el-checkbox :model-value="excluded.includes(item.serviceId)" :disabled="submitting||previewing" :aria-label="`${item.serviceName}本次不发布`" @update:model-value="toggleExcluded(item.serviceId, $event)">本次不发布</el-checkbox><el-input v-if="excluded.includes(item.serviceId)" v-model="reasons[item.serviceId]" :maxlength="500" :disabled="submitting||previewing" :aria-label="`${item.serviceName}不发布原因`" placeholder="必填：例如本次暂不上线，留待下一版本"/></div></article><p v-if="!loading&&!visibleItems.length" class="completion-empty">{{options?'此版本没有可收尾的模块。':'暂时无法读取模块，请重试。'}}</p></div><el-button v-if="!options&&!loading" :loading="loading" @click="loadOptions">重新读取发布情况</el-button></section>
      <el-form label-position="top"><el-form-item label="遗留事项" :required="action==='complete'&&unconfirmed>0"><el-input v-model="remaining" type="textarea" :rows="3" :maxlength="4000" show-word-limit :placeholder="unconfirmed?'还有待确认模块，请说明原因或后续安排。':'选填：还有哪些事项需要后续处理？'" aria-label="遗留事项" :disabled="submitting"/></el-form-item></el-form>
      <p v-if="action==='complete'&&activeRelease" class="completion-validation">该版本在此环境还有排队中或执行中的发布，请等待任务结束后再完成版本。</p><p v-if="action==='complete'&&!validExclusions" class="completion-validation">请填写每个“本次不发布”模块的原因。</p><p v-if="action==='complete'&&unconfirmed&&!remaining.trim()" class="completion-validation">还有 {{unconfirmed}} 个待确认模块，请填写遗留事项，或明确标记为“本次不发布”。</p>
      <section v-if="preview||summary||action==='edit'" class="completion-draft"><div class="completion-section-heading"><h4>小结草稿</h4><span>{{action==='edit'?'修改后保存为新的修订记录':previewMatches?'结合填写内容与发布情况生成，可继续编辑':'已保留草稿，需重新核对后才能确认'}}</span></div><el-input v-model="summary" type="textarea" :rows="10" :maxlength="8000" show-word-limit aria-label="小结草稿" :disabled="submitting||replacing"/><p v-if="preview" class="completion-preview-counts">本次核对：{{preview.counts.published}} 个已发布 · {{preview.counts.excluded}} 个本次不发布 · {{preview.counts.unconfirmed}} 个待确认。</p></section>
      <p v-if="stale||expired" class="completion-validation">预览已失效或发布情况可能已变化，请重新生成小结后确认。</p>
    </template>
    <template #footer><div class="completion-footer"><span>{{action==='complete'?'完成版本只登记收尾，不会执行部署或代码合并。':action==='edit'?'修改文字会保留历史修订和原发布快照。':'重新打开会记录原因及操作人。'}}</span><div><el-button :disabled="submitting||replacing" @click="close()">取消</el-button><el-button v-if="action==='complete'" :loading="previewing" :disabled="!canGenerate" :type="summary?'default':'primary'" @click="generate">{{summary?'重新生成小结':'生成小结'}}</el-button><el-button v-if="preview||summary||action!=='complete'" type="primary" :loading="submitting" :disabled="!canSave" @click="save">{{action==='edit'?'保存小结':action==='reopen'?'确认重新打开':'确认完成'}}</el-button></div></div></template>
  </el-dialog>
</template>

<style scoped>
.completion-identity{display:flex;align-items:center;gap:20px;flex-wrap:wrap;padding:16px 20px;background:#f5f8fd;border:1px solid #e2e9f4;border-radius:9px}.completion-identity>div{display:grid;gap:6px}.completion-identity span{font-size:11px;color:#74849a}.completion-identity b{font-size:22px;color:#233149;font-weight:650;font-variant-numeric:tabular-nums}.completion-identity>strong{font-size:14px;color:#416bdc;font-weight:600;overflow-wrap:anywhere}.completion-public-note{font-size:12px;line-height:1.8;color:#74849a;margin:14px 0 20px}.completion-alert{margin-bottom:18px}.completion-fields{display:grid;grid-template-columns:190px minmax(0,1fr);gap:18px;align-items:center}.completion-environment-note{font-size:12px;line-height:1.8;color:#74849a;padding-top:5px}.completion-form :deep(.el-form-item__label),.completion-dialog :deep(.el-form-item__label){font-size:12px}.completion-module-section{margin-bottom:24px;min-height:90px}.completion-section-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:10px}.completion-section-heading h4{margin:0;font-size:13px;color:#354b68;font-weight:600}.completion-section-heading>span{font-size:11px;color:#74849a}.completion-selection-hint{margin:0 0 12px;font-size:12px;line-height:1.8;color:#74849a}.completion-modules{max-height:380px;overflow:auto;border:1px solid #e4eaf3;border-radius:8px}.completion-module{padding:15px 18px}.completion-module+.completion-module{border-top:1px solid #edf1f6}.completion-module-top{display:flex;justify-content:space-between;gap:14px;align-items:center}.completion-module-meta{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:10px 0 0 44px;font-size:11px;color:#8a98ab}.completion-module-meta>strong{color:#6481ac;font-size:11px;font-weight:500;overflow-wrap:anywhere}.completion-module-message{margin:7px 0 0 44px;font-size:11px;line-height:1.7;color:#8b7b69}.completion-exclusion{display:flex;gap:14px;align-items:center;margin:10px 0 0 44px}.completion-exclusion :deep(.el-checkbox){margin-right:0;flex:none}.completion-exclusion :deep(.el-checkbox__label){font-size:12px}.completion-exclusion :deep(.el-input){flex:1}.completion-empty{padding:20px;text-align:center;color:#74849a;font-size:12px}.completion-validation{font-size:12px;line-height:1.8;color:#ad7547;margin:8px 0 15px}.completion-draft{padding:17px;border:1px solid #dce7f5;border-radius:9px;background:#f7faff}.completion-preview-counts{font-size:12px;color:#5978af;line-height:1.8;margin:11px 0 0}.completion-footer{display:flex;justify-content:space-between;align-items:center;gap:20px}.completion-footer>span{color:#8a98ab;font-size:11px;line-height:1.8;max-width:310px}.completion-footer>div{display:flex;gap:8px;flex:none;flex-wrap:wrap;justify-content:flex-end}.completion-footer .el-button+.el-button{margin-left:0}.completion-reopen-note{font-size:13px;color:#74849a;line-height:1.9;margin:0 0 20px}
@media(max-width:650px){.completion-identity{padding:14px;gap:13px}.completion-identity b{font-size:20px}.completion-fields{grid-template-columns:1fr;gap:0}.completion-environment-note{padding:0;margin:-10px 0 18px}.completion-section-heading{flex-wrap:wrap;gap:6px}.completion-module{padding:13px 12px}.completion-module-top{align-items:flex-start;flex-wrap:wrap;gap:9px}.completion-module-meta,.completion-module-message,.completion-exclusion{margin-left:0}.completion-exclusion{flex-direction:column;align-items:flex-start;gap:4px}.completion-exclusion :deep(.el-input){width:100%}.completion-draft{padding:12px}.completion-footer{flex-direction:column;align-items:stretch;gap:12px}.completion-footer>span{max-width:none}.completion-footer>div{gap:8px}}
</style>
