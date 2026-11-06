const assert = require('node:assert/strict'), { test } = require('node:test'), fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), ts = require('typescript');
const sandbox = { exports: {}, URL }; vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/gitEvents.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText, sandbox); const api = sandbox.exports;
const service = { id: 7, name: '示例服务', serviceKey: 'sample-service', serviceType: 'BACKEND', canBuild: true };
const item = (id, receivedAt) => ({ id, services: [service], branch: 'demo/fix-sample', commitId: 'a'.repeat(40), receivedAt, unread: true });
test('received time, then id determine descending latest five, independent of branch naming', () => {
  const items = [item(1, '2026-10-18T02:01:00Z'), item(4, '2026-10-18T02:04:00Z'), item(3, '2026-10-18T02:03:00Z'), item(2, '2026-10-18T02:02:00Z'), item(6, '2026-10-18T02:06:00Z'), item(5, '2026-10-18T02:05:00Z')];
  const result = api.normalizeGitEvents({ enabled: true, unread: true, latestEventId: 6, items });
  assert.deepEqual(Array.from(result.items, x => x.id), [6, 5, 4, 3, 2]); assert.equal(api.gitEventReadThrough(result.items), 6);
});
test('build routes preserve exact ordinary branch names and honor canBuild', () => {
  const route = api.gitEventBuildRoute(service, 'feature/fix + parser'); assert.equal(route.path, '/build/new'); assert.equal(route.query.serviceId, '7'); assert.equal(route.query.branch, 'feature/fix + parser');
  assert.equal(api.gitEventBuildRoute({ ...service, serviceType: 'FLUTTER_ANDROID' }, 'fix/app').path, '/apps/7/build');
  assert.equal(api.gitEventBuildRoute({ ...service, canBuild: false }, 'master'), undefined); assert.equal(api.gitEventBuildRoute(service, ''), undefined);
});
test('unsafe links, malformed replies and disabled feed do not produce misleading data', () => {
  assert.equal(api.githubEventUrl('https://github.com/example-team/web-app/commit/' + 'a'.repeat(40)), 'https://github.com/example-team/web-app/commit/' + 'a'.repeat(40));
  for (const value of ['javascript:alert(1)', 'https://evil.example/x', 'https://github.com:444/a/b/pull/3', 'https://u:secret@github.com/a/b/pull/1', 'https://github.com/a/b/pull/1?x=y', 'https://github.com/a/b/settings']) assert.equal(api.githubEventUrl(value), '');
  for (const value of [null, {}, { enabled: 'true', items: [] }, { enabled: true }]) assert.throws(() => api.normalizeGitEvents(value));
  assert.equal(api.normalizeGitEvents({ enabled: false, unread: true, items: [item(1, '2026-10-18T02:01:00Z')] }).items.length, 0);
  assert.equal(api.normalizeGitEvents({ enabled: true, items: [null, { id: 1, services: [] }, item(2, '2026-10-18T02:02:00Z')] }).items.length, 1);
});
