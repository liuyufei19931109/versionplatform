<script setup lang="ts">
import { tUi } from '../i18n'

import { serverConnectionLabel } from '../serverConnection'
import { useAppScope } from '../composables/useAppScope'
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Check, Lock, Promotion, RefreshRight } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { errorText, http, normalizeService, payload, rows } from '../api'
import { useProductionApproval } from '../composables/useProductionApproval'
import { useReleaseSubmission } from '../composables/useReleaseSubmission'
import type { Artifact, DeploymentServer, Release, Service, ServiceEnvironment } from '../types'
import { environmentLabel } from '../ui'
import { mobileArtifactEnvironmentError } from '../mobile'
import CommitHash from './CommitHash.vue'
import OtpInput from './OtpInput.vue'
import DeploymentReviewPanel from './DeploymentReviewPanel.vue'
import { reviewConfirmationLabel, useDeploymentReview } from '../composables/useDeploymentReview'
import type { BuildPublishTarget } from '../buildPublishIntent'
import { PRODUCTION_GATE_MESSAGE, productionEligible, readinessMap, type ProductionReadiness } from '../productionGate'

const { api, can, releasePath } = useAppScope()
const props = defineProps<{ modelValue: boolean; task: Release; initialTarget?: BuildPublishTarget }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const router = useRouter()
const loading = ref(false), loadError = ref(''), submitError = ref('')
const artifact = ref<Artifact>(), service = ref<Service>(), environments = ref<ServiceEnvironment[]>([]), servers = ref<DeploymentServer[]>([])
const form = reactive({ environment: '', serverIds: [] as string[], releaseNote: '' })
const approvalVisible = ref(false), code = ref('')
const useInitialTarget = ref(false)
// undefined = unknown (the server still enforces the rule); false = this artifact has not passed pre-production yet.
const productionAllowed = ref<boolean>()
const productionPresetBlocked = ref(false)
const approval = useProductionApproval()
const { approver, enabled, remaining, loading: approvalLoading, unavailable } = approval
const { submitting, enqueue } = useReleaseSubmission()
let request = 0, disposed = false
const open = computed({ get: () => props.modelValue, set: value => { if (!submitting.value) emit('update:modelValue', value) } })
const isMobile = computed(() => service.value?.serviceType === 'FLUTTER_ANDROID')
const selectedEnvironment = computed(() => environments.value.find(env => env.environmentName === form.environment))
const environmentServers = computed(() => (selectedEnvironment.value?.serverIds || []).map(id => servers.value.find(server => String(server.id) === String(id))).filter(Boolean) as DeploymentServer[])
const availableServers = computed(() => environmentServers.value.filter(server => server.enabled))
const selectedServers = computed(() => availableServers.value.filter(server => form.serverIds.includes(String(server.id))))
const ready = computed(() => Boolean(can('release') && artifact.value && service.value && !loading.value && !loadError.value && (isMobile.value ? !mobileArtifactEnvironmentError(artifact.value,form.environment) : selectedEnvironment.value?.enabled && selectedServers.value.length && selectedServers.value.length === form.serverIds.length)))
const reviewTarget = computed(() => {
  if (!open.value || !ready.value || isMobile.value || !artifact.value) return null
  return { serviceId: Number(props.task.serviceId), artifactId: Number(artifact.value.id), environment: form.environment, serverIds: selectedServers.value.map(server => Number(server.id)) }
})
const { result: review, key: reviewKey, loading: reviewing, error: reviewError, refresh: refreshReview, applyConflict } = useDeploymentReview(reviewTarget)
const reviewReady = computed(() => isMobile.value || Boolean(review.value && review.value.action !== 'ACTIVE'))
watch(reviewKey, () => { approvalVisible.value = false; code.value = ''; submitError.value = '' })
const canConfirmProduction = computed(() => ready.value && enabled.value && !approvalLoading.value && !unavailable.value && remaining.value === 0 && /^\d{6}$/.test(code.value))

