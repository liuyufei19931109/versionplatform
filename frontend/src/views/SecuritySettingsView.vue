<script setup lang="ts">
import { tUi } from '../i18n'

import { onMounted, reactive, ref } from 'vue'
import { Cellphone, CircleCheck, Key, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { errorText, http, payload } from '../api'
import { useRoute, useRouter } from 'vue-router'
import { authUser, setAuthUser } from '../auth'
import OtpInput from '../components/OtpInput.vue'

interface TotpStatus { enabled: boolean; username: string; issuer: string }
interface SetupResult { qrCodeDataUrl: string; manualKey: string; expiresAt: string }

const loading = ref(true)
const settingUp = ref(false)
const confirming = ref(false)
const status = reactive<TotpStatus>({ enabled: false, username: authUser.value?.username || '', issuer: '' })
const setup = ref<SetupResult | null>(null)
const code = ref('')
const replaceDialog = ref(false), currentCode = ref(''), replaceError = ref('')
const route=useRoute(),router=useRouter()

async function loadStatus() {
  loading.value = true
  try { Object.assign(status, payload<TotpStatus>(await http.get('/auth/totp/status'), status)) }
  catch (error) { ElMessage.error(errorText(error, tUi('读取二次认证状态失败'))) }
  finally { loading.value = false }
}

async function beginSetup() {
  if (settingUp.value) return
  if (status.enabled) { currentCode.value = ''; replaceError.value = ''; replaceDialog.value = true; return }
  await performSetup()
}

async function performSetup() {
  if (settingUp.value) return
  if (status.enabled && !/^\d{6}$/.test(currentCode.value)) return ElMessage.warning(tUi('请输入当前 6 位动态码'))
  settingUp.value = true
  try {
    setup.value = payload<SetupResult>(await http.post('/auth/totp/setup', { currentCode: currentCode.value }), {} as SetupResult)
    replaceDialog.value = false
    code.value = ''
  } catch (error) { replaceError.value = errorText(error, tUi('生成绑定二维码失败')); if (!replaceDialog.value) ElMessage.error(replaceError.value); currentCode.value = '' }
  finally { settingUp.value = false }
}

async function confirmSetup() {
  if (confirming.value) return
  if (!/^\d{6}$/.test(code.value)) return ElMessage.warning(tUi('请输入 Google Authenticator 中的 6 位动态码'))
  confirming.value = true
  try {
    const result=payload<Record<string,unknown>>(await http.post('/auth/totp/confirm', { code: code.value }),{})
    if(result.token)localStorage.setItem('release_token',String(result.token))
    const me=payload<Record<string,unknown>>(await http.get('/auth/me'),{})
    setAuthUser({username:String(me.username),displayName:String(me.displayName||me.username),admin:Boolean(me.admin),permissions:Array.isArray(me.permissions)?me.permissions.map(String):[],totpEnabled:true,requiresTotpSetup:false})
    status.enabled = true
    setup.value = null
    code.value = ''
    ElMessage.success(tUi('Google Authenticator 绑定成功'))
    if(route.query.first)await router.replace('/')
  } catch (error) { code.value = ''; ElMessage.error(errorText(error, tUi('动态码验证失败'))) }
  finally { confirming.value = false }
}

onMounted(loadStatus)
</script>

<template>
  <div class="page-head">
    <div><h2>{{ tUi('登录安全') }}</h2><p>{{ tUi('使用 Google Authenticator 保护账号登录和生产发版操作。') }}</p></div>
    <el-tag v-if="status.enabled" type="success" effect="dark" round><el-icon><CircleCheck /></el-icon>{{ tUi('已启用') }}</el-tag>
  </div>

  <section class="panel security-panel" v-loading="loading">
    <div v-if="!setup" class="security-overview">
      <div class="security-icon" :class="{ active: status.enabled }"><el-icon><Key /></el-icon></div>
      <div>
        <h3>{{ status.enabled ? tUi('账号二次认证已启用') : tUi('首次登录需要完成绑定') }}</h3>
        <p v-if="status.enabled">{{ tUi('账号 {0} 登录平台以及执行生产发版时，需要输入手机上的 6 位动态码。', status.username) }}</p>
        <p v-else>{{ tUi('完成扫码绑定后才能进入平台其他页面，之后每次登录都会要求输入动态码。') }}</p>
      </div>
      <el-button v-if="status.enabled" :icon="Refresh" :loading="settingUp" @click="beginSetup">{{ tUi('更换绑定') }}</el-button>
      <el-button v-else type="primary" :icon="Cellphone" :loading="settingUp" @click="beginSetup">{{ tUi('开始绑定') }}</el-button>
    </div>

    <div v-else class="setup-grid">
      <div class="qr-card"><img :src="setup.qrCodeDataUrl" :alt="tUi('Google Authenticator 绑定二维码')" /><span>{{ tUi('二维码 10 分钟内有效') }}</span></div>
      <div class="setup-steps">
        <h3>{{ tUi('绑定 Google Authenticator') }}</h3>
        <ol>
          <li>{{ tUi('打开手机上的 Google Authenticator。') }}</li>
          <li>{{ tUi('点击右下角“+”，选择“扫描二维码”。') }}</li>
          <li>{{ tUi('扫描左侧二维码，然后输入应用显示的 6 位数字。') }}</li>
        </ol>
        <div class="manual-key"><span>{{ tUi('无法扫码时手动输入密钥') }}</span><b class="mono">{{ setup.manualKey }}</b></div>
        <OtpInput v-model="code" :disabled="confirming" :label="tUi('新绑定动态码')" @submit="confirmSetup" />
        <div class="setup-actions"><el-button :disabled="confirming" @click="setup = null">{{ tUi('取消') }}</el-button><el-button type="primary" :loading="confirming" :disabled="code.length !== 6" @click="confirmSetup">{{ tUi('验证并启用') }}</el-button></div>
      </div>
    </div>
  </section>

  <section class="panel notes-panel">
    <h3>{{ tUi('使用说明') }}</h3>
    <div class="note-grid"><div><b>{{ tUi('登录保护') }}</b><span>{{ tUi('所有用户首次登录必须绑定。') }}</span></div><div><b>{{ tUi('一次一验') }}</b><span>{{ tUi('同一个动态码不能重复使用。') }}</span></div><div><b>{{ tUi('错误保护') }}</b><span>{{ tUi('连续错误 5 次后会锁定 5 分钟。') }}</span></div></div>
  </section>
  <el-dialog v-model="replaceDialog" :title="tUi('验证当前绑定')" width="430px" :close-on-click-modal="false" :show-close="!settingUp" :close-on-press-escape="!settingUp">
    <p class="dialog-description">{{ tUi('请输入当前 Google Authenticator 动态码。新绑定验证成功前，原绑定持续有效。') }}</p>
    <el-alert v-if="replaceError" :title="replaceError" type="error" :closable="false" show-icon style="margin-bottom:18px" />
    <OtpInput v-model="currentCode" :disabled="settingUp" :label="tUi('当前绑定动态码')" @submit="performSetup" />
    <template #footer><el-button :disabled="settingUp" @click="replaceDialog = false">{{ tUi('取消') }}</el-button><el-button type="primary" :loading="settingUp" :disabled="currentCode.length !== 6" @click="performSetup">{{ tUi('验证并继续') }}</el-button></template>
  </el-dialog>
</template>

<style scoped>
.security-panel{max-width:900px;padding:34px}.security-overview{display:flex;align-items:center;gap:20px;min-height:160px}.security-overview>div:nth-child(2){flex:1}.security-overview h3,.setup-steps h3,.notes-panel h3{margin:0 0 10px}.security-overview p{margin:0;color:#7d90aa;line-height:1.8}.security-icon{display:grid;place-items:center;width:68px;height:68px;border-radius:18px;background:#17263b;color:#7690af;font-size:30px}.security-icon.active{background:#123b32;color:#60dfb8}.setup-grid{display:grid;grid-template-columns:330px 1fr;gap:44px;align-items:center}.qr-card{display:grid;justify-items:center;gap:10px;padding:16px;border-radius:15px;background:#fff;color:#667085}.qr-card img{display:block;width:280px;height:280px}.setup-steps ol{padding-left:20px;color:#aebdd0;line-height:2}.manual-key{display:grid;gap:7px;padding:13px 15px;margin:16px 0;border-radius:9px;background:#091422}.manual-key span{font-size:12px;color:#71859e}.manual-key b{word-break:break-all;color:#78ddc1;letter-spacing:1px}.code-input{max-width:270px}.code-input :deep(.el-input__inner){font:700 22px "Cascadia Code",monospace;letter-spacing:8px;text-align:center}.setup-actions{display:flex;gap:10px;margin-top:16px}.notes-panel{max-width:900px}.note-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.note-grid>div{display:grid;gap:6px;padding:14px;border-radius:10px;background:#0b1625}.note-grid span{font-size:12px;color:#71839a}
</style>
