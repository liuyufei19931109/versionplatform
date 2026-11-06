<script setup lang="ts">
import { useAppScope } from '../composables/useAppScope'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { errorText, http, payload, rows } from '../api'
import { formatTime } from '../ui'
import CommitHash from './CommitHash.vue'

interface BranchCheck { id?: number; baselineSha?: string; buildSha?: string; status: string; message?: string; checkedAt?: string }
interface Merge { status: string; message?: string; reason?: string; baselineBefore?: string; baselineAfter?: string; commitId?: string; finishedAt?: string; retryAllowed?: boolean }
const { api, can } = useAppScope()
const props = defineProps<{ taskId: string; build: boolean; buildBranch?: string; environment?: string; taskStatus?: string }>()
const checks = ref<BranchCheck[]>([]), merge = ref<Merge | null>(null), error = ref(''), retrying = ref(false)
let timer: number | undefined, disposed = false, revision = 0
const mergeLabels: Record<string,string> = { NOT_CREATED:'尚未触发', NOT_APPLICABLE:'不适用', QUEUED:'等待合回', PENDING:'等待合回', RUNNING:'正在合回', SUCCESS:'已合回 master', MERGED:'已合回 master', ALREADY_MERGED:'已包含本次版本', FAILED:'合回失败，待处理', MANUAL_REQUIRED:'需要人工处理', NEEDS_ATTENTION:'需要人工处理', SKIPPED:'无需合回' }
const mergeLabel = computed(() => mergeLabels[merge.value?.status || ''] || merge.value?.status || '尚未触发')
const mergeDone = computed(() => ['SUCCESS','MERGED','ALREADY_MERGED'].includes(merge.value?.status || ''))
const canRetry = computed(() => can('release') && merge.value?.retryAllowed === true)
async function load() {
  const current = ++revision
  if (timer) window.clearTimeout(timer)
  try {
    if (props.build) { const result = rows<BranchCheck>((await http.get(api(`/tasks/${props.taskId}/branch-checks`))).data); if (disposed || current !== revision) return; checks.value = result }
    else if (props.environment === 'prod') { const result = payload<Merge | null>(await http.get(api(`/releases/${props.taskId}/merge`)), null); if (disposed || current !== revision) return; merge.value = result }
    if (disposed || current !== revision) return
    error.value = ''
  } catch (reason) { if (!disposed && current === revision) error.value = errorText(reason, '读取分支校验状态失败') }
  finally {
    if (!disposed && current === revision && (['PENDING','RUNNING'].includes(props.taskStatus || '') || ['QUEUED','RUNNING'].includes(merge.value?.status || '') || (!props.build && props.taskStatus==='SUCCESS' && merge.value?.status==='NOT_CREATED'))) timer = window.setTimeout(load, 4000)
  }
}
async function retry() {
  if (retrying.value) return
  retrying.value = true
  try { await http.post(api(`/releases/${props.taskId}/merge/retry`)); ElMessage.success('已提交代码合回重试'); await load() }
  catch (reason) { ElMessage.error(errorText(reason, '重试合回失败')) }
  finally { retrying.value = false }
}
watch(() => [props.taskId, props.taskStatus], load, { immediate:true })
onBeforeUnmount(() => { disposed = true; revision++; if (timer) window.clearTimeout(timer) })
</script>

