# XR-ACP Workbench — Android 15 / Android XR ネイティブシェル (`android/`)

XREAL AURA（Android XR / Android 15 API 35）および Android 15 Desktop Windowing 環境向けに、Phase 1 の空間デスクトップ IDE（`public/`）をネイティブ空間アプリとして包み込む薄い Kotlin + WebView シェルです。

## 主な機能

- **Pure `#000000` OST 起動:** Micro-OLED グラスで起動時の白フラッシュを防止
- **Android 15 ローカルブリッジ自動検出 (`LocalBridgeProbe.kt`):**
  - 起動時に `http://127.0.0.1:8765/api/health` を自動チェック
  - 稼働中ならローカルサーバーへ直接接続、未起動なら同梱 WebView アセット（`WebViewAssetLoader`）から起動し Tailscale リモートまたはオフラインデモを即利用可能
- **ハードウェアキーボードショートカット優先捕捉 (`HardwareShortcutRouter.kt`):**
  - `Ctrl+P`, `Ctrl+Shift+P`, `Ctrl+Shift+F`, `Ctrl+S`, `Ctrl+W`, `Ctrl+B`, `Ctrl+J`, `` Ctrl+` ``, `Ctrl+L`, `Ctrl+Space`, `F1`, `Alt+1..4` を Android / WebView に奪われる前に `Activity.dispatchKeyEvent` で捕捉し Web ワークベンチへ直送
- **独立空間パネル分離 (`SpatialPanelActivity.kt`):**
  - `XRHostBridge.openSpatialPanel("ai")` で Android XR の Home Space に隣接ウィンドウとして AI パネルを分離起動
- **Foreground Keep-Alive (`BridgeKeepAliveService.kt`):**
  - Android 15 `FOREGROUND_SERVICE_TYPE_DATA_SYNC` により、別ウィンドウへフォーカス移動しても ACP WebSocket / コンソールを切断させない

## ビルド手順（Android Studio / Gradle）

1. Web 資産を同期（Gradle ビルド時にも `syncWebAssets` タスクが自動実行されます）:
   ```bash
   npm run android:sync
   ```
2. Android Studio（Android SDK API 35 + JDK 17）で `android/` フォルダを開く。
3. デバッグ APK をビルド、または XREAL AURA（USB デバッグ有効）へ直接インストール:
   ```bash
   cd android
   gradle :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. Android 15 Linux Terminal（AVF）やリモート Linux ホスト側でバックエンドを起動する場合:
   ```bash
   ./scripts/setup-android15-linux.sh
   ```
