package dev.koduki.kucode

import android.content.Context
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Minimal, strictly non-blocking JS bridge exposed ONLY on `boot.html`
 * (never attached to the editor WebView).
 * Long-running operations (diagnostics, session report rendering/export, fallback POSIX CLI)
 * run on [Dispatchers.IO] and report back via callbacks so WebView's JavaBridge thread never ANRs.
 */
class BootHostBridge(
    private val context: Context,
    private val runtimeManager: PosixRuntimeManager,
    private val telemetryManager: SessionTelemetryManager = SessionTelemetryManager.getInstance(context),
    private val onOpenEditor: () -> Unit,
    private val onRequestAllFilesAccess: () -> Unit,
    private val onOpenNotificationSettings: () -> Unit = {},
    private val onOpenBatterySettings: () -> Unit = {},
    private val onCopyToClipboard: (text: String) -> Boolean,
    private val onDiagnosticsCompleted: (reportText: String) -> Unit,
    private val onCommandCompleted: (command: String, resultJson: String) -> Unit,
    private val onSessionReportLoaded: (selector: String, format: String, content: String) -> Unit = { _, _, _ -> },
    private val onSessionExported: (message: String) -> Unit = {},
    private val onShareDiagnostic: (java.io.File) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun close() {
        scope.cancel()
    }

    @JavascriptInterface
    fun getStatus(): String = runtimeManager.getStatus().toJsonString()

    @JavascriptInterface
    fun getLogs(): String = runtimeManager.getLogsText()

    @JavascriptInterface
    fun clearLogs() {
        runtimeManager.clearLogs()
    }

    @JavascriptInterface
    fun restartServer() {
        ServerKeepAliveService.start(context)
        runtimeManager.restart()
    }

    @JavascriptInterface
    fun resetDebian() {
        ServerKeepAliveService.start(context)
        runtimeManager.archiveAndResetDebian()
    }

    @JavascriptInterface
    fun restoreDebian() {
        ServerKeepAliveService.start(context)
        runtimeManager.archiveAndResetDebian(restoreLatest = true)
    }

    @JavascriptInterface
    fun openEditor() {
        onOpenEditor()
    }

    @JavascriptInterface
    fun requestAllFilesAccess() {
        onRequestAllFilesAccess()
    }

    @JavascriptInterface
    fun openNotificationSettings() {
        onOpenNotificationSettings()
    }

    @JavascriptInterface
    fun openBatterySettings() {
        onOpenBatterySettings()
    }

    @JavascriptInterface
    fun copyToClipboard(text: String): Boolean = onCopyToClipboard(text)

    @JavascriptInterface
    fun listSessions(): String = telemetryManager.listSessionsJson()

    @JavascriptInterface
    fun loadSessionReport(selector: String, format: String) {
        val safeSelector = selector.trim().ifEmpty { "latest" }
        val safeFormat = format.trim().ifEmpty { "ai" }
        scope.launch {
            val content = try {
                telemetryManager.renderSessionBundle(safeSelector, safeFormat)
            } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
                throw cancel
            } catch (e: Exception) {
                "Session report error: ${e.message}"
            }
            onSessionReportLoaded(safeSelector, safeFormat, content)
        }
    }

    @JavascriptInterface
    fun exportSessionReport(selector: String) {
        val safeSelector = selector.trim().ifEmpty { "latest" }
        scope.launch {
            val msg = try {
                val file = telemetryManager.exportSessionToShared(safeSelector)
                if (file != null) {
                    "保存しました: ${file.absolutePath}"
                } else {
                    "保存失敗: セッションが見つかりません ($safeSelector)"
                }
            } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
                throw cancel
            } catch (e: Exception) {
                "保存エラー: ${e.message}"
            }
            onSessionExported(msg)
        }
    }

    @JavascriptInterface
    fun shareDiagnostic(selector: String) {
        scope.launch {
            try {
                val report = telemetryManager.renderSessionBundle(selector.trim().ifEmpty { "latest" }, "ai")
                val dir = java.io.File(context.cacheDir, "diagnostic-share").apply { mkdirs() }
                // Limit retained export files; file names contain no user input.
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
                val file = java.io.File.createTempFile("diagnostic-", ".md", dir)
                val redacted = LogRedactor.redact(report)
                file.writeBytes(truncateUtf8Bytes(redacted, MAX_DIAGNOSTIC_SHARE_BYTES))
                onShareDiagnostic(file)
            } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
                throw cancel
            } catch (e: Exception) {
                onSessionExported("Diagnostic export failed: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun startDiagnostics() {
        scope.launch {
            val report = try {
                DiagnosticsRunner.collect(context, runtimeManager)
            } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
                throw cancel
            } catch (e: Exception) {
                "Diagnostics Error: ${e.message}"
            }
            onDiagnosticsCompleted(report)
        }
    }

    @JavascriptInterface
    fun readLicenseAsset(fileName: String): String {
        val safeName = fileName.trim()
        if (safeName !in ALLOWED_LICENSE_FILES) {
            return "Invalid license file."
        }
        return runCatching {
            context.assets.open("licenses/$safeName").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { e ->
            "License file not available ($safeName): ${e.message}"
        }
    }

    @JavascriptInterface
    fun startCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            onCommandCompleted("", PosixRuntimeManager.CommandResult(0, "").toJsonString())
            return
        }
        scope.launch {
            val resultJson = try {
                runtimeManager.runShellCommand(trimmed, recordTelemetry = true).toJsonString()
            } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
                throw cancel
            } catch (e: Exception) {
                PosixRuntimeManager.CommandResult(1, "Error: ${e.message}").toJsonString()
            }
            onCommandCompleted(trimmed, resultJson)
        }
    }

    companion object {
        internal const val MAX_DIAGNOSTIC_SHARE_BYTES = 1024 * 1024

        internal fun truncateUtf8Bytes(text: String, maxBytes: Int): ByteArray {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return bytes
            var end = maxBytes
            // Step back across UTF-8 continuation bytes (10xxxxxx) and any incomplete lead byte
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
                end--
            }
            return bytes.copyOfRange(0, end)
        }

        private val ALLOWED_LICENSE_FILES = setOf(
            "THIRD_PARTY_NOTICES.md",
            "LICENSE",
            "MIT-OpenVSCode.txt",
            "Apache-2.0.txt",
            "GPL-2.0.txt",
            "GPL-3.0.txt",
            "LGPL-2.1.txt",
            "LGPL-3.0.txt",
            "debian-manifest.txt",
        )
    }
}
