<script setup lang="ts">
import { tUi } from '../i18n'

import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useArtifactHistory } from '../composables/useDeploymentReview'

const props = defineProps<{ serviceId: number | string; artifactId: number | string }>()
const router = useRouter()
const { byArtifact, loading, error } = useArtifactHistory(computed(() => Number(props.serviceId)))
const production = computed(() => byArtifact.value.get(Number(props.artifactId))?.find(entry => entry.environment === 'prod'))
const pending = computed(() => {
  const attempt = production.value?.latestAttempt
  return attempt && ['RUNNING', 'PENDING'].includes(attempt.status) ? attempt : null
})
function chooseTargets() {
  router.push({ path: '/release/new', query: { serviceId: String(props.serviceId), artifactId: String(props.artifactId), environment: 'prod' } })
}
</script>

<template>
  <span class="production-actions">
    <el-button v-if="pending" type="primary" @click="router.push('/releases/'+pending.taskId)">{{ tUi('查看生产任务 #{0}', pending.taskId) }}</el-button>
    <el-button v-else-if="production?.latestSuccess" type="primary" @click="router.push('/releases/'+production.latestSuccess.taskId)">{{ tUi('查看生产记录 #{0}', production.latestSuccess.taskId) }}</el-button>
    <el-button :link="Boolean(pending || production?.latestSuccess)" :loading="loading" type="primary" @click="chooseTargets">{{pending?tUi('选择其他生产节点'):production?.latestSuccess?tUi('再次发布到生产'):error?tUi('核对并发布到生产'):tUi('发布到生产')}}</el-button>
  </span>
</template>

<style scoped>
.production-actions{display:inline-flex;gap:9px;align-items:center;flex-wrap:wrap}
.production-actions .el-button+.el-button{margin-left:0}
</style>

