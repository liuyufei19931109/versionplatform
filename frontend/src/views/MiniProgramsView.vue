<script setup lang="ts">
import { tUi, currentLocale } from '../i18n'

import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { authUser } from '../auth'
import { errorText, http } from '../api'
import { features } from '../features'
import { newRequestId } from '../ui'
import { miniActive, miniStages, miniStatus, type MiniJob, type MiniProject, type MiniPreparation, type MiniHistory } from '../miniprograms'
import MiniProgramPreparation from '../components/MiniProgramPreparation.vue'

const route = useRoute()
const projects = ref<MiniProject[]>([]), loading = ref(true), loadError = ref('')
const configurationOpen = ref(false), saving = ref(false), editingId = ref<number>()
const blank = () => ({ name: '', code: '', appId: '', gitRepo: '', defaultBranch: 'master', projectDirectory: '', robot: 1, runChecks: true, enabled: false })
const form = reactive(blank())
const buildOpen = ref(false), selected = ref<MiniProject>(), reading = ref(false), submitting = ref(false), branchError = ref(''), branches = ref<string[]>([])
const build = reactive({ action: 'PREVIEW' as 'PREVIEW'|'UPLOAD', branch: '', version: '1.0.0', note: '' })
const commit = ref<{ branch: string; commitId: string; title: string; committedAt: string }>(), confirmed = ref(false)
let requestId = '', readGeneration = 0, alive = true
const recordsOpen = ref(false), recordsLoading = ref(false), recordsError = ref(''), recordProject = ref<MiniProject>(), records = ref<MiniJob[]>([])
const recordsPage = ref(1), recordsTotal = ref(0), recordsFilters = reactive({ action: '', status: '' })
const preparationOpen = ref(false), preparing = ref(false), preparationError = ref(''), preparation = ref<MiniPreparation>(), preparationProject = ref<MiniProject>()
const reconcileOpen = ref(false), resolving = ref(false), reconciliation = reactive({ resolution: '' as ''|'CONFIRMED_UPLOADED'|'CONFIRMED_NOT_UPLOADED', note: '' })
let preparationGeneration = 0
let reconciliationJobId: number|undefined
const detailOpen = ref(false), detailLoading = ref(false), detailError = ref(''), detail = ref<MiniJob>(), qrUrl = ref(''), qrError = ref(''), cancelling = ref(false)
let detailId: number|undefined, detailGeneration = 0, recordsGeneration = 0, qrId: number|undefined, timer: ReturnType<typeof setTimeout>|undefined
const canSubmit = computed(() => selected.value?.enabled && !reading.value && !submitting.value && confirmed.value && commit.value?.branch === build.branch
  && !!build.version.trim() && !preparing.value && preparationProject.value?.id === selected.value?.id
  && (build.action === 'PREVIEW' ? preparation.value?.previewReady : features.miniprogramUpload && preparation.value?.uploadReady))
