# KuCode 仕様書 (spec.md)

- **Product:** KuCode（くうこーど / *"koo-code"*）— *空間に、書く。*
- **Version:** 0.6.0
- **Date:** 2026-11-23
- **Status:** Active

---

## 1. プロジェクト概要

### 1.1 目的
Android XR（XREAL AURA 等）の空間マルチウィンドウ環境（物理キーボード＋マウス／トラックパッド前提）において、**APK 1 本をインストールするだけで別途サーバー設定や Termux 導入なしに使える、空間コンピューティングのためのコードエディタ＋内蔵 POSIX 環境** を提供する。

### 1.2 コアスコープ（4 要件）
1. **`openvscode-server` 完全内蔵（別途インストール・手動実行不要）**
   - Debian minimal（`arm64` / `glibc` / `apt`）rootfs と公式 `openvscode-server`（`linux-arm64`）を APK に内蔵し、アプリ起動時に自動展開・自動起動する。
2. **基礎的なエディタ UI と空間視認性**
   - 「ファイルを開く / 閉じる / 編集する / 保存する」の基礎操作に集中し、スマホ向け仮想キーや過剰な独自 UI は持たない。
   - `data/Machine/settings.json` に純黒 OST テーマ（`#000000`）の初期値を適用し、Welcome・ミニマップ・パンくず・テレメトリ・ワークスペース信頼ダイアログ等のノイズを抑える（ユーザー側の `user/User/settings.json` や JSONC 設定は上書きしない）。
3. **Android XR ↔ POSIX 双方向ファイルアクセス**
   - **POSIX → Android 領域（マウント）**：アプリ共有領域を `/android/shared` に常時マウントし、ユーザーが全ファイルアクセス（`MANAGE_EXTERNAL_STORAGE`）を許可した場合は `/storage/emulated/0` を `/android/sdcard` としてマウント（`/workspace/android` から直接参照可能）。
   - **Android → POSIX 領域（通常フォルダ公開）**：Android 標準 `DocumentsProvider`（`KuCodeDocumentsProvider`）を実装し、Android XR の標準「Files」アプリや他アプリから POSIX の `/workspace` を通常フォルダとして読み書きできる（`NOFOLLOW_LINKS` 境界制御付き）。
4. **内蔵ターミナル & パッケージ管理（`apt` / `git` / `gh` / CLI エージェント）**
   - 人間や CLI 系 AI エージェントが同じ `/workspace` 上で開発できるよう、Debian POSIX 世界を内蔵する。
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
│  └──────────────────────────┬───────────────────────────────┘  │
│                             │ localhost HTTP/WS                │
│  ┌──────────────────────────▼───────────────────────────────┐  │
│  │ PosixRuntimeManager (Installer / Launcher / Shell / Store)│ │
│  │  ・jniLibs/arm64-v8a/libproot.so + libproot-loader.so    │  │
│  │  ・PROOT_LOADER により targetSdk=35 (W^X) のまま実行     │  │
│  │  ・/workspace & /root を rootfs 外に永続分離マウント     │  │
│  │  ・ダミー /proc, /dev/shm, 動的 DNS & Android CA 同期    │  │
│  │  ・内蔵拡張 kucode-tools (左 Activity Bar メニュー)      │  │
│  └──────────────────────────┬───────────────────────────────┘  │
│                             │                                  │
│  ┌──────────────────────────▼───────────────────────────────┐  │
│  │ Android ネイティブ連携（Android 標準 SDK クリーン実装）   │  │
│  │  ・KuCodeDocumentsProvider (Android Files → /workspace)  │  │
│  │  ・LocalDiagnosticsServer + DiagnosticsRunner (トークン認証)│ │
│  │  ・ServerKeepAliveService (START_NOT_STICKY / onTimeout) │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────┘
```

### Android 標準準拠の設計方針
- **`targetSdk = 35` を維持**：`proot` と `proot-loader` を `jniLibs/arm64-v8a/`（`useLegacyPackaging = true`、16KB ページアライメント）に置き、`PROOT_LOADER` 環境変数で指定することで Android 15 W^X 下でゲスト ELF を実行。
- **標準 Debian minimal (`arm64` / `glibc` / `apt`)**：公式 `openvscode-server-linux-arm64` や `apt install gh git` が無改造で動作。
- **スマホ用ネイティブ仮想端末を持たない**：物理キーボード＋マウス前提のため Web 側ターミナル（＋非 PTY フォールバックコンソール）に一本化。
- **Termux GPL ソース非流用**：`KuCodeDocumentsProvider`・インストーラー・サービスは Android 標準 SDK でゼロから Kotlin 実装。

---

## 3. ディレクトリ構成

設計判断の背景・代替案・トレードオフは [`docs/adr/`](docs/adr/README.md)（ADR-0001〜0009）を参照。

```text
.
├── spec.md
├── README.md
├── LICENSE
├── THIRD_PARTY_NOTICES.md
├── LICENSES/                             # 同梱 OSS ライセンス全文（ビルド時に APK へ自動同期）
├── docs/adr/                             # Architecture Decision Records (ADR-0001〜0009)
├── .github/workflows/ci.yml              # NDK proot + Debian rootfs + openvscode-server 同梱 APK ビルド
├── scripts/
│   ├── build-proot-android.sh            # Android NDK で libproot.so / libproot-loader.so をビルド（SHA-256 検証付き）
│   └── fetch-runtime-assets.sh           # Debian minimal rootfs (digest ピン留め) + openvscode-server + debian-manifest.txt
├── tests/                                # Node.js テスト（runtime-probes / kucode-tools 拡張）
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
            │   │   ├── kucode-tools/     # 内蔵拡張 (extension.js / package.json / icon.svg)
            │   │   └── runtime/          # CI/スクリプトで .tgz & debian-manifest.txt を配置
            │   ├── jniLibs/arm64-v8a/    # CI/スクリプトで libproot*.so を配置
            │   └── java/dev/koduki/kucode/
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
