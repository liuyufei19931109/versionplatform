<script setup lang="ts">
import { tUi } from '../i18n'

import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { authUser } from '../auth'
import { errorText, http, payload } from '../api'
import type { ReleaseVersion, VersionItem, VersionLinkPreview } from '../versions'
import ServiceIdentity from './ServiceIdentity.vue'
import CommitHash from './CommitHash.vue'

const props = defineProps<{ modelValue: boolean; version?: ReleaseVersion | null; item?: VersionItem | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; linked: [version: ReleaseVersion] }>()
const preview = ref<VersionLinkPreview | null>(null), loading = ref(false), submitting = ref(false), accepted = ref(false), error = ref('')
let generation = 0, controller: AbortController | undefined
function invalidate() { generation++; controller?.abort(); controller = undefined; preview.value = null; accepted.value = false; loading.value = false }
function close() { if (!submitting.value) emit('update:modelValue', false) }
async function load() {
  invalidate(); error.value = ''
  const version = props.version, item = props.item
  if (!props.modelValue || !authUser.value?.admin || !version || !item) return
  const request = generation, pending = new AbortController(); controller = pending; loading.value = true
  try {
    const value = payload<VersionLinkPreview>(await http.post(`/versions/${version.id}/services/${item.serviceId}/link-preview`, {}, { signal: pending.signal, timeout: 180000 }), {} as VersionLinkPreview)
    if (request !== generation) return
    if (!value.previewId || value.versionId !== version.id || value.branch !== item.branch || !value.headSha || !value.baselineSha || !value.services?.some(target => target.serviceId === item.serviceId)) throw new Error(tUi('关联核对结果不完整，请重新核对'))
    preview.value = value
  } catch (reason) { if (request === generation) error.value = errorText(reason, tUi('读取已有分支失败，请重新核对')) }
  finally { if (request === generation) { loading.value = false; controller = undefined } }
}
async function confirm() {
  const value = preview.value
  if (!value || !accepted.value || submitting.value || !authUser.value?.admin) return
  const request = generation; submitting.value = true; error.value = ''
  try {
    const result = payload<ReleaseVersion>(await http.post(`/versions/${value.versionId}/link-existing`, { previewId: value.previewId }, { timeout: 180000 }), {} as ReleaseVersion)
    if (request !== generation) return
    if (result.id !== value.versionId) throw new Error(tUi('未取得关联结果，请刷新版本后核对'))
    ElMessage.success(tUi('已有分支已关联，可以继续开发和打包'))
    emit('linked', result); emit('update:modelValue', false)
  } catch (reason) {
    if (request === generation) { error.value = errorText(reason, tUi('关联结果未确认，请刷新版本或重新核对')); preview.value = null; accepted.value = false }
  } finally { submitting.value = false }
}
watch([() => props.modelValue, () => props.version?.id, () => props.item?.serviceId], () => {
  if (props.modelValue) void load()
  else { invalidate(); error.value = '' }
})
onBeforeUnmount(invalidate)
</script>

<template>
  <el-dialog :model-value="modelValue" :title="tUi('关联已有分支')" width="640px" class="version-link-dialog" :close-on-click-modal="!submitting" :close-on-press-escape="!submitting" :show-close="!submitting" @update:model-value="close">
    <p class="link-intro">{{ tUi('将仓库中已有的分支用于本版本，保留现有代码。关联后可继续开发和打包，打包仍需通过最新 master 基线校验。') }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-if="loading" class="link-loading" role="status">{{ tUi('正在核对远程分支与 master…') }}</div>
    <template v-if="preview">
      <div class="link-branch"><span>{{ tUi('版本分支') }}</span><strong>{{preview.branch}}</strong></div>
      <div class="link-commits">
        <div><span>{{ tUi('已有分支最新提交') }}</span><CommitHash :value="preview.headSha"/></div>
        <div><span>{{ tUi('当前 master 基线') }}</span><CommitHash :value="preview.baselineSha"/></div>
        <p v-if="preview.baselineMessage">{{preview.baselineMessage}}</p>
      </div>
      <p class="link-target-heading">{{preview.services.length>1?tUi('以下模块共用此仓库，将一并关联：'):tUi('关联服务模块')}}</p>
      <div class="link-targets"><ServiceIdentity v-for="target in preview.services" :key="target.serviceId" :service="target"/></div>
      <el-checkbox v-model="accepted" :disabled="submitting" class="link-accept">{{ tUi('确认这些模块继续使用上述已有分支') }}</el-checkbox>
    </template>
    <template #footer>
      <el-button :disabled="submitting" @click="close">{{ tUi('取消') }}</el-button>
      <el-button v-if="!preview" type="primary" :loading="loading" @click="load">{{ tUi('重新核对') }}</el-button>
      <el-button v-else type="primary" :disabled="!accepted" :loading="submitting" @click="confirm">{{ tUi('确认关联') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.link-intro{margin:0 0 20px;color:#74849a;font-size:13px;line-height:1.9}.link-loading{padding:32px 0;color:#74849a;text-align:center}.link-branch{display:grid;gap:8px;margin:22px 0 17px}.link-branch>span,.link-commits span,.link-target-heading{font-size:12px;color:#74849a}.link-branch strong{font-size:18px;color:#354b68;overflow-wrap:anywhere}.link-commits{padding:16px;background:#f7f9fd;border-radius:7px;display:grid;gap:12px}.link-commits>div{display:flex;flex-wrap:wrap;gap:8px 16px;align-items:center}.link-commits>div>span{min-width:120px}.link-commits p{font-size:12px;color:#74849a;margin:0;overflow-wrap:anywhere}.link-target-heading{margin:22px 0 13px}.link-targets{display:grid;gap:17px}.link-accept{margin-top:23px;height:auto;white-space:normal}.link-accept :deep(.el-checkbox__label){white-space:normal;line-height:1.7;font-size:12px}
</style>
