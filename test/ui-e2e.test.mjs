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
      '--window-size=680,960',
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

  // Fetch page target WebSocket URL
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
    width: 680,
    height: 960,
    deviceScaleFactor: 2,
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
  'Headless Chromium UI E2E: OST/VST, IME safety, Diff keyboard approval, history restore on reload, diagnostics & offline demo',
  { skip: !CHROME_BIN && 'Chromium/Chrome binary not available in this environment' },
  async () => {
    const server = createBridgeServer({
      bindHost: '127.0.0.1',
      port: 0,
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

      // 1. Wait for WebSocket ACP mock agent ready
      await chrome.waitFor(
        `document.getElementById('statusDot')?.classList.contains('status-ready')`,
        8000,
        'status-ready on initial boot',
      );

      // 2. Verify OST default & OST/VST toggle persistence
      const initialTheme = await chrome.evaluate(`document.documentElement.dataset.theme`);
      assert.equal(initialTheme, 'ost');

      await chrome.evaluate(`document.getElementById('themeToggleBtn').click()`);
      assert.equal(await chrome.evaluate(`document.documentElement.dataset.theme`), 'vst');
      assert.equal(await chrome.evaluate(`document.getElementById('themeToggleBtn').textContent`), 'VST');

      // Switch back to OST
      await chrome.evaluate(`document.getElementById('themeToggleBtn').click()`);
      assert.equal(await chrome.evaluate(`document.documentElement.dataset.theme`), 'ost');

      // 3. Verify blur 50% dimming toggle
      await chrome.evaluate(`window.dispatchEvent(new Event('blur'))`);
      assert.equal(await chrome.evaluate(`document.body.classList.contains('window-dimmed')`), true);
      await chrome.evaluate(`window.dispatchEvent(new Event('focus'))`);
      assert.equal(await chrome.evaluate(`document.body.classList.contains('window-dimmed')`), false);

      // 4. Strict IME safety: Ctrl+Enter while composing must NOT send
      await chrome.evaluate(`(() => {
        const ta = document.getElementById('promptInput');
        ta.focus();
        ta.value = '日本語変換テスト';
        ta.dispatchEvent(new CompositionEvent('compositionstart'));
        const ev = new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true, cancelable: true });
        ta.dispatchEvent(ev);
      })()`);
      assert.equal(
        await chrome.evaluate(`document.querySelectorAll('.msg-user').length`),
        0,
        'Prompt must not submit during IME composition',
      );
      assert.equal(
        await chrome.evaluate(`!document.getElementById('imeIndicator').classList.contains('hidden')`),
        true,
      );

      // End composition, wait past 60ms Safari/Android guard window, then submit with Ctrl+Enter
      await chrome.evaluate(`(() => {
        const ta = document.getElementById('promptInput');
        ta.dispatchEvent(new CompositionEvent('compositionend'));
      })()`);
      await new Promise((r) => setTimeout(r, 90));
      await chrome.evaluate(`(() => {
        const ta = document.getElementById('promptInput');
        ta.value = 'XR空間執筆のドラフトを更新して';
        ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true, cancelable: true }));
      })()`);

      // 5. Wait for fixed permission approval bar with vertical Unified Diff
      await chrome.waitFor(
        `!document.getElementById('permissionBar').classList.contains('hidden') && document.querySelectorAll('#permPreview .diff-block').length > 0`,
        8000,
        'permission bar with Unified Diff',
      );

      // Optional screenshot capture if XR_SCREENSHOT_DIR is provided
      if (process.env.XR_SCREENSHOT_DIR) {
        await fs.mkdir(process.env.XR_SCREENSHOT_DIR, { recursive: true });
        const ostShot = await chrome.send('Page.captureScreenshot', { format: 'png' });
        await fs.writeFile(
          path.join(process.env.XR_SCREENSHOT_DIR, 'ost-diff-approval.png'),
          Buffer.from(ostShot.data, 'base64'),
        );
      }

      // 6. Approve with keyboard 'y' (input blurred)
      await chrome.evaluate(`(() => {
        document.activeElement?.blur?.();
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'y', bubbles: true, cancelable: true }));
      })()`);

      // Wait for permission bar to hide and turn to complete with Markdown & usage badge
      await chrome.waitFor(
        `document.getElementById('permissionBar').classList.contains('hidden') && document.querySelector('.msg-agent .turn-state')?.textContent === '完了'`,
        8000,
        'agent turn completion after y approval',
      );
      assert.equal(await chrome.evaluate(`!document.getElementById('usageBadge').classList.contains('hidden')`), true);

      // Allow debounced history persistence (180ms) to flush
      await new Promise((r) => setTimeout(r, 300));

      // 7. Reload page -> verify session/resume + local history timeline & Diff restoration (#8)
      await chrome.send('Page.reload', { ignoreCache: true });
      await chrome.waitFor(
        `document.getElementById('statusDot')?.classList.contains('status-ready') && document.querySelectorAll('.msg-user').length >= 1 && document.querySelectorAll('.diff-block').length >= 1`,
        8000,
        'cached timeline and diff restored after reload',
      );

      // 8. Open History modal and verify saved session appears (#8)
      await chrome.evaluate(`document.getElementById('historyBtn').click()`);
      await chrome.waitFor(
        `document.querySelectorAll('#historyList .history-item').length >= 1`,
        4000,
        'saved session in history modal',
      );
      await chrome.evaluate(`document.getElementById('closeHistoryBtn').click()`);

      // 9. Open Settings modal and run live connection diagnostics (#7)
      await chrome.evaluate(`document.getElementById('settingsBtn').click()`);
      await chrome.evaluate(`document.getElementById('testConnectionBtn').click()`);
      await chrome.waitFor(
        `document.getElementById('diagResult')?.classList.contains('diag-ok')`,
        5000,
        'connection diagnostic OK',
      );
      await chrome.evaluate(`document.getElementById('closeSettingsBtn').click()`);

      // 10. Switch to Offline Demo Mode + VST theme and run a quick demo prompt (#9)
      await chrome.evaluate(`document.getElementById('themeToggleBtn').click()`);
      await chrome.evaluate(`(() => {
        const sel = document.getElementById('profileQuickSelect');
        sel.value = 'offline-demo';
        sel.dispatchEvent(new Event('change'));
      })()`);
      await chrome.waitFor(
        `!document.getElementById('demoBadge').classList.contains('hidden') && document.getElementById('statusDot')?.classList.contains('status-ready')`,
        5000,
        'offline demo mode ready',
      );

      // Click first offline demo prompt chip
      await chrome.evaluate(`document.querySelector('[data-demo-prompt]')?.click()`);
      await chrome.waitFor(
        `!document.getElementById('permissionBar').classList.contains('hidden') && document.querySelectorAll('#permPreview .diff-block').length > 0`,
        6000,
        'offline demo Diff permission bar',
      );

      if (process.env.XR_SCREENSHOT_DIR) {
        const vstShot = await chrome.send('Page.captureScreenshot', { format: 'png' });
        await fs.writeFile(
          path.join(process.env.XR_SCREENSHOT_DIR, 'vst-demo-approval.png'),
          Buffer.from(vstShot.data, 'base64'),
        );
      }

      // Approve demo edit with keyboard 'y'
      await chrome.evaluate(`(() => {
        document.activeElement?.blur?.();
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'y', bubbles: true, cancelable: true }));
      })()`);
      await chrome.waitFor(
        `document.getElementById('permissionBar').classList.contains('hidden') && document.querySelector('.msg-agent .turn-state')?.textContent === '完了'`,
        6000,
        'offline demo completion',
      );

      assert.deepEqual(chrome.pageErrors, [], `Unexpected page errors: ${chrome.pageErrors.join('; ')}`);
      assert.deepEqual(chrome.consoleErrors, [], `Unexpected console errors: ${chrome.consoleErrors.join('; ')}`);
    } finally {
      await chrome.close();
      await server.close();
      await fs.rm(userDataDir, { recursive: true, force: true });
    }
  },
);
