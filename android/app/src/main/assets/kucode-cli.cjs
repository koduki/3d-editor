#!/opt/openvscode-server/node
'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawn} = require('node:child_process');

const ZERO_TRACE_ID = '0'.repeat(32);
const ZERO_SPAN_ID = '0'.repeat(16);
const TRACEPARENT_RE = /^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/i;

function parseTraceParent(raw) {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  const match = TRACEPARENT_RE.exec(trimmed);
  if (!match) return null;
  const version = match[1].toLowerCase();
  const traceId = match[2].toLowerCase();
  const parentSpanId = match[3].toLowerCase();
  const traceFlags = match[4].toLowerCase();
  if (version === 'ff' || traceId === ZERO_TRACE_ID || parentSpanId === ZERO_SPAN_ID) {
    return null;
  }
  return {
    version,
    traceId,
    parentSpanId,
    traceFlags,
    normalized: `${version}-${traceId}-${parentSpanId}-${traceFlags}`,
  };
}

function newSpanId(avoidSpanId) {
  let id = ZERO_SPAN_ID;
  while (id === ZERO_SPAN_ID || (avoidSpanId && id === avoidSpanId)) {
    id = crypto.randomBytes(8).toString('hex');
  }
  return id;
}

function deriveChildTraceParent(raw) {
  const parsed = parseTraceParent(raw);
  if (!parsed) return null;
  const spanId = newSpanId(parsed.parentSpanId);
  return {
    traceId: parsed.traceId,
    parentSpanId: parsed.parentSpanId,
    spanId,
    traceFlags: parsed.traceFlags,
    parentTraceparent: parsed.normalized,
    traceparent: `00-${parsed.traceId}-${spanId}-${parsed.traceFlags}`,
  };
}

function request(method, route, body) {
  const port = Number(process.env.KUCODE_ADMIN_PORT);
  const token = process.env.KUCODE_ADMIN_TOKEN;
  if (!Number.isInteger(port) || port <= 0 || port > 65535 || !token) {
    return Promise.reject(new Error('KuCode host bridge unavailable'));
  }
  const payload = body ? Buffer.from(JSON.stringify(body)) : Buffer.alloc(0);
  const headers = {'X-KuCode-Token': token, 'Content-Type': 'application/json', 'Content-Length': payload.length};
  const tp = parseTraceParent(process.env.TRACEPARENT);
  if (tp) headers.traceparent = tp.normalized;
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: '127.0.0.1', port, method, path: route, timeout: 10000,
      headers,
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
        const raw = Buffer.concat(chunks).toString('utf8');
        let result;
        try {
          result = JSON.parse(raw);
        } catch (_) {
          reject(new Error('Host API HTTP ' + res.statusCode + ': ' + raw.trim().slice(0, 200)));
          return;
        }
        if (!result || typeof result !== 'object' || result.version !== 1) {
          reject(new Error('Unsupported Android host bridge version'));
          return;
        }
        resolve(result);
      });
    });
    req.on('timeout', () => req.destroy(new Error('Host request timed out')));
    req.on('error', reject);
    req.end(payload);
  });
}

function inspectTool(candidate) {
  try {
    fs.accessSync(candidate, fs.constants.X_OK);
    if (!fs.statSync(candidate).isFile()) return null;
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
  } catch (_) { return null; }
}

function findTool(name) {
  for (const dir of (process.env.PATH || '').split(path.delimiter)) {
    if (!path.isAbsolute(dir)) continue;
    const tool = inspectTool(path.join(dir, name));
    if (tool) return tool;
  }
  return null;
}

function androidBuildTools() {
  const sdk = process.env.ANDROID_HOME;
  if (!sdk || !path.isAbsolute(sdk)) return {};
  const dir = path.join(sdk, 'build-tools');
  try {
    return Object.fromEntries(fs.readdirSync(dir, {withFileTypes: true})
      .filter(entry => entry.isDirectory() && /^[0-9][0-9A-Za-z_.-]*$/.test(entry.name)).slice(0, 32)
      .map(entry => [entry.name, Object.fromEntries(['aapt2', 'zipalign', 'apksigner']
        .map(name => [name, inspectTool(path.join(dir, entry.name, name))]))]));
  } catch (_) { return {}; }
}

function androidPlatforms() {
  const sdk = process.env.ANDROID_HOME;
  if (!sdk || !path.isAbsolute(sdk)) return [];
  try {
    return fs.readdirSync(path.join(sdk, 'platforms'), {withFileTypes: true})
      .filter(entry => entry.isDirectory() && /^android-[0-9A-Za-z_.-]+$/.test(entry.name))
      .map(entry => entry.name)
      .sort()
      .slice(0, 32);
  } catch (_) { return []; }
}

// Use the existing Flight Recorder event route; never include command arguments or secrets.
async function event(level, message, traceparent) {
  const port = Number(process.env.KUCODE_ADMIN_PORT);
  const token = process.env.KUCODE_ADMIN_TOKEN;
  if (!Number.isInteger(port) || port <= 0 || port > 65535 || !token) return;
  const payload = Buffer.from(JSON.stringify({level, source: 'selfhost.build', msg: message}));
  const headers = {'X-KuCode-Token': token, 'Content-Type': 'application/json', 'Content-Length': payload.length};
  const tp = parseTraceParent(traceparent || process.env.TRACEPARENT);
  if (tp) headers.traceparent = tp.normalized;
  await new Promise(resolve => {
    const req = http.request({host: '127.0.0.1', port, path: '/event', method: 'POST', timeout: 1500,
      headers}, res => { res.resume(); res.on('end', resolve); });
    req.on('error', resolve);
    req.on('timeout', () => req.destroy(new Error('event timeout')));
    req.end(payload);
  });
}

