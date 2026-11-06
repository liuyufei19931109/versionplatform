const assert = require('node:assert/strict'), { test } = require('node:test'), fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), ts = require('typescript');
const sandbox = { exports: {} }; vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/taskActivity.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText, sandbox); const api = sandbox.exports;
const raw = { key: 'task:25', id: 25, taskKind: 'BUILD', serviceId: 3, serviceName: '示例服务', serviceType: 'BACKEND', createdBy: 'operator', mine: true, hidden: false, status: 'PENDING', queuePosition: 2, canCancel: true, cancelPath: '/api/builds/25/cancel', detailPath: '/builds/25', currentStepLabel: '等待执行' };
const snapshot = extra => ({ serverTime: '2026-10-18T01:00:00Z', running: null, pending: [raw], runtimePending: [], pendingCount: 1, pendingRuntimeCount: 0, recentCompletions: [], concurrency: 1, ...extra });
test('parallel activity lists every running task and keeps permission redaction', () => {
  const feed=api.normalizeTaskActivity(snapshot({running:{...raw,status:'RUNNING'},runningTasks:[{...raw,status:'RUNNING',executionMode:'CLOUD'},{...raw,key:'task:26',id:26,status:'RUNNING',executionMode:'LOCAL'},{...raw,key:'hidden:cloud-1',status:'RUNNING',hidden:true,serviceName:'SECRET',createdBy:'SECRET',executionMode:'CLOUD',waitingReason:'SECRET_REASON'}],cloudConcurrency:3,cloudRunningCount:2}));
  assert.equal(feed.runningTasks.length,3);assert.equal(feed.cloudConcurrency,3);assert.equal(feed.cloudRunningCount,2);assert.equal(feed.runningTasks[2].serviceName,'其他任务');assert.equal(feed.runningTasks[2].id,null);assert.equal(feed.runningTasks[2].detailPath,'');
  assert.equal(feed.runningTasks[0].executionMode,'CLOUD');assert.equal(feed.runningTasks[1].executionMode,'LOCAL');assert.equal(feed.runningTasks[2].executionMode,'UNKNOWN');assert.equal(feed.runningTasks[2].waitingReason,'');
  assert.equal(api.normalizeTaskActivity(snapshot({running:{...raw,status:'RUNNING'}})).runningTasks.length,1);
});
test('execution modes and waiting reasons preserve scheduler facts without inventing queue progress', () => {
  const items = ['CLOUD', 'LOCAL', 'UNKNOWN', 'EXTERNAL', undefined].map((executionMode, index) => ({ ...raw, key: `task:${40 + index}`, id: 40 + index, executionMode, queuePosition: index + 1, waitingReason: index === 0 ? '等待同一服务的前序任务完成' : '' }));
  const feed = api.normalizeTaskActivity(snapshot({ pending: items, pendingCount: 5, runtimePending: [{ ...raw, key: 'restart:8', taskKind: 'RESTART', executionMode: 'LOCAL', queuePosition: null, waitingReason: '等待本机执行资源释放' }], pendingRuntimeCount: 1 }));
  assert.deepEqual(Array.from(feed.pending, item => item.executionMode), ['CLOUD', 'LOCAL', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN']);
  assert.equal(feed.pending[0].waitingReason, '等待同一服务的前序任务完成');
  assert.equal(feed.pending[1].waitingReason, '');
  assert.equal(feed.pending[1].position, 2);
  assert.equal(feed.runtimePending[0].executionMode, 'LOCAL');
  assert.equal(feed.runtimePending[0].waitingReason, '等待本机执行资源释放');
  assert.equal(feed.runtimePending[0].position, null);
  assert.equal(feed.pendingCount + feed.pendingRuntimeCount, 6);
  assert.equal(api.normalizeTaskActivity(snapshot({ running: { ...raw, status: 'RUNNING', waitingReason: '过期的排队原因' } })).running.waitingReason, '');
});
test('hidden activity strips all task identity, links and actions even if mistakenly supplied', () => {
  const feed = api.normalizeTaskActivity(snapshot({ running: { ...raw, hidden: true, status: 'RUNNING', executionMode: 'CLOUD', waitingReason: 'SECRET cloud repository and predecessor' } }));
  const item = feed.running;
  assert.equal(item.serviceName, '其他任务'); assert.equal(item.createdBy, ''); assert.equal(item.id, null); assert.equal(item.mine, false); assert.equal(item.detailPath, ''); assert.equal(item.canCancel, false); assert.equal(item.cancelPath, '');
  assert.equal(item.executionMode, 'UNKNOWN'); assert.equal(item.waitingReason, ''); assert.doesNotMatch(JSON.stringify(item), /SECRET|CLOUD/);
  assert.equal(feed.pending[0].position, 2); assert.equal(feed.pending[0].cancelPath, '/builds/25/cancel');
});
test('only explicit internal task detail/cancel routes are usable', () => {
  for (const route of ['/apps/builds/5', '/releases/7', '/runtime?operationId=3&serviceId=4']) assert.equal(api.taskDetailPath(route), route);
  for (const route of ['//evil.test', 'https://evil.test', '/settings', '/builds/7?x=1', '/runtime?operationId=1&token=secret']) assert.equal(api.taskDetailPath(route), '');
  for (const route of ['/api/runtime/restarts/7/cancel', '/api/apps/builds/1/cancel']) assert.equal(api.taskCancelPath(route), route.slice(4));
  for (const route of ['/api/settings', '/api/builds/1/retry', '//evil.test/x', '/builds/1/cancel?x=1']) assert.equal(api.taskCancelPath(route), '');
  assert.equal(api.normalizeTaskActivity(snapshot({ pending: [{ ...raw, status: 'RUNNING' }] })).pending[0].canCancel, false);
});
test('completion ledger ignores history and pending confirmation; records same-id retries separately', () => {
  const make = (status, finishedAt, eventKey) => ({ ...api.normalizeTaskActivity(snapshot()).pending[0], status, finishedAt, eventKey });
  const baseline = Date.parse('2026-10-18T01:00:00Z');
  const jobs = [make('SUCCESS', '2026-10-18T00:00:00Z', 'old'), make('WAITING_CONFIRMATION', '2026-10-18T01:01:00Z', 'waiting'), make('FAILED', '2026-10-18T01:02:00Z', 'first'), make('SUCCESS', '2026-10-18T01:04:00Z', 'retry')];
  assert.deepEqual(Array.from(api.unseenCompletions(jobs, { baseline, seen: ['first'] }), job => job.eventKey), ['retry']);
  assert.equal(api.unseenCompletions([{ ...jobs[3], mine: false }], { baseline, seen: [] }).length, 0);
  assert.equal(api.readActivityLedger('invalid'), null); assert.equal(api.readActivityLedger(JSON.stringify({ baseline, seen: Array.from({ length: 500 }, (_, i) => String(i)) })).seen.length, 256);
});
test('invalid snapshots fail instead of claiming idle; duration uses server offset input', () => {
  for (const value of [null, {}, snapshot({ serverTime: '' }), snapshot({ pendingCount: -1 }), snapshot({ running: {} }), snapshot({ recentCompletions: undefined })]) assert.throws(() => api.normalizeTaskActivity(value));
  assert.equal(api.activityElapsed({ startedAt: '2026-10-18T01:00:00Z', finishedAt: '' }, Date.parse('2026-10-18T01:03:20Z')), '3 分 20 秒');
  assert.equal(api.activityElapsed({ startedAt: 'bad', finishedAt: '' }), '');
  assert.equal(api.activityKind({ kind: 'BUILD', serviceType: 'FLUTTER_ANDROID', visible: true }), 'App 构建');
});

test('mini program activity permits only its own internal paths and announces uncertain results once', () => {
  assert.equal(api.taskDetailPath('/miniprograms?jobId=501'), '/miniprograms?jobId=501');
  assert.equal(api.taskDetailPath('/miniprograms?jobId=501&token=secret'), '');
  assert.equal(api.taskCancelPath('/api/miniprograms/jobs/501/cancel'), '/miniprograms/jobs/501/cancel');
  const result = { ...raw, taskKind: 'MINIPROGRAM', status: 'UNKNOWN', finishedAt: '2026-10-18T01:02:00Z', eventKey: 'mini-501' };
  const task = api.normalizeTaskActivity(snapshot({ recentCompletions: [result] })).recent[0];
  assert.equal(api.activityKind(task), '小程序构建');
  const ledger = { baseline: Date.parse('2026-10-18T01:00:00Z'), seen: [] };
  assert.equal(api.unseenCompletions([task], ledger).length, 1);
  assert.equal(api.unseenCompletions([task], { ...ledger, seen: ['mini-501'] }).length, 0);
  assert.equal(api.unseenCompletions([{ ...task, kind: 'BUILD' }], ledger).length, 0);
  const reconciled = api.normalizeTaskActivity(snapshot({ recentCompletions: [{ ...result, status: 'RECONCILED' }] })).recent[0];
  assert.equal(reconciled.status, 'RECONCILED'); assert.equal(api.unseenCompletions([reconciled], ledger).length, 0);
  assert.equal(api.normalizeTaskActivity(snapshot({ recentCompletions: [{ ...result, taskKind: 'BUILD', status: 'RECONCILED' }] })).recent[0].status, 'UNKNOWN');
});
