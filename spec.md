# XR-Editor 仕様書 (spec.md)

- **Version:** 0.5.0
- **Date:** 2026-11-22
- **Status:** Active (Minimal Reboot)

---

## 1. プロジェクト概要

### 1.1 目的
Android XR（XREAL AURA 等）の空間マルチウィンドウ環境（物理キーボード＋マウス／トラックパッド前提）において、**APK 1 本をインストールするだけで別途サーバー設定や Termux 導入なしに使える、最小構成の空間コードエディタ＋内蔵 POSIX ターミナル** を提供する。

### 1.2 今回のスコープ（意図的に絞った 4 要件）
1. **`openvscode-server` 完全内蔵（別途インストール・手動実行不要）**
   - Debian minimal（`arm64` / `glibc` / `apt`）rootfs と公式 `openvscode-server`（`linux-arm64`）を APK に内蔵し、アプリ起動時に自動展開・自動起動する。
2. **基礎的なエディタ UI**
   - まずは「ファイルを開く / 閉じる / 編集する / 保存する」の基礎操作に集中。
   - 初期 `settings.json` で純黒 OST テーマ（`#000000`）を適用し、Welcome・ミニマップ・パンくず・テレメトリ・ワークスペース信頼ダイアログ等のノイズをすべて無効化する。
3. **Android XR ↔ POSIX 双方向ファイルアクセス**
   - **POSIX → Android 領域（マウント）**：アプリ共有領域を `/android/shared` に常時マウントし、ユーザーが全ファイルアクセス（`MANAGE_EXTERNAL_STORAGE`）を許可した場合は `/storage/emulated/0` を `/android/sdcard` としてマウント（`/workspace/android` から直接参照可能）。
   - **Android → POSIX 領域（通常フォルダ公開）**：Android 標準 `DocumentsProvider`（`XrDocumentsProvider`）を実装し、Android XR の標準「Files」アプリや他アプリから POSIX の `/workspace` を普通の特定ディレクトリとして読み書きできる。
4. **最低限の内蔵ターミナル & パッケージ管理（`gh` 等）**
   - 人間や将来の AI による単純な CLI 操作・`apt` パッケージ管理（`gh`, `git`, `curl` 等）を POSIX 世界で完結させる。
   - `openvscode-server` 下部の統合ターミナルを標準としつつ、実機で `node-pty` が動作しない場合でも `boot.html` のフォールバック CLI コンソールから PRoot 経由で `bash -lc "<cmd>"` を直接実行できる。

### 1.3 今回対象外（v0.4.0 から削除したもの）
- 自作 ACP AI チャット UI・Diff 承認 UI・音声入力・デモモード・接続プロファイル管理・セッション履歴 DB・Markdown プレビュー・外部 Node.js ブリッジサーバー必須構成。

---

## 2. アーキテクチャ（Android XR 向けクリーン設計）

```text
┌────────────────────────────────────────────────────────────────┐
│ XR-Editor APK (arm64-v8a / compileSdk=36 / targetSdk=35)       │
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
│  │ PosixRuntimeManager (PRoot + Debian minimal + openvscode)│  │
│  │  ・jniLibs/arm64-v8a/libproot.so + libproot-loader.so    │  │
│  │  ・PROOT_LOADER により targetSdk=35 (W^X) のまま実行     │  │
│  │  ・/workspace & /root を rootfs 外に永続分離マウント     │  │
│  │  ・ダミー /proc, /dev/shm, 動的 /etc/resolv.conf 生成    │  │
│  │  ・内蔵拡張 xr-editor-tools (左 Activity Bar メニュー)   │  │
│  └──────────────────────────┬───────────────────────────────┘  │
│                             │                                  │
│  ┌──────────────────────────▼───────────────────────────────┐  │
│  │ Android ネイティブ連携（Android 標準 SDK クリーン実装）   │  │
│  │  ・XrDocumentsProvider (Android Files → POSIX /workspace)│  │
│  │  ・LocalDiagnosticsServer + DiagnosticsRunner (トークン認証)│ │
│  │  ・ServerKeepAliveService (Foreground DATA_SYNC + onTimeout)││
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────┘
```

