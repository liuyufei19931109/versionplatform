<script setup lang="ts">
import { tUi } from '../i18n'

import { onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { errorText, http } from '../api'
import { logoUnavailable, notifyLogoChange, platformLogo, previewPng, refreshLogo } from '../branding'

const chooser = ref<HTMLInputElement>()
const selected = ref<File>()
const preview = ref('')
const saving = ref(false)
let selectionId = 0

function discard() {
  ++selectionId
  selected.value = undefined
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = ''
  if (chooser.value) chooser.value.value = ''
}
onBeforeUnmount(discard)

async function choose(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  discard()
  const id = selectionId
  try {
    const url = await previewPng(file)
    if (id !== selectionId) { URL.revokeObjectURL(url); return }
    selected.value = file
    preview.value = url
  } catch (error) {
    if (id === selectionId) ElMessage.warning(errorText(error))
  }
}

async function save(remove: boolean) {
  if (saving.value || (!remove && !selected.value)) return
  saving.value = true
  try {
    if (remove) await http.delete('/settings/branding/logo')
    else await http.put('/settings/branding/logo', selected.value, { headers: { 'Content-Type': 'image/png' } })
    discard()
    await notifyLogoChange()
    ElMessage.success(remove ? tUi('已清除平台 Logo') : tUi('已保存平台 Logo'))
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { saving.value = false }
}
</script>

<template>
  <section class="panel branding-settings" aria-labelledby="branding-title">
    <h3 id="branding-title">{{ tUi('平台 Logo') }}</h3>
    <p>{{ tUi('显示在导航、登录页和浏览器标签页。支持 PNG，最大 256 KiB，宽高均不超过 1024 像素。') }}</p>
    <div class="branding-preview">
      <img v-if="preview || platformLogo" :src="preview || platformLogo" :alt="tUi('平台 Logo 预览')" />
      <span v-if="preview">{{ tUi('待保存的预览') }}</span>
      <span v-else-if="logoUnavailable">{{ tUi('无法读取当前 Logo') }} <el-button link @click="refreshLogo()">{{ tUi('重试') }}</el-button></span>
      <span v-else-if="!platformLogo">{{ tUi('未设置 Logo') }}</span>
    </div>
    <input ref="chooser" type="file" accept="image/png,.png" hidden @change="choose" />
    <div class="branding-actions">
      <el-button :disabled="saving" @click="chooser?.click()">{{ tUi('选择 PNG 图片') }}</el-button>
      <el-button v-if="selected" type="primary" :loading="saving" @click="save(false)">{{ tUi('保存 Logo') }}</el-button>
      <el-button v-if="selected" :disabled="saving" @click="discard">{{ tUi('取消') }}</el-button>
      <el-button v-else-if="platformLogo || logoUnavailable" :disabled="saving" @click="save(true)">{{ tUi('清除 Logo') }}</el-button>
    </div>
  </section>
</template>

<style scoped>
.branding-settings { margin-bottom: 18px; }
.branding-settings h3 { margin: 0; }
.branding-settings p, .branding-preview span { color: #586678; font-size: 13px; }
.branding-preview, .branding-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.branding-preview { min-height: 36px; margin: 12px 0; }
.branding-preview img { width: 56px; height: 56px; object-fit: contain; }
.branding-actions :deep(.el-button + .el-button) { margin-left: 0; }
</style>
