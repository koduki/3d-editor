import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { createBridgeServer } from '../server.mjs';

const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  '/usr/bin/google-chrome',
].filter(Boolean);

const CHROME_BIN = CHROME_CANDIDATES.find((p) => existsSync(p)) || null;

const silentLogger = {
  log() {},
  warn() {},
  error() {},
};

async function launchHeadlessChrome(userDataDir) {
  const proc = spawn(
    CHROME_BIN,
    [
      '--headless=new',
      '--no-sandbox',
      '--disable-gpu',
      '--disable-dev-shm-usage',
      '--remote-debugging-port=0',
      `--user-data-dir=${userDataDir}`,
      '--window-size=1440,900',
      'about:blank',
    ],
    { stdio: ['ignore', 'pipe', 'pipe'] },
  );

  const wsUrl = await new Promise((resolve, reject) => {
    let stderr = '';
    const timer = setTimeout(() => reject(new Error(`Chrome startup timeout. stderr: ${stderr}`)), 15000);
    proc.stderr.setEncoding('utf8');
    proc.stderr.on('data', (chunk) => {
      stderr += chunk;
      const m = stderr.match(/DevTools listening on (ws:\/\/[^\s]+)/);
      if (m) {
        clearTimeout(timer);
        resolve(m[1]);
      }
    });
    proc.once('exit', (code) => {
      clearTimeout(timer);
      reject(new Error(`Chrome exited early (${code}): ${stderr}`));
    });
  });

  const debugOrigin = new URL(wsUrl).origin.replace(/^ws:/, 'http:');
  const targetsRes = await fetch(`${debugOrigin}/json/list`);
  const targets = await targetsRes.json();
  const pageTarget = targets.find((t) => t.type === 'page') || targets[0];
  assert.ok(pageTarget?.webSocketDebuggerUrl, 'No page target found in Chrome');

  const cdpWs = new WebSocket(pageTarget.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    cdpWs.addEventListener('open', resolve, { once: true });
    cdpWs.addEventListener('error', reject, { once: true });
  });

  let nextId = 1;
  const pending = new Map();
  const consoleErrors = [];
  const pageErrors = [];

  cdpWs.addEventListener('message', (event) => {
    const msg = JSON.parse(event.data);
    if (msg.id !== undefined) {
      const waiter = pending.get(msg.id);
      if (waiter) {
        pending.delete(msg.id);
        if (msg.error) waiter.reject(new Error(`CDP ${msg.error.message}`));
        else waiter.resolve(msg.result);
      }
    } else if (msg.method === 'Runtime.exceptionThrown') {
      pageErrors.push(msg.params?.exceptionDetails?.text || 'Uncaught exception');
    } else if (msg.method === 'Runtime.consoleAPICalled' && msg.params?.type === 'error') {
      const text = (msg.params.args || []).map((a) => a.value ?? a.description ?? '').join(' ');
      consoleErrors.push(text);
    }
  });

  function send(method, params = {}) {
    const id = nextId++;
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      cdpWs.send(JSON.stringify({ id, method, params }));
    });
  }

  async function evaluate(expression) {
    const res = await send('Runtime.evaluate', {
      expression,
      awaitPromise: true,
      returnByValue: true,
    });
    if (res.exceptionDetails) {
      throw new Error(res.exceptionDetails.exception?.description || res.exceptionDetails.text);
    }
    return res.result?.value;
  }

  async function waitFor(predicateExpr, timeoutMs = 8000, label = predicateExpr) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      const val = await evaluate(`Boolean(${predicateExpr})`);
      if (val) return;
      await new Promise((r) => setTimeout(r, 60));
    }
    throw new Error(`Timed out waiting for: ${label}`);
  }

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height: 900,
    deviceScaleFactor: 1.5,
    mobile: false,
  });

  async function close() {
    try {
      cdpWs.close();
    } catch {
      // ignore
    }
    if (proc.exitCode === null) {
      const exited = new Promise((resolve) => proc.once('exit', resolve));
      try {
        proc.kill('SIGTERM');
      } catch {
        // ignore
      }
      await Promise.race([exited, new Promise((r) => setTimeout(r, 2000))]);
      if (proc.exitCode === null) {
        try {
          proc.kill('SIGKILL');
        } catch {
          // ignore
        }
        await Promise.race([exited, new Promise((r) => setTimeout(r, 500))]);
      }
    }
  }

  return { send, evaluate, waitFor, consoleErrors, pageErrors, close };
}