### Termux 方式からの主な代替ポイント
- **`targetSdk = 35` を維持**（`targetSdk = 28` へ下げない）：`proot` と `proot-loader` を `jniLibs/arm64-v8a/`（`useLegacyPackaging = true`、16KB ページアライメント）に置き、`PROOT_LOADER` 環境変数で指定することで Android 15 W^X 下でゲスト ELF を実行。
- **独自 `$PREFIX` / Bionic 移植を行わない**：標準 Debian minimal (`arm64` / `glibc` / `apt`) を使うことで、公式 `openvscode-server-linux-arm64` と `apt install gh git` が無改造で動作。
- **スマホ用ネイティブ仮想端末を持たない**：物理キーボード＋マウス前提のため Web 側ターミナル（＋非 PTY フォールバックコンソール）に一本化。
- **Termux の GPL-3.0 ソースを流用しない**：`XrDocumentsProvider`・インストーラー・サービスは Android 標準 SDK でゼロから Kotlin 実装。

---

## 3. ディレクトリ構成

設計判断の背景・代替案・トレードオフは [`docs/adr/`](docs/adr/README.md)（ADR-0001〜0008）を参照。

```text
.
├── spec.md
├── README.md
├── LICENSE
├── THIRD_PARTY_NOTICES.md
├── LICENSES/                             # 同梱 OSS ライセンス全文（ビルド時に APK へ自動同期）
├── docs/adr/                             # Architecture Decision Records (ADR-0001〜0008)
├── .github/workflows/ci.yml              # NDK proot + Debian rootfs + openvscode-server 同梱 APK ビルド
├── scripts/
│   ├── build-proot-android.sh            # Android NDK で libproot.so / libproot-loader.so をビルド
│   └── fetch-runtime-assets.sh           # Debian minimal rootfs + openvscode-server + debian-manifest.txt を生成
└── android/
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradle.properties
    └── app/
        ├── build.gradle.kts
        └── src/
            ├── main/
            │   ├── AndroidManifest.xml
            │   ├── assets/boot.html
            │   ├── assets/runtime/       # CI/スクリプトで .tgz & debian-manifest.txt を配置
            │   ├── jniLibs/arm64-v8a/    # CI/スクリプトで libproot*.so を配置
            │   └── java/dev/koduki/xreditor/
            │       ├── MainActivity.kt
            │       ├── PosixRuntimeConfig.kt
            │       ├── PosixRuntimeManager.kt
            │       ├── TarGzExtractor.kt
            │       ├── XrDocumentsProvider.kt
            │       ├── VsCodeExtensionAsset.kt
            │       ├── LocalDiagnosticsServer.kt
            │       ├── DiagnosticsRunner.kt
            │       ├── HardwareShortcutRouter.kt
            │       ├── BootHostBridge.kt
            │       └── ServerKeepAliveService.kt
            └── test/java/dev/koduki/xreditor/
                ├── DiagnosticsRunnerTest.kt
                ├── HardwareShortcutRouterTest.kt
                ├── PosixRuntimeConfigTest.kt
                ├── TarGzExtractorTest.kt
                └── VsCodeExtensionAssetTest.kt
```

---

## 4. 実機検証ゲート（XREAL AURA / Android XR）

1. **PRoot 起動ゲート**：`targetSdk = 35` で `libproot.so` + `libproot-loader.so` が Debian `bash` / `node` を起動できること。
2. **基礎エディタゲート**：`openvscode-server` が `http://127.0.0.1:<port>` で起動し、ファイルの開閉・編集・保存・物理キーボードショートカット（`Ctrl+S`, `Ctrl+W`）・日本語 IME が機能すること。
3. **ファイル連携 & CLI ゲート**：
   - Android XR の Files アプリから `/workspace` を開いてファイル読み書きできること。
   - `/workspace/android` から Android 側ファイルを開いて保存できること。
   - 内蔵ターミナル（またはフォールバック CLI）で `apt update && apt install -y ca-certificates gh` → `gh --version` が通ること。
