'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const http = require('node:http');
const Module = require('node:module');
const root = '../android/app/src/main/assets/';
const {main, findTool, build} = require(root + 'kucode-cli.cjs');

async function withMockHostServer(handler, fn) {
  const server = http.createServer(handler);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const previousPort = process.env.KUCODE_ADMIN_PORT;
  const previousToken = process.env.KUCODE_ADMIN_TOKEN;
  process.env.KUCODE_ADMIN_PORT = String(server.address().port);
  process.env.KUCODE_ADMIN_TOKEN = 'test-token';
  try {
    return await fn(server.address().port);
  } finally {
    if (previousPort === undefined) delete process.env.KUCODE_ADMIN_PORT;
    else process.env.KUCODE_ADMIN_PORT = previousPort;
    if (previousToken === undefined) delete process.env.KUCODE_ADMIN_TOKEN;
    else process.env.KUCODE_ADMIN_TOKEN = previousToken;
    await new Promise(resolve => server.close(resolve));
  }
}

test('CLI uses authenticated versioned request, handles pending/cancel exit codes, and rejects version mismatch', async () => {
  await withMockHostServer((req, res) => {
    assert.equal(req.headers['x-kucode-token'], 'test-token');
    if (req.url === '/api/v1/android/operations/test-id') {
      res.end(JSON.stringify({version: 1, operationId: 'test-id', state: 'awaiting_user'}));
    } else if (req.url === '/api/v1/android/cancel') {
      res.end(JSON.stringify({version: 1, operationId: 'test-id', state: 'cancelled', message: 'Cancelled by caller'}));
    } else if (req.url === '/api/v1/android/capabilities') {
      res.end(JSON.stringify({version: 2, canInstall: true}));
    } else {
      res.statusCode = 403;
      res.end('Forbidden');
    }
  }, async () => {
    const pending = await main(['android', 'operation-status', 'test-id']);
    assert.equal(pending.result.state, 'awaiting_user');
    assert.equal(pending.exit, 10);

    // Successful cancellation must return exit code 0, not 1
    const cancelled = await main(['android', 'cancel', 'test-id']);
    assert.equal(cancelled.result.state, 'cancelled');
    assert.equal(cancelled.exit, 0);

    // Unsupported bridge API version must fail clearly
    await assert.rejects(() => main(['android', 'capabilities']), /Unsupported Android host bridge version/);

    // Non-JSON HTTP errors must surface status code
    await assert.rejects(() => main(['android', 'launch', 'com.example.app']), /HTTP 403/);
  });
});

test('kucode build strips admin token from child env and posts start/end telemetry events', async () => {
  const events = [];
  await withMockHostServer((req, res) => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
      if (req.url === '/event') {
        events.push(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      }
      res.end('ok');
    });
  }, async () => {
    const code = await build(['--', process.execPath, '-e',
      'if (process.env.KUCODE_ADMIN_TOKEN || process.env.KUCODE_ADMIN_PORT) process.exit(42);']);
    assert.equal(code, 0);
  });
  assert.equal(events.length, 2);
  assert.equal(events[0].source, 'selfhost.build');
  assert.match(events[0].msg, /^start tool=/);
  assert.match(events[1].msg, /^end tool=.*exit=0 elapsedMs=/);
});

test('SDK doctor inspects paths without running commands', async () => {
  const {result} = await main(['sdk', 'doctor']);
  assert.equal(result.architecture, process.arch);
  assert.equal(typeof result.tools, 'object');
  assert.ok(Array.isArray(result.platforms));
  assert.equal(findTool('unlikely-nonexistent-kucode-cli'), null);
});

