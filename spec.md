# KuCode 仕様書 (spec.md)

- **Product:** KuCode（くうこーど / *"koo-code"*）— *空間に、書く。*
- **Version:** 0.9.1
- **Date:** 2026-11-25
- **Status:** Active（現行実装ベースライン＋第5節の次期仕様）

---

次期セルフホスト開発の規範仕様は第5節を参照。第1〜4節は現行実装の記録。

## 1. プロジェクト概要

### 1.1 目的
Android XR（XREAL AURA 等）の空間マルチウィンドウ環境（物理キーボード＋マウス／トラックパッド前提）において、**APK 1 本をインストールするだけで別途サーバー設定や Termux 導入なしに使える、空間コンピューティングのためのコードエディタ＋内蔵 POSIX 環境＋ACP AI エージェント基盤** を提供する。

### 1.2 コアスコープ（4 要件）
1. **`openvscode-server` 完全内蔵（別途インストール・手動実行不要）**
   - Debian minimal（`arm64` / `glibc` / `apt`）rootfs と公式 `openvscode-server`（`linux-arm64`）を APK に内蔵し、アプリ起動時に自動展開・自動起動する。
2. **基礎的なエディタ UI と空間視認性**
   - 「ファイルを開く / 閉じる / 編集する / 保存する」の基礎操作に集中し、スマホ向け仮想キーや過剰な独自 UI は持たない。
   - `user/Machine/settings.json` に純黒 OST テーマ（`#000000`）と `"chat.disableAIFeatures": true` の初期値を適用し、Welcome・ミニマップ・パンくず・テレメトリ・ワークスペース信頼ダイアログ・動作しない OpenVSCode 内蔵チャット導入 UI のノイズを抑える（ユーザー側の `user/User/settings.json` や JSONC 設定は上書きしない）。
3. **Android XR ↔ POSIX 双方向ファイルアクセス**
   - **POSIX → Android 領域（マウント）**：アプリ共有領域を `/android/shared` に常時マウントし、ユーザーが全ファイルアクセス（`MANAGE_EXTERNAL_STORAGE`）を許可した場合は `/storage/emulated/0` を `/android/sdcard` としてマウント（`/workspace/android` から直接参照可能）。
   - **Android → POSIX 領域（通常フォルダ公開）**：Android 標準 `DocumentsProvider`（`KuCodeDocumentsProvider`）を実装し、Android XR の標準「Files」アプリや他アプリから POSIX の `/workspace` を通常フォルダとして読み書きできる（`NOFOLLOW_LINKS` 境界制御付き）。
4. **内蔵ターミナル・パッケージ管理 & 内蔵 ACP AI エージェントチャット**
   - 人間と AI エージェントが同じ `/workspace` 上で開発できるよう、Debian POSIX 世界（`apt` / `git` / `gh` / `/usr/local/bin/node`）と `kucode-install-agy` / `kucode-install-copilot` 補助コマンドを内蔵する。
   - 内蔵拡張 `kucode-tools` に **ACP v1（Agent Client Protocol）対応 OST 純黒チャットパネル**（`kucode.acpChatView`）を統合し、**Google Antigravity CLI（`agy acp`）** を既定エージェントとして、GitHub Copilot CLI（`copilot --acp --stdio`）・Gemini CLI・Claude Code・Codex CLI・OpenCode 等の ACP 対応エージェントを Open VSX 制約なく利用できる（`configOptions` によるモデル・思考レベル切替、Diff 表示、`-32000` 認証誘導に対応）。
   - `openvscode-server` 下部の統合ターミナルを標準としつつ、実機で `node-pty` が動作しない場合でも `boot.html` のフォールバック CLI コンソールから PRoot 経由で `bash -lc "<cmd>"` を直接実行できる。

---

## 2. アーキテクチャ（Android XR 向けクリーン設計）

