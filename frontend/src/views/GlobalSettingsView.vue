<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Edit, Lock, Plus, Refresh, Setting } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { errorText, http, rows } from '../api'
import { authUser } from '../auth'
import BrandingSettings from '../components/BrandingSettings.vue'
import WecomNotificationSettings from '../components/WecomNotificationSettings.vue'
import { features } from '../features'

interface UserItem{id?:number|string;username:string;displayName:string;password?:string;admin:boolean;enabled:boolean;permissions:string[];totpEnabled:boolean;lastLoginAt?:string;createdAt?:string}
interface RegistrySettings{registryServer:string;username:string;password:string;passwordConfigured:boolean}
const menuOptions=computed(()=>[{value:'apps',label:'App 发布'},{value:'dashboard',label:'工作台'},{value:'versions',label:'版本管理'},{value:'runtime',label:'运行总览'},{value:'services',label:'服务管理'},{value:'servers',label:'服务器管理'},{value:'builds',label:'打包记录'},{value:'release',label:'新建发版'},{value:'releases',label:'发版记录'},...(features.miniprograms?[{value:'miniprograms',label:'小程序发布'}]:[])])
const router=useRouter(),loading=ref(false),saving=ref(false),registrySaving=ref(false),dialog=ref(false),users=ref<UserItem[]>([])
const form=reactive<UserItem>({username:'',displayName:'',password:'',admin:false,enabled:true,permissions:[],totpEnabled:false})
const registry=reactive<RegistrySettings>({registryServer:'',username:'',password:'',passwordConfigured:false})
async function load(){loading.value=true;try{const [userResult,registryResult]=await Promise.all([http.get('/users'),http.get('/settings/registry')]);users.value=rows<UserItem>(userResult.data);Object.assign(registry,registryResult.data||{},{password:''})}catch(e){ElMessage.error(errorText(e))}finally{loading.value=false}}
function open(user?:UserItem){Object.assign(form,{id:undefined,username:'',displayName:'',password:'',admin:false,enabled:true,permissions:[],totpEnabled:false},user||{},{password:''});dialog.value=true}
async function save(){if(saving.value)return;if(!form.username||!form.displayName)return ElMessage.warning('请填写用户名和姓名');if(!form.id&&!form.password)return ElMessage.warning('请设置初始密码');saving.value=true;try{const body={username:form.username,displayName:form.displayName,password:form.password,permissions:form.permissions,enabled:form.enabled};if(form.id)await http.put(`/users/${form.id}`,body);else await http.post('/users',body);ElMessage.success(form.id?'用户已更新':'用户已创建');dialog.value=false;await load()}catch(e){ElMessage.error(errorText(e))}finally{saving.value=false}}
async function saveRegistry(){if(registrySaving.value)return;if(!registry.registryServer||!registry.username)return ElMessage.warning('请填写仓库地址和用户名');if(!registry.passwordConfigured&&!registry.password)return ElMessage.warning('请填写登录密码');registrySaving.value=true;try{const result=await http.put('/settings/registry',{registryServer:registry.registryServer,username:registry.username,password:registry.password});Object.assign(registry,result.data||{},{password:''});ElMessage.success('镜像仓库配置已保存')}catch(e){ElMessage.error(errorText(e))}finally{registrySaving.value=false}}
function time(value?:string){return value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'尚未登录'}
onMounted(load)
</script>

