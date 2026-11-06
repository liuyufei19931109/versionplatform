<script setup lang="ts">
import { tUi, currentLocale } from '../i18n'

import { serverConnectionLabel } from '../serverConnection'
import { useAppScope } from '../composables/useAppScope'
import CommitHash from '../components/CommitHash.vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Box, Lock, Promotion, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { errorText, http, normalizeService, payload, rows } from '../api'
import type { Artifact, DeploymentServer, Service, ServiceEnvironment } from '../types'
import OtpInput from '../components/OtpInput.vue'
import ServiceSelect from '../components/ServiceSelect.vue'
import ServiceIdentity from '../components/ServiceIdentity.vue'
import ArtifactEnvironmentHistory from '../components/ArtifactEnvironmentHistory.vue'
import DeploymentReviewPanel from '../components/DeploymentReviewPanel.vue'
import { reviewConfirmationLabel, useArtifactHistory, useDeploymentReview } from '../composables/useDeploymentReview'
import { useProductionApproval } from '../composables/useProductionApproval'
import { useQueue } from '../composables/useQueue'
import { environmentLabel, timeParts } from '../ui'
import { useReleaseSubmission } from '../composables/useReleaseSubmission'
import { validEnvironment, validServiceId } from '../releaseHistory'
import { mobileArtifactEnvironmentError, mobileVersionLabel } from '../mobile'
import { PRODUCTION_GATE_MESSAGE, productionEligible, readinessBadge, readinessMap, type ProductionReadiness } from '../productionGate'


const { apps, api, releasePath, servicePath } = useAppScope()
const route = useRoute()
const router = useRouter()
const loading = ref(true)
const serviceLoadError = ref('')
const artifactLoadState = ref<'idle'|'loading'|'ready'|'error'>('idle')
const artifactLoadError = ref('')
const readinessLoading = ref(false)
const environmentLoading = ref(false), environmentLoadError = ref('')
let artifactController: AbortController | undefined
const { submitting, enqueue } = useReleaseSubmission()
const services = ref<Service[]>([])
const artifacts = ref<Artifact[]>([])
const environments = ref<ServiceEnvironment[]>([])
const servers = ref<DeploymentServer[]>([])
const artifactDialog = ref(false)
const artifactKeyword = ref('')
const artifactPage = ref(1)
const artifactPageSize = ref(5)
const pendingArtifactId = ref('')
const readiness = ref(new Map<string, ProductionReadiness>())
const readinessError = ref('')
const { approver, enabled: totpEnabled, remaining: totpRemaining, loading: totpLoading, unavailable: totpUnavailable, refresh: loadTotpStatus, handleError: handleTotpError } = useProductionApproval()
const { summary: queueSummary, refreshQueue } = useQueue()
const totpError = ref('')
let artifactRequest = 0, environmentRequest = 0
const totpDialog = ref(false)
const totpCode = ref('')

const form = reactive({
  serviceId: validServiceId(apps?route.params.serviceId:route.query.serviceId),
  artifactId: String(route.query.artifactId || ''),
  environment: String(validEnvironment(route.query.environment) || 'test'),
  serverIds: [] as string[],
  releaseNote: '',
})

const selectedService = computed(() => services.value.find((service) => String(service.id) === form.serviceId))
const isMobile = computed(() => apps || selectedService.value?.serviceType === 'FLUTTER_ANDROID')
const recentArtifacts = computed(() => artifacts.value.slice(0, 3))
const selectedArtifact = computed(() => artifacts.value.find((artifact) => String(artifact.id) === form.artifactId))
const selectedEnvironment = computed(() => environments.value.find((environment) => environment.environmentName === form.environment))
const historyServiceId = computed(() => isMobile.value || !selectedService.value ? null : Number(form.serviceId))
const history = useArtifactHistory(historyServiceId)
const reviewTarget = computed(() => {
  if (isMobile.value || !selectedArtifact.value || !selectedEnvironment.value?.enabled || !form.serverIds.length) return null
  return { serviceId: Number(form.serviceId), artifactId: Number(selectedArtifact.value.id), environment: form.environment, serverIds: form.serverIds.map(Number) }
})
const { result: review, loading: reviewing, error: reviewError, refresh: refreshReview, applyConflict, key: reviewKey } = useDeploymentReview(reviewTarget)
const reviewReady = computed(() => isMobile.value || Boolean(review.value && review.value.action !== 'ACTIVE'))
watch(reviewKey, () => { totpDialog.value = false; totpCode.value = ''; totpError.value = '' })
const appArtifactError = computed(() => isMobile.value && selectedArtifact.value ? mobileArtifactEnvironmentError(selectedArtifact.value, form.environment) : '')
const environmentReady = computed(() => isMobile.value ? Boolean(selectedArtifact.value && !appArtifactError.value) : Boolean(selectedEnvironment.value?.enabled && selectedEnvironment.value.serverIds?.length))
const environmentServers = computed(() => (selectedEnvironment.value?.serverIds||[]).map(id=>servers.value.find(server=>String(server.id)===String(id))).filter(Boolean) as DeploymentServer[])
const targetServers = computed(() => environmentServers.value.filter(server=>form.serverIds.includes(String(server.id))))
const filteredArtifacts = computed(() => {
  const keyword = artifactKeyword.value.trim().toLowerCase()
  if (!keyword) return artifacts.value
  return artifacts.value.filter((artifact) =>
    [artifact.version, artifact.branch, artifact.commitId, artifact.imageUrl, artifact.buildTaskId, artifact.buildEnvironmentName ? environmentLabel(artifact.buildEnvironmentName) : '']
      .some((value) => String(value || '').toLowerCase().includes(keyword)),
  )
})
const productionGate = computed(() => !isMobile.value && form.environment === 'prod')
// When the status cannot be read, leave the decision to the server instead of blocking every package.
function artifactBlocked(artifact?: Artifact) { return Boolean(artifact && productionGate.value && !readinessLoading.value && !readinessError.value && !productionEligible(readiness.value.get(String(artifact.id)))) }
function artifactReadiness(artifact?: Artifact) { return artifact ? readiness.value.get(String(artifact.id)) : undefined }
const selectedBlocked = computed(() => artifactBlocked(selectedArtifact.value))
function artifactBadge(artifact?: Artifact) { return readinessLoading.value ? { text: tUi('正在读取验证状态'), type: 'info' as const } : readinessBadge(artifactReadiness(artifact)) }
function artifactChoiceError(artifact: Artifact) { return isMobile.value ? mobileArtifactEnvironmentError(artifact) : productionGate.value && readinessLoading.value ? tUi('正在读取预生产验证状态') : artifactBlocked(artifact) ? tUi(PRODUCTION_GATE_MESSAGE) : '' }
function selectRecentArtifact(artifact: Artifact) { if (artifactLoadState.value !== 'ready' || artifactChoiceError(artifact)) return; form.artifactId = String(artifact.id) }
function artifactRowClass({ row }: { row: Artifact }) { return artifactBlocked(row) ? 'gate-blocked-row' : '' }
const pagedArtifacts = computed(() => {
  const start = (artifactPage.value - 1) * artifactPageSize.value
  return filteredArtifacts.value.slice(start, start + artifactPageSize.value)
})

