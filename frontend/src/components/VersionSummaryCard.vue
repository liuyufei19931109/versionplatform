<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { DocumentChecked, ArrowRight, Refresh } from '@element-plus/icons-vue'
import { errorText, http, payload } from '../api'
import { environmentLabel, formatTime } from '../ui'
import type { PublicVersionSummary } from '../versionCompletion'

const props = withDefaults(defineProps<{ placement?: 'dashboard' | 'header' }>(), { placement: 'dashboard' })
const summary = ref<PublicVersionSummary | null>(null), detail = ref<PublicVersionSummary | null>(null)
const loading = ref(false), error = ref(''), opened = ref(false), detailLoading = ref(false), detailError = ref('')
let request = 0, detailRequest = 0, disposed = false, timer: number | undefined
async function refresh() {
  const current = ++request
  loading.value = true
  try {
    const result = payload<PublicVersionSummary | null>(await http.get('/version-summaries/latest'), null)
    if (disposed || current !== request) return
    summary.value = result?.id && result.state === 'COMPLETED' ? result : null; error.value = ''
  } catch (reason) { if (!disposed && current === request) error.value = errorText(reason, '版本小结暂时无法读取') }
  finally { if (!disposed && current === request) { loading.value = false; if (timer) window.clearTimeout(timer); if (props.placement === 'dashboard') timer = window.setTimeout(refresh, 30000) } }
}
async function openSummary() {
  opened.value = true; detail.value = null; detailError.value = ''; detailLoading.value = true
  const current = ++detailRequest
  try {
    const latest = payload<PublicVersionSummary | null>(await http.get('/version-summaries/latest'), null)
    if (disposed || current !== detailRequest || !opened.value) return
    if (!latest?.versionId) { summary.value = null; detail.value = null; return }
    const result = payload<PublicVersionSummary | null>(await http.get(`/version-summaries/${latest.versionId}`), null)
    if (disposed || current !== detailRequest || !opened.value) return
    detail.value = result; summary.value = result; error.value = ''
  } catch (reason) { if (!disposed && current === detailRequest) detailError.value = errorText(reason, '版本小结暂时无法读取') }
  finally { if (!disposed && current === detailRequest) detailLoading.value = false }
}
function closeSummary(value: boolean) { opened.value = value; if (!value) { detailRequest++; detailLoading.value = false } }
onMounted(() => { if (props.placement === 'dashboard') refresh() })
onBeforeUnmount(() => { disposed = true; request++; detailRequest++; if (timer) window.clearTimeout(timer) })
</script>

<template>
  <el-button v-if="placement==='header'" class="summary-header-button" text :icon="DocumentChecked" aria-label="版本小结" title="版本小结" @click="openSummary"><span class="summary-header-label">版本小结</span></el-button>
  <section v-else class="panel completed-version-card" aria-label="最近完成版本">
    <div class="completed-heading"><div><span class="completed-symbol"><el-icon><DocumentChecked/></el-icon></span><div><h3>最近完成版本</h3><p>版本收尾与交付小结 · 全员可见</p></div></div><el-button v-if="summary" link type="primary" @click="openSummary">查看完整小结<el-icon><ArrowRight/></el-icon></el-button></div>
    <div v-if="error" class="summary-read-error" role="alert"><span>{{error}}</span><el-button link type="primary" :icon="Refresh" :loading="loading" @click="refresh">重试</el-button></div>
    <template v-if="summary"><div class="completed-meta"><time>{{summary.versionDate}}</time><el-tag size="small" type="success" effect="plain">已完成</el-tag><span>{{environmentLabel(summary.environment)}}环境</span></div><p class="completed-excerpt">{{summary.summary}}</p><div class="completed-footer"><div class="completed-counts"><span><b>{{summary.counts.published}}</b> 已发布</span><span><b>{{summary.counts.excluded}}</b> 本次不发布</span><span v-if="summary.counts.unconfirmed"><b>{{summary.counts.unconfirmed}}</b> 待确认</span></div><span>{{summary.completedBy}} · {{formatTime(summary.completedAt)}}</span></div></template>
    <p v-else-if="!error" class="completed-empty">{{loading?'正在读取版本小结…':'还没有完成的版本。管理员完成版本收尾后，小结会展示在这里。'}}</p>
  </section>
  <el-dialog :model-value="opened" title="版本小结" width="740px" class="version-public-summary-dialog" @update:model-value="closeSummary">
    <div v-loading="detailLoading" class="public-summary-content">
      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon/>
      <template v-else-if="detail"><div class="completed-meta"><time>{{detail.versionDate}}</time><el-tag size="small" :type="detail.state==='COMPLETED'?'success':'info'" effect="plain">{{detail.state==='COMPLETED'?'已完成':'已重新打开'}}</el-tag><span>{{environmentLabel(detail.environment)}}环境</span></div><p class="public-summary-text">{{detail.summary}}</p><div class="public-summary-counts"><div><b>{{detail.counts.published}}</b><span>已发布</span></div><div><b>{{detail.counts.excluded}}</b><span>本次不发布</span></div><div><b>{{detail.counts.unconfirmed}}</b><span>待确认</span></div></div><div class="public-summary-foot"><span>完成人 {{detail.completedBy}} · {{formatTime(detail.completedAt)}}</span><span v-if="detail.actor!==detail.completedBy||detail.createdAt!==detail.completedAt">最近更新 {{detail.actor}} · {{formatTime(detail.createdAt)}}</span><small>此处为管理员确认的小结与当时的发布情况快照。</small></div></template>
      <p v-else-if="!detailLoading" class="completed-empty">暂无已完成版本的小结。</p>
    </div>
    <template #footer><el-button v-if="detailError" :loading="detailLoading" @click="openSummary">重新读取</el-button><el-button type="primary" @click="closeSummary(false)">知道了</el-button></template>
  </el-dialog>
