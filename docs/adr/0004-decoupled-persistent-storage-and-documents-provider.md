# ADR-0004: `/workspace`・`/root` の永続分離と `XrDocumentsProvider` による双方向ファイル共有

- **ステータス:** Accepted
- **日付:** 2026-11-22

## 背景（Context）

初期設計では `/workspace` や `/root` が `filesDir/posix/rootfs/` の内側に存在していた。しかしこれでは将来 APK アップデートで Debian rootfs や `openvscode-server` のバージョンを更新し `rootfs/` を再展開した際、ユーザーのソースコード・SSH 鍵（`~/.ssh`）・`.gitconfig`・`gh` 認証情報（`~/.config/gh`）・シェル履歴が丸ごと消えるリスクがあった。

また Android XR 上では、エディタ内の POSIX 世界と Android 標準の「Files」アプリや他アプリの間でストレスなくファイルを受け渡せる必要があった。

## 決定事項（Decision）

1. **ユーザーデータの `rootfs/` 外への完全分離**
   - 作業領域：`filesDir/workspace` をゲスト `/workspace` にバインドマウント。
   - ホーム領域：`filesDir/home` をゲスト `/root` にバインドマウント（初回に `.bashrc` を自動生成）。
   - 既存端末向けに、旧 `rootfs/workspace` や `rootfs/root` にファイルがある場合は起動時に自動で分離先へマイグレーションする。
2. **Android → POSIX（`XrDocumentsProvider`）**
   - Android 標準 SDK の `DocumentsProvider` をゼロから Kotlin 実装し、`filesDir/workspace` を「Files」アプリや他アプリから通常フォルダとして読み書き・作成・リネーム・再帰削除・検索可能にする。
   - `/workspace/android` のようなシンボリックリンク経由で `/android` や `/` へ脱出されないよう、`canonicalPath` ジェイル検証でルート外パスを必ず拒否する。
3. **POSIX → Android（バインドマウント）**
   - 権限不要なアプリ共有領域（`getExternalFilesDir("shared")`）を `/android/shared` に常時マウント。
   - ユーザーが `MANAGE_EXTERNAL_STORAGE` を許可した場合のみ `/storage/emulated/0` を `/android/sdcard` にマウント。
   - `/workspace/android` 配下にシンボリックリンクを張り、VS Code のエクスプローラーからそのまま開けるようにする。

## 検討した代替案（Alternatives Considered）

- **代替案 A：`/workspace` を `rootfs/` 内に置き、更新時だけ退避・復元する**
  - アップデート途中のクラッシュでユーザーデータを失うリスクが残るため不採用。最初からマウント境界で分離する方が安全かつ単純。
- **代替案 B：SAF（Storage Access Framework）のツリー URI のみで作業する**
  - POSIX ツール（`git`, `npm`, `apt` 等）はネイティブな POSIX ファイルシステムを前提とするため、アプリ固有ストレージ上の実ディレクトリを正とし、`DocumentsProvider` で外へ公開する構成を採用。

## 影響とトレードオフ（Consequences）

- **メリット**：ランタイム（Debian / `openvscode-server`）を差し替えても、ソースコード・SSH 鍵・`gh` ログイン状態がそのまま維持される。Android 標準「Files」アプリとシームレスに双方向共有できる。
- **トレードオフ**：アプリをアンインストールすると `filesDir` 配下は消去されるため、重要な成果物は `git push` または `/android/sdcard` への保存が前提となる。

## 該当実装

- [PosixRuntimeConfig.kt](../../android/app/src/main/java/dev/koduki/xreditor/PosixRuntimeConfig.kt)
- [PosixRuntimeManager.kt](../../android/app/src/main/java/dev/koduki/xreditor/PosixRuntimeManager.kt)
- [XrDocumentsProvider.kt](../../android/app/src/main/java/dev/koduki/xreditor/XrDocumentsProvider.kt)
