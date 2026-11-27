'use strict';
const vscode = require('vscode');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {execFile} = require('node:child_process');

function androidProject(root) {
  if (!fs.existsSync(path.join(root, 'gradlew'))) return false;
  // Inspect a small bounded set of Gradle files, never execute their contents.
  return ['build.gradle', 'build.gradle.kts', 'app/build.gradle', 'app/build.gradle.kts', 'gradle/libs.versions.toml']
    .some(file => {
      try {
        const name = path.join(root, file);
        return fs.statSync(name).size <= 1024 * 1024 && /com\.android\.(application|library)|android\s*\{|android-application/.test(fs.readFileSync(name, 'utf8'));
      } catch (_) { return false; }
    });
}

const SAFE_GRADLE_TASKS = [
  'assembleDebug',
  'testDebugUnitTest',
  'testDebugMediumUnitTest',
  'lintDebug',
  'clean',
];

function findAndroidRoots(root, depth = 3) {
  const roots = [];
  let budget = 256;
  function walk(dir, remaining) {
    if (--budget < 0) return;
    if (androidProject(dir)) { roots.push(dir); return; }
    if (remaining < 1) return;
    try {
      for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
        if (budget < 0) break;
        if (entry.isDirectory() && !entry.name.startsWith('.') &&
            !['build', 'node_modules', 'vendor', 'target', 'dist'].includes(entry.name)) {
          walk(path.join(dir, entry.name), remaining - 1);
        }
      }
    } catch (_) {}
  }
  walk(root, depth);
  return roots;
}

function readProcMemInfoMb() {
  let fd = null;
  try {
    fd = fs.openSync('/proc/meminfo', 'r');
    const buf = Buffer.alloc(65536);
    const bytesRead = fs.readSync(fd, buf, 0, 65536, 0);
    const text = buf.subarray(0, bytesRead).toString('utf8');
    const totalMatch = /^MemTotal:\s*(\d+)\s*kB/m.exec(text);
    if (totalMatch) {
      const availMatch = /^MemAvailable:\s*(\d+)\s*kB/m.exec(text);
      return {
        totalMb: Math.floor(Number(totalMatch[1]) / 1024),
        availableMb: availMatch ? Math.floor(Number(availMatch[1]) / 1024) : -1,
        source: 'proc-meminfo',
      };
    }
  } catch (_) {
  } finally {
    if (fd !== null) {
      try { fs.closeSync(fd); } catch (_) {}
    }
  }
  try {
    const totalBytes = os.totalmem();
    const freeBytes = os.freemem();
    if (Number.isFinite(totalBytes) && totalBytes > 0) {
      return {
        totalMb: Math.floor(totalBytes / (1024 * 1024)),
        availableMb: Number.isFinite(freeBytes) && freeBytes >= 0 ? Math.floor(freeBytes / (1024 * 1024)) : -1,
        source: 'os',
      };
    }
  } catch (_) {}
  return {totalMb: 0, availableMb: -1, source: 'unknown'};
}

