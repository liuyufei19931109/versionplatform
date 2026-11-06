import { onBeforeUnmount, ref, watch } from 'vue'
import { hasMenu } from '../auth'
import { errorText, http, payload } from '../api'
import { normalizeCodeUpdates } from '../versionCodeUpdates'
import type { VersionCodeUpdate } from '../versionCodeUpdates'

export function useVersionCodeUpdates(versionId: () => number | undefined) {
  const enabled = ref(false), items = ref<VersionCodeUpdate[]>([]), loading = ref(false), error = ref('')
  let generation = 0, disposed = false, timer: ReturnType<typeof setTimeout> | undefined, controller: AbortController | undefined
  function stop() { generation++; controller?.abort(); controller = undefined; if (timer) clearTimeout(timer); timer = undefined }
  async function refresh() {
    const id = versionId()
    if (disposed || !id || !hasMenu('versions')) return
    stop(); const request = generation, pending = new AbortController(); controller = pending; loading.value = true
    try {
      const result = normalizeCodeUpdates(payload(await http.get(`/versions/${id}/code-updates`, { signal: pending.signal }), null))
      if (disposed || request !== generation || versionId() !== id) return
      enabled.value = result.enabled; items.value = result.items; error.value = ''
    } catch (reason) { if (!disposed && request === generation) error.value = errorText(reason, '代码动态暂时无法读取') }
    finally { if (!disposed && request === generation) { loading.value = false; controller = undefined; timer = setTimeout(refresh, 20000) } }
  }
  watch([versionId, () => hasMenu('versions')], () => { stop(); enabled.value = false; items.value = []; error.value = ''; loading.value = false; void refresh() }, { immediate: true, flush: 'sync' })
  onBeforeUnmount(() => { disposed = true; stop() })
  return { enabled, items, loading, error, refresh }
}
