package dev.koduki.xreditor

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
    fun probeCommands_referenceBundledOpenVsCodeAndNetworkEndpoints() {
        val ptyCmd = DiagnosticsRunner.buildNodePtyProbeCommand()
        assertTrue(ptyCmd.contains("/opt/openvscode-server/node"))
        assertTrue(ptyCmd.contains("node-pty"))
        assertTrue(ptyCmd.contains("NODE_PTY_SPAWN_OK"))

        val watcherCmd = DiagnosticsRunner.buildWatcherProbeCommand()
        assertTrue(watcherCmd.contains("@parcel/watcher"))
        assertTrue(watcherCmd.contains("max_user_watches"))

        val checks = DiagnosticsRunner.networkChecks()
        assertTrue(checks.getValue("DNS (deb.debian.org)").contains("getent hosts"))
        assertTrue(checks.getValue("Debian apt HTTP").contains("deb.debian.org"))
        assertTrue(checks.getValue("GitHub HTTPS").contains("api.github.com"))
    }
}
