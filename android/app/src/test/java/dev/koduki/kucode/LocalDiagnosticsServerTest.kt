package dev.koduki.kucode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Category(MediumTest::class)
class LocalDiagnosticsServerTest {

    @Test
    fun enforcesConstantTimeTokenAndServesEndpointsAcrossRestarts() {
        val copied = AtomicReference("")
        val openedBoot = AtomicBoolean(false)
        val server = LocalDiagnosticsServer(
            onRunDiagnostics = { "# Report\nPASS" },
            onGetLogs = { "log-line-1" },
            onCopyToClipboard = { txt -> copied.set(txt); true },
            onOpenBootPage = { openedBoot.set(true) },
            onRequestStoragePermission = {},
            onRestartServer = {},
        )
        assertTrue(server.isTokenAuthorized(server.authToken))
        assertFalse(server.isTokenAuthorized(null))
        assertFalse(server.isTokenAuthorized(""))
        val flippedLastChar = if (server.authToken.last() == '0') '1' else '0'
        assertFalse(server.isTokenAuthorized(server.authToken.dropLast(1) + flippedLastChar))

        val port = server.start()
        try {
            // Unauthorized request must be rejected with 403
            val unauth = (URL("http://127.0.0.1:$port/diagnostics").openConnection() as HttpURLConnection)
            assertEquals(403, unauth.responseCode)
            unauth.disconnect()

            // Authorized GET /diagnostics
            val diag = (URL("http://127.0.0.1:$port/diagnostics").openConnection() as HttpURLConnection).apply {
                setRequestProperty("X-KuCode-Token", server.authToken)
            }
            assertEquals(200, diag.responseCode)
            assertEquals("# Report\nPASS", diag.inputStream.bufferedReader().readText())
            diag.disconnect()

            // Authorized POST /copy
            val copyConn = (URL("http://127.0.0.1:$port/copy").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("X-KuCode-Token", server.authToken)
            }
            copyConn.outputStream.use { it.write("hello-xr".toByteArray()) }
            assertEquals(200, copyConn.responseCode)
            assertEquals("hello-xr", copied.get())
            copyConn.disconnect()

            // Authorized POST /open-boot
            val bootConn = (URL("http://127.0.0.1:$port/open-boot").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("X-KuCode-Token", server.authToken)
            }
            assertEquals(200, bootConn.responseCode)
            assertTrue(openedBoot.get())
            bootConn.disconnect()
        } finally {
            server.stop()
        }

        // Server must restart cleanly after stop()
        val restartedPort = server.start()
        try {
            val logs = (URL("http://127.0.0.1:$restartedPort/logs").openConnection() as HttpURLConnection).apply {
                setRequestProperty("X-KuCode-Token", server.authToken)
            }
            assertEquals(200, logs.responseCode)
            assertEquals("log-line-1", logs.inputStream.bufferedReader().readText())
            logs.disconnect()
        } finally {
            server.stop()
        }
    }

    @Test
    fun rejectsOversizedRequestLineTooManyHeadersAndOversizedBody() {
        val server = LocalDiagnosticsServer(
            onRunDiagnostics = { "ok" },
            onGetLogs = { "ok" },
            onCopyToClipboard = { true },
            onOpenBootPage = {},
            onRequestStoragePermission = {},
            onRestartServer = {},
        )
        val port = server.start()
        try {
            // 1. Oversized request line -> 414
            val longPath = "a".repeat(LocalDiagnosticsServer.MAX_LINE_BYTES + 128)
            val statusLongLine = sendRawHttpStatus(port, "GET /$longPath HTTP/1.1\r\n\r\n")
            assertEquals(414, statusLongLine)

            // 2. Oversized header line -> 431
            val longHeaderVal = "v".repeat(LocalDiagnosticsServer.MAX_LINE_BYTES + 128)
            val statusLongHeader = sendRawHttpStatus(port, "GET /logs HTTP/1.1\r\nX-Big: $longHeaderVal\r\n\r\n")
            assertEquals(431, statusLongHeader)

            // 3. Too many headers -> 431
            val manyHeaders = buildString {
                append("GET /logs HTTP/1.1\r\n")
                for (i in 0..(LocalDiagnosticsServer.MAX_HEADERS + 5)) {
                    append("X-H-$i: 1\r\n")
                }
                append("\r\n")
            }
            assertEquals(431, sendRawHttpStatus(port, manyHeaders))

            // 4. Oversized Content-Length after auth -> 413
            val oversizedBodyReq = buildString {
                append("POST /copy HTTP/1.1\r\n")
                append("X-KuCode-Token: ${server.authToken}\r\n")
                append("Content-Length: ${LocalDiagnosticsServer.MAX_BODY_BYTES + 1}\r\n")
                append("\r\n")
            }
            assertEquals(413, sendRawHttpStatus(port, oversizedBodyReq))
        } finally {
            server.stop()
        }
    }