```text
┌────────────────────────────────────────────────────────────────┐
│ KuCode APK (dev.koduki.kucode / compileSdk=36 / targetSdk=35)  │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ MainActivity + WebView (純黒 #000000 OST 起動)           │  │
│  │  ・初回: boot.html（展開進捗・ログ・実機診断・ミニCLI）   │  │
│  │  ・起動後: http://127.0.0.1:18765/?folder=/workspace     │  │
│  │  ・実 KeyEvent パススルー（Ctrl+Alt+D のみフック）       │  │
│  │  ・フォーカス枠 + 非フォーカス自動減光 / 別ポート外部起動 │  │
│  │  ・ファイル選択 / D&D / WindowInsets / レンダラー自動復旧 │  │
│  └──────────────────────────┬───────────────────────────────┘  │
│                             │ localhost HTTP/WS                │
│  ┌──────────────────────────▼───────────────────────────────┐  │
│  │ PosixRuntimeManager (Installer / Launcher / Shell / Store)│ │
│  │  ・jniLibs/arm64-v8a/libproot.so + libproot-loader.so    │  │
│  │  ・PROOT_LOADER により targetSdk=35 (W^X / 16KB) で実行  │  │
│  │  ・/workspace & /root を rootfs 外に永続分離マウント     │  │
│  │  ・ダミー /proc, /dev/shm, 動的 DNS & Android CA 同期    │  │
│  │  ・内蔵拡張 kucode-tools (ACP AI チャット + 管理メニュー) │  │
│  └──────────────────────────┬───────────────────────────────┘  │
│                             │                                  │
│  ┌──────────────────────────▼───────────────────────────────┐  │
│  │ Android ネイティブ連携（Android 標準 SDK クリーン実装）   │  │
│  │  ・KuCodeDocumentsProvider (Android Files → /workspace)  │  │
│  │  ・LocalDiagnosticsServer + DiagnosticsRunner (トークン認証)│ │
│  │  ・ServerKeepAliveService (specialUse / START_NOT_STICKY)│  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────┘
```

### Android 標準準拠の設計方針
- **`targetSdk = 35` を維持**：`proot` と `proot-loader` を `jniLibs/arm64-v8a/`（`useLegacyPackaging = true`、両方とも 16KB ページアライメント検証済み）に置き、`PROOT_LOADER` 環境変数で指定することで Android 15 W^X 下でゲスト ELF を実行。
- **標準 Debian minimal (`arm64` / `glibc` / `apt`)**：公式 `openvscode-server-linux-arm64` や `apt install gh git`、ACP 対応 CLI エージェントが無改造で動作。
- **スマホ用ネイティブ仮想端末を持たない**：物理キーボード＋マウス前提のため Web 側ターミナル（＋非 PTY フォールバックコンソール）に一本化。
- **Termux GPL ソース非流用**：`KuCodeDocumentsProvider`・インストーラー・サービスは Android 標準 SDK でゼロから Kotlin 実装。

---

## 3. ディレクトリ構成

設計判断の背景・代替案・トレードオフは [`docs/adr/`](docs/adr/README.md)（ADR-0001〜0011）を参照。

```text
.
├── spec.md
├── README.md
├── LICENSE
├── THIRD_PARTY_NOTICES.md
├── LICENSES/                             # 同梱 OSS ライセンス全文（ビルド時に APK へ自動同期）
├── docs/adr/                             # Architecture Decision Records (ADR-0001〜0011)
├── .github/workflows/ci.yml              # NDK proot + Debian rootfs + openvscode-server 同梱 APK ビルド & 検証
├── scripts/
│   ├── build-proot-android.sh            # Android NDK で 16KB 対応 libproot.so / libproot-loader.so をビルド
│   └── fetch-runtime-assets.sh           # Debian minimal rootfs (digest ピン留め) + openvscode-server + debian-manifest.txt
├── tests/                                # Node.js テスト（runtime-probes / kucode-tools 拡張 / ACP v1 プロトコル）
└── android/
    ├── gradlew / gradle/wrapper/         # Gradle 8.11.1 Wrapper
    ├── settings.gradle.kts               # rootProject.name = "KuCode"
    ├── build.gradle.kts
    ├── gradle.properties
    └── app/
        ├── build.gradle.kts              # applicationId = "dev.koduki.kucode"
        └── src/
            ├── main/
            │   ├── AndroidManifest.xml
            │   ├── assets/
            │   │   ├── boot.html
            │   │   ├── runtime-probes.cjs
            │   │   ├── kucode-tools/     # 内蔵拡張 (extension.js / acp-client.js / acp-webview.js / package.json / icon.svg)
            │   │   └── runtime/          # CI/スクリプトで .tgz & debian-manifest.txt を配置
            │   ├── jniLibs/arm64-v8a/    # CI/スクリプトで libproot*.so を配置
            │   └── java/dev/koduki/kucode/
            │       ├── KuCodeApplication.kt
            │       ├── MainActivity.kt
            │       ├── PosixRuntimeConfig.kt
            │       ├── PosixRuntimeManager.kt
            │       ├── RuntimeStatusStore.kt
            │       ├── RuntimeInstaller.kt
            │       ├── CaTrustSync.kt
            │       ├── OpenVsCodeLauncher.kt
            │       ├── PosixShellExecutor.kt
            │       ├── RuntimeFiles.kt
            │       ├── TarGzExtractor.kt
            │       ├── WorkspaceDocumentStore.kt
            │       ├── KuCodeDocumentsProvider.kt
            │       ├── VsCodeExtensionAsset.kt
            │       ├── LocalDiagnosticsServer.kt
            │       ├── DiagnosticsRunner.kt
            │       ├── SessionTelemetryManager.kt
            │       ├── SessionLogStore.kt
            │       ├── LogRedactor.kt
            │       ├── HardwareShortcutRouter.kt
            │       ├── BootHostBridge.kt
            │       └── ServerKeepAliveService.kt
            └── test/java/dev/koduki/kucode/  # 小規模・中規模テスト（MediumTest 分離）
```

