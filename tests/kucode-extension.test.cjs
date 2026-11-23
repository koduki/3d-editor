'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const Module = require('node:module');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const extDir = path.resolve(__dirname, '../android/app/src/main/assets/kucode-tools');
const extensionPath = path.join(extDir, 'extension.js');
const packageJsonPath = path.join(extDir, 'package.json');
const iconSvgPath = path.join(extDir, 'icon.svg');

function loadExtensionWithMockVscode(mockVscode) {
  delete require.cache[extensionPath];
  const origLoad = Module._load;
  Module._load = function patchedLoad(request, parent, isMain) {
    if (request === 'vscode') return mockVscode;
    return origLoad.call(this, request, parent, isMain);
  };
  try {
    return require(extensionPath);
  } finally {
    Module._load = origLoad;
  }
}

function createMockVscode() {
  const commands = new Map();
  const executedCommands = [];
  const configUpdates = [];
  const openedDocs = [];
  const clipboardWrites = [];
  const infoMessages = [];
  const errorMessages = [];
  const treeProviders = new Map();
  const statusItems = [];
  let quickPickResolver = null;

  const vscode = {
    StatusBarAlignment: { Left: 1, Right: 2 },
    ProgressLocation: { Notification: 15 },
    ConfigurationTarget: { Global: 1 },
    TreeItemCollapsibleState: { None: 0 },
    TreeItem: class TreeItem {
      constructor(label, collapsibleState) {
        this.label = label;
        this.collapsibleState = collapsibleState;
      }
    },
    ThemeIcon: class ThemeIcon {
      constructor(id) {
        this.id = id;
      }
    },
    commands: {
      registerCommand(id, handler) {
        commands.set(id, handler);
        return { dispose() {} };
      },
      async executeCommand(id) {
        executedCommands.push(id);
      },
    },
    window: {
      registerTreeDataProvider(viewId, provider) {
        treeProviders.set(viewId, provider);
        return { dispose() {} };
      },
      createStatusBarItem(alignment, priority) {
        const item = { alignment, priority, shown: false, show() { this.shown = true; }, dispose() {} };
        statusItems.push(item);
        return item;
      },
      async withProgress(_opts, task) {
        return await task();
      },
      async showTextDocument(doc) {
        openedDocs.push(doc);
      },
      async showQuickPick(items) {
        return quickPickResolver ? quickPickResolver(items) : undefined;
      },
      showInformationMessage(msg) {
        infoMessages.push(msg);
      },
      showErrorMessage(msg) {
        errorMessages.push(msg);
      },
    },
    workspace: {
      async openTextDocument(opts) {
        return opts;
      },
      getConfiguration() {
        return {
          async update(key, value, target) {
            configUpdates.push({ key, value, target });
          },
        };
      },
    },
    env: {
      clipboard: {
        async writeText(text) {
          clipboardWrites.push(text);
        },
      },
    },
  };

  return {
    vscode,
    commands,
    executedCommands,
    configUpdates,
    openedDocs,
    clipboardWrites,
    infoMessages,
    errorMessages,
    treeProviders,
    statusItems,
    setQuickPick(fn) {
      quickPickResolver = fn;
    },
  };
}

test('extension.js passes node --check and manifest/icon are valid', () => {
  const syntax = spawnSync(process.execPath, ['--check', extensionPath], { encoding: 'utf8' });
  assert.equal(syntax.status, 0, syntax.stderr);

  const pkg = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8'));
  assert.equal(pkg.name, 'kucode-tools');
  assert.equal(pkg.main, './extension.js');
  assert.ok(Array.isArray(pkg.contributes.commands) && pkg.contributes.commands.length >= 8);

  const svg = fs.readFileSync(iconSvgPath, 'utf8');
  assert.match(svg, /<svg\b/);
});

