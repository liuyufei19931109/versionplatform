<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { authUser } from '../auth'
import { errorText, http, payload } from '../api'
import { environmentLabel, formatTime } from '../ui'

type Environment = 'dev' | 'test' | 'prod'
interface IntegrationAccess {
  serviceId: number | string
  name: string
  environments: Environment[]
  active: boolean
  tokenPrefix?: string
  createdAt?: string | null
  updatedAt?: string | null
  revokedAt?: string | null
}
const props = defineProps<{ serviceId: number | string; serviceName?: string }>()
const environments: Environment[] = ['dev', 'test', 'prod']
const access = ref<IntegrationAccess>()
const loading = ref(false), action = ref<'issue' | 'revoke' | null>(null)
const loadError = ref(''), actionError = ref(''), loaded = ref(false)
const name = ref('版本管理后台'), selectedEnvironments = ref<Environment[]>(['test'])
const secretToken = ref(''), secretVisible = ref(false)
const tokenField = ref<HTMLTextAreaElement>(), listField = ref<HTMLInputElement>()
const isAdmin = computed(() => authUser.value?.admin === true)
const busy = computed(() => loading.value || action.value !== null)
const validSelection = computed(() => name.value.trim().length > 0 && selectedEnvironments.value.length > 0)
const canWrite = computed(() => isAdmin.value && loaded.value && !loadError.value && !busy.value)
const serviceId = computed(() => String(props.serviceId))
const listUrl = computed(() => `${location.origin}/api/mobile/apps/${serviceId.value}/releases`)
const detailUrl = computed(() => `${location.origin}/api/mobile/releases/{releaseId}`)
const authorization = computed(() => secretToken.value ? `Authorization: Bearer ${secretToken.value}` : '')
const statusLabel = computed(() => access.value?.active ? '使用中' : access.value?.revokedAt ? '已撤销' : '未生成')
let revision = 0, disposed = false, confirmationSequence = 0, ownedConfirmation: number | null = null
let readController: AbortController | undefined, writeController: AbortController | undefined

