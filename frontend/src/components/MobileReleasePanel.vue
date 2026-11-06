<script setup lang="ts">
import { useAppScope } from '../composables/useAppScope'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { errorText, http, payload } from '../api'
import { environmentLabel, formatTime, newRequestId } from '../ui'
import type { Release } from '../types'
import CommitHash from './CommitHash.vue'
import { createPackageDownload, PackageDownloadError, type PackageDownloadProgress } from '../mobileDownload'

interface MobileArtifact { buildEnvironmentName?:string; buildMode?:string; format?:'APK'|'AAB'; artifactId:number|string; buildTaskId?:number|string; applicationId:string; versionName:string; versionCode:number; sha256:string; sizeBytes:number; certificateSha256?:string; commitId?:string; flutterVersion?:string; flavor?:string; target?:string }
interface Delivery { task:Release; artifact:MobileArtifact; downloadedAt?:string; confirmedAt?:string; confirmationStatus?:string; confirmationMessage?:string; accessExpiresAt?:string }
interface Access { fetchToken:string; confirmToken:string; expiresAt:string; downloadUrl:string; confirmationUrl:string }
const { api, can } = useAppScope()
const props = defineProps<{ task:Release }>()
const emit = defineEmits<{ changed:[] }>()
const delivery=ref<Delivery>(), loading=ref(false), error=ref(''), downloading=ref(false), confirming=ref(false), confirmVisible=ref(false), confirmSuccess=ref(true), confirmMessage=ref(''), acknowledged=ref(false), confirmError=ref('')
const access=ref<Access>(), accessBusy=ref(false), accessVisible=ref(false)
const downloadState=ref<'idle'|'preparing'|'downloading'|'saving'|'ready'|'cancelled'|'error'>('idle')
const downloadProgress=ref<PackageDownloadProgress>({loaded:0,total:0,percent:0,speedBytesPerSecond:0}), downloadError=ref('')
let activeDownload:ReturnType<typeof createPackageDownload>|undefined, downloadRevision=0
const downloadTitle=computed(()=>({idle:'',preparing:'正在准备安装包',downloading:'正在下载安装包',saving:'正在准备保存',ready:'安装包已接收，请在浏览器中保存',cancelled:'下载已取消',error:'下载未完成'}[downloadState.value]))
const downloadPercent=computed(()=>downloadProgress.value.percent)
let revision=0, disposed=false, timer:number|undefined, confirmFingerprint='', confirmRequest=''
const artifact=computed(()=>delivery.value?.artifact)
const format=computed(()=>artifact.value?.format==='APK'?'APK':'AAB')
const pending=computed(()=>props.task.status==='WAITING_CONFIRMATION' && !delivery.value?.confirmedAt)
const canConfirm=computed(()=>can('release') && pending.value && !!delivery.value?.downloadedAt && !!artifact.value?.sha256)
const environment=computed(()=>environmentLabel(props.task.environment))
function apiAddress(value:string){try{return new URL(value,location.origin).href}catch{return value}}
function size(bytes?:number){if(bytes==null)return '—';return bytes>=1024*1024?`${(bytes/1024/1024).toFixed(1)} MB`:`${(bytes/1024).toFixed(1)} KB`}
async function load(){
  const current=++revision;loading.value=true
  if(timer)window.clearTimeout(timer)
  try{const result=payload<Delivery>(await http.get(api(`/mobile/releases/${props.task.id}`)),{} as Delivery);if(disposed||current!==revision)return;if(!result.artifact?.sha256)throw new Error('安装包信息不完整，暂不能交付');delivery.value=result;error.value='';if(result.task?.status && result.task.status!==props.task.status)emit('changed')}
  catch(reason){if(!disposed&&current===revision)error.value=errorText(reason,'读取 App 发布信息失败')}
  finally{if(!disposed&&current===revision){loading.value=false;if(pending.value)timer=window.setTimeout(load,5000)}}
}
async function download(){
  if(downloading.value||!artifact.value||loading.value&&!delivery.value)return
  const current=++downloadRevision, taskId=props.task.id, selected={...artifact.value}
  const filename=`${selected.applicationId || 'android'}-${selected.versionCode || selected.artifactId}.${selected.format==='APK'?'apk':'aab'}`
  const isCurrent=()=>!disposed&&current===downloadRevision&&taskId===props.task.id
  downloading.value=true;downloadError.value='';downloadState.value='preparing'
  downloadProgress.value={loaded:0,total:selected.sizeBytes,percent:0,speedBytesPerSecond:0}
  const request=createPackageDownload({expectedBytes:selected.sizeBytes,request:config=>http.get(api(`/mobile/releases/${taskId}/download`),config),onProgress:value=>{
    if(!isCurrent())return
    downloadProgress.value=value
    if(value.loaded>0)downloadState.value=value.percent===100?'saving':'downloading'
  }})
  activeDownload=request
  try{
    const blob=await request.promise
    if(!isCurrent())return
    const url=URL.createObjectURL(blob), link=document.createElement('a')
    link.href=url;link.download=filename;document.body.appendChild(link)
    try{link.click()}finally{link.remove();window.setTimeout(()=>URL.revokeObjectURL(url),60000)}
    downloadState.value='ready'
    downloading.value=false;activeDownload=undefined
    ElMessage.success('安装包已完整接收，请在浏览器中保存')
    await load()
  }catch(reason){
    if(!isCurrent())return
    if(reason instanceof PackageDownloadError&&reason.kind==='cancelled')downloadState.value='cancelled'
    else{downloadState.value='error';downloadError.value=reason instanceof Error?reason.message:'下载失败，请重新下载'}
  }finally{if(isCurrent()){downloading.value=false;activeDownload=undefined}}
}
function cancelDownload(){
  if(!downloading.value)return
  ++downloadRevision;activeDownload?.cancel();activeDownload=undefined;downloading.value=false;downloadState.value='cancelled';downloadError.value=''
}
function openConfirm(){confirmSuccess.value=true;confirmMessage.value='';acknowledged.value=false;confirmError.value='';confirmVisible.value=true}
async function confirm(){
  if(confirming.value||!canConfirm.value||!acknowledged.value||!artifact.value)return
  const body={artifactId:Number(artifact.value.artifactId),sha256:artifact.value.sha256,success:confirmSuccess.value,message:confirmMessage.value.trim()}
  const fingerprint=JSON.stringify(body);if(confirmFingerprint!==fingerprint){confirmFingerprint=fingerprint;confirmRequest=newRequestId()}
  confirming.value=true;confirmError.value=''
  try{await http.post(api(`/mobile/releases/${props.task.id}/confirm`),{...body,requestId:confirmRequest});if(disposed)return;confirmVisible.value=false;ElMessage.success('已记录实际发布结果');await load();emit('changed')}
  catch(reason){if(!disposed)confirmError.value=errorText(reason,'提交结果失败，请检查记录后重试')}
  finally{confirming.value=false}
}
async function createAccess(){if(accessBusy.value)return;accessBusy.value=true;try{const result=payload<Access>(await http.post(api(`/mobile/releases/${props.task.id}/access-token`)),{} as Access);if(disposed)return;access.value=result;if(delivery.value)delivery.value.accessExpiresAt=result.expiresAt;accessVisible.value=true;ElMessage.success('已生成本条发布记录专用接口凭据')}catch(reason){ElMessage.error(errorText(reason,'生成接口凭据失败'))}finally{accessBusy.value=false}}
async function revokeAccess(){if(accessBusy.value)return;accessBusy.value=true;try{await http.delete(api(`/mobile/releases/${props.task.id}/access-token`));access.value=undefined;accessVisible.value=false;if(delivery.value)delivery.value.accessExpiresAt=undefined;ElMessage.success('该发布记录的接口凭据已撤销')}catch(reason){ElMessage.error(errorText(reason,'撤销失败'))}finally{accessBusy.value=false}}
function closeAccess(){access.value=undefined;accessVisible.value=false}
watch(()=>props.task.id,()=>{
  ++downloadRevision;activeDownload?.cancel();activeDownload=undefined;downloading.value=false;downloadState.value='idle';downloadError.value=''
  delivery.value=undefined;access.value=undefined;accessVisible.value=false;confirmVisible.value=false
},{flush:'sync'})
watch(()=>[props.task.id,props.task.status],load,{immediate:true})
onBeforeUnmount(()=>{disposed=true;revision++;downloadRevision++;activeDownload?.cancel();if(timer)window.clearTimeout(timer);access.value=undefined})
</script>

