import { Box, User, Document, Cpu, List, Grid, Refresh, Collection, DataAnalysis, CircleCheck, Bell, Folder, Setting, Coin, Connection, Share, Monitor, Operation, Iphone } from '@element-plus/icons-vue'

export const serviceIcons = [
  { key: 'server', label: '通用服务', component: Box, color: '#65758d', background: '#edf1f6' },
  { key: 'users', label: '用户', component: User, color: '#7b57bb', background: '#f1ecfb' },
  { key: 'document', label: '文档', component: Document, color: '#4776c5', background: '#edf3fd' },
  { key: 'compute', label: '计算', component: Cpu, color: '#a26d24', background: '#fbf2e3' },
  { key: 'tasks', label: '任务', component: List, color: '#416bdc', background: '#edf2ff' },
  { key: 'package', label: '软件包', component: Box, color: '#a76d4b', background: '#faf0e9' },
  { key: 'cluster', label: '集群', component: Grid, color: '#65758d', background: '#edf1f6' },
  { key: 'sync', label: '同步', component: Refresh, color: '#16849c', background: '#e8f5f8' },
  { key: 'storage', label: '存储', component: Collection, color: '#a26d24', background: '#fbf2e3' },
  { key: 'reports', label: '报表', component: DataAnalysis, color: '#4776c5', background: '#edf3fd' },
  { key: 'approval', label: '审批', component: CircleCheck, color: '#2c8770', background: '#e7f4ef' },
  { key: 'notifications', label: '消息', component: Bell, color: '#a26d24', background: '#fbf2e3' },
  { key: 'files', label: '文件', component: Folder, color: '#4776c5', background: '#edf3fd' },
  { key: 'settings', label: '设置', component: Setting, color: '#65758d', background: '#edf1f6' },
  { key: 'database', label: '数据库', component: Coin, color: '#7b57bb', background: '#f1ecfb' },
  { key: 'api', label: '接口', component: Connection, color: '#16849c', background: '#e8f5f8' },
  { key: 'gateway', label: '网关', component: Share, color: '#4776c5', background: '#edf3fd' },
  { key: 'website', label: '网站', component: Monitor, color: '#16849c', background: '#e8f5f8' },
  { key: 'operations', label: '运营', component: Operation, color: '#7b57bb', background: '#f1ecfb' },
  { key: 'mobile', label: 'App', component: Iphone, color: '#2c8770', background: '#e7f4ef' },
] as const

export function serviceIcon(key?: string, serviceType?: string) {
  const explicit = serviceIcons.find(icon => icon.key === key)
  if (explicit) return explicit
  const fallback = serviceType === 'FLUTTER_ANDROID' ? 'mobile' : serviceType === 'FRONTEND' ? 'website' : 'server'
  return serviceIcons.find(icon => icon.key === fallback)!
}
