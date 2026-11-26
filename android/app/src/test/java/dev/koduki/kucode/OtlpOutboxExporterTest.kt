package dev.koduki.kucode

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pure-JVM regression tests for [OtlpOutboxExporter] consent recheck and mid-flush config changes.
 */
class OtlpOutboxExporterTest {

    @After
    fun tearDown() {
        LogRedactor.clearDynamicSecrets()
    }

    private class InMemoryStorage : KeyValueStorage {
        private val strings = linkedMapOf<String, String>()
        private val booleans = linkedMapOf<String, Boolean>()

        override fun getString(key: String): String? = strings[key]

        override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default

        override fun putString(key: String, value: String?) {
            if (value == null) strings.remove(key) else strings[key] = value
        }

        override fun putBoolean(key: String, value: Boolean) {
            booleans[key] = value
        }
    }

    private class ReversibleTestCipher : OtlpSecretCipher {
        override fun encrypt(plaintext: String): String = "ENC_TEST:" + plaintext.reversed()

        override fun decrypt(ciphertext: String): String? =
            if (ciphertext.startsWith("ENC_TEST:")) ciphertext.removePrefix("ENC_TEST:").reversed() else null
    }

    private fun sampleEnv() = SessionLogStore.SessionEnvMetadata(
        appVersion = "0.10.0-debug",
        packageName = "dev.koduki.kucode.debug",
        deviceSummary = "TestDevice",
        androidSummary = "15 (API 35)",
        buildSha = "deadbeef",
        notificationsEnabled = true,
        batteryOptIgnored = true,
    )

    @Test
    fun disablingExportMidSessionAbortsRemainingSignalsAndQueuedSessionsWithoutRetryFailure() {
        val dir = Files.createTempDirectory("kucode-otlp-consent-midsession").toFile()
        var nowMs = 1_760_000_500_000L
        val sentUrls = mutableListOf<String>()
        try {
            val diagDir = File(dir, "diagnostics")
            val outboxDir = File(dir, "otlp-outbox")
            val store = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val configStore = OtlpConfigStore(InMemoryStorage(), ReversibleTestCipher())
            configStore.saveConfig(
                enabled = true,
                endpointUrl = "https://otlp.example.com",
                granularity = OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS,
                unmeteredOnly = false,
                authHeaderName = "Authorization",
                authHeaderValue = "",
                clearAuthHeader = true,
            )

            // Enqueue 2 sessions, each producing traces, metrics, and logs
            val s1 = store.startSession(pid = 1001, env = sampleEnv())
            store.recordMetric(
                SessionLogStore.MetricSnapshot(
                    timestampMs = nowMs + 10L,
                    trigger = "sample1",
                    pssMb = 128,
                    runtimeState = "READY",
                    serverAlive = true,
                ),
            )
            store.recordEvent(SessionLogStore.EventLevel.ERROR, "test.s1", "S1_ERROR_SIGNAL")
            nowMs += 1_000L
            store.endSession("s1-done")

            nowMs += 1_000L
            val s2 = store.startSession(pid = 1002, env = sampleEnv())
            store.recordMetric(
                SessionLogStore.MetricSnapshot(
                    timestampMs = nowMs + 10L,
                    trigger = "sample2",
                    pssMb = 140,
                    runtimeState = "READY",
                    serverAlive = true,
                ),
            )
            store.recordEvent(SessionLogStore.EventLevel.ERROR, "test.s2", "S2_ERROR_SIGNAL")
            nowMs += 1_000L
            store.endSession("s2-done")

            var disabledOnce = false
            val fakeClient = object : OtlpHttpClient {
                override fun postJson(url: String, headers: Map<String, String>, jsonBody: String): OtlpHttpResponse {
                    sentUrls.add(url)
                    if (!disabledOnce) {
                        disabledOnce = true
                        // Simulate user disabling OTLP export concurrently while /v1/traces is in flight
                        configStore.saveConfig(
                            enabled = false,
                            endpointUrl = "https://otlp.example.com",
                            granularity = OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS,
                            unmeteredOnly = false,
                            authHeaderName = "Authorization",
                            authHeaderValue = "",
                            clearAuthHeader = false,
                        )
                    }
                    return OtlpHttpResponse(statusCode = 200, body = "{}")
                }
            }

            val exporter = OtlpOutboxExporter(
                sessionLogStore = store,
                outboxDir = outboxDir,
                configStore = configStore,
                httpClient = fakeClient,
                clockMs = { nowMs },
            )
            assertTrue(exporter.enqueueSession(s1.sessionId))
            nowMs += 100L
            assertTrue(exporter.enqueueSession(s2.sessionId))

            val aborted = exporter.flushOutbox()
            assertFalse(aborted.enabled)
            assertFalse(aborted.ok)
            assertEquals("disabled", aborted.skippedReason)
            assertEquals(0, aborted.flushed)
            assertEquals(0, aborted.failed)
            assertEquals(2, aborted.remaining)
            assertEquals(listOf("https://otlp.example.com/v1/traces"), sentUrls)

            val queued = OtlpOutboxStore(outboxDir).list()
            assertEquals(2, queued.size)
            val queued1 = queued.first { it.sessionId == s1.sessionId }
            val queued2 = queued.first { it.sessionId == s2.sessionId }
            assertEquals(0, queued1.attempts)
            assertTrue(queued1.tracesSent)
            assertFalse(queued1.metricsSent)
            assertFalse(queued1.logsSent)
            assertEquals(0, queued2.attempts)
            assertFalse(queued2.tracesSent)
            assertFalse(queued2.metricsSent)
            assertFalse(queued2.logsSent)

            // Re-enabling resumes only unsent signals without duplicating s1 /v1/traces
            sentUrls.clear()
            configStore.saveConfig(
                enabled = true,
                endpointUrl = "https://otlp.example.com",
                granularity = OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS,
                unmeteredOnly = false,
                authHeaderName = "Authorization",
                authHeaderValue = "",
                clearAuthHeader = false,
            )
            val resumed = exporter.flushOutbox()
            assertTrue(resumed.ok)
            assertTrue(resumed.enabled)
            assertEquals(2, resumed.flushed)
            assertEquals(0, resumed.failed)
            assertEquals(0, resumed.remaining)
            assertEquals(
                listOf(
                    "https://otlp.example.com/v1/metrics",
                    "https://otlp.example.com/v1/logs",
                    "https://otlp.example.com/v1/traces",
                    "https://otlp.example.com/v1/metrics",
                    "https://otlp.example.com/v1/logs",
                ),
                sentUrls,
            )
        } finally {
            deleteTreeNoFollow(dir)
        }
    }

