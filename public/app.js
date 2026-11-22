import { AcpClient } from './acp-client.js';
import { DemoAcpClient } from './demo-client.js';
import { renderMarkdown } from './markdown.js';
import { renderUnifiedDiffElement } from './diff.js';
import {
  loadProfileStore,
  saveProfileStore,
  getActiveProfile,
  upsertProfile,
  deleteProfile,
  buildProfileWsUrl,
  deriveHttpBaseUrl,
  diagnoseBridgeConnection,
} from './profiles.js';
import { createHistoryStore, deriveSessionTitle } from './history-store.js';
import { WorkspaceClient, flattenTreeFiles } from './workspace-client.js';
import { SpatialEditor, isMarkdownFile } from './editor.js';
import { SpatialConsole } from './console.js';
import { CommandPalette } from './palette.js';
import { createXrHostBridge } from './xr-host.js';

const SESSION_MAP_KEY = 'xr-acp-companion:sessions:v2';
const LAYOUT_STORAGE_KEY = 'xr-acp-workbench:layout:v1';

const FALLBACK_MODES = [
  { id: 'default', name: 'default' },
  { id: 'accept-edits', name: 'accept-edits' },
  { id: 'plan', name: 'plan' },
];

// ============================================================================
// DOM References
// ============================================================================

const $ = (id) => document.getElementById(id);

const els = {
  statusDot: $('statusDot'),
  statusText: $('statusText'),
  demoBadge: $('demoBadge'),
  quickOpenBtn: $('quickOpenBtn'),
  cmdPaletteBtn: $('cmdPaletteBtn'),
  globalSearchBtn: $('globalSearchBtn'),
  toggleExplorerBtn: $('toggleExplorerBtn'),
  toggleConsoleBtn: $('toggleConsoleBtn'),
  toggleAiBtn: $('toggleAiBtn'),
  profileQuickSelect: $('profileQuickSelect'),
  themeToggleBtn: $('themeToggleBtn'),
  settingsBtn: $('settingsBtn'),

  workbenchBody: $('workbenchBody'),
  explorerPane: $('explorerPane'),
  explorerResizer: $('explorerResizer'),
  workspaceNameLabel: $('workspaceNameLabel'),
  newFileBtn: $('newFileBtn'),
  newDirBtn: $('newDirBtn'),
  refreshTreeBtn: $('refreshTreeBtn'),
  treeFilterInput: $('treeFilterInput'),
  fileTree: $('fileTree'),

  editorPane: $('editorPane'),
  editorTabs: $('editorTabs'),
  editorToolbarPath: $('editorToolbarPath'),
  editorAttachAiBtn: $('editorAttachAiBtn'),
  editorPreviewBtn: $('editorPreviewBtn'),
  editorSaveBtn: $('editorSaveBtn'),
  editorConflictBanner: $('editorConflictBanner'),
  editorConflictReloadBtn: $('editorConflictReloadBtn'),
  editorConflictKeepBtn: $('editorConflictKeepBtn'),
  editorEmptyState: $('editorEmptyState'),
  welcomeQuickOpenBtn: $('welcomeQuickOpenBtn'),
  welcomeCmdPaletteBtn: $('welcomeCmdPaletteBtn'),
  welcomeNewFileBtn: $('welcomeNewFileBtn'),
  editorSurface: $('editorSurface'),
  editorGutter: $('editorGutter'),
  editorHighlight: $('editorHighlight'),
  editorTextarea: $('editorTextarea'),
  editorPreview: $('editorPreview'),

  consoleResizer: $('consoleResizer'),
  consolePane: $('consolePane'),
  consoleStatus: $('consoleStatus'),
  consoleStopBtn: $('consoleStopBtn'),
  consoleCopyBtn: $('consoleCopyBtn'),
  consoleClearBtn: $('consoleClearBtn'),
  consoleCloseBtn: $('consoleCloseBtn'),
  consoleOutput: $('consoleOutput'),
  consoleInput: $('consoleInput'),

  aiResizer: $('aiResizer'),
  aiPane: $('aiPane'),
  usageBadge: $('usageBadge'),
  modeSelect: $('modeSelect'),
  historyBtn: $('historyBtn'),
  newSessionBtn: $('newSessionBtn'),
  noticeRegion: $('noticeRegion'),
  timeline: $('timeline'),
  emptyState: $('emptyState'),
  demoQuickPrompts: $('demoQuickPrompts'),

  permissionBar: $('permissionBar'),
  permKindBadge: $('permKindBadge'),
  permTitle: $('permTitle'),
  permQueueBadge: $('permQueueBadge'),
  permMeta: $('permMeta'),
  permPreview: $('permPreview'),
  permActions: $('permActions'),

  aiContextBar: $('aiContextBar'),
  slashBar: $('slashBar'),
  voiceBanner: $('voiceBanner'),
  voiceBannerText: $('voiceBannerText'),
  voiceBtn: $('voiceBtn'),
  promptInput: $('promptInput'),
  cancelBtn: $('cancelBtn'),
  sendBtn: $('sendBtn'),
  sessionLabel: $('sessionLabel'),
  imeIndicator: $('imeIndicator'),

  statusBranch: $('statusBranch'),
  statusFileLang: $('statusFileLang'),
  statusCursor: $('statusCursor'),
  statusHostBadge: $('statusHostBadge'),

  contextMenu: $('contextMenu'),

  paletteModal: $('paletteModal'),
  paletteModeBadge: $('paletteModeBadge'),
  paletteInput: $('paletteInput'),
  paletteList: $('paletteList'),

  historyModal: $('historyModal'),
  closeHistoryBtn: $('closeHistoryBtn'),
  historyList: $('historyList'),
  clearHistoryBtn: $('clearHistoryBtn'),
  historyNewSessionBtn: $('historyNewSessionBtn'),

  settingsModal: $('settingsModal'),
  closeSettingsBtn: $('closeSettingsBtn'),
  settingsForm: $('settingsForm'),
  profileEditSelect: $('profileEditSelect'),
  addProfileBtn: $('addProfileBtn'),
  deleteProfileBtn: $('deleteProfileBtn'),
  profileNameInput: $('profileNameInput'),
  profileModeSelect: $('profileModeSelect'),
  bridgeFields: $('bridgeFields'),
  agentSelect: $('agentSelect'),
  wsUrlInput: $('wsUrlInput'),
  tokenInput: $('tokenInput'),
  workspaceInput: $('workspaceInput'),
  testConnectionBtn: $('testConnectionBtn'),
  diagResult: $('diagResult'),
  profileThemeSelect: $('profileThemeSelect'),
  dimOnBlurToggle: $('dimOnBlurToggle'),
  resetProcessBtn: $('resetProcessBtn'),
};

// ============================================================================
// Persistent Stores & Spatial Layout State
// ============================================================================

const xrHost = createXrHostBridge();
const profileStore = loadProfileStore(window.localStorage, window.location.search);
const historyStore = createHistoryStore();
const workspaceClient = new WorkspaceClient();

function loadLayoutState() {
  const defaults = {
    explorerWidth: 245,
    aiWidth: 420,
    consoleHeight: 190,
    showExplorer: true,
    showConsole: true,
    showAi: true,
  };
  try {
    const raw = localStorage.getItem(LAYOUT_STORAGE_KEY);
    if (raw) Object.assign(defaults, JSON.parse(raw));
  } catch {
    // ignore
  }
  return defaults;
}

function saveLayoutState() {
  try {
    localStorage.setItem(LAYOUT_STORAGE_KEY, JSON.stringify(layoutState));
  } catch {
    // ignore
  }
}

const layoutState = loadLayoutState();

function applyLayoutState() {
  const root = document.documentElement.style;
  root.setProperty('--explorer-w', `${layoutState.explorerWidth}px`);
  root.setProperty('--ai-w', `${layoutState.aiWidth}px`);
  root.setProperty('--console-h', `${layoutState.consoleHeight}px`);

  els.workbenchBody.classList.toggle('hide-explorer', !layoutState.showExplorer);
  els.workbenchBody.classList.toggle('hide-console', !layoutState.showConsole);
  els.workbenchBody.classList.toggle('hide-ai', !layoutState.showAi);

  els.toggleExplorerBtn.classList.toggle('active', layoutState.showExplorer);
  els.toggleExplorerBtn.setAttribute('aria-pressed', String(layoutState.showExplorer));
  els.toggleConsoleBtn.classList.toggle('active', layoutState.showConsole);
  els.toggleConsoleBtn.setAttribute('aria-pressed', String(layoutState.showConsole));
  els.toggleAiBtn.classList.toggle('active', layoutState.showAi);
  els.toggleAiBtn.setAttribute('aria-pressed', String(layoutState.showAi));
}

function initResizers() {
  function wireSplitter(handleEl, orientation, onDrag) {
    handleEl.addEventListener('mousedown', (e) => {
      e.preventDefault();
      handleEl.classList.add('dragging');
      document.body.classList.add(orientation === 'v' ? 'resizing-v' : 'resizing-h');

      const onMove = (moveEvent) => onDrag(moveEvent);
      const onUp = () => {
        handleEl.classList.remove('dragging');
        document.body.classList.remove('resizing-v', 'resizing-h');
        window.removeEventListener('mousemove', onMove);
        window.removeEventListener('mouseup', onUp);
        saveLayoutState();
      };
      window.addEventListener('mousemove', onMove);
      window.addEventListener('mouseup', onUp);
    });
  }

  wireSplitter(els.explorerResizer, 'v', (ev) => {
    const next = Math.max(170, Math.min(440, ev.clientX));
    layoutState.explorerWidth = Math.round(next);
    document.documentElement.style.setProperty('--explorer-w', `${layoutState.explorerWidth}px`);
  });

  wireSplitter(els.aiResizer, 'v', (ev) => {
    const next = Math.max(300, Math.min(680, window.innerWidth - ev.clientX));
    layoutState.aiWidth = Math.round(next);
    document.documentElement.style.setProperty('--ai-w', `${layoutState.aiWidth}px`);
  });

  wireSplitter(els.consoleResizer, 'h', (ev) => {
    const bodyRect = els.workbenchBody.getBoundingClientRect();
    const next = Math.max(110, Math.min(bodyRect.height - 180, bodyRect.bottom - ev.clientY));
    layoutState.consoleHeight = Math.round(next);
    document.documentElement.style.setProperty('--console-h', `${layoutState.consoleHeight}px`);
  });
}

function getSessionStorageKey(profileId, agentKey) {
  return `${profileId}::${agentKey}`;
}

function getSavedSessionId(profileId, agentKey) {
  try {
    const map = JSON.parse(localStorage.getItem(SESSION_MAP_KEY) || '{}');
    return map[getSessionStorageKey(profileId, agentKey)] || null;
  } catch {
    return null;
  }
}

function setSavedSessionId(profileId, agentKey, sessionId) {
  if (!profileId || !agentKey) return;
  try {
    const map = JSON.parse(localStorage.getItem(SESSION_MAP_KEY) || '{}');
    const key = getSessionStorageKey(profileId, agentKey);
    if (sessionId) map[key] = sessionId;
    else delete map[key];
    localStorage.setItem(SESSION_MAP_KEY, JSON.stringify(map));
  } catch {
    // ignore
  }
}

// ============================================================================
// App State
// ============================================================================

const wsClient = new AcpClient();
const demoClient = new DemoAcpClient();
let activeClient = wsClient;

let serverConfig = {
  workspaceDir: '/',
  workspaceName: 'workspace',
  defaultAgent: 'agy',
  agents: [
    { key: 'agy', label: 'agy acp' },
    { key: 'copilot', label: 'copilot --acp' },
    { key: 'gemini', label: 'gemini --acp' },
    { key: 'mock', label: 'Mock ACP Agent (Demo)' },
  ],
  authRequired: false,
};

