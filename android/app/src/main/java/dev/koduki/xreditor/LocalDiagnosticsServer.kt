package dev.koduki.xreditor

import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Minimal loopback-only (127.0.0.1) token-authenticated HTTP server hosted inside
 * the Android app so the embedded VS Code extension (`xr-editor-tools`) can trigger
 * on-device diagnostics, clipboard copy, storage permissions, or boot/CLI screen toggle.
 */
class LocalDiagnosticsServer(
    private val onRunDiagnostics: suspend () -> String,
    private val onGetLogs: () -> String,
    private val onCopyToClipboard: (String) -> Boolean,
    private val onOpenBootPage: () -> Unit,
    private val onRequestStoragePermission: () -> Unit,
    private val onRestartServer: () -> Unit,
) {
    val authToken: String = generateToken()

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private val executor = Executors.newCachedThreadPool()

    fun start(): Int {
        if (running && port > 0) return port
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        running = true

        executor.execute {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    break
                }
                executor.execute { handleClient(client) }
            }
        }
        return port
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 60_000
            val input = BufferedInputStream(s.getInputStream())
            val output = s.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                writeResponse(output, 400, "Bad Request")
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            if (headers["x-xr-token"] != authToken) {
                writeResponse(output, 403, "Forbidden")
                return
            }

            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
            val body = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var offset = 0
                while (offset < contentLength) {
                    val read = input.read(buf, offset, contentLength - offset)
                    if (read <= 0) break
                    offset += read
                }
                String(buf, 0, offset, Charsets.UTF_8)
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
            } catch (t: Throwable) {
                writeResponse(output, 500, "Internal Error: ${t.message}")
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
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
            baos.write(b)
            prev = b
        }
    }

    private fun writeResponse(output: OutputStream, statusCode: Int, body: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
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

    companion object {
        private const val MAX_BODY_BYTES = 1024 * 1024

        private fun generateToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
