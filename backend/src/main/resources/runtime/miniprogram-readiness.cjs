'use strict';
// Read-only probe: never load the SDK, run repository scripts or contact WeChat.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { createRequire } = require('node:module');

function inspect(input) {
  const result = { node: Number(process.versions.node.split('.')[0]) >= 20 ? 'OK' : 'OLD', nodeVersion: process.versions.node, sdk: 'MISSING', key: 'MISSING' };
  if (!input || typeof input.ciHome !== 'string' || typeof input.keyPath !== 'string' || typeof input.workspace !== 'string') return result;
  try {
    const home = fs.realpathSync(input.ciHome), requireCi = createRequire(path.join(home, 'package.json'));
    const sdkFile = fs.realpathSync(requireCi.resolve('miniprogram-ci/package.json'));
    if (!sdkFile.startsWith(fs.realpathSync(path.join(home, 'node_modules')) + path.sep)) throw new Error('SDK_OUTSIDE_TOOL');
    const expected = JSON.parse(fs.readFileSync(path.join(home, 'package.json'), 'utf8')).dependencies?.['miniprogram-ci'];
    const version = JSON.parse(fs.readFileSync(sdkFile, 'utf8')).version;
    if (!/^\d+\.\d+\.\d+$/.test(expected || '') || version !== expected) result.sdk = 'VERSION_MISMATCH';
    else { requireCi.resolve('miniprogram-ci'); result.sdk = 'OK'; result.sdkVersion = version; }
  } catch { /* Only fixed diagnostic codes leave this process. */ }
  try {
    const stat = fs.lstatSync(input.keyPath), keyPath = fs.realpathSync(input.keyPath);
    const workspace = fs.existsSync(input.workspace) ? fs.realpathSync(input.workspace) : path.resolve(input.workspace);
    const relative = path.relative(workspace, keyPath);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 65536) result.key = 'INVALID';
    else if (relative === '' || (!relative.startsWith('..' + path.sep) && !path.isAbsolute(relative))) result.key = 'IN_WORKSPACE';
    else if (process.platform !== 'win32' && ((stat.mode & 0o077) !== 0 || (process.getuid && stat.uid !== process.getuid()))) result.key = 'PERMISSIONS';
    else result.key = crypto.createPrivateKey(fs.readFileSync(input.keyPath)).asymmetricKeyType === 'rsa' ? 'OK' : 'INVALID';
  } catch { result.key = 'INVALID'; }
  return result;
}

if (require.main === module) {
  try {
    const input = fs.readFileSync(0, 'utf8');
    if (input.length > 32768) throw new Error('INPUT_TOO_LARGE');
    process.stdout.write(JSON.stringify(inspect(JSON.parse(input))));
  } catch { process.stdout.write(JSON.stringify({ node: 'ERROR', sdk: 'ERROR', key: 'ERROR' })); process.exitCode = 1; }
}
module.exports = { inspect };
