<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { authUser, hasMenu } from '../auth'
import { environmentLabel, formatTime } from '../ui'
import { completionOutcomeLabel, completionOutcomeType } from '../versionCompletion'
import type { CompletionDetail, CompletionItem } from '../versionCompletion'
import ServiceIdentity from './ServiceIdentity.vue'
import CommitHash from './CommitHash.vue'

const props = defineProps<{ detail: CompletionDetail; disabled?: boolean; services?: Array<{serviceId:number;displayIcon?:string}> }>()
const emit = defineEmits<{ edit: []; reopen: [] }>()
const router = useRouter()
const current = computed(() => props.detail.current)
const history = computed(() => props.detail.history.filter(item => item.id !== current.value?.id))
function allowedPath(item: CompletionItem) {
  if (item.detailPath?.startsWith('/apps/releases/') && /^\/apps\/releases\/\d+$/.test(item.detailPath) && hasMenu('apps')) return item.detailPath
  if (item.detailPath?.startsWith('/releases/') && /^\/releases\/\d+$/.test(item.detailPath) && (hasMenu('releases') || hasMenu('release'))) return item.detailPath
  return ''
}
</script>

<template>
  <section v-if="current" class="version-completion-panel" aria-label="版本收尾记录">
    <div class="completion-panel-heading"><div><h4>{{detail.state==='COMPLETED'?'版本小结':'历史小结'}}</h4><el-tag size="small" :type="detail.state==='COMPLETED'?'success':'info'" effect="plain">{{detail.state==='COMPLETED'?'已完成':'版本已重新打开'}}</el-tag><span>{{environmentLabel(current.environment)}}环境</span></div><div v-if="authUser?.admin&&detail.state==='COMPLETED'" class="actions"><el-button link type="primary" :disabled="disabled" @click="emit('edit')">编辑小结</el-button><el-button link type="primary" :disabled="disabled" @click="emit('reopen')">重新打开</el-button></div></div>
    <p v-if="detail.state==='OPEN'&&current.reason" class="completion-reopened">重新打开原因：{{current.reason}}<span>{{current.actor}} · {{formatTime(current.createdAt)}}</span></p>
    <p class="completion-saved-summary">{{current.summary}}</p>
    <div class="completion-snapshot-counts"><span><b>{{current.counts.published}}</b> 已发布</span><span><b>{{current.counts.excluded}}</b> 本次不发布</span><span><b>{{current.counts.unconfirmed}}</b> 待确认</span><small>以下为收尾时的发布情况快照</small></div>
    <div class="completion-snapshot-items"><article v-for="item in current.items" :key="item.serviceId"><div class="completion-snapshot-identity"><ServiceIdentity :service="{...item,displayIcon:services?.find(service=>service.serviceId===item.serviceId)?.displayIcon}"/><el-tag :type="completionOutcomeType(item)" size="small" effect="plain">{{completionOutcomeLabel(item)}}</el-tag></div><div class="completion-snapshot-meta"><strong>{{item.branch}}</strong><CommitHash :value="item.commitId"/><span v-if="item.finishedAt">{{formatTime(item.finishedAt)}}</span><el-button v-if="allowedPath(item)" link type="primary" @click="router.push(allowedPath(item))">发布 #{{item.taskId}}</el-button><span v-else-if="item.taskId">发布 #{{item.taskId}}</span></div><p v-if="item.reason||item.message">{{item.reason||item.message}}</p></article></div>
    <div class="completion-panel-foot"><span>完成人 {{current.completedBy}} · {{formatTime(current.completedAt)}}</span><span v-if="current.eventType==='EDIT'">最近编辑 {{current.actor}} · {{formatTime(current.createdAt)}}</span><small>小结文字及发布数量对所有登录成员可见。</small></div>
    <el-collapse v-if="history.length" class="completion-history"><el-collapse-item :title="`历史记录（${history.length}）`" name="history"><article v-for="record in history" :key="record.id"><div><b>{{record.eventType==='COMPLETE'?'完成版本':record.eventType==='EDIT'?'编辑小结':'重新打开'}}</b><span>{{record.actor}} · {{formatTime(record.createdAt)}}</span></div><p v-if="record.reason">原因：{{record.reason}}</p><p v-if="record.summary">{{record.summary}}</p></article></el-collapse-item></el-collapse>
  </section>
