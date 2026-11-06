<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { errorText, http, payload } from '../api'
import type { MobileBuildConfig, MobileEnvironmentName, MobileEnvironmentProfile, MobileSigningProfile } from '../types'
import { automaticMobileVersion, mobileParameterError, mobileParameterGroupError, mobileEnvironments, mobileEnvironmentDefaults, mobileEnvironmentError, mobileFormat, mobileParameterGroups, type MobileParameterGroup } from '../mobile'
import { environmentLabel } from '../ui'
import { authUser } from '../auth'
const props = defineProps<{ model: MobileBuildConfig; compact?: boolean }>()
const versionPolicy = computed({ get: () => props.model.versionPolicy || 'MANUAL', set: value => { props.model.versionPolicy = value } })
const active = ref<MobileEnvironmentName>('test'), profiles = ref<MobileSigningProfile[]>([]), profileLoading = ref(false), profileError = ref(''), registryConfigured = ref(false)
function newDrafts() {
  return { dartDefines: { key: '', value: '', error: '' }, environmentVariables: { key: '', value: '', error: '' } }
}
const drafts = reactive({ dev: newDrafts(), test: newDrafts(), prod: newDrafts() })
const groupLabels = { dartDefines: 'Dart 编译参数', environmentVariables: '构建环境变量' }
watch(() => props.model, model => {
  model.environments ||= {}
  for (const env of mobileEnvironments) {
    drafts[env.name] = newDrafts()
    const existing = model.environments[env.name]
    model.environments[env.name] = { ...mobileEnvironmentDefaults(env.name), ...existing, dartDefines: { ...existing?.dartDefines }, environmentVariables: { ...existing?.environmentVariables } }
  }
}, { immediate: true })
const profile = computed(() => props.model.environments![active.value] as MobileEnvironmentProfile)
const validation = computed(() => profile.value.enabled ? mobileEnvironmentError(props.model, active.value) : '')
const legacy = computed(() => Boolean(props.model.applicationId || props.model.signingProfile || props.model.expectedCertificateSha256))
const selectedSigning = computed(() => profiles.value.find(item => item.name === profile.value.signingProfile))
async function loadProfiles() {
  profileLoading.value = true; profileError.value = ''
  try { const result = payload<{ configured: boolean; profiles: MobileSigningProfile[] }>(await http.get('/apps/signing-profiles'), { configured: false, profiles: [] }); profiles.value = result.profiles || []; registryConfigured.value = result.configured }
  catch (error) { profileError.value = errorText(error, '无法读取构建机签名配置') }
  finally { profileLoading.value = false }
}
function chooseSigning(name: string) { const selected = profiles.value.find(item => item.name === name); profile.value.expectedCertificateSha256 = selected?.certificateSha256 || '' }
function copyLegacy() {
  const keys = ['applicationId', 'expectedCertificateSha256', 'signingProfile', 'flavor', 'target', 'versionName', 'versionCode', 'apkPath', 'aabPath'] as const
  for (const key of keys) if (props.model[key] !== undefined) Object.assign(profile.value, { [key]: props.model[key] })
  profile.value.enabled = false
  ElMessage.success('旧配置已复制到当前环境，核对签名、构建模式和参数后再启用')
}
function draftError(env: MobileEnvironmentName, group: MobileParameterGroup) {
  const draft = drafts[env][group], key = draft.key.trim(), entries = props.model.environments![env]![group]
  if (Object.prototype.hasOwnProperty.call(entries, key)) return '该名称已存在，请修改列表中的值'
  if (Object.keys(entries).length >= 40) return '每组最多添加 40 个参数'
  return mobileParameterError(group, key, draft.value)
}
function storeDraft(env: MobileEnvironmentName, group: MobileParameterGroup) {
  const draft = drafts[env][group]
  props.model.environments![env]![group][draft.key.trim()] = draft.value
  Object.assign(draft, { key: '', value: '', error: '' })
}
function addParameter(group: MobileParameterGroup) {
  const draft = drafts[active.value][group]
  draft.error = draftError(active.value, group)
  if (!draft.error) storeDraft(active.value, group)
}
function commitParameters() {
  const pending: Array<[MobileEnvironmentName, MobileParameterGroup]> = []
  for (const { name } of mobileEnvironments) for (const group of mobileParameterGroups) {
    const draft = drafts[name][group], hasDraft = Boolean(draft.key || draft.value)
    const entries = props.model.environments![name]![group]
    draft.error = mobileParameterGroupError(group, entries) || (hasDraft ? draftError(name, group) : '')
    if (draft.error) { active.value = name; ElMessage.warning(environmentLabel(name) + '：' + draft.error); return false }
    if (hasDraft) pending.push([name, group])
  }
  for (const [env, group] of pending) storeDraft(env, group)
  return true
}
defineExpose({ commitParameters })
watch(() => profile.value.buildMode, mode => { if (mode === 'RELEASE') profile.value.allowDebugSigning = false })
onMounted(loadProfiles)
</script>

