package dev.koduki.kucode

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionLogStoreTest {

    @After
    fun tearDown() {
        LogRedactor.clearDynamicSecrets()
    }

    private fun sampleEnv() = SessionLogStore.SessionEnvMetadata(
        appVersion = "0.7.0-debug (code=7, targetSdk=35)",
        packageName = "dev.koduki.kucode.debug",
        deviceSummary = "XREAL Android XR Puck (axrpuck)",
        androidSummary = "14 (API 34)",
        webviewVersion = "com.google.android.webview 157.0.8103.50",
        pageSize = "4096B (4KB)",
        xrSpatialSupported = true,
        hasAllFilesAccess = true,
        notificationsEnabled = true,
        batteryOptIgnored = true,
    )

    @Test
    fun recordsCleanLifecycleBenignNoiseMetricsSublogsAndExportsAiBundle() {
        val dir = Files.createTempDirectory("kucode-session-clean").toFile()
        var nowMs = 1_760_000_000_000L
        try {
            val store = SessionLogStore(
                diagnosticsRootDir = File(dir, "diagnostics"),
                clockMs = { nowMs },
            )
            val started = store.startSession(pid = 1001, env = sampleEnv())
            assertTrue(started.isCurrentSession)
            assertEquals(SessionLogStore.SessionStatus.RUNNING, started.status)

            nowMs += 500
            // Known benign messages must NOT count as real errors
            store.recordEvent(
                SessionLogStore.EventLevel.ERROR,
                "openvscode.stdout",
                "Error: Unable to retrieve mac address (unexpected format)",
            )
            store.recordEvent(
                SessionLogStore.EventLevel.ERROR,
                "openvscode.stdout",
                "File not found: /opt/openvscode-server/node_modules/vsda/rust/web/vsda_bg.wasm",
            )

            nowMs += 1000
            store.updateSessionState(
                runtimeState = "READY",
                editorPort = 18765,
                adminPort = 38689,
                activeView = "EDITOR",
                windowFocus = true,
                incrementExternalUrl = true,
            )
            store.recordEvent(
                SessionLogStore.EventLevel.INFO,
                "android.intent",
                "Opened external browser -> https://github.com/login/device?tkn=my_secret_token_12345",
            )
            store.recordMetric(
                SessionLogStore.MetricSnapshot(
                    timestampMs = nowMs,
                    trigger = "server_ready",
                    availMemMb = 7500,
                    totalMemMb = 15519,
                    pssMb = 182,
                    hostCpuTimeMs = 4321,
                    jvmHeapUsedMb = 24,
                    jvmHeapMaxMb = 256,
                    nativeHeapMb = 44,
                    openFds = 88,
                    threadCount = 26,
                    freeDiskMb = 416000,
                    thermalStatus = "NONE",
                    batteryPct = 85,
                    isCharging = true,
                    networkSummary = "WIFI(ok,dns=1)",
                    runtimeState = "READY",
                    serverAlive = true,
                    activeView = "EDITOR",
                    windowFocus = true,
                ),
            )
            store.recordMetric(
                SessionLogStore.MetricSnapshot(
                    timestampMs = nowMs + 100,
                    trigger = "cpu_unknown_check",
                    hostCpuTimeMs = -1L,
                ),
            )

            // Fake openvscode-server internal file logs
            val vscodeData = File(dir, "vscode-data")
            val runLogs = File(vscodeData, "data/logs/20261124T004800")
            File(runLogs, "exthost1").mkdirs()
            File(runLogs, "ptyhost.log").writeText("[ptyhost] spawned /bin/bash (cols=80, rows=24)\n")
            File(runLogs, "exthost1/exthost.log").writeText("[exthost] kucode-tools activated cleanly\n")
            store.harvestOpenVsCodeSubLogs(vscodeData)

            nowMs += 3000
            store.endSession(reason = "MainActivity finished normally", abnormal = false, exitCategory = "CLEAN")
            assertNull(store.currentSessionId())

            val sessions = store.listSessions()
            assertEquals(1, sessions.size)
            val fin = sessions.single()
            assertEquals(SessionLogStore.SessionStatus.ENDED_CLEANLY, fin.status)
            assertEquals("CLEAN", fin.exitCategory)
            assertEquals(0, fin.errorCount)
            assertEquals(2, fin.benignNoiseCount)
            assertEquals(1, fin.externalUrlCount)

            val aiReport = store.renderSessionBundle("latest", "ai")!!
            assertTrue(aiReport.contains("# KuCode AI セッション診断バンドル"))
            assertTrue(aiReport.contains("knownBenignFiltered=2"))
            assertTrue(aiReport.contains("WIFI(ok,dns=1)"))
            assertTrue(aiReport.contains("| 4321 |"))
            assertTrue(aiReport.contains("| unknown |"))
            assertTrue(aiReport.contains("[ptyhost] spawned /bin/bash"))
            assertTrue(aiReport.contains("[exthost] kucode-tools activated cleanly"))
            assertFalse("Query token must be redacted", aiReport.contains("my_secret_token_12345"))

            val jsonBundle = JSONObject(store.renderSessionBundle(fin.sessionId, "json")!!)
            assertEquals(fin.sessionId, jsonBundle.getJSONObject("meta").getString("sessionId"))
            assertEquals(2, jsonBundle.getJSONArray("metrics").length())
            assertEquals(4321L, jsonBundle.getJSONArray("metrics").getJSONObject(0).getLong("hostCpuTimeMs"))
            assertEquals(-1L, jsonBundle.getJSONArray("metrics").getJSONObject(1).getLong("hostCpuTimeMs"))

            val exported = store.exportSessionBundleToDirectory("latest", File(dir, "exports"))
            assertNotNull(exported)
            assertTrue(exported!!.isFile)
            assertTrue(exported.readText().contains(fin.sessionId))
        } finally {
            deleteTreeNoFollow(dir)
        }
    }

    @Test
    fun reconcilesSuddenAbnormalProcessKillsWithApplicationExitInfoAndUncaughtCrashes() {
        val dir = Files.createTempDirectory("kucode-session-abnormal").toFile()
        val diagDir = File(dir, "diagnostics")
        var nowMs = 1_760_000_100_000L
        try {
            // 1. Session 1 starts and enters READY, then process dies suddenly (no endSession called!)
            val store1 = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val s1 = store1.startSession(pid = 4242, env = sampleEnv())
            nowMs += 2_000
            store1.updateSessionState(runtimeState = "READY", editorPort = 18765, activeView = "EDITOR")
            store1.recordEvent(SessionLogStore.EventLevel.WARN, "android.memory", "onTrimMemory: RUNNING_CRITICAL (15)")

            // 2. Next app launch (store2, pid=5000) reconciles Session 1 against OS ApplicationExitInfo (ANR)
            nowMs += 10_000
            val store2 = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val reconciled = store2.reconcileUnfinishedSessions(
                currentPid = 5000,
                exitRecords = listOf(
                    SessionLogStore.OsProcessExitRecord(
                        pid = 4242,
                        timestampMs = nowMs - 1_000,
                        reasonCode = 6,
                        reasonName = "REASON_ANR",
                        status = 0,
                        importance = 100,
                        importanceName = "FOREGROUND",
                        pssMb = 412,
                        rssMb = 590,
                        description = "Input dispatching timed out",
                        traceSnippet = "\"main\" prio=5 tid=1 Blocked\n  at dev.koduki.kucode.Sample.block(Sample.kt:12)",
                    ),
                ),
            )
            assertEquals(1, reconciled.size)
            assertEquals(s1.sessionId, reconciled.single().sessionId)
            assertEquals(SessionLogStore.SessionStatus.ENDED_ABNORMALLY, reconciled.single().status)
            assertEquals("ANR", reconciled.single().exitCategory)
            assertTrue(store2.previousAbnormalReportFile.isFile)

            // Start Session 2 and verify `prev` & `prev-abnormal` resolve to Session 1
            nowMs += 1_000
            val s2 = store2.startSession(pid = 5000, env = sampleEnv())
            assertEquals(s1.sessionId, store2.resolveSessionSummary("prev")?.sessionId)
            assertEquals(s1.sessionId, store2.resolveSessionSummary("prev-abnormal")?.sessionId)

            val prevAiReport = store2.renderSessionBundle("prev-abnormal", "ai")!!
            assertTrue(prevAiReport.contains("REASON_ANR"))
            assertTrue(prevAiReport.contains("onTrimMemory: RUNNING_CRITICAL"))
            assertTrue(prevAiReport.contains("\"main\" prio=5 tid=1 Blocked"))

            // 3. Simulate uncaught JVM exception in Session 2
            nowMs += 2_000
            store2.recordUncaughtCrash(
                threadName = "RenderWorker",
                throwable = IllegalStateException("Simulated fatal crash with token ghp_1234567890abcdefghijklmnop"),
            )
            val s2AfterCrash = store2.resolveSessionSummary(s2.sessionId)!!
            assertEquals(SessionLogStore.SessionStatus.ENDED_ABNORMALLY, s2AfterCrash.status)
            assertEquals("CRASH_JVM", s2AfterCrash.exitCategory)

            val crashReport = store2.renderSessionBundle(s2.sessionId, "ai")!!
            assertTrue(crashReport.contains("Uncaught JVM Exception (thread=RenderWorker)"))
            assertTrue(crashReport.contains("IllegalStateException"))
            assertFalse("GitHub token inside exception message must be redacted", crashReport.contains("ghp_1234567890"))
        } finally {
            deleteTreeNoFollow(dir)
        }
    }

    @Test
    fun preservesBootHeadEarlyAlertsAndRecentTailAcrossRotationAndPrunesOldSessions() {
        val dir = Files.createTempDirectory("kucode-session-rotate").toFile()
        var nowMs = 1_760_000_500_000L
        try {
            val store = SessionLogStore(
                diagnosticsRootDir = File(dir, "diagnostics"),
                maxStoredSessions = 3,
                maxHeadEvents = 5,
                maxTailBytes = 1_200L,
                clockMs = { nowMs++ },
            )

            val s1 = store.startSession(pid = 10, env = sampleEnv())
            store.recordEvent(SessionLogStore.EventLevel.INFO, "boot", "HEAD_MARKER_BOOT_1")
            // Early error after head window must survive tail rotation via events.alerts.jsonl
            repeat(6) { idx ->
                store.recordEvent(SessionLogStore.EventLevel.INFO, "init", "init-step-$idx")
            }
            store.recordEvent(SessionLogStore.EventLevel.ERROR, "early.subsystem", "EARLY_CRITICAL_ERROR_SURVIVES")
            repeat(60) { idx ->
                store.recordEvent(SessionLogStore.EventLevel.INFO, "noisy", "routine-log-entry-number-$idx-" + "x".repeat(40))
            }
            store.recordEvent(SessionLogStore.EventLevel.INFO, "shutdown", "FINAL_TAIL_MARKER_99")
            store.endSession("done")

            val report = store.renderSessionBundle(s1.sessionId, "ai")!!
            assertTrue("Boot head event must survive rotation", report.contains("HEAD_MARKER_BOOT_1"))
            assertTrue("Early error must survive rotation in alerts digest", report.contains("EARLY_CRITICAL_ERROR_SURVIVES"))
            assertTrue("Latest tail event must be present", report.contains("FINAL_TAIL_MARKER_99"))
            assertTrue("Middle omission marker should explain rotation", report.contains("omitted"))

            // Create 3 more sessions; maxStoredSessions=3 must prune oldest completed session(s)
            nowMs += 5_000
            store.startSession(pid = 11, env = sampleEnv()); store.endSession("done-2")
            nowMs += 5_000
            store.startSession(pid = 12, env = sampleEnv()); store.endSession("done-3")
            nowMs += 5_000
            val active4 = store.startSession(pid = 13, env = sampleEnv())

            val remaining = store.listSessions()
            assertEquals(3, remaining.size)
            assertEquals(active4.sessionId, remaining.first().sessionId)
            assertFalse("Oldest session should be pruned", remaining.any { it.sessionId == s1.sessionId })
            store.endSession("done-4")
        } finally {
            deleteTreeNoFollow(dir)
        }
    }

    @Test
    fun filterAndClassifyLogcatLine_capturesWarningsErrorsAndChromiumInfoWithoutSelfLoops() {
        assertNull(SessionTelemetryManager.filterAndClassifyLogcatLine("--------- beginning of main"))
        assertNull(SessionTelemetryManager.filterAndClassifyLogcatLine("11-24 00:33:21.100 D/SomeNoisyTag( 1234): verbose chatter"))
        assertNull(SessionTelemetryManager.filterAndClassifyLogcatLine("11-24 00:33:21.100 E/KuCodeSession( 1234): internal self tag"))

        val chromiumInfo = SessionTelemetryManager.filterAndClassifyLogcatLine(
            "11-24 00:33:22.250 I/cr_Ime( 4812): ImeAdapter focused node updated",
        )
        assertNotNull(chromiumInfo)
        assertEquals(SessionLogStore.EventLevel.INFO, chromiumInfo!!.first)
        assertTrue(chromiumInfo.second.contains("cr_Ime"))

        val glError = SessionTelemetryManager.filterAndClassifyLogcatLine(
            "11-24 00:33:25.901 E/chromium( 4812): [ERROR:gl_surface_egl.cc(812)] EGL driver error",
        )
        assertNotNull(glError)
        assertEquals(SessionLogStore.EventLevel.ERROR, glError!!.first)
    }

    @Test
    fun filtersXrealBenignNoiseHarvestsUserLogsHandlesOomAndPreventsPidReuseDoubleAttribution() {
        val dir = Files.createTempDirectory("kucode-session-v090").toFile()
        var nowMs = 1_760_001_000_000L
        try {
            val diagDir = File(dir, "diagnostics")
            val store = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val s1 = store.startSession(pid = 7777, env = sampleEnv())

            // 1. All 5 XREAL Puck benign noise patterns must be filtered out of error/warn counts
            store.recordEvent(SessionLogStore.EventLevel.WARN, "logcat", "W/WindowOnBackDispatcher: sendCancelIfRunning: isInProgress=false")
            store.recordEvent(SessionLogStore.EventLevel.ERROR, "logcat", "E/OpenGLRenderer: Unable to match the desired swap behavior.")
            store.recordEvent(SessionLogStore.EventLevel.ERROR, "webview.console.editor", "ResizeObserver loop completed with undelivered notifications.")
            store.recordEvent(SessionLogStore.EventLevel.WARN, "webview.console.editor", "Failed to load addon-ligatures")
            store.recordEvent(SessionLogStore.EventLevel.ERROR, "webview.console.editor", "[DEP0040] DeprecationWarning: The punycode module is deprecated.")

            // 2. Harvest OpenVSCode sub-logs from real `--user-data-dir` path `user/logs/<timestamp>/`
            val vscodeData = File(dir, "vscode-data")
            val userRunLogs = File(vscodeData, "user/logs/20261124T050000")
            File(userRunLogs, "exthost1").mkdirs()
            File(userRunLogs, "remoteagent.log").writeText("[remoteagent] listening on 18765\n")
            File(userRunLogs, "remoteexthost.log").writeText("[remoteexthost] loaded kucode-tools\n")
            File(userRunLogs, "exthost1/exthost.log").writeText("[exthost1] acp ready\n")
            store.harvestOpenVsCodeSubLogs(vscodeData)

            val summary1 = store.resolveSessionSummary(s1.sessionId)!!
            assertEquals(0, summary1.errorCount)
            assertEquals(0, summary1.warnCount)
            assertEquals(5, summary1.benignNoiseCount)

            val ai1 = store.renderSessionBundle(s1.sessionId, "ai")!!
            assertTrue(ai1.contains("[remoteagent] listening on 18765"))
            assertTrue(ai1.contains("[remoteexthost] loaded kucode-tools"))
            assertTrue(ai1.contains("[exthost1] acp ready"))
            assertTrue(ai1.contains("knownBenignFiltered=5"))

            // 3. OOM uncaught crash takes fast path (`CRASH_JVM_OOM`) without crashing
            store.recordUncaughtCrash("WorkerThread", OutOfMemoryError("Java heap space"))
            val afterOom = store.resolveSessionSummary(s1.sessionId)!!
            assertEquals(SessionLogStore.SessionStatus.ENDED_ABNORMALLY, afterOom.status)
            assertEquals("CRASH_JVM_OOM", afterOom.exitCategory)

            // 4. PID reuse: two unfinished sessions with the SAME pid must NOT both claim one OsProcessExitRecord
            nowMs += 10_000L
            val storeA = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val stale1 = storeA.startSession(pid = 8888, env = sampleEnv())
            nowMs += 20_000L
            val storeB = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val stale2 = storeB.startSession(pid = 8888, env = sampleEnv())

            nowMs += 5_000L
            val storeC = SessionLogStore(diagnosticsRootDir = diagDir, clockMs = { nowMs })
            val largeTrace = "HEAD_THREAD_MAIN\n" + ("  at frame.line(File.kt:1)\n".repeat(4000)) + "TAIL_END"
            val reconciled = storeC.reconcileUnfinishedSessions(
                currentPid = 9999,
                exitRecords = listOf(
                    SessionLogStore.OsProcessExitRecord(
                        pid = 8888,
                        timestampMs = nowMs - 1_000L,
                        reasonCode = 6,
                        reasonName = "REASON_ANR",
                        status = 0,
                        importance = 100,
                        importanceName = "FOREGROUND",
                        pssMb = 300,
                        rssMb = 400,
                        description = "ANR on second session",
                        traceSnippet = largeTrace,
                    ),
                ),
            )
            assertEquals(2, reconciled.size)
            val byId = reconciled.associateBy { it.sessionId }
            assertEquals("OS_RECLAIMED_IDLE", byId.getValue(stale1.sessionId).exitCategory)
            assertEquals("ANR", byId.getValue(stale2.sessionId).exitCategory)

            // 5. Large ANR trace must preserve the HEAD (`HEAD_THREAD_MAIN`), not only the tail
            val anrReport = storeC.renderSessionBundle(stale2.sessionId, "ai")!!
            assertTrue("ANR trace head must be preserved", anrReport.contains("HEAD_THREAD_MAIN"))
        } finally {
            deleteTreeNoFollow(dir)
        }
    }
}
