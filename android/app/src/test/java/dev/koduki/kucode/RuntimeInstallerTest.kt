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
    fun apkUpgradeAndRollbackPreserveDebianHomeAndWorkspace() {
        val base = Files.createTempDirectory("xr-installer-test").toFile()
        try {
            val paths = testPaths(base)
            fun installer(tag: String) = RuntimeInstaller(
                openAsset = { name -> ByteArrayInputStream(fakeAssets(tag).getValue(name)) },
                expectedVersion = tag,
            )
            val v1 = installer("v1")
            assertEquals(RuntimeInstaller.ExtractOutcome.STAGED_NEW_INSTALL, v1.ensureExtracted(paths))
            assertEquals("v1", paths.versionMarker.readText())
            assertFalse(paths.openvscodeVersionMarker.exists())
            v1.commitInstallation(paths)
            assertEquals("v1", paths.openvscodeVersionMarker.readText())
            val packageFile = File(paths.rootfsDir, "usr/local/lib/custom-package").apply {
                parentFile.mkdirs()
                writeText("installed by apt")
            }
            val sdk = File(paths.homeDir, ".sdk/cache").apply { parentFile.mkdirs(); writeText("sdk") }
            val project = File(paths.workspaceDir, "source").apply { parentFile.mkdirs(); writeText("project") }
            val v2 = installer("v2")
            assertEquals(RuntimeInstaller.ExtractOutcome.STAGED_NEW_INSTALL, v2.ensureExtracted(paths))
            assertEquals("v1", paths.versionMarker.readText())
            assertTrue(paths.backupOpenvscodeDir.exists())
            assertTrue(v2.rollbackInstallation(paths))
            assertEquals("v1", paths.openvscodeVersionMarker.readText())
            assertEquals(RuntimeInstaller.ExtractOutcome.STAGED_NEW_INSTALL, v2.ensureExtracted(paths))
            v2.commitInstallation(paths)
            assertEquals("v2", paths.openvscodeVersionMarker.readText())
            assertEquals("v1", paths.versionMarker.readText())
            assertEquals("installed by apt", packageFile.readText())
            assertEquals("sdk", sdk.readText())
            assertEquals("project", project.readText())
            assertFalse(paths.backupOpenvscodeDir.exists())
            assertEquals(RuntimeInstaller.ExtractOutcome.ALREADY_INSTALLED, v2.ensureExtracted(paths))
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    fun interruptedLegacyUpgradePreservesBothTreesAndMigratesServer() {
        val base = Files.createTempDirectory("xr-recover-test").toFile()
        try {
            val paths = testPaths(base)
            paths.backupRootfsDir.mkdirs()
            File(paths.backupRootfsDir, ".kucode-runtime-version").writeText("v1")
            File(paths.backupRootfsDir, "bin/bash").apply { parentFile.mkdirs(); writeText("#!/bin/sh") }
            File(paths.backupRootfsDir, "opt/openvscode-server/bin/openvscode-server").apply {
                parentFile.mkdirs()
                writeText("#!/bin/sh")
            }
            paths.rootfsDir.mkdirs()
            File(paths.rootfsDir, "partial").writeText("preserve me")
            val installer = RuntimeInstaller(openAsset = { throw AssertionError("unexpected asset") }, expectedVersion = "v1")
            assertEquals(RuntimeInstaller.ExtractOutcome.ALREADY_INSTALLED, installer.ensureExtracted(paths))
            assertTrue(paths.openvscodeDir.resolve("bin/openvscode-server").isFile)
            assertEquals("v1", paths.versionMarker.readText())
            assertEquals("preserve me", paths.runtimeRoot.listFiles()!!.single { it.name.startsWith("rootfs-recovered-") }.resolve("partial").readText())
            val archived = installer.archiveDebianForReset(paths)!!
            assertEquals("v1", archived.resolve(".kucode-runtime-version").readText())
            assertFalse(paths.rootfsDir.exists())
            assertTrue(paths.openvscodeDir.exists())
            assertEquals(archived, installer.restoreLatestDebianBackup(paths))
            assertEquals("v1", paths.versionMarker.readText())
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    fun failedServerExtractionDoesNotModifyExistingDebian() {
        val base = Files.createTempDirectory("xr-failure-test").toFile()
        try {
            val paths = testPaths(base)
            val assets = fakeAssets("v1")
            val initial = RuntimeInstaller(openAsset = { ByteArrayInputStream(assets.getValue(it)) }, expectedVersion = "v1")
            initial.ensureExtracted(paths)
            initial.commitInstallation(paths)
            val sentinel = File(paths.rootfsDir, "apt-state").apply { writeText("keep") }
            val failing = RuntimeInstaller(openAsset = { throw java.io.IOException("simulated unavailable storage") }, expectedVersion = "v2")
            try {
                failing.ensureExtracted(paths)
                throw AssertionError("expected extraction failure")
            } catch (_: java.io.IOException) { }
            assertEquals("keep", sentinel.readText())
            assertEquals("v1", paths.openvscodeVersionMarker.readText())
            assertFalse("Failed server extraction must clean staging dir", paths.openvscodeStagingDir.exists())
            assertFalse("No Debian backup exists before reset", initial.hasDebianBackup(paths))
            initial.archiveDebianForReset(paths)
            assertTrue("Debian backup should be detected after reset", initial.hasDebianBackup(paths))
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

            // 1. Fresh install creates ONLY user/Machine/settings.json, never user/User/settings.json
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
            paths.machineSettingsFile.writeText("""{"editor.fontSize": 24, "custom.key": "keep", "security.workspace.trust.enabled": false}""")
            installer.ensureMachineVsCodeSettings(paths)
            val rawMigrated = paths.machineSettingsFile.readText()
            assertTrue("Migrated JSON should stay multi-line formatted", rawMigrated.contains("\n") && rawMigrated.endsWith("\n"))
            val merged = JSONObject(rawMigrated)
            assertEquals(24, merged.getInt("editor.fontSize"))
            assertEquals("keep", merged.getString("custom.key"))
            assertEquals(true, merged.getBoolean("security.workspace.trust.enabled"))
            assertEquals("off", merged.getString("terminal.integrated.gpuAcceleration"))
            assertEquals(true, merged.getBoolean("chat.disableAIFeatures"))
            assertEquals(userJsonc, userSettings.readText())
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    fun ensureMachineVsCodeSettingsMigratesLegacyDataMachineSettingsIncludingJsonc() {
        val base = Files.createTempDirectory("xr-settings-migrate").toFile()
        try {
            val paths = testPaths(base)
            val installer = RuntimeInstaller(openAsset = { throw AssertionError("unexpected asset open") })

            // Case 1: Legacy strict JSON migrates and merges missing defaults into user/Machine/settings.json
            paths.legacyMachineSettingsFile.parentFile!!.mkdirs()
            paths.legacyMachineSettingsFile.writeText("""{"editor.fontSize": 21, "custom.migrated": true}""")
            installer.ensureMachineVsCodeSettings(paths)
            assertTrue(paths.machineSettingsFile.isFile)
            val merged = JSONObject(paths.machineSettingsFile.readText())
            assertEquals(21, merged.getInt("editor.fontSize"))
            assertEquals(true, merged.getBoolean("custom.migrated"))
            assertEquals(true, merged.getBoolean("chat.disableAIFeatures"))

            // Case 2: Legacy JSONC migrates verbatim when user/Machine/settings.json does not exist
            paths.machineSettingsFile.delete()
            val legacyJsonc = "{\n  // legacy comment\n  \"editor.fontSize\": 18,\n}\n"
            paths.legacyMachineSettingsFile.writeText(legacyJsonc)
            installer.ensureMachineVsCodeSettings(paths)
            assertEquals(legacyJsonc, paths.machineSettingsFile.readText())
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    fun prepareRuntimeDirectories_syncsWorldReadableAptConfResolvConfCaBundleAndDirModes() {
        val base = Files.createTempDirectory("xr-prepare-test").toFile()
        try {
            val paths = testPaths(base)
            // Seed bundled openvscode node binary so prepareRuntimeDirectories links /usr/local/bin/node
            paths.openvscodeDir.mkdirs()
            File(paths.openvscodeDir, "node").writeText("#!/bin/sh\n")

            val caEntries = listOf(
                CaCertificateEntry("system:root.0", byteArrayOf(0x30, 0x01, 0x02), isUserAdded = false),
                CaCertificateEntry("user:corp.0", byteArrayOf(0x30, 0x03, 0x04), isUserAdded = true),
            )
            val installer = RuntimeInstaller(
                openAsset = { throw AssertionError("unexpected asset open") },
                querySystemDnsServers = { listOf("103.5.140.7", "103.5.140.8") },
                loadHostCaCertificates = { caEntries },
                installExtension = { dir -> File(dir, "extensions/kucode-tools").apply { mkdirs() } },
                installHostCli = {},
            )

            installer.prepareRuntimeDirectories(paths, availableProcessors = 4)

            val resolv = File(paths.rootfsDir, "etc/resolv.conf")
            assertTrue(resolv.isFile)
            assertEquals("nameserver 103.5.140.7\nnameserver 103.5.140.8\n", resolv.readText())
            assertTrue(
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ in Files.getPosixFilePermissions(resolv.toPath()),
            )

            val aptConf = paths.aptProotConfFile
            assertTrue(aptConf.isFile)
            assertTrue(aptConf.readText().contains("""APT::Sandbox::User "root";"""))
            assertTrue(aptConf.readText().contains("""Acquire::https::CaInfo "/etc/ssl/certs/ca-certificates.crt";"""))
            assertTrue(
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ in Files.getPosixFilePermissions(aptConf.toPath()),
            )

            assertTrue(paths.guestCaBundleHostFile.isFile)
            assertEquals(2, CaTrustSync.countCertificatesInPem(paths.guestCaBundleHostFile.readText()))
            assertTrue(
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ in
                    Files.getPosixFilePermissions(paths.guestCaBundleHostFile.toPath()),
            )
            assertTrue(paths.androidUserCaBundleFile.isFile)

            assertTrue(
                java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE in
                    Files.getPosixFilePermissions(paths.tmpDir.toPath()),
            )
            assertTrue(
                java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE in
                    Files.getPosixFilePermissions(paths.shmDir.toPath()),
            )

            // Confirm CLI host helpers (including kucode-install-agy and kucode-install-copilot) are installed in /usr/local/bin with 0755 permissions
            for (helper in listOf("kucode-copy", "kucode-paste", "kucode-open", "xdg-open", "kucode-logs", "kucode-install-agy", "kucode-install-copilot")) {
                val scriptFile = File(paths.rootfsDir, "usr/local/bin/$helper")
                assertTrue("$helper should be installed", scriptFile.isFile)
                val perms = Files.getPosixFilePermissions(scriptFile.toPath())
                assertTrue(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE in perms)
                assertTrue(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE in perms)
            }
            // Confirm bundled Node 22 symlink and login shell profile scripts
            val nodeLink = File(paths.rootfsDir, "usr/local/bin/node").toPath()
            assertTrue(Files.isSymbolicLink(nodeLink))
            assertEquals("/opt/openvscode-server/node", Files.readSymbolicLink(nodeLink).toString())
            assertTrue(File(paths.homeDir, ".local/bin").isDirectory)
            assertTrue(File(paths.homeDir, ".profile").isFile)
            assertTrue(File(paths.rootfsDir, "etc/profile.d/kucode-env.sh").isFile)
            assertTrue(File(paths.rootfsDir, "var/log/kucode").isDirectory)

            // Confirm secret atomic writes remain owner-only (0600) by default
            val secret = File(paths.vscodeDataDir, ".editor-token")
            writeTextAtomically(secret, "secret-token", Charsets.US_ASCII)
            val secretPerms = Files.getPosixFilePermissions(secret.toPath())
            assertFalse(java.nio.file.attribute.PosixFilePermission.GROUP_READ in secretPerms)
            assertFalse(java.nio.file.attribute.PosixFilePermission.OTHERS_READ in secretPerms)
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
