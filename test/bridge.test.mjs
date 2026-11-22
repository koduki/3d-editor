import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import WebSocket from 'ws';

import { createBridgeServer } from '../server.mjs';
import { computeUnifiedDiff } from '../public/diff.js';
import { renderMarkdown } from '../public/markdown.js';
import {
  loadProfileStore,
  saveProfileStore,
  upsertProfile,
  deleteProfile,
  deriveHttpBaseUrl,
  buildProfileWsUrl,
  diagnoseBridgeConnection,
  LEGACY_SETTINGS_KEY,
} from '../public/profiles.js';
import { createHistoryStore, deriveSessionTitle } from '../public/history-store.js';
import { DemoAcpClient } from '../public/demo-client.js';
import { renderAnsiToHtml } from '../public/console.js';
import { highlightCode, detectLanguage } from '../public/editor.js';
import { fuzzyScore } from '../public/palette.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '..');

const silentLogger = {
  log() {},
  warn() {},
  error() {},
};

function createMemoryStorage() {
  const map = new Map();
  return {
    getItem(k) {
      return map.has(k) ? map.get(k) : null;
    },
    setItem(k, v) {
      map.set(k, String(v));
    },
    removeItem(k) {
      map.delete(k);
    },
  };
}

function openWs(url, headers = {}) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, { headers });
    ws.once('open', () => resolve(ws));
    ws.once('error', reject);
  });
}