function current(expected: number, id: string) {
  return !disposed && revision === expected && serviceId.value === id
}
function clearSecret() { secretVisible.value = false; secretToken.value = '' }
function closeSecret(visible: boolean) { if (!visible) clearSecret() }
function normalizeStatus(value: unknown, id: string): IntegrationAccess {
  const data = value as Partial<IntegrationAccess> | null
  if (!data || String(data.serviceId) !== id || typeof data.active !== 'boolean' || !Array.isArray(data.environments)
    || data.environments.some(env => !environments.includes(env))) throw new Error('接口状态不完整，请刷新后重试')
  // Retain only public state; a response must never accidentally persist a complete token here.
  return {
    serviceId: data.serviceId!, name: typeof data.name === 'string' ? data.name : '',
    environments: [...data.environments], active: data.active,
    tokenPrefix: typeof data.tokenPrefix === 'string' ? data.tokenPrefix : undefined,
    createdAt: data.createdAt, updatedAt: data.updatedAt, revokedAt: data.revokedAt,
  }
}
async function loadStatus(expected = revision, id = serviceId.value) {
  if (!/^[1-9][0-9]*$/.test(id)) { loadError.value = '请先保存 App，再配置接口对接'; return }
  readController?.abort(); const controller = new AbortController(); readController = controller
  loading.value = true; loadError.value = ''
  try {
    const result = await http.get(`/apps/services/${id}/integration-access`, { signal: controller.signal })
    if (!current(expected, id) || controller.signal.aborted) return
    const state = normalizeStatus(payload<unknown>(result, null), id)
    access.value = state; loaded.value = true
    name.value = state.name || '版本管理后台'
    selectedEnvironments.value = state.environments.length ? [...state.environments] : ['test']
  } catch (failure) {
    if (current(expected, id) && !controller.signal.aborted) {
      loaded.value = false; loadError.value = errorText(failure, '读取接口凭据状态失败，请刷新重试')
    }
  } finally {
    if (current(expected, id) && readController === controller) { loading.value = false; readController = undefined }
  }
}
function reset() {
  revision++; readController?.abort(); writeController?.abort()
  readController = undefined; writeController = undefined
  if (ownedConfirmation !== null) { ownedConfirmation = null; ElMessageBox.close() }
  clearSecret(); access.value = undefined; loading.value = false; action.value = null
  loadError.value = ''; actionError.value = ''; loaded.value = false
  name.value = '版本管理后台'; selectedEnvironments.value = ['test']
}
async function confirmAction(message: string, title: string, button: string) {
  const own = ++confirmationSequence; ownedConfirmation = own
  try {
    await ElMessageBox.confirm(message, title, { confirmButtonText: button, cancelButtonText: '取消', type: 'warning', closeOnClickModal: false })
    return true
  } catch { return false }
  finally { if (ownedConfirmation === own) ownedConfirmation = null }
}
async function issue() {
  if (!canWrite.value || !validSelection.value) return
  const expected = revision, id = serviceId.value
  const request = { name: name.value.trim(), environments: [...selectedEnvironments.value] }
  action.value = 'issue'; actionError.value = ''; clearSecret()
  try {
    if (access.value?.active) {
      const confirmed = await confirmAction('生成新凭据后，旧凭据会立即失效。请同步更新版本管理后台使用的凭据。', '更换接口凭据', '确认更换')
      if (!confirmed || !current(expected, id) || !isAdmin.value) return
    }
    if (!current(expected, id) || !isAdmin.value) return
    const controller = new AbortController(); writeController = controller
    const response = await http.post(`/apps/services/${id}/integration-access`, request, { signal: controller.signal })
    if (!current(expected, id) || controller.signal.aborted || !isAdmin.value) return
    const result = payload<{ access?: unknown; token?: unknown }>(response, {})
    const state = normalizeStatus(result.access, id)
    if (typeof result.token !== 'string' || !/^[A-Za-z0-9_-]+$/.test(result.token)) throw new Error('完整凭据未返回，请刷新状态后重新生成')
    access.value = state; secretToken.value = result.token; secretVisible.value = true
    name.value = state.name; selectedEnvironments.value = [...state.environments]
    ElMessage.success('接口凭据已生成，请立即保存到对接后台')
  } catch (failure) {
    if (current(expected, id) && !writeController?.signal.aborted) {
      actionError.value = `${errorText(failure, '生成接口凭据失败')}。请求可能已生效，请先刷新状态；完整凭据无法找回，需要时重新生成。`
      loaded.value = false
    }
  } finally {
    if (current(expected, id)) { action.value = null; writeController = undefined }
  }
}
async function revoke() {
  if (!canWrite.value || !access.value?.active) return
  const expected = revision, id = serviceId.value
  action.value = 'revoke'; actionError.value = ''; clearSecret()
  try {
    const confirmed = await confirmAction('撤销后，使用此凭据的外部后台将无法读取交付列表、下载或确认结果。已有安装包和交付记录会保留。', '撤销接口凭据', '确认撤销')
    if (!confirmed || !current(expected, id) || !isAdmin.value) return
    const controller = new AbortController(); writeController = controller
    await http.delete(`/apps/services/${id}/integration-access`, { signal: controller.signal })
    if (!current(expected, id) || controller.signal.aborted) return
    await loadStatus(expected, id)
    if (current(expected, id)) ElMessage.success('接口凭据已撤销')
  } catch (failure) {
    if (current(expected, id) && !writeController?.signal.aborted) {
      actionError.value = `${errorText(failure, '撤销失败')}。请先刷新状态，确认是否已经撤销。`; loaded.value = false
    }
  } finally {
    if (current(expected, id)) { action.value = null; writeController = undefined }
  }
}
function selectText(event: FocusEvent) { (event.target as HTMLInputElement | HTMLTextAreaElement).select() }
async function copy(value: string, field?: HTMLInputElement | HTMLTextAreaElement) {
  if (!value) return
  const expected = revision, id = serviceId.value
  try {
    if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(value)
    if (current(expected, id)) ElMessage.success('已复制')
  } catch {
    if (!current(expected, id) || !field?.isConnected) return
    field.focus(); field.select()
    ElMessage.info('已选中文本，请按 Ctrl+C（Mac 使用 ⌘C）复制')
  }
}
watch([serviceId, isAdmin], () => { reset(); void loadStatus() }, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { disposed = true; reset() })
</script>

