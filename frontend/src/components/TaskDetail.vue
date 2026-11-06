<script setup lang="ts">
import { tUi } from '../i18n'

import { useAppScope } from '../composables/useAppScope'
import CommitHash from './CommitHash.vue'
import { mobileVersionLabel } from '../mobile'
import { cancellableTask, cancellationLabel, cancellationPrompt } from '../taskCancellation'
import { computed, shallowRef, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Check, Close, Loading, Refresh, Clock, Cloudy, Monitor } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorText, http, normalizeRelease, payload, stepLabel } from '../api'
import { environmentLabel, formatTime, newRequestId, statusLabel, statusType } from '../ui'
import { useQueue } from '../composables/useQueue'
import { useProductionApproval } from '../composables/useProductionApproval'
import OtpInput from './OtpInput.vue'
import ServiceIdentity from './ServiceIdentity.vue'
import ReleaseNotifications from './ReleaseNotifications.vue'
import MobileReleasePanel from './MobileReleasePanel.vue'
import TaskGovernance from './TaskGovernance.vue'
import BuildPublishDialog from './BuildPublishDialog.vue'
import ProductionPromotionAction from './ProductionPromotionAction.vue'
import TaskFailureGuide from './TaskFailureGuide.vue'
import TaskLogs from './TaskLogs.vue'
import ReleaseNodeLogs from './ReleaseNodeLogs.vue'
import { retryDeploymentScope } from '../taskFailure'
import { consumeBuildPublishIntent, forgetBuildPublish, renewBuildPublish } from '../buildPublishIntent'
import type { BuildPublishTarget, BuildAutoPublish } from '../buildPublishIntent'
import type { ReleaseStep } from '../types'
import { hasMenu } from '../auth'

