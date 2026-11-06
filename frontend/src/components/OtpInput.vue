<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{ modelValue: string; disabled?: boolean; autofocus?: boolean; label?: string }>(), { label: '6 位动态码' })
const emit = defineEmits<{ 'update:modelValue': [value: string]; submit: [] }>()
const fields = ref<HTMLInputElement[]>([])
let resetFocus = false
function focus(index: number) { nextTick(() => { if (props.disabled) return; fields.value[Math.max(0, Math.min(5, index))]?.focus(); fields.value[Math.max(0, Math.min(5, index))]?.select() }) }
watch(() => props.modelValue, (value, previous) => { if (!value && previous) { resetFocus = true; if (!props.disabled) { focus(0); resetFocus = false } } })
watch(() => props.disabled, disabled => { if (!disabled && resetFocus) { focus(0); resetFocus = false } })
onMounted(() => { if (props.autofocus) { if (props.disabled) resetFocus = true; else focus(0) } })
function insert(index: number, text: string) {
  const digits = text.replace(/\D/g, '').slice(0, 6)
  if (!digits) return
  const start = digits.length === 6 ? 0 : Math.min(index, props.modelValue.length)
  const value = props.modelValue.split('')
  for (let i = 0; i < digits.length && start + i < 6; i++) value[start + i] = digits[i]
  emit('update:modelValue', value.join('').slice(0, 6))
  focus(Math.min(5, start + digits.length))
}
function input(index: number, event: Event) {
  const target = event.target as HTMLInputElement
  if (!target.value) { emit('update:modelValue', props.modelValue.slice(0, index) + props.modelValue.slice(index + 1)); return }
  insert(index, target.value)
  nextTick(() => { target.value = props.modelValue[index] || '' })
}
function key(index: number, event: KeyboardEvent) {
  if (event.key === 'Backspace') { event.preventDefault(); const at = props.modelValue[index] ? index : index - 1; if (at >= 0) { emit('update:modelValue', props.modelValue.slice(0, at) + props.modelValue.slice(at + 1)); focus(at) } }
  else if (event.key === 'ArrowLeft') { event.preventDefault(); focus(index - 1) }
  else if (event.key === 'ArrowRight') { event.preventDefault(); focus(index + 1) }
  else if (event.key === 'Enter') { event.preventDefault(); if (props.modelValue.length === 6 && !props.disabled) emit('submit') }
}
function paste(index: number, event: ClipboardEvent) { event.preventDefault(); insert(index, event.clipboardData?.getData('text') || '') }
</script>

<template>
  <div class="otp-input" role="group" :aria-label="label">
    <input v-for="(_, index) in 6" :key="index" :ref="element => { if (element) fields[index] = element as HTMLInputElement }" :value="modelValue[index] || ''" :disabled="disabled" :autofocus="autofocus && index === 0" :aria-label="`${label}，第 ${index + 1} 位`" inputmode="numeric" pattern="[0-9]*" :autocomplete="index === 0 ? 'one-time-code' : 'off'" maxlength="6" type="text" @input="input(index, $event)" @keydown="key(index, $event)" @paste="paste(index, $event)" @focus="($event.target as HTMLInputElement).select()" />
  </div>
</template>

<style scoped>
.otp-input{display:flex;gap:9px;width:100%;justify-content:center}.otp-input input{min-width:0;width:48px;height:56px;border:1px solid #d6dfec;border-radius:10px;background:#fff;text-align:center;color:#172b4d;font:600 24px/1 ui-monospace,Consolas,monospace;outline:none;transition:border-color .15s,box-shadow .15s}.otp-input input:focus{border-color:#3c6de8;box-shadow:0 0 0 3px #3c6de817}.otp-input input:disabled{background:#f3f5f9;color:#8290a5;cursor:wait}@media(max-width:420px){.otp-input{gap:6px}.otp-input input{width:40px;height:50px}}
</style>
