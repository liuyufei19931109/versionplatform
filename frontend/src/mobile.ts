import type { Artifact, MobileBuildConfig, MobileEnvironmentName, MobileEnvironmentProfile, Service } from './types'

export const mobileEnvironments: Array<{ name: MobileEnvironmentName; label: string }> = [{ name: 'dev', label: '开发' }, { name: 'test', label: '预生产' }, { name: 'prod', label: '生产' }]
export const mobileParameterGroups = ['dartDefines', 'environmentVariables'] as const
export type MobileParameterGroup = typeof mobileParameterGroups[number]

export function mobileParameterError(group: MobileParameterGroup, key: string, value: string) {
  if (key.length > 80) return '参数名最多 80 个字符'
  if (group === 'environmentVariables' && !/^APP_BUILD_[A-Z0-9_]+$/.test(key)) return '构建环境变量名须以 APP_BUILD_ 开头，后接大写字母、数字或下划线'
  if (group === 'dartDefines' && !/^[A-Za-z][A-Za-z0-9_]*$/.test(key)) return 'Dart 参数名须以字母开头，使用字母、数字或下划线'
  if (/password|passwd|secret|token|private_key|api_key|signing|keystore|credential/i.test(key)) return '请勿填写密码、签名或密钥参数'
  if (typeof value !== 'string' || value.length > 2048 || /[\u0000-\u001f\u007f-\u009f]/.test(value)) return '参数值最多 2048 个字符，且不能包含控制字符'
  return ''
}

export function mobileParameterGroupError(group: MobileParameterGroup, entries: Record<string, string> = {}) {
  if (Object.keys(entries).length > 40) return '每组最多添加 40 个参数'
  for (const [key, value] of Object.entries(entries)) {
    const error = mobileParameterError(group, key, value)
    if (error) return key + '：' + error
  }
  return ''
}

