const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), ts = require('typescript');
const compiled = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/containerLogs.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
const context = { exports: {}, Date, Set, Map };
vm.runInNewContext(compiled, context);
const logs = context.exports;
const plain = value => JSON.parse(JSON.stringify(value));

test('levels are read from common Spring, bracketed, JSON and logfmt formats', () => {
  assert.equal(logs.levelOf('2026-10-19 14:00:00.123 ERROR 1 --- [main] c.e.App : boom'), 'error');
  assert.equal(logs.levelOf('[WARN] disk almost full'), 'warn');
  assert.equal(logs.levelOf('{"level":"error","msg":"x"}'), 'error');
  assert.equal(logs.levelOf('time=1 level=debug msg=x'), 'debug');
  assert.equal(logs.levelOf('GET /api/errors 200'), '');
  assert.equal(logs.levelOf('ERR_CONNECTION_RESET happened'), '');
});

test('stack traces inherit the error level so "errors only" keeps the whole trace', () => {
  const entries = logs.toEntries([
    { ts: 't1', text: '2026 INFO started' },
    { ts: 't2', text: '2026 ERROR request failed' },
    { ts: 't2', text: 'java.lang.IllegalStateException: boom' },
    { ts: 't2', text: '\tat com.example.Foo.bar(Foo.java:10)' },
    { ts: 't2', text: 'Caused by: java.io.IOException: closed' },
    { ts: 't2', text: '\t... 12 more' },
    { ts: 't3', text: '2026 INFO next request' },
  ], 1);
  assert.deepEqual(plain(entries.map(entry => [entry.level, entry.continuation])), [
    ['info', false], ['error', false], ['error', true], ['error', true], ['error', true], ['error', true], ['info', false],
  ]);
  const errors = logs.visibleEntries(entries, { query: '', onlyMatches: false, level: 'error' });
  assert.equal(errors.length, 5);
});

test('reconnect and load-earlier drop only the duplicated boundary lines', () => {
  const existing = logs.toEntries([{ ts: '2026-10-19T06:00:01.000000000Z', text: 'a' }, { ts: '2026-10-19T06:00:02.000000000Z', text: 'b' }], 1);
  const after = logs.withoutOverlap([
    { ts: '2026-10-19T06:00:01.000000000Z', text: 'a' }, { ts: '2026-10-19T06:00:02.000000000Z', text: 'b' },
    { ts: '2026-10-19T06:00:02.000000000Z', text: 'b2' }, { ts: '2026-10-19T06:00:03.000000000Z', text: 'c' },
  ], existing, 'after');
  assert.deepEqual(plain(after.map(line => line.text)), ['b2', 'c']);
  const before = logs.withoutOverlap([
    { ts: '2026-10-19T06:00:00.000000000Z', text: 'z' }, { ts: '2026-10-19T06:00:01.000000000Z', text: 'a' },
  ], existing, 'before');
  assert.deepEqual(plain(before.map(line => line.text)), ['z']);
});

test('search highlighting is case-insensitive and preserves the original text', () => {
  assert.deepEqual(plain(logs.segments('Error and ERROR', 'error')), [{ text: 'Error', hit: true }, { text: ' and ', hit: false }, { text: 'ERROR', hit: true }]);
  assert.deepEqual(plain(logs.segments('nothing', '')), [{ text: 'nothing', hit: false }]);
});

test('reconnect compares fractional application timestamps chronologically and keeps Docker nanoseconds', () => {
  const boundary = logs.toEntries([{ ts: '2026-10-19T06:00:02Z', text: 'boundary' }], 1);
  const after = logs.withoutOverlap([
    { ts: '2026-10-19T06:00:02.000Z', text: 'boundary' },
    { ts: '2026-10-19T06:00:02.001Z', text: 'later' },
  ], boundary, 'after');
  assert.deepEqual(plain(after.map(line => line.text)), ['later']);
  const precise = logs.toEntries([{ ts: '2026-10-19T06:00:02.000000001Z', text: 'first' }], 1);
  assert.equal(logs.withoutOverlap([{ ts: '2026-10-19T06:00:02.000000002Z', text: 'second' }], precise, 'after').length, 1);
});

test('sse parser handles split chunks, comments and CRLF', () => {
  let state = logs.parseSse('event:lines\r\ndata:{"a":1}\r\n\r\n: keepalive\n\nevent:end\ndata:{"re');
  assert.deepEqual(plain(state.events), [{ event: 'lines', data: '{"a":1}' }]);
  state = logs.parseSse(state.rest + 'ason":"ENDED"}\n\n');
  assert.deepEqual(plain(state.events), [{ event: 'end', data: '{"reason":"ENDED"}' }]);
  assert.equal(state.rest, '');
});

test('downloads keep docker timestamps and get a safe file name', () => {
  const entries = logs.toEntries([{ ts: '2026-10-19T06:00:01Z', text: 'a' }, { ts: null, text: 'b' }], 1);
  assert.equal(logs.downloadText(entries), '2026-10-19T06:00:01Z a\nb\n');
  assert.equal(logs.downloadName(['sample', 'prod', 'node/prod 1'], new Date(2026, 9, 19, 8, 5, 9)), 'sample-prod-node_prod_1-20261019-080509.log');
});

test('application log downloads preserve original timestamps and stack traces without an extra prefix', () => {
  const entries = logs.toEntries([
    { ts: '2026-10-19T06:00:01Z', text: '2026-10-19 14:00:01 ERROR request failed' },
    { ts: '2026-10-19T06:00:01Z', text: '\tat com.example.App.run(App.java:10)' },
  ], 1);
  assert.equal(logs.downloadText(entries, 'application'), '2026-10-19 14:00:01 ERROR request failed\n\tat com.example.App.run(App.java:10)\n');
});
