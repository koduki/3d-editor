package dev.koduki.kucode.oracle

import dev.koduki.kucode.KeyValueStorage
import dev.koduki.kucode.LocalDiagnosticsServer
import dev.koduki.kucode.LogRedactor
import dev.koduki.kucode.MediumTest
import dev.koduki.kucode.OtlpConfigStore
import dev.koduki.kucode.OtlpJsonSerializer
import dev.koduki.kucode.OtlpOutboxExporter
import dev.koduki.kucode.OtlpSecretCipher
import dev.koduki.kucode.PosixRuntimeConfig
import dev.koduki.kucode.SessionLogStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Clean-room spec-derived medium integration oracle tests for Issue #36.
 *
 * Covers AC-7 (live loopback LocalDiagnosticsServer + loopback OTLP collector E2E)
 * and AC-8 (generated kucode-logs CLI E2E).
 */
@Category(MediumTest::class)
class OtelFlightRecorderMediumOracleTest {

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

    private class InMemoryStorage : KeyValueStorage {
        private val strs = mutableMapOf<String, String>()
        private val bools = mutableMapOf<String, Boolean>()
        override fun getString(key: String): String? = strs[key]
        override fun getBoolean(key: String, default: Boolean): Boolean = bools[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) strs.remove(key) else strs[key] = value
        }
        override fun putBoolean(key: String, value: Boolean) {
            bools[key] = value
        }
    }

    private class IdentityCipher : OtlpSecretCipher {
        override fun encrypt(plaintext: String): String = "ENC:$plaintext"
        override fun decrypt(ciphertext: String): String? =
            if (ciphertext.startsWith("ENC:")) ciphertext.removePrefix("ENC:") else null
    }

    private data class RecordedHttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private class LoopbackHttpCollector {
        val requests = CopyOnWriteArrayList<RecordedHttpRequest>()
        @Volatile
        var responseStatus: Int = 200
        @Volatile
        var redirectLocation: String? = null

        private val serverSocket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val port: Int = serverSocket.localPort
        @Volatile
        private var running = true
        private val thread = Thread({
            while (running) {
                val client = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                handle(client)
            }
        }, "mock-otlp-collector").apply {
            isDaemon = true
            start()
        }

        private fun readLineAscii(input: BufferedInputStream): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
                if (b == '\n'.code) {
                    val arr = out.toByteArray()
                    val len = if (arr.isNotEmpty() && arr.last() == '\r'.code.toByte()) arr.size - 1 else arr.size
                    return String(arr, 0, len, Charsets.UTF_8)
                }
                out.write(b)
            }
        }

        private fun handle(socket: Socket) {
            socket.use { s ->
                s.soTimeout = 5_000
                val input = BufferedInputStream(s.getInputStream())
                val reqLine = readLineAscii(input) ?: return
                val parts = reqLine.split(' ')
                val method = parts.getOrNull(0)?.uppercase() ?: "GET"
                val path = parts.getOrNull(1) ?: "/"
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLineAscii(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                }
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyBytes = ByteArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val n = input.read(bodyBytes, readTotal, contentLength - readTotal)
                    if (n <= 0) break
                    readTotal += n
                }
                requests.add(RecordedHttpRequest(method, path, headers, String(bodyBytes, 0, readTotal, Charsets.UTF_8)))

                val code = responseStatus
                val loc = redirectLocation
                val respHeaders = buildString {
                    append("HTTP/1.1 $code ${if (code == 302) "Found" else "OK"}\r\n")
                    append("Content-Type: application/json\r\n")
                    if (code == 302 && !loc.isNullOrBlank()) {
                        append("Location: $loc\r\n")
                    }
                    append("Content-Length: 2\r\n")
                    append("Connection: close\r\n\r\n{}")
                }
                s.getOutputStream().write(respHeaders.toByteArray(Charsets.US_ASCII))
                s.getOutputStream().flush()
            }
        }

        fun close() {
            running = false
            runCatching { serverSocket.close() }
            thread.join(1_000)
        }
    }

    private fun httpRequest(
        method: String,
        url: String,
        token: String,
        extraHeaders: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = false
            if (token.isNotEmpty()) {
                setRequestProperty("X-KuCode-Token", token)
            }
            for ((k, v) in extraHeaders) {
                setRequestProperty(k, v)
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            code to text
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun ac7_liveDiagnosticsServerOtlpRoutesAndCollectorFlushWithoutRedirectSecretLeak() {
        // [AC-7] Live loopback LocalDiagnosticsServer (?format=otlp, POST /event, POST /span, POST /otlp/flush) + collector E2E
        val dir = Files.createTempDirectory("kucode-oracle-ac7").toFile()
        val collector = LoopbackHttpCollector()
        val redirectSink = LoopbackHttpCollector()
        val authSecret = "test_otlp_token_" + "1234567890abcdef"
        val authHeaderVal = "Bearer $authSecret"
        var server: LocalDiagnosticsServer? = null

        try {
            val store = SessionLogStore(diagnosticsRootDir = File(dir, "diagnostics"))
            val session = store.startSession(
                pid = 9100,
                env = SessionLogStore.SessionEnvMetadata(
                    appVersion = "0.10.0-debug",
                    packageName = "dev.koduki.kucode.debug",
                    buildSha = "ac7sha123",
                ),
            )
            store.recordEvent(SessionLogStore.EventLevel.INFO, "boot.info", "AC7_ROUTINE_INFO_MARKER")
            store.recordEvent(SessionLogStore.EventLevel.ERROR, "boot.err", "AC7_ERROR_WITH_SECRET $authSecret")
            store.recordMetric(
                SessionLogStore.MetricSnapshot(
                    timestampMs = session.startedAtMs + 100L,
                    trigger = "ac7_sample",
                    availMemMb = 5000,
                    totalMemMb = 8000,
                    pssMb = 160,
                    freeDiskMb = 25000,
                    runtimeState = "READY",
                    serverAlive = true,
                ),
            )

            val configStore = OtlpConfigStore(InMemoryStorage(), IdentityCipher())
            configStore.saveConfig(
                enabled = true,
                endpointUrl = "http://127.0.0.1:${collector.port}",
                granularity = OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS,
                unmeteredOnly = false,
                authHeaderName = "Authorization",
                authHeaderValue = authHeaderVal,
                clearAuthHeader = false,
            )
            val exporter = OtlpOutboxExporter(
                sessionLogStore = store,
                outboxDir = File(dir, "otlp-outbox"),
                configStore = configStore,
            )

            server = LocalDiagnosticsServer(
                onRunDiagnostics = { "ok" },
                onGetLogs = { "ok" },
                onCopyToClipboard = { true },
                onListSessions = { store.listSessionsJson() },
                onGetSessionBundle = { selector, format -> store.renderSessionBundle(selector, format) },
                onRecordClientEvent = { lvl, src, msg ->
                    val level = runCatching { SessionLogStore.EventLevel.valueOf(lvl.uppercase()) }
                        .getOrDefault(SessionLogStore.EventLevel.INFO)
                    store.recordEvent(level, src, msg)
                },
                sessionLogStore = store,
                otlpOutboxExporter = exporter,
                onOpenBootPage = {},
                onRequestStoragePermission = {},
                onRestartServer = {},
            )
            val port = server.start()
            val token = server.authToken
            val base = "http://127.0.0.1:$port"

            // 1. GET /sessions/current?format=otlp&granularity=summary-and-errors & full & invalid 400/404
            val (codeSummary, bodySummary) = httpRequest(
                "GET",
                "$base/sessions/current?format=otlp&granularity=summary-and-errors",
                token,
            )
            assertEquals(200, codeSummary)
            val jsonSummary = JSONObject(bodySummary)
            assertTrue(jsonSummary.has("resourceSpans"))
            assertTrue(jsonSummary.has("resourceMetrics"))
            assertTrue(jsonSummary.has("resourceLogs"))
            assertFalse(bodySummary.contains("AC7_ROUTINE_INFO_MARKER"))
            assertTrue(bodySummary.contains("AC7_ERROR_WITH_SECRET"))
            assertFalse("Auth secret must be redacted from OTLP JSON", bodySummary.contains(authSecret))

            val (codeFull, bodyFull) = httpRequest(
                "GET",
                "$base/sessions/current?format=otlp&granularity=full",
                token,
            )
            assertEquals(200, codeFull)
            assertTrue(bodyFull.contains("AC7_ROUTINE_INFO_MARKER"))

            assertEquals(400, httpRequest("GET", "$base/sessions/current?format=otlp&granularity=bogus", token).first)
            assertEquals(404, httpRequest("GET", "$base/sessions/20990101-000000000-0001-dead?format=otlp", token).first)

            // 2. POST /event with traceparent header + JSON override precedence
            val hdrTrace = "4bf92f3577b34da6a3ce929d0e0e4736"
            val hdrSpan = "00f067aa0ba902b7"
            val (ev1Code, _) = httpRequest(
                "POST",
                "$base/event",
                token,
                extraHeaders = mapOf("traceparent" to "00-$hdrTrace-$hdrSpan-01"),
                body = """{"level":"WARN","source":"ac7.hdr","msg":"event from header trace"}""",
            )
            assertEquals(200, ev1Code)

            val jsonTrace = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            val jsonSpan = "bbbbbbbbbbbbbbbb"
            val (ev2Code, _) = httpRequest(
                "POST",
                "$base/event",
                token,
                extraHeaders = mapOf("traceparent" to "00-$hdrTrace-$hdrSpan-01"),
                body = """{"level":"WARN","source":"ac7.json","msg":"event from json trace","traceId":"$jsonTrace","spanId":"$jsonSpan"}""",
            )
            assertEquals(200, ev2Code)

            val sessionBundle = JSONObject(store.renderSessionBundle("current", "json")!!)
            val events = sessionBundle.getJSONArray("events")
            val evHdr = (0 until events.length()).map { events.getJSONObject(it) }.first { it.getString("source") == "ac7.hdr" }
            val evJson = (0 until events.length()).map { events.getJSONObject(it) }.first { it.getString("source") == "ac7.json" }
            assertEquals(hdrTrace, evHdr.getString("traceId"))
            assertEquals(hdrSpan, evHdr.getString("spanId"))
            assertEquals(jsonTrace, evJson.getString("traceId"))
            assertEquals(jsonSpan, evJson.getString("spanId"))

            // 3. POST /span auth, CSRF Origin, 400 validation, and successful recording
            assertEquals(
                403,
                httpRequest("POST", "$base/span", token = "", body = """{"name":"unauth"}""").first,
            )
            assertEquals(
                403,
                httpRequest(
                    "POST",
                    "$base/span",
                    token = token,
                    extraHeaders = mapOf("Origin" to "https://evil.example"),
                    body = """{"name":"csrf"}""",
                ).first,
            )
            assertEquals(400, httpRequest("POST", "$base/span", token, body = """{"name":"   "}""").first)
            assertEquals(400, httpRequest("POST", "$base/span", token, body = "{bad json").first)
            assertEquals(
                400,
                httpRequest("POST", "$base/span", token, body = """{"name":"bad.id","traceId":"not-hex"}""").first,
            )

            val (spanCode, spanResp) = httpRequest(
                "POST",
                "$base/span",
                token,
                extraHeaders = mapOf("traceparent" to "00-$hdrTrace-$hdrSpan-01"),
                body = JSONObject()
                    .put("name", "ac7.http_span")
                    .put("kind", "CLIENT")
                    .put("startMs", session.startedAtMs + 200L)
                    .put("endMs", session.startedAtMs + 450L)
                    .put("status", "OK")
                    .put("attributes", JSONObject().put("kucode.test", "yes").put("secret_leak", authSecret))
                    .toString(),
            )
            assertEquals(200, spanCode)
            val spanRespJson = JSONObject(spanResp)
            assertTrue(spanRespJson.getBoolean("ok"))
            assertEquals(hdrTrace, spanRespJson.getString("traceId"))
            assertEquals(hdrSpan, spanRespJson.getString("parentSpanId"))
            val recordedSpanId = spanRespJson.getString("spanId")
            assertNotEquals(hdrSpan, recordedSpanId)

            val recordedSpan = store.readSessionSpans("current").first { it.spanId == recordedSpanId }
            assertEquals("ac7.http_span", recordedSpan.name)
            assertEquals(hdrTrace, recordedSpan.traceId)
            assertEquals(hdrSpan, recordedSpan.parentSpanId)

            // 4. POST /otlp/flush -> 404 on unknown selector, 200 E2E flush to loopback OTLP collector
            assertEquals(
                404,
                httpRequest("POST", "$base/otlp/flush", token, body = """{"selector":"20990101-000000000-0001-dead"}""").first,
            )

            val (flushCode, flushResp) = httpRequest(
                "POST",
                "$base/otlp/flush",
                token,
                body = """{"selector":"current"}""",
            )
            assertEquals(200, flushCode)
            val flushJson = JSONObject(flushResp)
            assertTrue(flushJson.getBoolean("ok"))
            assertTrue(flushJson.getBoolean("enabled"))
            assertEquals(1, flushJson.getInt("flushed"))
            assertEquals(0, flushJson.getInt("remaining"))

            val pathsSeen = collector.requests.map { it.path }.toSet()
            assertTrue("Expected /v1/traces POST", pathsSeen.contains("/v1/traces"))
            assertTrue("Expected /v1/metrics POST", pathsSeen.contains("/v1/metrics"))
            assertTrue("Expected /v1/logs POST", pathsSeen.contains("/v1/logs"))
            for (req in collector.requests) {
                assertEquals("POST", req.method)
                assertEquals(authHeaderVal, req.headers["authorization"])
                assertTrue(req.headers["content-type"].orEmpty().contains("application/json"))
                assertFalse("Auth secret must never appear inside OTLP JSON body", req.body.contains(authSecret))
            }

            // 5. Redirect safety: 302 from collector must NOT follow redirect or leak auth header
            collector.requests.clear()
            collector.responseStatus = 302
            collector.redirectLocation = "http://127.0.0.1:${redirectSink.port}/steal"

            val (redirFlushCode, redirFlushResp) = httpRequest(
                "POST",
                "$base/otlp/flush",
                token,
                body = """{"selector":"current"}""",
            )
            assertEquals(200, redirFlushCode)
            assertFalse(JSONObject(redirFlushResp).getBoolean("ok"))
            assertEquals("Redirect target must receive zero requests", 0, redirectSink.requests.size)
        } finally {
            server?.stop()
            collector.close()
            redirectSink.close()
            deleteRecursivelyNoFollow(dir)
        }
    }

    @Test
    fun ac8_generatedKucodeLogsCliSupportsOtlpShowExportAndOtlpFlush() {
        // [AC-8] PosixRuntimeConfig generated kucode-logs CLI E2E against loopback LocalDiagnosticsServer
        val dir = Files.createTempDirectory("kucode-oracle-ac8").toFile()
        val collector = LoopbackHttpCollector()
        val envSecret = "cli_otlp_secret_" + "abcdef1234567890"
        var server: LocalDiagnosticsServer? = null

        try {
            val store = SessionLogStore(diagnosticsRootDir = File(dir, "diagnostics"))
            val session = store.startSession(
                pid = 9200,
                env = SessionLogStore.SessionEnvMetadata(
                    appVersion = "0.10.0-debug",
                    packageName = "dev.koduki.kucode.debug",
                    buildSha = "ac8sha456",
                ),
            )
            store.recordEvent(SessionLogStore.EventLevel.INFO, "cli.info", "AC8_ROUTINE_INFO_LOG")
            store.recordEvent(SessionLogStore.EventLevel.ERROR, "cli.err", "AC8_ERROR_LOG")

            val configStore = OtlpConfigStore(InMemoryStorage(), IdentityCipher())
            configStore.saveConfig(
                enabled = true,
                endpointUrl = "http://127.0.0.1:${collector.port}",
                granularity = OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS,
                unmeteredOnly = false,
                authHeaderName = "Authorization",
                authHeaderValue = "Bearer $envSecret",
                clearAuthHeader = false,
            )
            val exporter = OtlpOutboxExporter(
                sessionLogStore = store,
                outboxDir = File(dir, "otlp-outbox"),
                configStore = configStore,
            )

            server = LocalDiagnosticsServer(
                onRunDiagnostics = { "ok" },
                onGetLogs = { "ok" },
                onCopyToClipboard = { true },
                onListSessions = { store.listSessionsJson() },
                onGetSessionBundle = { selector, format -> store.renderSessionBundle(selector, format) },
                sessionLogStore = store,
                otlpOutboxExporter = exporter,
                onOpenBootPage = {},
                onRequestStoragePermission = {},
                onRestartServer = {},
            )
            val port = server.start()

            val scripts = PosixRuntimeConfig.buildHostCliHelperScripts()
            val logsScript = File(dir, "kucode-logs").apply { writeText(scripts.getValue("kucode-logs")) }

            fun runLogsCli(cwd: File, vararg args: String): Pair<Int, String> {
                val proc = ProcessBuilder("node", logsScript.absolutePath, *args).apply {
                    directory(cwd)
                    environment()["KUCODE_ADMIN_PORT"] = port.toString()
                    environment()["KUCODE_ADMIN_TOKEN"] = server.authToken
                }.start()
                val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val err = proc.errorStream.bufferedReader(Charsets.UTF_8).readText()
                val rc = proc.waitFor()
                return rc to (if (rc == 0) out else "$out\n$err")
            }

            // 1. kucode-logs show current --otlp (default summary-and-errors omits INFO)
            val (rcShow, outShow) = runLogsCli(dir, "show", "current", "--otlp")
            assertEquals("show current --otlp failed: $outShow", 0, rcShow)
            val otlpSummary = JSONObject(outShow)
            assertTrue(otlpSummary.has("resourceSpans"))
            assertTrue(outShow.contains("AC8_ERROR_LOG"))
            assertFalse(outShow.contains("AC8_ROUTINE_INFO_LOG"))

            // 2. kucode-logs show current --otlp --full (includes INFO)
            val (rcFull, outFull) = runLogsCli(dir, "show", "current", "--otlp", "--full")
            assertEquals("show current --otlp --full failed: $outFull", 0, rcFull)
            assertTrue(JSONObject(outFull).has("resourceLogs"))
            assertTrue(outFull.contains("AC8_ROUTINE_INFO_LOG"))

            // 3. kucode-logs export current <destPath> --otlp
            val explicitTarget = File(dir, "custom-otlp-export.json")
            val (rcExportExplicit, outExportExplicit) = runLogsCli(dir, "export", "current", explicitTarget.absolutePath, "--otlp")
            assertEquals("export explicit failed: $outExportExplicit", 0, rcExportExplicit)
            assertTrue(explicitTarget.isFile)
            assertTrue(JSONObject(explicitTarget.readText()).has("resourceSpans"))

            // 4. kucode-logs export <sessionId> --otlp (default filename kucode-session-<selector>-otlp.json)
            val defaultExportDir = File(dir, "export-cwd").apply { mkdirs() }
            val (rcExportDefault, outExportDefault) = runLogsCli(defaultExportDir, "export", session.sessionId, "--otlp")
            assertEquals("export default failed: $outExportDefault", 0, rcExportDefault)
            val expectedDefaultFile = File(defaultExportDir, "kucode-session-${session.sessionId}-otlp.json")
            assertTrue("Expected default OTLP filename ${expectedDefaultFile.name}", expectedDefaultFile.isFile)

            // 5. kucode-logs otlp-flush
            val flushCountBefore = collector.requests.size
            val (rcFlush, outFlush) = runLogsCli(dir, "otlp-flush")
            assertEquals("otlp-flush failed: $outFlush", 0, rcFlush)
            val flushJson = JSONObject(outFlush)
            assertTrue(flushJson.getBoolean("ok"))
            assertTrue(flushJson.getBoolean("enabled"))
            assertTrue(collector.requests.size > flushCountBefore)
            assertTrue(collector.requests.any { it.path == "/v1/traces" })
        } finally {
            server?.stop()
            collector.close()
            deleteRecursivelyNoFollow(dir)
        }
    }
}
