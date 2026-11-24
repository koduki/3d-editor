# ADR-0010: XR 外部ブラウザ起動のエディタ保護（`onCreateWindow` / `kucode-open`）と起動〜終了単位フライトレコーダー（AI 診断バンドル）

- **ステータス:** Accepted
- **日付:** 2026-11-24

## 背景（Context）

XREAL Android XR 実機上で `gh auth login` 等の外部ブラウザ連携と障害調査を行う際、以下の 2 つの重大な課題が判明した：

1. **`gh auth` 等で外部ブラウザを開くと既存エディタウィンドウが応答不能になる問題**
   - `openvscode-server` は統合ターミナルに `BROWSER=.../helpers/browser.sh` を注入し、`gh auth login` 実行時に Web 側で `window.open("https://github.com/login/device", "_blank", "noopener")` を呼び出す。
   - Android `WebView` の既定値 `setSupportMultipleWindows(false)` では、Chromium が `window.open(..., "_blank")` を既存メインフレームへのトップレベル遷移（`kReuse`）に変換するため、`WebViewClient.shouldOverrideUrlLoading` が遷移をキャンセルする前に **メインフレームで `beforeunload` が同期発火** し、VS Code Web の `BrowserLifecycleService` が `onWillShutdown` / `onDidShutdown` を実行して全 WebSocket・Extension Host 接続・統合ターミナルを永続終了させていた。
   - 加えて、VS Code の Webview サブフレーム（`*.vscode-cdn.net`）の `isForMainFrame` 判定欠如、外部 `ACTION_VIEW` Intent の `FLAG_ACTIVITY_NEW_TASK` 欠如、WebView レンダラープロセス回収時（`onRenderProcessGone`）の自動復旧欠如が重なっていた。

2. **起動〜終了（正常・異常）を通じた横断診断ログ・メトリクスの欠如**
   - 従来はメモリ上の直近 300 行リングバッファのみで `openvscode-server` 標準出力しか保持しておらず、WebView コンソール／ナビゲーション、Android Activity／フォーカス／メモリ逼迫、プロセス自身の `logcat`（Chromium C++ / IME / GL）、OpenVSCode 内部ログ（`ptyhost.log` / `exthost.log`）、およびクラッシュ・ANR・LMKD 強制終了時の過去セッション記録が失われていた。

## 決定事項（Decision）

1. **エディタ WebView を一切アンロードさせない 2 段構えの外部ブラウザ連携**
   - **CLI 直送ルート（推奨・高速）**：`LocalDiagnosticsServer` に認証付き `POST /open-url`（`http`/`https` 限定、エディタ自身のポートおよび `?tkn=` 漏洩を拒否）を追加し、`/usr/local/bin/kucode-open`・`xdg-open` と環境変数 `GH_BROWSER` / `BROWSER`、`--without-browser-env-var` を設定。`gh auth login` からは VS Code の確認モーダルや WebView を経由せず直接 `FLAG_ACTIVITY_NEW_TASK` で隣の XR ブラウザウィンドウを起動する。
   - **WebView ポップアップ隔離ルート**：`setSupportMultipleWindows(true)` + `javaScriptCanOpenWindowsAutomatically = true` + `WebChromeClient.onCreateWindow` を実装し、一時デタッチ WebView で遷移先 URL を捕捉して外部ブラウザへ委譲後すぐ破棄することで、メインフレームの `beforeunload` 発火を完全に防止する。
   - **サブフレーム保護 & レンダラー自動復旧**：`!isForMainFrame` 時は `*.vscode-cdn.net` / `about:` / `blob:` / `data:` を許可し、`onRenderProcessGone` でエディタ WebView を自動再生成して稼働中サーバーへ再接続する。

