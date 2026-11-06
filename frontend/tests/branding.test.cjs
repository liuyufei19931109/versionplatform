const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')

const file = path.join(__dirname, '../src/branding.ts')
const compiled = ts.transpile(fs.readFileSync(file, 'utf8'), {
  target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
})
const branding = {}
new Function('exports', 'require', compiled)(branding, require)
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==', 'base64')
const favicon = {}
global.document = { querySelector: () => favicon }
global.Image = class { async decode() {} }

test('a clear wins against an older logo response and fetches stay anonymous and on this origin', async () => {
  let resolve
  global.fetch = (url, options) => {
    assert.equal(url, '/api/branding/logo')
    assert.equal(options.credentials, 'omit')
    assert.equal(options.mode, 'same-origin')
    assert.equal(options.redirect, 'error')
    assert.equal(options.cache, 'no-store')
    return new Promise(done => { resolve = done })
  }
  const pending = branding.refreshLogo()
  global.fetch = async () => new Response(null, { status: 204 })
  await branding.refreshLogo()
  resolve(new Response(png, { headers: { 'Content-Type': 'image/png' } }))
  await pending
  assert.equal(branding.platformLogo.value, '')
  assert.equal(favicon.href, 'data:,')
})

test('a valid PNG appears in the page and favicon together; a failed read clears both', async () => {
  global.fetch = async () => new Response(png, { headers: { 'Content-Type': 'image/png' } })
  await branding.refreshLogo()
  assert.match(branding.platformLogo.value, /^blob:/)
  assert.equal(favicon.href, branding.platformLogo.value)
  global.fetch = async () => new Response('not an image')
  await branding.refreshLogo()
  assert.equal(branding.platformLogo.value, '')
  assert.equal(favicon.href, 'data:,')
  assert.equal(branding.logoUnavailable.value, true)
})

test('rejects invalid format, length and dimensions before decoding a user file', async () => {
  await assert.rejects(branding.previewPng(new Blob(['<svg/>'])), /PNG/)
  await assert.rejects(branding.previewPng(new Blob([new Uint8Array(256 * 1024 + 1)])), /256 KiB/)
  const wide = Buffer.from(png)
  wide.writeUInt32BE(1025, 16)
  await assert.rejects(branding.previewPng(new Blob([wide])), /1024/)
})

test('a re-encoded server PNG may exceed the user upload limit', async () => {
  const large = new Blob([png, new Uint8Array(300 * 1024)])
  await assert.rejects(branding.previewPng(large), /256 KiB/)
  global.fetch = async () => new Response(large, { headers: { 'Content-Type': 'image/png' } })
  await branding.refreshLogo()
  assert.match(branding.platformLogo.value, /^blob:/)
  assert.equal(branding.logoUnavailable.value, false)
})
