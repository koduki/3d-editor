package dev.koduki.kucode

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Facade coordinating the embedded PRoot + Debian minimal + openvscode-server lifecycle on-device
 * by composing focused pure-JVM components:
 * - [RuntimeStatusStore]: atomic status snapshots & bounded log ring buffer
 * - [RuntimeInstaller]: atomic rootfs extraction, rollback/commit, and Machine settings merge
 * - [OpenVsCodeLauncher]: PRoot server launch, seccomp retry, log redaction, and health check
 * - [PosixShellExecutor]: non-PTY POSIX shell execution fallback with timeout & cancellation
 * - [LocalDiagnosticsServer]: hardened loopback admin HTTP server for the built-in extension
 * - [SessionTelemetryManager]: per-launch flight recorder & AI diagnostic bundle collector
 */
class PosixRuntimeManager private constructor(private val appContext: Context) {

    enum class State {
        IDLE,
        EXTRACTING,
        STARTING,
        READY,
        ERROR,
    }

    data class StatusSnapshot(
        val state: State,
        val message: String,
        val progress: Int,
        val serverUrl: String,
        val port: Int,
        val hasAllFilesAccess: Boolean,
        val workspacePath: String,
        val prootReady: Boolean,
    ) {
        fun toJsonString(): String = JSONObject()
            .put("state", state.name)
            .put("message", message)
            .put("progress", progress)
            .put("serverUrl", serverUrl)
            .put("port", port)
            .put("hasAllFilesAccess", hasAllFilesAccess)
            .put("workspacePath", workspacePath)
            .put("prootReady", prootReady)
            .toString()
    }

    data class CommandResult(
        val exitCode: Int,
        val output: String,
    ) {
        fun toJsonString(): String = JSONObject()
            .put("exitCode", exitCode)
            .put("output", output)
            .toString()
    }

