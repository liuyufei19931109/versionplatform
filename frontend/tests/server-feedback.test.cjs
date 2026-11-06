const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')

const src = path.join(__dirname, '../src')
const cache = new Map()
function load(name) {
  if (cache.has(name)) return cache.get(name)
  const filename = path.join(src, name + '.ts')
  const result = {}
  cache.set(name, result)
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText
  function imports(id) {
    if (!id.startsWith('.')) return require(id)
    if (id.endsWith('.json')) return require(path.join(src, id))
    return load(id)
  }
  new Function('exports', 'require', code)(result, imports)
  return result
}

const { selectLanguage } = load('./i18n')
const { errorText } = load('./api')
const failure = (status, data) => ({ response: { status, data } })

test('auth and deployment policy use the specific server message when sharing a validation code', () => {
  selectLanguage('es')
  assert.match(errorText(failure(400, { code: 'INVALID_ARGUMENT', message: '用户名或密码错误' })), /contraseña/)
  selectLanguage('en')
  assert.match(errorText(failure(400, { code: 'INVALID_ARGUMENT', message: '生产只能发布已在预生产发布成功的制品：请先将该制品发布到预生产并通过健康检查' })), /health check.*production/)
  assert.match(errorText(failure(409, { code: 'TOTP_REUSED' })), /already been used/)
  assert.match(errorText(failure(502, { code: 'HOST_KEY' })), /known_hosts/)
})

test('retains opaque server diagnostics, unknown codes, references and task numbers', () => {
  selectLanguage('es')
  const diagnostic = '服务 Ω: release/用户-42'
  const result = errorText(failure(403, { code: 'CUSTOM_BLOCK', reference: 'ref-7', message: diagnostic }))
  assert.match(result, /permiso/)
  assert.ok(result.includes(diagnostic) && result.includes('CUSTOM_BLOCK') && result.includes('ref-7'))
  const active = errorText(failure(409, { code: 'DEPLOY_ACTIVE', message: '该制品在所选节点已有发布任务，请查看任务 #52' }))
  assert.match(active, /tarea.*52/)
  selectLanguage('zh-CN')
  assert.equal(errorText(failure(409, { message: diagnostic })), diagnostic)
})

test('transport errors are localized and local exception details are preserved', () => {
  selectLanguage('en')
  assert.match(errorText(new Error('Network Error')), /connect/i)
  assert.match(errorText(new Error('timeout of 30000ms exceeded')), /timed out/i)
  assert.equal(errorText(new Error('branch/customer-φ')), 'branch/customer-φ')
  assert.equal(errorText(null, 'Fallback'), 'Fallback')
})
