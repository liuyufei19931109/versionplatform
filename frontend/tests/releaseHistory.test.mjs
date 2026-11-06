import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

// Execute the production pure helpers without loading the application's auth or HTTP modules.
const source = await readFile(new URL('../src/releaseHistory.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
const { initialEnvironment, rememberEnvironment, historyEnvironmentKey, validServiceId, filterHistory, historyVersions, clampHistoryPage } = await import(`data:text/javascript;base64,${Buffer.from(compiled.replaceAll("'./i18n'", JSON.stringify(new URL('../test-support/i18n.cjs', import.meta.url).href))).toString('base64')}`)

function entry(id, serviceId, environment, artifactId, status = 'SUCCESS', artifactVersion = 'release-20260915') {
  return { task: { id, serviceId, environment, artifactId, status, branch: 'release20260915', commitId: 'aabbccdd', serviceName: '前端服务', serviceKey: 'web-app' }, artifactVersion, buildTaskId: artifactId == null ? null : artifactId + 100, imageReference: null, targetNodeCount: 1, targetScopeKnown: true }
}
const records = [entry(10, 35, 'test', 100), entry(9, 35, 'test', 101, 'FAILED'), entry(8, 35, 'test', 100), entry(7, 35, 'dev', 102), entry(6, 33, 'test', 103), entry(5, 35, 'test', null, 'FAILED', null)]
const empty = { serviceId: '', artifactId: '', status: '', keyword: '' }

test('environment query takes priority; invalid and unavailable preferences fall back safely', () => {
  assert.equal(initialEnvironment('prod', { getItem: () => 'dev' }), 'prod')
  assert.equal(initialEnvironment('untrusted', { getItem: () => 'dev' }), 'dev')
  assert.equal(initialEnvironment(['prod'], { getItem: () => 'bogus' }), 'test')
  assert.equal(initialEnvironment(undefined, { getItem: () => { throw new Error('disabled') } }), 'test')
  const stored = []
  rememberEnvironment('dev', { setItem: (...value) => stored.push(value) })
  assert.deepEqual(stored, [[historyEnvironmentKey, 'dev']])
  assert.doesNotThrow(() => rememberEnvironment('test', { setItem: () => { throw new Error('quota') } }))
  assert.equal(validServiceId('35'), '35')
  for (const value of [['35'], '0', '-2', '3abc', '1.5', null]) assert.equal(validServiceId(value), '')
})

test('version options are scoped to service and environment, deduplicated by artifact identity and include failed attempts', () => {
  assert.deepEqual(historyVersions(records, 'test', ''), [])
  assert.deepEqual(historyVersions(records, 'test', '35').map(item => item.id), ['100', '101'])
  assert.deepEqual(historyVersions(records, 'dev', '35').map(item => item.id), ['102'])
  assert.equal(historyVersions(records, 'test', '35')[0].label, historyVersions(records, 'test', '35')[1].label)
})

test('same branch, version label and commit never merge distinct build artifacts', () => {
  assert.deepEqual(filterHistory(records, 'test', { ...empty, serviceId: '35', artifactId: '101' }).map(item => item.task.id), [9])
  assert.deepEqual(filterHistory(records, 'test', { ...empty, serviceId: '35', artifactId: '100' }).map(item => item.task.id), [10, 8])
})

test('environment fence and composed filters preserve failed legacy records', () => {
  assert.deepEqual(filterHistory(records, 'test', { ...empty, serviceId: '35', status: 'FAILED' }).map(item => item.task.id), [9, 5])
  assert.deepEqual(filterHistory(records, 'dev', { ...empty, keyword: ' WEB-APP ' }).map(item => item.task.id), [7])
  assert.deepEqual(filterHistory(records, 'test', { ...empty, serviceId: '35', keyword: '201' }).map(item => item.task.id), [9])
  assert.equal(filterHistory(records, 'prod', empty).length, 0)
})

test('pagination cannot stay beyond the last page after filtering or live updates', () => {
  assert.equal(clampHistoryPage(10, 13), 2)
  assert.equal(clampHistoryPage(2, 12), 1)
  assert.equal(clampHistoryPage(10, 0), 1)
  assert.equal(clampHistoryPage(-2, 30), 1)
  assert.equal(clampHistoryPage(NaN, 30), 1)
})