    data class HostActions(
        val onOpenBootPage: () -> Unit,
        val onRequestStoragePermission: () -> Unit,
        val onCopyToClipboard: (String) -> Boolean,
        val onReadFromClipboard: () -> String? = { null },
        val onOpenExternalUrl: ((String) -> Boolean)? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val telemetry = SessionTelemetryManager.getInstance(appContext)

    private val statusStore = RuntimeStatusStore(
        initialWorkspacePath = File(appContext.filesDir, "workspace").absolutePath,
    )

    private fun appendUnifiedLog(rawLine: String) {
        statusStore.appendLog(rawLine)
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return
        val source = if (trimmed.startsWith("[runtime]") || trimmed.startsWith("[error]")) {
            "runtime"
        } else {
            "openvscode.stdout"
        }
        val lower = trimmed.lowercase()
        when {
            trimmed.startsWith("[error]") ||
                lower.startsWith("error:") ||
                lower.startsWith("uncaught") ||
                lower.contains("] [error]") ||
                lower.contains("fatal error") ->
                telemetry.error(source, trimmed)
            trimmed.startsWith("[warn]") ||
                lower.startsWith("warning:") ||
                lower.contains("] [warning]") ||
                lower.contains("] [warn]") ->
                telemetry.warn(source, trimmed)
            else ->
                telemetry.info(source, trimmed)
        }
    }

    private val installer = RuntimeInstaller(
        openAsset = { path -> appContext.assets.open(path) },
        querySystemDnsServers = ::querySystemDnsServers,
        loadHostCaCertificates = CaTrustSync::loadAndroidTrustStoreCertificates,
        onProgress = { message, pct ->
            statusStore.update(hasAllFilesAccess()) {
                it.copy(state = State.EXTRACTING, message = message, progress = pct)
            }
        },
        onLog = ::appendUnifiedLog,
    )

    private val shellExecutor = PosixShellExecutor(
        ensureMountDirs = installer::ensureMountPointDirsExist,
    )

    private val launcher = OpenVsCodeLauncher(
        scope = scope,
        onLog = ::appendUnifiedLog,
    )

    @Volatile
    private var serverProcess: Process? = null

    @Volatile
    private var disableSeccomp: Boolean = false

    @Volatile
    private var hostActions: HostActions? = null

    private val diagnosticsServer = LocalDiagnosticsServer(
        onRunDiagnostics = { DiagnosticsRunner.collect(appContext, this) },
        onGetLogs = { getLogsText() },
        onCopyToClipboard = { text -> hostActions?.onCopyToClipboard?.invoke(text) ?: false },
        onReadFromClipboard = { hostActions?.onReadFromClipboard?.invoke() },
        onOpenExternalUrl = { url -> openExternalUrlViaHost(url) },
        currentEditorPortProvider = { statusStore.currentState().port },
        onListSessions = { telemetry.listSessionsJson() },
        onGetSessionBundle = { selector, format ->
            telemetry.store.renderSessionBundle(selector, format, getPaths().vscodeDataDir)
        },
        onRecordClientEvent = { level, source, message ->
            when (level.uppercase()) {
                "ERROR", "FATAL" -> telemetry.error(source, message)
                "WARN", "WARNING" -> telemetry.warn(source, message)
                else -> telemetry.info(source, message)
            }
        },
        onAuditRequest = { method, path, statusCode, durationMs, detail ->
            val note = if (detail.isNotEmpty()) " ($detail)" else ""
            val msg = "$method $path -> $statusCode (${durationMs}ms)$note"
            when {
                statusCode >= 500 -> telemetry.error("host.api", msg)
                statusCode >= 400 -> telemetry.warn("host.api", msg)
                else -> telemetry.info("host.api", msg)
            }
        },
        onOpenBootPage = { hostActions?.onOpenBootPage?.invoke() },
        onRequestStoragePermission = { hostActions?.onRequestStoragePermission?.invoke() },
        onRestartServer = { restart() },
    )

    fun getAdminPort(): Int = diagnosticsServer.port

    init {
        LogRedactor.registerSecret(diagnosticsServer.authToken)
        telemetry.bindRuntimeProviders(
            isServerAlive = ::isServerProcessAlive,
            currentRuntimeState = { statusStore.currentState().state.name },
        )
    }

    private fun openExternalUrlViaHost(rawUrl: String): Boolean {
        val customOpener = hostActions?.onOpenExternalUrl
        if (customOpener != null) {
            return customOpener.invoke(rawUrl)
        }
        return runCatching {
            val uri = Uri.parse(rawUrl)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            telemetry.recordExternalBrowserLaunch(rawUrl, "host.api.appContext", launched = true, detail = "NEW_TASK")
            true
        }.getOrElse { e ->
            telemetry.recordExternalBrowserLaunch(
                rawUrl,
                "host.api.appContext",
                launched = false,
                detail = "${e.javaClass.simpleName}: ${e.message}",
            )
            false
        }
    }

    fun setStatusListener(listener: ((StatusSnapshot) -> Unit)?) {
        statusStore.setListener(listener)
    }

    fun setHostActions(actions: HostActions?) {
        hostActions = actions
    }

    /**
     * Pure path builder without filesystem writes (`mkdirs` happens strictly on [Dispatchers.IO]
     * inside [RuntimeInstaller.ensureMountPointDirsExist]).
     */
    fun getPaths(): PosixRuntimeConfig.Paths {
        val runtimeRoot = File(appContext.filesDir, "runtime")
        val workspaceDir = File(appContext.filesDir, "workspace")
        val homeDir = File(appContext.filesDir, "home")
        val nativeLibDir = File(appContext.applicationInfo.nativeLibraryDir)
        val diagnosticsDir = File(appContext.filesDir, "diagnostics")
        val sharedDir = appContext.getExternalFilesDir("shared")
            ?: File(appContext.filesDir, "shared")

        val sdcardDir = if (hasAllFilesAccess()) {
            Environment.getExternalStorageDirectory()
        } else {
            null
        }

        return PosixRuntimeConfig.Paths(
            runtimeRoot = runtimeRoot,
            workspaceDir = workspaceDir,
            homeDir = homeDir,
            nativeLibDir = nativeLibDir,
            androidSharedDir = sharedDir,
            androidSdcardDir = sdcardDir,
            diagnosticsDir = diagnosticsDir,
        )
    }

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun querySystemDnsServers(): List<String> {
        return runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return emptyList()
            val network = cm.activeNetwork ?: return emptyList()
            val props = cm.getLinkProperties(network) ?: return emptyList()
            props.dnsServers.mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }

    fun getStatus(): StatusSnapshot = statusStore.snapshot(hasAllFilesAccess())

    fun editorLaunchUrl(): String? = statusStore.editorLaunchUrl()

    fun getLogsText(): String = statusStore.getLogsText()

    fun clearLogs() {
        statusStore.clearLogs()
    }

    fun startIfNeeded() {
        telemetry.ensureSessionActive("start_if_needed")
        scope.launch {
            lifecycleMutex.withLock {
                if (statusStore.currentState().state == State.READY && isServerProcessAlive()) {
                    statusStore.emitCurrent(hasAllFilesAccess())
                    return@withLock
                }
                if (serverProcess != null) stopInternal(emitIdleStatus = true)
                startInternal()
            }
        }
    }

    fun restart() {
        telemetry.info("runtime", "Manual server restart requested")
        scope.launch {
            lifecycleMutex.withLock {
                stopInternal(emitIdleStatus = true)
                startInternal()
            }
        }
    }

    fun stop() {
        scope.launch {
            lifecycleMutex.withLock {
                stopInternal(emitIdleStatus = true)
            }
        }
    }

    suspend fun runShellCommand(
        command: String,
        timeoutSeconds: Long = 120,
        forceNoSeccomp: Boolean? = null,
        recordTelemetry: Boolean = false,
    ): CommandResult {
        val adminPort = runCatching { diagnosticsServer.start() }.getOrDefault(0)
        val extraEnv = if (adminPort > 0) {
            mapOf(
                "KUCODE_ADMIN_PORT" to adminPort.toString(),
                "KUCODE_ADMIN_TOKEN" to diagnosticsServer.authToken,
                "BROWSER" to "/usr/local/bin/kucode-open",
                "GH_BROWSER" to "/usr/local/bin/kucode-open",
            )
        } else {
            emptyMap()
        }
        val t0 = System.currentTimeMillis()
        val result = shellExecutor.execute(
            paths = getPaths(),
            command = command,
            timeoutSeconds = timeoutSeconds,
            disableSeccomp = forceNoSeccomp ?: disableSeccomp,
            extraEnv = extraEnv,
        )
        if (recordTelemetry) {
            val elapsed = System.currentTimeMillis() - t0
            val preview = result.output.trim().take(500)
            val msg = "CLI command (exit=${result.exitCode}, ${elapsed}ms): $command" +
                if (preview.isNotEmpty()) "\n$preview" else ""
            if (result.exitCode == 0) {
                telemetry.info("posix.cli", msg)
            } else {
                telemetry.warn("posix.cli", msg)
            }
        }
        return result
    }

    internal fun editorAccessVerified(): Boolean {
        val current = statusStore.currentState()
        val token = current.editorToken ?: return false
        return current.state == State.READY && current.port > 0 && verifyEditorAccess(current.port, token)
    }

    private suspend fun startInternal() {
        val paths = getPaths()
        val prootReady = paths.prootBin.exists() && paths.prootLoader.exists()
        statusStore.update(hasAllFilesAccess(), notify = false) {
            it.copy(
                workspacePath = paths.workspaceDir.absolutePath,
                prootReady = prootReady,
            )
        }
        if (!prootReady) {
            val snap = statusStore.update(hasAllFilesAccess()) {
                it.copy(
                    state = State.ERROR,
                    message = "PRoot バイナリが見つかりません (${paths.nativeLibDir.absolutePath})",
                    progress = 0,
                    port = 0,
                    editorToken = null,
                )
            }
            telemetry.updateRuntimeSnapshot(snap, adminPort = diagnosticsServer.port, disableSeccomp = disableSeccomp)
            appendUnifiedLog("[error] Missing libproot.so or libproot-loader.so in ${paths.nativeLibDir}")
            return
        }

        var stagedNewInstall = false
        try {
            stagedNewInstall = installer.ensureExtracted(paths) == RuntimeInstaller.ExtractOutcome.STAGED_NEW_INSTALL
            installer.prepareRuntimeDirectories(paths)
            installer.ensureMachineVsCodeSettings(paths)

            val adminPort = diagnosticsServer.start()
            val result = launcher.launch(
                paths = paths,
                adminPort = adminPort,
                adminToken = diagnosticsServer.authToken,
                initialDisableSeccomp = disableSeccomp,
                allowSeccompRetry = true,
                onStarting = { port ->
                    val snap = statusStore.update(hasAllFilesAccess()) {
                        it.copy(
                            state = State.STARTING,
                            message = "内蔵 openvscode-server を起動中 (127.0.0.1:$port)...",
                            progress = 92,
                            port = port,
                            editorToken = null,
                        )
                    }
                    telemetry.updateRuntimeSnapshot(snap, adminPort = adminPort, disableSeccomp = disableSeccomp)
                },
                onProcessCreated = { proc -> serverProcess = proc },
                onUnexpectedExit = { proc, code ->
                    if (serverProcess === proc && statusStore.currentState().state == State.READY) {
                        serverProcess = null
                        val snap = statusStore.update(hasAllFilesAccess()) {
                            it.copy(
                                state = State.ERROR,
                                message = "サーバーが終了しました (exit=$code)",
                                progress = 0,
                                port = 0,
                                editorToken = null,
                            )
                        }
                        telemetry.error("runtime", "openvscode-server exited unexpectedly while READY (exit=$code)")
                        telemetry.harvestOpenVsCodeLogsNow()
                        telemetry.updateRuntimeSnapshot(snap, adminPort = diagnosticsServer.port, disableSeccomp = disableSeccomp)
                        telemetry.captureMetricSnapshotAsync("server_unexpected_exit_$code", includePss = true)
                    }
                },
            )

            when (result) {
                is OpenVsCodeLauncher.LaunchResult.Ready -> {
                    disableSeccomp = result.disableSeccomp
                    serverProcess = result.process
                    LogRedactor.registerSecret(result.token)
                    installer.commitInstallation(paths)
                    val snap = statusStore.update(hasAllFilesAccess()) {
                        it.copy(
                            state = State.READY,
                            message = "起動完了 (127.0.0.1:${result.port})",
                            progress = 100,
                            port = result.port,
                            editorToken = result.token,
                        )
                    }
                    appendUnifiedLog("[runtime] openvscode-server is READY at http://127.0.0.1:${result.port}")
                    telemetry.updateRuntimeSnapshot(snap, adminPort = adminPort, disableSeccomp = disableSeccomp)
                    telemetry.captureMetricSnapshotAsync("server_ready", includePss = true)
                }

                is OpenVsCodeLauncher.LaunchResult.Failed -> {
                    disableSeccomp = result.disableSeccomp
                    if (stagedNewInstall) {
                        runCatching { installer.rollbackInstallation(paths) }
                    }
                    telemetry.harvestOpenVsCodeLogsNow()
                    stopInternal(emitIdleStatus = false)
                    val snap = statusStore.update(hasAllFilesAccess()) {
                        it.copy(
                            state = State.ERROR,
                            message = "openvscode-server の起動に失敗しました (exit=${result.exitCode})",
                            progress = 0,
                            port = 0,
                            editorToken = null,
                        )
                    }
                    telemetry.error("runtime", "openvscode-server failed to start (exit=${result.exitCode}, disableSeccomp=$disableSeccomp)")
                    telemetry.updateRuntimeSnapshot(snap, adminPort = diagnosticsServer.port, disableSeccomp = disableSeccomp)
                    telemetry.captureMetricSnapshotAsync("server_launch_failed", includePss = true)
                }

                is OpenVsCodeLauncher.LaunchResult.TimedOut -> {
                    disableSeccomp = result.disableSeccomp
                    if (stagedNewInstall) {
                        runCatching { installer.rollbackInstallation(paths) }
                    }
                    telemetry.harvestOpenVsCodeLogsNow()
                    stopInternal(emitIdleStatus = false)
                    val snap = statusStore.update(hasAllFilesAccess()) {
                        it.copy(
                            state = State.ERROR,
                            message = "openvscode-server 応答タイムアウト (${result.timeoutMs / 1000}s)",
                            progress = 0,
                            port = 0,
                            editorToken = null,
                        )
                    }
                    telemetry.error("runtime", "openvscode-server startup timed out after ${result.timeoutMs}ms")
                    telemetry.updateRuntimeSnapshot(snap, adminPort = diagnosticsServer.port, disableSeccomp = disableSeccomp)
                    telemetry.captureMetricSnapshotAsync("server_launch_timeout", includePss = true)
                }
            }
        } catch (cancel: CancellationException) {
            if (stagedNewInstall) {
                runCatching { installer.rollbackInstallation(paths) }
            }
            stopInternal(emitIdleStatus = true)
            throw cancel
        } catch (e: Exception) {
            if (stagedNewInstall) {
                runCatching { installer.rollbackInstallation(paths) }
            }
            val lastProgress = statusStore.currentState().progress
            stopInternal(emitIdleStatus = false)
            appendUnifiedLog("[error] Startup failed: ${e.stackTraceToString()}")
            val snap = statusStore.update(hasAllFilesAccess()) {
                it.copy(
                    state = State.ERROR,
                    message = "起動失敗: ${e.message}",
                    progress = lastProgress,
                    port = 0,
                    editorToken = null,
                )
            }
            telemetry.updateRuntimeSnapshot(snap, adminPort = diagnosticsServer.port, disableSeccomp = disableSeccomp)
            telemetry.captureMetricSnapshotAsync("startup_exception", includePss = true)
        }
    }

    private fun stopInternal(emitIdleStatus: Boolean) {
        val proc = serverProcess
        serverProcess = null
        if (proc != null) {
            runCatching { telemetry.harvestOpenVsCodeLogsNow() }
        }
        if (proc != null && proc.isAlive) {
            appendUnifiedLog("[runtime] Stopping openvscode-server...")
            terminateServer(proc)
        }
        diagnosticsServer.stop()
        val snap = statusStore.update(hasAllFilesAccess(), notify = emitIdleStatus) {
            it.copy(
                state = State.IDLE,
                message = "停止しました",
                progress = 0,
                port = 0,
                editorToken = null,
            )
        }
        telemetry.updateRuntimeSnapshot(snap, adminPort = 0, disableSeccomp = disableSeccomp)
    }

    private fun isServerProcessAlive(): Boolean = serverProcess?.isAlive == true

    companion object {
        @Volatile
        private var instance: PosixRuntimeManager? = null

        fun getInstance(context: Context): PosixRuntimeManager {
            return instance ?: synchronized(this) {
                instance ?: PosixRuntimeManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
