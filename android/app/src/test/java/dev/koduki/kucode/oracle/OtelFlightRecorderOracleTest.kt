package dev.koduki.kucode.oracle

import dev.koduki.kucode.KeyValueStorage
import dev.koduki.kucode.LogRedactor
import dev.koduki.kucode.OtlpConfigStore
import dev.koduki.kucode.OtlpEndpointValidator
import dev.koduki.kucode.OtlpHttpClient
import dev.koduki.kucode.OtlpHttpResponse
import dev.koduki.kucode.OtlpJsonSerializer
import dev.koduki.kucode.OtlpOutboxExporter
import dev.koduki.kucode.OtlpSecretCipher
import dev.koduki.kucode.PosixRuntimeConfig
import dev.koduki.kucode.SessionLogStore
import dev.koduki.kucode.TraceContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Clean-room spec-derived unit oracle tests for Issue #36 (OTel Flight Recorder & Opt-In OTLP Export).
 *
 * Covers AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, and AC-12 strictly from docs/specs/otel-flight-recorder/spec.md.
 */
class OtelFlightRecorderOracleTest {

    @After
    fun tearDown() {
        LogRedactor.clearDynamicSecrets()
    }

    private fun deleteRecursivelyNoFollow(target: File) {
        if (!target.exists() && !Files.isSymbolicLink(target.toPath())) return
        if (Files.isDirectory(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            target.listFiles()?.forEach { deleteRecursivelyNoFollow(it) }
        }
        Files.deleteIfExists(target.toPath())
    }

    private fun sampleEnv(buildSha: String = "abc1234") = SessionLogStore.SessionEnvMetadata(
        appVersion = "0.10.0-debug (code=11, targetSdk=35)",
        packageName = "dev.koduki.kucode.debug",
        deviceSummary = "XREAL AURA (test)",
        androidSummary = "15 (API 35)",
        webviewVersion = "com.google.android.webview 157.0.0.0",
        pageSize = "16384B (16KB)",
        xrSpatialSupported = true,
        hasAllFilesAccess = true,
        notificationsEnabled = true,
        batteryOptIgnored = true,
        buildSha = buildSha,
    )

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private class InMemoryKeyValueStorage : KeyValueStorage {
        val strings = linkedMapOf<String, String>()
        val booleans = linkedMapOf<String, Boolean>()

        override fun getString(key: String): String? = strings[key]

        override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default

        override fun putString(key: String, value: String?) {
            if (value == null) strings.remove(key) else strings[key] = value
        }

        override fun putBoolean(key: String, value: Boolean) {
            booleans[key] = value
        }
    }

    private class FakePrefixSecretCipher : OtlpSecretCipher {
        override fun encrypt(plaintext: String): String =
            "CIPHER_V1:" + plaintext.reversed()

        override fun decrypt(ciphertext: String): String? =
            if (ciphertext.startsWith("CIPHER_V1:")) ciphertext.removePrefix("CIPHER_V1:").reversed() else null
    }

    private fun findAttrValue(attributes: JSONArray, key: String): JSONObject? {
        for (i in 0 until attributes.length()) {
            val item = attributes.optJSONObject(i) ?: continue
            if (item.optString("key") == key) {
                return item.optJSONObject("value")
            }
        }
        return null
    }

    @Test
    fun ac1_traceContextValidationParsingAndSessionBackwardCompatibility() {
        // [AC-1] TraceContext generation, validation, deterministic fallback, and W3C traceparent parsing
        val t1 = TraceContext.newTraceId()
        val t2 = TraceContext.newTraceId()
        assertTrue(TraceContext.isValidTraceId(t1))
        assertTrue(TraceContext.isValidTraceId(t2))
        assertNotEquals(t1, t2)
        assertNotEquals("0".repeat(32), t1)

        val s1 = TraceContext.newSpanId()
        val s2 = TraceContext.newSpanId()
        assertTrue(TraceContext.isValidSpanId(s1))
        assertTrue(TraceContext.isValidSpanId(s2))
        assertNotEquals(s1, s2)
        assertNotEquals("0".repeat(16), s1)

        assertFalse(TraceContext.isValidTraceId(null))
        assertFalse(TraceContext.isValidTraceId(""))
        assertFalse(TraceContext.isValidTraceId("0".repeat(32)))
        assertFalse(TraceContext.isValidTraceId("a".repeat(31)))
        assertFalse(TraceContext.isValidTraceId("a".repeat(33)))
        assertFalse(TraceContext.isValidTraceId("A".repeat(32)))
        assertFalse(TraceContext.isValidTraceId("g".repeat(32)))

        assertFalse(TraceContext.isValidSpanId(null))
        assertFalse(TraceContext.isValidSpanId(""))
        assertFalse(TraceContext.isValidSpanId("0".repeat(16)))
        assertFalse(TraceContext.isValidSpanId("b".repeat(15)))
        assertFalse(TraceContext.isValidSpanId("b".repeat(17)))
        assertFalse(TraceContext.isValidSpanId("B".repeat(16)))
        assertFalse(TraceContext.isValidSpanId("z".repeat(16)))

        val legacySessionId = "20261125-120000000-0001-abcd"
        val detTrace = TraceContext.deterministicTraceId(legacySessionId)
        val detSpan = TraceContext.deterministicSpanId(legacySessionId)
        assertEquals(sha256Hex("kucode-trace:$legacySessionId").take(32), detTrace)
        assertEquals(sha256Hex("kucode-span:$legacySessionId").take(16), detSpan)
        assertTrue(TraceContext.isValidTraceId(detTrace))
        assertTrue(TraceContext.isValidSpanId(detSpan))
        assertEquals(detTrace, TraceContext.deterministicTraceId(legacySessionId))
        assertNotEquals(detTrace, TraceContext.deterministicTraceId("20261125-120000001-0002-beef"))

        val formatted = TraceContext.formatTraceParent(t1, s1)
        assertEquals("00-$t1-$s1-01", formatted)
        assertEquals("00-$t1-$s1-00", TraceContext.formatTraceParent(t1, s1, "00"))

        var threwOnBadTrace = false
        try {
            TraceContext.formatTraceParent("0".repeat(32), s1)
        } catch (_: IllegalArgumentException) {
            threwOnBadTrace = true
        }
        assertTrue("formatTraceParent must throw IllegalArgumentException on all-zero traceId", threwOnBadTrace)

        var threwOnBadSpan = false
        try {
            TraceContext.formatTraceParent(t1, "bad")
        } catch (_: IllegalArgumentException) {
            threwOnBadSpan = true
        }
        assertTrue("formatTraceParent must throw IllegalArgumentException on invalid spanId", threwOnBadSpan)

        val parsedUpper = TraceContext.parseTraceParent("  00-${t1.uppercase()}-${s1.uppercase()}-01  ")
        assertNotNull(parsedUpper)
        assertEquals("00", parsedUpper!!.version)
        assertEquals(t1, parsedUpper.traceId)
        assertEquals(s1, parsedUpper.parentSpanId)
        assertEquals("01", parsedUpper.traceFlags)

        assertNull(TraceContext.parseTraceParent(null))
        assertNull(TraceContext.parseTraceParent(""))
        assertNull(TraceContext.parseTraceParent("ff-$t1-$s1-01"))
        assertNull(TraceContext.parseTraceParent("00-${"0".repeat(32)}-$s1-01"))
        assertNull(TraceContext.parseTraceParent("00-$t1-${"0".repeat(16)}-01"))
        assertNull(TraceContext.parseTraceParent("00-${t1.dropLast(1)}-$s1-01"))
        assertNull(TraceContext.parseTraceParent("00-$t1-$s1"))
        assertNull(TraceContext.parseTraceParent("00-gggggggggggggggggggggggggggggggg-$s1-01"))

        // [AC-1] SessionLogStore schema backward compatibility & legacy deterministic fallback
        val dir = Files.createTempDirectory("kucode-oracle-ac1").toFile()
        var nowMs = 1_760_000_000_000L
        try {
            val diagDir = File(dir, "diagnostics")
            val store = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            assertEquals("kucode-session-v1", SessionLogStore.SCHEMA_VERSION)

            // Default SessionEnvMetadata compatibility (without explicit buildSha)
            val defaultEnv = SessionLogStore.SessionEnvMetadata(deviceSummary = "Legacy Caller")
            assertEquals("unknown", defaultEnv.buildSha)

            val started = store.startSession(pid = 2100, env = sampleEnv(buildSha = "feedface99"))
            assertEquals("feedface99", started.buildSha)
            assertTrue(TraceContext.isValidTraceId(started.traceId))
            assertTrue(TraceContext.isValidSpanId(started.rootSpanId))
            assertEquals("00-${started.traceId}-${started.rootSpanId}-01", store.currentTraceParent())

            val metaJson = JSONObject(File(diagDir, "sessions/${started.sessionId}/meta.json").readText())
            assertEquals("kucode-session-v1", metaJson.getString("schemaVersion"))
            assertEquals("feedface99", metaJson.getString("buildSha"))
            assertEquals(started.traceId, metaJson.getString("traceId"))
            assertEquals(started.rootSpanId, metaJson.getString("rootSpanId"))

            val customTrace = TraceContext.newTraceId()
            val customSpan = TraceContext.newSpanId()
            store.recordEvent(SessionLogStore.EventLevel.INFO, "test.default", "default trace event")
            store.recordEvent(
                SessionLogStore.EventLevel.WARN,
                "test.custom",
                "custom trace event",
                traceId = customTrace,
                spanId = customSpan,
            )
            nowMs += 1_000L
            store.endSession("clean")
            assertNull(store.currentTraceParent())

            val jsonBundle = JSONObject(store.renderSessionBundle(started.sessionId, "json")!!)
            val events = jsonBundle.getJSONArray("events")
            val customEv = (0 until events.length())
                .map { events.getJSONObject(it) }
                .first { it.getString("source") == "test.custom" }
            assertEquals(customTrace, customEv.getString("traceId"))
            assertEquals(customSpan, customEv.getString("spanId"))

            // Legacy kucode-session-v1 directory on disk lacking buildSha, traceId, rootSpanId, and spans.jsonl
            val legacyDir = File(diagDir, "sessions/$legacySessionId").apply { mkdirs() }
            File(legacyDir, "meta.json").writeText(
                JSONObject()
                    .put("schemaVersion", "kucode-session-v1")
                    .put("sessionId", legacySessionId)
                    .put("pid", 999)
                    .put("status", "ENDED_CLEANLY")
                    .put("exitCategory", "CLEAN")
                    .put("exitSummary", "legacy clean exit")
                    .put("startedAtMs", 1_750_000_000_000L)
                    .put("updatedAtMs", 1_750_000_005_000L)
                    .put("endedAtMs", 1_750_000_005_000L)
                    .put("appVersion", "0.9.0")
                    .put("packageName", "dev.koduki.kucode")
                    .toString(2),
            )
            File(legacyDir, "events.head.jsonl").writeText(
                JSONObject()
                    .put("seq", 1)
                    .put("tsMs", 1_750_000_001_000L)
                    .put("offsetMs", 1_000L)
                    .put("level", "ERROR")
                    .put("source", "legacy.src")
                    .put("msg", "legacy alert message")
                    .toString() + "\n",
            )
            File(legacyDir, "events.alerts.jsonl").writeText(
                JSONObject()
                    .put("seq", 1)
                    .put("tsMs", 1_750_000_001_000L)
                    .put("offsetMs", 1_000L)
                    .put("level", "ERROR")
                    .put("source", "legacy.src")
                    .put("msg", "legacy alert message")
                    .toString() + "\n",
            )

            val legacySummary = store.resolveSessionSummary(legacySessionId)
            assertNotNull(legacySummary)
            assertEquals("unknown", legacySummary!!.buildSha)
            assertEquals("", legacySummary.traceId)
            assertEquals("", legacySummary.rootSpanId)
            assertTrue(store.renderSessionBundle(legacySessionId, "ai")!!.contains(legacySessionId))

            val legacyPayloads = OtlpJsonSerializer.serializeSession(store, legacySessionId)
            assertNotNull(legacyPayloads)
            val spans = legacyPayloads!!.tracesJson
                .getJSONArray("resourceSpans").getJSONObject(0)
                .getJSONArray("scopeSpans").getJSONObject(0)
                .getJSONArray("spans")
            assertEquals(1, spans.length())
            val rootSpan = spans.getJSONObject(0)
            assertEquals("kucode.session", rootSpan.getString("name"))
            assertEquals(detTrace, rootSpan.getString("traceId"))
            assertEquals(detSpan, rootSpan.getString("spanId"))

            val logRecord = legacyPayloads.logsJson!!
                .getJSONArray("resourceLogs").getJSONObject(0)
                .getJSONArray("scopeLogs").getJSONObject(0)
                .getJSONArray("logRecords").getJSONObject(0)
            assertEquals(detTrace, logRecord.getString("traceId"))
            assertEquals(detSpan, logRecord.getString("spanId"))
        } finally {
            deleteRecursivelyNoFollow(dir)
        }
    }

    @Test
    fun ac2_spanLifecycleUncaughtCrashAndApplicationExitInfoReconciliation() {
        // [AC-2] SpanRecord lifecycle, crash ERROR finalization, and ApplicationExitInfo reconciliation
        val dir = Files.createTempDirectory("kucode-oracle-ac2").toFile()
        var nowMs = 1_760_000_100_000L
        try {
            val diagDir = File(dir, "diagnostics")
            val store1 = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val s1 = store1.startSession(pid = 3100, env = sampleEnv())

            // Root span kucode.session auto-created and open
            val initialSpans = store1.readSessionSpans("current")
            assertEquals(1, initialSpans.size)
            assertEquals("kucode.session", initialSpans[0].name)
            assertEquals(s1.rootSpanId, initialSpans[0].spanId)
            assertNull(initialSpans[0].parentSpanId)
            assertNull(initialSpans[0].endMs)

            // Start child span (immediately persisted/flushed), then end with clamped timestamp and bounded attributes
            nowMs += 500L
            val childStart = store1.startSpan(
                name = "runtime.bootstrap",
                kind = SessionLogStore.SpanKind.INTERNAL,
                startMs = nowMs,
                attributes = mapOf("step" to "extract"),
            )!!
            assertNull(childStart.endMs)
            assertEquals(2, store1.readSessionSpans("current").size)

            // End with endMs < startMs -> must clamp endMs = startMs
            val endedChild = store1.endSpan(
                spanId = childStart.spanId,
                endMs = nowMs - 100L,
                status = SessionLogStore.SpanStatus.OK,
                attributes = mapOf("done" to true),
            )!!
            assertEquals(nowMs, endedChild.endMs)
            assertEquals("extract", endedChild.attributes["step"])
            assertEquals(true, endedChild.attributes["done"])

            // Record span directly with oversized name/statusMessage/attributes -> verify bounds
            val tooManyAttrs = (1..80).associate { "k_$it" to "v_$it" } + ("k_long_" + "x".repeat(200) to "v_" + "y".repeat(3000))
            val bounded = store1.recordSpan(
                name = "span_" + "n".repeat(250),
                kind = SessionLogStore.SpanKind.CLIENT,
                startMs = nowMs + 10L,
                endMs = nowMs + 50L,
                status = SessionLogStore.SpanStatus.ERROR,
                statusMessage = "err_" + "m".repeat(800),
                attributes = tooManyAttrs,
            )!!
            assertTrue(bounded.name.length <= 128)
            assertTrue((bounded.statusMessage?.length ?: 0) <= 512)
            assertTrue(bounded.attributes.size <= 64)
            assertTrue(bounded.attributes.keys.all { it.length <= 128 })
            assertTrue(bounded.attributes.values.filterIsInstance<String>().all { it.length <= 2048 })

            // Leave an open span when uncaught crash strikes -> must close with ERROR
            nowMs += 200L
            val openBeforeCrash = store1.startSpan(name = "openvscode.startup", startMs = nowMs)!!
            nowMs += 300L
            store1.recordUncaughtCrash(
                threadName = "MainThread",
                throwable = IllegalStateException("fatal startup failure"),
            )

            val spansAfterCrash = store1.readSessionSpans(s1.sessionId)
            val crashedRoot = spansAfterCrash.first { it.spanId == s1.rootSpanId }
            val crashedChild = spansAfterCrash.first { it.spanId == openBeforeCrash.spanId }
            assertNotNull(crashedRoot.endMs)
            assertEquals(SessionLogStore.SpanStatus.ERROR, crashedRoot.status)
            assertTrue(crashedRoot.statusMessage.orEmpty().contains("IllegalStateException"))
            assertNotNull(crashedChild.endMs)
            assertEquals(SessionLogStore.SpanStatus.ERROR, crashedChild.status)

            // OOM uncaught crash path must also finalize open spans with ERROR without crashing
            nowMs += 1_000L
            val sOom = store1.startSession(pid = 3101, env = sampleEnv())
            val openBeforeOom = store1.startSpan(name = "oom.work", startMs = nowMs)!!
            nowMs += 100L
            store1.recordUncaughtCrash("HeapWorker", OutOfMemoryError("Java heap space"))
            val oomSpans = store1.readSessionSpans(sOom.sessionId)
            assertEquals(SessionLogStore.SpanStatus.ERROR, oomSpans.first { it.spanId == sOom.rootSpanId }.status)
            assertEquals(SessionLogStore.SpanStatus.ERROR, oomSpans.first { it.spanId == openBeforeOom.spanId }.status)

            // Abnormal OS ApplicationExitInfo reconciliation (ANR) closes open spans as ERROR
            nowMs += 2_000L
            val storeAnr = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val sAnr = storeAnr.startSession(pid = 4100, env = sampleEnv())
            storeAnr.updateSessionState(runtimeState = "READY")
            val openBeforeAnr = storeAnr.startSpan(name = "acp.turn", startMs = nowMs + 100L)!!

            // Clean OS exit while a child span remained open -> root kucode.session becomes OK, interrupted child becomes ERROR
            nowMs += 5_000L
            val storeCleanExit = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val sCleanOs = storeCleanExit.startSession(pid = 4200, env = sampleEnv())
            storeCleanExit.updateSessionState(runtimeState = "READY")
            val openBeforeCleanOs = storeCleanExit.startSpan(name = "host_bridge.request", startMs = nowMs + 100L)!!

            nowMs += 10_000L
            val reconciler = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val reconciled = reconciler.reconcileUnfinishedSessions(
                currentPid = 5000,
                exitRecords = listOf(
                    SessionLogStore.OsProcessExitRecord(
                        pid = 4100,
                        timestampMs = nowMs - 2_000L,
                        reasonCode = 6,
                        reasonName = "REASON_ANR",
                        status = 0,
                        importance = 100,
                        importanceName = "FOREGROUND",
                        pssMb = 350,
                        rssMb = 500,
                        description = "Main thread blocked",
                    ),
                    SessionLogStore.OsProcessExitRecord(
                        pid = 4200,
                        timestampMs = nowMs - 500L,
                        reasonCode = 1,
                        reasonName = "REASON_EXIT_SELF",
                        status = 0,
                        importance = 100,
                        importanceName = "FOREGROUND",
                        pssMb = 200,
                        rssMb = 300,
                        description = "Clean self exit",
                    ),
                ),
            )
            assertEquals(2, reconciled.size)

            val anrSpans = reconciler.readSessionSpans(sAnr.sessionId)
            assertEquals(SessionLogStore.SpanStatus.ERROR, anrSpans.first { it.spanId == sAnr.rootSpanId }.status)
            assertEquals(SessionLogStore.SpanStatus.ERROR, anrSpans.first { it.spanId == openBeforeAnr.spanId }.status)

            val cleanOsSummary = reconciler.resolveSessionSummary(sCleanOs.sessionId)!!
            assertEquals(SessionLogStore.SessionStatus.ENDED_CLEANLY, cleanOsSummary.status)
            val cleanOsSpans = reconciler.readSessionSpans(sCleanOs.sessionId)
            val cleanRoot = cleanOsSpans.first { it.spanId == sCleanOs.rootSpanId }
            val interruptedChild = cleanOsSpans.first { it.spanId == openBeforeCleanOs.spanId }
            assertEquals(SessionLogStore.SpanStatus.OK, cleanRoot.status)
            assertNotNull(cleanRoot.endMs)
            assertEquals(SessionLogStore.SpanStatus.ERROR, interruptedChild.status)
            assertNotNull(interruptedChild.endMs)
            assertTrue(interruptedChild.statusMessage.orEmpty().contains("Interrupted by process exit"))
        } finally {
            deleteRecursivelyNoFollow(dir)
        }
    }

    @Test
    fun ac3_otlpJsonSerializerCanonicalFormatMetricsOmissionAndGranularity() {
        // [AC-3] Canonical OTLP/HTTP JSON v1.x serialization across all 3 signals & summary-and-errors vs full
        assertEquals("summary-and-errors", OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS)
        assertEquals("full", OtlpJsonSerializer.GRANULARITY_FULL)
        assertTrue(OtlpJsonSerializer.isValidGranularity("summary-and-errors"))
        assertTrue(OtlpJsonSerializer.isValidGranularity("full"))
        assertFalse(OtlpJsonSerializer.isValidGranularity("debug"))
        assertFalse(OtlpJsonSerializer.isValidGranularity(null))

        val dir = Files.createTempDirectory("kucode-oracle-ac3").toFile()
        var nowMs = 1_760_000_200_000L
        try {
            val diagDir = File(dir, "diagnostics")
            val store = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val session = store.startSession(pid = 6001, env = sampleEnv(buildSha = "c0ffee42"))

            nowMs += 100L
            store.recordSpan(
                name = "proot.command",
                kind = SessionLogStore.SpanKind.CLIENT,
                parentSpanId = session.rootSpanId,
                startMs = nowMs,
                endMs = nowMs + 250L,
                status = SessionLogStore.SpanStatus.OK,
                attributes = mapOf(
                    "kucode.command.tool" to "gradle",
                    "process.exit.code" to 0,
                    "kucode.cached" to false,
                    "kucode.ratio" to 0.75,
                ),
            )

            nowMs += 300L
            store.recordEvent(SessionLogStore.EventLevel.INFO, "boot.routine", "ROUTINE_INFO_MARKER_1")
            store.recordEvent(SessionLogStore.EventLevel.WARN, "launcher", "ALERT_WARN_MARKER_1")
            store.recordEvent(SessionLogStore.EventLevel.ERROR, "proot", "ALERT_ERROR_MARKER_1")

            store.recordMetric(
                SessionLogStore.MetricSnapshot(
                    timestampMs = nowMs,
                    trigger = "periodic",
                    availMemMb = 4096,
                    totalMemMb = 8192,
                    lowMemory = false,
                    pssMb = 210,
                    hostCpuTimeMs = 1500,
                    jvmHeapUsedMb = 32,
                    jvmHeapMaxMb = 256,
                    nativeHeapMb = 48,
                    openFds = 70,
                    threadCount = 24,
                    freeDiskMb = 50000,
                    thermalStatus = "LIGHT",
                    batteryPct = 88,
                    networkSummary = "WIFI(ok)",
                    runtimeState = "READY",
                    serverAlive = true,
                    activeView = "EDITOR",
                    windowFocus = true,
                ),
            )
            // Snapshot 2 has unknown (-1 / UNKNOWN) values that must be omitted from numeric metrics
            store.recordMetric(
                SessionLogStore.MetricSnapshot(
                    timestampMs = nowMs + 500L,
                    trigger = "unknown_check",
                    availMemMb = -1L,
                    totalMemMb = -1L,
                    lowMemory = true,
                    pssMb = -1L,
                    hostCpuTimeMs = -1L,
                    jvmHeapUsedMb = -1L,
                    nativeHeapMb = -1L,
                    openFds = -1,
                    threadCount = -1,
                    freeDiskMb = -1L,
                    thermalStatus = "UNKNOWN",
                    batteryPct = -1,
                    networkSummary = "WIFI(ok)",
                    runtimeState = "READY",
                    serverAlive = false,
                    activeView = "BOOT",
                    windowFocus = false,
                ),
            )

            val vscodeData = File(dir, "vscode-data")
            val runLogs = File(vscodeData, "user/logs/20261126T010000")
            runLogs.mkdirs()
            File(runLogs, "ptyhost.log").writeText("VSCODE_SUBLOG_MARKER_PTYHOST_ERROR\n")
            store.harvestOpenVsCodeSubLogs(vscodeData)

            nowMs += 1_000L
            store.recordUncaughtCrash("Worker-9", RuntimeException("EXIT_TRACE_FATAL_MARKER"))

            val summaryOnly = OtlpJsonSerializer.serializeSession(
                store = store,
                selector = session.sessionId,
                granularity = OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS,
            )!!
            assertNotNull(summaryOnly.metricsJson)
            assertNotNull(summaryOnly.logsJson)

            // Resource attributes must be identical across resourceSpans, resourceMetrics, resourceLogs
            val spanResAttrs = summaryOnly.combinedJson.getJSONArray("resourceSpans").getJSONObject(0)
                .getJSONObject("resource").getJSONArray("attributes")
            val metricResAttrs = summaryOnly.combinedJson.getJSONArray("resourceMetrics").getJSONObject(0)
                .getJSONObject("resource").getJSONArray("attributes")
            val logResAttrs = summaryOnly.combinedJson.getJSONArray("resourceLogs").getJSONObject(0)
                .getJSONObject("resource").getJSONArray("attributes")

            assertEquals(spanResAttrs.toString(), metricResAttrs.toString())
            assertEquals(spanResAttrs.toString(), logResAttrs.toString())

            assertEquals("kucode", findAttrValue(spanResAttrs, "service.name")?.getString("stringValue"))
            assertEquals("kucode", findAttrValue(spanResAttrs, "service.namespace")?.getString("stringValue"))
            assertEquals(session.sessionId, findAttrValue(spanResAttrs, "service.instance.id")?.getString("stringValue"))
            assertEquals(session.sessionId, findAttrValue(spanResAttrs, "session.id")?.getString("stringValue"))
            assertEquals("android", findAttrValue(spanResAttrs, "os.type")?.getString("stringValue"))
            assertEquals("c0ffee42", findAttrValue(spanResAttrs, "kucode.build.sha")?.getString("stringValue"))
            assertEquals(true, findAttrValue(spanResAttrs, "kucode.xr.spatial_supported")?.getBoolean("boolValue"))
            assertEquals(
                "summary-and-errors",
                findAttrValue(spanResAttrs, "kucode.telemetry.granularity")?.getString("stringValue"),
            )
            // process.pid intValue MUST be a decimal string per Protobuf-JSON canonical format
            val pidAnyVal = findAttrValue(spanResAttrs, "process.pid")!!
            assertTrue("process.pid intValue must be a String", pidAnyVal.get("intValue") is String)
            assertEquals("6001", pidAnyVal.getString("intValue"))

            // Spans: verify decimal string timestamps and attribute intValue, plus scope name
            val scopeSpans = summaryOnly.tracesJson.getJSONArray("resourceSpans").getJSONObject(0)
                .getJSONArray("scopeSpans").getJSONObject(0)
            assertEquals("dev.koduki.kucode.flight_recorder", scopeSpans.getJSONObject("scope").getString("name"))
            val spans = scopeSpans.getJSONArray("spans")
            assertTrue(spans.length() >= 2)
            val rootSpanJson = (0 until spans.length()).map { spans.getJSONObject(it) }
                .first { it.getString("name") == "kucode.session" }
            assertFalse("Root span must omit parentSpanId", rootSpanJson.has("parentSpanId"))
            assertTrue(rootSpanJson.get("startTimeUnixNano") is String)
            assertTrue(Regex("^[0-9]+$").matches(rootSpanJson.getString("startTimeUnixNano")))
            assertTrue(rootSpanJson.get("endTimeUnixNano") is String)

            val childSpanJson = (0 until spans.length()).map { spans.getJSONObject(it) }
                .first { it.getString("name") == "proot.command" }
            assertEquals(session.rootSpanId, childSpanJson.getString("parentSpanId"))
            val exitCodeAttr = findAttrValue(childSpanJson.getJSONArray("attributes"), "process.exit.code")!!
            assertTrue("Attribute intValue must be String", exitCodeAttr.get("intValue") is String)
            assertEquals("0", exitCodeAttr.getString("intValue"))

            // Metrics: verify decimal string timeUnixNano / asInt and omission of -1 unknown metrics
            val metricsArr = summaryOnly.metricsJson!!.getJSONArray("resourceMetrics").getJSONObject(0)
                .getJSONArray("scopeMetrics").getJSONObject(0).getJSONArray("metrics")
            val metricByName = (0 until metricsArr.length()).map { metricsArr.getJSONObject(it) }
                .associateBy { it.getString("name") }
            val pssPoints = metricByName.getValue("kucode.process.pss_mb").getJSONObject("gauge").getJSONArray("dataPoints")
            assertEquals(1, pssPoints.length())
            val pssPoint = pssPoints.getJSONObject(0)
            assertTrue(pssPoint.get("timeUnixNano") is String)
            assertTrue(pssPoint.get("asInt") is String)
            assertEquals("210", pssPoint.getString("asInt"))

            val cpuPoints = metricByName.getValue("kucode.host.cpu_time_ms").getJSONObject("gauge").getJSONArray("dataPoints")
            assertEquals(1, cpuPoints.length())
            val thermalPoints = metricByName.getValue("kucode.thermal.level").getJSONObject("gauge").getJSONArray("dataPoints")
            assertEquals(1, thermalPoints.length())
            val srvPoints = metricByName.getValue("kucode.server.alive").getJSONObject("gauge").getJSONArray("dataPoints")
            assertEquals(2, srvPoints.length())

            // summary-and-errors logs: includes WARN/ERROR/FATAL + exit-trace, excludes routine INFO & vscode-sublogs
            val summaryLogsText = summaryOnly.logsJson!!.toString()
            assertTrue(summaryLogsText.contains("ALERT_WARN_MARKER_1"))
            assertTrue(summaryLogsText.contains("ALERT_ERROR_MARKER_1"))
            assertTrue(summaryLogsText.contains("EXIT_TRACE_FATAL_MARKER"))
            assertFalse("summary-and-errors must exclude routine INFO logs", summaryLogsText.contains("ROUTINE_INFO_MARKER_1"))
            assertFalse("summary-and-errors must exclude vscode-sublogs.txt", summaryLogsText.contains("VSCODE_SUBLOG_MARKER_PTYHOST_ERROR"))

            // full granularity: includes routine INFO + alerts + exit-trace + vscode-sublogs
            val fullPayloads = OtlpJsonSerializer.serializeSession(
                store = store,
                selector = session.sessionId,
                granularity = OtlpJsonSerializer.GRANULARITY_FULL,
            )!!
            val fullLogsText = fullPayloads.logsJson!!.toString()
            assertTrue(fullLogsText.contains("ROUTINE_INFO_MARKER_1"))
            assertTrue(fullLogsText.contains("ALERT_WARN_MARKER_1"))
            assertTrue(fullLogsText.contains("EXIT_TRACE_FATAL_MARKER"))
            assertTrue(fullLogsText.contains("VSCODE_SUBLOG_MARKER_PTYHOST_ERROR"))

            // renderSessionBundle & exportSessionBundleToDirectory with format="otlp"
            val renderedFull = JSONObject(store.renderSessionBundle(session.sessionId, "otlp", granularity = OtlpJsonSerializer.GRANULARITY_FULL)!!)
            assertTrue(renderedFull.toString().contains("VSCODE_SUBLOG_MARKER_PTYHOST_ERROR"))

            val exportDir = File(dir, "exports")
            val exportedFile = store.exportSessionBundleToDirectory(session.sessionId, exportDir, format = "otlp")
            assertNotNull(exportedFile)
            assertEquals("kucode-session-${session.sessionId}-otlp.json", exportedFile!!.name)
            assertTrue(JSONObject(exportedFile.readText()).has("resourceSpans"))

            // Session with zero metrics -> metricsJson is null and combinedJson.resourceMetrics is empty array
            nowMs += 2_000L
            val noMetricSession = store.startSession(pid = 6002, env = sampleEnv())
            store.endSession("done")
            val noMetricPayloads = OtlpJsonSerializer.serializeSession(store, noMetricSession.sessionId)!!
            assertNull(noMetricPayloads.metricsJson)
            assertEquals(0, noMetricPayloads.combinedJson.getJSONArray("resourceMetrics").length())
        } finally {
            deleteRecursivelyNoFollow(dir)
        }
    }

    @Test
    fun ac4_logRedactorSanitizesAllSignalsAndOtlpConfigStoreProtectsSecrets() {
        // [AC-4] LogRedactor across OTLP signals + encrypted OtlpConfigStore + non-disclosure in getPublicConfigJson
        val ghTok = "ghp_" + "a".repeat(24)
        val skTok = "sk-proj-" + "b".repeat(24)
        val gcpTok = "AIza" + "c".repeat(35)
        val dynTok = "kucode_dyn_secret_" + "9876543210"
        val otlpAuthSuffix = "otlp_secret_token_" + "abcdef123456"
        val otlpAuthFull = "Bearer $otlpAuthSuffix"

        val storage = InMemoryKeyValueStorage()
        val cipher = FakePrefixSecretCipher()
        val configStore = OtlpConfigStore(storage, cipher)

        val defaultCfg = configStore.loadConfig()
        assertFalse(defaultCfg.enabled)
        assertEquals("", defaultCfg.endpointUrl)
        assertEquals("summary-and-errors", defaultCfg.granularity)
        assertFalse(defaultCfg.unmeteredOnly)
        assertEquals("Authorization", defaultCfg.authHeaderName)
        assertEquals("", defaultCfg.authHeaderValue)

        configStore.saveConfig(
            enabled = true,
            endpointUrl = "https://otel.example.com/v1/traces",
            granularity = "full",
            unmeteredOnly = true,
            authHeaderName = "X-OTLP-Auth",
            authHeaderValue = otlpAuthFull,
            clearAuthHeader = false,
        )

        // Storage must contain ciphertext only, never plaintext secret
        assertFalse(storage.strings.values.any { it.contains(otlpAuthSuffix) })
        assertTrue(storage.strings.values.any { it.startsWith("CIPHER_V1:") })

        // Dynamic secret registration redacts both full header and suffix
        assertFalse(LogRedactor.redact("leaked=$otlpAuthSuffix").contains(otlpAuthSuffix))

        // Public config JSON must NEVER expose plaintext authHeaderValue or secret suffix
        val pub1 = configStore.getPublicConfigJson(outboxPendingCount = 4)
        assertTrue(pub1.getBoolean("enabled"))
        assertEquals("https://otel.example.com", pub1.getString("endpointUrl"))
        assertEquals("full", pub1.getString("granularity"))
        assertTrue(pub1.getBoolean("unmeteredOnly"))
        assertEquals("X-OTLP-Auth", pub1.getString("authHeaderName"))
        assertTrue(pub1.getBoolean("hasAuthHeader"))
        assertEquals(4, pub1.getInt("outboxPendingCount"))
        assertFalse(pub1.has("authHeaderValue"))
        assertFalse(pub1.toString().contains(otlpAuthSuffix))

        // Blank/null authHeaderValue with clearAuthHeader=false preserves existing secret
        configStore.saveConfig(
            enabled = true,
            endpointUrl = "https://otel.example.com",
            granularity = "summary-and-errors",
            unmeteredOnly = false,
            authHeaderName = "Authorization",
            authHeaderValue = "",
            clearAuthHeader = false,
        )
        assertEquals(otlpAuthFull, configStore.loadConfig().authHeaderValue)
        configStore.saveConfig(
            enabled = true,
            endpointUrl = "https://otel.example.com",
            granularity = "summary-and-errors",
            unmeteredOnly = false,
            authHeaderName = "Authorization",
            authHeaderValue = null,
            clearAuthHeader = false,
        )
        assertEquals(otlpAuthFull, configStore.loadConfig().authHeaderValue)

        // Invalid header name rejected -> previous config preserved
        configStore.saveConfig(
            enabled = true,
            endpointUrl = "https://otel.example.com",
            granularity = "full",
            unmeteredOnly = false,
            authHeaderName = "Invalid Header:Name",
            authHeaderValue = "Bearer should_not_overwrite",
            clearAuthHeader = false,
        )
        assertEquals("Authorization", configStore.loadConfig().authHeaderName)
        assertEquals(otlpAuthFull, configStore.loadConfig().authHeaderValue)

        // clearAuthHeader=true wipes secret
        configStore.saveConfig(
            enabled = false,
            endpointUrl = "",
            granularity = "summary-and-errors",
            unmeteredOnly = false,
            authHeaderName = "Authorization",
            authHeaderValue = "",
            clearAuthHeader = true,
        )
        assertEquals("", configStore.loadConfig().authHeaderValue)
        assertFalse(configStore.getPublicConfigJson().getBoolean("hasAuthHeader"))

        // Re-register secrets and verify OtlpJsonSerializer redacts spans, logs, and resource attributes
        LogRedactor.registerSecret(dynTok)
        val dir = Files.createTempDirectory("kucode-oracle-ac4").toFile()
        try {
            val store = SessionLogStore(diagnosticsRootDir = File(dir, "diagnostics"))
            val s = store.startSession(pid = 7001, env = sampleEnv())
            store.recordSpan(
                name = "http https://u:$dynTok@example.com/a?tkn=$ghTok",
                startMs = s.startedAtMs + 10L,
                endMs = s.startedAtMs + 50L,
                status = SessionLogStore.SpanStatus.ERROR,
                statusMessage = "failed with $gcpTok",
                attributes = mapOf("auth" to "Authorization: Bearer $skTok"),
            )
            store.recordEvent(SessionLogStore.EventLevel.ERROR, "secret.test", "secret=$dynTok key=$skTok")
            store.endSession("exit reason token=$ghTok", abnormal = true)

            val payloads = OtlpJsonSerializer.serializeSession(store, s.sessionId)!!
            val combinedStr = payloads.combinedJson.toString()
            assertFalse(combinedStr.contains(ghTok))
            assertFalse(combinedStr.contains(skTok))
            assertFalse(combinedStr.contains(gcpTok))
            assertFalse(combinedStr.contains(dynTok))
        } finally {
            deleteRecursivelyNoFollow(dir)
        }
    }

    @Test
    fun ac5_otlpEndpointValidatorEnforcesHttpsAndStrictLoopbackHttpRules() {
        // [AC-5] Endpoint validation: https:// any host, http:// strictly 127.0.0.1 or localhost, reject userinfo/other schemes
        val rHttps = OtlpEndpointValidator.validate("  https://otlp.example.com/ingest/v1/traces/  ")
        assertTrue(rHttps.valid)
        assertEquals("https://otlp.example.com/ingest", rHttps.normalizedBaseUrl)

        val rMetrics = OtlpEndpointValidator.validate("https://otlp.example.com:4318/v1/metrics")
        assertTrue(rMetrics.valid)
        assertEquals("https://otlp.example.com:4318", rMetrics.normalizedBaseUrl)

        val rLogs = OtlpEndpointValidator.validate("https://otlp.example.com:4318/v1/logs/")
        assertTrue(rLogs.valid)
        assertEquals("https://otlp.example.com:4318", rLogs.normalizedBaseUrl)

        val rLoopbackIp = OtlpEndpointValidator.validate("http://127.0.0.1:4318/v1/traces")
        assertTrue(rLoopbackIp.valid)
        assertEquals("http://127.0.0.1:4318", rLoopbackIp.normalizedBaseUrl)

        val rLocalhost = OtlpEndpointValidator.validate("http://LOCALHOST:4318/")
        assertTrue(rLocalhost.valid)
        assertTrue(rLocalhost.normalizedBaseUrl.equals("http://localhost:4318", ignoreCase = true))

        // Reject insecure http:// on non-loopback hosts (including 127.0.0.2, [::1], LAN IP, remote domain)
        assertFalse(OtlpEndpointValidator.validate("http://otlp.example.com:4318").valid)
        assertFalse(OtlpEndpointValidator.validate("http://192.168.1.50:4318").valid)
        assertFalse(OtlpEndpointValidator.validate("http://127.0.0.2:4318").valid)
        assertFalse(OtlpEndpointValidator.validate("http://[::1]:4318").valid)

        // Reject userinfo, non-HTTP schemes, control chars, and blank when requireNonBlank=true
        assertFalse(OtlpEndpointValidator.validate("https://user:pass@otlp.example.com").valid)
        assertFalse(OtlpEndpointValidator.validate("http://user@localhost:4318").valid)
        assertFalse(OtlpEndpointValidator.validate("ftp://otlp.example.com").valid)
        assertFalse(OtlpEndpointValidator.validate("file:///tmp/otlp.json").valid)
        assertFalse(OtlpEndpointValidator.validate("https://otlp.example.com/\nX").valid)
        assertFalse(OtlpEndpointValidator.validate("", requireNonBlank = true).valid)
        assertNotNull(OtlpEndpointValidator.validate("", requireNonBlank = true).error)

        val rOptionalBlank = OtlpEndpointValidator.validate("   ", requireNonBlank = false)
        assertTrue(rOptionalBlank.valid)
        assertEquals("", rOptionalBlank.normalizedBaseUrl)
    }

    @Test
    fun ac6_otlpOutboxExporterOptInCapRetriesPermanent4xxAndUnmeteredDeferral() {
        // [AC-6] Default OFF, isolated outbox dir, cap 20, retry/backoff (30s, 120s, max 3), 4xx drop, unmeteredOnly
        val dir = Files.createTempDirectory("kucode-oracle-ac6").toFile()
        var nowMs = 1_760_000_300_000L
        var metered = false
        val requests = mutableListOf<Triple<String, Map<String, String>, String>>()
        val statusQueue = ArrayDeque<Int>()
        var defaultStatus = 200

        val fakeClient = object : OtlpHttpClient {
            override fun postJson(url: String, headers: Map<String, String>, jsonBody: String): OtlpHttpResponse {
                requests.add(Triple(url, headers, jsonBody))
                val code = if (statusQueue.isNotEmpty()) statusQueue.removeFirst() else defaultStatus
                return OtlpHttpResponse(statusCode = code, body = if (code in 200..299) "{}" else "err $code")
            }
        }

        try {
            val diagDir = File(dir, "diagnostics")
            val outboxDir = File(dir, "otlp-outbox")
            val store = SessionLogStore(
                diagnosticsRootDir = diagDir,
                maxStoredSessions = 30,
                clockMs = { nowMs },
            )
            val configStore = OtlpConfigStore(InMemoryKeyValueStorage(), FakePrefixSecretCipher())
            val exporter = OtlpOutboxExporter(
                sessionLogStore = store,
                outboxDir = outboxDir,
                configStore = configStore,
                httpClient = fakeClient,
                isNetworkMetered = { metered },
                clockMs = { nowMs },
            )

            // 1. Default OFF: zero HTTP calls, enqueue returns false, flush skippedReason="disabled"
            val s0 = store.startSession(pid = 8000, env = sampleEnv())
            store.endSession("clean")
            assertFalse(exporter.enqueueSession(s0.sessionId))
            val disabledFlush = exporter.flushOutbox()
            assertFalse(disabledFlush.enabled)
            assertEquals("disabled", disabledFlush.skippedReason)
            assertEquals(0, requests.size)
            assertFalse(File(diagDir, "otlp-outbox").exists())

            // 2. Enable opt-in & verify cap=20 prunes oldest 2 out of 22 enqueued sessions
            configStore.saveConfig(
                enabled = true,
                endpointUrl = "https://otlp.example.com/v1/traces",
                granularity = "summary-and-errors",
                unmeteredOnly = false,
                authHeaderName = "Authorization",
                authHeaderValue = "Bearer test_outbox_token_123456",
                clearAuthHeader = false,
            )

            val sessionIds = mutableListOf<String>()
            repeat(22) { idx ->
                nowMs += 1_000L
                val s = store.startSession(pid = 8100 + idx, env = sampleEnv())
                store.recordEvent(SessionLogStore.EventLevel.ERROR, "cap.test", "CAP_SESSION_$idx")
                store.endSession("done-$idx")
                assertTrue(exporter.enqueueSession(s.sessionId))
                sessionIds.add(s.sessionId)
            }
            // Verify isolated outbox is strictly separate from diagnostics directory
            assertTrue(outboxDir.isDirectory)
            assertFalse(File(diagDir, "otlp-outbox").exists())

            val capFlush = exporter.flushOutbox()
            assertTrue(capFlush.ok)
            assertEquals(20, capFlush.flushed)
            assertEquals(0, capFlush.remaining)
            val sentPayloadAll = requests.joinToString("\n") { it.third }
            assertFalse("Oldest pruned session 0 must not be flushed", sentPayloadAll.contains(sessionIds[0]))
            assertFalse("Oldest pruned session 1 must not be flushed", sentPayloadAll.contains(sessionIds[1]))
            assertTrue("Newest session 21 must be flushed", sentPayloadAll.contains(sessionIds[21]))
            requests.clear()

            // 3. Retry & backoff schedule (30s after 1st failure, 120s after 2nd failure, drop on 3rd failure)
            nowMs += 1_000L
            val sRetry = store.startSession(pid = 8200, env = sampleEnv())
            store.endSession("retry-test")
            assertTrue(exporter.enqueueSession(sRetry.sessionId))

            defaultStatus = 500
            val fail1 = exporter.flushOutbox()
            assertFalse(fail1.ok)
            assertEquals(1, fail1.failed)
            assertEquals(1, fail1.remaining)
            val callsAfterFail1 = requests.size
            assertTrue(callsAfterFail1 >= 1)

            // Before 30s backoff elapsed -> deferred, zero HTTP calls
            nowMs += 15_000L
            val backoffWait1 = exporter.flushOutbox(ignoreBackoff = false)
            assertEquals(callsAfterFail1, requests.size)
            assertEquals(1, backoffWait1.deferred)
            assertEquals(1, backoffWait1.remaining)

            // After 30s backoff elapsed -> 2nd attempt (429 transient)
            nowMs += 20_000L
            defaultStatus = 429
            val fail2 = exporter.flushOutbox(ignoreBackoff = false)
            assertTrue(requests.size > callsAfterFail1)
            assertEquals(1, fail2.failed)
            assertEquals(1, fail2.remaining)
            val callsAfterFail2 = requests.size

            // Before 120s backoff elapsed -> deferred, zero HTTP calls
            nowMs += 60_000L
            val backoffWait2 = exporter.flushOutbox(ignoreBackoff = false)
            assertEquals(callsAfterFail2, requests.size)
            assertEquals(1, backoffWait2.deferred)

            // After 120s backoff elapsed -> 3rd attempt (503) -> dropped on 3rd failure!
            nowMs += 70_000L
            defaultStatus = 503
            val fail3 = exporter.flushOutbox(ignoreBackoff = false)
            assertTrue(requests.size > callsAfterFail2)
            assertEquals(0, fail3.remaining)

            val callsAfterDrop = requests.size
            val emptyAfterDrop = exporter.flushOutbox(ignoreBackoff = true)
            assertEquals(callsAfterDrop, requests.size)
            assertEquals(0, emptyAfterDrop.remaining)
            requests.clear()

            // 4. Permanent 4xx (401/400) drops immediately on 1st failure without retry
            defaultStatus = 401
            nowMs += 1_000L
            val s401 = store.startSession(pid = 8300, env = sampleEnv())
            store.endSession("unauth-test")
            assertTrue(exporter.enqueueSession(s401.sessionId))
            val permFail = exporter.flushOutbox()
            assertEquals(1, permFail.failed)
            assertEquals(0, permFail.remaining)
            val callsAfter401 = requests.size
            assertEquals(0, exporter.flushOutbox(ignoreBackoff = true).remaining)
            assertEquals(callsAfter401, requests.size)
            requests.clear()

            // 5. unmeteredOnly = true defers when network is metered, flushes when unmetered
            defaultStatus = 200
            configStore.saveConfig(
                enabled = true,
                endpointUrl = "https://otlp.example.com",
                granularity = "summary-and-errors",
                unmeteredOnly = true,
                authHeaderName = "Authorization",
                authHeaderValue = "",
                clearAuthHeader = false,
            )
            nowMs += 1_000L
            val sMetered = store.startSession(pid = 8400, env = sampleEnv())
            store.endSession("metered-test")
            assertTrue(exporter.enqueueSession(sMetered.sessionId))

            metered = true
            val deferredRes = exporter.flushOutbox()
            assertEquals(0, requests.size)
            assertEquals(1, deferredRes.deferred)
            assertEquals(1, deferredRes.remaining)
            assertEquals("metered_network", deferredRes.skippedReason)

            metered = false
            val flushedRes = exporter.flushOutbox()
            assertTrue(flushedRes.ok)
            assertEquals(1, flushedRes.flushed)
            assertEquals(0, flushedRes.remaining)
            assertTrue(requests.any { it.first == "https://otlp.example.com/v1/traces" })
        } finally {
            deleteRecursivelyNoFollow(dir)
        }
    }

    @Test
    fun ac12_posixRuntimeConfigPropagatesValidTraceparentWhilePreservingPathOrder() {
        // [AC-12] PosixRuntimeConfig.buildProotEnvironment injects valid TRACEPARENT and preserves PATH precedence
        val paths = PosixRuntimeConfig.Paths(
            runtimeRoot = File("/data/user/0/dev.koduki.kucode/files/runtime"),
            workspaceDir = File("/data/user/0/dev.koduki.kucode/files/workspace"),
            homeDir = File("/data/user/0/dev.koduki.kucode/files/home"),
            nativeLibDir = File("/data/app/kucode/lib/arm64"),
            androidSharedDir = File("/sdcard/Android/data/dev.koduki.kucode/files/shared"),
        )
        val validTp = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        val envValid = PosixRuntimeConfig.buildProotEnvironment(
            paths = paths,
            adminPort = 41234,
            adminToken = "tok123",
            includeNodeExtraCaCerts = false,
            traceparent = validTp,
        )
        assertEquals(validTp, envValid["TRACEPARENT"])

        val pathParts = envValid.getValue("PATH").split(':')
        val usrLocalBinIdx = pathParts.indexOf("/usr/local/bin")
        val usrBinIdx = pathParts.indexOf("/usr/bin")
        assertTrue(usrLocalBinIdx > 0)
        assertTrue(usrBinIdx > usrLocalBinIdx)
        assertTrue(pathParts.indexOf("/root/.local/share/mise/shims") in 0 until usrLocalBinIdx)
        assertTrue(pathParts.indexOf("/root/.cargo/bin") in 0 until usrLocalBinIdx)
        assertTrue(pathParts.indexOf("/root/Android/Sdk/cmdline-tools/latest/bin") in 0 until usrLocalBinIdx)

        // Invalid or blank TRACEPARENT must be omitted
        for (badTp in listOf("", "   ", "invalid", "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "00-${"0".repeat(32)}-00f067aa0ba902b7-01")) {
            val envBad = PosixRuntimeConfig.buildProotEnvironment(
                paths = paths,
                includeNodeExtraCaCerts = false,
                traceparent = badTp,
            )
            assertFalse("Invalid traceparent '$badTp' must be omitted", envBad.containsKey("TRACEPARENT"))
        }
    }
}
