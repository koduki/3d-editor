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
    private val onOpenExternalUrl: (String) -> Boolean = { false },
    private val currentEditorPortProvider: () -> Int = { 0 },
    private val onListSessions: () -> String = { "{\"sessions\":[]}" },
    private val onGetSessionBundle: (selector: String, format: String) -> String? = { _, _ -> null },
    private val onRecordClientEvent: (level: String, source: String, message: String) -> Unit = { _, _, _ -> },
    private val onAuditRequest: (method: String, path: String, statusCode: Int, durationMs: Long, detail: String) -> Unit = { _, _, _, _, _ -> },
    private val onOpenBootPage: () -> Unit,
    private val onRequestStoragePermission: () -> Unit,
    private val onRestartServer: () -> Unit,
    private val onAndroidRequest: ((String, String, String) -> Pair<Int, String>)? = null,
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

    private val unauthorizedAuditLock = Any()
    private var unauthorizedAuditWindowStartMs: Long = 0L
    private var unauthorizedAuditCountInWindow: Int = 0

    private fun shouldEmitUnauthorizedAudit(): Boolean = synchronized(unauthorizedAuditLock) {
        val now = System.currentTimeMillis()
        if (now - unauthorizedAuditWindowStartMs >= UNAUTHORIZED_AUDIT_WINDOW_MS) {
            unauthorizedAuditWindowStartMs = now
            unauthorizedAuditCountInWindow = 1
            true
        } else if (unauthorizedAuditCountInWindow < UNAUTHORIZED_AUDIT_MAX_PER_WINDOW) {
            unauthorizedAuditCountInWindow++
            true
        } else {
            false
        }
    }

    private fun handleClient(socket: Socket) {
        val t0 = System.currentTimeMillis()
        val authDeadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(preAuthTimeoutMs.toLong())
        socket.use { s ->
            fun applyPreAuthRemainingTimeout() {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(authDeadlineNs - System.nanoTime())
                if (remainingMs <= 0L) throw SocketTimeoutException("Pre-auth deadline exceeded")
                s.soTimeout = remainingMs.coerceAtMost(preAuthTimeoutMs.toLong()).toInt().coerceAtLeast(1)
            }

            val input = BufferedInputStream(s.getInputStream())
            val output = s.getOutputStream()

            val requestLine = try {
                applyPreAuthRemainingTimeout()
                readBoundedLine(input, MAX_LINE_BYTES) { applyPreAuthRemainingTimeout() }?.first ?: return
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
            val rawTarget = parts[1]
            val path = rawTarget.substringBefore('?')
            val rawQuery = if ('?' in rawTarget) rawTarget.substringAfter('?') else ""

            val headers = mutableMapOf<String, String>()
            var totalHeaderBytes = 0
            var headerCount = 0
            try {
                while (true) {
                    applyPreAuthRemainingTimeout()
                    val readResult = readBoundedLine(input, MAX_LINE_BYTES) { applyPreAuthRemainingTimeout() } ?: break
                    val (line, lineBytes) = readResult
                    if (line.isEmpty()) break
                    headerCount++
                    totalHeaderBytes += lineBytes + 2
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
                    if (key == "content-length" && headers.containsKey(key)) {
                        safeWriteResponse(output, 400, "Duplicate Content-Length")
                        return
                    }
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

            // Reject browser-originated requests (CSRF / DNS-rebinding defense-in-depth)
            if (headers["origin"] != null || headers["sec-fetch-site"] != null) {
                safeWriteResponse(output, 403, "Forbidden")
                if (shouldEmitUnauthorizedAudit()) {
                    runCatching { onAuditRequest(method, path, 403, System.currentTimeMillis() - t0, "browser_origin_rejected") }
                }
                return
            }
            val hostHeader = headers["host"]
            if (hostHeader != null && !isAllowedLoopbackHost(hostHeader)) {
                safeWriteResponse(output, 403, "Forbidden")
                if (shouldEmitUnauthorizedAudit()) {
                    runCatching { onAuditRequest(method, path, 403, System.currentTimeMillis() - t0, "invalid_host") }
                }
                return
            }

            if (!isTokenAuthorized(headers["x-kucode-token"])) {
                safeWriteResponse(output, 403, "Forbidden")
                if (shouldEmitUnauthorizedAudit()) {
                    runCatching { onAuditRequest(method, path, 403, System.currentTimeMillis() - t0, "unauthorized") }
                }
                return
            }

            s.soTimeout = postAuthTimeoutMs

            if (headers["transfer-encoding"] != null) {
                safeWriteResponse(output, 501, "Transfer-Encoding Not Supported")
                return
            }

            val contentLengthHeader = headers["content-length"]
            val contentLength = if (contentLengthHeader == null) {
                0
            } else {
                val parsed = contentLengthHeader.toLongOrNull()
                if (parsed == null || parsed < 0L) {
                    safeWriteResponse(output, 400, "Invalid Content-Length")
                    return
                }
                if (parsed > MAX_BODY_BYTES.toLong()) {
                    safeWriteResponse(output, 413, "Payload Too Large")
                    return
                }
                parsed.toInt()
            }

            val bodyBytes = if (contentLength > 0) {
                try {
                    val buf = ByteArray(contentLength)
                    var offset = 0
                    while (offset < contentLength) {
                        val read = input.read(buf, offset, contentLength - offset)
                        if (read <= 0) break
                        offset += read
                    }
                    if (offset < contentLength) {
                        safeWriteResponse(output, 400, "Incomplete Request Body")
                        return
                    }
                    buf
                } catch (_: SocketTimeoutException) {
                    safeWriteResponse(output, 408, "Request Timeout")
                    return
                } catch (_: IOException) {
                    return
                }
            } else {
                ByteArray(0)
            }
            val body = String(bodyBytes, Charsets.UTF_8)

            fun audit(statusCode: Int, detail: String = "") {
                if (path != "/event") {
                    runCatching { onAuditRequest(method, path, statusCode, System.currentTimeMillis() - t0, detail) }
                }
            }

            fun respond(statusCode: Int, text: String, contentType: String = "text/plain; charset=utf-8"): Int {
                val bytes = text.toByteArray(Charsets.UTF_8)
                safeWriteResponseBytes(output, statusCode, bytes, contentType)
                return bytes.size
            }

            try {
                when {
                    path.startsWith("/api/v1/android/") && onAndroidRequest != null -> {
                        val (code, json) = onAndroidRequest.invoke(method, path, body)
                        respond(code, json, contentType = "application/json; charset=utf-8")
                        audit(code)
                    }

                    method == "GET" && path == "/diagnostics" -> {
                        val report = runBlocking { onRunDiagnostics() }
                        val byteLen = respond(200, report)
                        audit(200, "bytes=$byteLen")
                    }

                    method == "GET" && path == "/logs" -> {
                        val logs = onGetLogs()
                        val byteLen = respond(200, logs)
                        audit(200, "bytes=$byteLen")
                    }

                    method == "GET" && path == "/sessions" -> {
                        val sessionsJson = onListSessions()
                        respond(200, sessionsJson, contentType = "application/json; charset=utf-8")
                        audit(200)
                    }

                    method == "GET" && path.startsWith("/sessions/") -> {
                        val rawSelector = path.removePrefix("/sessions/")
                        val selector = decodeUrlPathSegment(rawSelector)
                        val format = parseQueryParam(rawQuery, "format") ?: "ai"
                        val bundle = onGetSessionBundle(selector, format)
                        if (bundle == null) {
                            respond(404, "Session not found")
                            audit(404, "selector=$selector")
                        } else {
                            val ct = if (format.equals("json", ignoreCase = true)) {
                                "application/json; charset=utf-8"
                            } else {
                                "text/plain; charset=utf-8"
                            }
                            respond(200, bundle, contentType = ct)
                            audit(200, "selector=$selector,format=$format")
                        }
                    }

                    method == "POST" && path == "/open-url" -> {
                        val targetUrl = body.trim()
                        val parsedUri = runCatching { java.net.URI(targetUrl) }.getOrNull()
                        val editorPort = currentEditorPortProvider()
                        val safe = parsedUri != null && PosixRuntimeConfig.isSafeExternalBrowserUri(
                            scheme = parsedUri.scheme,
                            host = parsedUri.host,
                            port = parsedUri.port,
                            rawQuery = parsedUri.rawQuery,
                            editorServerPort = editorPort,
                        )
                        if (!safe) {
                            respond(400, "Invalid or disallowed external URL")
                            audit(400, "rejected_url")
                        } else {
                            val launched = onOpenExternalUrl(targetUrl)
                            val code = if (launched) 200 else 500
                            respond(code, if (launched) "ok" else "failed to launch external browser")
                            audit(code, LogRedactor.redact(targetUrl))
                        }
                    }

                    method == "POST" && path == "/event" -> {
                        val trimmed = body.trim()
                        if (trimmed.isNotEmpty()) {
                            val json = if (trimmed.startsWith("{")) {
                                runCatching { org.json.JSONObject(trimmed) }.getOrNull()
                            } else {
                                null
                            }
                            if (json != null) {
                                val lvl = json.optString("level", "INFO")
                                val src = json.optString("source", "ext.kucode")
                                val msg = json.optString("msg", "")
                                if (msg.isNotBlank()) {
                                    onRecordClientEvent(lvl, src, msg)
                                }
                            } else {
                                onRecordClientEvent("INFO", "ext.kucode", trimmed)
                            }
                        }
                        respond(200, "ok")
                    }

                    method == "POST" && path == "/copy" -> {
                        val ok = onCopyToClipboard(body)
                        val code = if (ok) 200 else 500
                        respond(code, if (ok) "ok" else "clipboard unavailable")
                        audit(code, "bytes=${bodyBytes.size}")
                    }

                    method == "GET" && path == "/clipboard" -> {
                        val text = onReadFromClipboard()
                        if (text == null) {
                            respond(503, "clipboard unavailable")
                            audit(503)
                        } else {
                            val encoded = text.toByteArray(Charsets.UTF_8)
                            if (encoded.size > MAX_BODY_BYTES) {
                                respond(413, "Payload Too Large")
                                audit(413)
                            } else {
                                safeWriteResponseBytes(output, 200, encoded, "text/plain; charset=utf-8")
                                audit(200, "bytes=${encoded.size}")
                            }
                        }
                    }

                    method == "POST" && path == "/open-boot" -> {
                        onOpenBootPage()
                        respond(200, "ok")
                        audit(200)
                    }

                    method == "POST" && path == "/storage-permission" -> {
                        onRequestStoragePermission()
                        respond(200, "ok")
                        audit(200)
                    }

                    method == "POST" && path == "/restart" -> {
                        onRestartServer()
                        respond(200, "ok")
                        audit(200)
                    }

                    else -> {
                        respond(404, "Not Found")
                        audit(404)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                safeWriteResponse(output, 500, "Internal Error: ${LogRedactor.redact(e.message.orEmpty())}")
                audit(500, e.javaClass.simpleName)
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

    private fun isAllowedLoopbackHost(rawHost: String): Boolean {
        val h = rawHost.trim().lowercase()
        val hostPart = when {
            h.startsWith("[::1]") -> h.removePrefix("[::1]").let { rest ->
                if (rest.isEmpty() || (rest.startsWith(":") && rest.substring(1).toIntOrNull() != null)) return true
                return false
            }
            ':' in h -> {
                val portPart = h.substringAfterLast(':')
                if (portPart.toIntOrNull() == null) return false
                h.substringBeforeLast(':')
            }
            else -> h
        }
        return hostPart == "127.0.0.1" || hostPart == "localhost"
    }

    private fun decodeUrlPathSegment(raw: String): String {
        if ('%' !in raw) return raw
        val out = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '%' && i + 2 < raw.length) {
                val hex = raw.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(raw[i].code and 0xFF)
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun readBoundedLine(
        input: BufferedInputStream,
        maxBytes: Int,
        onBeforeRead: () -> Unit = {},
    ): Pair<String, Int>? {
        val baos = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            onBeforeRead()
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString(Charsets.UTF_8.name()) to baos.size()
            }
            if (b == '\n'.code) {
                val bytes = baos.toByteArray()
                val len = if (prev == '\r'.code && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.UTF_8) to len
            }
            if (baos.size() >= maxBytes) {
                throw HttpLimitExceededException("Line exceeds $maxBytes bytes")
            }
            baos.write(b)
            prev = b
        }
    }

    private fun safeWriteResponse(output: OutputStream, statusCode: Int, body: String) {
        safeWriteResponseBytes(output, statusCode, body.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")
    }

    private fun safeWriteResponseBytes(
        output: OutputStream,
        statusCode: Int,
        bodyBytes: ByteArray,
        contentType: String,
    ) {
        try {
            writeResponseBytes(output, statusCode, bodyBytes, contentType)
        } catch (_: IOException) {
            // Client already closed connection
        }
    }

    private fun parseQueryParam(rawQuery: String, key: String): String? {
        if (rawQuery.isEmpty()) return null
        for (pair in rawQuery.split('&')) {
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            if (k == key) {
                val rawVal = if (eq >= 0) pair.substring(eq + 1) else ""
                return runCatching { java.net.URLDecoder.decode(rawVal, Charsets.UTF_8.name()) }.getOrDefault(rawVal)
            }
        }
        return null
    }

    private fun writeResponse(
        output: OutputStream,
        statusCode: Int,
        body: String,
        contentType: String = "text/plain; charset=utf-8",
    ) {
        writeResponseBytes(output, statusCode, body.toByteArray(Charsets.UTF_8), contentType)
    }

    private fun writeResponseBytes(
        output: OutputStream,
        statusCode: Int,
        bodyBytes: ByteArray,
        contentType: String,
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            202 -> "Accepted"
            409 -> "Conflict"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            408 -> "Request Timeout"
            413 -> "Payload Too Large"
            414 -> "URI Too Long"
            431 -> "Request Header Fields Too Large"
            501 -> "Not Implemented"
            503 -> "Service Unavailable"
            else -> "Internal Server Error"
        }
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
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
        private const val UNAUTHORIZED_AUDIT_WINDOW_MS = 5_000L
        private const val UNAUTHORIZED_AUDIT_MAX_PER_WINDOW = 5

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
