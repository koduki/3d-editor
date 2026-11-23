# ADR-0003: Debian 12 minimal + `openvscode-server` を `.tgz`（`noCompress`）で同梱する

- **ステータス:** Accepted
- **日付:** 2026-11-22

## 背景（Context）

単体完結 APK に同梱する Linux ユーザー空間と Web 版 VS Code サーバーの選定において、以下の要件を満たす必要があった：

1. 公式の `linux-arm64` エディタバイナリが無改造で動作すること。
2. 開発者が端末内のターミナルから `git`, `gh`, `curl`, `openssh-client` 等の標準ツールを普通にインストールできること。
3. APK サイズを実用的な範囲（〜110 MiB 程度）に抑えること。

また開発途中、`assets/runtime/` に `.tar.gz` を置いたところ Android Gradle Plugin（AGP）のレガシー挙動により勝手に gunzip されて拡張子が削られ、APK が 334 MB に肥大化かつ実行時 `FileNotFoundException` となる問題に遭遇した。

## 決定事項（Decision）

- **ゲスト Linux**：公式 Docker イメージ `debian:bookworm-slim`（`linux/arm64` / `glibc` / `apt`、圧縮後 約 28.3 MB）を `docker export | gzip -6` で書き出して同梱する。
- **エディタサーバー**：Gitpod 公式 `openvscode-server-v1.109.5`（`linux-arm64`、約 74.5 MB）を同梱する。
- **アセット形式と展開**：
  - アーカイブ拡張子は `.tar.gz` ではなく **`.tgz`**（`debian-arm64-rootfs.tgz` / `openvscode-server-linux-arm64.tgz`）とし、`androidResources.noCompress += listOf("tgz", "tar", "xz")` を設定する。
  - 展開には `TarGzExtractor`（Apache Commons Compress + `java.nio.file.Files`）を用い、POSIX シンボリックリンク・ハードリンク・実行権限ビットを保持しつつパストラバーサルを防御し、`staging/` → `rootfs/` へのアトミック切替で途中中断による破損を防ぐ。

## 検討した代替案（Alternatives Considered）

- **代替案 A：Alpine Linux（`musl` / `apk`）**
  - サイズはさらに小さいが、公式 `openvscode-server` や多くの配布済みバイナリが `glibc` 依存であり、互換レイヤーや独自ビルドの保守負荷が高いため不採用。
- **代替案 B：`coder/code-server`**
  - アーカイブ単体で約 266.5 MB と `openvscode-server`（74.5 MB）の約 3.5 倍のサイズになり APK 肥大化が大きいため不採用。
- **代替案 C：初回起動時にネットワークからダウンロードする**
  - APK は軽量化できるが、オフライン環境や初回待ち時間の不安定さが増すため、v0.5.0 では「APK 1 本で完結」を優先して同梱方式を採った（将来の軽量化オプションとしては再検討可能）。

## 影響とトレードオフ（Consequences）

- **メリット**：APK 全体が約 109 MiB に収まり、公式 `openvscode-server` と Debian の `apt` エコシステムが無改造で動作する。`java.nio.file.Files` 採用により、JVM ユニットテストと Android API 26+ 実機で同一の展開コードがテスト可能になった。
- **トレードオフ**：Debian パッケージを APK に再配布する形になるため、GPL/LGPL の対応ソース管理（マニフェスト記録等）が必要になる（ADR-0008 で対応済み）。

## 該当実装

- [scripts/fetch-runtime-assets.sh](../../scripts/fetch-runtime-assets.sh)
- [android/app/build.gradle.kts](../../android/app/build.gradle.kts)
- [TarGzExtractor.kt](../../android/app/src/main/java/dev/koduki/xreditor/TarGzExtractor.kt)
- [TarGzExtractorTest.kt](../../android/app/src/test/java/dev/koduki/xreditor/TarGzExtractorTest.kt)
