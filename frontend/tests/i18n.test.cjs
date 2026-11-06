const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
const { parse, compileTemplate } = require('vue/compiler-sfc')
const { resolveLanguage, selectLanguage, currentLocale, tUi } = require('../test-support/i18n.cjs')

const root = path.resolve(__dirname, '../src')
const dir = path.join(root, 'locales')
const catalogs = fs.readdirSync(dir).map(name => JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8')))
const dictionary = Object.assign({}, ...catalogs)
const placeholders = text => [...new Set(text.match(/\{\d+\}/g) || [])].sort()

test('regional browser preferences select a supported language; Chinese is the fallback', () => {
  assert.equal(resolveLanguage(['pt-BR', 'es-MX', 'en']), 'es')
  assert.equal(resolveLanguage(['en_GB']), 'en')
  assert.equal(resolveLanguage(['zh-Hant']), 'zh-CN')
  assert.equal(resolveLanguage([]), 'zh-CN')
})

test('interpolation treats user values as literal text and uses the selected language immediately', () => {
  try {
    for (const lang of ['zh-CN', 'es', 'en']) {
      selectLanguage(lang)
      assert.equal(currentLocale(), lang)
      const name = 'demo-$&-{0}-中文'
      assert.ok(tUi('{0} · 发版平台', name).includes(name))
      assert.equal(tUi('customer-created-label-自定义'), 'customer-created-label-自定义')
      assert.equal(tUi('界面语言'), { 'zh-CN':'界面语言', es:'Idioma de la interfaz', en:'Interface language' }[lang])
    }
  } finally { selectLanguage('zh-CN') }
})

test('every catalog entry has both translations and preserves interpolation slots', () => {
  for (const [source, translations] of Object.entries(dictionary)) {
    assert.equal(translations.length, 2, source)
    for (const text of translations) {
      assert.ok(typeof text === 'string' && text.trim(), source)
      assert.deepEqual(placeholders(text), placeholders(source), source)
    }
  }
})

test('every literal UI message in a TypeScript file or Vue template is in the catalog', () => {
  const missing = new Set()
  function checkCode(name, source) {
    const tree = ts.createSourceFile(name, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
    function visit(node) {
      if (ts.isCallExpression(node)) {
        const callee = ts.isPropertyAccessExpression(node.expression) ? node.expression.name : node.expression
        const arg = node.arguments[0]
        if (ts.isIdentifier(callee) && callee.text === 'tUi' && arg && ts.isStringLiteralLike(arg)
            && !Object.hasOwn(dictionary, arg.text)) missing.add(name + ' — ' + arg.text)
      }
      ts.forEachChild(node, visit)
    }
    visit(tree)
  }
  for (const name of fs.readdirSync(root, { recursive: true })) {
    if (!/\.(ts|vue)$/.test(name)) continue
    const source = fs.readFileSync(path.join(root, name), 'utf8')
    if (name.endsWith('.ts')) { checkCode(name, source); continue }
    const { descriptor } = parse(source, { filename: name })
    if (descriptor.scriptSetup) checkCode(name, descriptor.scriptSetup.content)
    if (descriptor.template) {
      const { code, errors } = compileTemplate({ source: descriptor.template.content, filename: name, id: 'test' })
      assert.deepEqual(errors, [], name)
      checkCode(name, code)
    }
  }
  assert.deepEqual([...missing], [])
})
