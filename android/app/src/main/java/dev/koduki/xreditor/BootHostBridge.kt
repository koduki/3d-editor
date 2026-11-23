package dev.koduki.xreditor

import android.content.Context
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Minimal, strictly non-blocking JS bridge exposed ONLY on `boot.html`
 * (removed before navigating to openvscode-server).
 * Long-running operations (diagnostics, fallback POSIX CLI) run on Dispatchers.IO
 * and report back via callbacks so WebView's JavaBridge thread never blocks or ANRs.
 */
class BootHostBridge(
    private val context: Context,
    private val runtimeManager: PosixRuntimeManager,
    private val onOpenEditor: () -> Unit,
    private val onRequestAllFilesAccess: () -> Unit,
    private val onCopyToClipboard: (text: String) -> Boolean,
    private val onDiagnosticsCompleted: (reportText: String) -> Unit,
    private val onCommandCompleted: (command: String, resultJson: String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        runtimeManager.restart()
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
    fun copyToClipboard(text: String): Boolean = onCopyToClipboard(text)

    @JavascriptInterface
    fun startDiagnostics() {
        scope.launch {
            val report = try {
                DiagnosticsRunner.collect(context, runtimeManager)
            } catch (t: Throwable) {
                "Diagnostics Error: ${t.message}"
            }
            onDiagnosticsCompleted(report)
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
                runtimeManager.runShellCommand(trimmed).toJsonString()
            } catch (t: Throwable) {
                PosixRuntimeManager.CommandResult(1, "Error: ${t.message}").toJsonString()
            }
            onCommandCompleted(trimmed, resultJson)
        }
    }
}