const canCancel = computed(() => detail.value && miniActive(detail.value) && (authUser.value?.admin || authUser.value?.username === detail.value.createdBy))
const time = (value?: string) => value ? new Date(value).toLocaleString(currentLocale(), { hour12: false }) : '—'
function type(job: MiniJob) { return job.status === 'SUCCESS' ? 'success' : job.resolution ? 'info' : ['FAILED','UNKNOWN'].includes(job.status) ? 'danger' : job.status === 'RUNNING' ? 'primary' : 'info' }
async function inspectPreparation(project: MiniProject) {
  const generation = ++preparationGeneration
  preparationProject.value = project; preparation.value = undefined; preparing.value = true; preparationError.value = ''
  try { const {data} = await http.get<MiniPreparation>(`/miniprograms/${project.id}/readiness`, {timeout:20000}); if (alive && generation === preparationGeneration) preparation.value = data }
  catch (error) { if (generation === preparationGeneration) preparationError.value = errorText(error, tUi('准备检查未完成，请重试')) }
  finally { if (generation === preparationGeneration) preparing.value = false }
}
function openPreparation(project: MiniProject) { preparationOpen.value = true; void inspectPreparation(project) }
async function load() {
  loading.value = true; loadError.value = ''
  try { projects.value = (await http.get<MiniProject[]>('/miniprograms')).data }
  catch (e) { loadError.value = errorText(e, tUi('读取小程序失败')) }
  finally { loading.value = false }
}
function configure(project?: MiniProject) { editingId.value = project?.id; Object.assign(form, blank(), project || {}); configurationOpen.value = true }
async function save() {
  if (saving.value) return
  saving.value = true
  try {
    const body = { ...form }; if (editingId.value) await http.put(`/miniprograms/${editingId.value}`, body); else await http.post('/miniprograms', body)
    configurationOpen.value = false; ElMessage.success(tUi('小程序配置已保存')); await load()
  } catch (e) { ElMessage.error(errorText(e)) } finally { saving.value = false }
}
function openBuild(project: MiniProject, action: 'PREVIEW'|'UPLOAD') {
  selected.value = project; Object.assign(build, { action, branch: project.defaultBranch, version: '1.0.0', note: '' })
  commit.value = undefined; confirmed.value = false; requestId = newRequestId(); buildOpen.value = true; branches.value = [project.defaultBranch]
  void http.get<string[]>(`/miniprograms/${project.id}/branches`).then(response => { if (selected.value?.id === project.id && buildOpen.value) branches.value = response.data }).catch(() => {})
  void readCommit()
  void inspectPreparation(project)
}
async function readCommit() {
  const project = selected.value, branch = build.branch, generation = ++readGeneration
  commit.value = undefined; confirmed.value = false; branchError.value = ''; reading.value = true
  if (!project || !branch) { reading.value = false; return }
  try {
    const { data } = await http.get(`/miniprograms/${project.id}/commit`, { params: { branch }, timeout: 90000 })
    if (alive && generation === readGeneration && branch === build.branch) commit.value = data
  } catch (e) { if (generation === readGeneration) branchError.value = errorText(e, tUi('读取提交失败')) }
  finally { if (generation === readGeneration) reading.value = false }
}
watch(() => build.branch, () => { ++readGeneration; commit.value = undefined; confirmed.value = false; reading.value = false }, { flush: 'sync' })
watch(() => [build.branch, build.version, build.note, build.action], () => { if (!submitting.value) requestId = newRequestId() })
async function submit() {
  if (!canSubmit.value || !selected.value || !commit.value) return
  submitting.value = true
  try {
    const { data } = await http.post<MiniJob>('/miniprograms/jobs', { projectId: selected.value.id, action: build.action, branch: build.branch,
      commitId: commit.value.commitId, version: build.version.trim(), note: build.note, requestId })
    buildOpen.value = false; ElMessage.success(tUi('任务已加入队列')); await openDetail(data.id)
  } catch (e) { ElMessage.error(errorText(e)) } finally { submitting.value = false }
}
async function openRecords(project: MiniProject) { records.value = []; recordsPage.value = 1; recordsTotal.value = 0; recordsFilters.action = ''; recordsFilters.status = ''; recordProject.value = project; recordsOpen.value = true; await loadRecords() }
function changeHistory() { records.value = []; recordsPage.value = 1; void loadRecords() }
async function loadRecords() {
  const id = recordProject.value?.id; if (!id) return
  const generation = ++recordsGeneration
  recordsLoading.value = true; recordsError.value = ''
  try {
    const { data } = await http.get<MiniHistory>(`/miniprograms/${id}/history`, {params:{page:recordsPage.value,size:20,
      action:recordsFilters.action||undefined,status:recordsFilters.status==='UNRESOLVED'?undefined:recordsFilters.status||undefined,unresolved:recordsFilters.status==='UNRESOLVED'}})
    if (alive && generation === recordsGeneration) { records.value = data.items; recordsTotal.value = data.total }
  }
  catch (e) { if (generation === recordsGeneration) recordsError.value = errorText(e) } finally { if (generation === recordsGeneration) recordsLoading.value = false }
}
function clearQr() { if (qrUrl.value) URL.revokeObjectURL(qrUrl.value); qrUrl.value = ''; qrId = undefined; qrError.value = '' }
async function openDetail(id: number) { ++detailGeneration; detailId = id; detail.value = undefined; clearQr(); detailOpen.value = true; await loadDetail() }
async function loadDetail() {
  const id = detailId, generation = detailGeneration; if (!id) return
  detailLoading.value = !detail.value; detailError.value = ''
  try {
    const { data } = await http.get<MiniJob>(`/miniprograms/jobs/${id}`)
    if (!alive || generation !== detailGeneration || !detailOpen.value) return
    detail.value = data
    if (data.action === 'PREVIEW' && data.status === 'SUCCESS' && qrId !== id) {
      qrId = id
      try { const image = await http.get(`/miniprograms/jobs/${id}/preview`, { responseType: 'blob' }); if (alive && generation === detailGeneration) qrUrl.value = URL.createObjectURL(image.data) }
      catch (e) { if (generation === detailGeneration) qrError.value = errorText(e, tUi('预览码读取失败，请刷新重试')) }
    }
  } catch (e) { if (generation === detailGeneration) detailError.value = errorText(e) }
  finally { if (generation === detailGeneration) detailLoading.value = false }
}
async function cancel() {
  if (!detail.value || cancelling.value) return
  const id = detail.value.id
  try { await ElMessageBox.confirm(tUi('取消会停止本机后续操作。已开始的微信上传可能完成，需要核对微信后台。'), tUi('取消此任务'), { type: 'warning', confirmButtonText: tUi('确认取消'), cancelButtonText: tUi('继续等待') }) } catch { return }
  cancelling.value = true
  try { await http.post(`/miniprograms/jobs/${id}/cancel`); ElMessage.success(tUi('取消请求已提交')); if (detailId === id) await loadDetail() }
  catch (e) { ElMessage.error(errorText(e)) } finally { cancelling.value = false }
}
watch(detailOpen, value => { if (!value) { ++detailGeneration; clearQr() } })
function openReconciliation() { reconciliationJobId = detail.value?.id; reconciliation.resolution = ''; reconciliation.note = ''; reconcileOpen.value = true }
async function saveReconciliation() {
  const id = reconciliationJobId, generation = detailGeneration
  if (!id || detail.value?.id !== id || resolving.value || !reconciliation.resolution || !reconciliation.note.trim()) return
  resolving.value = true
  try {
    const {data} = await http.post<MiniJob>(`/miniprograms/jobs/${id}/reconciliation`, {...reconciliation,note:reconciliation.note.trim()})
    if (generation === detailGeneration) detail.value = data
    reconcileOpen.value = false; ElMessage.success(tUi('核对结果已登记，没有重新上传'))
    if (recordsOpen.value) void loadRecords()
  } catch (error) { ElMessage.error(errorText(error)); if (generation === detailGeneration) void loadDetail() }
  finally { resolving.value = false }
}
watch(() => route.query.jobId, value => { const id = Number(value); if (Number.isSafeInteger(id) && id > 0) void openDetail(id) })
async function poll() {
  if (!alive) return
  if (detailOpen.value && detail.value && miniActive(detail.value)) await loadDetail()
  if (recordsOpen.value && records.value.some(miniActive)) await loadRecords()
  if (alive) timer = setTimeout(poll, 3000)
}
onMounted(async () => { await load(); const id = Number(route.query.jobId); if (Number.isSafeInteger(id) && id > 0) await openDetail(id); void poll() })
onUnmounted(() => { alive = false; ++readGeneration; ++detailGeneration; ++preparationGeneration; ++recordsGeneration; if (timer) clearTimeout(timer); clearQr() })
</script>

