'use strict';
const vscode = require('vscode');
const http = require('http');

const OST_COLORS = {
  'editor.background': '#000000',
  'editor.foreground': '#f3f4f6',
  'editorGutter.background': '#000000',
  'editorLineNumber.foreground': '#6b7280',
  'editorLineNumber.activeForeground': '#f9fafb',
  'editorCursor.foreground': '#38bdf8',
  'sideBar.background': '#050505',
  'sideBar.foreground': '#e5e7eb',
  'sideBar.border': '#1f2937',
  'sideBarSectionHeader.background': '#0a0a0c',
  'activityBar.background': '#000000',
  'activityBar.foreground': '#f3f4f6',
  'activityBar.activeBorder': '#38bdf8',
  'panel.background': '#000000',
  'panel.border': '#1f2937',
  'terminal.background': '#000000',
  'terminal.foreground': '#f3f4f6',
  'terminalCursor.foreground': '#38bdf8',
  'titleBar.activeBackground': '#000000',
  'titleBar.inactiveBackground': '#000000',
  'statusBar.background': '#08080a',
  'statusBar.foreground': '#e5e7eb',
  'statusBar.border': '#1f2937',
  'tab.activeBackground': '#0d1117',
  'tab.activeForeground': '#ffffff',
  'tab.activeBorderTop': '#38bdf8',
  'tab.inactiveBackground': '#000000',
  'tab.inactiveForeground': '#9ca3af',
  'editorGroupHeader.tabsBackground': '#000000',
};

const VST_COLORS = {
  'editor.background': '#12141a',
  'editor.foreground': '#f3f4f6',
  'editorGutter.background': '#12141a',
  'editorLineNumber.foreground': '#64748b',
  'editorLineNumber.activeForeground': '#f8fafc',
  'editorCursor.foreground': '#38bdf8',
  'sideBar.background': '#171a22',
  'sideBar.foreground': '#e2e8f0',
  'sideBar.border': '#283040',
  'sideBarSectionHeader.background': '#1c202b',
  'activityBar.background': '#0f1117',
  'activityBar.foreground': '#f1f5f9',
  'activityBar.activeBorder': '#38bdf8',
  'panel.background': '#14171f',
  'panel.border': '#283040',
  'terminal.background': '#12141a',
  'terminal.foreground': '#f3f4f6',
  'terminalCursor.foreground': '#38bdf8',
  'titleBar.activeBackground': '#0f1117',
  'titleBar.inactiveBackground': '#0f1117',
  'statusBar.background': '#171a22',
  'statusBar.foreground': '#e2e8f0',
  'statusBar.border': '#283040',
  'tab.activeBackground': '#1e2430',
  'tab.activeForeground': '#ffffff',
  'tab.activeBorderTop': '#38bdf8',
  'tab.inactiveBackground': '#12141a',
  'tab.inactiveForeground': '#94a3b8',
  'editorGroupHeader.tabsBackground': '#12141a',
};

function callHost(method, path, body) {
  return new Promise((resolve, reject) => {
    const port = Number(process.env.KUCODE_ADMIN_PORT || 0);
    const token = process.env.KUCODE_ADMIN_TOKEN || '';
    if (!port) {
      reject(new Error('KUCODE_ADMIN_PORT is not configured'));
      return;
    }
    const payload = body ? Buffer.from(body, 'utf8') : null;
    const headers = { 'X-KuCode-Token': token };
    if (payload) {
      headers['Content-Type'] = 'text/plain; charset=utf-8';
      headers['Content-Length'] = String(payload.length);
    }
    const req = http.request(
      { hostname: '127.0.0.1', port, path, method, headers, timeout: 60000 },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          if (res.statusCode >= 200 && res.statusCode < 300) {
            resolve(text);
          } else {
            reject(new Error('Host API HTTP ' + res.statusCode + ': ' + text));
          }
        });
      }
    );
    req.on('error', reject);
    req.on('timeout', () => {
      req.destroy(new Error('Host API timed out'));
    });
    if (payload) req.write(payload);
    req.end();
  });
}

async function openTextTabAndCopy(content, language, copiedMessage) {
  const doc = await vscode.workspace.openTextDocument({ content, language });
  await vscode.window.showTextDocument(doc, { preview: false });
  let copied = false;
  try {
    await callHost('POST', '/copy', content);
    copied = true;
  } catch (_) {
    // Fallback to VS Code clipboard API below
  }
  try {
    await vscode.env.clipboard.writeText(content);
    copied = true;
  } catch (_) {
    // Ignore when WebView clipboard permission is restricted
  }
  if (copied) {
    vscode.window.showInformationMessage(copiedMessage);
  }
}

