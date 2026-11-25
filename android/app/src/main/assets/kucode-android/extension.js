'use strict';
const vscode = require('vscode');
const fs = require('node:fs');
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

function findAndroidRoots(root, depth = 2) {
  const roots = [];
  let budget = 128;
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

function cli(args) {
  return new Promise((resolve, reject) => {
    execFile('/usr/local/bin/kucode', args, {timeout: 15000, maxBuffer: 256 * 1024}, (err, stdout) => {
      try {
        const result = JSON.parse(stdout);
        if (result.version !== 1) throw new Error('Unsupported host bridge API');
        if (err && err.code !== 10 && args[0] !== 'sdk') throw new Error(result.message || 'KuCode CLI failed');
        resolve(result);
      } catch (e) { reject(e); }
    });
  });
}

async function chooseRoot() {
  if (!vscode.workspace.isTrusted) throw new Error('Trust this workspace before running development tasks.');
  const projects = (vscode.workspace.workspaceFolders || []).flatMap(folder =>
    findAndroidRoots(folder.uri.fsPath).map(root => ({folder, root})));
  if (!projects.length) throw new Error('No Android Gradle project detected.');
  if (projects.length === 1) return projects[0];
  const picked = await vscode.window.showQuickPick(projects.map(project => ({label: project.root, project})), {placeHolder: 'Choose an Android project'});
  return picked && picked.project;
}

async function task(project, executable, args, label) {
  if (!vscode.workspace.isTrusted) throw new Error('Workspace is not trusted.');
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

async function installApk(project) {
  const files = await vscode.window.showOpenDialog({
    canSelectFiles: true, canSelectMany: false, filters: {APK: ['apk']}, defaultUri: vscode.Uri.file(project.root), openLabel: 'Install APK',
  });
  if (!files || !files.length) return null;
  const op = await cli(['android', 'install', files[0].fsPath]);
  return vscode.window.withProgress({
    location: vscode.ProgressLocation.Notification, title: 'Android APK install — approve in Android notification', cancellable: true,
  }, async (progress, token) => {
    while (!token.isCancellationRequested) {
      const result = await cli(['android', 'operation-status', op.operationId]);
      progress.report({message: result.state});
      if (result.state === 'succeeded') return result.packageName;
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    await cli(['android', 'cancel', op.operationId]).catch(() => {});
    return null;
  });
}

function activate(context) {
  const refresh = () => vscode.commands.executeCommand('setContext', 'kucodeAndroid.project',
    vscode.workspace.isTrusted && (vscode.workspace.workspaceFolders || []).some(f => findAndroidRoots(f.uri.fsPath).length > 0));
  refresh();
  context.subscriptions.push(vscode.workspace.onDidChangeWorkspaceFolders(refresh), vscode.workspace.onDidGrantWorkspaceTrust(refresh));
  const register = (id, fn) => context.subscriptions.push(vscode.commands.registerCommand('kucodeAndroid.' + id, async () => {
    try {
      const folder = await chooseRoot();
      if (folder) await fn(folder);
    } catch (error) { vscode.window.showErrorMessage('KuCode Android: ' + error.message); }
  }));
  const output = vscode.window.createOutputChannel('KuCode Android');
  context.subscriptions.push(output);
  register('doctor', async () => {
    output.appendLine(JSON.stringify(await cli(['sdk', 'doctor']), null, 2));
    output.appendLine(JSON.stringify(await cli(['android', 'capabilities']), null, 2));
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
    const packages = selection.split(',').map(x => x.trim());
    if (!packages.every(x => /^[A-Za-z0-9_.;-]+$/.test(x) && !x.startsWith('-'))) throw new Error('Invalid SDK package ID');
    await task(folder, manager, ['--install', ...packages], 'SDK package install');
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
    if (!await task(folder, path.join(folder.root, 'gradlew'), ['assembleDebug'], 'Gradle assembleDebug')) return;
    const pkg = await installApk(folder);
    if (pkg) await cli(['android', 'launch', pkg]);
  });
}
module.exports = {activate, deactivate() {}, androidProject, findAndroidRoots};