test('activate registers all manifest commands, sidebar tree provider, and status bar item', () => {
  const pkg = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8'));
  const mock = createMockVscode();
  const ext = loadExtensionWithMockVscode(mock.vscode);
  const context = { subscriptions: [] };

  ext.activate(context);

  for (const cmd of pkg.contributes.commands) {
    assert.ok(mock.commands.has(cmd.command), `Missing command handler for ${cmd.command}`);
  }
  assert.ok(mock.treeProviders.has('kucode.menuView'));
  const provider = mock.treeProviders.get('kucode.menuView');
  const entries = provider.getChildren();
  assert.ok(entries.length >= 7);
  for (const entry of entries) {
    const item = provider.getTreeItem(entry);
    assert.ok(mock.commands.has(item.command.command), `Sidebar entry references unknown command ${item.command.command}`);
  }
  assert.equal(mock.statusItems.length, 1);
  assert.equal(mock.statusItems[0].shown, true);
  assert.equal(mock.statusItems[0].command, 'kucode.openMenu');
});

test('commands invoke authenticated host endpoints and apply spatial scale & optical theme presets', async (t) => {
  const received = [];
  const expectedToken = 'test-admin-token-42';
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const body = Buffer.concat(chunks).toString('utf8');
      if (req.headers['x-kucode-token'] !== expectedToken) {
        res.writeHead(403, { 'Content-Type': 'text/plain' });
        res.end('Forbidden');
        return;
      }
      received.push({ method: req.method, url: req.url, body });
      if (req.method === 'GET' && req.url === '/diagnostics') {
        res.writeHead(200, { 'Content-Type': 'text/plain' });
        res.end('# Diagnostics OK');
      } else if (req.method === 'GET' && req.url === '/logs') {
        res.writeHead(200, { 'Content-Type': 'text/plain' });
        res.end('boot log');
      } else {
        res.writeHead(200, { 'Content-Type': 'text/plain' });
        res.end('ok');
      }
    });
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());

  const prevPort = process.env.KUCODE_ADMIN_PORT;
  const prevToken = process.env.KUCODE_ADMIN_TOKEN;
  process.env.KUCODE_ADMIN_PORT = String(server.address().port);
  process.env.KUCODE_ADMIN_TOKEN = expectedToken;
  t.after(() => {
    if (prevPort === undefined) delete process.env.KUCODE_ADMIN_PORT;
    else process.env.KUCODE_ADMIN_PORT = prevPort;
    if (prevToken === undefined) delete process.env.KUCODE_ADMIN_TOKEN;
    else process.env.KUCODE_ADMIN_TOKEN = prevToken;
  });

  const mock = createMockVscode();
  const ext = loadExtensionWithMockVscode(mock.vscode);
  ext.activate({ subscriptions: [] });

  await mock.commands.get('kucode.runDiagnostics')();
  assert.deepEqual(mock.openedDocs[0], { content: '# Diagnostics OK', language: 'markdown' });
  assert.equal(mock.clipboardWrites[0], '# Diagnostics OK');
  assert.ok(received.some((r) => r.method === 'GET' && r.url === '/diagnostics'));
  assert.ok(received.some((r) => r.method === 'POST' && r.url === '/copy' && r.body === '# Diagnostics OK'));

  await mock.commands.get('kucode.openLogs')();
  assert.deepEqual(mock.openedDocs[1], { content: 'boot log', language: 'plaintext' });

  await mock.commands.get('kucode.openManager')();
  await mock.commands.get('kucode.requestStorage')();
  assert.ok(received.some((r) => r.method === 'POST' && r.url === '/open-boot'));
  assert.ok(received.some((r) => r.method === 'POST' && r.url === '/storage-permission'));

  mock.setQuickPick((items) => items[2]); // 特大
  await mock.commands.get('kucode.selectDisplayScale')();
  assert.ok(mock.configUpdates.some((u) => u.key === 'window.zoomLevel' && u.value === 1.0));
  assert.ok(mock.configUpdates.some((u) => u.key === 'editor.fontSize' && u.value === 18));

  mock.setQuickPick((items) => items[1]); // VST
  await mock.commands.get('kucode.selectOpticalTheme')();
  assert.ok(mock.configUpdates.some((u) => u.key === 'workbench.colorCustomizations' && u.value['editor.background'] === '#12141a'));

  // Missing port / wrong token fails cleanly
  process.env.KUCODE_ADMIN_TOKEN = 'bad-token';
  await assert.rejects(ext.callHost('GET', '/logs'), /HTTP 403/);
  delete process.env.KUCODE_ADMIN_PORT;
  await assert.rejects(ext.callHost('GET', '/logs'), /KUCODE_ADMIN_PORT is not configured/);
});