<template>
  <div class="page-head"><div><h2>基础配置</h2><p>管理平台用户、镜像仓库、发版通知和登录安全策略。</p></div><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button></div>
  <section class="settings-grid">
    <div class="panel setting-card"><span class="setting-icon"><el-icon><Setting/></el-icon></span><div><b>发版平台</b><p>平台名称及发版基础能力对全部服务生效。</p></div></div>
    <div class="panel setting-card"><span class="setting-icon secure"><el-icon><Lock/></el-icon></span><div><b>Google Authenticator</b><p>所有用户首次登录必须绑定，后续登录需要动态码。</p></div><el-button @click="router.push('/settings/security')">我的安全设置</el-button></div>
  </section>
  <BrandingSettings v-if="authUser?.admin" />
  <section class="panel registry-panel">
    <div class="panel-title"><div><h3>镜像仓库</h3><p>打包推送和服务器拉取镜像前会自动登录，密码加密保存且不会显示在日志中。</p></div><el-tag :type="registry.passwordConfigured?'success':'warning'">{{registry.passwordConfigured?'已配置':'待配置'}}</el-tag></div>
    <el-form label-position="top"><div class="registry-fields"><el-form-item label="仓库地址"><el-input v-model="registry.registryServer" placeholder="registry.example.com"/></el-form-item><el-form-item label="用户名"><el-input v-model="registry.username"/></el-form-item><el-form-item :label="registry.passwordConfigured?'更新密码（留空表示不修改）':'登录密码'"><el-input v-model="registry.password" type="password" show-password autocomplete="new-password"/></el-form-item><el-form-item class="registry-action"><el-button type="primary" :loading="registrySaving" @click="saveRegistry">保存仓库配置</el-button></el-form-item></div></el-form>
  </section>
  <WecomNotificationSettings />
  <section class="panel users-panel">
    <div class="panel-title"><div><h3>用户管理</h3><p>运行总览可单独授权；更新配置并重启需同时拥有运行总览和新建发版权限。发版记录为只读。</p></div><el-button type="primary" :icon="Plus" @click="open()">创建用户</el-button></div>
    <el-table :data="users" v-loading="loading" empty-text="暂无用户">
      <el-table-column label="用户" min-width="170"><template #default="{row}"><b>{{row.displayName}}</b><small class="table-sub">{{row.username}}</small></template></el-table-column>
      <el-table-column label="菜单权限" min-width="310"><template #default="{row}"><div class="permission-tags"><el-tag v-for="permission in row.permissions" :key="permission" size="small" effect="plain">{{menuOptions.find(item=>item.value===permission)?.label||permission}}</el-tag></div></template></el-table-column>
      <el-table-column label="二次认证" width="120"><template #default="{row}"><el-tag :type="row.totpEnabled?'success':'warning'">{{row.totpEnabled?'已绑定':'待绑定'}}</el-tag></template></el-table-column>
      <el-table-column label="状态" width="95"><template #default="{row}"><el-tag :type="row.enabled?'success':'info'">{{row.enabled?'启用':'停用'}}</el-tag></template></el-table-column>
      <el-table-column label="最后登录" width="180"><template #default="{row}">{{time(row.lastLoginAt)}}</template></el-table-column>
      <el-table-column label="操作" width="90"><template #default="{row}"><el-button link type="primary" :icon="Edit" @click="open(row)">编辑</el-button></template></el-table-column>
    </el-table>
  </section>

  <el-dialog v-model="dialog" :title="form.id?'编辑用户':'创建用户'" width="660px" destroy-on-close>
    <el-form label-position="top">
      <div class="two"><el-form-item label="用户名" required><el-input v-model="form.username" :disabled="!!form.id" placeholder="例如 zhangsan"/></el-form-item><el-form-item label="姓名" required><el-input v-model="form.displayName" placeholder="例如 张三"/></el-form-item></div>
      <el-form-item :label="form.id?'重置密码（可选）':'初始密码'" :required="!form.id"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" :placeholder="form.id?'留空表示不修改密码':'至少 6 位'"/></el-form-item>
      <el-form-item label="左侧菜单权限"><el-checkbox-group v-model="form.permissions" class="permission-options"><el-checkbox v-for="option in menuOptions" :key="option.value" :value="option.value" border :disabled="form.admin">{{option.label}}</el-checkbox></el-checkbox-group><div v-if="form.admin" class="field-help">admin 固定拥有全部菜单权限。</div><div class="field-help">版本管理：允许查看版本；创建版本、补充服务和重试仅限 admin。模块按已有服务和 App 权限展示。</div><div class="field-help">App 发布：管理 App 配置、构建、安装包和发布记录；不会授予普通服务权限。</div></el-form-item>
      <el-form-item><el-switch v-model="form.enabled" active-text="允许登录" :disabled="form.admin"/></el-form-item>
    </el-form>
    <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
  </el-dialog>
</template>

<style scoped>.settings-grid{display:grid;grid-template-columns:1fr 1fr;gap:18px;margin-bottom:18px}.setting-card{display:flex;align-items:center;gap:16px}.setting-card>div{flex:1}.setting-card b{color:#182230}.setting-card p,.panel-title p{margin:5px 0 0;color:#667085;font-size:12px}.setting-icon{display:grid;place-items:center;width:44px;height:44px;border-radius:11px;background:#eaf3ff;color:#1677ff;font-size:21px}.setting-icon.secure{background:#e7f7f0;color:#169b63}.registry-panel{margin-bottom:18px}.registry-fields{display:grid;grid-template-columns:1.35fr 1fr 1.2fr auto;gap:16px;align-items:end}.registry-action{padding-bottom:1px}.users-panel .panel-title{align-items:flex-start}.permission-tags{display:flex;gap:5px;flex-wrap:wrap}.table-sub{display:block;margin-top:4px;color:#98a2b3}.two{display:grid;grid-template-columns:1fr 1fr;gap:18px}.permission-options{display:grid;grid-template-columns:repeat(3,1fr);gap:9px;width:100%}.permission-options :deep(.el-checkbox){margin:0}.field-help{margin-top:7px;color:#98a2b3;font-size:12px}@media(max-width:1100px){.registry-fields{grid-template-columns:1fr 1fr}.registry-action{grid-column:1/-1}.settings-grid{grid-template-columns:1fr}}</style>
