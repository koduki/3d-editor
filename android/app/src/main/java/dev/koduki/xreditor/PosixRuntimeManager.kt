package dev.koduki.xreditor

import android.content.Context
import android.net.ConnectivityManager
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Manages the embedded PRoot + Debian minimal + openvscode-server lifecycle on-device:
 * - Keeps user `/workspace` decoupled at `filesDir/workspace` so rootfs upgrades never risk user data
 * - Atomically extracts bundled runtime .tgz assets on first boot
 * - Dynamically syncs Android's active DNS servers into `/etc/resolv.conf`
 * - Uses a stable preferred localhost port (18765) so VS Code Web preserves tabs & state across restarts
 * - Provides a non-PTY POSIX shell execution fallback without disturbing live runtime state
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
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val logs = CopyOnWriteArrayList<String>()

    @Volatile
    private var state: State = State.IDLE

    @Volatile
    private var statusMessage: String = "起動待機中..."

    @Volatile
    private var progress: Int = 0

    @Volatile
    private var serverPort: Int = 0

    @Volatile
    private var serverProcess: Process? = null

    @Volatile
    private var disableSeccomp: Boolean = false

    @Volatile
    private var onStatusChanged: ((StatusSnapshot) -> Unit)? = null

    @Volatile
    private var hostActions: HostActions? = null

    @Volatile
    private var editorToken: String? = null

    fun editorLaunchUrl(): String? {
        val token = editorToken ?: return null
        val url = getStatus().serverUrl
        return if (url.isEmpty()) null else "$url&tkn=$token"
    }

    private val diagnosticsServer = LocalDiagnosticsServer(
        onRunDiagnostics = { DiagnosticsRunner.collect(appContext, this) },
        onGetLogs = { getLogsText() },
        onCopyToClipboard = { text -> hostActions?.onCopyToClipboard?.invoke(text) ?: false },
        onOpenBootPage = { hostActions?.onOpenBootPage?.invoke() },
        onRequestStoragePermission = { hostActions?.onRequestStoragePermission?.invoke() },
        onRestartServer = { restart() },
    )

    fun setStatusListener(listener: ((StatusSnapshot) -> Unit)?) {
        onStatusChanged = listener
    }

    fun setHostActions(actions: HostActions?) {
        hostActions = actions
    }

    fun getPaths(): PosixRuntimeConfig.Paths {
        val runtimeRoot = File(appContext.filesDir, "runtime")
        val workspaceDir = File(appContext.filesDir, "workspace")
        val homeDir = File(appContext.filesDir, "home")
        val nativeLibDir = File(appContext.applicationInfo.nativeLibraryDir)
        val sharedDir = appContext.getExternalFilesDir("shared")
            ?: File(appContext.filesDir, "shared")
        sharedDir.mkdirs()

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

    fun getStatus(): StatusSnapshot {
        val paths = getPaths()
        val url = if (state == State.READY && serverPort > 0) {
            "http://127.0.0.1:$serverPort/?folder=${PosixRuntimeConfig.GUEST_WORKSPACE}"
        } else {
            ""
        }
        return StatusSnapshot(
            state = state,
            message = statusMessage,
            progress = progress,
            serverUrl = url,
            port = serverPort,
            hasAllFilesAccess = hasAllFilesAccess(),
            workspacePath = paths.workspaceDir.absolutePath,
            prootReady = paths.prootBin.exists() && paths.prootLoader.exists(),
        )
    }

    fun getLogsText(): String = logs.joinToString("\n")

    fun clearLogs() {
        logs.clear()
    }

    fun startIfNeeded() {
        scope.launch {
            lifecycleMutex.withLock {
                if (state == State.READY && isServerProcessAlive()) {
                    emitStatus()
                    return@withLock
                }
                if (serverProcess != null) stopInternal()
                startInternal()
            }
        }
    }

    fun restart() {
        scope.launch {
            lifecycleMutex.withLock {
                stopInternal()
                startInternal()
            }
        }
    }

    fun stop() {
        scope.launch {
            lifecycleMutex.withLock {
                stopInternal()
            }
        }
    }

    suspend fun runShellCommand(
        command: String,
        timeoutSeconds: Long = 120,
        forceNoSeccomp: Boolean? = null,
    ): CommandResult =
        withContext(Dispatchers.IO) {
            val paths = getPaths()
            if (!paths.prootBin.exists() || !paths.prootLoader.exists()) {
                return@withContext CommandResult(
                    exitCode = 127,
                    output = "PRoot binaries not found in ${paths.nativeLibDir.absolutePath}",
                )
            }
            if (!paths.rootfsDir.exists()) {
                return@withContext CommandResult(
                    exitCode = 1,
                    output = "Debian rootfs is not extracted yet.",
                )
            }

            // Ensure scratch/mount directories exist without rewriting live server config/extensions
            ensureMountPointDirsExist(paths)
            val prootCmd = PosixRuntimeConfig.buildProotCommand(
                paths = paths,
                guestCommand = PosixRuntimeConfig.buildShellCommand(command),
            )
            val pb = ProcessBuilder(prootCmd)
            pb.redirectErrorStream(true)
            val noSeccomp = forceNoSeccomp ?: disableSeccomp
            pb.environment().putAll(PosixRuntimeConfig.buildProotEnvironment(paths, noSeccomp))

            val output = StringBuilder()
            try {
                val proc = pb.start()
                val readerThread = Thread {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            output.append(line).append('\n')
                        }
                    }
                }
                readerThread.start()
                val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    readerThread.join(1000)
                    return@withContext CommandResult(
                        exitCode = 124,
                        output = output.toString() + "\n[Timed out after ${timeoutSeconds}s]",
                    )
                }
                readerThread.join(2000)
                CommandResult(proc.exitValue(), output.toString())
            } catch (t: Throwable) {
                CommandResult(1, "Failed to run command: ${t.message}")
            }
        }

    private suspend fun startInternal() {
        val paths = getPaths()
        if (!paths.prootBin.exists() || !paths.prootLoader.exists()) {
            updateStatus(
                State.ERROR,
                "PRoot バイナリが見つかりません (${paths.nativeLibDir.absolutePath})",
                0,
            )
            appendLog("[error] Missing libproot.so or libproot-loader.so in ${paths.nativeLibDir}")
            return
        }

        try {
            ensureExtracted(paths)
            prepareRuntimeDirectories(paths)
            ensureMinimalVsCodeSettings(paths)
            launchOpenVsCodeServer(paths, allowSeccompRetry = true)
        } catch (t: Throwable) {
            stopInternal()
            appendLog("[error] Startup failed: ${t.stackTraceToString()}")
            updateStatus(State.ERROR, "起動失敗: ${t.message}", progress)
        }
    }

    private fun stopInternal() {
        val proc = serverProcess
        if (proc != null && proc.isAlive) {
            appendLog("[runtime] Stopping openvscode-server...")
            terminateServer(proc)
        }
        serverProcess = null
        editorToken = null
        serverPort = 0
        diagnosticsServer.stop()
        updateStatus(State.IDLE, "停止しました", 0)
    }

    private fun ensureExtracted(paths: PosixRuntimeConfig.Paths) {
        // Recover an interrupted swap before checking the current installation.
        val backup = File(paths.runtimeRoot, "rootfs.previous")
        if (backup.exists()) {
            if (paths.rootfsDir.exists() && paths.versionMarker.isFile && paths.versionMarker.readText() == PosixRuntimeConfig.RUNTIME_VERSION) {
                removeRuntimeTree(backup)
            } else {
                removeRuntimeTree(paths.rootfsDir)
                Files.move(backup.toPath(), paths.rootfsDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        }
        val installed = paths.versionMarker.takeIf { it.exists() }?.readText()?.trim()
        val hasServerBin = File(paths.openvscodeDir, "bin/openvscode-server").exists()
        if (installed == PosixRuntimeConfig.RUNTIME_VERSION && hasServerBin) {
            appendLog("[runtime] Existing Debian + openvscode-server installation verified ($installed)")
            return
        }

        updateStatus(State.EXTRACTING, "初回セットアップ: Debian minimal rootfs を展開中...", 10)
        appendLog("[runtime] Extracting embedded Debian minimal rootfs...")

        paths.runtimeRoot.mkdirs()
        removeRuntimeTree(paths.stagingDir)
        Files.createDirectory(paths.stagingDir.toPath())

        appContext.assets.open(PosixRuntimeConfig.ASSET_ROOTFS).use { stream ->
            val stats = TarGzExtractor.extract(
                input = stream,
                destDir = paths.stagingDir,
                stripComponents = 0,
            ) { entries, current ->
                val pct = (10 + (entries / 250).coerceAtMost(35))
                updateStatus(State.EXTRACTING, "Debian rootfs 展開中 ($entries)... $current", pct)
            }
            appendLog("[runtime] Debian rootfs extracted: $stats")
        }

        updateStatus(State.EXTRACTING, "初回セットアップ: openvscode-server を展開中...", 50)
        appendLog("[runtime] Extracting embedded openvscode-server...")
        val stagingVscode = TarGzExtractor.resolveSafeChild(paths.stagingDir.canonicalFile, "opt/openvscode-server")
        appContext.assets.open(PosixRuntimeConfig.ASSET_OPENVSCODE).use { stream ->
            val stats = TarGzExtractor.extract(
                input = stream,
                destDir = stagingVscode,
                stripComponents = 1,
            ) { entries, current ->
                val pct = (50 + (entries / 200).coerceAtMost(35))
                updateStatus(State.EXTRACTING, "openvscode-server 展開中 ($entries)... $current", pct)
            }
            appendLog("[runtime] openvscode-server extracted: $stats")
        }

        Files.deleteIfExists(paths.versionMarker.toPath())
        if (paths.rootfsDir.exists()) {
            Files.move(paths.rootfsDir.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        Files.move(paths.stagingDir.toPath(), paths.rootfsDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
        // Commit the staged root only after the editor has successfully started.
        updateStatus(State.EXTRACTING, "展開完了。サーバーを起動します...", 88)
        appendLog("[runtime] Extraction finished cleanly.")
    }

    private fun writePrivateFile(target: File, value: String) {
        val temporary = Files.createTempFile(target.parentFile!!.toPath(), ".pending-", null)
        try {
            Files.write(temporary, value.toByteArray(Charsets.US_ASCII))
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun removeRuntimeTree(root: File) {
        // Files.walk does not follow links. A Debian rootfs contains guest-absolute symlinks.
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root.toPath()).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    private fun ensureMountPointDirsExist(paths: PosixRuntimeConfig.Paths) {
        paths.sysdataDir.mkdirs()
        paths.shmDir.mkdirs()
        paths.tmpDir.mkdirs()
        paths.vscodeDataDir.mkdirs()
        paths.workspaceDir.mkdirs()
        paths.homeDir.mkdirs()
        paths.androidSharedDir.mkdirs()

        // Ensure mount target directories exist inside rootfs
        File(paths.rootfsDir, "workspace").mkdirs()
        File(paths.rootfsDir, "android/shared").mkdirs()
        File(paths.rootfsDir, "android/sdcard").mkdirs()
        File(paths.rootfsDir, "var/lib/xr-vscode").mkdirs()
        File(paths.rootfsDir, "dev/shm").mkdirs()
        File(paths.rootfsDir, "tmp").mkdirs()
        File(paths.rootfsDir, "root").mkdirs()
    }

    private fun prepareRuntimeDirectories(paths: PosixRuntimeConfig.Paths) {
        ensureMountPointDirsExist(paths)

        // Create /workspace/android -> /android symlink for convenient explorer access
        val workspaceAndroidLink = File(paths.workspaceDir, "android")
        runCatching {
            TarGzExtractor.DefaultPosixFsOps.symlink("/android", workspaceAndroidLink)
        }

        // Seed welcome README.md if workspace has no README yet
        val readme = File(paths.workspaceDir, "README.md")
        if (!readme.exists()) {
            readme.writeText(PosixRuntimeConfig.buildWelcomeReadme())
        }

        // Seed persistent .bashrc in decoupled /root if absent
        val bashrc = File(paths.homeDir, ".bashrc")
        if (!bashrc.exists()) {
            bashrc.writeText(PosixRuntimeConfig.buildDefaultBashrc())
        }

        // Write fake /proc files to avoid Android SELinux denials in Node.js / Linux tools
        val cpus = Runtime.getRuntime().availableProcessors()
        for ((name, content) in PosixRuntimeConfig.buildFakeSysdataFiles(cpus)) {
            File(paths.sysdataDir, name).writeText(content)
        }

        // Sync active Android DNS servers into /etc/resolv.conf (fallback: 1.1.1.1 / 8.8.8.8)
        val dnsServers = querySystemDnsServers()
        val resolvConf = File(paths.rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        if (resolvConf.exists() && resolvConf.isDirectory) {
            resolvConf.deleteRecursively()
        }
        resolvConf.writeText(PosixRuntimeConfig.buildResolvConf(dnsServers))

        // Always sync the built-in VS Code extension (`xr-editor-tools`) so upgrades take effect
        // immediately without re-extracting the entire rootfs/openvscode-server archive
        if (paths.openvscodeDir.exists()) {
            val extDir = VsCodeExtensionAsset.installInto(paths.openvscodeDir)
            appendLog("[runtime] Synced built-in VS Code extension: ${extDir.absolutePath}")
        }
    }

    private fun ensureMinimalVsCodeSettings(paths: PosixRuntimeConfig.Paths) {
        val targets = listOf(
            File(paths.vscodeDataDir, "data/Machine/settings.json"),
            File(paths.vscodeDataDir, "user/User/settings.json"),
        )
        for (target in targets) {
            val existing = target.takeIf { it.exists() }?.readText()
            val merged = PosixRuntimeConfig.mergeDefaultVsCodeSettings(existing)
            if (existing != merged) {
                target.parentFile?.mkdirs()
                target.writeText(merged)
            }
        }
    }

    private suspend fun launchOpenVsCodeServer(
        paths: PosixRuntimeConfig.Paths,
        allowSeccompRetry: Boolean,
    ) {
        val adminPort = diagnosticsServer.start()
        val port = pickStableLocalPort()
        serverPort = port
        updateStatus(State.STARTING, "内蔵 openvscode-server を起動中 (127.0.0.1:$port)...", 92)

        val token = generateConnectionToken()
        // Replace the directory entry atomically, including any stale token from an interrupted boot.
        writePrivateFile(File(paths.vscodeDataDir, ".editor-token"), token)
        val guestCmd = PosixRuntimeConfig.buildOpenVsCodeServerCommand(port)
        val prootCmd = PosixRuntimeConfig.buildProotCommand(paths, guestCmd)
        appendLog("[runtime] Launching PRoot (disableSeccomp=$disableSeccomp) on 127.0.0.1:$port (admin=$adminPort)")

        val pb = ProcessBuilder(prootCmd)
        pb.redirectErrorStream(true)
        pb.environment().putAll(
            PosixRuntimeConfig.buildProotEnvironment(
                paths = paths,
                disableSeccomp = disableSeccomp,
                adminPort = adminPort,
                adminToken = diagnosticsServer.authToken,
            ),
        )

        val proc = pb.start()
        serverProcess = proc

        val announced = AtomicBoolean(false)
        // Drain process output continuously into the ring buffer
        scope.launch {
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.contains("http://localhost:$port") && line.contains("?tkn=$token")) {
                            announced.set(true)
                        }
                        appendLog(line.replace(token, "[redacted]"))
                    }
                }
            }
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            appendLog("[runtime] openvscode-server exited with code $code")
            if (serverProcess === proc && state == State.READY) {
                updateStatus(State.ERROR, "サーバーが終了しました (exit=$code)", 0)
            }
        }

        val startup = awaitServerStartup(proc, 45_000L) { announced.get() && editorAccessVerified(port, token) }
        when (startup) {
            ServerStartup.READY -> {
                if (!paths.versionMarker.exists()) {
                    writePrivateFile(paths.versionMarker, PosixRuntimeConfig.RUNTIME_VERSION)
                }
                editorToken = token
                updateStatus(State.READY, "起動完了 (127.0.0.1:$port)", 100)
                appendLog("[runtime] openvscode-server is READY at http://127.0.0.1:$port")
            }
            ServerStartup.EXITED -> {
                val exitCode = proc.exitValue()
                if (allowSeccompRetry && !disableSeccomp) {
                    appendLog("[runtime] Early exit ($exitCode); retrying with PROOT_NO_SECCOMP=1...")
                    disableSeccomp = true
                    launchOpenVsCodeServer(paths, allowSeccompRetry = false)
                } else {
                    stopInternal()
                    updateStatus(State.ERROR, "openvscode-server の起動に失敗しました (exit=$exitCode)", 0)
                }
            }
            ServerStartup.TIMED_OUT -> {
                stopInternal()
                updateStatus(State.ERROR, "openvscode-server 応答タイムアウト (45s)", 0)
            }
        }
    }

    private fun isServerProcessAlive(): Boolean = serverProcess?.isAlive == true

    /**
     * Prefers fixed port 18765 so WebView keeps a stable origin (preserving VS Code tabs,
     * IndexedDB state, and layout across restarts), falling back to an ephemeral port if busy.
     */
    private fun pickStableLocalPort(): Int {
        val loopback = InetAddress.getByName("127.0.0.1")
        try {
            ServerSocket(PosixRuntimeConfig.PREFERRED_SERVER_PORT, 1, loopback).use {
                return it.localPort
            }
        } catch (_: Throwable) {
            // Preferred port busy — pick an available ephemeral port
        }
        return try {
            ServerSocket(0, 1, loopback).use { it.localPort }
        } catch (_: Throwable) {
            PosixRuntimeConfig.PREFERRED_SERVER_PORT
        }
    }

    internal fun editorAccessVerified(): Boolean {
        val token = editorToken ?: return false
        return state == State.READY && editorAccessVerified(serverPort, token)
    }

    private fun editorAccessVerified(port: Int, token: String): Boolean =
        editorHttpStatus(port, null) == HttpURLConnection.HTTP_FORBIDDEN &&
            editorHttpStatus(port, token) == HttpURLConnection.HTTP_OK

    private fun editorHttpStatus(port: Int, token: String?): Int {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("Cookie", if (token == null) "" else "vscode-tkn=$token")
                connectTimeout = 500
                readTimeout = 500
                instanceFollowRedirects = false
            }
            conn.responseCode
        } catch (_: java.io.IOException) {
            -1
        } finally {
            conn?.disconnect()
        }
    }

    private fun updateStatus(newState: State, message: String, newProgress: Int) {
        state = newState
        statusMessage = message
        progress = newProgress
        emitStatus()
    }

    private fun emitStatus() {
        val snapshot = getStatus()
        onStatusChanged?.invoke(snapshot)
    }

    private fun appendLog(line: String) {
        logs.add(line)
        while (logs.size > MAX_LOG_LINES) {
            logs.removeAt(0)
        }
    }

    companion object {
        private const val MAX_LOG_LINES = 300

        @Volatile
        private var instance: PosixRuntimeManager? = null

        fun getInstance(context: Context): PosixRuntimeManager {
            return instance ?: synchronized(this) {
                instance ?: PosixRuntimeManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

internal enum class ServerStartup { READY, EXITED, TIMED_OUT }

internal suspend fun awaitServerStartup(process: Process, timeoutMs: Long, isReady: () -> Boolean): ServerStartup {
    val start = System.nanoTime()
    while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < timeoutMs) {
        if (!process.isAlive) return ServerStartup.EXITED
        if (isReady() && process.isAlive) return ServerStartup.READY
        delay(350)
    }
    terminateServer(process)
    return ServerStartup.TIMED_OUT
}

private fun terminateServer(process: Process) {
    process.destroy()
    if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        process.waitFor()
    }
}
