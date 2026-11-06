export interface GitEventService {
  id: number
  name: string
  serviceKey: string
  serviceType: string
  canBuild: boolean
}

export interface GitEvent {
  id: number
  services: GitEventService[]
  branch: string
  commitId: string
  summary: string
  actor: string
  receivedAt: string
  eventUrl: string
  unread: boolean
}

export interface GitEventFeed {
  enabled: boolean
  unread: boolean
  latestEventId: number | null
  items: GitEvent[]
}

export function emptyGitEvents(): GitEventFeed {
  return { enabled: false, unread: false, latestEventId: null, items: [] }
}

export function githubEventUrl(value: unknown): string {
  try {
    const url = new URL(String(value || ''))
    return url.protocol === 'https:' && url.hostname === 'github.com' && !url.port && !url.username && !url.password && !url.search && !url.hash && /^\/[A-Za-z0-9][A-Za-z0-9-]*\/[A-Za-z0-9_.-]+\/(commit\/[a-fA-F0-9]{40}|pull\/[1-9]\d*)$/.test(url.pathname) ? url.href : ''
  } catch { return '' }
}

export function normalizeGitEvents(value: unknown): GitEventFeed {
  if (!value || typeof value !== 'object' || typeof (value as GitEventFeed).enabled !== 'boolean' || !Array.isArray((value as GitEventFeed).items)) throw new Error('Git 动态返回格式不完整')
  const raw = value as Partial<GitEventFeed>
  if (!raw.enabled) return emptyGitEvents()
  const items = (Array.isArray(raw.items) ? raw.items : []).flatMap(item => {
    if (!item || !Number.isSafeInteger(item.id) || item.id < 1 || !Array.isArray(item.services)) return []
    const services = item.services.filter(service => service && Number.isSafeInteger(service.id) && service.id > 0).map(service => ({
      id: service.id, name: String(service.name || service.serviceKey || '未命名服务'), serviceKey: String(service.serviceKey || ''),
      serviceType: String(service.serviceType || ''), canBuild: service.canBuild === true,
    }))
    if (!services.length) return []
    return [{ id: item.id, services, branch: String(item.branch || ''), commitId: String(item.commitId || ''),
      summary: String(item.summary || ''), actor: String(item.actor || ''), receivedAt: String(item.receivedAt || ''),
      eventUrl: githubEventUrl(item.eventUrl), unread: item.unread === true }]
  }).sort((a, b) => {
    const time = (Date.parse(b.receivedAt) || 0) - (Date.parse(a.receivedAt) || 0)
    return time || b.id - a.id
  }).slice(0, 5)
  return { enabled: raw.enabled === true, unread: raw.unread === true, latestEventId: Number.isSafeInteger(raw.latestEventId) ? Number(raw.latestEventId) : null, items }
}

export function gitEventBuildRoute(service: GitEventService, branch: string, commitId?: string) {
  if (!service.canBuild || !branch) return undefined
  const observed = commitId && /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i.test(commitId) ? { noticedCommit: commitId } : {}
  return service.serviceType === 'FLUTTER_ANDROID'
    ? { path: `/apps/${service.id}/build`, query: { branch, ...observed } }
    : { path: '/build/new', query: { serviceId: String(service.id), branch, ...observed } }
}

export function gitEventReadThrough(items: GitEvent[]) {
  return items.reduce((through, item) => Math.max(through, item.id), 0)
}

export function gitEventTime(value: string, now = Date.now()) {
  const timestamp = Date.parse(value)
  if (!Number.isFinite(timestamp)) return '时间未知'
  const elapsed = Math.max(0, now - timestamp)
  if (elapsed < 60_000) return '刚刚'
  if (elapsed < 3_600_000) return `${Math.floor(elapsed / 60_000)} 分钟前`
  if (elapsed < 86_400_000) return `${Math.floor(elapsed / 3_600_000)} 小时前`
  return new Date(timestamp).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}
