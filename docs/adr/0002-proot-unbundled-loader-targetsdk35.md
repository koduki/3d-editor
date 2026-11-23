# ADR-0002: `targetSdk = 35`（W^X）維持のため分離ローダー型 PRoot を `jniLibs` 同梱する

- **ステータス:** Accepted
- **日付:** 2026-11-22

## 背景（Context）

Android 10（API 29）以降および `targetSdk = 35`（Android 15）では、SELinux の W^X（Write XOR Execute）ポリシーにより、アプリのデータディレクトリ（`filesDir`）配下に展開したバイナリに対する直接 `execve()` が禁止されている。

Termux はこれを回避するため `targetSdk = 28` に留める戦略を採っているが、Android XR や最新 Android OS・配布ストア要件を踏まえると `targetSdk = 35`（`compileSdk = 36`, `minSdk = 34`）を維持しつつ、かつ 16KB ページサイズ端末にも対応したクリーンな実行方式が必要だった。

## 決定事項（Decision）

- `targetSdk = 35` を維持したまま、`termux/proot`（`v5.1.107.102`）と静的リンクした Samba `talloc`（`2.5.0`）を Android NDK r27+ で `arm64-v8a` 向けにクロスコンパイルする。
- PRoot を **`PROOT_UNBUNDLE_LOADER` モード** でビルドし、本体とローダーの 2 バイナリを `libproot.so` / `libproot-loader.so` として `android/app/src/main/jniLibs/arm64-v8a/` に配置する。
  - `packaging.jniLibs.useLegacyPackaging = true` により、インストール時に実行権限付きで `nativeLibraryDir` へ展開させ、`PROOT_LOADER` 環境変数でローダーパスを明示してゲスト ELF を実行する。
  - リンカフラグ `-Wl,-z,max-page-size=16384` を指定し、16KB ページサイズ端末に対応する。
- Android 固有の環境差を吸収するため、以下を組み合わせる：
  - SELinux で読めない `/proc/{stat,loadavg,uptime,version,vmstat}` へのダミーファイルバインドマウント。
  - Android `ConnectivityManager` から取得したシステム DNS の `/etc/resolv.conf` 動的同期（取得不可時は `1.1.1.1` / `8.8.8.8` へフォールバック）。
  - 起動直後エラー時に `PROOT_NO_SECCOMP=1` を付与して自動リトライするフェイルセーフ。
  - Android 15 の `FOREGROUND_SERVICE_TYPE_DATA_SYNC` 6 時間上限クラッシュを防ぐため、`ServerKeepAliveService.onTimeout()` を実装し、フォアグラウンド復帰時に再登録する。

## 検討した代替案（Alternatives Considered）

- **代替案 A：`targetSdk = 28` へダウングレードする（Termux 方式）**
  - Android 14/15/XR でのインストール制限や将来的な互換性リスクが高いため不採用。
- **代替案 B：Android Virtualization Framework (AVF) / Linux Terminal VM を利用する**
  - 端末・ベンダーごとのサポート差が大きく、XREAL AURA 等の Android XR 実機で確実に動く保証がないため、まずはユーザー空間完結の PRoot を採用。
- **代替案 C：Termux アプリのビルド済み `.deb` や GPL-3.0 Android ソースを流用する**
  - ライセンス境界を明確にし保守性を高めるため、Android 側 Kotlin コードは標準 SDK でゼロから実装し、PRoot のみ再現可能なスクリプトでソースからビルドする方式とした。

## 影響とトレードオフ（Consequences）

- **メリット**：`targetSdk = 35` と 16KB ページ対応を維持したまま、root 権限不要で標準 Linux ELF（`bash`, `node`, `apt`, `openvscode-server`）を実行できた（Android XR 実機で起動確認済み）。
- **トレードオフ**：PRoot の `ptrace` オーバーヘッドにより I/O・プロセス起動はネイティブより遅い。また NDK r27+ の Clang で `ashmem_memfd.c` の暗黙宣言エラーが出るため、`scripts/build-proot-android.sh` で `#include <string.h>` の 1 行パッチを当てている（ADR-0008 で GPL §5(a) として明示）。

## 該当実装

- [scripts/build-proot-android.sh](../../scripts/build-proot-android.sh)
- [android/app/build.gradle.kts](../../android/app/build.gradle.kts)
- [PosixRuntimeConfig.kt](../../android/app/src/main/java/dev/koduki/kucode/PosixRuntimeConfig.kt)
- [PosixRuntimeManager.kt](../../android/app/src/main/java/dev/koduki/kucode/PosixRuntimeManager.kt)
- [ServerKeepAliveService.kt](../../android/app/src/main/java/dev/koduki/kucode/ServerKeepAliveService.kt)