function formatTime(value?: string) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString(currentLocale(), { hour12: false })
}

async function loadServices() {
  loading.value = true
  try {
    const [serviceResult,serverResult]=await Promise.all([http.get(api('/services')),apps?Promise.resolve({data:[]}):http.get(api('/servers'))])
    serviceLoadError.value = ''
    services.value = rows<Record<string, unknown>>(serviceResult.data).map(normalizeService).filter(service=>service.status!==false&&service.status!=='DISABLED'&&(!apps||service.serviceType==='FLUTTER_ANDROID'))
    servers.value = rows<DeploymentServer>(serverResult.data)
    if(environments.value.length)selectAllEnvironmentServers()
    if (form.serviceId && !services.value.some(service => String(service.id) === form.serviceId)) {
      form.serviceId = ''; ElMessage.warning(tUi('预选服务已停用或不存在，请重新选择'))
    }
    if (!apps && !form.serviceId && services.value.length) form.serviceId = String(services.value[0].id)
  } catch (error) {
    serviceLoadError.value = errorText(error, tUi('暂时无法读取服务列表'))
    ElMessage.error(serviceLoadError.value)
  } finally {
    loading.value = false
  }
}

async function loadArtifacts() {
  const request = ++artifactRequest
  artifactController?.abort()
  const controller = new AbortController(); artifactController = controller
  const serviceId = form.serviceId, mobile = isMobile.value
  const wantedArtifactId = String(route.query.artifactId || '')
  artifacts.value = []; form.artifactId = ''; readiness.value = new Map()
  readinessError.value = ''; artifactLoadError.value = ''; readinessLoading.value = false
  pendingArtifactId.value = ''; artifactDialog.value = false; artifactPage.value = 1
  if (!selectedService.value || !serviceId) { artifactLoadState.value = 'idle'; return }
  artifactLoadState.value = 'loading'
  if (!mobile) {
    readinessLoading.value = true
    void (async () => {
      try {
        const result = await http.get('/releases/production-readiness', { params: { serviceId }, signal: controller.signal })
        if (request === artifactRequest) readiness.value = readinessMap(rows<ProductionReadiness>(result.data))
      } catch {
        if (request === artifactRequest) readinessError.value = tUi('暂时无法读取预生产验证状态，提交时由平台校验')
      } finally { if (request === artifactRequest) readinessLoading.value = false }
    })()
  }
  try {
    const result = rows<Artifact>((await http.get(api('/artifacts'), { params: { serviceId }, signal: controller.signal })).data)
    if (request !== artifactRequest) return
    const enriched = apps ? await Promise.all(result.map(async item => {
      try { return {...item,...payload<Partial<Artifact>>(await http.get(api(`/mobile/artifacts/${item.id}`), {signal:controller.signal}),{}),id:item.id} }
      catch { return item }
    })) : result
    if (request !== artifactRequest) return
    artifacts.value = [...enriched].sort((a,b) => (Date.parse(b.createdAt || '') || 0) - (Date.parse(a.createdAt || '') || 0) || Number(b.id) - Number(a.id))
    form.artifactId = artifacts.value.some(item => String(item.id) === wantedArtifactId) ? wantedArtifactId : ''
    artifactLoadState.value = 'ready'
  } catch (error) {
    if (request !== artifactRequest) return
    artifacts.value = []; form.artifactId = ''; artifactLoadState.value = 'error'
    artifactLoadError.value = /timeout/i.test(String((error as {message?:string}).message)) ? tUi('读取包版本超时，请重新读取。') : errorText(error, tUi('暂时无法读取包版本，请重新读取。'))
  }
}

function selectAllEnvironmentServers(){if(isMobile.value){form.serverIds=[];return}form.serverIds=environmentServers.value.filter(server=>server.enabled).map(server=>String(server.id))}
async function loadEnvironments() {
  const request=++environmentRequest;form.serverIds=[];environments.value=[];environmentLoadError.value=''
  if(!selectedService.value||!form.serviceId||isMobile.value){environmentLoading.value=false;return}
  environmentLoading.value=true
  try{const value=rows<ServiceEnvironment>((await http.get(api(`/services/${form.serviceId}/environments`))).data);if(request!==environmentRequest)return;environments.value=value;selectAllEnvironmentServers()}
  catch(error){if(request===environmentRequest){environments.value=[];environmentLoadError.value=errorText(error,tUi('暂时无法读取环境配置'))}}
  finally{if(request===environmentRequest)environmentLoading.value=false}
}

function openArtifactDialog() {
  if (artifactLoadState.value !== 'ready') return
  pendingArtifactId.value = form.artifactId
  artifactKeyword.value = ''
  const selectedIndex = artifacts.value.findIndex((artifact) => String(artifact.id) === form.artifactId)
  artifactPage.value = selectedIndex < 0 ? 1 : Math.floor(selectedIndex / artifactPageSize.value) + 1
  artifactDialog.value = true
}

function chooseArtifact(artifact: Artifact) {
  if (artifactChoiceError(artifact)) return
  pendingArtifactId.value = String(artifact.id)
}

