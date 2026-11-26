# Architecture Decision Records (ADR)

KuCode の設計判断・採用理由・検討した代替案・トレードオフを意思決定ごとに記録しています。

| ADR | タイトル | ステータス | 日付 |
| --- | --- | --- | --- |
| [ADR-0001](0001-self-contained-apk-pivot.md) | 単体完結 APK による最小構成アーキテクチャを採用する | Accepted | 2026-11-22 |
| [ADR-0002](0002-proot-unbundled-loader-targetsdk35.md) | `targetSdk = 35`（W^X）維持のため分離ローダー型 PRoot を `jniLibs` 同梱する | Accepted | 2026-11-22 |
| [ADR-0003](0003-debian-minimal-and-openvscode-server.md) | Debian 12 minimal + `openvscode-server` を `.tgz`（`noCompress`）で同梱する | Accepted | 2026-11-22 |
| [ADR-0004](0004-decoupled-persistent-storage-and-documents-provider.md) | `/workspace`・`/root` の永続分離と `KuCodeDocumentsProvider` による双方向ファイル共有 | Accepted | 2026-11-22 |
| [ADR-0005](0005-fixed-port-and-isolated-security-boundaries.md) | 優先固定ポート `18765`・`KuCodeHost` 隔離・トークン認証ループバック管理 API | Accepted | 2026-11-22 |
| [ADR-0006](0006-xr-spatial-desktop-ux-and-builtin-extension.md) | 物理キーボード・空間マルチウィンドウ最適化 UX と内蔵拡張 `kucode-tools` | Accepted | 2026-11-22 |
| [ADR-0007](0007-on-device-diagnostics-and-non-pty-fallback.md) | 並列実機診断チェッカー（自動コピー）と非 PTY フォールバック CLI の内蔵 | Accepted | 2026-11-22 |
| [ADR-0008](0008-oss-license-compliance-and-manifest-bundling.md) | 本体 MIT 化とライセンス全文・Debian パッケージマニフェストの APK 自動同梱 | Accepted | 2026-11-23 |
| [ADR-0009](0009-brand-naming-kucode.md) | プロダクト名称と識別子を「KuCode」に統一する | Accepted | 2026-11-23 |
| [ADR-0010](0010-multi-window-browser-bridge-and-session-flight-recorder.md) | XR 外部ブラウザ起動のエディタ保護（`onCreateWindow` / `kucode-open`）と起動〜終了単位フライトレコーダー（AI 診断バンドル） | Accepted | 2026-11-24 |
| [ADR-0011](0011-builtin-acp-chat-and-v090-comprehensive-hardening.md) | 内蔵 ACP v1 AI エージェントチャット統合と v0.9.0 全層ハードニング | Accepted | 2026-11-24 |
| [ADR-0012](0012-aura-self-host-standard-linux.md) | AURA向けセルフホスト開発と標準Linuxへの収束 | Accepted（未実装） | 2026-11-25 |
| [ADR-0013](0013-otel-flight-recorder-and-opt-in-otlp-export.md) | Flight Recorder の OpenTelemetry (OTLP/HTTP JSON) 標準化とオプトイン・クラウド集約 | Accepted | 2026-11-26 |

ADR-0012はADR-0003/0004のrootfs更新モデル、ADR-0005のポート選定の一部を次期仕様として更新する。既存ADRは履歴として維持する。
