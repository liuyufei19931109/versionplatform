<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { errorText, http, payload } from '../api'
import type { RuntimeOperation } from '../runtime'
import { operationStage, operationStatus } from '../runtime'
import { environmentLabel, formatTime } from '../ui'

const props = defineProps<{ operationId?: string }>()
const emit = defineEmits<{ close: [] }>()
const operation = ref<RuntimeOperation>(), error = ref(''), loading = ref(false)
const open = computed({ get: () => Boolean(props.operationId), set: value => { if (!value) emit('close') } })
let generation = 0, disposed = false, timer: number | undefined, controller: AbortController | undefined
async function load() {
  if (timer) window.clearTimeout(timer)
  const id = props.operationId
  if (!id || !/^[1-9]\d*$/.test(id) || disposed) return
  controller?.abort()
  const current = ++generation
  controller = new AbortController()
  loading.value = true
  try {
    const data = payload<RuntimeOperation>(await http.get(`/runtime/restarts/${id}`, { signal: controller.signal }), {} as RuntimeOperation)
    if (disposed || current !== generation || id !== props.operationId) return
    if (String(data.id) !== id) throw new Error('未读取到该重启记录')
    operation.value = data; error.value = ''
  } catch (reason) {
    if (!disposed && current === generation) error.value = errorText(reason, '暂时无法读取重启记录')
  } finally {
    if (!disposed && current === generation) {
      loading.value = false
      if (!error.value && operation.value && ['PENDING','RUNNING'].includes(operation.value.status)) timer = window.setTimeout(load, 3000)
    }
  }
}
watch(() => props.operationId, () => {
  generation++; controller?.abort(); if (timer) window.clearTimeout(timer)
  operation.value = undefined; error.value = ''; loading.value = false
  if (props.operationId) void load()
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; generation++; controller?.abort(); if (timer) window.clearTimeout(timer) })
</script>

<template>
  <el-drawer v-model="open" :size="'min(680px, 100vw)'" :title="`重启记录 #${operationId}`" class="operation-detail-drawer">
    <div class="operation-detail" v-loading="loading && !operation">
      <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon><template #default>当前信息可能已过期，请重新读取。<el-button link type="primary" :loading="loading" @click="load">重新读取</el-button></template></el-alert>
      <template v-if="operation">
        <div class="operation-detail-heading"><h3>{{operation.serviceName}}</h3><el-tag :type="operation.status === 'FAILED' ? 'danger' : operation.status === 'SUCCESS' ? 'success' : 'info'">{{operationStatus(operation.status)}}</el-tag></div>
        <dl><dt>发布环境</dt><dd>{{environmentLabel(operation.environmentName)}}</dd><dt>服务器</dt><dd>{{operation.serverName}}</dd><dt>发起人</dt><dd>{{operation.createdBy}}</dd><dt>当前步骤</dt><dd>{{operationStage(operation.currentStage)}}</dd><dt>提交时间</dt><dd>{{formatTime(operation.createdAt)}}</dd><dt v-if="operation.startedAt">开始时间</dt><dd v-if="operation.startedAt">{{formatTime(operation.startedAt)}}</dd><dt v-if="operation.finishedAt">结束时间</dt><dd v-if="operation.finishedAt">{{formatTime(operation.finishedAt)}}</dd></dl>
        <p v-if="['FAILED','CANCELLED'].includes(operation.status)" class="operation-detail-hint">本次操作可能已执行部分步骤，请结合下方日志和目标容器状态判断结果。</p>
        <el-alert v-if="operation.errorMessage" :title="operation.errorMessage" type="error" :closable="false" show-icon/>
        <div class="operation-log-heading"><h4>步骤日志</h4><el-button link :loading="loading" @click="load">刷新</el-button></div><pre>{{operation.logText || '任务开始后展示日志。'}}</pre>
      </template>
      <p v-else-if="!error" class="operation-detail-hint">正在读取重启记录…</p>
    </div>
  </el-drawer>
</template>

<style scoped>
.operation-detail-heading{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:25px}.operation-detail-heading h3{font-size:20px;margin:0;color:#263b58;overflow-wrap:anywhere}.operation-detail dl{display:grid;grid-template-columns:80px minmax(0,1fr);gap:17px 16px;font-size:13px;line-height:1.6;margin-bottom:28px}.operation-detail dt{color:#74849a}.operation-detail dd{margin:0;overflow-wrap:anywhere;color:#354b68}.operation-detail-hint{font-size:12px;line-height:1.9;color:#866044}.operation-log-heading{display:flex;align-items:center;justify-content:space-between;margin-top:26px}.operation-log-heading h4{font-size:14px;color:#354b68}.operation-detail pre{background:#f6f8fc;border:1px solid #dce5f1;border-radius:6px;padding:15px;font:12px/1.75 Consolas,monospace;white-space:pre-wrap;overflow-wrap:anywhere;color:#435a76}.operation-detail .el-alert{margin-bottom:18px}
</style>
