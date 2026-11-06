<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { RefreshRight, Lock } from '@element-plus/icons-vue'
import { errorText, http, payload } from '../api'
import { useProductionApproval } from '../composables/useProductionApproval'
import type { RuntimeOperation, RuntimeRestartPreview, RuntimeRow } from '../runtime'
import { environmentLabel, newRequestId } from '../ui'
import OtpInput from './OtpInput.vue'

const props = defineProps<{ modelValue: boolean; row: RuntimeRow | null }>()
const emit = defineEmits<{ 'update:modelValue': [value:boolean]; submitted: [value:RuntimeOperation]; uncertain: [] }>()
const preview = ref<RuntimeRestartPreview | null>(null), loading = ref(false), submitting = ref(false), error = ref(''), code = ref('')
const now = ref(Date.now())
const approval = useProductionApproval()
const router = useRouter()
const { approver, enabled, remaining, loading: approvalLoading, unavailable } = approval
let request = 0, requestId = '', disposed = false
const timer = window.setInterval(() => { now.value = Date.now() }, 1000)
const production = computed(() => preview.value?.environmentName === 'prod' || props.row?.environmentName === 'prod')
const expired = computed(() => Boolean(preview.value && Date.parse(preview.value.expiresAt) <= now.value))
const canSubmit = computed(() => Boolean(preview.value && !expired.value && !loading.value && !submitting.value && (!production.value || enabled.value && !unavailable.value && !approvalLoading.value && !remaining.value && code.value.length === 6)))
const open = computed({get:() => props.modelValue,set:value => { if (!submitting.value) emit('update:modelValue',value) }})

async function loadPreview() {
  if (!props.row?.serverId || submitting.value) return
  const current = ++request, row = props.row
  preview.value = null; error.value = ''; code.value = ''; loading.value = true; requestId = newRequestId()
  try {
    const data = payload<RuntimeRestartPreview>(await http.post('/runtime/restart-preview', {environmentId:row.environmentId,serverId:row.serverId}, {timeout:45000}), null as unknown as RuntimeRestartPreview)
    if (disposed || current !== request || !props.modelValue) return
    if (!data?.token || !data.containers?.length) throw new Error('目标容器信息不完整，请重新确认')
    preview.value = data; now.value = Date.now()
    if (data.environmentName === 'prod') await approval.refresh()
  } catch (reason) { if (!disposed && current === request) error.value = errorText(reason,'无法确认目标容器') }
  finally { if (!disposed && current === request) loading.value = false }
}
async function submit() {
  if (!canSubmit.value || !preview.value) return
  submitting.value = true; error.value = ''
  try {
    const operation = payload<RuntimeOperation>(await http.post('/runtime/restarts',{token:preview.value.token,requestId,totpCode:production.value?code.value:undefined}), null as unknown as RuntimeOperation)
    if (!operation?.id) throw new Error('未读取到操作编号，请检查操作记录')
    ElMessage.success(`更新配置并重启 #${operation.id} 已提交`)
    emit('submitted',operation); emit('update:modelValue',false)
  } catch (reason) { error.value = errorText(reason); approval.handleError(reason); emit('uncertain') }
  finally { submitting.value = false }
}
watch(() => props.modelValue, value => { if (value) loadPreview(); else { request++; preview.value = null; code.value = '' } })
onBeforeUnmount(() => { disposed = true; request++; window.clearInterval(timer) })
</script>

