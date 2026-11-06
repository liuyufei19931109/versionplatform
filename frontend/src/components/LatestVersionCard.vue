<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Plus, Refresh } from '@element-plus/icons-vue'
import { authUser } from '../auth'
import { errorText, http, payload } from '../api'
import { canAddVersionServices, shanghaiToday, versionBusy, versionSummary } from '../versions'
import type { ReleaseVersion } from '../versions'
import VersionServiceList from './VersionServiceList.vue'

const router = useRouter(), versions = ref<ReleaseVersion[]>([]), loading = ref(false), error = ref('')
const completionStateError = ref('')
const today = ref(shanghaiToday()), chosenId = ref<number | null>(null)
// Only the homepage selection changes. Git event matching and release inputs keep their existing rules.
const unfinishedVersions = computed(() => versions.value.filter(item => item.lifecycleState !== 'COMPLETED'))
const defaultVersion = computed(() => unfinishedVersions.value.find(item => item.versionDate <= today.value) || unfinishedVersions.value[unfinishedVersions.value.length - 1] || null)
const allCompleted = computed(() => versions.value.length > 0 && versions.value.every(item => item.lifecycleState === 'COMPLETED'))
const version = computed(() => versions.value.find(item => item.id === chosenId.value) || defaultVersion.value)
const selectedId = computed({
  get: () => version.value?.id,
  set: (id: number | undefined) => { chosenId.value = id === defaultVersion.value?.id ? null : id ?? null }
})
const future = computed(() => Boolean(version.value && version.value.versionDate > today.value))
const selectionHint = computed(() => {
  if (version.value?.id !== defaultVersion.value?.id) return '已切换查看版本，模块与打包入口随所选版本切换。'
  return future.value ? '当前轮次已完成，或尚无已到期的未完成版本，展示最近的未来版本。' : '默认展示今天或之前最近的未完成版本，可切换查看其他版本。'
})
let timer: number | undefined, disposed = false
async function refresh() {
  if (disposed || loading.value) return
  loading.value = true
  try {
    const [versionResult, stateResult] = await Promise.allSettled([http.get('/versions'), http.get('/versions/completion-states')])
    if (versionResult.status === 'rejected') throw versionResult.reason
    const value = payload<ReleaseVersion[]>(versionResult.value, [])
    const states = stateResult.status === 'fulfilled' ? new Map(payload<{versionId:number;state:'OPEN'|'COMPLETED'}[]>(stateResult.value, []).map(item => [item.versionId,item.state])) : null
    if (disposed) return
    const previousStates = new Map(versions.value.map(item => [item.id,item.lifecycleState]))
    const chosenWasCompleted = chosenId.value ? previousStates.get(chosenId.value) === 'COMPLETED' : false
    versions.value = (Array.isArray(value) ? value : []).filter(item => item?.id && typeof item.versionDate === 'string' && Array.isArray(item.items)).map(item => ({...item,lifecycleState:states?states.get(item.id)||'OPEN' as const:previousStates.get(item.id)})).sort((a, b) => b.versionDate.localeCompare(a.versionDate))
    completionStateError.value = states ? '' : previousStates.size ? '收尾状态暂时无法读取，沿用上次已知状态；新版本状态尚未确认。' : '收尾状态暂时无法读取，状态未知，先按日期展示版本供查看。'
    today.value = shanghaiToday()
    if (chosenId.value && (!versions.value.some(item => item.id === chosenId.value) || !chosenWasCompleted && versions.value.find(item => item.id === chosenId.value)?.lifecycleState === 'COMPLETED')) chosenId.value = null
    error.value = ''
  }
  catch (e) { if (!disposed) error.value = errorText(e, '版本概览暂时无法读取') }
  finally { loading.value = false; if (timer) window.clearTimeout(timer); if (!disposed) timer = window.setTimeout(() => refresh(), versionBusy(version.value) ? 3000 : 30000) }
}
function open(add = false) { if (version.value) router.push({ path: `/versions/${version.value.id}`, query: add ? { add: '1' } : {} }) }
onMounted(() => refresh())
onBeforeUnmount(() => { disposed = true; if (timer) window.clearTimeout(timer) })
defineExpose({ refresh })
</script>

