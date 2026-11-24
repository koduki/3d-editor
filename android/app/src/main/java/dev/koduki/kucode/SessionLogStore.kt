package dev.koduki.kucode

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure-JVM, crash-safe flight recorder storing all logs, stdout/stderr, WebView console/navigation
 * events, host API calls, metrics snapshots, crash stacktraces, and Android OS [OsProcessExitRecord]
 * outcomes grouped by application lifecycle session (launch -> normal or abnormal termination).
 *
 * Storage invariants per session (`<diagnosticsDir>/sessions/<sessionId>/`):
 * - `meta.json`: atomic session metadata & live counters
 * - `events.head.jsonl`: first [MAX_HEAD_EVENTS] events (preserves boot & setup sequence forever)
 * - `events.tail.jsonl` (+ `.prev`): bounded append-only tail stream (preserves pre-exit context)
 * - `events.alerts.jsonl`: bounded stream of all WARN/ERROR/FATAL events across the entire session
 * - `metrics.jsonl`: periodic and event-triggered resource & window/thermal/network snapshots
 * - `vscode-sublogs.txt`: harvested tails from openvscode-server `ptyhost.log` / `exthost.log` / `remoteagent.log`
 * - `exit-trace.txt`: uncaught JVM stacktrace and/or OS ANR/tombstone trace from `ApplicationExitInfo`
 */
