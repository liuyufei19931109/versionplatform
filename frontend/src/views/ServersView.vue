<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Connection, Edit, Plus, Refresh, Delete } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorText, http, payload, rows } from '../api'
import type { DeploymentServer } from '../types'

const loading=ref(false),saving=ref(false),dialog=ref(false),testing=ref<string|number>(''),servers=ref<DeploymentServer[]>([])
const testDialog=ref(false),testResult=reactive({name:'',ssh:'',compose:'',message:'',services:''})
const advanced=ref<string[]>([])
const form=reactive<DeploymentServer>({connectionMode:'DIRECT',sshConnectTimeoutSeconds:10,sshCommandTimeoutSeconds:600,businessUrl:'',name:'',host:'',port:22,username:'root',authType:'SSH_KEY',privateKeyPath:'',password:'',passwordConfigured:false,credentialHint:'',description:'',composeDirectory:'',composeFile:'docker-compose.yml',composeUseSudo:false,enabled:true})

async function load(){loading.value=true;try{servers.value=rows<DeploymentServer>((await http.get('/servers')).data)}catch(e){ElMessage.error(errorText(e))}finally{loading.value=false}}
const configMode=computed(()=>form.connectionMode==='SSH_CONFIG')
function mode(server:DeploymentServer){return server.connectionMode==='SSH_CONFIG'?'SSH_CONFIG':'DIRECT'}
function connectTimeout(server:DeploymentServer){const value=server.sshConnectTimeoutSeconds;return Number.isInteger(value)&&Number(value)>=5&&Number(value)<=300?Number(value):mode(server)==='SSH_CONFIG'?60:10}
function safeBusinessUrl(value?:string|null){if(!value?.trim())return '';try{const url=new URL(value.trim());return ['http:','https:'].includes(url.protocol)&&!url.username&&!url.password&&url.hostname?url.href:''}catch{return ''}}
function changeMode(value:string){if(value==='SSH_CONFIG'){form.authType='SSH_KEY';form.password=''}const previousDefault=value==='SSH_CONFIG'?10:60;if(form.sshConnectTimeoutSeconds===previousDefault||form.sshConnectTimeoutSeconds==null)form.sshConnectTimeoutSeconds=value==='SSH_CONFIG'?60:10}
function open(server?:DeploymentServer){Object.assign(form,{id:undefined,connectionMode:'DIRECT',sshConnectTimeoutSeconds:10,sshCommandTimeoutSeconds:600,businessUrl:'',name:'',host:'',port:22,username:'root',authType:'SSH_KEY',privateKeyPath:'',password:'',passwordConfigured:false,credentialHint:'',description:'',composeDirectory:'',composeFile:'docker-compose.yml',composeUseSudo:false,enabled:true},server||{},{connectionMode:server?mode(server):'DIRECT',sshConnectTimeoutSeconds:server?connectTimeout(server):10,sshCommandTimeoutSeconds:server?.sshCommandTimeoutSeconds??600,businessUrl:server?.businessUrl||'',password:''});advanced.value=[];dialog.value=true}
async function save(){
  if(saving.value)return
  if(!form.name.trim()||!form.host.trim())return ElMessage.warning(configMode.value?'请填写服务器名称和 SSH 配置别名':'请填写服务器名称和地址')
  if(configMode.value&&!/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(form.host.trim()))return ElMessage.warning('SSH 配置别名只能包含字母、数字、点、下划线和短横线，不能填写命令或 URL')
  if(!configMode.value&&!form.username.trim())return ElMessage.warning('请填写 SSH 用户')
  if(!configMode.value&&form.authType==='PASSWORD'&&!form.password&&!form.passwordConfigured)return ElMessage.warning('请填写 SSH 密码')
  if(!Number.isInteger(form.sshConnectTimeoutSeconds)||Number(form.sshConnectTimeoutSeconds)<5||Number(form.sshConnectTimeoutSeconds)>300)return ElMessage.warning('连接超时请填写 5 至 300 秒的整数')
  if(!Number.isInteger(form.sshCommandTimeoutSeconds)||Number(form.sshCommandTimeoutSeconds)<10||Number(form.sshCommandTimeoutSeconds)>3600)return ElMessage.warning('命令超时请填写 10 至 3600 秒的整数')
  if(form.businessUrl?.trim()&&!safeBusinessUrl(form.businessUrl))return ElMessage.warning('业务访问地址请填写不含用户名或密码的完整 http:// 或 https:// 地址')
  saving.value=true
  try{const body={...form,name:form.name.trim(),host:form.host.trim(),businessUrl:form.businessUrl?.trim()||''};if(configMode.value){body.authType='SSH_KEY';delete body.password;}if(form.id)await http.put(`/servers/${form.id}`,body);else await http.post('/servers',body);ElMessage.success('服务器已保存');dialog.value=false;await load()}catch(e){ElMessage.error(errorText(e))}finally{saving.value=false}
}
async function remove(server:DeploymentServer){try{await ElMessageBox.confirm(`确认删除服务器“${server.name}”吗？`,'删除服务器',{type:'warning'});await http.delete(`/servers/${server.id}`);ElMessage.success('服务器已删除');await load()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(errorText(e))}}
async function test(server:DeploymentServer){
  if(testing.value)return
  testing.value=server.id||'';Object.assign(testResult,{name:server.name,ssh:'',compose:'',message:'',services:''})
  try{const result=payload<{message?:string;services?:string;sshStatus?:string;composeStatus?:string}>(await http.post(`/servers/${server.id}/test`,undefined,{timeout:(connectTimeout(server)*2+90)*1000}),{});Object.assign(testResult,{ssh:result.sshStatus||'SUCCESS',compose:result.composeStatus||'SUCCESS',message:result.message||'连接检查完成',services:result.services||''})}
  catch(e){const message=errorText(e,'连接检查失败'),data=(e as {response?:{data?:{sshStatus?:string;composeStatus?:string}}}).response?.data;Object.assign(testResult,{message,ssh:data?.sshStatus||(message.startsWith('SSH 已连接')?'SUCCESS':message.startsWith('SSH 连接失败')?'FAILED':'UNKNOWN'),compose:data?.composeStatus||(message.startsWith('SSH 已连接')?'FAILED':'NOT_RUN')})}
  finally{testing.value='';testDialog.value=true}
}
onMounted(load)
</script>

