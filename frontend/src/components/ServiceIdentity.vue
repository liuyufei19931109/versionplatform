<script setup lang="ts">
import { computed } from 'vue'
import ServiceIcon from './ServiceIcon.vue'

interface ServiceIdentityData {
  displayIcon?: string
  name?: string
  code?: string
  serviceName?: string
  serviceKey?: string
  serviceType?: 'BACKEND' | 'FRONTEND' | 'FLUTTER_ANDROID'
}

const props = withDefaults(defineProps<{
  service?: ServiceIdentityData
  detail?: string
  large?: boolean
  showType?: boolean
}>(), { detail: '', large: false, showType: true })

const code = computed(() => props.service?.code || props.service?.serviceKey || '')
const name = computed(() => props.service?.name || props.service?.serviceName || '服务')
const typeLabel = computed(() => props.service?.serviceType === 'FLUTTER_ANDROID' ? 'Android' : props.service?.serviceType === 'FRONTEND' ? '前端' : props.service?.serviceType === 'BACKEND' ? '后端' : '')
</script>

<template>
  <span class="service-identity" :class="{ 'service-identity--large': large }">
    <ServiceIcon :name="service?.displayIcon" :service-type="service?.serviceType" :code="code" :large="large"/>
    <span class="service-identity__content">
      <span class="service-identity__title"><span class="service-identity__name" :title="name">{{name}}</span><span v-if="showType && typeLabel" class="service-identity__type">{{typeLabel}}</span></span>
      <span v-if="code || detail" class="service-identity__meta" :title="[code, detail].filter(Boolean).join(' · ')"><span v-if="code" class="service-identity__code">{{code}}</span><span v-if="code && detail" class="service-identity__separator">·</span><span v-if="detail" class="service-identity__detail">{{detail}}</span></span>
    </span>
  </span>
</template>

<style scoped>
.service-identity{display:flex;align-items:center;gap:10px;min-width:0;width:100%;vertical-align:middle;text-align:left;line-height:1.4}
.service-identity__content{display:flex;flex-direction:column;gap:4px;min-width:0;flex:1}.service-identity__title{display:flex;align-items:center;gap:8px;min-width:0}.service-identity__name{font-size:15px;font-weight:700;color:#263a56;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.service-identity__type{flex-shrink:0;padding:1px 5px;border-radius:4px;border:1px solid #e2e8f0;background:#ffffffb3;font-size:10px;line-height:1.4;font-weight:400;color:#8390a4;white-space:nowrap}.service-identity__meta{display:flex;align-items:center;gap:6px;min-width:0;font-size:12px;font-weight:400;color:#798ba3;line-height:1.4}.service-identity__code{font-family:ui-monospace,"Cascadia Code",Consolas,monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.service-identity__separator{color:#bcc5d2;flex-shrink:0}.service-identity__detail{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.service-identity--large{gap:13px}.service-identity--large .service-identity__name{font-size:26px;color:#263a56;line-height:1.3}.service-identity--large .service-identity__type{font-size:11px;padding:2px 6px}.service-identity--large .service-identity__meta{font-size:12px}
@media(max-width:640px){.service-identity--large .service-identity__name{font-size:20px}.service-identity__title{gap:5px}}
</style>
