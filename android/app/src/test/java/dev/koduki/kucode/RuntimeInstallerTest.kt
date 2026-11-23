package dev.koduki.kucode

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class RuntimeInstallerTest {

    @Test
    fun extractCommitAndRollbackPreserveVersionInsideRootfsAndCleanBackupImmediatelyOnReady() {
        val base = Files.createTempDirectory("xr-installer-test").toFile()
        try {
            val paths = testPaths(base)
            val v1Assets = fakeAssets("v1")
            val installerV1 = RuntimeInstaller(
                openAsset = { name -> ByteArrayInputStream(v1Assets.getValue(name)) },
                expectedVersion = "v1",
                installExtension = { dir -> File(dir, "extensions/kucode-tools").apply { mkdirs() } },
            )

            // 1. Fresh install stages without writing version marker yet
            assertEquals(RuntimeInstaller.ExtractOutcome.STAGED_NEW_INSTALL, installerV1.ensureExtracted(paths))
            assertFalse(paths.versionMarker.exists())
            assertEquals("v1", File(paths.rootfsDir, "etc/release-tag").readText())

            // 2. Commit after READY writes marker inside rootfs and leaves no backup
            installerV1.commitInstallation(paths)
            assertEquals("v1", paths.versionMarker.readText())
            assertTrue(paths.versionMarker.toPath().startsWith(paths.rootfsDir.toPath()))
            assertFalse(paths.backupRootfsDir.exists())

            // 3. Re-running v1 verifies marker and skips re-extraction
            assertEquals(RuntimeInstaller.ExtractOutcome.ALREADY_INSTALLED, installerV1.ensureExtracted(paths))

            // 4. Upgrade to v2 stages v2 and moves intact v1 (with its marker!) to rootfs.previous
            val v2Assets = fakeAssets("v2")
            val installerV2 = RuntimeInstaller(
                openAsset = { name -> ByteArrayInputStream(v2Assets.getValue(name)) },
                expectedVersion = "v2",
                installExtension = { dir -> File(dir, "extensions/kucode-tools").apply { mkdirs() } },
            )
            assertEquals(RuntimeInstaller.ExtractOutcome.STAGED_NEW_INSTALL, installerV2.ensureExtracted(paths))
            assertEquals("v2", File(paths.rootfsDir, "etc/release-tag").readText())
            assertFalse(paths.versionMarker.exists())
            assertEquals("v1", File(paths.backupRootfsDir, ".kucode-runtime-version").readText())

            // 5. Simulated startup failure before READY rolls back to v1 WITH v1's version marker intact
            assertTrue(installerV2.rollbackInstallation(paths))
            assertFalse(paths.backupRootfsDir.exists())
            assertEquals("v1", File(paths.rootfsDir, "etc/release-tag").readText())
            assertEquals("v1", paths.versionMarker.readText())

            // Subsequent boot on v1 sees the preserved marker and does NOT re-extract!
            assertEquals(RuntimeInstaller.ExtractOutcome.ALREADY_INSTALLED, installerV1.ensureExtracted(paths))

            // 6. Successful upgrade to v2 commits marker inside rootfs and immediately removes rootfs.previous
            assertEquals(RuntimeInstaller.ExtractOutcome.STAGED_NEW_INSTALL, installerV2.ensureExtracted(paths))
            assertTrue(paths.backupRootfsDir.exists())
            installerV2.commitInstallation(paths)
            assertEquals("v2", paths.versionMarker.readText())
            assertFalse("Old rootfs backup must be removed immediately upon READY commit", paths.backupRootfsDir.exists())
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    fun recoverInterruptedInstallRestoresUncommittedBackupAndCleansCommittedBackup() {
        val base = Files.createTempDirectory("xr-recover-test").toFile()
        try {
            val paths = testPaths(base)
            val v1Assets = fakeAssets("v1")
            val installerV1 = RuntimeInstaller(
                openAsset = { name -> ByteArrayInputStream(v1Assets.getValue(name)) },
                expectedVersion = "v1",
                installExtension = { dir -> File(dir, "extensions/kucode-tools").apply { mkdirs() } },
            )

            // Simulate crash before READY: rootfs.previous has valid v1, rootfs has uncommitted partial v2
            paths.backupRootfsDir.mkdirs()
            File(paths.backupRootfsDir, ".kucode-runtime-version").writeText("v1")
            File(paths.backupRootfsDir, "opt/openvscode-server/bin").mkdirs()
            File(paths.backupRootfsDir, "opt/openvscode-server/bin/openvscode-server").writeText("#!/bin/sh\n")
            paths.rootfsDir.mkdirs()
            File(paths.rootfsDir, "broken").writeText("partial")

            // Recovery rolls back to v1 and sees v1 already installed (no re-extract!)
            assertEquals(RuntimeInstaller.ExtractOutcome.ALREADY_INSTALLED, installerV1.ensureExtracted(paths))
            assertFalse(paths.backupRootfsDir.exists())
            assertFalse(File(paths.rootfsDir, "broken").exists())
            assertEquals("v1", paths.versionMarker.readText())

            // Simulate crash right after writing v1 marker before deleting backup
            paths.backupRootfsDir.mkdirs()
            File(paths.backupRootfsDir, "stale").writeText("old")
            assertEquals(RuntimeInstaller.ExtractOutcome.ALREADY_INSTALLED, installerV1.ensureExtracted(paths))
            assertFalse(paths.backupRootfsDir.exists())
            assertEquals("v1", paths.versionMarker.readText())
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    fun ensureMachineVsCodeSettingsWritesOnlyMachineFileAndPreservesJsonc() {
        val base = Files.createTempDirectory("xr-settings-test").toFile()
        try {
            val paths = testPaths(base)
            val installer = RuntimeInstaller(
                openAsset = { throw AssertionError("unexpected asset open") },
            )
            val userSettings = File(paths.vscodeDataDir, "user/User/settings.json")

            // 1. Fresh install creates ONLY data/Machine/settings.json, never user/User/settings.json
            installer.ensureMachineVsCodeSettings(paths)
            assertTrue(paths.machineSettingsFile.isFile)
            assertFalse(userSettings.exists())
            assertEquals(16, JSONObject(paths.machineSettingsFile.readText()).getInt("editor.fontSize"))
            assertTrue(paths.machineSettingsFile.parentFile!!.listFiles()!!.none { it.name.startsWith(".pending-") })

            // 2. Existing user settings with JSONC trailing comma remain completely untouched
            userSettings.parentFile!!.mkdirs()
            val userJsonc = "{\n  // user note\n  \"editor.fontSize\": 22,\n}\n"
            userSettings.writeText(userJsonc)

            // 3. Machine settings with JSONC trailing comma are NOT overwritten!
            val machineJsonc = "{\n  // custom machine override\n  \"editor.fontSize\": 19,\n}\n"
            paths.machineSettingsFile.writeText(machineJsonc)

            installer.ensureMachineVsCodeSettings(paths)
            assertEquals(machineJsonc, paths.machineSettingsFile.readText())
            assertEquals(userJsonc, userSettings.readText())

            // 4. Strict JSON missing defaults gets merged atomically without overwriting custom keys
            paths.machineSettingsFile.writeText("""{"editor.fontSize": 24, "custom.key": "keep"}""")
            installer.ensureMachineVsCodeSettings(paths)
            val merged = JSONObject(paths.machineSettingsFile.readText())
            assertEquals(24, merged.getInt("editor.fontSize"))
            assertEquals("keep", merged.getString("custom.key"))
            assertEquals("off", merged.getString("terminal.integrated.gpuAcceleration"))
            assertEquals(userJsonc, userSettings.readText())
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    private fun testPaths(base: File): PosixRuntimeConfig.Paths = PosixRuntimeConfig.Paths(
        runtimeRoot = File(base, "runtime"),
        workspaceDir = File(base, "workspace"),
        homeDir = File(base, "home"),
        nativeLibDir = File(base, "native"),
        androidSharedDir = File(base, "shared"),
    )

    private fun fakeAssets(tag: String): Map<String, ByteArray> = mapOf(
        PosixRuntimeConfig.ASSET_ROOTFS to buildTarGz { tar ->
            addFile(tar, "etc/release-tag", tag)
            addFile(tar, "bin/bash", "#!/bin/sh\n", mode = 0b111_101_101)
        },
        PosixRuntimeConfig.ASSET_OPENVSCODE to buildTarGz { tar ->
            addFile(tar, "openvscode-v1/bin/openvscode-server", "#!/bin/sh\necho $tag\n", mode = 0b111_101_101)
            addFile(tar, "openvscode-v1/node", "#!/bin/sh\n", mode = 0b111_101_101)
        },
    )

    private fun addFile(tar: TarArchiveOutputStream, name: String, content: String, mode: Int = 0b110_100_100) {
        val bytes = content.toByteArray()
        val entry = TarArchiveEntry(name)
        entry.size = bytes.size.toLong()
        entry.mode = mode
        tar.putArchiveEntry(entry)
        tar.write(bytes)
        tar.closeArchiveEntry()
    }

    private fun buildTarGz(block: (TarArchiveOutputStream) -> Unit): ByteArray {
        val baos = ByteArrayOutputStream()
        GzipCompressorOutputStream(baos).use { gz ->
            TarArchiveOutputStream(gz).use { tar -> block(tar) }
        }
        return baos.toByteArray()
    }
}
