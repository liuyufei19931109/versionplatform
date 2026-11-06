<script setup lang="ts">
import type { Artifact } from '../types'
import CommitHash from './CommitHash.vue'
import { environmentLabel, timeParts } from '../ui'
import { mobileArtifactEnvironmentError, mobileVersionLabel } from '../mobile'
defineProps<{ rows: Record<string, unknown>[]; showEnvironment?: boolean; showDelivery?: boolean; enabled: boolean; emptyText: string }>()
const emit = defineEmits<{ deliver: [artifact: Record<string, unknown>] }>()
function blocked(row: Record<string, unknown>) { return mobileArtifactEnvironmentError(row as unknown as Artifact) }
</script>

<template>
  <p class="app-artifact-phone-hint">左右滑动查看分支、打包时间等信息</p>
  <el-table :data="rows" class="app-artifact-table" :empty-text="emptyText" row-key="id">
    <el-table-column label="安装包版本" min-width="180"><template #default="{row}"><b class="app-artifact-version">{{mobileVersionLabel(row)}}</b><span class="app-artifact-detail">{{row.buildMode==='DEBUG'?'Debug':row.buildMode==='RELEASE'?'Release':'未记录模式'}} · {{row.format==='APK'?'完整 APK':row.format||'安装包'}}</span></template></el-table-column>
    <el-table-column label="打包完成" width="155"><template #default="{row}"><div class="app-artifact-time" :title="timeParts(row.createdAt).full"><b>{{timeParts(row.createdAt).time}}</b><span>{{timeParts(row.createdAt).date}}</span><small v-if="row.buildTaskId">构建 #{{row.buildTaskId}}</small></div></template></el-table-column>
    <el-table-column label="分支 / Commit" min-width="205"><template #default="{row}"><b class="app-artifact-branch" :title="row.branch">{{row.branch||'未记录分支'}}</b><CommitHash :value="row.commitId"/></template></el-table-column>
    <el-table-column v-if="showEnvironment" label="构建环境" width="135"><template #default="{row}"><el-tag v-if="row.buildEnvironmentName" size="small" effect="plain">{{environmentLabel(row.buildEnvironmentName)}}</el-tag><span v-else>环境未记录</span><small v-if="row._detailsUnavailable" class="app-artifact-detail">详情暂未读取</small></template></el-table-column>
    <el-table-column label="打包人" width="105"><template #default="{row}">{{row.createdBy||'—'}}</template></el-table-column>
    <el-table-column v-if="showDelivery" label="交付" width="115" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="!enabled||!!blocked(row)" :title="blocked(row)" @click="emit('deliver',row)">创建交付</el-button></template></el-table-column>
  </el-table>
</template>

<style scoped>
.app-artifact-phone-hint{display:none}@media(max-width:640px){.app-artifact-phone-hint{display:block;color:#64748b;font-size:11px;line-height:1.6;margin:0 0 10px}}

.app-artifact-table{width:100%}.app-artifact-table :deep(.el-table__cell){padding:12px 0}.app-artifact-time{display:grid;gap:3px;line-height:1.5;font-variant-numeric:tabular-nums}.app-artifact-time b{font-size:16px;font-weight:700;color:#354b68}.app-artifact-time span{font-size:12px;color:#64748b}.app-artifact-time small{font-size:11px;color:#74849a}.app-artifact-branch{display:block;color:#354b68;font-weight:700;font-size:13px;overflow-wrap:anywhere;margin-bottom:5px}.app-artifact-version{display:block;color:#263a56;font-size:16px;font-weight:700;line-height:1.5}.app-artifact-detail{display:block;margin-top:5px;font-size:12px;line-height:1.6;color:#64748b}
</style>
