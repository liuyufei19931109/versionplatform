import { createRouter, createWebHistory } from 'vue-router'
import LoginView from './views/LoginView.vue'
import DashboardView from './views/DashboardView.vue'
import VersionsView from './views/VersionsView.vue'
import RuntimeView from './views/RuntimeView.vue'
import ContainerLogsView from './views/ContainerLogsView.vue'
import ServicesView from './views/ServicesView.vue'
import AppsView from './views/AppsView.vue'
import ServiceDetailView from './views/ServiceDetailView.vue'
import ReleaseWizardView from './views/ReleaseWizardView.vue'
import ReleasesView from './views/ReleasesView.vue'
import ReleaseDetailView from './views/ReleaseDetailView.vue'
import BuildWizardView from './views/BuildWizardView.vue'
import BuildsView from './views/BuildsView.vue'
import BuildDetailView from './views/BuildDetailView.vue'
import SecuritySettingsView from './views/SecuritySettingsView.vue'
import ServersView from './views/ServersView.vue'
import GlobalSettingsView from './views/GlobalSettingsView.vue'
import { authUser, clearAuth, hasMenu, setAuthUser } from './auth'
import { http, payload } from './api'
import { features, loadFeatures } from './features'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/miniprograms', component: () => import('./views/MiniProgramsView.vue'), meta: { title: '小程序发布', permission: 'miniprograms' } },
    { path: '/login', component: LoginView, meta: { public: true, title: '登录' } },
    { path: '/', component: DashboardView, meta: { title: '工作台', permission:'dashboard' } },
    { path: '/versions', component: VersionsView, meta: { title: '版本管理', permission:'versions' } },
    { path: '/versions/:id', component: VersionsView, meta: { title: '版本详情', permission:'versions' } },
    { path: '/runtime', component: RuntimeView, meta: { title: '运行总览', permission:'runtime' } },
    { path: '/runtime/logs', component: ContainerLogsView, meta: { title: '服务日志', permission:'runtime' } },
    { path: '/apps', component: AppsView, meta: { title: 'App 发布', permission:'apps', apps:true } },
    { path: '/apps/builds/:id', component: BuildDetailView, meta: { title: 'App 构建详情', permission:'apps', apps:true } },
    { path: '/apps/releases/:id', component: ReleaseDetailView, meta: { title: 'App 交付详情', permission:'apps', apps:true } },
    { path: '/apps/:serviceId/build', component: BuildWizardView, meta: { title: 'App 新建构建', permission:'apps', apps:true } },
    { path: '/apps/:serviceId/release', component: ReleaseWizardView, meta: { title: 'App 创建交付', permission:'apps', apps:true } },
    { path: '/apps/:id', component: ServiceDetailView, meta: { title: 'App 配置与版本', permission:'apps', apps:true } },
    { path: '/services', component: ServicesView, meta: { title: '服务管理', permission:'services' } },
    { path: '/services/:id', component: ServiceDetailView, meta: { title: '服务配置', permission:'services' } },
    { path: '/servers', component: ServersView, meta: { title: '服务器管理', permission:'servers' } },
    { path: '/build/new', component: BuildWizardView, meta: { title: '新建打包', permission:'builds' } },
    { path: '/builds', component: BuildsView, meta: { title: '打包记录', permission:'builds' } },
    { path: '/builds/:id', component: BuildDetailView, meta: { title: '打包详情', permission:'builds' } },
    { path: '/release/new', component: ReleaseWizardView, meta: { title: '新建发版', permission:'release' } },
    { path: '/releases', component: ReleasesView, meta: { title: '发版记录', permission:'releases' } },
    { path: '/releases/:id', component: ReleaseDetailView, meta: { title: '发版详情', permissionAny:['release','releases'] } },
    { path: '/settings', component: GlobalSettingsView, meta: { title: '基础配置', adminOnly:true } },
    { path: '/settings/security', component: SecuritySettingsView, meta: { title: '登录安全' } },
  ],
})

let verifiedToken = ''

router.beforeEach(async (to) => {
  document.title = `${String(to.meta.title || '控制台')} · 发版平台`
  // The login page must remain reachable even when a previous session is stale.
  if (to.meta.public) return true
  const token = localStorage.getItem('release_token')
  if (!token) { clearAuth(); verifiedToken = ''; return '/login' }
  if (token !== verifiedToken || !authUser.value) {
    try {
      const me = payload<Record<string, unknown>>(await http.get('/auth/me'), {})
      if (!me.username) throw new Error('登录状态无效')
      setAuthUser({username:String(me.username),displayName:String(me.displayName||me.username),admin:Boolean(me.admin),permissions:Array.isArray(me.permissions)?me.permissions.map(String):[],totpEnabled:Boolean(me.totpEnabled),requiresTotpSetup:!me.totpEnabled})
      verifiedToken = token
    } catch (error) {
      clearAuth(); verifiedToken = ''
      const status = (error as { response?: { status?: number } }).response?.status
      return { path: '/login', query: status === 401 || status === 403 ? { expired: '1' } : { unavailable: '1' } }
    }
  }
  if(authUser.value?.requiresTotpSetup&&to.path!=='/settings/security')return '/settings/security?first=1'
  if(to.path.startsWith('/miniprograms')) { await loadFeatures(); if(!features.miniprograms)return '/settings/security' }
  else void loadFeatures()
  if(to.meta.adminOnly&&!authUser.value?.admin)return firstAllowedPath()
  if(to.meta.permission&&!hasMenu(String(to.meta.permission)))return firstAllowedPath()
  if(Array.isArray(to.meta.permissionAny)&&!to.meta.permissionAny.some(value=>hasMenu(String(value))))return firstAllowedPath()
})

function firstAllowedPath(){const candidates=[['dashboard','/'],['versions','/versions'],['apps','/apps'],['runtime','/runtime'],['services','/services'],['servers','/servers'],['builds','/builds'],['release','/release/new'],['releases','/releases'],...(features.miniprograms?[['miniprograms','/miniprograms']]:[])];return candidates.find(([permission])=>hasMenu(permission))?.[1]||'/settings/security'}

export default router
