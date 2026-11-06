import { tUi } from './i18n'
import type { BuildEngine } from './types'

export function normalizeBuildEngine(value: unknown): BuildEngine {
  return String(value || '').trim().toUpperCase() === 'LOCAL' ? 'LOCAL' : 'GITHUB'
}

export function buildEngineLabel(value: unknown) {
  return normalizeBuildEngine(value) === 'LOCAL' ? tUi('本机构建') : 'GitHub Actions'
}