async function runDiagnosticsCommand() {
  await vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: 'KuCode: 実機チェックを実行中（PRoot / PTY / マウント / 疎通）...',
      cancellable: false,
    },
    async () => {
      try {
        const report = await callHost('GET', '/diagnostics');
        await openTextTabAndCopy(
          report,
          'markdown',
          '実機診断レポートを開き、クリップボードへコピーしました（そのまま貼り付けできます）'
        );
      } catch (err) {
        vscode.window.showErrorMessage('実機チェックに失敗しました: ' + err.message);
      }
    }
  );
}

async function openLogsCommand() {
  try {
    const logs = await callHost('GET', '/logs');
    await openTextTabAndCopy(
      logs || '(ログなし)',
      'plaintext',
      '起動・エラーログを開き、クリップボードへコピーしました'
    );
  } catch (err) {
    vscode.window.showErrorMessage('ログ取得に失敗しました: ' + err.message);
  }
}

async function toggleTerminalCommand() {
  await vscode.commands.executeCommand('workbench.action.terminal.toggleTerminal');
}

async function selectDisplayScaleCommand() {
  const presets = [
    { label: '標準（情報量重視）', description: 'zoom 0 / font 15px', zoom: 0, font: 15, line: 24, term: 14 },
    { label: '大（XREAL AURA 推奨）', description: 'zoom 0.5 / font 16px', zoom: 0.5, font: 16, line: 26, term: 15 },
    { label: '特大（遠距離・高視認性）', description: 'zoom 1.0 / font 18px', zoom: 1.0, font: 18, line: 29, term: 16 },
  ];
  const picked = await vscode.window.showQuickPick(presets, {
    title: 'KuCode 表示サイズ・ズーム',
    placeHolder: 'XR 空間での見やすいサイズを選択してください',
  });
  if (!picked) return;
  const cfg = vscode.workspace.getConfiguration();
  await cfg.update('window.zoomLevel', picked.zoom, vscode.ConfigurationTarget.Global);
  await cfg.update('editor.fontSize', picked.font, vscode.ConfigurationTarget.Global);
  await cfg.update('editor.lineHeight', picked.line, vscode.ConfigurationTarget.Global);
  await cfg.update('terminal.integrated.fontSize', picked.term, vscode.ConfigurationTarget.Global);
  vscode.window.showInformationMessage('表示サイズを「' + picked.label + '」に変更しました');
}

async function selectOpticalThemeCommand() {
  const themes = [
    {
      label: '$(eye) OST 純黒モード（XREAL AURA 光学シースルー向け）',
      description: '#000000 背景透過 + 高コントラスト枠線',
      colors: OST_COLORS,
      name: 'OST 純黒モード',
    },
    {
      label: '$(device-camera-video) VST 暗灰モード（パススルー・暗室向け）',
      description: '#12141a 不透明ダークスレート背景',
      colors: VST_COLORS,
      name: 'VST 暗灰モード',
    },
  ];
  const picked = await vscode.window.showQuickPick(themes, {
    title: 'KuCode 光学テーマ切替',
    placeHolder: 'デバイスの光学方式に合わせて選択してください',
  });
  if (!picked) return;
  const cfg = vscode.workspace.getConfiguration();
  await cfg.update('workbench.colorCustomizations', picked.colors, vscode.ConfigurationTarget.Global);
  vscode.window.showInformationMessage('光学テーマを「' + picked.name + '」に切り替えました');
}

async function openManagerCommand() {
  try {
    await callHost('POST', '/open-boot');
  } catch (err) {
    vscode.window.showErrorMessage('管理画面への切替に失敗しました: ' + err.message);
  }
}

async function requestStorageCommand() {
  try {
    await callHost('POST', '/storage-permission');
    vscode.window.showInformationMessage(
      'Android ストレージ許可後に「管理画面 → サーバー再起動」を実行すると /workspace/android/sdcard が有効になります'
    );
  } catch (err) {
    vscode.window.showErrorMessage('権限画面を開けませんでした: ' + err.message);
  }
}

