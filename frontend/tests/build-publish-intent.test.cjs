// All storage, identity, time and transport are local fixtures. No browser or network is used.
const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const source = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/buildPublishIntent.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const NOW = 1792252800000, DAY = 24 * 60 * 60 * 1000;
const key = (id = 501, user = 'operator') => `release:build-publish:${encodeURIComponent(user)}:${id}`;
const plain = value => value == null ? value : JSON.parse(JSON.stringify(value));
function runtime(options = {}) {
  const storage = options.storage || new Map();
  const authUser = { value: options.username === null ? null : { username: options.username || 'operator' } };
  let now = options.now ?? NOW;
  const api = { exports: {}, require(name) { assert.equal(name, './auth'); return { authUser }; }, Date: { now: () => now },
    sessionStorage: {
      getItem(name) { if (options.storageBlocked) throw new Error('storage blocked'); return storage.get(name) ?? null; },
      setItem(name, value) { if (options.storageBlocked) throw new Error('storage blocked'); storage.set(name, String(value)); },
      removeItem(name) { if (options.storageBlocked) throw new Error('storage blocked'); storage.delete(name); },
    },
  };
  vm.runInNewContext(source, api, { filename: 'buildPublishIntent.compiled.js' });
  return { ...api.exports, storage, authUser, setNow: value => { now = value; } };
}
const target = { environment: 'test', serverIds: [2, 7] };
function serialized(overrides = {}) { return JSON.stringify({ version: 2, createdAt: NOW, mode: 'CONFIRM', target, ...overrides }); }

test('explicit build-only survives refresh, is consumed once and never prompts', () => {
  const first = runtime(); first.rememberBuildPublish(501, null);
  const second = runtime({ storage: first.storage });
  const intent = plain(second.consumeBuildPublishIntent(501));
  assert.equal(intent.mode, 'BUILD_ONLY'); assert.equal(intent.target, undefined);
  assert.equal(second.consumeBuildPublishIntent(501), undefined); assert.equal(first.storage.has(key()), false);
  first.rememberBuildPublish(502, null); assert.equal(first.consumeBuildPublish(502), false);
});

test('confirmation target persists exactly and does not widen the requested server subset', () => {
  const first = runtime(); first.rememberBuildPublish(501, target);
  const second = runtime({ storage: first.storage });
  const intent = plain(second.consumeBuildPublishIntent(501));
  assert.equal(intent.version, 2); assert.equal(intent.mode, 'CONFIRM'); assert.equal(intent.createdAt, NOW);
  assert.deepEqual(intent.target, target); assert.equal(second.consumeBuildPublish(501), false);
});

test('server targets require explicit servers while App uses the generic artifact-locked flow', () => {
  const value = runtime(); value.rememberBuildPublish(501, { environment: 'prod', serverIds: [] });
  assert.equal(value.consumeBuildPublishIntent(501), undefined);
  value.rememberBuildPublish(502);
  const appIntent = value.consumeBuildPublishIntent(502);
  assert.equal(appIntent.mode, 'CONFIRM'); assert.equal(appIntent.target, undefined);
});

test('legacy timestamp is accepted only as generic confirmation without invented targets', () => {
  const value = runtime(); value.storage.set(key(), String(NOW));
  const intent = plain(value.consumeBuildPublishIntent(501));
  assert.equal(intent.mode, 'CONFIRM'); assert.equal(intent.target, undefined);
  assert.equal(value.consumeBuildPublishIntent(501), undefined);
  value.storage.set(key(502), String(NOW)); assert.equal(value.consumeBuildPublish(502), true);
});

test('an omitted legacy argument remains generic confirmation for old callers', () => {
  const value = runtime(); value.rememberBuildPublish(501);
  const intent = plain(value.consumeBuildPublishIntent(501)); assert.equal(intent.mode, 'CONFIRM'); assert.equal(intent.target, undefined);
});

test('different signed-in user cannot consume or delete the submitting users intent', () => {
  const value = runtime(); value.rememberBuildPublish(501, target);
  value.authUser.value = { username: 'different-user' };
  assert.equal(value.consumeBuildPublishIntent(501), undefined); assert.equal(value.storage.has(key()), true);
  value.authUser.value = { username: 'operator' }; assert.deepEqual(plain(value.consumeBuildPublishIntent(501).target), target);
});

test('separate tab memory and session storage cannot observe the original tab intent', () => {
  const original = runtime(), separateTab = runtime(); original.rememberBuildPublish(501, target);
  assert.equal(separateTab.consumeBuildPublishIntent(501), undefined);
  assert.equal(original.consumeBuildPublish(501), true);
});

test('storage failure keeps same-tab behavior without using global localStorage', () => {
  const value = runtime({ storageBlocked: true }); value.rememberBuildPublish(501, target);
  assert.deepEqual(plain(value.consumeBuildPublishIntent(501).target), target);
  assert.equal(value.consumeBuildPublishIntent(501), undefined);
  value.rememberBuildPublish(501, null); value.renewBuildPublish(501); assert.equal(value.consumeBuildPublish(501), false);
});

