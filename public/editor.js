/**
 * Spatial Desktop Code & Markdown Editor (v0.4).
 * Zero external dependencies — engineered for keyboard + mouse/trackpad workflows on
 * XREAL AURA (OST) and Meta Quest 3 (VST):
 * - Multi-tab bar with dirty indicator & middle-click close
 * - Synced line numbers + lightweight syntax highlighting backdrop
 * - Tab / Shift+Tab / auto-indent / Ctrl+S save / live Markdown split preview
 * - External FS change auto-reload (clean tabs) or conflict banner (dirty tabs)
 * - Direct file / selection context handoff to ACP AI Agent
 */

import { escapeHtml, renderMarkdown } from './markdown.js';

const LANG_BY_EXT = {
  js: 'JavaScript',
  mjs: 'JavaScript',
  cjs: 'JavaScript',
  ts: 'TypeScript',
  tsx: 'TypeScript',
  jsx: 'JavaScript',
  json: 'JSON',
  md: 'Markdown',
  markdown: 'Markdown',
  html: 'HTML',
  htm: 'HTML',
  css: 'CSS',
  sh: 'Shell',
  bash: 'Shell',
  zsh: 'Shell',
  yml: 'YAML',
  yaml: 'YAML',
  py: 'Python',
  rs: 'Rust',
  go: 'Go',
  kt: 'Kotlin',
  svg: 'SVG',
};

export function detectLanguage(filePath = '') {
  const ext = String(filePath).split('.').pop()?.toLowerCase() || '';
  return LANG_BY_EXT[ext] || 'Plain Text';
}

export function isMarkdownFile(filePath = '') {
  return /\.(md|markdown|mdx)$/i.test(String(filePath));
}

/**
 * Lightweight regex tokenizer for high-contrast OST/VST syntax highlighting.
 * Always keeps exact character/newline alignment with the overlaid textarea.
 */
