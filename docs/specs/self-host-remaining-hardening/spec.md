# Feature Contract: AURA Self-Host Remaining Software Hardening (#25, #26, #21, #19, #22/#24)

- **Tracking Issues:** [#25](https://github.com/koduki/kucode/issues/25) (SH-08), [#26](https://github.com/koduki/kucode/issues/26) (SH-09), [#21](https://github.com/koduki/kucode/issues/21) (SH-04), [#19](https://github.com/koduki/kucode/issues/19) (SH-02), [#22](https://github.com/koduki/kucode/issues/22) (SH-05), [#24](https://github.com/koduki/kucode/issues/24) (SH-07)
- **Parent Spec / ADR:** `spec.md` §5, [ADR-0012](../../adr/0012-aura-self-host-standard-linux.md), [ADR-0013](../../adr/0013-otel-flight-recorder-and-opt-in-otlp-export.md)
- **Delivery Target:** `merge-only`
- **Allowed Protected Paths:** `[]` (zero protected files unlocked; `android/gradle.properties`, `android/app/build.gradle.kts`, `network_security_config.xml`, `proguard-rules.pro`, `runtime-assets.lock.json`, `scripts/guards/**`, `.github/workflows/**`, `.agents/**`, `LICENSES/**` remain untouched)

---

## 1. Goals & Non-Goals

### Goals
1. **#25 (SH-08 Flight Recorder Observability & Gradle Recommendations)**:
   - Surface explicit `App Version` and `Build SHA` in `DiagnosticsRunner.collect` and top-level `Build SHA` in `SessionLogStore` AI reports while keeping `kucode-session-v1` backward-compatible.
   - Instrument telemetry & diagnostics overhead (`captureDurationMs` in `SessionLogStore.MetricSnapshot`, `kucode.telemetry.capture_duration_ms` in `OtlpJsonSerializer`, `SessionTelemetryManager.getTelemetryOverheadStats()`, and `DiagnosticsRunner` duration / CPU delta / PSS delta / diagnostics disk usage) without losing `"unknown"` rendering for unavailable metrics.
   - Enhance `kucode-cli.cjs` (`sdk doctor`, `build --`) and `kucode-android/extension.js` (`recommendGradleOptions`, adaptive `gradleArgs`) to capture bounded SDK/toolchain version hints (`--versions` opt-in for subprocess probing; default `sdk doctor` performs zero subprocess execution) and RAM-aware Gradle parallelism/JVM recommendations without modifying `android/gradle.properties`.
2. **#26 (SH-09 Generic Linux Language Smoke Runner)**:
   - Extend `scripts/aura-language-smoke.sh` to support `--allow-non-aura` / `--ci-self-test` (marking `execution_mode="CI/host"` vs `"AURA"`), `--with-network` (opt-in DNS/HTTPS CA probe; default remains offline/loopback-safe), per-target toolchain version capture, structured 5-column `results.tsv`, and `summary.json`.
   - Add automated Node test coverage in `tests/aura-language-smoke.test.cjs` verifying flag handling, `NOT RUN` vs `PASS`/`FAIL`, version reporting, and `summary.json` / `results.tsv` output.
3. **#21 (SH-04 SDK/PATH Consistency & Workspace Trust)**:
   - Align `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `XDG_*`, and user-precedent `PATH` across `PosixRuntimeConfig.buildProotEnvironment`, `/etc/profile.d/kucode-env.sh`, and `kucode-tools/acp-client.js` (`buildChildEnvironment` used by both `connect()` and `_handleTerminalCreate()`).
   - Enforce `vscode.workspace.isTrusted` checks in `kucode-tools/extension.js` (`setupAntigravityCliCommand`, `setupCopilotCliCommand`) and `acp-webview.js` (`openInTerminal`), and add untrusted-workspace test coverage in `tests/kucode-android.test.cjs` and `PosixRuntimeConfigTest.kt`.
4. **#19 (SH-02 Persistent Debian Preflight, Backup Retention & Recovery)**:
   - Preserve existing `/root` + `PRoot -0` fake-root `apt` compatibility per `docs/aura-self-host-acceptance.md`.
   - Add pre-extraction free-space preflight (`InsufficientStorageException`) in `RuntimeInstaller.ensureExtracted` so low-space failures leave existing `rootfs`, `openvscode`, `home`, and `workspace` untouched without leaving partial staging artifacts.
   - Add bounded Debian reset backup retention (`maxDebianBackups = 3`, `pruneOldDebianBackups`) and ensure `restoreLatestDebianBackup` archives current rootfs without premature pruning before restoring.
5. **#22 / #24 (Minor Host Bridge & Diagnostic Share Hardening)**:
   - Extract `MainActivity.handleDiagnosticShare` stream import validation into pure-JVM `DiagnosticShareImporter` (1 MiB size limit, `NOFOLLOW_LINKS` / symlink rejection, canonical containment under `/workspace`, `LogRedactor.redact`) with unit tests.
   - Export and harden `kucode-android/extension.js` `installApk` for terminal failures (`failed`, `timed_out`, `interrupted`, `cancelled`) and poll timeout cancellation, plus unit-test `AndroidHostBridgeCore.cleanupAbandonedOnStartup()`.

### Non-Goals
- Migrating PRoot `-0` / `/root` to a non-root Linux user (deferred per S1 decision to avoid breaking `apt` compatibility prior to AURA device validation).
- Modifying `android/gradle.properties`, `.github/workflows/**`, `runtime-assets.lock.json`, or any protected file.
- Claiming XREAL AURA physical-device verification from CI/host JVM/Node tests (`AC-12` MUST remain `NOT RUN`).

---

## 2. Normative Public Contracts & Interfaces

### 2.1 `kucode-cli.cjs` (`android/app/src/main/assets/kucode-cli.cjs`)
- Exports: `{ findTool, main, build, parseTraceParent, deriveChildTraceParent, recommendGradleOptions, collectSdkVersionHints }`.
- **`recommendGradleOptions(memOverride?)`**:
  - `memOverride` optional `{ totalMb?: number, availableMb?: number, source?: string }`.
  - When omitted, reads `/proc/meminfo` (`MemTotal`, `MemAvailable` in kB -> integer MiB), falling back to `os.totalmem()` / `os.freemem()` (`source: 'proc-meminfo' | 'os' | 'unknown'`).
  - Tier classification:
    - `low`: `totalMb > 0 && (totalMb < 4096 || (availableMb >= 0 && availableMb < 2048))` -> `maxWorkers: 1`, `jvmArgs: '-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8'`.
    - `high`: `totalMb >= 12288 && (availableMb < 0 || availableMb >= 6144)` -> `maxWorkers: 3`, `jvmArgs: '-Xmx3072m -Dfile.encoding=UTF-8'`.
    - `standard`: otherwise -> `maxWorkers: 2`, `jvmArgs: '-Xmx2048m -Dfile.encoding=UTF-8'`.
  - Returns `{ totalMb, availableMb, source, tier, maxWorkers, jvmArgs, flags: ['--no-daemon', '--max-workers=' + maxWorkers], gradleProperty: 'org.gradle.jvmargs=' + jvmArgs, modifiesGradleProperties: false }`.
- **`collectSdkVersionHints(cwd = process.cwd(), env = process.env)`**:
  - Strictly zero subprocess execution.
  - Reads bounded metadata (`<= 64 KiB` per file):
    - `javaVersion`: parsed `JAVA_VERSION` from `$JAVA_HOME/release` (if `env.JAVA_HOME` is an absolute path).
    - `gradleVersion`: parsed `distributionUrl` version token from `<cwd>/gradle/wrapper/gradle-wrapper.properties` or `<cwd>/android/gradle/wrapper/gradle-wrapper.properties`.
    - `androidPlatforms`: comma-joined directory names from `androidPlatforms()` (up to 4).
    - `androidBuildTools`: comma-joined build-tools versions from `Object.keys(androidBuildTools())` (up to 4).
    - `buildSha`: `env.KUCODE_BUILD_SHA` if matching `/^[0-9A-Za-z._-]{4,64}$/`.
  - Returns object containing only present non-empty string fields (each `<= 128` chars).
- **`main(['sdk', 'doctor', ...flags])`**:
  - Default (no `--versions` and no `--probe-versions`): `probedVersions: false`, **zero `execFileSync`/`spawn` calls**. Populates `{ version: 1, architecture, buildTools, platforms, home, androidHome, javaHome, tools, toolVersions, probedVersions, memory, gradleRecommendation }`. Static `toolVersions` includes `java` (from `$JAVA_HOME/release` if present) and `node` (`process.version` if `tools.node?.real === process.execPath`).
  - With `--versions` or `--probe-versions`: sets `probedVersions: true` and runs `execFileSync(tool.real, args, { cwd: os.tmpdir(), timeout: 1500, maxBuffer: 32768, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })` (total probe budget `<= 8000` ms) ONLY for allowlisted tools found in `tools` with `abi !== 'non-aarch64-elf'` (`java -version`, `javac -version`, `kotlinc -version`, `gradle --version`, `node -v`, `npm -v`, `tsc --version`, `python3 --version`, `uv --version`, `go version`, `ruby -v`, `bundle --version`, `cargo --version`, `gcc --version`, `g++ --version`, `clang --version`, `cmake --version`, `git --version`, `mise --version`). Errors/timeouts never throw; missing/failed probes stay omitted or `'probe-failed'`. Version strings are sanitized to single-line `<= 160` chars.
  - Exit code: `0` iff `process.arch === 'arm64'` and every discovered tool/buildTool has `abi !== 'non-aarch64-elf'`, else `1`.
- **`build(args)`**:
  - Preserves stripping `KUCODE_ADMIN_TOKEN` / `KUCODE_ADMIN_PORT`, deriving child `TRACEPARENT`, posting `/event` `start`/`end`, and never logging `args.slice(2)` or secrets.
  - Merges non-empty `collectSdkVersionHints(process.cwd(), process.env)` into `proot.command` span `attributes`: `kucode.sdk.java_version`, `kucode.sdk.gradle_version`, `kucode.sdk.android_platforms`, `kucode.sdk.android_build_tools`, `kucode.build.sha`.

### 2.2 `kucode-android/extension.js` (`android/app/src/main/assets/kucode-android/extension.js`)
- Module boundary invariant: zero references to `kucode-tools`.
- Exports `{ activate, deactivate, androidProject, findAndroidRoots, gradleArgs, recommendGradleOptions, cli, installApk, launchWithRetry }`.
- **`recommendGradleOptions(envVars = process.env, options = {})`**:
  - Accepts `options.memInfo` or `envVars.KUCODE_GRADLE_TOTAL_MEM_MB` / `KUCODE_GRADLE_AVAIL_MEM_MB`, otherwise delegates to `/proc/meminfo` -> `os`. Same `low`/`standard`/`high` thresholds as `kucode-cli.cjs`.
- **`gradleArgs(tasks, envVars = process.env, options = {})`**:
  - Preserves exact output `['--no-daemon', '--max-workers=2', ...tasks]` when called as `gradleArgs(['assembleDebug'], {})` on standard/CI hosts (`totalMb >= 4096` without explicit auto-tune override).
  - Respects explicit flags already in `tasks` (`--no-daemon`, `--daemon`, `--max-workers`, `-Dorg.gradle.jvmargs=`), `KUCODE_GRADLE_MAX_WORKERS` (`1..16`), `KUCODE_GRADLE_JVMARGS`, `GRADLE_OPTS`.
  - When `rec.tier === 'low'` OR (`(options.memInfo || envVars.KUCODE_GRADLE_TOTAL_MEM_MB || envVars.KUCODE_GRADLE_AUTO_TUNE === '1')` and `rec.tier !== 'standard'`): emits `--max-workers=<rec.maxWorkers>` and `-Dorg.gradle.jvmargs=<rec.jvmArgs>` (unless overridden by `tasks` / `KUCODE_GRADLE_JVMARGS` / `GRADLE_OPTS`). Never writes `gradle.properties`.
- **`installApk(project, { runCli = cli, pollIntervalMs = 1000, maxPolls = 960 } = {})`**:
  - Returns `null` if file dialog is cancelled or `token.isCancellationRequested` (calling `['android', 'cancel', op.operationId]`).
  - Returns `result.packageName` when `result.state === 'succeeded'`.
  - Throws `Error` when `state` is `'failed'`, `'timed_out'`, `'interrupted'`, `'cancelled'`, or poll count exceeds `maxPolls` (attempting best-effort `['android', 'cancel', op.operationId]` on poll timeout).
- **Workspace Trust**:
  - When `vscode.workspace.isTrusted === false`, `activate()` sets `kucodeAndroid.project = false`, `provideTasks()` returns `[]`, and `chooseRoot()` / `task()` reject with `'Trust this workspace before running development tasks.'` / `'Workspace is not trusted.'`.
  - `doctor` and `diagnostics` commands run `cli(['sdk', 'doctor', '--versions'], undefined, { timeoutMs: 20000 })`.

### 2.3 Flight Recorder & Diagnostics Overhead (`SessionLogStore`, `OtlpJsonSerializer`, `SessionTelemetryManager`, `DiagnosticsRunner`)
- **`SessionLogStore.MetricSnapshot`**:
  - Adds `val captureDurationMs: Long = -1L` at the end of constructor (backward-compatible default).
  - `toJsonObject()` includes `"captureDurationMs": captureDurationMs`; `fromJsonObject()` defaults missing to `-1L`.
- **`OtlpJsonSerializer`**:
  - Emits gauge metric `kucode.telemetry.capture_duration_ms` (`unit: "ms"`) when `captureDurationMs >= 0L`; omits when `< 0L`.
- **`SessionLogStore.buildAiMarkdownReportInternal`**:
  - Preserves `SCHEMA_VERSION = "kucode-session-v1"` and exact 14-column Section 2 metrics table header.
  - Emits `- Build SHA: \`${summary.buildSha}\`` in the session metadata header.
  - In Section 2, when `>= 1` snapshot has `captureDurationMs >= 0L`, emits `- Telemetry Capture Overhead: \`last=<last>ms, avg=<avg>ms, max=<max>ms, samples=<n>\``.
- **`SessionTelemetryManager`**:
  - `data class TelemetryOverheadStats(val sampleCount: Long, val lastCaptureDurationMs: Long, val maxCaptureDurationMs: Long, val totalCaptureDurationMs: Long)`
  - `fun getTelemetryOverheadStats(): TelemetryOverheadStats`
  - `buildMetricSnapshot` times capture via `System.nanoTime()` and updates atomic overhead stats; records `captureDurationMs` on every `MetricSnapshot`.
- **`DiagnosticsRunner`**:
  - `data class DiagnosticsOverhead(val diagnosticsDurationMs: Long, val hostCpuDeltaMs: Long, val pssDeltaMb: Long?, val diagnosticsDiskBytes: Long, val telemetryLastCaptureDurationMs: Long = -1L)`
  - Pure-JVM helpers:
    - `fun computeDirectorySizeBytes(dir: File): Long` (recursive sum skipping symlinks via `NOFOLLOW_LINKS`, returning `-1L` if missing/symlink/unreadable).
    - `fun computeDiagnosticsOverhead(startElapsedMs: Long, endElapsedMs: Long, startCpuMs: Long, endCpuMs: Long, startPssMb: Long, endPssMb: Long, diagnosticsDiskBytes: Long, telemetryLastCaptureDurationMs: Long = -1L): DiagnosticsOverhead` (`pssDeltaMb` is `null` if either input `< 0L`; `hostCpuDeltaMs` is `-1L` if either `< 0L` else `maxOf(0L, endCpuMs - startCpuMs)`).
    - `fun formatOverheadSummary(overhead: DiagnosticsOverhead): String` (unavailable metrics render literally as `"unknown"`).
  - `collect(context, runtime)` includes `"App Version"`, `"Build SHA"`, `"Diagnostics Overhead"`, and `"Diagnostics Disk"` in `deviceInfo`, wraps the run in a `"diagnostics.collect"` span, and handles `diagnostics_end` / `diagnostics_error_end` in `finally` without duplicate `diagnostics_end` snapshots.

### 2.4 `scripts/aura-language-smoke.sh` (#26)
- CLI flags:
  - `--allow-non-aura` / `--ci-self-test`: bypasses non-ARM64 `exit 2` abort and sets `execution_mode="CI/host"` (default on `aarch64`/`arm64` is `"AURA"`, and non-ARM64 without this flag exits `2` with `NOT RUN: AURA native ARM64 is required.`).
  - `--with-network` (or `KUCODE_SMOKE_NETWORK=1`): sets `network_mode="with-network"` and runs an external DNS/HTTPS smoke target (`network`) against `KUCODE_SMOKE_NETWORK_URL` (default `https://deb.debian.org/`). Default `network_mode="offline"` runs zero external network calls (Python fixture uses `127.0.0.1` loopback only).
  - `--output <dir>` / `--output=<dir>` or positional `[out_dir]`; unknown `--*` flags exit `2`.
- Output files in `<out_dir>`:
  - `results.tsv`: header `test\tresult\tduration_seconds\tversion\texecution_mode`; `version` is single-line sanitized (tabs/newlines stripped, `"not-installed"` when tool missing); missing tools record `NOT RUN` and cause non-zero exit (`1`).
  - `summary.json`: `{ schema: 1, utc, architecture, executionMode, networkMode, exitCode, counts: { pass, fail, notRun, total }, results: [{ test, result, durationSeconds, version, executionMode, logFile }] }`.
  - `environment.txt` and per-target `<test>.log`.

### 2.5 SDK/PATH Consistency & Workspace Trust (#21)
- **`PosixRuntimeConfig`**:
  - `fun detectGuestJavaHome(paths: Paths): String?` (pure-JVM; checks `rootfsDir/usr/lib/jvm/default-java` with symlink confinement within `rootfsDir`, `rootfsDir/usr/lib/jvm/*`, and `homeDir/.local/share/mise/installs/java/*`, returning guest POSIX path like `/usr/lib/jvm/default-java` or `/root/.local/share/mise/installs/java/<ver>` when `bin/java` exists).
  - `buildProotEnvironment(..., javaHome: String? = detectGuestJavaHome(paths))`: sets `JAVA_HOME` when non-blank valid POSIX path and places `"$javaHome/bin"` after user/SDK directories and before `/usr/local/sbin`.
  - `buildProfileDEnvScript()`: maintains parity with `buildProotEnvironment` for `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `XDG_*`, `JAVA_HOME`, and user-precedent `PATH`.
- **`kucode-tools/acp-client.js`**:
  - Exports `detectJavaHome(envVars, options)` and `buildChildEnvironment(baseEnv, extraEnv)`.
  - `buildChildEnvironment` ensures `HOME`, `XDG_CACHE_HOME`, `XDG_CONFIG_HOME`, `XDG_DATA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `JAVA_HOME` (when detectable), user-precedent `PATH`, and `TRACEPARENT` are aligned across `connect()` and `_handleTerminalCreate()`.
- **`kucode-tools/extension.js` & `acp-webview.js`**:
  - `setupAntigravityCliCommand`, `setupCopilotCliCommand`, and `AcpChatViewProvider.openInTerminal` reject/return early without spawning terminals or running commands when `vscode.workspace.isTrusted === false`. Module boundary: zero references to `kucode-android`.

### 2.6 Persistent Debian Preflight, Backup Retention & Recovery (#19)
- **`RuntimeInstaller`**:
  - `class InsufficientStorageException(val requiredBytes: Long, val availableBytes: Long, val target: String) : IOException(...)`
  - Constructor params (defaulted for full backward compatibility): `usableSpaceBytes: (File) -> Long = { it.usableSpace }`, `minRootfsFreeBytes: Long = 512L * 1024L * 1024L`, `minOpenvscodeFreeBytes: Long = 512L * 1024L * 1024L`, `maxDebianBackups: Int = 3`.
  - `ensureExtracted(paths)` checks `usableSpaceBytes(paths.runtimeRoot)` before extracting `rootfs` and before extracting `openvscode`; throws `InsufficientStorageException` without creating staging directories or mutating `rootfsDir`, `openvscodeDir`, `homeDir`, or `workspaceDir`.
  - `pruneOldDebianBackups(paths, keepCount = maxDebianBackups, preserveDir: File? = null): List<File>` prunes oldest `rootfs-backup-*` directories in `runtimeRoot` sorted by `lastModified` desc then `name` desc via `deleteTreeNoFollow`, never touching `homeDir` or `workspaceDir`.
  - `archiveDebianForReset(paths, pruneBackups = true)` uses `<epochMs>-<seq>` suffix and prunes when `pruneBackups = true`; `restoreLatestDebianBackup(paths)` archives current rootfs with `pruneBackups = false`, moves selected backup to `rootfsDir`, and then calls `pruneOldDebianBackups`.

### 2.7 Diagnostic Share Import & Host Bridge Cleanup (#22, #24)
- **`DiagnosticShareImporter` (`dev.koduki.kucode.DiagnosticShareImporter`, pure-JVM)**:
  - `const val MIME_TYPE = "application/vnd.kucode.diagnostic"`
  - `const val IMPORT_DIR_NAME = "diagnostics-import"`
  - `const val MAX_IMPORT_BYTES = 1024 * 1024`
  - `data class ImportResult(val file: File, val guestPath: String)`
  - `fun isValidShareRequest(action: String?, mimeType: String?, uriScheme: String?): Boolean`
  - `fun importFromStream(input: InputStream, workspaceDir: File, maxBytes: Int = MAX_IMPORT_BYTES, idGenerator: () -> String = { UUID.randomUUID().toString() }): ImportResult` — rejects payloads `> maxBytes`, rejects symlinks on `workspaceDir`, `diagnostics-import`, and target (`NOFOLLOW_LINKS`), verifies canonical containment within `workspaceDir`, redacts via `LogRedactor.redact`, writes atomically to `import-<safeId>.md`.

---

## 3. Layer / Ownership Plan (Wave 1 Parallel -> Wave 2 Sequential)

- **Wave 1-A (`wave1-jvm-core`, `complex` / `inherit`)**: `RuntimeInstaller.kt`, `PosixRuntimeConfig.kt`, `DiagnosticShareImporter.kt`, `RuntimeInstallerTest.kt`, `PosixRuntimeConfigTest.kt`, `AndroidInstallPolicyTest.kt`, `DiagnosticShareImporterTest.kt`
- **Wave 1-B (`wave1-observability-jvm`, `complex` / `inherit`)**: `SessionLogStore.kt`, `OtlpJsonSerializer.kt`, `SessionTelemetryManager.kt`, `DiagnosticsRunner.kt`, `DiagnosticsRunnerTest.kt`, `SessionLogStoreTest.kt`
- **Wave 1-C (`wave1-cli-ext-smoke`, `complex` / `inherit`)**: `kucode-cli.cjs`, `kucode-android/extension.js`, `kucode-tools/extension.js`, `kucode-tools/acp-client.js`, `kucode-tools/acp-webview.js`, `scripts/aura-language-smoke.sh`, `tests/kucode-android.test.cjs`, `tests/kucode-extension.test.cjs`, `tests/kucode-acp.test.cjs`, `tests/aura-language-smoke.test.cjs`
- **Wave 2 (`wave2-host-wiring`, `standard` / `flash`)**: `MainActivity.kt`, `scripts/aura-self-host-e2e.sh`

---

## 4. Clean-Room Oracle Test Plan (`S3a_ORACLE_TEST_GEN`)

1. `android/app/src/test/java/dev/koduki/kucode/oracle/SelfHostHardeningOracleTest.kt` (`AC-1`, `AC-2`, `AC-6`, `AC-8`, `AC-9`, `AC-10`, `AC-11`)
2. `tests/oracle/self-host-hardening.oracle.test.cjs` (`AC-3`, `AC-4`, `AC-5`, `AC-7`, `AC-11`)
3. Physical XREAL AURA real-device gates (`AC-12`): explicitly recorded as `NOT RUN`.

---

## 5. Rollback Plan
- All `gradle.properties` defaults, `kucode-session-v1` schema compatibility, offline smoke defaults, and `/root` + `PRoot -0` runtime layout remain intact; full rollback is a single git revert of the squash commit on `main`.
