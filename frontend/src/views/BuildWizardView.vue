<script setup lang="ts">
import { tUi, currentLocale } from '../i18n'

import { useAppScope } from '../composables/useAppScope'
import CommitHash from '../components/CommitHash.vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Check, Clock, RefreshRight, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { errorText, http, normalizeService, payload, rows } from '../api'
import type { GitCommitPreview, Service } from '../types'
import { automaticMobileVersion, mobileEnabledEnvironments, mobileEnvironment, mobileEnvironmentError, mobileFormat } from '../mobile'
import { preBuildCandidates, readyVersionPreview } from '../mobileVersioning'
import type { MobileVersionPreview, PreBuildCandidate } from '../mobileVersioning'
import { environmentLabel, formatTime, newRequestId } from '../ui'
import ServiceSelect from '../components/ServiceSelect.vue'
import { rememberBuildPublish } from '../buildPublishIntent'
import BuildPublishPlan from '../components/BuildPublishPlan.vue'
import type { BuildPublishTarget } from '../buildPublishIntent'

const { apps, api, can, buildPath, servicePath } = useAppScope()
const route=useRoute(),router=useRouter(),locked=computed(()=>apps||Boolean(route.query.serviceId)),loading=ref(false),submitting=ref(false),services=ref<Service[]>([]),branches=ref<string[]>([]),branchLoading=ref(false)
const form=reactive({serviceId:String(apps?route.params.serviceId||'':route.query.serviceId||''),branch:'',buildNote:'',environmentName:''})
const plannedPublish=ref<{mode:'BUILD_ONLY'|'PUBLISH';environment:string;serverIds:number[]}>({mode:'BUILD_ONLY',environment:'',serverIds:[]}),publishPlanValid=ref(true)
const automaticPublish=computed(()=>!isMobile.value&&can('release')&&plannedPublish.value.mode==='PUBLISH'&&['dev','test'].includes(plannedPublish.value.environment))
let submittedFingerprint = '', requestId = '', branchRequest = 0
const commitLoading=ref(false),commitPreview=ref<GitCommitPreview>(),commitError=ref(''),commitConfirmed=ref(false)
const requestedVersionBranch = computed(() => typeof route.query.branch === 'string' ? route.query.branch.trim() : '')
const changedNotice = computed(() => {
  const noticed = route.query.noticedCommit
  const latest = commitPreview.value?.commitId
  if (typeof noticed !== 'string' || !/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i.test(noticed)) return
  if (!latest || latest.toLowerCase() === noticed.toLowerCase() || form.branch !== requestedVersionBranch.value || productionFromPre.value) return
  return { noticed, latest }
})
const versionBranchError = ref('')
let commitRequest=0
const selected=computed(()=>services.value.find(s=>String(s.id)===form.serviceId&&s.status!==false&&s.status!=='DISABLED'))
const isMobile=computed(()=>selected.value?.serviceType==='FLUTTER_ANDROID')
const appEnvironments=computed(()=>mobileEnabledEnvironments(selected.value?.mobileBuild))
const appProfile=computed(()=>mobileEnvironment(selected.value?.mobileBuild,form.environmentName))
const automaticVersion=computed(()=>isMobile.value&&automaticMobileVersion(selected.value?.mobileBuild))
const productionFromPre=computed(()=>automaticVersion.value&&form.environmentName==='prod')
const candidates=ref<PreBuildCandidate[]>([]),preBuildTaskId=ref<number>(),candidateLoading=ref(false),candidateError=ref(''),versionPreview=ref<MobileVersionPreview>(),versionLoading=ref(false),versionError=ref('')
const selectedPre=computed(()=>candidates.value.find(item=>item.buildTaskId===preBuildTaskId.value))
const versionReady=computed(()=>!automaticVersion.value||readyVersionPreview(versionPreview.value,form.environmentName,preBuildTaskId.value))
let candidateRequest=0,versionRequest=0,disposed=false,candidateAbort:AbortController|undefined,versionAbort:AbortController|undefined
const appBuildError=computed(()=>isMobile.value?mobileEnvironmentError(selected.value?.mobileBuild,form.environmentName):'')
const appParameters = computed(() => [
  ...Object.entries(appProfile.value?.dartDefines || {}).map(([key, value]) => ({ group: 'Dart', key, value })),
  ...Object.entries(appProfile.value?.environmentVariables || {}).map(([key, value]) => ({ group: tUi('构建环境'), key, value })),
])
const buildLabel=computed(()=>isMobile.value?tUi('构建 Android {0}', mobileFormat(selected.value?.mobileBuild)):selected.value?.serviceType==='FRONTEND'?tUi('{0} 构建', selected.value.buildTool||'npm'):tUi('构建 Spring Boot JAR'))
async function loadServices(){loading.value=true;try{services.value=rows<Record<string,unknown>>((await http.get(api('/services'))).data).map(normalizeService).filter(s=>s.status!==false&&s.status!=='DISABLED'&&(!apps||s.serviceType==='FLUTTER_ANDROID'));if(locked.value&&!selected.value)form.serviceId='';else if(!locked.value&&!form.serviceId&&services.value.length)form.serviceId=String(services.value[0].id)}catch(e){ElMessage.error(errorText(e))}finally{loading.value=false}}
async function loadBranches(){
  const request=++branchRequest
  branches.value=[];form.branch='';versionBranchError.value='';commitPreview.value=undefined;commitConfirmed.value=false;commitError.value='';commitRequest++;commitLoading.value=false;branchLoading.value=false;if(!selected.value||productionFromPre.value)return
  branchLoading.value=true
  try{const value=payload<unknown>((await http.get(api(`/services/${form.serviceId}/branches`))).data,[]);if(request!==branchRequest)return;branches.value=Array.isArray(value)?value.map(String):[];if(requestedVersionBranch.value){if(branches.value.includes(requestedVersionBranch.value))form.branch=requestedVersionBranch.value;else versionBranchError.value=tUi('分支 {0} 在仓库中不存在。请核对分支是否已删除，或手动选择其他分支。', requestedVersionBranch.value)}else{const fallback=String(selected.value?.defaultBranch||'');form.branch=branches.value.includes(fallback)?fallback:(branches.value[0]||'')}}
  catch(e){if(request===branchRequest)ElMessage.error(errorText(e,tUi('读取 Git 分支失败')))}
  finally{if(request===branchRequest)branchLoading.value=false}
}
async function loadCommit(){
  const request=++commitRequest;commitPreview.value=undefined;commitError.value='';commitConfirmed.value=false;commitLoading.value=false;if(!selected.value||!form.branch)return
  if(productionFromPre.value){const source=selectedPre.value;if(source&&source.branch===form.branch)commitPreview.value={branch:source.branch,commitId:source.commitId,shortCommitId:source.commitId.slice(0,8),title:tUi('沿用已成功构建的 {0}', source.versionName),message:'',authorName:tUi('PRE 构建已锁定'),authorEmail:'',committedAt:source.createdAt,changedFiles:[],changedFilesTruncated:false};return}
  commitLoading.value=true
  try{const value=payload<GitCommitPreview>((await http.get(api(`/services/${form.serviceId}/branches/latest-commit`),{params:{branch:form.branch}})).data,{} as GitCommitPreview);if(request===commitRequest&&value.commitId)commitPreview.value=value}
  catch(e){if(request===commitRequest)commitError.value=errorText(e,tUi('读取最新提交失败'))}
  finally{if(request===commitRequest)commitLoading.value=false}
}
async function loadCandidates(){
  const request=++candidateRequest;candidateAbort?.abort();candidateAbort=undefined;candidates.value=[];preBuildTaskId.value=undefined;candidateError.value='';candidateLoading.value=false
  if(!productionFromPre.value||!selected.value)return
  const controller=new AbortController();candidateAbort=controller;candidateLoading.value=true
  try{const result=preBuildCandidates(payload(await http.get(api(`/services/${form.serviceId}/pre-build-candidates`),{signal:controller.signal}),[]));if(!disposed&&request===candidateRequest)candidates.value=result}
  catch(error){if(!disposed&&request===candidateRequest)candidateError.value=errorText(error,tUi('读取成功 PRE 版本失败'))}
  finally{if(!disposed&&request===candidateRequest)candidateLoading.value=false}
}
async function loadVersionPreview(){
  const request=++versionRequest;versionAbort?.abort();versionAbort=undefined;versionPreview.value=undefined;versionError.value='';versionLoading.value=false
  if(!automaticVersion.value||!form.environmentName||!commitPreview.value||productionFromPre.value&&!selectedPre.value)return
  const controller=new AbortController();versionAbort=controller;versionLoading.value=true
  const environmentName=form.environmentName,sourceId=preBuildTaskId.value
  try{const value=payload<MobileVersionPreview>(await http.get(api(`/services/${form.serviceId}/build-version-preview`),{signal:controller.signal,params:{environmentName,branch:form.branch,commitId:commitPreview.value.commitId,...(productionFromPre.value?{preBuildTaskId:sourceId}:{})}}),{} as MobileVersionPreview);if(disposed||request!==versionRequest)return;versionPreview.value=value;if(!readyVersionPreview(value,environmentName,sourceId))versionError.value=value.message||tUi('暂时无法预览版本，请刷新后再提交')}
  catch(error){if(!disposed&&request===versionRequest)versionError.value=errorText(error,tUi('版本预览暂不可用'))}
  finally{if(!disposed&&request===versionRequest)versionLoading.value=false}
}
watch(()=>form.serviceId,loadBranches)
watch(()=>[route.query.serviceId,route.query.branch],()=>{const next=String(apps?route.params.serviceId||'':route.query.serviceId||'');if(next&&next!==form.serviceId)form.serviceId=next;else loadBranches()})
watch(()=>form.branch,loadCommit)
watch(()=>form.environmentName,(_next,previous)=>{commitConfirmed.value=false;preBuildTaskId.value=undefined;void loadCandidates();if(productionFromPre.value){branchRequest++;commitRequest++;branchLoading.value=false;commitLoading.value=false;form.branch='';commitPreview.value=undefined;versionBranchError.value=''}else if(automaticVersion.value&&previous==='prod')void loadBranches()},{flush:'sync'})
watch(selected, service => {
  const requested = typeof route.query.environment === 'string' ? route.query.environment : ''
  form.environmentName = ''
  if (!service || !isMobile.value || !requested) return
  if (appEnvironments.value.some(env => env.name === requested)) form.environmentName = requested
  else ElMessage.warning(tUi('原构建环境已停用，请重新选择可用环境'))
}, { flush: 'sync' })
watch(()=>form.serviceId,()=>{plannedPublish.value={mode:'BUILD_ONLY',environment:'',serverIds:[]};publishPlanValid.value=true},{flush:'sync'})
watch(preBuildTaskId,()=>{commitRequest++;commitConfirmed.value=false;commitPreview.value=undefined;form.branch=selectedPre.value?.branch||'';if(selectedPre.value)void loadCommit()},{flush:'sync'})
watch(()=>[automaticVersion.value,form.serviceId,form.environmentName,form.branch,commitPreview.value?.commitId,preBuildTaskId.value],()=>{void loadVersionPreview()},{flush:'sync'})
function time(value:string){return value?new Date(value).toLocaleString(currentLocale(),{hour12:false}):'—'}
async function submit(){
  if(submitting.value)return
  if(!selected.value)return ElMessage.warning(tUi('服务未启用或不存在，请先完成配置并启用服务'))
  if(!form.serviceId||!form.branch)return ElMessage.warning(tUi('请选择服务和代码分支'))
  if(isMobile.value&&appBuildError.value)return ElMessage.warning(appBuildError.value)
  if(!isMobile.value&&can('release')&&!publishPlanValid.value)return ElMessage.warning(tUi('请先选择有效的发布环境和服务器，或选择仅打包'))
  if(automaticVersion.value&&(!versionReady.value||versionLoading.value))return ElMessage.warning(versionError.value||tUi('请先确认可用的自动版本'))
  if(productionFromPre.value&&(!selectedPre.value||form.branch!==selectedPre.value.branch||commitPreview.value?.commitId!==selectedPre.value.commitId))return ElMessage.warning(tUi('请重新选择成功的 PRE 版本并确认锁定代码'))
  if(!commitPreview.value||!commitConfirmed.value)return ElMessage.warning(tUi('请先确认本次构建的代码版本'))
  const body={serviceId:Number(form.serviceId),branch:form.branch,buildNote:form.buildNote,commitId:commitPreview.value.commitId,...(isMobile.value?{environmentName:form.environmentName}:{}),...(productionFromPre.value?{preBuildTaskId:preBuildTaskId.value}:{}),...(automaticPublish.value?{autoPublish:{environment:plannedPublish.value.environment,serverIds:[...plannedPublish.value.serverIds]}}:{})}
  const plannedTarget:BuildPublishTarget|null|undefined=isMobile.value?undefined:can('release')&&plannedPublish.value.mode==='PUBLISH'&&plannedPublish.value.environment==='prod'?{environment:'prod',serverIds:[...plannedPublish.value.serverIds]}:null
  const fingerprint=JSON.stringify(body);if(fingerprint!==submittedFingerprint){requestId=newRequestId();submittedFingerprint=fingerprint}
  submitting.value=true
  try{const data=payload<Record<string,unknown>>(await http.post(api('/builds'),{...body,requestId}),{});if(!data.id)throw new Error(tUi('未读取到打包任务编号，请先检查打包记录'));rememberBuildPublish(String(data.id),plannedTarget);ElMessage.success(automaticPublish.value?tUi('打包任务已加入队列，成功后自动发布到')+environmentLabel(plannedPublish.value.environment):tUi('打包任务已加入队列'));await router.replace(buildPath(String(data.id)))}catch(e){ElMessage.error(errorText(e,tUi('创建打包任务失败')))}finally{submitting.value=false}
}
onMounted(async()=>{const initial=form.serviceId;await loadServices();if(initial)await loadBranches()})
onBeforeUnmount(()=>{disposed=true;candidateRequest++;versionRequest++;branchRequest++;commitRequest++;candidateAbort?.abort();versionAbort?.abort()})
</script>

