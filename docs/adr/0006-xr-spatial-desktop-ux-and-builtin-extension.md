# ADR-0006: 物理キーボード・XR マルチウィンドウ最適化 UX と内蔵拡張 `xr-editor-tools`

- **ステータス:** Accepted
- **日付:** 2026-11-22

## 背景（Context）

Android XR（XREAL AURA 等の光学シースルーグラス）に物理キーボード＋マウス／トラックパッドを接続して開発する際、通常のスマホ・デスクトップ向け設定のままでは以下の実運用上の問題が生じた：

1. **実機操作フィードバック**：VS Code の右下ステータスバーは XR の視界端にありクリックしづらい。また JIS キーボードでは `Ctrl+\`` によるターミナル開閉が押しづらい。
2. **光学シースルー（OST）視認性**：通常のダークグレー背景は Micro-OLED 上で発光して現実世界と干渉し、細字や暗いコメント文字は環境光で潰れやすい。
3. **空間マルチウィンドウでの迷子・誤操作**：どのウィンドウにキーボードフォーカスがあるか分かりにくく、トラックパッドのピンチズーム誤爆や Back キー誤押下による即終了が起きやすい。
4. **合成 `KeyboardEvent` の干渉**：初期実装では Android 側で `Ctrl+S/W/O/P` を JS 合成イベントに変換していたが、信頼フラグ（`isTrusted`）のない合成イベントは Monaco / xterm.js や IME と衝突しやすい。

## 決定事項（Decision）

1. **物理キーボードの実 `KeyEvent` パススルー**
   - `HardwareShortcutRouter` はホスト専用ショートカット `Ctrl+Alt+D`（管理・診断画面トグル）のみをフックし、その他のキー・ショートカット・IME 入力はすべてネイティブ `KeyEvent` のまま WebView へ直送する。
   - `OnBackPressedCallback` で Back 操作による誤終了をガードし、WebView のピンチズーム（`setSupportZoom(false)`）を無効化して倍率調整を VS Code の `window.zoomLevel` に統一する。
2. **OST 初期プリセットと空間フォーカス演出**
   - 初回設定（既存ユーザー設定を壊さない `mergeDefaultVsCodeSettings`）で、純黒 OST 背景 `#000000`・`fontWeight: "500"`・`fontSize: 16`・`lineHeight: 26`・`window.zoomLevel: 0.5`・太さ `3px` のシアンカーソル・明るめコメント `#8da2be`・ミニマップ/Welcome/パンくず/テレメトリ無効化を適用する。
   - Android WebView での xterm WebGL 黒画面を防ぐため `terminal.integrated.gpuAcceleration: "off"` を初期設定する。
   - `MainActivity` のルート `FrameLayout` に `1.5dp` のフォーカス枠（アクティブ `#38BDF8` / 非アクティブ `#18181B`）を設け、`onWindowFocusChanged` で非フォーカス時に `alpha = 0.55f` へ自動減光する。
3. **システム内蔵 VS Code 拡張 `xr-editor-tools`（v0.5.2）**
   - `/opt/openvscode-server/extensions/xr-editor-tools/` へ配置し、起動ごとに `prepareRuntimeDirectories()` で自動同期する（rootfs 再展開なしで APK 上書き更新が即反映され、`extensions.json` キャッシュによるスキップも回避）。
   - 押しやすい **左端 Activity Bar（バイザーアイコン `XR-Editor` サイドバー）**、右クリックメニュー、エディタ右上ボタン、左下ステータスバー（`XRメニュー`）、コマンドパレットから以下を提供する：
     - 実機チェック実行（レポート表示＆自動コピー）
     - 統合ターミナル開閉（JIS 配列対策）
     - 表示サイズ切替（`標準` / `大` / `特大`）
     - 光学テーマ切替（`OST 純黒 #000000` / `VST 暗灰 #12141a`）
     - 起動・エラーログ表示 / 管理画面（`boot.html`）遷移 / Android ストレージ許可設定

## 検討した代替案（Alternatives Considered）

- **代替案 A：ステータスバー右下のみに診断ボタンを置く**
  - 実機検証で右下は非常に押しづらいことが判明したため、左 Activity Bar サイドバーと右クリックメニュー中心へ変更。
- **代替案 B：Android 側オーバーレイツールバーを常設する**
  - エディタ画面の垂直領域を圧迫し XR ウィンドウのノイズになるため、VS Code 標準の Activity Bar / サイドバーに自然に統合した。

## 影響とトレードオフ（Consequences）

- **メリット**：物理キーボード本来の操作感（Monaco / xterm.js / IME）を損なわず、XR 空間でも視認性とクリックしやすさを両立できた。
- **トレードオフ**：`xr-editor-tools` のコードは Kotlin（`VsCodeExtensionAsset.kt`）内に文字列生成で埋め込んでいるため、将来拡張が大規模化する場合は独立パッケージ化を検討する余地がある。

## 該当実装

- [MainActivity.kt](../../android/app/src/main/java/dev/koduki/xreditor/MainActivity.kt)
- [HardwareShortcutRouter.kt](../../android/app/src/main/java/dev/koduki/xreditor/HardwareShortcutRouter.kt)
- [PosixRuntimeConfig.kt](../../android/app/src/main/java/dev/koduki/xreditor/PosixRuntimeConfig.kt)
- [VsCodeExtensionAsset.kt](../../android/app/src/main/java/dev/koduki/xreditor/VsCodeExtensionAsset.kt)
- [HardwareShortcutRouterTest.kt](../../android/app/src/test/java/dev/koduki/xreditor/HardwareShortcutRouterTest.kt)
- [VsCodeExtensionAssetTest.kt](../../android/app/src/test/java/dev/koduki/xreditor/VsCodeExtensionAssetTest.kt)
