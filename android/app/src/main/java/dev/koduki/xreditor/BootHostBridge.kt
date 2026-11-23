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
                runtimeManager.runShellCommand(trimmed).toJsonString()
            } catch (t: Throwable) {
                PosixRuntimeManager.CommandResult(1, "Error: ${t.message}").toJsonString()
            }
            onCommandCompleted(trimmed, resultJson)
        }
    }

    companion object {
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