2. **起動〜終了単位のフライトレコーダー（`SessionLogStore` / `SessionTelemetryManager`）と AI 診断バンドル**
   - `KuCodeApplication.onCreate()` から終了までを 1 セッション（`<filesDir>/diagnostics/sessions/<sessionId>/`）として管理し、以下を即時フラッシュ付き NDJSON で統合収集する：
     - Android Activity / Service / Window Focus / `onTrimMemory` / Thermal / Network イベント
     - エディタ・管理画面双方の WebView ナビゲーション・HTTP/SSL エラー・コンソール出力・未捕捉 JS エラー
     - `openvscode-server` 標準出力＋内部ファイルログ（`ptyhost.log` / `exthost.log` / `remoteagent.log`）＋内蔵拡張 `kucode-tools` のターミナル開閉・ウィンドウ状態イベント（`POST /event`）
     - 自プロセス `logcat --pid=<myPid>`（Chromium / IME / GL 等のネイティブ警告・エラー）
     - 定期・イベント駆動のリソースメトリクス（空き/総 RAM、PSS、JVM/Native Heap、FD 数、スレッド数、空き容量、サーマル状態、バッテリー、ネットワーク）
   - **正常終了・異常終了の双方に対応**：
     - 正常終了（Activity 終了・通知からの停止）時は最終メトリクスと内部ログを収集して `ENDED_CLEANLY` で確定。
     - 未捕捉 JVM 例外はチェイン型 `UncaughtExceptionHandler` でスタックトレースを同期保存し `ENDED_ABNORMALLY (CRASH_JVM)` で確定。
     - OS による強制終了（ANR、LMKD `LOW_MEMORY`、ネイティブクラッシュ、シグナル等）は次回起動時に Android `ActivityManager.getHistoricalProcessExitReasons`（`ApplicationExitInfo`）を PID 照合して自動確定し、ANR トレース等も添付する。
   - **秘密情報の自動マスク（`LogRedactor`）と既知無害ログの自動注釈**：
     - 接続トークン（`?tkn=` / `vscode-tkn`）、管理トークン、OAuth `code`、GitHub トークン（`ghp_*` / `gho_*` / `github_pat_*`）を保存・出力前に自動マスクし、AI へそのまま安全に貼り付けられる構造化 Markdown（`kucode-session-v1`）を生成する。
   - **4 経路での閲覧・取得**：
     1. VS Code 内蔵拡張 `kucode-tools`（「セッション診断履歴 & AI レポートを開く」）
     2. 管理画面 `boot.html`（異常終了バナー・セッション選択・コピー・共有フォルダ保存）
     3. POSIX ターミナル CLI `kucode-logs`（`list` / `show prev` / `export`）および `/var/log/kucode` マウント
     4. Android「Files」アプリの読み取り専用ルート **KuCode Logs**（`KuCodeDocumentsProvider`）

## 影響とトレードオフ（Consequences）

- **メリット**：`gh auth login` や外部リンク起動でエディタ状態・ターミナルセッションが一切壊れなくなる。また再現困難な実機クラッシュ・OOM・フォーカス問題も、次回起動後に過去セッションを選ぶだけで AI が即座に原因特定できる完全な時系列バンドルを取得できる。
- **トレードオフ**：セッションごとにディスク書き込みが発生するが、ヘッド固定＋テールローテーション＋アラート別保存と最大 20 セッション自動ローテーションにより容量上限を厳格に抑えている。

## 該当実装

- [MainActivity.kt](../../android/app/src/main/java/dev/koduki/kucode/MainActivity.kt)
- [PosixRuntimeConfig.kt](../../android/app/src/main/java/dev/koduki/kucode/PosixRuntimeConfig.kt)
- [SessionLogStore.kt](../../android/app/src/main/java/dev/koduki/kucode/SessionLogStore.kt)
- [SessionTelemetryManager.kt](../../android/app/src/main/java/dev/koduki/kucode/SessionTelemetryManager.kt)
- [LogRedactor.kt](../../android/app/src/main/java/dev/koduki/kucode/LogRedactor.kt)
- [LocalDiagnosticsServer.kt](../../android/app/src/main/java/dev/koduki/kucode/LocalDiagnosticsServer.kt)
- [KuCodeDocumentsProvider.kt](../../android/app/src/main/java/dev/koduki/kucode/KuCodeDocumentsProvider.kt)
- [extension.js](../../android/app/src/main/assets/kucode-tools/extension.js)
- [boot.html](../../android/app/src/main/assets/boot.html)
