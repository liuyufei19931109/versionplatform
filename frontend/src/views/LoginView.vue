<script setup lang="ts">
import { tUi } from '../i18n'

import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Lock, User, ArrowRight } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { errorText, http, payload } from '../api'
import { setAuthUser } from '../auth'
import OtpInput from '../components/OtpInput.vue'
import LanguageSelect from '../components/LanguageSelect.vue'
import PlatformBrand from '../components/PlatformBrand.vue'

const router = useRouter(), loading = ref(false), totpStep = ref(false), lastLoginError = ref<unknown>(null)
const route = useRoute()
const loginError = computed(() => lastLoginError.value ? errorText(lastLoginError.value, tUi('登录失败，请检查账号密码')) : '')
const sessionNotice = computed(() => route.query.expired ? tUi('登录状态已失效，请重新登录。') : route.query.unavailable ? tUi('暂时无法验证登录状态，请稍后重新登录。') : '')
const form = reactive({ username: '', password: '', totpCode: '' })
async function login() {
  if (loading.value) return
  if (!form.username.trim() || !form.password) return ElMessage.warning(tUi('请输入账号和密码'))
  if (totpStep.value && !/^\d{6}$/.test(form.totpCode)) return ElMessage.warning(tUi('请输入 6 位动态码'))
  loading.value = true; lastLoginError.value = null
  try {
    const data = payload<Record<string, unknown>>(await http.post('/auth/login', { ...form, username: form.username.trim() }), {})
    if (data.requiresTotp) { totpStep.value = true; form.totpCode = ''; return }
    const token = String(data.token || data.accessToken || '')
    if (!token) throw new Error(tUi('登录未完成，请稍后重试'))
    localStorage.setItem('release_token', token)
    setAuthUser({ username: String(data.username || form.username), displayName: String(data.displayName || form.username), admin: Boolean(data.admin), permissions: Array.isArray(data.permissions) ? data.permissions.map(String) : [], totpEnabled: !data.requiresTotpSetup, requiresTotpSetup: Boolean(data.requiresTotpSetup) })
    await router.replace(data.requiresTotpSetup ? '/settings/security?first=1' : '/')
  } catch (error) { lastLoginError.value = error; if (totpStep.value) form.totpCode = '' }
  finally { loading.value = false }
}
function back() { if (loading.value) return; totpStep.value = false; form.totpCode = ''; lastLoginError.value = null }
</script>

<template>
  <main class="login-page">
    <LanguageSelect class="login-language" />
    <section class="login-story">
      <div class="login-brand"><PlatformBrand :size="40" /><div><strong>{{ tUi('发版平台') }}</strong></div></div>
      <div class="story-body"><span class="story-kicker">{{ tUi('从代码到交付') }}</span><h1>{{ tUi('每一次发布，') }}<br>{{ tUi('都心中有数。') }}</h1><p>{{ tUi('统一管理服务、构建与部署。') }}<br>{{ tUi('让版本有据可查，让交付有序进行。') }}</p>
        <div class="delivery-preview"><div class="preview-head"><span>{{ tUi('交付流程') }}</span><span class="preview-caption">{{ tUi('构建 → 发布') }}</span></div><div v-for="(step, index) in [tUi('确认代码版本'), tUi('构建镜像制品'), tUi('发布到目标环境')]" :key="step" class="delivery-stage"><span class="stage-number">0{{index + 1}}</span><b>{{step}}</b><span class="stage-line"></span></div></div>
      </div>
      <small class="story-foot">{{ tUi('发版平台 · 软件交付工作空间') }}</small>
    </section>
    <section class="login-form-area">
      <div class="login-card">
        <div class="form-brand"><PlatformBrand :size="32" class="mobile-brand" /><span class="form-kicker">{{ tUi('欢迎使用发版平台') }}</span></div><h2>{{totpStep ? tUi('验证您的身份') : tUi('登录工作空间')}}</h2><p class="form-intro">{{totpStep ? tUi('打开 Google Authenticator，输入当前动态码。') : tUi('使用您的平台账号继续。')}}</p>
        <el-alert v-if="loginError" :title="loginError" type="error" show-icon :closable="false" class="login-alert" />
        <el-alert v-else-if="sessionNotice && !totpStep" :title="sessionNotice" type="info" show-icon :closable="false" class="login-alert" />
        <form @submit.prevent="login">
          <template v-if="!totpStep"><label class="field-label" for="login-user">{{ tUi('账号') }}</label><el-input id="login-user" v-model="form.username" autocomplete="username" size="large" :placeholder="tUi('请输入账号')" :prefix-icon="User" :disabled="loading" /><label class="field-label password-label" for="login-password">{{ tUi('密码') }}</label><el-input id="login-password" v-model="form.password" autocomplete="current-password" size="large" type="password" show-password :placeholder="tUi('请输入密码')" :prefix-icon="Lock" :disabled="loading" /></template>
          <template v-else><div class="otp-account">{{ tUi('正在验证') }} <b>{{form.username}}</b></div><OtpInput v-model="form.totpCode" :disabled="loading" autofocus @submit="login" /><p class="otp-help">{{ tUi('支持粘贴完整的 6 位数字，动态码每 30 秒更新。') }}</p></template>
          <el-button native-type="submit" type="primary" size="large" :loading="loading" :disabled="totpStep && form.totpCode.length !== 6" class="login-submit">{{totpStep ? tUi('验证并登录') : tUi('继续')}}<el-icon v-if="!loading"><ArrowRight /></el-icon></el-button>
          <el-button v-if="totpStep" text :disabled="loading" class="back-login" @click="back">{{ tUi('返回账号密码') }}</el-button>
        </form>
        <p class="login-security"><el-icon><Lock /></el-icon>{{ tUi('账号验证与动态码保护') }}</p>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-language { position: absolute; top: 16px; right: 20px; z-index: 2; }

