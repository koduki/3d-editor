/**
 * Unified Workspace FS + Git + File Watcher Client.
 * Seamlessly supports both live ws-acp-bridge HTTP/SSE endpoints and an in-memory
 * virtual workspace for Offline Demo Mode (v0.3/v0.4).
 */

import { deriveHttpBaseUrl } from './profiles.js';

function createInitialDemoFiles() {
  return new Map([
    [
      'README.md',
      [
        '# XR-ACP Spatial Workbench (Demo Workspace)',
        '',
        'キーボード＋マウス／トラックパッド前提で設計された空間コンピューター向け IDE です。',
        '',
        '## 主な操作',
        '- `Ctrl+P`: クイックファイルオープン',
        '- `Ctrl+Shift+P`: コマンドパレット',
        '- `Ctrl+S`: アクティブファイルを保存',
        '- `Ctrl+J` / ``Ctrl+` ``: 軽量コマンドコンソール開閉',
        '- `Ctrl+L`: 右側の AI エージェントへフォーカス',
        '- 右クリック: ファイルツリー・エディタでコンテキストメニュー',
      ].join('\n'),
    ],
    [
      'posts/xr-spatial-writing.md',
      [
        '# XREAL AURA × Meta Quest 3 空間執筆環境',
        '',
        '## ワークベンチ構成',
        '- 左ペイン: ファイルツリー & Git ステータス',
        '- 中央ペイン: コード／Markdown エディタ（ライブプレビュー対応）',
        '- 右ペイン: ACP AI エージェント（縦型 Unified Diff 承認）',
        '- 下ペイン: エージェント連動 軽量コマンドコンソール',
        '',
        '## メモ',
        'OST（完全黒 `#000000`）と VST（半透明ガラス）をワンクリックで切り替えられる。',
      ].join('\n'),
    ],
    [
      'src/xr-panel-layout.js',
      [
        '/**',
        ' * Spatial window luminance & pane sizing helpers for Android XR / Horizon OS.',
        ' */',
        'export function applyPanelLuminance(isFocused, mode, dimOnBlur = true) {',
        '  const base = mode === "ost" ? 1.0 : 0.92;',
        '  if (!dimOnBlur || isFocused) return base;',
        '  return base * 0.5;',
        '}',
        '',
        'export const DESKTOP_SHORTCUTS = {',
        '  quickOpen: "Ctrl+P",',
        '  commandPalette: "Ctrl+Shift+P",',
        '  save: "Ctrl+S",',
        '  consoleToggle: "Ctrl+J",',
        '  aiFocus: "Ctrl+L",',
        '};',
      ].join('\n'),
    ],
    [
      'package.json',
      JSON.stringify(
        {
          name: 'xr-spatial-blog',
          version: '0.4.0',
          type: 'module',
          scripts: {
            test: 'node --test',
            build: 'node scripts/build.mjs',
          },
        },
        null,
        2,
      ),
    ],
  ]);
}

function buildTreeFromMap(fileMap, dirSet) {
  const root = { childrenMap: new Map() };

  function ensureDir(parts) {
    let cur = root;
    let acc = '';
    for (const part of parts) {
      acc = acc ? `${acc}/${part}` : part;
      if (!cur.childrenMap.has(part)) {
        cur.childrenMap.set(part, {
          name: part,
          path: acc,
          type: 'dir',
          childrenMap: new Map(),
        });
      }
      cur = cur.childrenMap.get(part);
    }
    return cur;
  }

  for (const d of dirSet) {
    const parts = d.split('/').filter(Boolean);
    if (parts.length > 0) ensureDir(parts);
  }

  for (const filePath of fileMap.keys()) {
    const parts = filePath.split('/').filter(Boolean);
    if (parts.length === 0) continue;
    const fileName = parts.pop();
    const parent = ensureDir(parts);
    parent.childrenMap.set(fileName, {
      name: fileName,
      path: filePath,
      type: 'file',
    });
  }

  function finalize(node) {
    const list = [];
    for (const child of node.childrenMap.values()) {
      if (child.type === 'dir') {
        list.push({
          name: child.name,
          path: child.path,
          type: 'dir',
          children: finalize(child),
        });
      } else {
        list.push(child);
      }
    }
    list.sort((a, b) => {
      if (a.type !== b.type) return a.type === 'dir' ? -1 : 1;
      return a.name.localeCompare(b.name, 'en', { sensitivity: 'base' });
    });
    return list;
  }

  return finalize(root);
}

export class WorkspaceClient extends EventTarget {
  constructor() {
    super();
    this.mode = 'bridge';
    this.baseUrl = '';
    this.token = '';
    this.workspaceDir = '/';
    this.workspaceName = 'workspace';

    this._eventSource = null;
    this._demoFiles = createInitialDemoFiles();
    this._demoDirs = new Set(['posts', 'src']);
    this._demoModified = new Set();
  }

  configure(profile, { pageOrigin = '' } = {}) {
    this.stopWatching();
    if (profile.mode === 'demo') {
      this.mode = 'demo';
      this.baseUrl = '';
      this.token = '';
      this.workspaceDir = profile.workspaceDir || '/workspace/xr-blog';
      this.workspaceName = 'xr-spatial-blog (Demo)';
      return;
    }

    this.mode = 'bridge';
    this.token = profile.token || '';
    try {
      this.baseUrl = deriveHttpBaseUrl(profile.wsUrl, pageOrigin);
    } catch {
      this.baseUrl = pageOrigin;
    }
  }

