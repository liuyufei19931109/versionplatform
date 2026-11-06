<script setup lang="ts">
import { tUi } from '../i18n'

import { normalizeBuildEngine } from '../buildEngine'
import type { BuildEngine } from '../types'

defineProps<{ modelValue?: BuildEngine; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: BuildEngine] }>()
function update(value: unknown) { emit('update:modelValue', normalizeBuildEngine(value)) }
</script>

<template>
  <el-form-item :label="tUi('构建方式')" class="build-engine-field">
    <div class="build-engine-setting">
      <el-radio-group :model-value="normalizeBuildEngine(modelValue)" :disabled="disabled" :aria-label="tUi('构建方式')" @update:model-value="update">
        <el-radio-button value="GITHUB">{{ tUi('GitHub Actions（默认）') }}</el-radio-button>
        <el-radio-button value="LOCAL">{{ tUi('本机构建') }}</el-radio-button>
      </el-radio-group>
      <p class="build-engine-note"><strong>{{ tUi('仅之后新建任务生效') }}</strong>{{ tUi('，历史任务重试沿用原方式。全局本机回退开关优先。') }}</p>
    </div>
  </el-form-item>
</template>

<style scoped>
.build-engine-setting{width:100%;min-width:0}
.build-engine-note{margin:12px 0 0;padding:10px 13px;border-left:3px solid #6486d7;background:#f1f5fd;color:#526b89;font-size:12px;line-height:1.8;overflow-wrap:anywhere}
.build-engine-note strong{font-weight:600;color:#354b68}
@media(max-width:420px){.build-engine-setting :deep(.el-radio-group){display:flex;width:100%}.build-engine-setting :deep(.el-radio-button){flex:1}.build-engine-setting :deep(.el-radio-button__inner){width:100%;padding:9px 10px;font-size:12px}}
</style>