let workspaceTree = [];
let flatWorkspaceFiles = [];
let gitStatusMap = {};
const expandedDirs = new Set(['public', 'scripts', 'docs', 'src', 'posts']);

let isRunning = false;
let activeTurnSeq = 0;
let currentAgentTurn = null;
let lastReplayedUserCard = null;
const toolCallsById = new Map();
const permissionQueue = [];
const attachedContexts = []; // [{ id, label, text }]

let timelineEntries = [];
let sessionCreatedAt = Date.now();
let lastUsageSnapshot = null;
let persistTimer = null;
let bridgeFailureCount = 0;
let offeredDemoHint = false;
let editingProfileId = profileStore.activeProfileId;

// Strict IME composition tracking
let imeComposing = false;
let lastCompositionEndAt = 0;

// Speech Recognition state
let recognition = null;
let isRecording = false;
let voiceBaseText = '';

// ============================================================================
// Spatial Editor, Lightweight Console, Command Palette & Context Menu
// ============================================================================

const spatialEditor = new SpatialEditor({
  tabBarEl: els.editorTabs,
  toolbarPathEl: els.editorToolbarPath,
  saveBtn: els.editorSaveBtn,
  previewBtn: els.editorPreviewBtn,
  attachAiBtn: els.editorAttachAiBtn,
  conflictBannerEl: els.editorConflictBanner,
  conflictReloadBtn: els.editorConflictReloadBtn,
  conflictKeepBtn: els.editorConflictKeepBtn,
  emptyEl: els.editorEmptyState,
  surfaceEl: els.editorSurface,
  gutterEl: els.editorGutter,
  highlightEl: els.editorHighlight,
  textareaEl: els.editorTextarea,
  previewEl: els.editorPreview,
  workspaceClient,
  onStatusChange: (status) => {
    els.statusFileLang.textContent = status.language || '-';
    els.statusCursor.textContent = `Ln ${status.line}, Col ${status.col}`;
    if (status.path) {
      xrHost.notifyWindowTitle(`${status.dirty ? '● ' : ''}${status.path} — XR-ACP Workbench`);
    } else {
      xrHost.notifyWindowTitle('XR-ACP Workbench');
    }
    highlightActiveTreeRow(status.path);
  },
  onSaved: async (savedPath) => {
    showNotice({ severity: 'info', title: `保存しました: ${savedPath}` });
    await refreshGitStatus();
  },
  onError: (msg) => {
    showNotice({ severity: 'error', title: 'エディタエラー', description: msg });
  },
  onAttachContext: (ctx) => {
    attachEditorContextToAi(ctx);
  },
  onContextMenu: (event, ctx) => {
    if (!ctx) return;
    openContextMenu(event.clientX, event.clientY, [
      {
        label: ctx.hasSelection
          ? `選択範囲 (${ctx.startLine}-${ctx.endLine}行) を AI へ添付`
          : 'このファイルを AI へ添付',
        shortcut: 'Ctrl+L',
        onClick: () => attachEditorContextToAi(ctx),
      },
      {
        label: 'ファイルを保存',
        shortcut: 'Ctrl+S',
        onClick: () => spatialEditor.saveActiveFile(),
      },
      ...(isMarkdownFile(ctx.path)
        ? [
            {
              label: 'Markdown プレビューを切替',
              onClick: () => spatialEditor.toggleMarkdownPreview(),
            },
          ]
        : []),
      { sep: true },
      {
        label: 'ファイルパスをコピー',
        onClick: () => navigator.clipboard?.writeText(ctx.path).catch(() => {}),
      },
    ]);
  },
});

const spatialConsole = new SpatialConsole({
  outputEl: els.consoleOutput,
  inputEl: els.consoleInput,
  statusEl: els.consoleStatus,
  stopBtn: els.consoleStopBtn,
  clearBtn: els.consoleClearBtn,
  copyBtn: els.consoleCopyBtn,
  onJumpToFile: (filePath, line) => {
    spatialEditor.openFile(filePath, { line, focus: true });
  },
});

const commandPalette = new CommandPalette({
  modalEl: els.paletteModal,
  inputEl: els.paletteInput,
  listEl: els.paletteList,
  modeBadgeEl: els.paletteModeBadge,
  getFiles: () => flatWorkspaceFiles,
  getCommands: () => buildCommandCatalog(),
  searchWorkspace: (q) => workspaceClient.search(q),
  onOpenFile: (filePath, line) => spatialEditor.openFile(filePath, { line, focus: true }),
});

function buildCommandCatalog() {
  return [
    {
      title: '光学テーマ切替: OST (XREAL AURA) / VST (Meta Quest 3)',
      subtitle: `現在: ${profileStore.theme.toUpperCase()}`,
      keywords: 'theme ost vst optical dark',
      run: () => applyTheme(profileStore.theme === 'ost' ? 'vst' : 'ost'),
    },
    {
      title: 'ファイルをクイックオープン',
      shortcut: 'Ctrl+P',
      keywords: 'open file quick',
      run: () => commandPalette.open('files'),
    },
    {
      title: 'ワークスペース全文検索',
      shortcut: 'Ctrl+Shift+F',
      keywords: 'search grep find',
      run: () => commandPalette.open('search'),
    },
    {
      title: 'アクティブファイルを保存',
      shortcut: 'Ctrl+S',
      keywords: 'save write file',
      run: () => spatialEditor.saveActiveFile(),
    },
    {
      title: 'Markdown ライブプレビューを切替',
      keywords: 'markdown preview md',
      run: () => spatialEditor.toggleMarkdownPreview(),
    },
    {
      title: 'ファイルツリー表示を切替',
      shortcut: 'Ctrl+B',
      keywords: 'explorer sidebar tree toggle',
      run: () => togglePane('showExplorer'),
    },
    {
      title: '軽量コマンドコンソールを切替',
      shortcut: 'Ctrl+J',
      keywords: 'console terminal toggle',
      run: () => togglePane('showConsole', { focusConsole: true }),
    },
    {
      title: 'AI エージェントパネルを切替',
      shortcut: 'Alt+3',
      keywords: 'ai agent acp toggle',
      run: () => togglePane('showAi'),
    },
    {
      title: '現在のファイル／選択範囲を AI コンテキストへ添付',
      shortcut: 'Ctrl+L',
      keywords: 'attach context ai prompt',
      run: () => {
        const ctx = spatialEditor.getSelectionContext();
        if (ctx) attachEditorContextToAi(ctx);
        focusAiComposer();
      },
    },
    {
      title: '新規ファイルを作成',
      keywords: 'new create file',
      run: () => promptCreateFile(''),
    },
    {
      title: '新規フォルダを作成',
      keywords: 'new create directory folder',
      run: () => promptCreateDir(''),
    },
    {
      title: 'コンソールでテストを実行 (npm test)',
      keywords: 'run test npm console',
      run: () => {
        ensurePaneVisible('showConsole');
        spatialConsole.runCommand('npm test');
      },
    },
    {
      title: 'コンソールで Git ステータス確認 (git status)',
      keywords: 'git status console',
      run: () => {
        ensurePaneVisible('showConsole');
        spatialConsole.runCommand('git status');
      },
    },
    {
      title: 'AI セッション履歴を開く',
      keywords: 'history sessions resume',
      run: () => openHistoryModal(),
    },
    {
      title: '新規 AI セッションを開始 (session/new)',
      keywords: 'new session reset ai',
      run: () => startFreshSession(),
    },
    {
      title: '接続プロファイル・表示設定を開く',
      keywords: 'settings profile tailscale token',
      run: () => openSettingsModal(),
    },
    {
      title: 'オフラインデモモードへ切替',
      keywords: 'demo offline mode',
      run: () => switchToDemoMode(),
    },
    {
      title: 'AI パネルを別空間ウィンドウへ分離',
      keywords: 'detach spatial window panel xr',
      run: () => xrHost.detachSpatialPanel('ai'),
    },
  ];
}

// ============================================================================
// Custom Desktop Right-Click Context Menu
// ============================================================================

function closeContextMenu() {
  els.contextMenu.classList.add('hidden');
  els.contextMenu.innerHTML = '';
}

function openContextMenu(clientX, clientY, items) {
  els.contextMenu.innerHTML = '';
  for (const item of items) {
    if (item.sep) {
      const sep = document.createElement('div');
      sep.className = 'context-menu-sep';
      els.contextMenu.appendChild(sep);
      continue;
    }
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `context-menu-item${item.danger ? ' danger' : ''}`;
    btn.setAttribute('role', 'menuitem');

    const label = document.createElement('span');
    label.textContent = item.label;
    btn.appendChild(label);

    if (item.shortcut) {
      const kbd = document.createElement('kbd');
      kbd.textContent = item.shortcut;
      btn.appendChild(kbd);
    }

    btn.addEventListener('click', () => {
      closeContextMenu();
      item.onClick?.();
    });
    els.contextMenu.appendChild(btn);
  }

  els.contextMenu.classList.remove('hidden');
  const rect = els.contextMenu.getBoundingClientRect();
  const x = Math.min(clientX, window.innerWidth - rect.width - 8);
  const y = Math.min(clientY, window.innerHeight - rect.height - 8);
  els.contextMenu.style.left = `${Math.max(4, x)}px`;
  els.contextMenu.style.top = `${Math.max(4, y)}px`;
}

document.addEventListener('click', (e) => {
  if (!els.contextMenu.classList.contains('hidden') && !els.contextMenu.contains(e.target)) {
    closeContextMenu();
  }
});

// ============================================================================
// Workspace Explorer Tree & Git Status
// ============================================================================

function getFileIconLabel(name, isDir) {
  if (isDir) return { text: 'DIR', cls: 'tree-icon-dir' };
  const ext = name.split('.').pop()?.toLowerCase() || '';
  if (ext === 'md' || ext === 'markdown') return { text: 'MD', cls: 'tree-icon-md' };
  if (['js', 'mjs', 'ts', 'tsx', 'jsx'].includes(ext)) return { text: ext.toUpperCase().slice(0, 2), cls: 'tree-icon-js' };
  if (ext === 'json') return { text: '{}', cls: '' };
  if (ext === 'css') return { text: '#', cls: '' };
  if (ext === 'html') return { text: '<>', cls: '' };
  if (ext === 'sh') return { text: '$_', cls: '' };
  return { text: '··', cls: '' };
}

async function refreshWorkspaceTree() {
  try {
    const res = await workspaceClient.getTree();
    workspaceTree = res.tree || [];
    flatWorkspaceFiles = flattenTreeFiles(workspaceTree);
    els.workspaceNameLabel.textContent = (res.workspaceName || 'WORKSPACE').toUpperCase();
    els.workspaceNameLabel.title = res.workspaceDir || '';
    renderFileTree();
  } catch (err) {
    els.fileTree.innerHTML = `<div class="tree-empty">ツリーを取得できません: ${err.message}</div>`;
  }
}

async function refreshGitStatus() {
  try {
    const git = await workspaceClient.getGitStatus();
    gitStatusMap = git.files || {};
    els.statusBranch.textContent = git.isGitRepo ? `⎇ ${git.branch || 'main'}` : '⎇ no-git';
    renderFileTree();
  } catch {
    els.statusBranch.textContent = '⎇ -';
  }
}

function filterTreeNodes(nodes, query) {
  if (!query) return nodes;
  const q = query.toLowerCase();
  const out = [];
  for (const node of nodes) {
    if (node.type === 'file') {
      if (node.path.toLowerCase().includes(q)) out.push(node);
    } else if (node.type === 'dir') {
      const matchedChildren = filterTreeNodes(node.children || [], query);
      if (matchedChildren.length > 0 || node.name.toLowerCase().includes(q)) {
        out.push({ ...node, children: matchedChildren });
      }
    }
  }
  return out;
}

