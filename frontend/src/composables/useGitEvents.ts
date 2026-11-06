import { tUi } from '../i18n'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { authUser, hasMenu } from '../auth'
import { errorText, http, payload } from '../api'
import { emptyGitEvents, normalizeGitEvents } from '../gitEvents'

export function useGitEvents() {
  const feed = ref(emptyGitEvents()), loading = ref(false), error = ref(''), readError = ref(''), acknowledged = ref(new Set<number>())
  const available = computed(() => Boolean(authUser.value && !authUser.value.requiresTotpSetup && ['services', 'builds', 'apps', 'release'].some(hasMenu)))
  const unread = computed(() => feed.value.unread && feed.value.items.some(item => item.unread && !acknowledged.value.has(item.id)))
  let disposed = false, generation = 0, pending: Promise<void> | undefined, timer: ReturnType<typeof setTimeout> | undefined
  let controller: AbortController | undefined
  const readControllers = new Set<AbortController>()

  function stopTimer() { if (timer) clearTimeout(timer); timer = undefined }
  function schedule() { stopTimer(); if (!disposed && available.value && document.visibilityState !== 'hidden') timer = setTimeout(() => { void refresh() }, 20_000) }
  function reset() {
    generation++; stopTimer(); controller?.abort(); controller = undefined; pending = undefined
    readControllers.forEach(value => value.abort()); readControllers.clear()
    feed.value = emptyGitEvents(); loading.value = false; error.value = ''; readError.value = ''; acknowledged.value = new Set()
  }
  function refresh(): Promise<void> {
    if (disposed || !available.value) return Promise.resolve()
    if (pending) return pending
    stopTimer()
    const request = generation, abort = new AbortController()
    controller = abort; loading.value = true
    pending = (async () => {
      try {
        const result = normalizeGitEvents(payload(await http.get('/github/events', { signal: abort.signal, timeout: 15_000 }), null))
        if (disposed || request !== generation) return
        feed.value = result; error.value = ''
      } catch (reason) {
        if (!disposed && request === generation && !abort.signal.aborted) error.value = errorText(reason, tUi('Git 动态暂时无法读取'))
      } finally {
        if (!disposed && request === generation) { loading.value = false; pending = undefined; controller = undefined; schedule() }
      }
    })()
    return pending
  }
  async function markRead(throughEventId: number, eventIds: number[]) {
    if (disposed || !available.value || !throughEventId || !eventIds.length || eventIds.every(id => acknowledged.value.has(id))) return
    const request = generation, abort = new AbortController()
    readControllers.add(abort)
    try {
      await http.post('/github/events/read', { throughEventId, eventIds }, { signal: abort.signal, timeout: 15_000 })
      if (disposed || request !== generation) return
      acknowledged.value = new Set([...acknowledged.value, ...eventIds]); readError.value = ''
    } catch {
      if (!disposed && request === generation && !abort.signal.aborted) readError.value = tUi('已读状态未保存，稍后重新打开可重试。')
    } finally { readControllers.delete(abort) }
  }
  function visibilityChanged() { if (document.visibilityState === 'hidden') stopTimer(); else void refresh() }
  watch(() => [authUser.value?.username, authUser.value?.admin, authUser.value?.permissions?.join(','), available.value], () => { reset(); void refresh() }, { immediate: true, flush: 'sync' })
  onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
  onBeforeUnmount(() => { disposed = true; reset(); document.removeEventListener('visibilitychange', visibilityChanged) })
  return { available, feed, loading, error, readError, unread, refresh, markRead }
}
