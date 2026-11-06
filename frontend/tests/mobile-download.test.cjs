const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), ts = require('typescript');

// Exercise the shipped helper with fake time and a controllable transport, without waiting minutes.
function setup(expectedBytes = 100) {
  let now = 0, timerId = 0, config, resolveRequest, rejectRequest, calls = 0;
  const timers = new Map(), samples = [];
  const sandbox = {
    exports: {}, Blob, AbortController, performance: { now: () => now },
    setTimeout(fn, delay) { const id = ++timerId; timers.set(id, { at: now + delay, fn }); return id; },
    clearTimeout(id) { timers.delete(id); },
  };
  const source = fs.readFileSync(path.join(__dirname, '../src/mobileDownload.ts'), 'utf8');
  vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText, sandbox);
  function start(extra = {}) {
    const run = sandbox.exports.createPackageDownload({ expectedBytes, onProgress: value => samples.push(value), request(value) {
      config = value; calls++;
      return new Promise((resolve, reject) => { resolveRequest = resolve; rejectRequest = reject; config.signal.addEventListener('abort', () => reject(new Error('transport abort')), { once: true }); });
    }, ...extra });
    // Prevent Node from treating intentionally delayed rejection assertions as unhandled.
    run.promise.catch(() => {});
    return run;
  }
  function advance(ms) {
    const end = now + ms;
    while (true) {
      const next = [...timers].filter(([, timer]) => timer.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      now = next[1].at; timers.delete(next[0]); next[1].fn();
    }
    now = end;
  }
  return { start, advance, samples, timers, get config() { return config; }, get calls() { return calls; }, progress(loaded, total) { config.onDownloadProgress({ loaded, total }); }, finish(bytes = expectedBytes) { resolveRequest({ data: new Blob([new Uint8Array(bytes)]) }); }, fail(error) { rejectRequest(error); } };
}
const flush = () => new Promise(resolve => setImmediate(resolve));

test('continuous data beyond two minutes completes with no overall timeout and exact size', async () => {
  const t = setup(300), run = t.start(); await flush();
  assert.equal(t.config.responseType, 'blob'); assert.equal(t.config.timeout, 0);
  for (let i = 1; i <= 10; i++) { t.advance(30000); t.progress(i * 30, 999); assert.equal(t.config.signal.aborted, false); }
  assert.equal(t.samples.at(-1).percent, 99); assert.equal(t.samples.at(-1).total, 300); assert.equal(t.samples.at(-1).speedBytesPerSecond, 1);
  t.finish(); const blob = await run.promise;
  assert.equal(blob.size, 300); assert.equal(t.samples.at(-1).percent, 100); assert.equal(t.timers.size, 0);
});

test('two minutes without new bytes aborts even if repeated progress events arrive', async () => {
  const t = setup(), run = t.start(); await flush(); t.progress(10); t.advance(60000); t.progress(10); t.advance(60001);
  await assert.rejects(run.promise, error => error.kind === 'idle'); assert.equal(t.config.signal.aborted, true); assert.equal(t.timers.size, 0);
});

test('connection that never returns its first byte is bounded by the idle timer', async () => {
  const t = setup(), run = t.start(); await flush(); t.advance(120001);
  await assert.rejects(run.promise, error => error.kind === 'idle'); assert.equal(t.config.signal.aborted, true);
});

test('immediate cancellation never issues a request and active cancellation aborts transport', async () => {
  const early = setup(), earlyRun = early.start(); earlyRun.cancel(); await assert.rejects(earlyRun.promise, error => error.kind === 'cancelled'); assert.equal(early.calls, 0);
  const t = setup(), run = t.start(); await flush(); t.progress(40); run.cancel(); await assert.rejects(run.promise, error => error.kind === 'cancelled'); assert.equal(t.config.signal.aborted, true); assert.equal(t.timers.size, 0);
  const samples = t.samples.length; t.progress(100); assert.equal(t.samples.length, samples);
});

test('a truncated HTTP-success blob is rejected and never reports completed progress', async () => {
  const t = setup(), run = t.start(); await flush(); t.progress(50); t.finish(50);
  await assert.rejects(run.promise, error => error.kind === 'incomplete'); assert.equal(t.samples.some(sample => sample.percent === 100), false);
});

test('more bytes than immutable artifact size abort immediately', async () => {
  const t = setup(), run = t.start(); await flush(); t.progress(101);
  await assert.rejects(run.promise, error => error.kind === 'incomplete'); assert.equal(t.config.signal.aborted, true);
});

test('JSON blob response displays the server message instead of generic Axios failure', async () => {
  const t = setup(), run = t.start(); await flush(); t.fail({ response: { status: 503, data: new Blob([JSON.stringify({ message: '下载暂不可用，请重新下载' })], { type: 'application/json' }) } });
  await assert.rejects(run.promise, error => error.kind === 'request' && error.message === '下载暂不可用，请重新下载'); assert.equal(t.timers.size, 0);
});

test('non-JSON permission failure remains actionable and does not expose raw response', async () => {
  const t = setup(), run = t.start(); await flush(); t.fail({ response: { status: 403, data: new Blob(['<html>internal stack details</html>']) } });
  await assert.rejects(run.promise, error => error.kind === 'request' && error.message === '没有下载权限，请联系管理员');
});

test('missing expected file size blocks the request instead of accepting arbitrary file data', async () => {
  const t = setup(0), run = t.start(); await assert.rejects(run.promise, error => error.kind === 'incomplete'); assert.equal(t.calls, 0); assert.equal(t.timers.size, 0);
});

test('retry is a fresh transfer and is not canceled by the old transfer timer', async () => {
  const t = setup(), first = t.start(); await flush(); t.progress(30); first.cancel(); await assert.rejects(first.promise, error => error.kind === 'cancelled');
  const second = t.start(); await flush(); t.advance(90000); t.progress(50); t.advance(90000); assert.equal(t.config.signal.aborted, false); t.finish(); await second.promise; assert.equal(t.calls, 2);
});