    @Test
    fun rejectsExcessConcurrentConnectionsWith503WithoutExhaustingThreads() {
        val holdWorker = CountDownLatch(1)
        val workerEntered = CountDownLatch(1)
        val server = LocalDiagnosticsServer(
            onRunDiagnostics = {
                workerEntered.countDown()
                holdWorker.await(5, TimeUnit.SECONDS)
                "done"
            },
            onGetLogs = { "ok" },
            onCopyToClipboard = { true },
            onOpenBootPage = {},
            onRequestStoragePermission = {},
            onRestartServer = {},
            maxWorkers = 1,
            maxQueuedConnections = 1,
            preAuthTimeoutMs = 1_500,
        )
        val port = server.start()
        val sockets = mutableListOf<Socket>()
        try {
            // Connection 1 occupies the single worker thread
            val s1 = Socket("127.0.0.1", port)
            sockets.add(s1)
            s1.getOutputStream().write("GET /diagnostics HTTP/1.1\r\nX-KuCode-Token: ${server.authToken}\r\n\r\n".toByteArray())
            s1.getOutputStream().flush()
            assertTrue("Worker should enter /diagnostics", workerEntered.await(3, TimeUnit.SECONDS))

            // Connection 2 fills the single queue slot
            val s2 = Socket("127.0.0.1", port)
            sockets.add(s2)
            s2.getOutputStream().write("GET /logs HTTP/1.1\r\nX-KuCode-Token: ${server.authToken}\r\n\r\n".toByteArray())
            s2.getOutputStream().flush()

            // Give accept loop a moment to enqueue s2
            Thread.sleep(100)

            // Connection 3 exceeds maxWorkers(1) + maxQueued(1) and must be rejected with 503
            val status3 = sendRawHttpStatus(port, "GET /logs HTTP/1.1\r\nX-KuCode-Token: ${server.authToken}\r\n\r\n")
            assertEquals(503, status3)

            // Release worker and confirm queued request completes and server stays healthy
            holdWorker.countDown()
            val resp1 = BufferedReader(InputStreamReader(s1.getInputStream())).readLine()
            assertTrue("Expected 200 for s1, got $resp1", resp1?.contains("200") == true)
            val resp2 = BufferedReader(InputStreamReader(s2.getInputStream())).readLine()
            assertTrue("Expected 200 for s2, got $resp2", resp2?.contains("200") == true)
        } finally {
            holdWorker.countDown()
            sockets.forEach { runCatching { it.close() } }
            server.stop()
        }
    }