test('renewing failed builds retains explicit mode and target instead of inventing publication', () => {
  const value = runtime(); value.rememberBuildPublish(501, null); value.rememberBuildPublish(502, target);
  value.setNow(NOW + 60000); value.renewBuildPublish(501); value.renewBuildPublish(502);
  const only = plain(value.consumeBuildPublishIntent(501)), confirm = plain(value.consumeBuildPublishIntent(502));
  assert.equal(only.mode, 'BUILD_ONLY'); assert.equal(only.createdAt, NOW + 60000);
  assert.equal(confirm.mode, 'CONFIRM'); assert.deepEqual(confirm.target, target); assert.equal(confirm.createdAt, NOW + 60000);
});

test('expiration boundary, future timestamps and malformed storage are rejected and removed', () => {
  const invalid = [String(NOW - DAY), String(NOW + 1), '0', '-1', 'NaN', '{}', '[]', 'null', 'true', '{broken',
    serialized({ version: 99 }), serialized({ mode: 'AUTO_PUBLISH' }), serialized({ createdAt: NOW + 1 }),
    serialized({ createdAt: NOW - DAY }), serialized({ createdAt: 'not-a-time' }),
    serialized({ target: { environment: 'production', serverIds: [1] } }),
    serialized({ target: { environment: 'test', serverIds: ['not-a-server'] } }),
    serialized({ target: { environment: 'test', serverIds: [0] } }),
    serialized({ target: { environment: 'test', serverIds: [-1] } }),
    serialized({ target: { environment: 'test', serverIds: [1.5] } }),
    serialized({ target: { environment: 'test', serverIds: [2, 2] } }),
    serialized({ target: { environment: 'test', serverIds: [Number.MAX_SAFE_INTEGER + 1] } }),
    serialized({ target: { environment: 'test' } }),
  ];
  for (const raw of invalid) {
    const value = runtime(); value.storage.set(key(), raw);
    assert.equal(value.consumeBuildPublishIntent(501), undefined, raw); assert.equal(value.storage.has(key()), false, raw);
  }
  const boundary = runtime(); boundary.storage.set(key(), serialized({ createdAt: NOW - DAY + 1 }));
  assert.equal(boundary.consumeBuildPublishIntent(501).mode, 'CONFIRM');
});

test('retry cannot revive an expired or malformed record into a confirmation prompt', () => {
  for (const raw of [serialized({ createdAt: NOW - DAY, mode: 'BUILD_ONLY', target: undefined }), '{broken', serialized({ mode: 'AUTO_PUBLISH' })]) {
    const value = runtime(); value.storage.set(key(), raw); value.renewBuildPublish(501);
    assert.equal(value.consumeBuildPublish(501), false, raw);
  }
});

test('forget erases persisted and in-memory intent, matching cancel or discard behavior', () => {
  for (const storageBlocked of [false, true]) {
    const value = runtime({ storageBlocked }); value.rememberBuildPublish(501, target); value.forgetBuildPublish(501);
    assert.equal(value.consumeBuildPublishIntent(501), undefined);
  }
});

test('invalid replacement clears an older plan instead of silently keeping the previous targets', () => {
  const value = runtime(); value.rememberBuildPublish(501, target);
  value.rememberBuildPublish(501, { environment: 'test', serverIds: [-1] });
  assert.equal(value.consumeBuildPublishIntent(501), undefined);
  assert.equal(value.storage.has(key()), false);
});

test('later mutations to selected servers cannot change a queued intent, including memory fallback', () => {
  for (const storageBlocked of [false, true]) {
    const value = runtime({ storageBlocked }), selection = { environment: 'test', serverIds: [2] };
    value.rememberBuildPublish(501, selection); selection.serverIds.push(9); selection.environment = 'prod';
    assert.deepEqual(plain(value.consumeBuildPublishIntent(501).target), { environment: 'test', serverIds: [2] });
  }
});

test('missing identity and invalid task identifiers do not store cross-task intent', () => {
  const signedOut = runtime({ username: null }); signedOut.rememberBuildPublish(501, target); assert.equal(signedOut.storage.size, 0);
  const value = runtime();
  for (const id of ['', 'wrong', '../501', '501:other', -1, 1.5, null, undefined]) {
    value.rememberBuildPublish(id, target); assert.equal(value.consumeBuildPublishIntent(id), undefined);
  }
  assert.equal(value.storage.size, 0);
});

test('username separator characters are encoded without colliding with another namespace', () => {
  const value = runtime({ username: 'operator:other/test' }); value.rememberBuildPublish(501, target);
  assert.equal(value.storage.has(key(501, 'operator:other/test')), true);
  value.authUser.value = { username: 'operator' }; assert.equal(value.consumeBuildPublishIntent(501), undefined);
});