function createRpcHelper(ws) {
  let nextId = 1;
  const pending = new Map();
  const notifications = [];
  const inboundRequests = [];
  let permHandler = null;

  ws.on('message', (raw) => {
    const lines = raw.toString('utf8').split(/\r?\n/).filter(Boolean);
    for (const line of lines) {
      const msg = JSON.parse(line);
      if (!msg.method && msg.id !== undefined) {
        const waiter = pending.get(String(msg.id));
        if (waiter) {
          pending.delete(String(msg.id));
          if (msg.error) waiter.reject(new Error(msg.error.message));
          else waiter.resolve(msg.result);
        }
      } else if (msg.method && msg.id === undefined) {
        notifications.push(msg);
      } else if (msg.method && msg.id !== undefined) {
        inboundRequests.push(msg);
        if (permHandler) permHandler(msg);
      }
    }
  });

  return {
    notifications,
    inboundRequests,
    onInboundRequest(fn) {
      permHandler = fn;
    },
    request(method, params = {}) {
      const id = nextId++;
      return new Promise((resolve, reject) => {
        pending.set(String(id), { resolve, reject });
        ws.send(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
      });
    },
    notify(method, params = {}) {
      ws.send(`${JSON.stringify({ jsonrpc: '2.0', method, params })}\n`);
    },
    respond(id, result) {
      ws.send(`${JSON.stringify({ jsonrpc: '2.0', id, result })}\n`);
    },
  };
}

// ============================================================================
// Unit Tests: Unified Diff & Markdown
// ============================================================================

test('computeUnifiedDiff generates hunks with additions, deletions, new file, and identical file', () => {
  const oldText = 'line 1\nline 2\nline 3\nline 4\n';
  const newText = 'line 1\nline 2 edited\nline 3\nline 3.5\nline 4\n';
  const diff = computeUnifiedDiff(oldText, newText, 1);

  assert.equal(diff.additions, 2);
  assert.equal(diff.deletions, 1);
  assert.equal(diff.isNewFile, false);
  assert.equal(diff.hunks.length, 1);
  assert.match(diff.hunks[0].header, /^@@ -1,4 \+1,5 @@$/);

  const newFileDiff = computeUnifiedDiff(null, 'a\nb\n');
  assert.equal(newFileDiff.isNewFile, true);
  assert.equal(newFileDiff.additions, 2);
  assert.equal(newFileDiff.deletions, 0);

  const sameDiff = computeUnifiedDiff('same\n', 'same\n');
  assert.equal(sameDiff.hunks.length, 0);
});

test('renderMarkdown sanitizes HTML/XSS and renders code blocks with copy button', () => {
  const md = [
    '# Title',
    '',
    'Hello **XR** <script>alert(1)</script> [bad](javascript:alert(1)) [ok](https://example.com)',
    '',
    '- item 1',
    '- item 2',
    '',
    '```js',
    'const x = 1 < 2;',
    '```',
  ].join('\n');

  const html = renderMarkdown(md);
  assert.ok(!html.includes('<script>'));
  assert.ok(!html.includes('javascript:alert'));
  assert.ok(html.includes('<strong>XR</strong>'));
  assert.ok(html.includes('href="https://example.com"'));
  assert.ok(html.includes('<ul>'));
  assert.ok(html.includes('data-copy-code'));
  assert.ok(html.includes('const x = 1 &lt; 2;'));
});

// ============================================================================
// Unit Tests: Connection Profiles & Diagnostics (#7)
// ============================================================================

test('profiles store migrates v0.1 settings, supports CRUD, and applies query overrides', () => {
  const storage = createMemoryStorage();
  storage.setItem(
    LEGACY_SETTINGS_KEY,
    JSON.stringify({
      theme: 'vst',
      dimOnBlur: false,
      agent: 'copilot',
      customWsUrl: 'ws://100.64.1.2:8765',
      token: 'legacy-tok',
      workspaceOverride: '/tmp/blog',
    }),
  );

  const store = loadProfileStore(storage);
  assert.equal(store.theme, 'vst');
  assert.equal(store.dimOnBlur, false);
  assert.equal(store.profiles[0].agent, 'copilot');
  assert.equal(store.profiles[0].wsUrl, 'ws://100.64.1.2:8765');
  assert.equal(store.profiles[0].token, 'legacy-tok');

  const added = upsertProfile(store, {
    id: 'quest-home',
    name: 'Quest 3 Home',
    mode: 'bridge',
    wsUrl: 'ws://100.100.1.5:8765',
    agent: 'agy',
    token: 'tok-abc',
    preferredTheme: 'vst',
  });
  saveProfileStore(store, storage);

  const reloaded = loadProfileStore(storage, '?demo=1&theme=ost');
  assert.equal(reloaded.activeProfileId, 'offline-demo');
  assert.equal(reloaded.theme, 'ost');
  assert.ok(reloaded.profiles.some((p) => p.id === added.id));

  assert.equal(deleteProfile(reloaded, added.id), true);
  assert.equal(reloaded.profiles.some((p) => p.id === added.id), false);

  assert.equal(deriveHttpBaseUrl('ws://100.64.0.10:8765/?agent=agy'), 'http://100.64.0.10:8765');
  assert.equal(deriveHttpBaseUrl('wss://bridge.example.com/ws'), 'https://bridge.example.com');

  const wsUrl = buildProfileWsUrl(
    { wsUrl: 'ws://100.64.0.10:8765', agent: 'agy', token: 'sec' },
    { pageOrigin: 'http://localhost:8765' },
  );
  assert.equal(wsUrl, 'ws://100.64.0.10:8765/?agent=agy&token=sec');
});

// ============================================================================
// Unit Tests: Local Session History Store (#8)
// ============================================================================

test('historyStore persists, lists in recency order, filters, and deletes sessions', async () => {
  const storage = createMemoryStorage();
  const store = createHistoryStore({ indexedDB: null, storage });

  assert.equal(deriveSessionTitle([{ type: 'user', text: '  Hello\nXR world  ' }]), 'Hello XR world');

  await store.saveSession({
    sessionId: 'sess_1',
    profileId: 'local-aura',
    agentKey: 'agy',
    updatedAt: 1000,
    entries: [{ type: 'user', text: 'First prompt' }],
  });
  await store.saveSession({
    sessionId: 'sess_2',
    profileId: 'remote-tailscale',
    agentKey: 'mock',
    updatedAt: 2000,
    entries: [{ type: 'user', text: 'Second prompt' }],
  });

  const all = await store.listSessions();
  assert.equal(all.length, 2);
  assert.equal(all[0].sessionId, 'sess_2');
  assert.equal(all[0].title, 'Second prompt');

  const filtered = await store.listSessions({ profileId: 'local-aura' });
  assert.equal(filtered.length, 1);
  assert.equal(filtered[0].sessionId, 'sess_1');

  const loaded = await store.getSession('sess_1');
  assert.equal(loaded.entries[0].text, 'First prompt');

  await store.deleteSession('sess_1');
  assert.equal(await store.getSession('sess_1'), null);

  await store.clearAll();
  assert.equal((await store.listSessions()).length, 0);
});

// ============================================================================
// Unit Tests: Offline Demo Client (#9)
// ============================================================================

test('DemoAcpClient supports full offline ACP v1 lifecycle with Diff approval, command exec, and plan mode', async () => {
  const demo = new DemoAcpClient();
  demo._stepDelayMs = 5;

  const updates = [];
  demo.addEventListener('update', (e) => updates.push(e.detail.update));

  let permCount = 0;
  demo.addEventListener('permission', (e) => {
    permCount++;
    demo.respondPermission(e.detail.id, { outcome: 'selected', optionId: 'allow-once' });
  });

  const readyPromise = new Promise((resolve) => {
    demo.addEventListener('state', (e) => {
      if (e.detail.state === 'ready') resolve();
    });
  });

  demo.connect({ cwd: '/workspace/demo' });
  await readyPromise;

  assert.ok(demo.sessionId.startsWith('sess_demo_'));
  assert.ok(updates.some((u) => u.sessionUpdate === 'available_commands_update'));

  // 1. Blog draft edit with Unified Diff permission approval
  const res1 = await demo.sendPrompt('記事ドラフトに追記して');
  assert.equal(res1.stopReason, 'end_turn');
  assert.equal(permCount, 1);
  assert.ok(updates.some((u) => u.sessionUpdate === 'tool_call' && u.content?.[0]?.type === 'diff'));

  // 2. Shell test command approval
  const res2 = await demo.sendPrompt('/test テスト実行して');
  assert.equal(res2.stopReason, 'end_turn');
  assert.equal(permCount, 2);

  // 3. Switch to plan mode -> no permission prompt
  await demo.setMode('plan');
  const res3 = await demo.sendPrompt('設計案を考えて');
  assert.equal(res3.stopReason, 'end_turn');
  assert.equal(permCount, 2);

  demo.disconnect();
});

// ============================================================================
// Backend Security, Diagnostics, Static PWA Assets & Bridge E2E Tests (#2, #6, #7)
// ============================================================================

test('server refuses non-loopback bind without ACP_BRIDGE_TOKEN', () => {
  assert.throws(
    () =>
      createBridgeServer({
        bindHost: '0.0.0.0',
        port: 0,
        token: '',
        logger: silentLogger,
      }),
    /ACP_BRIDGE_TOKEN/,
  );
});

test('server enforces token auth, CORS diagnostics, static PWA assets, and blocks path traversal', async () => {
  const server = createBridgeServer({
    bindHost: '127.0.0.1',
    port: 0,
    token: 'super-secret-123',
    defaultAgent: 'mock',
    keepAliveMs: 2000,
    logger: silentLogger,
  });

  const { port } = await server.start();
  try {
    // CORS preflight on /api/config
    const preflight = await fetch(`http://127.0.0.1:${port}/api/config`, { method: 'OPTIONS' });
    assert.equal(preflight.status, 204);
    assert.equal(preflight.headers.get('access-control-allow-origin'), '*');

    // Health endpoint
    const healthRes = await fetch(`http://127.0.0.1:${port}/api/health`);
    assert.equal(healthRes.status, 200);
    const health = await healthRes.json();
    assert.equal(health.ok, true);
    assert.equal(health.authRequired, true);

    // /api/config rejects missing token, accepts Bearer header and query param
    const resUnauth = await fetch(`http://127.0.0.1:${port}/api/config`);
    assert.equal(resUnauth.status, 401);

    const resBearer = await fetch(`http://127.0.0.1:${port}/api/config`, {
      headers: { Authorization: 'Bearer super-secret-123' },
    });
    assert.equal(resBearer.status, 200);
    const cfg = await resBearer.json();
    assert.equal(cfg.authRequired, true);
    assert.equal(cfg.defaultAgent, 'mock');

    // Live diagnoseBridgeConnection helper (auth_required vs ok)
    const diagBad = await diagnoseBridgeConnection({
      mode: 'bridge',
      wsUrl: `ws://127.0.0.1:${port}`,
      token: 'wrong-token',
    });
    assert.equal(diagBad.ok, false);
    assert.equal(diagBad.status, 'auth_required');

    const diagOk = await diagnoseBridgeConnection({
      mode: 'bridge',
      wsUrl: `ws://127.0.0.1:${port}`,
      token: 'super-secret-123',
    });
    assert.equal(diagOk.ok, true);
    assert.equal(diagOk.status, 'ok');
    assert.ok(diagOk.latencyMs >= 1);

    // PWA / Store static assets accessible
    for (const asset of [
      '/',
      '/manifest.json',
      '/sw.js',
      '/privacy.html',
      '/.well-known/assetlinks.json',
      '/icons/icon-192.png',
      '/icons/icon-512.png',
      '/icons/icon-maskable-512.png',
    ]) {
      const r = await fetch(`http://127.0.0.1:${port}${asset}`, { method: 'HEAD' });
      assert.equal(r.status, 200, `Expected 200 for ${asset}`);
    }

    // Path traversal blocked
    const resTraversal = await fetch(`http://127.0.0.1:${port}/..%2fpackage.json`);
    assert.ok(resTraversal.status === 403 || resTraversal.status === 404);

    // WebSocket rejected without token, accepted with Bearer header
    await assert.rejects(() => openWs(`ws://127.0.0.1:${port}/?agent=mock`));
    const ws = await openWs(`ws://127.0.0.1:${port}/?agent=mock`, {
      Authorization: 'Bearer super-secret-123',
    });
    ws.close();
  } finally {
    await server.close();
  }
});

test('full ACP v1 flow: initialize, prompt, Diff approval, set_mode, warm session/resume, and ?reset=1', async () => {
  const server = createBridgeServer({
    bindHost: '127.0.0.1',
    port: 0,
    defaultAgent: 'mock',
    keepAliveMs: 10000,
    logger: silentLogger,
  });

  const { port } = await server.start();
  try {
    const ws1 = await openWs(`ws://127.0.0.1:${port}/?agent=mock`);
    const rpc1 = createRpcHelper(ws1);

    // 1. Initialize
    const initRes = await rpc1.request('initialize', {
      protocolVersion: 1,
      clientCapabilities: { session: { notices: {} } },
      clientInfo: { name: 'test-client', version: '0.3.0' },
    });
    assert.equal(initRes.protocolVersion, 1);
    assert.ok(initRes.agentCapabilities?.sessionCapabilities?.resume);

    // 2. session/new
    const newRes = await rpc1.request('session/new', {
      cwd: process.cwd(),
      mcpServers: [],
    });
    assert.ok(newRes.sessionId);
    assert.equal(newRes.modes.currentModeId, 'default');

    // 3. session/prompt with permission approval
    let permissionSeen = null;
    rpc1.onInboundRequest((req) => {
      if (req.method === 'session/request_permission') {
        permissionSeen = req;
        rpc1.respond(req.id, {
          outcome: { outcome: 'selected', optionId: 'allow-once' },
        });
      }
    });

    const promptRes = await rpc1.request('session/prompt', {
      sessionId: newRes.sessionId,
      prompt: [{ type: 'text', text: 'ブログ構成案をアップデートして' }],
    });
    assert.equal(promptRes.stopReason, 'end_turn');
    assert.ok(permissionSeen);

    const updateTypes = new Set(rpc1.notifications.map((n) => n.params?.update?.sessionUpdate));
    assert.ok(updateTypes.has('plan'));
    assert.ok(updateTypes.has('agent_thought_chunk'));
    assert.ok(updateTypes.has('tool_call'));
    assert.ok(updateTypes.has('agent_message_chunk'));
    assert.ok(updateTypes.has('usage_update'));

    // 4. Switch mode
    await rpc1.request('session/set_mode', {
      sessionId: newRes.sessionId,
      modeId: 'plan',
    });

    // 5. Disconnect & verify keep-alive + warm reattach + session/resume
    const firstPid = server.pool.get('mock::default')?.child?.pid;
    assert.ok(firstPid);

    const closedPromise = new Promise((r) => ws1.once('close', r));
    ws1.close();
    await closedPromise;

    assert.equal(server.pool.size, 1);

    const ws2 = await openWs(`ws://127.0.0.1:${port}/?agent=mock`);
    const rpc2 = createRpcHelper(ws2);

    const reInit = await rpc2.request('initialize', {
      protocolVersion: 1,
      clientCapabilities: {},
      clientInfo: { name: 'test-client', version: '0.3.0' },
    });
    assert.equal(reInit.protocolVersion, 1);

    const resumeRes = await rpc2.request('session/resume', {
      sessionId: newRes.sessionId,
      cwd: process.cwd(),
      mcpServers: [],
    });
    assert.equal(resumeRes.modes.currentModeId, 'plan');
    assert.equal(server.pool.get('mock::default')?.child?.pid, firstPid);

    ws2.close();

    // 6. ?reset=1 spawns a brand-new process
    const ws3 = await openWs(`ws://127.0.0.1:${port}/?agent=mock&reset=1`);
    const secondPid = server.pool.get('mock::default')?.child?.pid;
    assert.ok(secondPid && secondPid !== firstPid, 'Expected ?reset=1 to spawn new agent PID');
    ws3.close();
  } finally {
    await server.close();
  }
});

test('bridge buffers pending session/request_permission while detached and replays on reconnect', async () => {
  const server = createBridgeServer({
    bindHost: '127.0.0.1',
    port: 0,
    defaultAgent: 'mock',
    keepAliveMs: 10000,
    logger: silentLogger,
  });

  const { port } = await server.start();
  try {
    const ws1 = await openWs(`ws://127.0.0.1:${port}/?agent=mock`);
    const rpc1 = createRpcHelper(ws1);

    await rpc1.request('initialize', { protocolVersion: 1, clientCapabilities: {} });
    const { sessionId } = await rpc1.request('session/new', { cwd: process.cwd(), mcpServers: [] });

    // Trigger prompt and detach right as permission arrives WITHOUT answering it
    const permArrived = new Promise((resolve) => {
      rpc1.onInboundRequest((req) => {
        if (req.method === 'session/request_permission') resolve(req);
      });
    });

    // Fire prompt without awaiting on ws1 (since we'll close ws1 before prompt completes)
    ws1.send(
      `${JSON.stringify({
        jsonrpc: '2.0',
        id: 99,
        method: 'session/prompt',
        params: { sessionId, prompt: [{ type: 'text', text: '切断中に承認待ちを発生させて' }] },
      })}\n`,
    );

    const firstPerm = await permArrived;
    const closed = new Promise((r) => ws1.once('close', r));
    ws1.close();
    await closed;

    // Reconnect and verify the unanswered permission is replayed immediately
    const ws2 = await openWs(`ws://127.0.0.1:${port}/?agent=mock`);
    const rpc2 = createRpcHelper(ws2);

    const replayedPerm = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Timed out waiting for replayed permission')), 3000);
      rpc2.onInboundRequest((req) => {
        if (req.method === 'session/request_permission') {
          clearTimeout(timer);
          resolve(req);
        }
      });
    });

    assert.equal(replayedPerm.id, firstPerm.id);
    // Answer the replayed permission cleanly
    rpc2.respond(replayedPerm.id, { outcome: { outcome: 'selected', optionId: 'allow-once' } });
    ws2.close();
  } finally {
    await server.close();
  }
});