async function load() {
  if (submitting.value) return
  const current = ++request, taskId = String(props.task.id), serviceId = String(props.task.serviceId || '')
  loading.value = true; loadError.value = ''; productionAllowed.value = undefined; productionPresetBlocked.value = false; artifact.value = undefined; service.value = undefined; environments.value = []; servers.value = []; form.serverIds = []
  try {
    if (!can('release')) throw new Error(tUi('当前账号没有新建发版权限'))
    if (props.task.status !== 'SUCCESS' || !serviceId) throw new Error(tUi('只有打包成功的任务可以发布'))
    const [artifactResult, serviceResult] = await Promise.all([
      http.get(api('/artifacts'), { params: { serviceId } }), http.get(api('/services')),
    ])
    if (disposed || current !== request || !props.modelValue || String(props.task.id) !== taskId) return
    // BUILD tasks do not carry artifactId. Never fall back to the service's latest artifact.
    const matching = rows<Artifact>(artifactResult.data).filter(item => String(item.buildTaskId) === taskId)
    if (matching.length !== 1 || !matching[0]?.id) throw new Error(tUi('暂未找到本次构建的唯一制品，请重新读取；不会改用其他版本'))
    const built = matching[0]
    if (built.serviceId != null && String(built.serviceId) !== serviceId || props.task.commitId && built.commitId !== props.task.commitId) throw new Error(tUi('本次构建与制品版本不一致，请检查打包记录'))
    const target = rows<Record<string, unknown>>(serviceResult.data).map(normalizeService).find(item => String(item.id) === serviceId)
    if (!target || target.status === false || target.status === 'DISABLED') throw new Error(tUi('该服务已停用或不存在，请先检查服务配置'))
    artifact.value = built; service.value = target
    if (isMobile.value) {
      const metadata = payload<Partial<Artifact>>(await http.get(api(`/mobile/artifacts/${built.id}`)), {})
      if (disposed || current !== request || !props.modelValue) return
      artifact.value = { ...built, ...metadata, id: built.id }; form.environment = artifact.value.buildEnvironmentName || ''
      const reason = mobileArtifactEnvironmentError(artifact.value)
      if (reason) throw new Error(reason)
    } else {
      const [environmentResult, serverResult, readinessResult] = await Promise.all([http.get(api(`/services/${serviceId}/environments`)), http.get(api('/servers')),
        http.get('/releases/production-readiness', { params: { serviceId } }).then(result => rows<ProductionReadiness>(result.data), () => null)])
      if (disposed || current !== request || !props.modelValue) return
      environments.value = rows<ServiceEnvironment>(environmentResult.data); servers.value = rows<DeploymentServer>(serverResult.data)
      if (readinessResult) productionAllowed.value = productionEligible(readinessMap(readinessResult).get(String(built.id)))
    }
    if (!isMobile.value && useInitialTarget.value && props.initialTarget?.environment === 'prod' && productionAllowed.value === false) {
      // A new package cannot go to production first. Keep the dialog usable for pre-production instead of failing.
      productionPresetBlocked.value = true; useInitialTarget.value = false; selectServers()
    } else if (!isMobile.value && useInitialTarget.value && props.initialTarget) {
      const target = props.initialTarget
      form.environment = target.environment
      form.serverIds = target.serverIds.map(String)
      if (!selectedEnvironment.value?.enabled || !form.serverIds.length || form.serverIds.some(id => !availableServers.value.some(server => String(server.id) === id))) throw new Error(tUi('打包前选择的环境或服务器已停用、移除或调整，请重新选择发布目标。不会自动换成其他服务器。'))
    } else selectServers()
  } catch (reason) { if (!disposed && current === request) loadError.value = errorText(reason, tUi('读取发布配置失败')) }
  finally { if (!disposed && current === request) loading.value = false }
}
function selectServers() { form.serverIds = selectedEnvironment.value?.enabled ? availableServers.value.map(server => String(server.id)) : [] }
watch(() => form.environment, () => { selectServers(); approvalVisible.value = false; code.value = ''; submitError.value = '' }, { flush: 'sync' })
function reselectTarget() { useInitialTarget.value = false; form.environment = ''; form.serverIds = []; void load() }
watch(() => props.modelValue, value => {
  if (value) { useInitialTarget.value = Boolean(props.initialTarget); form.environment = ''; form.serverIds = []; form.releaseNote = ''; approvalVisible.value = false; code.value = ''; submitError.value = ''; load() }
  else { request++; loading.value = false; approvalVisible.value = false; code.value = '' }
}, { immediate: true })
async function requestPublish() {
  if (!ready.value || !reviewReady.value || submitting.value) return
  submitError.value = ''
  if (form.environment === 'prod' || review.value?.action === 'CONFIRM') {
    approvalVisible.value = true
    code.value = ''
    if (form.environment === 'prod') await approval.refresh()
    return
  }
  await publish()
}
async function publish() {
  if (!ready.value || !reviewReady.value || submitting.value || !artifact.value) return
  if (review.value?.action === 'CONFIRM' && !approvalVisible.value) return
  if (form.environment === 'prod' && (!approvalVisible.value || !canConfirmProduction.value)) return
  try {
    const task = await enqueue({ serviceId: Number(props.task.serviceId), artifactId: Number(artifact.value.id), environment: form.environment, serverIds: selectedServers.value.map(server => Number(server.id)), releaseNote: form.releaseNote }, form.environment === 'prod' ? code.value : '', isMobile.value, review.value?.confirmationToken || undefined)
    if (!task || disposed) return
    ElMessage.success(isMobile.value ? tUi('App 发布记录已创建，请拉取安装包并确认发布结果') : tUi('发版任务已加入队列'))
    emit('update:modelValue', false)
    await router.replace(releasePath(String(task.id)))
  } catch (reason) {
    if (disposed) return
    if (!isMobile.value && applyConflict(reason)) {
      approvalVisible.value = review.value?.action === 'CONFIRM'
      submitError.value = tUi('提交前目标状态已变化，请重新核对。')
      if (approvalVisible.value && form.environment === 'prod') await approval.refresh()
      return
    }
    approval.handleError(reason)
    code.value = ''
    submitError.value = errorText(reason, tUi('创建发版任务失败'))
  }
}
onBeforeUnmount(() => { disposed = true; request++ })
</script>

