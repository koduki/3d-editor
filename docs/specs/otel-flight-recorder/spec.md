# Feature Contract: OpenTelemetry (OTLP) Flight Recorder & Opt-In Cloud Aggregation

- **Issue:** #36
- **ADR:** [ADR-0013](../../adr/0013-otel-flight-recorder-and-opt-in-otlp-export.md)
- **Delivery Target:** `merge-only`
- **Allowed Protected Paths:** `[]` (zero protected files unlocked; `network_security_config.xml`, `build.gradle.kts`, `proguard-rules.pro`, `scripts/guards/**`, `.github/workflows/**` remain untouched)

---

## 1. Goals & Non-Goals

### Goals
1. **Phase 0 (ADR)**: Document the hybrid offline-first + OTLP/HTTP JSON architecture in `docs/adr/0013-otel-flight-recorder-and-opt-in-otlp-export.md`.
2. **Phase 1 (Local OTel Compatibility & Correlation)**:
   - Add W3C Trace Context (`traceId`, `spanId`, `traceparent`) and lightweight `SpanRecord` persistence (`spans.jsonl`) to `SessionLogStore` while keeping `kucode-session-v1` 100% backward-compatible.
   - Promote `buildSha` to a first-class field on `SessionEnvMetadata` and `SessionSummary`.
   - Finalize unfinished spans on clean exit, uncaught JVM crash, and next-boot `reconcileUnfinishedSessions` (`ApplicationExitInfo`) with `status = ERROR` (`code = 2`) for abnormal/interrupted spans.
   - Implement a pure-JVM OTLP/HTTP JSON v1.x serializer (`OtlpJsonSerializer`) covering `resourceSpans`, `resourceMetrics`, and `resourceLogs` with `summary-and-errors` (default) and `full` granularities.
   - Propagate W3C `traceparent` across Android Host (`PosixRuntimeConfig`, `OpenVsCodeLauncher`, `PosixShellExecutor`), `LocalDiagnosticsServer` (`POST /event`, `POST /span`), `kucode-cli.cjs` (`kucode build --`, `kucode android ...`), and `kucode-tools` (`acp.turn`, terminal env).
3. **Phase 2 (Opt-In Store-and-Forward Cloud Export)**:
   - Default-OFF opt-in OTLP/HTTP JSON export configured via `boot.html` (`BootHostBridge`) and backed by Android `SharedPreferences` (`kucode_otlp_prefs`, `MODE_PRIVATE`) with Android Keystore AES-256-GCM encryption for the auth header value (injectable `OtlpSecretCipher` for JVM unit tests) and automatic `LogRedactor.registerSecret` registration.
   - Strict endpoint validation (`https://` required; `http://` permitted ONLY for `127.0.0.1` and `localhost`; rejects userinfo and non-HTTP/HTTPS schemes; `instanceFollowRedirects = false`).
   - Isolated outbox (`<filesDir>/otlp-outbox`, separate from `<filesDir>/diagnostics`) with max 20 queued sessions, max 3 retry attempts with backoff, permanent 4xx drop, `unmeteredOnly` deferral, automatic enqueue/flush on session end / next-boot reconcile / network availability, and manual flush (`boot.html`, `POST /otlp/flush`, `kucode-logs otlp-flush`).
4. **Phase 3 (Generic OTLP Analyzer & AI Triage CLI)**:
   - Extend `.agents/skills/ship/scripts/analyze-flight-recorder.mjs` to parse OTLP JSON bundles (single signal, combined `{resourceSpans, resourceMetrics, resourceLogs}`, or array/batch), fetch from `--url <http(s)://...>` with `--header-env`, and filter by `--session-id`, `--build-sha`, `--abnormal-only`, preserving `schema: 2`, `readOnly: true`, `untrustedData: true`, and omitting unbounded `INFO` logs.
   - Provide `scripts/otlp-triage.mjs` as a thin generic CLI entrypoint delegating to `analyze-flight-recorder.mjs`.

