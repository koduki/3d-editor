'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const Module = require('node:module');
const cp = require('node:child_process');
const { promisify } = require('node:util');

const execFileAsync = promisify(cp.execFile);

const REPO_ROOT = path.resolve(__dirname, '../..');
const ASSETS_DIR = path.join(REPO_ROOT, 'android/app/src/main/assets');
const CLI_PATH = path.join(ASSETS_DIR, 'kucode-cli.cjs');
const ANDROID_EXT_PATH = path.join(ASSETS_DIR, 'kucode-android/extension.js');
const ANDROID_PKG_PATH = path.join(ASSETS_DIR, 'kucode-android/package.json');
const TOOLS_DIR = path.join(ASSETS_DIR, 'kucode-tools');
const TOOLS_EXT_PATH = path.join(TOOLS_DIR, 'extension.js');
const TOOLS_PKG_PATH = path.join(TOOLS_DIR, 'package.json');
const ACP_CLIENT_PATH = path.join(TOOLS_DIR, 'acp-client.js');
const ACP_WEBVIEW_PATH = path.join(TOOLS_DIR, 'acp-webview.js');
const SMOKE_SCRIPT_PATH = path.join(REPO_ROOT, 'scripts/aura-language-smoke.sh');

async function withLoopbackServer(handler, fn) {
  const server = http.createServer(handler);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  try {
    return await fn(port);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

async function withEnv(overrides, fn) {
  const previous = {};
  for (const [k, v] of Object.entries(overrides)) {
    previous[k] = Object.prototype.hasOwnProperty.call(process.env, k) ? process.env[k] : undefined;
    if (v === undefined || v === null) delete process.env[k];
    else process.env[k] = String(v);
  }
  try {
    return await fn();
  } finally {
    for (const [k, v] of Object.entries(previous)) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
  }
}

function loadModuleWithMockVscode(modulePath, mockVscode) {
  for (const key of [modulePath, ACP_WEBVIEW_PATH, ACP_CLIENT_PATH]) {
    delete require.cache[key];
  }
  const origLoad = Module._load;
  Module._load = function patchedLoad(request, parent, isMain) {
    if (request === 'vscode') return mockVscode;
    return origLoad.call(this, request, parent, isMain);
  };
  try {
    return require(modulePath);
  } finally {
    Module._load = origLoad;
  }
}

function makeFakeExecTool(binDir, name, scriptBody) {
  const toolPath = path.join(binDir, name);
  fs.writeFileSync(toolPath, scriptBody, { encoding: 'utf8', mode: 0o755 });
  return toolPath;
}

test('[AC-3] kucode-cli.cjs recommendGradleOptions, collectSdkVersionHints, sdk doctor zero-exec vs --versions, and build span hints without leaks', async (t) => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kucode-hardening-ac3-'));
  t.after(() => fs.rmSync(tmp, { recursive: true, force: true }));

  const cliMod = require(CLI_PATH);
  assert.equal(typeof cliMod.recommendGradleOptions, 'function');
  assert.equal(typeof cliMod.collectSdkVersionHints, 'function');

  // 1. RAM-tier Gradle recommendations (low / standard / high) without modifying gradle.properties
  const lowRec = cliMod.recommendGradleOptions({ totalMb: 3072, availableMb: 2500, source: 'test' });
  assert.equal(lowRec.tier, 'low');
  assert.equal(lowRec.maxWorkers, 1);
  assert.equal(lowRec.jvmArgs, '-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8');
  assert.deepEqual(lowRec.flags, ['--no-daemon', '--max-workers=1']);
  assert.equal(lowRec.gradleProperty, 'org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8');
  assert.equal(lowRec.modifiesGradleProperties, false);

  const lowAvailRec = cliMod.recommendGradleOptions({ totalMb: 8192, availableMb: 1500, source: 'test' });
  assert.equal(lowAvailRec.tier, 'low');
  assert.equal(lowAvailRec.maxWorkers, 1);

  const stdRec = cliMod.recommendGradleOptions({ totalMb: 8192, availableMb: 4096, source: 'test' });
  assert.equal(stdRec.tier, 'standard');
  assert.equal(stdRec.maxWorkers, 2);
  assert.equal(stdRec.jvmArgs, '-Xmx2048m -Dfile.encoding=UTF-8');
  assert.deepEqual(stdRec.flags, ['--no-daemon', '--max-workers=2']);

  const highRec = cliMod.recommendGradleOptions({ totalMb: 16384, availableMb: 8192, source: 'test' });
  assert.equal(highRec.tier, 'high');
  assert.equal(highRec.maxWorkers, 3);
  assert.equal(highRec.jvmArgs, '-Xmx3072m -Dfile.encoding=UTF-8');
  assert.deepEqual(highRec.flags, ['--no-daemon', '--max-workers=3']);

  // 2. collectSdkVersionHints reads bounded metadata strictly with zero subprocess execution
  const fakeJavaHome = path.join(tmp, 'jdk');
  fs.mkdirSync(fakeJavaHome, { recursive: true });
  fs.writeFileSync(path.join(fakeJavaHome, 'release'), 'JAVA_VERSION="21.0.5"\n', 'utf8');

  const fakeCwd = path.join(tmp, 'project');
  fs.mkdirSync(path.join(fakeCwd, 'android/gradle/wrapper'), { recursive: true });
  fs.writeFileSync(
    path.join(fakeCwd, 'android/gradle/wrapper/gradle-wrapper.properties'),
    'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.11.1-bin.zip\n',
    'utf8',
  );

  const fakeSdk = path.join(tmp, 'android-sdk');
  fs.mkdirSync(path.join(fakeSdk, 'platforms/android-35'), { recursive: true });
  fs.mkdirSync(path.join(fakeSdk, 'build-tools/35.0.0'), { recursive: true });
  const btDir = path.join(fakeSdk, 'build-tools/35.0.0');
  makeFakeExecTool(btDir, 'aapt2', '#!/bin/sh\nexit 0\n');

  const hints = await withEnv({ ANDROID_HOME: fakeSdk }, async () =>
    cliMod.collectSdkVersionHints(fakeCwd, {
      ...process.env,
      JAVA_HOME: fakeJavaHome,
      ANDROID_HOME: fakeSdk,
      KUCODE_BUILD_SHA: 'deadbeef1234',
    }),
  );
  assert.equal(hints.javaVersion, '21.0.5');
  assert.equal(hints.gradleVersion, '8.11.1');
  assert.equal(hints.androidPlatforms, 'android-35');
  assert.equal(hints.androidBuildTools, '35.0.0');
  assert.equal(hints.buildSha, 'deadbeef1234');

  // Invalid KUCODE_BUILD_SHA is omitted
  const badShaHints = await withEnv({ ANDROID_HOME: fakeSdk }, async () =>
    cliMod.collectSdkVersionHints(fakeCwd, {
      JAVA_HOME: 'relative/path',
      KUCODE_BUILD_SHA: 'bad sha with spaces!',
    }),
  );
  assert.equal(badShaHints.buildSha, undefined);
  assert.equal(badShaHints.javaVersion, undefined);

  // 3. sdk doctor default MUST perform zero subprocess calls, whereas --versions probes allowlisted tools
  const fakeBin = path.join(tmp, 'bin');
  fs.mkdirSync(fakeBin, { recursive: true });
  const markerPath = path.join(tmp, 'probe-invoked.marker');
  makeFakeExecTool(
    fakeBin,
    'git',
    `#!/bin/sh\necho "invoked" >> ${JSON.stringify(markerPath)}\necho "git version 2.47.1"\n`,
  );
  makeFakeExecTool(
    fakeBin,
    'python3',
    `#!/bin/sh\necho "invoked" >> ${JSON.stringify(markerPath)}\necho "Python 3.12.9"\n`,
  );
  makeFakeExecTool(
    fakeBin,
    'cmake',
    `#!/bin/sh\necho "error line 1" >&2\nexit 1\n`,
  );

  await withEnv({
    PATH: `${fakeBin}${path.delimiter}${path.dirname(process.execPath)}`,
    JAVA_HOME: fakeJavaHome,
    ANDROID_HOME: fakeSdk,
  }, async () => {
    const defaultDoc = await cliMod.main(['sdk', 'doctor']);
    assert.equal(defaultDoc.result.probedVersions, false);
    assert.equal(fs.existsSync(markerPath), false, 'Default sdk doctor must NOT execute child tools');
    assert.equal(defaultDoc.result.toolVersions.java, '21.0.5');
    assert.equal(typeof defaultDoc.result.memory, 'object');
    assert.equal(typeof defaultDoc.result.gradleRecommendation, 'object');

    const probedDoc = await cliMod.main(['sdk', 'doctor', '--versions']);
    assert.equal(probedDoc.result.probedVersions, true);
    assert.equal(fs.existsSync(markerPath), true, 'sdk doctor --versions must probe discovered tools');
    assert.match(probedDoc.result.toolVersions.git, /2\.47\.1/);
    assert.match(probedDoc.result.toolVersions.python3, /3\.12\.9/);
    assert.ok(
      probedDoc.result.toolVersions.cmake === undefined || probedDoc.result.toolVersions.cmake === 'probe-failed',
      `Failed probe must be omitted or 'probe-failed', got: ${probedDoc.result.toolVersions.cmake}`,
    );
  });

  // 4. kucode build merges SDK version hints into proot.command span attributes without leaking args or admin secrets
  const spans = [];
  const secretArg = 'top_secret_build_arg_9876543210';
  await withLoopbackServer(async (req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const bodyStr = Buffer.concat(chunks).toString('utf8');
      if (req.url === '/span') {
        spans.push({ raw: bodyStr, body: JSON.parse(bodyStr) });
        res.setHeader('Content-Type', 'application/json');
        res.end(JSON.stringify({ ok: true }));
      } else {
        res.end('ok');
      }
    });
  }, async (port) => {
    const prevCwd = process.cwd();
    process.chdir(fakeCwd);
    try {
      await withEnv({
        KUCODE_ADMIN_PORT: String(port),
        KUCODE_ADMIN_TOKEN: 'oracle-admin-secret-token',
        JAVA_HOME: fakeJavaHome,
        ANDROID_HOME: fakeSdk,
        KUCODE_BUILD_SHA: 'feedcafe99',
        TRACEPARENT: '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01',
      }, async () => {
        const exitCode = await cliMod.build([
          '--',
          process.execPath,
          '-e',
          'if (process.env.KUCODE_ADMIN_TOKEN || process.env.KUCODE_ADMIN_PORT) process.exit(42);',
          '--',
          '--secret=' + secretArg,
        ]);
        assert.equal(exitCode, 0);
      });
    } finally {
      process.chdir(prevCwd);
    }
  });

  assert.equal(spans.length, 1);
  assert.equal(spans[0].raw.includes(secretArg), false, 'Build command args must never leak in span payload');
  assert.equal(spans[0].raw.includes('oracle-admin-secret-token'), false);
  const attrs = spans[0].body.attributes;
  assert.equal(attrs['kucode.sdk.java_version'], '21.0.5');
  assert.equal(attrs['kucode.sdk.gradle_version'], '8.11.1');
  assert.equal(attrs['kucode.sdk.android_platforms'], 'android-35');
  assert.equal(attrs['kucode.sdk.android_build_tools'], '35.0.0');
  assert.equal(attrs['kucode.build.sha'], 'feedcafe99');
});