<template>
  <el-dialog v-model="open" :title="tUi('发布本次构建')" width="640px" class="build-publish-dialog" :close-on-click-modal="false" :close-on-press-escape="!submitting" :show-close="!submitting">
    <div class="build-publish-content" v-loading="loading">
      <div class="built-version">
        <span class="built-check"><el-icon><Check/></el-icon></span>
        <div class="built-description"><h3>{{service?.name || task.serviceName}}</h3><div class="built-meta"><span class="built-success">{{ tUi('打包成功') }}</span><span>{{ tUi('构建 #{0}', task.id) }}</span><span>{{service?.code || task.serviceKey}}</span></div></div>
      </div>
      <div class="built-code"><span>{{ tUi('本次版本') }}</span><b v-if="isMobile&&artifact?.versionName">{{artifact.versionName}}</b><b :title="artifact?.branch || task.branch">{{artifact?.branch || task.branch || tUi('未记录分支')}}</b><CommitHash :value="artifact?.commitId || task.commitId"/></div>
      <template v-if="loadError"><el-alert :title="loadError" type="error" :closable="false" show-icon/><el-button class="publish-reload" :icon="RefreshRight" :loading="loading" @click="load">{{ tUi('重新读取发布配置') }}</el-button><el-button v-if="useInitialTarget" class="publish-reload" :disabled="loading" @click="reselectTarget">{{ tUi('重新选择发布目标') }}</el-button></template>
      <el-form v-else label-position="top" :disabled="submitting || loading || approvalVisible" class="build-publish-form">
        <p v-if="useInitialTarget&&!isMobile" class="publish-hint preset-notice">{{ tUi('已带入打包前选择的发布目标，可在下方修改。确认加入队列后才会发布。') }}</p><el-form-item :label="tUi('发布环境')" required>
          <el-radio-group v-if="!isMobile" v-model="form.environment" class="publish-environments" :aria-label="tUi('发布环境')">
            <el-radio-button value="dev">{{ tUi('开发') }}</el-radio-button><el-radio-button value="test">{{ tUi('预生产') }}</el-radio-button><el-radio-button value="prod" :disabled="productionAllowed===false">{{ tUi('生产') }}</el-radio-button>
          </el-radio-group>
          <p v-if="!isMobile&&productionAllowed===false" class="publish-hint production-gate-hint">{{ tUi('{0}{1}。请先发布到预生产，通过健康检查后再从发版记录发布到生产。', productionPresetBlocked?tUi('打包前预选了生产：'):'', tUi(PRODUCTION_GATE_MESSAGE)) }}</p>
          <div v-if="isMobile" class="mobile-locked-target"><el-tag :type="form.environment==='prod'?'danger':'warning'" effect="plain">{{environmentLabel(form.environment)}}</el-tag><b>{{artifact?.applicationId}}</b><span>{{artifact?.buildMode==='DEBUG'?'Debug':'Release'}} · {{artifact?.format}}</span><small>{{ tUi('环境已在构建时锁定。发布其他环境需要重新构建对应安装包。') }}</small></div>
          <p v-if="!form.environment" class="publish-hint">{{isMobile?tUi('选择安装包要发布的环境，每个环境独立记录结果。'):tUi('先选择环境，再确认要更新的服务器。')}}</p>
        </el-form-item>
        <template v-if="form.environment">
          <el-alert v-if="isMobile" :title="form.environment==='prod'?tUi('生产发布确认成功后，自动合回本次 Commit 到 master。'):tUi('本环境只记录发布结果，不自动合并代码。')" :description="tUi('创建记录后拉取安装包，由外部系统发布，再确认实际发布结果。包名、签名和构建参数均使用本次构建时锁定的配置。')" type="info" :closable="false" show-icon/>
          <el-alert v-else-if="!selectedEnvironment?.enabled || !availableServers.length" :title="tUi('该环境暂无可用服务器，请先完成环境与集群配置。')" type="warning" :closable="false" show-icon/>
          <el-form-item v-else :label="tUi('发布服务器')" required>
            <div class="publish-server-summary"><span>{{ tUi('已选 {0} / {1} 台', selectedServers.length, availableServers.length) }}</span><el-button link type="primary" :disabled="submitting || loading || approvalVisible" @click="selectServers">{{ tUi('全选') }}</el-button></div>
            <el-checkbox-group v-model="form.serverIds" class="publish-server-list" :aria-label="tUi('发布服务器')">
              <el-checkbox v-for="server in environmentServers" :key="server.id" :value="String(server.id)" :disabled="!server.enabled || submitting || loading || approvalVisible" border><span><b>{{server.name}}</b><small>{{serverConnectionLabel(server,{includeUser:false})}}{{server.enabled?'':tUi(' · 已停用')}}</small></span></el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item :label="tUi('发版说明（选填）')"><el-input v-model="form.releaseNote" type="textarea" :rows="2" :placeholder="tUi('本次发布的变更说明')"/></el-form-item>
        </template>
      </el-form>
      <DeploymentReviewPanel v-if="reviewTarget" :review="review" :environment="form.environment" :servers="servers" :loading="reviewing" :error="reviewError" @retry="refreshReview"/>
      <div v-if="approvalVisible && form.environment!=='prod'" class="publish-production"><h4>{{reviewConfirmationLabel(review)}}<el-button link type="primary" :disabled="submitting" @click="approvalVisible=false;submitError=''">{{ tUi('返回修改') }}</el-button></h4><p>{{service?.name}} · {{environmentLabel(form.environment)}} · {{selectedServers.map(server=>server.name).join('、')}}</p></div>
      <div v-else-if="approvalVisible" class="publish-production">
        <h4><el-icon><Lock/></el-icon>{{ tUi('生产发版二次确认') }}<el-button link type="primary" :disabled="submitting" @click="approvalVisible=false; code=''; submitError=''">{{ tUi('返回修改') }}</el-button></h4>
        <p>{{ tUi('{0} 将发布到{1}{2}。请输入当前账号', service?.name, environmentLabel(form.environment), isMobile?tUi('，创建安装包发布记录'):tUi('，共 ')+selectedServers.length+tUi(' 台服务器')) }}<b>{{approver}}</b> {{ tUi('的动态码。') }}</p>
        <div v-if="approvalLoading" class="publish-hint">{{ tUi('正在读取验证状态…') }}</div>
        <div v-else-if="unavailable" class="publish-hint">{{ tUi('暂时无法读取验证状态。') }}<el-button link type="primary" @click="approval.refresh">{{ tUi('重新读取') }}</el-button></div>
        <template v-else-if="enabled"><div v-if="remaining" class="otp-countdown">{{ tUi('当前动态码已使用，请在') }} <b>{{remaining}}</b> {{ tUi('秒后输入新码。') }}</div><OtpInput v-model="code" :disabled="submitting || remaining > 0" :label="tUi('生产发版确认动态码')" @submit="publish"/></template>
        <template v-else><p>{{ tUi('当前账号尚未绑定 Google Authenticator，请先完成绑定。') }}</p><el-button link type="primary" @click="open=false; router.push('/settings/security')">{{ tUi('绑定我的验证器') }}</el-button></template>
      </div>
      <el-alert v-if="submitError" :title="submitError" type="error" :closable="false" show-icon class="publish-submit-error"/>
    </div>
    <template #footer><div class="publish-footer"><small>{{ tUi('稍后也可从本任务发布此版本') }}</small><div><el-button :disabled="submitting" @click="open=false">{{ tUi('暂不发布') }}</el-button><el-button v-if="approvalVisible" type="danger" :disabled="!reviewReady || (form.environment==='prod'&&!canConfirmProduction)" :loading="submitting" @click="publish">{{form.environment!=='prod'?reviewConfirmationLabel(review):isMobile?tUi('验证并创建发布记录'):review?.action==='CONFIRM'?tUi('验证并')+reviewConfirmationLabel(review):tUi('验证并加入队列')}}</el-button><el-button v-else type="primary" :icon="Promotion" :disabled="!ready || !reviewReady" :loading="submitting" @click="requestPublish">{{isMobile?tUi('创建发布记录'):review?.action==='ACTIVE'?tUi('所选节点已有任务'):review?.action==='CONFIRM'?tUi('核对并发布'):tUi('加入发版队列')}}</el-button></div></div></template>
  </el-dialog>
