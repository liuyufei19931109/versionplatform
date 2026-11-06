<script setup lang="ts">
import { tUi } from '../i18n'

import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { authUser, hasMenu } from '../auth'
import { versionBuildRoute, versionStatusLabel, versionStatusType } from '../versions'
import type { VersionItem } from '../versions'
import { useVersionCodeUpdates } from '../composables/useVersionCodeUpdates'
import { codeBuildBlocked, codeBuildStatusLabel, codeEventLabel, codeStatusLabel, codeStatusType } from '../versionCodeUpdates'
import type { VersionCodeUpdate } from '../versionCodeUpdates'
import { environmentLabel, formatTime, timeParts } from '../ui'
import ServiceIdentity from './ServiceIdentity.vue'
import CommitHash from './CommitHash.vue'

const props = withDefaults(defineProps<{ items: VersionItem[]; versionId?: number; compact?: boolean; busy?: boolean }>(), { compact: false, busy: false })
const emit = defineEmits<{ retry: [item: VersionItem]; link: [item: VersionItem] }>()
const router = useRouter()
const code = useVersionCodeUpdates(() => props.versionId)
const { enabled: codeEnabled, loading: codeLoading, error: codeError } = code
const codeByService = computed(() => new Map(code.items.value.map(item => [item.serviceId, item])))
function update(item: VersionItem): VersionCodeUpdate {
  const value = codeByService.value.get(item.serviceId)
  return value?.branch === item.branch ? value : { serviceId: item.serviceId, branch: item.branch, status: 'UNKNOWN' }
}
function buildRoute(item: VersionItem) { return codeEnabled.value && codeBuildBlocked(update(item)) ? undefined : versionBuildRoute(item) }
function build(item: VersionItem) {
  const target = buildRoute(item)
  if (!target) return
  const noticedCommit = codeEnabled.value && !stale(item) ? update(item).headSha : undefined
  router.push({ ...target, query: { ...target.query, noticedCommit } })
}
type BuildMatch = { status?: string; artifactId?: number; buildTaskId?: number; environmentName?: string }
function matchAction(item: VersionItem, match: BuildMatch) {
  if (item.status !== 'SUCCESS' || !codeEnabled.value || stale(item) || codeBuildBlocked(update(item))) return
  const app = item.serviceType === 'FLUTTER_ANDROID'
  if (match.status === 'BUILT' && match.artifactId && hasMenu(app ? 'apps' : 'release')) {
    return { label: app ? tUi('交付此包') : tUi('用此包发版'), to: {
      path: app ? `/apps/${item.serviceId}/release` : '/release/new',
      query: { serviceId: String(item.serviceId), artifactId: String(match.artifactId), environment: match.environmentName },
    } }
  }
  if (match.buildTaskId && hasMenu(app ? 'apps' : 'builds')) {
    const label = match.status === 'BUILD_FAILED' ? tUi('查看失败任务') : match.status === 'BUILT' ? tUi('查看构建') : tUi('查看任务')
    return { label, to: { path: `${app ? '/apps' : ''}/builds/${match.buildTaskId}` } }
  }
}
const rowActions = computed(() => new Map(props.items.map(item => {
  const state = update(item)
  const matches: BuildMatch[] = item.serviceType === 'FLUTTER_ANDROID' ? state.environments || []
    : [{ status: state.buildStatus, artifactId: state.artifactId, buildTaskId: state.buildTaskId }]
  const actions = matches.flatMap(match => {
    const action = matchAction(item, match)
    if (!action) return []
    const label = match.environmentName ? environmentLabel(match.environmentName) + ' · ' + action.label : action.label
    return [{ label, to: action.to }]
  })
  return [item.serviceId, actions] as const
})))
function stale(item: VersionItem) { const value = update(item); return Boolean(codeError.value) || value.status === 'SYNC_ERROR' || value.status === 'UNKNOWN' && Boolean(value.headSha) }
function updateTime(item: VersionItem) { const parts = timeParts(update(item).updatedAt); return `${parts.date} ${parts.time}`.trim() }
function checkedTime(item: VersionItem) { const parts = timeParts(update(item).checkedAt); return `${parts.date} ${parts.time}`.trim() }
function linkedBranchMessage(message: string): string {
  const match = /^已关联已有分支；核对时提交 ([0-9a-f]{40,64})；后续打包继续校验最新 master$/i.exec(message)
  return match ? tUi('已关联已有分支；核对时提交 {0}；后续打包继续校验最新 master', match[1]) : tUi(message)
}
</script>

