import { useAppScope } from './useAppScope'
import { computed, onBeforeUnmount, ref } from 'vue'
import { http, payload } from '../api'
import { authUser } from '../auth'

interface Approval { approver?: string; enabled?: boolean; serverTime?: string; nextCodeAvailableAt?: string; retryAfterSeconds?: number }
export function useProductionApproval() {
  const { api } = useAppScope()
  const enabled = ref(false), loading = ref(false), unavailable = ref(false), deadline = ref(0), now = ref(Date.now())
  const account = ref(''), approver = computed(() => account.value || authUser.value?.username || '当前账号')
  const remaining = computed(() => Math.max(0, Math.ceil((deadline.value - now.value) / 1000)))
  const timer = window.setInterval(() => { now.value = Date.now() }, 500)
  function apply(value: Approval) {
    if (typeof value.approver === 'string') account.value = value.approver
    if (typeof value.enabled === 'boolean') enabled.value = value.enabled
    const server = Date.parse(value.serverTime || ''), next = Date.parse(value.nextCodeAvailableAt || '')
    const seconds = typeof value.retryAfterSeconds === 'number' ? value.retryAfterSeconds : Number.isFinite(server) && Number.isFinite(next) ? Math.max(0, (next - server) / 1000) : 0
    now.value = Date.now(); deadline.value = now.value + seconds * 1000
  }
  async function refresh() { loading.value = true; unavailable.value = false; account.value = ''; try { apply(payload<Approval>(await http.get(api('/releases/production-approval')), {})) } catch { unavailable.value = true } finally { loading.value = false } }
  function handleError(error: unknown) { const data = (error as { response?: { data?: Approval } }).response?.data; if (data) apply(data) }
  onBeforeUnmount(() => window.clearInterval(timer))
  return { approver, enabled, loading, unavailable, remaining, refresh, handleError }
}