function renderFileTree() {
  const filterText = els.treeFilterInput.value.trim();
  const nodes = filterTreeNodes(workspaceTree, filterText);
  els.fileTree.innerHTML = '';

  if (nodes.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'tree-empty';
    empty.textContent = filterText ? '一致するファイルがありません' : 'ワークスペースが空です';
    els.fileTree.appendChild(empty);
    return;
  }

  const activeFile = spatialEditor.getActiveTab()?.path || '';

  function appendNodes(list, depth) {
    for (const node of list) {
      const isDir = node.type === 'dir';
      const isOpen = Boolean(filterText) || expandedDirs.has(node.path);

      const row = document.createElement('div');
      row.className = `tree-row${!isDir && node.path === activeFile ? ' active' : ''}`;
      row.style.paddingLeft = `${8 + depth * 14}px`;
      row.dataset.treePath = node.path;
      row.setAttribute('role', 'treeitem');

      const left = document.createElement('div');
      left.className = 'tree-row-left';

      const chevron = document.createElement('span');
      chevron.className = 'tree-chevron';
      chevron.textContent = isDir ? (isOpen ? '▾' : '▸') : '';

      const iconInfo = getFileIconLabel(node.name, isDir);
      const icon = document.createElement('span');
      icon.className = `tree-icon ${iconInfo.cls}`;
      icon.textContent = iconInfo.text;

      const name = document.createElement('span');
      name.className = 'tree-name';
      name.textContent = node.name;
      name.title = node.path;

      left.append(chevron, icon, name);
      row.appendChild(left);

      const gitCode = gitStatusMap[node.path];
      if (!isDir && gitCode) {
        const badge = document.createElement('span');
        const normalized = gitCode.includes('?') ? 'U' : gitCode[0] || 'M';
        badge.className = `tree-git-badge tree-git-${normalized}`;
        badge.textContent = normalized;
        badge.title = `Git: ${gitCode}`;
        row.appendChild(badge);
      }

      row.addEventListener('click', () => {
        if (isDir) {
          if (expandedDirs.has(node.path)) expandedDirs.delete(node.path);
          else expandedDirs.add(node.path);
          renderFileTree();
        } else {
          spatialEditor.openFile(node.path, { focus: true });
        }
      });

      row.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        const baseDir = isDir ? node.path : node.path.includes('/') ? node.path.split('/').slice(0, -1).join('/') : '';
        openContextMenu(e.clientX, e.clientY, [
          ...(!isDir
            ? [
                { label: 'エディタで開く', onClick: () => spatialEditor.openFile(node.path, { focus: true }) },
                {
                  label: 'AI コンテキストへ添付',
                  onClick: async () => {
                    try {
                      const file = await workspaceClient.readFile(node.path);
                      attachContextChip({
                        id: `file:${node.path}`,
                        label: node.path,
                        text: `ファイル \`${node.path}\`:\n\`\`\`\n${file.content.slice(0, 6000)}\n\`\`\``,
                      });
                      focusAiComposer();
                    } catch (err) {
                      showNotice({ severity: 'error', title: '読み込み失敗', description: err.message });
                    }
                  },
                },
                { sep: true },
              ]
            : []),
          { label: 'ここに新規ファイルを作成', onClick: () => promptCreateFile(baseDir) },
          { label: 'ここに新規フォルダを作成', onClick: () => promptCreateDir(baseDir) },
          { label: '名前を変更', onClick: () => promptRenameEntry(node.path) },
          {
            label: '相対パスをコピー',
            onClick: () => navigator.clipboard?.writeText(node.path).catch(() => {}),
          },
          { sep: true },
          { label: '削除', danger: true, onClick: () => promptDeleteEntry(node.path) },
        ]);
      });

      els.fileTree.appendChild(row);

      if (isDir && isOpen && Array.isArray(node.children)) {
        appendNodes(node.children, depth + 1);
      }
    }
  }

  appendNodes(nodes, 0);
}

function highlightActiveTreeRow(activePath) {
  for (const row of els.fileTree.querySelectorAll('.tree-row')) {
    row.classList.toggle('active', Boolean(activePath) && row.dataset.treePath === activePath);
  }
}

async function promptCreateFile(baseDir = '') {
  const suggestion = baseDir ? `${baseDir}/untitled.md` : 'untitled.md';
  const input = window.prompt('作成するファイルパス（ワークスペース相対）:', suggestion);
  if (!input || !input.trim()) return;
  const rel = input.trim().replace(/^\/+/, '');
  try {
    await workspaceClient.writeFile(rel, '');
    await refreshWorkspaceTree();
    await spatialEditor.openFile(rel, { focus: true });
  } catch (err) {
    showNotice({ severity: 'error', title: 'ファイル作成失敗', description: err.message });
  }
}

async function promptCreateDir(baseDir = '') {
  const suggestion = baseDir ? `${baseDir}/new-folder` : 'new-folder';
  const input = window.prompt('作成するフォルダパス（ワークスペース相対）:', suggestion);
  if (!input || !input.trim()) return;
  const rel = input.trim().replace(/^\/+/, '');
  try {
    await workspaceClient.mkdir(rel);
    expandedDirs.add(rel);
    await refreshWorkspaceTree();
  } catch (err) {
    showNotice({ severity: 'error', title: 'フォルダ作成失敗', description: err.message });
  }
}

async function promptRenameEntry(fromPath) {
  const next = window.prompt('新しいパスを入力:', fromPath);
  if (!next || !next.trim() || next.trim() === fromPath) return;
  try {
    await workspaceClient.rename(fromPath, next.trim());
    spatialEditor.closeTab(fromPath, { force: true });
    await refreshWorkspaceTree();
    await refreshGitStatus();
  } catch (err) {
    showNotice({ severity: 'error', title: '名前変更失敗', description: err.message });
  }
}

async function promptDeleteEntry(targetPath) {
  if (!window.confirm(`本当に削除しますか？\n${targetPath}`)) return;
  try {
    await workspaceClient.deleteEntry(targetPath);
    spatialEditor.closeTab(targetPath, { force: true });
    await refreshWorkspaceTree();
    await refreshGitStatus();
  } catch (err) {
    showNotice({ severity: 'error', title: '削除失敗', description: err.message });
  }
}

workspaceClient.addEventListener('fs_changed', async (e) => {
  const paths = e.detail?.paths || [];
  await spatialEditor.handleExternalChanges(paths);
  await refreshWorkspaceTree();
  await refreshGitStatus();
});

// ============================================================================
// AI Context Chips (Editor -> ACP Prompt Handoff)
// ============================================================================

function attachContextChip(chip) {
  const existingIdx = attachedContexts.findIndex((c) => c.id === chip.id);
  if (existingIdx >= 0) attachedContexts[existingIdx] = chip;
  else attachedContexts.push(chip);
  renderAttachedContexts();
}

function attachEditorContextToAi(ctx) {
  if (!ctx) return;
  if (ctx.hasSelection && ctx.selection.trim()) {
    attachContextChip({
      id: `sel:${ctx.path}:${ctx.startLine}-${ctx.endLine}`,
      label: `${ctx.path}:${ctx.startLine}-${ctx.endLine}`,
      text: `対象ファイル \`${ctx.path}\` (${ctx.startLine}〜${ctx.endLine}行目):\n\`\`\`\n${ctx.selection}\n\`\`\``,
    });
  } else {
    attachContextChip({
      id: `file:${ctx.path}`,
      label: ctx.path,
      text: `現在開いているファイル \`${ctx.path}\`:\n\`\`\`\n${ctx.content.slice(0, 6000)}\n\`\`\``,
    });
  }
  focusAiComposer();
}

function renderAttachedContexts() {
  els.aiContextBar.innerHTML = '';
  if (attachedContexts.length === 0) {
    els.aiContextBar.classList.add('hidden');
    return;
  }
  for (const chip of attachedContexts) {
    const el = document.createElement('span');
    el.className = 'context-chip';
    const text = document.createElement('span');
    text.textContent = `@${chip.label}`;
    const rm = document.createElement('button');
    rm.type = 'button';
    rm.textContent = '×';
    rm.setAttribute('aria-label', `${chip.label} を削除`);
    rm.addEventListener('click', () => {
      const idx = attachedContexts.findIndex((c) => c.id === chip.id);
      if (idx >= 0) attachedContexts.splice(idx, 1);
      renderAttachedContexts();
    });
    el.append(text, rm);
    els.aiContextBar.appendChild(el);
  }
  els.aiContextBar.classList.remove('hidden');
}

// ============================================================================
// Pane Toggling & Direct Focus Helpers
// ============================================================================

function ensurePaneVisible(key) {
  if (!layoutState[key]) {
    layoutState[key] = true;
    applyLayoutState();
    saveLayoutState();
  }
}

function togglePane(key, { focusConsole = false } = {}) {
  layoutState[key] = !layoutState[key];
  applyLayoutState();
  saveLayoutState();
  if (focusConsole && layoutState.showConsole) {
    spatialConsole.focus();
  }
}

function focusAiComposer() {
  ensurePaneVisible('showAi');
  els.promptInput.focus();
}

// ============================================================================
// Theme & Blur Dimming
// ============================================================================

function applyTheme(theme, { persist = true } = {}) {
  const normalized = theme === 'vst' ? 'vst' : 'ost';
  profileStore.theme = normalized;
  document.documentElement.dataset.theme = normalized;
  els.themeToggleBtn.textContent = normalized.toUpperCase();
  els.themeToggleBtn.setAttribute('aria-pressed', normalized === 'vst' ? 'true' : 'false');

  const metaTheme = document.querySelector('meta[name="theme-color"]');
  if (metaTheme) {
    metaTheme.setAttribute('content', normalized === 'ost' ? '#000000' : '#10131a');
  }
  if (persist) {
    saveProfileStore(profileStore);
  }
}

function applyBlurDimming(dimmed) {
  if (profileStore.dimOnBlur && dimmed) {
    document.body.classList.add('window-dimmed');
  } else {
    document.body.classList.remove('window-dimmed');
  }
}

window.addEventListener('blur', () => applyBlurDimming(true));
window.addEventListener('focus', () => applyBlurDimming(false));
document.addEventListener('visibilitychange', () => applyBlurDimming(document.hidden));

// ============================================================================
// Notices & Status Bar
// ============================================================================

function showNotice({ severity = 'info', title, description = '', actions = [] }) {
  const item = document.createElement('div');
  const sevClass = ['info', 'warning', 'error'].includes(severity) ? `notice-${severity}` : 'notice-info';
  item.className = `notice-item ${sevClass}`;

  const body = document.createElement('div');
  body.className = 'notice-body';

  const strong = document.createElement('strong');
  strong.textContent = title;
  body.appendChild(strong);

  if (description) {
    const desc = document.createElement('div');
    desc.textContent = description;
    body.appendChild(desc);
  }

  if (Array.isArray(actions) && actions.length > 0) {
    const actionsRow = document.createElement('div');
    actionsRow.className = 'notice-actions';
    for (const act of actions) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'notice-action-btn';
      btn.textContent = act.label;
      btn.addEventListener('click', () => {
        item.remove();
        act.onClick?.();
      });
      actionsRow.appendChild(btn);
    }
    body.appendChild(actionsRow);
  }

  const closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'notice-close';
  closeBtn.setAttribute('aria-label', '通知を閉じる');
  closeBtn.textContent = '×';
  closeBtn.addEventListener('click', () => item.remove());

  item.append(body, closeBtn);
  els.noticeRegion.appendChild(item);

  while (els.noticeRegion.children.length > 3) {
    els.noticeRegion.firstElementChild.remove();
  }
}