// Post completed OTel span to Flight Recorder; never include command arguments or secrets.
async function postSpan(spanPayload, traceparent) {
  const port = Number(process.env.KUCODE_ADMIN_PORT);
  const token = process.env.KUCODE_ADMIN_TOKEN;
  if (!Number.isInteger(port) || port <= 0 || port > 65535 || !token) return;
  const payload = Buffer.from(JSON.stringify(spanPayload));
  const headers = {'X-KuCode-Token': token, 'Content-Type': 'application/json', 'Content-Length': payload.length};
  const tp = parseTraceParent(traceparent || process.env.TRACEPARENT);
  if (tp) headers.traceparent = tp.normalized;
  await new Promise(resolve => {
    const req = http.request({host: '127.0.0.1', port, path: '/span', method: 'POST', timeout: 1500,
      headers}, res => { res.resume(); res.on('end', resolve); });
    req.on('error', resolve);
    req.on('timeout', () => req.destroy(new Error('span timeout')));
    req.end(payload);
  });
}

async function build(args) {
  if (args[0] !== '--' || !args[1]) throw new Error('Usage: kucode build -- <command> [args...]');
  const label = path.basename(args[1]).replace(/[^a-zA-Z0-9_.-]/g, '_').slice(0, 64);
  const childCtx = deriveChildTraceParent(process.env.TRACEPARENT);
  const startMs = Date.now();
  const start = process.hrtime.bigint();
  await event('INFO', 'start tool=' + label, childCtx ? childCtx.parentTraceparent : null);
  const env = {...process.env};
  delete env.KUCODE_ADMIN_TOKEN;
  delete env.KUCODE_ADMIN_PORT;
  if (childCtx) {
    env.TRACEPARENT = childCtx.traceparent;
  } else {
    delete env.TRACEPARENT;
  }
  const result = await new Promise(resolve => {
    const child = spawn(args[1], args.slice(2), {stdio: 'inherit', env, shell: false});
    child.on('error', err => {
      console.error('kucode build: failed to start ' + args[1] + ': ' + err.message);
      resolve({code: 127, signal: null});
    });
    child.on('exit', (code, signal) => resolve({code: code ?? 1, signal}));
  });
  const elapsedMs = Number((process.hrtime.bigint() - start) / 1000000n);
  const endMs = Math.max(startMs, Date.now());
  await event(
    result.code === 0 ? 'INFO' : 'WARN',
    'end tool=' + label + ' exit=' + result.code + ' elapsedMs=' + elapsedMs + ' signal=' + (result.signal || 'none'),
    childCtx ? childCtx.parentTraceparent : null,
  );
  const spanPayload = {
    name: 'proot.command',
    kind: 'CLIENT',
    startMs,
    endMs,
    status: result.code === 0 ? 'OK' : 'ERROR',
    attributes: {
      'kucode.command.tool': label,
      'process.exit.code': result.code,
      'kucode.build.signal': result.signal || 'none',
    },
  };
  if (childCtx) {
    spanPayload.traceId = childCtx.traceId;
    spanPayload.spanId = childCtx.spanId;
    spanPayload.parentSpanId = childCtx.parentSpanId;
  }
  await postSpan(spanPayload, childCtx ? childCtx.parentTraceparent : null);
  return result.code;
}

async function main(args) {
  const [group, command, value, extra] = args;
  if (group === 'sdk' && command === 'doctor') {
    const names = ['java', 'javac', 'kotlinc', 'gradle', 'android', 'sdkmanager', 'mise', 'node', 'npm', 'tsc', 'python3', 'uv', 'go', 'ruby', 'bundle', 'cargo', 'gcc', 'g++', 'clang', 'cmake', 'git'];
    const tools = Object.fromEntries(names.map(name => [name, findTool(name)]));
    const buildTools = androidBuildTools();
    const platforms = androidPlatforms();
    const result = {version: 1, architecture: process.arch, buildTools, platforms, home: process.env.HOME, androidHome: process.env.ANDROID_HOME, javaHome: process.env.JAVA_HOME || 'unknown', tools};
    return {result, exit: process.arch === 'arm64' && [...Object.values(tools), ...Object.values(buildTools).flatMap(Object.values)].every(x => !x || x.abi !== 'non-aarch64-elf') ? 0 : 1};
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
  if (command === 'cancel') {
    const cancelFailed = ['failed', 'interrupted'].includes(result.state);
    return {result, exit: cancelFailed ? 1 : 0};
  }
  const failed = ['failed', 'interrupted', 'timed_out', 'cancelled'].includes(result.state);
  const pending = ['staging', 'submitting', 'submitted', 'awaiting_user'].includes(result.state);
  return {result, exit: failed ? 1 : command === 'operation-status' && pending ? 10 : 0};
}
if (require.main === module) {
  if (process.argv[2] === 'build') {
    build(process.argv.slice(3)).then(code => { process.exitCode = code; }).catch(error => { console.error(error.message); process.exitCode = 1; });
  } else main(process.argv.slice(2)).then(({result, exit}) => {
    console.log(JSON.stringify(result)); process.exitCode = exit;
  }).catch(error => {
    console.log(JSON.stringify({version: 1, state: 'failed', message: error.message})); process.exitCode = 1;
  });
}
module.exports = {findTool, main, build, parseTraceParent, deriveChildTraceParent};
