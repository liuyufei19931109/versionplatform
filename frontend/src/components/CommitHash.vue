<script setup lang="ts">
import { computed, ref } from 'vue'
import { Check, CopyDocument } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { shortCommit } from '../ui'

const props = defineProps<{ value?: unknown }>()
const full = computed(() => String(props.value || '').trim())
const copied = ref(false)
async function copy() {
  if (!full.value) return
  try {
    if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(full.value)
    else {
      // The platform also runs over HTTP, where the Clipboard API is unavailable.
      const previous = document.activeElement as HTMLElement | null
      const field = document.createElement('textarea')
      field.value = full.value
      field.setAttribute('readonly', '')
      field.style.cssText = 'position:fixed;left:-9999px;top:0'
      document.body.appendChild(field)
      try { field.select(); if (!document.execCommand('copy')) throw new Error('copy unavailable') }
      finally { field.remove(); previous?.focus({ preventScroll: true }) }
    }
    copied.value = true
    ElMessage.success('已复制完整 Commit')
  } catch { ElMessage.warning('复制失败，请从悬浮提示中查看完整 Commit') }
}
</script>

<template>
  <button v-if="full" type="button" class="commit-hash" :title="full" :aria-label="`复制完整 Commit ${full}`" @click.stop="copy" @mouseleave="copied = false" @blur="copied = false">
    <code>{{ shortCommit(full) }}</code><el-icon aria-hidden="true"><Check v-if="copied"/><CopyDocument v-else/></el-icon>
  </button>
  <span v-else class="commit-hash-empty">—</span>
</template>

<style scoped>
.commit-hash{display:inline-flex;align-items:center;gap:7px;vertical-align:middle;max-width:100%;padding:3px 5px;border:1px solid transparent;border-radius:5px;background:transparent;color:#526984;cursor:pointer;font:inherit;line-height:1.5;white-space:nowrap}.commit-hash code{font:12px/1.5 ui-monospace,"Cascadia Code",Consolas,monospace}.commit-hash>.el-icon{font-size:13px;color:#8291a7}.commit-hash:hover,.commit-hash:focus-visible{color:#345fbe;background:#edf3ff;border-color:#dce6f8}.commit-hash-empty{color:#8391a5;font-size:12px}
</style>
