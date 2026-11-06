import { tUi } from '../i18n'
import { useAppScope } from './useAppScope'
import { ref } from 'vue'
import { http, payload } from '../api'
import { newRequestId } from '../ui'

export interface ReleaseSubmission {
  serviceId: number
  artifactId: number
  environment: string
  serverIds: number[]
  releaseNote: string
}

// Both release entry points retain the same request ID after a timeout or approval error.
export function useReleaseSubmission() {
  const { api } = useAppScope()
  const submitting = ref(false)
  let fingerprint = '', requestId = ''
  async function enqueue(body: ReleaseSubmission, totpCode = '', mobile = false, confirmationToken?: string) {
    if (submitting.value) return
    const next = JSON.stringify({ body, mobile })
    if (next !== fingerprint) { fingerprint = next; requestId = newRequestId() }
    submitting.value = true
    try {
      const endpoint = mobile ? '/mobile/releases' : '/releases'
      const request = mobile ? { serviceId: body.serviceId, artifactId: body.artifactId, environmentName: body.environment, releaseNote: body.releaseNote, totpCode, requestId } : { ...body, totpCode, requestId, confirmationToken }
      const task = payload<{ id?: number | string }>(await http.post(api(endpoint), request), {})
      if (!task.id) throw new Error(tUi('未读取到发版任务编号，请先检查发版记录后再重试'))
      return task
    } finally { submitting.value = false }
  }
  return { submitting, enqueue }
}