function recommendGradleOptions(envVars = process.env, options = {}) {
  const env = envVars && typeof envVars === 'object' ? envVars : {};
  const opts = options && typeof options === 'object' ? options : {};
  let mem = null;

  if (opts.memInfo && typeof opts.memInfo === 'object') {
    const rawTotal = Number(opts.memInfo.totalMb);
    const rawAvail = Number(opts.memInfo.availableMb);
    if (Number.isFinite(rawTotal) && rawTotal >= 0) {
      mem = {
        totalMb: Math.floor(rawTotal),
        availableMb: Number.isFinite(rawAvail) && rawAvail >= 0 ? Math.floor(rawAvail) : -1,
        source: typeof opts.memInfo.source === 'string' && opts.memInfo.source ? opts.memInfo.source : 'override',
      };
    }
  }
  if (!mem && env.KUCODE_GRADLE_TOTAL_MEM_MB !== undefined && env.KUCODE_GRADLE_TOTAL_MEM_MB !== '') {
    const rawTotal = Number(env.KUCODE_GRADLE_TOTAL_MEM_MB);
    const rawAvail = Number(env.KUCODE_GRADLE_AVAIL_MEM_MB);
    if (Number.isFinite(rawTotal) && rawTotal >= 0) {
      mem = {
        totalMb: Math.floor(rawTotal),
        availableMb: Number.isFinite(rawAvail) && rawAvail >= 0 ? Math.floor(rawAvail) : -1,
        source: 'env',
      };
    }
  }
  if (!mem) {
    mem = readProcMemInfoMb();
  }

  const {totalMb, availableMb, source} = mem;
  let tier = 'standard';
  let maxWorkers = 2;
  let jvmArgs = '-Xmx2048m -Dfile.encoding=UTF-8';

  if (totalMb > 0 && (totalMb < 4096 || (availableMb >= 0 && availableMb < 2048))) {
    tier = 'low';
    maxWorkers = 1;
    jvmArgs = '-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8';
  } else if (totalMb >= 12288 && (availableMb < 0 || availableMb >= 6144)) {
    tier = 'high';
    maxWorkers = 3;
    jvmArgs = '-Xmx3072m -Dfile.encoding=UTF-8';
  }

  return {
    totalMb,
    availableMb,
    source,
    tier,
    maxWorkers,
    jvmArgs,
    flags: ['--no-daemon', '--max-workers=' + maxWorkers],
    gradleProperty: 'org.gradle.jvmargs=' + jvmArgs,
    modifiesGradleProperties: false,
  };
}

function gradleArgs(tasks, envVars = process.env, options = {}) {
  const taskList = Array.isArray(tasks) ? tasks : [];
  const env = envVars && typeof envVars === 'object' ? envVars : {};
  const opts = options && typeof options === 'object' ? options : {};
  const args = [];

  const hasDaemonFlag = taskList.some(t => t === '--no-daemon' || t === '--daemon');
  const hasMaxWorkersFlag = taskList.some(t => t === '--max-workers' || String(t).startsWith('--max-workers='));
  const hasJvmArgsFlag = taskList.some(t => String(t).startsWith('-Dorg.gradle.jvmargs='));

  const rec = recommendGradleOptions(env, opts);
  const explicitAutoTune = Boolean(
    (opts.memInfo && typeof opts.memInfo === 'object') ||
    (env.KUCODE_GRADLE_TOTAL_MEM_MB !== undefined && env.KUCODE_GRADLE_TOTAL_MEM_MB !== '') ||
    env.KUCODE_GRADLE_AUTO_TUNE === '1'
  );
  const applyRec = rec.tier === 'low' || (explicitAutoTune && rec.tier !== 'standard');

  if (!hasDaemonFlag) {
    args.push('--no-daemon');
  }

  if (!hasMaxWorkersFlag) {
    const rawWorkers = env.KUCODE_GRADLE_MAX_WORKERS;
    const parsedWorkers = rawWorkers !== undefined && rawWorkers !== '' ? Number(rawWorkers) : NaN;
    if (Number.isInteger(parsedWorkers) && parsedWorkers >= 1 && parsedWorkers <= 16) {
      args.push('--max-workers=' + parsedWorkers);
    } else if (applyRec) {
      args.push('--max-workers=' + rec.maxWorkers);
    } else {
      args.push('--max-workers=2');
    }
  }

  if (!hasJvmArgsFlag) {
    const customJvmArgs = typeof env.KUCODE_GRADLE_JVMARGS === 'string' ? env.KUCODE_GRADLE_JVMARGS.trim() : '';
    const hasGradleOpts = typeof env.GRADLE_OPTS === 'string' && env.GRADLE_OPTS.trim().length > 0;
    if (customJvmArgs && !customJvmArgs.includes('\0')) {
      args.push('-Dorg.gradle.jvmargs=' + customJvmArgs);
    } else if (!hasGradleOpts && applyRec) {
      args.push('-Dorg.gradle.jvmargs=' + rec.jvmArgs);
    }
  }

  const aapt2 = env.KUCODE_AAPT2;
  if (typeof aapt2 === 'string' && path.isAbsolute(aapt2) && !aapt2.includes('\0')) {
    args.push('-Pandroid.aapt2FromMavenOverride=' + aapt2);
  }
  return [...args, ...taskList];
}