test('[AC-4] kucode-android/extension.js adaptive gradleArgs, installApk failure/timeout handling, and untrusted workspace gating', async (t) => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kucode-hardening-ac4-'));
  t.after(() => fs.rmSync(tmp, { recursive: true, force: true }));

  const androidProjectDir = path.join(tmp, 'app');
  fs.mkdirSync(androidProjectDir, { recursive: true });
  fs.writeFileSync(path.join(androidProjectDir, 'gradlew'), '#!/bin/sh\n', 'utf8');
  fs.writeFileSync(path.join(androidProjectDir, 'build.gradle.kts'), 'plugins { id("com.android.application") }\n', 'utf8');

  let isTrusted = true;
  let selectedFiles = [{ fsPath: path.join(androidProjectDir, 'app-debug.apk') }];
  const contexts = new Map();
  const commands = new Map();
  let taskProvider = null;

  const mockVscode = {
    ProgressLocation: { Notification: 15 },
    Uri: { file: (p) => ({ fsPath: p }) },
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
      constructor(proc, args, options) {
        this.process = proc;
        this.args = args;
        this.options = options;
      }
    },
    workspace: {
      get isTrusted() {
        return isTrusted;
      },
      workspaceFolders: [{ uri: { fsPath: androidProjectDir } }],
      onDidChangeWorkspaceFolders() { return { dispose() {} }; },
      onDidGrantWorkspaceTrust() { return { dispose() {} }; },
      createFileSystemWatcher() {
        return {
          onDidCreate() { return { dispose() {} }; },
          onDidDelete() { return { dispose() {} }; },
          dispose() {},
        };
      },
    },
    tasks: {
      registerTaskProvider(type, provider) {
        taskProvider = { type, provider };
        return { dispose() {} };
      },
    },
    commands: {
      executeCommand(cmd, key, val) {
        if (cmd === 'setContext') contexts.set(key, val);
      },
      registerCommand(id, fn) {
        commands.set(id, fn);
        return { dispose() {} };
      },
    },
    window: {
      createOutputChannel() { return { appendLine() {}, show() {}, dispose() {} }; },
      showErrorMessage() {},
      showInformationMessage() {},
      async showOpenDialog() { return selectedFiles; },
      withProgress(_opts, taskFn) {
        return taskFn({ report() {} }, { isCancellationRequested: false });
      },
    },
  };

  const ext = loadModuleWithMockVscode(ANDROID_EXT_PATH, mockVscode);
  assert.equal(typeof ext.recommendGradleOptions, 'function');
  assert.equal(typeof ext.installApk, 'function');

  // 1. Default gradleArgs(['assembleDebug'], {}) preserves exact CI output when no low-RAM or auto-tune override
  assert.deepEqual(ext.gradleArgs(['assembleDebug'], {}), ['--no-daemon', '--max-workers=2', 'assembleDebug']);

  // Low-RAM memInfo / env triggers max-workers=1 and jvmargs
  const lowArgs = ext.gradleArgs(['assembleDebug'], {}, { memInfo: { totalMb: 3072, availableMb: 2048 } });
  assert.ok(lowArgs.includes('--no-daemon'));
  assert.ok(lowArgs.includes('--max-workers=1'));
  assert.ok(lowArgs.includes('-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8'));

  // High-RAM with explicit auto-tune emits max-workers=3 and -Xmx3072m
  const highArgs = ext.gradleArgs(
    ['assembleDebug'],
    { KUCODE_GRADLE_TOTAL_MEM_MB: '16384', KUCODE_GRADLE_AVAIL_MEM_MB: '8192', KUCODE_GRADLE_AUTO_TUNE: '1' },
  );
  assert.ok(highArgs.includes('--max-workers=3'));
  assert.ok(highArgs.includes('-Dorg.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8'));

  // Explicit task flags win over recommendations
  const explicitArgs = ext.gradleArgs(
    ['--daemon', '--max-workers=4', '-Dorg.gradle.jvmargs=-Xmx4096m', 'assembleDebug'],
    { KUCODE_GRADLE_TOTAL_MEM_MB: '2048' },
  );
  assert.equal(explicitArgs.filter((a) => a.includes('max-workers')).length, 1);
  assert.ok(explicitArgs.includes('--max-workers=4'));
  assert.ok(explicitArgs.includes('-Dorg.gradle.jvmargs=-Xmx4096m'));
  assert.equal(explicitArgs.includes('--no-daemon'), false);

  // 2. installApk succeeds cleanly
  const pkgOk = await ext.installApk({ root: androidProjectDir }, {
    pollIntervalMs: 1,
    maxPolls: 5,
    runCli: async (args) => {
      if (args[1] === 'install') return { version: 1, operationId: 'op-1' };
      return { version: 1, state: 'succeeded', packageName: 'com.example.demo' };
    },
  });
  assert.equal(pkgOk, 'com.example.demo');

  // Terminal states ('failed', 'timed_out', 'interrupted', 'cancelled') must throw Error
  for (const termState of ['failed', 'timed_out', 'interrupted', 'cancelled']) {
    await assert.rejects(
      () => ext.installApk({ root: androidProjectDir }, {
        pollIntervalMs: 1,
        maxPolls: 5,
        runCli: async (args) => {
          if (args[1] === 'install') return { version: 1, operationId: 'op-' + termState };
          return { version: 1, state: termState, message: 'terminal ' + termState };
        },
      }),
      new RegExp(termState),
    );
  }

  // Poll timeout attempts best-effort cancel and throws Error
  const cancelCalls = [];
  await assert.rejects(
    () => ext.installApk({ root: androidProjectDir }, {
      pollIntervalMs: 1,
      maxPolls: 2,
      runCli: async (args) => {
        if (args[1] === 'install') return { version: 1, operationId: 'op-timeout' };
        if (args[1] === 'cancel') {
          cancelCalls.push(args);
          return { version: 1, state: 'cancelled' };
        }
        return { version: 1, state: 'awaiting_user' };
      },
    }),
    /timed out|timeout|poll/i,
  );
  assert.deepEqual(cancelCalls, [['android', 'cancel', 'op-timeout']]);

  // Cancelled dialog returns null
  selectedFiles = [];
  const cancelledDialog = await ext.installApk({ root: androidProjectDir }, {
    pollIntervalMs: 1,
    maxPolls: 2,
    runCli: async () => { throw new Error('should not call cli'); },
  });
  assert.equal(cancelledDialog, null);

  // 3. Untrusted workspace blocks activation context, task provider, and command invocation
  isTrusted = false;
  ext.activate({ subscriptions: [] });
  assert.equal(contexts.get('kucodeAndroid.project'), false);
  assert.deepEqual(taskProvider.provider.provideTasks(), []);
});

