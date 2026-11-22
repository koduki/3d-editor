/**
 * Desktop Quick Open (Ctrl+P), Command Palette (Ctrl+Shift+P / F1),
 * and Workspace Text Search (Ctrl+Shift+F) modal controller.
 */

export function fuzzyScore(query, target) {
  const q = String(query || '').trim().toLowerCase();
  const t = String(target || '').toLowerCase();
  if (!q) return 1;
  if (t === q) return 1000;
  if (t.includes(q)) return 500 - t.indexOf(q) - (t.length - q.length) * 0.1;

  // Ordered subsequence match
  let qi = 0;
  let score = 0;
  let streak = 0;
  for (let ti = 0; ti < t.length && qi < q.length; ti++) {
    if (t[ti] === q[qi]) {
      qi++;
      streak++;
      score += 10 + streak * 4;
    } else {
      streak = 0;
    }
  }
  return qi === q.length ? score : -1;
}

export class CommandPalette {
  constructor({ modalEl, inputEl, listEl, modeBadgeEl, getFiles, getCommands, searchWorkspace, onOpenFile }) {
    this.modalEl = modalEl;
    this.inputEl = inputEl;
    this.listEl = listEl;
    this.modeBadgeEl = modeBadgeEl;
    this.getFiles = getFiles;
    this.getCommands = getCommands;
    this.searchWorkspace = searchWorkspace;
    this.onOpenFile = onOpenFile;

    this.mode = 'files'; // 'files' | 'commands' | 'search'
    this.items = [];
    this.selectedIdx = 0;
    this._searchTimer = null;

    this.#bindEvents();
  }

  #bindEvents() {
    this.modalEl.addEventListener('click', (e) => {
      if (e.target === this.modalEl) this.close();
    });

    this.inputEl.addEventListener('input', () => {
      const val = this.inputEl.value;
      if (this.mode === 'files' && val.startsWith('>')) {
        this.mode = 'commands';
        this.inputEl.value = val.slice(1).trimStart();
        this.#updateHeader();
      }
      this.#refresh();
    });

    this.inputEl.addEventListener('keydown', (e) => {
      if (e.isComposing || e.keyCode === 229) return;
      if (e.key === 'Escape') {
        e.preventDefault();
        this.close();
        return;
      }
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        if (this.items.length > 0) {
          this.selectedIdx = (this.selectedIdx + 1) % this.items.length;
          this.#renderList();
        }
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        if (this.items.length > 0) {
          this.selectedIdx = (this.selectedIdx - 1 + this.items.length) % this.items.length;
          this.#renderList();
        }
        return;
      }
      if (e.key === 'Enter') {
        e.preventDefault();
        const chosen = this.items[this.selectedIdx];
        if (chosen) {
          this.close();
          chosen.run?.();
        }
      }
    });
  }

  isOpen() {
    return !this.modalEl.classList.contains('hidden');
  }

  open(mode = 'files', initialQuery = '') {
    this.mode = mode;
    this.inputEl.value = initialQuery;
    this.selectedIdx = 0;
    this.#updateHeader();
    this.modalEl.classList.remove('hidden');
    this.inputEl.focus();
    this.inputEl.select();
    this.#refresh();
  }

  close() {
    if (this._searchTimer) {
      clearTimeout(this._searchTimer);
      this._searchTimer = null;
    }
    this.modalEl.classList.add('hidden');
  }

  #updateHeader() {
    if (this.mode === 'commands') {
      this.modeBadgeEl.textContent = 'コマンドパレット';
      this.inputEl.placeholder = 'コマンドを検索… (OST切替 / 保存 / コンソール / 設定 など)';
    } else if (this.mode === 'search') {
      this.modeBadgeEl.textContent = 'ワークスペース全文検索';
      this.inputEl.placeholder = 'ファイル内の文字列を検索…';
    } else {
      this.modeBadgeEl.textContent = 'クイックファイル開く';
      this.inputEl.placeholder = 'ファイル名で絞り込み… (先頭に > でコマンドパレット)';
    }
  }

  #refresh() {
    const q = this.inputEl.value.trim();

    if (this.mode === 'search') {
      if (this._searchTimer) clearTimeout(this._searchTimer);
      if (q.length < 2) {
        this.items = [];
        this.#renderList('2文字以上入力して検索してください');
        return;
      }
      this.#renderList('検索中…');
      this._searchTimer = setTimeout(async () => {
        try {
          const res = await this.searchWorkspace(q);
          this.items = (res.results || []).map((r) => ({
            title: `${r.path}:${r.line}`,
            subtitle: r.text,
            shortcut: '',
            run: () => this.onOpenFile(r.path, r.line),
          }));
          this.selectedIdx = 0;
          this.#renderList('一致する行は見つかりませんでした');
        } catch (err) {
          this.items = [];
          this.#renderList(`検索エラー: ${err.message}`);
        }
      }, 150);
      return;
    }

    if (this.mode === 'commands') {
      const cmds = this.getCommands() || [];
      this.items = cmds
        .map((c) => ({
          ...c,
          score: fuzzyScore(q, `${c.title} ${c.keywords || ''}`),
        }))
        .filter((c) => c.score >= 0)
        .sort((a, b) => b.score - a.score)
        .slice(0, 40);
      this.selectedIdx = 0;
      this.#renderList('該当するコマンドがありません');
      return;
    }

    // Files mode
    const files = this.getFiles() || [];
    this.items = files
      .map((filePath) => ({
        title: filePath.split('/').pop() || filePath,
        subtitle: filePath,
        shortcut: '',
        score: fuzzyScore(q, filePath),
        run: () => this.onOpenFile(filePath, 1),
      }))
      .filter((item) => item.score >= 0)
      .sort((a, b) => b.score - a.score)
      .slice(0, 50);
    this.selectedIdx = 0;
    this.#renderList('一致するファイルがありません');
  }

  #renderList(emptyText = '結果なし') {
    this.listEl.innerHTML = '';
    if (this.items.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'palette-empty';
      empty.textContent = emptyText;
      this.listEl.appendChild(empty);
      return;
    }

    this.items.forEach((item, idx) => {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = `palette-item${idx === this.selectedIdx ? ' selected' : ''}`;

      const left = document.createElement('div');
      left.className = 'palette-item-left';

      const title = document.createElement('div');
      title.className = 'palette-item-title';
      title.textContent = item.title;
      left.appendChild(title);

      if (item.subtitle) {
        const sub = document.createElement('div');
        sub.className = 'palette-item-sub';
        sub.textContent = item.subtitle;
        left.appendChild(sub);
      }

      row.appendChild(left);

      if (item.shortcut) {
        const kbd = document.createElement('kbd');
        kbd.className = 'palette-item-kbd';
        kbd.textContent = item.shortcut;
        row.appendChild(kbd);
      }

      row.addEventListener('mouseenter', () => {
        this.selectedIdx = idx;
        for (const el of this.listEl.children) el.classList.remove('selected');
        row.classList.add('selected');
      });

      row.addEventListener('click', () => {
        this.close();
        item.run?.();
      });

      this.listEl.appendChild(row);
    });
  }
}