function cli(args, exec = execFile, options = {}) {
  const execFn = typeof exec === 'function' ? exec : execFile;
  const timeoutMs = options && typeof options.timeoutMs === 'number' && options.timeoutMs > 0
    ? options.timeoutMs
    : 15000;
  return new Promise((resolve, reject) => {
    execFn('/usr/local/bin/kucode', args, {timeout: timeoutMs, maxBuffer: 256 * 1024}, (err, stdout) => {
      try {
        const result = JSON.parse(stdout);
        if (!result || result.version !== 1) throw new Error('Unsupported host bridge API');
        if (err && err.code !== 10 && args[0] !== 'sdk') throw new Error(result.message || 'KuCode CLI failed');
        resolve(result);
      } catch (e) { reject(e); }
    });
  });
}

function execText(binary, args) {
  return new Promise((resolve, reject) => {
    execFile(binary, args, {timeout: 15000, maxBuffer: 512 * 1024}, (err, stdout, stderr) => {
      if (err) reject(new Error((stderr || stdout || err.message || 'Command failed').trim()));
      else resolve(stdout);
    });
  });
}

async function chooseRoot() {
  if (vscode.workspace.isTrusted === false || !vscode.workspace.isTrusted) {
    throw new Error('Trust this workspace before running development tasks.');
  }
  const projects = (vscode.workspace.workspaceFolders || []).flatMap(folder =>
    findAndroidRoots(folder.uri.fsPath).map(root => ({folder, root})));
  if (!projects.length) throw new Error('No Android Gradle project detected.');
  if (projects.length === 1) return projects[0];
  const picked = await vscode.window.showQuickPick(projects.map(project => ({label: project.root, project})), {placeHolder: 'Choose an Android project'});
  return picked && picked.project;
}

function createGradleTask(project, gradleTask, id) {
  return new vscode.Task(
    {type: 'kucodeAndroid', task: gradleTask, ...(id ? {id} : {})},
    project.folder,
    'Gradle ' + gradleTask,
    'KuCode Android',
    new vscode.ProcessExecution(
      '/usr/local/bin/kucode',
      ['build', '--', path.join(project.root, 'gradlew'), ...gradleArgs([gradleTask])],
      {cwd: project.root},
    ),
  );
}

async function task(project, executable, args, label) {
  if (vscode.workspace.isTrusted === false || !vscode.workspace.isTrusted) {
    throw new Error('Workspace is not trusted.');
  }
  const approved = await vscode.window.showWarningMessage('Run ' + label + ' for ' + project.root + '?', {modal: true}, 'Run');
  if (approved !== 'Run') return false;
  const id = require('node:crypto').randomUUID();
  const t = new vscode.Task({type: 'kucodeAndroid', id}, project.folder, label, 'KuCode Android',
    new vscode.ProcessExecution('/usr/local/bin/kucode', ['build', '--', executable, ...args], {cwd: project.root}));
  return new Promise((resolve, reject) => {
    const matches = event => event.execution.task.definition.id === id;
    const cleanup = () => { processSub.dispose(); taskSub.dispose(); };
    const processSub = vscode.tasks.onDidEndTaskProcess(event => {
      if (matches(event)) { cleanup(); resolve(event.exitCode === 0); }
    });
    const taskSub = vscode.tasks.onDidEndTask(event => {
      if (matches(event)) { cleanup(); resolve(false); }
    });
    vscode.tasks.executeTask(t).then(() => {}, error => { cleanup(); reject(error); });
  });
}