<template>
  <div class="page-head"><div><h2>{{apps?tUi('新建 App 构建'):tUi('新建打包')}}</h2><p>{{ tUi('锁定代码版本，校验分支基线，再生成可发布制品。') }}</p></div><el-button :icon="ArrowLeft" @click="apps?router.push(form.serviceId?servicePath(form.serviceId):'/apps'):router.back()">{{ tUi('返回') }}</el-button></div>
  <section class="panel wizard-card" v-loading="loading"><div class="wizard-body"><div class="wizard-section"><h3>{{ tUi('打包配置') }}</h3><p class="muted">{{ tUi('确认代码版本后加入队列，打包成功的制品可用于发版。') }}</p><el-form label-position="top" :disabled="submitting" style="margin-top:24px">
    <el-form-item :label="tUi('目标服务')" required><ServiceSelect v-model="form.serviceId" :services="services" :disabled="locked"/></el-form-item>
    <el-alert v-if="locked&&!loading&&!selected" class="unavailable-service" :title="tUi('此服务暂不能打包')" type="warning" :closable="false" show-icon style="margin-bottom:20px"><p>{{ tUi('服务未启用或不存在。待配置的 App 需先补齐构建配置并启用。') }}</p><el-button link type="primary" @click="router.push(apps?'/apps':'/services')">{{apps?tUi('返回 App 发布'):tUi('返回服务管理')}}</el-button></el-alert>
    <div v-if="selected" class="repository-hint"><span>{{ tUi('代码仓库') }}</span><span class="mono">{{selected.gitUrl||tUi('未配置 Git 仓库')}}</span></div>
    <template v-if="isMobile">
      <el-form-item :label="tUi('构建环境')" required><el-radio-group v-model="form.environmentName" class="app-build-environments" :aria-label="tUi('App 构建环境')"><el-radio-button v-for="env in appEnvironments" :key="env.name" :value="env.name">{{env.label}}</el-radio-button></el-radio-group><small class="app-build-hint">{{ tUi('先选择环境，再核对代码。安装包只能交付到所选环境。') }}</small></el-form-item>
      <el-alert v-if="!appEnvironments.length" :title="tUi('还没有启用的 App 构建环境，请先配置该环境的包名和签名，再启用构建。')" type="warning" :closable="false" show-icon/>
      <div v-if="appProfile" class="app-build-summary" :class="{'is-production':form.environmentName==='prod'}"><div class="app-build-summary-title"><b>{{ tUi('{0}安装包', environmentLabel(form.environmentName)) }}</b><el-tag :type="form.environmentName==='prod'?'danger':'warning'" effect="plain">{{appProfile.buildMode==='DEBUG'?'Debug':'Release'}} · {{mobileFormat(selected?.mobileBuild)}}</el-tag></div><dl><dt>{{ tUi('包名') }}</dt><dd>{{appProfile.applicationId || tUi('未配置')}}</dd><dt>{{ tUi('签名配置') }}</dt><dd>{{appProfile.signingProfile || tUi('未配置')}}<small>{{appProfile.allowDebugSigning?tUi('允许已登记测试证书'):tUi('仅正式证书')}}</small></dd><dt>{{ tUi('证书指纹') }}</dt><dd class="fingerprint">{{appProfile.expectedCertificateSha256 || tUi('未配置')}}</dd><template v-for="parameter in appParameters" :key="parameter.group + parameter.key"><dt class="parameter-preview-name">{{parameter.key}}<small>{{parameter.group}}</small></dt><dd>{{parameter.value || tUi('（空字符串）')}}</dd></template></dl><p v-if="!appParameters.length">{{ tUi('未添加构建参数，沿用 App 源码默认值；请确认与所选环境一致。') }}</p><el-alert v-if="appBuildError" :title="appBuildError" type="error" :closable="false" show-icon/></div>
    </template>
    <div class="branch-baseline" role="note" :aria-label="tUi('打包基准分支')">
      <div><span>{{ tUi('基准分支') }}</span><strong>master</strong><small>{{ tUi('远程最新') }}</small></div>
      <p>{{ tUi('所有项目的待构建版本都必须包含该基准分支的最新提交。任务执行前和每次重试都会重新校验；不满足时阻止打包。') }}</p>
    </div>
    <div v-if="productionFromPre" class="pre-version-picker"><div class="pre-picker-heading"><b>{{ tUi('选择已验证的 PRE 版本') }}</b><el-button link type="primary" :loading="candidateLoading" @click="loadCandidates">{{ tUi('刷新 PRE 版本') }}</el-button></div><p>{{ tUi('生产会重新构建选中 PRE 的同一份代码，使用生产环境配置与签名。分支和 Commit 已锁定。') }}</p><el-select v-model="preBuildTaskId" :loading="candidateLoading" clearable filterable :placeholder="tUi('选择成功的 PRE 构建')" :aria-label="tUi('选择成功 PRE 版本')" style="width:100%"><el-option v-for="candidate in candidates" :key="candidate.buildTaskId" :value="candidate.buildTaskId" :label="`${candidate.versionName} · ${candidate.branch} · ${candidate.commitId.slice(0,8)}`"><div class="pre-version-option"><b>{{candidate.versionName}}</b><span>{{candidate.branch}} · {{candidate.commitId.slice(0,8)}}</span></div></el-option></el-select><el-alert v-if="candidateError" :title="candidateError" type="error" :closable="false" show-icon/><p v-else-if="!candidateLoading&&!candidates.length" class="pre-version-empty">{{ tUi('暂无可用的成功 PRE 版本。请先完成预生产构建，再回来创建生产版本。') }}</p><div v-if="selectedPre" class="pre-source-meta"><span>{{ tUi('PRE 构建时间 {0}', formatTime(selectedPre.createdAt)) }}</span><span>{{ tUi('来源构建 #{0}', selectedPre.buildTaskId) }}</span></div></div>
    <el-form-item v-else :label="tUi('待构建分支')" required style="margin-top:20px"><el-select v-model="form.branch" :disabled="!selected" :loading="branchLoading" filterable size="large" :placeholder="tUi('从 Git 仓库选择要构建的分支')" style="width:100%"><el-option v-for="branch in branches" :key="branch" :label="branch" :value="branch"/></el-select></el-form-item>
    <el-alert v-if="!productionFromPre&&versionBranchError&&!form.branch" :title="versionBranchError" type="warning" :closable="false" show-icon style="margin-bottom:18px"/>
    <p v-else-if="!productionFromPre&&requestedVersionBranch&&form.branch===requestedVersionBranch" class="version-build-source">{{ tUi('已带入所选分支，下面展示该分支当前最新的 Commit；请核对后再打包。') }}</p>
    <div v-if="automaticVersion&&form.environmentName" class="automatic-version-preview" :class="{'is-production':productionFromPre}" data-testid="automatic-version-preview"><div><span>{{productionFromPre?tUi('预计生产版本'):tUi('预计 App 版本')}}</span><strong>{{versionLoading?tUi('正在核对版本…'):versionPreview?.versionName||tUi('待确认代码版本')}}</strong></div><p>{{productionFromPre?tUi('生产版本独立递增：0.0.1 → 0.0.2，不加后缀。代码锁定所选 PRE 的分支和 Commit。'):form.environmentName==='dev'?tUi('开发版本由平台独立递进，使用 -dev 后缀。'):tUi('由平台依次分配版本，例如 0.1.9-pre → 0.2.0-pre。')}}</p><small>{{ tUi('预览不占用版本；提交后以任务实际分配的版本为准。') }}</small><el-alert v-if="versionError" :title="versionError" type="warning" :closable="false" show-icon/><el-button v-if="versionError" link type="primary" :loading="versionLoading" @click="loadVersionPreview">{{ tUi('重新预览版本') }}</el-button></div>
    <div class="commit-wrap" v-loading="commitLoading" element-loading-text="正在读取分支最新提交…">
      <div v-if="commitPreview" class="commit-card">
        <el-alert v-if="changedNotice" class="commit-drift" :title="tUi('收到提醒后，分支已有新提交')" type="warning" :closable="false" show-icon>
          {{ tUi('提醒对应') }} <CommitHash :value="changedNotice.noticed"/>{{ tUi('，本次将构建') }} <CommitHash :value="changedNotice.latest"/>{{ tUi('。请核对后确认。') }}
        </el-alert>
        <div class="commit-head"><div><span class="commit-label">{{ tUi('待构建分支 · {0}', form.branch) }}</span><h4>{{commitPreview.title||tUi('未填写提交标题')}}</h4></div><div class="build-revision"><span>{{ tUi('待构建 Commit') }}</span><CommitHash :value="commitPreview.commitId"/></div></div>
        <pre v-if="commitPreview.message&&commitPreview.message!==commitPreview.title" class="commit-message">{{commitPreview.message}}</pre>
        <div class="commit-meta"><span><el-icon><User/></el-icon>{{commitPreview.authorName}} <small>{{commitPreview.authorEmail}}</small></span><span><el-icon><Clock/></el-icon>{{time(commitPreview.committedAt)}}</span></div>
        <details v-if="commitPreview.changedFiles.length" class="changed-files"><summary>{{ tUi('查看变更文件（{0}{1}）', commitPreview.changedFiles.length, commitPreview.changedFilesTruncated?'+':'') }}</summary><ul><li v-for="file in commitPreview.changedFiles" :key="file" class="mono">{{file}}</li></ul></details>
        <el-checkbox v-model="commitConfirmed" class="commit-confirm">{{ tUi('我已确认，本次打包将构建以上 Commit') }}</el-checkbox>
      </div>
      <el-alert v-else-if="commitError" type="error" :closable="false" show-icon><template #title>{{ tUi('最新提交读取失败') }}</template><div>{{commitError}}</div><el-button link type="primary" :icon="RefreshRight" @click="loadCommit">{{ tUi('重新读取') }}</el-button></el-alert>
      <div v-else class="commit-placeholder">{{productionFromPre?tUi('选择成功 PRE 版本后，展示其锁定的 Commit 供确认。'):tUi('选择分支后将在这里显示最新 Commit，供打包前确认。')}}</div>
    </div>
    <BuildPublishPlan v-if="selected&&!isMobile&&can('release')" v-model="plannedPublish" :service-id="form.serviceId" :disabled="submitting" @valid="publishPlanValid=$event"/>
    <el-form-item :label="tUi('打包说明')"><el-input v-model="form.buildNote" type="textarea" :rows="3" :placeholder="tUi('本次构建的变更说明')"/></el-form-item>
  </el-form><div v-if="selected" class="pipeline-preview"><span>{{ tUi('拉取代码') }}</span><i>→</i><span>{{buildLabel}}</span><i>→</i><span>{{isMobile?tUi('校验包名与签名'):tUi('构建镜像')}}</span><i>→</i><span>{{isMobile?tUi('归档 ')+mobileFormat(selected?.mobileBuild):tUi('推送镜像')}}</span></div></div></div>
  <div class="wizard-foot"><span class="muted">{{automaticPublish?tUi('成功后自动发布到')+environmentLabel(plannedPublish.environment)+' · '+plannedPublish.serverIds.length+tUi(' 台服务器'):isMobile&&form.environmentName?tUi('将构建')+environmentLabel(form.environmentName)+tUi('安装包，环境信息随任务锁定'):tUi('将构建已确认的 Commit')}}</span><el-button type="primary" :icon="Check" :loading="submitting" :disabled="!selected||commitLoading||!commitPreview||!commitConfirmed||!!appBuildError||versionLoading||!versionReady||!isMobile&&can('release')&&!publishPlanValid" @click="submit">{{automaticPublish?tUi('打包并自动发布'):apps?tUi('加入构建队列'):tUi('加入打包队列')}}</el-button></div></section>
