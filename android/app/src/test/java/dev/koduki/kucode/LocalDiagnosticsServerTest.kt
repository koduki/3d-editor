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