    @Test
    fun disablingExportAfterFirstSessionPreventsSecondQueuedSessionFromSending() {
        val dir = Files.createTempDirectory("kucode-otlp-consent-between-sessions").toFile()
        var nowMs = 1_760_000_600_000L
        val sentBodies = mutableListOf<String>()
        try {
            val diagDir = File(dir, "diagnostics")
            val outboxDir = File(dir, "otlp-outbox")
            val store = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val configStore = OtlpConfigStore(InMemoryStorage(), ReversibleTestCipher())
            configStore.saveConfig(
                enabled = true,
                endpointUrl = "https://otlp.example.com",
                granularity = OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS,
                unmeteredOnly = false,
                authHeaderName = "Authorization",
                authHeaderValue = "",
                clearAuthHeader = true,
            )

            // Two sessions with traces only (no metrics, no WARN/ERROR/FATAL alerts)
            val s1 = store.startSession(pid = 2001, env = sampleEnv())
            nowMs += 500L
            store.endSession("s1-clean")

            nowMs += 1_000L
            val s2 = store.startSession(pid = 2002, env = sampleEnv())
            nowMs += 500L
            store.endSession("s2-clean")

            val fakeClient = object : OtlpHttpClient {
                override fun postJson(url: String, headers: Map<String, String>, jsonBody: String): OtlpHttpResponse {
                    sentBodies.add(jsonBody)
                    // Disable export while session 1's single POST is in progress
                    configStore.saveConfig(
                        enabled = false,
                        endpointUrl = "https://otlp.example.com",
                        granularity = OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS,
                        unmeteredOnly = false,
                        authHeaderName = "Authorization",
                        authHeaderValue = "",
                        clearAuthHeader = false,
                    )
                    return OtlpHttpResponse(statusCode = 200, body = "{}")
                }
            }

            val exporter = OtlpOutboxExporter(
                sessionLogStore = store,
                outboxDir = outboxDir,
                configStore = configStore,
                httpClient = fakeClient,
                clockMs = { nowMs },
            )
            assertTrue(exporter.enqueueSession(s1.sessionId))
            nowMs += 100L
            assertTrue(exporter.enqueueSession(s2.sessionId))

            val result = exporter.flushOutbox()
            assertFalse(result.enabled)
            assertFalse(result.ok)
            assertEquals("disabled", result.skippedReason)
            assertEquals(1, result.flushed)
            assertEquals(0, result.failed)
            assertEquals(1, result.remaining)
            assertEquals(1, sentBodies.size)
            assertTrue(sentBodies.single().contains(s1.sessionId))
            assertFalse(sentBodies.single().contains(s2.sessionId))

            val remainingItems = OtlpOutboxStore(outboxDir).list()
            assertEquals(1, remainingItems.size)
            assertEquals(s2.sessionId, remainingItems.single().sessionId)
            assertEquals(0, remainingItems.single().attempts)
            assertFalse(remainingItems.single().tracesSent)
        } finally {
            deleteTreeNoFollow(dir)
        }
    }