</template>

<style scoped>
.parameter-preview-name{overflow-wrap:anywhere}.parameter-preview-name small{display:block;margin-top:4px;font-size:10px;line-height:1.7;overflow-wrap:anywhere;color:#74849a}
.pre-version-picker{padding:18px;margin:0 0 22px;background:#fffcfa;border:1px solid #edd9d2;border-radius:9px}.pre-picker-heading{display:flex;justify-content:space-between;gap:12px;align-items:center;color:#354b68;font-size:14px}.pre-version-picker p{font-size:12px;color:#74849a;line-height:1.8}.pre-version-picker .el-alert{margin-top:12px}.pre-source-meta{display:flex;flex-wrap:wrap;gap:6px 15px;margin-top:12px;color:#74849a;font-size:11px}.pre-version-option{display:flex;gap:12px;align-items:center;font-size:12px}.pre-version-option>span{min-width:0;overflow:hidden;text-overflow:ellipsis;color:#74849a}.automatic-version-preview{padding:16px 18px;background:#f3f7fd;border-left:3px solid #6b87c4;border-radius:6px;margin-bottom:20px}.automatic-version-preview>div:first-child{display:flex;align-items:baseline;gap:16px;flex-wrap:wrap}.automatic-version-preview>div>span{font-size:12px;color:#74849a}.automatic-version-preview strong{font-size:21px;color:#355da7;font-weight:650;overflow-wrap:anywhere}.automatic-version-preview p{font-size:12px;color:#52647c;line-height:1.8;margin:8px 0 4px}.automatic-version-preview small{font-size:11px;color:#74849a;line-height:1.8}.automatic-version-preview .el-alert{margin-top:10px}.automatic-version-preview.is-production{background:#fffcfa;border-left-color:#c48b6b}.automatic-version-preview.is-production strong{color:#a35340}
.version-build-source{margin:-8px 0 16px;color:#5978af;font-size:12px;line-height:1.7}
.app-build-environments{display:flex;width:100%}.app-build-environments :deep(.el-radio-button){flex:1}.app-build-environments :deep(.el-radio-button__inner){width:100%;padding:13px}.app-build-hint{display:block;width:100%;color:#74849a;font-size:12px;margin-top:8px}.app-build-summary{padding:18px;margin-bottom:22px;border:1px solid #dce5f1;border-radius:9px;background:#fafcff}.app-build-summary.is-production{border-color:#edd9d2;background:#fffcfa}.app-build-summary-title{display:flex;justify-content:space-between;gap:14px;align-items:center;color:#304863}.app-build-summary dl{display:grid;grid-template-columns:88px minmax(0,1fr);gap:10px;font-size:12px;margin:18px 0 0}.app-build-summary dt{color:#74849a}.app-build-summary dd{margin:0;overflow-wrap:anywhere;color:#354b68}.app-build-summary dd small{display:block;color:#74849a;margin-top:5px}.app-build-summary p{font-size:12px;color:#74849a;line-height:1.8;margin-bottom:0}.app-build-summary .el-alert{margin-top:15px}

.branch-baseline{padding:15px 17px;margin:0 0 22px;border-left:3px solid #6b87c4;border-radius:6px;background:#f3f6fb}.branch-baseline>div{display:flex;align-items:center;gap:12px}.branch-baseline>div>span{color:#667b95;font-size:12px}.branch-baseline strong{color:#304863;font-size:15px;font-weight:600}.branch-baseline small{color:#6d809b;font-size:11px;padding:2px 7px;border:1px solid #dae3f0;border-radius:4px}.branch-baseline p{margin:10px 0 0;color:#667b95;font-size:12px;line-height:1.8}.build-revision{display:grid;justify-items:end;gap:5px;flex-shrink:0}.build-revision>span{font-size:11px;color:#74849a}.commit-head>div:first-child{min-width:0}.commit-label{overflow-wrap:anywhere}@media(max-width:650px){.commit-head{flex-wrap:wrap}.build-revision{justify-items:start}.branch-baseline{padding:13px 14px}.pipeline-preview{flex-wrap:wrap}}
.repository-hint{display:flex;align-items:baseline;gap:10px;padding:0 2px;margin:-12px 0 20px;font-size:11px;color:#93a0b4}.repository-hint>span:first-child{flex-shrink:0}.repository-hint .mono{min-width:0;overflow-wrap:anywhere;color:#7688a2}.commit-wrap{min-height:92px;margin:-4px 0 20px}.commit-card{padding:18px;border:1px solid #cfe0f5;border-radius:12px;background:#f8fbff}.commit-head{display:flex;justify-content:space-between;gap:16px;align-items:flex-start}.commit-label{font-size:12px;color:#1677ff}.commit-head h4{margin:6px 0 0;color:#182230;font-size:16px}.commit-id{font-family:Consolas,monospace}.commit-message{margin:14px 0 0;padding:12px;border-radius:8px;background:#fff;border:1px solid #e4eaf1;color:#344054;font:12px/1.65 Consolas,monospace;white-space:pre-wrap;max-height:160px;overflow:auto}.commit-meta{display:flex;flex-wrap:wrap;gap:12px 22px;margin-top:14px;color:#667085;font-size:12px}.commit-meta span{display:flex;align-items:center;gap:6px}.commit-meta small{color:#98a2b3}.changed-files{margin-top:13px;color:#475467;font-size:12px}.changed-files summary{cursor:pointer;color:#1677ff}.changed-files ul{max-height:160px;overflow:auto;margin:9px 0 0;padding:10px 10px 10px 30px;background:#fff;border-radius:8px}.changed-files li{padding:3px 0}.commit-confirm{margin-top:15px}.commit-placeholder{display:grid;place-items:center;min-height:92px;border:1px dashed #d0d5dd;border-radius:11px;color:#98a2b3;font-size:12px}.pipeline-preview{display:flex;align-items:center;justify-content:center;gap:9px;color:#6e8198;font-size:12px;margin-top:24px}.pipeline-preview span{background:#f8fafc;border:1px solid #dce3eb;padding:8px 11px;border-radius:7px}.pipeline-preview i{font-style:normal}.wizard-foot{display:flex;justify-content:space-between;padding:18px 30px;border-top:1px solid #e4e7ec}</style>
