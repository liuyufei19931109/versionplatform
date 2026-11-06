import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = await readFile(new URL('../src/mobile.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
const { mobileBuildDefaults, mobileEnvironmentDefaults, mobileBuildError, mobileEnvironmentError, mobileArtifactEnvironmentError, normalizedMobileBuild, mobileParameterError, mobileParameterGroupError } = await import(`data:text/javascript;base64,${Buffer.from(compiled.replaceAll("'./i18n'", JSON.stringify(new URL('../test-support/i18n.cjs', import.meta.url).href))).toString('base64')}`)
const profile = (name) => ({ ...mobileEnvironmentDefaults(name), enabled: true, applicationId: `com.example.${name}`, expectedCertificateSha256: (name === 'prod' ? 'b' : 'a').repeat(64), signingProfile: name === 'prod' ? 'formal' : 'test', buildMode: name === 'prod' ? 'RELEASE' : 'DEBUG', allowDebugSigning: name !== 'prod' })
const config = () => ({ ...mobileBuildDefaults('APK'), flutterVersion: '3.47.3', environments: { test: profile('test'), prod: profile('prod') } })

test('legacy global signing stays preserved and does not enable production or any environment', () => {
  const legacy = { ...mobileBuildDefaults('APK'), flutterVersion: '3.47.3', applicationId: 'com.example.old', expectedCertificateSha256: 'AA:'.repeat(31) + 'AA', signingProfile: 'legacy' }
  assert.match(mobileBuildError(legacy), /至少完成并启用一个/)
  const normalized = normalizedMobileBuild(legacy)
  assert.equal(normalized.expectedCertificateSha256, legacy.expectedCertificateSha256)
  assert.equal(normalized.applicationId, legacy.applicationId)
  assert.deepEqual(normalized.environments, {})
})
test('PRE Debug APK and production Release with distinct identities can coexist', () => {
  const value = config()
  assert.equal(mobileBuildError(value), '')
  value.environments.dev = mobileEnvironmentDefaults('dev')
  assert.equal(mobileBuildError(value), '')
  value.environments.test.allowDebugSigning = false
  assert.match(mobileBuildError(value), /明确允许/)
})
test('production signing and release modes cannot reuse test identity or debug permission', () => {
  for (const field of ['applicationId', 'expectedCertificateSha256', 'signingProfile']) {
    const value = config(); value.environments.prod[field] = value.environments.test[field]
    assert.match(mobileBuildError(value), /必须使用不同/)
  }
  const value = config(); value.environments.prod.allowDebugSigning = true
  assert.match(mobileBuildError(value), /生产环境必须/)
  const releaseTest = config(); releaseTest.environments.test.buildMode = 'RELEASE'; assert.match(mobileBuildError(releaseTest), /Release 构建不允许/)
})
test('nullable old artifact environment remains readable but cannot be reused for a new release', () => {
  assert.match(mobileArtifactEnvironmentError({}), /历史安装包/)
  assert.match(mobileArtifactEnvironmentError({ buildEnvironmentName: 'test', buildMode: 'DEBUG' }, 'prod'), /必须与/)
  assert.match(mobileArtifactEnvironmentError({ buildEnvironmentName: 'prod', buildMode: 'DEBUG' }, 'prod'), /Release/)
  assert.equal(mobileArtifactEnvironmentError({ buildEnvironmentName: 'test', buildMode: 'DEBUG' }, 'test'), '')
  assert.equal(mobileArtifactEnvironmentError({ buildEnvironmentName: 'prod', buildMode: 'RELEASE' }, 'prod'), '')
})
test('a new mobile project needs no prescribed parameters in any environment', () => {
  const value = config()
  assert.equal(mobileBuildError(value), '')
  value.environments.test.dartDefines['projectMode'] = 'test'
  value.environments.prod.environmentVariables.APP_BUILD_PROFILE = 'release'
  assert.equal(mobileBuildError(value), '')
})

test('Dart values are project-defined text, including empty strings and spaces', () => {
  const value = config()
  value.environments.test.dartDefines = { FEATURE_ENABLED: 'false', APP_BASE_URL: 'project-defined', OPTIONAL: '' }
  value.environments.prod.dartDefines = { FEATURE_ENABLED: 'true' }
  value.environments.test.environmentVariables = { APP_BUILD_LABEL: ' two words ' }
  const normalized = normalizedMobileBuild(value)
  assert.equal(mobileBuildError(normalized), '')
  assert.deepEqual(normalized.environments.test.dartDefines, value.environments.test.dartDefines)
  assert.equal(normalized.environments.test.environmentVariables.APP_BUILD_LABEL, ' two words ')
  normalized.environments.test.dartDefines.FEATURE_ENABLED = 'changed'
  assert.equal(value.environments.test.dartDefines.FEATURE_ENABLED, 'false')
  assert.equal(normalized.environments.prod.dartDefines.FEATURE_ENABLED, 'true')
})

test('variable names cannot override the host toolchain or the signing process', () => {
  for (const key of ['PATH', 'JAVA_TOOL_OPTIONS', 'RELEASE_SIGNING_PROPERTIES', 'APP_BUILD_', 'APP_BUILD_lowercase']) {
    const value = config(); value.environments.test.environmentVariables = { [key]: 'test' }
    assert.match(mobileEnvironmentError(value, 'test'), /APP_BUILD_/)
  }
  for (const part of ['password', 'passwd', 'secret', 'token', 'private_key', 'api_key', 'signing', 'keystore', 'credential']) {
    assert.match(mobileParameterError('dartDefines', 'App_' + part, 'example'), /密码/)
    assert.match(mobileParameterError('environmentVariables', 'APP_BUILD_' + part.toUpperCase(), 'example'), /密码/)
  }
})

test('both parameter groups enforce key, text and count limits independently', () => {
  const value = config()
  for (const group of ['dartDefines', 'environmentVariables']) {
    const map = Object.fromEntries(Array.from({ length: 40 }, (_, i) => ['APP_BUILD_K' + i, '']))
    value.environments.test[group] = map
    assert.equal(mobileEnvironmentError(value, 'test'), '')
    assert.match(mobileParameterGroupError(group, { ...map, APP_BUILD_EXTRA: 'v' }), /40/)
    assert.equal(mobileParameterError(group, 'APP_BUILD_X', 'x'.repeat(2048)), '')
    assert.match(mobileParameterError(group, 'APP_BUILD_X', 'x'.repeat(2049)), /2048/)
    assert.match(mobileParameterError(group, 'APP_BUILD_X', 'a\nb'), /控制字符/)
    assert.match(mobileParameterError(group, 'APP_BUILD_' + 'X'.repeat(71), 'v'), /80/)
  }
  assert.match(mobileParameterError('dartDefines', '1_INVALID', 'v'), /字母/)
})