.login-page{display:grid;grid-template-columns:minmax(380px,1fr) minmax(440px,.95fr);min-height:100vh;background:#fff}.login-story{display:flex;flex-direction:column;padding:48px 10%;background:#163451;border-right:1px solid #163451;position:relative;overflow:hidden}.login-brand{display:flex;align-items:center;gap:12px}.login-brand strong{display:block;color:#ffffff;font-size:18px;letter-spacing:2px}.login-brand small{display:block;font-size:9px;font-weight:500;letter-spacing:2.1px;color:#b2c4d8;margin-top:5px}.story-body{margin:auto 0;padding:65px 0;z-index:1}.story-kicker{display:inline-block;color:#b5c9e0;font-size:13px;letter-spacing:0;margin-bottom:22px}.story-body h1{font-size:clamp(38px,3.2vw,54px);font-weight:650;line-height:1.35;letter-spacing:-1px;margin:0;color:#ffffff}.story-body>p{color:#bed0e2;font-size:15px;line-height:1.9;margin:22px 0 32px}.delivery-preview{max-width:375px;border:1px solid #ffffff2b;background:#ffffff08;box-shadow:none;border-radius:14px;padding:20px 23px}.preview-head{display:flex;justify-content:space-between;color:#d2dfed;font-size:13px;margin-bottom:12px}.preview-caption{font-size:9px;letter-spacing:1px;color:#a5bed7}.delivery-stage{display:flex;align-items:center;gap:13px;padding:14px 0}.stage-number{color:#ffffff;background:#315fce;border-radius:8px;font:12px ui-monospace,monospace;padding:8px}.delivery-stage b{font-size:13px;font-weight:500;color:#eef4fa}.stage-line{height:4px;border-radius:3px;background:#7898b5;width:38px;margin-left:auto}.story-foot{font-size:11px;color:#b2c4d8}.login-form-area{display:flex;flex-direction:column;justify-content:center;align-items:center;padding:50px 8%;position:relative}.login-card{width:100%;max-width:370px}.form-kicker{color:#607087;font-size:12px}.login-card h2{font-size:29px;font-weight:650;color:#202e43;letter-spacing:-.5px;margin:14px 0 10px}.form-intro{color:#607087;font-size:13px;line-height:1.7;margin:0 0 34px}.field-label{display:block;font-size:13px;color:#495870;font-weight:500;margin-bottom:10px}.password-label{margin-top:22px}.login-card :deep(.el-input__wrapper){min-height:46px;border-radius:9px}.login-submit{width:100%;height:46px;margin-top:28px;border-radius:9px;display:flex;gap:9px;font-weight:550}.login-security{display:flex;align-items:center;justify-content:center;gap:6px;margin:27px 0 0;font-size:11px;color:#607087}.login-footer{position:absolute;bottom:32px;color:#718197;font-size:9px;letter-spacing:2px}.otp-account{color:#8290a4;font-size:13px;margin-bottom:23px}.otp-account b{color:#425572}.otp-help{color:#95a0b1;font-size:11px;line-height:1.7;margin:17px 0 0;text-align:center}.back-login{display:block;margin:12px auto 0;color:#7e8da4}.login-alert{margin-bottom:22px}@media(max-width:850px){.login-page{grid-template-columns:1fr}.login-story{display:none}.login-form-area{min-height:100vh;padding:55px 25px}}
.form-brand { display: flex; align-items: center; gap: 8px; }
.mobile-brand { display: none; }
@media (max-width: 850px) { .mobile-brand { display: block; } }
</style>
