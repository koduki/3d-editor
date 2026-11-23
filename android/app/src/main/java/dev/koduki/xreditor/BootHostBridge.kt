package dev.koduki.xreditor

import android.content.Context
import android.webkit.JavascriptInterface
import kotlinx.coroutines.runBlocking

/**
 * Minimal JS bridge exposed on the local boot/diagnostics page (`boot.html`),
 * allowing users to inspect boot progress, run the built-in device diagnostic
 * checklist, copy reports/logs to the Android clipboard, grant storage access,
 * restart the embedded server, or run fallback POSIX CLI commands.
 */
class BootHostBridge(
    private val context: Context,
    private val runtimeManager: PosixRuntimeManager,
    private val onOpenEditor: () -> Unit,
    private val onRequestAllFilesAccess: () -> Unit,
    private val onCopyToClipboard: (text: String) -> Boolean,
) {

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
    fun runDiagnostics(): String = runBlocking {
        DiagnosticsRunner.collect(context, runtimeManager)
    }

    @JavascriptInterface
    fun copyToClipboard(text: String): Boolean = onCopyToClipboard(text)

    @JavascriptInterface
    fun runCommand(command: String): String {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return PosixRuntimeManager.CommandResult(0, "").toJsonString()
        }
        return runBlocking {
            runtimeManager.runShellCommand(trimmed).toJsonString()
        }
    }
}
