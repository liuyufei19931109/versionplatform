const { runWithTranslations } = require('../test-support/i18n.cjs')
const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs'), path = require('node:path'), ts = require('typescript');
const compiled = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/productionGate.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
const context = { exports: {} };
runWithTranslations(compiled, context);
const { readinessMap, productionEligible, readinessBadge } = context.exports;

test('only artifacts with a successful pre-production or production deployment are eligible', () => {
  const map = readinessMap([{ artifactId: 1, preTaskId: 10 }, { artifactId: 2, prodTaskId: 20 }, { artifactId: 3 }]);
  assert.equal(productionEligible(map.get('1')), true);
  assert.equal(productionEligible(map.get('2')), true);
  assert.equal(productionEligible(map.get('3')), false);
  assert.equal(productionEligible(map.get('4')), false);
});
test('badge prefers pre-production evidence over a past production release', () => {
  assert.equal(readinessBadge({ artifactId: 1, preTaskId: 10, prodTaskId: 20 }).text, '预生产已通过');
  assert.equal(readinessBadge({ artifactId: 2, prodTaskId: 20 }).text, '曾发布生产');
  assert.equal(readinessBadge(undefined).type, 'warning');
});