<template>
  <div class="version-services" :class="{ 'is-compact': compact }">
    <div v-if="codeError" class="version-code-error" role="status"><span>{{ tUi('代码动态暂时无法读取。{0}', codeEnabled?tUi('下方保留上次结果，请重新核对。'):tUi('版本记录和原打包入口仍可使用。')) }}</span><el-button link type="primary" :loading="codeLoading" @click="code.refresh">{{ tUi('重新读取') }}</el-button></div>
    <div v-else-if="codeEnabled" class="version-code-notice"><span>{{ tUi('代码更新提醒') }}</span><small>{{compact?tUi('关注后续新提交，不自动打包或发布'):tUi('关联时开始监控分支，后续新提交才提醒打包；不会自动打包或发布。')}}</small><el-button v-if="!compact" link type="primary" :loading="codeLoading" @click="code.refresh">{{ tUi('刷新代码状态') }}</el-button></div>
    <div class="version-service-head" aria-hidden="true"><span>{{ tUi('服务模块') }}</span><span>{{ tUi('版本分支') }}<span v-if="!compact"> {{ tUi('/ 核对基线') }}</span></span><span>{{codeEnabled?tUi('分支 / 代码状态'):tUi('分支状态')}}</span><span>{{ tUi('操作') }}</span></div>
    <article v-for="item in items" :key="item.serviceId" class="version-service-row" :data-service-id="item.serviceId" :data-code-status="codeEnabled?update(item).status:undefined">
      <ServiceIdentity :service="item" />
      <div class="version-service-branch"><b :title="item.branch">{{ item.branch }}</b><div v-if="!compact" class="version-service-baseline"><span>master</span><CommitHash :value="item.baselineSha" /></div><small v-if="!compact&&item.baselineMessage" :title="item.baselineMessage">{{item.baselineMessage}}</small>
        <div v-if="codeEnabled" class="version-code-event" :class="{'is-stale':stale(item)}">
          <div v-if="update(item).headSha" class="version-code-revision"><span>{{stale(item)?tUi('上次记录'):tUi('最新提交')}}</span><CommitHash :value="update(item).headSha"/></div>
          <p v-if="update(item).title" class="version-code-title" :title="update(item).title">{{update(item).title}}</p>
          <div v-if="update(item).updatedAt||!compact&&update(item).actor" class="version-code-byline"><span v-if="!compact">{{codeEventLabel(update(item).eventType)}}<template v-if="update(item).actor"> · {{update(item).actor}}</template></span><time v-if="update(item).updatedAt" :title="formatTime(update(item).updatedAt)">{{update(item).eventType==='SYNC'?tUi('发现于'):tUi('记录于')}} {{updateTime(item)}}</time></div>
          <small v-if="update(item).checkedAt" class="version-code-checked" :title="formatTime(update(item).checkedAt)">{{ tUi('最近核对 {0}', checkedTime(item)) }}</small>
          <p v-if="stale(item)" class="version-code-stale">{{update(item).headSha?tUi('旧记录，尚未确认最新代码'):tUi('尚未确认分支最新代码')}}</p>
        </div>
      </div>
      <div class="version-service-state"><div class="version-creation-state"><small v-if="codeEnabled">{{ tUi('分支') }}</small><el-tag size="small" :type="versionStatusType(item.status)" effect="light" disable-transitions>{{ versionStatusLabel(item.status) }}</el-tag></div><p v-if="item.message&&item.status!=='SUCCESS'">{{ tUi(item.message) }}</p>
        <p v-if="item.status==='SUCCESS'&&item.message?.startsWith('已关联已有分支')" class="version-code-message" :title="linkedBranchMessage(item.message)">{{ tUi('已关联已有分支，保留现有代码') }}</p>
        <div v-if="codeEnabled" class="version-code-state"><el-tag size="small" :type="codeError?'info':codeStatusType(update(item).status)" effect="light" disable-transitions>{{codeError?tUi('上次：'):''}}{{codeStatusLabel(update(item).status)}}</el-tag>
          <small v-if="['NO_UPDATES','UNKNOWN'].includes(update(item).status)&&update(item).buildStatus==='PENDING_BUILD'" class="version-code-build-note">{{codeBuildStatusLabel(update(item).buildStatus)}}</small>
          <div v-if="item.serviceType==='FLUTTER_ANDROID'&&update(item).environments?.length" class="version-code-environments"><span v-for="env in update(item).environments" :key="env.environmentName" :class="'code-env-'+env.status.toLowerCase()"><b>{{environmentLabel(env.environmentName)}}</b>{{codeBuildStatusLabel(env.status)}}</span></div>
          <p v-if="!compact&&update(item).message" class="version-code-message">{{ tUi(update(item).message || '') }}</p>
          <p v-else-if="update(item).status==='UNKNOWN'&&!compact">{{ tUi('尚未取得分支核对结果') }}</p>
        </div>
      </div>
      <div class="version-service-actions">
        <el-button v-for="action in rowActions.get(item.serviceId)" :key="action.label" link type="primary" @click="router.push(action.to)">{{action.label}}</el-button>
        <el-button v-if="buildRoute(item)" link :type="rowActions.get(item.serviceId)?.length ? 'info' : 'primary'" @click="build(item)">{{rowActions.get(item.serviceId)?.length ? tUi('新建打包') : tUi('去打包')}}</el-button>
        <template v-else-if="authUser?.admin&&['FAILED','CONFLICT','UNKNOWN'].includes(item.status)&&!compact">
          <el-button link type="primary" :disabled="busy" @click="emit('link',item)">{{ tUi('关联已有分支') }}</el-button>
          <el-button link type="primary" :disabled="busy" @click="emit('retry',item)">{{ tUi('核对并重试') }}</el-button>
        </template>
        <span v-else-if="!rowActions.get(item.serviceId)?.length" class="no-action">{{codeEnabled&&codeBuildBlocked(update(item))?tUi('请核对分支'):'—'}}</span>
        <a v-if="codeEnabled&&update(item).eventUrl" :href="update(item).eventUrl" target="_blank" rel="noopener noreferrer" class="version-github-link">{{ tUi('看 GitHub') }}</a>
      </div>
    </article>
    <div v-if="!items.length" class="version-services-empty">{{ tUi('当前权限下没有可查看的服务模块。') }}</div>
  </div>
