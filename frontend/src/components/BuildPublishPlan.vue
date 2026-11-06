<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { errorText, http, rows } from '../api'
import { environmentLabel } from '../ui'
import { serverConnectionLabel } from '../serverConnection'
import type { DeploymentServer, ServiceEnvironment } from '../types'
type Selection = { mode: 'BUILD_ONLY' | 'PUBLISH'; environment: string; serverIds: number[] }
const props = defineProps<{ modelValue: Selection; serviceId: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: Selection]; valid: [value: boolean] }>()
const loading=ref(false),error=ref(''),environments=ref<ServiceEnvironment[]>([]),servers=ref<DeploymentServer[]>([])
let generation=0,disposed=false,controller:AbortController|undefined
const mode=computed({get:()=>props.modelValue.mode,set:(value:Selection['mode'])=>emit('update:modelValue',{mode:value,environment:'',serverIds:[]})})
const environment=computed({get:()=>props.modelValue.environment,set:(value:string)=>emit('update:modelValue',{...props.modelValue,environment:value,serverIds:[]})})
const serverIds=computed({get:()=>props.modelValue.serverIds,set:(value:number[])=>emit('update:modelValue',{...props.modelValue,serverIds:value})})
const selectedEnvironment=computed(()=>environments.value.find(item=>item.environmentName===environment.value))
const availableServers=computed(()=>servers.value.filter(server=>server.enabled&&selectedEnvironment.value?.serverIds?.some(id=>String(id)===String(server.id))))
const valid=computed(()=>mode.value==='BUILD_ONLY'||!loading.value&&!error.value&&selectedEnvironment.value?.enabled===true&&serverIds.value.length>0&&serverIds.value.every(id=>availableServers.value.some(server=>Number(server.id)===id)))
watch(valid,value=>emit('valid',Boolean(value)),{immediate:true,flush:'sync'})
async function load(){
 const request=++generation;controller?.abort();controller=undefined;loading.value=false;error.value='';environments.value=[];servers.value=[]
 if(mode.value!=='PUBLISH'||!props.serviceId)return
 const pending=new AbortController();controller=pending;loading.value=true
 try{const [envResult,serverResult]=await Promise.all([http.get(`/services/${props.serviceId}/environments`,{signal:pending.signal}),http.get('/servers',{signal:pending.signal})]);if(disposed||generation!==request)return;environments.value=rows<ServiceEnvironment>(envResult.data);servers.value=rows<DeploymentServer>(serverResult.data)}
 catch(reason){if(!disposed&&generation===request)error.value=errorText(reason,'暂时无法读取发布目标')}
 finally{if(!disposed&&generation===request)loading.value=false}
}
watch(()=>[props.serviceId,props.modelValue.mode],()=>{void load()},{immediate:true})
onBeforeUnmount(()=>{disposed=true;generation++;controller?.abort()})
</script>

<template>
 <section class="build-publish-plan" aria-label="打包后操作">
  <div class="plan-heading"><b>打包后操作</b><span>提前选择环境和服务器</span></div>
  <el-radio-group v-model="mode" :disabled="disabled" aria-label="打包后操作"><el-radio-button value="BUILD_ONLY">仅打包</el-radio-button><el-radio-button value="PUBLISH">打包成功后发布</el-radio-button></el-radio-group>
  <p v-if="mode==='BUILD_ONLY'">完成打包后保留制品，需要时可从任务详情手动发布。</p>
  <template v-else>
   <p>新打包的制品需先在预生产发布成功，才能发布到生产，因此这里只能预选开发或预生产。</p>
   <p>开发和预生产：打包成功后自动将所选目标加入发布队列，切换或关闭页面也会继续。打包失败则停止自动发布。</p>
   <div v-loading="loading" class="plan-targets">
    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon/><el-button v-if="error" link type="primary" :disabled="disabled" @click="load">重新读取发布目标</el-button>
    <p v-if="!loading&&!error&&!environments.some(env=>env.enabled)">该服务暂无可发布环境，可先仅打包，或到服务配置完善环境。</p><el-form-item label="预选发布环境" required><el-radio-group v-model="environment" :disabled="disabled||loading||!!error" aria-label="预选发布环境"><el-radio-button v-for="name in ['dev','test','prod']" :key="name" :value="name" :disabled="name==='prod'||!environments.some(env=>env.environmentName===name&&env.enabled)">{{environmentLabel(name)}}</el-radio-button></el-radio-group></el-form-item>
    <template v-if="environment"><div class="plan-server-heading"><span>发布服务器 · 已选 {{serverIds.length}} 台</span><el-button link type="primary" :disabled="disabled||!availableServers.length" @click="serverIds=availableServers.map(server=>Number(server.id))">全选</el-button></div><el-checkbox-group v-model="serverIds" :disabled="disabled" aria-label="预选发布服务器" class="plan-server-list"><el-checkbox v-for="server in availableServers" :key="server.id" :value="Number(server.id)" border><span><b>{{server.name}}</b><small>{{serverConnectionLabel(server,{includeUser:false})}}</small></span></el-checkbox></el-checkbox-group><p v-if="!availableServers.length">该环境暂无可用服务器，请先完善服务环境配置。</p><p v-else-if="!serverIds.length">至少选择一台发布服务器。</p></template>
   </div>
  </template>
 </section>
</template>

<style scoped>
.build-publish-plan{padding:18px;margin:22px 0;border:1px solid #dce5f1;border-radius:9px;background:#fafcff}.plan-heading{display:flex;gap:12px;flex-wrap:wrap;align-items:baseline;margin-bottom:16px;color:#354b68;font-size:14px}.plan-heading>span,.build-publish-plan p{font-size:12px;color:#74849a;line-height:1.8}.build-publish-plan p{margin:12px 0 0}.plan-targets{margin-top:20px;min-height:60px}.plan-targets .el-alert{margin-bottom:10px}.plan-server-heading{display:flex;justify-content:space-between;align-items:center;color:#52647c;font-size:12px;margin-bottom:10px}.plan-server-list{display:grid;gap:9px}.plan-server-list .el-checkbox{width:100%;height:auto;margin:0;padding:12px;background:#fff}.plan-server-list :deep(.el-checkbox__label){white-space:normal;min-width:0}.plan-server-list b,.plan-server-list small{display:block;line-height:1.6;overflow-wrap:anywhere}.plan-server-list b{font-size:12px;color:#354b68}.plan-server-list small{font-size:11px;color:#74849a}
</style>