function confirmArtifact() {
  const chosen = artifacts.value.find(item => String(item.id) === pendingArtifactId.value)
  if (!chosen || artifactLoadState.value !== 'ready') return ElMessage.warning(tUi('请选择一个包版本'))
  if (artifactChoiceError(chosen)) return ElMessage.warning(artifactChoiceError(chosen))
  if (isMobile.value) { const reason = mobileArtifactEnvironmentError(artifacts.value.find(item => String(item.id) === pendingArtifactId.value)); if (reason) return ElMessage.warning(reason) }
  if (artifactBlocked(artifacts.value.find(item => String(item.id) === pendingArtifactId.value))) return ElMessage.warning(tUi(PRODUCTION_GATE_MESSAGE))
  form.artifactId = pendingArtifactId.value
  artifactDialog.value = false
}

watch(selectedService, () => { loadArtifacts(); loadEnvironments() }, { flush: 'sync' })
watch(() => [route.query.serviceId, route.query.environment], () => {
  if (submitting.value) return
  const id = validServiceId(route.query.serviceId)
  if (id && services.value.some(service => String(service.id) === id)) form.serviceId = id
  form.environment = validEnvironment(route.query.environment) || 'test'
})
watch(() => form.environment, selectAllEnvironmentServers)
watch(selectedArtifact, value => { if(isMobile.value) form.environment = value?.buildEnvironmentName || ''; totpDialog.value = false; totpCode.value = '' })
watch(artifactKeyword, () => { artifactPage.value = 1 })
watch([artifactPageSize, filteredArtifacts], () => {
  const maxPage = Math.max(1, Math.ceil(filteredArtifacts.value.length / artifactPageSize.value))
  if (artifactPage.value > maxPage) artifactPage.value = maxPage
})

async function submit() {
  if (submitting.value || totpLoading.value || artifactLoadState.value !== 'ready' || environmentLoading.value || productionGate.value && readinessLoading.value) return
  if (!selectedService.value) return ElMessage.warning(tUi('请选择已启用的应用或服务'))
  if (!selectedArtifact.value) return ElMessage.warning(tUi('请选择一个成功的包版本'))
  if (isMobile.value && appArtifactError.value) return ElMessage.warning(appArtifactError.value)
  if (selectedBlocked.value) return ElMessage.warning(tUi(PRODUCTION_GATE_MESSAGE))
  if (!environmentReady.value) return ElMessage.warning(tUi('当前环境尚未配置集群服务器，请先到服务详情中配置'))
  if (!isMobile.value && !form.serverIds.length) return ElMessage.warning(tUi('请选择至少一台发布服务器'))
  if (!reviewReady.value) return
  if (form.environment === 'prod' || review.value?.action === 'CONFIRM') {
    totpCode.value = ''
    totpError.value = ''
    totpDialog.value = true
    if (form.environment === 'prod') await loadTotpStatus()
    return
  }
  createRelease()
}

async function createRelease(code = '') {
  if (!reviewReady.value || artifactLoadState.value !== 'ready' || !selectedArtifact.value || environmentLoading.value || productionGate.value && readinessLoading.value) return
  if (review.value?.action === 'CONFIRM' && !totpDialog.value) return
  if (submitting.value || (form.environment === 'prod' && (totpLoading.value || totpUnavailable.value || totpRemaining.value > 0))) return
  if (isMobile.value && (!selectedArtifact.value || appArtifactError.value)) return ElMessage.warning(appArtifactError.value || tUi('请选择已记录构建环境的安装包'))
  if (selectedBlocked.value) return ElMessage.warning(tUi(PRODUCTION_GATE_MESSAGE))
  if (form.environment === 'prod' && !/^\d{6}$/.test(code)) return ElMessage.warning(tUi('请输入 6 位动态码'))
  try {
    const body = {
      serviceId: Number(form.serviceId), artifactId: Number(form.artifactId), environment: form.environment, serverIds: form.serverIds.map(Number),
      releaseNote: form.releaseNote,
    }
    const data = await enqueue(body, code, isMobile.value, review.value?.confirmationToken || undefined)
    if (!data) return
    totpDialog.value = false
    ElMessage.success(isMobile.value?tUi('App 发布记录已创建，请拉取安装包并确认发布结果'):tUi('发版任务已加入队列'))
    router.replace(releasePath(String(data.id)))
  } catch (error) {
    if (!isMobile.value && applyConflict(error)) {
      totpDialog.value = review.value?.action === 'CONFIRM'
      totpError.value = tUi('提交前目标状态已变化，请按下方最新记录重新核对。')
      if (totpDialog.value && form.environment === 'prod') await loadTotpStatus()
      ElMessage.warning(tUi('发布目标有变化，请重新核对'))
      return
    }
    totpCode.value = ''
    handleTotpError(error)
    totpError.value = errorText(error, tUi('创建发版任务失败'))
    if (!totpDialog.value) ElMessage.error(totpError.value)
  }
}

onMounted(() => { loadServices(); refreshQueue() })
onBeforeUnmount(() => { ++artifactRequest; ++environmentRequest; artifactController?.abort() })
</script>

