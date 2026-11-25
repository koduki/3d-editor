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
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

internal enum class ServerStartup { READY, EXITED, TIMED_OUT }

/**
 * Pure-JVM launcher for the embedded openvscode-server process (under PRoot in production,
 * or injectable process starter in unit tests).
 *
 * Handles atomic `.editor-token` creation, orphan PRoot PID reclamation for fixed port 18765,
 * log draining with token redaction, startup health verification, automatic `PROOT_NO_SECCOMP=1`
 * retry on early exit, and reliable subprocess termination.
 */
internal class OpenVsCodeLauncher(
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit = {},
    private val tokenGenerator: () -> String = ::generateConnectionToken,
    private val portPicker: () -> Int = { pickStableLocalPort() },
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
    private val orphanReclaimer: (PosixRuntimeConfig.Paths, (String) -> Unit) -> Boolean = ::reclaimOrphanServerIfNeeded,
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

        runCatching { orphanReclaimer(paths, onLog) }

        while (true) {
            val port = portPicker()
            onStarting(port)

            val token = tokenGenerator()
            writeTextAtomically(File(paths.vscodeDataDir, ".editor-token"), token, Charsets.US_ASCII)

            val (prootCmd, env) = commandBuilder(paths, port, disableSeccomp, adminPort, adminToken)
            onLog("[runtime] Launching PRoot (disableSeccomp=$disableSeccomp) on 127.0.0.1:$port (admin=$adminPort)")

            val proc = processStarter(prootCmd, env)
            runCatching { proc.outputStream.close() }
            val procPid = extractProcessPid(proc)
            if (procPid != null && procPid > 0L) {
                runCatching {
                    writeTextAtomically(paths.serverPidFile, "$procPid\n", Charsets.US_ASCII)
                }
            }
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
                if (procPid != null && procPid > 0L) {
                    clearServerPidIfMatches(paths.serverPidFile, procPid)
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
                if (procPid != null && procPid > 0L) {
                    clearServerPidIfMatches(paths.serverPidFile, procPid)
                }
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
                    if (procPid != null && procPid > 0L) {
                        clearServerPidIfMatches(paths.serverPidFile, procPid)
                    }
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
        process.waitFor(2, TimeUnit.SECONDS)
    }
}

internal fun extractProcessPid(process: Process): Long? {
    runCatching {
        val m = process.javaClass.getMethod("pid")
        val value = m.invoke(process) as? Long
        if (value != null && value > 0L) return value
    }
    runCatching {
        val f = process.javaClass.getDeclaredField("pid")
        f.isAccessible = true
        val value = (f.get(process) as? Number)?.toLong()
        if (value != null && value > 0L) return value
    }
    val match = Regex("\\bpid=(\\d+)\\b").find(process.toString())
    return match?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
}

internal fun clearServerPidIfMatches(pidFile: File, expectedPid: Long) {
    runCatching {
        if (!pidFile.isFile) return
        val recorded = pidFile.readText(Charsets.US_ASCII).trim().toLongOrNull()
        if (recorded == expectedPid) {
            Files.deleteIfExists(pidFile.toPath())
        }
    }
}

internal fun isLocalPortAvailable(port: Int): Boolean {
    return try {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(port, 1, loopback).use { true }
    } catch (_: IOException) {
        false
    }
}

/**
 * Reclaims a lingering orphan PRoot/openvscode-server process recorded in `.server-pid`
 * after a prior JVM crash so WebView keeps fixed port 18765 (and its IndexedDB origin).
 */
internal fun reclaimOrphanServerIfNeeded(
    paths: PosixRuntimeConfig.Paths,
    onLog: (String) -> Unit = {},
    preferredPort: Int = PosixRuntimeConfig.PREFERRED_SERVER_PORT,
    isPortAvailable: (Int) -> Boolean = ::isLocalPortAvailable,
    readProcCmdline: (Long) -> String? = ::readLinuxProcCmdline,
    sendSignal: (Long, String) -> Boolean = ::sendPosixSignal,
): Boolean {
    val pidFile = paths.serverPidFile
    if (!pidFile.isFile) return false
    val recordedPid = runCatching { pidFile.readText(Charsets.US_ASCII).trim().toLongOrNull() }.getOrNull()
    if (recordedPid == null || recordedPid <= 1L) {
        runCatching { Files.deleteIfExists(pidFile.toPath()) }
        return false
    }
    if (isPortAvailable(preferredPort)) {
        runCatching { Files.deleteIfExists(pidFile.toPath()) }
        return false
    }
    val cmdline = readProcCmdline(recordedPid)
    if (cmdline.isNullOrBlank()) {
        runCatching { Files.deleteIfExists(pidFile.toPath()) }
        return false
    }
    val isKuCodeServer = cmdline.contains("libproot.so") ||
        cmdline.contains("openvscode-server") ||
        cmdline.contains(paths.rootfsDir.absolutePath)
    if (!isKuCodeServer) {
        return false
    }

    onLog("[runtime] Reclaiming orphan server PID=$recordedPid holding port $preferredPort...")
    sendSignal(recordedPid, "TERM")
    var waitedMs = 0L
    while (waitedMs < 1_500L) {
        if (isPortAvailable(preferredPort) || readProcCmdline(recordedPid).isNullOrBlank()) {
            runCatching { Files.deleteIfExists(pidFile.toPath()) }
            onLog("[runtime] Reclaimed orphan server PID=$recordedPid cleanly.")
            return true
        }
        try {
            Thread.sleep(100)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            break
        }
        waitedMs += 100L
    }
    sendSignal(recordedPid, "KILL")
    waitedMs = 0L
    while (waitedMs < 1_000L) {
        if (isPortAvailable(preferredPort) || readProcCmdline(recordedPid).isNullOrBlank()) {
            break
        }
        try {
            Thread.sleep(100)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            break
        }
        waitedMs += 100L
    }
    runCatching { Files.deleteIfExists(pidFile.toPath()) }
    onLog("[runtime] Force-reclaimed orphan server PID=$recordedPid.")
    return true
}

private fun readLinuxProcCmdline(pid: Long): String? = runCatching {
    val f = File("/proc/$pid/cmdline")
    if (!f.isFile) return null
    val raw = f.readBytes()
    if (raw.isEmpty()) return null
    String(raw, Charsets.UTF_8).replace('\u0000', ' ').trim()
}.getOrNull()

private fun sendPosixSignal(pid: Long, signalName: String): Boolean {
    val killCandidates = listOf("/system/bin/kill", "/bin/kill", "/usr/bin/kill")
    val killBin = killCandidates.firstOrNull { File(it).canExecute() } ?: "kill"
    return runCatching {
        val p = ProcessBuilder(killBin, "-$signalName", pid.toString()).start()
        p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)
}

/**
 * Prefers fixed port 18765 so WebView keeps a stable origin (preserving VS Code tabs,
 * IndexedDB state, and layout across restarts), falling back to an ephemeral port if busy.
 */
internal fun pickStableLocalPort(preferredPort: Int = PosixRuntimeConfig.PREFERRED_SERVER_PORT): Int {
    val loopback = InetAddress.getByName("127.0.0.1")
    try {
        ServerSocket(preferredPort, 1, loopback).use {
            return it.localPort
        }
    } catch (_: IOException) {
        // Preferred port busy — pick an available ephemeral port
    }
    return try {
        ServerSocket(0, 1, loopback).use { it.localPort }
    } catch (_: IOException) {
        preferredPort
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
