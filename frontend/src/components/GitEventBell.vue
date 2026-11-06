<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, Close, RefreshRight } from '@element-plus/icons-vue'
import { useGitEvents } from '../composables/useGitEvents'
import { gitEventBuildRoute, gitEventReadThrough, gitEventTime } from '../gitEvents'
import type { GitEvent, GitEventService } from '../gitEvents'
import { formatTime, shortCommit } from '../ui'
import { authUser } from '../auth'

const router = useRouter(), route = useRoute()
const { available, feed, loading, error, readError, unread, refresh, markRead } = useGitEvents()
const open = ref(false), shown = ref<GitEvent[]>([]), snapshotLoading = ref(false), shownAt = ref(Date.now())
const trigger = ref<HTMLButtonElement>(), panel = ref<HTMLElement>(), list = ref<HTMLElement>(), hasOverflow = ref(false)
const newEvents = computed(() => open.value && feed.value.items.some(item => !shown.value.some(shownItem => shownItem.id === item.id)))
let opening = 0
async function showLatest() {
  const request = ++opening
  snapshotLoading.value = true
  await refresh()
  if (!open.value || request !== opening) return
  shown.value = feed.value.items.map(item => ({ ...item, services: item.services.map(service => ({ ...service })) }))
  shownAt.value = Date.now(); snapshotLoading.value = false
  await nextTick()
  measureOverflow()
  if (open.value && request === opening && document.visibilityState !== 'hidden') void markRead(gitEventReadThrough(shown.value), shown.value.map(item => item.id))
}
watch(open, value => {
  if (value) { shown.value = []; void showLatest(); void nextTick(() => panel.value?.focus()) }
  else { opening++; snapshotLoading.value = false }
})
watch(() => route.fullPath, () => { open.value = false })
watch(available, value => { if (!value) { open.value = false; shown.value = [] } })
watch(() => [authUser.value?.username, authUser.value?.admin, authUser.value?.permissions?.join(',')], () => { open.value = false; shown.value = [] }, { flush: 'sync' })
function measureOverflow() { hasOverflow.value = Boolean(list.value && list.value.scrollHeight > list.value.clientHeight + 1) }
onMounted(() => window.addEventListener('resize', measureOverflow))
onBeforeUnmount(() => window.removeEventListener('resize', measureOverflow))
function close() { open.value = false; trigger.value?.focus() }
function build(event: GitEvent, service: GitEventService) {
  const target = gitEventBuildRoute(service, event.branch, event.commitId)
  if (target) { open.value = false; void router.push(target) }
}
function buildable(event: GitEvent) { return event.services.filter(service => service.canBuild) }
function onPanelKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close() }
}
</script>