function getCurrentProfile() {
  return getActiveProfile(profileStore);
}

function getActiveAgentKey(profile = getCurrentProfile()) {
  if (profile.mode === 'demo') return 'demo';
  return profile.agent || serverConfig.defaultAgent || 'agy';
}

function renderProfileQuickSelect() {
  els.profileQuickSelect.innerHTML = '';
  for (const p of profileStore.profiles) {
    const opt = document.createElement('option');
    opt.value = p.id;
    opt.textContent = p.name;
    els.profileQuickSelect.appendChild(opt);
  }
  els.profileQuickSelect.value = profileStore.activeProfileId;
}

function updateDemoUiFlags() {
  const isDemo = getCurrentProfile().mode === 'demo';
  els.demoBadge.classList.toggle('hidden', !isDemo);
  els.demoQuickPrompts.classList.toggle('hidden', !isDemo);
}

function updateConnectionUi(state, detail = {}) {
  els.statusDot.className = `status-dot status-${state}`;
  const profile = getCurrentProfile();
  const agentName = activeClient.agentInfo?.title || activeClient.agentInfo?.name || getActiveAgentKey(profile);

  switch (state) {
    case 'connecting':
      els.statusText.textContent = `${agentName} 接続中…`;
      break;
    case 'initializing':
      els.statusText.textContent = `${agentName} 初期化中…`;
      break;
    case 'ready':
      els.statusText.textContent = `${agentName}`;
      bridgeFailureCount = 0;
      break;
    case 'reconnecting':
      els.statusText.textContent = detail.reason || '再接続中…';
      if (profile.mode === 'bridge') {
        bridgeFailureCount++;
        if (bridgeFailureCount >= 2 && !offeredDemoHint) {
          offeredDemoHint = true;
          showNotice({
            severity: 'warning',
            title: 'バックエンドへ接続できません',
            description: 'server.mjs が未起動か Tailscale 設定が未完了の可能性があります。',
            actions: [
              { label: '設定・接続テストを開く', onClick: () => openSettingsModal() },
              { label: 'オフラインデモで試す', onClick: () => switchToDemoMode() },
            ],
          });
        }
      }
      break;
    case 'error':
      els.statusText.textContent = detail.message || '接続エラー';
      break;
    default:
      els.statusText.textContent = detail.reason || '未接続';
      break;
  }

  const ready = state === 'ready';
  els.modeSelect.disabled = !ready;
  els.sendBtn.disabled = !ready;
}

function updateRunningUi(running) {
  isRunning = running;
  els.cancelBtn.classList.toggle('hidden', !running);
  els.sendBtn.textContent = running ? '割り込み' : '送信';
}

function updateModesUi(modes) {
  const list =
    modes?.availableModes && modes.availableModes.length > 0 ? modes.availableModes : FALLBACK_MODES;
  const current = modes?.currentModeId || list[0]?.id || 'default';

  els.modeSelect.innerHTML = '';
  for (const mode of list) {
    const opt = document.createElement('option');
    opt.value = mode.id;
    opt.textContent = mode.name || mode.id;
    if (mode.description) opt.title = mode.description;
    els.modeSelect.appendChild(opt);
  }
  els.modeSelect.value = current;
}

function updateUsageUi(update) {
  if (!update || typeof update.used !== 'number' || typeof update.size !== 'number' || update.size <= 0) {
    els.usageBadge.classList.add('hidden');
    return;
  }
  lastUsageSnapshot = {
    used: update.used,
    size: update.size,
    cost: update.cost || null,
  };
  const pct = Math.min(100, Math.round((update.used / update.size) * 100));
  const usedK = update.used >= 1000 ? `${(update.used / 1000).toFixed(1)}k` : String(update.used);
  const sizeK = update.size >= 1000 ? `${Math.round(update.size / 1000)}k` : String(update.size);
  let label = `${usedK}/${sizeK} (${pct}%)`;
  if (update.cost && typeof update.cost.amount === 'number') {
    const cur = update.cost.currency === 'USD' ? '$' : `${update.cost.currency} `;
    label += ` · ${cur}${update.cost.amount.toFixed(3)}`;
  }
  els.usageBadge.textContent = label;
  els.usageBadge.classList.remove('hidden');
}

