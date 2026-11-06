<script setup lang="ts">
import { language, selectLanguage, tUi, type Language } from '../i18n'

function change(event: Event) {
  selectLanguage((event.target as HTMLSelectElement).value as Language)
}
</script>

<template>
  <select class="language-select" :aria-label="tUi('界面语言')" :title="tUi('界面语言')"
          :value="language" @change="change">
    <option value="zh-CN" lang="zh-CN">简体中文</option>
    <option value="es" lang="es">Español</option>
    <option value="en" lang="en">English</option>
  </select>
</template>

<style scoped>
.language-select { max-width: 7rem; min-height: 32px; padding: 0 5px; border: 1px solid #cbd5e1; border-radius: 6px; color: #334155; background: white; font: inherit; font-size: 13px; cursor: pointer; }
.language-select:focus-visible { outline: 2px solid #346b9a; outline-offset: 2px; }
</style>
