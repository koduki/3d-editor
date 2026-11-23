package dev.koduki.kucode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

internal enum class ServerStartup { READY, EXITED, TIMED_OUT }

/**
 * Pure-JVM launcher for the embedded openvscode-server process (under PRoot in production,
 * or injectable process starter in unit tests).
 *
 * Handles atomic `.editor-token` creation, log draining with token redaction,
 * startup health verification, automatic `PROOT_NO_SECCOMP=1` retry on early exit,
 * and reliable subprocess termination.
 */
internal class OpenVsCodeLauncher(
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit = {},
    private val tokenGenerator: () -> String = ::generateConnectionToken,
    private val portPicker: () -> Int = ::pickStableLocalPort,
    private val startupTimeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS,
    private val commandBuilder: (
        paths: PosixRuntimeConfig.Paths,
        port: Int,
        disableSeccomp: Boolean,
        adminPort: Int,
        adminToken: String,
    ) -> Pair<List<String>, Map<String, String>> = { paths, port, disableSeccomp, adminPort, adminToken ->
        val guestCmd = PosixRuntimeConfig.buildOpenVsCodeServerCommand(port)
        val prootCmd = PosixRuntimeConfig.buildProotCommand(paths, guestCmd)
        val env = PosixRuntimeConfig.buildProotEnvironment(
            paths = paths,
            disableSeccomp = disableSeccomp,
            adminPort = adminPort,
            adminToken = adminToken,
        )
        prootCmd to env
    },
    private val processStarter: (List<String>, Map<String, String>) -> Process = { cmd, env ->
        ProcessBuilder(cmd).apply {
            redirectErrorStream(true)
            environment().putAll(env)
        }.start()
    },
    private val healthChecker: (port: Int, token: String) -> Boolean = ::verifyEditorAccess,
) {
    sealed interface LaunchResult {
        data class Ready(
            val process: Process,
            val port: Int,
            val token: String,
            val disableSeccomp: Boolean,
        ) : LaunchResult

        data class Failed(
            val exitCode: Int,
            val disableSeccomp: Boolean,
        ) : LaunchResult

        data class TimedOut(
            val timeoutMs: Long,
            val disableSeccomp: Boolean,
        ) : LaunchResult
    }

    suspend fun launch(
        paths: PosixRuntimeConfig.Paths,
        adminPort: Int,
        adminToken: String,
        initialDisableSeccomp: Boolean,
        allowSeccompRetry: Boolean,
        onStarting: (port: Int) -> Unit = {},
        onProcessCreated: (Process) -> Unit = {},
        onUnexpectedExit: (Process, Int) -> Unit = { _, _ -> },
    ): LaunchResult {
        var disableSeccomp = initialDisableSeccomp
        var retryAllowed = allowSeccompRetry

        while (true) {
            val port = portPicker()
            onStarting(port)

            val token = tokenGenerator()
            writeTextAtomically(File(paths.vscodeDataDir, ".editor-token"), token, Charsets.US_ASCII)

            val (prootCmd, env) = commandBuilder(paths, port, disableSeccomp, adminPort, adminToken)
            onLog("[runtime] Launching PRoot (disableSeccomp=$disableSeccomp) on 127.0.0.1:$port (admin=$adminPort)")

            val proc = processStarter(prootCmd, env)
            onProcessCreated(proc)

            val announced = AtomicBoolean(false)
            // Drain process output continuously into the ring buffer
            scope.launch {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            if (line.contains("http://localhost:$port") && line.contains("?tkn=$token")) {
                                announced.set(true)
                            }
                            onLog(line.replace(token, "[redacted]"))
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: IOException) {
                    // Process output stream closed
                }
                val code = try {
                    proc.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    -1
                }
                onLog("[runtime] openvscode-server exited with code $code")
                onUnexpectedExit(proc, code)
            }

            val startup = try {
                awaitServerStartup(proc, startupTimeoutMs) {
                    announced.get() && healthChecker(port, token)
                }
            } catch (cancel: CancellationException) {
                terminateServer(proc)
                throw cancel
            }

            when (startup) {
                ServerStartup.READY -> {
                    return LaunchResult.Ready(
                        process = proc,
                        port = port,
                        token = token,
                        disableSeccomp = disableSeccomp,
                    )
                }

                ServerStartup.EXITED -> {
                    val exitCode = proc.exitValue()
                    if (retryAllowed && !disableSeccomp) {
                        onLog("[runtime] Early exit ($exitCode); retrying with PROOT_NO_SECCOMP=1...")
                        disableSeccomp = true
                        retryAllowed = false
                    } else {
                        return LaunchResult.Failed(exitCode = exitCode, disableSeccomp = disableSeccomp)
                    }
                }

                ServerStartup.TIMED_OUT -> {
                    return LaunchResult.TimedOut(timeoutMs = startupTimeoutMs, disableSeccomp = disableSeccomp)
                }
            }
        }
    }

    companion object {
        internal const val DEFAULT_STARTUP_TIMEOUT_MS = 45_000L
    }
}

internal suspend fun awaitServerStartup(
    process: Process,
    timeoutMs: Long,
    isReady: () -> Boolean,
): ServerStartup {
    val start = System.nanoTime()
    while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < timeoutMs) {
        if (!process.isAlive) return ServerStartup.EXITED
        if (isReady() && process.isAlive) return ServerStartup.READY
        delay(350)
    }
    terminateServer(process)
    return ServerStartup.TIMED_OUT
}

internal fun terminateServer(process: Process) {
    process.destroy()
    if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        process.waitFor()
    }
}

/**
 * Prefers fixed port 18765 so WebView keeps a stable origin (preserving VS Code tabs,
 * IndexedDB state, and layout across restarts), falling back to an ephemeral port if busy.
 */
internal fun pickStableLocalPort(): Int {
    val loopback = InetAddress.getByName("127.0.0.1")
    try {
        ServerSocket(PosixRuntimeConfig.PREFERRED_SERVER_PORT, 1, loopback).use {
            return it.localPort
        }
    } catch (_: IOException) {
        // Preferred port busy — pick an available ephemeral port
    }
    return try {
        ServerSocket(0, 1, loopback).use { it.localPort }
    } catch (_: IOException) {
        PosixRuntimeConfig.PREFERRED_SERVER_PORT
    }
}

internal fun verifyEditorAccess(port: Int, token: String): Boolean =
    editorHttpStatus(port, null) == HttpURLConnection.HTTP_FORBIDDEN &&
        editorHttpStatus(port, token) == HttpURLConnection.HTTP_OK

private fun editorHttpStatus(port: Int, token: String?): Int {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("Cookie", if (token == null) "" else "vscode-tkn=$token")
            connectTimeout = 500
            readTimeout = 500
            instanceFollowRedirects = false
        }
        conn.responseCode
    } catch (_: IOException) {
        -1
    } finally {
        conn?.disconnect()
    }
}