  #headers(extra = {}) {
    const h = { ...extra };
    if (this.token) h.Authorization = `Bearer ${this.token}`;
    return h;
  }

  async #fetchJson(pathAndQuery, options = {}) {
    const res = await fetch(`${this.baseUrl}${pathAndQuery}`, {
      cache: 'no-store',
      ...options,
      headers: this.#headers(options.headers || {}),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      const err = new Error(data.error || `HTTP ${res.status}`);
      err.status = res.status;
      throw err;
    }
    return data;
  }

  startWatching() {
    this.stopWatching();
    if (this.mode !== 'bridge' || typeof EventSource === 'undefined' || !this.baseUrl) return;

    const url = new URL(`${this.baseUrl}/api/fs/events`);
    if (this.token) url.searchParams.set('token', this.token);

    try {
      const es = new EventSource(url.toString());
      es.onmessage = (ev) => {
        try {
          const msg = JSON.parse(ev.data);
          if (msg.type === 'fs_changed') {
            this.dispatchEvent(new CustomEvent('fs_changed', { detail: msg }));
          }
        } catch {
          // ignore
        }
      };
      this._eventSource = es;
    } catch {
      // ignore SSE errors on unsupported environments
    }
  }

  stopWatching() {
    if (this._eventSource) {
      try {
        this._eventSource.close();
      } catch {
        // ignore
      }
      this._eventSource = null;
    }
  }

  async getTree() {
    if (this.mode === 'demo') {
      return {
        workspaceDir: this.workspaceDir,
        workspaceName: this.workspaceName,
        tree: buildTreeFromMap(this._demoFiles, this._demoDirs),
      };
    }
    const res = await this.#fetchJson('/api/fs/tree');
    this.workspaceDir = res.workspaceDir || this.workspaceDir;
    this.workspaceName = res.workspaceName || this.workspaceName;
    return res;
  }

  async readFile(filePath) {
    if (this.mode === 'demo') {
      if (!this._demoFiles.has(filePath)) {
        throw new Error(`File not found: ${filePath}`);
      }
      const content = this._demoFiles.get(filePath);
      return {
        path: filePath,
        binary: false,
        size: content.length,
        mtimeMs: Date.now(),
        content,
      };
    }
    return this.#fetchJson(`/api/fs/file?path=${encodeURIComponent(filePath)}`);
  }

  async writeFile(filePath, content) {
    if (this.mode === 'demo') {
      const clean = String(filePath || '').replace(/^\/+/, '');
      this._demoFiles.set(clean, String(content ?? ''));
      this._demoModified.add(clean);
      const parts = clean.split('/');
      if (parts.length > 1) this._demoDirs.add(parts.slice(0, -1).join('/'));
      this.dispatchEvent(new CustomEvent('fs_changed', { detail: { paths: [clean] } }));
      return { path: clean, size: String(content ?? '').length, mtimeMs: Date.now() };
    }
    return this.#fetchJson('/api/fs/file', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: filePath, content }),
    });
  }

  async mkdir(dirPath) {
    if (this.mode === 'demo') {
      const clean = String(dirPath || '').replace(/^\/+|\/+$/g, '');
      this._demoDirs.add(clean);
      return { path: clean };
    }
    return this.#fetchJson('/api/fs/mkdir', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: dirPath }),
    });
  }

  async rename(from, to) {
    if (this.mode === 'demo') {
      const cleanFrom = String(from).replace(/^\/+/, '');
      const cleanTo = String(to).replace(/^\/+/, '');
      if (this._demoFiles.has(cleanFrom)) {
        this._demoFiles.set(cleanTo, this._demoFiles.get(cleanFrom));
        this._demoFiles.delete(cleanFrom);
        this._demoModified.add(cleanTo);
      }
      return { from: cleanFrom, to: cleanTo };
    }
    return this.#fetchJson('/api/fs/rename', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ from, to }),
    });
  }

  async deleteEntry(targetPath) {
    if (this.mode === 'demo') {
      const clean = String(targetPath).replace(/^\/+|\/+$/g, '');
      this._demoFiles.delete(clean);
      this._demoDirs.delete(clean);
      for (const k of [...this._demoFiles.keys()]) {
        if (k.startsWith(`${clean}/`)) this._demoFiles.delete(k);
      }
      return { deleted: clean };
    }
    return this.#fetchJson(`/api/fs/entry?path=${encodeURIComponent(targetPath)}`, {
      method: 'DELETE',
    });
  }

  async search(query) {
    if (this.mode === 'demo') {
      const q = String(query || '').trim().toLowerCase();
      if (!q) return { query, results: [] };
      const results = [];
      for (const [filePath, content] of this._demoFiles.entries()) {
        const lines = content.split('\n');
        for (let i = 0; i < lines.length; i++) {
          if (lines[i].toLowerCase().includes(q)) {
            results.push({ path: filePath, line: i + 1, text: lines[i].trim().slice(0, 140) });
          }
        }
      }
      return { query, results };
    }
    return this.#fetchJson(`/api/fs/search?q=${encodeURIComponent(query)}`);
  }

  async getGitStatus() {
    if (this.mode === 'demo') {
      const files = {};
      for (const f of this._demoModified) files[f] = 'M';
      return { isGitRepo: true, branch: 'demo-main', files };
    }
    return this.#fetchJson('/api/git/status');
  }
}

/**
 * Flatten a directory tree into a sorted list of file paths (for Quick Open Ctrl+P).
 */
export function flattenTreeFiles(tree, out = []) {
  if (!Array.isArray(tree)) return out;
  for (const node of tree) {
    if (node.type === 'file') {
      out.push(node.path);
    } else if (node.type === 'dir' && Array.isArray(node.children)) {
      flattenTreeFiles(node.children, out);
    }
  }
  return out;
}