<template>
  <el-dialog v-model="open" title="更新配置并重启" width="600px" :close-on-click-modal="false" :close-on-press-escape="!submitting" :show-close="!submitting" destroy-on-close class="restart-dialog">
    <div v-loading="loading" class="restart-content">
      <p class="restart-intro">读取服务器上最新的 Compose 和环境配置，使用当前镜像重新创建所选服务，完成后检查运行状态。</p>
      <div class="restart-scope"><span>重新加载 .env / env_file</span><span>保留当前镜像版本</span><span>仅操作所选服务</span></div>
      <el-alert title="服务会短暂中断。请先保存服务器配置；共用配置修改后，其他受影响服务需分别操作。" type="warning" :closable="false" show-icon/>
      <dl v-if="row" class="restart-target"><dt>服务</dt><dd>{{preview?.serviceName || row.serviceName}} <small>{{preview?.serviceKey || row.serviceKey}}</small></dd><dt>环境</dt><dd><el-tag :type="production?'danger':'info'" size="small">{{environmentLabel(preview?.environmentName || row.environmentName)}}</el-tag></dd><dt>目标节点</dt><dd>{{preview?.serverName || row.serverName}}<small>{{preview?.host || row.host}}</small></dd></dl>
      <div v-if="preview" class="restart-containers"><div class="restart-label">已核对 {{preview.containers.length}} 个容器</div><div v-for="container in preview.containers" :key="container.id"><b>{{container.name}}</b><code>{{container.imageReference}}</code><small>{{container.id.slice(0,12)}}</small></div></div>
      <div v-if="production && preview" class="restart-approval"><h4><el-icon><Lock/></el-icon>生产操作确认</h4><p>请输入当前账号 <b>{{approver}}</b> 的 Google Authenticator 动态码。</p><OtpInput v-model="code" :disabled="submitting || approvalLoading || remaining > 0 || unavailable || !enabled" label="当前账号动态码" @submit="submit"/><p v-if="remaining > 0" class="restart-code-wait">该动态码已使用，{{remaining}} 秒后可输入新动态码。</p><p v-else-if="unavailable || !enabled" class="restart-code-wait">{{unavailable?'无法读取验证状态，请重试。':'当前账号尚未绑定动态码，请先配置二次认证。'}} <el-button v-if="unavailable" link @click="approval.refresh()">重新读取</el-button><el-button v-else link type="primary" @click="open=false; router.push('/settings/security')">绑定我的验证器</el-button></p></div>
      <el-alert v-if="expired" title="本次确认已过期，请重新核对目标容器。" type="info" :closable="false" show-icon/>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="restart-error"/>
      <el-button v-if="expired || error && !preview" :icon="RefreshRight" :loading="loading" :disabled="submitting" class="restart-retry" @click="loadPreview">重新确认目标</el-button>
    </div>
    <template #footer><el-button :disabled="submitting" @click="open=false">取消</el-button><el-button type="primary" :loading="submitting" :disabled="!canSubmit" :icon="RefreshRight" @click="submit">{{production?'确认更新生产配置并重启':'确认更新配置并重启'}}</el-button></template>
  </el-dialog>
</template>

<style scoped>
.restart-scope{display:flex;flex-wrap:wrap;gap:7px;margin:-3px 0 18px}.restart-scope span{padding:5px 9px;border-radius:6px;background:#eef4ff;color:#4f6d9d;font-size:12px;line-height:1.5}
.restart-content{min-height:150px}.restart-intro{margin:0 0 16px;color:#77869b;font-size:13px;line-height:1.7}.restart-target{display:grid;grid-template-columns:72px minmax(0,1fr);gap:14px 12px;margin:23px 0;color:#4c607b;font-size:13px}.restart-target dt{color:#8897aa}.restart-target dd{margin:0;overflow-wrap:anywhere}.restart-target small{display:block;color:#8c99ab;font-size:11px;margin-top:4px}.restart-containers{border:1px solid #e5eaf2;border-radius:10px;background:#f8fafc;margin-bottom:20px;padding:15px 17px}.restart-label{font-size:11px;color:#8a97a9;margin-bottom:12px}.restart-containers>div+div{padding:10px 0;border-top:1px solid #e8edf4}.restart-containers b{display:block;font-size:12px;font-weight:500;color:#4f6581}.restart-containers code{display:block;margin-top:5px;font:11px/1.7 ui-monospace,Consolas,monospace;color:#71829b;overflow-wrap:anywhere}.restart-containers small{font-size:10px;color:#a0aabb}.restart-approval{border-top:1px solid #e7edf5;padding-top:17px;margin-bottom:20px}.restart-approval h4{display:flex;align-items:center;gap:7px;margin:0;font-size:13px;color:#50617a}.restart-approval p{font-size:12px;color:#8895a8;line-height:1.7;margin:10px 0 15px}.restart-code-wait{color:#ad7547!important;text-align:center}.restart-error{margin-top:15px}.restart-retry{margin-top:14px}
</style>