// ============================================================================
// Script Test: Termux Screenshot Watcher (#8)
// ============================================================================

test('scripts/watch-ss.sh --once copies image safely (including spaces) and outputs Markdown link', async () => {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'xr-acp-ss-'));
  try {
    const srcFile = path.join(tmpDir, 'Screenshot 2026 11 21.png');
    const outDir = path.join(tmpDir, 'blog-images');
    await fs.writeFile(srcFile, Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));

    const scriptPath = path.join(REPO_ROOT, 'scripts', 'watch-ss.sh');
    const stdout1 = execFileSync(scriptPath, ['--once', srcFile], {
      env: {
        ...process.env,
        BLOG_ASSETS_DIR: outDir,
        PUBLIC_PREFIX: '/images',
        ALT_TEXT: 'XR Screenshot',
      },
      encoding: 'utf8',
    });

    assert.match(stdout1, /!\[XR Screenshot\]\(\/images\/ss-\d{8}-\d{6}\.png\)/);

    // Run a second time in the same second to test collision suffix
    const stdout2 = execFileSync(scriptPath, ['--once', srcFile], {
      env: {
        ...process.env,
        BLOG_ASSETS_DIR: outDir,
        PUBLIC_PREFIX: '/images',
      },
      encoding: 'utf8',
    });

    const files = await fs.readdir(outDir);
    assert.equal(files.length, 2, `Expected 2 unique copied files, got ${files.join(', ')} (${stdout2})`);
  } finally {
    await fs.rm(tmpDir, { recursive: true, force: true });
  }
});