<template>
  <div class="mobile-build-settings">
    <div class="shared-heading"><h4>共用构建工具</h4><span>各环境使用同一份代码和工具版本</span></div>
    <el-form-item label="安装包格式" required><el-radio-group v-model="model.outputFormat"><el-radio-button value="APK">完整 APK</el-radio-button><el-radio-button value="AAB">AAB</el-radio-button></el-radio-group><small>{{mobileFormat(model)==='APK'?'用于内部安装和升级，生成一个完整 APK。预生产 Debug 构建请选择此项。':'用于应用商店处理和分发，仅支持 Release 构建。'}}</small></el-form-item>
    <div class="mobile-build-fields"><el-form-item label="Flutter 固定版本" required><el-input v-model="model.flutterVersion" placeholder="例如 3.35.7"/><small>填写项目使用的完整版本号，例如 3.47.3。</small></el-form-item><el-form-item label="App 构建超时（秒）"><el-input-number v-model="model.buildTimeoutSeconds" :min="60" :max="7200" :step="60" :precision="0"/></el-form-item></div>
    <el-form-item label="默认 Dart 入口文件"><el-input v-model="model.target" placeholder="lib/main.dart"/></el-form-item>
    <el-form-item label="App 版本管理"><el-radio-group v-model="versionPolicy" aria-label="App 版本策略" :disabled="!authUser?.admin"><el-radio-button value="AUTO">自动管理</el-radio-button><el-radio-button value="MANUAL">手动配置</el-radio-button></el-radio-group><small>{{authUser?.admin?'仅影响此 App 后续新建的构建，历史安装包和任务保留原版本。':'当前策略由管理员设置；你仍可按现有策略构建 App。'}}</small></el-form-item>
    <div v-if="automaticMobileVersion(model)" class="version-policy-guide"><b>按环境自动编号，生产使用已验证的 PRE 代码</b><p>PRE：0.1.0-pre → 0.1.1-pre → … → 0.1.9-pre → 0.2.0-pre。</p><p>生产版本独立从 0.0.1 开始递增，不加后缀；选择成功的 PRE 版本后，锁定其分支和 Commit 构建。</p><small>版本在加入队列时由平台分配；失败或取消不回收已分配的编号。内部 Android versionCode 自动递增，用于覆盖升级校验。</small></div>
    <el-alert title="构建机准备" description="构建机需要安装匹配的 Flutter、Android SDK 和签名资料。此处只引用已准备的签名，不上传密钥或密码。" type="info" :closable="false" show-icon/>

    <div class="environment-heading"><h4>按环境配置安装包</h4><p>先确定环境，再绑定包名、签名与可选构建参数。构建后的安装包只能发布到同一环境。</p></div>
    <el-tabs v-model="active" type="card" class="mobile-env-tabs" aria-label="App 构建环境配置">
      <el-tab-pane v-for="env in mobileEnvironments" :key="env.name" :name="env.name"><template #label><span>{{env.label}}环境 <i class="env-indicator" :class="{'is-enabled':model.environments?.[env.name]?.enabled}"/></span></template></el-tab-pane>
    </el-tabs>
    <section class="mobile-env-config" :class="{'is-production':active==='prod'}" :aria-label="environmentLabel(active)+'构建配置'">
      <div class="env-config-title"><div><h4>{{environmentLabel(active)}}安装包</h4><p>{{active==='prod'?'生产安装包使用 Release 模式和正式签名。':'可以沿用已安装测试包的原签名，保持覆盖升级兼容。'}}</p></div><el-switch v-model="profile.enabled" :active-text="'启用'+environmentLabel(active)+'构建'"/></div>
      <div v-if="legacy" class="legacy-config"><details><summary>查看旧的全局配置</summary><dl><dt>包名</dt><dd>{{model.applicationId || '未填写'}}</dd><dt>签名名称</dt><dd>{{model.signingProfile || '未填写'}}</dd><dt>证书指纹</dt><dd>{{model.expectedCertificateSha256 || '未填写'}}</dd></dl></details><p>旧值已保留，不会自动用于任何环境。复制后仍需核对并启用。</p><el-button size="small" @click="copyLegacy">复制旧配置到{{environmentLabel(active)}}</el-button></div>
      <div class="mobile-build-fields"><el-form-item label="Android applicationId" required><el-input v-model="profile.applicationId" placeholder="com.example.app"/><small>预生产建议使用独立的 .pre 包名，避免覆盖正式 App。</small></el-form-item><el-form-item label="构建模式" required><el-radio-group v-model="profile.buildMode"><el-radio-button value="DEBUG" :disabled="active==='prod'||mobileFormat(model)==='AAB'">Debug</el-radio-button><el-radio-button value="RELEASE">Release</el-radio-button></el-radio-group><small>{{active==='prod'?'生产固定使用 Release。':'Debug 与签名是两项独立配置，仍需指定固定证书。'}}</small></el-form-item></div>
      <el-form-item label="服务端签名配置" required>
        <div class="signing-picker"><el-select v-model="profile.signingProfile" :loading="profileLoading" filterable clearable placeholder="选择构建机上已登记的签名" @change="chooseSigning"><el-option v-if="profile.signingProfile&&!selectedSigning" :value="profile.signingProfile" :label="profile.signingProfile+'（构建机未找到）'" disabled/><el-option v-for="item in profiles" :key="item.name" :value="item.name" :label="item.name+(item.debugCertificate?' · 测试证书':' · 正式证书')" :disabled="!item.ready||((active==='prod'||profile.buildMode==='RELEASE')&&item.debugCertificate)"/></el-select><el-button :loading="profileLoading" @click="loadProfiles">刷新签名</el-button></div>
        <small v-if="profileError" class="field-error">{{profileError}}。已有配置保留，可刷新重试。</small><small v-else-if="!profileLoading && (!registryConfigured||!profiles.length)">还没有可用签名。请管理员在构建机登记 PRE 原签名和生产正式签名，再点击“刷新签名”。未完成时可关闭环境并保存草稿。</small><small v-else-if="selectedSigning&&!selectedSigning.ready" class="field-error">{{selectedSigning.message || '该签名尚未准备完成'}}</small>
      </el-form-item>
      <el-form-item :label="mobileFormat(model)==='APK'?'应用签名证书 SHA-256 指纹':'上传签名证书 SHA-256 指纹'" required><el-input v-model="profile.expectedCertificateSha256" readonly placeholder="选择签名后自动读取证书指纹"/><small>从构建机证书读取。覆盖升级时必须与已安装版本的签名一致。</small></el-form-item>
      <el-form-item v-if="active!=='prod'&&profile.buildMode==='DEBUG'"><el-checkbox v-model="profile.allowDebugSigning">允许当前环境进行 Debug 构建并使用已登记的测试证书</el-checkbox><small>仍需匹配指定证书指纹；切换为 Release 后自动关闭此许可。</small></el-form-item>
      <el-alert v-if="selectedSigning?.debugCertificate && profile.buildMode==='RELEASE'" title="Release 构建不允许 Debug 测试证书，请改选正式签名或在非生产环境使用 Debug 构建。" type="warning" :closable="false" show-icon/>
      <el-alert v-else-if="selectedSigning?.debugCertificate && !profile.allowDebugSigning" title="当前选择的是 Debug 测试证书，请明确勾选允许后再构建。" type="warning" :closable="false" show-icon/>
      <section v-for="group in mobileParameterGroups" :key="group" class="build-parameters" :aria-label="groupLabels[group]">
        <div class="parameters-heading"><div><h4>{{groupLabels[group]}}</h4><p>{{group === 'dartDefines' ? '通过 --dart-define 传给应用，请使用项目声明的名称。' : '仅传给构建进程。名称以 APP_BUILD_ 开头，不要填写凭据。'}}</p></div><span>{{Object.keys(profile[group]).length}} / 40 项</span></div>
        <div v-for="(_, key) in profile[group]" :key="key" class="parameter-row">
          <code class="parameter-name">{{key}}</code>
          <div class="parameter-value"><el-input v-model="profile[group][key]" :aria-label="key + ' 参数值'" :maxlength="2048"/><small v-if="mobileParameterError(group,key,profile[group][key])" class="field-error">{{mobileParameterError(group,key,profile[group][key])}}</small></div>
          <el-button link type="danger" :aria-label="'移除 ' + key" @click="delete profile[group][key]">移除</el-button>
        </div>
        <div class="parameter-add-row">
          <el-input v-model="drafts[active][group].key" :aria-label="groupLabels[group] + '名称'" :maxlength="80" :placeholder="group === 'dartDefines' ? '例如 FEATURE_ENABLED' : '例如 APP_BUILD_MODE'" @input="drafts[active][group].error=''"/>
          <el-input v-model="drafts[active][group].value" :aria-label="groupLabels[group] + '值'" :maxlength="2048" placeholder="参数值（可为空）" @input="drafts[active][group].error=''" @keydown.enter.prevent="addParameter(group)"/>
          <el-button type="primary" plain @click="addParameter(group)">添加</el-button>
        </div>
        <p v-if="drafts[active][group].error" class="parameter-error" role="alert">{{drafts[active][group].error}}</p>
      </section>
      <details v-if="!compact" class="mobile-advanced"><summary>版本、Flavor 与高级构建参数</summary>
        <div class="mobile-build-fields"><el-form-item label="Flavor（选填）"><el-input v-model="profile.flavor" placeholder="项目未配置 Flavor 时留空"/></el-form-item><el-form-item label="当前环境 Dart 入口（选填）"><el-input v-model="profile.target" :placeholder="model.target||'lib/main.dart'"/></el-form-item></div>
        <div v-if="!automaticMobileVersion(model)" class="mobile-build-fields"><el-form-item label="版本名称（选填）"><el-input v-model="profile.versionName" placeholder="留空使用 pubspec.yaml"/></el-form-item><el-form-item label="内部 versionCode（选填）"><el-input-number v-model="profile.versionCode" :min="1" :max="2100000000" :precision="0" placeholder="使用项目配置"/><small>Android 覆盖升级使用的整数，需大于已分发版本。</small></el-form-item></div><p v-else class="field-help">当前为自动版本管理，构建时由平台分配版本。已有手动配置会保留，内部升级序号会参考历史记录与已登记基线继续递增。</p>
        <el-form-item v-if="mobileFormat(model)==='APK'" label="APK 输出路径（选填）"><el-input v-model="profile.apkPath" :placeholder="profile.buildMode==='DEBUG'?'build/app/outputs/flutter-apk/app-debug.apk':'build/app/outputs/flutter-apk/app-release.apk'"/><small>留空按构建模式和 Flavor 自动定位完整 APK。</small></el-form-item><el-form-item v-else label="AAB 输出路径（选填）"><el-input v-model="profile.aabPath" placeholder="build/app/outputs/bundle/release/app-release.aab"/></el-form-item>
      </details>
      <el-alert v-if="validation" class="environment-validation" :title="validation" type="warning" :closable="false" show-icon/>
      <p class="environment-saving-note">{{profile.enabled?'保存后仅影响此环境的新构建，历史安装包和发布记录保持原样。':'此环境尚未启用，可以保存未完成的配置，暂不会出现在构建环境选项中。'}}</p>
    </section>
  </div>