</template>

<style scoped>
.completed-version-card{margin-bottom:22px}.completed-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.completed-heading>div{display:flex;align-items:center;gap:12px}.completed-symbol{display:grid;place-items:center;width:38px;height:38px;border-radius:10px;background:#eaf5ef;color:#4a8b6b;font-size:21px}.completed-heading h3{margin:0;color:#354b68;font-size:15px;font-weight:600}.completed-heading p{margin:6px 0 0;color:#74849a;font-size:12px}.completed-meta{display:flex;align-items:center;flex-wrap:wrap;gap:13px;margin-top:22px}.completed-meta time{font-size:23px;letter-spacing:-.4px;font-weight:650;color:#233149;font-variant-numeric:tabular-nums}.completed-meta>span:last-child{color:#74849a;font-size:12px}.completed-excerpt{display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:3;overflow:hidden;color:#52647c;font-size:13px;line-height:1.9;white-space:pre-wrap;margin:17px 0 20px;overflow-wrap:anywhere}.completed-footer{border-top:1px solid #edf1f6;padding-top:15px;display:flex;align-items:center;justify-content:space-between;gap:14px;flex-wrap:wrap;color:#8a98ab;font-size:11px}.completed-counts{display:flex;gap:20px;flex-wrap:wrap;color:#74849a;font-size:12px}.completed-counts b{color:#4a647f;font-weight:600}.completed-empty{font-size:12px;line-height:1.9;color:#74849a;margin:21px 0 0}.summary-read-error{display:flex;align-items:center;gap:12px;margin-top:15px;color:#a46d46;font-size:12px}.public-summary-content{min-height:120px}.public-summary-content .completed-meta{margin-top:0}.public-summary-text{margin:22px 0;color:#4b5f79;font-size:13px;line-height:1.95;white-space:pre-wrap;overflow-wrap:anywhere}.public-summary-counts{display:grid;grid-template-columns:repeat(3,1fr);border:1px solid #e2eaf3;border-radius:9px;background:#f8fafc;padding:17px;gap:15px}.public-summary-counts>div{display:grid;gap:7px;text-align:center}.public-summary-counts b{font-size:22px;color:#354b68;font-weight:600}.public-summary-counts span{color:#74849a;font-size:12px}.public-summary-foot{display:grid;gap:8px;margin-top:20px;color:#74849a;font-size:11px;line-height:1.8}.public-summary-foot small{font-size:11px;color:#8a98ab}.summary-header-button{font-size:12px;color:#6481b0}
@media(max-width:650px){.completed-heading{align-items:flex-start;flex-wrap:wrap}.completed-meta time{font-size:21px}.completed-footer{align-items:flex-start;flex-direction:column}.completed-counts{gap:14px}.summary-header-button{padding:8px;width:32px;height:32px}.summary-header-label{display:none}.summary-header-button :deep(.el-icon+span){margin-left:0}.public-summary-counts{padding:13px;gap:8px}}
</style>
