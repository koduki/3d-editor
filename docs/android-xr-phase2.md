# Phase 2 設計書: Android 15 / Android XR ネイティブ殻（XRHostBridge）

Phase 1 で完成した **XR-ACP Workbench（Web 空間デスクトップ IDE + Node.js バックエンド）** を、Google Play / Meta Horizon Store から Termux APK サイドロードなしで配布・運用するための Android 15 (API 35+) / Android XR ネイティブシェル設計です。

---

## 1. なぜ「薄いネイティブ殻 + Web ワークベンチ」なのか

1. **Android Chrome の制約を解消:**
   - Chrome のショートカット奪取（`Ctrl+P` 印刷、`Ctrl+W` タブ閉鎖、`Ctrl+L` URL バーフォーカス、`Ctrl+S` ページ保存）を Android ネイティブ `Activity.dispatchKeyEvent` で横取りし、WebView 内の IDE へ確実に届ける。
   - タブ強制サスペンドによる WebSocket 切断を防ぐ（Foreground Service によるローカルブリッジ保持）。
2. **Android 15（API 35）標準機能へのスムーズな追従:**
   - **Desktop Windowing / Freeform Multi-Window:** 物理キーボード・マウス／トラックパッドのホバー・右クリック・リサイズを標準サポート。
   - **Android 15 Linux Terminal (AVF / Debian VM):** 将来的に Android XR 側で有効化された際、VM 内の `127.0.0.1:8765` で稼働する `node server.mjs` へ無改造でそのまま接続できる（`glibc` フル対応で `agy acp` もネイティブ動作）。
   - **AVF 未有効端末向けのフォールバック:** 必要なら Acode 同様に `jniLibs/` に `libproot.so` + Alpine 最小ブートストラップを同梱するか、Tailscale リモートホストへ接続する。
3. **Android XR Spatial Panels（Jetpack XR SDK）:**
   - 本アプリの `public/xr-host.js` はすでに `window.XRHostBridge` を抽象化済み。
   - `XRHostBridge.openSpatialPanel("ai")` を実装するだけで、中央エディタと右 AI パネルを 3D 空間内の独立パネルとして分離配置できる（Web 側も `?focusPane=ai` に対応済み）。

---

## 2. `window.XRHostBridge` インターフェース仕様

Android 側（Kotlin / `WebView.addJavascriptInterface(XrHostBridge(), "XRHostBridge")`）が提供する最小 API：

```kotlin
interface XRHostBridge {
    @JavascriptInterface
    fun getCapabilities(): String
    // 例: {"nativeHost":true,"spatialPanels":true,"shortcutCapture":true,"android15Avf":true}

    @JavascriptInterface
    fun registerShortcuts(shortcutsJson: String)
    // 例: ["Ctrl+P","Ctrl+Shift+P","Ctrl+Shift+F","Ctrl+S","Ctrl+B","Ctrl+J","Ctrl+L"]

    @JavascriptInterface
    fun openSpatialPanel(paneId: String)
    // Jetpack XR SpatialPanel として ?focusPane=<paneId> を独立起動

    @JavascriptInterface
    fun setWindowTitle(title: String)
}
```

---

## 3. Android 15 (API 35) 実装チェックリスト

- [ ] `compileSdk = 35`, `targetSdk = 35`, `minSdk = 34`（Android XR / Android 15 対応）
- [ ] `AndroidManifest.xml` に `android:resizeableActivity="true"` および `PROPERTY_XR_ACTIVITY_START_MODE` を設定
- [ ] `dispatchKeyEvent` で `Ctrl+P / S / W / L / J / B / Shift+P` を WebView へ優先転送
- [ ] ローカル実行モード時、`127.0.0.1:8765/api/health` をヘルスチェックし、未起動ならローカルランタイム（AVF / 内蔵 PRoot）起動または Tailscale プロファイル選択へ誘導
- [ ] バッテリー最適化除外ガイド＆Foreground Service 通知で ACP プロセス切断を防止
