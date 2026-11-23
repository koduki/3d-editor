package dev.koduki.kucode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class DiagnosticsRunnerTest {

    @Test
    fun formatMarkdownReport_rendersDeviceChecksAndRecentLogs() {
        val md = DiagnosticsRunner.formatMarkdownReport(
            DiagnosticsRunner.ReportInput(
                timestampUtc = "2026-11-23T02:50:00Z",
                deviceInfo = linkedMapOf(
                    "Device" to "XREAL AURA",
                    "Android" to "15 (API 35)",
                    "Page Size" to "16384 bytes (16KB)",
                ),
                checks = listOf(
                    DiagnosticsRunner.CheckItem("PRoot + Debian シェル実行 (Gate 1)", true, "Debian GNU/Linux 12", 140),
                    DiagnosticsRunner.CheckItem("node-pty 実起動テスト（統合ターミナル検証）", true, "NODE_PTY_SPAWN_OK", 95),
                    DiagnosticsRunner.CheckItem("内蔵 openvscode-server HTTP 応答 (Gate 2)", false, "Connection refused", 25),
                ),
                recentLogs = "[runtime] Starting...\n[error] sample error line",
            ),
        )

        assertTrue(md.contains("# KuCode 実機診断レポート"))
        assertTrue(md.contains("- Device: `XREAL AURA`"))
        assertTrue(md.contains("- Page Size: `16384 bytes (16KB)`"))
        assertTrue(md.contains("動作チェック結果 (2/3 PASS)"))
        assertTrue(md.contains("### [PASS] PRoot + Debian シェル実行 (Gate 1) (140ms)"))
        assertTrue(md.contains("### [PASS] node-pty 実起動テスト（統合ターミナル検証） (95ms)"))
        assertTrue(md.contains("### [FAIL] 内蔵 openvscode-server HTTP 応答 (Gate 2) (25ms)"))
        assertTrue(md.contains("[error] sample error line"))
    }

    @Test
    fun runWithCleanup_preservesPrimaryExceptionWhenCleanupAlsoFails() {
        try {
            DiagnosticsRunner.runWithCleanup(
                block = { throw IllegalStateException("primary failure") },
                cleanup = { throw IllegalStateException("cleanup failure") },
            )
            fail("Expected primary failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("primary failure", e.message)
            assertEquals(1, e.suppressed.size)
            assertEquals("cleanup failure", e.suppressed[0].message)
        }
    }

    @Test
    fun runWithCleanup_propagatesCleanupFailureWhenBlockSucceeds() {
        try {
            DiagnosticsRunner.runWithCleanup(
                block = { "ok" },
                cleanup = { throw IllegalStateException("delete failed") },
            )
            fail("Expected cleanup failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("delete failed", e.message)
        }
    }

    @Test
    fun runWithCleanup_preservesCancellationExceptionWhenCleanupFails() {
        try {
            DiagnosticsRunner.runWithCleanup(
                block = { throw CancellationException("cancelled") },
                cleanup = { throw IllegalStateException("cleanup failure") },
            )
            fail("Expected CancellationException to propagate")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
            assertEquals(1, e.suppressed.size)
            assertEquals("cleanup failure", e.suppressed[0].message)
        }
    }

    @Test
    @Category(MediumTest::class)
    fun workspaceExchangeRequiresTheAndroidPayloadBeforeOverwriting() {
        val id = UUID.randomUUID()
        val dir = Files.createTempDirectory("kucode-files-test").toFile()
        try {
            val file = dir.resolve(".kucode-files-$id")
            val command = DiagnosticsRunner.fileExchangeCommand(".", id)
            fun run() = ProcessBuilder("sh", "-c", command).directory(dir).start().waitFor()
            file.writeText("unchanged")
            assertTrue(run() != 0)
            assertEquals("unchanged", file.readText())
            file.writeText("android-$id")
            assertEquals(0, run())
            assertEquals("guest-$id", file.readText())
        } finally {
            deleteTreeNoFollow(dir)
        }
    }

    @Test
    fun probeCommands_referenceBundledOpenVsCodeAndNetworkEndpoints() {
        val command = DiagnosticsRunner.nativeProbeCommand("pty", "console.log(process.argv)")
        assertTrue(command.startsWith("/opt/openvscode-server/node - pty <<'KUCODE_PROBE_JS'"))
        assertTrue(command.endsWith("\nKUCODE_PROBE_JS"))

        val checks = DiagnosticsRunner.networkChecks()
        assertTrue(checks.getValue("システム CA 証明書 & apt 設定").contains("apt-config dump"))
        assertTrue(checks.getValue("DNS (deb.debian.org)").contains("getent hosts"))
        assertTrue(checks.getValue("Debian apt HTTP").contains("deb.debian.org"))
        assertTrue(checks.getValue("Debian apt HTTPS (システム CA)").contains("https://deb.debian.org"))
        assertTrue(checks.getValue("Debian apt HTTPS (システム CA)").contains("ca: [ca]"))
        assertTrue(checks.getValue("GitHub HTTPS (システム CA)").contains("https://api.github.com/zen"))
        assertTrue(checks.getValue("GitHub HTTPS (システム CA)").contains("User-Agent"))
    }

    @Test
    fun querySystemCaSummary_reportsMissingAndSyncedCounts() {
        val base = Files.createTempDirectory("kucode-diag-ca").toFile()
        try {
            val paths = PosixRuntimeConfig.Paths(
                runtimeRoot = java.io.File(base, "runtime"),
                workspaceDir = java.io.File(base, "workspace"),
                homeDir = java.io.File(base, "home"),
                nativeLibDir = java.io.File(base, "native"),
                androidSharedDir = java.io.File(base, "shared"),
            )
            assertTrue(DiagnosticsRunner.querySystemCaSummary(paths).startsWith("missing"))

            CaTrustSync.syncIntoRootfs(
                paths,
                listOf(
                    CaCertificateEntry("system:a.0", byteArrayOf(0x30, 0x01), isUserAdded = false),
                    CaCertificateEntry("user:b.0", byteArrayOf(0x30, 0x02), isUserAdded = true),
                ),
            )
            assertEquals("2 certs (android-synced, user=1)", DiagnosticsRunner.querySystemCaSummary(paths))
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    @Category(MediumTest::class)
    fun httpsSystemCaProbe_strictlyUsesConfiguredCaBundleAndAptConfParsesCleanly() {
        val dir = Files.createTempDirectory("kucode-https-probe-test").toFile()
        var serverProc: Process? = null
        try {
            // 1. Verify apt-config parses KuCode's apt PRoot drop-in cleanly when apt-config is installed
            val aptConfigAvailable = runCatching {
                ProcessBuilder("sh", "-c", "command -v apt-config >/dev/null").start().waitFor() == 0
            }.getOrDefault(false)
            if (aptConfigAvailable) {
                val confFile = dir.resolve("99kucode-proot")
                confFile.writeText(PosixRuntimeConfig.buildAptProotConf())
                val dumpProc = ProcessBuilder(
                    "apt-config", "-c", confFile.absolutePath, "dump",
                    "APT::Sandbox::User", "APT::Sandbox::Seccomp", "Acquire::https::CaInfo",
                ).redirectErrorStream(true).start()
                val dumpOut = dumpProc.inputStream.bufferedReader().readText()
                assertEquals(0, dumpProc.waitFor())
                assertTrue(dumpOut.contains("""APT::Sandbox::User "root";"""))
                assertTrue(dumpOut.contains("""APT::Sandbox::Seccomp "false";"""))
                assertTrue(dumpOut.contains("""Acquire::https::CaInfo "/etc/ssl/certs/ca-certificates.crt";"""))
            }

            // 2. Exercise DiagnosticsRunner.httpsSystemCaProbeCommand against a real local TLS server
            val hasTools = runCatching {
                ProcessBuilder("sh", "-c", "command -v node >/dev/null && command -v openssl >/dev/null").start().waitFor() == 0
            }.getOrDefault(false)
            org.junit.Assume.assumeTrue(hasTools)

            val keyA = dir.resolve("a.key")
            val crtA = dir.resolve("a.crt")
            val keyB = dir.resolve("b.key")
            val crtB = dir.resolve("b.crt")
            assertEquals(
                0,
                ProcessBuilder(
                    "openssl", "req", "-x509", "-newkey", "rsa:2048",
                    "-keyout", keyA.absolutePath, "-out", crtA.absolutePath,
                    "-days", "1", "-nodes", "-subj", "/CN=localhost",
                    "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1",
                ).redirectErrorStream(true).start().waitFor(),
            )
            assertEquals(
                0,
                ProcessBuilder(
                    "openssl", "req", "-x509", "-newkey", "rsa:2048",
                    "-keyout", keyB.absolutePath, "-out", crtB.absolutePath,
                    "-days", "1", "-nodes", "-subj", "/CN=other",
                ).redirectErrorStream(true).start().waitFor(),
            )

            val serverScript = """
                const fs = require("node:fs");
                const https = require("node:https");
                const srv = https.createServer(
                  { key: fs.readFileSync(process.argv[1]), cert: fs.readFileSync(process.argv[2]) },
                  (req, res) => { res.writeHead(200); res.end(); }
                );
                srv.listen(0, "127.0.0.1", () => console.log("PORT=" + srv.address().port));
            """.trimIndent()
            serverProc = ProcessBuilder("node", "-e", serverScript, keyA.absolutePath, crtA.absolutePath)
                .redirectErrorStream(true)
                .start()
            val portLine = serverProc.inputStream.bufferedReader().readLine()
            assertTrue("Expected PORT=..., got $portLine", portLine != null && portLine.startsWith("PORT="))
            val port = portLine!!.removePrefix("PORT=").toInt()

            val probeCmd = DiagnosticsRunner.httpsSystemCaProbeCommand("https://127.0.0.1:$port/", nodeBin = "node")

            fun runProbe(caFile: String): Pair<Int, String> {
                val p = ProcessBuilder("sh", "-c", probeCmd).apply {
                    redirectErrorStream(true)
                    environment()["SSL_CERT_FILE"] = caFile
                }.start()
                val out = p.inputStream.bufferedReader().readText()
                return p.waitFor() to out
            }

            // Trusted multi-cert bundle succeeds
            val multiBundle = dir.resolve("trusted.crt").apply {
                writeText(crtB.readText() + "\n" + crtA.readText())
            }
            val (okCode, okOut) = runProbe(multiBundle.absolutePath)
            assertEquals("Expected trusted CA bundle to pass, output: $okOut", 0, okCode)
            assertTrue(okOut.contains("HTTP 200"))

            // Untrusted CA bundle fails
            val (badCode, badOut) = runProbe(crtB.absolutePath)
            assertTrue("Expected untrusted CA bundle to fail, output: $badOut", badCode != 0)

            // Empty CA bundle fails (never falls back to Node built-in CAs)
            val emptyBundle = dir.resolve("empty.crt").apply { writeText("") }
            val (emptyCode, emptyOut) = runProbe(emptyBundle.absolutePath)
            assertTrue("Expected empty CA bundle to fail, output: $emptyOut", emptyCode != 0)
            assertTrue(emptyOut.contains("Empty or invalid CA bundle"))
        } finally {
            serverProc?.destroyForcibly()
            deleteTreeNoFollow(dir)
        }
    }
}