async function openMenuCommand() {
  const items = [
    {
      label: '$(checklist) 実機チェックを実行（レポートを開く & 自動コピー）',
      description: '端末情報・PRoot・node-pty・マウント・疎通・エラーログを一括確認',
      action: runDiagnosticsCommand,
    },
    {
      label: '$(terminal) 統合ターミナルを開閉',
      description: 'Debian POSIX bash / apt / git / gh（JISキーボード補助）',
      action: toggleTerminalCommand,
    },
    {
      label: '$(zoom-in) 表示サイズ・ズームを変更（標準 / 大 / 特大）',
      description: 'XREAL AURA 70° FOV 向け文字サイズ調整',
      action: selectDisplayScaleCommand,
    },
    {
      label: '$(color-mode) 光学テーマを切替（OST 純黒 / VST 暗灰）',
      description: 'シースルー透過黒と不透明ダークをワンクリック切替',
      action: selectOpticalThemeCommand,
    },
    {
      label: '$(output) 起動・エラーログを開く（自動コピー）',
      description: 'openvscode-server と PRoot の最新ログを表示',
      action: openLogsCommand,
    },
    {
      label: '$(tools) 管理画面を開く（ミニCLI / 再起動）',
      description: '非PTYフォールバックターミナル・進捗・サーバー再起動画面へ切替',
      action: openManagerCommand,
    },
    {
      label: '$(folder-opened) Android ストレージアクセスを許可',
      description: '/workspace/android/sdcard マウント用の権限設定を開く',
      action: requestStorageCommand,
    },
  ];
  const picked = await vscode.window.showQuickPick(items, {
    title: 'KuCode 実機チェック & 空間設定メニュー',
    placeHolder: '実行する項目を選択してください',
  });
  if (picked) {
    await picked.action();
  }
}

function createSidebarMenuProvider() {
  const entries = [
    {
      label: '実機チェックを実行',
      description: 'レポート表示 & 自動コピー',
      icon: 'checklist',
      command: 'kucode.runDiagnostics',
    },
    {
      label: 'ターミナル開閉',
      description: 'POSIX bash / gh',
      icon: 'terminal',
      command: 'kucode.toggleTerminal',
    },
    {
      label: '表示サイズ変更',
      description: '標準 / 大 / 特大',
      icon: 'zoom-in',
      command: 'kucode.selectDisplayScale',
    },
    {
      label: '光学テーマ切替',
      description: 'OST 純黒 / VST 暗灰',
      icon: 'color-mode',
      command: 'kucode.selectOpticalTheme',
    },
    {
      label: '起動・エラーログを開く',
      description: '自動コピー',
      icon: 'output',
      command: 'kucode.openLogs',
    },
    {
      label: '管理画面を開く',
      description: 'ミニCLI / 再起動',
      icon: 'tools',
      command: 'kucode.openManager',
    },
    {
      label: 'Android ストレージ許可',
      description: '/android/sdcard マウント',
      icon: 'folder-opened',
      command: 'kucode.requestStorage',
    },
  ];

  return {
    getTreeItem(entry) {
      const item = new vscode.TreeItem(entry.label, vscode.TreeItemCollapsibleState.None);
      item.description = entry.description;
      item.iconPath = new vscode.ThemeIcon(entry.icon);
      item.command = { command: entry.command, title: entry.label };
      return item;
    },
    getChildren(element) {
      return element ? [] : entries;
    },
  };
}

function activate(context) {
  context.subscriptions.push(
    vscode.commands.registerCommand('kucode.openMenu', openMenuCommand),
    vscode.commands.registerCommand('kucode.runDiagnostics', runDiagnosticsCommand),
    vscode.commands.registerCommand('kucode.openLogs', openLogsCommand),
    vscode.commands.registerCommand('kucode.toggleTerminal', toggleTerminalCommand),
    vscode.commands.registerCommand('kucode.selectDisplayScale', selectDisplayScaleCommand),
    vscode.commands.registerCommand('kucode.selectOpticalTheme', selectOpticalThemeCommand),
    vscode.commands.registerCommand('kucode.openManager', openManagerCommand),
    vscode.commands.registerCommand('kucode.requestStorage', requestStorageCommand),
    vscode.window.registerTreeDataProvider('kucode.menuView', createSidebarMenuProvider())
  );

  const statusItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
  statusItem.text = '$(pulse) KuCode';
  statusItem.tooltip = 'KuCode 実機チェック・表示サイズ・光学テーマ・管理画面を開く';
  statusItem.command = 'kucode.openMenu';
  statusItem.show();
  context.subscriptions.push(statusItem);
}

function deactivate() {}

module.exports = {
  activate,
  deactivate,
  OST_COLORS,
  VST_COLORS,
  callHost,
  createSidebarMenuProvider,
};
