package dev.koduki.kucode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pure-JVM non-PTY shell command runner for diagnostics and fallback CLI execution.
 * Enforces execution timeouts and propagates coroutine cancellation while terminating
 * the underlying child process cleanly.
 */
internal class PosixShellExecutor(
    private val ensureMountDirs: (PosixRuntimeConfig.Paths) -> Unit = {},
    private val checkPrereqs: Boolean = true,
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
    suspend fun execute(
        paths: PosixRuntimeConfig.Paths,
        command: String,
        timeoutSeconds: Long = 120,
        disableSeccomp: Boolean = false,
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

        // Ensure scratch/mount directories exist without rewriting live server config/extensions
        ensureMountDirs(paths)
        val (cmd, env) = commandBuilder(paths, command, disableSeccomp)

        val output = StringBuilder()
        var proc: Process? = null
        var readerThread: Thread? = null
        try {
            val started = processStarter(cmd, env)
            proc = started
            val reader = Thread({
                try {
                    BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            output.append(line).append('\n')
                        }
                    }
                } catch (_: Exception) {
                    // Stream closed on process termination
                }
            }, "kucode-shell-reader").apply {
                isDaemon = true
                start()
            }
            readerThread = reader

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
                started.destroyForcibly()
                started.waitFor(1, TimeUnit.SECONDS)
                reader.join(1000)
                return@withContext PosixRuntimeManager.CommandResult(
                    exitCode = 124,
                    output = output.toString() + "\n[Timed out after ${timeoutSeconds}s]",
                )
            }
            reader.join(2000)
            PosixRuntimeManager.CommandResult(started.exitValue(), output.toString())
        } catch (cancel: CancellationException) {
            proc?.destroyForcibly()
            readerThread?.join(500)
            throw cancel
        } catch (e: Exception) {
            proc?.destroyForcibly()
            readerThread?.join(500)
            PosixRuntimeManager.CommandResult(1, "Failed to run command: ${e.message}")
        }
    }
}