export function highlightCode(text, lang) {
  const escaped = escapeHtml(text);
  if (lang === 'Plain Text' || text.length > 120_000) {
    return `${escaped}\n`;
  }

  if (lang === 'Markdown') {
    return (
      escaped
        .split('\n')
        .map((line) => {
          if (/^#{1,6}\s/.test(line)) return `<span class="tok-heading">${line}</span>`;
          if (/^\s*```/.test(line)) return `<span class="tok-keyword">${line}</span>`;
          if (/^\s*&gt;/.test(line)) return `<span class="tok-comment">${line}</span>`;
          return line
            .replace(/`([^`]+)`/g, '<span class="tok-string">`$1`</span>')
            .replace(/\*\*([^*]+)\*\*/g, '<span class="tok-keyword">**$1**</span>');
        })
        .join('\n') + '\n'
    );
  }

  if (lang === 'JSON') {
    return (
      escaped.replace(
        /(&quot;(?:\\.|[^&])*?&quot;\s*:)|(&quot;(?:\\.|[^&])*?&quot;)|\b(true|false|null)\b|\b(-?\d+(?:\.\d+)?)\b/g,
        (m, key, str, lit, num) => {
          if (key) return `<span class="tok-prop">${key}</span>`;
          if (str) return `<span class="tok-string">${str}</span>`;
          if (lit) return `<span class="tok-keyword">${lit}</span>`;
          if (num) return `<span class="tok-number">${num}</span>`;
          return m;
        },
      ) + '\n'
    );
  }

  if (lang === 'CSS') {
    return (
      escaped.replace(
        /(\/\*[\s\S]*?\*\/)|(--[a-zA-Z0-9_-]+)|([a-zA-Z-]+(?=\s*:))|(#[0-9a-fA-F]{3,8}\b)|(\b\d+(?:\.\d+)?(?:px|rem|em|%|vh|vw|dvh|ms|s|deg)?\b)/g,
        (m, com, cssVar, prop, hex, num) => {
          if (com) return `<span class="tok-comment">${com}</span>`;
          if (cssVar) return `<span class="tok-prop">${cssVar}</span>`;
          if (prop) return `<span class="tok-keyword">${prop}</span>`;
          if (hex) return `<span class="tok-string">${hex}</span>`;
          if (num) return `<span class="tok-number">${num}</span>`;
          return m;
        },
      ) + '\n'
    );
  }

  // JS / TS / Shell / Python / HTML / general code
  const pattern =
    /(\/\/[^\n]*|\/\*[\s\S]*?\*\/|#[^\n]*)|(&quot;(?:\\.|[^&\n])*?&quot;|&#39;(?:\\.|[^&\n])*?&#39;|`[^`]*`)|\b(import|export|from|const|let|var|function|async|await|return|if|else|for|while|switch|case|break|continue|class|extends|new|try|catch|finally|throw|default|typeof|interface|type|def|elif|pass|local|then|fi|done|esac)\b|\b(true|false|null|undefined|this|super|NaN)\b|\b(\d+(?:\.\d+)?)\b/g;

  return (
    escaped.replace(pattern, (m, com, str, kw, lit, num) => {
      if (com) return `<span class="tok-comment">${com}</span>`;
      if (str) return `<span class="tok-string">${str}</span>`;
      if (kw) return `<span class="tok-keyword">${kw}</span>`;
      if (lit) return `<span class="tok-prop">${lit}</span>`;
      if (num) return `<span class="tok-number">${num}</span>`;
      return m;
    }) + '\n'
  );
}

export class SpatialEditor {
  constructor({
    tabBarEl,
    toolbarPathEl,
    saveBtn,
    previewBtn,
    attachAiBtn,
    conflictBannerEl,
    conflictReloadBtn,
    conflictKeepBtn,
    emptyEl,
    surfaceEl,
    gutterEl,
    highlightEl,
    textareaEl,
    previewEl,
    workspaceClient,
    onStatusChange,
    onSaved,
    onError,
    onAttachContext,
    onContextMenu,
  }) {
    this.tabBarEl = tabBarEl;
    this.toolbarPathEl = toolbarPathEl;
    this.saveBtn = saveBtn;
    this.previewBtn = previewBtn;
    this.attachAiBtn = attachAiBtn;
    this.conflictBannerEl = conflictBannerEl;
    this.conflictReloadBtn = conflictReloadBtn;
    this.conflictKeepBtn = conflictKeepBtn;
    this.emptyEl = emptyEl;
    this.surfaceEl = surfaceEl;
    this.gutterEl = gutterEl;
    this.highlightEl = highlightEl;
    this.textareaEl = textareaEl;
    this.previewEl = previewEl;

    this.workspaceClient = workspaceClient;
    this.onStatusChange = onStatusChange;
    this.onSaved = onSaved;
    this.onError = onError;
    this.onAttachContext = onAttachContext;
    this.onContextMenu = onContextMenu;

    this.tabs = []; // [{ path, content, savedContent, dirty, language, binary, externalConflict, scrollTop, selectionStart }]
    this.activePath = null;
    this.previewVisible = false;
    this._previewRaf = null;

    this.#bindEvents();
    this.#renderEmpty();
  }

  #bindEvents() {
    this.textareaEl.addEventListener('input', () => {
      const tab = this.getActiveTab();
      if (!tab) return;
      tab.content = this.textareaEl.value;
      tab.dirty = tab.content !== tab.savedContent;
      this.#syncVisuals(tab);
      this.#renderTabs();
      this.#emitStatus();
    });

    this.textareaEl.addEventListener('scroll', () => {
      const top = this.textareaEl.scrollTop;
      const left = this.textareaEl.scrollLeft;
      this.gutterEl.scrollTop = top;
      this.highlightEl.scrollTop = top;
      this.highlightEl.scrollLeft = left;
      const tab = this.getActiveTab();
      if (tab) tab.scrollTop = top;
    });

    const updateCursor = () => this.#emitStatus();
    this.textareaEl.addEventListener('keyup', updateCursor);
    this.textareaEl.addEventListener('mouseup', updateCursor);
    this.textareaEl.addEventListener('focus', updateCursor);

    this.textareaEl.addEventListener('keydown', (e) => {
      if (e.isComposing || e.keyCode === 229) return;

      // Tab / Shift+Tab indentation (2 spaces)
      if (e.key === 'Tab') {
        e.preventDefault();
        this.#handleTabIndent(e.shiftKey);
        return;
      }

      // Auto-indent on Enter
      if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey && !e.altKey) {
        const start = this.textareaEl.selectionStart;
        const end = this.textareaEl.selectionEnd;
        const val = this.textareaEl.value;
        const lineStart = val.lastIndexOf('\n', start - 1) + 1;
        const currentLine = val.slice(lineStart, start);
        const indentMatch = currentLine.match(/^(\s+)/);
        if (indentMatch) {
          e.preventDefault();
          const insert = `\n${indentMatch[1]}`;
          this.textareaEl.setRangeText(insert, start, end, 'end');
          this.textareaEl.dispatchEvent(new Event('input'));
        }
      }
    });

    this.textareaEl.addEventListener('contextmenu', (e) => {
      const tab = this.getActiveTab();
      if (!tab || !this.onContextMenu) return;
      e.preventDefault();
      this.onContextMenu(e, this.getSelectionContext());
    });

    this.saveBtn?.addEventListener('click', () => this.saveActiveFile());
    this.previewBtn?.addEventListener('click', () => this.toggleMarkdownPreview());
    this.attachAiBtn?.addEventListener('click', () => {
      const ctx = this.getSelectionContext();
      if (ctx) this.onAttachContext?.(ctx);
    });

    this.conflictReloadBtn?.addEventListener('click', async () => {
      const tab = this.getActiveTab();
      if (!tab) return;
      await this.reloadTab(tab.path, { force: true });
    });

    this.conflictKeepBtn?.addEventListener('click', () => {
      const tab = this.getActiveTab();
      if (!tab) return;
      tab.externalConflict = false;
      this.conflictBannerEl?.classList.add('hidden');
    });
  }

  #handleTabIndent(unindent) {
    const ta = this.textareaEl;
    const start = ta.selectionStart;
    const end = ta.selectionEnd;
    const val = ta.value;

    if (!unindent && start === end) {
      ta.setRangeText('  ', start, end, 'end');
      ta.dispatchEvent(new Event('input'));
      return;
    }

    const lineStart = val.lastIndexOf('\n', start - 1) + 1;
    const selected = val.slice(lineStart, end);
    const lines = selected.split('\n');
    const updated = lines.map((line) => {
      if (unindent) {
        if (line.startsWith('  ')) return line.slice(2);
        if (line.startsWith('\t') || line.startsWith(' ')) return line.slice(1);
        return line;
      }
      return `  ${line}`;
    });
    const replacement = updated.join('\n');
    ta.setRangeText(replacement, lineStart, end, 'select');
    ta.dispatchEvent(new Event('input'));
  }

  getActiveTab() {
    return this.tabs.find((t) => t.path === this.activePath) || null;
  }

  getOpenPaths() {
    return this.tabs.map((t) => t.path);
  }

  getCursorPosition() {
    const tab = this.getActiveTab();
    if (!tab) return { line: 1, col: 1 };
    const pos = this.textareaEl.selectionStart || 0;
    const before = this.textareaEl.value.slice(0, pos);
    const lines = before.split('\n');
    return {
      line: lines.length,
      col: lines[lines.length - 1].length + 1,
    };
  }

  getSelectionContext() {
    const tab = this.getActiveTab();
    if (!tab) return null;
    const start = this.textareaEl.selectionStart || 0;
    const end = this.textareaEl.selectionEnd || 0;
    const val = this.textareaEl.value;
    const hasSelection = end > start;
    const startLine = val.slice(0, start).split('\n').length;
    const endLine = val.slice(0, end).split('\n').length;
    return {
      path: tab.path,
      language: tab.language,
      hasSelection,
      selection: hasSelection ? val.slice(start, end) : '',
      startLine,
      endLine,
      content: tab.content,
    };
  }

  async openFile(filePath, { line = null, focus = true } = {}) {
    const clean = String(filePath || '').replace(/^\/+/, '');
    if (!clean) return;

    let tab = this.tabs.find((t) => t.path === clean);
    if (!tab) {
      try {
        const res = await this.workspaceClient.readFile(clean);
        tab = {
          path: res.path || clean,
          content: res.binary ? '[バイナリファイルはテキストエディタで編集できません]' : res.content,
          savedContent: res.binary ? '[バイナリファイルはテキストエディタで編集できません]' : res.content,
          dirty: false,
          binary: Boolean(res.binary),
          language: detectLanguage(clean),
          externalConflict: false,
          scrollTop: 0,
        };
        this.tabs.push(tab);
      } catch (err) {
        this.onError?.(`ファイルを開けませんでした (${clean}): ${err.message}`);
        return;
      }
    }

    this.activateTab(tab.path, { line, focus });
  }

  activateTab(filePath, { line = null, focus = true } = {}) {
    const tab = this.tabs.find((t) => t.path === filePath);
    if (!tab) {
      this.#renderEmpty();
      return;
    }

    this.activePath = tab.path;
    this.emptyEl.classList.add('hidden');
    this.surfaceEl.classList.remove('hidden');

    this.textareaEl.value = tab.content;
    this.textareaEl.readOnly = tab.binary;
    this.toolbarPathEl.textContent = tab.path;
    this.toolbarPathEl.title = tab.path;

    const canPreview = isMarkdownFile(tab.path);
    this.previewBtn?.classList.toggle('hidden', !canPreview);
    if (!canPreview && this.previewVisible) {
      this.previewVisible = false;
      this.previewEl.classList.add('hidden');
      this.previewBtn?.setAttribute('aria-pressed', 'false');
    }

    this.conflictBannerEl?.classList.toggle('hidden', !tab.externalConflict);

    this.#syncVisuals(tab);
    this.#renderTabs();

    if (line && line >= 1) {
      this.jumpToLine(line);
    } else {
      this.textareaEl.scrollTop = tab.scrollTop || 0;
      this.gutterEl.scrollTop = this.textareaEl.scrollTop;
      this.highlightEl.scrollTop = this.textareaEl.scrollTop;
    }

    if (focus && !tab.binary) {
      this.textareaEl.focus();
    }
    this.#emitStatus();
  }

  jumpToLine(lineNo) {
    const val = this.textareaEl.value;
    const lines = val.split('\n');
    const target = Math.max(1, Math.min(Number(lineNo) || 1, lines.length));
    let offset = 0;
    for (let i = 0; i < target - 1; i++) {
      offset += lines[i].length + 1;
    }
    this.textareaEl.focus();
    this.textareaEl.setSelectionRange(offset, offset + (lines[target - 1]?.length || 0));
    const approxLineHeight = parseFloat(getComputedStyle(this.textareaEl).lineHeight) || 24;
    this.textareaEl.scrollTop = Math.max(0, (target - 5) * approxLineHeight);
    this.gutterEl.scrollTop = this.textareaEl.scrollTop;
    this.highlightEl.scrollTop = this.textareaEl.scrollTop;
    this.#emitStatus();
  }

  async saveActiveFile() {
    const tab = this.getActiveTab();
    if (!tab || tab.binary) return false;

    try {
      await this.workspaceClient.writeFile(tab.path, tab.content);
      tab.savedContent = tab.content;
      tab.dirty = false;
      tab.externalConflict = false;
      this.conflictBannerEl?.classList.add('hidden');
      if (this.saveBtn && this.activePath === tab.path) {
        this.saveBtn.disabled = true;
      }
      this.#renderTabs();
      this.#emitStatus();
      this.onSaved?.(tab.path);
      return true;
    } catch (err) {
      this.onError?.(`保存に失敗しました (${tab.path}): ${err.message}`);
      return false;
    }
  }

  closeTab(filePath, { force = false } = {}) {
    const idx = this.tabs.findIndex((t) => t.path === filePath);
    if (idx < 0) return;
    const tab = this.tabs[idx];
    if (tab.dirty && !force) {
      if (!window.confirm(`未保存の変更があります。\n${tab.path} を閉じますか？`)) {
        return;
      }
    }
    this.tabs.splice(idx, 1);
    if (this.activePath === filePath) {
      const next = this.tabs[Math.min(idx, this.tabs.length - 1)];
      if (next) this.activateTab(next.path, { focus: false });
      else this.#renderEmpty();
    } else {
      this.#renderTabs();
    }
  }

  closeAllTabs() {
    this.tabs = [];
    this.activePath = null;
    this.#renderEmpty();
  }

  async handleExternalChanges(changedPaths) {
    if (!Array.isArray(changedPaths) || changedPaths.length === 0) return;
    const changedSet = new Set(changedPaths.map((p) => String(p).replace(/^\/+/, '')));
    for (const tab of this.tabs) {
      if (!changedSet.has(tab.path)) continue;
      await this.reloadTab(tab.path, { force: false });
    }
  }

  async reloadTab(filePath, { force = false } = {}) {
    const tab = this.tabs.find((t) => t.path === filePath);
    if (!tab) return;
    try {
      const res = await this.workspaceClient.readFile(tab.path);
      if (res.binary) return;
      if (res.content === tab.content) {
        tab.savedContent = res.content;
        tab.dirty = false;
        tab.externalConflict = false;
        if (this.activePath === tab.path) {
          this.conflictBannerEl?.classList.add('hidden');
          if (this.saveBtn) this.saveBtn.disabled = true;
        }
        this.#renderTabs();
        return;
      }

      if (tab.dirty && !force) {
        tab.externalConflict = true;
        if (this.activePath === tab.path) {
          this.conflictBannerEl?.classList.remove('hidden');
        }
        return;
      }

      tab.content = res.content;
      tab.savedContent = res.content;
      tab.dirty = false;
      tab.externalConflict = false;
      if (this.activePath === tab.path) {
        const scrollTop = this.textareaEl.scrollTop;
        this.textareaEl.value = tab.content;
        this.textareaEl.scrollTop = scrollTop;
        this.conflictBannerEl?.classList.add('hidden');
        this.#syncVisuals(tab);
      }
      this.#renderTabs();
    } catch {
      // File may have been deleted externally; leave buffer in memory
    }
  }

  toggleMarkdownPreview(forceState = null) {
    const tab = this.getActiveTab();
    if (!tab || !isMarkdownFile(tab.path)) return;
    this.previewVisible = forceState !== null ? Boolean(forceState) : !this.previewVisible;
    this.previewEl.classList.toggle('hidden', !this.previewVisible);
    this.previewBtn?.setAttribute('aria-pressed', this.previewVisible ? 'true' : 'false');
    if (this.previewVisible) {
      this.previewEl.innerHTML = renderMarkdown(tab.content);
    }
  }

  focus() {
    if (this.activePath) {
      this.textareaEl.focus();
    }
  }

  #syncVisuals(tab) {
    const lineCount = Math.max(1, tab.content.split('\n').length);
    const nums = [];
    for (let i = 1; i <= lineCount; i++) nums.push(i);
    this.gutterEl.textContent = nums.join('\n');

    this.highlightEl.innerHTML = highlightCode(tab.content, tab.language);
    this.highlightEl.scrollTop = this.textareaEl.scrollTop;
    this.highlightEl.scrollLeft = this.textareaEl.scrollLeft;

    if (this.previewVisible && isMarkdownFile(tab.path)) {
      if (this._previewRaf) cancelAnimationFrame(this._previewRaf);
      this._previewRaf = requestAnimationFrame(() => {
        this._previewRaf = null;
        this.previewEl.innerHTML = renderMarkdown(tab.content);
      });
    }

    if (this.saveBtn) {
      this.saveBtn.disabled = !tab.dirty || tab.binary;
    }
  }

  #renderEmpty() {
    this.activePath = null;
    this.tabBarEl.innerHTML = '';
    this.toolbarPathEl.textContent = 'ファイル未選択';
    this.surfaceEl.classList.add('hidden');
    this.emptyEl.classList.remove('hidden');
    this.conflictBannerEl?.classList.add('hidden');
    this.previewBtn?.classList.add('hidden');
    if (this.saveBtn) this.saveBtn.disabled = true;
    this.#emitStatus();
  }

  #renderTabs() {
    this.tabBarEl.innerHTML = '';
    for (const tab of this.tabs) {
      const el = document.createElement('div');
      el.className = `editor-tab${tab.path === this.activePath ? ' active' : ''}${tab.dirty ? ' dirty' : ''}`;
      el.title = tab.path;

      const labelBtn = document.createElement('button');
      labelBtn.type = 'button';
      labelBtn.className = 'editor-tab-label';
      const baseName = tab.path.split('/').pop() || tab.path;
      labelBtn.textContent = tab.dirty ? `${baseName} ●` : baseName;
      labelBtn.addEventListener('click', () => this.activateTab(tab.path));

      // Desktop middle-click closes tab
      el.addEventListener('auxclick', (e) => {
        if (e.button === 1) {
          e.preventDefault();
          this.closeTab(tab.path);
        }
      });

      const closeBtn = document.createElement('button');
      closeBtn.type = 'button';
      closeBtn.className = 'editor-tab-close';
      closeBtn.setAttribute('aria-label', `${baseName} を閉じる`);
      closeBtn.textContent = '×';
      closeBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        this.closeTab(tab.path);
      });

      el.append(labelBtn, closeBtn);
      this.tabBarEl.appendChild(el);
    }
  }

  #emitStatus() {
    const tab = this.getActiveTab();
    const cursor = this.getCursorPosition();
    this.onStatusChange?.({
      path: tab?.path || '',
      language: tab?.language || '-',
      dirty: Boolean(tab?.dirty),
      line: cursor.line,
      col: cursor.col,
    });
  }
}
