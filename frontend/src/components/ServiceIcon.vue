<script setup lang="ts">
import { computed } from 'vue'
import { serviceIcon } from '../serviceIcons'
const props = defineProps<{ name?: string; serviceType?: string; code?: string; large?: boolean }>()
const icon = computed(() => serviceIcon(props.name, props.serviceType))
</script>

<template>
  <span class="service-symbol" :class="{ 'service-symbol--large': large }" :data-service-icon="icon.key" :style="{ color: icon.color, background: icon.background }" aria-hidden="true"><component :is="icon.component"/></span>
</template>

<style scoped>
.service-symbol{display:inline-grid;place-items:center;flex:0 0 36px;width:36px;height:36px;border-radius:9px}.service-symbol svg{width:21px;height:21px}.service-symbol--large{width:46px;height:46px;flex-basis:46px;border-radius:12px}.service-symbol--large svg{width:27px;height:27px}
</style>