<template>
  <div class="integration-access" v-loading="loading" :aria-busy="busy">
    <div class="integration-heading"><div><h4>连接版本管理后台</h4><p>让外部后台持续读取这个 App 的交付记录、拉取安装包并回传实际发布结果。</p></div><el-button :disabled="busy" @click="loadStatus()">刷新状态</el-button></div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="integration-alert"/>
    <el-alert v-if="actionError" :title="actionError" type="warning" :closable="false" show-icon class="integration-alert"/>
    <div v-if="loaded || access" class="integration-status">
      <div class="status-line"><b>{{access?.name || 'App 接口凭据'}}</b><el-tag :type="access?.active?'success':'info'" effect="plain">{{statusLabel}}</el-tag><span>长期有效，轮换或撤销后失效</span></div>
      <dl><dt>允许环境</dt><dd><template v-if="access?.environments.length"><el-tag v-for="env in access.environments" :key="env" :type="env==='prod'?'danger':env==='test'?'warning':'info'" size="small" effect="plain">{{environmentLabel(env)}}</el-tag></template><span v-else>尚未设置</span></dd><dt>凭据标识</dt><dd class="token-prefix">{{access?.tokenPrefix || '生成后显示前缀'}}<small v-if="access?.tokenPrefix">仅显示前缀，不能用于接口请求</small></dd><dt>最近更新</dt><dd>{{access?.updatedAt?formatTime(access.updatedAt):'—'}}<span v-if="access?.revokedAt" class="revoked-at">撤销于 {{formatTime(access.revokedAt)}}</span></dd></dl>
    </div>
    <div v-if="isAdmin" class="integration-editor">
      <el-form label-position="top" :disabled="busy || !loaded || !!loadError" @submit.prevent="issue">
        <el-form-item label="对接名称" required><el-input v-model="name" maxlength="100" show-word-limit placeholder="例如：移动应用版本管理后台"/></el-form-item>
        <el-form-item label="允许访问的环境" required><el-checkbox-group v-model="selectedEnvironments"><el-checkbox v-for="env in environments" :key="env" :value="env">{{environmentLabel(env)}}</el-checkbox></el-checkbox-group><p class="field-help">此凭据只访问当前 App 所选环境的交付记录；修改范围需要生成新凭据。</p></el-form-item>
        <p v-if="selectedEnvironments.includes('prod')" class="production-note">生产发布结果确认成功后，沿用平台的代码合回规则。生成凭据本身不会触发发布。</p>
        <div class="integration-actions"><el-button type="primary" :loading="action==='issue'" :disabled="!canWrite || !validSelection" @click="issue">{{access?.active?'更换凭据并应用范围':'生成长期凭据'}}</el-button><el-button v-if="access?.active" type="danger" plain :loading="action==='revoke'" :disabled="!canWrite" @click="revoke">撤销凭据</el-button></div>
      </el-form>
    </div>
    <p v-else class="read-only-note">你可以查看接口状态和地址。生成、更换或撤销凭据请联系管理员。</p>
    <div class="integration-endpoints"><h4>对接地址</h4><p>使用同一个凭据完成以下操作，凭据保存在版本管理后台。</p><div class="endpoint-row"><b>GET</b><div><span>当前 App 的交付列表</span><code>{{listUrl}}</code></div></div><div class="endpoint-row"><b>GET</b><div><span>交付详情</span><code>{{detailUrl}}</code></div></div><div class="endpoint-row"><b>GET</b><div><span>下载本次安装包</span><code>{{detailUrl}}/download</code></div></div><div class="endpoint-row"><b>POST</b><div><span>确认实际发布结果</span><code>{{detailUrl}}/confirm</code></div></div><p class="field-help">列表可加 ?environmentName=test 只看预生产；支持 limit 和 beforeId 分页。releaseId 使用交付记录编号。先拉取并核对安装包，外部版本实际生效后再确认；只下载或生成草稿不代表发布成功。</p></div>
  </div>

  <el-dialog :model-value="secretVisible" title="保存本次接口凭据" width="720px" class="integration-secret-dialog" :close-on-click-modal="false" @update:model-value="closeSecret">
    <template v-if="secretVisible && secretToken"><el-alert title="完整凭据只展示这一次" description="请保存到对接后台。关闭弹窗、切换页面或 App 后将清除显示，之后只能重新生成。" type="warning" :closable="false" show-icon/>
      <p class="secret-scope"><b>{{serviceName || '当前 App'}} · {{access?.name}}</b><span>{{access?.environments.map(environmentLabel).join('、')}} · 长期有效</span></p>
      <label class="secret-field" for="integration-bearer">Authorization 请求头<textarea id="integration-bearer" ref="tokenField" :value="authorization" readonly rows="3" spellcheck="false" autocomplete="off" @focus="selectText"/></label><el-button @click="copy(authorization,tokenField)">复制请求头</el-button>
      <label class="secret-field" for="integration-release-list">交付列表地址（GET）<input id="integration-release-list" ref="listField" :value="listUrl" readonly spellcheck="false" @focus="selectText"/></label><el-button @click="copy(listUrl,listField)">复制列表地址</el-button>
      <div class="secret-paths"><p>详情（GET）：<code>{{detailUrl}}</code></p><p>下载（GET）：<code>{{detailUrl}}/download</code></p><p>确认（POST）：<code>{{detailUrl}}/confirm</code></p></div><p class="secret-help">如浏览器不支持直接复制，点击文本框后按 Ctrl+C（Mac 使用 ⌘C）。后续请求均使用上面的 Authorization 请求头。</p>
    </template><template #footer><el-button type="primary" @click="clearSecret">已保存，关闭</el-button></template>
  </el-dialog>