### Non-Goals
- Adding `opentelemetry-android`, `opentelemetry-java`, gRPC, Protobuf, or any new Gradle dependency.
- Creating a vendor-specific MCP server (e.g. Grafana/ClickHouse/SigNoz-specific MCP; deferred per ADR-0012 CLI First).
- Turning routine `logcat` or WebView console point events into spans (they remain OTel Logs correlated by `traceId`/`spanId`).
- Modifying `network_security_config.xml`, `build.gradle.kts`, `proguard-rules.pro`, or breaking any existing `kucode-session-v1` offline access path.
- Claiming physical XREAL AURA real-device acceptance from CI/host tests (`AC-14` MUST remain `NOT RUN`).

---

## 2. Public Contracts & Normative Interfaces

### 2.1 `TraceContext` (`dev.koduki.kucode.TraceContext`, pure-JVM)
- Constants & generators:
  - `fun newTraceId(): String` — 32 lowercase hex chars, never all-zero (`"0".repeat(32)`).
  - `fun newSpanId(): String` — 16 lowercase hex chars, never all-zero (`"0".repeat(16)`).
  - `fun deterministicTraceId(sessionId: String): String` — deterministic 32 lowercase hex chars derived from `SHA-256("kucode-trace:$sessionId")`, non-all-zero.
  - `fun deterministicSpanId(sessionId: String): String` — deterministic 16 lowercase hex chars derived from `SHA-256("kucode-span:$sessionId")`, non-all-zero.
  - `fun isValidTraceId(value: String?): Boolean` — exact 32 lowercase hex chars `[0-9a-f]{32}` and not all-zero.
  - `fun isValidSpanId(value: String?): Boolean` — exact 16 lowercase hex chars `[0-9a-f]{16}` and not all-zero.
- W3C `traceparent`:
  - `data class ParsedTraceParent(val version: String, val traceId: String, val parentSpanId: String, val traceFlags: String)`
  - `fun formatTraceParent(traceId: String, spanId: String, traceFlags: String = "01"): String` — returns `"00-$traceId-$spanId-$traceFlags"` (throws `IllegalArgumentException` if IDs invalid).
  - `fun parseTraceParent(header: String?): ParsedTraceParent?` — parses trimmed W3C `traceparent` (`<2hex version>-<32hex traceId>-<16hex spanId>-<2hex flags>`, case-insensitive input normalized to lowercase). Returns `null` if `version == "ff"`, all-zero `traceId`/`spanId`, wrong segment count/length, or non-hex chars.