function renderSlashCommands(commands) {
  if (!Array.isArray(commands) || commands.length === 0) {
    els.slashBar.innerHTML = '';
    els.slashBar.classList.add('hidden');
    return;
  }
  els.slashBar.innerHTML = '';
  for (const cmd of commands) {
    const name = String(cmd?.name || '').replace(/^\//, '');
    if (!name) continue;
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'slash-chip';
    btn.textContent = `/${name}`;
    if (cmd.description) btn.title = cmd.description;
    btn.addEventListener('click', () => {
      const current = els.promptInput.value.trim();
      els.promptInput.value = current ? `/${name} ${current}` : `/${name} `;
      autoResizeTextarea();
      els.promptInput.focus();
    });
    els.slashBar.appendChild(btn);
  }
  els.slashBar.classList.remove('hidden');
}

// ============================================================================
// Local Session History Persistence (#8)
// ============================================================================

function schedulePersistSession() {
  if (!activeClient.sessionId || timelineEntries.length === 0) return;
  if (persistTimer) clearTimeout(persistTimer);
  persistTimer = setTimeout(() => {
    persistTimer = null;
    persistCurrentSessionNow();
  }, 180);
}

async function persistCurrentSessionNow() {
  const sessionId = activeClient.sessionId;
  if (!sessionId || timelineEntries.length === 0) return;
  const profile = getCurrentProfile();
  const agentKey = getActiveAgentKey(profile);

  await historyStore.saveSession({
    sessionId,
    profileId: profile.id,
    agentKey,
    title: deriveSessionTitle(timelineEntries),
    modeId: els.modeSelect.value || 'default',
    usage: lastUsageSnapshot,
    createdAt: sessionCreatedAt,
    updatedAt: Date.now(),
    entries: timelineEntries,
  });
}

function clearTimelineDom() {
  currentAgentTurn = null;
  lastReplayedUserCard = null;
  toolCallsById.clear();
  timelineEntries = [];
  lastUsageSnapshot = null;
  sessionCreatedAt = Date.now();
  els.usageBadge.classList.add('hidden');

  for (const child of [...els.timeline.children]) {
    if (child !== els.emptyState) {
      child.remove();
    }
  }
  els.emptyState.classList.remove('hidden');
}

async function restoreCachedSessionIfAvailable(sessionId) {
  const cached = await historyStore.getSession(sessionId);
  if (!cached || !Array.isArray(cached.entries) || cached.entries.length === 0) {
    return false;
  }

  clearTimelineDom();
  sessionCreatedAt = cached.createdAt || Date.now();
  if (cached.usage) updateUsageUi(cached.usage);

  for (const entry of cached.entries) {
    if (entry.type === 'user') {
      appendUserMessage(entry.text, { record: true, messageId: entry.messageId });
    } else if (entry.type === 'system') {
      appendSystemMessage(entry.text, { record: true });
    } else if (entry.type === 'agent') {
      const turn = ensureAgentTurn({
        agentName: entry.agentName,
        statusLabel: entry.statusLabel || '完了',
        record: true,
      });
      if (Array.isArray(entry.plan) && entry.plan.length > 0) {
        renderPlanUpdate(entry.plan, { persist: false });
      }
      if (entry.thought) {
        renderThoughtChunk(entry.thought, { persist: false });
      }
      if (Array.isArray(entry.tools)) {
        for (const tc of entry.tools) {
          const merged = mergeToolCall(tc);
          if (merged) renderToolCallCard(merged, { persist: false });
        }
      }
      if (entry.markdown) {
        turn.mdText = entry.markdown;
        turn.record.markdown = entry.markdown;
        turn.mdBody.innerHTML = renderMarkdown(entry.markdown);
      }
      finishAgentTurn(entry.statusLabel || '完了', { persist: false });
    }
  }

  if (permissionQueue.length > 0) {
    renderActivePermission();
  }
  scrollTimelineToBottom(true);
  return true;
}

// ============================================================================
// Timeline Rendering
// ============================================================================

function hideEmptyState() {
  if (els.emptyState && !els.emptyState.classList.contains('hidden')) {
    els.emptyState.classList.add('hidden');
  }
}

function scrollTimelineToBottom(force = false) {
  const el = els.timeline;
  const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
  if (force || distanceFromBottom < 180) {
    el.scrollTop = el.scrollHeight;
  }
}

function appendSystemMessage(text, { record = false } = {}) {
  hideEmptyState();
  const card = document.createElement('div');
  card.className = 'msg-card msg-system';
  card.textContent = text;
  els.timeline.appendChild(card);
  if (record) {
    timelineEntries.push({ type: 'system', text });
    schedulePersistSession();
  }
  scrollTimelineToBottom();
}

function appendUserMessage(text, { record = true, messageId = null } = {}) {
  hideEmptyState();
  lastReplayedUserCard = null;
  const card = document.createElement('article');
  card.className = 'msg-card msg-user';

  const role = document.createElement('div');
  role.className = 'msg-role';
  role.textContent = 'YOU';

  const body = document.createElement('div');
  body.className = 'msg-user-body';
  body.textContent = text;

  card.append(role, body);
  els.timeline.appendChild(card);

  let rec = null;
  if (record) {
    rec = { type: 'user', text, messageId };
    timelineEntries.push(rec);
    schedulePersistSession();
  }

  scrollTimelineToBottom(true);
  return { card, body, rec };
}

function appendUserChunk(text, messageId = null) {
  if (
    lastReplayedUserCard &&
    ((messageId && lastReplayedUserCard.messageId === messageId) || (!messageId && !lastReplayedUserCard.messageId))
  ) {
    lastReplayedUserCard.text += text;
    lastReplayedUserCard.body.textContent = lastReplayedUserCard.text;
    if (lastReplayedUserCard.rec) {
      lastReplayedUserCard.rec.text = lastReplayedUserCard.text;
      schedulePersistSession();
    }
    scrollTimelineToBottom();
    return;
  }
  const created = appendUserMessage(text, { record: true, messageId });
  lastReplayedUserCard = {
    messageId,
    text,
    body: created.body,
    rec: created.rec,
  };
}

function ensureAgentTurn({ agentName = null, statusLabel = '生成中…', record = true } = {}) {
  if (currentAgentTurn) return currentAgentTurn;

  hideEmptyState();
  lastReplayedUserCard = null;
  const card = document.createElement('article');
  card.className = 'msg-card msg-agent';

  const resolvedAgentName =
    agentName || activeClient.agentInfo?.title || activeClient.agentInfo?.name || 'AGENT';

  const role = document.createElement('div');
  role.className = 'msg-role';
  const roleName = document.createElement('span');
  roleName.textContent = resolvedAgentName;
  const roleState = document.createElement('span');
  roleState.className = 'turn-state';
  roleState.textContent = statusLabel;
  role.append(roleName, roleState);

  const planHost = document.createElement('div');
  const thoughtHost = document.createElement('div');
  const toolsHost = document.createElement('div');
  toolsHost.className = 'tool-list';
  const mdBody = document.createElement('div');
  mdBody.className = 'markdown-body';

  card.append(role, planHost, thoughtHost, toolsHost, mdBody);
  els.timeline.appendChild(card);

  const turnRecord = {
    type: 'agent',
    agentName: resolvedAgentName,
    statusLabel,
    plan: [],
    thought: '',
    tools: [],
    markdown: '',
  };

  if (record) timelineEntries.push(turnRecord);

  currentAgentTurn = {
    card,
    roleState,
    planHost,
    thoughtHost,
    thoughtDetails: null,
    thoughtContent: null,
    thoughtText: '',
    toolsHost,
    toolCards: new Map(),
    mdBody,
    mdText: '',
    mdRaf: null,
    record: turnRecord,
  };

  scrollTimelineToBottom(true);
  return currentAgentTurn;
}

function finishAgentTurn(label = '', { persist = true } = {}) {
  if (!currentAgentTurn) return;
  if (currentAgentTurn.mdRaf) {
    cancelAnimationFrame(currentAgentTurn.mdRaf);
    currentAgentTurn.mdRaf = null;
    currentAgentTurn.mdBody.innerHTML = renderMarkdown(currentAgentTurn.mdText);
  }
  currentAgentTurn.roleState.textContent = label;
  currentAgentTurn.record.statusLabel = label;
  currentAgentTurn = null;
  if (persist) schedulePersistSession();
  scrollTimelineToBottom();
}

function renderThoughtChunk(text, { persist = true } = {}) {
  const turn = ensureAgentTurn();
  turn.thoughtText += text;
  turn.record.thought = turn.thoughtText;

  if (!turn.thoughtDetails) {
    const details = document.createElement('details');
    details.className = 'thought-details';

    const summary = document.createElement('summary');
    summary.textContent = '思考プロセス';

    const content = document.createElement('div');
    content.className = 'thought-content';

    details.append(summary, content);
    turn.thoughtHost.appendChild(details);
    turn.thoughtDetails = details;
    turn.thoughtContent = content;
  }

  turn.thoughtContent.textContent = turn.thoughtText;
  if (persist) schedulePersistSession();
  scrollTimelineToBottom();
}

function renderAgentMarkdownChunk(text) {
  const turn = ensureAgentTurn();
  turn.mdText += text;
  turn.record.markdown = turn.mdText;

  if (!turn.mdRaf) {
    turn.mdRaf = requestAnimationFrame(() => {
      turn.mdRaf = null;
      turn.mdBody.innerHTML = renderMarkdown(turn.mdText);
      scrollTimelineToBottom();
    });
  }
  schedulePersistSession();
}

function renderPlanUpdate(entries, { persist = true } = {}) {
  if (!Array.isArray(entries) || entries.length === 0) return;
  const turn = ensureAgentTurn();
  turn.record.plan = entries.map((e) => ({ ...e }));

  const card = document.createElement('div');
  card.className = 'plan-card';

  const title = document.createElement('div');
  title.className = 'plan-title';
  title.textContent = '実行プラン';

  const list = document.createElement('ul');
  list.className = 'plan-list';

  for (const entry of entries) {
    const li = document.createElement('li');
    const st = entry.status || 'pending';
    li.className = `plan-item plan-${st}`;

    const icon = document.createElement('span');
    icon.className = 'plan-icon';
    icon.textContent = st === 'completed' ? '✓' : st === 'in_progress' ? '▶' : '○';

    const text = document.createElement('span');
    text.textContent = entry.content || '';

    li.append(icon, text);
    list.appendChild(li);
  }

  card.append(title, list);
  turn.planHost.replaceChildren(card);
  if (persist) schedulePersistSession();
  scrollTimelineToBottom();
}

function mergeToolCall(update) {
  const id = update.toolCallId;
  if (!id) return null;

  const existing = toolCallsById.get(id) || {
    toolCallId: id,
    title: '',
    name: '',
    kind: 'other',
    status: 'pending',
    content: [],
    locations: [],
    rawInput: undefined,
    rawOutput: undefined,
  };

  const merged = {
    ...existing,
    ...(update.title != null ? { title: update.title } : {}),
    ...(update.name != null ? { name: update.name } : {}),
    ...(update.kind != null ? { kind: update.kind } : {}),
    ...(update.status != null ? { status: update.status } : {}),
    ...(Array.isArray(update.locations) ? { locations: update.locations } : {}),
    ...(update.rawInput !== undefined && update.rawInput !== null ? { rawInput: update.rawInput } : {}),
    ...(update.rawOutput !== undefined && update.rawOutput !== null ? { rawOutput: update.rawOutput } : {}),
  };

  if (Array.isArray(update.content) && update.content.length > 0) {
    const updateHasDiff = update.content.some((c) => c?.type === 'diff');
    const existingDiffs = (existing.content || []).filter((c) => c?.type === 'diff');
    if (!updateHasDiff && existingDiffs.length > 0) {
      merged.content = [...existingDiffs, ...update.content];
    } else {
      merged.content = update.content;
    }
  }

  toolCallsById.set(id, merged);
  return merged;
}

function renderToolCallContentInto(container, toolCall) {
  container.innerHTML = '';

  if (Array.isArray(toolCall.content)) {
    for (const item of toolCall.content) {
      if (!item) continue;
      if (item.type === 'diff') {
        container.appendChild(
          renderUnifiedDiffElement({
            path: item.path,
            oldText: item.oldText,
            newText: item.newText,
          }),
        );
      } else if (item.type === 'content' && item.content?.type === 'text' && item.content.text) {
        const pre = document.createElement('pre');
        pre.className = 'tool-text-out';
        pre.textContent = item.content.text;
        container.appendChild(pre);
      } else if (item.type === 'terminal' && item.terminalId) {
        const p = document.createElement('div');
        p.className = 'tool-text-out';
        p.textContent = `Terminal: ${item.terminalId}`;
        container.appendChild(p);
      }
    }
  }
}

function renderToolCallCard(toolCall, { persist = true } = {}) {
  const turn = ensureAgentTurn();
  let refs = turn.toolCards.get(toolCall.toolCallId);

  if (!refs) {
    const card = document.createElement('div');
    card.className = 'tool-card';

    const head = document.createElement('div');
    head.className = 'tool-head';

    const headLeft = document.createElement('div');
    headLeft.className = 'tool-head-left';

    const kindEl = document.createElement('span');
    kindEl.className = 'tool-kind';

    const titleEl = document.createElement('span');
    titleEl.className = 'tool-title';

    headLeft.append(kindEl, titleEl);

    const statusEl = document.createElement('span');
    statusEl.className = 'tool-status';

    head.append(headLeft, statusEl);

    const body = document.createElement('div');
    body.className = 'tool-body';

    card.append(head, body);
    turn.toolsHost.appendChild(card);

    refs = { card, kindEl, titleEl, statusEl, body };
    turn.toolCards.set(toolCall.toolCallId, refs);
  }

  refs.kindEl.textContent = (toolCall.kind || 'tool').toUpperCase();
  refs.titleEl.textContent = toolCall.title || toolCall.name || toolCall.toolCallId;
  refs.titleEl.title = refs.titleEl.textContent;

  const st = toolCall.status || 'pending';
  refs.statusEl.className = `tool-status tool-status-${st}`;
  refs.statusEl.textContent =
    st === 'pending'
      ? '待機'
      : st === 'in_progress'
        ? '実行中'
        : st === 'completed'
          ? '完了'
          : st === 'failed'
            ? '失敗'
            : st;

  renderToolCallContentInto(refs.body, toolCall);

  const idx = turn.record.tools.findIndex((t) => t.toolCallId === toolCall.toolCallId);
  const snapshot = structuredClone ? structuredClone(toolCall) : JSON.parse(JSON.stringify(toolCall));
  if (idx >= 0) turn.record.tools[idx] = snapshot;
  else turn.record.tools.push(snapshot);

  if (persist) schedulePersistSession();
  scrollTimelineToBottom();
}

// ============================================================================
// Permission Approval Bar (session/request_permission)
// ============================================================================

function enqueuePermission(req) {
  ensurePaneVisible('showAi');
  permissionQueue.push(req);
  if (permissionQueue.length === 1) {
    renderActivePermission();
  } else {
    updatePermissionQueueBadge();
  }
}

function updatePermissionQueueBadge() {
  if (permissionQueue.length > 1) {
    els.permQueueBadge.textContent = `残り ${permissionQueue.length} 件`;
    els.permQueueBadge.classList.remove('hidden');
  } else {
    els.permQueueBadge.classList.add('hidden');
  }
}

function renderActivePermission() {
  if (permissionQueue.length === 0) {
    els.permissionBar.classList.add('hidden');
    els.permPreview.innerHTML = '';
    els.permActions.innerHTML = '';
    return;
  }

  const current = permissionQueue[0];
  const rawTc = current.params?.toolCall || {};
  const merged = rawTc.toolCallId ? mergeToolCall(rawTc) : rawTc;
  const options = Array.isArray(current.params?.options) ? current.params.options : [];

  els.permKindBadge.textContent = (merged?.kind || 'ACTION').toUpperCase();
  els.permTitle.textContent = merged?.title || merged?.name || 'エージェント操作の承認';
  updatePermissionQueueBadge();

  const metaParts = [];
  if (Array.isArray(merged?.locations) && merged.locations.length > 0) {
    metaParts.push(
      merged.locations.map((loc) => (loc.line ? `${loc.path}:${loc.line}` : loc.path)).join(' , '),
    );
  }
  if (merged?.rawInput !== undefined && merged?.rawInput !== null) {
    const cmd =
      typeof merged.rawInput === 'string'
        ? merged.rawInput
        : merged.rawInput.command || merged.rawInput.cmd || '';
    if (cmd) metaParts.push(`$ ${cmd}`);
  }
  els.permMeta.textContent = metaParts.join(' — ');

  renderToolCallContentInto(els.permPreview, merged || {});
  if (els.permPreview.children.length === 0 && merged?.rawInput && typeof merged.rawInput === 'object') {
    const pre = document.createElement('pre');
    pre.className = 'tool-text-out';
    pre.textContent = JSON.stringify(merged.rawInput, null, 2);
    els.permPreview.appendChild(pre);
  }

  els.permActions.innerHTML = '';
  for (const opt of options) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `perm-btn perm-btn-${opt.kind || 'allow_once'}`;
    btn.textContent = opt.name || opt.optionId;
    btn.addEventListener('click', () => resolveCurrentPermission({ outcome: 'selected', optionId: opt.optionId }));
    els.permActions.appendChild(btn);
  }

  if (options.length === 0) {
    const cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'perm-btn perm-btn-reject_once';
    cancelBtn.textContent = 'キャンセル (Esc)';
    cancelBtn.addEventListener('click', () => resolveCurrentPermission({ outcome: 'cancelled' }));
    els.permActions.appendChild(cancelBtn);
  }

  els.permissionBar.classList.remove('hidden');

  if (document.activeElement === els.promptInput && els.promptInput.value.trim() === '' && !imeComposing) {
    els.promptInput.blur();
  }
  scrollTimelineToBottom();
}

function resolveCurrentPermission(outcome) {
  if (permissionQueue.length === 0) return;
  const current = permissionQueue.shift();
  try {
    activeClient.respondPermission(current.id, outcome);
  } catch (err) {
    showNotice({ severity: 'error', title: '承認応答の送信に失敗', description: err.message });
  }
  renderActivePermission();
}

function cancelAllPermissions() {
  while (permissionQueue.length > 0) {
    const item = permissionQueue.shift();
    try {
      activeClient.respondPermission(item.id, { outcome: 'cancelled' });
    } catch {
      // ignore
    }
  }
  renderActivePermission();
}

function pickPermissionOption(preferredKinds) {
  if (permissionQueue.length === 0) return null;
  const options = permissionQueue[0].params?.options || [];
  for (const kind of preferredKinds) {
    const found = options.find((o) => o.kind === kind);
    if (found) return found;
  }
  return null;
}

// ============================================================================
// ACP Client Event Wiring
// ============================================================================

