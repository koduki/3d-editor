# ADR-0012: AURA向けセルフホスト開発と標準Linuxへの収束

- **Status:** Accepted（実装・実機検証は未完了）
- **Date:** 2026-11-25
- **Tracking:** [#17](https://github.com/koduki/kucode/issues/17)

## Context

KuCodeはAndroid専用IDEではなく、空間コンピューティング向けのAIネイティブな汎用Linux開発環境である。最初の包括的な実用検証として、XREAL AURA上のKuCodeでKuCode本体を開発する。現行v0.9.1はPRoot/Debianを同梱し、rootfsをランタイムバージョン更新で交換する。これではapt追加パッケージを保持できない。また、資産生成スクリプトはDocker/ホストx86-64 NDKを前提とする。

将来Android XRが標準Linux環境（AVFベース等）を利用可能にする場合、独自PRootを置き換えたい。ただし一般アプリが標準VMを管理・接続できることは未確認であり、Android OSのバージョンのみで利用可能と判断しない。

## Decisions

1. **セルフホストの範囲**: KuCodeのKotlin/JS/設定/リソースを端末内で編集・テスト・ビルド・署名・インストール・起動・診断する。PC、外部ビルドサービス、ADBを必須としない。通常のネットワークからソース、SDK、固定済み依存資産の取得は許容。PRoot/loader、Debian seed、OpenVSCode自体の再コンパイルは不要。
2. **対象・SDK**: 初期の正式対象はXREAL AURA/Linux ARM64のみ。2026年11月時点の最新安定版から互換性のある組合せを選定し、配布元・バージョン・ハッシュを固定する。公式ARM64、Debian公式パッケージ、信頼できるコミュニティARM64ビルドの順に評価。旧版互換・x86エミュレーションは非ゴール。採用バージョンは実測で確定し、未検証の組合せを対応済みとしない。
3. **永続Linux**: Linuxファイルシステムを開発データの正本とする。Debian rootfs、home、workspace、SDK、cache、ユーザー設定を通常APK更新で保持する。OS更新はapt等で独立。管理対象のOpenVSCode、内蔵拡張、PRootとホスト同期設定はユーザー領域から分離し、更新を検証して切替・復旧する。標準LinuxのHOME/一般ユーザー/作業ディレクトリの概念に寄せる（PRoot擬似UIDは隔離ではない）。既存データの一度限りの安全な移行を行い、初期化は明示的操作のみ。SAF/DocumentsProvider等は共有の補助手段であり、外部ストレージをビルドの正本にしない。
4. **薄いSDK統合**: apt、mise、公式SDK CLI、Gradle Wrapper等を再利用し、大型SDKはオンデマンド。CLI、Tasks、ACPのPATH/環境を一致させる。未信頼プロジェクトの設定による実行はユーザーの信頼確認を尊重。言語固有LSP/DAPは既存拡張を使う。独自パッケージ管理は作らない。
5. **Android拡張**: kucode-androidをkucode-toolsと別のVS Code拡張として設計。単一リポジトリ、同一CI、同一APKで初期配布し、Androidプロジェクトでのみ活性化。SDK導入支援、Gradleタスク、APK配置・起動・診断UXを担当する。Coreは汎用Linux・XR・ホストAPIを担当。バージョン付き最小Host Bridge/CLIを契約とし、互いの内部実装に依存しない。通常PC版VS Code対応、別配布、独自ビルドシステムは不要。
6. **CLI First**: 既存CLIを直接使い、Androidホストでしかできない操作だけKuCode CLIを提供する。GUIとAIは同じ経路を利用。標準入出力、終了コード、機械可読JSON、非同期操作のID/結果/承認待ち/取消を明確化する。専用MCPは必要性が判明するまで追加しない。
7. **Android Native連携**: PackageInstaller.Session、Intent、SAF等の標準APIを使い、未知アプリ許可、署名、ユーザー承認、バックグラウンド起動制限、package visibilityに従う。Root、Shizuku、ADB、非公開APIは必須にしない。Host Bridgeは入力検証・認証・監査を行うが独自IAMは作らない。同一UIDのLinux/ACP/拡張に対するトークンはOS隔離境界ではない。APKのステージングと対象確認、安定版誤更新防止を行う。高度なデバッグのADBは任意。
8. **Stable/Dev**: 既存.debug suffixを活用し、UID・rootfs・home・SDK・workspace・認証情報・署名・ポート・Provider authorityを分離。Dev障害/再インストールがStableを破壊しない。診断は標準アプリ間通信/明示的なURI共有等で、同一署名を仮定しない。診断データは機密除去・容量制限・ビルドID対応を必須とする。Stable更新は通常の署名済みAPK更新。
9. **資産配布**: PRoot/loader、Debian seed、OpenVSCode、対応ライセンス/パッケージマニフェストを固定成果物としてGitHub Releasesで配布。CIと端末のAPK生成は同じ取得・検証・パッケージング経路を使用し、生成ジョブを分離。ハッシュ不一致は拒否、キャッシュは再利用する。既存の16KB検査・OSSライセンス義務を維持。GitHub Releases主体、ストア対応は今回の範囲外。
10. **観測性と検証**: Flight Recorderを再利用し、ビルドSHA/バージョン/終了コード/時間、メモリ、CPU、thermal、ストレージ、終了理由を取得可能な範囲で記録。未知値はunknown。独自負荷制御は作らず既存ツールの設定を実測で調整する。KuCodeのみ包括的E2E、主要言語は小規模スモーク、その他はbest effort。ADBなしのDev診断を必須とする。

## Implementation boundary

PRootは暫定の実行手段であり、開発機能は標準Linux CLI/ファイルシステム上で成立させる。PRoot固有処理は現行RuntimeInstaller/PosixRuntimeConfig/Manager等に局所化。新しいSDK管理をrootfs展開処理へ密結合させない。AVFへの移行時にはコード変更を許容し、LinuxBackend、動的切替、仮想FS、将来APIの模倣を作らない。

## Alternatives and consequences

- 使い捨てrootfs/差分レイヤーより永続rootfsを選択。apt状態を維持できる反面、明示的な復旧とOS管理が必要。
- SDK管理GUIより既存CLIの薄い統合を選択。全ツールの互換性は保証しない。
- Android拡張の別リポジトリ・別配布は今は不要。モジュール境界だけ確立。
- ADB必須ではなく標準Android APIを選択。任意アプリのLogcat/JDWP/UI操作をADBなしで保証しない。
- Stable/Dev共有環境ではなく独立環境を選択。追加ストレージ・RAM消費を実測する。
- 仮想化抽象化より将来の直接改修を選択。標準VMへの接続可否が判明してから移行を判断する。

## Supersedes

ADR-0003/0004のAPK更新に伴うrootfs交換を前提とする部分と、固定rootホーム・workspace配置の設計を次期仕様で更新する。ADR-0005の固定ポート方針はStable/Dev共存のため両者の衝突回避を優先する。既存のv0.9.1実装記述は履歴であり、実装済みとみなさない。他の既存決定は矛盾しない範囲で維持する。