<template>
  <div class="page-head">
    <div><h2>{{apps?tUi('创建 App 交付'):tUi('新建发版')}}</h2><p>{{apps?tUi('选择已校验的安装包，记录目标环境及实际发布结果。'):tUi('选择一个已成功打包的版本进行部署，不会重复构建。')}}</p></div>
    <el-button :icon="ArrowLeft" @click="apps?router.push(form.serviceId?servicePath(form.serviceId,'artifacts'):'/apps'):router.back()">{{ tUi('返回') }}</el-button>
  </div>

  <div class="queue-notice"><span class="queue-symbol">1</span><div><b>{{isMobile?tUi('任务队列'):tUi('服务器发布按序执行')}}</b><span>{{queueSummary}}</span></div></div>
  <section class="panel wizard-card" v-loading="loading">
    <div class="wizard-body">
      <div class="wizard-section">
        <h3>{{ tUi('选择包版本') }}</h3>
        <p class="muted">{{ tUi('这里只显示成功生成的制品包；打包失败的任务可在打包记录中查看完整构建日志。') }}</p>
        <el-form label-position="top" :disabled="submitting || totpDialog" style="margin-top:24px">
          <el-form-item :label="tUi('目标服务')" required>
            <ServiceSelect v-model="form.serviceId" :services="services" :disabled="apps"/><el-alert v-if="apps&&!loading&&!selectedService" :title="tUi('此 App 未启用或不存在，暂不能创建交付。')" type="warning" :closable="false" show-icon style="margin-top:12px"/>
          </el-form-item>

          <el-form-item :label="tUi('包版本')" required>
            <div class="artifact-slot" data-testid="release-artifacts" :data-state="loading?'loading':artifactLoadState" :aria-busy="loading||artifactLoadState==='loading'">
            <div v-if="loading||artifactLoadState==='loading'" class="artifact-loading" role="status" aria-live="polite">
              <b>{{ tUi('正在读取包版本') }}</b><p>{{selectedService?.name?tUi('正在读取 ')+selectedService.name+tUi(' 的成功打包记录，请稍候。'):tUi('正在读取服务信息，请稍候。')}}</p>
              <div class="artifact-skeleton" aria-hidden="true"><div v-for="item in 3" :key="item"><span/><span/><i/></div></div>
            </div>
            <div v-else-if="!selectedService" class="artifact-state" :role="serviceLoadError?'alert':undefined"><b>{{serviceLoadError?tUi('暂时无法读取服务'):tUi('请先选择目标服务')}}</b><p>{{serviceLoadError||tUi('选择服务后，读取对应的成功打包记录。')}}</p><el-button v-if="serviceLoadError" @click="loadServices">{{ tUi('重新读取服务') }}</el-button></div>
            <div v-else-if="artifactLoadState==='error'" class="artifact-state artifact-state--error" role="alert"><b>{{ tUi('暂时无法读取包版本') }}</b><p>{{artifactLoadError}}</p><el-button @click="loadArtifacts">{{ tUi('重新读取包版本') }}</el-button></div>
            <div v-else-if="selectedArtifact" class="selected-artifact">
              <div class="selected-artifact-icon"><el-icon><Box /></el-icon></div>
              <div class="selected-artifact-info">
                <div class="selected-artifact-title"><b :title="selectedArtifact.branch">{{isMobile?mobileVersionLabel(selectedArtifact):selectedArtifact.branch || tUi('未记录分支')}}</b><el-tag size="small" type="success">{{ tUi('已选择') }}</el-tag></div>
                <div v-if="apps" class="selected-artifact-meta"><span>{{selectedArtifact.format==='APK'?tUi('完整 APK'):selectedArtifact.format||tUi('安装包')}}</span><span v-if="selectedArtifact.versionName">{{selectedArtifact.branch}}</span></div><div class="selected-artifact-meta"><CommitHash :value="selectedArtifact.commitId"/><span v-if="selectedArtifact.buildTaskId">{{ tUi('构建 #{0}', selectedArtifact.buildTaskId) }}</span><span>{{ tUi('打包人 {0}', selectedArtifact.createdBy || '—') }}</span></div><div class="selected-artifact-time">{{ tUi('打包完成 {0}', formatTime(selectedArtifact.createdAt)) }}<template v-if="!isMobile&&artifactReadiness(selectedArtifact)?.preVerifiedAt">{{ tUi('· 预生产发布成功 {0}', formatTime(artifactReadiness(selectedArtifact)?.preVerifiedAt || '')) }}</template></div>
                <ArtifactEnvironmentHistory v-if="!isMobile" :entries="history.byArtifact.value.get(Number(selectedArtifact.id))" :loading="history.loading.value" :error="history.error.value" :servers="servers" @retry="history.refresh"/>
                <small v-if="!isMobile" class="selected-artifact-image" :title="selectedArtifact.imageUrl || selectedArtifact.version">{{ selectedArtifact.imageUrl || selectedArtifact.version }}</small>
              </div>
              <el-button @click="openArtifactDialog">{{ tUi('重新选择') }}</el-button>
            </div>

            <div v-else-if="artifacts.length" class="recent-artifacts">
              <div class="recent-artifacts-head"><b>{{ tUi('最近 {0} 个成功包', recentArtifacts.length) }}</b><el-button link type="primary" @click="openArtifactDialog">{{ tUi('查看全部 {0} 个', artifacts.length) }}</el-button></div>
              <article v-for="artifact in recentArtifacts" :key="artifact.id" class="recent-artifact" :data-artifact-id="artifact.id">
                <div class="recent-artifact-main"><b :title="isMobile?mobileVersionLabel(artifact):artifact.branch">{{isMobile?mobileVersionLabel(artifact):artifact.branch||tUi('未记录分支')}}</b><time :title="timeParts(artifact.createdAt).full">{{timeParts(artifact.createdAt).date}} {{timeParts(artifact.createdAt).time}}</time><div class="recent-artifact-meta"><CommitHash :value="artifact.commitId"/><span>{{artifact.buildTaskId?tUi('构建 #')+artifact.buildTaskId:tUi('制品 #')+artifact.id}}</span><span>{{ tUi('打包人 {0}', artifact.createdBy||'—') }}</span><span v-if="isMobile&&artifact.buildEnvironmentName">{{environmentLabel(artifact.buildEnvironmentName)}} · {{artifact.format||tUi('安装包')}}</span></div><ArtifactEnvironmentHistory v-if="!isMobile" class="recent-envs" :entries="history.byArtifact.value.get(Number(artifact.id))" :loading="history.loading.value" :error="history.error.value" :servers="servers" @retry="history.refresh"/><small v-if="artifactChoiceError(artifact)" class="recent-artifact-reason">{{artifactChoiceError(artifact)}}</small></div>
                <el-button :disabled="!!artifactChoiceError(artifact)" :aria-label="tUi('选择构建 #')+(artifact.buildTaskId||artifact.id)" @click="selectRecentArtifact(artifact)">{{ tUi('选择') }}</el-button>
              </article>
              <p class="recent-artifacts-note">{{ tUi('选择本次要发布的包，再核对下方环境与服务器。') }}</p>
            </div>
            <div v-else-if="artifactLoadState==='ready'" class="artifact-state artifact-state--empty"><b>{{ tUi('该服务还没有成功打包的制品') }}</b><p>{{ tUi('先完成一次打包，生成制品后即可在这里选择发布。') }}</p><el-button @click="router.push(apps?`/apps/${form.serviceId}/build`:`/build/new?serviceId=${form.serviceId}`)">{{ tUi('去打包') }}</el-button></div>
            </div>
          </el-form-item>

          <el-form-item :label="tUi('目标环境')">
            <el-radio-group v-if="!isMobile" v-model="form.environment">
              <el-radio-button value="dev">{{ tUi('开发') }}</el-radio-button><el-radio-button value="test">{{ tUi('预生产') }}</el-radio-button><el-radio-button value="prod">{{ tUi('生产') }}</el-radio-button>
            </el-radio-group>
            <div v-if="isMobile" class="app-environment-lock"><template v-if="selectedArtifact?.buildEnvironmentName"><el-tag :type="form.environment==='prod'?'danger':'warning'" effect="plain">{{environmentLabel(selectedArtifact.buildEnvironmentName)}}</el-tag><b>{{selectedArtifact.buildMode==='DEBUG'?'Debug':'Release'}}</b><span>{{selectedArtifact.applicationId}}</span><small>{{ tUi('跟随安装包的构建环境，不能切换。需要其他环境时，请重新构建。') }}</small></template><small v-else>{{selectedArtifact?tUi('该历史安装包未记录构建环境。'):tUi('选择安装包后自动显示其构建环境。')}}</small></div>
            <el-alert v-if="appArtifactError" :title="appArtifactError" type="warning" :closable="false" show-icon style="margin-top:14px"/>
            <div v-if="!isMobile&&environmentLoading" class="environment-reading" role="status">{{ tUi('正在读取环境配置…') }}</div>
            <div v-else-if="!isMobile&&environmentLoadError" class="environment-reading" role="alert">{{environmentLoadError}}<el-button link type="primary" @click="loadEnvironments">{{ tUi('重试') }}</el-button></div>
            <el-alert v-if="isMobile && environmentReady" :title="form.environment==='prod'?tUi('生产启用自动合回时，发布成功后合并已验证的提交'):tUi('当前环境不自动合并代码')" :description="tUi('包名、签名和构建参数已随构建锁定。拉取后由外部系统发布，再确认结果；不能将测试安装包转为生产包。')" type="info" :closable="false" show-icon style="margin-top:14px"/><div v-else-if="!isMobile&&!environmentLoading&&!environmentLoadError&&selectedService" class="environment-summary" :class="{unconfigured:!environmentReady}">
              <template v-if="environmentReady"><span class="status-dot status-success"></span><b>{{ tUi('集群已配置') }}</b><span>{{ tUi('{0} 个可用节点，已选择 {1} 个', environmentServers.filter(server=>server.enabled).length, targetServers.length) }}</span></template>
              <template v-else><span class="status-dot status-failed"></span><b>{{ tUi('环境未配置') }}</b><el-button link type="primary" @click="router.push(`/services/${form.serviceId}`)">{{ tUi('前往服务详情配置集群') }}</el-button></template>
            </div>
            <div v-if="!isMobile && environmentReady" class="release-machines">
              <div class="release-machines-head"><div><b>{{ tUi('选择发布机器') }}</b><span>{{ tUi('机器动态读取自服务器管理，默认选择当前环境的全部节点') }}</span></div><el-button link type="primary" @click="selectAllEnvironmentServers">{{ tUi('全选') }}</el-button></div>
              <el-checkbox-group v-model="form.serverIds" class="machine-options">
                <el-checkbox v-for="server in environmentServers" :key="server.id" :value="String(server.id)" :disabled="!server.enabled" border>
                  <span class="machine-name">{{server.name}}</span><small class="mono">{{serverConnectionLabel(server)}}</small><el-tag v-if="server.connectionMode!=='SSH_CONFIG'" size="small" effect="plain" :type="server.authType==='PASSWORD'?'warning':'primary'">{{server.authType==='PASSWORD'?tUi('密码'):'SSH Key'}}</el-tag>
                </el-checkbox>
              </el-checkbox-group>
            </div>
            <DeploymentReviewPanel v-if="reviewTarget" class="target-preview" :review="review" :loading="reviewing" :error="reviewError" :environment="form.environment" :servers="servers" @retry="refreshReview"/>
            <div v-if="form.environment === 'prod'" class="production-tip"><el-icon><Lock /></el-icon><span>{{ tUi('生产发版需要当前登录账号的 Google Authenticator 动态码确认{0}', isMobile?'':tUi('。请选择预生产已通过或曾发布到生产的包。')) }}</span></div>
            <div v-if="selectedBlocked" class="production-gate" role="alert"><div><b>{{tUi(PRODUCTION_GATE_MESSAGE)}}</b><span>{{ tUi('该制品还没有在预生产发布成功。请先发布到预生产并通过健康检查，再把同一个制品发布到生产。曾在生产发布成功的制品可直接用于回滚。') }}</span></div><el-button size="small" @click="form.environment='test'">{{ tUi('改为发布到预生产') }}</el-button></div>
            <el-alert v-if="productionGate&&readinessError" :title="readinessError" type="warning" :closable="false" show-icon style="margin-top:10px"/>
          </el-form-item>
          <el-form-item :label="tUi('发版说明')"><el-input v-model="form.releaseNote" type="textarea" :rows="3" /></el-form-item>
        </el-form>

        <div v-if="selectedArtifact" class="summary-list">
          <div class="summary-row"><span>{{ tUi('服务') }}</span><ServiceIdentity :service="selectedService"/></div>
          <div class="summary-row"><span>{{ tUi('选定版本') }}</span><b>{{ selectedArtifact.version }}</b></div>
          <div class="summary-row"><span>{{ tUi('目标环境') }}</span><b>{{environmentLabel(form.environment)}}</b></div><div v-if="!isMobile&&!readinessError" class="summary-row"><span>{{ tUi('预生产验证') }}</span><b :class="{'gate-blocked':selectedBlocked}">{{artifactBadge(selectedArtifact).text}}</b></div><div v-if="!isMobile" class="summary-row"><span>{{ tUi('发布机器') }}</span><b>{{ tUi('{0} 台', targetServers.length) }}</b></div><div class="summary-row"><span>{{isMobile?tUi('交付步骤'):tUi('部署步骤')}}</span><b>{{isMobile?tUi('拉取安装包 → 外部发布 → 确认结果'):tUi('逐台连接 → 启动服务 → 健康检查')}}</b></div>
        </div>
      </div>
    </div>
    <div class="wizard-foot"><span class="muted">{{ tUi('复用成功制品，无需重复打包') }}</span><el-button type="primary" :icon="Promotion" :loading="submitting || totpLoading" :disabled="!reviewReady||artifactLoadState!=='ready'||environmentLoading||(productionGate&&readinessLoading)||!selectedService||!selectedArtifact||!environmentReady||(!isMobile&&!form.serverIds.length)||selectedBlocked" @click="submit">{{isMobile?tUi('创建发布记录'):review?.action==='ACTIVE'?tUi('所选节点已有任务'):review?.action==='CONFIRM'?tUi('核对并发布'):tUi('加入发版队列')}}</el-button></div>
  </section>

  <el-dialog v-model="artifactDialog" :title="tUi('选择包版本')" width="min(960px, 92vw)" destroy-on-close>
    <div class="artifact-dialog-head">
      <div><ServiceIdentity :service="selectedService"/><span class="artifact-service-help">{{productionGate&&!readinessError?tUi('目标为生产：只能选择预生产已通过，或曾发布生产（用于回滚）的制品'):tUi('只展示成功打包并生成制品的记录')}}</span></div>
      <el-input v-model="artifactKeyword" :prefix-icon="Search" clearable :placeholder="tUi('搜索版本、分支、Commit')" style="width:280px" />
    </div>

    <p class="mobile-table-hint">{{ tUi('左右滑动查看完整版本与打包信息') }}</p>
    <el-table :data="pagedArtifacts" row-key="id" highlight-current-row class="artifact-table" :row-class-name="artifactRowClass" :empty-text="tUi('没有匹配的包版本')" @row-click="chooseArtifact">
      <el-table-column width="52" align="center">
        <template #default="{ row }"><el-radio v-model="pendingArtifactId" :value="String(row.id)" :aria-label="tUi('选择 {0}', isMobile?mobileVersionLabel(row):row.version)" :disabled="!!artifactChoiceError(row)"><span /></el-radio></template>
      </el-table-column>
      <el-table-column v-if="isMobile" :label="tUi('构建环境')" width="135"><template #default="{row}"><el-tag v-if="row.buildEnvironmentName" :type="row.buildEnvironmentName==='prod'?'danger':'warning'" effect="plain">{{environmentLabel(row.buildEnvironmentName)}}</el-tag><span v-else>{{ tUi('历史包 · 待重建') }}</span><small class="artifact-version-caption">{{row.buildMode || tUi('未记录模式')}}</small></template></el-table-column>
      <el-table-column :label="tUi('打包完成')" width="150"><template #default="{ row }"><div class="record-time" :title="timeParts(row.createdAt).full"><b>{{timeParts(row.createdAt).time}}</b><span>{{timeParts(row.createdAt).date}}</span></div></template></el-table-column>
      <el-table-column :label="tUi('分支 / Commit')" min-width="220"><template #default="{ row }"><b class="record-branch" :title="row.branch">{{row.branch || tUi('未记录分支')}}</b><CommitHash :value="row.commitId"/></template></el-table-column>
      <el-table-column v-if="apps" :label="tUi('格式')" width="90"><template #default="{row}">{{row.format||tUi('安装包')}}</template></el-table-column><el-table-column :label="tUi('构建版本')" min-width="230"><template #default="{ row }"><b class="artifact-build-number">{{isMobile?mobileVersionLabel(row):row.buildTaskId ? tUi('构建 #{0}', row.buildTaskId) : tUi('制品 #{0}', row.id)}}</b><small class="artifact-version-caption" :title="row.imageUrl || row.version">{{isMobile?(row.buildTaskId?tUi('构建 #{0}', row.buildTaskId):''):row.version}}</small></template></el-table-column>
      <el-table-column v-if="!isMobile" :label="tUi('发布历史')" min-width="200"><template #default="{ row }"><ArtifactEnvironmentHistory :entries="history.byArtifact.value.get(Number(row.id))" :loading="history.loading.value" :error="history.error.value" :servers="servers" @retry="history.refresh"/></template></el-table-column>
      <el-table-column :label="tUi('打包人')" width="110"><template #default="{ row }">{{ row.createdBy || '—' }}</template></el-table-column>
    </el-table>

    <div class="artifact-pagination">
      <el-pagination v-model:current-page="artifactPage" v-model:page-size="artifactPageSize" :page-sizes="[5, 10, 20]" :total="filteredArtifacts.length" layout="total, sizes, prev, pager, next" />
    </div>

    <template #footer><el-button @click="artifactDialog = false">{{ tUi('取消') }}</el-button><el-button type="primary" :disabled="!pendingArtifactId||(productionGate&&readinessLoading)" @click="confirmArtifact">{{ tUi('确认选择') }}</el-button></template>
  </el-dialog>

  <el-dialog v-model="totpDialog" :title="form.environment==='prod'?tUi('生产发版二次确认'):tUi('核对发布范围')" width="min(540px, 94vw)" :close-on-click-modal="false" :show-close="!submitting" :close-on-press-escape="!submitting">
    <template v-if="form.environment!=='prod'">
      <p class="review-dialog-service"><strong>{{selectedService?.name}}</strong>{{ tUi('· {0} · 构建 #{1}', environmentLabel(form.environment), selectedArtifact?.buildTaskId || '—') }}</p>
      <el-alert v-if="totpError" :title="totpError" type="warning" :closable="false"/>
      <DeploymentReviewPanel :review="review" :environment="form.environment" :servers="servers" @retry="refreshReview"/>
    </template>
    <div v-else-if="totpLoading" class="empty" v-loading="true">{{ tUi('正在读取验证状态') }}</div>
    <div v-else-if="totpUnavailable" class="empty"><div><strong>{{ tUi('暂时无法读取验证状态') }}</strong><el-button @click="loadTotpStatus">{{ tUi('重新读取') }}</el-button></div></div>
    <div v-else-if="totpEnabled" class="totp-confirm">
      <div class="totp-lock"><el-icon><Lock /></el-icon></div>
      <h3>{{ tUi('确认发布到生产环境') }}</h3>
      <p>{{ selectedService?.name }} · {{ selectedArtifact?.version }}</p>
      <div class="totp-summary"><span>{{ tUi('目标环境') }}</span><b>{{ tUi('生产') }}</b><span>Commit</span><CommitHash :value="selectedArtifact?.commitId"/><template v-if="!isMobile&&artifactReadiness(selectedArtifact)"><span>{{ tUi('预生产验证') }}</span><b>{{artifactReadiness(selectedArtifact)?.preVerifiedAt ? `${formatTime(artifactReadiness(selectedArtifact)?.preVerifiedAt || '')} · ${artifactReadiness(selectedArtifact)?.preVerifiedBy || '—'}` : tUi('曾发布生产（回滚）')}}</b></template></div>
      <DeploymentReviewPanel v-if="!isMobile" class="confirmation-preview" :review="review" :environment="form.environment" :servers="servers" @retry="refreshReview"/>
      <el-alert v-if="totpError" :title="totpError" type="error" :closable="false" show-icon style="margin-bottom:16px" />
      <div v-if="totpRemaining > 0" class="otp-countdown">{{ tUi('当前动态码已使用，请在') }} <b>{{totpRemaining}}</b> {{ tUi('秒后输入新码。') }}</div>
      <OtpInput v-model="totpCode" :disabled="submitting || totpRemaining > 0" :label="tUi('生产发版确认动态码')" @submit="createRelease(totpCode)" />
      <small>{{ tUi('请使用当前账号') }} <b>{{approver}}</b> {{ tUi('绑定的动态码，验证成功后才会创建发版任务。') }}</small>
    </div>
    <div v-else class="totp-unbound">
      <div class="totp-lock warning"><el-icon><Lock /></el-icon></div>
      <h3>{{ tUi('当前账号尚未设置二次认证') }}</h3><p>{{ tUi('请先为账号 {0} 绑定 Google Authenticator。', approver) }}</p>
      <el-button type="primary" @click="router.push('/settings/security'); totpDialog = false">{{ tUi('绑定我的验证器') }}</el-button>
    </div>
    <template #footer>
      <template v-if="form.environment!=='prod' || (totpEnabled && !totpUnavailable && !totpLoading)">
        <el-button :disabled="submitting" @click="totpDialog = false">{{ tUi('取消') }}</el-button>
        <el-button type="danger" :loading="submitting" :disabled="!reviewReady || (form.environment==='prod' && (totpCode.length!==6 || totpRemaining>0))" @click="createRelease(form.environment==='prod'?totpCode:'')">{{form.environment!=='prod'?reviewConfirmationLabel(review):isMobile?tUi('验证并创建发布记录'):review?.action==='CONFIRM'?tUi('验证并')+reviewConfirmationLabel(review):tUi('验证并加入队列')}}</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.recent-envs{grid-column:1/-1}.target-preview{width:100%;margin-top:12px}.confirmation-preview{text-align:left;margin:12px 0}.review-dialog-service{margin:0 0 12px;color:#40536b}

.artifact-slot{width:100%;min-width:0}.artifact-loading> b,.artifact-state>b{display:block;font-size:15px;color:#354b68;font-weight:650}.artifact-loading>p,.artifact-state>p{color:#74849a;font-size:12px;line-height:1.8;margin:8px 0 15px}.artifact-loading{width:100%;padding:15px 0}.artifact-skeleton>div{display:grid;grid-template-columns:1fr 88px;gap:8px;padding:12px 0;border-bottom:1px solid #edf1f6}.artifact-skeleton span{height:13px;border-radius:4px;background:#edf1f6}.artifact-skeleton span:first-child{max-width:210px}.artifact-skeleton span:nth-child(2){width:80px;justify-self:end}.artifact-skeleton i{display:block;height:10px;width:65%;max-width:330px;border-radius:4px;background:#f2f5f9}.artifact-state{padding:30px 0;min-height:160px;display:flex;align-items:flex-start;justify-content:center;flex-direction:column}.artifact-state .el-button{margin:4px 0 0}.artifact-state--error>b{color:#a05b43}.artifact-state--empty{min-height:180px}.recent-artifacts-head{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:0 0 6px;border-bottom:1px solid #e6ecf4}.recent-artifacts-head>b{font-size:13px;color:#52647c;font-weight:600}.recent-artifacts-head .el-button{font-size:12px}.recent-artifact{display:flex;align-items:center;gap:14px;padding:8px 0;border-bottom:1px solid #edf1f6}.recent-artifact-main{min-width:0;flex:1;display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:baseline;gap:4px 14px;line-height:1.4}.recent-artifact-main>b{font-size:14px;font-weight:700;color:#263a56;overflow-wrap:anywhere;min-width:0;line-height:1.4}.recent-artifact-main>time{font-size:12px;font-variant-numeric:tabular-nums;color:#52647c;white-space:nowrap;line-height:1.4}.recent-artifact-meta{grid-column:1/-1;display:flex;align-items:center;gap:5px 11px;flex-wrap:wrap;color:#64748b;font-size:11px;line-height:1.5}.recent-artifact>.el-button{flex:none;min-width:62px}.recent-artifact-reason{grid-column:1/-1;font-size:11px;line-height:1.7;color:#97635d}.recent-artifacts-note{margin:9px 0 0;font-size:12px;color:#74849a;line-height:1.8}.environment-reading{display:flex;align-items:center;flex-wrap:wrap;gap:10px;margin-top:15px;font-size:12px;color:#74849a}@media(max-width:640px){.recent-artifact{gap:10px}.recent-artifact-main{grid-template-columns:minmax(0,1fr);gap:3px}.recent-artifact-main>b,.recent-artifact-main>time{grid-column:1}.recent-artifact-meta{gap:5px 9px}.recent-artifact-main>b{min-width:0}}

.app-environment-lock{display:flex;align-items:center;gap:12px;flex-wrap:wrap;font-size:12px}.app-environment-lock span{overflow-wrap:anywhere}.app-environment-lock small{display:block;flex-basis:100%;color:#74849a;line-height:1.8}

.selected-artifact{display:flex;align-items:center;gap:14px;width:100%;padding:16px;border:1px solid #2b65a7;border-radius:10px;background:rgba(36,112,192,.08)}
.selected-artifact-icon,.artifact-picker-icon{display:grid;place-items:center;flex:0 0 42px;height:42px;border-radius:10px;background:rgba(64,158,255,.14);color:#65b1ff;font-size:21px}
.selected-artifact-info{display:grid;gap:4px;min-width:0;flex:1}.selected-artifact-title{display:flex;align-items:center;gap:10px}
.selected-artifact-info span,.selected-artifact-info small{font-size:12px;color:#71879d;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.artifact-picker{display:flex;align-items:center;gap:14px;width:100%;padding:16px;border:1px dashed #35516e;border-radius:10px;background:rgba(12,26,43,.45);color:inherit;text-align:left;cursor:pointer;transition:.2s}
.artifact-picker:hover{border-color:#409eff;background:rgba(64,158,255,.07)}.artifact-picker>span:nth-child(2){display:grid;gap:5px;flex:1}.artifact-picker small{color:#71879d}
.artifact-dialog-head{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:16px}.artifact-dialog-head>div{display:grid;gap:8px;min-width:0}.artifact-service-help,.table-sub{display:block;color:#71879d;font-size:12px}
.artifact-table :deep(.el-table__row){cursor:pointer}.artifact-table :deep(.el-radio){margin-right:0}.artifact-pagination{display:flex;justify-content:flex-end;margin-top:18px}.wizard-foot{display:flex;justify-content:space-between;padding:18px 30px;border-top:1px solid #1f3046}
.environment-summary{display:flex;align-items:center;gap:8px;width:100%;margin-top:12px;padding:10px 12px;border-radius:8px;background:#ecf9f3;color:#344054;font-size:12px}.environment-summary.unconfigured{background:#fff5f5}.environment-summary .status-dot{margin:0}.environment-summary>span:last-child{color:#667085}.environment-summary .el-button{margin-left:auto}
.release-machines{width:100%;margin-top:12px;padding:14px;border:1px solid #e1e6ee;border-radius:10px;background:#fbfcfe}.release-machines-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:11px}.release-machines-head>div{display:grid;gap:4px}.release-machines-head span{font-size:12px;color:#7b8798}.machine-options{display:grid;gap:8px}.machine-options :deep(.el-checkbox){width:100%;height:auto;margin:0;padding:11px 13px}.machine-options :deep(.el-checkbox__label){display:grid;grid-template-columns:minmax(120px,1fr) minmax(220px,1.5fr) auto;align-items:center;gap:10px;width:100%}.machine-name{font-weight:600;color:#344054}.machine-options small{color:#667085}
.production-tip{display:flex;align-items:center;gap:7px;width:100%;margin-top:10px;color:#e6a23c;font-size:12px}.totp-confirm,.totp-unbound{text-align:center}.totp-lock{display:grid;place-items:center;width:58px;height:58px;margin:0 auto 14px;border-radius:16px;background:#3a2027;color:#ff7880;font-size:26px}.totp-lock.warning{background:#3b311b;color:#e6a23c}.totp-confirm h3,.totp-unbound h3{margin:0 0 7px}.totp-confirm>p,.totp-unbound p{margin:0 0 18px;color:#7c8ea6}.totp-summary{display:grid;grid-template-columns:90px 1fr;gap:8px;text-align:left;padding:13px;margin-bottom:18px;border-radius:9px;background:#0a1422}.totp-summary span{color:#71849c}.totp-confirm :deep(.el-input__inner){font:700 21px "Cascadia Code",monospace;letter-spacing:5px;text-align:center}.totp-confirm small{display:block;margin-top:10px;color:#6f829a}
@media(max-width:720px){.selected-artifact{align-items:flex-start;flex-wrap:wrap}.selected-artifact-info{width:calc(100% - 60px)}.artifact-dialog-head{align-items:stretch;flex-direction:column}.artifact-dialog-head :deep(.el-input){width:100%!important}.artifact-pagination{overflow-x:auto;justify-content:flex-start}}
.selected-artifact-info{min-width:0}.selected-artifact-title b{font-size:16px;color:#263b58;overflow-wrap:anywhere}.selected-artifact-meta{display:flex;align-items:center;gap:12px;flex-wrap:wrap;color:#60748e;font-size:12px;margin-top:8px}.selected-artifact-time{font-size:12px;color:#526984;margin-top:8px;font-variant-numeric:tabular-nums}.selected-artifact-image{display:block;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#7b8ba0;margin-top:8px;font-size:11px}.totp-summary .commit-hash{justify-self:end}.artifact-dialog-head{align-items:flex-start}
@media(max-width:650px){.selected-artifact{flex-wrap:wrap;padding:15px!important;gap:12px!important}.selected-artifact-icon{display:none!important}.selected-artifact-info{flex-basis:100%}.selected-artifact>.el-button{margin-left:auto}.artifact-dialog-head{flex-direction:column;gap:14px}.artifact-dialog-head>.el-input{width:100%!important}.artifact-pagination :deep(.el-pagination){flex-wrap:wrap;gap:10px;justify-content:flex-end}.wizard-foot{flex-wrap:wrap;gap:14px;padding:16px!important}.release-machines-head{gap:12px}.release-machines-head>div{min-width:0}}
.production-gate{display:flex;align-items:center;gap:14px;width:100%;margin-top:12px;padding:12px 14px;border:1px solid #f3c9c9;border-left:3px solid #d9534f;border-radius:8px;background:#fff7f7;font-size:12px;line-height:1.7}.production-gate>div{display:grid;gap:3px;flex:1;min-width:0}.production-gate b{color:#b42318}.production-gate span{color:#7a4b4b}.gate-blocked{color:#b42318}
.artifact-table :deep(.gate-blocked-row){cursor:not-allowed;opacity:.55}
@media(max-width:650px){.production-gate{flex-direction:column;align-items:flex-start}}
.artifact-build-number{display:block;color:#52647c;font-size:13px;font-weight:500}.artifact-version-caption{display:block;margin-top:5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#7b8ba0;font-size:12px}
</style>