const { apps, api, can, servicePath } = useAppScope()
const props = defineProps<{ kind: 'builds' | 'releases' }>()
const router = useRouter(), route = useRoute(), id = String(route.params.id)
const taskSource = shallowRef<Record<string, unknown>>({id})
const task = computed(() => normalizeRelease(taskSource.value))
const loading = ref(false), error = ref(''), action = ref(false), otpDialog = ref(false), otp = ref(''), otpError = ref('')
const publishDialog = ref(false), cancelRequested = ref(false)
const overlappingReleases = ref<number[]>([])
const plannedPublishTarget = ref<BuildPublishTarget>()
const autoPublish = ref<BuildAutoPublish>(), taskLogs=ref<InstanceType<typeof TaskLogs>>()
const cloudRunUrl=ref('')
const waitingAutoPublish=computed(()=>!!autoPublish.value&&['WAITING_BUILD','READY'].includes(autoPublish.value.status))
const canManualPublish=computed(()=>!autoPublish.value||['BLOCKED','CANCELLED'].includes(autoPublish.value.status))
const autoPublishTitle=computed(()=>autoPublish.value?.status==='LINKED_EXISTING'?tUi('已关联发布任务 #')+autoPublish.value.deploymentTaskId:autoPublish.value?.deploymentTaskId?tUi('已自动创建发布任务 #')+autoPublish.value.deploymentTaskId:autoPublish.value?.status==='WAITING_BUILD'?tUi('打包成功后自动发布'):autoPublish.value?.status==='READY'?tUi('打包已成功，等待发布队列'):tUi('自动发布已停止'))
const { summary, refreshQueue, positionText } = useQueue()
const { approver, enabled, remaining, loading: approvalLoading, unavailable, refresh: refreshApproval, handleError } = useProductionApproval()
const isBuild = computed(() => props.kind === 'builds'), title = computed(() => isBuild.value ? (apps?tUi('构建'):tUi('打包')) : (apps?tUi('交付'):tUi('发版'))), running = computed(() => ['PENDING','RUNNING'].includes(String(task.value.status)))
const isMobileDelivery = computed(() => !isBuild.value && task.value.deliveryType === 'MOBILE')
const isMobileTask = computed(() => task.value.serviceType === 'FLUTTER_ANDROID')
const cloudBuild = computed(() => [task.value.backendBuildEngine, task.value.frontendBuildEngine, task.value.mobileBuildEngine].includes('GITHUB'))
const engineTitle = computed(() => task.value.backendBuildEngine === 'GITHUB' ? tUi('GitHub 后端云构建') : task.value.frontendBuildEngine === 'GITHUB' ? tUi('GitHub 前端云构建') : task.value.mobileBuildEngine === 'GITHUB' ? tUi('GitHub 云端构建') : tUi('服务器本机构建'))
const canOperate = computed(() => isBuild.value || can('release'))
const retryScope = computed(() => !isBuild.value && !isMobileDelivery.value ? retryDeploymentScope(task.value) : '')
const production = computed(() => !isBuild.value && task.value.environment === 'prod')
const defaults = computed(() => (isBuild.value ? (task.value.serviceType==='FLUTTER_ANDROID'?['GIT_PULL',task.value.mobileOutputFormat==='APK'?'BUILD_APK':'BUILD_AAB',task.value.mobileOutputFormat==='APK'?'VALIDATE_APK':'VALIDATE_AAB','ARCHIVE_ARTIFACT']:['GIT_PULL','BUILD_JAR','BUILD_IMAGE','PUSH_IMAGE']) : ['SSH_CONNECT','START_SERVICE','HEALTH_CHECK']).map(code => stepLabel(code, task.value.serviceType)))
const steps = computed<ReleaseStep[]>(() => task.value.steps?.length ? task.value.steps : defaults.value.map(name => ({name,status:'WAITING'})))
const logs = computed(() => steps.value.filter(step => step.log).map(step => `【${step.name}】\n${step.log}`).join('\n') || task.value.logs || (task.value.status === 'CANCELLED' ? tUi('任务已取消。已执行的远端操作不会自动撤销，请检查运行状态。') : tUi('任务开始后，执行日志会显示在这里。')))
const retryLabel = computed(() => {
  if (!isBuild.value) return tUi('重试{0}', title.value)
  if (steps.value.find(step => step.status === 'FAILED')?.code === 'PUSH_IMAGE') return tUi('重试推送')
  return task.value.commitId ? tUi('重试原提交 {0}', task.value.commitId.slice(0, 8)) : tUi('重试{0}', title.value)
})
let timer: number | undefined, disposed = false, fetching = false, reloadRequested = false, loadRevision = 0, retryRequest = '', failedVersion = ''
async function load(silent = false) {
  if (fetching) { reloadRequested = true; return }; fetching = true; if (!silent) loading.value = true
  const revision = loadRevision
  try {
    const [result] = await Promise.all([http.get(api(`/${props.kind}/${id}`)), refreshQueue()]); const raw=payload<Record<string, unknown>>(result, {})
    if (disposed || revision !== loadRevision) return
    cloudRunUrl.value=typeof raw.cloudRunUrl==='string'&&/^https:\/\/github\.com\/[\w.-]+\/[\w.-]+\/actions\/runs\/\d+$/.test(raw.cloudRunUrl)?raw.cloudRunUrl:''
    taskSource.value = raw
    const value = task.value
    const version = String(value.finishedAt || value.updatedAt || '')
    if (value.status === 'FAILED' && failedVersion !== version) { failedVersion = version; retryRequest = '' }
    autoPublish.value=isBuild.value&&raw.autoPublish?raw.autoPublish as BuildAutoPublish:undefined; if(!['PENDING','RUNNING'].includes(String(value.status)))cancelRequested.value=false; error.value = ''; maybeOfferPublish()
  } catch (value) { if (!disposed && revision === loadRevision) error.value = errorText(value, tUi('暂时无法读取任务')) }
  finally { fetching = false; loading.value = false; if (timer) window.clearTimeout(timer); if (!disposed && (running.value || waitingAutoPublish.value || task.value.status === 'WAITING_CONFIRMATION' || reloadRequested)) timer = window.setTimeout(() => load(true), reloadRequested ? 0 : 3000); reloadRequested = false }
}
async function requestRetry() { if (action.value || approvalLoading.value) return; if (production.value) { otp.value = ''; otpError.value = ''; otpDialog.value = true; await refreshApproval() } else await retry() }
async function retry() {
  if (action.value || (production.value && (remaining.value || unavailable.value || approvalLoading.value))) return
  if (production.value && !/^\d{6}$/.test(otp.value)) return ElMessage.warning(tUi('请输入 6 位动态码'))
  action.value = true; retryRequest ||= newRequestId()
  overlappingReleases.value = []
  try { await http.post(api(`/${props.kind}/${id}/retry`), { requestId: retryRequest, ...(production.value ? {totpCode: otp.value} : {}) }); loadRevision++; if(isBuild.value)renewBuildPublish(id); retryRequest = ''; otpDialog.value = false; ElMessage.success(tUi('任务已重新加入队列')); await load(true) }
  catch (value) {
    const response = (value as {response?: {status?: number; data?: {code?: string; preflight?: {activeTasks?: {taskId:number}[]}}}}).response
    if (!isBuild.value && response?.status === 409 && response.data?.code === 'DEPLOY_ACTIVE') {
      overlappingReleases.value = (response.data.preflight?.activeTasks || []).map(task => task.taskId).filter(id => Number.isSafeInteger(id) && id > 0)
    }
    otp.value = ''
    if (overlappingReleases.value.length) otpDialog.value = false
    else {
      handleError(value)
      otpError.value = errorText(value)
      if (!otpDialog.value) ElMessage.error(otpError.value)
    }
  }
  finally { action.value = false }
}
async function cancel() {
  if (action.value || cancelRequested.value || !cancellableTask(task.value,isBuild.value)) return
  action.value = true
  try { await ElMessageBox.confirm(cancellationPrompt(task.value.status), cancellationLabel(task.value.status), { confirmButtonText:cancellationLabel(task.value.status),cancelButtonText:tUi('继续执行'),type:'warning' }) } catch { action.value = false; return }
  try { const result=payload<{message?:string}>(await http.post(api(`/${props.kind}/${id}/cancel`)),{}); loadRevision++; cancelRequested.value=true; ElMessage.success(result.message ? tUi(result.message) : tUi('取消请求已提交，正在等待任务状态更新')); await load(true) } catch (value) { ElMessage.error(errorText(value)) } finally { action.value = false }
}
function node(status?: string) { return status === 'SUCCESS' ? 'ok' : status === 'FAILED' ? 'bad' : status === 'RUNNING' ? 'doing' : '' }
function icon(status?: string) { return status === 'SUCCESS' ? Check : status === 'FAILED' ? Close : status === 'RUNNING' ? Loading : undefined }
function maybeOfferPublish() {
  if (!isBuild.value || disposed) return
  if (autoPublish.value) { forgetBuildPublish(id); return }
  if (task.value.status === 'CANCELLED') { forgetBuildPublish(id); return }
  if (task.value.status === 'SUCCESS' && can('release') && document.visibilityState === 'visible') {
    const intent = consumeBuildPublishIntent(id)
    if (intent?.mode === 'CONFIRM') { plannedPublishTarget.value = intent.target; publishDialog.value = true }
  }
}
function manualPublish() { if(!canManualPublish.value)return;plannedPublishTarget.value = undefined; publishDialog.value = true }
onMounted(() => { document.addEventListener('visibilitychange', maybeOfferPublish); load() }); onBeforeUnmount(() => { disposed = true; if (timer) window.clearTimeout(timer); document.removeEventListener('visibilitychange', maybeOfferPublish) })
function autoPublishMessage(plan: BuildAutoPublish): string {
  if (plan.deploymentTaskId && plan.status === 'QUEUED') return tUi('已自动创建发布任务 #{0}', plan.deploymentTaskId)
  if (plan.deploymentTaskId && plan.status === 'LINKED_EXISTING') return tUi('已关联相同制品与节点的发布任务 #{0}', plan.deploymentTaskId)
  return tUi(plan.message || '')
}
</script>