// ============================================================================
// Spatial Desktop IDE v0.4 Tests: ANSI Console, Syntax HL, Fuzzy, FS Jail, /ws/console
// ============================================================================

test('renderAnsiToHtml, highlightCode, detectLanguage, and fuzzyScore work safely', () => {
  const ansiHtml = renderAnsiToHtml('\x1b[32mPASS\x1b[0m public/app.js:42 <script>x</script>');
  assert.ok(ansiHtml.includes('class="ansi-green"'));
  assert.ok(ansiHtml.includes('data-jump-file="public/app.js"'));
  assert.ok(ansiHtml.includes('data-jump-line="42"'));
  assert.ok(!ansiHtml.includes('<script>'));

  assert.equal(detectLanguage('src/main.ts'), 'TypeScript');
  assert.equal(detectLanguage('posts/blog.md'), 'Markdown');

  const hlJs = highlightCode('const x = "hi"; // comment', 'JavaScript');
  assert.ok(hlJs.includes('tok-keyword'));
  assert.ok(hlJs.includes('tok-string'));
  assert.ok(hlJs.includes('tok-comment'));

  assert.ok(fuzzyScore('app', 'public/app.js') > fuzzyScore('app', 'docs/a-p-p.md'));
  assert.equal(fuzzyScore('zzz', 'public/app.js'), -1);
});

