import axios from 'axios'
import type { Release, ReleaseStep, Service } from './types'
import { clearAuth } from './auth'
import { normalizedMobileBuild } from './mobile'
import { normalizeBuildEngine } from './buildEngine'

export const http = axios.create({ baseURL: '/api', timeout: 30000 })
let redirectingToLogin = false

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('release_token')
  if (token && config.url !== '/auth/login' && config.url !== '/health') config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const url = String(error.config?.url || '')
    const sentToken = String(error.config?.headers?.Authorization || '').replace(/^Bearer\s+/i, '')
    const currentToken = localStorage.getItem('release_token')
    if (error.response?.status === 401 && (!currentToken || sentToken === currentToken)) {
      clearAuth()
      // Route guards handle /auth/me; late failures from an older token do not log out a new session.
      if (url !== '/auth/me' && url !== '/auth/login' && location.pathname !== '/login' && !redirectingToLogin) {
        redirectingToLogin = true
        location.replace('/login?expired=1')
      }
    }
    if(error.response?.status===403&&error.response?.data?.code==='TOTP_SETUP_REQUIRED'&&url!=='/auth/me'&&location.pathname!=='/login'&&!location.pathname.includes('/settings/security'))location.replace('/settings/security?first=1')
    return Promise.reject(error)
  },
)

export function payload<T>(value: unknown, fallback: T): T {
  const raw = (value as { data?: unknown })?.data ?? value
  const nested = (raw as { data?: unknown })?.data ?? raw
  return (nested ?? fallback) as T
}

export function rows<T>(value: unknown): T[] {
  const data = payload<unknown>(value, [])
  if (Array.isArray(data)) return data as T[]
  const obj = data as Record<string, unknown>
  for (const key of ['content', 'records', 'list', 'items']) {
    if (Array.isArray(obj?.[key])) return obj[key] as T[]
  }
  return []
}

export function errorText(error: unknown, fallback = '请求失败，请稍后重试') {
  const e = error as { response?: { data?: { message?: string; error?: string } }; message?: string }
  const message = e.response?.data?.message || e.response?.data?.error || e.message || fallback
  if (message === 'Internal Server Error') return '服务器处理失败，请稍后重试或查看平台日志'
  if (message === 'Network Error') return '无法连接平台，请检查网络后重试'
  if (/timeout.*exceeded/i.test(message)) return '请求超时，请稍后重试；任务可能已经创建，请先检查任务记录'
  return message
}

function jsonObject(value: unknown): Record<string, unknown> {
  if (typeof value !== 'string') return (value || {}) as Record<string, unknown>
  try { return JSON.parse(value) as Record<string, unknown> } catch { return {} }
}

export function normalizeService(raw: Record<string, unknown>): Service {
  const config = jsonObject(raw.configJson)
  const serviceType = String(raw.serviceType || config.serviceType || 'BACKEND').toUpperCase() as Service['serviceType']
  const env = jsonObject(raw.envJson)
  const envVariables = Array.isArray(raw.envVariables)
    ? raw.envVariables as Service['envVariables']
    : Object.entries(env).map(([key, value]) => ({ key, value: String(value ?? ''), secret: false }))
  return {
    ...config, ...raw,
    name: String(raw.name || ''), code: String(raw.code || raw.serviceKey || ''),
    displayIcon: typeof raw.displayIcon === 'string' ? raw.displayIcon : typeof config.displayIcon === 'string' ? config.displayIcon : '',
    serviceType,
    buildEngine: normalizeBuildEngine(raw.buildEngine),
    imageBuildMode: String(raw.imageBuildMode || config.imageBuildMode || (serviceType === 'FRONTEND' ? 'STATIC_NGINX' : 'REPOSITORY_DOCKERFILE')).toUpperCase() as Service['imageBuildMode'],
    status: raw.status ?? raw.enabled ?? true,
    gitUrl: String(raw.gitUrl || raw.gitRepo || ''),
    workDirectory: String(raw.workDirectory || config.workDirectory || ''),
    deployHost: String(raw.deployHost || raw.targetHost || ''),
    startupArgs: String(raw.startupArgs || raw.startCommand || ''),
    envVariables,
  } as Service
}