### 2.2 `SessionLogStore` (`dev.koduki.kucode.SessionLogStore`, pure-JVM)
- Backward compatibility: `SCHEMA_VERSION` remains `"kucode-session-v1"`. All existing constructors, methods, JSON keys, and Markdown bundle format remain intact.
- `SessionEnvMetadata`: adds `val buildSha: String = "unknown"` (defaulted so existing positional/named callers compile unchanged).
- `SessionSummary`: adds `val buildSha: String = "unknown"`, `val traceId: String = ""`, `val rootSpanId: String = ""`. `meta.json` includes `"buildSha"`, `"traceId"`, `"rootSpanId"`. Missing keys on legacy `meta.json` default to `"unknown"` / `""`.
- `RecordedEvent`: adds `val traceId: String? = null`, `val spanId: String? = null`. Serialized into `events.*.jsonl` when non-null; omitted or defaulted to `null` when absent on legacy JSONL.
- `recordEvent(...)`: adds optional parameters `traceId: String? = null, spanId: String? = null` (defaulting to active session `traceId` and `rootSpanId`).
- Spans:
  - `enum class SpanKind(val otlpCode: Int) { INTERNAL(1), SERVER(2), CLIENT(3), PRODUCER(4), CONSUMER(5) }`
  - `enum class SpanStatus(val otlpCode: Int) { UNSET(0), OK(1), ERROR(2) }`
  - `data class SpanRecord(val traceId: String, val spanId: String, val parentSpanId: String? = null, val name: String, val kind: SpanKind = SpanKind.INTERNAL, val startMs: Long, val endMs: Long? = null, val status: SpanStatus = SpanStatus.UNSET, val statusMessage: String? = null, val attributes: Map<String, Any?> = emptyMap())`
  - Storage: `<sessionDir>/spans.jsonl` (max `DEFAULT_MAX_SPANS = 500` unique spans per session; `worldReadable = true`).
  - Attribute bounds & redaction: max 64 entries per span, key <= 128 chars, string value <= 2048 chars and redacted via `LogRedactor.redact`; `name` <= 128 chars and redacted; `statusMessage` <= 512 chars and redacted.
  - Methods:
    - `fun startSpan(name: String, kind: SpanKind = SpanKind.INTERNAL, parentSpanId: String? = null, traceId: String? = null, spanId: String? = null, startMs: Long = clockMs(), attributes: Map<String, Any?> = emptyMap()): SpanRecord?` — appends open line (`endMs = null`) and flushes immediately.
    - `fun endSpan(spanId: String, endMs: Long = clockMs(), status: SpanStatus = SpanStatus.OK, statusMessage: String? = null, attributes: Map<String, Any?> = emptyMap()): SpanRecord?` — merges attributes, clamps `endMs = maxOf(startMs, endMs)`, appends finalized line, flushes immediately.
    - `fun recordSpan(name: String, kind: SpanKind = SpanKind.INTERNAL, traceId: String? = null, spanId: String? = null, parentSpanId: String? = null, startMs: Long, endMs: Long, status: SpanStatus = SpanStatus.OK, statusMessage: String? = null, attributes: Map<String, Any?> = emptyMap()): SpanRecord?` — records a completed span directly.
    - `fun currentTraceParent(): String?` — returns active session `00-<traceId>-<rootSpanId>-01` or `null` if no active session.
    - `fun readSessionSpans(selector: String = "current"): List<SpanRecord>` — collapses `spans.jsonl` by `spanId` (latest entry wins, ordered by `startMs`).
  - Lifecycle invariants:
    - `startSession(...)`: generates non-zero `traceId` & `rootSpanId` and starts root span `"kucode.session"` (`spanId = rootSpanId`, `parentSpanId = null`).
    - `endSession(...)`: closes root `"kucode.session"` and any open child spans (`OK` on clean end, `ERROR` on abnormal end).
    - `recordUncaughtCrash(...)`: synchronously closes `"kucode.session"` and all open spans with `SpanStatus.ERROR` (`statusMessage = headline`) before exiting; keeps OOM-safe path (no heavy OTLP/Markdown build on `OutOfMemoryError`).
    - `reconcileUnfinishedSessions(...)`: reads `spans.jsonl`; closes any open span (`endMs == null`) and ensures `"kucode.session"` exists. If `finalStatus == SessionStatus.ENDED_ABNORMALLY`, unfinished spans (including `"kucode.session"`) are finalized with `SpanStatus.ERROR` and `statusMessage = exitSummary`. If `finalStatus == SessionStatus.ENDED_CLEANLY`, `"kucode.session"` is finalized with `SpanStatus.OK` and any unfinished non-root child span is finalized with `SpanStatus.ERROR` (`statusMessage = "Interrupted by process exit: $exitCategory"`).
- Rendering & export:
  - `fun renderSessionBundle(selector: String = "current", format: String = "ai", vscodeDataDir: File? = null, granularity: String = OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS): String?`
    - Existing `"ai"`, `"raw"`, `"json"` unchanged.
    - `"otlp"` returns combined OTLP/HTTP JSON string (`{ "resourceSpans": [...], "resourceMetrics": [...], "resourceLogs": [...] }`) serialized by `OtlpJsonSerializer`.
  - `fun exportSessionBundleToDirectory(selector: String, targetDir: File, vscodeDataDir: File? = null, format: String = "ai", granularity: String = OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS): File?`
    - When `format == "otlp"`, writes `kucode-session-${summary.sessionId}-otlp.json` (`0644`).