</template>

<style scoped>
.integration-access{max-width:980px;padding:8px 2px}.integration-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:20px;margin:8px 0 22px}.integration-heading h4,.integration-endpoints h4{margin:0 0 8px;color:#182230;font-size:16px}.integration-heading p,.integration-endpoints>p{margin:0;color:#667085;font-size:13px;line-height:1.8}.integration-heading .el-button{flex-shrink:0}.integration-alert{margin-bottom:18px}.integration-status{border:1px solid #e4e8ef;border-radius:10px;background:#f7f9fc;padding:20px 22px}.status-line{display:flex;align-items:center;flex-wrap:wrap;gap:10px}.status-line>b{color:#304863;font-size:15px}.status-line>span:last-child{color:#667085;font-size:12px}.integration-status dl{display:grid;grid-template-columns:96px minmax(0,1fr);gap:15px 18px;margin:20px 0 0;font-size:13px;line-height:1.7}.integration-status dt{color:#74849a}.integration-status dd{margin:0;color:#52647c;overflow-wrap:anywhere}.integration-status dd .el-tag{margin-right:8px}.token-prefix small,.revoked-at{display:block;margin-top:3px;font-size:12px;color:#74849a}.integration-editor{max-width:740px;margin-top:28px}.field-help{width:100%;margin:7px 0 0;color:#74849a;font-size:12px;line-height:1.8}.integration-actions{display:flex;gap:12px;flex-wrap:wrap;margin:20px 0 28px}.integration-actions .el-button+.el-button{margin-left:0}.production-note{font-size:12px;line-height:1.8;color:#8a5a27;margin:-6px 0 12px}.read-only-note{font-size:13px;line-height:1.8;color:#667085;margin:22px 0}.integration-endpoints{border-top:1px solid #e7edf5;padding-top:24px;margin-top:26px}.endpoint-row{display:grid;grid-template-columns:54px minmax(0,1fr);gap:16px;padding:16px 0;border-bottom:1px solid #edf0f4;font-size:12px}.endpoint-row>b{color:#4165b5;padding-top:2px}.endpoint-row>div{display:grid;gap:5px;color:#667085}.endpoint-row code,.secret-paths code{font-size:12px;color:#52647c;overflow-wrap:anywhere;white-space:normal}.secret-scope{display:grid;gap:7px;margin:20px 0;color:#304863;font-size:14px}.secret-scope span{font-size:12px;color:#667085}.secret-field{display:grid;gap:8px;font-size:13px;color:#52647c;margin:20px 0 9px}.secret-field textarea,.secret-field input{box-sizing:border-box;width:100%;padding:12px 14px;border:1px solid #d7e0ed;border-radius:8px;background:#f8faff;color:#304863;font-family:inherit;font-size:13px;line-height:1.7;resize:vertical;overflow-wrap:anywhere}.secret-field textarea:focus,.secret-field input:focus{outline:2px solid #4165e6;outline-offset:2px}.secret-help{color:#74849a;font-size:12px;line-height:1.8;margin:18px 0 0}.secret-paths{margin-top:20px;font-size:12px;line-height:1.8;color:#667085}.secret-paths p{margin:9px 0}@media(max-width:650px){.integration-heading{gap:12px}.integration-status{padding:16px}.integration-status dl{grid-template-columns:76px minmax(0,1fr);gap:12px}.status-line>span:last-child{width:100%}.integration-actions .el-button{flex:1}.endpoint-row{gap:8px}.integration-heading p{font-size:12px}.secret-paths code{display:block}}
</style>
