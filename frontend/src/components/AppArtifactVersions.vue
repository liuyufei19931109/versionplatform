<script setup lang="ts">
import { tUi } from '../i18n'

import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppArtifactTable from './AppArtifactTable.vue'
const props = defineProps<{ artifacts: Record<string, unknown>[]; loading: boolean; loaded: boolean; error: string; enabled: boolean; showDelivery: boolean }>()
const emit = defineEmits<{ retry: []; deliver: [artifact: Record<string, unknown>] }>()
const route = useRoute(), router = useRouter()
const environments = [{name:'prod',get label() { return tUi('生产') }},{name:'test',get label() { return tUi('预生产') }}] as const
function requestedEnvironment() { const value=route.query.packageEnvironment; return value==='prod'||value==='test'?value:undefined }
const active = ref<'prod'|'test'>(requestedEnvironment()||'prod')
let initialized = false, chosen = Boolean(requestedEnvironment())
const ordered = computed(() => [...props.artifacts].sort((a,b)=>(Date.parse(String(b.createdAt||''))||0)-(Date.parse(String(a.createdAt||''))||0)||Number(b.id)-Number(a.id)))
const grouped = computed(() => ({prod:ordered.value.filter(item=>item.buildEnvironmentName==='prod'),test:ordered.value.filter(item=>item.buildEnvironmentName==='test')}))
const other = computed(() => ordered.value.filter(item=>!['prod','test'].includes(String(item.buildEnvironmentName))))
const incomplete = computed(() => props.artifacts.filter(item=>item._detailsUnavailable).length)
watch(() => props.loaded, loaded => { if(!loaded||initialized)return;initialized=true;if(!chosen)active.value=grouped.value.prod.length?'prod':grouped.value.test.length?'test':'prod' }, {immediate:true})
watch(() => route.query.packageEnvironment, () => { const value=requestedEnvironment();if(value){active.value=value;chosen=true} })
watch(active, value => { if((initialized||chosen)&&route.query.packageEnvironment!==value)router.replace({query:{...route.query,packageEnvironment:value}}) })
function changePane(pane: {paneName?:string|number}) { change(pane.paneName) }
function change(value: unknown) {
  if(value!=='prod'&&value!=='test')return
  chosen=true
  if(route.query.packageEnvironment!==value)router.replace({query:{...route.query,packageEnvironment:value}})
}
</script>

<template>
  <section class="app-artifact-versions" data-testid="app-artifact-versions" :aria-busy="loading">
    <div class="app-artifact-filter">
      <el-tabs v-model="active" class="app-environment-tabs" @tab-click="changePane">
        <el-tab-pane v-for="environment in environments" :key="environment.name" :name="environment.name">
          <template #label><span class="app-environment-label">{{environment.label}}<span>{{loaded?grouped[environment.name].length:'—'}}</span></span></template>
          <div v-if="active===environment.name" class="app-environment-content">
            <div v-if="loading" class="app-packages-loading" role="status">{{ tUi('正在读取安装包版本…') }}<el-skeleton :rows="3"/></div>
            <el-alert v-else-if="error" type="error" :title="error" :closable="false" show-icon><template #default><el-button link type="primary" @click="emit('retry')">{{ tUi('重新读取安装包') }}</el-button></template></el-alert>
            <template v-else-if="loaded"><p class="app-packages-scope">{{ tUi('{0}，按打包时间倒序。', environment.name==='prod'?tUi('正式发布使用的生产安装包'):tUi('用于上线前验证的预生产安装包')) }}</p><AppArtifactTable :rows="grouped[environment.name]" :enabled="enabled" :show-delivery="showDelivery" :empty-text="tUi('暂无')+environment.label+tUi('安装包')" @deliver="emit('deliver',$event)"/></template>
          </div>
        </el-tab-pane>
      </el-tabs>
      <el-button class="app-artifacts-refresh" size="small" :loading="loading" :disabled="loading" @click="emit('retry')">{{ tUi('刷新列表') }}</el-button>
    </div>
    <div v-if="!loading&&loaded&&incomplete" class="app-packages-warning" role="status">{{ tUi('{0} 条安装包详情暂未读取，记录仍保留在列表中。', incomplete) }}<el-button link type="primary" @click="emit('retry')">{{ tUi('重试读取') }}</el-button></div>
    <el-collapse v-if="!loading&&loaded&&other.length" class="app-other-packages"><el-collapse-item :title="tUi('其他及历史记录（')+other.length+'）'" name="other"><p class="app-packages-scope">{{ tUi('开发包和未记录环境的历史安装包保留在此处；未记录构建环境的包需重新构建后交付。') }}</p><AppArtifactTable :rows="other" :enabled="enabled" :show-delivery="showDelivery" show-environment :empty-text="tUi('暂无其他安装包')" @deliver="emit('deliver',$event)"/></el-collapse-item></el-collapse>
  </section>
</template>

<style scoped>
.app-artifact-versions{min-width:0}.app-artifact-filter{position:relative}.app-environment-tabs :deep(.el-tabs__header){padding-right:90px;margin-bottom:16px}.app-environment-tabs :deep(.el-tabs__item){height:42px}.app-environment-label{display:flex;align-items:center;gap:8px;font-size:14px;font-weight:600}.app-environment-label>span{min-width:22px;padding:1px 6px;border-radius:5px;background:#f0f3f8;color:#64748b;line-height:18px;text-align:center;font-size:11px;font-weight:500}.app-environment-tabs :deep(.is-active .app-environment-label>span){background:#edf2ff;color:#416bdc}.app-artifacts-refresh{position:absolute;right:0;top:5px}.app-packages-scope{margin:0 0 13px;color:#64748b;font-size:12px;line-height:1.7}.app-packages-loading{min-height:190px;color:#64748b;font-size:13px;line-height:1.8;padding:10px 0}.app-packages-loading :deep(.el-skeleton){margin-top:19px}.app-other-packages{margin-top:18px}.app-other-packages :deep(.el-collapse-item__header){font-size:12px;color:#64748b}.app-packages-warning{display:flex;align-items:center;flex-wrap:wrap;gap:7px;margin-top:12px;color:#946c36;font-size:12px;line-height:1.7}@media(max-width:640px){.app-environment-tabs :deep(.el-tabs__header){padding-right:80px}.app-environment-tabs :deep(.el-tabs__item){padding-left:12px;padding-right:12px}.app-environment-label{font-size:13px;gap:6px}}
</style>
