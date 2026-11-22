/**
 * Lightweight Spatial Command Console (v0.4).
 * Designed around the insight that agents do most heavy lifting while humans need a fast,
 * zero-bloat command runner with ANSI output, Ctrl+C interrupt, command history, and clickable file:line links.
 */

import { escapeHtml } from './markdown.js';
import { deriveHttpBaseUrl } from './profiles.js';

const ANSI_COLOR_MAP = {
  30: 'ansi-black',
  31: 'ansi-red',
  32: 'ansi-green',
  33: 'ansi-yellow',
  34: 'ansi-blue',
  35: 'ansi-magenta',
  36: 'ansi-cyan',
  37: 'ansi-white',
  90: 'ansi-gray',
  91: 'ansi-red',
  92: 'ansi-green',
  93: 'ansi-yellow',
  94: 'ansi-blue',
  95: 'ansi-magenta',
  96: 'ansi-cyan',
  97: 'ansi-white',
};

/**
 * Convert ANSI SGR sequences into safe HTML spans & make file:line patterns clickable.
 */
export function renderAnsiToHtml(rawText) {
  const str = String(rawText ?? '');
  // Match CSI SGR sequences \x1b[...m
  const regex = /\x1b\[([0-9;]*)m/g;
  let lastIndex = 0;
  let colorClass = '';
  let bold = false;
  let dim = false;
  const parts = [];

  function pushSegment(text) {
    if (!text) return;
    let safe = escapeHtml(text);
    // Linkify relative workspace paths with line numbers like `public/app.js:42` or `server.mjs:120:5`
    safe = safe.replace(
      /(^|[\s([{"'])([a-zA-Z0-9_./-]+\.[a-zA-Z0-9]{1,8}):(\d+)(?::(\d+))?/g,
      (_, lead, filePath, lineNo, colNo) => {
        if (/^https?$/i.test(filePath)) return _;
        const label = colNo ? `${filePath}:${lineNo}:${colNo}` : `${filePath}:${lineNo}`;
        return `${lead}<button type="button" class="console-file-link" data-jump-file="${escapeHtml(filePath)}" data-jump-line="${lineNo}">${label}</button>`;
      },
    );

    const classes = [];
    if (colorClass) classes.push(colorClass);
    if (bold) classes.push('ansi-bold');
    if (dim) classes.push('ansi-dim');

    if (classes.length > 0) {
      parts.push(`<span class="${classes.join(' ')}">${safe}</span>`);
    } else {
      parts.push(safe);
    }
  }

  let match;
  while ((match = regex.exec(str)) !== null) {
    pushSegment(str.slice(lastIndex, match.index));
    lastIndex = regex.lastIndex;

    const codes = (match[1] || '0').split(';').map((c) => Number(c || 0));
    for (const code of codes) {
      if (code === 0) {
        colorClass = '';
        bold = false;
        dim = false;
      } else if (code === 1) {
        bold = true;
      } else if (code === 2) {
        dim = true;
      } else if (code === 22) {
        bold = false;
        dim = false;
      } else if (code === 39) {
        colorClass = '';
      } else if (ANSI_COLOR_MAP[code]) {
        colorClass = ANSI_COLOR_MAP[code];
      }
    }
  }
  pushSegment(str.slice(lastIndex));
  return parts.join('');
}

export class SpatialConsole {
  constructor({ outputEl, inputEl, statusEl, stopBtn, clearBtn, copyBtn, onJumpToFile }) {
    this.outputEl = outputEl;
    this.inputEl = inputEl;
    this.statusEl = statusEl;
    this.stopBtn = stopBtn;
    this.clearBtn = clearBtn;
    this.copyBtn = copyBtn;
    this.onJumpToFile = onJumpToFile;

    this.mode = 'bridge';
    this.ws = null;
    this.runningId = null;
    this.history = [];
    this.historyIdx = -1;
    this.rawPlainText = '';
    this._reconnectTimer = null;
    this._wsUrl = '';
    this._manualDisconnect = false;

    this.#bindUi();
  }

  #bindUi() {
    this.inputEl.addEventListener('keydown', (e) => {
      if (e.isComposing || e.keyCode === 229) return;

      if (e.ctrlKey && !e.metaKey && !e.altKey && e.key.toLowerCase() === 'c') {
        if (this.runningId) {
          e.preventDefault();
          this.killRunning();
        }
        return;
      }

      if (e.key === 'Enter') {
        e.preventDefault();
        const val = this.inputEl.value;
        this.inputEl.value = '';
        if (this.runningId) {
          this.sendStdin(`${val}\n`);
        } else if (val.trim()) {
          this.runCommand(val.trim());
        }
        return;
      }

      if (e.key === 'ArrowUp' && !this.runningId) {
        if (this.history.length === 0) return;
        e.preventDefault();
        if (this.historyIdx < 0) this.historyIdx = this.history.length - 1;
        else if (this.historyIdx > 0) this.historyIdx--;
        this.inputEl.value = this.history[this.historyIdx] || '';
        return;
      }

      if (e.key === 'ArrowDown' && !this.runningId) {
        if (this.history.length === 0) return;
        e.preventDefault();
        if (this.historyIdx < this.history.length - 1) {
          this.historyIdx++;
          this.inputEl.value = this.history[this.historyIdx] || '';
        } else {
          this.historyIdx = -1;
          this.inputEl.value = '';
        }
      }
    });

    this.stopBtn?.addEventListener('click', () => this.killRunning());
    this.clearBtn?.addEventListener('click', () => this.clear());
    this.copyBtn?.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(this.rawPlainText);
        const orig = this.copyBtn.textContent;
        this.copyBtn.textContent = 'コピー済';
        setTimeout(() => {
          this.copyBtn.textContent = orig;
        }, 1200);
      } catch {
        // ignore
      }
    });

    this.outputEl.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-jump-file]');
      if (!btn) return;
      const file = btn.getAttribute('data-jump-file');
      const line = Number(btn.getAttribute('data-jump-line') || 1);
      if (file) this.onJumpToFile?.(file, line);
    });
  }

  connect(profile, { pageOrigin = '' } = {}) {
    this.disconnect();
    this._manualDisconnect = false;

    if (profile.mode === 'demo') {
      this.mode = 'demo';
      this.#setStatus('Demo Ready', 'ok');
      this.appendLine('XR-ACP Offline Demo Console — npm test / git status / ls / echo が実行可能です。', 'meta');
      return;
    }

    this.mode = 'bridge';
    let httpOrigin;
    try {
      httpOrigin = deriveHttpBaseUrl(profile.wsUrl, pageOrigin);
    } catch {
      this.#setStatus('URL エラー', 'err');
      return;
    }

    const wsUrl = new URL('/ws/console', httpOrigin);
    wsUrl.protocol = wsUrl.protocol === 'https:' ? 'wss:' : 'ws:';
    if (profile.token) wsUrl.searchParams.set('token', profile.token);
    this._wsUrl = wsUrl.toString();
    this.#openWs();
  }

  #openWs() {
    if (this._reconnectTimer) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }
    this.#setStatus('接続中…', 'warn');

    let ws;
    try {
      ws = new WebSocket(this._wsUrl);
    } catch {
      this.#setStatus('未接続', 'err');
      return;
    }

    this.ws = ws;

    ws.addEventListener('open', () => {
      if (this.ws !== ws) return;
      this.#setStatus('Ready', 'ok');
    });

    ws.addEventListener('message', (ev) => {
      if (this.ws !== ws) return;
      let msg;
      try {
        msg = JSON.parse(ev.data);
      } catch {
        return;
      }

      if (msg.type === 'started') {
        this.runningId = msg.id;
        this.#setRunning(true, msg.command);
      } else if (msg.type === 'output') {
        this.appendOutput(msg.data, msg.stream);
      } else if (msg.type === 'exit') {
        if (this.runningId === msg.id) {
          this.runningId = null;
          this.#setRunning(false);
        }
        const ok = msg.code === 0;
        const dur = typeof msg.durationMs === 'number' ? `${(msg.durationMs / 1000).toFixed(2)}s` : '';
        this.appendLine(
          ok
            ? `✓ 完了 (exit 0${dur ? `, ${dur}` : ''})`
            : `✕ 終了 (exit ${msg.code ?? 'null'}${msg.signal ? ` / ${msg.signal}` : ''}${dur ? `, ${dur}` : ''})`,
          ok ? 'ok' : 'err',
        );
      } else if (msg.type === 'error') {
        this.appendLine(`[error] ${msg.message}`, 'err');
      }
    });

    ws.addEventListener('close', () => {
      if (this.ws !== ws) return;
      this.ws = null;
      this.runningId = null;
      this.#setRunning(false);
      if (this._manualDisconnect) {
        this.#setStatus('切断', 'muted');
        return;
      }
      this.#setStatus('再接続中…', 'warn');
      this._reconnectTimer = setTimeout(() => {
        if (!this._manualDisconnect && this.mode === 'bridge') this.#openWs();
      }, 2500);
    });
  }

  disconnect() {
    this._manualDisconnect = true;
    if (this._reconnectTimer) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }
    if (this.ws) {
      try {
        this.ws.close();
      } catch {
        // ignore
      }
      this.ws = null;
    }
    this.runningId = null;
    this.#setRunning(false);
  }

  #setStatus(label, level = 'muted') {
    if (!this.statusEl) return;
    this.statusEl.textContent = label;
    this.statusEl.className = `console-status console-status-${level}`;
  }

  #setRunning(running, cmd = '') {
    if (this.stopBtn) {
      this.stopBtn.disabled = !running;
      this.stopBtn.classList.toggle('hidden', !running);
    }
    this.inputEl.placeholder = running
      ? `実行中 (${cmd.slice(0, 32)}) — Enter で標準入力送信 / Ctrl+C で中断`
      : 'コマンドを実行… (例: npm test, git status / ↑↓ 履歴)';
    if (running) {
      this.#setStatus('実行中…', 'warn');
    } else if (this.mode === 'demo' || (this.ws && this.ws.readyState === WebSocket.OPEN)) {
      this.#setStatus(this.mode === 'demo' ? 'Demo Ready' : 'Ready', 'ok');
    }
  }

  appendLine(text, kind = 'meta') {
    const line = document.createElement('div');
    line.className = `console-line console-line-${kind}`;
    line.textContent = text;
    this.outputEl.appendChild(line);
    this.rawPlainText += `${text}\n`;
    this.outputEl.scrollTop = this.outputEl.scrollHeight;
  }

  appendOutput(chunk, stream = 'stdout') {
    const span = document.createElement('span');
    span.className = stream === 'stderr' ? 'console-chunk console-stderr' : 'console-chunk';
    span.innerHTML = renderAnsiToHtml(chunk);
    this.outputEl.appendChild(span);
    this.rawPlainText += chunk;
    this.outputEl.scrollTop = this.outputEl.scrollHeight;
  }

  clear() {
    this.outputEl.innerHTML = '';
    this.rawPlainText = '';
  }

  focus() {
    this.inputEl.focus();
  }

  runCommand(command) {
    const cmd = String(command || '').trim();
    if (!cmd) return;

    if (this.history[this.history.length - 1] !== cmd) {
      this.history.push(cmd);
      if (this.history.length > 80) this.history.shift();
    }
    this.historyIdx = -1;

    this.appendLine(`$ ${cmd}`, 'cmd');

    if (this.mode === 'demo') {
      this.#runDemoCommand(cmd);
      return;
    }

    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      this.appendLine('コンソール WebSocket が未接続です。接続先設定を確認してください。', 'err');
      return;
    }

    const id = `cmd_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
    this.ws.send(JSON.stringify({ type: 'exec', id, command: cmd }));
  }

  sendStdin(data) {
    if (this.mode !== 'bridge' || !this.ws || !this.runningId) return;
    this.ws.send(JSON.stringify({ type: 'stdin', id: this.runningId, data }));
    this.appendLine(data.replace(/\n$/, ''), 'stdin');
  }

  killRunning() {
    if (!this.runningId) return;
    if (this.mode === 'bridge' && this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type: 'kill', id: this.runningId, signal: 'SIGINT' }));
      this.appendLine('^C (SIGINT 送信)', 'warn');
    }
  }

  #runDemoCommand(cmd) {
    const lower = cmd.toLowerCase();
    if (lower === 'clear') {
      this.clear();
      return;
    }
    if (lower.startsWith('echo ')) {
      this.appendOutput(`${cmd.slice(5)}\n`, 'stdout');
      this.appendLine('✓ 完了 (exit 0, 0.01s)', 'ok');
      return;
    }
    if (lower === 'pwd') {
      this.appendOutput('/workspace/xr-blog\n', 'stdout');
      this.appendLine('✓ 完了 (exit 0, 0.01s)', 'ok');
      return;
    }
    if (lower === 'ls' || lower.startsWith('ls ')) {
      this.appendOutput('README.md\npackage.json\nposts/xr-spatial-writing.md\nsrc/xr-panel-layout.js\n', 'stdout');
      this.appendLine('✓ 完了 (exit 0, 0.01s)', 'ok');
      return;
    }
    if (lower.startsWith('git status')) {
      this.appendOutput('On branch demo-main\nChanges not staged for commit:\n  modified:   posts/xr-spatial-writing.md:1\n', 'stdout');
      this.appendLine('✓ 完了 (exit 0, 0.02s)', 'ok');
      return;
    }
    if (lower.startsWith('npm test')) {
      this.appendOutput('\x1b[32m✔ spatial-panel-luminance (src/xr-panel-layout.js:4)\x1b[0m\n\x1b[32m✔ unified-diff-engine\x1b[0m\n\x1b[36mℹ tests 2 | pass 2 | fail 0\x1b[0m\n', 'stdout');
      this.appendLine('✓ 完了 (exit 0, 0.14s)', 'ok');
      return;
    }
    this.appendOutput(`[demo] 実行シミュレート: ${cmd}\nヒント: npm test / git status / ls / pwd / echo ... / clear が試せます。\n`, 'stdout');
    this.appendLine('✓ 完了 (exit 0, 0.02s)', 'ok');
  }
}
