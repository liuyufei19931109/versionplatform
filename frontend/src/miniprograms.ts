import { tUi } from './i18n'
export interface MiniProject {
  id: number; name: string; code: string; appId: string; gitRepo: string; defaultBranch: string;
  projectDirectory: string; robot: number; runChecks: boolean; enabled: boolean
}
export interface MiniJob {
  id: number; projectId: number; projectName: string; appId: string; action: 'PREVIEW' | 'UPLOAD';
  branch: string; commitId: string; version: string; note?: string; createdBy: string;
  status: string; stage: string; baselineCommit?: string; errorMessage?: string; logText?: string;
  createdAt: string; startedAt?: string; finishedAt?: string
  resolution?: 'CONFIRMED_UPLOADED'|'CONFIRMED_NOT_UPLOADED'; resolvedBy?: string; resolvedAt?: string; resolutionNote?: string
}
export interface MiniPreparation {
  localReady: boolean; previewReady: boolean; uploadReady: boolean; checkedAt: string;
  checks: { code: string; label: string; status: 'OK'|'ERROR'|'INFO'|'MANUAL'; message: string }[]
}
export interface MiniHistory { items: MiniJob[]; total: number; page: number; size: number }
export const miniStages: Record<string,string> = {
  get QUEUED() { return tUi('等待执行') }, get BASELINE_CHECK() { return tUi('校验基准分支') }, get GIT_PULL() { return tUi('拉取代码') }, get CHECK() { return tUi('代码检查') }, get WECHAT_TRANSFER() { return tUi('微信编译与传输') }, get COMPLETE() { return tUi('已完成') },
}
export function miniStatus(job: MiniJob) {
  if (job.status === 'UNKNOWN' && job.resolution) return job.resolution === 'CONFIRMED_UPLOADED' ? tUi('已核对 · 微信已收到') : tUi('已核对 · 微信未收到')
  if (job.status === 'SUCCESS') return job.action === 'PREVIEW' ? tUi('预览码已生成') : tUi('已上传 · 待提审')
  if (job.status === 'UNKNOWN' && job.action === 'PREVIEW') return tUi('预览结果未确认')
  return ({ PENDING: tUi('排队中'), RUNNING: tUi('执行中'), FAILED: tUi('失败'), CANCELLED: tUi('已取消'), UNKNOWN: tUi('微信结果待核对') } as Record<string,string>)[job.status] || job.status
}
export const miniActive = (job: MiniJob) => ['PENDING','RUNNING'].includes(job.status)
