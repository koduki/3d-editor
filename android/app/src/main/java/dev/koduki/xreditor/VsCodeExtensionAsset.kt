package dev.koduki.xreditor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Generates and installs the tiny built-in VS Code extension (`xr-editor-tools`)
 * directly into `/opt/openvscode-server/extensions/xr-editor-tools/`.
 *
 * Adds a status bar button, editor title button, and Command Palette menu inside
 * VS Code so the user can run on-device diagnostics, copy reports/logs, grant
 * Android storage access, or switch to the fallback CLI/boot screen with 1 click.
 */
object VsCodeExtensionAsset {

    const val EXTENSION_DIR_NAME = "xr-editor-tools"

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

        val menus = JSONObject()
            .put("editor/title", editorTitleMenu)

        val contributes = JSONObject()
            .put("commands", commands)
            .put("menus", menus)

        val pkg = JSONObject()
            .put("name", "xr-editor-tools")
            .put("displayName", "XR-Editor Diagnostics & Tools")
            .put("description", "Built-in Android XR diagnostics, log viewer, and storage menu")
            .put("version", "0.5.0")
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
              label: '$(output) 起動・エラーログを開く（自動コピー）',
              description: 'openvscode-server と PRoot の最新ログを表示',
              action: openLogsCommand,
            },
            {
              label: '$(terminal) 管理画面を開く（ミニCLI / 再起動）',
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
            title: 'XR-Editor 実機チェック & 管理メニュー',
            placeHolder: '実行する項目を選択してください',
          });
          if (picked) {
            await picked.action();
          }
        }

        function activate(context) {
          context.subscriptions.push(
            vscode.commands.registerCommand('xrEditor.openMenu', openMenuCommand),
            vscode.commands.registerCommand('xrEditor.runDiagnostics', runDiagnosticsCommand),
            vscode.commands.registerCommand('xrEditor.openLogs', openLogsCommand),
            vscode.commands.registerCommand('xrEditor.openManager', openManagerCommand),
            vscode.commands.registerCommand('xrEditor.requestStorage', requestStorageCommand)
          );

          const statusItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 1000);
          statusItem.text = '$(pulse) XRチェック';
          statusItem.tooltip = 'XR-Editor 実機チェック・エラーログ・管理画面メニューを開く';
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
        File(extDir, "package.json").writeText(buildPackageJson())
        File(extDir, "extension.js").writeText(buildExtensionJs())
        return extDir
    }
}