test('Android extension detects nested projects, registers tasks/commands, checks API version, and retries launch', async (t) => {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'kucode-android-'));
  t.after(() => fs.rmSync(base, {recursive: true, force: true}));

  const registeredCommands = new Map();
  let registeredTaskProvider = null;
  const contexts = new Map();
  const mockVscode = {
    ProgressLocation: {Notification: 15},
    Task: class Task {
      constructor(definition, scope, name, source, execution) {
        this.definition = definition;
        this.scope = scope;
        this.name = name;
        this.source = source;
        this.execution = execution;
      }
    },
    ProcessExecution: class ProcessExecution {
      constructor(processPath, args, options) {
        this.process = processPath;
        this.args = args;
        this.options = options;
      }
    },
    workspace: {
      isTrusted: true,
      workspaceFolders: [{uri: {fsPath: base}}],
      onDidChangeWorkspaceFolders() { return {dispose() {}}; },
      onDidGrantWorkspaceTrust() { return {dispose() {}}; },
      createFileSystemWatcher() {
        return {
          onDidCreate() { return {dispose() {}}; },
          onDidDelete() { return {dispose() {}}; },
          dispose() {},
        };
      },
    },
    tasks: {
      registerTaskProvider(type, provider) {
        registeredTaskProvider = {type, provider};
        return {dispose() {}};
      },
    },
    commands: {
      executeCommand(cmd, key, val) {
        if (cmd === 'setContext') contexts.set(key, val);
      },
      registerCommand(id, fn) {
        registeredCommands.set(id, fn);
        return {dispose() {}};
      },
    },
    window: {
      createOutputChannel() { return {appendLine() {}, show() {}, dispose() {}}; },
      showErrorMessage() {},
      withProgress(_opts, taskFn) {
        return taskFn({report() {}}, {isCancellationRequested: false});
      },
    },
  };

  const extPath = require.resolve(root + 'kucode-android/extension.js');
  delete require.cache[extPath];
  const oldLoad = Module._load;
  Module._load = function(name, ...args) { return name === 'vscode' ? mockVscode : oldLoad.call(this, name, ...args); };
  let ext;
  try { ext = require(extPath); }
  finally { Module._load = oldLoad; }

  // 1. Non-Android Gradle project is ignored; nested monorepo project (depth 2) is detected
  fs.writeFileSync(path.join(base, 'gradlew'), '');
  fs.writeFileSync(path.join(base, 'build.gradle.kts'), 'plugins { java }');
  assert.equal(ext.androidProject(base), false);
  fs.writeFileSync(path.join(base, 'build.gradle.kts'), 'plugins { id("com.android.application") }');
  assert.equal(ext.androidProject(base), true);
  const nested = path.join(base, 'kucode', 'android');
  fs.mkdirSync(nested, {recursive: true});
  fs.renameSync(path.join(base, 'gradlew'), path.join(nested, 'gradlew'));
  fs.renameSync(path.join(base, 'build.gradle.kts'), path.join(nested, 'build.gradle.kts'));
  assert.deepEqual(ext.findAndroidRoots(base), [nested]);

  // 2. Gradle args include low-memory flags and optional ARM64 aapt2 override
  assert.deepEqual(ext.gradleArgs(['assembleDebug'], {}), ['--no-daemon', '--max-workers=2', 'assembleDebug']);
  assert.deepEqual(
    ext.gradleArgs(['assembleDebug'], {KUCODE_AAPT2: '/root/Android/Sdk/build-tools/36.0.0/aapt2'}),
    ['--no-daemon', '--max-workers=2', '-Pandroid.aapt2FromMavenOverride=/root/Android/Sdk/build-tools/36.0.0/aapt2', 'assembleDebug'],
  );

  // 3. Extension activates, registers all manifest commands and TaskProvider
  const pkgManifest = JSON.parse(fs.readFileSync(path.resolve(__dirname, root + 'kucode-android/package.json'), 'utf8'));
  const context = {subscriptions: []};
  ext.activate(context);
  assert.equal(contexts.get('kucodeAndroid.project'), true);
  for (const cmd of pkgManifest.contributes.commands) {
    assert.ok(registeredCommands.has(cmd.command), 'Missing registered command: ' + cmd.command);
  }
  assert.equal(registeredTaskProvider.type, 'kucodeAndroid');
  const provided = registeredTaskProvider.provider.provideTasks();
  assert.equal(provided.length, 2);
  assert.deepEqual(provided.map(t => t.definition.task), ['assembleDebug', 'testDebugUnitTest']);

  // 4. cli() rejects mismatched host bridge API versions cleanly
  await assert.rejects(
    () => ext.cli(['android', 'capabilities'], (_bin, _args, _opts, cb) => cb(null, JSON.stringify({version: 99}))),
    /Unsupported host bridge API/,
  );

  // 5. launchWithRetry retries transient focus errors and succeeds once KuCode regains focus
  let attempts = 0;
  const launched = await ext.launchWithRetry('dev.koduki.kucode.debug', async () => {
    attempts++;
    if (attempts < 3) throw new Error('Keep KuCode foreground');
    return {version: 1, state: 'launched'};
  }, {maxAttempts: 5, delayMs: 1});
  assert.equal(attempts, 3);
  assert.equal(launched.state, 'launched');
});

