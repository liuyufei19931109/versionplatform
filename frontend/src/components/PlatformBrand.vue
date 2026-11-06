<script setup lang="ts">
import { platformLogo } from '../branding'
withDefaults(defineProps<{ size?: number }>(), { size: 36 })
</script>

<template>
  <img v-if="platformLogo" :src="platformLogo" class="platform-brand-symbol"
       alt="" aria-hidden="true" :width="size" :height="size" draggable="false" />
</template>

<style scoped>
.platform-brand-symbol { display: block; flex: none; object-fit: contain; user-select: none; }
</style>