<template>
  <div class="page-head" :class="{'mobile-task-head':isMobileTask}">
    <div :class="{'mobile-task-identity':isMobileTask}">
      <el-button link :icon="ArrowLeft" @click="router.push(apps?(task.serviceId?servicePath(task.serviceId,isBuild?'builds':'releases'):'/apps'):`/${kind}`)">{{ tUi('返回{0}记录', title) }}</el-button>
      <template v-if="isMobileTask">
        <h2 class="detail-heading mobile-task-heading">
          <span class="mobile-task-name">{{task.serviceName || tUi('正在读取服务')}}</span>
          <span class="mobile-task-version-info"><span class="mobile-task-version">{{mobileVersionLabel(task)}}</span><span class="detail-number">#{{id}}</span></span>
        </h2>
        <p class="mobile-task-branch"><span class="mobile-task-branch-label">{{ tUi('分支') }}</span><span>{{task.branch || '—'}}</span><template v-if="!isBuild"><span class="inline-separator">/</span><span>{{environmentLabel(task.environment)}}</span></template></p>
      </template>
      <template v-else>
        <h2 class="detail-heading task-service-heading"><ServiceIdentity :service="task" large/><span class="detail-number">{{title}} #{{id}}</span></h2>
        <p>{{ tUi('分支 {0}', task.branch || '—') }}<template v-if="!isBuild"><span class="inline-separator">/</span>{{environmentLabel(task.environment)}}</template></p>
      </template>
    </div>
    <div class="actions"><el-button :icon="Refresh" :loading="loading" @click="load()">{{ tUi('刷新') }}</el-button><el-button v-if="isBuild && task.status === 'SUCCESS' && can('release') && canManualPublish" type="primary" @click="manualPublish">{{ tUi('发布此版本') }}</el-button><ReleaseNodeLogs v-if="!isBuild && !apps && !isMobileDelivery && hasMenu('runtime') && task.status !== 'PENDING'" :task="task"/><ProductionPromotionAction v-if="!isBuild && !apps && !isMobileDelivery && task.status === 'SUCCESS' && task.environment === 'test' && task.serviceId && task.artifactId && can('release')" :service-id="task.serviceId" :artifact-id="task.artifactId"/><el-button v-if="canOperate && cancellableTask(task,isBuild)" type="danger" plain :loading="action" :disabled="cancelRequested" @click="cancel">{{cancelRequested?tUi('已请求中止'):cancellationLabel(task.status)}}</el-button></div>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon style="margin-bottom:18px" />
  <div v-if="isBuild && task.status" class="task-engine-strip" :class="{cloud:cloudBuild}"><el-icon><Cloudy v-if="cloudBuild"/><Monitor v-else/></el-icon><strong>{{engineTitle}}</strong><span>{{ tUi('{0}关闭页面不影响任务。', cloudBuild?tUi('云端完成编译，平台校验产物并继续后续步骤。'):tUi('在发版服务器执行构建。')) }}</span><a v-if="cloudRunUrl" :href="cloudRunUrl" target="_blank" rel="noopener noreferrer">{{ tUi('查看 GitHub 完整构建日志 ↗') }}</a></div>
  <el-alert v-if="cancelRequested && running" :title="tUi('已请求中止，正在等待当前命令退出')" :description="tUi('状态会自动更新。已经执行的远端操作不会自动撤销，取消后请检查运行状态。')" type="warning" :closable="false" show-icon style="margin-bottom:18px"/>
  <template v-if="task.status">
  <div class="task-banner" :class="String(task.status || '').toLowerCase()"><div class="task-banner-icon"><el-icon><Check v-if="task.status === 'SUCCESS'"/><Close v-else-if="task.status === 'FAILED'"/><Loading v-else-if="task.status === 'RUNNING'" class="rotating"/><Clock v-else/></el-icon></div><div><h3>{{statusLabel(task.status)}}</h3><p>{{task.status === 'WAITING_CONFIRMATION' ? tUi('安装包已锁定，请拉取后在外部完成发布，再确认该环境的实际发布结果。') : task.status === 'PENDING' ? positionText(id) : task.status === 'SUCCESS' ? tUi('{0}已完成，执行详情已归档。', title) : task.status === 'FAILED' ? String(task.errorMessage || tUi('请查看失败步骤和日志。')) : task.status === 'CANCELLED' ? (task.startedAt?tUi('任务已中止；已经执行的远端操作不会自动撤销，请核对运行状态。'):tUi('该任务已在执行前取消。')) : tUi('任务正在执行，状态和日志会自动更新。')}}</p></div><span v-if="running" class="live-indicator">{{ tUi('自动更新') }}</span></div>
  <TaskFailureGuide v-if="task.status==='FAILED'&&!isMobileDelivery" :task="task" :is-build="isBuild" :can-retry="canOperate" :retry-label="retryLabel" :retry-scope="retryScope" :busy="action||approvalLoading" @retry="requestRetry" @logs="taskLogs?.showFailure()"/>
  <el-alert v-if="overlappingReleases.length" :title="tUi('已有任务正在处理该包的目标节点')" type="warning" :closable="false" show-icon class="auto-publish-status"><el-button v-for="taskId in overlappingReleases" :key="taskId" link type="primary" @click="router.push('/releases/'+taskId)">{{ tUi('查看发布任务 #{0}', taskId) }}</el-button></el-alert>
  <el-alert v-if="autoPublish" :title="autoPublishTitle" :type="autoPublish.status==='BLOCKED'||autoPublish.status==='CANCELLED'?'warning':'info'" :closable="false" show-icon class="auto-publish-status">
    <p>{{ tUi('{0} · {1} 台服务器。{2}', environmentLabel(autoPublish.environment), autoPublish.serverIds.length, autoPublishMessage(autoPublish) || tUi('计划已保存到服务器，切换或关闭页面也会继续。')) }}</p>
    <el-button v-if="autoPublish.deploymentTaskId && can('release')" link type="primary" @click="router.push('/releases/'+autoPublish.deploymentTaskId)">{{ tUi('查看发布任务 #{0}', autoPublish.deploymentTaskId) }}</el-button>
    <p v-if="task.status==='FAILED'">{{ tUi('本次重试仅打包，成功后请手动发布。') }}</p>
  </el-alert>
  <div v-if="task.status === 'PENDING'" class="queue-notice compact"><span class="queue-symbol"><el-icon><Clock/></el-icon></span><div><b>{{summary}}</b><span>{{ tUi('云构建按可用名额调度，本机重步骤与发布按序执行。') }}</span></div></div>
  <MobileReleasePanel v-if="isMobileDelivery" :task="task" @changed="load(true)"/><div class="task-layout" :class="{'task-layout--mobile':isMobileDelivery}">
    <section v-if="!isMobileDelivery" class="panel task-steps-panel"><div class="panel-title"><h3>{{ tUi('执行步骤') }}</h3><span class="muted">{{ tUi('{0} / {1} 已完成', steps.filter(step=>step.status==='SUCCESS').length, steps.length) }}</span></div><div class="steps-list"><div v-for="(step,index) in steps" :key="step.id || index" class="step-line" :class="{'is-current':step.status==='RUNNING','is-failed':step.status==='FAILED'}"><div class="step-node" :class="node(step.status)"><el-icon v-if="icon(step.status)" :class="{rotating:step.status==='RUNNING'}"><component :is="icon(step.status)"/></el-icon><span v-else>{{index + 1}}</span></div><div class="step-info"><b>{{step.name}}</b><small>{{statusLabel(step.status)}}</small></div><div class="step-time">{{step.finishedAt || step.startedAt ? formatTime(step.finishedAt || step.startedAt) : '—'}}</div></div></div></section>
    <aside class="panel task-meta"><div class="panel-title"><h3>{{ tUi('任务信息') }}</h3></div><dl><dt>{{ tUi('服务') }}</dt><dd><ServiceIdentity :service="task"/></dd><dt>{{ tUi('分支') }}</dt><dd>{{task.branch || '—'}}</dd><dt>Commit</dt><dd><CommitHash :value="task.commitId"/></dd><template v-if="isBuild && task.serviceType==='FLUTTER_ANDROID'"><dt>{{ tUi('App 版本') }}</dt><dd>{{mobileVersionLabel(task)}}</dd><dt>{{ tUi('构建环境') }}</dt><dd>{{task.environment?environmentLabel(task.environment):tUi('历史记录未保存')}}</dd><dt>{{ tUi('构建模式') }}</dt><dd>{{task.mobileBuildMode || tUi('历史记录未保存')}}</dd></template><template v-if="!isBuild"><dt>{{ tUi('目标环境') }}</dt><dd>{{environmentLabel(task.environment)}}</dd><dt>{{ tUi('制品编号') }}</dt><dd>#{{task.artifactId || '—'}}</dd></template><dt>{{ tUi('发起人') }}</dt><dd>{{task.createdBy || '—'}}</dd><dt>{{ tUi('创建时间') }}</dt><dd>{{formatTime(task.createdAt)}}</dd><template v-if="task.startedAt"><dt>{{ tUi('开始时间') }}</dt><dd>{{formatTime(task.startedAt)}}</dd></template><template v-if="task.finishedAt"><dt>{{ tUi('完成时间') }}</dt><dd>{{formatTime(task.finishedAt)}}</dd></template><dt>{{ tUi('说明') }}</dt><dd>{{task.description || tUi('未填写')}}</dd></dl><details v-if="task.serviceType==='FLUTTER_ANDROID'&&task.versionCode" class="mobile-version-verification"><summary>{{ tUi('查看版本校验信息') }}</summary><p>{{ tUi('内部 versionCode：{0}', task.versionCode) }}</p><p v-if="task.preBuildTaskId">{{ tUi('来源 PRE 构建：#{0}', task.preBuildTaskId) }}</p><p>{{ tUi('内部编号用于 Android 覆盖升级与接口核验。') }}</p></details></aside>
  </div>
  <TaskGovernance :task-id="id" :build="isBuild" :build-branch="task.branch" :environment="task.environment" :task-status="task.status"/>
  <ReleaseNotifications v-if="!isBuild && !apps" :task-id="id" :task-status="task.status" :can-retry="canOperate" />
  <TaskLogs v-if="!isMobileDelivery" ref="taskLogs" :task="task" :steps="steps" :fallback-log="logs"/>
  </template>
  <div v-else class="panel empty" v-loading="loading"><div><strong>{{loading ? tUi('正在读取任务') : tUi('任务暂不可用')}}</strong><p>{{ tUi('请检查任务编号，或稍后刷新。') }}</p></div></div>
  <BuildPublishDialog v-if="isBuild" v-model="publishDialog" :task="task" :initial-target="plannedPublishTarget"/>
  <el-dialog v-model="otpDialog" :title="tUi('生产任务重试确认')" width="460px" :close-on-click-modal="false" :show-close="!action" :close-on-press-escape="!action">
    <div v-if="approvalLoading" class="empty" v-loading="true">{{ tUi('正在读取验证状态') }}</div><div v-else-if="unavailable" class="empty"><div><strong>{{ tUi('暂时无法读取验证状态') }}</strong><el-button @click="refreshApproval">{{ tUi('重新读取') }}</el-button></div></div>
    <template v-else-if="enabled"><p class="dialog-description">{{ tUi('即将重试生产任务') }} <b>#{{id}}</b>{{ tUi('，请输入当前账号') }} <b>{{approver}}</b> {{ tUi('的动态码确认。') }}</p><p class="dialog-description">{{retryScope}}</p><el-alert v-if="otpError" :title="otpError" type="error" :closable="false" show-icon style="margin-bottom:16px"/><div v-if="remaining" class="otp-countdown">{{ tUi('当前动态码已使用，请在') }} <b>{{remaining}}</b> {{ tUi('秒后输入新码。') }}</div><OtpInput v-model="otp" :disabled="action || remaining > 0" :label="tUi('生产任务重试动态码')" @submit="retry"/></template>
    <div v-else><el-alert :title="tUi('当前账号尚未绑定 Google Authenticator，请先完成绑定。')" type="warning" :closable="false"/><el-button link type="primary" @click="otpDialog=false; router.push('/settings/security')">{{ tUi('绑定我的验证器') }}</el-button></div>
    <template #footer><el-button :disabled="action" @click="otpDialog = false">{{ tUi('取消') }}</el-button><el-button type="danger" :loading="action" :disabled="!enabled || unavailable || approvalLoading || remaining > 0 || otp.length !== 6" @click="retry">{{ tUi('验证并重新排队') }}</el-button></template>
  </el-dialog>
