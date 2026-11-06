<script setup lang="ts">
import { tUi } from '../i18n'

import { computed } from 'vue'
import type { Service } from '../types'
import ServiceIdentity from './ServiceIdentity.vue'

const props = defineProps<{ modelValue: string; services: Service[]; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const selected = computed(() => props.services.find(service => String(service.id) === props.modelValue))
</script>

<template>
  <el-select :model-value="modelValue" :disabled="disabled" filterable size="large" class="service-select" popper-class="service-select-menu" :placeholder="tUi('请选择目标服务')" @update:model-value="emit('update:modelValue', String($event))">
    <template #label><ServiceIdentity v-if="selected" :service="selected"/><span v-else>{{modelValue}}</span></template>
    <el-option v-for="service in services" :key="service.id" :label="[service.name, service.code].filter(Boolean).join(' · ')" :value="String(service.id)"><ServiceIdentity :service="service"/></el-option>
  </el-select>
</template>

<style scoped>
.service-select{width:100%}.service-select :deep(.el-select__wrapper){min-height:62px;padding:10px 14px}.service-select :deep(.el-select__placeholder){width:100%;min-width:0}.service-select :deep(.el-select__selected-item){line-height:1.4}.service-select :deep(.el-select__selection){min-width:0}
</style>

<style>
.service-select-menu .el-select-dropdown__item{height:auto;min-height:62px;padding:11px 15px;line-height:1.4;margin:2px 5px;border-radius:7px}.service-select-menu .el-select-dropdown__list{padding:5px 0}.service-select-menu .el-select-dropdown__item.is-selected{background:#f0f5ff}.service-select-menu .el-select-dropdown__item.is-selected .service-identity__name{color:#416bdc}
</style>
