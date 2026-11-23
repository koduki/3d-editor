package dev.koduki.kucode

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

class PosixShellExecutorTest {

    @Test
    fun execute_returns127WhenProotBinariesMissingAnd1WhenRootfsMissing() = runBlocking {
        val base = Files.createTempDirectory("kucode-shell-prereq").toFile()
        try {
            val paths = testPaths(base)
            val executor = PosixShellExecutor()

            val missingProot = executor.execute(paths, "echo hi")
            assertEquals(127, missingProot.exitCode)
            assertTrue(missingProot.output.contains("PRoot binaries not found"))

            paths.nativeLibDir.mkdirs()
            paths.prootBin.writeText("bin")
            paths.prootLoader.writeText("loader")

            val missingRootfs = executor.execute(paths, "echo hi")
            assertEquals(1, missingRootfs.exitCode)
            assertTrue(missingRootfs.output.contains("Debian rootfs is not extracted"))
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    @Category(MediumTest::class)
    fun execute_capturesOutputExitCodeTimeoutAndPropagatesCancellation() = runBlocking {
        val base = Files.createTempDirectory("kucode-shell-exec").toFile()
        try {
            val paths = testPaths(base)
            val startedProc = AtomicReference<Process?>()
            val executor = PosixShellExecutor(
                checkPrereqs = false,
                commandBuilder = { _, command, _ -> listOf("sh", "-c", command) to emptyMap() },
                processStarter = { cmd, env ->
                    ProcessBuilder(cmd).apply {
                        redirectErrorStream(true)
                        environment().putAll(env)
                    }.start().also { startedProc.set(it) }
                },
            )

            // 1. Success + stdout/stderr capture
            val ok = executor.execute(paths, "echo out; echo err >&2")
            assertEquals(0, ok.exitCode)
            assertTrue(ok.output.contains("out"))
            assertTrue(ok.output.contains("err"))

            // 2. Non-zero exit code preserved
            val failed = executor.execute(paths, "exit 42")
            assertEquals(42, failed.exitCode)

            // 3. Timeout forcibly reaps process and returns 124
            val timedOut = executor.execute(paths, "sleep 30", timeoutSeconds = 1)
            assertEquals(124, timedOut.exitCode)
            assertTrue(timedOut.output.contains("Timed out"))
            assertFalse("Timed-out child must be terminated", startedProc.get()!!.isAlive)

            // 4. Coroutine cancellation reaps child and propagates CancellationException (never swallowed)
            startedProc.set(null)
            val job = async {
                executor.execute(paths, "sleep 30", timeoutSeconds = 30)
            }
            val deadline = System.currentTimeMillis() + 3_000
            while (startedProc.get() == null && System.currentTimeMillis() < deadline) {
                delay(20)
            }
            val running = startedProc.get()
            assertTrue("Expected child process to start", running != null && running.isAlive)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertFalse("Cancelled child must be terminated", running!!.isAlive)
            try {
                job.await()
                fail("Expected CancellationException")
            } catch (_: CancellationException) {
                // expected
            }
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    private fun testPaths(base: File): PosixRuntimeConfig.Paths = PosixRuntimeConfig.Paths(
        runtimeRoot = File(base, "runtime"),
        workspaceDir = File(base, "workspace"),
        homeDir = File(base, "home"),
        nativeLibDir = File(base, "native"),
        androidSharedDir = File(base, "shared"),
    )
}
