# ADR-0005: 優先固定ポート `18765`・`XREditorHost` 隔離・トークン認証ループバック管理 API

- **ステータス:** Accepted
- **日付:** 2026-11-22

## 背景（Context）

構成レビューを通じて、WebView・ローカルサーバー・VS Code 内蔵拡張の間の通信およびライフサイクルに以下の課題が見つかった：

1. **エフェメラルポートによる状態消失**：起動ごとにランダムなポートで `openvscode-server` を開くと Web オリジン（`http://127.0.0.1:<port>`）が毎回変わり、VS Code Web が IndexedDB に保存している開いていたタブ・サイドバー幅・UI 状態が再起動のたびに初期化されてしまう。
2. **JS ブリッジの露出リスクと ANR**：`addJavascriptInterface("XREditorHost")` や `allowFileAccess` が VS Code ページ表示中も残っていると、VS Code 拡張や Webview コンテンツから Android ホスト操作（コマンド実行等）を呼べる攻撃面になる。また JS ブリッジ内で `runBlocking` を行うと WebView の JavaBridge スレッドが詰まり ANR を招く。
3. **別ポート開発サーバーの誤遷移**：開発者が統合ターミナルで `localhost:3000` 等の Web サーバーを起動してリンクを開いた際、`127.0.0.1` を一律 WebView 内に閉じ込めるとエディタ画面が開発対象アプリで上書きされてしまう。

## 決定事項（Decision）

1. **優先固定ポート `18765`**：`PosixRuntimeConfig.PREFERRED_SERVER_PORT = 18765` を優先バインドし、使用中の場合のみ OS 割り当てのエフェメラルポートへフォールバックする。
2. **`XREditorHost` の厳格隔離と非同期化**：
   - `XREditorHost` と `allowFileAccess = true` は `file:///android_asset/boot.html` 表示中のみ有効化し、`openvscode-server` へ遷移する直前に `removeJavascriptInterface`・`allowFileAccess = false` で切り離す。
   - 診断やミニ CLI 実行は `BootHostBridge` 内で `Dispatchers.IO` コルーチンへ非同期委譲し、完了時にコールバック（`window.__onDiagnosticsResult` / `window.__onCommandResult`）で返す。
3. **トークン認証ループバック管理 API（`LocalDiagnosticsServer`）**：
   - 内蔵 VS Code 拡張（`xr-editor-tools`）から Android 側機能（診断実行・ログ取得・クリップボードコピー・管理画面遷移・ストレージ権限・再起動）を呼ぶ経路は、`127.0.0.1` 限定バインドの `LocalDiagnosticsServer` に一本化する。
   - 起動ごとに `SecureRandom` で生成した 24 バイトランダムトークン（`XR_ADMIN_TOKEN`）を PRoot プロセス環境変数経由で渡し、全リクエストで `X-XR-Token` ヘッダ一致を検証する（タイムアウト 60 秒）。
   - `ClipboardManager` 書き込みは `MainLooper` 上で実行し、バックグラウンドスレッド例外を防ぐ。
4. **ポート認識 URL ルーティング**：
   - `PosixRuntimeConfig.shouldKeepInsideWebView` で `file://` とエディタ自身のポート（`127.0.0.1:<editorPort>`）のみ WebView 内に留め、他ポートの `localhost`（例 `:3000`）や外部 URL は `Intent.ACTION_VIEW` で Android XR 上の別ブラウザウィンドウへ開く。

## 検討した代替案（Alternatives Considered）

- **代替案 A：VS Code 画面でも `XREditorHost` を常時アタッチする**
  - WebView 上の任意スクリプト・拡張機能からホスト機能へアクセス可能になりセキュリティ境界が崩れるため不採用。
- **代替案 B：毎回ランダムポートで起動する**
  - VS Code Web のタブ・レイアウト状態が毎回消え、日常開発ツールとして致命的に使いづらいため不採用。

## 影響とトレードオフ（Consequences）

- **メリット**：再起動してもエディタのタブ・レイアウト状態が維持され、別ポートのプレビューも XR 上の隣ウィンドウで開ける。同時に JS ブリッジ露出と ANR リスクを排除できた。
- **トレードオフ**：`LocalDiagnosticsServer` という小さなループバック HTTP サーバーを 1 つ維持する必要があるが、標準 `java.net.ServerSocket` のみ（外部依存ゼロ）で約 200 行に収まっている。

## 該当実装

- [MainActivity.kt](../../android/app/src/main/java/dev/koduki/xreditor/MainActivity.kt)
- [BootHostBridge.kt](../../android/app/src/main/java/dev/koduki/xreditor/BootHostBridge.kt)
- [LocalDiagnosticsServer.kt](../../android/app/src/main/java/dev/koduki/xreditor/LocalDiagnosticsServer.kt)
- [PosixRuntimeConfig.kt](../../android/app/src/main/java/dev/koduki/xreditor/PosixRuntimeConfig.kt)
- [PosixRuntimeConfigTest.kt](../../android/app/src/test/java/dev/koduki/xreditor/PosixRuntimeConfigTest.kt)