<template>
  <el-popover v-if="available" v-model:visible="open" trigger="click" placement="bottom-end" :width="424" :show-arrow="false" :offset="12" popper-class="git-events-popover" :teleported="true" :persistent="false">
    <template #reference>
      <button ref="trigger" type="button" class="git-bell" :class="{ 'is-open': open }" :aria-label="unread ? 'Git 动态，有未读更新' : 'Git 动态'" aria-haspopup="dialog" :aria-expanded="open" aria-controls="git-events-panel" title="Git 动态" data-testid="git-event-bell" @keydown.esc.stop="close">
        <el-icon :size="20"><Bell /></el-icon><span v-if="unread" class="git-bell-dot" aria-hidden="true" />
      </button>
    </template>
    <section id="git-events-panel" ref="panel" class="git-events-panel" role="dialog" aria-modal="false" aria-labelledby="git-events-heading" tabindex="-1" @keydown="onPanelKeydown">
      <header class="git-events-heading"><div><h2 id="git-events-heading">Git 动态</h2><p>最近 5 条推送 · 最新在前</p></div><button type="button" class="git-events-icon" aria-label="关闭 Git 动态" @click="close"><el-icon><Close /></el-icon></button></header>
      <div v-if="newEvents && !snapshotLoading" class="git-events-new"><span>有新的代码推送</span><button type="button" @click="showLatest">查看最新动态</button></div>
      <div v-if="snapshotLoading" class="git-events-state" role="status"><el-icon class="git-events-loading"><RefreshRight /></el-icon><span>正在读取代码动态…</span></div>
      <template v-else>
        <div v-if="error" class="git-events-error" role="status"><span>{{ shown.length ? '暂时无法刷新，以下为上次读取的动态。' : '暂时无法读取 Git 动态，请重试。' }}</span><button type="button" @click="showLatest">重试</button></div>
        <ol v-if="shown.length" ref="list" class="git-events-list" aria-label="最近的代码推送">
          <li v-for="event in shown" :key="event.id" class="git-event" :class="{ 'was-unread': event.unread }" :data-event-id="event.id">
            <div class="git-event-title"><strong :title="event.services.map(service => service.name).join('、')">{{ event.services.map(service => service.name).join('、') }}</strong><time :datetime="event.receivedAt" :title="formatTime(event.receivedAt)">{{ gitEventTime(event.receivedAt, shownAt) }}</time></div>
            <div class="git-event-branch"><span aria-hidden="true">⑂</span><b :title="event.branch">{{ event.branch }}</b></div>
            <p class="git-event-summary" :title="event.summary">{{ event.summary || '未填写提交说明' }}</p>
            <div class="git-event-meta"><span :title="event.actor">{{ event.actor || 'GitHub 用户' }} 推送</span><span>{{ shortCommit(event.commitId) }}</span></div>
            <div class="git-event-actions">
              <a v-if="event.eventUrl" :href="event.eventUrl" target="_blank" rel="noopener noreferrer" :aria-label="`在 GitHub 查看 ${event.branch} 的提交`">查看 GitHub</a>
              <button v-if="buildable(event).length === 1" type="button" class="git-event-build" @click="build(event, buildable(event)[0]!)">去打包</button>
              <el-dropdown v-else-if="buildable(event).length > 1" trigger="click" @command="(id: number) => { const service = event.services.find(item => item.id === id); if (service) build(event, service) }">
                <button type="button" class="git-event-build" aria-label="选择服务去打包">选择服务打包</button>
                <template #dropdown><el-dropdown-menu><el-dropdown-item v-for="service in buildable(event)" :key="service.id" :command="service.id">{{ service.name }}</el-dropdown-item></el-dropdown-menu></template>
              </el-dropdown>
            </div>
          </li>
        </ol>
        <div v-else-if="!error" class="git-events-state"><el-icon :size="28"><Bell /></el-icon><strong>{{ feed.enabled ? '还没有代码推送' : 'GitHub 联动暂未启用' }}</strong><p>{{ feed.enabled ? '已接入服务有新推送后，会显示在这里。' : '启用 GitHub 联动后，这里会展示最新代码动态。' }}</p></div>
      </template>
      <footer class="git-events-footer"><span><b v-if="hasOverflow" class="git-events-scroll-hint">共 {{ shown.length }} 条，可向下滚动查看</b>{{ readError || '选择“去打包”后，仍需核对并确认代码版本。' }}</span><button type="button" :disabled="loading || snapshotLoading" aria-label="刷新 Git 动态" @click="showLatest"><el-icon><RefreshRight /></el-icon></button></footer>
    </section>
  </el-popover>
</template>

