package dev.koduki.kucode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@Category(MediumTest::class)
class OpenVsCodeLauncherTest {

    @Test
    fun launch_retriesWithNoSeccompRedactsTokenAndWritesTokenFile() = runBlocking {
        val base = Files.createTempDirectory("xr-launcher-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val logs = CopyOnWriteArrayList<String>()
        val attempts = AtomicInteger(0)
        val seenSeccompFlags = CopyOnWriteArrayList<Boolean>()
        var launchedProcess: Process? = null
        try {
            val paths = testPaths(base)
            paths.vscodeDataDir.mkdirs()

            val launcher = OpenVsCodeLauncher(
                scope = scope,
                onLog = { logs.add(it) },
                tokenGenerator = { "super-secret-token-123" },
                portPicker = { 29876 },
                startupTimeoutMs = 3_000L,
                commandBuilder = { _, port, disableSeccomp, _, _ ->
                    seenSeccompFlags.add(disableSeccomp)
                    val attempt = attempts.incrementAndGet()
                    val script = if (attempt == 1) {
                        "echo 'seccomp blocked'; exit 159"
                    } else {
                        "echo 'Web UI available at http://localhost:$port/?tkn=super-secret-token-123'; sleep 30"
                    }
                    listOf("sh", "-c", script) to emptyMap()
                },
                healthChecker = { port, token -> port == 29876 && token == "super-secret-token-123" },
                orphanReclaimer = { _, _ -> false },
            )

            val result = launcher.launch(
                paths = paths,
                adminPort = 0,
                adminToken = "",
                initialDisableSeccomp = false,
                allowSeccompRetry = true,
            )

            assertTrue("Expected LaunchResult.Ready, got $result", result is OpenVsCodeLauncher.LaunchResult.Ready)
            val ready = result as OpenVsCodeLauncher.LaunchResult.Ready
            launchedProcess = ready.process
            assertEquals(29876, ready.port)
            assertEquals("super-secret-token-123", ready.token)
            assertTrue("Should have retried with disableSeccomp=true", ready.disableSeccomp)
            assertEquals(listOf(false, true), seenSeccompFlags.toList())
            assertEquals("super-secret-token-123", File(paths.vscodeDataDir, ".editor-token").readText())
            assertTrue(".server-pid should exist while server is running", paths.serverPidFile.isFile)
            assertTrue(logs.any { it.contains("[redacted]") })
            assertTrue("Token must never appear in plain text logs", logs.none { it.contains("super-secret-token-123") })
        } finally {
            launchedProcess?.let(::terminateServer)
            scope.cancel()
            deleteTreeNoFollow(base)
        }
    }

    @Test
    fun reclaimOrphanServerIfNeeded_killsMatchingKuCodeProcessAndSkipsUnrelatedCmdline() {
        val base = Files.createTempDirectory("xr-orphan-test").toFile()
        try {
            val paths = testPaths(base)
            paths.vscodeDataDir.mkdirs()

            // 1. Unrelated cmdline must NEVER be killed
            paths.serverPidFile.writeText("12345\n")
            val sentUnrelated = mutableListOf<Pair<Long, String>>()
            val reclaimedUnrelated = reclaimOrphanServerIfNeeded(
                paths = paths,
                preferredPort = 18765,
                isPortAvailable = { false },
                readProcCmdline = { "/system/bin/surfaceflinger" },
                sendSignal = { pid, sig -> sentUnrelated.add(pid to sig); true },
            )
            assertFalse(reclaimedUnrelated)
            assertTrue(sentUnrelated.isEmpty())

            // 2. Matching PRoot/openvscode cmdline gets SIGTERM and cleans up .server-pid
            var alive = true
            val sentMatching = mutableListOf<Pair<Long, String>>()
            val reclaimed = reclaimOrphanServerIfNeeded(
                paths = paths,
                preferredPort = 18765,
                isPortAvailable = { !alive },
                readProcCmdline = { if (alive) "/data/app/libproot.so -r ${paths.rootfsDir.absolutePath} openvscode-server" else null },
                sendSignal = { pid, sig ->
                    sentMatching.add(pid to sig)
                    if (sig == "TERM") alive = false
                    true
                },
            )
            assertTrue(reclaimed)
            assertEquals(listOf(12345L to "TERM"), sentMatching)
            assertFalse(paths.serverPidFile.exists())
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    fun verifyEditorAccess_requires403ForAnonymousAnd200ForValidCookie() {
        val expectedToken = "tok-verify-999"
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            val port = server.localPort
            val thread = Thread {
                repeat(4) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        return@Thread
                    }
                    client.use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                        var cookie = ""
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.lowercase().startsWith("cookie:")) {
                                cookie = line.substringAfter(':').trim()
                            }
                        }
                        val status = if (cookie == "vscode-tkn=$expectedToken") "200 OK" else "403 Forbidden"
                        val resp = "HTTP/1.1 $status\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                        s.getOutputStream().write(resp.toByteArray(Charsets.US_ASCII))
                        s.getOutputStream().flush()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            assertTrue(verifyEditorAccess(port, expectedToken))
            assertFalse(verifyEditorAccess(port, "wrong-token"))
            thread.join(2_000)
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
