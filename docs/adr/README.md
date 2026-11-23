# Architecture Decision Records (ADR)

XR-Editor の設計判断・採用理由・検討した代替案・トレードオフを意思決定ごとに記録しています。

| ADR | タイトル | ステータス | 日付 |
| --- | --- | --- | --- |
| [ADR-0001](0001-self-contained-apk-pivot.md) | v0.4.0 外部サーバー構成を廃止し、単体完結 APK（Minimal Reboot）へピボットする | Accepted | 2026-11-22 |
| [ADR-0002](0002-proot-unbundled-loader-targetsdk35.md) | `targetSdk = 35`（W^X）維持のため分離ローダー型 PRoot を `jniLibs` 同梱する | Accepted | 2026-11-22 |
| [ADR-0003](0003-debian-minimal-and-openvscode-server.md) | Debian 12 minimal + `openvscode-server` を `.tgz`（`noCompress`）で同梱する | Accepted | 2026-11-22 |
| [ADR-0004](0004-decoupled-persistent-storage-and-documents-provider.md) | `/workspace`・`/root` の永続分離と `XrDocumentsProvider` による双方向ファイル共有 | Accepted | 2026-11-22 |
| [ADR-0005](0005-fixed-port-and-isolated-security-boundaries.md) | 優先固定ポート `18765`・`XREditorHost` 隔離・トークン認証ループバック管理 API | Accepted | 2026-11-22 |
| [ADR-0006](0006-xr-spatial-desktop-ux-and-builtin-extension.md) | 物理キーボード・XR マルチウィンドウ最適化 UX と内蔵拡張 `xr-editor-tools` | Accepted | 2026-11-22 |
| [ADR-0007](0007-on-device-diagnostics-and-non-pty-fallback.md) | 並列実機診断チェッカー（自動コピー）と非 PTY フォールバック CLI の内蔵 | Accepted | 2026-11-22 |
| [ADR-0008](0008-oss-license-compliance-and-manifest-bundling.md) | 本体 MIT 化とライセンス全文・Debian パッケージマニフェストの APK 自動同梱 | Accepted | 2026-11-23 |
