package dev.koduki.kucode

import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Minimal loopback-only (127.0.0.1) token-authenticated HTTP server hosted inside
 * the Android app so the embedded extension (`kucode-tools`) can trigger
 * on-device diagnostics, clipboard copy/paste, storage permissions, or boot/CLI screen toggle.
 *
 * Security hardening for pre-authentication input:
 * - Bounded worker thread pool & queue so hostile local apps cannot exhaust threads/memory
 * - Strict caps on line length, header count, total header bytes, and request body size
 * - Short pre-auth socket read timeout (extended only after constant-time token verification)
 * - Constant-time token comparison via [MessageDigest.isEqual]
 */
class LocalDiagnosticsServer(
    private val onRunDiagnostics: suspend () -> String,
    private val onGetLogs: () -> String,
    private val onCopyToClipboard: (String) -> Boolean,
    private val onReadFromClipboard: () -> String? = { null },
    private val onOpenBootPage: () -> Unit,
    private val onRequestStoragePermission: () -> Unit,
    private val onRestartServer: () -> Unit,
    private val maxWorkers: Int = DEFAULT_MAX_WORKERS,
    private val maxQueuedConnections: Int = DEFAULT_MAX_QUEUED_CONNECTIONS,
    private val preAuthTimeoutMs: Int = DEFAULT_PRE_AUTH_TIMEOUT_MS,
    private val postAuthTimeoutMs: Int = DEFAULT_POST_AUTH_TIMEOUT_MS,
) {
    val authToken: String = generateConnectionToken()
    private val authTokenBytes: ByteArray = authToken.toByteArray(Charsets.US_ASCII)

    private val lifecycleLock = Any()

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private var acceptThread: Thread? = null
    private var workerPool: ThreadPoolExecutor? = null

    fun start(): Int = synchronized(lifecycleLock) {
        if (running && port > 0) return port

        val socket = ServerSocket(0, DEFAULT_BACKLOG, InetAddress.getByName("127.0.0.1"))
        val pool = createWorkerPool(maxWorkers, maxQueuedConnections)
        serverSocket = socket
        workerPool = pool
        port = socket.localPort
        running = true

        val acceptor = Thread({
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    pool.execute { handleClient(client) }
                } catch (_: RejectedExecutionException) {
                    rejectOverloadedClient(client)
                }
            }
        }, "kucode-diag-accept").apply {
            isDaemon = true
            start()
        }
        acceptThread = acceptor
        return port
    }

    fun stop() = synchronized(lifecycleLock) {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
        acceptThread = null
        workerPool?.shutdownNow()
        workerPool = null
    }

    private fun rejectOverloadedClient(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 500
                writeResponse(s.getOutputStream(), 503, "Service Unavailable")
            }
        } catch (_: IOException) {
            // Best-effort rejection on overloaded queue
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = preAuthTimeoutMs
            val input = BufferedInputStream(s.getInputStream())
            val output = s.getOutputStream()

            val requestLine = try {
                readBoundedLine(input, MAX_LINE_BYTES) ?: return
            } catch (limit: HttpLimitExceededException) {
                safeWriteResponse(output, 414, limit.message ?: "URI Too Long")
                return
            } catch (_: SocketTimeoutException) {
                safeWriteResponse(output, 408, "Request Timeout")
                return
            } catch (_: IOException) {
                return
            }

            val parts = requestLine.split(' ')
            if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                safeWriteResponse(output, 400, "Bad Request")
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            val headers = mutableMapOf<String, String>()
            var totalHeaderBytes = 0
            var headerCount = 0
            try {
                while (true) {
                    val line = readBoundedLine(input, MAX_LINE_BYTES) ?: break
                    if (line.isEmpty()) break
                    headerCount++
                    totalHeaderBytes += line.length + 2
                    if (headerCount > MAX_HEADERS || totalHeaderBytes > MAX_TOTAL_HEADER_BYTES) {
                        safeWriteResponse(output, 431, "Request Header Fields Too Large")
                        return
                    }
                    val colon = line.indexOf(':')
                    if (colon <= 0) {
                        safeWriteResponse(output, 400, "Bad Request")
                        return
                    }
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            } catch (_: HttpLimitExceededException) {
                safeWriteResponse(output, 431, "Request Header Fields Too Large")
                return
            } catch (_: SocketTimeoutException) {
                safeWriteResponse(output, 408, "Request Timeout")
                return
            } catch (_: IOException) {
                return
            }

            if (!isTokenAuthorized(headers["x-kucode-token"])) {
                safeWriteResponse(output, 403, "Forbidden")
                return
            }

            s.soTimeout = postAuthTimeoutMs

            val contentLengthHeader = headers["content-length"]
            val contentLength = if (contentLengthHeader == null) {
                0
            } else {
                val parsed = contentLengthHeader.toIntOrNull()
                if (parsed == null || parsed < 0) {
                    safeWriteResponse(output, 400, "Invalid Content-Length")
                    return
                }
                if (parsed > MAX_BODY_BYTES) {
                    safeWriteResponse(output, 413, "Payload Too Large")
                    return
                }
                parsed
            }

            val body = if (contentLength > 0) {
                try {
                    val buf = ByteArray(contentLength)
                    var offset = 0
                    while (offset < contentLength) {
                        val read = input.read(buf, offset, contentLength - offset)
                        if (read <= 0) break
                        offset += read
                    }
                    String(buf, 0, offset, Charsets.UTF_8)
                } catch (_: SocketTimeoutException) {
                    safeWriteResponse(output, 408, "Request Timeout")
                    return
                } catch (_: IOException) {
                    return
                }
            } else {
                ""
            }

            try {
                when {
                    method == "GET" && path == "/diagnostics" -> {
                        val report = runBlocking { onRunDiagnostics() }
                        writeResponse(output, 200, report)
                    }

                    method == "GET" && path == "/logs" -> {
                        writeResponse(output, 200, onGetLogs())
                    }

                    method == "POST" && path == "/copy" -> {
                        val ok = onCopyToClipboard(body)
                        writeResponse(output, if (ok) 200 else 500, if (ok) "ok" else "clipboard unavailable")
                    }

                    method == "GET" && path == "/clipboard" -> {
                        val text = onReadFromClipboard()
                        when {
                            text == null -> writeResponse(output, 503, "clipboard unavailable")
                            text.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES ->
                                writeResponse(output, 413, "Payload Too Large")
                            else -> writeResponse(output, 200, text)
                        }
                    }

                    method == "POST" && path == "/open-boot" -> {
                        onOpenBootPage()
                        writeResponse(output, 200, "ok")
                    }

                    method == "POST" && path == "/storage-permission" -> {
                        onRequestStoragePermission()
                        writeResponse(output, 200, "ok")
                    }

                    method == "POST" && path == "/restart" -> {
                        onRestartServer()
                        writeResponse(output, 200, "ok")
                    }

                    else -> writeResponse(output, 404, "Not Found")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                safeWriteResponse(output, 500, "Internal Error: ${e.message}")
            }
        }
    }

    internal fun isTokenAuthorized(providedToken: String?): Boolean {
        if (providedToken == null) return false
        return MessageDigest.isEqual(
            providedToken.toByteArray(Charsets.UTF_8),
            authTokenBytes,
        )
    }

    private fun readBoundedLine(input: BufferedInputStream, maxBytes: Int): String? {
        val baos = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString(Charsets.UTF_8.name())
            }
            if (b == '\n'.code) {
                val bytes = baos.toByteArray()
                val len = if (prev == '\r'.code && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.UTF_8)
            }
            if (baos.size() >= maxBytes) {
                throw HttpLimitExceededException("Line exceeds $maxBytes bytes")
            }
            baos.write(b)
            prev = b
        }
    }

    private fun safeWriteResponse(output: OutputStream, statusCode: Int, body: String) {
        try {
            writeResponse(output, statusCode, body)
        } catch (_: IOException) {
            // Client already closed connection
        }
    }

    private fun writeResponse(output: OutputStream, statusCode: Int, body: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            408 -> "Request Timeout"
            413 -> "Payload Too Large"
            414 -> "URI Too Long"
            431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"
            else -> "Internal Server Error"
        }
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        output.write(header)
        output.write(bodyBytes)
        output.flush()
    }

    private class HttpLimitExceededException(message: String) : IOException(message)

    companion object {
        internal const val MAX_BODY_BYTES = 1024 * 1024
        internal const val MAX_LINE_BYTES = 8 * 1024
        internal const val MAX_HEADERS = 64
        internal const val MAX_TOTAL_HEADER_BYTES = 32 * 1024
        internal const val DEFAULT_MAX_WORKERS = 8
        internal const val DEFAULT_MAX_QUEUED_CONNECTIONS = 16
        private const val DEFAULT_BACKLOG = 16
        private const val DEFAULT_PRE_AUTH_TIMEOUT_MS = 5_000
        private const val DEFAULT_POST_AUTH_TIMEOUT_MS = 60_000

        private fun createWorkerPool(maxWorkers: Int, maxQueued: Int): ThreadPoolExecutor {
            val safeWorkers = maxWorkers.coerceAtLeast(1)
            val safeQueued = maxQueued.coerceAtLeast(1)
            val counter = AtomicInteger(1)
            val factory = ThreadFactory { runnable ->
                Thread(runnable, "kucode-diag-worker-${counter.getAndIncrement()}").apply {
                    isDaemon = true
                }
            }
            return ThreadPoolExecutor(
                safeWorkers,
                safeWorkers,
                30L,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(safeQueued),
                factory,
                ThreadPoolExecutor.AbortPolicy(),
            ).apply {
                allowCoreThreadTimeOut(true)
            }
        }
    }
}

internal fun generateConnectionToken(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