</template>

<style scoped>
.build-parameters{margin:24px 0 20px;padding-top:20px;border-top:1px solid #e6ecf4}.parameters-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:14px;margin-bottom:16px}.parameters-heading h4{margin:0 0 7px!important;font-size:15px;color:#304863}.parameters-heading p{margin:0;color:#74849a;font-size:12px;line-height:1.8}.parameters-heading>span{padding-top:3px;font-size:12px;color:#74849a;white-space:nowrap}.parameter-table-head,.parameter-row,.parameter-add-row{display:grid;grid-template-columns:minmax(0,1.15fr) minmax(0,1.5fr) 82px;align-items:start;gap:12px}.parameter-table-head{padding:10px 0;background:#f7f9fc;font-size:11px;color:#74849a}.parameter-table-head>span:first-child{padding-left:10px}.parameter-row{padding:14px 0;border-bottom:1px solid #e6ecf4}.parameter-name{min-width:0;padding-top:6px}.parameter-name code{font-size:12px;line-height:1.7;overflow-wrap:anywhere;color:#354b68}.parameter-name small{margin-top:3px}.parameter-value{min-width:0}.parameter-row>.el-button{margin-top:3px}.parameter-empty{padding:16px;background:#f7f9fc;margin:0;color:#74849a;font-size:12px;line-height:1.8}.parameter-add-row{margin-top:16px;align-items:center}.parameter-add-row .el-autocomplete{width:100%;min-width:0}.parameter-note{margin:10px 0 0;color:#74849a;font-size:11px;line-height:1.8}.parameter-error{margin:10px 0 0;color:#bc584d;font-size:12px;line-height:1.7;overflow-wrap:anywhere}.parameter-suggestion{padding:5px 0;line-height:1.6;white-space:normal;overflow-wrap:anywhere}.parameter-suggestion b{display:block;font-size:12px;font-weight:500}.parameter-suggestion small{display:block;color:#74849a;font-size:11px;line-height:1.7}
@media(max-width:650px){.parameter-table-head{display:none}.parameter-row{grid-template-columns:minmax(0,1fr) 44px;gap:8px 10px}.parameter-row .parameter-name{grid-column:1;grid-row:1}.parameter-row .parameter-value{grid-column:1/-1;grid-row:2}.parameter-row>.el-button{grid-column:2;grid-row:1;justify-self:end}.parameter-add-row{grid-template-columns:1fr;gap:10px}.parameter-add-row>.el-button{width:100%}.parameters-heading{gap:8px}}
.version-policy-guide{padding:15px 17px;margin:0 0 22px;border-left:3px solid #6b87c4;border-radius:6px;background:#f3f6fb;color:#52647c;font-size:12px;line-height:1.8}.version-policy-guide>b{color:#354b68;font-size:13px}.version-policy-guide p{margin:7px 0}
.mobile-build-settings :deep(.el-checkbox){height:auto;max-width:100%;align-items:flex-start}.mobile-build-settings :deep(.el-checkbox__label){white-space:normal;line-height:1.7;overflow-wrap:anywhere}.mobile-build-settings :deep(.el-checkbox__input){margin-top:4px}
.shared-heading,.environment-heading{margin:0 0 20px}.shared-heading h4,.environment-heading h4,.address-heading h4{margin:0 0 7px!important;font-size:15px;color:#304863}.shared-heading span,.environment-heading p,.address-heading p{margin:0;font-size:12px;line-height:1.8;color:#74849a}.environment-heading{margin-top:30px}.mobile-build-fields{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:18px}.mobile-build-settings small{display:block;width:100%;font-size:12px;color:#74849a;line-height:1.65;margin-top:6px;overflow-wrap:anywhere}.mobile-build-settings :deep(.el-input-number),.mobile-build-settings :deep(.el-select){width:100%}.mobile-env-tabs :deep(.el-tabs__header){margin-bottom:0}.env-indicator{display:inline-block;width:6px;height:6px;border-radius:50%;background:#bdc8d6;margin-left:7px;vertical-align:middle}.env-indicator.is-enabled{background:#2b9a70}.mobile-env-config{padding:23px 20px 8px;border:1px solid #dce5f1;border-top:0;border-radius:0 0 10px 10px}.env-config-title{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:22px}.env-config-title h4{margin:0 0 7px!important;font-size:16px;color:#304863}.env-config-title p{font-size:12px;line-height:1.8;color:#74849a;margin:0}.is-production .env-config-title h4{color:#a35340}.signing-picker{display:flex;gap:10px;width:100%}.signing-picker .el-select{flex:1;min-width:0}.legacy-config{padding:13px 15px;background:#f6f8fb;border-radius:7px;margin-bottom:24px;font-size:12px;color:#687b94}.legacy-config summary{cursor:pointer;color:#415c80}.legacy-config dl{display:grid;grid-template-columns:74px minmax(0,1fr);gap:7px}.legacy-config dd{margin:0;overflow-wrap:anywhere}.legacy-config p{line-height:1.7}.address-heading{margin:25px 0 17px;padding-top:22px;border-top:1px solid #e6ecf4}.mobile-advanced{margin-top:22px}.mobile-advanced summary{cursor:pointer;color:#4169bb;font-size:13px;margin-bottom:20px}.define-row{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.6fr) 65px;align-items:center;gap:9px;margin:9px 0;font-size:12px}.define-row>span{overflow-wrap:anywhere}.environment-validation{margin-top:18px}.environment-saving-note{color:#74849a;font-size:12px;line-height:1.8;margin-top:20px}.field-error{color:#bc584d!important}
@media(max-width:650px){.mobile-build-fields{grid-template-columns:1fr;gap:0}.mobile-env-config{padding:18px 12px 7px}.env-config-title{flex-direction:column}.define-row{grid-template-columns:minmax(0,1fr) minmax(0,1fr) 54px}.signing-picker{flex-wrap:wrap}.signing-picker .el-select{flex-basis:100%}}
</style>
