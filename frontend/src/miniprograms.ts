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
  QUEUED: '等待执行', BASELINE_CHECK: '校验基准分支', GIT_PULL: '拉取代码', CHECK: '代码检查', WECHAT_TRANSFER: '微信编译与传输', COMPLETE: '已完成',
}
export function miniStatus(job: MiniJob) {
  if (job.status === 'UNKNOWN' && job.resolution) return job.resolution === 'CONFIRMED_UPLOADED' ? '已核对 · 微信已收到' : '已核对 · 微信未收到'
  if (job.status === 'SUCCESS') return job.action === 'PREVIEW' ? '预览码已生成' : '已上传 · 待提审'
  if (job.status === 'UNKNOWN' && job.action === 'PREVIEW') return '预览结果未确认'
  return ({ PENDING: '排队中', RUNNING: '执行中', FAILED: '失败', CANCELLED: '已取消', UNKNOWN: '微信结果待核对' } as Record<string,string>)[job.status] || job.status
}
export const miniActive = (job: MiniJob) => ['PENDING','RUNNING'].includes(job.status)
