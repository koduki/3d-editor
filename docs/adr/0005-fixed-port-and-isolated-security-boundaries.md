# ADR-0005: 優先固定ポート `18765`・`KuCodeHost` 隔離・トークン認証ループバック管理 API

- **ステータス:** Accepted
- **日付:** 2026-11-22

## 背景（Context）

構成レビューを通じて、WebView・ローカルサーバー・内蔵拡張の間の通信およびライフサイクルに以下の課題が見つかった：

1. **エフェメラルポートによる状態消失**：起動ごとにランダムなポートで `openvscode-server` を開くと Web オリジン（`http://127.0.0.1:<port>`）が毎回変わり、ブラウザ側 IndexedDB に保存している開いていたタブ・サイドバー幅・UI 状態が再起動のたびに初期化されてしまう。
2. **JS ブリッジの露出リスクと ANR**：`addJavascriptInterface("KuCodeHost")` や `allowFileAccess` がエディタ表示中も残っていると、拡張や Webview コンテンツから Android ホスト操作（コマンド実行等）を呼べる攻撃面になる。また JS ブリッジ内で `runBlocking` を行うと WebView の JavaBridge スレッドが詰まり ANR を招く。
3. **別ポート開発サーバーの誤遷移**：開発者が統合ターミナルで `localhost:3000` 等の Web サーバーを起動してリンクを開いた際、`127.0.0.1` を一律 WebView 内に閉じ込めるとエディタ画面が開発対象アプリで上書きされてしまう。

## 決定事項（Decision）

1. **優先固定ポート `18765`**：`PosixRuntimeConfig.PREFERRED_SERVER_PORT = 18765` を優先バインドし、使用中の場合のみ OS 割り当てのエフェメラルポートへフォールバックする。
   - IDE は管理 API と独立した起動ごとの接続トークンを要求する。アプリが認証付き URL を開き、起動ログではトークンを伏せる。
2. **`KuCodeHost` の厳格隔離と非同期化**：
   - 管理画面は同梱の `boot.html` 専用 WebView に分離し、このビューにのみ `KuCodeHost` を登録する。エディタ用 WebView を保持したまま表示を切り替える。
   - 診断やミニ CLI 実行は `BootHostBridge` 内で `Dispatchers.IO` コルーチンへ非同期委譲し、完了時にコールバック（`window.__onDiagnosticsResult` / `window.__onCommandResult`）で返す。
3. **トークン認証ループバック管理 API（`LocalDiagnosticsServer`）**：
   - 内蔵拡張（`kucode-tools`）から Android 側機能（診断実行・ログ取得・クリップボードコピー・管理画面遷移・ストレージ権限・再起動）を呼ぶ経路は、`127.0.0.1` 限定バインドの `LocalDiagnosticsServer` に一本化する。
   - 起動ごとに `SecureRandom` で生成した 24 バイトランダムトークン（`KUCODE_ADMIN_TOKEN`）を PRoot プロセス環境変数経由で渡し、全リクエストで `X-KuCode-Token` ヘッダを定数時間比較（`MessageDigest.isEqual`）で検証する。認証前入力には行数・ヘッダ数・総サイズ・ワーカー数・接続キューの上限を設ける。
   - `ClipboardManager` 書き込みは `MainLooper` 上で実行し、バックグラウンドスレッド例外を防ぐ。
4. **ポート認識 URL ルーティング**：
   - `PosixRuntimeConfig.shouldKeepInsideWebView` でエディタ自身の HTTP ポート（`127.0.0.1:<editorPort>`）のみ WebView 内に留め、他ポートの `localhost`（例 `:3000`）や外部 URL は `Intent.ACTION_VIEW` で Android XR 上の別ブラウザウィンドウへ開く。

## 検討した代替案（Alternatives Considered）

- **代替案 A：エディタ画面でも `KuCodeHost` を常時アタッチする**
  - WebView 上の任意スクリプト・拡張機能からホスト機能へアクセス可能になりセキュリティ境界が崩れるため不採用。
- **代替案 B：毎回ランダムポートで起動する**
  - エディタのタブ・レイアウト状態が毎回消え、日常開発ツールとして致命的に使いづらいため不採用。

## 影響とトレードオフ（Consequences）

- **メリット**：再起動してもエディタのタブ・レイアウト状態が維持され、別ポートのプレビューも XR 上の隣ウィンドウで開ける。同時に JS ブリッジ露出と ANR リスク、認証前 DoS リスクを排除できた。
- **トレードオフ**：`LocalDiagnosticsServer` という小さなループバック HTTP サーバーを 1 つ維持する必要があるが、標準 `java.net.ServerSocket` のみ（外部依存ゼロ）で簡潔に保てている。

## 該当実装

- [MainActivity.kt](../../android/app/src/main/java/dev/koduki/kucode/MainActivity.kt)
- [BootHostBridge.kt](../../android/app/src/main/java/dev/koduki/kucode/BootHostBridge.kt)
- [LocalDiagnosticsServer.kt](../../android/app/src/main/java/dev/koduki/kucode/LocalDiagnosticsServer.kt)
- [PosixRuntimeConfig.kt](../../android/app/src/main/java/dev/koduki/kucode/PosixRuntimeConfig.kt)
- [PosixRuntimeConfigTest.kt](../../android/app/src/test/java/dev/koduki/kucode/PosixRuntimeConfigTest.kt)
