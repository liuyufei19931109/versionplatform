<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Refresh, Search, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorText, http, normalizeService, rows, serviceRequest } from '../api'
import MobileBuildSettings from '../components/MobileBuildSettings.vue'
import BuildEngineSelect from '../components/BuildEngineSelect.vue'
import { buildEngineLabel } from '../buildEngine'
import { isMobileDraft, mobileBuildDefaults, mobileBuildError } from '../mobile'
import type { BuildEngine, Service, ServiceType } from '../types'
import ServiceIdentity from '../components/ServiceIdentity.vue'
import ServiceIconPicker from '../components/ServiceIconPicker.vue'
import { environmentLabel, statusLabel, formatTime, windowsCommand } from '../ui'

const mobileSettings=ref<{commitParameters:()=>boolean}>()
const importBuildEngine=ref<BuildEngine>('GITHUB')
const router=useRouter(), loading=ref(false), error=ref(''), list=ref<Service[]>([]), keyword=ref(''), projectFilter=ref('ALL'), dialog=ref(false), saving=ref(false),importDialog=ref(false),importing=ref(false),repoText=ref(''),importServiceType=ref<ServiceType>('BACKEND')
const form=reactive<Service>({ name:'',code:'',serviceType:'BACKEND',buildEngine:'GITHUB',description:'',gitUrl:'',defaultBranch:'main',buildTool:'Maven',status:true })
const compactQuery=window.matchMedia('(max-width: 1200px)'),compactList=ref(compactQuery.matches)
function updateCompactList(){compactList.value=compactQuery.matches}
const serviceFilters=computed(()=>[{value:'ALL',label:'全部服务',count:list.value.length},{value:'BACKEND',label:'后端',count:list.value.filter(s=>s.serviceType==='BACKEND').length},{value:'FRONTEND',label:'前端',count:list.value.filter(s=>s.serviceType==='FRONTEND').length}])
const visibleServices=computed(()=>list.value.filter(s=>(projectFilter.value==='ALL'||s.serviceType===projectFilter.value)&&(!keyword.value.trim()||`${s.name} ${s.code}`.toLowerCase().includes(keyword.value.trim().toLowerCase()))))
const enabledCount=computed(()=>list.value.filter(s=>s.status!==false&&s.status!=='DISABLED').length)
function repositoryLabel(value?:string){return value?.replace(/^git@github\.com:/,'github.com/').replace(/^https?:\/\//,'').replace(/\.git$/,'')||'未配置仓库'}
function serviceStatus(service:Service){return isMobileDraft(service)?'待配置':service.status===false||service.status==='DISABLED'?'已停用':'已启用'}
async function load(){loading.value=true;error.value='';try{list.value=rows<Record<string,unknown>>((await http.get('/services')).data).map(normalizeService)}catch(e){error.value=errorText(e)}finally{loading.value=false}}
function typeDefaults(type:ServiceType){if(type==='FLUTTER_ANDROID')return{serviceType:type,buildTool:'Flutter',buildCommand:'',jarPath:'',mobileBuild:mobileBuildDefaults()};return type==='FRONTEND'?{serviceType:type,imageBuildMode:'STATIC_NGINX' as const,buildTool:'npm',buildCommand:'npm ci && npm run build',jarPath:'dist',dockerfilePath:'Dockerfile'}:{serviceType:type,imageBuildMode:'REPOSITORY_DOCKERFILE' as const,buildTool:'Maven',buildCommand:'mvn clean package -DskipTests',jarPath:'target/*.jar',dockerfilePath:'Dockerfile'}}
function projectTypeChanged(value:string){Object.assign(form,typeDefaults(value as ServiceType))}
function frontendCommand(tool:string){return tool==='pnpm'?'pnpm install --frozen-lockfile && pnpm run build':tool==='yarn'?'yarn install --frozen-lockfile && yarn build':'npm ci && npm run build'}
function buildToolChanged(value:string){if(form.serviceType==='FLUTTER_ANDROID')return;if(form.serviceType==='FRONTEND')form.buildCommand=frontendCommand(value);else form.buildCommand=value==='Gradle'?'./gradlew clean build -x test':'mvn clean package -DskipTests'}
function open(row?:Service){Object.assign(form,{id:undefined,name:'',code:'',displayIcon:'',description:'',gitUrl:'',defaultBranch:'main',buildEngine:'GITHUB',configJson:undefined,mobileBuild:undefined,status:true},typeDefaults('BACKEND'),row||{});dialog.value=true}
async function save(){if(saving.value)return;if(!form.name||!form.code)return ElMessage.warning('请填写服务名称和标识');if(form.serviceType==='FLUTTER_ANDROID'){if(mobileSettings.value&&!mobileSettings.value.commitParameters())return;const reason=mobileBuildError(form.mobileBuild);if(reason)return ElMessage.warning(reason)}saving.value=true;try{form.id?await http.put(`/services/${form.id}`,serviceRequest(form)):await http.post('/services',serviceRequest(form));ElMessage.success('保存成功');dialog.value=false;load()}catch(e){ElMessage.error(errorText(e))}finally{saving.value=false}}
async function remove(row:Service){try{await ElMessageBox.confirm(`确认删除服务“${row.name}”？`,'删除确认',{type:'warning'});await http.delete(`/services/${row.id}`);ElMessage.success('已删除');load()}catch(e){if(e!=='cancel')ElMessage.error(errorText(e))}}
function repositoryName(url:string){const clean=url.trim().replace(/[\\/]$/,'').replace(/\.git$/i,'');return clean.split(/[\\/:]/).filter(Boolean).pop()||''}
async function importRepositories(){if(importing.value)return;const urls=[...new Set(repoText.value.split(/\r?\n/).map(v=>v.trim()).filter(Boolean))];if(!urls.length)return ElMessage.warning('请至少填写一个 Git 仓库地址');importing.value=true;let success=0,skipped=0,failed=0;const existing=new Set(list.value.map(item=>String(item.code||'').toLowerCase()));for(const gitUrl of urls){const code=repositoryName(gitUrl);if(!code||existing.has(code.toLowerCase())){skipped++;continue}try{await http.post('/services',serviceRequest({name:code,code,gitUrl,defaultBranch:'main',buildEngine:importBuildEngine.value,...typeDefaults(importServiceType.value),status:true}));existing.add(code.toLowerCase());success++}catch{failed++}}importing.value=false;if(success){ElMessage.success(`已导入 ${success} 个服务`);repoText.value='';importDialog.value=false;await load()}if(skipped||failed)ElMessage.warning(`跳过 ${skipped} 个，失败 ${failed} 个`)}
function openDetail(row: Service){router.push(`/services/${row.id}`)}
onMounted(()=>{compactQuery.addEventListener('change',updateCompactList);void load()})
onBeforeUnmount(()=>compactQuery.removeEventListener('change',updateCompactList))
</script>

<template>
  <div class="page-head"><div><h2>服务管理</h2><p>管理代码仓库、构建方式与部署环境。</p></div><div class="actions"><el-button :icon="UploadFilled" @click="importDialog=true">批量导入仓库</el-button><el-button type="primary" :icon="Plus" @click="open()">新建服务</el-button></div></div>
  <section class="panel services-panel">
    <div class="service-catalog-head"><div class="service-type-tabs" role="group" aria-label="筛选服务类型"><button v-for="item in serviceFilters" :key="item.value" type="button" :class="{active:projectFilter===item.value}" :aria-pressed="projectFilter===item.value" @click="projectFilter=item.value">{{item.label}}<span>{{item.count}}</span></button></div><span class="service-catalog-count">{{enabledCount}} 个已启用</span></div>
    <div class="panel-title service-toolbar"><div class="actions"><el-input v-model="keyword" placeholder="搜索服务名称或标识" aria-label="搜索服务" clearable :prefix-icon="Search"/><span class="service-result-count">{{visibleServices.length}} 个服务</span></div><el-button :icon="Refresh" text :loading="loading" @click="load">刷新</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="service-load-error"><template #default>读取失败，请重试。已有内容保留，未修改服务配置。</template></el-alert>
    <el-table class="clickable-table service-desktop-table" v-if="visibleServices.length&&!compactList" v-loading="loading" :data="visibleServices" @row-click="openDetail">
      <el-table-column label="服务" min-width="235"><template #default="s"><ServiceIdentity :service="s.row"/></template></el-table-column>
      <el-table-column label="代码来源" min-width="230"><template #default="s"><div class="service-repository" :title="s.row.gitUrl">{{repositoryLabel(s.row.gitUrl)}}</div><div class="service-source-branch"><span>默认分支</span><b :title="s.row.defaultBranch">{{s.row.defaultBranch||'main'}}</b></div></template></el-table-column>
      <el-table-column label="构建方式" width="155"><template #default="s"><el-tag size="small" effect="plain" :type="s.row.buildEngine==='LOCAL'?'info':'primary'">{{buildEngineLabel(s.row.buildEngine)}}</el-tag><small v-if="s.row.buildTool" class="service-build-tool">{{s.row.buildTool}}</small></template></el-table-column>
      <el-table-column label="状态" width="105"><template #default="s"><span class="service-state" :class="{enabled:serviceStatus(s.row)==='已启用'}"><i></i>{{serviceStatus(s.row)}}</span></template></el-table-column>
      <el-table-column label="操作" width="125" @click.stop><template #default="s"><div class="service-row-actions"><el-button link type="primary" @click.stop="openDetail(s.row)">配置</el-button><el-button link type="danger" @click.stop="remove(s.row)">删除</el-button></div></template></el-table-column>
    </el-table>
    <div v-if="visibleServices.length&&compactList" class="service-mobile-list"><article v-for="service in visibleServices" :key="service.id"><ServiceIdentity :service="service"/><p class="service-repository" :title="service.gitUrl">{{repositoryLabel(service.gitUrl)}}</p><div class="service-mobile-facts"><span>{{buildEngineLabel(service.buildEngine)}}</span><span class="service-state" :class="{enabled:serviceStatus(service)==='已启用'}"><i></i>{{serviceStatus(service)}}</span></div><div class="service-mobile-actions"><span class="service-source-branch"><span>分支</span><b>{{service.defaultBranch||'main'}}</b></span><el-button @click="openDetail(service)">配置</el-button><el-button link type="danger" @click="remove(service)">删除</el-button></div></article></div>
    <div v-if="loading&&!list.length" class="service-initial-loading" role="status"><el-skeleton :rows="4" animated/><p>正在读取服务与构建配置…</p></div>
    <div v-else-if="!visibleServices.length&&!error" class="empty"><div><strong>{{list.length ? '没有匹配的服务' : '还没有服务'}}</strong><p>{{list.length ? '调整搜索内容或服务类型。' : '先创建服务，再配置代码仓库与部署环境。'}}</p><el-button v-if="!list.length" type="primary" :icon="Plus" @click="open()">新建服务</el-button></div></div>
  </section>
  <el-dialog v-model="dialog" :title="form.id?'编辑服务':'新建服务'" width="640px">
    <el-form label-position="top">
      <el-form-item label="项目类型" required><el-radio-group v-model="form.serviceType" :disabled="!!form.id" @change="projectTypeChanged"><el-radio-button value="BACKEND">后端项目</el-radio-button><el-radio-button value="FRONTEND">前端项目</el-radio-button></el-radio-group></el-form-item>
      <div class="service-form-pair"><el-form-item label="服务名称" required><el-input v-model="form.name" placeholder="例如：示例服务"/></el-form-item><el-form-item label="服务标识" required><el-input v-model="form.code" placeholder="sample-service" :disabled="!!form.id"/></el-form-item></div>
      <el-form-item label="服务图标"><ServiceIconPicker v-model="form.displayIcon" :service-type="form.serviceType" :code="form.code"/></el-form-item>
      <el-form-item label="Git 仓库地址"><el-input v-model="form.gitUrl" placeholder="git@github.com:team/project.git"/></el-form-item>
      <BuildEngineSelect v-model="form.buildEngine" :disabled="saving"/>
      <div class="service-form-pair"><el-form-item label="默认分支"><el-input v-model="form.defaultBranch"/></el-form-item><el-form-item label="构建工具"><el-select v-model="form.buildTool" style="width:100%" @change="buildToolChanged"><template v-if="form.serviceType==='FLUTTER_ANDROID'"><el-option label="Flutter" value="Flutter"/></template><template v-else-if="form.serviceType==='FRONTEND'"><el-option label="npm" value="npm"/><el-option label="pnpm" value="pnpm"/><el-option label="yarn" value="yarn"/></template><template v-else><el-option label="Maven" value="Maven"/><el-option label="Gradle" value="Gradle"/></template></el-select></el-form-item></div>
      <MobileBuildSettings v-if="form.serviceType==='FLUTTER_ANDROID' && form.mobileBuild" ref="mobileSettings" :model="form.mobileBuild" compact/><el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="3"/></el-form-item><el-form-item><el-switch v-model="form.status" active-text="启用服务"/></el-form-item>
    </el-form>
    <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
  </el-dialog>
  <el-dialog v-model="importDialog" title="批量导入 Git 仓库" width="640px">
    <p class="muted" style="margin-top:0">每行填写一个 SSH 仓库地址，平台会为每个仓库创建独立服务，并在打包时实时读取该仓库的分支。</p>
    <el-form-item label="这些仓库的项目类型"><el-radio-group v-model="importServiceType"><el-radio-button value="BACKEND">后端项目</el-radio-button><el-radio-button value="FRONTEND">前端项目</el-radio-button></el-radio-group></el-form-item>
    <BuildEngineSelect v-model="importBuildEngine" :disabled="importing"/>
    <el-input v-model="repoText" type="textarea" :rows="9" placeholder="git@git.example.com:team/api.git&#10;git@git.example.com:team/web.git"/>
    <template #footer><el-button @click="importDialog=false">取消</el-button><el-button type="primary" :loading="importing" @click="importRepositories">开始导入</el-button></template>
  </el-dialog>
</template>

<style scoped>
.services-panel{padding-top:0}.service-catalog-head{display:flex;align-items:center;justify-content:space-between;gap:12px;border-bottom:1px solid #dce3ec;margin-bottom:20px}.service-type-tabs{display:flex;gap:24px;min-width:0}.service-type-tabs button{display:flex;align-items:center;gap:8px;padding:18px 0 16px;border:0;border-bottom:2px solid transparent;background:transparent;color:#607087;font:inherit;font-size:13px;white-space:nowrap;cursor:pointer}.service-type-tabs button.active{border-bottom-color:#315fce;color:#244eaa;font-weight:650}.service-type-tabs button>span{padding:1px 6px;border-radius:5px;background:#edf1f6;font-size:11px;font-variant-numeric:tabular-nums}.service-type-tabs button.active>span{background:#eaf0fe;color:#315fce}.service-catalog-count,.service-result-count{font-size:12px;color:#607087;white-space:nowrap}.service-toolbar{margin-bottom:18px}.service-toolbar .el-input{width:280px}.service-load-error{margin-bottom:16px}.service-row-actions{display:flex;gap:14px}.service-build-tool{display:block;margin-top:5px;color:#607087;font-size:12px}.service-repository{max-width:100%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:#4a607a;font-size:12px}.service-source-branch{display:flex;align-items:center;gap:8px;margin-top:5px;font-size:12px;min-width:0;color:#607087}.service-source-branch b{font-weight:550;color:#203149;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.service-source-branch>span{flex-shrink:0;font-size:11px}.service-state{display:inline-flex;align-items:center;gap:7px;color:#607087;font-size:12px;white-space:nowrap}.service-state i{width:6px;height:6px;border-radius:50%;background:#97a3b1}.service-state.enabled{color:#168269}.service-state.enabled i{background:#168269}.service-form-pair{display:grid;grid-template-columns:1fr 1fr;gap:16px}.service-mobile-list{display:none}.service-initial-loading{padding:22px 4px 12px}.service-initial-loading p{font-size:13px;color:#607087;margin-top:20px}
@media(max-width:1200px){.service-desktop-table{display:none}.service-mobile-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 24px}.service-mobile-list article{padding:19px 0;border-bottom:1px solid #e6ebf1;min-width:0}.service-mobile-facts{display:flex;align-items:center;gap:18px;font-size:12px;color:#315fce}.service-mobile-actions{display:flex;align-items:center;gap:13px;margin-top:16px}.service-mobile-actions .service-source-branch{flex:1;margin:0;min-width:0}.service-mobile-actions .el-button{flex-shrink:0}.service-mobile-list .service-repository{margin:12px 0}}
@media(max-width:650px){.service-catalog-count{display:none}.service-type-tabs{width:100%;gap:22px}.service-type-tabs button{font-size:12px}.service-toolbar{flex-wrap:wrap;gap:10px}.service-toolbar .actions{width:100%;flex-wrap:wrap;gap:10px}.service-toolbar .el-input{width:auto;flex:1;min-width:160px}.service-mobile-list{grid-template-columns:1fr}.service-form-pair{grid-template-columns:1fr;gap:0}.service-result-count{font-size:11px}}
</style>