</template>

<style scoped>
.production-gate-hint{margin:10px 0 0;color:#b4532a!important}
.mobile-locked-target{display:flex;align-items:center;flex-wrap:wrap;gap:10px;font-size:12px}.mobile-locked-target b{overflow-wrap:anywhere;font-weight:500}.mobile-locked-target small{flex-basis:100%;font-size:12px;line-height:1.8;color:#74849a}

.preset-notice{margin:0 0 18px}.build-publish-content{min-height:150px}.built-version{display:flex;align-items:center;gap:14px;margin-bottom:19px}.built-check{display:grid;place-items:center;width:42px;height:42px;flex:none;background:#eaf6ef;color:#2b9a70;border-radius:50%;font-size:22px}.built-description{min-width:0}.built-description h3{margin:0 0 7px;color:#263a56;font-size:22px;font-weight:650;overflow-wrap:anywhere}.built-meta{display:flex;align-items:center;flex-wrap:wrap;gap:8px 13px;font-size:12px;color:#74849a}.built-success{color:#2b8e69;font-weight:500}.built-code{display:flex;align-items:center;flex-wrap:wrap;gap:10px 15px;padding:13px 0 18px;border-bottom:1px solid #e6ecf4;margin-bottom:22px;font-size:12px}.built-code>span{color:#74849a}.built-code>b{color:#354b68;font-size:13px;overflow-wrap:anywhere;min-width:0}.built-code :deep(.commit-hash){margin-left:auto}.build-publish-form :deep(.el-form-item){margin-bottom:21px}.publish-environments{width:100%;display:flex}.publish-environments :deep(.el-radio-button){flex:1}.publish-environments :deep(.el-radio-button__inner){width:100%;padding:12px 8px}.publish-hint{width:100%;color:#74849a;font-size:12px;line-height:1.7;margin:11px 0 0}.publish-server-summary{display:flex;justify-content:space-between;align-items:center;width:100%;margin-bottom:8px;font-size:12px;color:#74849a}.publish-server-list{display:grid;gap:8px;width:100%}.publish-server-list :deep(.el-checkbox){height:auto;min-height:57px;margin:0;width:100%;padding:11px 13px}.publish-server-list :deep(.el-checkbox__label){min-width:0;width:100%;padding-left:11px;white-space:normal}.publish-server-list b{display:block;font-size:13px;font-weight:550;color:#354b68;overflow-wrap:anywhere}.publish-server-list small{display:block;font-size:12px;color:#74849a;margin-top:4px;overflow-wrap:anywhere}.publish-production{padding:17px 18px 20px;background:#fff8f5;border:1px solid #f1ded5;border-radius:9px;margin-top:15px}.publish-production h4{display:flex;align-items:center;gap:7px;margin:0;color:#9e5d39;font-size:13px}.publish-production p{font-size:12px;line-height:1.8;color:#866b5c;margin:11px 0 16px}.publish-submit-error,.publish-reload{margin-top:16px}.publish-footer{display:flex;align-items:center;justify-content:space-between;gap:12px;text-align:left}.publish-footer>small{color:#74849a;font-size:11px}.publish-footer>div{display:flex;gap:9px;flex:none}.build-publish-form :deep(.el-alert){margin-bottom:21px}
@media(max-width:650px){.built-description h3{font-size:20px}.built-check{width:35px;height:35px;font-size:19px}.built-code{gap:9px 12px}.publish-footer{align-items:stretch;flex-direction:column;gap:14px}.publish-footer>div{justify-content:flex-end}.publish-production{padding:15px 10px}.publish-footer>small{font-size:12px}}
</style>