function bindClientEvents(clientInstance) {
  clientInstance.addEventListener('state', (e) => {
    if (clientInstance !== activeClient) return;
    updateConnectionUi(e.detail.state, e.detail);
    if (e.detail.state === 'disconnected' || e.detail.state === 'error') {
      updateRunningUi(false);
    }
  });

  clientInstance.addEventListener('session', async (e) => {
    if (clientInstance !== activeClient) return;
    const { sessionId, resumed, method, modes } = e.detail;
    const profile = getCurrentProfile();
    const activeAgent = getActiveAgentKey(profile);
    setSavedSessionId(profile.id, activeAgent, sessionId);
    els.sessionLabel.textContent = `Session: ${sessionId ? sessionId.slice(0, 20) : '-'}`;
    updateModesUi(modes);

    if (resumed && method === 'session/resume') {
      const restored = await restoreCachedSessionIfAvailable(sessionId);
      appendSystemMessage(
        restored
          ? `既存セッションとローカル履歴を復帰しました (${sessionId})`
          : `既存セッションを復帰しました (${sessionId})`,
      );
    } else if (!resumed) {
      sessionCreatedAt = Date.now();
    }
  });

  clientInstance.addEventListener('update', (e) => {
    if (clientInstance !== activeClient) return;
    const update = e.detail?.update;
    if (!update || !update.sessionUpdate) return;

    switch (update.sessionUpdate) {
      case 'user_message_chunk': {
        const text = update.content?.type === 'text' ? update.content.text : '';
        if (text) appendUserChunk(text, update.messageId || null);
        break;
      }
      case 'agent_thought_chunk': {
        const text = update.content?.type === 'text' ? update.content.text : '';
        if (text) renderThoughtChunk(text);
        break;
      }
      case 'agent_message_chunk': {
        const text = update.content?.type === 'text' ? update.content.text : '';
        if (text) renderAgentMarkdownChunk(text);
        break;
      }
      case 'plan': {
        renderPlanUpdate(update.entries);
        break;
      }
      case 'tool_call':
      case 'tool_call_update': {
        const merged = mergeToolCall(update);
        if (merged) {
          renderToolCallCard(merged);
          // Mirror agent-executed command output into the bottom console log for quick inspection
          if (merged.kind === 'execute' && merged.status === 'completed' && Array.isArray(merged.content)) {
            const cmdLabel = merged.rawInput?.command || merged.title || merged.name;
            spatialConsole.appendLine(`[AI] $ ${cmdLabel}`, 'cmd');
            for (const c of merged.content) {
              if (c?.type === 'content' && c.content?.text) {
                spatialConsole.appendOutput(`${c.content.text}\n`, 'stdout');
              }
            }
          }
          // When agent completes a file edit, refresh workspace tree & git status
          if (merged.kind === 'edit' && merged.status === 'completed') {
            refreshWorkspaceTree();
            refreshGitStatus();
          }
        }
        break;
      }
      case 'available_commands_update': {
        renderSlashCommands(update.availableCommands || []);
        break;
      }
      case 'current_mode_update': {
        if (update.currentModeId) {
          els.modeSelect.value = update.currentModeId;
          schedulePersistSession();
        }
        break;
      }
      case 'usage_update': {
        updateUsageUi(update);
        schedulePersistSession();
        break;
      }
      case 'notice': {
        showNotice({
          severity: update.severity || 'info',
          title: update.title || '通知',
          description: update.description || '',
        });
        break;
      }
      default:
        break;
    }
  });

  clientInstance.addEventListener('permission', (e) => {
    if (clientInstance !== activeClient) return;
    enqueuePermission(e.detail);
  });
}

bindClientEvents(wsClient);
bindClientEvents(demoClient);

// ============================================================================
// Sending Prompts & Cancellation
// ============================================================================

async function submitPrompt(overrideText = null) {
  const userText = (overrideText !== null ? overrideText : els.promptInput.value).trim();
  if (!userText) return;
  if (activeClient.state !== 'ready') {
    showNotice({ severity: 'warning', title: 'エージェント未接続', description: '接続完了後に送信してください。' });
    return;
  }

  if (isRecording) stopVoiceInput();

  if (overrideText === null) {
    els.promptInput.value = '';
    autoResizeTextarea();
  }

  let payloadText = userText;
  let displayText = userText;
  if (attachedContexts.length > 0) {
    const contextPrefix = attachedContexts.map((c) => c.text).join('\n\n');
    const labels = attachedContexts.map((c) => `@${c.label}`).join(' ');
    payloadText = `${contextPrefix}\n\n---\n${userText}`;
    displayText = `${labels}\n${userText}`;
    attachedContexts.length = 0;
    renderAttachedContexts();
  }

  const mySeq = ++activeTurnSeq;
  appendUserMessage(displayText, { record: true });
  finishAgentTurn('');
  ensureAgentTurn();
  updateRunningUi(true);

  try {
    const res = await activeClient.sendPrompt(payloadText);
    if (mySeq === activeTurnSeq) {
      const reason = res?.stopReason;
      finishAgentTurn(reason === 'cancelled' ? '中断済み' : '完了');
    }
  } catch (err) {
    if (mySeq === activeTurnSeq) {
      finishAgentTurn('エラー');
      showNotice({
        severity: 'error',
        title: 'プロンプト実行エラー',
        description: err?.message || '送信に失敗しました',
      });
    }
  } finally {
    if (mySeq === activeTurnSeq) {
      updateRunningUi(false);
    }
  }
}

function cancelActiveTurn() {
  cancelAllPermissions();
  activeClient.cancelPrompt();
}

// ============================================================================
// Push-to-Talk Voice Input (Web Speech API ja-JP)
// ============================================================================

function initVoiceInput() {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SpeechRecognition) {
    els.voiceBtn.disabled = true;
    els.voiceBtn.title = 'このブラウザは Web Speech API 音声入力に未対応です';
    return;
  }

  recognition = new SpeechRecognition();
  recognition.lang = 'ja-JP';
  recognition.continuous = true;
  recognition.interimResults = true;

  recognition.addEventListener('start', () => {
    isRecording = true;
    voiceBaseText = els.promptInput.value;
    els.voiceBtn.classList.add('recording');
    els.voiceBtn.setAttribute('aria-pressed', 'true');
    els.voiceBanner.classList.remove('hidden');
    els.voiceBannerText.textContent = '音声認識中 (ja-JP)… Ctrl+Space で停止';
  });

  recognition.addEventListener('result', (event) => {
    let finalSegment = '';
    let interimSegment = '';

    for (let i = event.resultIndex; i < event.results.length; i++) {
      const transcript = event.results[i][0]?.transcript || '';
      if (event.results[i].isFinal) finalSegment += transcript;
      else interimSegment += transcript;
    }

    if (finalSegment) {
      const sep = voiceBaseText && !/[\s\n]$/.test(voiceBaseText) ? ' ' : '';
      voiceBaseText = `${voiceBaseText}${sep}${finalSegment}`;
      els.promptInput.value = voiceBaseText;
      autoResizeTextarea();
    }

    els.voiceBannerText.textContent = interimSegment
      ? `認識中: ${interimSegment}`
      : '音声認識中 (ja-JP)… Ctrl+Space で停止';
  });

  recognition.addEventListener('error', (event) => {
    if (event.error !== 'no-speech' && event.error !== 'aborted') {
      showNotice({
        severity: 'warning',
        title: '音声入力エラー',
        description: `Web Speech API: ${event.error}`,
      });
    }
    stopVoiceInput();
  });

  recognition.addEventListener('end', () => {
    isRecording = false;
    els.voiceBtn.classList.remove('recording');
    els.voiceBtn.setAttribute('aria-pressed', 'false');
    els.voiceBanner.classList.add('hidden');
  });
}

function startVoiceInput() {
  if (!recognition || isRecording) return;
  try {
    recognition.start();
  } catch {
    // ignore
  }
}

function stopVoiceInput() {
  if (!recognition || !isRecording) return;
  try {
    recognition.stop();
  } catch {
    // ignore
  }
}

function toggleVoiceInput() {
  if (!recognition) {
    showNotice({
      severity: 'info',
      title: '音声入力非対応',
      description: 'このブラウザ環境では Web Speech API が利用できません。',
    });
    return;
  }
  if (isRecording) stopVoiceInput();
  else startVoiceInput();
}

// ============================================================================
// IME-Safe Input & Spatial Desktop Keyboard Shortcuts
// ============================================================================

function isImeEvent(event) {
  return (
    imeComposing ||
    Boolean(event.isComposing) ||
    event.keyCode === 229 ||
    performance.now() - lastCompositionEndAt < 60
  );
}

function isEditableElement(el) {
  if (!el) return false;
  const tag = el.tagName;
  return tag === 'TEXTAREA' || tag === 'INPUT' || tag === 'SELECT' || el.isContentEditable;
}

function autoResizeTextarea() {
  const ta = els.promptInput;
  ta.style.height = 'auto';
  const nextHeight = Math.min(ta.scrollHeight, 160);
  ta.style.height = `${nextHeight}px`;
  ta.style.overflowY = ta.scrollHeight > 160 ? 'auto' : 'hidden';
}

els.promptInput.addEventListener('compositionstart', () => {
  imeComposing = true;
  els.imeIndicator.classList.remove('hidden');
});

els.promptInput.addEventListener('compositionend', () => {
  imeComposing = false;
  lastCompositionEndAt = performance.now();
  els.imeIndicator.classList.add('hidden');
});

els.promptInput.addEventListener('input', autoResizeTextarea);