### 2.3 `OtlpJsonSerializer` (`dev.koduki.kucode.OtlpJsonSerializer`, pure-JVM)
- Constants:
  - `const val GRANULARITY_SUMMARY_AND_ERRORS = "summary-and-errors"`
  - `const val GRANULARITY_FULL = "full"`
  - `fun isValidGranularity(value: String?): Boolean`
- Data class:
  - `data class OtlpPayloads(val tracesJson: JSONObject, val metricsJson: JSONObject?, val logsJson: JSONObject?, val combinedJson: JSONObject)`
  - `fun serializeSession(store: SessionLogStore, selector: String = "current", granularity: String = GRANULARITY_SUMMARY_AND_ERRORS, vscodeDataDir: File? = null): OtlpPayloads?`
- Wire format (OTLP/HTTP JSON v1.x, Protobuf-JSON canonical):
  - All `*TimeUnixNano`, `AnyValue.intValue`, and `NumberDataPoint.asInt` MUST be decimal strings (e.g. `"1700000000000000000"`).
  - All string attribute values, span names, status messages, and log bodies MUST pass through `LogRedactor.redact`.
  - **Resource attributes** (identical across `resourceSpans`, `resourceMetrics`, `resourceLogs`):
    - `service.name` (`"kucode"`), `service.namespace` (`"kucode"`), `service.version` (`summary.appVersion`), `service.instance.id` (`summary.sessionId`), `session.id` (`summary.sessionId`), `os.type` (`"android"`), `os.version` (`summary.androidSummary`), `device.model.identifier` (`summary.deviceSummary`), `process.pid` (`intValue`), `kucode.package.name`, `kucode.build.sha`, `kucode.runtime.version`, `kucode.xr.spatial_supported` (`boolValue`), `kucode.page_size`, `kucode.webview.version`, `kucode.session.status`, `kucode.exit.category`, `kucode.exit.summary`, `kucode.runtime.state`, `kucode.disable_seccomp` (`boolValue`), `kucode.has_all_files_access` (`boolValue`), `kucode.notifications_enabled` (`boolValue`), `kucode.battery_opt_ignored` (`boolValue`), `kucode.telemetry.granularity`.
  - **Traces (`resourceSpans`)**:
    - `scope: { name: "dev.koduki.kucode.flight_recorder", version: summary.appVersion }`.
    - Each span has `traceId` (32 hex), `spanId` (16 hex), optional `parentSpanId` (16 hex, omitted if null), `name`, `kind` (`1..5`), `startTimeUnixNano`, `endTimeUnixNano`, `attributes`, `status: { code: 0|1|2, message?: string }`.
    - Always emits at least root `"kucode.session"` span (synthesized from `SessionSummary` using deterministic IDs for legacy sessions without `spans.jsonl`).
  - **Metrics (`resourceMetrics`)**:
    - Gauge metrics with `timeUnixNano` and data-point attributes (`kucode.metric.trigger`, `kucode.runtime.state`, `kucode.active_view`, `kucode.thermal.status`, `kucode.network.summary`, `kucode.window.focus`). Unknown numeric values (`< 0`) omitted:
      - `kucode.process.pss_mb` (`MiBy`), `kucode.process.avail_mem_mb` (`MiBy`), `kucode.process.total_mem_mb` (`MiBy`), `kucode.process.jvm_heap_used_mb` (`MiBy`), `kucode.process.native_heap_mb` (`MiBy`), `kucode.process.open_fds` (`{fd}`), `kucode.process.thread_count` (`{thread}`), `kucode.disk.free_mb` (`MiBy`), `kucode.host.cpu_time_ms` (`ms`), `kucode.device.battery_pct` (`%`), `kucode.process.low_memory` (`0`|`1`), `kucode.server.alive` (`0`|`1`), `kucode.thermal.level` (`0..6`, omitted if `"UNKNOWN"`).
    - `metricsJson` is `null` if zero metric data points exist; `combinedJson.resourceMetrics` is `[]` in that case.
  - **Logs (`resourceLogs`)**:
    - Severity: `INFO -> 9`, `WARN -> 13`, `ERROR -> 17`, `FATAL -> 21`.
    - Each `logRecord` has `timeUnixNano`, `observedTimeUnixNano`, `severityNumber`, `severityText`, `body: { stringValue }`, `traceId`, `spanId`, `attributes` (`kucode.event.source`, `kucode.event.seq` when applicable, optional `kucode.event.benign`, `kucode.log.kind`).
    - `summary-and-errors`: includes `events.alerts.jsonl` (`WARN`/`ERROR`/`FATAL`) + `exit-trace.txt` (`severityNumber=21`, `kucode.log.kind="exit_trace"`). Excludes routine `INFO` events and `vscode-sublogs.txt`.
    - `full`: includes head + tail events (deduped by `seq`, sorted by `seq`) + `exit-trace.txt` + `vscode-sublogs.txt` (`severityNumber=9`, `kucode.log.kind="vscode_sublogs"`).