test(
  'Headless Chromium Spatial Desktop IDE E2E: Explorer, Editor+Save+MD Preview, QuickOpen, Console, AI Diff Approval, Reload History & Demo',
  { skip: !CHROME_BIN && 'Chromium/Chrome binary not available in this environment' },
  async () => {
    // Seed an isolated temporary workspace so Ctrl+S edits don't dirty the repo
    const tmpWorkspace = await fs.mkdtemp(path.join(os.tmpdir(), 'xr-ide-ws-'));
    await fs.mkdir(path.join(tmpWorkspace, 'posts'), { recursive: true });
    await fs.mkdir(path.join(tmpWorkspace, 'src'), { recursive: true });
    await fs.writeFile(
      path.join(tmpWorkspace, 'posts', 'xr-notes.md'),
      '# XR Spatial Workbench\n\n- Keyboard + Mouse/Trackpad first\n- OST `#000000` black\n',
      'utf8',
    );
    await fs.writeFile(
      path.join(tmpWorkspace, 'src', 'app.js'),
      'export function greet(name) {\n  return `Hello ${name}`;\n}\n',
      'utf8',
    );

    const server = createBridgeServer({
      bindHost: '127.0.0.1',
      port: 0,
      workspaceDir: tmpWorkspace,
      defaultAgent: 'mock',
      keepAliveMs: 15000,
      logger: silentLogger,
    });
    const { port } = await server.start();
    const userDataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'xr-acp-chrome-'));
    const chrome = await launchHeadlessChrome(userDataDir);

    try {
      const baseUrl = `http://127.0.0.1:${port}/`;
      await chrome.send('Page.navigate', { url: baseUrl });

      // 1. Wait for ACP agent + Explorer tree + Console ready
      await chrome.waitFor(
        `document.getElementById('statusDot')?.classList.contains('status-ready') && document.querySelectorAll('#fileTree .tree-row').length >= 2`,
        8000,
        'ACP ready and file tree loaded',
      );

      // 2. Open posts/xr-notes.md via Quick Open (Ctrl+P)
      await chrome.evaluate(`document.dispatchEvent(new KeyboardEvent('keydown', { key: 'p', ctrlKey: true, bubbles: true, cancelable: true }))`);
      await chrome.waitFor(`!document.getElementById('paletteModal').classList.contains('hidden')`, 3000, 'Quick Open modal');
      await chrome.evaluate(`(() => {
        const inp = document.getElementById('paletteInput');
        inp.value = 'xr-notes';
        inp.dispatchEvent(new Event('input'));
        inp.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
      })()`);

      await chrome.waitFor(
        `document.getElementById('editorToolbarPath')?.textContent === 'posts/xr-notes.md' && !document.getElementById('editorSurface').classList.contains('hidden')`,
        4000,
        'posts/xr-notes.md opened in editor',
      );

      // 3. Toggle live Markdown preview & edit + save with Ctrl+S
      await chrome.evaluate(`document.getElementById('editorPreviewBtn').click()`);
      assert.equal(await chrome.evaluate(`!document.getElementById('editorPreview').classList.contains('hidden')`), true);

      await chrome.evaluate(`(() => {
        const ta = document.getElementById('editorTextarea');
        ta.value += '\\n- Saved via Ctrl+S\\n';
        ta.dispatchEvent(new Event('input'));
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true, bubbles: true, cancelable: true }));
      })()`);

      await chrome.waitFor(`document.getElementById('editorSaveBtn').disabled === true`, 4000, 'file saved cleanly');
      const diskContent = await fs.readFile(path.join(tmpWorkspace, 'posts', 'xr-notes.md'), 'utf8');
      assert.match(diskContent, /Saved via Ctrl\+S/);

      // 4. Run a real command in the bottom Lightweight Command Console
      await chrome.waitFor(`document.getElementById('consoleStatus')?.textContent === 'Ready'`, 4000, 'console WS ready');
      await chrome.evaluate(`(() => {
        const inp = document.getElementById('consoleInput');
        inp.value = 'echo spatial-console-verified';
        inp.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
      })()`);
      await chrome.waitFor(
        `document.getElementById('consoleOutput')?.textContent.includes('spatial-console-verified')`,
        5000,
        'console output rendered',
      );

      // 5. Attach active file context to AI panel (Ctrl+L / AI添付 button)
      await chrome.evaluate(`document.getElementById('editorAttachAiBtn').click()`);
      assert.equal(
        await chrome.evaluate(`!document.getElementById('aiContextBar').classList.contains('hidden')`),
        true,
        'Expected active file context chip attached to AI panel',
      );

      // 6. Strict IME safety in AI composer
      await chrome.evaluate(`(() => {
        const ta = document.getElementById('promptInput');
        ta.focus();
        ta.value = '変換中テスト';
        ta.dispatchEvent(new CompositionEvent('compositionstart'));
        ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true, cancelable: true }));
      })()`);
      assert.equal(await chrome.evaluate(`document.querySelectorAll('.msg-user').length`), 0);

      await chrome.evaluate(`document.getElementById('promptInput').dispatchEvent(new CompositionEvent('compositionend'))`);
      await new Promise((r) => setTimeout(r, 90));
      await chrome.evaluate(`(() => {
        const ta = document.getElementById('promptInput');
        ta.value = 'この記事ドラフトに構成案を追記して';
        ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true, cancelable: true }));
      })()`);

      // 7. Wait for fixed permission approval bar with Unified Diff
      await chrome.waitFor(
        `!document.getElementById('permissionBar').classList.contains('hidden') && document.querySelectorAll('#permPreview .diff-block').length > 0`,
        8000,
        'AI Unified Diff approval bar',
      );

      if (process.env.XR_SCREENSHOT_DIR) {
        await fs.mkdir(process.env.XR_SCREENSHOT_DIR, { recursive: true });
        const ostShot = await chrome.send('Page.captureScreenshot', { format: 'png' });
        await fs.writeFile(
          path.join(process.env.XR_SCREENSHOT_DIR, 'workbench-ost-1440.png'),
          Buffer.from(ostShot.data, 'base64'),
        );
      }

      // 8. Approve permission via keyboard 'y'
      await chrome.evaluate(`(() => {
        document.activeElement?.blur?.();
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'y', bubbles: true, cancelable: true }));
      })()`);

      await chrome.waitFor(
        `document.getElementById('permissionBar').classList.contains('hidden') && document.querySelector('.msg-agent .turn-state')?.textContent === '完了'`,
        8000,
        'AI turn completed after approval',
      );

      await new Promise((r) => setTimeout(r, 300));

      // 9. Reload -> verify cached AI session history restores automatically
      await chrome.send('Page.reload', { ignoreCache: true });
      await chrome.waitFor(
        `document.getElementById('statusDot')?.classList.contains('status-ready') && document.querySelectorAll('.msg-user').length >= 1 && document.querySelectorAll('.diff-block').length >= 1`,
        8000,
        'cached session history restored after reload',
      );

      // 10. Command Palette (Ctrl+Shift+P) -> toggle OST -> VST & switch to Offline Demo
      await chrome.evaluate(`document.dispatchEvent(new KeyboardEvent('keydown', { key: 'P', ctrlKey: true, shiftKey: true, bubbles: true, cancelable: true }))`);
      await chrome.waitFor(`!document.getElementById('paletteModal').classList.contains('hidden')`, 3000, 'Command Palette open');
      await chrome.evaluate(`(() => {
        const inp = document.getElementById('paletteInput');
        inp.value = 'VST';
        inp.dispatchEvent(new Event('input'));
        inp.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
      })()`);
      assert.equal(await chrome.evaluate(`document.documentElement.dataset.theme`), 'vst');

      // Switch to Offline Demo profile -> virtual workspace README.md opens automatically
      await chrome.evaluate(`(() => {
        const sel = document.getElementById('profileQuickSelect');
        sel.value = 'offline-demo';
        sel.dispatchEvent(new Event('change'));
      })()`);
      await chrome.waitFor(
        `!document.getElementById('demoBadge').classList.contains('hidden') && document.getElementById('editorToolbarPath')?.textContent === 'README.md'`,
        6000,
        'Offline Demo virtual workspace ready',
      );

      // Open src/xr-panel-layout.js in demo editor + run demo npm test in console
      await chrome.evaluate(`(() => {
        const row = [...document.querySelectorAll('#fileTree .tree-row')].find((r) => r.dataset.treePath === 'src/xr-panel-layout.js');
        row?.click();
      })()`);
      await chrome.waitFor(`document.getElementById('editorToolbarPath')?.textContent === 'src/xr-panel-layout.js'`, 3000, 'demo code file opened');
      await chrome.evaluate(`document.querySelector('[data-console-cmd="npm test"]')?.click()`);
      await chrome.waitFor(`document.getElementById('consoleOutput')?.textContent.includes('spatial-panel-luminance')`, 3000, 'demo console test run');

      // Trigger a Demo AI prompt for VST screenshot
      await chrome.evaluate(`document.querySelector('[data-demo-prompt]')?.click()`);
      await chrome.waitFor(
        `!document.getElementById('permissionBar').classList.contains('hidden') && document.querySelectorAll('#permPreview .diff-block').length > 0`,
        6000,
        'demo Diff approval bar',
      );

      if (process.env.XR_SCREENSHOT_DIR) {
        const vstShot = await chrome.send('Page.captureScreenshot', { format: 'png' });
        await fs.writeFile(
          path.join(process.env.XR_SCREENSHOT_DIR, 'workbench-vst-1440.png'),
          Buffer.from(vstShot.data, 'base64'),
        );
      }

      assert.deepEqual(chrome.pageErrors, [], `Unexpected page errors: ${chrome.pageErrors.join('; ')}`);
      assert.deepEqual(chrome.consoleErrors, [], `Unexpected console errors: ${chrome.consoleErrors.join('; ')}`);
    } finally {
      await chrome.close();
      await server.close();
      await fs.rm(userDataDir, { recursive: true, force: true });
      await fs.rm(tmpWorkspace, { recursive: true, force: true });
    }
  },
);