async function installApk(project, {runCli = cli, pollIntervalMs = 1000, maxPolls = 960} = {}) {
  const files = await vscode.window.showOpenDialog({
    canSelectFiles: true, canSelectMany: false, filters: {APK: ['apk']}, defaultUri: vscode.Uri.file(project.root), openLabel: 'Install APK',
  });
  if (!files || !files.length) return null;
  const op = await runCli(['android', 'install', files[0].fsPath]);
  if (op && op.state === 'succeeded') return op.packageName;
  if (!op || !op.operationId) {
    throw new Error((op && op.message) || 'Android APK install did not return an operationId');
  }
  return vscode.window.withProgress({
    location: vscode.ProgressLocation.Notification, title: 'Android APK install — approve in Android notification', cancellable: true,
  }, async (progress, token) => {
    for (let polls = 0; polls < maxPolls; polls++) {
      if (token && token.isCancellationRequested) {
        await Promise.resolve(runCli(['android', 'cancel', op.operationId])).catch(() => {});
        return null;
      }
      const result = await runCli(['android', 'operation-status', op.operationId]);
      progress.report({message: result.state});
      if (result.state === 'succeeded') return result.packageName;
      if (['failed', 'timed_out', 'interrupted', 'cancelled'].includes(result.state)) {
        throw new Error(
          result.message
            ? `Android APK install ${result.state}: ${result.message}`
            : `Android APK install ${result.state}`
        );
      }
      if (polls + 1 < maxPolls) {
        await new Promise(resolve => setTimeout(resolve, pollIntervalMs));
      }
    }
    if (token && token.isCancellationRequested) {
      await Promise.resolve(runCli(['android', 'cancel', op.operationId])).catch(() => {});
      return null;
    }
    await Promise.resolve(runCli(['android', 'cancel', op.operationId])).catch(() => {});
    throw new Error('Android APK install poll timed out after ' + maxPolls + ' polls');
  });
}

async function launchWithRetry(pkg, runCli = cli, {maxAttempts = 15, delayMs = 2000} = {}) {
  return vscode.window.withProgress({
    location: vscode.ProgressLocation.Notification,
    title: 'Launching ' + pkg + ' — return focus to KuCode if Android installer is still open',
    cancellable: true,
  }, async (progress, token) => {
    let lastError = null;
    for (let attempt = 1; attempt <= maxAttempts && !token.isCancellationRequested; attempt++) {
      progress.report({message: `attempt ${attempt}/${maxAttempts}`});
      try {
        return await runCli(['android', 'launch', pkg]);
      } catch (err) {
        lastError = err;
        if (attempt < maxAttempts && !token.isCancellationRequested) {
          await new Promise(resolve => setTimeout(resolve, delayMs));
        }
      }
    }
    if (token.isCancellationRequested) return null;
    throw lastError || new Error('Failed to launch ' + pkg);
  });
}