test('Workspace FS API supports CRUD, search, git status, and strictly blocks traversal/symlink escapes', async () => {
  const tmpWorkspace = await fs.mkdtemp(path.join(os.tmpdir(), 'xr-ws-'));
  const outsideFile = path.join(os.tmpdir(), `xr-outside-${Date.now()}.txt`);
  await fs.writeFile(outsideFile, 'TOP_SECRET', 'utf8');

  const server = createBridgeServer({
    bindHost: '127.0.0.1',
    port: 0,
    workspaceDir: tmpWorkspace,
    defaultAgent: 'mock',
    logger: silentLogger,
  });

  const { port } = await server.start();
  const base = `http://127.0.0.1:${port}`;
  try {
    // Write file & read back
    const putRes = await fetch(`${base}/api/fs/file`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: 'notes/hello.md', content: '# Spatial IDE\nHello XR\n' }),
    });
    assert.equal(putRes.status, 200);

    const getRes = await fetch(`${base}/api/fs/file?path=notes/hello.md`);
    assert.equal(getRes.status, 200);
    const fileData = await getRes.json();
    assert.equal(fileData.content, '# Spatial IDE\nHello XR\n');

    // Tree listing
    const treeRes = await fetch(`${base}/api/fs/tree`);
    const treeData = await treeRes.json();
    assert.equal(treeData.tree[0].name, 'notes');
    assert.equal(treeData.tree[0].children[0].path, 'notes/hello.md');

    // Workspace text search
    const searchRes = await fetch(`${base}/api/fs/search?q=Spatial`);
    const searchData = await searchRes.json();
    assert.equal(searchData.results.length, 1);
    assert.equal(searchData.results[0].line, 1);

    // Rename & delete
    const renRes = await fetch(`${base}/api/fs/rename`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ from: 'notes/hello.md', to: 'notes/renamed.md' }),
    });
    assert.equal(renRes.status, 200);

    const delRes = await fetch(`${base}/api/fs/entry?path=notes/renamed.md`, { method: 'DELETE' });
    assert.equal(delRes.status, 200);

    // Path traversal blocked (403)
    const escRes = await fetch(`${base}/api/fs/file?path=../../etc/passwd`);
    assert.equal(escRes.status, 403);

    // Symlink escape blocked (403)
    await fs.symlink(outsideFile, path.join(tmpWorkspace, 'evil-link.txt'));
    const symRes = await fetch(`${base}/api/fs/file?path=evil-link.txt`);
    assert.equal(symRes.status, 403);

    // Git status on non-git dir returns isGitRepo: false gracefully
    const gitRes = await fetch(`${base}/api/git/status`);
    assert.equal(gitRes.status, 200);
    const gitData = await gitRes.json();
    assert.equal(gitData.isGitRepo, false);
  } finally {
    await server.close();
    await fs.rm(tmpWorkspace, { recursive: true, force: true });
    await fs.rm(outsideFile, { force: true });
  }
});

