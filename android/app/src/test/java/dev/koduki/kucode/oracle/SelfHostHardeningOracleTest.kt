package dev.koduki.kucode.oracle

import android.content.pm.PackageInstaller
import dev.koduki.kucode.AndroidBridgePlatform
import dev.koduki.kucode.AndroidHostBridgeCore
import dev.koduki.kucode.DiagnosticShareImporter
import dev.koduki.kucode.DiagnosticsRunner
import dev.koduki.kucode.InstalledPackageSigners
import dev.koduki.kucode.LogRedactor
import dev.koduki.kucode.OtlpJsonSerializer
import dev.koduki.kucode.ParsedApkInfo
import dev.koduki.kucode.PosixRuntimeConfig
import dev.koduki.kucode.RuntimeInstaller
import dev.koduki.kucode.SessionLogStore
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Clean-room spec-derived oracle tests for `self-host-remaining-hardening`.
 *
 * Covers AC-1, AC-2, AC-6, AC-8, AC-9, and AC-10 strictly from
 * `docs/specs/self-host-remaining-hardening/spec.md` and `acceptance.json`.
 */
class SelfHostHardeningOracleTest {

    @After
    fun tearDown() {
        LogRedactor.clearDynamicSecrets()
    }

    private fun deleteRecursivelyNoFollow(target: File) {
        if (!target.exists() && !Files.isSymbolicLink(target.toPath())) return
        if (Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            target.listFiles()?.forEach { deleteRecursivelyNoFollow(it) }
        }
        Files.deleteIfExists(target.toPath())
    }

    private fun sampleEnv(buildSha: String = "deadbeef42") = SessionLogStore.SessionEnvMetadata(
        appVersion = "0.10.0-debug (code=11, targetSdk=35)",
        packageName = "dev.koduki.kucode.debug",
        deviceSummary = "XREAL AURA (test)",
        androidSummary = "15 (API 35)",
        webviewVersion = "com.google.android.webview 157.0.0.0",
        pageSize = "16384B (16KB)",
        xrSpatialSupported = true,
        hasAllFilesAccess = true,
        notificationsEnabled = true,
        batteryOptIgnored = true,
        buildSha = buildSha,
    )

    private fun testPaths(base: File): PosixRuntimeConfig.Paths = PosixRuntimeConfig.Paths(
        runtimeRoot = File(base, "runtime"),
        workspaceDir = File(base, "workspace"),
        homeDir = File(base, "home"),
        nativeLibDir = File(base, "native"),
        androidSharedDir = File(base, "shared"),
    )

    private fun buildTarGz(block: (TarArchiveOutputStream) -> Unit): ByteArray {
        val baos = ByteArrayOutputStream()
        GzipCompressorOutputStream(baos).use { gz ->
            TarArchiveOutputStream(gz).use { tar -> block(tar) }
        }
        return baos.toByteArray()
    }