test('recommendGradleOptions, collectSdkVersionHints, sdk doctor --versions, and build span hints work without leaking args', async (t) => {
  const cliMod = require(root + 'kucode-cli.cjs');
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kucode-cli-hardening-'));
  t.after(() => fs.rmSync(tmp, {recursive: true, force: true}));

  const low = cliMod.recommendGradleOptions({totalMb: 3072, availableMb: 2500, source: 'test'});
  assert.equal(low.tier, 'low');
  assert.equal(low.maxWorkers, 1);
  assert.equal(low.modifiesGradleProperties, false);

  const std = cliMod.recommendGradleOptions({totalMb: 8192, availableMb: 4096, source: 'test'});
  assert.equal(std.tier, 'standard');
  assert.equal(std.maxWorkers, 2);

  const high = cliMod.recommendGradleOptions({totalMb: 16384, availableMb: 8192, source: 'test'});
  assert.equal(high.tier, 'high');
  assert.equal(high.maxWorkers, 3);

  const fakeJdk = path.join(tmp, 'jdk');
  fs.mkdirSync(fakeJdk, {recursive: true});
  fs.writeFileSync(path.join(fakeJdk, 'release'), 'JAVA_VERSION="21.0.4"\n', 'utf8');

  const projDir = path.join(tmp, 'proj');
  fs.mkdirSync(path.join(projDir, 'gradle/wrapper'), {recursive: true});
  fs.writeFileSync(
    path.join(projDir, 'gradle/wrapper/gradle-wrapper.properties'),
    'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10.2-bin.zip\n',
    'utf8',
  );

  const sdkDir = path.join(tmp, 'sdk');
  fs.mkdirSync(path.join(sdkDir, 'platforms/android-35'), {recursive: true});
  fs.mkdirSync(path.join(sdkDir, 'build-tools/35.0.0'), {recursive: true});

  const hints = cliMod.collectSdkVersionHints(projDir, {
    JAVA_HOME: fakeJdk,
    ANDROID_HOME: sdkDir,
    KUCODE_BUILD_SHA: 'abc1234',
  });
  assert.equal(hints.javaVersion, '21.0.4');
  assert.equal(hints.gradleVersion, '8.10.2');
  assert.equal(hints.androidPlatforms, 'android-35');
  assert.equal(hints.androidBuildTools, '35.0.0');
  assert.equal(hints.buildSha, 'abc1234');

  const fakeBin = path.join(tmp, 'bin');
  fs.mkdirSync(fakeBin, {recursive: true});
  const marker = path.join(tmp, 'ran.txt');
  fs.writeFileSync(path.join(fakeBin, 'git'), `#!/bin/sh\necho ran >> ${JSON.stringify(marker)}\necho "git version 2.46.0"\n`, {mode: 0o755});
  fs.writeFileSync(path.join(fakeBin, 'cmake'), '#!/bin/sh\nexit 1\n', {mode: 0o755});

  const prevPath = process.env.PATH;
  const prevJava = process.env.JAVA_HOME;
  const prevSdk = process.env.ANDROID_HOME;
  process.env.PATH = `${fakeBin}${path.delimiter}${path.dirname(process.execPath)}`;
  process.env.JAVA_HOME = fakeJdk;
  process.env.ANDROID_HOME = sdkDir;
  try {
    const defDoc = await main(['sdk', 'doctor']);
    assert.equal(defDoc.result.probedVersions, false);
    assert.equal(fs.existsSync(marker), false);
    assert.equal(defDoc.result.toolVersions.java, '21.0.4');
    assert.equal(defDoc.result.toolVersions.node, process.version);

    const probedDoc = await main(['sdk', 'doctor', '--probe-versions']);
    assert.equal(probedDoc.result.probedVersions, true);
    assert.equal(fs.existsSync(marker), true);
    assert.equal(probedDoc.result.toolVersions.git, 'git version 2.46.0');
    assert.equal(probedDoc.result.toolVersions.cmake, 'probe-failed');
  } finally {
    process.env.PATH = prevPath;
    if (prevJava === undefined) delete process.env.JAVA_HOME;
    else process.env.JAVA_HOME = prevJava;
    if (prevSdk === undefined) delete process.env.ANDROID_HOME;
    else process.env.ANDROID_HOME = prevSdk;
  }

  const spans = [];
  const prevCwd = process.cwd();
  process.chdir(projDir);
  process.env.JAVA_HOME = fakeJdk;
  process.env.ANDROID_HOME = sdkDir;
  process.env.KUCODE_BUILD_SHA = 'deadbeef';
  try {
    await withMockHostServer((req, res) => {
      const chunks = [];
      req.on('data', c => chunks.push(c));
      req.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        if (req.url === '/span') spans.push({raw: body, json: JSON.parse(body)});
        res.end('ok');
      });
    }, async () => {
      const code = await build(['--', process.execPath, '-e', 'process.exit(0)', '--', '--secret-token-xyz']);
      assert.equal(code, 0);
    });
  } finally {
    process.chdir(prevCwd);
    if (prevJava === undefined) delete process.env.JAVA_HOME;
    else process.env.JAVA_HOME = prevJava;
    if (prevSdk === undefined) delete process.env.ANDROID_HOME;
    else process.env.ANDROID_HOME = prevSdk;
    delete process.env.KUCODE_BUILD_SHA;
  }
  assert.equal(spans.length, 1);
  assert.equal(spans[0].raw.includes('--secret-token-xyz'), false);
  assert.equal(spans[0].json.attributes['kucode.sdk.java_version'], '21.0.4');
  assert.equal(spans[0].json.attributes['kucode.sdk.gradle_version'], '8.10.2');
  assert.equal(spans[0].json.attributes['kucode.build.sha'], 'deadbeef');
});

