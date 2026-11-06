import { tUi } from './i18n'
import { Box, User, Document, Cpu, List, Grid, Refresh, Collection, DataAnalysis, CircleCheck, Bell, Folder, Setting, Coin, Connection, Share, Monitor, Operation, Iphone } from '@element-plus/icons-vue'

export const serviceIcons = [
  { key: 'server', get label() { return tUi('通用服务') }, component: Box, color: '#65758d', background: '#edf1f6' },
  { key: 'users', get label() { return tUi('用户') }, component: User, color: '#7b57bb', background: '#f1ecfb' },
  { key: 'document', get label() { return tUi('文档') }, component: Document, color: '#4776c5', background: '#edf3fd' },
  { key: 'compute', get label() { return tUi('计算') }, component: Cpu, color: '#a26d24', background: '#fbf2e3' },
  { key: 'tasks', get label() { return tUi('任务') }, component: List, color: '#416bdc', background: '#edf2ff' },
  { key: 'package', get label() { return tUi('软件包') }, component: Box, color: '#a76d4b', background: '#faf0e9' },
  { key: 'cluster', get label() { return tUi('集群') }, component: Grid, color: '#65758d', background: '#edf1f6' },
  { key: 'sync', get label() { return tUi('同步') }, component: Refresh, color: '#16849c', background: '#e8f5f8' },
  { key: 'storage', get label() { return tUi('存储') }, component: Collection, color: '#a26d24', background: '#fbf2e3' },
  { key: 'reports', get label() { return tUi('报表') }, component: DataAnalysis, color: '#4776c5', background: '#edf3fd' },
  { key: 'approval', get label() { return tUi('审批') }, component: CircleCheck, color: '#2c8770', background: '#e7f4ef' },
  { key: 'notifications', get label() { return tUi('消息') }, component: Bell, color: '#a26d24', background: '#fbf2e3' },
  { key: 'files', get label() { return tUi('文件') }, component: Folder, color: '#4776c5', background: '#edf3fd' },
  { key: 'settings', get label() { return tUi('设置') }, component: Setting, color: '#65758d', background: '#edf1f6' },
  { key: 'database', get label() { return tUi('数据库') }, component: Coin, color: '#7b57bb', background: '#f1ecfb' },
  { key: 'api', get label() { return tUi('接口') }, component: Connection, color: '#16849c', background: '#e8f5f8' },
  { key: 'gateway', get label() { return tUi('网关') }, component: Share, color: '#4776c5', background: '#edf3fd' },
  { key: 'website', get label() { return tUi('网站') }, component: Monitor, color: '#16849c', background: '#e8f5f8' },
  { key: 'operations', get label() { return tUi('运营') }, component: Operation, color: '#7b57bb', background: '#f1ecfb' },
  { key: 'mobile', label: 'App', component: Iphone, color: '#2c8770', background: '#e7f4ef' },
] as const

export function serviceIcon(key?: string, serviceType?: string) {
  const explicit = serviceIcons.find(icon => icon.key === key)
  if (explicit) return explicit
  const fallback = serviceType === 'FLUTTER_ANDROID' ? 'mobile' : serviceType === 'FRONTEND' ? 'website' : 'server'
  return serviceIcons.find(icon => icon.key === fallback)!
}
