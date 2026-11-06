const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

// Exercise the production normalizer without a browser, API server, or real platform account.
const source = fs.readFileSync(path.join(__dirname, '../src/api.ts'), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText
const mobileModule = { exports: {} }
vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/mobile.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText, mobileModule)
const buildEngineModule = { exports: {} }
vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/buildEngine.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText, buildEngineModule)
const sandbox = { exports: {}, require: name => name === './auth' ? { clearAuth() {} } : name === './mobile' ? mobileModule.exports : name === './buildEngine' ? buildEngineModule.exports : require(name) }
vm.runInNewContext(compiled, sandbox)
const { normalizeRelease, normalizeTaskLog, stepLabel } = sandbox.exports

test('App task identity and independent delivery mode survive normalization', () => {
  const result = normalizeRelease({ task: { id: 700, serviceType: 'FLUTTER_ANDROID', deliveryType: 'MOBILE', environmentName: 'prod', status: 'WAITING_CONFIRMATION' }, steps: [{ stepType: 'BUILD_AAB' }, { stepType: 'VALIDATE_AAB' }, { stepType: 'ARCHIVE_ARTIFACT' }] })
  assert.equal(result.serviceType, 'FLUTTER_ANDROID')
  assert.equal(result.deliveryType, 'MOBILE')
  assert.equal(result.environment, 'prod')
  assert.equal(result.status, 'WAITING_CONFIRMATION')
  assert.deepEqual(Array.from(result.steps, step => step.name), ['构建 Android AAB', '校验安装包', '归档安装包'])
})

test('APK task labels and immutable build format are preserved independently of current service settings', () => {
  const result = normalizeRelease({ task: { id: 701, serviceType: 'FLUTTER_ANDROID', mobileOutputFormat: 'APK' }, steps: [{ stepType: 'BUILD_APK' }, { stepType: 'VALIDATE_APK' }, { stepType: 'ARCHIVE_ARTIFACT' }] })
  assert.equal(result.mobileOutputFormat, 'APK')
  assert.deepEqual(Array.from(result.steps, step => step.name), ['构建 Android APK', '校验 APK 安装包', '归档安装包'])
  assert.equal(normalizeTaskLog('开始：BUILD_APK\n开始：VALIDATE_APK\n', 'FLUTTER_ANDROID'), '开始：构建 Android APK\n开始：校验 APK 安装包\n')
})

