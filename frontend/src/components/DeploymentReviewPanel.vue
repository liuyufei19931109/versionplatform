<script setup lang="ts">
import { tUi } from '../i18n'

import { computed } from 'vue'
import { environmentLabel, formatTime, statusLabel } from '../ui'
import type { DeploymentServer } from '../types'
import type { DeploymentReview } from '../composables/useDeploymentReview'

const props = defineProps<{ review: DeploymentReview | null; environment: string; servers: DeploymentServer[]; loading?: boolean; error?: string }>()
defineEmits<{ retry: [] }>()
const nodeName = (id: number) => props.servers.find(server => String(server.id) === String(id))?.name || tUi('节点 #') + id
const stateLabel = { get FIRST() { return tUi('暂无成功记录') }, get CHANGE() { return tUi('将更新版本') }, get REPEAT() { return tUi('最近成功发布的是此包') }, get ROLLBACK() { return tUi('将切回此包') }, get UNKNOWN() { return tUi('发布状态待核对') } }
const remaining = computed(() => {
  const review = props.review
  if (review?.action !== 'ACTIVE' || review.activeTasks.some(task => !task.scopeKnown)) return []
  const busy = new Set(review.activeTasks.flatMap(task => task.serverIds))
  return review.nodes.filter(node => !busy.has(node.serverId)).map(node => nodeName(node.serverId))
})
const title = computed(() => {
  if (!props.review) return ''
  if (props.review.action === 'ACTIVE') return tUi('这个包已有任务涉及所选节点')
  if (props.review.nodes.some(node => node.state === 'ROLLBACK')) return tUi('本次会将部分或全部节点切回历史版本')
  if (props.review.nodes.some(node => node.state === 'REPEAT')) return tUi('所选节点中已有成功发布此包的记录')
  if (props.review.action === 'CONFIRM') return tUi('请核对目标节点后再发布')
  return tUi('本次发布目标')
})
</script>

<template>
  <section class="deployment-review" :class="{warning:review?.action==='CONFIRM',active:review?.action==='ACTIVE'}" :aria-label="tUi('目标发布情况')" aria-live="polite">
    <p v-if="loading">{{ tUi('正在核对所选节点的发布情况…') }}</p>
    <p v-else-if="error" class="review-error">{{error}} <el-button link type="primary" @click="$emit('retry')">{{ tUi('重新核对') }}</el-button></p>
    <template v-else-if="review">
      <div class="review-title"><strong>{{title}}</strong><small>{{ tUi('{0} · {1} 台', environmentLabel(environment), review.nodes.length) }}</small></div>
      <div v-for="node in review.nodes" :key="node.serverId" class="review-node">
        <div><b>{{nodeName(node.serverId)}}</b><span :class="'state-'+node.state">{{stateLabel[node.state]}}</span></div>
        <p v-if="node.latestSuccess">{{ tUi('最近成功 · {0} · {1} · {2}', node.latestSuccess.buildTaskId?tUi('构建 #')+node.latestSuccess.buildTaskId:tUi('制品 #')+node.latestSuccess.artifactId, formatTime(node.latestSuccess.at), node.latestSuccess.actor || tUi('发起人未记录')) }}</p>
        <p v-if="node.state==='UNKNOWN' && node.latestAttempt">{{ tUi('待核对记录 ·') }} <router-link :to="'/releases/'+node.latestAttempt.taskId">{{ tUi('任务 #{0}', node.latestAttempt.taskId) }}</router-link> · {{statusLabel(node.latestAttempt.status)}} · {{formatTime(node.latestAttempt.at)}}</p>
      </div>
      <div v-for="task in review.activeTasks" :key="task.taskId" class="review-task">
        <router-link :to="'/releases/'+task.taskId">{{ tUi('查看任务 #{0}', task.taskId) }}</router-link>
        <span>{{statusLabel(task.status)}} · {{task.actor}} · {{task.scopeKnown ? task.serverIds.map(nodeName).join('、') : tUi('节点范围待核对')}}</span>
      </div>
      <p v-if="remaining.length" class="review-remaining">{{ tUi('可单独发布 {0}。如需补发，请在服务器列表调整勾选范围。', remaining.join('、')) }}</p>
      <el-button v-if="review.action==='ACTIVE'" link type="primary" @click="$emit('retry')">{{ tUi('重新核对任务状态') }}</el-button>
    </template>
  </section>
</template>

<style scoped>
.deployment-review{border:1px solid #dfe7ef;background:#f7f9fc;color:#334861;border-radius:8px;padding:12px 14px;font-size:12px;line-height:1.6;min-width:0}
.deployment-review.warning{background:#fff9ec;border-color:#edd6a4}.deployment-review.active{background:#eff4ff;border-color:#cddcf5}
.deployment-review>p,.review-node p{margin:0}.review-title,.review-node>div,.review-task{display:flex;gap:5px 12px;align-items:baseline;flex-wrap:wrap}.review-title{justify-content:space-between}
.review-title strong{font-size:13px}.review-title small,.review-node p,.review-task span{color:#617187;font-size:11px}.review-node{margin-top:7px;overflow-wrap:anywhere}.review-node b{font-weight:600}
.state-REPEAT,.state-UNKNOWN{color:#946116}.state-ROLLBACK,.review-error{color:#a84836}
.deployment-review .review-remaining{margin-top:8px}.review-task{margin-top:8px}.review-task a{color:#315fce;font-weight:600}.review-task span{overflow-wrap:anywhere}
</style>