    @Test
    fun finalizedJvmCrashSessionMarkedDuringCrashIsEnqueuedAndExportedOnNextBoot() {
        val dir = Files.createTempDirectory("kucode-otlp-crash-nextboot").toFile()
        var nowMs = 1_760_000_700_000L
        val sentRequests = mutableListOf<Pair<String, String>>()
        try {
            val diagDir = File(dir, "diagnostics")
            val outboxDir = File(dir, "otlp-outbox")
            val storage = InMemoryStorage()
            val cipher = ReversibleTestCipher()
            val configStore1 = OtlpConfigStore(storage, cipher)
            configStore1.saveConfig(
                enabled = true,
                endpointUrl = "https://otlp.example.com",
                granularity = OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS,
                unmeteredOnly = false,
                authHeaderName = "Authorization",
                authHeaderValue = "",
                clearAuthHeader = true,
            )

            // Process 1: active session crashes with OOM (finalized as ENDED_ABNORMALLY before death)
            val store1 = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val exporter1 = OtlpOutboxExporter(
                sessionLogStore = store1,
                outboxDir = outboxDir,
                configStore = configStore1,
                clockMs = { nowMs },
            )
            assertTrue(exporter1.isEnabledCached())

            store1.startSession(pid = 3001, env = sampleEnv())
            nowMs += 200L
            store1.startSpan(name = "proot.command", startMs = nowMs)
            val crashedId = store1.currentSessionId()
            nowMs += 300L
            store1.recordUncaughtCrash("main", OutOfMemoryError("simulated oom"))
            exporter1.markPendingCrashSessionIfEnabled(crashedId)

            // Marker file is non-.json, so outbox pendingCount() is still 0 until next boot drains it
            assertTrue(exporter1.hasPendingCrashSessions())
            assertEquals(0, exporter1.pendingCount())
            assertFalse(File(diagDir, "otlp-outbox").exists())

            // Process 2 (next boot): reconcileUnfinishedSessions skips ENDED_ABNORMALLY sessions
            nowMs += 5_000L
            val store2 = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val reconciled = store2.reconcileUnfinishedSessions(currentPid = 3002)
            assertTrue("Already-finalized JVM crash must be skipped by reconcileUnfinishedSessions", reconciled.isEmpty())

            val fakeClient = object : OtlpHttpClient {
                override fun postJson(url: String, headers: Map<String, String>, jsonBody: String): OtlpHttpResponse {
                    sentRequests.add(url to jsonBody)
                    return OtlpHttpResponse(statusCode = 200, body = "{}")
                }
            }
            val configStore2 = OtlpConfigStore(storage, cipher)
            val exporter2 = OtlpOutboxExporter(
                sessionLogStore = store2,
                outboxDir = outboxDir,
                configStore = configStore2,
                httpClient = fakeClient,
                clockMs = { nowMs },
            )

            assertTrue(exporter2.hasPendingCrashSessions())
            assertEquals(1, exporter2.drainPendingCrashSessions())
            assertFalse(exporter2.hasPendingCrashSessions())
            assertEquals(1, exporter2.pendingCount())

            val flushResult = exporter2.flushOutbox()
            assertTrue(flushResult.ok)
            assertTrue(flushResult.enabled)
            assertEquals(1, flushResult.flushed)
            assertEquals(0, flushResult.remaining)
            assertFalse(exporter2.hasPendingCrashSessions())
            assertEquals(0, exporter2.pendingCount())

            val tracesBody = sentRequests.firstOrNull { it.first == "https://otlp.example.com/v1/traces" }?.second.orEmpty()
            val logsBody = sentRequests.firstOrNull { it.first == "https://otlp.example.com/v1/logs" }?.second.orEmpty()
            assertTrue("Expected /v1/traces payload with crashed session ID", tracesBody.contains(crashedId!!))
            assertTrue("Expected /v1/traces payload with ERROR status code 2", tracesBody.contains("\"code\":2"))
            assertTrue("Expected /v1/traces payload with proot.command span", tracesBody.contains("proot.command"))
            assertTrue("Expected /v1/logs payload with crash trace", logsBody.contains("OutOfMemoryError"))
            assertTrue("Expected /v1/logs payload with simulated oom message", logsBody.contains("simulated oom"))
        } finally {
            deleteTreeNoFollow(dir)
        }
    }

