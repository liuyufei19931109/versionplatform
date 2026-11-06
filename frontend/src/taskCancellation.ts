import type { Release } from './types'
import { authUser } from './auth'

export const runningCancellationMessage = '停止后续步骤；已经执行的远端操作不会自动撤销，取消后请检查运行状态。'
export function cancellableTask(task: Release, build: boolean) {
  return (build || ownsTask(task)) && (build || task.deliveryType !== 'MOBILE') && (task.status === 'PENDING' || ((!build || task.mobileBuildEngine === 'GITHUB' || task.frontendBuildEngine === 'GITHUB' || task.backendBuildEngine === 'GITHUB') && task.status === 'RUNNING'))
}
export function ownsTask(task: {createdBy?: string}) { return Boolean(authUser.value?.admin || (task.createdBy && authUser.value?.username === task.createdBy)) }
export function cancellationLabel(status?: string) { return status === 'RUNNING' ? '中止后续步骤' : '取消排队' }
export function cancellationPrompt(status?: string) { return status === 'RUNNING' ? runningCancellationMessage : '任务尚未开始。确认取消排队？' }
