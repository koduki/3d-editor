package dev.koduki.xreditor

import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

        assertTrue(md.contains("# XR-Editor 実機診断レポート"))
        assertTrue(md.contains("- Device: `XREAL AURA`"))
        assertTrue(md.contains("- Page Size: `16384 bytes (16KB)`"))
        assertTrue(md.contains("動作チェック結果 (2/3 PASS)"))
        assertTrue(md.contains("### [PASS] PRoot + Debian シェル実行 (Gate 1) (140ms)"))
        assertTrue(md.contains("### [PASS] node-pty 実起動テスト（統合ターミナル検証） (95ms)"))
        assertTrue(md.contains("### [FAIL] 内蔵 openvscode-server HTTP 応答 (Gate 2) (25ms)"))
        assertTrue(md.contains("[error] sample error line"))
    }

    @Test
    fun workspaceExchangeRequiresTheAndroidPayloadBeforeOverwriting() {
        val id = UUID.randomUUID()
        val dir = Files.createTempDirectory("xr-files-test").toFile()
        try {
            val file = dir.resolve(".xr-files-$id")
            val command = DiagnosticsRunner.fileExchangeCommand(".", id)
            fun run() = ProcessBuilder("sh", "-c", command).directory(dir).start().waitFor()
            file.writeText("unchanged")
            assertTrue(run() != 0)
            assertEquals("unchanged", file.readText())
            file.writeText("android-$id")
            assertEquals(0, run())
            assertEquals("guest-$id", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun probeCommands_referenceBundledOpenVsCodeAndNetworkEndpoints() {
        val command = DiagnosticsRunner.nativeProbeCommand("pty", "console.log(process.argv)")
        assertTrue(command.startsWith("/opt/openvscode-server/node - pty <<'XR_PROBE_JS'"))
        assertTrue(command.endsWith("\nXR_PROBE_JS"))

        val checks = DiagnosticsRunner.networkChecks()
        assertTrue(checks.getValue("DNS (deb.debian.org)").contains("getent hosts"))
        assertTrue(checks.getValue("Debian apt HTTP").contains("deb.debian.org"))
        assertTrue(checks.getValue("GitHub HTTPS").contains("api.github.com"))
    }
}
