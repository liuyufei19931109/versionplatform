const { runWithTranslations } = require('../test-support/i18n.cjs')
const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs'), path = require('node:path'), ts = require('typescript');
const compiled = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/taskFailure.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
const context = { exports: {}, require: name => { assert.equal(name, './api'); return { stepLabel: code => code }; } };
runWithTranslations(compiled, context);
const guide = context.exports.taskFailureGuide;
test('baseline rejection guides new immutable commit rather than retrying old one', () => {
  const result = guide({ id: 1, errorMessage: '当前分支未包含 master 最新提交' });
  assert.equal(result.newBuild, true); assert.match(result.hint, /原来锁定/);
});
test('health failure does not incorrectly imply deployment did not occur', () => {
  const result = guide({ id: 1, steps: [{ code: 'HEALTH_CHECK', status: 'FAILED' }] });
  assert.match(result.title, /HEALTH_CHECK/); assert.match(result.hint, /可能已经启动/);
});
test('actual failed step wins over older current step and does not expose arbitrary errors', () => {
  const result = guide({ id: 1, currentStep: 'BUILD_IMAGE', errorMessage: 'password=DO_NOT_DISPLAY', steps: [{ code: 'PUSH_IMAGE', status: 'FAILED' }] });
  assert.match(result.title, /PUSH_IMAGE/); assert.match(result.hint, /重试推送/); assert.doesNotMatch(JSON.stringify(result), /DO_NOT_DISPLAY/);
});
test('unknown stage keeps evidence-based generic guidance', () => {
  const result = guide({ id: 1 }); assert.equal(result.title, '任务未完成'); assert.equal(result.newBuild, false); assert.match(result.hint, /查看错误日志/);
});

test('only observed errors from a selected target lead directly to its logs', () => {
  const failed = {status:'FAILED', targetServerIds:'2,8', errorMessage:'[失败节点 #8] [REMOTE_COMMAND] failed'};
  assert.equal(context.exports.failedTargetId(failed), '8');
  for (const errorMessage of ['legacy failure', '[失败节点 #9] failed', 'command output [失败节点 #8] failed']) {
    assert.equal(context.exports.failedTargetId({...failed,errorMessage}), undefined);
  }
  assert.equal(context.exports.failedTargetId({...failed,status:'SUCCESS'}), undefined);
  const scope = context.exports.retryDeploymentScope({...failed,steps:[{status:'SUCCESS',name:'连接'},{status:'FAILED',name:'启动服务'}]});
  assert.match(scope,/从「启动服务」/); assert.match(scope,/#2、#8/);
});

test('compilation failures offer a new revision as well as retrying the pinned build', () => {
  for(const code of ['BUILD_JAR','BUILD_APK','BUILD_AAB']) assert.equal(guide({currentStep:code}).alternativeBuild,true);
  for(const code of ['PUSH_IMAGE','START_SERVICE','HEALTH_CHECK']) assert.equal(guide({currentStep:code}).alternativeBuild,false);
});