<template>
  <div class="page-head"><div><h2>服务器管理</h2><p>统一维护集群节点；同一台服务器可以被多个服务和环境复用。</p></div><div class="actions"><el-button :icon="Refresh" @click="load">刷新</el-button><el-button type="primary" :icon="Plus" @click="open()">添加服务器</el-button></div></div>
  <section class="panel" v-loading="loading">
    <el-table :data="servers" empty-text="还没有服务器，请先添加">
      <el-table-column label="服务器" min-width="180"><template #default="{row}"><b>{{row.name}}</b><small class="server-sub">{{row.description||'暂无说明'}}</small></template></el-table-column>
      <el-table-column label="连接地址" min-width="210"><template #default="{row}"><span class="mono">{{mode(row)==='SSH_CONFIG'?row.host:`${row.username}@${row.host}:${row.port}`}}</span><small v-if="mode(row)==='SSH_CONFIG'" class="server-sub">SSH 配置别名</small><a v-if="safeBusinessUrl(row.businessUrl)" class="business-link" :href="safeBusinessUrl(row.businessUrl)" target="_blank" rel="noopener noreferrer">节点业务入口</a></template></el-table-column>
      <el-table-column label="连接方式" width="145"><template #default="{row}"><el-tag effect="plain" :type="mode(row)==='SSH_CONFIG'?'primary':'info'">{{mode(row)==='SSH_CONFIG'?'SSH 配置 · IAP':'直接 SSH'}}</el-tag><small v-if="mode(row)==='DIRECT'" class="server-sub">{{row.authType==='PASSWORD'?'用户名密码':'SSH Key'}}</small></template></el-table-column>
      <el-table-column label="认证配置" min-width="220" show-overflow-tooltip><template #default="{row}"><span v-if="mode(row)==='SSH_CONFIG'">由平台受控 SSH 配置管理</span><span v-else-if="row.authType==='PASSWORD'">{{row.passwordConfigured?'密码已加密保存':'未配置密码'}}</span><span v-else class="mono">{{row.privateKeyPath||'系统默认 SSH Key'}}</span></template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="{row}"><el-tag :type="row.enabled?'success':'info'">{{row.enabled?'启用':'停用'}}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="255" fixed="right"><template #default="{row}"><el-button link type="success" :icon="Connection" :loading="testing===row.id" @click="test(row)">测试连接</el-button><el-button link type="primary" :icon="Edit" @click="open(row)">编辑</el-button><el-button link type="danger" :icon="Delete" @click="remove(row)">删除</el-button></template></el-table-column>
    </el-table>
  </section>

  <el-dialog v-model="dialog" :title="form.id?'编辑服务器':'添加服务器'" width="620px" destroy-on-close>
    <el-form label-position="top" :disabled="saving">
      <el-form-item label="连接方式" required><el-radio-group v-model="form.connectionMode" @change="changeMode"><el-radio-button value="DIRECT">直接 SSH</el-radio-button><el-radio-button value="SSH_CONFIG">SSH 配置别名（IAP）</el-radio-button></el-radio-group></el-form-item>
      <div class="two"><el-form-item label="服务器名称" required><el-input v-model="form.name" placeholder="例如：测试节点 1"/></el-form-item><el-form-item :label="configMode?'SSH 配置别名':'服务器地址'" required><el-input v-model="form.host" :placeholder="configMode?'例如：staging-01':'IP 地址或域名'"/></el-form-item></div>
      <el-alert v-if="configMode" class="iap-help" title="使用平台运行账号的受控 SSH 配置" description="请由运维提前为该别名配置 Google 认证、IAP 连接方式和 SSH 密钥。页面只填写别名，不编辑代理命令或配置文件路径；保存不会自动配置 Google 认证。" type="info" :closable="false" show-icon/>
      <template v-else><div class="two"><el-form-item label="SSH 用户" required><el-input v-model="form.username" placeholder="root"/></el-form-item><el-form-item label="SSH 端口" required><el-input-number v-model="form.port" :min="1" :max="65535" style="width:100%"/></el-form-item></div>
      <el-form-item label="认证方式" required><el-radio-group v-model="form.authType"><el-radio-button value="SSH_KEY">SSH Key</el-radio-button><el-radio-button value="PASSWORD">用户名密码</el-radio-button></el-radio-group></el-form-item>
      <el-form-item v-if="form.authType==='SSH_KEY'" label="私钥文件路径"><el-input v-model="form.privateKeyPath" placeholder="/opt/release-platform/ssh/server_key"/><div class="field-help">填写平台服务器上的私钥路径，留空使用默认 SSH Key。</div></el-form-item>
      <el-form-item v-else label="SSH 密码" required><el-input v-model="form.password" type="password" show-password autocomplete="new-password" :placeholder="form.passwordConfigured?'留空表示继续使用原密码':'请输入 SSH 登录密码'"/><div class="field-help">密码加密保存，保存后不会回显。</div></el-form-item>
      </template>
      <el-form-item label="凭据备注"><el-input v-model="form.credentialHint" placeholder="例如：生产机专用密钥"/></el-form-item>
      <el-form-item label="节点业务入口（可选）"><el-input v-model="form.businessUrl" placeholder="https://app.example.com"/><div class="field-help">共享节点只填写共同入口，不代表每个服务的访问地址；不参与 SSH 或 Compose 连接。请勿在地址中填写用户名、密码。</div></el-form-item>
      <div class="two"><el-form-item label="Compose 项目目录（可选）"><el-input v-model="form.composeDirectory" placeholder="/srv/services"/></el-form-item><el-form-item label="Compose 文件"><el-input v-model="form.composeFile" placeholder="docker-compose.yml"/></el-form-item></div>
      <el-form-item><el-switch v-model="form.composeUseSudo" active-text="Compose 命令使用 sudo"/><div class="field-help">{{configMode?'SSH 配置连接不使用本页密码；如启用 sudo，请由运维为该账号配置必要的免交互权限。':'密码认证时，平台通过 SSH 标准输入交给 sudo，不会把密码写入命令或日志。'}}</div></el-form-item>
      <el-alert type="info" :closable="false" show-icon title="连接检查会分别显示 SSH 和 Compose 状态；仅使用容器部署时可以不填 Compose 目录。"/>
      <el-collapse v-model="advanced" class="connection-advanced"><el-collapse-item title="高级连接设置" name="timeouts"><div class="two"><el-form-item label="SSH 连接超时（秒）"><el-input-number v-model="form.sshConnectTimeoutSeconds" :min="5" :max="300" :precision="0" style="width:100%"/><div class="field-help">{{configMode?'IAP 建议 60 秒':'直连默认 10 秒'}}；允许 5 至 300 秒。</div></el-form-item><el-form-item label="SSH 命令超时（秒）"><el-input-number v-model="form.sshCommandTimeoutSeconds" :min="10" :max="3600" :precision="0" style="width:100%"/><div class="field-help">默认 600 秒；允许 10 至 3600 秒。连接检查会使用较短的检查命令时限。</div></el-form-item></div></el-collapse-item></el-collapse>
      <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="2" placeholder="机房、用途等信息"/></el-form-item>
      <el-form-item><el-switch v-model="form.enabled" active-text="允许发版到此服务器"/></el-form-item>
    </el-form>
    <template #footer><el-button :disabled="saving" @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
  </el-dialog>
  <el-dialog v-model="testDialog" title="连接检查结果" width="540px"><p class="dialog-description">{{testResult.name}}</p><div class="health-row"><span>SSH 连接</span><el-tag :type="testResult.ssh==='SUCCESS'?'success':testResult.ssh==='FAILED'?'danger':'info'">{{testResult.ssh==='SUCCESS'?'已连接':testResult.ssh==='FAILED'?'连接失败':'未能确认'}}</el-tag></div><div class="health-row"><span>Compose 配置</span><el-tag :type="testResult.compose==='SUCCESS'?'success':testResult.compose==='FAILED'?'danger':'info'">{{testResult.compose==='SUCCESS'?'检查通过':testResult.compose==='FAILED'?'检查失败':testResult.compose==='NOT_CONFIGURED'?'未配置':'未执行'}}</el-tag></div><p class="dialog-description" style="margin-top:20px">{{testResult.message}}</p><pre v-if="testResult.services" class="log-box" style="min-height:80px;max-height:200px">{{testResult.services}}</pre><template #footer><el-button type="primary" @click="testDialog=false">知道了</el-button></template></el-dialog>
</template>

<style scoped>.iap-help{margin:0 0 23px}.business-link{display:block;width:max-content;margin-top:7px;color:#4e73c3;font-size:12px;text-decoration:none}.business-link:hover{text-decoration:underline}.connection-advanced{margin:22px 0;border-top:1px solid #e8edf5;border-bottom:1px solid #e8edf5}.connection-advanced :deep(.el-collapse-item__header){font-size:12px;font-weight:500;color:#617593}.connection-advanced :deep(.el-collapse-item__content){padding-top:8px;padding-bottom:0}.field-help{line-height:1.7}.two{display:grid;grid-template-columns:1fr 1fr;gap:18px}.server-sub{display:block;margin-top:5px;color:#98a2b3}.field-help{margin-top:6px;color:#98a2b3;font-size:12px}@media(max-width:650px){.two{grid-template-columns:1fr;gap:0}.iap-help :deep(.el-alert__description){line-height:1.7}}</style>
