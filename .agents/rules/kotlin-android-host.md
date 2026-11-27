---
trigger: glob
globs: "android/app/src/**/*.kt, android/app/build.gradle.kts"
description: "Architectural, security, and testability rules for KuCode's Android Kotlin host layer."
---

# Kotlin / Android Host Rules

1. **Pure-JVM Core Split**: Keep deterministic business logic, policy checks, parsing, redaction, and state machines in pure-JVM classes/objects (like `PosixRuntimeConfig`, `AndroidHostBridgeCore`, `LogRedactor`, `SessionLogStore`) so they run fast under `:app:testDebugUnitTest` without Android framework mocks.
2. **Android 15 / XR Invariants**:
   - Maintain `targetSdk = 35`, `abiFilters += listOf("arm64-v8a")`, `useLegacyPackaging = true`, and `noCompress += listOf("tgz", "tar", "xz")`.
   - Never introduce Termux GPL source code or non-public/root/ADB-required APIs for core flows (ADR-0001, ADR-0012).
3. **Security & Isolation**:
   - Enforce `NOFOLLOW_LINKS` on all `/workspace` and `/workspace/diagnostics-import` filesystem operations.
   - Apply `LogRedactor.redact(...)` before persisting or exporting any telemetry, logcat, or diagnostic share payload.
   - Keep `LocalDiagnosticsServer` loopback-only (`127.0.0.1`) with constant-time `X-KuCode-Token` comparison and CSRF/body-size guards.
   - Block Stable (`dev.koduki.kucode`) self-install/overwrite in `AndroidHostBridge`; keep Stable and Dev (`.debug`) notification IDs, ports, and provider authorities strictly isolated.
4. **Persistence & Recovery**:
   - Normal APK updates must preserve persistent Debian `runtime/rootfs`, `home`, and `workspace`. Clean up `staging` directories in `finally` blocks and verify backups exist before stopping services on restore.
5. **Test Discipline**:
   - Mark subprocess/socket/archive tests with `@Category(MediumTest::class)`.
   - Never add `@Ignore` or new `@Suppress` annotations to hide failures.
6. **`@JavascriptInterface` Sync-Read vs I/O-Lock Separation**:
   - UI thread and `@JavascriptInterface` synchronous readers must NEVER acquire blocking disk or network I/O locks.
   - Keep fast in-memory state/snapshot locks strictly separate from blocking I/O locks in an acyclic lock DAG (never hold a UI/bridge state lock across disk/network I/O, and prefer atomic/immutable snapshots or CAS/monotonic ordering for cross-thread reads).