test('[AC-5] scripts/aura-language-smoke.sh supports --allow-non-aura/--ci-self-test, offline default vs --with-network, 5-col results.tsv, summary.json, and unknown flag exit 2', async (t) => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kucode-hardening-ac5-'));
  t.after(() => fs.rmSync(tmp, { recursive: true, force: true }));

  // 1. Unknown flag exits 2 cleanly
  const outBadFlag = path.join(tmp, 'out-bad-flag');
  await assert.rejects(
    () => execFileAsync('bash', [SMOKE_SCRIPT_PATH, '--allow-non-aura', '--unknown-smoke-flag', outBadFlag], {
      cwd: REPO_ROOT,
    }),
    (err) => err && err.code === 2,
  );

  // 2. Non-ARM64 host without --allow-non-aura/--ci-self-test exits 2 (on non-ARM64 runners)
  if (process.arch !== 'arm64') {
    const outNoAllow = path.join(tmp, 'out-no-allow');
    await assert.rejects(
      () => execFileAsync('bash', [SMOKE_SCRIPT_PATH, outNoAllow], { cwd: REPO_ROOT }),
      (err) => err && err.code === 2 && /NOT RUN: AURA native ARM64 is required/.test((err.stdout || '') + (err.stderr || '')),
    );
  }

  // 3. Controlled fake toolchain environment covering PASS, FAIL, NOT RUN, offline default, and 5-col TSV + summary.json
  const fakeBin = path.join(tmp, 'fake-bin');
  fs.mkdirSync(fakeBin, { recursive: true });
  // Provide only node, npm, python3 (with missing go, cargo, kotlinc, etc.) so we deterministically verify PASS + NOT RUN
  fs.symlinkSync(process.execPath, path.join(fakeBin, 'node'));
  makeFakeExecTool(fakeBin, 'npm', '#!/bin/sh\necho "10.9.2"\n');
  const sysPython = '/usr/bin/python3';
  if (fs.existsSync(sysPython)) {
    fs.symlinkSync(sysPython, path.join(fakeBin, 'python3'));
  }

  const outOffline = path.join(tmp, 'out-offline');
  let offlineExit = 0;
  try {
    await execFileAsync(
      'bash',
      [SMOKE_SCRIPT_PATH, '--ci-self-test', '--output', outOffline],
      {
        cwd: REPO_ROOT,
        env: {
          PATH: `${fakeBin}:/usr/bin:/bin`,
          HOME: tmp,
        },
      },
    );
  } catch (err) {
    offlineExit = err.code;
  }

  const resultsTsv = fs.readFileSync(path.join(outOffline, 'results.tsv'), 'utf8');
  const lines = resultsTsv.trim().split('\n');
  assert.equal(lines[0], 'test\tresult\tduration_seconds\tversion\texecution_mode');
  assert.ok(lines.length >= 10, 'Expected header + all 9 language targets in offline mode');
  for (const line of lines.slice(1)) {
    const cols = line.split('\t');
    assert.equal(cols.length, 5, `Expected 5 columns in results.tsv row: ${line}`);
    assert.ok(['PASS', 'FAIL', 'NOT RUN'].includes(cols[1]));
    if (process.arch !== 'arm64') {
      assert.equal(cols[4], 'CI/host');
    }
  }
  assert.ok(
    lines.some((l) => l.startsWith('javascript\tPASS\t')),
    `Expected javascript PASS row in results.tsv:\n${resultsTsv}`,
  );
  assert.ok(
    lines.some((l) => /\tNOT RUN\t/.test(l) && /\tnot-installed\t/.test(l)),
    `Expected missing toolchain row with NOT RUN and not-installed:\n${resultsTsv}`,
  );
  // Missing tools cause non-zero exit (1)
  if (lines.some((l) => /\tNOT RUN\t|\tFAIL\t/.test(l))) {
    assert.equal(offlineExit, 1);
  }

  const summaryOffline = JSON.parse(fs.readFileSync(path.join(outOffline, 'summary.json'), 'utf8'));
  assert.equal(summaryOffline.schema, 1);
  assert.equal(summaryOffline.networkMode, 'offline');
  assert.equal(summaryOffline.executionMode, process.arch === 'arm64' ? 'AURA' : 'CI/host');
  assert.equal(typeof summaryOffline.counts.total, 'number');
  assert.equal(
    summaryOffline.counts.pass + summaryOffline.counts.fail + summaryOffline.counts.notRun,
    summaryOffline.counts.total,
  );
  assert.ok(Array.isArray(summaryOffline.results) && summaryOffline.results.length === summaryOffline.counts.total);
  assert.equal(summaryOffline.results.some((r) => r.test === 'network'), false);

  // 4. --with-network hits local loopback URL via KUCODE_SMOKE_NETWORK_URL without touching internet
  const networkHits = [];
  await withLoopbackServer((req, res) => {
    networkHits.push(req.url);
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('smoke-net-ok');
  }, async (port) => {
    const outNet = path.join(tmp, 'out-with-network');
    try {
      await execFileAsync(
        'bash',
        [SMOKE_SCRIPT_PATH, '--allow-non-aura', '--with-network', '--output=' + outNet],
        {
          cwd: REPO_ROOT,
          env: {
            PATH: `${fakeBin}:/usr/bin:/bin`,
            HOME: tmp,
            KUCODE_SMOKE_NETWORK_URL: `http://127.0.0.1:${port}/health`,
          },
        },
      );
    } catch (_) {
      // Missing optional compilers still cause non-zero exit; verify network target specifically
    }

    assert.ok(networkHits.length >= 1, '--with-network must probe KUCODE_SMOKE_NETWORK_URL');
    const summaryNet = JSON.parse(fs.readFileSync(path.join(outNet, 'summary.json'), 'utf8'));
    assert.equal(summaryNet.networkMode, 'with-network');
    const netEntry = summaryNet.results.find((r) => r.test === 'network');
    assert.ok(netEntry, 'summary.json must include network result when --with-network is passed');
    assert.equal(netEntry.result, 'PASS');
  });
});