<template>
  <section class="panel latest-version-card" aria-label="版本概览">
    <div class="latest-version-heading">
      <div><h3>版本概览</h3><p>查看所选版本的模块与开发分支</p></div>
      <div v-if="versions.length" class="version-picker">
        <label for="dashboard-version">查看版本</label>
        <el-select id="dashboard-version" v-model="selectedId" aria-label="切换版本" placeholder="切换查看历史版本" :loading="loading" popper-class="dashboard-version-options">
          <el-option v-for="item in versions" :key="item.id" :value="item.id" :label="item.versionDate">
            <span>{{item.versionDate}}</span><small>{{item.lifecycleState==='COMPLETED'?'已完成':item.id===defaultVersion?.id?'默认':item.versionDate>today?'未来版本':'历史版本'}}</small>
          </el-option>
        </el-select>
      </div>
      <div class="actions"><el-button v-if="version&&authUser?.admin&&canAddVersionServices(version)" :icon="Plus" :disabled="versionBusy(version)" @click="open(true)">补充服务</el-button><el-button v-if="version" link type="primary" @click="open()">查看版本<el-icon><ArrowRight/></el-icon></el-button><el-button v-else-if="authUser?.admin&&!error" type="primary" :icon="Plus" @click="router.push({path:'/versions',query:{create:'1'}})">创建版本</el-button></div>
    </div>
    <div v-if="error" class="latest-version-error" role="alert"><span>{{error}}{{version?'；下方为上次读取的结果。':''}}</span><el-button link type="primary" :icon="Refresh" :loading="loading" @click="refresh()">重新读取</el-button></div>
    <p v-if="completionStateError&&!error" class="completion-state-note" role="status">{{completionStateError}}</p>
    <template v-if="version">
      <div class="version-selection-note"><span>{{selectionHint}}</span><el-button v-if="chosenId&&version.id!==defaultVersion?.id" link type="primary" @click="chosenId=null">回到当前版本</el-button></div>
      <div class="latest-version-meta"><time>{{version.versionDate}}</time><strong>{{version.branch}}</strong><el-tag v-if="version.lifecycleState==='COMPLETED'" size="small" type="success" effect="plain">已完成</el-tag><el-tag v-if="future" class="version-future-tag" size="small" type="info" effect="plain">即将到来</el-tag><span class="version-module-summary">{{versionSummary(version)}}</span></div>
      <VersionServiceList :key="version.id" :items="version.items" :version-id="version.id" compact/>
    </template>
    <div v-else-if="!error" class="latest-version-empty"><b>{{loading?'正在读取版本…':allCompleted?'当前版本已全部完成':'还没有创建版本'}}</b><p>{{allCompleted?(authUser?.admin?'可以创建下一版本，也可从上方切换查看已完成的历史版本。':'等待管理员创建下一版本，也可从上方切换查看已完成的历史版本。'):(authUser?.admin?'创建版本，统一为参与的服务建立 release 分支。':'admin 创建版本后，会在这里显示你有权限查看的服务和分支。')}}</p></div>
  </section>
</template>

<style scoped>
.latest-version-card{margin-bottom:22px;padding-bottom:10px}
.completion-state-note{font-size:11px;line-height:1.8;color:#9b7e55;margin:0 0 15px}
.latest-version-heading{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:16px;margin-bottom:20px}
.latest-version-heading h3{margin:0;color:#354b68;font-size:15px;font-weight:600}
.latest-version-heading p{margin:6px 0 0;color:#74849a;font-size:12px}
.latest-version-heading>.actions{margin-left:auto}.latest-version-heading .el-button{font-size:12px}
.version-picker{display:flex;align-items:center;gap:9px;margin-left:auto}.version-picker label{font-size:12px;color:#74849a;white-space:nowrap}.version-picker .el-select{width:180px}
.version-selection-note{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:-3px 0 17px;color:#74849a;font-size:12px;line-height:1.7}.version-selection-note .el-button{font-size:12px}
.latest-version-meta{display:flex;gap:17px;align-items:center;flex-wrap:wrap;padding-bottom:20px}
.latest-version-meta time{font-size:24px;font-weight:650;color:#233149;letter-spacing:-.4px;font-variant-numeric:tabular-nums}
.latest-version-meta strong{font-size:14px;font-weight:600;color:#416bdc;overflow-wrap:anywhere}
.version-module-summary{margin-left:auto;color:#74849a;font-size:12px}.version-future-tag{font-size:11px}
.latest-version-error{display:flex;justify-content:space-between;align-items:center;gap:15px;padding:12px 14px;margin-bottom:15px;background:#fff7f5;border:1px solid #f0ddd6;border-radius:7px;color:#986d3c;font-size:12px;line-height:1.7}
.latest-version-empty{padding:18px 0 28px;color:#52647c}.latest-version-empty>b{font-size:13px;font-weight:500}.latest-version-empty p{font-size:12px;line-height:1.8;color:#74849a;margin:8px 0 0}
@media(max-width:650px){
  .latest-version-heading{align-items:flex-start;gap:14px}.latest-version-heading>.actions{margin-left:0}
  .version-picker{order:3;width:100%;margin-left:0}.version-picker .el-select{flex:1;width:auto;min-width:0}
  .version-selection-note{margin-top:0}.version-selection-note .el-button{height:auto}
  .latest-version-meta{gap:10px}.latest-version-meta time{font-size:22px;flex-basis:100%}.version-module-summary{margin-left:0;flex-basis:100%}
  .latest-version-error{align-items:flex-start;flex-direction:column}
}
</style>

<style>
.dashboard-version-options .el-select-dropdown__item{display:flex;align-items:center;justify-content:space-between;gap:20px}.dashboard-version-options .el-select-dropdown__item small{color:#74849a;font-size:11px;font-weight:400}
</style>