export function serviceRequest(service: Service) {
  const env = Object.fromEntries((service.envVariables || []).filter(v => v.key).map(v => [v.key, v.value]))
  const configKeys = ['displayIcon','buildTool','javaVersion','nodeVersion','buildTimeout','gitAuthType','gitCredential','imageName','imageBuildMode','registryCredential','deployPort','containerPort','environment','mobileBuild']
  // Preserve fields that this editor does not own, including signing and future config extensions.
  const config = { ...jsonObject(service.configJson), ...Object.fromEntries(configKeys.map(key => [key, service[key]]).filter(([, value]) => value !== undefined)) }
  if (service.serviceType === 'FLUTTER_ANDROID' && service.mobileBuild) config.mobileBuild = normalizedMobileBuild(service.mobileBuild)
  else if (service.serviceType !== 'FLUTTER_ANDROID') delete config.mobileBuild
  if (service.serviceType === 'FRONTEND') config.nodeVersion = '22'
  else if (service.serviceType !== 'FLUTTER_ANDROID') config.javaVersion = '21'
  return {
    name: service.name, serviceKey: service.code, serviceType: service.serviceType || 'BACKEND', buildEngine: normalizeBuildEngine(service.buildEngine), description: service.description,
    enabled: service.status !== false && service.status !== 'DISABLED',
    gitRepo: service.gitUrl, defaultBranch: service.defaultBranch,
    buildCommand: service.buildCommand, workDirectory: service.workDirectory, jarPath: service.jarPath,
    dockerfilePath: service.dockerfilePath, imageRepository: service.imageRepository,
    targetHost: service.deployHost, targetUser: service.targetUser || 'root',
    startCommand: service.startupArgs, healthCheckUrl: service.healthCheckUrl,
    envJson: JSON.stringify(env), configJson: JSON.stringify(config),
  }
}

const stepNames: Record<string, string> = {
  GIT_PULL:'拉取代码', BUILD_JAR:'构建应用', BUILD_IMAGE:'构建 Docker 镜像',
  PUSH_IMAGE:'推送镜像仓库', SSH_CONNECT:'连接远程服务器', START_SERVICE:'启动服务', HEALTH_CHECK:'健康检查',
  BUILD_APK:'构建 Android APK',VALIDATE_APK:'校验 APK 安装包',BUILD_AAB:'构建 Android AAB', VALIDATE_AAB:'校验安装包', ARCHIVE_ARTIFACT:'归档安装包',
}

export function stepLabel(code: string, serviceType?: string) {
  if (code === 'BUILD_JAR') return serviceType === 'FRONTEND' ? '构建 Node 前端' : serviceType === 'BACKEND' ? '构建 Spring Boot JAR' : '构建应用'
  return stepNames[code] || code
}

function normalizeStep(step: Record<string, unknown>, serviceType?: string): ReleaseStep {
  const code=String(step.code || step.stepType || '')
  const name = stepNames[code] ? stepLabel(code, serviceType) : String(step.name || step.stepName || code)
  const log = normalizeTaskLog(String(step.log || step.logText || ''), serviceType)
  return { ...step, code, name, stepName: name, log, logText: log } as ReleaseStep
}

function cleanLog(value: string) { return value.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g, '') }

export function normalizeTaskLog(value: string, serviceType?: string) {
  // Only complete legacy platform marker lines are relabeled; command/source output is preserved.
  return cleanLog(value).replace(
    /^开始：(GIT_PULL|BUILD_JAR|BUILD_IMAGE|PUSH_IMAGE|SSH_CONNECT|START_SERVICE|HEALTH_CHECK|BUILD_AAB|VALIDATE_AAB|BUILD_APK|VALIDATE_APK|ARCHIVE_ARTIFACT)(?=\r?$)/gm,
    (_line, code: string) => `开始：${stepLabel(code, serviceType)}`,
  )
}

export function normalizeRelease(raw: Record<string, unknown>): Release {
  const wrapper = raw.task && typeof raw.task === 'object' ? raw.task as Record<string, unknown> : raw
  const rawSteps = (Array.isArray(raw.steps) ? raw.steps : Array.isArray(wrapper.steps) ? wrapper.steps : []) as Record<string, unknown>[]
  const serviceType=String(wrapper.serviceType || raw.serviceType || '').toUpperCase()
  const normalizedSteps = rawSteps.map(step=>normalizeStep(step,serviceType))
  const logs = normalizeTaskLog(String(wrapper.logs || raw.logs || wrapper.logText || raw.logText || normalizedSteps.map(step => step.log).filter(Boolean).join('\n') || ''), serviceType)
  return {
    ...wrapper,
    id: String(wrapper.id || ''),
    serviceType: (['FRONTEND','BACKEND','FLUTTER_ANDROID'].includes(serviceType) ? serviceType : undefined) as Release['serviceType'],
    serviceName: String(wrapper.serviceName || raw.serviceName || (wrapper.serviceId ? `服务 #${wrapper.serviceId}` : '')),
    serviceKey: String(wrapper.serviceKey || raw.serviceKey || ''),
    environment: String(wrapper.environment || wrapper.environmentName || ''),
    description: String(wrapper.description || wrapper.releaseNote || wrapper.buildNote || ''),
    steps: normalizedSteps,
    logs,
    logText: logs,
  } as Release
}
