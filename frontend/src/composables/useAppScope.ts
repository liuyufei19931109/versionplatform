import { useRoute } from 'vue-router'
import { hasMenu } from '../auth'

// App-only accounts stay within server-enforced App endpoints and routes.
export function useAppScope() {
  const route = useRoute()
  const apps = route.meta.apps === true
  const api = (path: string) => apps ? `/apps${path}` : path
  const can = (permission: string) => hasMenu(apps ? 'apps' : permission)
  const buildPath = (id: string | number) => `${apps ? '/apps' : ''}/builds/${id}`
  const releasePath = (id: string | number) => `${apps ? '/apps' : ''}/releases/${id}`
  const servicePath = (id: string | number, tab = '') => `${apps ? '/apps' : '/services'}/${id}${tab ? `?tab=${tab}` : ''}`
  return { apps, api, can, buildPath, releasePath, servicePath }
}
