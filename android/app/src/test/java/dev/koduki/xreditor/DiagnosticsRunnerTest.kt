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
                ),
                checks = listOf(
                    DiagnosticsRunner.CheckItem("PRoot + Debian シェル実行 (Gate 1)", true, "Debian GNU/Linux 12", 140),
                    DiagnosticsRunner.CheckItem("内蔵 openvscode-server HTTP 応答 (Gate 2)", false, "Connection refused", 25),
                ),
                recentLogs = "[runtime] Starting...\n[error] sample error line",
            ),
        )

        assertTrue(md.contains("# XR-Editor 実機診断レポート"))
        assertTrue(md.contains("- Device: `XREAL AURA`"))
        assertTrue(md.contains("動作チェック結果 (1/2 PASS)"))
        assertTrue(md.contains("### [PASS] PRoot + Debian シェル実行 (Gate 1) (140ms)"))
        assertTrue(md.contains("### [FAIL] 内蔵 openvscode-server HTTP 応答 (Gate 2) (25ms)"))
        assertTrue(md.contains("[error] sample error line"))
    }
}
