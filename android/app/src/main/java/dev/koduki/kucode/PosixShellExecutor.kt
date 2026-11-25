package dev.koduki.kucode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pure-JVM non-PTY shell command runner for diagnostics and fallback CLI execution.
 *
 * Hardening guarantees:
 * - Closes child stdin immediately to avoid FD leaks and stdin-blocking hangs
 * - Caps captured stdout/stderr at [maxOutputChars] while draining excess bytes to avoid pipe deadlock or OOM
 * - Uses pooled [Dispatchers.IO] coroutine reading instead of spawning raw OS threads per command
 * - Terminates timed-out/cancelled processes gracefully (SIGTERM -> SIGKILL) and closes pipes deterministically
 */
internal class PosixShellExecutor(
    private val ensureMountDirs: (PosixRuntimeConfig.Paths) -> Unit = {},
    private val checkPrereqs: Boolean = true,
    private val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    private val commandBuilder: (
        paths: PosixRuntimeConfig.Paths,
        command: String,
        noSeccomp: Boolean,
    ) -> Pair<List<String>, Map<String, String>> = { paths, command, noSeccomp ->
        PosixRuntimeConfig.buildProotCommand(
            paths = paths,
            guestCommand = PosixRuntimeConfig.buildShellCommand(command),
        ) to PosixRuntimeConfig.buildProotEnvironment(paths, noSeccomp)
    },
    private val processStarter: (List<String>, Map<String, String>) -> Process = { cmd, env ->
        ProcessBuilder(cmd).apply {
            redirectErrorStream(true)
            environment().putAll(env)
        }.start()
    },
) {
    private val mountDirLock = Any()

    suspend fun execute(
        paths: PosixRuntimeConfig.Paths,
        command: String,
        timeoutSeconds: Long = 120,
        disableSeccomp: Boolean = false,
        extraEnv: Map<String, String> = emptyMap(),
    ): PosixRuntimeManager.CommandResult = withContext(Dispatchers.IO) {
        if (checkPrereqs) {
            if (!paths.prootBin.exists() || !paths.prootLoader.exists()) {
                return@withContext PosixRuntimeManager.CommandResult(
                    exitCode = 127,
                    output = "PRoot binaries not found in ${paths.nativeLibDir.absolutePath}",
                )
            }
            if (!paths.rootfsDir.exists()) {
                return@withContext PosixRuntimeManager.CommandResult(
                    exitCode = 1,
                    output = "Debian rootfs is not extracted yet.",
                )
            }
        }

        // Ensure scratch/mount directories exist without racing concurrent diagnostics probes
        synchronized(mountDirLock) {
            ensureMountDirs(paths)
        }
        val (cmd, baseEnv) = commandBuilder(paths, command, disableSeccomp)
        val env = if (extraEnv.isEmpty()) {
            baseEnv
        } else {
            LinkedHashMap(baseEnv).apply { putAll(extraEnv) }
        }

        val output = StringBuilder()
        var truncated = false
        var proc: Process? = null
        try {
            val started = processStarter(cmd, env)
            proc = started
            runCatching { started.outputStream.close() }

            coroutineScope {
                val readerJob = async(Dispatchers.IO) {
                    try {
                        BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8)).use { br ->
                            val charBuf = CharArray(4096)
                            while (true) {
                                val n = br.read(charBuf)
                                if (n < 0) break
                                if (output.length < maxOutputChars) {
                                    val remaining = maxOutputChars - output.length
                                    if (n <= remaining) {
                                        output.append(charBuf, 0, n)
                                    } else {
                                        output.append(charBuf, 0, remaining)
                                        truncated = true
                                    }
                                } else {
                                    truncated = true
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Stream closed on process termination
                    }
                }

                val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.coerceAtLeast(1))
                var finished = false
                while (!finished) {
                    currentCoroutineContext().ensureActive()
                    val remainingNanos = deadlineNanos - System.nanoTime()
                    if (remainingNanos <= 0L) break
                    val waitMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceIn(1L, 100L)
                    finished = started.waitFor(waitMs, TimeUnit.MILLISECONDS)
                }

                if (!finished) {
                    terminateServer(started)
                    runCatching { started.inputStream.close() }
                    withTimeoutOrNull(1_000L) { readerJob.await() }
                    readerJob.cancel()
                    val body = buildOutput(output, truncated)
                    return@coroutineScope PosixRuntimeManager.CommandResult(
                        exitCode = 124,
                        output = body + "\n[Timed out after ${timeoutSeconds}s]",
                    )
                }

                withTimeoutOrNull(2_000L) { readerJob.await() }
                if (!readerJob.isCompleted) {
                    runCatching { started.inputStream.close() }
                    readerJob.cancel()
                }
                PosixRuntimeManager.CommandResult(started.exitValue(), buildOutput(output, truncated))
            }
        } catch (cancel: CancellationException) {
            proc?.let {
                terminateServer(it)
                runCatching { it.inputStream.close() }
            }
            throw cancel
        } catch (e: Exception) {
            proc?.let {
                terminateServer(it)
                runCatching { it.inputStream.close() }
            }
            PosixRuntimeManager.CommandResult(1, "Failed to run command: ${e.message}")
        }
    }

    private fun buildOutput(sb: StringBuilder, truncated: Boolean): String =
        if (truncated) sb.toString() + "\n...[output truncated at $maxOutputChars chars]" else sb.toString()

    companion object {
        internal const val DEFAULT_MAX_OUTPUT_CHARS = 256 * 1024
    }
}
