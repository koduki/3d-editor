package dev.koduki.xreditor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Generates and installs the tiny built-in VS Code extension (`xr-editor-tools`)
 * directly into `/opt/openvscode-server/extensions/xr-editor-tools/`.
 *
 * Exposes an easy-to-hit left Activity Bar menu (`XR-Editor` sidebar view) and
 * context menus with:
 * - On-device diagnostics & log viewer (with auto-clipboard copy)
 * - 1-click Terminal toggle (friendly for JIS keyboards where Ctrl+` is awkward)
 * - 1-click Spatial display scale selector (Standard / Large / XL)
 * - 1-click Optical theme switcher (OST Pure Black #000000 / VST Dark Slate)
 * - Storage permission & fallback CLI manager screen switcher
 */
object VsCodeExtensionAsset {

    const val EXTENSION_DIR_NAME = "xr-editor-tools"

    fun buildIconSvg(): String = """
        <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <rect x="2" y="6" width="20" height="12" rx="4" />
          <path d="M9 18l3-4 3 4" />
          <circle cx="8" cy="11.5" r="1.2" fill="currentColor" />
          <circle cx="16" cy="11.5" r="1.2" fill="currentColor" />
        </svg>
    """.trimIndent() + "\n"

    fun buildPackageJson(): String {
        val commands = JSONArray()
            .put(
                JSONObject()
                    .put("command", "xrEditor.openMenu")
                    .put("title", "XR-Editor: チェック & 管理メニューを開く")
                    .put("icon", "\$(pulse)"),
            )
            .put(
                JSONObject()
                    .put("command", "xrEditor.runDiagnostics")
                    .put("title", "XR-Editor: 実機チェックを実行（レポートを開く & コピー）"),
            )
            .put(
                JSONObject()
                    .put("command", "xrEditor.openLogs")
                    .put("title", "XR-Editor: 起動・エラーログを開く"),
            )
            .put(
                JSONObject()
                    .put("command", "xrEditor.toggleTerminal")
                    .put("title", "XR-Editor: 統合ターミナルを開閉"),
            )
            .put(
                JSONObject()
                    .put("command", "xrEditor.selectDisplayScale")
                    .put("title", "XR-Editor: 表示サイズ・ズームを変更（標準 / 大 / 特大）"),
            )
            .put(
                JSONObject()
                    .put("command", "xrEditor.selectOpticalTheme")
                    .put("title", "XR-Editor: 光学テーマを切替（OST 純黒 / VST 暗灰）"),
            )
            .put(
                JSONObject()
                    .put("command", "xrEditor.openManager")
                    .put("title", "XR-Editor: 管理画面を開く（ミニCLI / ストレージ許可 / 再起動）"),
            )
            .put(
                JSONObject()
                    .put("command", "xrEditor.requestStorage")
                    .put("title", "XR-Editor: Android ストレージアクセスを許可"),
            )

        val editorTitleMenu = JSONArray()
            .put(
                JSONObject()
                    .put("command", "xrEditor.openMenu")
                    .put("group", "navigation@999"),
            )

        val contextMenuItems = JSONArray()
            .put(
                JSONObject()
                    .put("command", "xrEditor.runDiagnostics")
                    .put("group", "z_xreditor@1"),
            )
            .put(
                JSONObject()
                    .put("command", "xrEditor.openLogs")
                    .put("group", "z_xreditor@2"),
            )
            .put(
                JSONObject()
                    .put("command", "xrEditor.openManager")
                    .put("group", "z_xreditor@3"),
            )

        val menus = JSONObject()
            .put("editor/title", editorTitleMenu)
            .put("editor/context", contextMenuItems)
            .put("explorer/context", contextMenuItems)

        val viewsContainers = JSONObject()
            .put(
                "activitybar",
                JSONArray().put(
                    JSONObject()
                        .put("id", "xr-editor")
                        .put("title", "XR-Editor")
                        .put("icon", "icon.svg"),
                ),
            )

        val views = JSONObject()
            .put(
                "xr-editor",
                JSONArray().put(
                    JSONObject()
                        .put("id", "xrEditor.menuView")
                        .put("name", "実機チェック & XR表示"),
                ),
            )

        val contributes = JSONObject()
            .put("commands", commands)
            .put("menus", menus)
            .put("viewsContainers", viewsContainers)
            .put("views", views)

        val pkg = JSONObject()
            .put("name", "xr-editor-tools")
            .put("displayName", "XR-Editor Diagnostics & Tools")
            .put("description", "Built-in Android XR diagnostics, optical presets, log viewer, and storage menu")
            .put("version", "0.5.2")
            .put("publisher", "koduki")
            .put("engines", JSONObject().put("vscode", "^1.80.0"))
            .put("categories", JSONArray().put("Other"))
            .put("activationEvents", JSONArray().put("onStartupFinished"))
            .put("main", "./extension.js")
            .put("contributes", contributes)

        return pkg.toString(2) + "\n"
    }

    fun buildExtensionJs(): String = """
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
          'editorGroupHeader.tabsBackground': '#000000'
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
          'editorGroupHeader.tabsBackground': '#12141a'
        };

        function callHost(method, path, body) {
          return new Promise((resolve, reject) => {
            const port = Number(process.env.XR_ADMIN_PORT || 0);
            const token = process.env.XR_ADMIN_TOKEN || '';
            if (!port) {
              reject(new Error('XR_ADMIN_PORT is not configured'));
              return;
            }
            const payload = body ? Buffer.from(body, 'utf8') : null;
            const headers = { 'X-XR-Token': token };
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
          } catch (_) {}
          try {
            await vscode.env.clipboard.writeText(content);
            copied = true;
          } catch (_) {}
          if (copied) {
            vscode.window.showInformationMessage(copiedMessage);
          }
        }

        async function runDiagnosticsCommand() {
          await vscode.window.withProgress(
            {
              location: vscode.ProgressLocation.Notification,
              title: 'XR-Editor: 実機チェックを実行中（PRoot / PTY / マウント / 疎通）...',
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
            title: 'XR-Editor 表示サイズ・ズーム',
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
            title: 'XR-Editor 光学テーマ切替',
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
            title: 'XR-Editor 実機チェック & XR設定メニュー',
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
              command: 'xrEditor.runDiagnostics',
            },
            {
              label: 'ターミナル開閉',
              description: 'POSIX bash / gh',
              icon: 'terminal',
              command: 'xrEditor.toggleTerminal',
            },
            {
              label: '表示サイズ変更',
              description: '標準 / 大 / 特大',
              icon: 'zoom-in',
              command: 'xrEditor.selectDisplayScale',
            },
            {
              label: '光学テーマ切替',
              description: 'OST 純黒 / VST 暗灰',
              icon: 'color-mode',
              command: 'xrEditor.selectOpticalTheme',
            },
            {
              label: '起動・エラーログを開く',
              description: '自動コピー',
              icon: 'output',
              command: 'xrEditor.openLogs',
            },
            {
              label: '管理画面を開く',
              description: 'ミニCLI / 再起動',
              icon: 'tools',
              command: 'xrEditor.openManager',
            },
            {
              label: 'Android ストレージ許可',
              description: '/android/sdcard マウント',
              icon: 'folder-opened',
              command: 'xrEditor.requestStorage',
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
            vscode.commands.registerCommand('xrEditor.openMenu', openMenuCommand),
            vscode.commands.registerCommand('xrEditor.runDiagnostics', runDiagnosticsCommand),
            vscode.commands.registerCommand('xrEditor.openLogs', openLogsCommand),
            vscode.commands.registerCommand('xrEditor.toggleTerminal', toggleTerminalCommand),
            vscode.commands.registerCommand('xrEditor.selectDisplayScale', selectDisplayScaleCommand),
            vscode.commands.registerCommand('xrEditor.selectOpticalTheme', selectOpticalThemeCommand),
            vscode.commands.registerCommand('xrEditor.openManager', openManagerCommand),
            vscode.commands.registerCommand('xrEditor.requestStorage', requestStorageCommand),
            vscode.window.registerTreeDataProvider('xrEditor.menuView', createSidebarMenuProvider())
          );

          const statusItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
          statusItem.text = '$(pulse) XRメニュー';
          statusItem.tooltip = 'XR-Editor 実機チェック・表示サイズ・光学テーマ・管理画面を開く';
          statusItem.command = 'xrEditor.openMenu';
          statusItem.show();
          context.subscriptions.push(statusItem);
        }

        function deactivate() {}

        module.exports = { activate, deactivate };
    """.trimIndent() + "\n"

    fun installInto(openVsCodeDir: File): File {
        val extDir = File(openVsCodeDir, "extensions/$EXTENSION_DIR_NAME")
        extDir.mkdirs()
        File(extDir, "icon.svg").writeText(buildIconSvg())
        File(extDir, "package.json").writeText(buildPackageJson())
        File(extDir, "extension.js").writeText(buildExtensionJs())
        return extDir
    }
}
