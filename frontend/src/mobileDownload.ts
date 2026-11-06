/** Download activity is measured in received bytes, not total request duration. */
export interface PackageDownloadProgress {
  loaded: number
  total: number
  percent: number
  speedBytesPerSecond: number
}

interface DownloadRequest {
  responseType: 'blob'
  timeout: 0
  signal: AbortSignal
  onDownloadProgress: (event: { loaded: number; total?: number }) => void
}

interface PackageDownloadOptions {
  expectedBytes: number
  request: (config: DownloadRequest) => Promise<{ data: Blob }>
  onProgress: (value: PackageDownloadProgress) => void
  idleTimeoutMs?: number
}

export class PackageDownloadError extends Error {
  constructor(public readonly kind: 'cancelled' | 'idle' | 'incomplete' | 'request', message: string) {
    super(message)
    this.name = 'PackageDownloadError'
  }
}

async function requestError(error: unknown): Promise<Error> {
  if (error instanceof PackageDownloadError) return error
  const failure = error as { response?: { status?: number; data?: unknown }; message?: string }
  let body = failure.response?.data
  if (body instanceof Blob && body.size < 65536) {
    try { body = JSON.parse(await body.text()) } catch { body = undefined }
  }
  const detail = body as { message?: unknown } | undefined
  if (typeof detail?.message === 'string' && detail.message.trim()) {
    return new PackageDownloadError('request', detail.message)
  }
  if (failure.response?.status === 401) return new PackageDownloadError('request', '登录已过期，请重新登录后下载')
  if (failure.response?.status === 403) return new PackageDownloadError('request', '没有下载权限，请联系管理员')
  return new PackageDownloadError('request', '下载连接中断，请检查网络后重新下载')
}

export function createPackageDownload(options: PackageDownloadOptions) {
  const controller = new AbortController()
  const startedAt = performance.now()
  const total = options.expectedBytes
  let loaded = 0
  let finished = false
  let interruption: PackageDownloadError | undefined
  let idleTimer: ReturnType<typeof setTimeout> | undefined

  function interrupt(error: PackageDownloadError) {
    if (finished || interruption) return
    interruption = error
    if (idleTimer !== undefined) clearTimeout(idleTimer)
    controller.abort()
  }

  function armIdleTimer() {
    if (idleTimer !== undefined) clearTimeout(idleTimer)
    idleTimer = setTimeout(() => interrupt(new PackageDownloadError('idle', '下载长时间没有收到数据，已停止。请检查网络后重新下载')), options.idleTimeoutMs ?? 120000)
  }

  function report(complete = false) {
    const seconds = (performance.now() - startedAt) / 1000
    options.onProgress({ loaded, total, percent: complete ? 100 : Math.min(99, Math.floor(loaded / total * 100)), speedBytesPerSecond: seconds > 0 ? loaded / seconds : 0 })
  }

  const promise = Promise.resolve().then(async () => {
    try {
      if (interruption) throw interruption
      if (!Number.isSafeInteger(total) || total <= 0) throw new PackageDownloadError('incomplete', '安装包大小信息不完整，请刷新页面后重试')
      armIdleTimer()
      report()
      const response = await options.request({
        responseType: 'blob',
        timeout: 0,
        signal: controller.signal,
        onDownloadProgress(event) {
          if (finished || interruption || !Number.isFinite(event.loaded) || event.loaded <= loaded) return
          loaded = event.loaded
          if (loaded > total) {
            interrupt(new PackageDownloadError('incomplete', '收到的文件大小与安装包记录不一致，已停止下载，请刷新后重试'))
            return
          }
          armIdleTimer()
          report()
        },
      })
      if (interruption) throw interruption
      if (!(response.data instanceof Blob) || response.data.size !== total) {
        throw new PackageDownloadError('incomplete', '安装包没有完整接收，未保存文件，请重新下载')
      }
      loaded = total
      report(true)
      return response.data
    } catch (error) {
      throw interruption ?? await requestError(error)
    } finally {
      finished = true
      if (idleTimer !== undefined) clearTimeout(idleTimer)
    }
  })

  return { promise, cancel: () => interrupt(new PackageDownloadError('cancelled', '已取消下载')) }
}