    @Test
    fun servesAuthenticatedClipboardReadAndHandlesUnavailableAndOversizedPayloads() {
        val clipboard = AtomicReference<String?>("こんにちは\nKuCode クリップボード")
        val server = LocalDiagnosticsServer(
            onRunDiagnostics = { "ok" },
            onGetLogs = { "ok" },
            onCopyToClipboard = { txt -> clipboard.set(txt); true },
            onReadFromClipboard = { clipboard.get() },
            onOpenBootPage = {},
            onRequestStoragePermission = {},
            onRestartServer = {},
        )
        val port = server.start()
        try {
            // Unauthorized GET /clipboard -> 403
            val unauth = URL("http://127.0.0.1:$port/clipboard").openConnection() as HttpURLConnection
            assertEquals(403, unauth.responseCode)
            unauth.disconnect()

            // Authorized UTF-8 clipboard read -> 200
            val okConn = (URL("http://127.0.0.1:$port/clipboard").openConnection() as HttpURLConnection).apply {
                setRequestProperty("X-KuCode-Token", server.authToken)
            }
            assertEquals(200, okConn.responseCode)
            assertEquals("こんにちは\nKuCode クリップボード", okConn.inputStream.bufferedReader(Charsets.UTF_8).readText())
            okConn.disconnect()

            // Empty clipboard -> 200 with empty body
            clipboard.set("")
            val emptyConn = (URL("http://127.0.0.1:$port/clipboard").openConnection() as HttpURLConnection).apply {
                setRequestProperty("X-KuCode-Token", server.authToken)
            }
            assertEquals(200, emptyConn.responseCode)
            assertEquals("", emptyConn.inputStream.bufferedReader(Charsets.UTF_8).readText())
            emptyConn.disconnect()

            // Unavailable clipboard (null) -> 503
            clipboard.set(null)
            val unavailConn = (URL("http://127.0.0.1:$port/clipboard").openConnection() as HttpURLConnection).apply {
                setRequestProperty("X-KuCode-Token", server.authToken)
            }
            assertEquals(503, unavailConn.responseCode)
            unavailConn.disconnect()

            // Oversized clipboard (> MAX_BODY_BYTES) -> 413
            clipboard.set("x".repeat(LocalDiagnosticsServer.MAX_BODY_BYTES + 1))
            val bigConn = (URL("http://127.0.0.1:$port/clipboard").openConnection() as HttpURLConnection).apply {
                setRequestProperty("X-KuCode-Token", server.authToken)
            }
            assertEquals(413, bigConn.responseCode)
            bigConn.disconnect()
        } finally {
            server.stop()
        }
    }

    @Test
    fun clipboardHelperScriptsRoundTripWithLocalDiagnosticsServer() {
        val clipboard = AtomicReference<String?>("")
        val server = LocalDiagnosticsServer(
            onRunDiagnostics = { "ok" },
            onGetLogs = { "ok" },
            onCopyToClipboard = { txt -> clipboard.set(txt); true },
            onReadFromClipboard = { clipboard.get() },
            onOpenBootPage = {},
            onRequestStoragePermission = {},
            onRestartServer = {},
        )
        val tmpDir = java.nio.file.Files.createTempDirectory("kucode-clip-cli").toFile()
        val port = server.start()
        try {
            val scripts = PosixRuntimeConfig.buildClipboardHelperScripts()
            val copyScript = java.io.File(tmpDir, "kucode-copy").apply { writeText(scripts.getValue("kucode-copy")) }
            val pasteScript = java.io.File(tmpDir, "kucode-paste").apply { writeText(scripts.getValue("kucode-paste")) }

            // 1. kucode-copy via argv
            val argvProc = ProcessBuilder("node", copyScript.absolutePath, "hello", "xr", "world").apply {
                environment()["KUCODE_ADMIN_PORT"] = port.toString()
                environment()["KUCODE_ADMIN_TOKEN"] = server.authToken
            }.start()
            assertEquals(0, argvProc.waitFor())
            assertEquals("hello xr world", clipboard.get())

            // 2. kucode-copy via stdin (UTF-8 + multiline)
            val multiline = "一行目\n二行目\nfinal line"
            val stdinProc = ProcessBuilder("node", copyScript.absolutePath).apply {
                environment()["KUCODE_ADMIN_PORT"] = port.toString()
                environment()["KUCODE_ADMIN_TOKEN"] = server.authToken
            }.start()
            stdinProc.outputStream.use { it.write(multiline.toByteArray(Charsets.UTF_8)) }
            assertEquals(0, stdinProc.waitFor())
            assertEquals(multiline, clipboard.get())

            // 3. kucode-paste reads full payload back on stdout
            val pasteProc = ProcessBuilder("node", pasteScript.absolutePath).apply {
                environment()["KUCODE_ADMIN_PORT"] = port.toString()
                environment()["KUCODE_ADMIN_TOKEN"] = server.authToken
            }.start()
            val pasted = pasteProc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            assertEquals(0, pasteProc.waitFor())
            assertEquals(multiline, pasted)

            // 4. Large payload (~200 KiB) must flush completely to stdout without truncation
            val largeText = "0123456789abcdef\n".repeat(12_000)
            clipboard.set(largeText)
            val largePasteProc = ProcessBuilder("node", pasteScript.absolutePath).apply {
                environment()["KUCODE_ADMIN_PORT"] = port.toString()
                environment()["KUCODE_ADMIN_TOKEN"] = server.authToken
            }.start()
            val largeOut = largePasteProc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            assertEquals(0, largePasteProc.waitFor())
            assertEquals(largeText.length, largeOut.length)
            assertEquals(largeText, largeOut)

            // 5. Invalid token fails with non-zero exit and HTTP 403
            val badTokenProc = ProcessBuilder("node", pasteScript.absolutePath).apply {
                environment()["KUCODE_ADMIN_PORT"] = port.toString()
                environment()["KUCODE_ADMIN_TOKEN"] = "wrong-token"
            }.start()
            val badTokenErr = badTokenProc.errorStream.bufferedReader(Charsets.UTF_8).readText()
            assertTrue(badTokenProc.waitFor() != 0)
            assertTrue(badTokenErr.contains("403"))

            // 6. Missing env fails cleanly
            val noEnvProc = ProcessBuilder("node", copyScript.absolutePath, "x").apply {
                environment().remove("KUCODE_ADMIN_PORT")
                environment().remove("KUCODE_ADMIN_TOKEN")
            }.start()
            assertTrue(noEnvProc.waitFor() != 0)
        } finally {
            server.stop()
            deleteTreeNoFollow(tmpDir)
        }
    }