<template>
  <div class="page-head"><div><h2>{{ tUi('小程序发布') }}</h2><p>{{ tUi('核对代码、扫码预览，将确认的版本上传到微信。') }}</p></div><el-button v-if="authUser?.admin" type="primary" :icon="Plus" @click="configure()">{{ tUi('登记小程序') }}</el-button></div>
  <section class="panel mini-workspace" :aria-label="tUi('小程序项目')">
    <div class="mini-toolbar"><span>{{ tUi('上传后的提审与正式发布，在微信公众平台完成。') }}</span><el-button text :icon="Refresh" :loading="loading" @click="load">{{ tUi('刷新') }}</el-button></div>
    <div v-if="loading" class="mini-loading"><el-skeleton :rows="3" animated/><p>{{ tUi('正在读取小程序…') }}</p></div>
    <el-alert v-else-if="loadError" :title="loadError" type="error" :closable="false" show-icon/>
    <div v-else-if="!projects.length" class="mini-empty"><h3>{{ tUi('还没有登记小程序') }}</h3><p>{{ tUi('准备好仓库和 AppID，先保存配置，再启用预览。') }}</p><el-button v-if="authUser?.admin" @click="configure()">{{ tUi('登记第一个小程序') }}</el-button></div>
    <article v-for="project in loading || loadError ? [] : projects" :key="project.id" class="mini-project">
      <div class="mini-project-head"><div><h3>{{ project.name }}</h3><span>{{ project.appId }}</span></div><el-tag :type="project.enabled ? 'success' : 'info'" effect="plain">{{ project.enabled ? tUi('已启用') : tUi('待配置') }}</el-tag></div>
      <p class="mini-repo">{{ project.gitRepo }}</p><p class="mini-branch">{{ tUi('默认分支') }} <strong>{{ project.defaultBranch }}</strong><span>{{ tUi('基准分支') }} <strong>master</strong></span></p>
      <div class="mini-actions"><el-button @click="openRecords(project)">{{ tUi('版本记录') }}</el-button><el-button @click="openPreparation(project)">{{ tUi('准备检查') }}</el-button><el-button v-if="authUser?.admin" @click="configure(project)">{{ tUi('配置') }}</el-button><el-button :disabled="!project.enabled" @click="openBuild(project, 'PREVIEW')">{{ tUi('生成预览码') }}</el-button><el-button type="primary" :disabled="!project.enabled || !features.miniprogramUpload" @click="openBuild(project, 'UPLOAD')">{{ tUi('上传微信') }}</el-button></div>
    </article>
    <p v-if="!features.miniprogramUpload" class="mini-note">{{ tUi('微信上传尚未开放，当前可先配置项目和生成预览码。') }}</p>
  </section>

  <el-drawer v-model="preparationOpen" :title="tUi('{0} · 准备检查', preparationProject?.name || '')" size="min(100%, 600px)">
    <el-skeleton v-if="preparing" :rows="6" animated/>
    <el-alert v-else-if="preparationError" :title="preparationError" type="error" :closable="false"/>
    <MiniProgramPreparation v-else-if="preparation" :report="preparation"/>
    <div class="mini-actions"><el-button :loading="preparing" @click="preparationProject && inspectPreparation(preparationProject)">{{ tUi('重新检查') }}</el-button></div>
  </el-drawer>

  <el-dialog v-model="configurationOpen" :title="editingId ? tUi('小程序配置') : tUi('登记小程序')" width="680px" :close-on-click-modal="false">
    <el-form label-position="top" :disabled="saving" class="mini-form">
      <el-form-item :label="tUi('小程序名称')" required><el-input v-model="form.name" maxlength="100" :placeholder="tUi('用于平台展示的名称')"/></el-form-item>
      <div class="mini-columns"><el-form-item :label="tUi('项目标识')" required><el-input v-model="form.code" maxlength="80" :placeholder="tUi('例如 demo-miniapp')"/></el-form-item><el-form-item label="AppID" required><el-input v-model="form.appId" :disabled="!!editingId" maxlength="18"/></el-form-item></div>
      <el-form-item :label="tUi('Git 仓库地址')" required><el-input v-model="form.gitRepo" placeholder="git@github.com:team/project.git"/></el-form-item>
      <div class="mini-columns"><el-form-item :label="tUi('默认分支')" required><el-input v-model="form.defaultBranch"/></el-form-item><el-form-item :label="tUi('项目目录')"><el-input v-model="form.projectDirectory" :placeholder="tUi('留空表示仓库根目录')"/></el-form-item></div>
      <el-form-item :label="tUi('微信上传机器人')"><el-input-number v-model="form.robot" :min="1" :max="30"/><span class="mini-help">{{ tUi('固定编号，便于在微信后台找到平台上传的版本。') }}</span></el-form-item>
      <el-form-item><el-checkbox v-model="form.runChecks">{{ tUi('构建前运行项目检查与测试') }}</el-checkbox><p class="mini-help">{{ tUi('适用于提供 scripts/check.js 和 scripts/test 的原生项目。') }}</p></el-form-item>
      <el-form-item :label="tUi('启用项目')"><el-switch v-model="form.enabled"/><span class="mini-help">{{ tUi('密钥由管理员保存在构建机上，页面不填写密钥。') }}</span></el-form-item>
    </el-form><template #footer><el-button :disabled="saving" @click="configurationOpen=false">{{ tUi('取消') }}</el-button><el-button type="primary" :loading="saving" @click="save">{{ tUi('保存配置') }}</el-button></template>
  </el-dialog>

  <el-dialog v-model="buildOpen" :title="build.action === 'PREVIEW' ? tUi('生成预览码') : tUi('上传微信开发版本')" width="700px" :close-on-click-modal="false" :close-on-press-escape="!submitting" :show-close="!submitting">
    <h3 class="mini-dialog-name">{{ selected?.name }}</h3><p class="mini-note">{{ tUi('{0} · 基准分支 master', selected?.appId) }}</p>
    <el-alert :title="build.action === 'UPLOAD' ? tUi('本次上传到微信开发版本，之后仍需提审和发布。') : tUi('预览码供有权限的微信账号扫码，不会替换正式版本。')" type="info" :closable="false"/>
    <div class="mini-build-preparation" aria-live="polite"><span v-if="preparing">{{ tUi('正在检查构建准备…') }}</span><template v-else><span>{{preparationError || (!preparation?.localReady ? tUi('构建准备尚未完成，请先处理检查项。') : build.action==='UPLOAD' && !preparation?.uploadReady ? tUi('上传条件尚未满足，请查看准备检查。') : tUi('本机准备检查通过；微信侧权限仍需联调验证。'))}}</span><el-button text type="primary" @click="selected && openPreparation(selected)">{{ tUi('查看准备检查') }}</el-button></template></div>
    <el-form label-position="top" :disabled="submitting" class="mini-form">
      <el-form-item :label="tUi('代码分支')" required><el-select v-model="build.branch" filterable allow-create @change="readCommit"><el-option v-for="branch in branches" :key="branch" :label="branch" :value="branch"/></el-select></el-form-item>
      <div class="mini-commit" aria-live="polite"><span v-if="reading">{{ tUi('正在读取最新提交…') }}</span><template v-else-if="branchError"><el-alert :title="branchError" type="error" :closable="false"/><el-button text @click="readCommit">{{ tUi('重新读取') }}</el-button></template><template v-else-if="commit"><strong>{{ commit.title }}</strong><p><code>{{ commit.commitId.slice(0, 8) }}</code> · {{ time(commit.committedAt) }}</p><el-checkbox v-model="confirmed">{{ tUi('确认使用此 Commit') }}</el-checkbox></template></div>
      <el-form-item :label="tUi('版本号')" required><el-input v-model="build.version" maxlength="64" :placeholder="tUi('例如 1.0.0')"/></el-form-item><el-form-item :label="tUi('版本说明')"><el-input v-model="build.note" type="textarea" :rows="2" maxlength="500" show-word-limit/></el-form-item>
      <p class="mini-help">{{ tUi('使用所选 Commit 中的项目配置。执行前会重新检查是否包含最新 master。') }}</p>
    </el-form><template #footer><el-button :disabled="submitting" @click="buildOpen=false">{{ tUi('取消') }}</el-button><el-button type="primary" :disabled="!canSubmit" :loading="submitting" @click="submit">{{ build.action === 'PREVIEW' ? tUi('确认生成预览码') : tUi('确认上传微信') }}</el-button></template>
  </el-dialog>

  <el-drawer v-model="recordsOpen" :title="tUi('{0} · 版本记录', recordProject?.name || '')" size="min(100%, 1000px)">
    <div class="mini-toolbar"><span>{{ tUi('共 {0} 条记录 · 按提交时间倒序', recordsTotal) }}</span><el-button text :icon="Refresh" :loading="recordsLoading" @click="loadRecords">{{ tUi('刷新') }}</el-button></div>
    <div class="mini-history-filters"><el-radio-group v-model="recordsFilters.action" :aria-label="tUi('操作类型')" @change="changeHistory"><el-radio-button value="">{{ tUi('全部操作') }}</el-radio-button><el-radio-button value="PREVIEW">{{ tUi('预览') }}</el-radio-button><el-radio-button value="UPLOAD">{{ tUi('上传') }}</el-radio-button></el-radio-group><el-select v-model="recordsFilters.status" :aria-label="tUi('记录状态')" @change="changeHistory"><el-option :label="tUi('全部状态')" value=""/><el-option :label="tUi('等待核对')" value="UNRESOLVED"/><el-option :label="tUi('成功')" value="SUCCESS"/><el-option :label="tUi('失败')" value="FAILED"/><el-option :label="tUi('排队中')" value="PENDING"/><el-option :label="tUi('执行中')" value="RUNNING"/><el-option :label="tUi('已取消')" value="CANCELLED"/><el-option :label="tUi('结果不确定（含已核对）')" value="UNKNOWN"/></el-select></div>
    <el-skeleton v-if="recordsLoading && !records.length" :rows="4" animated/>
    <el-alert v-else-if="recordsError" :title="recordsError" type="error" :closable="false"/>
    <el-table v-else v-loading="recordsLoading" :data="records" :empty-text="tUi('没有符合条件的记录')"><el-table-column :label="tUi('版本 / 时间')" min-width="175"><template #default="{row}"><strong>{{row.version}}</strong><small>{{time(row.createdAt)}}</small></template></el-table-column><el-table-column :label="tUi('分支 / Commit')" min-width="180"><template #default="{row}"><b>{{row.branch}}</b><small>{{row.commitId.slice(0,8)}}</small></template></el-table-column><el-table-column :label="tUi('操作')" width="85"><template #default="{row}">{{row.action==='PREVIEW'?tUi('预览'):tUi('上传')}}</template></el-table-column><el-table-column :label="tUi('状态')" min-width="170"><template #default="{row}"><el-tag :type="type(row)">{{miniStatus(row)}}</el-tag></template></el-table-column><el-table-column prop="createdBy" :label="tUi('发起人')" width="90"/><el-table-column width="70"><template #default="{row}"><el-button text type="primary" @click="openDetail(row.id)">{{ tUi('详情') }}</el-button></template></el-table-column></el-table>
    <el-pagination v-if="recordsTotal>20 && !recordsError" v-model:current-page="recordsPage" :disabled="recordsLoading" :page-size="20" :total="recordsTotal" layout="prev, pager, next" class="mini-pagination" @current-change="loadRecords"/>
  </el-drawer>
  <el-drawer v-model="detailOpen" :title="tUi('小程序任务详情')" size="min(100%, 740px)">
    <el-skeleton v-if="detailLoading" :rows="5" animated/>
    <el-alert v-else-if="detailError" :title="detailError" type="error" :closable="false"/>
    <template v-else-if="detail"><h2 class="mini-dialog-name">{{detail.projectName}}</h2><div class="mini-detail-version"><b>{{detail.version}}</b><el-tag :type="type(detail)">{{miniStatus(detail)}}</el-tag></div>
      <dl class="mini-facts"><dt>{{ tUi('代码分支') }}</dt><dd>{{detail.branch}}</dd><dt>{{ tUi('锁定 Commit') }}</dt><dd><code>{{detail.commitId}}</code></dd><dt>{{ tUi('基准 master') }}</dt><dd>{{detail.baselineCommit?.slice(0,8)||tUi('等待校验')}}</dd><dt>{{ tUi('发起人') }}</dt><dd>{{detail.createdBy}}</dd><dt>{{ tUi('当前步骤') }}</dt><dd>{{miniStages[detail.stage]||detail.stage}}</dd><dt>{{ tUi('创建时间') }}</dt><dd>{{time(detail.createdAt)}}</dd><dt>{{ tUi('版本说明') }}</dt><dd>{{detail.note||tUi('未填写')}}</dd></dl>
      <el-alert v-if="detail.errorMessage" :title="(detail.resolution?tUi('原始执行结果：'):'')+detail.errorMessage" :type="detail.status==='UNKNOWN'?'warning':'error'" :closable="false" show-icon/>
      <section v-if="detail.resolution" class="mini-reconciliation"><h3>{{ tUi('管理员核对记录') }}</h3><p><strong>{{detail.resolution==='CONFIRMED_UPLOADED'?tUi('微信后台已找到此版本'):tUi('微信后台未找到此版本')}}</strong></p><p>{{detail.resolutionNote}}</p><small>{{detail.resolvedBy}} · {{time(detail.resolvedAt)}}</small><small>{{ tUi('这是人工核对结果，原始执行状态与日志保留；此次操作没有上传或发布代码。') }}</small></section>
      <div v-else-if="detail.status==='UNKNOWN' && detail.action==='UPLOAD'" class="mini-reconciliation"><p>{{ tUi('请先到微信后台核对 AppID、版本号、上传时间和版本说明，核对前暂停该项目的新上传。') }}</p><el-button v-if="authUser?.admin" type="primary" plain @click="openReconciliation">{{ tUi('登记核对结果') }}</el-button><small v-else>{{ tUi('请联系管理员登记核对结果。') }}</small></div>
      <el-alert v-if="detail.status==='SUCCESS' && detail.action==='UPLOAD'" :title="tUi('代码已上传微信开发版本，请到微信公众平台设置体验版或提交审核。当前记录不代表正式上线。')" type="success" :closable="false"/>
      <div v-if="qrUrl" class="mini-qr"><img :src="qrUrl" :alt="tUi('小程序预览二维码')"/><p>{{ tUi('使用有权限的微信扫码；失效后请重新生成。') }}</p></div><el-alert v-if="qrError" :title="qrError" type="error" :closable="false"/>
      <pre class="mini-log" :aria-label="tUi('执行日志')">{{detail.logText||tUi('正在等待执行…')}}</pre>
    </template><div class="mini-actions"><el-button :icon="Refresh" @click="clearQr(); loadDetail()">{{ tUi('刷新状态') }}</el-button><el-button v-if="canCancel" type="danger" plain :loading="cancelling" @click="cancel">{{ tUi('取消任务') }}</el-button></div>
  </el-drawer>
  <el-dialog v-model="reconcileOpen" :title="tUi('登记微信核对结果')" width="min(92vw, 560px)" :close-on-click-modal="false" :close-on-press-escape="!resolving" :show-close="!resolving">
    <p class="mini-note">{{ tUi('仅记录微信后台的实际情况，不会重新上传，也不会变更原始执行状态。保存后核对记录不可覆盖。') }}</p>
    <el-form label-position="top" :disabled="resolving"><el-form-item :label="tUi('核对结果')" required><el-radio-group v-model="reconciliation.resolution"><el-radio value="CONFIRMED_UPLOADED">{{ tUi('微信后台已找到此版本') }}</el-radio><el-radio value="CONFIRMED_NOT_UPLOADED">{{ tUi('微信后台未找到此版本') }}</el-radio></el-radio-group></el-form-item><el-form-item :label="tUi('核对说明')" required><el-input v-model="reconciliation.note" type="textarea" :rows="3" maxlength="500" show-word-limit :placeholder="tUi('记录核对到的版本号、上传时间和判断依据')"/></el-form-item></el-form>
    <template #footer><el-button :disabled="resolving" @click="reconcileOpen=false">{{ tUi('取消') }}</el-button><el-button type="primary" :loading="resolving" :disabled="!reconciliation.resolution || !reconciliation.note.trim()" @click="saveReconciliation">{{ tUi('保存核对结果') }}</el-button></template>
  </el-dialog>
