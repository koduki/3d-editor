# XR-Editor

Android XR（XREAL AURA 等）の空間デスクトップ環境（物理キーボード＋マウス／トラックパッド前提）向けに、**`openvscode-server` と最小 Debian POSIX 環境を APK 1 本に内蔵**したシンプルコードエディタです。

外部サーバーのインストール・起動や Termux の別途導入は不要です。

## 主な特徴

- **単体完結 APK**：`PRoot` + `Debian minimal (arm64 / apt)` + `openvscode-server (linux-arm64)` を内蔵し、アプリ起動だけでローカル自動起動
- **最小構成の空間エディタ UI**：純黒 OST（`#000000`）初期テーマで、まず「ファイルを開く / 閉じる / 編集する / 保存する」に集中
- **Android XR ↔ POSIX 双方向ファイルアクセス**：
  - **Android → POSIX**：Android 標準「Files」アプリから本アプリの `/workspace` を通常のフォルダとして読み書き可能（`XrDocumentsProvider`）
  - **POSIX → Android**：`/android/shared`（権限不要）および全ファイルアクセス許可時の `/android/sdcard`（`/storage/emulated/0`）を `/workspace/android` から直接参照
- **最低限の内蔵ターミナル**：POSIX 環境で `apt install -y ca-certificates gh git` 等のパッケージ管理や CLI 操作が可能（統合ターミナル＋非 PTY フォールバックコンソール搭載）

## インストール（CI/CD ビルド済み APK）

GitHub Actions の Artifacts（`xr-editor-debug-apk`）または [Releases](https://github.com/koduki/3d-editor/releases) から APK を取得してインストールします：

```bash
adb install -r xr-editor-debug.apk
```

## ローカルビルド手順

前提：JDK 17、Android SDK（API 36 + NDK r27+）、Docker（Debian arm64 rootfs 生成用）、`curl`、`make`

```bash
# 1. Android arm64-v8a 向け PRoot (libproot.so / libproot-loader.so) をビルド
./scripts/build-proot-android.sh

# 2. Debian minimal rootfs + openvscode-server (linux-arm64) を取得して assets/runtime へ配置
./scripts/fetch-runtime-assets.sh

# 3. ユニットテスト実行 & Debug APK ビルド
cd android
gradle :app:testDebugUnitTest :app:assembleDebug
```

詳細は [spec.md](spec.md) および [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) を参照してください。
