import { currentLocale, tUi } from './i18n'
import feedback from './locales/feedback.json'
import axios from 'axios'
import type { Release, ReleaseStep, Service } from './types'
import { clearAuth } from './auth'
import { normalizedMobileBuild } from './mobile'
import { normalizeBuildEngine } from './buildEngine'

export const http = axios.create({ baseURL: '/api', timeout: 30000 })
let redirectingToLogin = false

http.interceptors.request.use((config) => {
  config.headers['Accept-Language'] = currentLocale()
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

const feedbackByCode: Record<string, string> = {
  ACCOUNT_DISABLED: '账号已停用或不存在',
  SESSION_EXPIRED: '登录已过期，请重新登录',
  APP_INTEGRATION_ACCESS_DENIED: 'App 对接凭据无效、已撤销或没有当前操作权限',
  DELIVERY_ACCESS_DENIED: '分发凭证无效、已过期或没有当前操作权限',
  TOTP_SETUP_REQUIRED: '首次登录请先绑定 Google Authenticator',
  TOTP_INVALID: '动态码不正确，请输入手机上当前显示的 6 位数字',
  TOTP_REUSED: '该动态码已用于登录或其他确认，请等待手机显示新的动态码后重试',
  TOTP_LOCKED: '动态码连续错误次数过多，请在倒计时结束后重试',
  PERMISSION_DENIED: '没有执行当前操作的权限',
  VALIDATION_FAILED: '输入验证未通过',
  INVALID_ARGUMENT: '输入验证未通过',
  INVALID_REQUEST: '提交内容格式不正确，请刷新页面后重试',
  METHOD_NOT_ALLOWED: '当前接口不支持此操作',
  DATA_CONFLICT: '数据正在被其他操作修改或存在重复记录，请刷新后重试',
  DEPLOY_ACTIVE: '无法确认请求结果',
  DEPLOY_REVIEW_REQUIRED: '请核对目标节点最近发布的版本后再次确认',
  REMOTE_AUTH_FAILED: '远程认证失败，请检查配置的账号、密码或 SSH 密钥',
  REMOTE_PERMISSION_DENIED: '文件或目录权限不足，请检查 SSH 用户、配置文件读取权限及 sudo 设置',
  REMOTE_UNREACHABLE: '无法连接目标服务，请检查地址、端口及网络设置',
  OPERATION_TIMEOUT: '操作超时，请检查网络及目标服务状态后重试',
  CONNECT_TIMEOUT: 'SSH 连接超时，请检查节点网络、代理配置及 SSH 服务状态。',
  HOST_KEY: 'SSH 主机指纹未受信任或已变化，请由运维核对并准备 known_hosts；平台不会自动接受新指纹。',
  SSH_AUTH: 'SSH 登录认证失败，请检查目标机账号、私钥、OS Login 配置及文件权限。',
  REMOTE_COMMAND: '远程命令执行失败，请先核对目标状态',
  REMOTE_CHECK_FAILED: '远程连接检查失败',
  IAP_PERMISSION: 'Google/IAP 权限不足，请核对机器身份、项目、实例及 IAP 隧道授权。',
  TUNNEL_FAILED: 'IAP 隧道未建立，请核对项目、区域、实例、网络出口及 IAP 到目标 SSH 端口的防火墙规则。',
  INTERNAL_ERROR: '操作未完成，请稍后重试；如持续出现，请联系管理员',
}

const feedbackByStatus: Record<number, string> = {
  400: '输入验证未通过', 401: '请先登录', 403: '没有执行当前操作的权限',
  404: '未找到请求的资源', 405: '当前接口不支持此操作', 409: '无法确认请求结果',
  413: '请求数据过大', 415: '文件或请求格式不受支持', 422: '输入验证未通过',
  429: '请求过于频繁，请稍后重试', 500: '服务器处理失败，请稍后重试或查看平台日志',
  502: '远程连接检查失败', 504: '操作超时，请检查网络及目标服务状态后重试',
}

export function errorText(error: unknown, fallback = tUi('请求失败，请稍后重试')) {
  const e = error as { response?: { status?: number; data?: { code?: unknown; reference?: unknown; message?: unknown; error?: unknown } }; message?: string } | null
  if (!e?.response) {
    if (e?.message === 'Network Error') return tUi('无法连接平台，请检查网络后重试')
    if (/timeout.*exceeded/i.test(e?.message || '')) return tUi('请求超时，请稍后重试；任务可能已经创建，请先检查任务记录')
    return e?.message || fallback
  }
  const { data, status } = e.response
  const message = typeof data?.message === 'string' && data.message ? data.message : typeof data?.error === 'string' ? data.error : ''
  const code = typeof data?.code === 'string' ? data.code : ''
  const reference = typeof data?.reference === 'string' ? data.reference : ''
  const active = code === 'DEPLOY_ACTIVE' && /^该制品在所选节点已有发布任务，请查看任务 #(\d+)$/.exec(message)
  let result: string
  if (active) result = tUi('该制品在所选节点已有发布任务，请查看任务 #{0}', active[1])
  else if (Object.prototype.hasOwnProperty.call(feedback, message)) result = tUi(message)
  else {
    const knownCode = Object.prototype.hasOwnProperty.call(feedbackByCode, code)
    const summary = (knownCode ? feedbackByCode[code] : undefined) ?? feedbackByStatus[status ?? 0]
    result = summary ? tUi(summary) : fallback
    if (message && message !== 'Internal Server Error') {
      result = currentLocale() === 'zh-CN' ? message : tUi('{0} 服务器返回：{1}', result, message)
    }
    if (code && !knownCode) result = tUi('{0}（错误码：{1}）', result, code)
  }
  return reference ? tUi('{0}（参考编号：{1}）', result, reference) : result
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
  get GIT_PULL() { return tUi('拉取代码') }, get BUILD_JAR() { return tUi('构建应用') }, get BUILD_IMAGE() { return tUi('构建 Docker 镜像') },
  get PUSH_IMAGE() { return tUi('推送镜像仓库') }, get SSH_CONNECT() { return tUi('连接远程服务器') }, get START_SERVICE() { return tUi('启动服务') }, get HEALTH_CHECK() { return tUi('健康检查') },
  get BUILD_APK() { return tUi('构建 Android APK') },get VALIDATE_APK() { return tUi('校验 APK 安装包') },get BUILD_AAB() { return tUi('构建 Android AAB') }, get VALIDATE_AAB() { return tUi('校验安装包') }, get ARCHIVE_ARTIFACT() { return tUi('归档安装包') },
}

export function stepLabel(code: string, serviceType?: string) {
  if (code === 'BUILD_JAR') return serviceType === 'FRONTEND' ? tUi('构建 Node 前端') : serviceType === 'BACKEND' ? tUi('构建 Spring Boot JAR') : tUi('构建应用')
  return stepNames[code] || code
}

function normalizeStep(step: Record<string, unknown>, serviceType?: string): ReleaseStep {
  const code=String(step.code || step.stepType || '')
  const name = stepNames[code] ? stepLabel(code, serviceType) : String(step.name || step.stepName || code)
  const rawLog = String(step.log || step.logText || '')
  const log = normalizeTaskLog(rawLog, serviceType)
  return { ...step, code, name, stepName: name, log, logText: log, rawLog } as ReleaseStep
}

function cleanLog(value: string) { return value.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g, '') }

export function normalizeTaskLog(value: string, serviceType?: string) {
  // Only complete legacy platform marker lines are relabeled; command/source output is preserved.
  return cleanLog(value).replace(
    /^开始：(GIT_PULL|BUILD_JAR|BUILD_IMAGE|PUSH_IMAGE|SSH_CONNECT|START_SERVICE|HEALTH_CHECK|BUILD_AAB|VALIDATE_AAB|BUILD_APK|VALIDATE_APK|ARCHIVE_ARTIFACT)(?=\r?$)/gm,
    (_line, code: string) => tUi('开始：{0}', stepLabel(code, serviceType)),
  )
}

export function normalizeRelease(raw: Record<string, unknown>): Release {
  const wrapper = raw.task && typeof raw.task === 'object' ? raw.task as Record<string, unknown> : raw
  const rawSteps = (Array.isArray(raw.steps) ? raw.steps : Array.isArray(wrapper.steps) ? wrapper.steps : []) as Record<string, unknown>[]
  const serviceType=String(wrapper.serviceType || raw.serviceType || '').toUpperCase()
  const normalizedSteps = rawSteps.map(step=>normalizeStep(step,serviceType))
  const rawLog = String(wrapper.logs || raw.logs || wrapper.logText || raw.logText || normalizedSteps.map(step => step.rawLog).filter(Boolean).join('\n') || '')
  const logs = normalizeTaskLog(rawLog, serviceType)
  return {
    ...wrapper,
    id: String(wrapper.id || ''),
    serviceType: (['FRONTEND','BACKEND','FLUTTER_ANDROID'].includes(serviceType) ? serviceType : undefined) as Release['serviceType'],
    serviceName: String(wrapper.serviceName || raw.serviceName || (wrapper.serviceId ? tUi('服务 #{0}', wrapper.serviceId) : '')),
    serviceKey: String(wrapper.serviceKey || raw.serviceKey || ''),
    environment: String(wrapper.environment || wrapper.environmentName || ''),
    description: String(wrapper.description || wrapper.releaseNote || wrapper.buildNote || ''),
    steps: normalizedSteps,
    logs,
    logText: logs,
    rawLog,
  } as Release
}