    private fun addTarFile(tar: TarArchiveOutputStream, name: String, content: String, mode: Int = 0b110_100_100) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val entry = TarArchiveEntry(name)
        entry.size = bytes.size.toLong()
        entry.mode = mode
        tar.putArchiveEntry(entry)
        tar.write(bytes)
        tar.closeArchiveEntry()
    }

    private fun fakeAssets(tag: String): Map<String, ByteArray> = mapOf(
        PosixRuntimeConfig.ASSET_ROOTFS to buildTarGz { tar ->
            addTarFile(tar, "etc/release-tag", tag)
            addTarFile(tar, "bin/bash", "#!/bin/sh\n", mode = 0b111_101_101)
        },
        PosixRuntimeConfig.ASSET_OPENVSCODE to buildTarGz { tar ->
            addTarFile(tar, "openvscode-v1/bin/openvscode-server", "#!/bin/sh\necho $tag\n", mode = 0b111_101_101)
            addTarFile(tar, "openvscode-v1/node", "#!/bin/sh\n", mode = 0b111_101_101)
        },
    )

    @Test
    fun ac1_metricCaptureDurationOtlpGaugeAndMarkdownBuildShaOverhead() {
        // [AC-1] MetricSnapshot.captureDurationMs backward compat, OTLP gauge emission, and AI Markdown Build SHA + overhead summary
        val dir = Files.createTempDirectory("kucode-hardening-ac1").toFile()
        var nowMs = 1_760_000_000_000L
        try {
            // Backward compatibility: MetricSnapshot defaults captureDurationMs to -1L and parses missing field as -1L
            val defaultSnap = SessionLogStore.MetricSnapshot(timestampMs = nowMs, trigger = "default_compat")
            assertEquals(-1L, defaultSnap.captureDurationMs)
            assertEquals(-1L, defaultSnap.toJsonObject().getLong("captureDurationMs"))

            val legacyJson = JSONObject()
                .put("tsMs", nowMs)
                .put("trigger", "legacy")
                .put("pssMb", 120L)
            val parsedLegacy = SessionLogStore.MetricSnapshot.fromJsonObject(legacyJson)
            assertEquals(-1L, parsedLegacy.captureDurationMs)

            val store = SessionLogStore(diagnosticsRootDir = File(dir, "diagnostics"), clockMs = { nowMs })
            val session = store.startSession(pid = 2001, env = sampleEnv(buildSha = "sha987654"))

            nowMs += 200L
            store.recordMetric(
                SessionLogStore.MetricSnapshot(
                    timestampMs = nowMs,
                    trigger = "sample_1",
                    pssMb = 210L,
                    hostCpuTimeMs = 1200L,
                    captureDurationMs = 7L,
                ),
            )
            nowMs += 200L
            store.recordMetric(
                SessionLogStore.MetricSnapshot(
                    timestampMs = nowMs,
                    trigger = "sample_2",
                    pssMb = 220L,
                    hostCpuTimeMs = 1400L,
                    captureDurationMs = 19L,
                ),
            )
            nowMs += 200L
            store.recordMetric(
                SessionLogStore.MetricSnapshot(
                    timestampMs = nowMs,
                    trigger = "sample_unknown",
                    pssMb = 225L,
                    captureDurationMs = -1L,
                ),
            )
            nowMs += 200L
            store.endSession("clean")

            // Markdown report keeps kucode-session-v1 + 14-column header and emits Build SHA + Telemetry Capture Overhead
            val aiReport = store.renderSessionBundle(session.sessionId, "ai")!!
            assertTrue(aiReport.contains("- Schema: `kucode-session-v1`"))
            assertTrue(aiReport.contains("- Build SHA: `sha987654`"))
            assertTrue(
                aiReport.contains("| Offset | Trigger | Avail/Total RAM | PSS | Host CPU ms | JVM Heap | Native | FDs | Thr | DiskFree | Thermal | Bat | Net | State / View / Focus |"),
            )
            assertTrue(
                "AI report must render telemetry capture overhead summary for samples >= 0",
                aiReport.contains("- Telemetry Capture Overhead: `last=19ms, avg=13ms, max=19ms, samples=2`"),
            )

            // OTLP serializer emits kucode.telemetry.capture_duration_ms only for >= 0L points
            val otlp = OtlpJsonSerializer.serializeSession(store, session.sessionId)!!
            val metricsArr = otlp.metricsJson!!
                .getJSONArray("resourceMetrics").getJSONObject(0)
                .getJSONArray("scopeMetrics").getJSONObject(0)
                .getJSONArray("metrics")
            val captureMetric = (0 until metricsArr.length())
                .map { metricsArr.getJSONObject(it) }
                .firstOrNull { it.getString("name") == "kucode.telemetry.capture_duration_ms" }
            assertNotNull("Expected kucode.telemetry.capture_duration_ms metric", captureMetric)
            assertEquals("ms", captureMetric!!.getString("unit"))
            val points = captureMetric.getJSONObject("gauge").getJSONArray("dataPoints")
            assertEquals("Negative captureDurationMs (-1L) must be omitted", 2, points.length())
            assertEquals("7", points.getJSONObject(0).getString("asInt"))
            assertEquals("19", points.getJSONObject(1).getString("asInt"))
            assertTrue(points.getJSONObject(0).get("timeUnixNano") is String)

            // When all snapshots have captureDurationMs < 0L, metric & overhead line are omitted cleanly
            nowMs += 1_000L
            val sNoOverhead = store.startSession(pid = 2002, env = sampleEnv(buildSha = "sha000001"))
            store.recordMetric(
                SessionLogStore.MetricSnapshot(
                    timestampMs = nowMs + 10L,
                    trigger = "legacy_only",
                    pssMb = 100L,
                    captureDurationMs = -1L,
                ),
            )
            store.endSession("clean")

            val noOverheadAi = store.renderSessionBundle(sNoOverhead.sessionId, "ai")!!
            assertTrue(noOverheadAi.contains("- Build SHA: `sha000001`"))
            assertFalse(noOverheadAi.contains("- Telemetry Capture Overhead:"))
            val noOverheadOtlp = OtlpJsonSerializer.serializeSession(store, sNoOverhead.sessionId)!!
            val noOverheadMetrics = noOverheadOtlp.metricsJson!!
                .getJSONArray("resourceMetrics").getJSONObject(0)
                .getJSONArray("scopeMetrics").getJSONObject(0)
                .getJSONArray("metrics")
            assertFalse(
                (0 until noOverheadMetrics.length()).any {
                    noOverheadMetrics.getJSONObject(it).getString("name") == "kucode.telemetry.capture_duration_ms"
                },
            )
        } finally {
            deleteRecursivelyNoFollow(dir)
        }
    }

    @Test
    fun ac2_diagnosticsRunnerPureJvmOverheadAndSymlinkSafeDiskComputation() {
        // [AC-2] DiagnosticsRunner pure-JVM overhead & NOFOLLOW_LINKS disk sizing helpers
        val dir = Files.createTempDirectory("kucode-hardening-ac2").toFile()
        try {
            val diagDir = File(dir, "diagnostics").apply { mkdirs() }
            val outsideFile = File(dir, "outside-huge.bin").apply {
                writeBytes(ByteArray(10_000) { 0x41 })
            }
            val nested = File(diagDir, "sessions/s1").apply { mkdirs() }
            File(nested, "a.json").writeBytes(ByteArray(123) { 0x61 })
            File(nested, "b.log").writeBytes(ByteArray(456) { 0x62 })
            // Symlink inside diagnostics pointing outside must NEVER be traversed or counted
            Files.createSymbolicLink(File(diagDir, "escape-link").toPath(), outsideFile.toPath())
            Files.createSymbolicLink(File(diagDir, "escape-dir-link").toPath(), dir.toPath())

            val computedBytes = DiagnosticsRunner.computeDirectorySizeBytes(diagDir)
            assertEquals(123L + 456L, computedBytes)

            // Missing dir or symlink root returns -1L
            assertEquals(-1L, DiagnosticsRunner.computeDirectorySizeBytes(File(dir, "does-not-exist")))
            val rootLink = File(dir, "root-symlink")
            Files.createSymbolicLink(rootLink.toPath(), diagDir.toPath())
            assertEquals(-1L, DiagnosticsRunner.computeDirectorySizeBytes(rootLink))

            val validOverhead = DiagnosticsRunner.computeDiagnosticsOverhead(
                startElapsedMs = 1_000L,
                endElapsedMs = 1_420L,
                startCpuMs = 500L,
                endCpuMs = 635L,
                startPssMb = 180L,
                endPssMb = 192L,
                diagnosticsDiskBytes = 2048L,
                telemetryLastCaptureDurationMs = 9L,
            )
            assertEquals(420L, validOverhead.diagnosticsDurationMs)
            assertEquals(135L, validOverhead.hostCpuDeltaMs)
            assertEquals(12L, validOverhead.pssDeltaMb)
            assertEquals(2048L, validOverhead.diagnosticsDiskBytes)
            assertEquals(9L, validOverhead.telemetryLastCaptureDurationMs)

            val summaryValid = DiagnosticsRunner.formatOverheadSummary(validOverhead)
            assertTrue(summaryValid.contains("420"))
            assertTrue(summaryValid.contains("135"))
            assertTrue(summaryValid.contains("12"))
            assertFalse("Valid metrics should not render as unknown: $summaryValid", summaryValid.contains("unknown"))

            // Negative / unavailable PSS or CPU or telemetry must clamp duration/cpu and render "unknown" cleanly
            val unknownOverhead = DiagnosticsRunner.computeDiagnosticsOverhead(
                startElapsedMs = 2_000L,
                endElapsedMs = 1_900L,
                startCpuMs = -1L,
                endCpuMs = 100L,
                startPssMb = 180L,
                endPssMb = -1L,
                diagnosticsDiskBytes = -1L,
                telemetryLastCaptureDurationMs = -1L,
            )
            assertEquals(0L, unknownOverhead.diagnosticsDurationMs)
            assertEquals(-1L, unknownOverhead.hostCpuDeltaMs)
            assertNull(unknownOverhead.pssDeltaMb)

            val summaryUnknown = DiagnosticsRunner.formatOverheadSummary(unknownOverhead)
            assertTrue(
                "Unavailable metrics must render literally as 'unknown': $summaryUnknown",
                summaryUnknown.contains("unknown"),
            )
        } finally {
            deleteRecursivelyNoFollow(dir)
        }
    }

    @Test
    fun ac6_posixRuntimeConfigDetectsGuestJavaHomeAndMaintainsProfileDParity() {
        // [AC-6] PosixRuntimeConfig.detectGuestJavaHome symlink confinement, buildProotEnvironment, and buildProfileDEnvScript parity
        val base = Files.createTempDirectory("kucode-hardening-ac6").toFile()
        try {
            val paths = testPaths(base)
            paths.rootfsDir.mkdirs()
            paths.homeDir.mkdirs()

            // 1. No JDK installed -> detectGuestJavaHome returns null, JAVA_HOME omitted
            assertNull(PosixRuntimeConfig.detectGuestJavaHome(paths))
            val envWithoutJava = PosixRuntimeConfig.buildProotEnvironment(paths, includeNodeExtraCaCerts = false)
            assertFalse(envWithoutJava.containsKey("JAVA_HOME"))

            // 2. Escape symlink at /usr/lib/jvm/default-java pointing outside rootfs must be rejected
            val outsideJdk = File(base, "outside-jdk").apply {
                File(this, "bin").mkdirs()
                File(this, "bin/java").writeText("#!/bin/sh\n")
            }
            val jvmDir = File(paths.rootfsDir, "usr/lib/jvm").apply { mkdirs() }
            Files.createSymbolicLink(File(jvmDir, "default-java").toPath(), outsideJdk.toPath())
            assertNull("Symlink escaping rootfs must not be accepted as JAVA_HOME", PosixRuntimeConfig.detectGuestJavaHome(paths))

            // 3. Mise-managed JDK in homeDir/.local/share/mise/installs/java/<ver>/bin/java is detected
            val miseJava = File(paths.homeDir, ".local/share/mise/installs/java/21.0.5")
            File(miseJava, "bin").mkdirs()
            File(miseJava, "bin/java").writeText("#!/bin/sh\n")
            assertEquals("/root/.local/share/mise/installs/java/21.0.5", PosixRuntimeConfig.detectGuestJavaHome(paths))

            // 4. Valid in-rootfs default-java symlink takes priority over mise fallback
            val internalJdk = File(jvmDir, "java-17-openjdk-arm64").apply {
                File(this, "bin").mkdirs()
                File(this, "bin/java").writeText("#!/bin/sh\n")
            }
            Files.delete(File(jvmDir, "default-java").toPath())
            Files.createSymbolicLink(File(jvmDir, "default-java").toPath(), internalJdk.toPath())
            assertEquals("/usr/lib/jvm/default-java", PosixRuntimeConfig.detectGuestJavaHome(paths))

            // 5. buildProotEnvironment sets JAVA_HOME and places $JAVA_HOME/bin after user/SDK dirs and before /usr/local/sbin
            val env = PosixRuntimeConfig.buildProotEnvironment(paths, includeNodeExtraCaCerts = false)
            assertEquals("/usr/lib/jvm/default-java", env["JAVA_HOME"])
            assertEquals("/root/Android/Sdk", env["ANDROID_HOME"])
            assertEquals("/root/Android/Sdk", env["ANDROID_SDK_ROOT"])
            assertEquals("/root/.cache", env["XDG_CACHE_HOME"])
            assertEquals("/root/.config", env["XDG_CONFIG_HOME"])
            assertEquals("/root/.local/share", env["XDG_DATA_HOME"])

            val pathParts = env.getValue("PATH").split(':')
            val javaBinIdx = pathParts.indexOf("/usr/lib/jvm/default-java/bin")
            val sdkPlatformIdx = pathParts.indexOf("/root/Android/Sdk/platform-tools")
            val usrLocalSbinIdx = pathParts.indexOf("/usr/local/sbin")
            val usrLocalBinIdx = pathParts.indexOf("/usr/local/bin")
            assertTrue("JAVA_HOME/bin must be present on PATH", javaBinIdx > 0)
            assertTrue("JAVA_HOME/bin must follow user/SDK dirs", javaBinIdx > sdkPlatformIdx)
            assertTrue("JAVA_HOME/bin must precede /usr/local/sbin", javaBinIdx < usrLocalSbinIdx)
            assertTrue(usrLocalSbinIdx < usrLocalBinIdx)

            // Invalid/blank javaHome argument must be ignored safely
            val blankJavaEnv = PosixRuntimeConfig.buildProotEnvironment(paths, includeNodeExtraCaCerts = false, javaHome = "relative/jdk")
            assertFalse(blankJavaEnv.containsKey("JAVA_HOME"))

            // 6. profile.d script parity for ANDROID_HOME, ANDROID_SDK_ROOT, XDG_*, JAVA_HOME, and PATH
            val profileD = PosixRuntimeConfig.buildProfileDEnvScript()
            assertTrue(profileD.contains("ANDROID_HOME"))
            assertTrue(profileD.contains("ANDROID_SDK_ROOT"))
            assertTrue(profileD.contains("XDG_CACHE_HOME"))
            assertTrue(profileD.contains("XDG_CONFIG_HOME"))
            assertTrue(profileD.contains("XDG_DATA_HOME"))
            assertTrue(profileD.contains("JAVA_HOME"))
            assertTrue(profileD.contains("mise/installs/java"))

            val bashCheck = ProcessBuilder("bash", "-n").redirectErrorStream(true).start()
            bashCheck.outputStream.use { it.write(profileD.toByteArray(Charsets.UTF_8)) }
            val bashOut = bashCheck.inputStream.bufferedReader().readText()
            assertEquals("profile.d failed bash -n: $bashOut", 0, bashCheck.waitFor())
        } finally {
            deleteRecursivelyNoFollow(base)
        }
    }

    @Test
    fun ac8_runtimeInstallerLowSpacePreflightBackupRetentionAndRecovery() {
        // [AC-8] InsufficientStorageException preflight, openvscode.previous recovery, and bounded Debian backup retention
        val base = Files.createTempDirectory("kucode-hardening-ac8").toFile()
        try {
            val paths = testPaths(base)
            val assets = fakeAssets("v1")

            // 1. Fresh install blocked when usable space < minRootfsFreeBytes -> zero partial files or staging dirs left
            val lowRootfsInstaller = RuntimeInstaller(
                openAsset = { name -> ByteArrayInputStream(assets.getValue(name)) },
                expectedVersion = "v1",
                usableSpaceBytes = { 100L * 1024L * 1024L },
                minRootfsFreeBytes = 512L * 1024L * 1024L,
                minOpenvscodeFreeBytes = 512L * 1024L * 1024L,
            )
            val rootfsErr = assertThrows(RuntimeInstaller.InsufficientStorageException::class.java) {
                lowRootfsInstaller.ensureExtracted(paths)
            }
            assertEquals("rootfs", rootfsErr.target)
            assertEquals(512L * 1024L * 1024L, rootfsErr.requiredBytes)
            assertEquals(100L * 1024L * 1024L, rootfsErr.availableBytes)
            assertFalse(paths.rootfsDir.exists())
            assertFalse(paths.stagingDir.exists())
            assertFalse(paths.openvscodeStagingDir.exists())

            // 2. Successful v1 install, seed persistent user files
            val okInstaller = RuntimeInstaller(
                openAsset = { name -> ByteArrayInputStream(assets.getValue(name)) },
                expectedVersion = "v1",
                usableSpaceBytes = { 4L * 1024L * 1024L * 1024L },
            )
            assertEquals(RuntimeInstaller.ExtractOutcome.STAGED_NEW_INSTALL, okInstaller.ensureExtracted(paths))
            okInstaller.commitInstallation(paths)
            val userRootfsMarker = File(paths.rootfsDir, "etc/custom-apt-pkg").apply { writeText("apt-installed") }
            val userHomeMarker = File(paths.homeDir, ".ssh/id_test").apply { parentFile.mkdirs(); writeText("home-keep") }
            val userWorkspaceMarker = File(paths.workspaceDir, "app/main.kt").apply { parentFile.mkdirs(); writeText("ws-keep") }

            // 3. v2 upgrade blocked before openvscode extraction when space is insufficient -> persistent rootfs/home/workspace untouched
            val v2Assets = fakeAssets("v2")
            val lowServerInstaller = RuntimeInstaller(
                openAsset = { name -> ByteArrayInputStream(v2Assets.getValue(name)) },
                expectedVersion = "v2",
                usableSpaceBytes = { 64L * 1024L * 1024L },
                minRootfsFreeBytes = 512L * 1024L * 1024L,
                minOpenvscodeFreeBytes = 256L * 1024L * 1024L,
            )
            val serverErr = assertThrows(RuntimeInstaller.InsufficientStorageException::class.java) {
                lowServerInstaller.ensureExtracted(paths)
            }
            assertEquals("openvscode", serverErr.target)
            assertEquals("apt-installed", userRootfsMarker.readText())
            assertEquals("home-keep", userHomeMarker.readText())
            assertEquals("ws-keep", userWorkspaceMarker.readText())
            assertEquals("v1", paths.openvscodeVersionMarker.readText())
            assertFalse(paths.openvscodeStagingDir.exists())

            // 4. Interrupted openvscode.previous with broken active openvscodeDir recovers previous openvscode cleanly
            paths.backupOpenvscodeDir.mkdirs()
            File(paths.backupOpenvscodeDir, ".kucode-managed-version").writeText("v1")
            File(paths.backupOpenvscodeDir, "bin/openvscode-server").apply { parentFile.mkdirs(); writeText("#!/bin/sh\necho recovered\n") }
            File(paths.openvscodeDir, "bin/openvscode-server").delete()
            File(paths.openvscodeDir, ".kucode-managed-version").delete()
            assertEquals(RuntimeInstaller.ExtractOutcome.ALREADY_INSTALLED, okInstaller.ensureExtracted(paths))
            assertTrue(File(paths.openvscodeDir, "bin/openvscode-server").isFile)
            assertEquals("v1", paths.openvscodeVersionMarker.readText())

            // 5. Bounded Debian backup retention across archiveDebianForReset + restoreLatestDebianBackup
            val retentionInstaller = RuntimeInstaller(
                openAsset = { name -> ByteArrayInputStream(assets.getValue(name)) },
                expectedVersion = "v1",
                usableSpaceBytes = { 4L * 1024L * 1024L * 1024L },
                maxDebianBackups = 3,
            )
            // Create 4 reset backups sequentially with distinct mtimes
            val archivedDirs = mutableListOf<File>()
            repeat(4) { idx ->
                if (!paths.rootfsDir.exists()) {
                    File(paths.rootfsDir, "bin").mkdirs()
                    File(paths.rootfsDir, "bin/bash").writeText("#!/bin/sh\n")
                    File(paths.rootfsDir, ".kucode-runtime-version").writeText("v1")
                }
                File(paths.rootfsDir, "etc/gen").apply { parentFile.mkdirs(); writeText("gen-$idx") }
                val archived = retentionInstaller.archiveDebianForReset(paths, pruneBackups = true)!!
                archived.setLastModified(1_700_000_000_000L + idx * 10_000L)
                archivedDirs.add(archived)
            }
            val backupsAfterArchive = paths.runtimeRoot.listFiles()
                ?.filter { it.name.startsWith("rootfs-backup-") }
                ?.sortedBy { it.lastModified() }
                .orEmpty()
            assertEquals("At most maxDebianBackups=3 backups must remain", 3, backupsAfterArchive.size)
            assertFalse("Oldest backup gen-0 must be pruned", archivedDirs[0].exists())
            assertTrue(archivedDirs[3].exists())
            assertEquals("home-keep", userHomeMarker.readText())
            assertEquals("ws-keep", userWorkspaceMarker.readText())

            // restoreLatestDebianBackup archives current broken rootfs WITHOUT pruning before moving selected backup back
            File(paths.rootfsDir, "bin").mkdirs()
            File(paths.rootfsDir, "bin/bash").writeText("#!/bin/sh\n")
            File(paths.rootfsDir, "etc/gen").apply { parentFile.mkdirs(); writeText("current-to-archive") }
            val restored = retentionInstaller.restoreLatestDebianBackup(paths)
            assertEquals(archivedDirs[3], restored)
            assertEquals("gen-3", File(paths.rootfsDir, "etc/gen").readText())
            val backupsAfterRestore = paths.runtimeRoot.listFiles()
                ?.filter { it.name.startsWith("rootfs-backup-") }
                .orEmpty()
            assertTrue("Bounded retention remains <= 3 after restore", backupsAfterRestore.size <= 3)
            assertTrue(
                "Current rootfs archived during restore must survive pruning",
                backupsAfterRestore.any { File(it, "etc/gen").takeIf(File::isFile)?.readText() == "current-to-archive" },
            )
            assertEquals("home-keep", userHomeMarker.readText())
            assertEquals("ws-keep", userWorkspaceMarker.readText())
        } finally {
            deleteRecursivelyNoFollow(base)
        }
    }

    @Test
    fun ac9_diagnosticShareImporterValidatesCapsRejectsSymlinksAndRedacts() {
        // [AC-9] DiagnosticShareImporter request validation, 1 MiB cap, NOFOLLOW_LINKS, containment, LogRedactor
        assertEquals("application/vnd.kucode.diagnostic", DiagnosticShareImporter.MIME_TYPE)
        assertEquals("diagnostics-import", DiagnosticShareImporter.IMPORT_DIR_NAME)
        assertEquals(1024 * 1024, DiagnosticShareImporter.MAX_IMPORT_BYTES)

        assertTrue(
            DiagnosticShareImporter.isValidShareRequest(
                action = "android.intent.action.SEND",
                mimeType = "application/vnd.kucode.diagnostic",
                uriScheme = "content",
            ),
        )
        assertFalse(DiagnosticShareImporter.isValidShareRequest("android.intent.action.VIEW", DiagnosticShareImporter.MIME_TYPE, "content"))
        assertFalse(DiagnosticShareImporter.isValidShareRequest("android.intent.action.SEND", "text/plain", "content"))
        assertFalse(DiagnosticShareImporter.isValidShareRequest("android.intent.action.SEND", DiagnosticShareImporter.MIME_TYPE, "file"))
        assertFalse(DiagnosticShareImporter.isValidShareRequest("android.intent.action.SEND", DiagnosticShareImporter.MIME_TYPE, "http"))

        val base = Files.createTempDirectory("kucode-hardening-ac9").toFile()
        try {
            val workspace = File(base, "workspace").apply { mkdirs() }
            val outside = File(base, "outside").apply { mkdirs() }

            // 1. Happy path: redacts secrets and writes inside workspace/diagnostics-import/import-<safeId>.md
            val dynSecret = "kucode_share_dyn_secret_123456"
            LogRedactor.registerSecret(dynSecret)
            val ghTok = "ghp_" + "a".repeat(24)
            val payload = "# Shared Report\nAuthorization: Bearer top_secret_bearer_token\nToken: $ghTok\nDyn: $dynSecret\n"
            val result = DiagnosticShareImporter.importFromStream(
                input = ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8)),
                workspaceDir = workspace,
                idGenerator = { "abc/../../evil-id-01" },
            )
            assertTrue(result.file.isFile)
            assertTrue(result.file.canonicalPath.startsWith(workspace.canonicalPath))
            assertTrue(result.file.name.startsWith("import-"))
            assertTrue(result.file.name.endsWith(".md"))
            assertFalse("Path traversal characters in id must be sanitized", result.file.name.contains(".."))
            assertTrue(result.guestPath.startsWith("/workspace/diagnostics-import/import-"))
            val written = result.file.readText(Charsets.UTF_8)
            assertTrue(written.contains("# Shared Report"))
            assertFalse("Bearer token must be redacted", written.contains("top_secret_bearer_token"))
            assertFalse("GitHub token must be redacted", written.contains(ghTok))
            assertFalse("Dynamic secret must be redacted", written.contains(dynSecret))

            // 2. Reject payloads > maxBytes
            val oversized = ByteArray(DiagnosticShareImporter.MAX_IMPORT_BYTES + 1) { 0x41 }
            assertThrows(IllegalArgumentException::class.java) {
                DiagnosticShareImporter.importFromStream(
                    input = ByteArrayInputStream(oversized),
                    workspaceDir = workspace,
                    idGenerator = { "too-big" },
                )
            }
            assertFalse(File(workspace, "diagnostics-import/import-too-big.md").exists())

            // 3. Reject symlink diagnostics-import directory escaping workspace
            val symlinkWorkspace = File(base, "ws-symlink-dir").apply { mkdirs() }
            Files.createSymbolicLink(File(symlinkWorkspace, "diagnostics-import").toPath(), outside.toPath())
            assertThrows(IOException::class.java) {
                DiagnosticShareImporter.importFromStream(
                    input = ByteArrayInputStream("hello".toByteArray()),
                    workspaceDir = symlinkWorkspace,
                    idGenerator = { "escape1" },
                )
            }
            assertEquals(0, outside.listFiles()?.size ?: 0)

            // 4. Reject symlink workspaceDir
            val symlinkRoot = File(base, "ws-root-link")
            Files.createSymbolicLink(symlinkRoot.toPath(), outside.toPath())
            assertThrows(IOException::class.java) {
                DiagnosticShareImporter.importFromStream(
                    input = ByteArrayInputStream("hello".toByteArray()),
                    workspaceDir = symlinkRoot,
                    idGenerator = { "escape2" },
                )
            }
        } finally {
            deleteRecursivelyNoFollow(base)
        }
    }

    @Test
    fun ac10_androidHostBridgeCleanupAbandonedOnStartupClearsSessionsAndStagingDir() {
        // [AC-10] AndroidHostBridgeCore.cleanupAbandonedOnStartup abandons owned sessions and removes staging dir
        val base = Files.createTempDirectory("kucode-hardening-ac10").toFile()
        try {
            val stageDir = File(base, "cache/android-install").apply { mkdirs() }
            val leftoverApk = File(stageDir, "stale-123.apk").apply { writeText("leftover") }
            val nestedStage = File(stageDir, "nested").apply { mkdirs() }
            File(nestedStage, "temp.part").writeText("leftover-nested")

            val abandonCount = AtomicInteger(0)
            val platform = object : AndroidBridgePlatform {
                override val ownPackageName: String = "dev.koduki.kucode.debug"
                override val filesDir: File = base
                override val stageDir: File = stageDir
                override fun canRequestPackageInstalls(): Boolean = true
                override fun areNotificationsEnabled(): Boolean = true
                override fun isInstallChannelEnabled(): Boolean = true
                override fun parseApk(apk: File): ParsedApkInfo? = null
                override fun getInstalledSigners(packageName: String): InstalledPackageSigners? = null
                override fun createSession(packageName: String, sizeBytes: Long): Int = 1
                override fun writeAndCommitSession(sessionId: Int, apk: File, operationId: String) = Unit
                override fun abandonSession(sessionId: Int) = Unit
                override fun abandonOwnedSessions() {
                    abandonCount.incrementAndGet()
                }
                override fun notifyPendingUserAction(sessionId: Int, packageName: String, confirmIntent: Any) = Unit
                override fun cancelNotification(sessionId: Int) = Unit
            }

            val directExecutor = Executor { runnable -> runnable.run() }
            val core = AndroidHostBridgeCore(platform, directExecutor)
            core.cleanupAbandonedOnStartup()

            assertEquals(1, abandonCount.get())
            assertFalse("Staging APK must be removed on startup cleanup", leftoverApk.exists())
            assertFalse("Staging directory must be deleted on startup cleanup", stageDir.exists())
        } finally {
            deleteRecursivelyNoFollow(base)
        }
    }
}
