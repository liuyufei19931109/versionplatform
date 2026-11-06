<script setup lang="ts">
import { currentLocale, tUi } from '../i18n'

import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, ArrowLeft, ArrowUp, Bottom, Close, CopyDocument, Delete, Download, FullScreen, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { errorText, http, payload } from '../api'
import { clearAuth } from '../auth'
import type { RuntimeRow, RuntimeSnapshot } from '../runtime'
import { environmentLabel } from '../ui'
import { copyLogText, readLogWrap, saveLogWrap } from '../logReading'
import {
  MAX_ENTRIES, SINCE_OPTIONS, TAIL_OPTIONS, downloadName, downloadText, logTime, matches, parseSse, segments, toEntries, visibleEntries, withoutOverlap,
  type LogEntry, type LogHeader, type LogSnapshot, type LogSource, type RawLogLine,
} from '../containerLogs'

type Phase = 'idle' | 'loading' | 'connecting' | 'live' | 'reconnecting' | 'ended' | 'error'
const route = useRoute(), router = useRouter()

function pref<T>(key: string, fallback: T): T { try { const value = localStorage.getItem(`containerLogs.${key}`); return value == null ? fallback : JSON.parse(value) as T } catch { return fallback } }
function savePref(key: string, value: unknown) { try { localStorage.setItem(`containerLogs.${key}`, JSON.stringify(value)) } catch { /* private mode */ } }

const rows = ref<RuntimeRow[]>([]), rowsError = ref('')
const header = ref<LogHeader>()
const entries = shallowRef<LogEntry[]>([])
let nextId = 1
const tail = ref<number>(TAIL_OPTIONS.includes(pref('tail', 500)) ? pref('tail', 500) : 500), since = ref('')
const live = ref(true), phase = ref<Phase>('idle'), error = ref(''), notice = ref(''), fetchedAt = ref('')
const truncatedEarly = ref(false), dropped = ref(0), trimmed = ref(0)
const query = ref(''), onlyMatches = ref(false), level = ref<'all' | 'warn' | 'error'>('all')
const showTime = ref(pref('time', true)), wrap = ref(readLogWrap()), fullscreen = ref(false)
const follow = ref(true), unseen = ref(0), activeId = ref<number>()
const loadingEarlier = ref(false), noEarlier = ref(false), retry = ref(0)
const viewport = ref<HTMLElement>(), searchInput = ref<{ focus: () => void }>()
let controller: AbortController | undefined, generation = 0, retryTimer: ReturnType<typeof setTimeout> | undefined, resumePending = false

const environmentId = computed(() => Number(route.query.environmentId) || resolvedRow.value?.environmentId || undefined)
const serverId = computed(() => Number(route.query.serverId) || undefined)
const containerId = computed(() => /^[a-f0-9]{12,64}$/.test(String(route.query.containerId || '')) ? String(route.query.containerId) : '')
const source = computed<LogSource>(() => route.query.source === 'stdout' ? 'stdout' : 'application')
const fileId = computed(() => /^[a-f0-9]{64}$/.test(String(route.query.fileId || '')) ? String(route.query.fileId) : '')
// Release details link with serviceId + environment; resolve the binding from the runtime overview.
const resolvedRow = computed(() => rows.value.find(row => String(row.serviceId) === String(route.query.serviceId) && row.environmentName === route.query.environment && String(row.serverId) === String(route.query.serverId)))
const currentRow = computed(() => rows.value.find(row => row.environmentId === environmentId.value && row.serverId === serverId.value))
const targetKey = computed(() => environmentId.value && serverId.value ? `${environmentId.value}:${serverId.value}` : '')
const nodeGroups = computed(() => {
  const groups = new Map<string, { label: string; rows: RuntimeRow[] }>()
  for (const row of rows.value) {
    if (!row.serverId) continue
    const key = String(row.serviceId)
    if (!groups.has(key)) groups.set(key, { label: row.serviceName, rows: [] })
    groups.get(key)!.rows.push(row)
  }
  return [...groups.values()]
})
const trimmedQuery = computed(() => query.value.trim())
const visible = computed(() => visibleEntries(entries.value, { query: trimmedQuery.value, onlyMatches: onlyMatches.value, level: level.value }))
const matchIds = computed(() => trimmedQuery.value ? visible.value.filter(entry => matches(entry, trimmedQuery.value)).map(entry => entry.id) : [])
const errorIds = computed(() => entries.value.filter(entry => entry.level === 'error' && !entry.continuation).map(entry => entry.id))
const activeMatchIndex = computed(() => activeId.value == null ? -1 : matchIds.value.indexOf(activeId.value))
// Chunks let Vue skip unchanged blocks (v-memo) and the browser skip off-screen ones (content-visibility).
const chunks = computed(() => {
  const size = 200, list: { key: string; entries: LogEntry[]; active: boolean }[] = []
  for (let index = 0; index < visible.value.length; index += size) {
    const part = visible.value.slice(index, index + size)
    list.push({ key: `${part[0].id}-${part.length}-${part[part.length - 1].id}`, entries: part, active: activeId.value != null && part.some(entry => entry.id === activeId.value) })
  }
  return list
})
const statusText = computed(() => ({
  idle: fetchedAt.value ? tUi('已读取 {0}', logTime(fetchedAt.value)) : entries.value.length ? tUi('已暂停') : tUi('未读取'), loading: tUi('正在读取…'), connecting: tUi('正在连接…'),
  live: tUi('实时跟踪中'), reconnecting: tUi('连接中断，正在重连（{0}/3）', retry.value), ended: tUi('已停止跟踪'), error: tUi('读取失败'),
})[phase.value])
const currentContainer = computed(() => header.value?.containers.find(container => container.id === header.value?.containerId))
const actualSource = computed(() => header.value?.source || source.value)
const selectedFile = computed(() => header.value?.files?.find(file => file.id === header.value?.fileId))
const sourcePath = computed(() => actualSource.value === 'application'
  ? [header.value?.logDirectory, selectedFile.value?.name].filter(Boolean).join('/')
  : tUi('容器标准输出（docker logs）'))
