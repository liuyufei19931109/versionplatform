<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Bell, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorText, http, payload } from '../api'

interface WecomSettings {
  enabled: boolean
  webhookConfigured: boolean
  groupName: string
  publicBaseUrl: string
  environments: string[]
  notifySuccess: boolean
  notifyFailure: boolean
}
const form = reactive<WecomSettings>({ enabled: false, webhookConfigured: false, groupName: '', publicBaseUrl: '', environments: ['test', 'prod'], notifySuccess: true, notifyFailure: true })
const webhook = ref(''), loading = ref(false), saving = ref(false), testing = ref(false), loadError = ref(''), savedValue = ref('')
const environmentOptions = [{ value: 'dev', label: '开发/测试' }, { value: 'test', label: '预生产' }, { value: 'prod', label: '生产' }]
function settingsBody() {
  return { enabled: form.enabled, groupName: form.groupName.trim(), publicBaseUrl: form.publicBaseUrl.trim(), environments: [...form.environments].sort(), notifySuccess: form.notifySuccess, notifyFailure: form.notifyFailure }
}
const dirty = computed(() => Boolean(webhook.value.trim()) || JSON.stringify(settingsBody()) !== savedValue.value)
function accept(value: WecomSettings) {
  Object.assign(form, value)
  form.environments = [...(value.environments || [])]
  webhook.value = ''
  savedValue.value = JSON.stringify(settingsBody())
}
async function load() {
  if (loading.value) return
  loading.value = true
  try { accept(payload<WecomSettings>(await http.get('/settings/notifications/wecom'), form)); loadError.value = '' }
  catch (error) { loadError.value = errorText(error, '暂时无法读取群通知配置') }
  finally { loading.value = false }
}
async function save() {
  if (saving.value || loading.value) return
  if (form.enabled && !form.webhookConfigured && !webhook.value.trim()) return ElMessage.warning('请填写群机器人的 Webhook 地址')
  if (form.enabled && !form.environments.length) return ElMessage.warning('请至少选择一个通知环境')
  if (form.enabled && !form.notifySuccess && !form.notifyFailure) return ElMessage.warning('请至少选择一种发版结果')
  saving.value = true
  try {
    const body = { ...settingsBody(), ...(webhook.value.trim() ? { webhook: webhook.value.trim() } : {}) }
    accept(payload<WecomSettings>(await http.put('/settings/notifications/wecom', body), form))
    loadError.value = ''
    ElMessage.success('发版通知配置已保存')
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { saving.value = false }
}
async function sendTest() {
  if (testing.value || saving.value || dirty.value || !form.enabled || !form.webhookConfigured) return
  testing.value = true
  try {
    await ElMessageBox.confirm(`将向「${form.groupName || '已配置的企业微信群'}」发送一条测试通知，不会触发发版。`, '发送测试通知', { confirmButtonText: '发送到群', cancelButtonText: '取消', type: 'info' })
  } catch { testing.value = false; return }
  try { await http.post('/settings/notifications/wecom/test'); ElMessage.success('测试通知已加入发送队列，请稍后查看企业微信群') }
  catch (error) { ElMessage.error(errorText(error)) }
  finally { testing.value = false }
}
onMounted(load)
</script>

<template>
  <section class="panel notification-settings" v-loading="loading">
    <div class="panel-title">
      <div class="notification-title"><span class="notification-symbol"><el-icon><Bell /></el-icon></span><div><h3>发版通知 · 企业微信</h3><p>所选环境的所有服务共用一个群，在发版结束后发送最终结果。</p></div></div>
      <el-tag :type="form.enabled && form.webhookConfigured ? 'success' : 'info'">{{ form.enabled && form.webhookConfigured ? '已启用' : '未启用' }}</el-tag>
    </div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="notification-load-error"><template #default><el-button link type="primary" :icon="Refresh" @click="load">重新读取</el-button></template></el-alert>
    <el-form v-else label-position="top" :disabled="saving || testing">
      <div class="notification-enable"><el-switch v-model="form.enabled" active-text="开启发版结果通知" /><span>通知发送失败不会改变发版结果。</span></div>
      <div class="notification-fields">
        <el-form-item label="群名称"><el-input v-model="form.groupName" maxlength="80" placeholder="例如：发版通知群" /></el-form-item>
        <el-form-item label="平台访问地址"><el-input v-model="form.publicBaseUrl" placeholder="例如：https://release.example.com" /><div class="notification-help">群消息中的“查看发版详情”将跳转到此地址。</div></el-form-item>
        <el-form-item class="notification-webhook" :label="form.webhookConfigured ? '更新 Webhook（留空保留现有地址）' : '机器人 Webhook 地址'"><el-input v-model="webhook" type="password" autocomplete="new-password" placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=…" /><div class="notification-help"><span v-if="form.webhookConfigured" class="configured-dot">已安全保存</span>地址包含密钥，保存后不再显示。</div></el-form-item>
        <el-form-item label="通知环境"><el-checkbox-group v-model="form.environments"><el-checkbox v-for="environment in environmentOptions" :key="environment.value" :value="environment.value">{{ environment.label }}</el-checkbox></el-checkbox-group><div class="notification-help">🔴 生产　🟢 预生产　⚪ 开发/测试；只发送勾选环境的新发布结果。</div></el-form-item>
        <el-form-item label="通知结果"><div><el-checkbox v-model="form.notifySuccess">发版成功</el-checkbox><el-checkbox v-model="form.notifyFailure">发版失败</el-checkbox></div></el-form-item>
      </div>
      <div class="notification-footer"><p>仅通知新完成的发版任务，不补发历史记录；打包和服务重启不发送。</p><div class="notification-actions"><el-tooltip :content="!form.enabled ? '先启用并保存通知配置' : !form.webhookConfigured ? '先配置并保存群机器人的 Webhook' : '先保存配置，再发送到已配置的企业微信群'" :disabled="!dirty && form.enabled && form.webhookConfigured"><span><el-button :loading="testing" :disabled="dirty || !form.enabled || !form.webhookConfigured || saving" @click="sendTest">发送测试通知</el-button></span></el-tooltip><el-button type="primary" :loading="saving" @click="save">保存通知配置</el-button></div></div>
    </el-form>
  </section>
</template>

<style scoped>
.notification-settings{margin-bottom:18px}.notification-title{display:flex;align-items:center;gap:12px}.notification-title p{margin:5px 0 0;color:#667085;font-size:12px;line-height:1.6}.notification-symbol{display:grid;place-items:center;flex-shrink:0;width:40px;height:40px;border-radius:11px;background:#eaf6f0;color:#1b9766;font-size:21px}.notification-enable{display:flex;align-items:center;gap:18px;margin-bottom:20px;padding:12px 16px;background:#f8fafc;border:1px solid #eef1f5;border-radius:10px}.notification-enable>span{font-size:12px;color:#667085}.notification-fields{display:grid;grid-template-columns:1fr 1fr;gap:0 24px}.notification-webhook{grid-column:1/-1}.notification-help{margin-top:6px;font-size:12px;line-height:1.5;color:#98a2b3}.configured-dot{color:#169b63;margin-right:12px}.notification-footer{border-top:1px solid #eef1f5;padding-top:16px;display:flex;align-items:center;justify-content:space-between;gap:20px}.notification-footer p{margin:0;color:#667085;font-size:12px;line-height:1.6}.notification-actions{display:flex;gap:10px;flex-shrink:0}.notification-actions :deep(.el-button){margin-left:0}.notification-load-error{margin-bottom:8px}@media(max-width:900px){.notification-fields{grid-template-columns:1fr}.notification-enable,.notification-footer{align-items:flex-start;flex-direction:column;gap:10px}.notification-actions{flex-wrap:wrap}.notification-title{align-items:flex-start}}
</style>