### 2.4 `OtlpOutboxExporter` (`dev.koduki.kucode.OtlpOutboxExporter`, pure-JVM)
- **`OtlpEndpointValidator`**:
  - `data class ValidationResult(val valid: Boolean, val normalizedBaseUrl: String = "", val error: String? = null)`
  - `fun validate(rawUrl: String, requireNonBlank: Boolean = true): ValidationResult`
    - Trims whitespace, rejects control chars, rejects userinfo (`user:pass@`), requires `http` or `https` scheme.
    - Allows `https://` for any non-blank host.
    - Allows `http://` **ONLY** when host (case-insensitive) is exactly `"127.0.0.1"` or `"localhost"`. Rejects `http://` on any other host (including remote IPs, domains, `127.0.0.2`, `[::1]`).
    - Strips trailing `/v1/traces`, `/v1/metrics`, `/v1/logs`, and trailing `/`.
- **`OtlpSecretCipher` & `OtlpConfigStore`**:
  - `interface OtlpSecretCipher { fun encrypt(plaintext: String): String; fun decrypt(ciphertext: String): String? }`
  - `interface KeyValueStorage { fun getString(key: String): String?; fun getBoolean(key: String, default: Boolean): Boolean; fun putString(key: String, value: String?); fun putBoolean(key: String, value: Boolean) }`
  - `data class OtlpConfig(val enabled: Boolean = false, val endpointUrl: String = "", val granularity: String = OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS, val unmeteredOnly: Boolean = false, val authHeaderName: String = "Authorization", val authHeaderValue: String = "")`
  - `OtlpConfigStore(storage: KeyValueStorage, cipher: OtlpSecretCipher)`:
    - Defaults: `enabled = false`, `endpointUrl = ""`, `granularity = "summary-and-errors"`, `unmeteredOnly = false`, `authHeaderName = "Authorization"`, `authHeaderValue = ""`.
    - `fun loadConfig(): OtlpConfig` — decrypts stored auth header value and registers it via `LogRedactor.registerSecret` (both full value and suffix after space if `>= 8` chars).
    - `fun saveConfig(enabled: Boolean, endpointUrl: String, granularity: String, unmeteredOnly: Boolean, authHeaderName: String, authHeaderValue: String?, clearAuthHeader: Boolean = false): SaveResult`
      - Validates `endpointUrl` (`requireNonBlank = enabled`), `granularity`, and `authHeaderName` (valid HTTP header token `[A-Za-z0-9-]+`).
      - Encrypts non-empty `authHeaderValue` via `cipher.encrypt(...)` before persisting (never stores plaintext in `storage`).
      - Empty/null `authHeaderValue` with `clearAuthHeader = false` preserves existing encrypted secret; `clearAuthHeader = true` deletes it.
      - Registers secret via `LogRedactor.registerSecret`.
    - `fun getPublicConfigJson(outboxPendingCount: Int = 0): JSONObject` — returns `{ enabled, endpointUrl, granularity, unmeteredOnly, authHeaderName, hasAuthHeader, authHeaderPreview, outboxPendingCount }`. MUST NEVER include plaintext `authHeaderValue`.