</template>

<style scoped>
.version-code-notice,.version-code-error{display:flex;align-items:center;flex-wrap:wrap;gap:7px 13px;margin:0 0 13px;font-size:12px;color:#52647c;line-height:1.7}.version-code-notice>span{font-weight:600}.version-code-notice>small{font-size:11px;color:#74849a}.version-code-notice>.el-button,.version-code-error>.el-button{margin-left:auto;font-size:12px}.version-code-error{padding:10px 13px;background:#fff8ec;border-left:3px solid #d4a24d;border-radius:4px;color:#8d6028}.version-code-event{min-width:0;margin-top:6px;padding-top:8px;border-top:1px solid #edf1f6}.version-code-revision{display:flex;gap:4px;align-items:center;flex-wrap:wrap;color:#74849a;font-size:11px}.version-code-title{margin:5px 0 0;color:#52647c;font-size:12px;line-height:1.65;overflow:hidden;text-overflow:ellipsis;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow-wrap:anywhere}.version-code-byline{display:flex;flex-wrap:wrap;gap:3px 10px;margin-top:5px;font-size:11px;color:#74849a;line-height:1.7;overflow-wrap:anywhere}.version-code-byline time{font-variant-numeric:tabular-nums}.version-code-checked{display:block;margin-top:4px;font-size:10px;color:#8a98ab;line-height:1.6}.version-code-stale{margin:5px 0 0;font-size:11px;color:#986d3c;line-height:1.6}.version-creation-state{display:flex;align-items:center;gap:6px}.version-creation-state>small{font-size:11px;color:#74849a}.version-code-state{margin-top:10px}.version-code-state>.el-tag{max-width:100%;height:auto;min-height:21px;white-space:normal;line-height:1.5;padding:2px 7px}.version-code-environments{display:grid;gap:5px;margin-top:8px;font-size:10px;line-height:1.65;color:#74849a}.version-code-environments>span{display:flex;align-items:baseline;gap:6px;flex-wrap:wrap}.version-code-environments b{font-weight:500;color:#52647c}.version-code-environments .code-env-built{color:#28734f}.version-code-environments .code-env-build_failed{color:#a84040}.version-code-environments .code-env-building{color:#355da7}.version-service-state .version-code-message{color:#74849a}.version-github-link{color:#74849a;text-decoration:none;font-size:11px;line-height:1.8}.version-github-link:hover{color:#416bdc;text-decoration:underline}.version-github-link:focus-visible{outline:2px solid #416bdc;outline-offset:3px}.version-service-actions{display:flex;align-items:flex-end;flex-direction:column;gap:10px}.is-compact .version-code-title{-webkit-line-clamp:1;font-size:11px}.is-compact .version-code-notice{margin-bottom:10px}.is-compact .version-code-byline{font-size:10px}
.version-code-build-note{display:block;margin-top:5px;color:#74849a;font-size:11px;line-height:1.65}.version-code-environments .code-env-pending_build{color:#74849a}
.version-service-state :deep(.el-tag--success){--el-tag-text-color:#28734f;--el-tag-bg-color:#edf7f1;--el-tag-border-color:#cfe7d8}.version-service-state :deep(.el-tag--danger){--el-tag-text-color:#a84040;--el-tag-bg-color:#fff1f1;--el-tag-border-color:#f2d6d6}.version-service-state :deep(.el-tag--warning){--el-tag-text-color:#8d6028;--el-tag-bg-color:#fff5e4;--el-tag-border-color:#f0dfbc}.version-service-state :deep(.el-tag--primary){--el-tag-text-color:#355da7;--el-tag-bg-color:#f0f4ff;--el-tag-border-color:#d5e0f7}
.version-services{min-width:0}.version-service-head,.version-service-row{display:grid;grid-template-columns:minmax(180px,1.25fr) minmax(175px,1fr) minmax(115px,.8fr) 90px;gap:18px;align-items:center}.version-service-head{padding:12px 16px;background:#f7f9fd;border-radius:7px;color:#74849a;font-size:12px}.version-service-row{padding:19px 16px;border-bottom:1px solid #edf1f6;min-width:0}.version-service-row:last-child{border:0}.version-service-branch{min-width:0;display:grid;gap:5px}.version-service-branch>b{font-size:13px;color:#354b68;font-weight:600;overflow-wrap:anywhere}.version-service-baseline{display:flex;align-items:center;gap:5px;color:#74849a;font-size:11px}.version-service-baseline>span{font-weight:500}.version-service-branch>small{color:#7f8da2;font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.version-service-state{min-width:0}.version-service-state p{margin:7px 0 0;font-size:11px;line-height:1.65;color:#97635d;overflow-wrap:anywhere}.version-service-actions{justify-self:end;white-space:nowrap}.version-service-actions .el-button{font-size:12px}.no-action{color:#a8b3c3;font-size:12px}.version-services-empty{padding:30px;text-align:center;color:#74849a;font-size:12px}.is-compact .version-service-row{padding-top:16px;padding-bottom:16px}.is-compact .version-service-state p{max-height:50px;overflow:auto}
@media(max-width:1160px){.version-service-head,.version-service-row{grid-template-columns:minmax(170px,1.2fr) minmax(150px,1fr) 100px 80px;gap:12px}}
@media(max-width:850px){.version-service-head{display:none}.version-service-row{grid-template-columns:minmax(0,1fr) auto;gap:13px;padding:18px 0}.version-service-row>.service-identity{grid-column:1;grid-row:1}.version-service-branch{grid-column:1;grid-row:2}.version-service-state{grid-column:1;grid-row:3}.version-service-actions{grid-column:2;grid-row:1/4}.version-service-branch>b{font-size:12px}.version-service-baseline{display:none}.version-service-branch>small{display:none}.version-service-state p{max-width:100%}}
</style>
