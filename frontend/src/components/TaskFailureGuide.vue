<script setup lang="ts">
import { tUi } from '../i18n'

import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { hasMenu } from '../auth'
import type { Release } from '../types'
import { taskFailureGuide } from '../taskFailure'

const props = defineProps<{ task: Release; isBuild: boolean; canRetry: boolean; retryLabel: string; retryScope?: string; busy?: boolean }>()
const emit = defineEmits<{ logs: []; retry: [] }>()
const router = useRouter()
const guide = computed(() => taskFailureGuide(props.task))
const mobile = computed(() => props.task.serviceType === 'FLUTTER_ANDROID')
const canBuild = computed(() => props.isBuild && Boolean(props.task.serviceId && hasMenu(mobile.value ? 'apps' : 'builds')))
const productionApp = computed(() => mobile.value && props.task.environment === 'prod')
function newBuild() {
  if (!canBuild.value) return
  const query = {
    serviceId: String(props.task.serviceId),
    branch: props.task.branch || undefined,
    environment: mobile.value ? props.task.environment : undefined,
  }
  void router.push({ path: mobile.value ? `/apps/${props.task.serviceId}/build` : '/build/new', query })
}
</script>

<template>
  <section class="task-failure-guide" :aria-label="tUi('失败后的处理建议')">
    <div><b>{{guide.title}}</b><p>{{guide.hint}}</p><p v-if="retryScope" class="retry-scope">{{retryScope}}</p></div>
    <div class="failure-guide-actions">
      <el-button type="primary" plain @click="emit('logs')">{{ tUi('查看错误日志') }}</el-button>
      <el-button v-if="canRetry && !guide.newBuild" :loading="busy" @click="emit('retry')">{{retryLabel}}</el-button>
      <el-button v-if="canBuild && (guide.newBuild || guide.alternativeBuild)" :disabled="busy" @click="newBuild">{{productionApp ? tUi('重新选择代码构建生产包') : tUi('用分支最新提交新建打包')}}</el-button>
    </div>
  </section>
</template>

<style scoped>
.task-failure-guide .retry-scope{font-weight:600;color:#684924}.failure-guide-actions{max-width:100%;flex-wrap:wrap}.failure-guide-actions button{max-width:100%}
.task-failure-guide{display:flex;align-items:center;justify-content:space-between;gap:24px;margin:0 0 22px;padding:18px 22px;border-left:3px solid #c87c57;background:#fffaf5;color:#52647c}.task-failure-guide b{font-size:14px;font-weight:600;color:#865434}.task-failure-guide p{max-width:760px;margin:7px 0 0;font-size:12px;line-height:1.85}.failure-guide-actions{display:flex;flex-shrink:0;gap:8px}.failure-guide-actions .el-button+.el-button{margin-left:0}@media(max-width:760px){.task-failure-guide{align-items:flex-start;flex-direction:column;gap:14px;padding:16px}.failure-guide-actions{flex-wrap:wrap}}
</style>
