<script setup lang="ts">
import { tUi } from '../i18n'

import { computed, nextTick, ref, watch } from 'vue'
import { CopyDocument, Bottom, Check } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { Release, ReleaseStep } from '../types'
import { statusLabel, statusType } from '../ui'
import { copyLogText, readLogWrap, saveLogWrap } from '../logReading'

const props = defineProps<{ task: Release; steps: ReleaseStep[]; fallbackLog: string }>()
const filter = ref<'all'|'failed'>('all')
const panel = ref<HTMLElement>()
const output = ref<HTMLElement>()
const wrapLogs = ref(readLogWrap()), copied = ref(false)
const failedSteps = computed(() => props.steps.filter(step => step.status === 'FAILED'))
const failedLog = computed(() => failedSteps.value.map(step => `【${step.name || step.code || tUi('失败步骤')}】\n${step.log || step.logText || tUi('该步骤没有输出日志。')}`).join('\n') || String(props.task.errorMessage || tUi('当前没有独立的失败步骤，请查看全部日志。')))
const visibleLog = computed(() => props.task.status === 'FAILED' && filter.value === 'failed' ? failedLog.value : props.fallbackLog)
// Copy backend output verbatim; translated step labels and empty-state messages are UI only.
const copyableLog = computed(() => {
  if (props.task.status !== 'FAILED' || filter.value !== 'failed') return props.task.rawLog || ''
  return failedSteps.value.map(step => step.rawLog).filter(Boolean).join('\n') || String(props.task.errorMessage || '')
})
// Bound the DOM for multi-megabyte compiler output. Copy uses the complete unwrapped text.
const linesPerPage = 500
const page = ref(1)
const lines = computed(() => visibleLog.value.split(/\r\n|\r|\n/))
const pageCount = computed(() => Math.max(1, Math.ceil(lines.value.length / linesPerPage)))
const firstLine = computed(() => (page.value - 1) * linesPerPage)
const pageLines = computed(() => lines.value.slice(firstLine.value, firstLine.value + linesPerPage))
watch(filter, () => { page.value = 1; output.value?.scrollTo({ top: 0 }) }, { flush: 'sync' })
watch(pageCount, count => { if (page.value > count) page.value = count }, { flush: 'sync' })
watch(wrapLogs, saveLogWrap)
function changePage(next: number) {
  page.value = Math.min(pageCount.value, Math.max(1, next))
  output.value?.scrollTo({ top: 0, left: 0 })
}
async function scrollToEnd() {
  page.value = pageCount.value
  await nextTick()
  output.value?.scrollTo({ top: output.value.scrollHeight, behavior: 'auto' })
}
function lineTone(value: string) {
  if (/^【[^】\n]+】$/.test(value)) return 'is-step'
  if (/^(?:\s*\[ERROR\]|失败：|\s*(?:error|fatal):)/i.test(value)) return 'is-error'
  if (/^\s*\[(?:WARN|WARNING)\]/i.test(value)) return 'is-warning'
  return ''
}
async function copyLog() {
  try {
    await copyLogText(copyableLog.value)
    copied.value = true; ElMessage.success(tUi('已复制当前日志'))
  } catch { ElMessage.warning(tUi('复制失败，请在日志中选择文本复制')) }
}
async function showFailure() {
  filter.value = 'failed'
  await nextTick()
  panel.value?.scrollIntoView({ block: 'start', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
  panel.value?.focus({ preventScroll: true })
}
defineExpose({ showFailure })
</script>

<template>
  <section ref="panel" class="panel log-panel task-logs" tabindex="-1" :aria-label="tUi('执行日志')">
    <div class="panel-title"><div><h3>{{ tUi('执行日志') }}</h3><span class="panel-subtitle">{{ tUi('按步骤记录任务输出') }}</span></div><el-tag :type="statusType(task.status)" size="small">{{statusLabel(task.status)}}</el-tag></div>
    <div v-if="task.status === 'FAILED'" class="log-filter" role="group" :aria-label="tUi('日志范围')">
      <button type="button" :aria-pressed="filter === 'all'" :class="{active:filter === 'all'}" @click="filter = 'all'">{{ tUi('全部步骤') }}</button>
      <button type="button" :aria-pressed="filter === 'failed'" :class="{active:filter === 'failed'}" @click="filter = 'failed'">{{ tUi('失败步骤') }}</button>
      <span>{{ tUi('保留完整日志，可切换查看上下文') }}</span>
    </div>
    <div class="log-toolbar"><span>{{ tUi('任务 #{0}', task.id) }}<span v-if="task.status==='RUNNING'" class="log-updating">{{ tUi('持续更新') }}</span></span><div><button type="button" :class="{active:wrapLogs}" :aria-pressed="wrapLogs" @click="wrapLogs=!wrapLogs">{{ tUi('自动换行') }}</button><button type="button" @click="copyLog" :disabled="!copyableLog" @mouseleave="copied=false" @blur="copied=false"><el-icon><Check v-if="copied"/><CopyDocument v-else/></el-icon>{{copied?tUi('已复制'):tUi('复制日志')}}</button><button type="button" @click="scrollToEnd"><el-icon><Bottom/></el-icon>{{ tUi('跳至末尾') }}</button></div></div>
    <div v-if="pageCount > 1" class="log-pages">
      <span>{{ tUi('{0}–{1} 行 / 共 {2} 行', firstLine + 1, firstLine + pageLines.length, lines.length) }}</span>
      <button type="button" :disabled="page <= 1" @click="changePage(page - 1)">{{ tUi('上一段') }}</button>
      <button type="button" :disabled="page >= pageCount" @click="changePage(page + 1)">{{ tUi('下一段') }}</button>
    </div>
    <div ref="output" class="log-box" :class="{'log-box--nowrap':!wrapLogs}" tabindex="0" :aria-label="tUi('任务日志输出')">
      <div class="log-lines">
        <div v-for="(line, index) in pageLines" :key="firstLine + index" class="task-log-line" :class="lineTone(line)">
          <span class="line-number" aria-hidden="true">{{firstLine + index + 1}}</span>
          <span class="line-content">{{line}}</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.log-toolbar{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;margin:0 0 10px;color:#607087;font-size:11px}.log-toolbar>span{font-variant-numeric:tabular-nums}.log-toolbar>div{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.log-toolbar button{display:inline-flex;align-items:center;gap:5px;border:1px solid #dce3ec;border-radius:6px;padding:6px 9px;background:#fff;color:#607087;font:inherit;cursor:pointer;line-height:1.4}.log-toolbar button:hover{background:#f3f5f8;color:#203149}.log-toolbar button.active{color:#315fce;background:#edf2ff;border-color:#cfdbf4}.log-toolbar button:focus-visible{outline:2px solid #315fce;outline-offset:2px}.log-updating{color:#168269;margin-left:12px}.log-box.log-box--nowrap{white-space:pre;overflow-wrap:normal}.log-box:focus-visible{outline:2px solid #315fce;outline-offset:3px}
@media(max-width:600px){.log-toolbar>div{width:100%}.log-toolbar button{flex:1;justify-content:center;white-space:nowrap}}
.task-logs{scroll-margin-top:136px}.task-logs:focus-visible{outline:2px solid #416bdc;outline-offset:3px}.log-filter{display:flex;gap:6px;align-items:center;flex-wrap:wrap;margin:0 0 14px}.log-filter button{font:inherit;font-size:12px;padding:7px 12px;border:1px solid #dce5f1;border-radius:5px;background:#fff;color:#667a95;cursor:pointer}.log-filter button.active{background:#edf3fd;border-color:#b8cbed;color:#355fae}.log-filter button:focus-visible{outline:2px solid #416bdc;outline-offset:2px}.log-filter span{font-size:11px;color:#74849a;margin-left:8px}
.task-logs,.log-box{max-width:100%;min-width:0}
.log-box{padding:10px 0 16px;tab-size:4;line-height:1.7;overscroll-behavior-x:contain}
.task-log-line{display:grid;grid-template-columns:6ch minmax(0,1fr);gap:12px;padding:2px 14px 2px 0;align-items:start}
.line-number{color:#a5bace;border-right:1px solid #415a71;text-align:right;padding-right:1ch;user-select:none;font-variant-numeric:tabular-nums}
.line-content{min-width:0;min-height:1.7em}
.task-log-line.is-step{color:#a9d2ff;background:#1d405f;font-weight:650}
.task-log-line.is-error{color:#ffc4c8;background:#362e43}
.task-log-line.is-warning{color:#f6d88a}
.log-box--nowrap .log-lines{width:max-content;min-width:100%}
.log-pages{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin:0 0 10px;font-size:12px;color:#53657c}
.log-pages button{font:inherit;border:1px solid #d6dfea;background:white;color:#315fce;border-radius:5px;padding:3px 8px;cursor:pointer}
.log-pages button:disabled{color:#7d8794;cursor:default}
@media(max-width:650px){.task-log-line{grid-template-columns:4.5ch minmax(0,1fr);gap:8px;padding-right:8px}.log-box{font-size:12px}}
</style>