test('[AC-7] kucode-tools acp-client.js buildChildEnvironment parity and untrusted workspace gating on setup CLI & openInTerminal', async (t) => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kucode-hardening-ac7-'));
  t.after(() => fs.rmSync(tmp, { recursive: true, force: true }));

  const acpClient = require(ACP_CLIENT_PATH);
  assert.equal(typeof acpClient.detectJavaHome, 'function');
  assert.equal(typeof acpClient.buildChildEnvironment, 'function');

  // 1. detectJavaHome & buildChildEnvironment parity
  const fakeJdk = path.join(tmp, 'jdk-21');
  fs.mkdirSync(path.join(fakeJdk, 'bin'), { recursive: true });
  makeFakeExecTool(path.join(fakeJdk, 'bin'), 'java', '#!/bin/sh\nexit 0\n');

  const detectedJdk = acpClient.detectJavaHome({ JAVA_HOME: fakeJdk });
  assert.equal(detectedJdk, fakeJdk);

  const parentTp = '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01';
  const childEnv = acpClient.buildChildEnvironment(
    {
      HOME: '/root',
      JAVA_HOME: fakeJdk,
      PATH: '/usr/bin:/bin',
      TRACEPARENT: parentTp,
    },
    { CUSTOM_FLAG: 'yes' },
  );
  assert.equal(childEnv.HOME, '/root');
  assert.equal(childEnv.XDG_CACHE_HOME, '/root/.cache');
  assert.equal(childEnv.XDG_CONFIG_HOME, '/root/.config');
  assert.equal(childEnv.XDG_DATA_HOME, '/root/.local/share');
  assert.equal(childEnv.ANDROID_HOME, '/root/Android/Sdk');
  assert.equal(childEnv.ANDROID_SDK_ROOT, '/root/Android/Sdk');
  assert.equal(childEnv.JAVA_HOME, fakeJdk);
  assert.equal(childEnv.CUSTOM_FLAG, 'yes');
  assert.ok(childEnv.TRACEPARENT && childEnv.TRACEPARENT.startsWith('00-4bf92f3577b34da6a3ce929d0e0e4736-'));
  assert.notEqual(childEnv.TRACEPARENT, parentTp);

  const pathParts = childEnv.PATH.split(path.delimiter);
  assert.ok(pathParts.indexOf('/root/.local/bin') < pathParts.indexOf('/usr/bin'));
  assert.ok(pathParts.indexOf(`${fakeJdk}/bin`) < pathParts.indexOf('/usr/local/bin'));

  // Verify live AcpSessionClient connect() and terminal/create use aligned child env
  const workspace = path.join(tmp, 'workspace');
  fs.mkdirSync(workspace, { recursive: true });
  const envCaptureFile = path.join(tmp, 'agent-and-term-env.json');
  const mockAgent = path.join(tmp, 'env-check-agent.cjs');
  fs.writeFileSync(
    mockAgent,
    `'use strict';
const fs = require('node:fs');
const readline = require('node:readline');
const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
let seq = 1;
const pending = new Map();
function send(o) { process.stdout.write(JSON.stringify(o) + '\\n'); }
function callClient(method, params) {
  return new Promise((resolve, reject) => {
    const id = 'c-' + (seq++);
    pending.set(id, { resolve, reject });
    send({ jsonrpc: '2.0', id, method, params });
  });
}
rl.on('line', async (line) => {
  const m = JSON.parse(line.trim() || '{}');
  if (m.id !== undefined && m.method === undefined) {
    const p = pending.get(m.id);
    if (p) { pending.delete(m.id); p.resolve(m.result); }
    return;
  }
  if (m.method === 'initialize') {
    send({ jsonrpc: '2.0', id: m.id, result: { protocolVersion: 1, agentInfo: { name: 'env-check', version: '1' }, agentCapabilities: {}, authMethods: [] } });
  } else if (m.method === 'session/new') {
    send({ jsonrpc: '2.0', id: m.id, result: { sessionId: 's-env-1', configOptions: [] } });
  } else if (m.method === 'session/prompt') {
    const created = await callClient('terminal/create', {
      sessionId: 's-env-1',
      command: process.execPath,
      args: ['-e', 'console.log(JSON.stringify({ANDROID_HOME:process.env.ANDROID_HOME,XDG_DATA_HOME:process.env.XDG_DATA_HOME,JAVA_HOME:process.env.JAVA_HOME,PATH:process.env.PATH,TRACEPARENT:process.env.TRACEPARENT}))'],
    });
    await callClient('terminal/wait_for_exit', { sessionId: 's-env-1', terminalId: created.terminalId });
    const out = await callClient('terminal/output', { sessionId: 's-env-1', terminalId: created.terminalId });
    await callClient('terminal/release', { sessionId: 's-env-1', terminalId: created.terminalId });
    fs.writeFileSync(${JSON.stringify(envCaptureFile)}, JSON.stringify({
      agent: {
        ANDROID_HOME: process.env.ANDROID_HOME,
        XDG_DATA_HOME: process.env.XDG_DATA_HOME,
        JAVA_HOME: process.env.JAVA_HOME,
        PATH: process.env.PATH,
      },
      term: JSON.parse(out.output.trim()),
    }));
    send({ jsonrpc: '2.0', id: m.id, result: { stopReason: 'end_turn' } });
  }
});
`,
    'utf8',
  );

  await withEnv({
    HOME: '/root',
    JAVA_HOME: fakeJdk,
    ANDROID_HOME: undefined,
    XDG_DATA_HOME: undefined,
    TRACEPARENT: parentTp,
  }, async () => {
    const client = new acpClient.AcpSessionClient({
      command: process.execPath,
      args: [mockAgent],
      workspaceRoot: workspace,
    });
    try {
      await client.connect();
      await client.prompt('check env');
    } finally {
      await client.dispose();
    }
  });

  const captured = JSON.parse(fs.readFileSync(envCaptureFile, 'utf8'));
  assert.equal(captured.agent.ANDROID_HOME, '/root/Android/Sdk');
  assert.equal(captured.term.ANDROID_HOME, '/root/Android/Sdk');
  assert.equal(captured.agent.XDG_DATA_HOME, '/root/.local/share');
  assert.equal(captured.term.XDG_DATA_HOME, '/root/.local/share');
  assert.equal(captured.agent.JAVA_HOME, fakeJdk);
  assert.equal(captured.term.JAVA_HOME, fakeJdk);
  assert.ok(captured.term.TRACEPARENT && captured.term.TRACEPARENT.startsWith('00-4bf92f3577b34da6a3ce929d0e0e4736-'));

  // 2. Untrusted workspace gating on setupAntigravityCliCommand, setupCopilotCliCommand, and openInTerminal
  const createdTerminals = [];
  const commands = new Map();
  const errorMessages = [];
  const mockVscode = {
    StatusBarAlignment: { Left: 1 },
    TreeItemCollapsibleState: { None: 0 },
    TreeItem: class TreeItem { constructor(l, c) { this.label = l; this.collapsibleState = c; } },
    ThemeIcon: class ThemeIcon { constructor(id) { this.id = id; } },
    workspace: {
      isTrusted: false,
      workspaceFolders: [{ uri: { fsPath: workspace } }],
      textDocuments: [],
      getConfiguration() { return { get() {}, inspect() { return {}; } }; },
    },
    commands: {
      registerCommand(id, fn) { commands.set(id, fn); return { dispose() {} }; },
      async executeCommand() {},
    },
    window: {
      registerTreeDataProvider() { return { dispose() {} }; },
      registerWebviewViewProvider() { return { dispose() {} }; },
      createStatusBarItem() { return { show() {}, dispose() {} }; },
      createTerminal(opts) {
        const term = { ...opts, show() {}, sendText() {} };
        createdTerminals.push(term);
        return term;
      },
      showErrorMessage(msg) { errorMessages.push(msg); },
      showInformationMessage() {},
    },
  };

  const toolsExt = loadModuleWithMockVscode(TOOLS_EXT_PATH, mockVscode);
  toolsExt.activate({ subscriptions: [] });

  await commands.get('kucode.setupAntigravityCli')();
  await commands.get('kucode.setupCopilotCli')();
  assert.equal(createdTerminals.length, 0, 'Untrusted workspace must NOT create terminals for CLI setup commands');
  assert.ok(errorMessages.length >= 2, 'Untrusted workspace should inform/error when setup commands are blocked');

  const provider = new toolsExt.AcpChatViewProvider({ extensionVersion: '0.10.0' });
  await assert.rejects(
    () => provider.openInTerminal('echo should-not-run', 'Test'),
    /Trust this workspace|not trusted/i,
  );
  assert.equal(createdTerminals.length, 0, 'Untrusted workspace openInTerminal must NOT create terminal');
});