    @Test
    fun openUrlAndSessionLogsCliRoundTripWithStrictValidation() {
        val tmpDir = java.nio.file.Files.createTempDirectory("kucode-open-logs-cli").toFile()
        val openedUrls = mutableListOf<String>()
        val store = SessionLogStore(diagnosticsRootDir = java.io.File(tmpDir, "diag"))
        val session1 = store.startSession(
            pid = 9001,
            env = SessionLogStore.SessionEnvMetadata(deviceSummary = "Test XR Device"),
        )
        store.recordEvent(SessionLogStore.EventLevel.ERROR, "test.err", "previous session error")
        store.endSession("crash simulated", abnormal = true, exitCategory = "CRASH_JVM")

        val session2 = store.startSession(
            pid = 9002,
            env = SessionLogStore.SessionEnvMetadata(deviceSummary = "Test XR Device"),
        )

        val server = LocalDiagnosticsServer(
            onRunDiagnostics = { "ok" },
            onGetLogs = { "ok" },
            onCopyToClipboard = { true },
            onOpenExternalUrl = { url -> openedUrls.add(url); true },
            currentEditorPortProvider = { 18765 },
            onListSessions = { store.listSessionsJson() },
            onGetSessionBundle = { selector, format -> store.renderSessionBundle(selector, format) },
            onRecordClientEvent = { level, src, msg ->
                val lvl = runCatching { SessionLogStore.EventLevel.valueOf(level.uppercase()) }
                    .getOrDefault(SessionLogStore.EventLevel.INFO)
                store.recordEvent(lvl, src, msg)
            },
            onOpenBootPage = {},
            onRequestStoragePermission = {},
            onRestartServer = {},
        )
        val port = server.start()
        try {
            val scripts = PosixRuntimeConfig.buildHostCliHelperScripts()
            val openScript = java.io.File(tmpDir, "kucode-open").apply { writeText(scripts.getValue("kucode-open")) }
            val logsScript = java.io.File(tmpDir, "kucode-logs").apply { writeText(scripts.getValue("kucode-logs")) }

            // 1. kucode-open opens https://github.com/login/device cleanly
            val ghOpen = ProcessBuilder("node", openScript.absolutePath, "https://github.com/login/device").apply {
                environment()["KUCODE_ADMIN_PORT"] = port.toString()
                environment()["KUCODE_ADMIN_TOKEN"] = server.authToken
            }.start()
            assertEquals(0, ghOpen.waitFor())
            assertEquals(listOf("https://github.com/login/device"), openedUrls)

            // 2. kucode-open / server rejects editor port & token-bearing URLs with HTTP 400
            val selfPortOpen = ProcessBuilder("node", openScript.absolutePath, "http://127.0.0.1:18765/?folder=/workspace").apply {
                environment()["KUCODE_ADMIN_PORT"] = port.toString()
                environment()["KUCODE_ADMIN_TOKEN"] = server.authToken
            }.start()
            assertTrue(selfPortOpen.waitFor() != 0)
            assertEquals(1, openedUrls.size)

            val tokenLeakOpen = ProcessBuilder("node", openScript.absolutePath, "https://example.com/?tkn=secret123").apply {
                environment()["KUCODE_ADMIN_PORT"] = port.toString()
                environment()["KUCODE_ADMIN_TOKEN"] = server.authToken
            }.start()
            assertTrue(tokenLeakOpen.waitFor() != 0)
            assertEquals(1, openedUrls.size)

            // 3. POST /event records extension telemetry into active session
            val evConn = (URL("http://127.0.0.1:$port/event").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("X-KuCode-Token", server.authToken)
            }
            evConn.outputStream.use {
                it.write("""{"level":"WARN","source":"ext.terminal","msg":"Terminal closed: code=130"}""".toByteArray())
            }
            assertEquals(200, evConn.responseCode)
            evConn.disconnect()

            // 4. kucode-logs list shows both current and previous abnormal sessions
            val listProc = ProcessBuilder("node", logsScript.absolutePath, "list").apply {
                environment()["KUCODE_ADMIN_PORT"] = port.toString()
                environment()["KUCODE_ADMIN_TOKEN"] = server.authToken
            }.start()
            val listOut = listProc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            assertEquals(0, listProc.waitFor())
            assertTrue(listOut.contains(session1.sessionId))
            assertTrue(listOut.contains(session2.sessionId))
            assertTrue(listOut.contains("CRASH_JVM"))

            // 5. kucode-logs show prev-abnormal outputs the AI bundle for session1
            val showPrevProc = ProcessBuilder("node", logsScript.absolutePath, "show", "prev-abnormal").apply {
                environment()["KUCODE_ADMIN_PORT"] = port.toString()
                environment()["KUCODE_ADMIN_TOKEN"] = server.authToken
            }.start()
            val prevOut = showPrevProc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            assertEquals(0, showPrevProc.waitFor())
            assertTrue(prevOut.contains(session1.sessionId))
            assertTrue(prevOut.contains("previous session error"))

            // 6. kucode-logs export writes current session AI bundle to file
            val exportTarget = java.io.File(tmpDir, "exported-ai.md")
            val exportProc = ProcessBuilder("node", logsScript.absolutePath, "export", "latest", exportTarget.absolutePath).apply {
                environment()["KUCODE_ADMIN_PORT"] = port.toString()
                environment()["KUCODE_ADMIN_TOKEN"] = server.authToken
            }.start()
            assertEquals(0, exportProc.waitFor())
            assertTrue(exportTarget.isFile)
            assertTrue(exportTarget.readText().contains("Terminal closed: code=130"))
        } finally {
            store.endSession("cleanup")
            server.stop()
            deleteTreeNoFollow(tmpDir)
        }
    }

