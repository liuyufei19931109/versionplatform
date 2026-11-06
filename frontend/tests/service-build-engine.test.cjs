const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), ts = require('typescript');

const modules = new Map();
function load(name) {
  if (modules.has(name)) return modules.get(name);
  const context = { exports: {}, require(id) {
    if (id === './auth') return { clearAuth() {} };
    if (id.startsWith('./')) return load(id.slice(2));
    return require(id);
  } };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, `../src/${name}.ts`), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true } }).outputText, context);
  modules.set(name, context.exports);
  return context.exports;
}
const { normalizeService, serviceRequest } = load('api');
const { normalizeBuildEngine, buildEngineLabel } = load('buildEngine');

test('legacy and new services default to GitHub for every service type', () => {
  for (const serviceType of ['BACKEND', 'FRONTEND', 'FLUTTER_ANDROID']) {
    const old = normalizeService({ name: 'old', serviceType, configJson: JSON.stringify({ buildEngine: 'LOCAL' }) });
    assert.equal(old.buildEngine, 'GITHUB');
    assert.equal(serviceRequest(old).buildEngine, 'GITHUB');
    assert.equal(serviceRequest({ name: 'new', serviceType }).buildEngine, 'GITHUB');
  }
  for (const value of [undefined, null, '', 'unknown']) assert.equal(normalizeBuildEngine(value), 'GITHUB');
  assert.equal(normalizeBuildEngine('local'), 'LOCAL');
  assert.equal(buildEngineLabel(undefined), 'GitHub Actions');
  assert.equal(buildEngineLabel('LOCAL'), '本机构建');
});

test('top-level build engine survives both directions for every service type', () => {
  for (const serviceType of ['BACKEND', 'FRONTEND', 'FLUTTER_ANDROID']) for (const buildEngine of ['LOCAL', 'GITHUB']) {
    const model = normalizeService({ name: 'service', serviceKey: 'sample', serviceType, buildEngine, configJson: '{}' });
    const request = serviceRequest(model);
    assert.equal(request.buildEngine, buildEngine);
    assert.equal(normalizeService(request).buildEngine, buildEngine);
    assert.equal(JSON.parse(request.configJson).buildEngine, undefined);
  }
});

test('switching only the engine preserves unknown JSON and all existing signing configuration', () => {
  const profile = { enabled: true, applicationId: 'com.example.pre', expectedCertificateSha256: 'a'.repeat(64), signingProfile: 'pre-signing', buildMode: 'DEBUG', allowDebugSigning: true, dartDefines: { CUSTOM_ENDPOINT: 'http://example.test' }, environmentVariables: { APP_BUILD_MODE: 'false' }, futureSigningOptions: { v4: true } };
  const config = { unknownArray: ['x', { future: 2 }], signing: { provider: 'registered', keyReference: 'signing-ref' }, mobileBuild: { outputFormat: 'APK', flutterVersion: '3.47.3', applicationId: 'com.legacy.app', signingProfile: 'legacy-signing', expectedCertificateSha256: 'BB:'.repeat(31) + 'BB', futureMobileOption: { enabled: true }, environments: { test: profile } } };
  const raw = { name: 'app', serviceType: 'FLUTTER_ANDROID', buildEngine: 'GITHUB', configJson: JSON.stringify(config) };
  const model = normalizeService(raw);
  model.buildEngine = 'LOCAL';
  const request = serviceRequest(model);
  const saved = JSON.parse(request.configJson);
  assert.equal(request.buildEngine, 'LOCAL');
  assert.deepEqual(saved.mobileBuild, config.mobileBuild);
  assert.deepEqual(saved.signing, config.signing);
  assert.deepEqual(saved.unknownArray, config.unknownArray);
  assert.equal(raw.configJson, JSON.stringify(config));
  model.buildEngine = 'GITHUB';
  assert.deepEqual(JSON.parse(serviceRequest(model).configJson), saved);
});

test('editable config overrides old values while untouched extensions are retained', () => {
  const model = normalizeService({ name: 'frontend', serviceType: 'FRONTEND', buildEngine: 'LOCAL', configJson: JSON.stringify({ buildTool: 'npm', futureDocker: { cache: ['one', 'two'] }, registryCredential: 'registry-ref' }) });
  model.buildTool = 'pnpm';
  const saved = JSON.parse(serviceRequest(model).configJson);
  assert.equal(saved.buildTool, 'pnpm');
  assert.equal(saved.registryCredential, 'registry-ref');
  assert.deepEqual(saved.futureDocker, { cache: ['one', 'two'] });
});

test('partial setting updates do not erase an existing mobile config that was not provided', () => {
  const config = { mobileBuild: { signingProfile: 'preserved', unknownOption: true }, future: 'preserved' };
  const saved = JSON.parse(serviceRequest({ name: 'app', serviceType: 'FLUTTER_ANDROID', buildEngine: 'LOCAL', configJson: JSON.stringify(config) }).configJson);
  assert.deepEqual(saved, config);
});