test('[AC-11] Strict kucode-android vs kucode-tools module boundary and Workspace Trust config/flag invariants', () => {
  // 1. Cross-module isolation between kucode-android and kucode-tools
  for (const file of fs.readdirSync(path.join(ASSETS_DIR, 'kucode-android'))) {
    if (!/\.(js|cjs|mjs|json)$/.test(file)) continue;
    const text = fs.readFileSync(path.join(ASSETS_DIR, 'kucode-android', file), 'utf8');
    assert.equal(text.includes('kucode-tools'), false, `kucode-android/${file} must not reference kucode-tools`);
  }
  for (const file of fs.readdirSync(TOOLS_DIR)) {
    if (!/\.(js|cjs|mjs|json)$/.test(file)) continue;
    const text = fs.readFileSync(path.join(TOOLS_DIR, file), 'utf8');
    assert.equal(text.includes('kucode-android'), false, `kucode-tools/${file} must not reference kucode-android`);
  }

  // 2. Workspace Trust manifest flags
  const androidPkg = JSON.parse(fs.readFileSync(ANDROID_PKG_PATH, 'utf8'));
  assert.equal(androidPkg.capabilities.untrustedWorkspaces.supported, false);

  const toolsPkg = JSON.parse(fs.readFileSync(TOOLS_PKG_PATH, 'utf8'));
  assert.equal(toolsPkg.capabilities.untrustedWorkspaces.supported, 'limited');
  for (const key of ['kucode.acp.defaultAgent', 'kucode.acp.permissionMode', 'kucode.acp.agents']) {
    const prop = toolsPkg.contributes.configuration.properties[key];
    assert.equal(prop.scope, 'application');
    assert.equal(prop.restricted, true);
  }
});
