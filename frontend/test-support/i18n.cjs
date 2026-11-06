// The isolated TypeScript tests run without Vite. Give their VM modules the same
// translation catalog as the application, while preserving their API/storage mocks.
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')
const ts = require('typescript')

const entry = path.resolve(__dirname, '../src/i18n.ts')
const source = fs.readFileSync(entry, 'utf8')
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
})
const messages = {}
vm.runInNewContext(outputText, { exports: messages, require: createRequire(entry) })
exports.tUi = messages.tUi
exports.currentLocale = messages.currentLocale
exports.selectLanguage = messages.selectLanguage
exports.resolveLanguage = messages.resolveLanguage
exports.withTranslations = (fallback = require) => name => {
  if (name === './i18n' || name === '../i18n') return messages
  if (/^\.\/locales\/[a-z]+\.json$/.test(name)) return createRequire(entry)(name)
  return fallback(name)
}
exports.runWithTranslations = (code, sandbox, options) => {
  sandbox.require = exports.withTranslations(sandbox.require)
  return vm.runInNewContext(code, sandbox, options)
}