document.addEventListener('keydown', (event) => {
  const mod = event.ctrlKey || event.metaKey;
  const keyLower = event.key.toLowerCase();

  // Context menu Escape close
  if (!els.contextMenu.classList.contains('hidden') && event.key === 'Escape') {
    closeContextMenu();
    return;
  }

  // Modal Escape handling
  if (!els.settingsModal.classList.contains('hidden')) {
    if (event.key === 'Escape' && !isImeEvent(event)) closeSettingsModal();
    return;
  }
  if (!els.historyModal.classList.contains('hidden')) {
    if (event.key === 'Escape' && !isImeEvent(event)) closeHistoryModal();
    return;
  }
  if (commandPalette.isOpen()) {
    return;
  }

  // Desktop Command Palette: Ctrl+Shift+P / Cmd+Shift+P / F1
  if ((mod && event.shiftKey && keyLower === 'p') || event.key === 'F1') {
    event.preventDefault();
    commandPalette.open('commands');
    return;
  }

  // Desktop Quick Open: Ctrl+P / Cmd+P
  if (mod && !event.shiftKey && !event.altKey && keyLower === 'p') {
    event.preventDefault();
    commandPalette.open('files');
    return;
  }

  // Workspace Full-Text Search: Ctrl+Shift+F / Cmd+Shift+F
  if (mod && event.shiftKey && keyLower === 'f') {
    event.preventDefault();
    commandPalette.open('search');
    return;
  }

  // Save active file: Ctrl+S / Cmd+S
  if (mod && !event.shiftKey && !event.altKey && keyLower === 's') {
    event.preventDefault();
    spatialEditor.saveActiveFile();
    return;
  }

  // Toggle Explorer: Ctrl+B / Cmd+B
  if (mod && !event.shiftKey && !event.altKey && keyLower === 'b') {
    event.preventDefault();
    togglePane('showExplorer');
    return;
  }

  // Toggle & focus Console: Ctrl+J / Cmd+J / Ctrl+`
  if (mod && !event.shiftKey && !event.altKey && (keyLower === 'j' || event.code === 'Backquote')) {
    event.preventDefault();
    togglePane('showConsole', { focusConsole: true });
    return;
  }

  // Focus AI composer (and attach editor selection if focused): Ctrl+L / Cmd+L
  if (mod && !event.shiftKey && !event.altKey && keyLower === 'l') {
    event.preventDefault();
    if (document.activeElement === els.editorTextarea) {
      const ctx = spatialEditor.getSelectionContext();
      if (ctx?.hasSelection) attachEditorContextToAi(ctx);
    }
    focusAiComposer();
    return;
  }

  // Direct spatial pane focus: Alt+1 (Explorer) / Alt+2 (Editor) / Alt+3 (AI) / Alt+4 (Console)
  if (event.altKey && !mod && !event.shiftKey) {
    if (event.key === '1') {
      event.preventDefault();
      ensurePaneVisible('showExplorer');
      els.treeFilterInput.focus();
      return;
    }
    if (event.key === '2') {
      event.preventDefault();
      spatialEditor.focus();
      return;
    }
    if (event.key === '3') {
      event.preventDefault();
      focusAiComposer();
      return;
    }
    if (event.key === '4') {
      event.preventDefault();
      ensurePaneVisible('showConsole');
      spatialConsole.focus();
      return;
    }
  }

  // Ctrl+Space: Toggle Push-to-Talk voice input
  if (event.ctrlKey && !event.altKey && !event.metaKey && event.code === 'Space') {
    event.preventDefault();
    toggleVoiceInput();
    return;
  }

  // Ctrl+Enter / Cmd+Enter: IME-safe AI prompt submit (when focused in AI composer or outside editor)
  if (mod && event.key === 'Enter' && document.activeElement !== els.editorTextarea && document.activeElement !== els.consoleInput) {
    if (isImeEvent(event)) return;
    event.preventDefault();
    submitPrompt();
    return;
  }

  // Permission approval shortcuts when no input/editor/console is focused
  if (permissionQueue.length > 0 && !isEditableElement(document.activeElement)) {
    if (isImeEvent(event)) return;
    if (event.ctrlKey || event.metaKey || event.altKey) return;

    if (keyLower === 'y' || event.key === 'Enter') {
      const opt = pickPermissionOption(['allow_once', 'allow_always']);
      if (opt) {
        event.preventDefault();
        resolveCurrentPermission({ outcome: 'selected', optionId: opt.optionId });
      }
      return;
    }
    if (keyLower === 'a') {
      const opt = pickPermissionOption(['allow_always']);
      if (opt) {
        event.preventDefault();
        resolveCurrentPermission({ outcome: 'selected', optionId: opt.optionId });
      }
      return;
    }
    if (keyLower === 'n' || event.key === 'Escape') {
      event.preventDefault();
      const opt = pickPermissionOption(['reject_once', 'reject_always']);
      if (opt) resolveCurrentPermission({ outcome: 'selected', optionId: opt.optionId });
      else resolveCurrentPermission({ outcome: 'cancelled' });
      return;
    }
  }

  // Escape while AI running (no permission bar active)
  if (event.key === 'Escape' && isRunning && permissionQueue.length === 0 && !isImeEvent(event)) {
    event.preventDefault();
    cancelActiveTurn();
  }
});

// Timeline delegation (demo prompt chips & markdown code copy buttons)
els.timeline.addEventListener('click', async (event) => {
  const demoBtn = event.target.closest('[data-demo-prompt]');
  if (demoBtn) {
    const prompt = demoBtn.getAttribute('data-demo-prompt');
    if (prompt) submitPrompt(prompt);
    return;
  }

  const btn = event.target.closest('[data-copy-code]');
  if (!btn) return;
  const codeEl = btn.closest('.md-code-wrap')?.querySelector('pre code');
  if (!codeEl) return;
  try {
    await navigator.clipboard.writeText(codeEl.textContent || '');
    const orig = btn.textContent;
    btn.textContent = 'コピー済み';
    setTimeout(() => {
      btn.textContent = orig;
    }, 1200);
  } catch {
    btn.textContent = '失敗';
  }
});

// Console quick command pills delegation
els.consolePane.addEventListener('click', (event) => {
  const btn = event.target.closest('[data-console-cmd]');
  if (!btn) return;
  const cmd = btn.getAttribute('data-console-cmd');
  if (cmd) spatialConsole.runCommand(cmd);
});

// ============================================================================
// Connection, Profiles & Diagnostics
// ============================================================================

function getEffectiveWorkspaceDir(profile = getCurrentProfile()) {
  return profile.workspaceDir?.trim() || serverConfig.workspaceDir || '/';
}

async function fetchServerConfigForProfile(profile = getCurrentProfile()) {
  if (profile.mode === 'demo') return true;
  let baseUrl;
  try {
    baseUrl = deriveHttpBaseUrl(profile.wsUrl, window.location.origin);
  } catch {
    return false;
  }

  try {
    const headers = {};
    if (profile.token) headers.Authorization = `Bearer ${profile.token}`;
    const res = await fetch(`${baseUrl}/api/config`, { cache: 'no-store', headers });
    if (res.status === 401) {
      showNotice({
        severity: 'warning',
        title: '認証トークンが必要です',
        description: 'ホスト側で設定した ACP_BRIDGE_TOKEN をプロファイル設定へ入力してください。',
        actions: [
          { label: '設定を開く', onClick: () => openSettingsModal() },
          { label: 'オフラインデモで試す', onClick: () => switchToDemoMode() },
        ],
      });
      return false;
    }
    if (!res.ok) return false;
    const data = await res.json();
    serverConfig = { ...serverConfig, ...data };
    populateAgentSelect(els.agentSelect.value);
    return true;
  } catch {
    return false;
  }
}

function populateAgentSelect(selectedValue = '') {
  els.agentSelect.innerHTML = '';
  const list = serverConfig.agents || [];
  for (const ag of list) {
    const opt = document.createElement('option');
    opt.value = ag.key;
    opt.textContent = ag.label || ag.key;
    els.agentSelect.appendChild(opt);
  }
  const desired = selectedValue || serverConfig.defaultAgent || 'agy';
  if (desired && !list.some((a) => a.key === desired)) {
    const customOpt = document.createElement('option');
    customOpt.value = desired;
    customOpt.textContent = `${desired} (カスタム)`;
    els.agentSelect.appendChild(customOpt);
  }
  els.agentSelect.value = desired;
}

async function connectActiveProfile({ reset = false, targetSessionId = null } = {}) {
  const profile = getCurrentProfile();
  renderProfileQuickSelect();
  updateDemoUiFlags();

  if (profile.preferredTheme === 'ost' || profile.preferredTheme === 'vst') {
    applyTheme(profile.preferredTheme, { persist: false });
  }

  wsClient.disconnect({ manual: true });
  demoClient.disconnect({ manual: true });
  cancelAllPermissions();
  renderSlashCommands([]);
  bridgeFailureCount = 0;
  offeredDemoHint = false;

  // Configure Workspace FS + Lightweight Console alongside ACP client
  workspaceClient.configure(profile, { pageOrigin: window.location.origin });
  spatialConsole.connect(profile, { pageOrigin: window.location.origin });

  if (profile.mode === 'demo') {
    activeClient = demoClient;
    const savedSessionId = reset ? null : targetSessionId || getSavedSessionId(profile.id, 'demo');
    if (reset) {
      setSavedSessionId(profile.id, 'demo', null);
      clearTimelineDom();
    }
    activeClient.connect({
      cwd: profile.workspaceDir || '/workspace/xr-blog',
      savedSessionId,
      reset,
    });
    await refreshWorkspaceTree();
    await refreshGitStatus();
    if (spatialEditor.getOpenPaths().length === 0) {
      await spatialEditor.openFile('README.md', { focus: false });
    }
    return;
  }

  activeClient = wsClient;
  const configOk = await fetchServerConfigForProfile(profile);
  const agentKey = getActiveAgentKey(profile);
  const savedSessionId = reset ? null : targetSessionId || getSavedSessionId(profile.id, agentKey);
  if (reset) {
    setSavedSessionId(profile.id, agentKey, null);
    clearTimelineDom();
  }

  if (configOk) {
    workspaceClient.startWatching();
    await refreshWorkspaceTree();
    await refreshGitStatus();
  }

  let wsUrl;
  try {
    wsUrl = buildProfileWsUrl(profile, {
      defaultAgent: serverConfig.defaultAgent,
      pageOrigin: window.location.origin,
    });
  } catch (err) {
    showNotice({
      severity: 'error',
      title: 'WebSocket URL が無効です',
      description: err.message,
      actions: [{ label: '設定を開く', onClick: () => openSettingsModal() }],
    });
    return;
  }

  activeClient.connect({
    wsUrl,
    cwd: getEffectiveWorkspaceDir(profile),
    savedSessionId,
    reset,
  });
}

function switchToDemoMode() {
  let demo = profileStore.profiles.find((p) => p.mode === 'demo');
  if (!demo) {
    demo = upsertProfile(profileStore, {
      id: 'offline-demo',
      name: 'オフラインデモ (サーバー不要)',
      mode: 'demo',
      workspaceDir: '/workspace/xr-blog',
    });
  }
  profileStore.activeProfileId = demo.id;
  saveProfileStore(profileStore);
  spatialEditor.closeAllTabs();
  clearTimelineDom();
  connectActiveProfile({ reset: false });
}

// ============================================================================
// History Modal & Settings Modal
// ============================================================================