test('Android extension handles adaptive gradleArgs, installApk states/cancellation/timeouts, and untrusted workspace gating', async (t) => {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'kucode-android-hardening-'));
  t.after(() => fs.rmSync(base, {recursive: true, force: true}));
  fs.writeFileSync(path.join(base, 'gradlew'), '');
  fs.writeFileSync(path.join(base, 'build.gradle.kts'), 'plugins { id("com.android.application") }');

  let isTrusted = true;
  let cancelRequested = false;
  let selectedFiles = [{fsPath: path.join(base, 'app-debug.apk')}];
  const errorMessages = [];
  const registeredCommands = new Map();
  let taskProvider = null;

  const mockVscode = {
    ProgressLocation: {Notification: 15},
    Uri: {file: p => ({fsPath: p})},
    Task: class Task { constructor(d, s, n, src, ex) { this.definition = d; this.scope = s; this.name = n; this.source = src; this.execution = ex; } },
    ProcessExecution: class ProcessExecution { constructor(p, a, o) { this.process = p; this.args = a; this.options = o; } },
    workspace: {
      get isTrusted() { return isTrusted; },
      workspaceFolders: [{uri: {fsPath: base}}],
      onDidChangeWorkspaceFolders() { return {dispose() {}}; },
      onDidGrantWorkspaceTrust() { return {dispose() {}}; },
    },
    tasks: {
      registerTaskProvider(_type, provider) { taskProvider = provider; return {dispose() {}}; },
    },
    commands: {
      executeCommand() {},
      registerCommand(id, fn) { registeredCommands.set(id, fn); return {dispose() {}}; },
    },
    window: {
      createOutputChannel() { return {appendLine() {}, show() {}, dispose() {}}; },
      showErrorMessage(msg) { errorMessages.push(msg); },
      showInformationMessage() {},
      async showOpenDialog() { return selectedFiles; },
      withProgress(_opts, fn) {
        return fn({report() {}}, {get isCancellationRequested() { return cancelRequested; }});
      },
    },
  };

  const extPath = require.resolve(root + 'kucode-android/extension.js');
  delete require.cache[extPath];
  const oldLoad = Module._load;
  Module._load = function(name, ...args) { return name === 'vscode' ? mockVscode : oldLoad.call(this, name, ...args); };
  let ext;
  try { ext = require(extPath); }
  finally { Module._load = oldLoad; }

  // Adaptive gradleArgs
  const lowArgs = ext.gradleArgs(['assembleDebug'], {}, {memInfo: {totalMb: 2048, availableMb: 1024}});
  assert.deepEqual(lowArgs, ['--no-daemon', '--max-workers=1', '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8', 'assembleDebug']);

  const envWorkers = ext.gradleArgs(['assembleDebug'], {KUCODE_GRADLE_MAX_WORKERS: '4', KUCODE_GRADLE_JVMARGS: '-Xmx1536m'});
  assert.deepEqual(envWorkers, ['--no-daemon', '--max-workers=4', '-Dorg.gradle.jvmargs=-Xmx1536m', 'assembleDebug']);

  const gradleOptsOverride = ext.gradleArgs(['assembleDebug'], {GRADLE_OPTS: '-Xmx2048m'}, {memInfo: {totalMb: 2048, availableMb: 1024}});
  assert.deepEqual(gradleOptsOverride, ['--no-daemon', '--max-workers=1', 'assembleDebug']);

  // installApk cancellation
  cancelRequested = true;
  const cancels = [];
  const cancelledRes = await ext.installApk({root: base}, {
    pollIntervalMs: 1,
    maxPolls: 3,
    runCli: async (args) => {
      if (args[1] === 'install') return {version: 1, operationId: 'op-c'};
      if (args[1] === 'cancel') { cancels.push(args); return {version: 1, state: 'cancelled'}; }
      return {version: 1, state: 'awaiting_user'};
    },
  });
  assert.equal(cancelledRes, null);
  assert.deepEqual(cancels, [['android', 'cancel', 'op-c']]);
  cancelRequested = false;

  // installApk terminal states
  for (const state of ['failed', 'timed_out', 'interrupted', 'cancelled']) {
    await assert.rejects(
      () => ext.installApk({root: base}, {
        pollIntervalMs: 1,
        maxPolls: 2,
        runCli: async (args) => args[1] === 'install'
          ? {version: 1, operationId: 'op-' + state}
          : {version: 1, state},
      }),
      new RegExp(state),
    );
  }

  // Untrusted workspace gating
  isTrusted = false;
  ext.activate({subscriptions: []});
  assert.deepEqual(taskProvider.provideTasks(), []);
  await registeredCommands.get('kucodeAndroid.doctor')();
  assert.ok(errorMessages.some(m => /Trust this workspace/.test(m)));
});