const busy = computed(() => phase.value === 'loading' || phase.value === 'connecting')

watch(showTime, value => savePref('time', value))
watch(wrap, saveLogWrap)
watch(tail, value => savePref('tail', value))

function resetBuffer() { entries.value = []; nextId = 1; trimmed.value = 0; dropped.value = 0; unseen.value = 0; truncatedEarly.value = false; noEarlier.value = false; activeId.value = undefined; follow.value = true }

function append(lines: RawLogLine[]) {
  const incoming = resumePending ? withoutOverlap(lines, entries.value.slice(-200), 'after') : lines
  resumePending = false
  if (!incoming.length) return
  const created = toEntries(incoming, nextId, entries.value[entries.value.length - 1]); nextId += created.length
  let next = entries.value.concat(created)
  if (next.length > MAX_ENTRIES) { trimmed.value += next.length - MAX_ENTRIES; next = next.slice(-MAX_ENTRIES) }
  entries.value = next
  if (follow.value) void nextTick(scrollToBottom)
  else unseen.value += created.length
}

function replace(lines: RawLogLine[]) { resetBuffer(); const created = toEntries(lines, nextId); nextId += created.length; entries.value = created; void nextTick(scrollToBottom) }

function stopStream() {
  generation++; controller?.abort(); controller = undefined; loadingEarlier.value = false
  if (retryTimer) { clearTimeout(retryTimer); retryTimer = undefined }
  if (phase.value === 'live' || phase.value === 'connecting' || phase.value === 'reconnecting') phase.value = 'idle'
}

function params(extra: Record<string, string | number | undefined>) {
  const value: Record<string, string> = { environmentId: String(environmentId.value), serverId: String(serverId.value), source: source.value }
  if (containerId.value) value.containerId = containerId.value
  const selectedId = fileId.value || header.value?.fileId
  if (source.value === 'application' && selectedId) value.fileId = selectedId
  for (const [key, item] of Object.entries(extra)) if (item !== undefined && item !== '') value[key] = String(item)
  return value
}

async function loadSnapshot() {
  if (!targetKey.value) return
  stopStream(); const current = ++generation
  phase.value = 'loading'; error.value = ''; notice.value = ''
  try {
    const data = payload<LogSnapshot>(await http.get('/runtime/logs', { params: params({ tail: tail.value, since: since.value }), timeout: 120000 }), {} as LogSnapshot)
    if (current !== generation) return
    header.value = data.header; replace(data.lines || []); truncatedEarly.value = Boolean(data.truncated); fetchedAt.value = data.fetchedAt || new Date().toISOString(); phase.value = 'idle'
  } catch (reason) {
    if (current !== generation) return
    error.value = errorText(reason, tUi('读取日志失败')); phase.value = 'error'
  }
}

class StreamRejected extends Error {}

