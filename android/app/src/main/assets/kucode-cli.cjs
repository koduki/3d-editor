#!/opt/openvscode-server/node
'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

function request(method, route, body) {
  const port = Number(process.env.KUCODE_ADMIN_PORT);
  const token = process.env.KUCODE_ADMIN_TOKEN;
  if (!Number.isInteger(port) || port <= 0 || port > 65535 || !token) {
    return Promise.reject(new Error('KuCode host bridge unavailable'));
  }
  const payload = body ? Buffer.from(JSON.stringify(body)) : Buffer.alloc(0);
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: '127.0.0.1', port, method, path: route, timeout: 10000,
      headers: {'X-KuCode-Token': token, 'Content-Type': 'application/json', 'Content-Length': payload.length},
    }, res => {
      let length = 0;
      const chunks = [];
      res.on('data', chunk => {
        length += chunk.length;
        if (length > 65536) { res.destroy(new Error('Host response too large')); return; }
        chunks.push(chunk);
      });
      res.on('error', reject);
      res.on('end', () => {
        try {
          const result = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          if (result.version !== 1) throw new Error('Unsupported Android host bridge version');
          resolve(result);
        } catch (e) { reject(e); }
      });
    });
    req.on('timeout', () => req.destroy(new Error('Host request timed out')));
    req.on('error', reject);
    req.end(payload);
  });
}

function findTool(name) {
  for (const dir of (process.env.PATH || '').split(path.delimiter)) {
    // Never search relative directories/project-local executables.
    if (!path.isAbsolute(dir)) continue;
    const candidate = path.join(dir, name);
    try {
      fs.accessSync(candidate, fs.constants.X_OK);
      if (!fs.statSync(candidate).isFile()) continue;
      const real = fs.realpathSync(candidate);
      const fd = fs.openSync(real, 'r');
      let abi = 'script-or-unknown';
      try {
        const header = Buffer.alloc(20);
        if (fs.readSync(fd, header, 0, 20, 0) === 20 && header.subarray(0, 4).equals(Buffer.from([0x7f, 69, 76, 70]))) {
          abi = header[4] === 2 && header[5] === 1 && header.readUInt16LE(18) === 183 ? 'aarch64' : 'non-aarch64-elf';
        }
      } finally { fs.closeSync(fd); }
      return {path: candidate, real, abi};
    } catch (_) {}
  }
  return null;
}

async function main(args) {
  const [group, command, value, extra] = args;
  if (group === 'sdk' && command === 'doctor') {
    const names = ['java', 'javac', 'gradle', 'android', 'sdkmanager', 'mise', 'node', 'npm', 'python3', 'uv', 'go', 'ruby', 'cargo', 'gcc', 'clang', 'cmake'];
    const tools = Object.fromEntries(names.map(name => [name, findTool(name)]));
    const result = {version: 1, architecture: process.arch, home: process.env.HOME, androidHome: process.env.ANDROID_HOME, javaHome: process.env.JAVA_HOME || 'unknown', tools};
    return {result, exit: process.arch === 'arm64' && Object.values(tools).every(x => !x || x.abi !== 'non-aarch64-elf') ? 0 : 1};
  }
  if (group !== 'android') throw new Error('Usage: kucode sdk doctor | kucode android capabilities|install <apk> [package]|launch <package>|operation-status <id>|cancel <id>');
  const base = '/api/v1/android/';
  let result;
  switch (command) {
    case 'capabilities': result = await request('GET', base + 'capabilities'); break;
    case 'install':
      if (!value) throw new Error('APK path required');
      result = await request('POST', base + 'install', {path: path.resolve(value), ...(extra ? {packageName: extra} : {})});
      break;
    case 'operation-status':
      if (!value) throw new Error('Operation ID required');
      result = await request('GET', base + 'operations/' + encodeURIComponent(value)); break;
    case 'cancel':
      if (!value) throw new Error('Operation ID required');
      result = await request('POST', base + 'cancel', {operationId: value}); break;
    case 'launch':
      if (!value) throw new Error('Package name required');
      result = await request('POST', base + 'launch', {packageName: value}); break;
    default: throw new Error('Unknown android command');
  }
  const failed = ['failed', 'interrupted', 'timed_out', 'cancelled'].includes(result.state);
  const pending = ['staging', 'submitting', 'submitted', 'awaiting_user'].includes(result.state);
  return {result, exit: failed ? 1 : command === 'operation-status' && pending ? 10 : 0};
}
if (require.main === module) {
  main(process.argv.slice(2)).then(({result, exit}) => {
    console.log(JSON.stringify(result)); process.exitCode = exit;
  }).catch(error => {
    console.log(JSON.stringify({version: 1, state: 'failed', message: error.message})); process.exitCode = 1;
  });
}
module.exports = {findTool, main};