test('/ws/console streams stdout/exit and interrupts running command on kill', async () => {
  const server = createBridgeServer({
    bindHost: '127.0.0.1',
    port: 0,
    defaultAgent: 'mock',
    logger: silentLogger,
  });

  const { port } = await server.start();
  try {
    const ws = await openWs(`ws://127.0.0.1:${port}/ws/console`);
    const events = [];

    const waitEvent = (pred, timeoutMs = 4000) =>
      new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Console event timeout')), timeoutMs);
        const check = (msg) => {
          if (pred(msg)) {
            clearTimeout(timer);
            ws.off('message', onMsg);
            resolve(msg);
          }
        };
        const onMsg = (raw) => {
          const parsed = JSON.parse(raw.toString('utf8'));
          events.push(parsed);
          check(parsed);
        };
        ws.on('message', onMsg);
      });

    // 1. Run simple echo command
    const exitP = waitEvent((m) => m.type === 'exit' && m.id === 'c1');
    ws.send(JSON.stringify({ type: 'exec', id: 'c1', command: 'echo xr-console-ok' }));
    const exitMsg = await exitP;
    assert.equal(exitMsg.code, 0);
    const combinedOut = events
      .filter((e) => e.type === 'output' && e.id === 'c1')
      .map((e) => e.data)
      .join('');
    assert.match(combinedOut, /xr-console-ok/);

    // 2. Kill long-running sleep process via SIGINT
    const startSleep = waitEvent((m) => m.type === 'started' && m.id === 'c2');
    ws.send(JSON.stringify({ type: 'exec', id: 'c2', command: 'sleep 20' }));
    await startSleep;

    const killExit = waitEvent((m) => m.type === 'exit' && m.id === 'c2');
    ws.send(JSON.stringify({ type: 'kill', id: 'c2', signal: 'SIGINT' }));
    const killed = await killExit;
    assert.ok(killed.signal === 'SIGINT' || killed.code !== 0);

    ws.close();
  } finally {
    await server.close();
  }
});
