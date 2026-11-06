<script setup lang="ts">
import { computed, ref } from 'vue'
import ServiceIcon from './ServiceIcon.vue'
import { serviceIcon, serviceIcons } from '../serviceIcons'
const props = defineProps<{ modelValue?: string; serviceType?: string; code?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const open = ref(false)
const selected = computed(() => serviceIcon(props.modelValue, props.serviceType))
function choose(key: string) { emit('update:modelValue', key); open.value = false }
</script>

<template>
  <div class="service-icon-picker">
    <button type="button" class="service-icon-current" aria-label="更换服务图标" @click="open=true"><ServiceIcon :name="modelValue" :service-type="serviceType" :code="code"/><span><b>{{modelValue?selected.label:'自动匹配'}}</b><small>选择内置图标</small></span><span class="service-icon-change">更换</span></button>
    <small class="service-icon-help">随服务配置保存，在服务列表、打包和发布等页面统一展示。</small>
    <el-dialog v-model="open" title="选择服务图标" width="min(520px, calc(100vw - 32px))" append-to-body destroy-on-close>
      <p class="service-icon-intro">20 个内置图标，选定后保存服务配置即可生效。</p>
      <div class="service-icon-grid" role="group" aria-label="内置服务图标">
        <button v-for="icon in serviceIcons" :key="icon.key" type="button" :aria-label="'使用'+icon.label+'图标'" :aria-pressed="modelValue===icon.key" :class="{selected:modelValue===icon.key}" @click="choose(icon.key)"><ServiceIcon :name="icon.key"/><span>{{icon.label}}</span></button>
      </div>
      <template #footer><el-button @click="choose('')">恢复自动匹配</el-button><el-button @click="open=false">取消</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.service-icon-picker{width:100%}.service-icon-current{display:flex;align-items:center;gap:12px;width:260px;max-width:100%;padding:10px 12px;border:1px solid #dce4f0;border-radius:8px;background:#fff;cursor:pointer;text-align:left;font:inherit}.service-icon-current>span:nth-child(2){display:grid;gap:4px}.service-icon-current b{font-size:14px;font-weight:700;color:#263a56}.service-icon-current small,.service-icon-help{font-size:12px;line-height:1.7;color:#74849a}.service-icon-change{margin-left:auto;font-size:12px;color:#416bdc}.service-icon-help{display:block;margin-top:7px}.service-icon-intro{margin:0 0 18px;color:#74849a;font-size:13px;line-height:1.7}.service-icon-grid{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:9px}.service-icon-grid button{display:flex;align-items:center;flex-direction:column;gap:8px;padding:12px 3px;border:1px solid #e4eaf3;border-radius:8px;background:#fff;color:#52647c;font:inherit;font-size:12px;cursor:pointer}.service-icon-grid button:hover,.service-icon-grid button.selected{border-color:#416bdc;background:#f3f6ff}.service-icon-current:focus-visible,.service-icon-grid button:focus-visible{outline:2px solid #416bdc;outline-offset:3px}@media(max-width:480px){.service-icon-grid{gap:6px}.service-icon-grid button{font-size:11px;padding:9px 2px}}
</style>