internal class SessionLogStore(
    private val diagnosticsRootDir: File,
    private val maxStoredSessions: Int = DEFAULT_MAX_SESSIONS,
    private val maxHeadEvents: Int = DEFAULT_MAX_HEAD_EVENTS,
    private val maxTailBytes: Long = DEFAULT_MAX_TAIL_BYTES,
    private val maxAlertEvents: Int = DEFAULT_MAX_ALERT_EVENTS,
    private val maxMetricsSnapshots: Int = DEFAULT_MAX_METRICS_SNAPSHOTS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    enum class SessionStatus {
        RUNNING,
        ENDED_CLEANLY,
        ENDED_ABNORMALLY,
    }

    enum class EventLevel {
        INFO,
        WARN,
        ERROR,
        FATAL,
    }

    data class SessionEnvMetadata(
        val appVersion: String = "unknown",
        val packageName: String = "dev.koduki.kucode",
        val deviceSummary: String = "unknown",
        val androidSummary: String = "unknown",
        val webviewVersion: String = "unknown",
        val pageSize: String = "unknown",
        val runtimeVersion: String = PosixRuntimeConfig.RUNTIME_VERSION,
        val xrSpatialSupported: Boolean = false,
        val hasAllFilesAccess: Boolean = false,
        val notificationsEnabled: Boolean = false,
        val batteryOptIgnored: Boolean = false,
    )

    data class MetricSnapshot(
        val timestampMs: Long,
        val offsetMs: Long = 0L,
        val trigger: String,
        val availMemMb: Long = -1L,
        val totalMemMb: Long = -1L,
        val lowMemory: Boolean = false,
        val pssMb: Long = -1L,
        val jvmHeapUsedMb: Long = -1L,
        val jvmHeapMaxMb: Long = -1L,
        val nativeHeapMb: Long = -1L,
        val openFds: Int = -1,
        val threadCount: Int = -1,
        val freeDiskMb: Long = -1L,
        val thermalStatus: String = "UNKNOWN",
        val batteryPct: Int = -1,
        val isCharging: Boolean = false,
        val powerSaveMode: Boolean = false,
        val networkSummary: String = "unknown",
        val runtimeState: String = "IDLE",
        val serverAlive: Boolean = false,
        val activeView: String = "BOOT",
        val windowFocus: Boolean = true,
    ) {
        fun toJsonObject(computedOffsetMs: Long = offsetMs): JSONObject = JSONObject()
            .put("tsMs", timestampMs)
            .put("offsetMs", computedOffsetMs)
            .put("trigger", trigger)
            .put("availMemMb", availMemMb)
            .put("totalMemMb", totalMemMb)
            .put("lowMemory", lowMemory)
            .put("pssMb", pssMb)
            .put("jvmHeapUsedMb", jvmHeapUsedMb)
            .put("jvmHeapMaxMb", jvmHeapMaxMb)
            .put("nativeHeapMb", nativeHeapMb)
            .put("openFds", openFds)
            .put("threadCount", threadCount)
            .put("freeDiskMb", freeDiskMb)
            .put("thermalStatus", thermalStatus)
            .put("batteryPct", batteryPct)
            .put("isCharging", isCharging)
            .put("powerSaveMode", powerSaveMode)
            .put("networkSummary", networkSummary)
            .put("runtimeState", runtimeState)
            .put("serverAlive", serverAlive)
            .put("activeView", activeView)
            .put("windowFocus", windowFocus)

        companion object {
            fun fromJsonObject(obj: JSONObject): MetricSnapshot = MetricSnapshot(
                timestampMs = obj.optLong("tsMs", 0L),
                offsetMs = obj.optLong("offsetMs", 0L),
                trigger = obj.optString("trigger", "sample"),
                availMemMb = obj.optLong("availMemMb", -1L),
                totalMemMb = obj.optLong("totalMemMb", -1L),
                lowMemory = obj.optBoolean("lowMemory", false),
                pssMb = obj.optLong("pssMb", -1L),
                jvmHeapUsedMb = obj.optLong("jvmHeapUsedMb", -1L),
                jvmHeapMaxMb = obj.optLong("jvmHeapMaxMb", -1L),
                nativeHeapMb = obj.optLong("nativeHeapMb", -1L),
                openFds = obj.optInt("openFds", -1),
                threadCount = obj.optInt("threadCount", -1),
                freeDiskMb = obj.optInt("freeDiskMb", -1).toLong().let {
                    if (obj.has("freeDiskMb")) obj.optLong("freeDiskMb", -1L) else it
                },
                thermalStatus = obj.optString("thermalStatus", "UNKNOWN"),
                batteryPct = obj.optInt("batteryPct", -1),
                isCharging = obj.optBoolean("isCharging", false),
                powerSaveMode = obj.optBoolean("powerSaveMode", false),
                networkSummary = obj.optString("networkSummary", "unknown"),
                runtimeState = obj.optString("runtimeState", "IDLE"),
                serverAlive = obj.optBoolean("serverAlive", false),
                activeView = obj.optString("activeView", "BOOT"),
                windowFocus = obj.optBoolean("windowFocus", true),
            )
        }
    }

    data class OsProcessExitRecord(
        val pid: Int,
        val timestampMs: Long,
        val reasonCode: Int,
        val reasonName: String,
        val status: Int,
        val importance: Int,
        val importanceName: String,
        val pssMb: Long,
        val rssMb: Long,
        val description: String = "",
        val traceSnippet: String? = null,
    )

    data class RecordedEvent(
        val seq: Int,
        val timestampMs: Long,
        val offsetMs: Long,
        val level: EventLevel,
        val source: String,
        val message: String,
        val benignNote: String? = null,
    ) {
        fun toJsonObject(): JSONObject {
            val obj = JSONObject()
                .put("seq", seq)
                .put("tsMs", timestampMs)
                .put("offsetMs", offsetMs)
                .put("level", level.name)
                .put("source", source)
                .put("msg", message)
            if (!benignNote.isNullOrEmpty()) {
                obj.put("benign", benignNote)
            }
            return obj
        }

        companion object {
            fun fromJsonObject(obj: JSONObject): RecordedEvent = RecordedEvent(
                seq = obj.optInt("seq", 0),
                timestampMs = obj.optLong("tsMs", 0L),
                offsetMs = obj.optLong("offsetMs", 0L),
                level = runCatching { EventLevel.valueOf(obj.optString("level", "INFO")) }.getOrDefault(EventLevel.INFO),
                source = obj.optString("source", "general"),
                message = obj.optString("msg", ""),
                benignNote = obj.optString("benign", "").ifEmpty { null },
            )
        }
    }

    data class SessionSummary(
        val sessionId: String,
        val pid: Int,
        val status: SessionStatus,
        val exitCategory: String,
        val exitSummary: String,
        val startedAtMs: Long,
        val updatedAtMs: Long,
        val endedAtMs: Long?,
        val appVersion: String,
        val packageName: String,
        val deviceSummary: String,
        val androidSummary: String,
        val webviewVersion: String,
        val pageSize: String,
        val runtimeVersion: String,
        val lastRuntimeState: String,
        val editorPort: Int,
        val adminPort: Int,
        val disableSeccomp: Boolean,
        val hasAllFilesAccess: Boolean,
        val notificationsEnabled: Boolean,
        val batteryOptIgnored: Boolean,
        val xrSpatialSupported: Boolean,
        val lastActiveView: String,
        val lastWindowFocus: Boolean,
        val totalEvents: Int,
        val errorCount: Int,
        val warnCount: Int,
        val benignNoiseCount: Int,
        val externalUrlCount: Int,
        val isCurrentSession: Boolean = false,
    ) {
        val durationMs: Long
            get() = ((endedAtMs ?: updatedAtMs) - startedAtMs).coerceAtLeast(0L)

        fun toJsonObject(): JSONObject = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("sessionId", sessionId)
            .put("pid", pid)
            .put("status", status.name)
            .put("exitCategory", exitCategory)
            .put("exitSummary", exitSummary)
            .put("startedAtMs", startedAtMs)
            .put("startedAtUtc", formatUtcIso(startedAtMs))
            .put("updatedAtMs", updatedAtMs)
            .put("updatedAtUtc", formatUtcIso(updatedAtMs))
            .put("endedAtMs", endedAtMs ?: JSONObject.NULL)
            .put("endedAtUtc", endedAtMs?.let(::formatUtcIso) ?: JSONObject.NULL)
            .put("durationMs", durationMs)
            .put("durationHuman", formatDurationHuman(durationMs))
            .put("appVersion", appVersion)
            .put("packageName", packageName)
            .put("deviceSummary", deviceSummary)
            .put("androidSummary", androidSummary)
            .put("webviewVersion", webviewVersion)
            .put("pageSize", pageSize)
            .put("runtimeVersion", runtimeVersion)
            .put("lastRuntimeState", lastRuntimeState)
            .put("editorPort", editorPort)
            .put("adminPort", adminPort)
            .put("disableSeccomp", disableSeccomp)
            .put("hasAllFilesAccess", hasAllFilesAccess)
            .put("notificationsEnabled", notificationsEnabled)
            .put("batteryOptIgnored", batteryOptIgnored)
            .put("xrSpatialSupported", xrSpatialSupported)
            .put("lastActiveView", lastActiveView)
            .put("lastWindowFocus", lastWindowFocus)
            .put("totalEvents", totalEvents)
            .put("errorCount", errorCount)
            .put("warnCount", warnCount)
            .put("benignNoiseCount", benignNoiseCount)
            .put("externalUrlCount", externalUrlCount)
            .put("isCurrentSession", isCurrentSession)

        companion object {
            fun fromJsonObject(obj: JSONObject, isCurrent: Boolean = false): SessionSummary = SessionSummary(
                sessionId = obj.optString("sessionId", "unknown"),
                pid = obj.optInt("pid", 0),
                status = runCatching { SessionStatus.valueOf(obj.optString("status", "RUNNING")) }
                    .getOrDefault(SessionStatus.RUNNING),
                exitCategory = obj.optString("exitCategory", "RUNNING"),
                exitSummary = obj.optString("exitSummary", ""),
                startedAtMs = obj.optLong("startedAtMs", 0L),
                updatedAtMs = obj.optLong("updatedAtMs", 0L),
                endedAtMs = if (obj.isNull("endedAtMs") || !obj.has("endedAtMs")) null else obj.optLong("endedAtMs"),
                appVersion = obj.optString("appVersion", "unknown"),
                packageName = obj.optString("packageName", "dev.koduki.kucode"),
                deviceSummary = obj.optString("deviceSummary", "unknown"),
                androidSummary = obj.optString("androidSummary", "unknown"),
                webviewVersion = obj.optString("webviewVersion", "unknown"),
                pageSize = obj.optString("pageSize", "unknown"),
                runtimeVersion = obj.optString("runtimeVersion", PosixRuntimeConfig.RUNTIME_VERSION),
                lastRuntimeState = obj.optString("lastRuntimeState", "IDLE"),
                editorPort = obj.optInt("editorPort", 0),
                adminPort = obj.optInt("adminPort", 0),
                disableSeccomp = obj.optBoolean("disableSeccomp", false),
                hasAllFilesAccess = obj.optBoolean("hasAllFilesAccess", false),
                notificationsEnabled = obj.optBoolean("notificationsEnabled", false),
                batteryOptIgnored = obj.optBoolean("batteryOptIgnored", false),
                xrSpatialSupported = obj.optBoolean("xrSpatialSupported", false),
                lastActiveView = obj.optString("lastActiveView", "BOOT"),
                lastWindowFocus = obj.optBoolean("lastWindowFocus", true),
                totalEvents = obj.optInt("totalEvents", 0),
                errorCount = obj.optInt("errorCount", 0),
                warnCount = obj.optInt("warnCount", 0),
                benignNoiseCount = obj.optInt("benignNoiseCount", 0),
                externalUrlCount = obj.optInt("externalUrlCount", 0),
                isCurrentSession = isCurrent,
            )
        }
    }

    private class ActiveSessionHandle(
        var summary: SessionSummary,
        val dir: File,
        var headStream: BufferedOutputStream?,
        var tailStream: BufferedOutputStream?,
        var alertsStream: BufferedOutputStream?,
        var metricsStream: BufferedOutputStream?,
        var tailBytesWritten: Long = 0L,
        var alertCountWritten: Int = 0,
        var metricsCountWritten: Int = 0,
        var lastMetaPersistMs: Long = 0L,
    ) {
        fun closeStreams() {
            runCatching { headStream?.flush(); headStream?.close() }
            runCatching { tailStream?.flush(); tailStream?.close() }
            runCatching { alertsStream?.flush(); alertsStream?.close() }
            runCatching { metricsStream?.flush(); metricsStream?.close() }
            headStream = null
            tailStream = null
            alertsStream = null
            metricsStream = null
        }
    }

    private val lock = Any()
    private val sessionsRootDir: File get() = File(diagnosticsRootDir, "sessions")
    private var active: ActiveSessionHandle? = null

    val latestAiReportFile: File get() = File(diagnosticsRootDir, "latest-ai-report.md")
    val previousAbnormalReportFile: File get() = File(diagnosticsRootDir, "previous-abnormal-ai-report.md")

    fun currentSessionId(): String? = synchronized(lock) { active?.summary?.sessionId }

    fun currentSummary(): SessionSummary? = synchronized(lock) { active?.summary?.copy(isCurrentSession = true) }

    /**
     * Reconciles any sessions left in `RUNNING` state from prior OS process lifetimes using
     * Android's historical `ApplicationExitInfo` records (when available).
     * Returns the list of newly reconciled session summaries.
     */
    fun reconcileUnfinishedSessions(
        currentPid: Int,
        exitRecords: List<OsProcessExitRecord> = emptyList(),
    ): List<SessionSummary> = synchronized(lock) {
        ensureRootDirectories()
        val reconciled = mutableListOf<SessionSummary>()
        val dirs = sessionsRootDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()

        for (dir in dirs) {
            if (active?.dir == dir) continue
            val metaFile = File(dir, FILE_META)
            if (!metaFile.isFile) continue
            val rawSummary = runCatching {
                SessionSummary.fromJsonObject(JSONObject(metaFile.readText(Charsets.UTF_8)))
            }.getOrNull() ?: continue

            if (rawSummary.status != SessionStatus.RUNNING) continue
            if (rawSummary.pid == currentPid && active != null) continue

            // Recompute exact event statistics from flushed JSONL files on disk in case meta.json lagged
            val recoveredCounts = recomputeCountsFromDisk(dir, rawSummary)

            // Match OS exit record by PID (picking closest timestamp >= startedAtMs if PID reused)
            val matchedExit = exitRecords
                .filter { it.pid > 0 && it.pid == rawSummary.pid && it.timestampMs >= rawSummary.startedAtMs - 5_000L }
                .minByOrNull { kotlin.math.abs(it.timestampMs - recoveredCounts.updatedAtMs) }

            val (finalStatus, category, exitSummary, endMs) = classifyReconciledExit(recoveredCounts, matchedExit)

            if (matchedExit != null) {
                appendReconciliationEventToDisk(
                    dir = dir,
                    startedAtMs = recoveredCounts.startedAtMs,
                    seq = recoveredCounts.totalEvents + 1,
                    timestampMs = endMs,
                    level = if (finalStatus == SessionStatus.ENDED_ABNORMALLY) EventLevel.ERROR else EventLevel.INFO,
                    source = "os.exit_info",
                    message = "Reconciled process exit: reason=${matchedExit.reasonName}(${matchedExit.reasonCode}), " +
                        "status=${matchedExit.status}, importance=${matchedExit.importanceName}(${matchedExit.importance}), " +
                        "pss=${matchedExit.pssMb}MB, rss=${matchedExit.rssMb}MB, desc=${matchedExit.description.ifBlank { "none" }}",
                )
                if (!matchedExit.traceSnippet.isNullOrBlank()) {
                    appendTraceToDisk(dir, "=== Android ApplicationExitInfo Trace (${matchedExit.reasonName}) ===\n${matchedExit.traceSnippet}")
                }
            } else {
                appendReconciliationEventToDisk(
                    dir = dir,
                    startedAtMs = recoveredCounts.startedAtMs,
                    seq = recoveredCounts.totalEvents + 1,
                    timestampMs = endMs,
                    level = EventLevel.WARN,
                    source = "os.exit_info",
                    message = "Previous session did not record a clean exit and no matching OS ApplicationExitInfo was found (possible sudden kill, power loss, or OS eviction).",
                )
            }

            val updatedSummary = recoveredCounts.copy(
                status = finalStatus,
                exitCategory = category,
                exitSummary = exitSummary,
                updatedAtMs = endMs,
                endedAtMs = endMs,
                totalEvents = recoveredCounts.totalEvents + 1,
                errorCount = recoveredCounts.errorCount + if (finalStatus == SessionStatus.ENDED_ABNORMALLY) 1 else 0,
                warnCount = recoveredCounts.warnCount + if (matchedExit == null && finalStatus != SessionStatus.ENDED_ABNORMALLY) 1 else 0,
                isCurrentSession = false,
            )
            writeSummaryAtomically(dir, updatedSummary)
            reconciled.add(updatedSummary)

            if (finalStatus == SessionStatus.ENDED_ABNORMALLY) {
                runCatching {
                    val report = buildAiMarkdownReportInternal(dir, updatedSummary)
                    writeTextAtomically(previousAbnormalReportFile, report, Charsets.UTF_8, worldReadable = true)
                }
            }
        }
        pruneOldSessionsLocked()
        return reconciled
    }

    fun startSession(
        pid: Int,
        env: SessionEnvMetadata,
    ): SessionSummary = synchronized(lock) {
        active?.let { existing ->
            if (existing.summary.status == SessionStatus.RUNNING) {
                return existing.summary.copy(isCurrentSession = true)
            }
            existing.closeStreams()
            active = null
        }

        ensureRootDirectories()
        val now = clockMs()
        val sessionId = generateSessionId(now)
        val dir = File(sessionsRootDir, sessionId)
        ensureDirectoryWithMode(dir, 0b111_101_101)

        val summary = SessionSummary(
            sessionId = sessionId,
            pid = pid,
            status = SessionStatus.RUNNING,
            exitCategory = "RUNNING",
            exitSummary = "active",
            startedAtMs = now,
            updatedAtMs = now,
            endedAtMs = null,
            appVersion = env.appVersion,
            packageName = env.packageName,
            deviceSummary = env.deviceSummary,
            androidSummary = env.androidSummary,
            webviewVersion = env.webviewVersion,
            pageSize = env.pageSize,
            runtimeVersion = env.runtimeVersion,
            lastRuntimeState = "IDLE",
            editorPort = 0,
            adminPort = 0,
            disableSeccomp = false,
            hasAllFilesAccess = env.hasAllFilesAccess,
            notificationsEnabled = env.notificationsEnabled,
            batteryOptIgnored = env.batteryOptIgnored,
            xrSpatialSupported = env.xrSpatialSupported,
            lastActiveView = "BOOT",
            lastWindowFocus = true,
            totalEvents = 0,
            errorCount = 0,
            warnCount = 0,
            benignNoiseCount = 0,
            externalUrlCount = 0,
            isCurrentSession = true,
        )

        val handle = ActiveSessionHandle(
            summary = summary,
            dir = dir,
            headStream = BufferedOutputStream(FileOutputStream(File(dir, FILE_EVENTS_HEAD), true)),
            tailStream = BufferedOutputStream(FileOutputStream(File(dir, FILE_EVENTS_TAIL), true)),
            alertsStream = BufferedOutputStream(FileOutputStream(File(dir, FILE_EVENTS_ALERTS), true)),
            metricsStream = BufferedOutputStream(FileOutputStream(File(dir, FILE_METRICS), true)),
            lastMetaPersistMs = now,
        )
        active = handle
        writeSummaryAtomically(dir, summary)
        pruneOldSessionsLocked()

        recordEventLocked(
            handle = handle,
            level = EventLevel.INFO,
            source = "session",
            rawMessage = "Session started (id=$sessionId, pid=$pid, app=${env.packageName} ${env.appVersion}, device=${env.deviceSummary}, webview=${env.webviewVersion})",
            forceMetaPersist = true,
        )
        if (!env.notificationsEnabled || !env.batteryOptIgnored) {
            recordEventLocked(
                handle = handle,
                level = EventLevel.WARN,
                source = "android.perms",
                rawMessage = "Background resilience notice: notificationsEnabled=${env.notificationsEnabled}, batteryOptIgnored=${env.batteryOptIgnored}",
                forceMetaPersist = true,
            )
        }
        refreshLatestAiReportLocked()
        return handle.summary.copy(isCurrentSession = true)
    }

    fun recordEvent(
        level: EventLevel,
        source: String,
        message: String,
    ) = synchronized(lock) {
        val handle = active ?: return
        if (handle.summary.status != SessionStatus.RUNNING) return
        recordEventLocked(handle, level, source, message, forceMetaPersist = false)
    }

    fun updateSessionState(
        runtimeState: String? = null,
        editorPort: Int? = null,
        adminPort: Int? = null,
        disableSeccomp: Boolean? = null,
        hasAllFilesAccess: Boolean? = null,
        notificationsEnabled: Boolean? = null,
        batteryOptIgnored: Boolean? = null,
        activeView: String? = null,
        windowFocus: Boolean? = null,
        incrementExternalUrl: Boolean = false,
    ) = synchronized(lock) {
        val handle = active ?: return
        if (handle.summary.status != SessionStatus.RUNNING) return
        val now = clockMs()
        val cur = handle.summary
        val updated = cur.copy(
            updatedAtMs = now,
            lastRuntimeState = runtimeState ?: cur.lastRuntimeState,
            editorPort = editorPort ?: cur.editorPort,
            adminPort = adminPort ?: cur.adminPort,
            disableSeccomp = disableSeccomp ?: cur.disableSeccomp,
            hasAllFilesAccess = hasAllFilesAccess ?: cur.hasAllFilesAccess,
            notificationsEnabled = notificationsEnabled ?: cur.notificationsEnabled,
            batteryOptIgnored = batteryOptIgnored ?: cur.batteryOptIgnored,
            lastActiveView = activeView ?: cur.lastActiveView,
            lastWindowFocus = windowFocus ?: cur.lastWindowFocus,
            externalUrlCount = cur.externalUrlCount + if (incrementExternalUrl) 1 else 0,
        )
        handle.summary = updated
        handle.lastMetaPersistMs = now
        writeSummaryAtomically(handle.dir, updated)
    }

    fun recordMetric(snapshot: MetricSnapshot) = synchronized(lock) {
        val handle = active ?: return
        if (handle.summary.status != SessionStatus.RUNNING) return
        if (handle.metricsCountWritten >= maxMetricsSnapshots) return

        val offset = (snapshot.timestampMs - handle.summary.startedAtMs).coerceAtLeast(0L)
        val line = (snapshot.toJsonObject(offset).toString() + "\n").toByteArray(Charsets.UTF_8)
        runCatching {
            handle.metricsStream?.write(line)
            handle.metricsStream?.flush()
            handle.metricsCountWritten++
        }
        val now = clockMs()
        if (now - handle.lastMetaPersistMs >= META_FLUSH_INTERVAL_MS) {
            handle.summary = handle.summary.copy(updatedAtMs = now)
            handle.lastMetaPersistMs = now
            writeSummaryAtomically(handle.dir, handle.summary)
        }
    }

    /**
     * Harvests bounded tails of openvscode-server's internal file logs (`remoteagent.log`,
     * `ptyhost.log`, `exthost.log`) into the session directory so Extension Host and PTY issues
     * that never reach stdout are preserved for AI debugging.
     */
    fun harvestOpenVsCodeSubLogs(vscodeDataDir: File, targetSessionId: String? = null) = synchronized(lock) {
        val dir = when {
            targetSessionId != null -> resolveSafeSessionDir(targetSessionId) ?: return
            active != null -> active!!.dir
            else -> return
        }
        val harvested = extractOpenVsCodeSubLogs(vscodeDataDir)
        if (harvested.isNotBlank()) {
            writeTextAtomically(File(dir, FILE_VSCODE_SUBLOGS), harvested, Charsets.UTF_8, worldReadable = true)
        }
    }

    /**
     * Records an uncaught JVM exception synchronously and marks the active session as
     * [SessionStatus.ENDED_ABNORMALLY] before process death.
     */
    fun recordUncaughtCrash(
        threadName: String,
        throwable: Throwable,
        finalMetric: MetricSnapshot? = null,
        vscodeDataDir: File? = null,
    ) = synchronized(lock) {
        val handle = active ?: return
        if (finalMetric != null) {
            runCatching { recordMetric(finalMetric) }
        }
        val stack = LogRedactor.redact(throwable.stackTraceToString())
        val headline = "${throwable.javaClass.name}: ${LogRedactor.redact(throwable.message.orEmpty())}".trimEnd(':', ' ')
        recordEventLocked(
            handle = handle,
            level = EventLevel.FATAL,
            source = "crash.jvm",
            rawMessage = "Uncaught exception on thread '$threadName': $headline",
            forceMetaPersist = false,
        )
        appendTraceToDisk(handle.dir, "=== Uncaught JVM Exception (thread=$threadName) ===\n$stack")
        if (vscodeDataDir != null) {
            runCatching {
                val sub = extractOpenVsCodeSubLogs(vscodeDataDir)
                if (sub.isNotBlank()) {
                    writeTextAtomically(File(handle.dir, FILE_VSCODE_SUBLOGS), sub, Charsets.UTF_8, worldReadable = true)
                }
            }
        }
        finalizeSessionLocked(
            handle = handle,
            status = SessionStatus.ENDED_ABNORMALLY,
            exitCategory = "CRASH_JVM",
            exitSummary = "Uncaught exception on $threadName: $headline".take(240),
        )
    }

    /**
     * Finalizes the active session cleanly (or abnormally when e.g. FGS quota times out while active).
     */
    fun endSession(
        reason: String,
        abnormal: Boolean = false,
        exitCategory: String = if (abnormal) "ABNORMAL_RUNTIME" else "CLEAN",
        finalMetric: MetricSnapshot? = null,
        vscodeDataDir: File? = null,
    ) = synchronized(lock) {
        val handle = active ?: return
        if (handle.summary.status != SessionStatus.RUNNING) return
        if (finalMetric != null) {
            runCatching { recordMetric(finalMetric) }
        }
        if (vscodeDataDir != null) {
            runCatching {
                val sub = extractOpenVsCodeSubLogs(vscodeDataDir)
                if (sub.isNotBlank()) {
                    writeTextAtomically(File(handle.dir, FILE_VSCODE_SUBLOGS), sub, Charsets.UTF_8, worldReadable = true)
                }
            }
        }
        recordEventLocked(
            handle = handle,
            level = if (abnormal) EventLevel.ERROR else EventLevel.INFO,
            source = "session",
            rawMessage = "Session ending ($exitCategory): $reason",
            forceMetaPersist = false,
        )
        finalizeSessionLocked(
            handle = handle,
            status = if (abnormal) SessionStatus.ENDED_ABNORMALLY else SessionStatus.ENDED_CLEANLY,
            exitCategory = exitCategory,
            exitSummary = LogRedactor.redact(reason).take(240),
        )
    }

    fun listSessions(): List<SessionSummary> = synchronized(lock) {
        ensureRootDirectories()
        val activeId = active?.summary?.sessionId
        val result = mutableListOf<SessionSummary>()
        val dirs = sessionsRootDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (dir in dirs) {
            if (dir.name == activeId && active != null) {
                result.add(active!!.summary.copy(isCurrentSession = true))
                continue
            }
            val metaFile = File(dir, FILE_META)
            if (!metaFile.isFile) continue
            val parsed = runCatching {
                SessionSummary.fromJsonObject(JSONObject(metaFile.readText(Charsets.UTF_8)), isCurrent = false)
            }.getOrNull() ?: continue
            result.add(parsed)
        }
        result.sortWith(
            compareByDescending<SessionSummary> { it.isCurrentSession }
                .thenByDescending { it.startedAtMs }
                .thenByDescending { it.updatedAtMs }
                .thenByDescending { it.sessionId },
        )
        return result
    }

    fun listSessionsJson(): String {
        val sessions = listSessions()
        val arr = JSONArray()
        for (s in sessions) {
            arr.put(s.toJsonObject())
        }
        val prevAbnormal = sessions.firstOrNull { !it.isCurrentSession && it.status == SessionStatus.ENDED_ABNORMALLY }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("currentSessionId", currentSessionId() ?: JSONObject.NULL)
            .put("previousAbnormalSessionId", prevAbnormal?.sessionId ?: JSONObject.NULL)
            .put("sessions", arr)
            .toString(2)
    }

    /**
     * Resolves special aliases (`latest`, `current`, `prev`, `prev-abnormal`) or an exact `sessionId`.
     */
    fun resolveSessionSummary(selector: String): SessionSummary? = synchronized(lock) {
        val clean = selector.trim()
        val sessions = listSessions()
        if (sessions.isEmpty()) return null
        return when (clean.lowercase(Locale.US)) {
            "", "latest", "current" -> sessions.first()
            "prev", "previous" -> sessions.firstOrNull { !it.isCurrentSession } ?: sessions.first()
            "prev-abnormal", "abnormal" -> sessions.firstOrNull {
                !it.isCurrentSession && it.status == SessionStatus.ENDED_ABNORMALLY
            }
            else -> sessions.firstOrNull { it.sessionId == clean }
        }
    }

    fun renderSessionBundle(
        selector: String = "latest",
        format: String = "ai",
        vscodeDataDir: File? = null,
    ): String? = synchronized(lock) {
        val target = resolveSessionSummary(selector) ?: return null
        val dir = resolveSafeSessionDir(target.sessionId) ?: return null
        if (target.isCurrentSession && vscodeDataDir != null) {
            runCatching {
                val sub = extractOpenVsCodeSubLogs(vscodeDataDir)
                if (sub.isNotBlank()) {
                    writeTextAtomically(File(dir, FILE_VSCODE_SUBLOGS), sub, Charsets.UTF_8, worldReadable = true)
                }
            }
            active?.let { writeSummaryAtomically(it.dir, it.summary) }
        }
        return when (format.lowercase(Locale.US)) {
            "raw", "text", "timeline" -> buildRawTimelineReportInternal(dir, target)
            "json" -> buildJsonBundleInternal(dir, target)
            else -> {
                val md = buildAiMarkdownReportInternal(dir, target)
                if (target.isCurrentSession) {
                    runCatching { writeTextAtomically(latestAiReportFile, md, Charsets.UTF_8, worldReadable = true) }
                }
                md
            }
        }
    }

    fun exportSessionBundleToDirectory(
        selector: String,
        exportDir: File,
        vscodeDataDir: File? = null,
    ): File? = synchronized(lock) {
        val target = resolveSessionSummary(selector) ?: return null
        val markdown = renderSessionBundle(target.sessionId, format = "ai", vscodeDataDir = vscodeDataDir) ?: return null
        ensureDirectoryWithMode(exportDir, 0b111_101_101)
        val outFile = File(exportDir, "kucode-session-${target.sessionId}-ai.md")
        writeTextAtomically(outFile, markdown, Charsets.UTF_8, worldReadable = true)
        return outFile
    }

    private fun recordEventLocked(
        handle: ActiveSessionHandle,
        level: EventLevel,
        source: String,
        rawMessage: String,
        forceMetaPersist: Boolean,
    ) {
        val now = clockMs()
        val sanitized = LogRedactor.redact(rawMessage)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trimEnd('\n')
            .let { if (it.length > MAX_SINGLE_MESSAGE_CHARS) it.take(MAX_SINGLE_MESSAGE_CHARS) + "...[truncated]" else it }
        if (sanitized.isBlank() && level == EventLevel.INFO) return

        val benignNote = classifyBenignReason(sanitized)
        val effectiveLevel = if (benignNote != null && (level == EventLevel.ERROR || level == EventLevel.WARN)) {
            EventLevel.INFO
        } else {
            level
        }

        val nextSeq = handle.summary.totalEvents + 1
        val offset = (now - handle.summary.startedAtMs).coerceAtLeast(0L)
        val event = RecordedEvent(
            seq = nextSeq,
            timestampMs = now,
            offsetMs = offset,
            level = effectiveLevel,
            source = source.take(40),
            message = sanitized,
            benignNote = benignNote,
        )
        val lineBytes = (event.toJsonObject().toString() + "\n").toByteArray(Charsets.UTF_8)

        runCatching {
            if (nextSeq <= maxHeadEvents) {
                handle.headStream?.write(lineBytes)
                handle.headStream?.flush()
            } else {
                rotateTailIfNeeded(handle, lineBytes.size)
                handle.tailStream?.write(lineBytes)
                handle.tailStream?.flush()
                handle.tailBytesWritten += lineBytes.size
            }

            if ((effectiveLevel == EventLevel.WARN || effectiveLevel == EventLevel.ERROR || effectiveLevel == EventLevel.FATAL) &&
                handle.alertCountWritten < maxAlertEvents
            ) {
                handle.alertsStream?.write(lineBytes)
                handle.alertsStream?.flush()
                handle.alertCountWritten++
            }
        }

        val updated = handle.summary.copy(
            updatedAtMs = now,
            totalEvents = nextSeq,
            errorCount = handle.summary.errorCount + if (effectiveLevel == EventLevel.ERROR || effectiveLevel == EventLevel.FATAL) 1 else 0,
            warnCount = handle.summary.warnCount + if (effectiveLevel == EventLevel.WARN) 1 else 0,
            benignNoiseCount = handle.summary.benignNoiseCount + if (benignNote != null) 1 else 0,
        )
        handle.summary = updated

        val isHighPriority = effectiveLevel == EventLevel.ERROR || effectiveLevel == EventLevel.FATAL
        if (forceMetaPersist || isHighPriority || (now - handle.lastMetaPersistMs >= META_FLUSH_INTERVAL_MS)) {
            handle.lastMetaPersistMs = now
            writeSummaryAtomically(handle.dir, updated)
        }
    }

    private fun rotateTailIfNeeded(handle: ActiveSessionHandle, incomingBytes: Int) {
        if (handle.tailBytesWritten + incomingBytes <= maxTailBytes) return
        runCatching {
            handle.tailStream?.flush()
            handle.tailStream?.close()
            handle.tailStream = null
            val tailFile = File(handle.dir, FILE_EVENTS_TAIL)
            val prevFile = File(handle.dir, FILE_EVENTS_TAIL_PREV)
            if (tailFile.exists()) {
                Files.move(tailFile.toPath(), prevFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            handle.tailStream = BufferedOutputStream(FileOutputStream(tailFile, true))
            handle.tailBytesWritten = 0L
        }
    }

    private fun finalizeSessionLocked(
        handle: ActiveSessionHandle,
        status: SessionStatus,
        exitCategory: String,
        exitSummary: String,
    ) {
        val now = clockMs()
        val finalSummary = handle.summary.copy(
            status = status,
            exitCategory = exitCategory,
            exitSummary = exitSummary,
            updatedAtMs = now,
            endedAtMs = now,
            isCurrentSession = false,
        )
        handle.summary = finalSummary
        handle.closeStreams()
        writeSummaryAtomically(handle.dir, finalSummary)
        runCatching {
            val md = buildAiMarkdownReportInternal(handle.dir, finalSummary)
            writeTextAtomically(latestAiReportFile, md, Charsets.UTF_8, worldReadable = true)
            if (status == SessionStatus.ENDED_ABNORMALLY) {
                writeTextAtomically(previousAbnormalReportFile, md, Charsets.UTF_8, worldReadable = true)
            }
        }
        active = null
        pruneOldSessionsLocked()
    }

    private fun refreshLatestAiReportLocked() {
        val handle = active ?: return
        runCatching {
            val md = buildAiMarkdownReportInternal(handle.dir, handle.summary)
            writeTextAtomically(latestAiReportFile, md, Charsets.UTF_8, worldReadable = true)
        }
    }

    private fun buildAiMarkdownReportInternal(dir: File, summary: SessionSummary): String {
        val headEvents = readEventsJsonl(File(dir, FILE_EVENTS_HEAD))
        val prevTailEvents = readEventsJsonl(File(dir, FILE_EVENTS_TAIL_PREV))
        val tailEvents = readEventsJsonl(File(dir, FILE_EVENTS_TAIL))
        val alertEvents = readEventsJsonl(File(dir, FILE_EVENTS_ALERTS))
        val metrics = readMetricsJsonl(File(dir, FILE_METRICS))
        val subLogs = readOptionalText(File(dir, FILE_VSCODE_SUBLOGS))
        val exitTrace = readOptionalText(File(dir, FILE_EXIT_TRACE))

        val sb = StringBuilder(16_384)
        sb.appendLine("# KuCode AI セッション診断バンドル (Flight Recorder)")
        sb.appendLine("- Schema: `$SCHEMA_VERSION`")
        sb.appendLine("- Session ID: `${summary.sessionId}`${if (summary.isCurrentSession) " *(現在稼働中)*" else ""}")
        sb.appendLine("- Session Status: `${summary.status}` (`${summary.exitCategory}`)")
        sb.appendLine("- Exit / State Summary: `${summary.exitSummary}`")
        sb.appendLine("- Started (UTC): `${formatUtcIso(summary.startedAtMs)}`")
        sb.appendLine(
            "- Ended / Updated (UTC): `${formatUtcIso(summary.endedAtMs ?: summary.updatedAtMs)}` " +
                "(Duration: `${formatDurationHuman(summary.durationMs)}`)",
        )
        sb.appendLine("- Process PID: `${summary.pid}` | App: `${summary.packageName} ${summary.appVersion}`")
        sb.appendLine("- Device: `${summary.deviceSummary}` | Android: `${summary.androidSummary}` | PageSize: `${summary.pageSize}`")
        sb.appendLine("- WebView: `${summary.webviewVersion}` | XR Spatial: `${summary.xrSpatialSupported}`")
        sb.appendLine(
            "- Runtime: `${summary.runtimeVersion}` | State: `${summary.lastRuntimeState}` | " +
                "EditorPort: `${summary.editorPort}` | AdminPort: `${summary.adminPort}` | " +
                "DisableSeccomp: `${summary.disableSeccomp}`",
        )
        sb.appendLine(
            "- Host Permissions & View: `AllFiles=${summary.hasAllFilesAccess}, " +
                "Notifications=${summary.notificationsEnabled}, BatteryOptIgnored=${summary.batteryOptIgnored}, " +
                "ActiveView=${summary.lastActiveView}, WindowFocus=${summary.lastWindowFocus}, " +
                "ExternalBrowserLaunches=${summary.externalUrlCount}`",
        )
        sb.appendLine()

        // Section 1: AI Triage & Anomaly Digest
        sb.appendLine("## 1. AI トリアージ要約 (Anomaly & Error Digest)")
        sb.appendLine(
            "- Counters: `totalEvents=${summary.totalEvents}, errors=${summary.errorCount}, " +
                "warnings=${summary.warnCount}, knownBenignFiltered=${summary.benignNoiseCount}`",
        )
        if (summary.benignNoiseCount > 0) {
            sb.appendLine(
                "- Note for AI: Known benign Android/OSS OpenVSCode messages (`Unable to retrieve mac address`, " +
                    "`vsda_bg.wasm` / `vsda.js` 404) occurred `${summary.benignNoiseCount}` time(s) and are harmless.",
            )
        }
        if (alertEvents.isEmpty()) {
            sb.appendLine("- Detected Alerts (WARN/ERROR/FATAL): `(none)`")
        } else {
            sb.appendLine("- Detected Alerts (Chronological, up to ${alertEvents.size}):")
            for (ev in alertEvents.takeLast(40)) {
                sb.appendLine("  - `[+${formatOffset(ev.offsetMs)}] [${ev.level}] [${ev.source}]` ${ev.message.replace('\n', ' ')}")
            }
        }
        sb.appendLine()

        // Section 2: Resource & Health Metrics Timeline
        sb.appendLine("## 2. リソース & ヘルスメトリクス推移 (Metrics Timeline)")
        if (metrics.isEmpty()) {
            sb.appendLine("`(no metrics recorded)`")
        } else {
            sb.appendLine("| Offset | Trigger | Avail/Total RAM | PSS | JVM Heap | Native | FDs | Thr | DiskFree | Thermal | Bat | Net | State / View / Focus |")
            sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
            val selectedMetrics = selectRepresentativeMetrics(metrics, maxRows = 25)
            for (m in selectedMetrics) {
                val ram = if (m.availMemMb >= 0 && m.totalMemMb >= 0) "${m.availMemMb}/${m.totalMemMb}MB${if (m.lowMemory) "!" else ""}" else "-"
                val pss = if (m.pssMb >= 0) "${m.pssMb}MB" else "-"
                val jvm = if (m.jvmHeapUsedMb >= 0) "${m.jvmHeapUsedMb}/${m.jvmHeapMaxMb}MB" else "-"
                val nat = if (m.nativeHeapMb >= 0) "${m.nativeHeapMb}MB" else "-"
                val fds = if (m.openFds >= 0) m.openFds.toString() else "-"
                val thr = if (m.threadCount >= 0) m.threadCount.toString() else "-"
                val disk = if (m.freeDiskMb >= 0) "${m.freeDiskMb}MB" else "-"
                val bat = if (m.batteryPct >= 0) "${m.batteryPct}%${if (m.isCharging) "+" else ""}${if (m.powerSaveMode) "(PS)" else ""}" else "-"
                val viewFocus = "${m.runtimeState}/${m.activeView}/focus=${m.windowFocus}/srv=${m.serverAlive}"
                sb.appendLine(
                    "| +${formatOffset(m.offsetMs)} | `${m.trigger}` | $ram | $pss | $jvm | $nat | $fds | $thr | $disk | ${m.thermalStatus} | $bat | ${m.networkSummary} | `$viewFocus` |",
                )
            }
        }
        sb.appendLine()

        // Section 3: Crash / OS Exit Trace (if present)
        if (exitTrace.isNotBlank()) {
            sb.appendLine("## 3. クラッシュ / OS 終了トレース (Crash & ApplicationExitInfo Trace)")
            sb.appendLine("```text")
            sb.appendLine(exitTrace.trim())
            sb.appendLine("```")
            sb.appendLine()
        }

        // Section 4: Unified Cross-Layer Event Timeline
        val combinedTail = (prevTailEvents + tailEvents).takeLast(MAX_REPORT_TAIL_EVENTS)
        sb.appendLine("## 4. 統合イベント・標準出力・WebView・Logcat タイムライン")
        if (headEvents.isEmpty() && combinedTail.isEmpty()) {
            sb.appendLine("```text\n(no events)\n```")
        } else {
            sb.appendLine("```text")
            for (ev in headEvents) {
                sb.appendLine(formatEventLine(ev))
            }
            val lastHeadSeq = headEvents.lastOrNull()?.seq ?: 0
            val firstTailSeq = combinedTail.firstOrNull()?.seq ?: (lastHeadSeq + 1)
            if (firstTailSeq > lastHeadSeq + 1) {
                sb.appendLine("... [omitted ${firstTailSeq - lastHeadSeq - 1} routine middle events; all WARN/ERROR events are preserved in Section 1] ...")
            }
            for (ev in combinedTail) {
                if (ev.seq > lastHeadSeq) {
                    sb.appendLine(formatEventLine(ev))
                }
            }
            sb.appendLine("```")
        }
        sb.appendLine()

        // Section 5: OpenVSCode Internal Sub-Logs
        if (subLogs.isNotBlank()) {
            sb.appendLine("## 5. OpenVSCode 内部ログ抜粋 (remoteagent / ptyhost / exthost)")
            sb.appendLine("```text")
            sb.appendLine(subLogs.trim())
            sb.appendLine("```")
        }

        return sb.toString()
    }

    private fun buildRawTimelineReportInternal(dir: File, summary: SessionSummary): String {
        val headEvents = readEventsJsonl(File(dir, FILE_EVENTS_HEAD))
        val prevTailEvents = readEventsJsonl(File(dir, FILE_EVENTS_TAIL_PREV))
        val tailEvents = readEventsJsonl(File(dir, FILE_EVENTS_TAIL))
        val exitTrace = readOptionalText(File(dir, FILE_EXIT_TRACE))
        val subLogs = readOptionalText(File(dir, FILE_VSCODE_SUBLOGS))

        val sb = StringBuilder(16_384)
        sb.appendLine("# Session ${summary.sessionId} [${summary.status} / ${summary.exitCategory}]")
        sb.appendLine("# Started=${formatUtcIso(summary.startedAtMs)} Duration=${formatDurationHuman(summary.durationMs)} PID=${summary.pid}")
        sb.appendLine("# Summary=${summary.exitSummary}")
        sb.appendLine()
        for (ev in headEvents) {
            sb.appendLine(formatEventLine(ev))
        }
        val lastHeadSeq = headEvents.lastOrNull()?.seq ?: 0
        val allTail = prevTailEvents + tailEvents
        val firstTailSeq = allTail.firstOrNull()?.seq ?: (lastHeadSeq + 1)
        if (firstTailSeq > lastHeadSeq + 1) {
            sb.appendLine("... [${firstTailSeq - lastHeadSeq - 1} events rotated] ...")
        }
        for (ev in allTail) {
            if (ev.seq > lastHeadSeq) {
                sb.appendLine(formatEventLine(ev))
            }
        }
        if (exitTrace.isNotBlank()) {
            sb.appendLine()
            sb.appendLine(exitTrace.trim())
        }
        if (subLogs.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("=== OpenVSCode Internal Logs ===")
            sb.appendLine(subLogs.trim())
        }
        return sb.toString()
    }

    private fun buildJsonBundleInternal(dir: File, summary: SessionSummary): String {
        val headEvents = readEventsJsonl(File(dir, FILE_EVENTS_HEAD))
        val prevTailEvents = readEventsJsonl(File(dir, FILE_EVENTS_TAIL_PREV))
        val tailEvents = readEventsJsonl(File(dir, FILE_EVENTS_TAIL))
        val alertEvents = readEventsJsonl(File(dir, FILE_EVENTS_ALERTS))
        val metrics = readMetricsJsonl(File(dir, FILE_METRICS))

        val lastHeadSeq = headEvents.lastOrNull()?.seq ?: 0
        val mergedEvents = headEvents + (prevTailEvents + tailEvents).filter { it.seq > lastHeadSeq }

        return JSONObject()
            .put("meta", summary.toJsonObject())
            .put("alerts", JSONArray().apply { alertEvents.forEach { put(it.toJsonObject()) } })
            .put("metrics", JSONArray().apply { metrics.forEach { put(it.toJsonObject()) } })
            .put("events", JSONArray().apply { mergedEvents.forEach { put(it.toJsonObject()) } })
            .put("exitTrace", readOptionalText(File(dir, FILE_EXIT_TRACE)))
            .put("vscodeSubLogs", readOptionalText(File(dir, FILE_VSCODE_SUBLOGS)))
            .toString(2)
    }

    private fun recomputeCountsFromDisk(dir: File, base: SessionSummary): SessionSummary {
        val head = readEventsJsonl(File(dir, FILE_EVENTS_HEAD))
        val prevTail = readEventsJsonl(File(dir, FILE_EVENTS_TAIL_PREV))
        val tail = readEventsJsonl(File(dir, FILE_EVENTS_TAIL))
        val alerts = readEventsJsonl(File(dir, FILE_EVENTS_ALERTS))
        val metrics = readMetricsJsonl(File(dir, FILE_METRICS))

        val allSeen = (head + prevTail + tail).distinctBy { it.seq }
        val maxSeq = maxOf(base.totalEvents, allSeen.maxOfOrNull { it.seq } ?: 0)
        val maxTs = listOfNotNull(
            base.updatedAtMs,
            allSeen.maxOfOrNull { it.timestampMs },
            metrics.maxOfOrNull { it.timestampMs },
        ).maxOrNull() ?: base.startedAtMs

        val alertErrs = alerts.count { it.level == EventLevel.ERROR || it.level == EventLevel.FATAL }
        val alertWarns = alerts.count { it.level == EventLevel.WARN }
        val benignCount = maxOf(base.benignNoiseCount, allSeen.count { it.benignNote != null })

        return base.copy(
            updatedAtMs = maxTs,
            totalEvents = maxSeq,
            errorCount = maxOf(base.errorCount, alertErrs),
            warnCount = maxOf(base.warnCount, alertWarns),
            benignNoiseCount = benignCount,
        )
    }

    private data class ReconciledClassification(
        val status: SessionStatus,
        val category: String,
        val summary: String,
        val endedAtMs: Long,
    )

    private fun classifyReconciledExit(
        session: SessionSummary,
        exit: OsProcessExitRecord?,
    ): ReconciledClassification {
        if (exit == null) {
            val wasIdle = session.lastRuntimeState == "IDLE"
            return if (wasIdle) {
                ReconciledClassification(
                    status = SessionStatus.ENDED_CLEANLY,
                    category = "OS_RECLAIMED_IDLE",
                    summary = "Idle process reclaimed without explicit shutdown",
                    endedAtMs = session.updatedAtMs,
                )
            } else {
                ReconciledClassification(
                    status = SessionStatus.ENDED_ABNORMALLY,
                    category = "ABNORMAL_UNEXPLAINED",
                    summary = "Process terminated unexpectedly while runtime was ${session.lastRuntimeState} (no OS ApplicationExitInfo)",
                    endedAtMs = session.updatedAtMs,
                )
            }
        }

        val endMs = maxOf(session.updatedAtMs, exit.timestampMs)
        val detailSuffix = "(reason=${exit.reasonName}, status=${exit.status}, importance=${exit.importanceName}, pss=${exit.pssMb}MB)"

        return when (exit.reasonName) {
            "REASON_CRASH" -> ReconciledClassification(
                SessionStatus.ENDED_ABNORMALLY,
                "CRASH_JVM",
                "Unhandled Java/Kotlin crash $detailSuffix",
                endMs,
            )
            "REASON_CRASH_NATIVE" -> ReconciledClassification(
                SessionStatus.ENDED_ABNORMALLY,
                "CRASH_NATIVE",
                "Native crash / signal $detailSuffix",
                endMs,
            )
            "REASON_ANR" -> ReconciledClassification(
                SessionStatus.ENDED_ABNORMALLY,
                "ANR",
                "Application Not Responding (ANR) $detailSuffix",
                endMs,
            )
            "REASON_INITIALIZATION_FAILURE" -> ReconciledClassification(
                SessionStatus.ENDED_ABNORMALLY,
                "INIT_FAILURE",
                "Process initialization failure $detailSuffix",
                endMs,
            )
            "REASON_EXCESSIVE_RESOURCE_USAGE" -> ReconciledClassification(
                SessionStatus.ENDED_ABNORMALLY,
                "EXCESSIVE_RESOURCE",
                "Killed by OS for excessive CPU/battery resource usage $detailSuffix",
                endMs,
            )
            "REASON_PERMISSION_CHANGE" -> ReconciledClassification(
                SessionStatus.ENDED_ABNORMALLY,
                "PERMISSION_CHANGE",
                "Process restarted by OS due to runtime permission change $detailSuffix",
                endMs,
            )
            "REASON_LOW_MEMORY" -> {
                val wasActive = session.lastRuntimeState != "IDLE" || exit.importance <= 200
                ReconciledClassification(
                    if (wasActive) SessionStatus.ENDED_ABNORMALLY else SessionStatus.ENDED_CLEANLY,
                    if (wasActive) "LOW_MEMORY" else "CACHED_LOW_MEMORY",
                    "Killed by Android LMKD low-memory killer $detailSuffix",
                    endMs,
                )
            }
            "REASON_SIGNALED" -> {
                val wasActive = session.lastRuntimeState != "IDLE"
                ReconciledClassification(
                    if (wasActive) SessionStatus.ENDED_ABNORMALLY else SessionStatus.ENDED_CLEANLY,
                    "SIGNALED",
                    "Terminated by OS signal ${exit.status} $detailSuffix",
                    endMs,
                )
            }
            "REASON_USER_REQUESTED", "REASON_USER_STOPPED" -> ReconciledClassification(
                SessionStatus.ENDED_CLEANLY,
                "USER_TERMINATED",
                "Stopped by user / system task manager $detailSuffix",
                endMs,
            )
            "REASON_PACKAGE_STATE_CHANGE", "REASON_PACKAGE_UPDATED" -> ReconciledClassification(
                SessionStatus.ENDED_CLEANLY,
                "APP_UPDATED",
                "Stopped for APK package update $detailSuffix",
                endMs,
            )
            "REASON_EXIT_SELF" -> ReconciledClassification(
                SessionStatus.ENDED_CLEANLY,
                "CLEAN",
                "Process exited cleanly $detailSuffix",
                endMs,
            )
            "REASON_FREEZER" -> {
                val wasActive = session.lastRuntimeState != "IDLE"
                ReconciledClassification(
                    if (wasActive) SessionStatus.ENDED_ABNORMALLY else SessionStatus.ENDED_CLEANLY,
                    "FREEZER",
                    "Killed by Android cached-app freezer $detailSuffix",
                    endMs,
                )
            }
            else -> {
                val wasActive = session.lastRuntimeState != "IDLE"
                ReconciledClassification(
                    if (wasActive) SessionStatus.ENDED_ABNORMALLY else SessionStatus.ENDED_CLEANLY,
                    if (wasActive) "ABNORMAL_OTHER" else "CLEAN_OTHER",
                    "Process exited $detailSuffix",
                    endMs,
                )
            }
        }
    }

    private fun appendReconciliationEventToDisk(
        dir: File,
        startedAtMs: Long,
        seq: Int,
        timestampMs: Long,
        level: EventLevel,
        source: String,
        message: String,
    ) {
        val ev = RecordedEvent(
            seq = seq,
            timestampMs = timestampMs,
            offsetMs = (timestampMs - startedAtMs).coerceAtLeast(0L),
            level = level,
            source = source,
            message = LogRedactor.redact(message),
        )
        val line = (ev.toJsonObject().toString() + "\n").toByteArray(Charsets.UTF_8)
        runCatching {
            FileOutputStream(File(dir, FILE_EVENTS_TAIL), true).use {
                it.write(line)
                it.flush()
            }
            if (level == EventLevel.WARN || level == EventLevel.ERROR || level == EventLevel.FATAL) {
                FileOutputStream(File(dir, FILE_EVENTS_ALERTS), true).use {
                    it.write(line)
                    it.flush()
                }
            }
        }
    }

    private fun appendTraceToDisk(dir: File, traceBlock: String) {
        val sanitized = LogRedactor.redact(traceBlock).take(MAX_TRACE_CHARS)
        val traceFile = File(dir, FILE_EXIT_TRACE)
        val existing = readOptionalText(traceFile)
        val combined = if (existing.isBlank()) {
            sanitized + "\n"
        } else {
            (existing.trimEnd() + "\n\n" + sanitized).takeLast(MAX_TRACE_CHARS) + "\n"
        }
        runCatching {
            writeTextAtomically(traceFile, combined, Charsets.UTF_8, worldReadable = true)
        }
    }

    private fun pruneOldSessionsLocked() {
        val activeDirName = active?.dir?.name
        val dirs = sessionsRootDir.listFiles()
            ?.filter { it.isDirectory && it.name != activeDirName }
            ?.sortedByDescending { it.name }
            .orEmpty()

        val keepLimit = (maxStoredSessions - if (activeDirName != null) 1 else 0).coerceAtLeast(1)
        if (dirs.size > keepLimit) {
            for (stale in dirs.drop(keepLimit)) {
                runCatching { deleteTreeNoFollow(stale) }
            }
        }
    }

    private fun ensureRootDirectories() {
        ensureDirectoryWithMode(diagnosticsRootDir, 0b111_101_101)
        ensureDirectoryWithMode(sessionsRootDir, 0b111_101_101)
    }

    private fun resolveSafeSessionDir(sessionId: String): File? {
        if (!SESSION_ID_REGEX.matches(sessionId)) return null
        val candidate = File(sessionsRootDir, sessionId)
        if (!Files.isDirectory(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return candidate
    }

    private fun writeSummaryAtomically(dir: File, summary: SessionSummary) {
        runCatching {
            writeTextAtomically(
                File(dir, FILE_META),
                summary.toJsonObject().toString(2) + "\n",
                Charsets.UTF_8,
                worldReadable = true,
            )
        }
    }

    companion object {
        const val SCHEMA_VERSION = "kucode-session-v1"

        internal const val DEFAULT_MAX_SESSIONS = 20
        internal const val DEFAULT_MAX_HEAD_EVENTS = 250
        internal const val DEFAULT_MAX_TAIL_BYTES = 768L * 1024L
        internal const val DEFAULT_MAX_ALERT_EVENTS = 300
        internal const val DEFAULT_MAX_METRICS_SNAPSHOTS = 360
        private const val MAX_REPORT_TAIL_EVENTS = 450
        private const val MAX_SINGLE_MESSAGE_CHARS = 4_096
        private const val MAX_TRACE_CHARS = 96 * 1024
        private const val MAX_SUBLOG_BYTES_PER_FILE = 8 * 1024
        private const val META_FLUSH_INTERVAL_MS = 2_000L

        private const val FILE_META = "meta.json"
        private const val FILE_EVENTS_HEAD = "events.head.jsonl"
        private const val FILE_EVENTS_TAIL = "events.tail.jsonl"
        private const val FILE_EVENTS_TAIL_PREV = "events.tail.prev.jsonl"
        private const val FILE_EVENTS_ALERTS = "events.alerts.jsonl"
        private const val FILE_METRICS = "metrics.jsonl"
        private const val FILE_VSCODE_SUBLOGS = "vscode-sublogs.txt"
        private const val FILE_EXIT_TRACE = "exit-trace.txt"

        private val SESSION_ID_REGEX = Regex("^[0-9]{8}-[0-9]{6,9}(?:-[0-9]{4})?-[0-9a-f]{4,6}$")
        private val random = SecureRandom()
        private val sessionSeq = java.util.concurrent.atomic.AtomicInteger(0)

        internal fun classifyBenignReason(message: String): String? = when {
            message.contains("Unable to retrieve mac address", ignoreCase = true) ->
                "benign: Android SELinux blocks MAC address read for untrusted_app"
            message.contains("vsda_bg.wasm", ignoreCase = true) ||
                message.contains("node_modules/vsda/rust/web/vsda.js", ignoreCase = true) ->
                "benign: proprietary vsda absent in OSS openvscode-server"
            else -> null
        }

        internal fun extractOpenVsCodeSubLogs(vscodeDataDir: File): String {
            val candidates = listOf(
                File(vscodeDataDir, "data/logs"),
                File(vscodeDataDir, "logs"),
            )
            val logsBase = candidates.firstOrNull { it.isDirectory } ?: return ""
            val latestRunDir = logsBase.listFiles()
                ?.filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
                ?.maxByOrNull { it.name }
                ?: return ""

            val filesToCollect = mutableListOf<Pair<String, File>>()
            for (rootLog in listOf("remoteagent.log", "ptyhost.log", "sharedprocess.log")) {
                val f = File(latestRunDir, rootLog)
                if (f.isFile && !Files.isSymbolicLink(f.toPath())) {
                    filesToCollect.add(rootLog to f)
                }
            }
            latestRunDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("exthost") && !Files.isSymbolicLink(it.toPath()) }
                ?.sortedBy { it.name }
                ?.forEach { extDir ->
                    val extLog = File(extDir, "exthost.log")
                    if (extLog.isFile && !Files.isSymbolicLink(extLog.toPath())) {
                        filesToCollect.add("${extDir.name}/exthost.log" to extLog)
                    }
                }

            if (filesToCollect.isEmpty()) return ""
            val sb = StringBuilder()
            sb.appendLine("# Log directory: ${latestRunDir.name}")
            for ((label, file) in filesToCollect) {
                val tail = readBoundedTailText(file, MAX_SUBLOG_BYTES_PER_FILE)
                    .lines()
                    .takeLast(40)
                    .joinToString("\n")
                    .trim()
                if (tail.isNotEmpty()) {
                    sb.appendLine("--- $label ---")
                    sb.appendLine(LogRedactor.redact(tail))
                }
            }
            return sb.toString()
        }

        private fun readBoundedTailText(file: File, maxBytes: Int): String {
            return try {
                val len = file.length()
                if (len <= 0L) return ""
                java.io.RandomAccessFile(file, "r").use { raf ->
                    val start = (len - maxBytes).coerceAtLeast(0L)
                    raf.seek(start)
                    val buf = ByteArray((len - start).toInt())
                    raf.readFully(buf)
                    val text = String(buf, Charsets.UTF_8)
                    if (start > 0L) text.substringAfter('\n', text) else text
                }
            } catch (_: IOException) {
                ""
            }
        }

        private fun readEventsJsonl(file: File): List<RecordedEvent> {
            if (!file.isFile) return emptyList()
            val out = mutableListOf<RecordedEvent>()
            runCatching {
                file.useLines(Charsets.UTF_8) { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) continue
                        runCatching { RecordedEvent.fromJsonObject(JSONObject(trimmed)) }
                            .getOrNull()
                            ?.let { out.add(it) }
                    }
                }
            }
            return out
        }

        private fun readMetricsJsonl(file: File): List<MetricSnapshot> {
            if (!file.isFile) return emptyList()
            val out = mutableListOf<MetricSnapshot>()
            runCatching {
                file.useLines(Charsets.UTF_8) { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) continue
                        runCatching { MetricSnapshot.fromJsonObject(JSONObject(trimmed)) }
                            .getOrNull()
                            ?.let { out.add(it) }
                    }
                }
            }
            return out
        }

        private fun readOptionalText(file: File): String {
            if (!file.isFile) return ""
            return runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
        }

        private fun selectRepresentativeMetrics(all: List<MetricSnapshot>, maxRows: Int): List<MetricSnapshot> {
            if (all.size <= maxRows) return all
            val headCount = maxRows / 3
            val tailCount = maxRows - headCount
            return (all.take(headCount) + all.takeLast(tailCount)).distinctBy { it.timestampMs to it.trigger }
        }

        private fun formatEventLine(ev: RecordedEvent): String {
            val tag = if (ev.benignNote != null) "${ev.level}/BENIGN" else ev.level.name
            val multiLineAdjusted = if (ev.message.contains('\n')) {
                ev.message.replace("\n", "\n    ")
            } else {
                ev.message
            }
            return "[+${formatOffset(ev.offsetMs)}] [$tag] [${ev.source}] $multiLineAdjusted"
        }

        internal fun formatOffset(offsetMs: Long): String {
            val safe = offsetMs.coerceAtLeast(0L)
            val totalSeconds = safe / 1000L
            val millis = safe % 1000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
        }

        internal fun formatDurationHuman(durationMs: Long): String {
            val sec = (durationMs.coerceAtLeast(0L)) / 1000L
            val h = sec / 3600L
            val m = (sec % 3600L) / 60L
            val s = sec % 60L
            return when {
                h > 0 -> "${h}h ${m}m ${s}s"
                m > 0 -> "${m}m ${s}s"
                else -> "${s}s (${durationMs}ms)"
            }
        }

        internal fun formatUtcIso(epochMs: Long): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return fmt.format(Date(epochMs))
        }

        private fun generateSessionId(nowMs: Long): String {
            val fmt = SimpleDateFormat("yyyyMMdd-HHmmssSSS", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val seq = (sessionSeq.incrementAndGet() and 0xFFFF) % 10_000
            val suffix = ByteArray(2).also { random.nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            return String.format(Locale.US, "%s-%04d-%s", fmt.format(Date(nowMs)), seq, suffix)
        }
    }
}
