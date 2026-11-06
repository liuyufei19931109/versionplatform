<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { http, payload } from '../api'
import { deploymentTargetIds, failedTargetId } from '../taskFailure'
import type { Release } from '../types'
import type { RuntimeSnapshot } from '../runtime'

const props = defineProps<{ task: Release }>()
const router = useRouter()
const choosing = ref(false)
const names = ref(new Map<string, string>())
const targets = computed(() => deploymentTargetIds(props.task))
const failed = computed(() => failedTargetId(props.task))

function open(id: string) {
  if (!targets.value.includes(id)) return
  choosing.value = false
  void router.push({ path: '/runtime/logs', query: {
    serviceId: String(props.task.serviceId), environment: props.task.environment, serverId: id,
  } })
}
async function choose() {
  const direct = failed.value || (targets.value.length === 1 ? targets.value[0] : undefined)
  if (direct) return open(direct)
  choosing.value = true
  try {
    const snapshot = payload<RuntimeSnapshot>(await http.get('/runtime'), {} as RuntimeSnapshot)
    names.value = new Map((snapshot.rows || [])
      .filter(row => String(row.serviceId) === String(props.task.serviceId) && row.environmentName === props.task.environment && row.serverId)
      .map(row => [String(row.serverId), row.serverName || '']))
  } catch { /* Historical target IDs stay available if names cannot be resolved. */ }
}
</script>

<template>
  <el-button v-if="targets.length" @click="choose">{{failed ? '查看失败节点 #' + failed + ' 日志' : '查看服务日志'}}</el-button>
  <el-dialog v-model="choosing" title="选择要查看日志的目标节点" width="min(430px, 94vw)" append-to-body>
    <p v-if="task.status === 'FAILED'" class="node-hint">这条记录未标明失败节点，请选择本次发布的目标机器。</p>
    <div class="log-node-list">
      <el-button v-for="id in targets" :key="id" @click="open(id)"><b>节点 #{{id}}</b><span>{{names.get(id)}}</span></el-button>
    </div>
  </el-dialog>
</template>

<style scoped>
.node-hint{margin:0 0 14px;color:#53647a;font-size:13px;line-height:1.6}
.log-node-list{display:grid;gap:8px}
.log-node-list .el-button{margin:0;justify-content:flex-start;height:auto;min-height:38px;white-space:normal;text-align:left}
.log-node-list span{overflow-wrap:anywhere}
.log-node-list b{margin-right:9px}
</style>