- **`OtlpOutboxStore` & `OtlpHttpExporter`**:
  - Outbox dir: `<filesDir>/otlp-outbox` (separate from `<filesDir>/diagnostics`).
  - Max queued sessions: `DEFAULT_MAX_OUTBOX_SESSIONS = 20` (prunes oldest when exceeded).
  - Max retries per session: `DEFAULT_MAX_RETRIES = 3`, backoff schedule `30_000L` ms (after 1st failure), `120_000L` ms (after 2nd failure), drop on 3rd failure.
  - Permanent `4xx` HTTP responses (excluding `408` and `429`) drop the outbox item immediately without retrying.
  - HTTP transport (`OtlpHttpClient` injectable interface + default `HttpURLConnection` implementation):
    - Sets `Content-Type: application/json; charset=utf-8`, optional `<authHeaderName>: <authHeaderValue>`, `connectTimeout = 10_000`, `readTimeout = 15_000`, `instanceFollowRedirects = false`.
    - POSTs `tracesJson` to `<base>/v1/traces`, `metricsJson` (if non-null) to `<base>/v1/metrics`, and `logsJson` (if non-null) to `<base>/v1/logs`.
    - Re-validates URL before sending.
  - `OtlpOutboxExporter`:
    - `fun enqueueSession(sessionId: String, granularity: String? = null, vscodeDataDir: File? = null): Boolean` — no-op returning `false` when `config.enabled == false`. Serializes via `OtlpJsonSerializer` and persists to outbox (atomic write).
    - `fun flushOutbox(ignoreBackoff: Boolean = false): FlushResult` — returns `{ ok, enabled, enqueued, flushed, failed, deferred, remaining, skippedReason, lastError }`. When `config.enabled == false`, returns `enabled = false`, `skippedReason = "disabled"`, sends zero HTTP requests. When `config.unmeteredOnly == true` and `isNetworkMetered() == true`, defers all items (`deferred = pending`, `remaining = pending`, `skippedReason = "metered_network"`).

### 2.5 `LocalDiagnosticsServer` HTTP Extensions (`127.0.0.1` loopback, `X-KuCode-Token` auth, CSRF guard, 1 MiB cap)
- `GET /sessions/<selector>?format=otlp[&granularity=summary-and-errors|full]`
  - `200 application/json; charset=utf-8` with combined OTLP JSON.
  - `400` on invalid `granularity` or invalid `format`; `404` if session not found.
- `POST /event`
  - Existing JSON `{ level, source, msg }` and plain-text body behavior preserved.
  - Now also reads optional `traceparent` HTTP header and optional `traceId` / `spanId` JSON fields (JSON fields take precedence if valid; otherwise `traceparent` if valid; otherwise active session `traceId`/`rootSpanId`).
- `POST /span`
  - Accepts JSON `{ name (required non-blank), traceId?, spanId?, parentSpanId?, kind?, startMs?, endMs?, durationMs?, phase? ("start"|"end"), status? ("UNSET"|"OK"|"ERROR"), statusMessage?, attributes? }`.
  - Reads optional `traceparent` header as parent context (`traceId` + `parentSpanId`) when not explicitly specified in JSON.
  - Returns `200 application/json` `{ "ok": true, "traceId": "...", "spanId": "...", "parentSpanId": "..." }`.
  - Returns `400` on invalid JSON, missing/blank `name`, or malformed explicit `traceId`/`spanId`/`parentSpanId`.
- `POST /otlp/flush`
  - Accepts empty body or optional JSON `{ "selector"?: string }`.
  - If `selector` provided and not found, returns `404`.
  - Returns `200 application/json` with `FlushResult` JSON (`{ ok, enabled, enqueued, flushed, failed, deferred, remaining, skippedReason, lastError }`).

