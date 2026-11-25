package dev.koduki.kucode

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide Android flight recorder coordinating [SessionLogStore] across:
 * - Application boot & `ApplicationExitInfo` reconciliation (ANR, OOM/LMKD, native crash, signals)
 * - Chaining `Thread.UncaughtExceptionHandler` for synchronous, OOM-safe crash persistence
 * - Activity & Service lifecycle, window focus, `onTrimMemory`, thermal & network transitions
 * - Process-own `logcat --pid=<myPid>` streaming (Chromium WebView C++, IME, GL/Vulkan, ART warnings)
 * - Periodic and event-driven resource & health metrics sampling
 *
 * Routine writes and Binder permission queries are serialized on a dedicated daemon worker so
 * Android's main UI thread never blocks on disk I/O or lock contention.
 */
class SessionTelemetryManager private constructor(private val appContext: Context) {

    private val diagnosticsDir = File(appContext.filesDir, "diagnostics")
    private val vscodeDataDir = File(appContext.filesDir, "runtime/vscode-data")

    internal val store = SessionLogStore(diagnosticsRootDir = diagnosticsDir)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kucode-telemetry-writer").apply { isDaemon = true }
    }
    private val hooksInstalled = AtomicBoolean(false)
    private val crashHandlerInstalled = AtomicBoolean(false)
    private val handlingCrash = AtomicBoolean(false)
    private val initialReconcileStarted = AtomicBoolean(false)
    private val initialReconcileLatch = CountDownLatch(1)

    private val sessionLifecycleLock = Any()
    private val logcatLock = Any()

    @Volatile
    private var logcatProcess: Process? = null

    @Volatile
    private var logcatThread: Thread? = null

    @Volatile
    private var hasStartedSessionInProcess: Boolean = false

    @Volatile
    private var pendingReconciledNotes: List<SessionLogStore.SessionSummary> = emptyList()

    @Volatile
    private var metricsJob: Job? = null

    @Volatile
    private var serverAliveProvider: () -> Boolean = { false }

    @Volatile
    private var runtimeStateProvider: () -> String = { "IDLE" }

    @Volatile
    private var lastActiveView: String = "BOOT"

    @Volatile
    private var lastWindowFocus: Boolean = true

    fun diagnosticsRootDirectory(): File = diagnosticsDir

    fun bindRuntimeProviders(
        isServerAlive: () -> Boolean,
        currentRuntimeState: () -> String,
    ) {
        serverAliveProvider = isServerAlive
        runtimeStateProvider = currentRuntimeState
    }

    /**
     * Installs crash handler + OS lifecycle callbacks and asynchronously reconciles unfinished
     * sessions from prior process lifetimes without opening a new `RUNNING` session.
     * Safe to invoke from [KuCodeApplication.onCreate] even when woken only for [KuCodeDocumentsProvider].
     */
    fun initializeProcessHooks() {
        if (hooksInstalled.compareAndSet(false, true)) {
            installUncaughtExceptionHandlerOnce()
            registerSystemCallbacks()
        }
        triggerInitialReconcileAsync()
    }

    private fun triggerInitialReconcileAsync() {
        if (!initialReconcileStarted.compareAndSet(false, true)) return
        enqueueWrite {
            try {
                val myPid = android.os.Process.myPid()
                val exitRecords = collectHistoricalExitRecords(appContext)
                val reconciled = store.reconcileUnfinishedSessions(
                    currentPid = myPid,
                    exitRecords = exitRecords,
                    vscodeDataDir = vscodeDataDir,
                )
                if (reconciled.isNotEmpty()) {
                    pendingReconciledNotes = reconciled
                }
            } finally {
                initialReconcileLatch.countDown()
            }
        }
    }

    private fun awaitInitialReconcile() {
        if (!initialReconcileStarted.get()) {
            triggerInitialReconcileAsync()
        }
        runCatching { initialReconcileLatch.await(3, TimeUnit.SECONDS) }
    }

    private fun enqueueWrite(block: () -> Unit) {
        runCatching {
            writerExecutor.execute {
                runCatching(block)
            }
        }
    }

    /**
     * Ensures an active session exists (starts a fresh session on launch or after a previous
     * session in the same warm OS process ended cleanly).
     */
    fun ensureSessionActive(trigger: String) {
        initializeProcessHooks()
        enqueueWrite {
            synchronized(sessionLifecycleLock) {
                if (store.currentSessionId() != null) return@synchronized
                val myPid = android.os.Process.myPid()
                val env = collectEnvMetadata(appContext)
                val isWarmRestart = hasStartedSessionInProcess
                val startedAtMs = System.currentTimeMillis()
                store.startSession(pid = myPid, env = env)
                hasStartedSessionInProcess = true

                val reconciled = pendingReconciledNotes
                if (reconciled.isNotEmpty()) {
                    pendingReconciledNotes = emptyList()
                    for (prev in reconciled) {
                        val lvl = if (prev.status == SessionLogStore.SessionStatus.ENDED_ABNORMALLY) {
                            SessionLogStore.EventLevel.WARN
                        } else {
                            SessionLogStore.EventLevel.INFO
                        }
                        store.recordEvent(
                            lvl,
                            "session.reconcile",
                            "Previous session ${prev.sessionId} (pid=${prev.pid}) finalized as ${prev.status}/${prev.exitCategory}: ${prev.exitSummary}",
                        )
                    }
                }
                if (isWarmRestart) {
                    store.recordEvent(
                        SessionLogStore.EventLevel.INFO,
                        "session",
                        "Started new session in warm process (trigger=$trigger)",
                    )
                }
                startLogcatCollector(myPid, sinceMs = if (isWarmRestart) startedAtMs else null)
                startPeriodicMetricsLoop()
                scope.launch {
                    captureMetricSnapshot(
                        if (isWarmRestart) "session_restart_$trigger" else "session_start",
                        includePss = true,
                    )
                }
            }
        }
    }

    fun info(source: String, message: String) {
        enqueueWrite { store.recordEvent(SessionLogStore.EventLevel.INFO, source, message) }
    }

    fun warn(source: String, message: String) {
        enqueueWrite { store.recordEvent(SessionLogStore.EventLevel.WARN, source, message) }
    }

    fun error(source: String, message: String) {
        enqueueWrite { store.recordEvent(SessionLogStore.EventLevel.ERROR, source, message) }
    }

    fun recordWebViewConsole(
        viewTag: String,
        levelName: String,
        message: String,
        sourceId: String?,
        lineNumber: Int,
    ) {
        val lvl = when (levelName.uppercase()) {
            "ERROR" -> SessionLogStore.EventLevel.ERROR
            "WARNING", "WARN" -> SessionLogStore.EventLevel.WARN
            else -> SessionLogStore.EventLevel.INFO
        }
        val loc = if (!sourceId.isNullOrBlank()) {
            " (${LogRedactor.redact(sourceId)}:$lineNumber)"
        } else {
            ""
        }
        val formatted = "$message$loc"
        enqueueWrite { store.recordEvent(lvl, "webview.console.$viewTag", formatted) }
    }

    fun recordExternalBrowserLaunch(
        rawUri: String,
        source: String,
        launched: Boolean,
        detail: String = "",
    ) {
        val safeUri = LogRedactor.redact(rawUri)
        val suffix = if (detail.isNotBlank()) " ($detail)" else ""
        enqueueWrite {
            if (launched) {
                store.updateSessionState(incrementExternalUrl = true)
                store.recordEvent(
                    SessionLogStore.EventLevel.INFO,
                    "android.intent",
                    "Opened external browser via $source -> $safeUri$suffix",
                )
            } else {
                store.recordEvent(
                    SessionLogStore.EventLevel.ERROR,
                    "android.intent",
                    "Failed to open external browser via $source -> $safeUri$suffix",
                )
            }
        }
    }

    fun updateRuntimeSnapshot(
        snapshot: PosixRuntimeManager.StatusSnapshot,
        adminPort: Int = 0,
        disableSeccomp: Boolean = false,
    ) {
        enqueueWrite {
            store.updateSessionState(
                runtimeState = snapshot.state.name,
                editorPort = if (snapshot.port > 0) snapshot.port else null,
                adminPort = if (adminPort > 0) adminPort else null,
                disableSeccomp = disableSeccomp,
                hasAllFilesAccess = snapshot.hasAllFilesAccess,
                notificationsEnabled = queryNotificationsEnabled(appContext),
                batteryOptIgnored = queryBatteryOptIgnored(appContext),
            )
            if (snapshot.state == PosixRuntimeManager.State.READY) {
                store.refreshLatestAiReport(vscodeDataDir)
            }
        }
    }

    fun updateWindowState(
        activeView: String? = null,
        hasFocus: Boolean? = null,
    ) {
        if (activeView != null) lastActiveView = activeView
        if (hasFocus != null) lastWindowFocus = hasFocus
        enqueueWrite {
            store.updateSessionState(
                activeView = activeView,
                windowFocus = hasFocus,
                hasAllFilesAccess = Environment.isExternalStorageManager(),
                notificationsEnabled = queryNotificationsEnabled(appContext),
                batteryOptIgnored = queryBatteryOptIgnored(appContext),
            )
        }
    }

    fun captureMetricSnapshotAsync(trigger: String, includePss: Boolean = false) {
        scope.launch {
            captureMetricSnapshot(trigger, includePss)
        }
    }

    internal fun captureMetricSnapshot(trigger: String, includePss: Boolean = false): SessionLogStore.MetricSnapshot {
        val snapshot = buildMetricSnapshot(trigger, includePss)
        store.recordMetric(snapshot)
        return snapshot
    }

    fun harvestOpenVsCodeLogsNow() {
        enqueueWrite { store.harvestOpenVsCodeSubLogs(vscodeDataDir) }
    }

    fun endCurrentSession(
        reason: String,
        abnormal: Boolean = false,
        exitCategory: String = if (abnormal) "ABNORMAL_RUNTIME" else "CLEAN",
    ) {
        metricsJob?.cancel()
        metricsJob = null
        stopLogcatCollector()
        enqueueWrite {
            synchronized(sessionLifecycleLock) {
                val finalMetric = runCatching { buildMetricSnapshot("session_end", includePss = true) }.getOrNull()
                store.endSession(
                    reason = reason,
                    abnormal = abnormal,
                    exitCategory = exitCategory,
                    finalMetric = finalMetric,
                    vscodeDataDir = vscodeDataDir,
                )
            }
        }
    }

    fun listSessionsJson(): String {
        awaitInitialReconcile()
        return store.listSessionsJson()
    }

    internal fun listSessionSummaries(): List<SessionLogStore.SessionSummary> {
        awaitInitialReconcile()
        return store.listSessions()
    }

    fun renderSessionBundle(selector: String = "latest", format: String = "ai"): String {
        awaitInitialReconcile()
        return store.renderSessionBundle(
            selector = selector,
            format = format,
            vscodeDataDir = vscodeDataDir,
        ) ?: "# Session not found: $selector\n"
    }

    fun exportSessionToShared(selector: String = "latest"): File? {
        awaitInitialReconcile()
        val sharedBase = appContext.getExternalFilesDir("shared") ?: File(appContext.filesDir, "shared")
        val exportDir = File(sharedBase, "diagnostics")
        return store.exportSessionBundleToDirectory(
            selector = selector,
            exportDir = exportDir,
            vscodeDataDir = vscodeDataDir,
        )
    }

    private fun installUncaughtExceptionHandlerOnce() {
        if (!crashHandlerInstalled.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                if (handlingCrash.compareAndSet(false, true)) {
                    stopLogcatCollector()
                    val isOom = throwable is OutOfMemoryError || throwable.cause is OutOfMemoryError
                    val emergencyMetric = if (isOom) {
                        null
                    } else {
                        runCatching { buildMetricSnapshot("uncaught_crash", includePss = false) }.getOrNull()
                    }
                    store.recordUncaughtCrash(
                        threadName = thread.name,
                        throwable = throwable,
                        finalMetric = emergencyMetric,
                        vscodeDataDir = if (isOom) null else vscodeDataDir,
                    )
                }
            } catch (_: Throwable) {
                // Never suppress chaining to Android's default crash handler
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
    }

    private fun registerSystemCallbacks() {
        val app = appContext as? Application
        app?.registerComponentCallbacks(object : ComponentCallbacks2 {
            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) {
                val levelName = trimLevelToString(level)
                val evLevel = if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
                ) {
                    SessionLogStore.EventLevel.WARN
                } else {
                    SessionLogStore.EventLevel.INFO
                }
                enqueueWrite { store.recordEvent(evLevel, "android.memory", "onTrimMemory: $levelName ($level)") }
                captureMetricSnapshotAsync("trim_memory_$levelName", includePss = true)
            }

            override fun onConfigurationChanged(newConfig: Configuration) {
                val msg = "Configuration changed: screen=${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp, densityDpi=${newConfig.densityDpi}, uiMode=${newConfig.uiMode}"
                enqueueWrite { store.recordEvent(SessionLogStore.EventLevel.INFO, "android.config", msg) }
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                enqueueWrite { store.recordEvent(SessionLogStore.EventLevel.WARN, "android.memory", "onLowMemory callback triggered") }
                captureMetricSnapshotAsync("low_memory", includePss = true)
            }
        })

        app?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                ensureSessionActive("activity_created")
                info("android.activity", "${activity.javaClass.simpleName}.onCreate (restored=${savedInstanceState != null})")
            }

            override fun onActivityStarted(activity: Activity) {
                info("android.activity", "${activity.javaClass.simpleName}.onStart")
            }

            override fun onActivityResumed(activity: Activity) {
                info("android.activity", "${activity.javaClass.simpleName}.onResume")
            }

            override fun onActivityPaused(activity: Activity) {
                info("android.activity", "${activity.javaClass.simpleName}.onPause")
            }

            override fun onActivityStopped(activity: Activity) {
                info("android.activity", "${activity.javaClass.simpleName}.onStop (isChangingConfigurations=${activity.isChangingConfigurations})")
                captureMetricSnapshotAsync("activity_stopped", includePss = false)
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                info(
                    "android.activity",
                    "${activity.javaClass.simpleName}.onDestroy (isFinishing=${activity.isFinishing}, isChangingConfigurations=${activity.isChangingConfigurations})",
                )
            }
        })

        runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.addThermalStatusListener(appContext.mainExecutor) { status ->
                val name = thermalStatusName(status)
                val lvl = if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
                    SessionLogStore.EventLevel.WARN
                } else {
                    SessionLogStore.EventLevel.INFO
                }
                enqueueWrite { store.recordEvent(lvl, "android.thermal", "Thermal status changed: $name ($status)") }
                if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
                    captureMetricSnapshotAsync("thermal_$name", includePss = false)
                }
            }
        }

        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    enqueueWrite {
                        store.recordEvent(
                            SessionLogStore.EventLevel.INFO,
                            "android.network",
                            "Default network available: ${queryNetworkSummary(appContext)}",
                        )
                    }
                }

                override fun onLost(network: Network) {
                    warn("android.network", "Default network lost")
                }
            })
        }
    }

    private fun startPeriodicMetricsLoop() {
        if (metricsJob?.isActive == true) return
        metricsJob = scope.launch {
            var tick = 0
            while (isActive && store.currentSessionId() != null) {
                delay(PERIODIC_METRICS_INTERVAL_MS)
                tick++
                // Sample PSS and refresh latest-ai-report.md every 3rd tick (~3 min)
                val includePss = (tick % 3 == 0)
                captureMetricSnapshot("periodic_${tick}m", includePss = includePss)
                if (includePss) {
                    runCatching { store.refreshLatestAiReport(vscodeDataDir) }
                }
            }
        }
    }

    private fun startLogcatCollector(myPid: Int, sinceMs: Long? = null) {
        synchronized(logcatLock) {
            if (logcatProcess?.isAlive == true || logcatThread?.isAlive == true) return
            val cmd = mutableListOf("/system/bin/logcat", "-v", "time", "--pid=$myPid")
            if (sinceMs != null) {
                val cutoff = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(sinceMs))
                cmd.add("-T")
                cmd.add(cutoff)
            }
            val thread = Thread({
                var proc: Process? = null
                try {
                    proc = ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .start()
                    synchronized(logcatLock) {
                        if (Thread.currentThread().isInterrupted) {
                            runCatching { proc.destroyForcibly() }
                            return@Thread
                        }
                        logcatProcess = proc
                    }
                    BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                        while (!Thread.currentThread().isInterrupted) {
                            val line = reader.readLine() ?: break
                            val classified = filterAndClassifyLogcatLine(line) ?: continue
                            store.recordEvent(classified.first, "logcat", classified.second)
                        }
                    }
                } catch (_: Exception) {
                    // Process closed or logcat unavailable
                } finally {
                    runCatching { proc?.destroyForcibly() }
                    synchronized(logcatLock) {
                        if (logcatProcess === proc) logcatProcess = null
                        if (logcatThread === Thread.currentThread()) logcatThread = null
                    }
                }
            }, "kucode-logcat-recorder").apply {
                isDaemon = true
            }
            logcatThread = thread
            thread.start()
        }
    }

    private fun stopLogcatCollector() {
        val proc: Process?
        val thread: Thread?
        synchronized(logcatLock) {
            proc = logcatProcess
            thread = logcatThread
            logcatProcess = null
            logcatThread = null
        }
        runCatching { thread?.interrupt() }
        runCatching {
            if (proc != null) {
                proc.destroy()
                if (!proc.waitFor(300, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                }
            }
        }
    }

    private fun buildMetricSnapshot(trigger: String, includePss: Boolean): SessionLogStore.MetricSnapshot {
        val now = System.currentTimeMillis()
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memInfo) }

        val rt = Runtime.getRuntime()
        val jvmUsedMb = (rt.totalMemory() - rt.freeMemory()) / BYTES_PER_MB
        val jvmMaxMb = rt.maxMemory() / BYTES_PER_MB
        val nativeMb = runCatching { Debug.getNativeHeapAllocatedSize() / BYTES_PER_MB }.getOrDefault(-1L)

        val pssMb = if (includePss && am != null) {
            runCatching {
                val pmi = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
                if (pmi != null && pmi.isNotEmpty()) pmi[0].totalPss.toLong() / 1024L else -1L
            }.getOrDefault(-1L)
        } else {
            -1L
        }

        val openFds = runCatching { File("/proc/self/fd").list()?.size ?: -1 }.getOrDefault(-1)
        val threads = runCatching { Thread.activeCount() }.getOrDefault(-1)
        val freeDiskMb = runCatching { appContext.filesDir.usableSpace / BYTES_PER_MB }.getOrDefault(-1L)

        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermal = runCatching { pm?.let { thermalStatusName(it.currentThermalStatus) } ?: "UNKNOWN" }
            .getOrDefault("UNKNOWN")
        val powerSave = runCatching { pm?.isPowerSaveMode ?: false }.getOrDefault(false)

        val (batPct, charging) = queryBatteryStatus(appContext)

        return SessionLogStore.MetricSnapshot(
            timestampMs = now,
            trigger = trigger,
            availMemMb = if (memInfo.totalMem > 0) memInfo.availMem / BYTES_PER_MB else -1L,
            totalMemMb = if (memInfo.totalMem > 0) memInfo.totalMem / BYTES_PER_MB else -1L,
            lowMemory = memInfo.lowMemory,
            pssMb = pssMb,
            hostCpuTimeMs = runCatching { android.os.Process.getElapsedCpuTime() }.getOrDefault(-1L),
            jvmHeapUsedMb = jvmUsedMb,
            jvmHeapMaxMb = jvmMaxMb,
            nativeHeapMb = nativeMb,
            openFds = openFds,
            threadCount = threads,
            freeDiskMb = freeDiskMb,
            thermalStatus = thermal,
            batteryPct = batPct,
            isCharging = charging,
            powerSaveMode = powerSave,
            networkSummary = queryNetworkSummary(appContext),
            runtimeState = runCatching { runtimeStateProvider() }.getOrDefault("UNKNOWN"),
            serverAlive = runCatching { serverAliveProvider() }.getOrDefault(false),
            activeView = lastActiveView,
            windowFocus = lastWindowFocus,
        )
    }

    companion object {
        private const val BYTES_PER_MB = 1024L * 1024L
        private const val PERIODIC_METRICS_INTERVAL_MS = 60_000L
        private const val MAX_EXIT_TRACE_BYTES = 64 * 1024

        @Volatile
        private var instance: SessionTelemetryManager? = null

        fun getInstance(context: Context): SessionTelemetryManager {
            return instance ?: synchronized(this) {
                instance ?: SessionTelemetryManager(context.applicationContext).also { instance = it }
            }
        }

        private val LOGCAT_TIME_REGEX = Regex(
            "^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+([VDIWEFA])/([^(:]+)(?:\\(\\s*\\d+\\))?:\\s*(.*)$",
        )

        private val INTERESTING_INFO_TAGS = listOf(
            "chromium",
            "cr_",
            "AwContents",
            "WebView",
            "Ime",
            "InputMethod",
            "ActivityTaskManager",
            "WindowManager",
            "Choreographer",
        )

        internal fun filterAndClassifyLogcatLine(rawLine: String): Pair<SessionLogStore.EventLevel, String>? {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("--------- beginning of")) return null
            if (trimmed.contains("KuCodeSession")) return null

            val match = LOGCAT_TIME_REGEX.find(trimmed) ?: return null
            val prio = match.groupValues[1]
            val tag = match.groupValues[2].trim()
            val body = match.groupValues[3].trim()
            if (body.isEmpty()) return null

            val level = when (prio) {
                "F", "A" -> SessionLogStore.EventLevel.FATAL
                "E" -> SessionLogStore.EventLevel.ERROR
                "W" -> SessionLogStore.EventLevel.WARN
                "I" -> {
                    if (INTERESTING_INFO_TAGS.any { tag.contains(it, ignoreCase = true) }) {
                        SessionLogStore.EventLevel.INFO
                    } else {
                        return null
                    }
                }
                else -> return null
            }
            return level to "$prio/$tag: $body"
        }

        private fun collectEnvMetadata(context: Context): SessionLogStore.SessionEnvMetadata {
            val pkgInfo = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            val versionName = pkgInfo?.versionName ?: "unknown"
            val versionCode = pkgInfo?.longVersionCode ?: 0L
            val webview = runCatching {
                WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"
            }.getOrDefault("unknown")
            val pageSize = runCatching {
                val bytes = Os.sysconf(OsConstants._SC_PAGESIZE)
                "${bytes}B (${bytes / 1024}KB)"
            }.getOrDefault("unknown")
            val xrSpatial = runCatching {
                context.packageManager.hasSystemFeature("android.software.xr.api.spatial")
            }.getOrDefault(false)

            return SessionLogStore.SessionEnvMetadata(
                appVersion = "$versionName (code=$versionCode, targetSdk=${context.applicationInfo.targetSdkVersion}, git=${context.getString(R.string.kucode_build_sha)})",
                packageName = context.packageName,
                deviceSummary = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
                androidSummary = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                webviewVersion = webview,
                pageSize = pageSize,
                runtimeVersion = PosixRuntimeConfig.RUNTIME_VERSION,
                xrSpatialSupported = xrSpatial,
                hasAllFilesAccess = Environment.isExternalStorageManager(),
                notificationsEnabled = queryNotificationsEnabled(context),
                batteryOptIgnored = queryBatteryOptIgnored(context),
            )
        }

        private fun collectHistoricalExitRecords(context: Context): List<SessionLogStore.OsProcessExitRecord> {
            return runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return emptyList()
                val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
                infos.map { info ->
                    val trace = readPrintableExitTrace(info)
                    SessionLogStore.OsProcessExitRecord(
                        pid = info.pid,
                        timestampMs = info.timestamp,
                        reasonCode = info.reason,
                        reasonName = exitReasonToName(info.reason),
                        status = info.status,
                        importance = info.importance,
                        importanceName = importanceToName(info.importance),
                        pssMb = info.pss / 1024L,
                        rssMb = info.rss / 1024L,
                        description = info.description.orEmpty(),
                        traceSnippet = trace,
                    )
                }
            }.getOrDefault(emptyList())
        }

        private fun readPrintableExitTrace(info: ApplicationExitInfo): String? {
            return runCatching {
                val stream = info.traceInputStream ?: return null
                val baos = ByteArrayOutputStream()
                stream.use { input ->
                    val buf = ByteArray(4096)
                    while (baos.size() < MAX_EXIT_TRACE_BYTES) {
                        val read = input.read(buf, 0, minOf(buf.size, MAX_EXIT_TRACE_BYTES - baos.size()))
                        if (read <= 0) break
                        baos.write(buf, 0, read)
                    }
                }
                val bytes = baos.toByteArray()
                if (bytes.isEmpty()) return null
                val nonPrintable = bytes.count { b ->
                    val c = b.toInt() and 0xFF
                    c < 0x09 || (c in 0x0E..0x1F)
                }
                // Native crash traces on API 31+ can be binary protobuf tombstones; avoid dumping raw binary
                if (nonPrintable * 10 > bytes.size) {
                    "(Binary tombstone protobuf attached by Android OS: ${bytes.size} bytes; reason=${exitReasonToName(info.reason)})"
                } else {
                    String(bytes, Charsets.UTF_8).trim().ifEmpty { null }
                }
            }.getOrNull()
        }

        internal fun exitReasonToName(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "REASON_USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "REASON_DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER"
            ApplicationExitInfo.REASON_FREEZER -> "REASON_FREEZER"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "REASON_PACKAGE_STATE_CHANGE"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "REASON_PACKAGE_UPDATED"
            else -> "REASON_UNKNOWN_$reason"
        }

        private fun importanceToName(importance: Int): String = when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
            else -> "IMPORTANCE_$importance"
        }

        @Suppress("DEPRECATION")
        private fun trimLevelToString(level: Int): String = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "LEVEL_$level"
        }

        private fun thermalStatusName(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "STATUS_$status"
        }

        private fun queryNotificationsEnabled(context: Context): Boolean = runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.areNotificationsEnabled() ?: false
        }.getOrDefault(false)

        private fun queryBatteryOptIgnored(context: Context): Boolean = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        }.getOrDefault(false)

        private fun queryBatteryStatus(context: Context): Pair<Int, Boolean> = runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return -1 to false
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            pct to charging
        }.getOrDefault(-1 to false)

        private fun queryNetworkSummary(context: Context): String = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "unavailable"
            val net = cm.activeNetwork ?: return "offline"
            val caps = cm.getNetworkCapabilities(net) ?: return "no_caps"
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETH"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELL"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "OTHER"
            }
            val validated = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "ok" else "unvalidated"
            val dnsCount = cm.getLinkProperties(net)?.dnsServers?.size ?: 0
            "$transport($validated,dns=$dnsCount)"
        }.getOrDefault("unknown")
    }
}