    @Test
    fun pendingCrashSessionMarkerIsClearedWithoutSendingWhenConsentWithdrawnBeforeNextBoot() {
        val dir = Files.createTempDirectory("kucode-otlp-crash-consent-off").toFile()
        var nowMs = 1_760_000_800_000L
        val sentRequests = mutableListOf<String>()
        try {
            val diagDir = File(dir, "diagnostics")
            val outboxDir = File(dir, "otlp-outbox")
            val storage = InMemoryStorage()
            val cipher = ReversibleTestCipher()
            val configStore1 = OtlpConfigStore(storage, cipher)
            configStore1.saveConfig(
                enabled = true,
                endpointUrl = "https://otlp.example.com",
                granularity = OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS,
                unmeteredOnly = false,
                authHeaderName = "Authorization",
                authHeaderValue = "",
                clearAuthHeader = true,
            )

            val store1 = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val exporter1 = OtlpOutboxExporter(
                sessionLogStore = store1,
                outboxDir = outboxDir,
                configStore = configStore1,
                clockMs = { nowMs },
            )
            store1.startSession(pid = 4001, env = sampleEnv())
            val crashedId = store1.currentSessionId()
            nowMs += 200L
            store1.recordUncaughtCrash("Worker-1", IllegalStateException("fatal worker failure"))
            exporter1.markPendingCrashSessionIfEnabled(crashedId)
            assertTrue(exporter1.hasPendingCrashSessions())

            // Consent withdrawn before next-boot drain
            val configStore2 = OtlpConfigStore(storage, cipher)
            configStore2.saveConfig(
                enabled = false,
                endpointUrl = "https://otlp.example.com",
                granularity = OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS,
                unmeteredOnly = false,
                authHeaderName = "Authorization",
                authHeaderValue = "",
                clearAuthHeader = false,
            )

            val fakeClient = object : OtlpHttpClient {
                override fun postJson(url: String, headers: Map<String, String>, jsonBody: String): OtlpHttpResponse {
                    sentRequests.add(url)
                    return OtlpHttpResponse(statusCode = 200, body = "{}")
                }
            }
            val store2 = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val exporter2 = OtlpOutboxExporter(
                sessionLogStore = store2,
                outboxDir = outboxDir,
                configStore = configStore2,
                httpClient = fakeClient,
                clockMs = { nowMs },
            )

            assertEquals(0, exporter2.drainPendingCrashSessions())
            assertFalse(exporter2.hasPendingCrashSessions())
            assertEquals(0, exporter2.pendingCount())
            val flushRes = exporter2.flushOutbox()
            assertFalse(flushRes.enabled)
            assertEquals("disabled", flushRes.skippedReason)
            assertTrue(sentRequests.isEmpty())
        } finally {
            deleteTreeNoFollow(dir)
        }
    }
}
