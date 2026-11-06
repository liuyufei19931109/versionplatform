import { tUi } from '../i18n'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { authUser } from '../auth'
import { http, payload } from '../api'
import { completionKey, normalizeTaskActivity, readActivityLedger, unseenCompletions } from '../taskActivity'
import type { ActivityNoticeLedger, ActivityTask, TaskActivity } from '../taskActivity'

const noticePrefix = 'release_task_activity_notices_v1:'
const leasePrefix = 'release_task_activity_lease_v1:'

export function useTaskActivity(onCompleted: (tasks: ActivityTask[]) => void) {
  const feed = ref<TaskActivity | null>(null), loading = ref(false), error = ref(''), clock = ref(Date.now())
  const storageSessionValid = ref(true)
  const available = computed(() => Boolean(authUser.value && !authUser.value.requiresTotpSetup && storageSessionValid.value))
  const scope = computed(() => JSON.stringify([authUser.value?.username, authUser.value?.admin, [...(authUser.value?.permissions || [])].sort(), available.value]))
  const now = computed(() => error.value && feed.value ? Date.parse(feed.value.serverTime) : clock.value + offset)
  let offset = 0, disposed = false, generation = 0, pending: Promise<void> | undefined
  let timer: ReturnType<typeof setTimeout> | undefined, tick: ReturnType<typeof setInterval> | undefined, controller: AbortController | undefined
  let noticePending = false, memoryLedger: ActivityNoticeLedger | null = null
  const tabId = `${Date.now()}-${Math.random().toString(36).slice(2)}`

  function storageGet(key: string) { try { return localStorage.getItem(key) } catch { return null } }
  function storageSet(key: string, value: string) { try { localStorage.setItem(key, value); return true } catch { return false } }
  function stopTimer() { if (timer) clearTimeout(timer); timer = undefined }
  function schedule() { stopTimer(); if (!disposed && available.value && document.visibilityState !== 'hidden') timer = setTimeout(() => { void refresh() }, 5000) }
  function reset() {
    generation++; stopTimer(); controller?.abort(); controller = undefined; pending = undefined
    feed.value = null; loading.value = false; error.value = ''; memoryLedger = null; offset = 0
  }

  async function noticeCompletions(snapshot: TaskActivity, request: number) {
    if (noticePending || disposed || request !== generation || document.visibilityState === 'hidden') return
    const username = authUser.value?.username
    if (!username) return
    const key = noticePrefix + encodeURIComponent(username), lockKey = leasePrefix + encodeURIComponent(username)
    const read = () => readActivityLedger(storageGet(key)) || memoryLedger
    const save = (ledger: ActivityNoticeLedger) => { memoryLedger = ledger; storageSet(key, JSON.stringify(ledger)) }
    if (!read()) {
      // Establish a watermark on first entry; older finished jobs remain available in the panel.
      save({ baseline: Date.parse(snapshot.serverTime), seen: snapshot.recent.map(completionKey).slice(-256) })
      return
    }
    noticePending = true
    const deliver = () => {
      if (disposed || request !== generation || document.visibilityState === 'hidden' || authUser.value?.username !== username) return
      const ledger = read()
      if (!ledger) return
      const tasks = unseenCompletions(snapshot.recent, ledger)
      if (!tasks.length) return
      save({ baseline: ledger.baseline, seen: [...new Set([...ledger.seen, ...tasks.map(completionKey)])].slice(-256) })
      onCompleted(tasks)
    }
    try {
      if (navigator.locks?.request) {
        await navigator.locks.request(lockKey, { ifAvailable: true }, lock => { if (lock) deliver() })
      } else {
        // The public platform can run over HTTP, where Web Locks is unavailable.
        // A short cross-tab lease plus a shared event ledger avoids repeated notices.
        const lease = storageGet(lockKey)
        try { if (lease && Number(JSON.parse(lease).until) > Date.now()) return } catch { /* ignore expired malformed lease */ }
        const ownLease = JSON.stringify({ owner: tabId, until: Date.now() + 5000 })
        if (!storageSet(lockKey, ownLease)) { deliver(); return }
        await new Promise(resolve => setTimeout(resolve, 120))
        if (storageGet(lockKey) === ownLease) {
          deliver()
          if (storageGet(lockKey) === ownLease) { try { localStorage.removeItem(lockKey) } catch { /* storage can be disabled */ } }
        }
      }
    } finally { noticePending = false }
  }

  function refresh(): Promise<void> {
    if (disposed || !available.value || document.visibilityState === 'hidden') return Promise.resolve()
    if (pending) return pending
    stopTimer()
    const request = generation, abort = new AbortController()
    controller = abort; loading.value = true
    pending = (async () => {
      try {
        const snapshot = normalizeTaskActivity(payload(await http.get('/activity', { signal: abort.signal, timeout: 12_000 }), null))
        if (disposed || request !== generation) return
        offset = Date.parse(snapshot.serverTime) - Date.now(); clock.value = Date.now()
        feed.value = snapshot; error.value = ''
        await noticeCompletions(snapshot, request)
      } catch {
        if (!disposed && request === generation && !abort.signal.aborted) error.value = feed.value ? tUi('状态更新中断，当前显示上次结果') : tUi('暂时无法读取任务状态')
      } finally {
        if (!disposed && request === generation) { loading.value = false; pending = undefined; controller = undefined; schedule() }
      }
    })()
    return pending
  }

  function visibilityChanged() { if (document.visibilityState === 'hidden') stopTimer(); else { clock.value = Date.now(); void refresh() } }
  function sessionStorageChanged(event: StorageEvent) {
    if (event.key !== 'release_user' && event.key !== 'release_token') return
    let storedUser: { username?: string; admin?: boolean; permissions?: string[] } = {}
    try { storedUser = JSON.parse(storageGet('release_user') || '{}') } catch { /* clear old account task details */ }
    storageSessionValid.value = !!storageGet('release_token') && storedUser.username === authUser.value?.username && Boolean(storedUser.admin) === Boolean(authUser.value?.admin) && JSON.stringify([...(storedUser.permissions || [])].sort()) === JSON.stringify([...(authUser.value?.permissions || [])].sort())
    reset(); if (storageSessionValid.value) void refresh()
  }
  watch(scope, () => { reset(); void refresh() }, { immediate: true, flush: 'sync' })
  onMounted(() => {
    document.addEventListener('visibilitychange', visibilityChanged); window.addEventListener('storage', sessionStorageChanged)
    tick = setInterval(() => { if (document.visibilityState !== 'hidden') clock.value = Date.now() }, 1000)
  })
  onBeforeUnmount(() => {
    disposed = true; reset(); if (tick) clearInterval(tick)
    document.removeEventListener('visibilitychange', visibilityChanged); window.removeEventListener('storage', sessionStorageChanged)
  })
  return { available, feed, loading, error, now, refresh, scope }
}