</template>

<style scoped>
.mini-build-preparation{display:flex;align-items:center;flex-wrap:wrap;justify-content:space-between;gap:8px;margin-top:12px;padding:10px 12px;background:#f4f7fc;color:#72829a;font-size:12px;line-height:1.8}.mini-history-filters{display:flex;flex-wrap:wrap;gap:12px;margin:20px 0}.mini-history-filters .el-select{width:215px}.mini-pagination{justify-content:flex-end;margin-top:20px}.mini-reconciliation{background:#f5f8fc;border:1px solid #e3eaf4;border-radius:8px;padding:16px;margin:18px 0;font-size:13px;line-height:1.8;color:#60748e}.mini-reconciliation h3{margin:0;color:#304968;font-size:15px}.mini-reconciliation p{white-space:pre-wrap;overflow-wrap:anywhere;margin:8px 0}
.mini-workspace{padding:24px 28px}.mini-toolbar{display:flex;justify-content:space-between;align-items:center;gap:16px;color:#72829a;font-size:13px}.mini-project{padding:22px 0;border-top:1px solid #e4ebf5}.mini-project-head{display:flex;justify-content:space-between;align-items:center;gap:16px}.mini-project h3,.mini-dialog-name{margin:0 0 8px;font-size:23px;font-weight:750;color:#253b59}.mini-project-head span,.mini-note,.mini-help{font-size:12px;color:#72829a;line-height:1.8}.mini-repo{font-size:13px;color:#60748e;overflow-wrap:anywhere;margin:12px 0}.mini-branch{font-size:13px;color:#72829a;display:flex;flex-wrap:wrap;gap:6px 12px}.mini-branch strong{color:#304968}.mini-branch span{margin-left:18px}.mini-actions{display:flex;flex-wrap:wrap;gap:8px;justify-content:flex-end;margin-top:18px}.mini-actions .el-button+.el-button{margin-left:0}.mini-loading,.mini-empty{padding:32px 0}.mini-empty{text-align:center}.mini-empty p,.mini-loading p{color:#72829a}.mini-form{margin-top:20px}.mini-columns{display:grid;grid-template-columns:1fr 1fr;gap:18px}.mini-form .el-select{width:100%}.mini-help{margin:0 0 0 12px}.mini-commit{padding:15px 18px;margin-bottom:22px;background:#f4f7fc;border-left:3px solid #4668d8;min-height:70px;overflow-wrap:anywhere}.mini-commit p{font-size:12px;color:#72829a;margin:8px 0}.mini-detail-version{display:flex;align-items:center;gap:16px;margin:20px 0}.mini-detail-version>b{font-size:24px;color:#253b59}.mini-facts{display:grid;grid-template-columns:105px minmax(0,1fr);gap:14px;font-size:13px;line-height:1.7}.mini-facts dt{color:#72829a}.mini-facts dd{margin:0;overflow-wrap:anywhere}.mini-log{background:#f4f7fc;padding:18px;white-space:pre-wrap;word-break:break-word;line-height:1.9;font-size:12px;max-height:340px;overflow:auto;margin-top:24px}.mini-qr{text-align:center;margin-top:22px}.mini-qr img{width:240px;max-width:100%}.mini-qr p{color:#72829a;font-size:12px}small{display:block;color:#72829a;font-size:12px;margin-top:6px}@media(max-width:640px){.mini-columns{grid-template-columns:1fr;gap:0}.mini-workspace{padding:18px}.mini-actions{justify-content:flex-start}.mini-branch span{margin-left:0}.mini-toolbar{align-items:flex-start}.mini-help{display:block;margin-left:0}.mini-project h3{font-size:21px}}
</style>