---

## 4. 実機検証ゲート（XREAL AURA / Android XR）

1. **PRoot 起動ゲート**：`targetSdk = 35` で `libproot.so` + `libproot-loader.so` が Debian `bash` / `node` を起動できること。
2. **基礎エディタゲート**：`openvscode-server` が `http://127.0.0.1:<port>` で起動し、ファイルの開閉・編集・保存・物理キーボードショートカット（`Ctrl+S`, `Ctrl+W`, `Ctrl+C`/`Ctrl+X`/`Ctrl+V`, ターミナル `Ctrl+Shift+C`/`Ctrl+Shift+V`）・日本語 IME が機能すること。
3. **ファイル連携 & CLI ゲート**：
   - Android XR の Files アプリから「KuCode」の `/workspace` を開いてファイル読み書きできること。
   - `/workspace/android` から Android 側ファイルを開いて保存できること。
   - 内蔵ターミナル（またはフォールバック CLI）で `apt update && apt install -y ca-certificates gh` → `gh --version` が通ること。
4. **空間作業の往復**：日本語 IME の変換確定後に保存し、統合ターミナルで開発サーバーを起動する。隣のプレビューから戻り、カーソル位置と最初のキー入力を確認する。未保存の変更がある状態で管理画面を往復し、編集内容・選択位置・ターミナルセッションが保持されることを確認する。

---

## 5. 次期仕様：AURAセルフホスト（Accepted / 実機受け入れ未完了）

