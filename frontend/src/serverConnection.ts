import type { DeploymentServer } from './types'

export function serverConnectionLabel(server: DeploymentServer, options: {includeUser?: boolean; includeAuth?: boolean} = {}) {
  if (server.connectionMode === 'SSH_CONFIG') return `SSH 配置 · ${server.host}`
  const address = `${options.includeUser === false ? '' : `${server.username}@`}${server.host}:${server.port}`
  return options.includeAuth ? `${address} · ${server.authType === 'PASSWORD' ? '密码' : 'SSH Key'}` : address
}
