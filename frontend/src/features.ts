import { reactive } from 'vue'
import { http } from './api'

export const features = reactive({ miniprograms: false, miniprogramUpload: false })
let loadedToken = ''
let loading: Promise<void> | undefined
export async function loadFeatures() {
  const token = localStorage.getItem('release_token') || ''
  if (!token) { loadedToken = ''; Object.assign(features, { miniprograms: false, miniprogramUpload: false }); return }
  if (token === loadedToken) return
  if (loading) { await loading; if (loadedToken === token) return }
  loading = (async () => {
    try {
      const { data } = await http.get('/features', { timeout: 4000 })
      if (token !== localStorage.getItem('release_token')) return
      features.miniprograms = data?.miniprograms === true
      features.miniprogramUpload = features.miniprograms && data?.miniprogramUpload === true
      loadedToken = token
    } catch { Object.assign(features, { miniprograms: false, miniprogramUpload: false }) }
  })()
  try { await loading } finally { loading = undefined }
}
