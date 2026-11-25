# ADR-0011: 内蔵 ACP v1 AI エージェントチャット統合と v0.9.0 全層ハードニング

- **Status:** Accepted
- **Date:** 2026-11-24
- **Version:** `0.9.0`

---

## 1. 背景（Context）

XREAL Android XR Puck 実機（`v0.8.0-debug`）で取得した Flight Recorder 診断バンドルおよびコードベース全体の総点検により、次の構造的課題が判明した。

1. **OpenVSCode 上での GitHub Copilot Chat 失敗**
   - OpenVSCode Server の既定ギャラリーは Open VSX であり、Microsoft Marketplace 専用の `GitHub.copilot-chat` 拡張は存在しないため、OAuth 承認後に `NotFound: The extension 'GitHub.copilot-chat' cannot be installed` で必ず失敗する。
   - 一方で Microsoft Visual Studio Marketplace への接続変更は利用規約・再配布ポリシーに抵触する（ADR-0008 の方針とも相容れない）。
2. **OpenVSCode `--user-data-dir` と設定／ログパスの不一致**
   - 起動引数 `--user-data-dir /var/lib/kucode-vscode/user` を指定しているため、OpenVSCode 1.109.5 は Machine 設定を `user/Machine/settings.json`、サブプロセスログを `user/logs/<timestamp>/` に配置していた。従来コードは `data/Machine/settings.json`・`data/logs/` を参照していたため、OST 初期設定や OpenVSCode サブログ回収が機能していなかった。
3. **ネイティブ 16KB ページアライメント・Android 15 FGS・セキュリティ／安定性課題**
   - `libproot-loader.so` が `GNUmakefile` の `LOADER_LDFLAGS` により 4KB (`0x1000`) アライメントのままで Android 15+ 16KB ページ端末で起動不可となるリスク。
   - `ServerKeepAliveService` が `dataSync`（Android 15 で 6 時間 / 24 時間制限）を使用。
   - `bootView` の `onRenderProcessGone` 未実装、WebView ファイル選択・D&D・Edge-to-Edge WindowInsets 未対応、`KuCodeDocumentsProvider.isChildDocument` の `..` セグメント検証漏れ、`LocalDiagnosticsServer` のブラウザ CSRF／Slowloris／`Transfer-Encoding` 耐性不足、Flight Recorder の `KuCodeApplication.onCreate` 自動開始によるレポート上書きやメインスレッド同期 I/O、R8 Full Mode での `@JavascriptInterface` 剥離リスクなど計 21 件の課題。

---

## 2. 決定（Decision）

### 2.1 内蔵 ACP v1（Agent Client Protocol）AI エージェントパネルの実装
- OpenVSCode の内蔵チャットセットアップ UI は `user/Machine/settings.json` に `"chat.disableAIFeatures": true` を設定して非表示にする（Open VSX ポリシー遵守）。
- 代わりに、内蔵拡張 `kucode-tools` 内に **ゼロ依存・MIT ライセンスの ACP v1（JSON-RPC 2.0 NDJSON over stdio）クライアント**（`acp-client.js`）と **OST 純黒 Webview チャットパネル**（`acp-webview.js`, ビュー ID `kucode.acpChatView`）を実装する。
- 対応プリセット（v0.9.1 で Google Antigravity を既定に追加）：
  - **Google Antigravity CLI (`agy acp --disable-telemetry`)**（既定、`kucode-install-agy` で公式 `antigravity.google/cli/install.sh` から arm64 環境へ導入＆`agy acp --login` 認証。ACP v1 `configOptions` / `session/set_config_option` によるモデル・思考レベル切替、Diff 表示、`-32000` 認証誘導に対応。単体 `agy_acp_server.par` の TCMalloc 48-bit VA 問題と異なり、公式 `agy` CLI は標準 `malloc` 採用のため 39-bit VA Android ARM64 カーネルでも動作しやすい）
  - **GitHub Copilot CLI (`copilot --acp --stdio`)**（`kucode-install-copilot` で arm64 環境へワンクリック導入＆`ld-linux-aarch64` ラッパー自動フォールバック）
  - **Gemini CLI (`gemini --experimental-acp`)**
  - **Claude Code (`claude-agent-acp`)**
  - **Codex CLI (`codex-acp`)**
  - **OpenCode (`opencode acp`)**
  - ユーザー設定 `kucode.acp.agents` による任意 ACP エージェント追加
- クライアント側ファイル操作（`fs/read_text_file`, `fs/write_text_file`）は `resolveSafeWorkspacePath()` により `/workspace` 境界内へ厳格に閉じ込め、`..` やシンボリックリンク脱出を拒否する。
- 内蔵 OpenVSCode の Node 22 を `/usr/local/bin/node` へシンボリックリンクし、`PATH` 先頭に `/root/.local/bin` を追加する。