<template>
  <section class="panel mobile-delivery" v-loading="loading && !delivery">
    <div class="panel-title"><div><h3>Android 安装包交付</h3><span class="panel-subtitle">{{environment}}环境 · 发布记录 #{{task.id}}</span></div><el-tag :type="task.environment==='prod'?'danger':'info'" effect="plain">{{environment}}</el-tag></div>
    <div v-if="error" class="mobile-delivery-error"><el-alert :title="error" type="error" :closable="false" show-icon/><el-button @click="load">重新读取</el-button></div>
    <template v-else-if="artifact">
      <div class="delivery-artifact"><div><span>App 版本</span><h4>{{artifact.versionName || '版本未记录'}}</h4><p>{{artifact.applicationId}}<i>Android {{format==='APK'?'完整 APK':'AAB'}} · {{size(artifact.sizeBytes)}}</i></p><p class="artifact-flavor">构建环境：{{artifact.buildEnvironmentName?environmentLabel(artifact.buildEnvironmentName):'历史记录未保存'}} · 模式：{{artifact.buildMode || '历史记录未保存'}}</p><p class="artifact-flavor">Flavor：{{artifact.flavor || '未指定（项目默认）'}}</p></div><CommitHash :value="artifact.commitId || task.commitId"/></div>
      <div class="delivery-progress"><div class="complete"><b>安装包已锁定</b><span>制品 #{{artifact.artifactId}}<template v-if="artifact.buildTaskId"> · 构建 #{{artifact.buildTaskId}}</template></span></div><div :class="{complete:delivery?.downloadedAt}"><b>{{delivery?.downloadedAt?'已拉取安装包':'待拉取安装包'}}</b><span>{{delivery?.downloadedAt?formatTime(delivery.downloadedAt):'下载或通过接口拉取本次 '+format}}</span></div><div :class="{complete:delivery?.confirmedAt}"><b>{{delivery?.confirmedAt?'已确认发布结果':'待确认实际发布'}}</b><span>{{delivery?.confirmedAt?formatTime(delivery.confirmedAt):'在外部完成发布后，回传结果'}}</span></div></div>
      <p class="delivery-guidance">{{task.environment==='prod'?'启用生产自动合回时，平台会在确认发布成功后尝试合回已验证的提交。':'该环境仅记录发布结果，不触发代码合并。'}} 下载或导入外部版本草稿，不代表已经发布。请在外部版本正式生效后确认。</p>
      <details class="artifact-verification"><summary>查看安装包校验信息</summary><dl><dt>内部 versionCode</dt><dd>{{artifact.versionCode}} · 用于 Android 覆盖升级与接口核验</dd><dt>文件 SHA-256</dt><dd>{{artifact.sha256}}</dd><dt>签名证书 SHA-256</dt><dd>{{artifact.certificateSha256 || '—'}}</dd><dt>Dart 入口</dt><dd>{{artifact.target || 'lib/main.dart'}}</dd><dt>Flutter 版本</dt><dd>{{artifact.flutterVersion || '—'}}</dd></dl></details>
      <p v-if="delivery?.confirmationMessage" class="delivery-result">发布说明：{{delivery.confirmationMessage}}</p>
      <div class="delivery-actions"><el-button :loading="downloading" @click="download">{{downloadState==='error'||downloadState==='cancelled'?'重新下载':'下载本次 '+format}}</el-button><el-button v-if="pending && can('release')" type="primary" :disabled="!canConfirm" @click="openConfirm">确认实际发布结果</el-button><small v-if="pending && !delivery?.downloadedAt">先拉取本次安装包，再确认发布结果。</small></div>
      <div v-if="downloadState!=='idle'" class="package-download" :class="{'package-download-error':downloadState==='error'}" data-testid="package-download">
        <div class="package-download-heading"><span role="status" aria-live="polite">{{downloadTitle}}</span><el-button v-if="downloading" size="small" @click="cancelDownload">取消下载</el-button><b v-else-if="downloadState==='ready'">100%</b></div>
        <el-progress v-if="downloadState!=='cancelled'" :percentage="downloadPercent" :status="downloadState==='error'?'exception':downloadState==='ready'?'success':undefined" :stroke-width="8" :show-text="downloadState!=='ready'"/>
        <div class="package-download-stats"><span>{{size(downloadProgress.loaded)}} / {{size(downloadProgress.total)}}</span><span v-if="downloadState==='downloading'">{{size(downloadProgress.speedBytesPerSecond)}}/s</span></div>
        <p v-if="downloadState==='error'" role="alert">{{downloadError}}</p>
        <p v-else-if="downloadState==='cancelled'">可以重新下载，安装包仍保存在平台。</p>
        <p v-else-if="downloadState==='ready'">接收完成不代表已发布，请在外部完成发布后再确认结果。</p>
        <p v-else>接收完成后会交给浏览器保存。保持此页面打开，慢速下载会持续进行。</p>
      </div>
      <div v-if="can('release') && (pending || delivery?.accessExpiresAt)" class="delivery-api"><div><b>由外部系统拉取和确认</b><p>{{delivery?.accessExpiresAt?'接口凭据到期：'+formatTime(delivery.accessExpiresAt)+'。可立即撤销。':'生成只属于本条记录的拉取与确认凭据，有效期 24 小时，可随时撤销。'}}</p></div><div><el-button v-if="pending" :loading="accessBusy" @click="createAccess">生成接口凭据</el-button><el-button link type="danger" :disabled="accessBusy" @click="revokeAccess">撤销凭据</el-button></div></div>
    </template>
  </section>

  <el-dialog v-model="confirmVisible" title="确认 App 实际发布结果" width="560px" :close-on-click-modal="false" :show-close="!confirming" :close-on-press-escape="!confirming">
    <div class="confirm-delivery-summary"><b>{{task.serviceName}} · {{environment}} · {{artifact?.versionName}}</b><span>发布 #{{task.id}} · 制品 #{{artifact?.artifactId}}</span><CommitHash :value="artifact?.commitId || task.commitId"/></div>
    <el-form label-position="top" :disabled="confirming"><el-form-item label="实际发布结果"><el-radio-group v-model="confirmSuccess"><el-radio-button :value="true">发布成功</el-radio-button><el-radio-button :value="false">发布失败</el-radio-button></el-radio-group></el-form-item><el-form-item label="结果说明（选填）"><el-input v-model="confirmMessage" type="textarea" :rows="3" maxlength="500" placeholder="例如商店发布结果或自有版本库的版本记录"/></el-form-item><el-checkbox v-model="acknowledged" class="delivery-ack">我确认这是以上安装包在{{environment}}环境的实际发布结果，已核对外部发布记录。</el-checkbox></el-form>
    <el-alert v-if="task.environment==='prod' && confirmSuccess" title="确认生产发布成功将触发代码合回。仅下载完成时，请勿确认成功。" type="warning" :closable="false" show-icon style="margin-top:16px"/>
    <el-alert v-if="confirmError" :title="confirmError" type="error" :closable="false" show-icon style="margin-top:16px"/>
    <template #footer><el-button :disabled="confirming" @click="confirmVisible=false">暂不确认</el-button><el-button :type="task.environment==='prod'?'danger':'primary'" :disabled="!acknowledged || !canConfirm" :loading="confirming" @click="confirm">提交发布结果</el-button></template>
  </el-dialog>

  <el-dialog v-model="accessVisible" title="本次发布的接口凭据" width="690px" :close-on-click-modal="false" @closed="closeAccess">
    <template v-if="access"><el-alert title="凭据仅在此显示一次，关闭后不再展示。请交给负责本次发布的外部系统。" type="info" :closable="false" show-icon/>
      <div class="access-fields"><p>范围：{{environment}} · 发布 #{{task.id}} · 制品 #{{artifact?.artifactId}}<br/>到期：{{formatTime(access.expiresAt)}}</p><label>拉取地址（GET）<el-input :model-value="apiAddress(access.downloadUrl)" readonly/></label><label>拉取 Authorization 请求头<el-input :model-value="'Bearer '+access.fetchToken" readonly type="password" show-password/></label><label>确认地址（POST）<el-input :model-value="apiAddress(access.confirmationUrl)" readonly/></label><label>确认 Authorization 请求头<el-input :model-value="'Bearer '+access.confirmToken" readonly type="password" show-password/></label><label>确认请求体示例<pre>{{JSON.stringify({artifactId:Number(artifact?.artifactId),sha256:artifact?.sha256,success:true,requestId:'每次结果使用一个唯一 UUID',message:'实际发布结果说明'},null,2)}}</pre></label><p>两个凭据用途独立，请勿交换。确认接口只在外部实际发布后调用；重试时保留相同 requestId。</p></div>
    </template><template #footer><el-button type="danger" plain :loading="accessBusy" @click="revokeAccess">撤销本次凭据</el-button><el-button type="primary" @click="closeAccess">已保存，关闭</el-button></template>
  </el-dialog>
