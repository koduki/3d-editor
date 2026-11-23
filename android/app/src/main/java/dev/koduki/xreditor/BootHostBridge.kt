package dev.koduki.xreditor

import android.webkit.JavascriptInterface
import kotlinx.coroutines.runBlocking

/**
 * Minimal JS bridge exposed ONLY on the local boot/diagnostics page (`boot.html`),
 * allowing users to inspect boot progress, grant Android storage mount access,
 * restart the embedded server, or run fallback POSIX CLI commands (`gh`, `apt`, `ls`).
 */
class BootHostBridge(
    private val runtimeManager: PosixRuntimeManager,
    private val onOpenEditor: () -> Unit,
    private val onRequestAllFilesAccess: () -> Unit,
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