test('legacy App configuration remains AAB while new drafts can explicitly default to APK without fabricated identity', () => {
  const mobileSource = fs.readFileSync(path.join(__dirname, '../src/mobile.ts'), 'utf8')
  const mobileSandbox = { exports: {} }
  vm.runInNewContext(ts.transpileModule(mobileSource, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, mobileSandbox)
  const { mobileBuildDefaults, mobileFormat, isMobileDraft } = mobileSandbox.exports
  assert.equal(mobileFormat({}), 'AAB')
  assert.equal(mobileBuildDefaults().outputFormat, 'AAB')
  const draft = mobileBuildDefaults('APK')
  assert.equal(draft.outputFormat, 'APK')
  assert.equal(draft.flutterVersion, '')
  assert.equal(draft.applicationId, '')
  assert.equal(draft.expectedCertificateSha256, '')
  assert.equal(isMobileDraft({ name: 'App', serviceType: 'FLUTTER_ANDROID', status: false, mobileBuild: draft }), true)
})

test('App form preserves nested build fields and cannot leak App settings into existing project types', () => {
  const { normalizeService, serviceRequest } = sandbox.exports
  const mobileBuild = { flutterVersion: '3.35.7', applicationId: 'com.example.app', expectedCertificateSha256: 'AB:CD '.repeat(16), buildTimeoutSeconds: 600 }
  const app = normalizeService({ name: 'App', serviceKey: 'app', serviceType: 'FLUTTER_ANDROID', configJson: JSON.stringify({ mobileBuild }) })
  const saved = serviceRequest(app)
  const config = JSON.parse(saved.configJson)
  assert.equal(config.mobileBuild.flutterVersion, '3.35.7')
  assert.equal(config.mobileBuild.expectedCertificateSha256, mobileBuild.expectedCertificateSha256)
  assert.deepEqual(config.mobileBuild.environments, {})
  assert.equal(config.javaVersion, undefined)
  assert.equal(JSON.parse(serviceRequest({ ...app, serviceType: 'BACKEND' }).configJson).mobileBuild, undefined)
  assert.equal(JSON.parse(serviceRequest({ ...app, serviceType: 'FRONTEND' }).configJson).mobileBuild, undefined)
})

test('list/dashboard task metadata exposes the real searchable service name', () => {
  const tasks = [{ id: 35, serviceId: 7, serviceName: '前端工作台', serviceType: 'FRONTEND' }, { id: 33, serviceId: 9, serviceName: '示例 API', serviceType: 'BACKEND' }].map(normalizeRelease)
  assert.equal(tasks[0].serviceName, '前端工作台')
  assert.equal(tasks[1].serviceName, '示例 API')
  assert.equal(tasks.filter(task => task.serviceName.includes('工作台'))[0].id, '35')
})

test('detail task metadata takes precedence and gives frontend build steps the correct labels', () => {
  const result = normalizeRelease({ serviceName: 'legacy', serviceType: 'BACKEND', task: { id: 35, serviceId: 7, serviceName: '前端工作台', serviceType: 'FRONTEND' }, steps: [{ stepType: 'BUILD_JAR', stepName: '构建 Spring Boot JAR', logText: '开始：BUILD_JAR\r\n$ npm run build\r\n' }] })
  assert.equal(result.serviceName, '前端工作台')
  assert.equal(result.serviceType, 'FRONTEND')
  assert.equal(result.steps[0].code, 'BUILD_JAR')
  assert.equal(result.steps[0].name, '构建 Node 前端')
  assert.equal(result.steps[0].stepName, '构建 Node 前端')
  assert.equal(result.steps[0].log, '开始：构建 Node 前端\r\n$ npm run build\r\n')
  assert.equal(result.steps[0].logText, result.steps[0].log)
  assert.equal(result.logs, result.steps[0].log)
})

test('legacy root metadata and missing-service fallback remain compatible', () => {
  const legacy = normalizeRelease({ serviceType: 'FRONTEND', serviceName: '旧接口服务名', task: { id: 35, serviceId: 7 }, steps: [{ stepType: 'BUILD_JAR' }] })
  assert.equal(legacy.serviceName, '旧接口服务名')
  assert.equal(legacy.steps[0].name, '构建 Node 前端')
  assert.equal(normalizeRelease({ id: 33, serviceId: 9 }).serviceName, '服务 #9')
})

test('deleted service metadata keeps its explanation without guessing the old build technology', () => {
  const result = normalizeRelease({ id: 35, serviceId: 7, serviceName: '已删除服务 #7', serviceType: null, steps: [{ stepType: 'BUILD_JAR', logText: '开始：BUILD_JAR\n' }] })
  assert.equal(result.serviceName, '已删除服务 #7')
  assert.equal(result.serviceType, undefined)
  assert.equal(result.steps[0].name, '构建应用')
  assert.equal(result.steps[0].log, '开始：构建应用\n')
})

test('only exact legacy platform marker lines change; user commands and source text survive', () => {
  const input = '开始：BUILD_JAR\n$ echo "开始：BUILD_JAR"\n[INFO] 开始：BUILD_JAR\nconst stage = "BUILD_JAR";\n 开始：BUILD_JAR\n开始：BUILD_JAR-extra\n开始：构建 Node 前端\n'
  const expected = input.replace(/^开始：BUILD_JAR\n/, '开始：构建 Node 前端\n')
  assert.equal(normalizeTaskLog(input, 'FRONTEND'), expected)
  assert.equal(normalizeTaskLog('开始：BUILD_JAR\n', 'BACKEND'), '开始：构建 Spring Boot JAR\n')
})

test('aggregate/root/raw log paths use the same frontend conversion', () => {
  for (const field of ['logs', 'logText']) {
    const flat = normalizeRelease({ id: 35, serviceType: 'FRONTEND', [field]: '开始：BUILD_JAR\n' })
    const wrapped = normalizeRelease({ task: { id: 35, serviceType: 'FRONTEND' }, [field]: '开始：BUILD_JAR\n' })
    assert.equal(flat.logs, '开始：构建 Node 前端\n')
    assert.equal(flat.logText, flat.logs)
    assert.equal(wrapped.logs, flat.logs)
  }
})

test('all supported step labels match platform log labels; unknown steps remain readable', () => {
  for (const code of ['GIT_PULL', 'BUILD_JAR', 'BUILD_IMAGE', 'PUSH_IMAGE', 'SSH_CONNECT', 'START_SERVICE', 'HEALTH_CHECK']) {
    assert.equal(normalizeTaskLog(`开始：${code}`, 'FRONTEND'), `开始：${stepLabel(code, 'FRONTEND')}`)
  }
  const unknown = normalizeRelease({ id: 1, steps: [{ code: 'CUSTOM', name: '自定义步骤', log: '开始：CUSTOM' }] })
  assert.equal(unknown.steps[0].name, '自定义步骤')
  assert.equal(unknown.steps[0].log, '开始：CUSTOM')
})
