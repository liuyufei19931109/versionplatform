<script setup lang="ts">
import { computed } from 'vue'
import { Box, DataLine, HomeFilled, Promotion, Files, Monitor, Setting, Connection, Iphone, Collection } from '@element-plus/icons-vue'
import { authUser, hasMenu } from '../auth'
import { features } from '../features'

withDefaults(defineProps<{ active: string; collapsed?: boolean }>(), { collapsed: false })
const emit = defineEmits<{ navigate: [] }>()
const groups = computed(() => [
  { label: '工作空间', items: [
    { path: '/', label: '工作台', icon: HomeFilled, show: hasMenu('dashboard') },
    { path: '/versions', label: '版本管理', icon: Collection, show: hasMenu('versions') },
    { path: '/runtime', label: '运行总览', icon: Connection, show: hasMenu('runtime') },
  ] },
  { label: '构建与发布', items: [
    { path: '/services', label: '服务管理', icon: Box, show: hasMenu('services') },
    { path: '/apps', label: 'App 发布', icon: Iphone, show: hasMenu('apps') },
    { path: '/miniprograms', label: '小程序发布', icon: Connection, show: features.miniprograms && hasMenu('miniprograms') },
    { path: '/builds', label: '打包记录', icon: Files, show: hasMenu('builds') },
    { path: '/release/new', label: '新建发版', icon: Promotion, show: hasMenu('release') },
    { path: '/releases', label: '发版记录', icon: DataLine, show: hasMenu('releases') },
  ] },
  { label: '平台管理', items: [
    { path: '/servers', label: '服务器管理', icon: Monitor, show: hasMenu('servers') },
    { path: '/settings', label: '基础配置', icon: Setting, show: Boolean(authUser.value?.admin) },
  ] },
].map(group => ({ ...group, items: group.items.filter(item => item.show) })).filter(group => group.items.length))
</script>

<template>
  <nav class="platform-navigation" aria-label="主导航">
    <div v-for="group in groups" :key="group.label" class="navigation-group">
      <div v-if="!collapsed" class="nav-label">{{ group.label }}</div>
      <el-menu :default-active="active" router :collapse="collapsed" :collapse-transition="false" class="nav-menu" @select="emit('navigate')">
        <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon><template #title>{{ item.label }}</template>
        </el-menu-item>
      </el-menu>
    </div>
  </nav>
</template>