</template>

<style scoped>
.package-download{margin-top:16px;padding:16px 18px;border:1px solid #dbe5f5;border-radius:8px;background:#f7faff;max-width:680px}.package-download-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;min-height:28px;margin-bottom:10px;color:#304863;font-size:13px;font-weight:600}.package-download-heading>b{color:#2e7863;font-variant-numeric:tabular-nums}.package-download-stats{display:flex;justify-content:space-between;gap:12px;margin-top:8px;color:#526984;font-size:12px;font-variant-numeric:tabular-nums}.package-download p{margin:10px 0 0;color:#667b95;font-size:12px;line-height:1.65}.package-download-error{border-color:#edc6c6;background:#fffafa}.package-download-error p{color:#b43d3d}.package-download :deep(.el-progress__text){font-size:12px!important;font-variant-numeric:tabular-nums}@media(max-width:650px){.package-download{padding:14px 12px}.package-download-heading{align-items:flex-start}}
.mobile-delivery{margin-bottom:20px}.mobile-delivery .panel-title>:deep(.el-tag){flex-shrink:0}.delivery-artifact{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;padding:7px 0 24px}.delivery-artifact>div{min-width:0}.delivery-artifact span{font-size:12px;color:#74849a}.delivery-artifact h4{margin:8px 0 10px;font-size:21px;color:#304863;overflow-wrap:anywhere}.delivery-artifact p{margin:0;font-size:13px;color:#526984}.delivery-artifact b{font-weight:400}.delivery-artifact p.artifact-flavor{margin-top:10px;font-size:12px;color:#667b95}.delivery-artifact i{display:inline-block;font-style:normal;margin-left:18px;color:#74849a;font-size:12px}.delivery-progress{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));border-top:1px solid #e1e8f2;border-bottom:1px solid #e1e8f2}.delivery-progress>div{padding:18px 16px 18px 0;border-top:3px solid transparent}.delivery-progress .complete{border-top-color:#4b9b83}.delivery-progress b{display:block;color:#657992;font-size:13px}.delivery-progress .complete b{color:#2e7863}.delivery-progress span{display:block;margin-top:8px;color:#74849a;font-size:12px;line-height:1.6}.delivery-guidance,.delivery-result{font-size:13px;color:#617793;line-height:1.8;margin:19px 0}.artifact-verification{font-size:12px;color:#667b95}.artifact-verification summary{cursor:pointer;padding:5px 0}.artifact-verification dl{display:grid;grid-template-columns:150px 1fr;gap:10px;padding:16px;background:#f7f9fc;border-radius:8px}.artifact-verification dt{color:#74849a}.artifact-verification dd{margin:0;word-break:break-all;line-height:1.6}.delivery-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-top:23px}.delivery-actions small{font-size:12px;color:#74849a}.delivery-actions :deep(.el-button+.el-button){margin-left:0}.delivery-api{display:flex;justify-content:space-between;gap:16px;align-items:center;border-top:1px solid #e7edf5;margin-top:24px;padding-top:20px}.delivery-api b{font-size:13px;color:#526984}.delivery-api p{margin:6px 0 0;font-size:12px;color:#74849a;line-height:1.7}.delivery-api>div:last-child{display:flex;gap:12px;flex-shrink:0}.confirm-delivery-summary{display:grid;gap:10px;padding-bottom:18px;margin-bottom:20px;border-bottom:1px solid #e7edf5}.confirm-delivery-summary>b{color:#304863;font-size:16px}.confirm-delivery-summary>span{font-size:12px;color:#74849a}.delivery-ack{height:auto;align-items:flex-start}.delivery-ack :deep(.el-checkbox__label){white-space:normal;font-size:13px;line-height:1.8}.delivery-ack :deep(.el-checkbox__input){margin-top:5px}.access-fields{font-size:12px;color:#617793}.access-fields label{display:grid;gap:7px;margin-top:15px}.access-fields p{line-height:1.8}.access-fields pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f5f8fc;padding:14px;border-radius:7px;font:12px/1.7 ui-monospace,Consolas,monospace}.mobile-delivery-error{display:grid;gap:14px;justify-items:start}@media(max-width:650px){.delivery-artifact{flex-wrap:wrap}.delivery-artifact h4{font-size:18px}.delivery-artifact i{display:block;margin:9px 0 0}.delivery-progress{grid-template-columns:1fr}.delivery-progress>div{padding:14px 0}.delivery-progress>div:not(:first-child){border-top:1px solid #e1e8f2}.delivery-progress .complete{border-left:3px solid #4b9b83;padding-left:12px}.artifact-verification dl{grid-template-columns:1fr;gap:7px}.artifact-verification dd{margin-bottom:6px}.delivery-api{align-items:flex-start;flex-direction:column}.delivery-actions .el-button{flex:1}.delivery-actions small{width:100%}}
</style>