<style>
.el-popover.git-events-popover{padding:0!important;border:1px solid #e2e8f2!important;border-radius:12px!important;width:min(424px,calc(100vw - 24px))!important;box-shadow:0 12px 35px #2539561f!important;background:#fff!important;color:#52647c}
</style>
<style scoped>
.git-events-scroll-hint{display:block;font-weight:500;color:#617b9f;margin-bottom:3px}
.git-bell{position:relative;display:grid;place-items:center;width:36px;height:36px;padding:0;border:0;border-radius:9px;background:transparent;color:#6c7c93;cursor:pointer}.git-bell:hover,.git-bell.is-open{background:#eef2fd;color:#416bdc}.git-bell-dot{position:absolute;top:5px;right:5px;width:7px;height:7px;background:#db5964;border:2px solid #fff;border-radius:50%;box-sizing:content-box}.git-bell:focus-visible,.git-events-panel button:focus-visible,.git-events-panel a:focus-visible{outline:2px solid #416bdc;outline-offset:3px}.git-events-panel{outline:none;font-family:inherit}.git-events-heading{display:flex;align-items:flex-start;justify-content:space-between;padding:19px 20px 16px;border-bottom:1px solid #edf0f6}.git-events-heading h2{font-size:16px;line-height:1.5;font-weight:650;color:#253956;margin:0}.git-events-heading p{font-size:11px;color:#8694a9;margin:4px 0 0}.git-events-icon{display:grid;place-items:center;width:28px;height:28px;border:0;background:transparent;color:#8795a9;border-radius:6px;cursor:pointer}.git-events-icon:hover{background:#f3f6fb;color:#416bdc}.git-events-list{list-style:none;margin:0;padding:0;max-height:min(760px,calc(100dvh - 210px));overflow-y:auto;overscroll-behavior:contain}.git-event{padding:14px 20px 12px;border-bottom:1px solid #edf0f6}.git-event:last-child{border-bottom:0}.git-event.was-unread{border-left:3px solid #7c98df;padding-left:17px}.git-event-title{display:flex;align-items:baseline;justify-content:space-between;gap:14px}.git-event-title strong{color:#304460;font-size:14px;font-weight:650;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.git-event-title time{font-size:11px;white-space:nowrap;color:#7c8da5}.git-event-branch{display:flex;align-items:center;gap:6px;margin-top:7px;min-width:0;color:#416bdc;font-size:12px}.git-event-branch>span{font-size:17px;line-height:1}.git-event-branch b{font-weight:550;overflow-wrap:anywhere;line-height:1.5}.git-event-summary{color:#52647c;font-size:12px;line-height:1.6;margin:8px 0 0;overflow:hidden;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow-wrap:anywhere}.git-event-meta{display:flex;justify-content:space-between;gap:12px;margin-top:7px;color:#8795a9;font-size:11px}.git-event-meta>span:first-child{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.git-event-meta>span:last-child{flex-shrink:0;font-variant-numeric:tabular-nums}.git-event-actions{display:flex;align-items:center;justify-content:flex-end;gap:18px;margin-top:10px}.git-event-actions a,.git-event-build,.git-events-new button,.git-events-error button{font-family:inherit;font-size:12px;color:#416bdc;line-height:1.5;text-decoration:none;background:none;padding:0;border:0;cursor:pointer}.git-event-actions a{color:#7a8aa1}.git-event-actions a:hover,.git-event-build:hover,.git-events-new button:hover,.git-events-error button:hover{text-decoration:underline}.git-event-build{font-weight:600}.git-events-state{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;min-height:185px;padding:28px 25px;color:#8a99ae;text-align:center;font-size:12px}.git-events-state strong{font-weight:500;color:#52647c;font-size:13px}.git-events-state p{margin:0;line-height:1.8}.git-events-footer{display:flex;align-items:center;justify-content:space-between;gap:15px;padding:12px 16px 12px 20px;border-top:1px solid #edf0f6;font-size:10px;line-height:1.6;color:#8a99ae;background:#fafbfd;border-radius:0 0 12px 12px}.git-events-footer button{display:grid;place-items:center;width:28px;height:28px;border:0;color:#7186a6;background:transparent;border-radius:6px;cursor:pointer;flex-shrink:0}.git-events-footer button:hover{background:#eef2fd}.git-events-footer button:disabled{opacity:.45;cursor:wait}.git-events-new,.git-events-error{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:10px 20px;font-size:11px;line-height:1.6;background:#f0f4ff;color:#5372ac}.git-events-error{background:#fff8f4;color:#976a50}.git-events-loading{animation:git-events-spin 1.5s linear infinite}@keyframes git-events-spin{to{transform:rotate(360deg)}}@media(prefers-reduced-motion:reduce){.git-events-loading{animation:none}}@media(max-width:420px){.git-event{padding-right:16px}.git-event-title{gap:8px}.git-events-heading{padding:16px}.git-events-footer{padding-left:16px}.git-events-list{max-height:calc(100dvh - 225px)}}
</style>