</template>

<style scoped>
.page-head>div:first-child{min-width:0;flex:1}.page-head p{overflow-wrap:anywhere}
@media(max-width:650px){.page-head>div:first-child{flex-basis:100%}}
.task-layout{align-items:start}.task-layout>.panel{margin-top:0}
.task-engine-strip{display:flex;align-items:center;gap:10px 14px;padding:12px 16px;border:1px solid #dce3ec;border-radius:9px;background:#fff;color:#607087;font-size:12px;line-height:1.7;margin:0 0 18px;flex-wrap:wrap}.task-engine-strip>.el-icon{font-size:19px;color:#607087}.task-engine-strip strong{font-size:12px;color:#203149;font-weight:650}.task-engine-strip.cloud>.el-icon{color:#315fce}.task-engine-strip>span{flex:1;min-width:200px}.task-engine-strip a{color:#315fce;text-decoration:none;font-weight:600;white-space:nowrap}.task-engine-strip a:hover{text-decoration:underline}.task-steps-panel .step-info b{color:#203149;font-weight:600;font-size:13px}.task-steps-panel .step-info small,.task-steps-panel .step-time{color:#607087;font-size:11px}.task-steps-panel .step-line.is-current .step-info b{color:#315fce}.task-steps-panel .step-line.is-failed .step-info b{color:#bd4653}.task-steps-panel .step-node{width:30px;height:30px}.task-steps-panel .step-node.doing{background:#315fce;color:#fff;border-color:#315fce}.task-steps-panel .step-node.ok{color:#168269;background:#eaf6f0;border-color:#cce7d9}.task-steps-panel .step-node.bad{color:#bd4653;background:#fff0f1;border-color:#f2d8dc}
@media(max-width:650px){.task-engine-strip>span{flex-basis:calc(100% - 33px);margin-left:33px;min-width:0}.task-engine-strip>a{margin-left:33px}.task-steps-panel .step-line{grid-template-columns:30px minmax(0,1fr);column-gap:12px;row-gap:5px;padding-bottom:24px}.task-steps-panel .step-time{grid-column:2;padding-top:0}}
.task-service-heading{display:flex;align-items:center;gap:16px;flex-wrap:wrap}.task-service-heading>.service-identity{width:auto;flex:1;min-width:180px}.task-service-heading>.detail-number{flex:none;font-size:16px;color:#8291a6;font-weight:500}

.mobile-task-identity{min-width:0;flex:1}
.page-head .mobile-task-heading{display:flex;align-items:center;flex-wrap:wrap;gap:10px 16px;font-size:28px;font-weight:700;line-height:1.4}
.mobile-task-name{min-width:0;overflow-wrap:anywhere}
.mobile-task-version-info{display:inline-flex;align-items:center;flex-wrap:wrap;gap:10px;min-width:0}
.mobile-task-version{padding:3px 9px;border-radius:6px;background:#eaf0ff;color:#416bdc;font-size:16px;font-weight:600;overflow-wrap:anywhere}
.mobile-task-heading .detail-number{font-size:16px}
.page-head .mobile-task-branch{display:flex;align-items:baseline;flex-wrap:wrap;gap:6px 10px;color:#536178;font-size:13px;line-height:1.7;overflow-wrap:anywhere}
.mobile-task-branch-label{color:#74849a;flex-shrink:0}
.mobile-task-branch .inline-separator{margin:0}
.mobile-task-head>.actions{flex-shrink:0}
@media(max-width:720px){.mobile-task-identity{flex-basis:100%}.page-head .mobile-task-heading{font-size:24px;gap:8px 12px}.mobile-task-version{font-size:14px}.mobile-task-heading .detail-number{font-size:14px}.page-head .mobile-task-branch{font-size:12px}}
.auto-publish-status{margin:0 0 18px}.auto-publish-status p{margin:8px 0;line-height:1.7}
.mobile-version-verification{padding-top:15px;border-top:1px solid #edf1f6;color:#74849a;font-size:12px;line-height:1.8}.mobile-version-verification summary{cursor:pointer;color:#416bdc}.mobile-version-verification p{margin:8px 0}
.task-layout.task-layout--mobile{display:block}.task-layout--mobile .task-meta dl{grid-template-columns:110px minmax(0,1fr) 110px minmax(0,1fr);column-gap:18px}.task-layout--mobile .task-meta{margin-top:0}@media(max-width:800px){.task-layout--mobile .task-meta dl{grid-template-columns:76px minmax(0,1fr);column-gap:0}}
</style>