### 2.2 OpenVSCode パス修正とレガシー移行
- `PosixRuntimeConfig.Paths.machineSettingsFile` を `user/Machine/settings.json` へ修正し、既存 `data/Machine/settings.json` が存在する場合のみ初回起動時に自動移行する（JSONC も無改変で保持）。
- `SessionLogStore.extractOpenVsCodeSubLogs` が `user/logs`（＋フォールバックで `data/logs`, `logs`）を探索し、`remoteagent.log` / `ptyhost.log` / `remoteexthost.log` / `remoteCLI.log` / `sharedprocess.log` / `exthost*/*.log` をセッション時間窓付きで回収する。

### 2.3 全層ハードニング（21 件の一括修正）
- **16KB ページ完全対応**：`scripts/build-proot-android.sh` で `src/GNUmakefile` にも `-z,max-page-size=16384,-z,common-page-size=16384` を適用し、ビルド時と CI で `llvm-readelf` による全 `LOAD` セグメント検証を強制。
- **Android 15 Foreground Service**：`ServerKeepAliveService` を `specialUse`（`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 明記）へ移行し、`startForeground` 例外ガード・`onTimeout` の無条件 `stopSelf()`・専用モノクロベクタ通知アイコン（`ic_stat_kucode.xml`）を適用。
- **WebView & Android XR ホスト**：`bootView` にも `onRenderProcessGone` 復旧と `blockNetworkLoads = true` を追加；`onShowFileChooser` / `requestDragAndDropPermissions` / `WindowInsetsCompat`（`systemBars | displayCutout | ime`）を実装；`POST_NOTIFICATIONS` 初回許可リクエストと `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` を導入；`network_security_config.xml` で loopback 外の平文通信を禁止しつつユーザー CA を信頼；`onCreateWindow` で `shouldInterceptRequest` により OAuth URL の二重 GET を防止し同時ポップアップ数を制限；サブフレームの外部遷移に `hasGesture()` を要求；`singleTask`・`enableOnBackInvokedCallback="true"`・`grammaticalGender` を適用。
- **DocumentsProvider / LocalDiagnosticsServer / LogRedactor**：`WorkspaceDocumentStore.isChildDocument` を正規セグメント検証へ刷新し `create()` を原子的リトライ化；`KuCodeDocumentsProvider` に `notifyChange`・ディレクトリ拒否・SVG サムネイル除外・レポート再生成デバウンスを導入；`LocalDiagnosticsServer` に `Origin`/`Sec-Fetch-Site`/非ループバック `Host` 拒否・Slowloris 壁時計デッドライン・`Transfer-Encoding` 501・重複 `Content-Length` 400・403 監査ログレート制限を追加；`LogRedactor` に PEM・URL 認証情報・フラグメント・各種 API キー接頭辞を追加。
- **Flight Recorder & プロセス管理**：`KuCodeApplication.onCreate` での自動セッション開始を廃止し `MainActivity`/`ServerKeepAliveService` のみで開始（Android Files 参照で異常レポートを上書きしない）；通常書き込み・初期照合を単一バックグラウンド `writerExecutor` へオフロード；OOM クラッシュ軽量パスと PID 再利用二重紐付け防止を実装；XREAL 実機の既知無害ノイズ 5 パターンをトリアージフィルタへ追加；`OpenVsCodeLauncher` に `.server-pid` 孤児プロセス回収、`PosixShellExecutor` に 256KiB 出力上限とコルーチン読取りを導入。
- **R8 & CI 品質ゲート**：`proguard-rules.pro` に `-keepattributes *Annotation*,SourceFile,LineNumberTable` と `public class BootHostBridge` 保持ルールを追加；CI で `concurrency`、ACP/拡張テスト、実 `openvscode-server` フラグスモークテスト、`:app:testReleaseUnitTest`、無署名 `:app:assembleRelease`、16KB ELF 検証、全 9 ライセンス資産検証を毎回実行。

---

## 3. 結果とトレードオフ（Consequences）

### メリット
- OpenVSCode / Open VSX の利用規約を完全に守りながら、Copilot を含む複数の最新エージェント型 AI を OST 純黒 UI で直接操作できる。
- エージェントの進化・切替（Copilot CLI ↔ Gemini ↔ Claude ↔ Codex ↔ OpenCode）が KuCode の拡張更新なしで設定変更だけで完結する。
- Android 15（16KB ページ・Edge-to-Edge・FGS 制限・R8 Full Mode）およびマルチウィンドウ XR 運用での実機クラッシュ・セキュリティ・診断欠落リスクを一掃できる。

### トレードオフ
- 各 AI エージェント CLI（`copilot`, `gemini`, `claude-agent-acp` 等）は初回のみターミナル経由でオンライン導入・ログインが必要（オフライン APK 肥大化を防ぐため意図的に非同梱）。
- `specialUse` FGS を Google Play ストアへ公開する場合は、Play Console の FGS 宣言フォームで `local_posix_ide_runtime_for_xr_multiwindow` の用途説明が必要となる。
