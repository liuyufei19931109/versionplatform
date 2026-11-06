<script setup lang="ts">
import { tUi } from '../i18n'

import { computed } from 'vue'
import { environmentLabel, formatTime, statusLabel } from '../ui'
import type { DeploymentServer } from '../types'
import type { HistoryItem, ReleaseStamp } from '../composables/useDeploymentReview'

const props = defineProps<{ entries?: HistoryItem[]; loading: boolean; error: string; servers: DeploymentServer[] }>()
defineEmits<{ retry: [] }>()
const environments = ['dev', 'test', 'prod'] as const
const items = computed(() => environments.map(environment => {
  const history = props.entries?.find(entry => entry.environment === environment)
  const latest = history?.latestAttempt
  const success = history?.latestSuccess
  const label = latest && latest.status !== 'SUCCESS' ? (success ? tUi('已发 · ') : '') + statusLabel(latest.status) : success ? tUi('已发') : tUi('暂无记录')
  return { environment, history, label }
}))

function scope(task: ReleaseStamp) {
  if (!task.scopeKnown || !task.serverIds.length) return tUi('节点范围待核对')
  return task.serverIds.map(id => props.servers.find(server => String(server.id) === String(id))?.name || tUi('节点 #') + id).join('、')
}
</script>

<template>
  <div class="environment-history" :aria-label="tUi('制品发布历史')" @click.stop>
    <span v-if="loading" class="history-message" role="status">{{ tUi('正在读取发布历史…') }}</span>
    <span v-else-if="error" class="history-message">{{ tUi('发布历史暂时无法读取') }} <button type="button" @click.stop="$emit('retry')">{{ tUi('重读') }}</button></span>
    <template v-else v-for="item in items" :key="item.environment">
      <el-popover v-if="item.history" placement="top-start" trigger="click" :width="280">
        <template #reference><button type="button" class="history-env" :class="{success:item.history.latestSuccess}" :aria-label="environmentLabel(item.environment)+tUi('发布历史，')+item.label">{{environmentLabel(item.environment)}} <b>{{item.label}}</b></button></template>
        <div class="history-detail">
          <strong>{{ tUi('{0}发布历史', environmentLabel(item.environment)) }}</strong>
          <template v-if="item.history.latestSuccess">
            <div><span>{{ tUi('最近成功') }}</span><router-link :to="'/releases/'+item.history.latestSuccess.taskId">{{ tUi('任务 #{0}', item.history.latestSuccess.taskId) }}</router-link></div>
            <p>{{formatTime(item.history.latestSuccess.at)}} · {{item.history.latestSuccess.actor || tUi('发起人未记录')}}</p>
            <p>{{scope(item.history.latestSuccess)}}</p>
          </template>
          <p v-else>{{ tUi('暂无成功发布记录') }}</p>
          <template v-if="item.history.latestAttempt && item.history.latestAttempt.taskId!==item.history.latestSuccess?.taskId">
            <div><span>{{ tUi('最近尝试 · {0}', statusLabel(item.history.latestAttempt.status)) }}</span><router-link :to="'/releases/'+item.history.latestAttempt.taskId">{{ tUi('任务 #{0}', item.history.latestAttempt.taskId) }}</router-link></div>
            <p>{{formatTime(item.history.latestAttempt.at)}} · {{item.history.latestAttempt.actor || tUi('发起人未记录')}}</p>
            <p>{{scope(item.history.latestAttempt)}}</p>
          </template>
        </div>
      </el-popover>
      <span v-else class="history-env empty-env">{{ tUi('{0} 暂无记录', environmentLabel(item.environment)) }}</span>
    </template>
  </div>
</template>

<style scoped>
.environment-history{display:flex;flex-wrap:wrap;gap:3px 8px;align-items:center;min-width:0;line-height:1.45}
.history-env{padding:0;font:inherit;font-size:11px;color:#5c6b81;border:0;background:transparent;white-space:nowrap}
button.history-env,.history-message button{cursor:pointer;text-decoration:underline;text-underline-offset:3px;text-decoration-color:#b8c4d2}
.history-env b{font-weight:600}.history-env.success{color:#176952}.empty-env{color:#788597}
.history-message{font-size:11px;color:#66758a}.history-message button{border:0;background:none;color:#315fce;font:inherit;padding:1px 4px}
.history-detail{font-size:12px;line-height:1.55;overflow-wrap:anywhere;color:#52647a}
.history-detail strong{color:#203149}.history-detail div{display:flex;flex-wrap:wrap;justify-content:space-between;gap:5px;margin-top:10px}
.history-detail p{margin:4px 0}.history-detail a{color:#315fce}
</style>