### 2.6 POSIX CLI, `kucode-tools` & `boot.html`
- **`PosixRuntimeConfig.buildProotEnvironment`**:
  - Adds optional `traceparent: String = ""` parameter; injects `TRACEPARENT` env var when valid non-blank. Preserves exact PATH ordering (user/SDK shims before `/usr/local/bin` and `/usr/bin`).
- **`kucode-logs`** (generated in `PosixRuntimeConfig.buildHostCliHelperScripts`):
  - `kucode-logs show [selector] [--ai|--raw|--json|--otlp] [--summary|--full]`
  - `kucode-logs export [selector] [destPath] [--otlp] [--summary|--full]` (default filename `kucode-session-<selector>-otlp.json` when `--otlp`)
  - `kucode-logs otlp-flush [selector]` — calls `POST /otlp/flush` and prints JSON result.
- **`kucode-cli.cjs` (`/usr/local/bin/kucode`)**:
  - `kucode build -- <cmd> [args...]`: preserves stripping of `KUCODE_ADMIN_TOKEN`/`KUCODE_ADMIN_PORT` from child env; reads `process.env.TRACEPARENT`, derives child `spanId` (preserving `traceId`), injects derived `TRACEPARENT` into child env; preserves existing `POST /event` start/end messages; posts completed `proot.command` span to `POST /span` with `traceparent` header and attributes `{ "kucode.command.tool": label, "process.exit.code": exitCode, "kucode.build.signal": signal }` (NEVER command arguments or env secrets).
  - `kucode android ...`: attaches `traceparent` header when `process.env.TRACEPARENT` is valid.
- **`kucode-tools` (`extension.js`, `acp-webview.js`, `acp-client.js`)**:
  - `extension.js`: adds `reportHostSpan(spanPayload, traceparent)` calling `POST /span`; keeps `kucode-android` boundary strictly intact.
  - `acp-webview.js`: records `acp.turn` span per prompt turn (`kucode.acp.agent`, `kucode.acp.stop_reason`, `kucode.acp.attachment_count`, redacted error message if failed; NEVER prompt text or file contents) and keeps application-scoped `getApplicationSetting()` untouched.
  - `acp-client.js`: passes valid `TRACEPARENT` to spawned ACP CLI and `terminal/create` child environments.
- **`BootHostBridge` & `boot.html`**:
  - `@JavascriptInterface` methods: `getOtlpConfig(): String`, `saveOtlpConfig(configJson: String): String`, `flushOtlpOutbox()`, `exportSessionReport(selector: String)` (existing) and `exportSessionReportWithFormat(selector: String, format: String)` (explicit distinct method name so WebView JS bridge has zero overload ambiguity).
  - `boot.html`: adds `<option value="otlp">OTLP JSON (OpenTelemetry)</option>` to `#sessionFormatSelect`, plus an OTLP Cloud Export Settings card (`enabled`, `endpointUrl`, `granularity`, `unmeteredOnly`, `authHeaderName`, `type="password"` `authHeaderValue`, `clearAuthHeader` checkbox, Save button, Send Now / Flush Outbox button, outbox status readout, `window.__onOtlpFlushResult` callback).