function activate(context) {
  const refresh = () => vscode.commands.executeCommand('setContext', 'kucodeAndroid.project',
    Boolean(vscode.workspace.isTrusted) && (vscode.workspace.workspaceFolders || []).some(f => findAndroidRoots(f.uri.fsPath).length > 0));
  refresh();
  context.subscriptions.push(vscode.workspace.onDidChangeWorkspaceFolders(refresh), vscode.workspace.onDidGrantWorkspaceTrust(refresh));
  if (typeof vscode.workspace.createFileSystemWatcher === 'function') {
    const watcher = vscode.workspace.createFileSystemWatcher('**/settings.{gradle,gradle.kts}');
    context.subscriptions.push(watcher, watcher.onDidCreate(refresh), watcher.onDidDelete(refresh));
  }
  if (vscode.tasks && typeof vscode.tasks.registerTaskProvider === 'function') {
    context.subscriptions.push(vscode.tasks.registerTaskProvider('kucodeAndroid', {
      provideTasks() {
        if (vscode.workspace.isTrusted === false || !vscode.workspace.isTrusted) return [];
        const projects = (vscode.workspace.workspaceFolders || []).flatMap(folder =>
          findAndroidRoots(folder.uri.fsPath).map(root => ({folder, root})));
        return projects.flatMap(project =>
          ['assembleDebug', 'testDebugUnitTest'].map(name => createGradleTask(project, name)));
      },
      resolveTask(t) {
        return t;
      },
    }));
  }
  const register = (id, fn) => context.subscriptions.push(vscode.commands.registerCommand('kucodeAndroid.' + id, async () => {
    try {
      const folder = await chooseRoot();
      if (folder) await fn(folder);
    } catch (error) { vscode.window.showErrorMessage('KuCode Android: ' + error.message); }
  }));
  const output = vscode.window.createOutputChannel('KuCode Android');
  context.subscriptions.push(output);
  register('doctor', async () => {
    output.appendLine(JSON.stringify(await cli(['sdk', 'doctor', '--versions'], undefined, {timeoutMs: 20000}), null, 2));
    output.appendLine(JSON.stringify(await cli(['android', 'capabilities']), null, 2));
    output.show();
  });
  register('diagnostics', async () => {
    output.appendLine('=== KuCode Android Diagnostics ===');
    output.appendLine(JSON.stringify(await cli(['sdk', 'doctor', '--versions'], undefined, {timeoutMs: 20000}), null, 2));
    output.appendLine(JSON.stringify(await cli(['android', 'capabilities']), null, 2));
    try {
      output.appendLine('=== Recent Host Session Summary ===');
      output.appendLine(await execText('/usr/local/bin/kucode-logs', ['list']));
    } catch (err) {
      output.appendLine('(kucode-logs unavailable: ' + err.message + ')');
    }
    output.show();
  });
  register('installSdk', async folder => {
    const sdk = process.env.ANDROID_HOME;
    if (!sdk) throw new Error('ANDROID_HOME is not configured. Run SDK Doctor.');
    const manager = path.join(sdk, 'cmdline-tools/latest/bin/sdkmanager');
    if (!fs.existsSync(manager)) {
      await vscode.window.showInformationMessage('Install the reviewed native ARM64 Android CLI / SDK command-line tools first; see docs/android-arm64-probe.md. No x86 fallback.');
      return;
    }
    const selection = await vscode.window.showInputBox({prompt: 'Review and enter sdkmanager package IDs separated by commas', value: 'platforms;android-37,build-tools;36.0.0'});
    if (!selection) return;
    const packages = selection.split(',').map(x => x.trim()).filter(Boolean);
    if (!packages.length || !packages.every(x => /^[A-Za-z0-9_.;-]+$/.test(x) && !x.startsWith('-'))) throw new Error('Invalid SDK package ID');
    await task(folder, manager, ['--install', ...packages], 'SDK package install');
  });
  register('test', async folder => {
    await task(folder, path.join(folder.root, 'gradlew'), gradleArgs(['testDebugUnitTest']), 'Gradle testDebugUnitTest');
  });
  register('gradleTask', async folder => {
    const picked = await vscode.window.showQuickPick(SAFE_GRADLE_TASKS, {placeHolder: 'Choose a Gradle task'});
    if (!picked) return;
    await task(folder, path.join(folder.root, 'gradlew'), gradleArgs([picked]), 'Gradle ' + picked);
  });
  register('install', async folder => {
    const pkg = await installApk(folder);
    if (pkg) vscode.window.showInformationMessage('Installed ' + pkg);
  });
  register('launch', async () => {
    const pkg = await vscode.window.showInputBox({prompt: 'Installed Android package name'});
    if (pkg) await cli(['android', 'launch', pkg]);
  });
  register('buildRun', async folder => {
    if (!await task(folder, path.join(folder.root, 'gradlew'), gradleArgs(['assembleDebug']), 'Gradle assembleDebug')) return;
    const pkg = await installApk(folder);
    if (pkg) await launchWithRetry(pkg);
  });
}
function deactivate() {}

module.exports = {
  activate,
  deactivate,
  androidProject,
  findAndroidRoots,
  gradleArgs,
  recommendGradleOptions,
  cli,
  installApk,
  launchWithRetry,
};
