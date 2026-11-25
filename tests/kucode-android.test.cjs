'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const http = require('node:http');
const Module = require('node:module');
const root = '../android/app/src/main/assets/';
const {main, findTool} = require(root + 'kucode-cli.cjs');

test('CLI uses authenticated versioned request and reports pending status', async () => {
  const server = http.createServer((req, res) => {
    assert.equal(req.headers['x-kucode-token'], 'test-token');
    assert.equal(req.url, '/api/v1/android/operations/test-id');
    res.end(JSON.stringify({version: 1, operationId: 'test-id', state: 'awaiting_user'}));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const previousPort = process.env.KUCODE_ADMIN_PORT, previousToken = process.env.KUCODE_ADMIN_TOKEN;
  process.env.KUCODE_ADMIN_PORT = String(server.address().port);
  process.env.KUCODE_ADMIN_TOKEN = 'test-token';
  try {
    const {result, exit} = await main(['android', 'operation-status', 'test-id']);
    assert.equal(result.state, 'awaiting_user');
    assert.equal(exit, 10);
  } finally {
    if (previousPort === undefined) delete process.env.KUCODE_ADMIN_PORT; else process.env.KUCODE_ADMIN_PORT = previousPort;
    if (previousToken === undefined) delete process.env.KUCODE_ADMIN_TOKEN; else process.env.KUCODE_ADMIN_TOKEN = previousToken;
    await new Promise(resolve => server.close(resolve));
  }
});

test('SDK doctor inspects paths without running commands', async () => {
  const {result} = await main(['sdk', 'doctor']);
  assert.equal(result.architecture, process.arch);
  assert.equal(typeof result.tools, 'object');
  assert.equal(findTool('unlikely-nonexistent-kucode-cli'), null);
});

test('Android detection ignores non-Android Gradle projects', () => {
  const oldLoad = Module._load;
  Module._load = function(name, ...args) { return name === 'vscode' ? {} : oldLoad.call(this, name, ...args); };
  let project;
  try { project = require(root + 'kucode-android/extension.js').androidProject; }
  finally { Module._load = oldLoad; }
  const base = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'kucode-android-'));
  try {
    fs.writeFileSync(path.join(base, 'gradlew'), '');
    fs.writeFileSync(path.join(base, 'build.gradle.kts'), 'plugins { java }');
    assert.equal(project(base), false);
    fs.writeFileSync(path.join(base, 'build.gradle.kts'), 'plugins { id("com.android.application") }');
    assert.equal(project(base), true);
  } finally { fs.rmSync(base, {recursive: true, force: true}); }
});