### 2.7 Phase 3 — Analyzer & Generic Triage CLI
- **`.agents/skills/ship/scripts/analyze-flight-recorder.mjs`**:
  - Preserves existing `analyzeSessionDir`, `parseKuCodeAiMarkdownBundle`, and `analyzeDiagnosticBundleFile` (`schema: 2`, `readOnly: true`).
  - Exports `parseOtlpJsonBundle(rawOrObj, sourceLabel)` and `filterOtlpSummaries(items, filters)` and `fetchOtlpJsonFromUrl(url, options)`:
    - Accepts single-signal OTLP JSON (`resourceSpans` / `resourceMetrics` / `resourceLogs`), combined `{ resourceSpans, resourceMetrics, resourceLogs }`, or JSON array of OTLP bundles.
    - Returns `schema: 2`, `readOnly: true`, `untrustedData: true`, `format: "otlp-json"`, `sessionId`, `traceId`, `buildSha`, `status`, `exitCategory`, `abnormal`, `appVersion`, `packageName`, `spansSummary` (`total`, `errorCount`, `errorSpans[]`, `slowestSpans[]`), `metricsSummary` (`snapshotCount`, `maxPssMb`, `minFreeDiskMb`, `lowMemoryEvents`, `serverDownSnapshots`, `thermalStatuses[]`), `alertsSummary` (`total`, `byLevel`, `bySource`, `recentAlerts[]`), `exitTraceHead[]`, `vscodeSublogErrors[]`, `omittedInfoLogsCount` (drops unbounded `INFO` logs).
  - CLI options:
    - `analyze-flight-recorder.mjs <path> [--session-id <id>] [--build-sha <sha>] [--abnormal-only]`
    - `analyze-flight-recorder.mjs --url <http(s)://...> [--header-env <ENV_NAME>] [--session-id <id>] [--build-sha <sha>] [--abnormal-only]`
    - Rejects non-`http://`/`https://` schemes (e.g., `file://`), bounds response to 10 MiB and 15s timeout, never echoes `--header-env` secret value.
- **`scripts/otlp-triage.mjs`**:
  - Thin executable Node CLI delegating to `analyze-flight-recorder.mjs` (`--url`, `<file|dir>`, `--session-id`, `--build-sha`, `--abnormal-only`, `--header-env`).

---

## 3. Layer / Ownership Plan (Wave 1 Parallel -> Wave 2 Sequential)

- **Wave 1-A (`otel-jvm-core`)**: `TraceContext.kt`, `SessionLogStore.kt`, `OtlpJsonSerializer.kt`, `OtlpOutboxExporter.kt`, `LocalDiagnosticsServer.kt`
- **Wave 1-B (`otel-cli-extension`)**: `kucode-cli.cjs`, `kucode-tools/extension.js`, `kucode-tools/acp-webview.js`, `kucode-tools/acp-client.js`
- **Wave 1-C (`otel-analyzer-cli`)**: `.agents/skills/ship/scripts/analyze-flight-recorder.mjs`, `scripts/otlp-triage.mjs`
- **Wave 2-A (`android-telemetry-wiring`)**: `AndroidOtlpPlatform.kt`, `PosixRuntimeConfig.kt`, `OpenVsCodeLauncher.kt`, `SessionTelemetryManager.kt`
- **Wave 2-B (`host-bridge-ui-wiring`)**: `PosixRuntimeManager.kt`, `BootHostBridge.kt`, `MainActivity.kt`, `boot.html`

---

## 4. Clean-Room Oracle Test Plan (`S3a_ORACLE_TEST_GEN`)

1. `android/app/src/test/java/dev/koduki/kucode/oracle/OtelFlightRecorderOracleTest.kt` (`AC-1`..`AC-6`, `AC-12`)
2. `android/app/src/test/java/dev/koduki/kucode/oracle/OtelFlightRecorderMediumOracleTest.kt` (`@Category(MediumTest::class)`, `AC-7`, `AC-8`)
3. `tests/oracle/otel-flight-recorder.oracle.test.cjs` (`AC-8`, `AC-9`, `AC-10`, `AC-11`, `AC-13`)
4. Physical XREAL AURA device gate (`AC-14`): explicitly recorded as `NOT RUN`.

---

## 5. Rollback Plan
- All OTLP cloud export behavior is default-OFF (`enabled = false`). Disabling the toggle immediately stops all outbound OTLP traffic while preserving local `kucode-session-v1` offline diagnostics.
- Full code rollback is a single git revert of the squash commit on `main`; existing `<filesDir>/diagnostics/sessions/*` directories remain readable by prior versions because `meta.json` and JSONL changes are strictly additive.
