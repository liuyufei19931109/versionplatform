export interface PreBuildCandidate {
  buildTaskId: number; artifactId: number; versionName: string; versionCode: number; baseVersion?: string
  branch: string; commitId: string; createdAt: string; applicationId: string
}
export interface MobileVersionPreview {
  versionPolicy: 'AUTO' | 'MANUAL'; environmentName: string; versionName?: string; versionCode?: number
  baseVersion?: string; preBuildTaskId?: number; allocationPending: boolean; message?: string
}
export function preBuildCandidates(value: unknown): PreBuildCandidate[] {
  if (!Array.isArray(value)) throw new Error('PRE 版本列表返回不完整，请刷新重试')
  return value.filter(item => item && Number.isSafeInteger(item.buildTaskId) && item.buildTaskId > 0 && Number.isSafeInteger(item.artifactId) && item.artifactId > 0 && typeof item.versionName === 'string' && item.versionName.trim() && typeof item.branch === 'string' && item.branch.trim() && /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/i.test(item.commitId || ''))
}
export function readyVersionPreview(value: MobileVersionPreview | undefined, environmentName: string, preBuildTaskId?: number) {
  return Boolean(value?.versionPolicy === 'AUTO' && value.environmentName === environmentName && value.versionName?.trim() && value.allocationPending === true && (environmentName !== 'prod' || value.preBuildTaskId === preBuildTaskId && preBuildTaskId))
}
