# ADR-0013: Flight Recorder の OpenTelemetry (OTLP/HTTP JSON) 標準化とオプトイン・クラウド集約

- **ステータス:** Accepted
- **日付:** 2026-11-26
- **関連:** [ADR-0010](0010-multi-window-browser-bridge-and-session-flight-recorder.md), [ADR-0011](0011-builtin-acp-chat-and-v090-comprehensive-hardening.md), [ADR-0012](0012-aura-self-host-standard-linux.md), Issue #36

## 背景（Context）

[ADR-0010](0010-multi-window-browser-bridge-and-session-flight-recorder.md) で導入した Flight Recorder（`SessionLogStore` / `SessionTelemetryManager`）は、端末ローカル完結でクラッシュ・ANR・LMKD に強い診断記録を実現した。一方で以下の課題が残っていた。

1. **実機障害調査の手動コピーコスト**: XREAL AURA 実機上の障害を AI エージェントに調査させる際、人間が `boot.html` や `kucode-logs` から独自 Markdown バンドル（`kucode-session-v1`）を取り出して共有する手間が発生する。
2. **独自スキーマと文字列パース依存**: 独自 JSON キーや Markdown の正規表現パース（`.agents/skills/ship/scripts/analyze-flight-recorder.mjs`）に依存しており、汎用 Observability 基盤や AI 調査ツールと連携しにくい。
3. **レイヤー横断の区間・因果追跡の不足**: Android Host・WebView・`LocalDiagnosticsServer`・`kucode-tools`・`kucode-cli`・PRoot 間の処理が `sessionId` のみで緩く関連づけられており、処理所要時間や因果関係（Trace / Span）を追えない。

## 決定事項（Decision）

**既存のオフライン先行（Offline-first / Store-and-Forward）記録基盤と軽量依存構成を維持しつつ、データ形式・相関プロトコル・エクスポート先を OpenTelemetry 標準（OTLP/HTTP JSON + W3C Trace Context）に寄せる。**

1. **軽量・追加依存ゼロの OTel 互換設計**
   - `opentelemetry-android` / `opentelemetry-java`・gRPC・Protobuf のフル導入は APK サイズとメモリ負荷に対して過剰であり、また LMKD/OOM 時の取りこぼし対策や `ApplicationExitInfo` 突合、`LogRedactor` による端末内リダクションは結局自前実装が必要なため見送る。
   - 既存の純 JVM 依存（`org.json` + 標準 HTTP + `kotlinx-coroutines-android`）のまま、W3C `traceparent`・軽量 `SpanRecord`・OTLP/HTTP JSON v1.x シリアライザ・Store-and-Forward エクスポーターを実装する。
2. **既存ローカル互換性と OTel 3 シグナル対応**
   - `kucode-session-v1` の既存ファイル（`meta.json`, `events.*.jsonl`, `metrics.jsonl`, `vscode-sublogs.txt`, `exit-trace.txt`）および 4 つのローカル閲覧経路は後方互換を維持する。
   - `SessionSummary` に `buildSha`, `traceId`, `rootSpanId` を追加し、主要処理区間（`kucode.session`, `runtime.bootstrap`, `openvscode.startup`, `host_bridge.request`, `proot.command`, `acp.turn`）を `spans.jsonl` に即時フラッシュ記録する。`logcat` や WebView コンソール等の点イベントはスパン化せず OTel Logs に留める。
   - 異常終了・クラッシュ時に未完了のまま残ったスパンは、`recordUncaughtCrash` および次回起動時の `reconcileUnfinishedSessions`（`ApplicationExitInfo` 照合）で `STATUS_CODE_ERROR` (`code = 2`) として確定させる。
3. **W3C Trace Context (`traceparent`) の伝播**
   - Android Host から PRoot 環境（`TRACEPARENT`）・`LocalDiagnosticsServer`（`traceparent` ヘッダ、`POST /event`・`POST /span`）・`kucode-cli.cjs`・`kucode-tools`（ACP ターン）へトレース文脈を伝播させる。
4. **オプトイン・プライバシー保護・Store-and-Forward クラウド送信**
   - クラウド送信は **デフォルト OFF のオプトイン** とし、デフォルト送信粒度は `summary-and-errors`（Resource + 全 Span + Metrics + WARN/ERROR/FATAL Logs + exit-trace）とする（`full` も選択可能）。
   - 認証ヘッダ値は Android 標準 `AndroidKeyStore`（AES-256-GCM）で暗号化して専用 `SharedPreferences`（`kucode_otlp_prefs`, `MODE_PRIVATE`）へ保存し、`LogRedactor.registerSecret` へ動的登録する。WebView（`boot.html`）へ平文トークンは一切返さない。
   - OTLP エンドポイントは `https://` 必須（ローカル検証用の `127.0.0.1` / `localhost` のみ `http://` 許可）とし、保護ファイル `network_security_config.xml` は変更しない。リダイレクト追従を無効化し、認証ヘッダの意図しない流出を防ぐ。
   - 未送信キュー（`<filesDir>/otlp-outbox`）は `<filesDir>/diagnostics`（PRoot `/var/log/kucode`・`KuCodeDocumentsProvider` 公開領域）と分離し、最大 20 セッション・最大 3 回リトライ（バックオフ付き）・`unmeteredOnly` オプション・手動フラッシュ（`boot.html` / `POST /otlp/flush` / `kucode-logs otlp-flush`）を備える。
5. **AI エージェント向け汎用トリアージ CLI（CLI First）**
   - [ADR-0012](0012-aura-self-host-standard-linux.md) の CLI First 方針に従い、特定ベンダー専用 MCP サーバーは追加せず、`.agents/skills/ship/scripts/analyze-flight-recorder.mjs` の OTLP JSON 対応および汎用トリアージ CLI（`scripts/otlp-triage.mjs`）を整備する。すべての外部・ローカル診断データは untrusted data として扱い、無制限な生 INFO ログをコンテキストへ流さない。

## 影響とトレードオフ（Consequences）

- **メリット**:
  - 既存の軽量 APK・クラッシュ耐性・オフライン完結性を一切損なわずに、Grafana / SigNoz / ClickHouse / OTel Collector 等の標準基盤へ直接連携できる。
  - レイヤー横断の所要時間・エラー因果が `traceId` / `spanId` で一意に結合され、AI エージェントによる RCA 精度が大幅に向上する。
- **トレードオフ**:
  - OTLP/HTTP JSON v1.x スキーマを自前シリアライズするため、Protobuf-JSON 規約（64-bit 整数の文字列化等）を Clean-Room Oracle テストで継続的に検証する必要がある。
  - 特定クラウド製品固有のクエリ API 連携は将来必要性が判明した時点で別 ADR/Issue として追加する。
