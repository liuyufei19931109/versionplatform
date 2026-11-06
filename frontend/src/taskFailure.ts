import { tUi } from './i18n'
import type { Release } from './types'
import { stepLabel } from './api'

export function deploymentTargetIds(task: Release): string[] {
  const raw = String(task.targetServerIds || '').trim()
  if (!raw) return []
  const ids = raw.split(',').map(id => id.trim())
  return ids.every(id => /^[1-9]\d*$/.test(id)) ? Array.from(new Set(ids)) : []
}

export function failedTargetId(task: Release): string | undefined {
  if (task.status !== 'FAILED') return
  const failed = /^\[失败节点 #([1-9]\d*)\] /.exec(String(task.errorMessage || ''))?.[1]
  return failed && deploymentTargetIds(task).includes(failed) ? failed : undefined
}

export function retryDeploymentScope(task: Release): string {
  const next = task.steps?.find(step => step.status !== 'SUCCESS')
  const first = next?.name || (next?.code ? stepLabel(next.code, task.serviceType) : '')
  const ids = deploymentTargetIds(task)
  const nodes = ids.length ? tUi('{0}（共 {1} 台）', ids.map(id => '#' + id).join('、'), ids.length) : tUi('当前环境配置的全部节点（历史任务未记录节点）')
  return tUi('{0}，本次操作 {1}。', first ? tUi('将从「') + first + tUi('」开始') : tUi('将继续执行未完成步骤'), nodes)
}

export function taskFailureGuide(task: Release) {
  const failed = task.steps?.find(step => step.status === 'FAILED')
  const code = String(failed?.code || task.currentStep || '')
  const message = String(task.errorMessage || '')
  const baseline = /基准分支|必须包含.*master|未包含.*master|不包含.*master/.test(message)
  if (baseline) return {
    title: tUi('分支基线校验未通过'),
    hint: tUi('先将最新 master 合入待构建分支，再新建打包并确认最新 Commit。重试原任务仍使用原来锁定的代码。'),
    newBuild: true, alternativeBuild: false,
  }
  const hints: Record<string, string> = {
    get GIT_PULL() { return tUi('先查看拉取步骤的错误，核对分支、Commit 和仓库访问权限。修改代码后请新建打包，重试仍使用本次锁定的 Commit。') },
    get BUILD_JAR() { return tUi('先查看构建日志中的首个错误。代码需要修改时，请修复并提交后新建打包；下载依赖等临时问题恢复后，可重试本任务。') },
    get BUILD_IMAGE() { return tUi('查看镜像构建日志，核对 Dockerfile、构建上下文和基础镜像访问情况。修改代码后请新建打包。') },
    get PUSH_IMAGE() { return tUi('查看推送日志，核对镜像仓库权限和网络。恢复后可重试推送，已成功完成的步骤会保留。') },
    get SSH_CONNECT() { return tUi('查看连接步骤日志，核对目标服务器、登录凭据和连接通道。连接恢复后再重试发布。') },
    get START_SERVICE() { return tUi('服务可能已执行部分启动操作。先核对目标容器状态，再根据日志修正配置或重试发布。') },
    get HEALTH_CHECK() { return tUi('服务可能已经启动。先核对容器状态、应用启动日志和健康检查地址；检查失败不代表代码没有部署。') },
    get BUILD_APK() { return tUi('查看 Android 构建的首个错误，核对依赖、构建工具和签名配置。普通版本升级警告不一定是失败原因。') },
    get BUILD_AAB() { return tUi('查看 Android 构建的首个错误，核对依赖、构建工具和签名配置。普通版本升级警告不一定是失败原因。') },
    get VALIDATE_APK() { return tUi('核对所选环境的包名、签名证书和版本信息。校验未通过的安装包不能交付。') },
    get VALIDATE_AAB() { return tUi('核对所选环境的包名、签名证书和版本信息。校验未通过的安装包不能交付。') },
    get ARCHIVE_ARTIFACT() { return tUi('查看归档日志，核对构建机磁盘空间、目录权限及安装包是否存在。') },
  }
  return {
    title: code ? tUi('{0}未完成', stepLabel(code, task.serviceType)) : tUi('任务未完成'),
    hint: hints[code] || tUi('先查看错误日志，确认原因后再重试。重试使用本次任务锁定的代码和目标。'),
    newBuild: false,
    alternativeBuild: ['GIT_PULL', 'BUILD_JAR', 'BUILD_IMAGE', 'BUILD_APK', 'BUILD_AAB'].includes(code),
  }
}