async function startStream(resume: boolean) {
  if (!targetKey.value) return
  stopStream(); const current = ++generation, abort = new AbortController(); controller = abort
  const last = entries.value[entries.value.length - 1]
  resume = resume && Boolean(last?.ts)
  phase.value = retry.value ? 'reconnecting' : 'connecting'; error.value = ''; notice.value = ''; fetchedAt.value = ''
  if (!resume) resetBuffer()
  resumePending = resume
  const search = new URLSearchParams(params(resume ? { since: last!.ts!, tail: 5000 } : { tail: tail.value, since: since.value }))
  let ended = false
  try {
    const token = localStorage.getItem('release_token')
    const response = await fetch(`/api/runtime/logs/stream?${search}`, { headers: { Accept: 'text/event-stream, application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, signal: abort.signal, cache: 'no-store' })
    if (current !== generation) return
    if (response.status === 401) { clearAuth(); location.replace('/login?expired=1'); return }
    if (!response.ok || !response.body) {
      const body = await response.json().catch(() => ({})) as { message?: string }
      throw new StreamRejected(body.message || tUi('日志连接失败（HTTP {0}）', response.status))
    }
    const reader = response.body.getReader(), decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      if (current !== generation) { void reader.cancel(); return }
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const parsed = parseSse(buffer); buffer = parsed.rest
      for (const event of parsed.events) {
        const data = JSON.parse(event.data)
        if (event.event === 'header') {
          const nextHeader = data as LogHeader
          if (resume && header.value && (header.value.source !== nextHeader.source || header.value.fileId !== nextHeader.fileId || header.value.containerId !== nextHeader.containerId)) {
            resetBuffer(); resumePending = false
          }
          header.value = nextHeader; phase.value = 'live'; retry.value = 0
        }
        else if (event.event === 'lines') { append(data.lines || []); dropped.value += Number(data.dropped) || 0 }
        else if (event.event === 'end') { ended = true; finish(data.reason, data.message) }
      }
    }
    if (current === generation && !ended) reconnect()
  } catch (reason) {
    if (abort.signal.aborted || current !== generation) return
    if (reason instanceof StreamRejected) { error.value = reason.message; phase.value = 'error'; live.value = false; return }
    reconnect()
  }
}

function finish(reason: string, message: string) {
  if (reason === 'ERROR') { error.value = message || tUi('日志读取中断'); phase.value = 'error' }
  else { notice.value = message; phase.value = 'ended' }
  live.value = false
}

function reconnect() {
  if (!live.value) return
  if (retry.value >= 3) { error.value = tUi('实时日志连接已断开，请检查网络后重新连接'); phase.value = 'error'; live.value = false; retry.value = 0; return }
  retry.value++; phase.value = 'reconnecting'
  retryTimer = setTimeout(() => { retryTimer = undefined; if (live.value) void startStream(true) }, [2000, 5000, 10000][retry.value - 1])
}

function reload() { if (live.value) void startStream(false); else void loadSnapshot() }

watch(live, value => {
  if (value) { retry.value = 0; void startStream(entries.value.length > 0) }
  else stopStream()
})
watch([tail, since], () => { if (targetKey.value) reload() })
watch(() => [route.query.environmentId, route.query.serverId, route.query.containerId, route.query.source, route.query.fileId], () => {
  stopStream(); header.value = undefined; resetBuffer(); error.value = ''; notice.value = ''; fetchedAt.value = ''; retry.value = 0
  if (!targetKey.value) { stopStream(); phase.value = 'idle'; return }
  if (!live.value) live.value = true
  else void startStream(false)
})

function selectTarget(value: string) {
  const [env, server] = value.split(':')
  void router.replace({ path: '/runtime/logs', query: { environmentId: env, serverId: server } })
}
function selectContainer(value: string) { void router.replace({ query: { ...route.query, containerId: value } }) }
function selectSource(value: LogSource) { void router.replace({ query: { ...route.query, source: value, fileId: undefined, containerId: undefined } }) }
function selectFile(value: string) { void router.replace({ query: { ...route.query, fileId: value } }) }

async function loadEarlier() {
  const first = entries.value[0]
  if (!first?.ts || loadingEarlier.value || !targetKey.value) return
  const current = generation
  loadingEarlier.value = true
  try {
    const data = payload<LogSnapshot>(await http.get('/runtime/logs', { params: params({ tail: tail.value, until: first.ts, containerId: header.value?.containerId }), timeout: 120000 }), {} as LogSnapshot)
    if (current !== generation) return
    const lines = withoutOverlap(data.lines || [], entries.value.slice(0, 200), 'before')
    if (!lines.length) { noEarlier.value = true; return }
    const element = viewport.value, before = element?.scrollHeight || 0
    const created = toEntries(lines, nextId); nextId += created.length
    entries.value = created.concat(entries.value)
    follow.value = false
    await nextTick()
    if (element) element.scrollTop += element.scrollHeight - before
  } catch (reason) { if (current === generation) ElMessage.error(errorText(reason, tUi('读取更早的日志失败'))) }
  finally { if (current === generation) loadingEarlier.value = false }
}

function onScroll() {
  const element = viewport.value
  if (!element) return
  const atBottom = element.scrollTop + element.clientHeight >= element.scrollHeight - 24
  follow.value = atBottom
  if (atBottom) unseen.value = 0
}
function scrollToBottom() { const element = viewport.value; if (element) element.scrollTop = element.scrollHeight; follow.value = true; unseen.value = 0 }
async function reveal(id: number) {
  activeId.value = id; follow.value = false
  await nextTick()
  viewport.value?.querySelector(`[data-id="${id}"]`)?.scrollIntoView({ block: 'center' })
}
function stepMatch(direction: 1 | -1) {
  const ids = matchIds.value
  if (!ids.length) return
  const index = activeMatchIndex.value < 0 ? (direction > 0 ? 0 : ids.length - 1) : (activeMatchIndex.value + direction + ids.length) % ids.length
  void reveal(ids[index])
}
function nextError() {
  const ids = errorIds.value
  if (!ids.length) return
  const target = ids.find(id => activeId.value == null || id > activeId.value) ?? ids[0]
  // Errors pass every level filter; only "matches only" can hide them.
  if (!visible.value.some(entry => entry.id === target)) onlyMatches.value = false
  void reveal(target)
}
watch(trimmedQuery, () => { activeId.value = undefined })

function download() {
  const blob = new Blob([downloadText(entries.value, actualSource.value)], { type: 'text/plain;charset=utf-8' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = downloadName([currentRow.value?.serviceKey || header.value?.serviceName, header.value?.environmentName, header.value?.serverName, selectedFile.value?.name || actualSource.value])
  link.click(); setTimeout(() => URL.revokeObjectURL(link.href), 1000)
}
async function copyVisible() {
  const text = downloadText(visible.value, actualSource.value)
  try {
    await copyLogText(text)
    ElMessage.success(tUi('已复制 {0} 行', visible.value.length))
  } catch { ElMessage.warning(tUi('浏览器未允许复制，请改用下载')) }
}
function clearView() { resetBuffer(); notice.value = '' }

function onKey(event: KeyboardEvent) {
  const target = event.target as HTMLElement | null
  const typing = target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)
  if (event.key === '/' && !typing) { event.preventDefault(); searchInput.value?.focus() }
  else if (event.key === 'Escape' && fullscreen.value && !typing) fullscreen.value = false
}
function onSearchKey(event: KeyboardEvent) {
  if (event.key === 'Enter') { event.preventDefault(); stepMatch(event.shiftKey ? -1 : 1) }
  else if (event.key === 'Escape') query.value = ''
}

onMounted(async () => {
  window.addEventListener('keydown', onKey)
  try { rows.value = payload<RuntimeSnapshot>(await http.get('/runtime'), { rows: [] } as unknown as RuntimeSnapshot).rows || [] }
  catch (reason) { rowsError.value = errorText(reason, tUi('读取服务节点失败')) }
  if (!route.query.environmentId && resolvedRow.value) { await router.replace({ query: { environmentId: String(resolvedRow.value.environmentId), serverId: String(resolvedRow.value.serverId) } }); return }
  if (targetKey.value) void startStream(false)
})
onBeforeUnmount(() => { window.removeEventListener('keydown', onKey); stopStream() })
</script>

<template>
  <div class="logs-page" :class="{ 'is-fullscreen': fullscreen }">
    <div class="page-head logs-head">
      <div>
        <el-button link :icon="ArrowLeft" @click="router.push('/runtime')">{{ tUi('返回运行总览') }}</el-button>
        <h2>{{ tUi('服务日志') }}</h2>
        <p v-if="header || currentRow" class="logs-identity">
          <b>{{ header?.serviceName || currentRow?.serviceName }}</b>
          <span class="environment-chip" :class="`environment-chip--${header?.environmentName || currentRow?.environmentName}`">{{ environmentLabel(header?.environmentName || currentRow?.environmentName) }}</span>
          <span>{{ header?.serverName || currentRow?.serverName }}</span>
          <template v-if="header?.containerName"><span class="inline-separator">/</span><code>{{ header.containerName }}</code><span class="container-state" :class="`is-${currentContainer?.state}`">{{ currentContainer?.state }}</span></template>
        </p>
        <p v-else>{{ tUi('选择服务节点，查看应用日志或容器标准输出。') }}</p>
      </div>
    </div>

    <section class="panel logs-panel">
      <div class="logs-toolbar">
        <div class="logs-toolbar-row">
          <el-select :model-value="targetKey" filterable :placeholder="tUi('选择服务节点')" class="logs-node-select" :aria-label="tUi('服务节点')" @update:model-value="selectTarget">
            <el-option-group v-for="group in nodeGroups" :key="group.label" :label="group.label">
              <el-option v-for="row in group.rows" :key="row.key" :value="`${row.environmentId}:${row.serverId}`" :label="`${row.serviceName} · ${environmentLabel(row.environmentName)} · ${row.serverName}`">
                <span class="node-option"><span class="environment-chip" :class="`environment-chip--${row.environmentName}`">{{ environmentLabel(row.environmentName) }}</span>{{ row.serverName }}<small>{{ row.host }}</small></span>
              </el-option>
            </el-option-group>
          </el-select>
          <el-select :model-value="source" class="logs-source-select" :aria-label="tUi('日志来源')" @update:model-value="selectSource">
            <el-option value="application" :label="tUi('应用日志')"/>
            <el-option value="stdout" :label="tUi('容器标准输出')"/>
          </el-select>
          <el-select v-if="actualSource === 'application' && header?.files?.length" :model-value="header.fileId" class="logs-file-select" filterable :aria-label="tUi('日志文件')" @update:model-value="selectFile">
            <el-option v-for="file in header.files" :key="file.id" :value="file.id" :label="file.name"/>
          </el-select>
          <el-select v-if="actualSource === 'stdout' && (header?.containers.length || 0) > 1" :model-value="header?.containerId" class="logs-container-select" :aria-label="tUi('容器')" @update:model-value="selectContainer">
            <el-option v-for="container in header?.containers" :key="container.id" :value="container.id" :label="container.name || container.id.slice(0, 12)">
              <span class="node-option">{{ container.name || container.id.slice(0, 12) }}<span class="container-state" :class="`is-${container.state}`">{{ container.state }}</span></span>
            </el-option>
          </el-select>
          <div class="logs-live">
            <span class="logs-status" :class="`is-${phase}`" role="status"><i></i>{{ statusText }}</span>
            <el-switch v-model="live" :disabled="!targetKey" inline-prompt :active-text="tUi('实时')" :inactive-text="tUi('暂停')" :aria-label="tUi('实时跟踪')"/>
            <el-button v-if="!live" :icon="Refresh" :loading="phase === 'loading'" :disabled="!targetKey" @click="loadSnapshot">{{ tUi('刷新') }}</el-button>
          </div>
        </div>
        <div class="logs-source-info" :aria-label="tUi('当前日志来源')">
          <span>{{ actualSource === 'application' ? tUi('日志路径') : tUi('当前来源') }}</span><code>{{ sourcePath }}</code>
          <span v-if="header?.fallbackReason" class="logs-warning">{{ header.fallbackReason }}</span>
        </div>
        <div class="logs-toolbar-row">
          <div class="logs-search">
            <el-input ref="searchInput" v-model="query" :prefix-icon="Search" clearable :placeholder="tUi('搜索日志（按 / 聚焦，回车下一个）')" :aria-label="tUi('搜索日志')" @keydown="onSearchKey"/>
            <span v-if="trimmedQuery" class="search-count">{{ matchIds.length ? `${activeMatchIndex + 1 || '–'} / ${matchIds.length}` : tUi('无匹配') }}</span>
            <el-button-group v-if="trimmedQuery">
              <el-button :icon="ArrowUp" :disabled="!matchIds.length" :aria-label="tUi('上一个匹配')" @click="stepMatch(-1)"/>
              <el-button :icon="ArrowDown" :disabled="!matchIds.length" :aria-label="tUi('下一个匹配')" @click="stepMatch(1)"/>
            </el-button-group>
            <el-checkbox v-model="onlyMatches" :disabled="!trimmedQuery">{{ tUi('只看匹配行') }}</el-checkbox>
          </div>
          <el-radio-group v-model="level" size="small" :aria-label="tUi('日志级别')">
            <el-radio-button value="all">{{ tUi('全部') }}</el-radio-button><el-radio-button value="warn">{{ tUi('警告及以上') }}</el-radio-button><el-radio-button value="error">{{ tUi('仅错误') }}</el-radio-button>
          </el-radio-group>
          <div class="logs-range">
            <el-select v-model="tail" size="small" :aria-label="tUi('读取行数')" class="range-select"><el-option v-for="value in TAIL_OPTIONS" :key="value" :value="value" :label="tUi('最近 {0} 行', value)"/></el-select>
            <el-select v-model="since" size="small" :aria-label="tUi('时间范围')" :placeholder="tUi('不限时间')" class="range-select"><el-option v-for="option in SINCE_OPTIONS" :key="option.value" :value="option.value" :label="option.label"/></el-select>
          </div>
          <div class="logs-view-toggles">
            <el-checkbox v-model="showTime" size="small">{{ tUi('时间') }}</el-checkbox>
            <el-checkbox v-model="wrap" size="small">{{ tUi('自动换行') }}</el-checkbox>
            <el-tooltip :content="tUi('下载当前已读取的日志')" placement="top"><el-button size="small" :icon="Download" :disabled="!entries.length" :aria-label="tUi('下载日志')" @click="download"/></el-tooltip>
            <el-tooltip :content="tUi('复制当前显示的行')" placement="top"><el-button size="small" :icon="CopyDocument" :disabled="!visible.length" :aria-label="tUi('复制日志')" @click="copyVisible"/></el-tooltip>
            <el-tooltip :content="tUi('清空视图（不影响服务器上的日志）')" placement="top"><el-button size="small" :icon="Delete" :disabled="!entries.length" :aria-label="tUi('清空视图')" @click="clearView"/></el-tooltip>
            <el-tooltip :content="fullscreen ? tUi('退出全屏（Esc）') : tUi('全屏查看')" placement="top"><el-button size="small" :icon="fullscreen ? Close : FullScreen" :aria-label="fullscreen ? tUi('退出全屏') : tUi('全屏查看')" @click="fullscreen = !fullscreen"/></el-tooltip>
          </div>
        </div>
      </div>

      <div class="logs-console-wrap">
        <div ref="viewport" class="logs-console" :class="{ wrap, 'no-time': !showTime }" tabindex="0" :aria-label="tUi('服务日志内容')" @scroll.passive="onScroll">
          <div v-if="entries.length && entries[0].ts" class="logs-earlier">
            <span v-if="trimmed">{{ tUi('视图仅保留最近 {0} 行，已移除较早的 {1} 行', MAX_ENTRIES.toLocaleString(currentLocale()), trimmed.toLocaleString(currentLocale())) }}</span>
            <span v-else-if="truncatedEarly">{{ tUi('部分较早日志超出单次读取大小，已省略') }}</span>
            <el-button v-if="!trimmed" link size="small" :loading="loadingEarlier" :disabled="noEarlier" @click="loadEarlier">{{ noEarlier ? tUi('没有更早的日志') : tUi('加载更早的 {0} 行', tail) }}</el-button>
          </div>
          <div v-for="chunk in chunks" :key="chunk.key" v-memo="[chunk.key, trimmedQuery, chunk.active && activeId]" class="logs-chunk" :style="{ containIntrinsicSize: `auto ${chunk.entries.length * 20}px` }">
            <div v-for="entry in chunk.entries" :key="entry.id" class="log-line" :class="[entry.level && `is-${entry.level}`, { 'is-continuation': entry.continuation, 'is-active': entry.id === activeId }]" :data-id="entry.id">
              <time class="log-time" :datetime="entry.ts || undefined" :title="entry.ts || undefined"><span class="full-clock">{{logTime(entry.ts)}}</span><span class="compact-clock">{{logTime(entry.ts).slice(6,14)}}</span></time>
              <span class="log-text"><template v-for="(part, index) in segments(entry.text, trimmedQuery)" :key="index"><mark v-if="part.hit">{{ part.text }}</mark><template v-else>{{ part.text }}</template></template></span>
            </div>
          </div>
          <div v-if="!entries.length" class="logs-empty">
            <template v-if="!targetKey"><b>{{ tUi('选择要查看的服务节点') }}</b><span>{{ rowsError || tUi('在上方选择服务与节点，平台会读取该服务的最新日志。') }}</span></template>
            <template v-else-if="phase === 'error'"><b>{{ tUi('日志读取失败') }}</b><span>{{ error }}</span><el-button size="small" @click="live ? reload() : (live = true)">{{ tUi('重新连接') }}</el-button></template>
            <template v-else-if="busy || phase === 'reconnecting'"><b>{{ statusText }}</b><span>{{ tUi('首次连接经 IAP 的节点可能需要十几秒。') }}</span></template>
            <template v-else><b>{{ tUi('这段时间没有日志输出') }}</b><span>{{ tUi('可以扩大时间范围或读取行数。') }}</span></template>
          </div>
          <div v-else-if="!visible.length" class="logs-empty"><b>{{ tUi('没有符合筛选条件的行') }}</b><span>{{ tUi('调整级别或关闭“只看匹配行”。') }}</span></div>
        </div>
        <button v-if="!follow && entries.length" type="button" class="logs-jump" @click="scrollToBottom"><el-icon><Bottom/></el-icon>{{ unseen ? tUi('{0} 条新日志', unseen > 999 ? '999+' : unseen) : tUi('回到最新') }}</button>
      </div>

      <div class="logs-footer">
        <span>{{ tUi('共 {0} 行', entries.length.toLocaleString(currentLocale())) }}<template v-if="visible.length !== entries.length">{{ tUi('· 显示 {0} 行', visible.length.toLocaleString(currentLocale())) }}</template></span>
        <button v-if="errorIds.length" type="button" class="logs-error-count" @click="nextError">{{ tUi('错误 {0} 处 · 跳到下一处', errorIds.length) }}</button>
        <span v-if="dropped" class="logs-warning">{{ tUi('输出过快，已跳过 {0} 行', dropped.toLocaleString(currentLocale())) }}</span>
        <span v-if="notice" class="logs-notice">{{ notice }}<el-button link type="primary" size="small" @click="live = true">{{ tUi('继续跟踪') }}</el-button></span>
        <span v-if="error && entries.length" class="logs-warning">{{ error }}<el-button link type="primary" size="small" @click="live ? reload() : (live = true)">{{ tUi('重新连接') }}</el-button></span>
        <span class="logs-hint">{{ tUi('{0} · 时间为本地时区', actualSource === 'application' ? tUi('应用日志文件') : tUi('容器标准输出')) }}</span>
      </div>
    </section>
  </div>
</template>

<style scoped>
.logs-page{--log-bg:#0e1621;--log-fg:#cdd6e4;--log-dim:#5d6e86;--log-line:#18222f}
.logs-head{margin-bottom:18px}.logs-head h2{margin:6px 0 8px}.logs-identity{display:flex;align-items:center;flex-wrap:wrap;gap:8px;color:#52647c}.logs-identity b{color:#29364b;font-size:14px}.logs-identity code{font:12px ui-monospace,"Cascadia Code",Consolas,monospace;color:#3d5170}
.environment-chip{display:inline-block;flex-shrink:0;border-radius:4px;background:#f0f3f8;color:#6c7f9e;padding:2px 6px;font-size:10px;line-height:1.6}.environment-chip--test{background:#f1effa;color:#8471a9}.environment-chip--prod{background:#fff3e8;color:#ad7c4d}
.container-state{display:inline-block;padding:0 6px;border-radius:9px;font-size:10px;line-height:17px;background:#eef1f6;color:#6c7f9e}.container-state.is-running{background:#e8f6ef;color:#2b8a63}.container-state.is-exited,.container-state.is-dead{background:#fdeeee;color:#c2414b}.container-state.is-restarting{background:#fff6e6;color:#a8741f}
.logs-panel{padding:0;overflow:hidden}
.logs-toolbar{display:grid;gap:12px;padding:16px 18px;border-bottom:1px solid #e8edf4;background:#fbfcfe}
.logs-toolbar-row{display:flex;align-items:center;flex-wrap:wrap;gap:10px 14px}
.logs-node-select{width:min(420px,100%)}.logs-container-select{width:220px}.logs-source-select{width:150px}.logs-file-select{width:260px}
.logs-source-info{display:flex;align-items:center;flex-wrap:wrap;gap:6px 10px;font-size:12px;color:#74849a}.logs-source-info code{font:12px ui-monospace,"Cascadia Code",Consolas,monospace;color:#52647c;overflow-wrap:anywhere}
.node-option{display:flex;align-items:center;gap:8px;min-width:0}.node-option small{margin-left:auto;padding-left:12px;color:#94a0b2;font-size:11px}
.logs-live{display:flex;align-items:center;gap:12px;margin-left:auto}
.logs-status{display:inline-flex;align-items:center;gap:7px;font-size:12px;color:#74849a;white-space:nowrap}.logs-status i{width:7px;height:7px;border-radius:50%;background:#b6c0cf}
.logs-status.is-live{color:#2b8a63}.logs-status.is-live i{background:#2fb37a;box-shadow:0 0 0 0 rgba(47,179,122,.5);animation:pulse 1.8s infinite}
.logs-status.is-connecting i,.logs-status.is-loading i,.logs-status.is-reconnecting i{background:#e0a336}.logs-status.is-reconnecting{color:#a8741f}.logs-status.is-error{color:#c2414b}.logs-status.is-error i{background:#d4535c}
@keyframes pulse{0%{box-shadow:0 0 0 0 rgba(47,179,122,.45)}70%{box-shadow:0 0 0 7px rgba(47,179,122,0)}100%{box-shadow:0 0 0 0 rgba(47,179,122,0)}}
.logs-search{display:flex;align-items:center;gap:8px;flex:1 1 360px;min-width:260px}.logs-search .el-input{flex:1}.search-count{font-size:12px;color:#74849a;font-variant-numeric:tabular-nums;white-space:nowrap}
.logs-range{display:flex;gap:8px}.range-select{width:128px}
.logs-view-toggles{display:flex;align-items:center;gap:6px;margin-left:auto}.logs-view-toggles .el-checkbox{margin-right:6px}
.logs-console-wrap{position:relative}
.logs-console{height:calc(100vh - 360px);min-height:380px;overflow:auto;background:var(--log-bg);color:var(--log-fg);font:12px/20px ui-monospace,"Cascadia Code","JetBrains Mono",Consolas,monospace;padding:8px 0 14px;outline:none;scrollbar-color:#34445a var(--log-bg)}
.logs-console:focus-visible{box-shadow:inset 0 0 0 2px #5b7fe0}
.logs-chunk{content-visibility:auto}
.log-line{display:flex;gap:14px;padding:0 16px 0 13px;border-left:3px solid transparent;white-space:pre;min-width:max-content}
.logs-console.wrap .log-line{white-space:pre-wrap;min-width:0}.logs-console.wrap .log-text{overflow-wrap:anywhere;min-width:0}
.log-line:hover{background:var(--log-line)}
.log-time{flex:0 0 auto;color:var(--log-dim);user-select:none}.logs-console.no-time .log-time{display:none}
.log-text{flex:1 1 auto}
.log-line.is-error{background:rgba(239,68,68,.09);border-left-color:#f26d6d}.log-line.is-error .log-text{color:#ffb3b3}
.log-line.is-warn{background:rgba(234,179,8,.07);border-left-color:#e3b341}.log-line.is-warn .log-text{color:#f4d58d}
.log-line.is-debug .log-text{color:#8394ad}
.log-line.is-continuation .log-text{padding-left:2ch}.log-line.is-continuation.is-error .log-text{color:#e79b9b}
.log-line.is-active{background:rgba(91,127,224,.22);border-left-color:#7c9cf0}
.log-text mark{background:#f5c84c;color:#1a1300;border-radius:2px;padding:0 1px}.log-line.is-active mark{background:#ffe08a;outline:1px solid #fff3c4}
.logs-earlier{display:flex;align-items:center;justify-content:center;gap:12px;padding:4px 16px 10px;color:var(--log-dim);font-size:11px;font-family:Inter,"PingFang SC","Microsoft YaHei",sans-serif}.logs-earlier .el-button{color:#8fb0ff}
.logs-empty{display:grid;justify-items:center;gap:8px;padding:90px 20px;text-align:center;color:#8a9ab2;font-family:Inter,"PingFang SC","Microsoft YaHei",sans-serif;font-size:12px}.logs-empty b{color:#d5deeb;font-size:14px;font-weight:600}.logs-empty span{max-width:460px;line-height:1.8}
.logs-jump{position:absolute;right:22px;bottom:18px;display:inline-flex;align-items:center;gap:6px;padding:7px 13px;border:1px solid #3a4d68;border-radius:16px;background:#1d2a3b;color:#dbe4f2;font-size:12px;cursor:pointer;box-shadow:0 6px 18px rgba(0,0,0,.35)}.logs-jump:hover{background:#26364c}
.logs-footer{display:flex;align-items:center;flex-wrap:wrap;gap:8px 18px;padding:10px 18px;border-top:1px solid #e8edf4;font-size:12px;color:#74849a;background:#fbfcfe}
.logs-error-count{border:0;background:#fdeeee;color:#c2414b;border-radius:10px;padding:2px 10px;font-size:12px;cursor:pointer}.logs-error-count:hover{background:#fbe0e2}
.logs-warning{color:#a8741f}.logs-notice{color:#52647c}.logs-hint{margin-left:auto;color:#a1acbc}
.logs-page.is-fullscreen .logs-panel{position:fixed;inset:0;z-index:2000;border-radius:0;display:flex;flex-direction:column;margin:0}
.logs-page.is-fullscreen .logs-console-wrap{flex:1;min-height:0}.logs-page.is-fullscreen .logs-console{height:100%}
@media(max-width:900px){.logs-live,.logs-view-toggles{margin-left:0}.logs-hint{display:none}.logs-console{height:calc(100vh - 430px)}}
@media(max-width:650px){.logs-toolbar{padding:14px}.logs-node-select,.logs-container-select,.logs-file-select{width:100%}.logs-search{min-width:0;flex-basis:100%}.logs-range{width:100%}.range-select{flex:1}.logs-console{min-height:320px;font-size:11px;line-height:18px}.log-line{gap:10px;padding:0 10px}}
@media(prefers-reduced-motion:reduce){.logs-status.is-live i{animation:none}}
.logs-console-wrap,.logs-console{min-width:0;max-width:100%}
.logs-console{tab-size:4;overscroll-behavior-x:contain}
.logs-console:not(.wrap) .logs-chunk{content-visibility:visible;min-width:max-content}
.logs-console.wrap .log-line{display:grid;grid-template-columns:18.5ch minmax(0,1fr);box-sizing:border-box;width:100%;align-items:start}
.logs-console.wrap.no-time .log-line{grid-template-columns:minmax(0,1fr)}
.logs-console.wrap .log-text{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.65;min-height:1.65em}
.compact-clock{display:none}
@media(max-width:650px){
  .logs-console{font-size:12px;line-height:1.65}
  .logs-console.wrap .log-line{grid-template-columns:8.5ch minmax(0,1fr);gap:7px;padding-inline:8px}
  .logs-console.wrap.no-time .log-line{grid-template-columns:minmax(0,1fr)}
  .log-time .full-clock{display:none}
  .log-time .compact-clock{display:inline}
}
</style>