export function mobileBuildDefaults(format: 'APK' | 'AAB' = 'AAB'): MobileBuildConfig {
  return { outputFormat: format, apkPath:'', flutterVersion: '', applicationId: '', expectedCertificateSha256: '', flavor: '', target: 'lib/main.dart', versionName: '', signingProfile: '', buildTimeoutSeconds: 600, aabPath: 'build/app/outputs/bundle/release/app-release.aab', environments: {} }
}
export function mobileEnvironmentDefaults(name: MobileEnvironmentName): MobileEnvironmentProfile {
  return { enabled: false, applicationId: '', expectedCertificateSha256: '', signingProfile: '', buildMode: name === 'prod' ? 'RELEASE' : 'DEBUG', allowDebugSigning: false, flavor: '', target: '', versionName: '', apkPath: '', aabPath: '', dartDefines: {}, environmentVariables: {} }
}
export function mobileFormat(value?: MobileBuildConfig) { return value?.outputFormat === 'APK' ? 'APK' : 'AAB' }
export function automaticMobileVersion(value?: MobileBuildConfig) { return value?.versionPolicy === 'AUTO' }
export function mobileVersionLabel(value?: { versionName?: string; version?: string }) { return value?.versionName || value?.version?.replace(/\s*\(?\s*#\d+\)?\s*$/, '') || '版本未记录' }
export function mobileEnvironment(value: MobileBuildConfig | undefined, name: string) { return value?.environments?.[name as MobileEnvironmentName] }
export function mobileEnabledEnvironments(value?: MobileBuildConfig) { return mobileEnvironments.filter(env => mobileEnvironment(value, env.name)?.enabled) }
export function mobileMissingFields(value?: MobileBuildConfig) {
  const missing: string[] = []
  if (!value?.flutterVersion?.trim()) missing.push('Flutter 版本')
  if (!mobileEnabledEnvironments(value).length) missing.push('至少一个环境的包名与签名配置')
  return missing
}

export function isMobileDraft(service: Service) {
  return service.serviceType === 'FLUTTER_ANDROID'
    && (service.status === false || service.status === 'DISABLED')
    && Boolean(mobileBuildError(service.mobileBuild))
}

export function mobileEnvironmentError(value: MobileBuildConfig | undefined, name: string) {
  const profile = mobileEnvironment(value, name)
  if (!profile?.enabled) return '请先启用并配置该构建环境'
  if (!/^[a-zA-Z]\w*(?:\.[a-zA-Z]\w*)+$/.test(profile.applicationId?.trim() || '')) return '请填写有效的 Android applicationId，例如 com.example.app'
  if (!profile.signingProfile?.trim()) return '请选择已登记的签名配置'
  if (!/^[a-fA-F0-9]{64}$/.test((profile.expectedCertificateSha256 || '').replace(/[:\s]/g, ''))) return '请填写签名证书的 SHA-256 指纹（64 位十六进制）'
  if (!['DEBUG', 'RELEASE'].includes(profile.buildMode)) return '请选择构建模式'
  if (name === 'prod' && (profile.buildMode !== 'RELEASE' || profile.allowDebugSigning)) return '生产环境必须使用 Release 构建和正式签名'
  if (profile.buildMode === 'DEBUG' && mobileFormat(value) !== 'APK') return 'Debug 构建只支持完整 APK，请切换格式或使用 Release'
  if (profile.buildMode === 'DEBUG' && !profile.allowDebugSigning) return 'Debug 构建需明确允许使用已登记的测试证书'
  if (profile.buildMode === 'RELEASE' && profile.allowDebugSigning) return 'Release 构建不允许 Debug 测试证书，请关闭 Debug 许可'
  if (mobileFormat(value) === 'AAB' && profile.allowDebugSigning) return 'AAB 仅支持正式签名，请关闭允许 Debug 测试证书'
  for (const group of mobileParameterGroups) {
    const error = mobileParameterGroupError(group, profile[group])
    if (error) return error
  }
  return ''
}

export function mobileBuildError(value?: MobileBuildConfig) {
  if (value?.outputFormat && !['APK','AAB'].includes(value.outputFormat)) return '请选择有效的安装包格式：APK 或 AAB'
  if (!value?.flutterVersion?.trim()) return '请填写项目使用的固定 Flutter 版本'
  if (!/^\d+\.\d+\.\d+(?:-[\w.]+)?$/.test(value.flutterVersion.trim())) return 'Flutter 版本需明确到版本号，例如 3.35.7，不能使用 latest 或 stable'
  if (!value.buildTimeoutSeconds || value.buildTimeoutSeconds < 60 || value.buildTimeoutSeconds > 7200) return 'App 构建超时需在 60 至 7200 秒之间'
  const enabled = mobileEnabledEnvironments(value)
  if (!enabled.length) return '请至少完成并启用一个 App 构建环境；旧的全局签名不会自动用于生产'
  for (const env of enabled) { const error = mobileEnvironmentError(value, env.name); if (error) return `${env.label}：${error}` }
  const production = value.environments?.prod
  if (production?.enabled) for (const env of enabled.filter(item => item.name !== 'prod')) {
    const profile = value.environments![env.name]!
    if (production.applicationId === profile.applicationId) return '生产与非生产环境必须使用不同的 Android 包名'
    if (production.signingProfile === profile.signingProfile || production.expectedCertificateSha256.replace(/[:\s]/g, '').toLowerCase() === profile.expectedCertificateSha256.replace(/[:\s]/g, '').toLowerCase()) return '生产与非生产环境必须使用不同的签名配置和证书'
  }
  return ''
}
export function mobileArtifactEnvironmentError(artifact?: Pick<Artifact, 'buildEnvironmentName' | 'buildMode'>, target?: string) {
  if (!artifact || !mobileEnvironments.some(env => env.name === artifact.buildEnvironmentName)) return '该历史安装包没有记录构建环境，请选择环境重新打包后再创建交付；已有记录仍可查看'
  if (target && artifact.buildEnvironmentName !== target) return '发布环境必须与安装包的构建环境一致，请为目标环境重新打包'
  if (artifact.buildEnvironmentName === 'prod' && artifact.buildMode !== 'RELEASE') return '生产安装包必须具有 Release 构建记录，请重新构建'
  return ''
}
export function normalizedMobileBuild(value: MobileBuildConfig): MobileBuildConfig {
  // Keep legacy flat fields unchanged. Only explicit environment profiles govern new builds.
  return { ...value, environments: Object.fromEntries(Object.entries(value.environments || {}).map(([name, profile]) => [name, { ...profile, expectedCertificateSha256: (profile.expectedCertificateSha256 || '').replace(/[:\s]/g, '').toLowerCase(), dartDefines: { ...profile.dartDefines }, environmentVariables: { ...profile.environmentVariables } }])) }
}
