<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Fold, Expand, Operation, SwitchButton, Lock } from '@element-plus/icons-vue'
import { authUser, clearAuth } from './auth'
import PlatformBrand from './components/PlatformBrand.vue'
import { listenForLogoChanges, platformLogo, refreshLogo } from './branding'
import GitEventBell from './components/GitEventBell.vue'
import GlobalTaskActivity from './components/GlobalTaskActivity.vue'
import VersionSummaryCard from './components/VersionSummaryCard.vue'
import PlatformNavigation from './components/PlatformNavigation.vue'

const stopLogoUpdates = listenForLogoChanges()
onBeforeUnmount(stopLogoUpdates)
watch(() => authUser.value?.username, () => { void refreshLogo() })
const route = useRoute()
const router = useRouter()
const collapsed = ref(false)
const mobileNavigation = ref(false)
const accountMenu = ref<{ handleClose: () => void }>()
const narrowScreen = window.matchMedia('(max-width: 720px)')
const mobile = ref(narrowScreen.matches)
function resizeNavigation(event: MediaQueryListEvent) { mobile.value = event.matches; accountMenu.value?.handleClose(); if (!event.matches) mobileNavigation.value = false }
narrowScreen.addEventListener('change', resizeNavigation)
onBeforeUnmount(() => narrowScreen.removeEventListener('change', resizeNavigation))
watch(() => route.path, () => { mobileNavigation.value = false; accountMenu.value?.handleClose() })
function toggleNavigation() { accountMenu.value?.handleClose(); if (mobile.value) mobileNavigation.value = !mobileNavigation.value; else collapsed.value = !collapsed.value }
const isLogin = computed(() => route.path === '/login')
const title = computed(() => String(route.meta.title || '控制台'))
const activeMenu = computed(() => route.path.startsWith('/versions') ? '/versions' : route.path.startsWith('/apps') ? '/apps' : route.path.startsWith('/build') ? '/builds' : route.path.startsWith('/services') ? '/services' : route.path.startsWith('/releases') ? '/releases' : route.path.startsWith('/runtime') ? '/runtime' : route.path)

function logout() {
  clearAuth()
  router.replace('/login')
}
</script>

<template>
  <router-view v-if="isLogin" />
  <div v-else class="shell">
    <aside class="sidebar" :class="{ collapsed }">
      <div v-if="!collapsed || platformLogo" class="brand">
        <PlatformBrand />
        <div v-if="!collapsed"><b>发版平台</b><small>发版与交付工作台</small></div>
      </div>
      <PlatformNavigation :active="activeMenu" :collapsed="collapsed" />
    </aside>
    <section class="main-area">
      <header class="topbar">
        <div class="topbar-left"><el-button text circle :aria-label="mobile ? '打开导航' : collapsed ? '展开导航' : '收起导航'" :aria-expanded="mobile ? mobileNavigation : !collapsed" @click="toggleNavigation"><el-icon><Expand v-if="collapsed || mobile"/><Fold v-else /></el-icon></el-button><span class="workspace-context">交付工作空间</span><span class="topbar-divider" aria-hidden="true">/</span><h1>{{ title }}</h1></div>
        <div class="top-actions">
          <VersionSummaryCard v-if="authUser" :key="`summary-${authUser.username}`" placement="header" />
          <GitEventBell v-if="authUser" :key="authUser.username" />
          <el-dropdown ref="accountMenu" trigger="click">
            <button type="button" class="user-chip" aria-label="账户菜单"><span class="avatar">{{authUser?.displayName?.slice(0,1)||'U'}}</span><span class="user-name">{{authUser?.displayName||authUser?.username}}</span><el-icon><Operation /></el-icon></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item @click="router.push('/settings/security')"><el-icon><Lock /></el-icon>二次认证</el-dropdown-item><el-dropdown-item divided @click="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </header>
      <GlobalTaskActivity v-if="authUser" :key="authUser.username" />
      <main class="content"><router-view :key="route.path" /></main>
    </section>
    <el-drawer v-model="mobileNavigation" direction="ltr" size="272px" :show-close="true" class="mobile-navigation-drawer" title="发版平台">
      <template #header="{ titleId, titleClass }">
        <div class="mobile-drawer-brand"><PlatformBrand :size="28" /><span :id="titleId" :class="titleClass">发版平台</span></div>
      </template>
      <PlatformNavigation :active="activeMenu" @navigate="mobileNavigation = false" />
    </el-drawer>
  </div>
</template>

<style scoped>
.mobile-drawer-brand { display: flex; align-items: center; gap: 10px; }
</style>