<template>
  <section v-if="build || environment==='prod'" class="panel governance-panel">
    <div class="panel-title"><div><h3>{{build?'打包分支校验':'生产代码合回'}}</h3><span class="panel-subtitle">{{build?'基准分支 master（远程最新），每次执行前重新校验':'仅合回本次实际发布的 Commit，合回状态独立于发布结果'}}</span></div><el-button v-if="canRetry" :loading="retrying" @click="retry">重试代码合回</el-button></div>
    <div v-if="error" class="governance-error">{{error}}<el-button link type="primary" @click="load">重新读取</el-button></div>
    <template v-else-if="build"><div v-if="!checks.length" class="governance-empty">{{['PENDING','RUNNING'].includes(taskStatus||'')?'任务执行前读取基准分支的最新提交，并校验待构建版本。':'该记录暂无分支校验信息。'}}</div><div v-for="(check,index) in checks" :key="check.id || index" class="branch-check"><el-tag :type="['PASS','PASSED','SUCCESS'].includes(check.status)?'success':'danger'" size="small">{{['PASS','PASSED','SUCCESS'].includes(check.status)?'通过':'未通过'}}</el-tag><div class="check-description"><b>{{check.message || '分支基线检查'}}</b><dl class="check-versions"><div class="check-baseline"><dt>基准分支</dt><dd><strong>master</strong><small>校验时的远程最新提交</small><CommitHash :value="check.baselineSha"/></dd></div><div class="check-build"><dt>待构建分支</dt><dd><strong>{{buildBranch || '未记录分支'}}</strong><small>本次锁定的 Commit</small><CommitHash :value="check.buildSha"/></dd></div></dl></div><time>{{formatTime(check.checkedAt)}}</time></div></template>
    <template v-else><div class="merge-state"><span class="status-dot" :class="mergeDone?'status-success':canRetry?'status-failed':'status-pending'"></span><b>{{mergeLabel}}</b><CommitHash v-if="merge?.commitId" :value="merge.commitId"/><time v-if="merge?.finishedAt">{{formatTime(merge.finishedAt)}}</time></div><p class="governance-note">{{merge?.message || (mergeDone?'远程 master 已确认包含本次生产版本。':taskStatus==='SUCCESS'?'生产发布已经成功，代码合回单独处理；无需重新发布。':'生产发布成功后才会触发代码合回。')}}</p></template>
  </section>
</template>

<style scoped>
.check-versions{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:18px;margin:15px 0 0}.check-versions>div{min-width:0}.check-versions>div+div{padding-left:18px;border-left:1px solid #e5ebf3}.check-versions dt{font-size:12px;color:#74849a;margin-bottom:7px}.check-versions dd{margin:0;display:flex;align-items:flex-start;flex-direction:column;gap:5px}.check-versions strong{font-size:13px;color:#354b68;font-weight:600;overflow-wrap:anywhere}.check-versions small{font-size:11px;color:#74849a;line-height:1.7}.check-versions :deep(.commit-hash){margin-left:-5px}@media(max-width:650px){.check-versions{grid-template-columns:1fr;gap:13px}.check-versions>div+div{padding:13px 0 0;border-left:0;border-top:1px solid #e5ebf3}.check-description{flex-basis:calc(100% - 60px)}}

.governance-panel{margin-top:20px}.governance-panel .panel-title{gap:15px}.branch-check{display:flex;align-items:flex-start;gap:14px;padding:16px 0;border-top:1px solid #edf0f5}.check-description{flex:1;min-width:0}.check-description>b{display:block;font-size:13px;font-weight:500;color:#354b68;line-height:1.7;overflow-wrap:anywhere}.check-description>div{display:flex;align-items:center;flex-wrap:wrap;gap:8px 14px;margin-top:7px;color:#74849a;font-size:12px}.branch-check time,.merge-state time{font-size:12px;color:#74849a;white-space:nowrap}.merge-state{display:flex;align-items:center;gap:12px;font-size:14px;color:#354b68;flex-wrap:wrap}.merge-state .status-dot{margin:0}.merge-state time{margin-left:auto}.governance-empty,.governance-note,.governance-error{font-size:13px;color:#6c7f98;line-height:1.8;margin:12px 0 0;overflow-wrap:anywhere}.governance-error{color:#bc5555;display:flex;gap:12px;align-items:center}@media(max-width:650px){.branch-check{flex-wrap:wrap;gap:10px}.branch-check time{width:100%;padding-left:50px}.governance-panel .panel-title{align-items:flex-start;flex-wrap:wrap}.merge-state time{margin-left:0;width:100%}}
</style>
