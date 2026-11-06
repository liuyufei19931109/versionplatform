<script setup lang="ts">
import { tUi, currentLocale } from '../i18n'

import type { MiniPreparation } from '../miniprograms'
defineProps<{ report: MiniPreparation }>()
const labels = { get OK() { return tUi('已检查') }, get ERROR() { return tUi('待处理') }, get INFO() { return tUi('提示') }, get MANUAL() { return tUi('待联调') } } as const
</script>

<template>
  <div class="mini-preparation">
    <el-alert :title="report.localReady ? tUi('本机准备检查通过') : tUi('还有配置需要处理')" :type="report.localReady ? 'success' : 'warning'" :closable="false" show-icon/>
    <p class="preparation-note">{{ tUi('检查工具、依赖和密钥，不执行构建，不向微信上传。') }}</p>
    <ul>
      <li v-for="check in report.checks" :key="check.code"><div><strong>{{check.label}}</strong><el-tag :type="check.status==='OK'?'success':check.status==='ERROR'?'danger':'info'" effect="plain" size="small">{{labels[check.status]}}</el-tag></div><p>{{check.message}}</p></li>
    </ul>
    <small>{{ tUi('检查时间 {0}。提交任务和开始执行前会再次检查。', new Date(report.checkedAt).toLocaleString(currentLocale(), {hour12:false})) }}</small>
  </div>
</template>

<style scoped>
.preparation-note,.mini-preparation small{color:#72829a;font-size:12px;line-height:1.8}.mini-preparation ul{list-style:none;margin:20px 0;padding:0}.mini-preparation li{padding:16px 0;border-bottom:1px solid #e5ebf4}.mini-preparation li>div{display:flex;gap:12px;justify-content:space-between;align-items:center}.mini-preparation strong{font-size:14px;color:#304968}.mini-preparation p{font-size:13px;line-height:1.8;color:#72829a;margin:8px 0 0}.mini-preparation small{display:block}
</style>
