# ADR-0008: 本体 MIT 化とライセンス全文・Debian パッケージマニフェストの APK 自動同梱

- **ステータス:** Accepted
- **日付:** 2026-11-23

## 背景（Context）

単体完結 APK として公開配布（GitHub Releases / CI Artifact 等）するにあたり、同梱する全コンポーネントのライセンス整合性と配布義務を棚卸しした結果、以下が確認された：

1. **ライセンス互換性（競合なし）**：
   - `termux/proot` のソースヘッダは **`GPL-2.0-or-later`** であり、静的リンクする Samba `talloc`（**`LGPL-3.0-or-later`**）と互換（結合後の `libproot.so` 全体が **`GPL-3.0-or-later`** となる）。
   - Android アプリ本体（Kotlin）は Termux の GPL-3.0 ソースを流用せずゼロから実装しており、PRoot / Debian / `openvscode-server` とは `ProcessBuilder` によるプロセス境界で分離された集合体（Aggregate）である。
2. **配布義務上の不足点**：
   - リポジトリ自身の `LICENSE` が未配置だった。
   - `THIRD_PARTY_NOTICES.md` に `ashmem_memfd.c` への 1 行パッチ（GPL §5(a) 改変明示）や結合ライセンス `GPL-3.0-or-later`、AndroidX/KotlinX 依存、商標免責が不足していた。
   - ライセンス本文一式と、浮動タグ `debian:bookworm-slim` に実際に入った全パッケージの正確な対応ソース一覧（`debian-manifest.txt`）が APK 内に同梱されていなかった。

## 決定事項（Decision）

1. **KuCode 本体のライセンス**：ルートに [LICENSE](../../LICENSE)（**MIT License**）を配置し、同梱サードパーティは各自のライセンスを維持する構造を明示する。
2. **ライセンス全文の一元管理と APK 自動同梱**：
   - 公式ライセンス全文（`Apache-2.0.txt`, `GPL-2.0.txt`, `GPL-3.0.txt`, `LGPL-2.1.txt`, `LGPL-3.0.txt`, `MIT-OpenVSCode.txt`）を `LICENSES/` に配置する。
   - `scripts/fetch-runtime-assets.sh` で `debian-arm64-rootfs.tgz` 内の `var/lib/dpkg/status` を直接解析（x86_64 CI でもエミュレーション不要）し、イメージダイジェスト・SHA-256・全バイナリ/ソースパッケージ対応表を `debian-manifest.txt` として自動生成する。
   - `android/app/build.gradle.kts` の `syncLicenseAssets` タスクにより、`LICENSE`・`THIRD_PARTY_NOTICES.md`・`LICENSES/*.txt`・`debian-manifest.txt` をビルド時に APK 内 `assets/licenses/` へ自動同梱する（Git 内の重複配置を避ける）。
3. **CI 検証・Release 添付・アプリ内閲覧**：
   - `.github/workflows/ci.yml` で APK 内の `assets/licenses/*` 同梱を毎回 `unzip -l` で検証し、`v*` タグの GitHub Release 時にも `debian-manifest.txt` と `THIRD_PARTY_NOTICES.md` を添付する。
   - `BootHostBridge.readLicenseAsset`（許可ファイル名ホワイトリスト制）と `boot.html` のライセンスビューアにより、アプリ内から全ライセンス本文と Debian パッケージ一覧を直接閲覧できるようにする。
   - `THIRD_PARTY_NOTICES.md` に 1 行パッチの改変明示、対応ソース取得手順、3 年間の書面提供オファー、商標免責を明記する。

## 検討した代替案（Alternatives Considered）

- **代替案 A：リポジトリのルートにだけ `THIRD_PARTY_NOTICES.md` を置き、APK 内には入れない**
  - バイナリ単体（APK）を受け取ったユーザーにライセンス本文・著作権表示が渡らず、MIT / Apache-2.0 / GPL のバイナリ配布要件を満たさないため不採用。
- **代替案 B：Debian / OpenVSCode を APK 同梱せず初回オンラインダウンロード方式へ切り替える**
  - Debian の再配布義務自体は消滅するが、KuCode の「APK 1 本で完結」という中核コンセプト（ADR-0001 / ADR-0003）を優先し、今回はマニフェスト自動生成＋同梱＋書面提供オファーで適法性を担保した。

## 影響とトレードオフ（Consequences）

- **メリット**：バイナリ配布（GitHub Releases 等）における OSS コンプライアンス上の抜け漏れを自動化で防止できる。ライセンスファイルが増えてもリポジトリ内で二重管理にならない。
- **トレードオフ**：`v*` リリース公開後 3 年間は、要求があれば対応ソース一式を提供できる状態を維持する必要がある（Debian は `snapshot.debian.org` + `debian-manifest.txt`、PRoot/talloc は固定タグ URL + `build-proot-android.sh` で再現可能）。

## 該当実装

- [LICENSE](../../LICENSE)
- [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)
- [LICENSES/](../../LICENSES/)
- [scripts/fetch-runtime-assets.sh](../../scripts/fetch-runtime-assets.sh)
- [android/app/build.gradle.kts](../../android/app/build.gradle.kts)
- [BootHostBridge.kt](../../android/app/src/main/java/dev/koduki/kucode/BootHostBridge.kt)
- [android/app/src/main/assets/boot.html](../../android/app/src/main/assets/boot.html)
- [.github/workflows/ci.yml](../../.github/workflows/ci.yml)