</template>

<style scoped>
.version-completion-panel{border:1px solid #dce8e2;background:#fbfdfc;border-radius:10px;padding:20px;margin:21px 0 23px}.completion-panel-heading{display:flex;justify-content:space-between;align-items:center;gap:15px;flex-wrap:wrap}.completion-panel-heading>div:first-child{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.completion-panel-heading h4{margin:0;font-size:14px;color:#354b68;font-weight:600}.completion-panel-heading>div>span:last-child{font-size:11px;color:#74849a}.completion-saved-summary{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px;line-height:1.95;color:#52647c;margin:19px 0}.completion-snapshot-counts{display:flex;gap:18px;flex-wrap:wrap;align-items:center;padding:14px 0;font-size:12px;border-top:1px solid #e4ece7;color:#74849a}.completion-snapshot-counts b{color:#4f6d5c;font-weight:600}.completion-snapshot-counts small{margin-left:auto;color:#8a98ab;font-size:11px}.completion-snapshot-items{border-top:1px solid #e4ece7}.completion-snapshot-items>article{padding:15px 0;border-bottom:1px solid #e8eeeb}.completion-snapshot-identity{display:flex;align-items:center;justify-content:space-between;gap:14px}.completion-snapshot-meta{display:flex;align-items:center;gap:13px;flex-wrap:wrap;margin:8px 0 0 44px;font-size:11px;color:#8a98ab}.completion-snapshot-meta>strong{font-size:11px;font-weight:500;color:#6481ac;overflow-wrap:anywhere}.completion-snapshot-meta :deep(.el-button){font-size:11px;height:auto}.completion-snapshot-items>article>p{margin:6px 0 0 44px;font-size:12px;line-height:1.8;color:#7e796e;overflow-wrap:anywhere;white-space:pre-wrap}.completion-panel-foot{display:flex;flex-wrap:wrap;gap:10px 20px;margin-top:15px;color:#74849a;font-size:11px;line-height:1.8}.completion-panel-foot small{color:#8a98ab;font-size:11px;flex-basis:100%}.completion-reopened{font-size:12px;line-height:1.8;background:#f1f4f8;padding:12px;border-radius:7px;color:#67798f;white-space:pre-wrap;overflow-wrap:anywhere}.completion-reopened>span{display:block;font-size:11px;color:#8a98ab;margin-top:5px}.completion-history{margin-top:15px;border-color:#e4ece7}.completion-history :deep(.el-collapse-item__header){background:transparent;font-size:12px;color:#74849a}.completion-history :deep(.el-collapse-item__wrap){background:transparent}.completion-history article{padding:12px 0;border-bottom:1px solid #e4ece7}.completion-history article:last-child{border:0}.completion-history article>div{display:flex;gap:15px;flex-wrap:wrap;justify-content:space-between;font-size:11px;color:#8a98ab}.completion-history b{font-size:12px;color:#52647c;font-weight:500}.completion-history p{font-size:12px;line-height:1.85;color:#74849a;white-space:pre-wrap;overflow-wrap:anywhere;margin:8px 0 0}
@media(max-width:650px){.version-completion-panel{padding:15px}.completion-snapshot-counts{gap:10px 15px}.completion-snapshot-counts small{flex-basis:100%;margin:0}.completion-snapshot-identity{align-items:flex-start;flex-wrap:wrap;gap:9px}.completion-snapshot-meta,.completion-snapshot-items>article>p{margin-left:0}}
</style>