function formatTimestamp(ms) {
  if (!ms) return '-';
  try {
    return new Date(ms).toLocaleString('ja-JP', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return '-';
  }
}

async function renderHistoryModalList() {
  const sessions = await historyStore.listSessions();
  els.historyList.innerHTML = '';

  if (sessions.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'history-empty';
    empty.textContent = '保存されたセッション履歴はまだありません。';
    els.historyList.appendChild(empty);
    return;
  }

  for (const s of sessions) {
    const row = document.createElement('div');
    const isActive = s.sessionId === activeClient.sessionId;
    row.className = `history-item${isActive ? ' history-active' : ''}`;

    const mainBtn = document.createElement('button');
    mainBtn.type = 'button';
    mainBtn.className = 'history-item-main';

    const title = document.createElement('div');
    title.className = 'history-item-title';
    title.textContent = s.title || s.sessionId;

    const meta = document.createElement('div');
    meta.className = 'history-item-meta';
    const msgCount = Array.isArray(s.entries) ? s.entries.length : 0;
    meta.textContent = `${s.agentKey} · ${msgCount} 件 · ${formatTimestamp(s.updatedAt)} · ${s.sessionId.slice(0, 14)}`;

    mainBtn.append(title, meta);
    mainBtn.addEventListener('click', async () => {
      closeHistoryModal();
      await switchToSavedSession(s);
    });

    const delBtn = document.createElement('button');
    delBtn.type = 'button';
    delBtn.className = 'ctrl-btn danger-btn';
    delBtn.textContent = '削除';
    delBtn.addEventListener('click', async () => {
      await historyStore.deleteSession(s.sessionId);
      await renderHistoryModalList();
    });

    row.append(mainBtn, delBtn);
    els.historyList.appendChild(row);
  }
}

async function switchToSavedSession(savedRecord) {
  const targetProfile = profileStore.profiles.find((p) => p.id === savedRecord.profileId);
  if (targetProfile) {
    profileStore.activeProfileId = targetProfile.id;
    saveProfileStore(profileStore);
  }
  await restoreCachedSessionIfAvailable(savedRecord.sessionId);
  await connectActiveProfile({ reset: false, targetSessionId: savedRecord.sessionId });
}

async function openHistoryModal() {
  await persistCurrentSessionNow();
  await renderHistoryModalList();
  els.historyModal.classList.remove('hidden');
}

function closeHistoryModal() {
  els.historyModal.classList.add('hidden');
}

function syncProfileFormFromId(profileId) {
  editingProfileId = profileId;
  const profile =
    profileStore.profiles.find((p) => p.id === profileId) || getCurrentProfile();
  editingProfileId = profile.id;

  els.profileEditSelect.innerHTML = '';
  for (const p of profileStore.profiles) {
    const opt = document.createElement('option');
    opt.value = p.id;
    opt.textContent = p.name;
    els.profileEditSelect.appendChild(opt);
  }
  els.profileEditSelect.value = profile.id;
  els.deleteProfileBtn.disabled = profileStore.profiles.length <= 1;

  els.profileNameInput.value = profile.name;
  els.profileModeSelect.value = profile.mode;
  populateAgentSelect(profile.agent || serverConfig.defaultAgent);
  els.wsUrlInput.value = profile.wsUrl || '';
  els.tokenInput.value = profile.token || '';
  els.workspaceInput.value = profile.workspaceDir || '';
  els.workspaceInput.placeholder = `空欄で ${serverConfig.workspaceDir} を使用`;
  els.profileThemeSelect.value = profile.preferredTheme || '';
  els.dimOnBlurToggle.checked = Boolean(profileStore.dimOnBlur);

  const isDemo = profile.mode === 'demo';
  els.bridgeFields.classList.toggle('hidden', isDemo);
  els.resetProcessBtn.classList.toggle('hidden', isDemo);
  els.diagResult.classList.add('hidden');
}

function readProfileFormDraft() {
  return {
    id: editingProfileId,
    name: els.profileNameInput.value.trim() || 'プロファイル',
    mode: els.profileModeSelect.value === 'demo' ? 'demo' : 'bridge',
    agent: els.agentSelect.value,
    wsUrl: els.wsUrlInput.value.trim(),
    token: els.tokenInput.value.trim(),
    workspaceDir: els.workspaceInput.value.trim(),
    preferredTheme: els.profileThemeSelect.value,
  };
}

function openSettingsModal() {
  syncProfileFormFromId(profileStore.activeProfileId);
  els.settingsModal.classList.remove('hidden');
  els.profileNameInput.focus();
}

function closeSettingsModal() {
  els.settingsModal.classList.add('hidden');
}

async function runConnectionDiagnostics() {
  const draft = readProfileFormDraft();
  els.testConnectionBtn.disabled = true;
  els.diagResult.className = 'diag-result';
  els.diagResult.textContent = '接続診断を実行中…';
  els.diagResult.classList.remove('hidden');

  try {
    const result = await diagnoseBridgeConnection(draft, {
      pageOrigin: window.location.origin,
    });
    const cls = result.ok ? 'diag-ok' : result.status === 'auth_required' ? 'diag-warn' : 'diag-err';
    els.diagResult.className = `diag-result ${cls}`;
    els.diagResult.innerHTML = '';

    const msgEl = document.createElement('div');
    msgEl.textContent = result.message;
    els.diagResult.appendChild(msgEl);

    if (result.hint) {
      const hintEl = document.createElement('div');
      hintEl.className = 'diag-hint';
      hintEl.textContent = result.hint;
      els.diagResult.appendChild(hintEl);
    }

    if (result.ok && result.config) {
      serverConfig = { ...serverConfig, ...result.config };
      populateAgentSelect(draft.agent || result.config.defaultAgent);
    }
  } finally {
    els.testConnectionBtn.disabled = false;
  }
}

// ============================================================================
// Wire UI Buttons
// ============================================================================

els.quickOpenBtn.addEventListener('click', () => commandPalette.open('files'));
els.welcomeQuickOpenBtn.addEventListener('click', () => commandPalette.open('files'));
els.cmdPaletteBtn.addEventListener('click', () => commandPalette.open('commands'));
els.welcomeCmdPaletteBtn.addEventListener('click', () => commandPalette.open('commands'));
els.globalSearchBtn.addEventListener('click', () => commandPalette.open('search'));
els.welcomeNewFileBtn.addEventListener('click', () => promptCreateFile(''));

els.toggleExplorerBtn.addEventListener('click', () => togglePane('showExplorer'));
els.toggleConsoleBtn.addEventListener('click', () => togglePane('showConsole', { focusConsole: true }));
els.consoleCloseBtn.addEventListener('click', () => togglePane('showConsole'));
els.toggleAiBtn.addEventListener('click', () => togglePane('showAi'));

els.newFileBtn.addEventListener('click', () => promptCreateFile(''));
els.newDirBtn.addEventListener('click', () => promptCreateDir(''));
els.refreshTreeBtn.addEventListener('click', async () => {
  await refreshWorkspaceTree();
  await refreshGitStatus();
});
els.treeFilterInput.addEventListener('input', renderFileTree);

els.themeToggleBtn.addEventListener('click', () => {
  applyTheme(profileStore.theme === 'ost' ? 'vst' : 'ost');
});

els.profileQuickSelect.addEventListener('change', async () => {
  await persistCurrentSessionNow();
  profileStore.activeProfileId = els.profileQuickSelect.value;
  saveProfileStore(profileStore);
  spatialEditor.closeAllTabs();
  clearTimelineDom();
  await connectActiveProfile({ reset: false });
});

els.modeSelect.addEventListener('change', async () => {
  const nextMode = els.modeSelect.value;
  try {
    await activeClient.setMode(nextMode);
    appendSystemMessage(`実行モードを "${nextMode}" に切り替えました`);
    schedulePersistSession();
  } catch (err) {
    showNotice({
      severity: 'warning',
      title: 'モード切替に失敗',
      description: err?.message || 'このエージェントはモード切替に未対応の可能性があります。',
    });
  }
});

async function startFreshSession() {
  await persistCurrentSessionNow();
  if (activeClient.state !== 'ready') {
    clearTimelineDom();
    await connectActiveProfile({ reset: false });
    return;
  }
  try {
    cancelAllPermissions();
    clearTimelineDom();
    await activeClient.createNewSession(getEffectiveWorkspaceDir());
    appendSystemMessage(`新規セッションを開始しました (${activeClient.sessionId})`);
  } catch (err) {
    showNotice({ severity: 'error', title: '新規セッション作成失敗', description: err.message });
  }
}

els.newSessionBtn.addEventListener('click', startFreshSession);
els.historyNewSessionBtn.addEventListener('click', async () => {
  closeHistoryModal();
  await startFreshSession();
});

els.historyBtn.addEventListener('click', openHistoryModal);
els.closeHistoryBtn.addEventListener('click', closeHistoryModal);
els.historyModal.addEventListener('click', (e) => {
  if (e.target === els.historyModal) closeHistoryModal();
});
els.clearHistoryBtn.addEventListener('click', async () => {
  await historyStore.clearAll();
  await renderHistoryModalList();
  showNotice({ severity: 'info', title: 'ローカル履歴をクリアしました' });
});

els.settingsBtn.addEventListener('click', openSettingsModal);
els.closeSettingsBtn.addEventListener('click', closeSettingsModal);
els.settingsModal.addEventListener('click', (e) => {
  if (e.target === els.settingsModal) closeSettingsModal();
});

els.profileEditSelect.addEventListener('change', () => {
  syncProfileFormFromId(els.profileEditSelect.value);
});

els.profileModeSelect.addEventListener('change', () => {
  const isDemo = els.profileModeSelect.value === 'demo';
  els.bridgeFields.classList.toggle('hidden', isDemo);
  els.resetProcessBtn.classList.toggle('hidden', isDemo);
});

els.addProfileBtn.addEventListener('click', () => {
  const created = upsertProfile(profileStore, {
    id: `profile-${Date.now()}`,
    name: `カスタム接続 ${profileStore.profiles.length + 1}`,
    mode: 'bridge',
    wsUrl: 'ws://100.64.0.10:8765',
    agent: 'agy',
    preferredTheme: 'vst',
  });
  saveProfileStore(profileStore);
  renderProfileQuickSelect();
  syncProfileFormFromId(created.id);
  els.profileNameInput.focus();
  els.profileNameInput.select();
});

els.deleteProfileBtn.addEventListener('click', () => {
  if (deleteProfile(profileStore, editingProfileId)) {
    saveProfileStore(profileStore);
    renderProfileQuickSelect();
    syncProfileFormFromId(profileStore.activeProfileId);
  }
});

els.testConnectionBtn.addEventListener('click', runConnectionDiagnostics);

els.settingsForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const saved = upsertProfile(profileStore, readProfileFormDraft());
  profileStore.activeProfileId = saved.id;
  profileStore.dimOnBlur = els.dimOnBlurToggle.checked;
  saveProfileStore(profileStore);
  closeSettingsModal();
  await persistCurrentSessionNow();
  spatialEditor.closeAllTabs();
  clearTimelineDom();
  await connectActiveProfile({ reset: false });
});

els.resetProcessBtn.addEventListener('click', async () => {
  const saved = upsertProfile(profileStore, readProfileFormDraft());
  profileStore.activeProfileId = saved.id;
  profileStore.dimOnBlur = els.dimOnBlurToggle.checked;
  saveProfileStore(profileStore);
  closeSettingsModal();
  await persistCurrentSessionNow();
  clearTimelineDom();
  appendSystemMessage(`エージェントプロセス (${getActiveAgentKey(saved)}) を再起動します…`);
  await connectActiveProfile({ reset: true });
});

els.sendBtn.addEventListener('click', () => submitPrompt());
els.cancelBtn.addEventListener('click', cancelActiveTurn);
els.voiceBtn.addEventListener('click', toggleVoiceInput);

// ============================================================================
// Bootstrap
// ============================================================================

async function boot() {
  applyTheme(profileStore.theme, { persist: false });
  applyLayoutState();
  initResizers();
  renderProfileQuickSelect();
  updateDemoUiFlags();
  initVoiceInput();

  // Android 15 / Android XR host bridge setup
  const hostCaps = xrHost.getCapabilities();
  els.statusHostBadge.textContent = hostCaps.nativeHost
    ? 'Android XR Native'
    : hostCaps.android15Avf
      ? 'Android 15 Web'
      : 'Spatial Web IDE';
  xrHost.registerDesktopShortcuts([
    'Ctrl+P',
    'Ctrl+Shift+P',
    'Ctrl+Shift+F',
    'Ctrl+S',
    'Ctrl+W',
    'Ctrl+B',
    'Ctrl+J',
    'Ctrl+`',
    'Ctrl+L',
    'Ctrl+Space',
    'F1',
    'Alt+1',
    'Alt+2',
    'Alt+3',
    'Alt+4',
  ]);

  // Native hardware-keyboard shortcut hook (called by Android Activity.dispatchKeyEvent)
  window.__xrDispatchShortcut = (spec) => {
    if (!spec || imeComposing) return false;
    if ((spec.ctrlKey || spec.metaKey) && !spec.shiftKey && !spec.altKey && String(spec.key).toLowerCase() === 'w') {
      const tab = spatialEditor.getActiveTab();
      if (tab) spatialEditor.closeTab(tab.path);
      return true;
    }
    const ev = new KeyboardEvent('keydown', {
      key: spec.key || '',
      code: spec.code || '',
      ctrlKey: Boolean(spec.ctrlKey),
      shiftKey: Boolean(spec.shiftKey),
      altKey: Boolean(spec.altKey),
      metaKey: Boolean(spec.metaKey),
      bubbles: true,
      cancelable: true,
    });
    document.dispatchEvent(ev);
    return true;
  };

  window.__xrSetWindowFocused = (focused) => {
    applyBlurDimming(!focused);
  };

  // Support single-pane spatial detachment (?focusPane=ai|editor|console)
  const focusPane = new URLSearchParams(window.location.search).get('focusPane');
  if (focusPane === 'ai') {
    layoutState.showExplorer = false;
    layoutState.showConsole = false;
    layoutState.showAi = true;
    applyLayoutState();
  }

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
  }

  await connectActiveProfile({ reset: false });
}

boot();