本節は2026-11-25確定の次期規範仕様。第1〜4節はv0.9.1時点の実装ベースラインであり、矛盾する場合は次期開発に本節を優先する。詳細な理由・非ゴールは [ADR-0012](docs/adr/0012-aura-self-host-standard-linux.md)、進捗は [Epic #17](https://github.com/koduki/kucode/issues/17) を参照。

### 5.1 スコープと責務

- 汎用の空間Linux IDE。Android開発は一つの用途、KuCode自身を最初のE2E対象とする。対象はXREAL AURA/ARM64、2026年11月の最新安定版互換セットを検証・固定。旧版・x86は対象外。
- 単一APKで基礎環境を起動。SDKはオンデマンド、既存CLIを使用。Linux内で編集/ビルド/テスト/署名、Android標準APIで配置/起動/診断。PC/外部ビルド/ADB不要。依存資産の事前ビルド・ネット取得は許容。
- KuCode Core: XR UI・WebView・永続Linux起動・ホスト連携・診断。kucode-tools: 汎用操作・ACP・診断UI。kucode-android: Androidプロジェクトのみで活性化する独立拡張。別package/テスト境界、同一repo/CI/APK。SDK・Gradle処理を再実装しない。
- CLI First。GUI/AIは既存CLIと最小Host Bridge CLIを共用。APIはバージョン付き、結果/エラー/非同期承認状態を機械可読にする。独自MCPは必須にしない。

### 5.2 ストレージ・ランタイム

| 領域 | 所有者 | 更新時の要件 |
| --- | --- | --- |
| Debian rootfs / apt | ユーザー | 通常APK更新で保持 |
| home / workspace / SDK / cache | ユーザー | Linux正本、保持 |
| OpenVSCode / 内蔵拡張 / PRoot | KuCode | 独立して検証・更新・復旧 |
| DNS / CA / ホスト同期設定 | KuCode | 管理範囲のみ同期 |
| Android共有領域 | Android / ユーザー | 明示共有、ビルド正本にしない |

標準LinuxのHOME/一般ユーザー概念に寄せる。PRoot擬似権限はセキュリティ境界ではない。既存データの一度限りの移行、明示初期化、失敗時の復旧を検証する。将来標準Linux VMを利用可能になった場合は実装を直接変更し、先回りのバックエンド抽象化は行わない。

### 5.3 ホスト連携・安全性

PackageInstaller.SessionとIntentを利用し、APK対象/署名/パス検証、ステージング、Android標準承認、結果通知を実装する。バックグラウンド起動やpackage visibilityの制約に従う。標準権限を迂回しない。Stable/Devは独立UID・データ・SDK・認証情報・ポート・Authority・署名。Stable誤更新を防止。Dev診断は明示的なOS標準共有で取得し機密除去する。任意アプリのログ/JDWP等は保証しない。既存のトークン認証は同一UID内の隔離ではない。

### 5.4 ビルド・配布・観測

GitHub Releasesの固定資産（PRoot/loader、Debian seed、OpenVSCode、ライセンス）を検証・キャッシュしてCIと端末で同じGradleパッケージング経路を使う。資産生成は別責務。ストア対応は非ゴール。SDKの具体バージョンは実機互換性確認後に固定。Flight RecorderにビルドID/時間/終了コード、CPU/PSS/thermal/ストレージ/終了理由を取得可能な範囲で追加する。未取得はunknown、独自スケジューラなし。

### 5.5 受け入れ条件（全必須項目をAURA実機で検証）

- [ ] 安定版単一APKの初回起動、ARM64 SDKオンデマンド導入。
- [ ] Git clone、編集、ACPによる変更、JVM/Nodeテスト、端末内APK生成・署名。
- [ ] ADBなしでDev APKをインストール・起動し、Stable/Dev独立環境を確認。
- [ ] Devの障害を意図的に再現し、機密除去済み診断をStableのAIで解析。
- [ ] 通常APK更新後もapt追加パッケージ、home、workspace、SDK/cacheを保持。
- [ ] 管理資産更新失敗・容量不足・強制終了時のデータ保持と復旧。
- [ ] Java/Kotlin、JS/TS、Python、Go、Ruby、Rust、C/C++の基本スモーク結果を記録（個別の完全IDE対応は保証しない）。
- [ ] ビルド・実行時のリソース観測、機種/OS/コミット/SDK/資産ハッシュ/手順/結果の証跡を保存。

CIは実機試験の代替としない。未実施はNOT RUN。x86、旧SDK、独自SDK管理・ビルド・デバッガー、全依存資産の自己コンパイル、ストア対応、他端末互換性レイヤーは対象外。

### 5.6 実装Issue

| ID | Issue | 依存 |
| --- | --- | --- |
| SH-01 | [#18 ARM64成立性](https://github.com/koduki/kucode/issues/18) | なし（KuCode実ビルド検証はSH-03資産利用） |
| SH-02 | [#19 永続Debian](https://github.com/koduki/kucode/issues/19) | なし |
| SH-03 | [#20 固定資産・共通ビルド](https://github.com/koduki/kucode/issues/20) | なし |
| SH-04 | [#21 SDK/PATH](https://github.com/koduki/kucode/issues/21) | SH-01, SH-02 |
| SH-05 | [#22 Host Bridge](https://github.com/koduki/kucode/issues/22) | なし |
| SH-06 | [#23 Android拡張](https://github.com/koduki/kucode/issues/23) | SH-04, SH-05 |
| SH-07 | [#24 Stable/Dev](https://github.com/koduki/kucode/issues/24) | SH-05 |
| SH-08 | [#25 観測性](https://github.com/koduki/kucode/issues/25) | SH-02, SH-07 |
| SH-09 | [#26 汎用言語スモーク](https://github.com/koduki/kucode/issues/26) | SH-04 |
| SH-10 | [#27 E2E](https://github.com/koduki/kucode/issues/27) | SH-01〜09 |