    @Test
    fun enforcesCsrfHeadersSmugglingGuardsSlowlorisDeadlineAndAuditRateLimiting() {
        val audit403Count = java.util.concurrent.atomic.AtomicInteger(0)
        val seenSelectors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = LocalDiagnosticsServer(
            onRunDiagnostics = { "ok" },
            onGetLogs = { "ok" },
            onCopyToClipboard = { true },
            onGetSessionBundle = { selector, _ ->
                seenSelectors.add(selector)
                "bundle:$selector"
            },
            onAuditRequest = { _, _, status, _, _ ->
                if (status == 403) audit403Count.incrementAndGet()
            },
            onOpenBootPage = {},
            onRequestStoragePermission = {},
            onRestartServer = {},
            preAuthTimeoutMs = 400,
        )
        val port = server.start()
        try {
            // 1. Browser Origin / Sec-Fetch-Site / non-loopback Host rejected with 403 even with valid token
            assertEquals(
                403,
                sendRawHttpStatus(
                    port,
                    "GET /logs HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nOrigin: https://evil.example\r\nX-KuCode-Token: ${server.authToken}\r\n\r\n",
                ),
            )
            assertEquals(
                403,
                sendRawHttpStatus(
                    port,
                    "GET /logs HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nSec-Fetch-Site: cross-site\r\nX-KuCode-Token: ${server.authToken}\r\n\r\n",
                ),
            )
            assertEquals(
                403,
                sendRawHttpStatus(
                    port,
                    "GET /logs HTTP/1.1\r\nHost: attacker.example:$port\r\nX-KuCode-Token: ${server.authToken}\r\n\r\n",
                ),
            )

            // 2. Request smuggling guards: Transfer-Encoding -> 501, duplicate Content-Length -> 400
            assertEquals(
                501,
                sendRawHttpStatus(
                    port,
                    "POST /copy HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nX-KuCode-Token: ${server.authToken}\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
                ),
            )
            assertEquals(
                400,
                sendRawHttpStatus(
                    port,
                    "POST /copy HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 2\r\nContent-Length: 3\r\nX-KuCode-Token: ${server.authToken}\r\n\r\nhi",
                ),
            )

            // 3. Premature EOF on declared Content-Length -> 400
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3_000
                s.getOutputStream().write(
                    "POST /copy HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nX-KuCode-Token: ${server.authToken}\r\nContent-Length: 50\r\n\r\nshort".toByteArray(Charsets.US_ASCII),
                )
                s.getOutputStream().flush()
                s.shutdownOutput()
                val statusLine = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII)).readLine()
                assertTrue("Expected 400 on premature EOF, got $statusLine", statusLine?.contains("400") == true)
            }

            // 4. Slowloris trickle exceeds wall-clock preAuthTimeoutMs -> 408
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3_000
                val out = s.getOutputStream()
                out.write("GET /logs HTTP/1.1\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                for (i in 0 until 8) {
                    Thread.sleep(90)
                    runCatching {
                        out.write("X-Slow-$i: a\r\n".toByteArray(Charsets.US_ASCII))
                        out.flush()
                    }
                }
                val statusLine = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII)).readLine()
                assertTrue("Expected 408 on slowloris trickle, got $statusLine", statusLine?.contains("408") == true)
            }

            // 5. Literal '+' in session path segment must NOT be mangled to space
            val plusConn = (URL("http://127.0.0.1:$port/sessions/sess+2026%2B1?format=ai").openConnection() as HttpURLConnection).apply {
                setRequestProperty("X-KuCode-Token", server.authToken)
            }
            assertEquals(200, plusConn.responseCode)
            assertEquals("bundle:sess+2026+1", plusConn.inputStream.bufferedReader(Charsets.UTF_8).readText())
            plusConn.disconnect()

            // 6. Burst of 403s is rate-limited in audit callback
            repeat(15) {
                assertEquals(403, sendRawHttpStatus(port, "GET /logs HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n"))
            }
            assertTrue("Audit 403 count should be throttled (<= 5), got ${audit403Count.get()}", audit403Count.get() <= 5)
        } finally {
            server.stop()
        }
    }

    private fun sendRawHttpStatus(port: Int, rawRequest: String): Int {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val out = socket.getOutputStream()
            runCatching {
                out.write(rawRequest.toByteArray(Charsets.US_ASCII))
                out.flush()
            }
            val statusLine = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine()
                ?: return -1
            return statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        }
    }
}

