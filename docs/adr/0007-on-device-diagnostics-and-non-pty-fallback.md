# ADR-0007: 並列実機診断チェッカー（自動コピー）と非 PTY フォールバック CLI の内蔵

- **ステータス:** Accepted
- **日付:** 2026-11-22

## 背景（Context）

Android XR 実機（XREAL AURA 等）はエミュレータと異なり、カーネルの 16KB ページサイズ・SELinux ポリシー・seccomp 挙動・`node-pty` の PTY 確保・`inotify` 上限・ネットワーク DNS・WebView バージョンなど環境固有の要因が多い。また実機で正常起動すると即座に VS Code UI へ遷移するため、起動ログや詳細診断結果を取り出して共有する手段が別途必要だった。

## 決定事項（Decision）

1. **アプリ内蔵の非破壊・並列診断スクリプト（`DiagnosticsRunner`）**
   - 端末基礎情報（端末名・Android API・ABI・4KB/16KB ページサイズ・SELinux・WebView・ディスプレイ・接続中 KB/Mouse・システム DNS・バッテリー最適化・通知・ストレージ/メモリ等）を収集。
   - ネイティブバイナリ確認・展開確認・`KuCodeDocumentsProvider` の共有ルート確認・PRoot Gate 1（`PROOT_NO_SECCOMP=1` 自動プローブ含む）を実行したうえで、**5 系統の POSIX プローブを並列実行**する：
     1. Node.js & `openvscode-server` バージョン
     2. `node-pty` でシェルへ入力し、サイズ変更後の行・列とシェルが読み取った一意な文字列を照合
     3. `@parcel/watcher` で作業領域の一時ディレクトリを購読し、作成したファイルのイベントを照合
     4. Android DocumentsProvider で一時ファイルを作成し、ゲストの `/workspace` で照合・上書きし、Android 側で読み戻して削除。永続ホームとアプリ共有領域もホストとゲストの双方から一時ファイルの内容を照合する
     5. DNS 解決・Debian apt HTTP・GitHub HTTPS・`dpkg`/`apt`/`git`/`gh`/`curl`
   - 直近 80 行のサーバー／エラーログを含めた単一の Markdown レポートに整形し、エディタ内からはタブ表示＋Android ネイティブクリップボード自動コピー、`boot.html` からはボタン 1 つ（および起動エラー時は自動実行）で取得可能にする。
2. **非 PTY フォールバック CLI コンソール（`boot.html`）**
   - 万一 `node-pty` が動かない端末でも最低限の CLI・`apt` 操作を継続できるよう、`boot.html` から `PosixRuntimeManager.runShellCommand()` 経由で PRoot の `bash -lc` を実行可能にする。
   - `runShellCommand()` は稼働中サーバーの設定ファイルを書き換えないよう `ensureMountPointDirsExist()` に分離する。

## 検討した代替案（Alternatives Considered）

- **代替案 A：`adb logcat` と手動シェルコマンドで実機調査する**
  - 実機単体で完結せず、毎回コマンドを打つ負担が大きいため不採用。
- **代替案 B：プローブを直列実行する**
  - ネットワーク遅延時に `LocalDiagnosticsServer` のソケットタイムアウト（60 秒）に近づく恐れがあったため、Gate 1 通過後のプローブを `coroutineScope { async { ... } }` で並列化した。

## 影響とトレードオフ（Consequences）

- **メリット**：実機から 1 クリックで診断レポートをコピーしてそのまま共有・デバッグできる。起動失敗時も自動でレポートが生成される。
- **トレードオフ**：診断実行中は PRoot の子プロセスを一時的に並列起動するため一瞬 CPU を消費するが、非破壊かつ数秒で完了する。

## 該当実装

- [DiagnosticsRunner.kt](../../android/app/src/main/java/dev/koduki/kucode/DiagnosticsRunner.kt)
- [DiagnosticsRunnerTest.kt](../../android/app/src/test/java/dev/koduki/kucode/DiagnosticsRunnerTest.kt)
- [android/app/src/main/assets/boot.html](../../android/app/src/main/assets/boot.html)
- [PosixRuntimeManager.kt](../../android/app/src/main/java/dev/koduki/kucode/PosixRuntimeManager.kt)
