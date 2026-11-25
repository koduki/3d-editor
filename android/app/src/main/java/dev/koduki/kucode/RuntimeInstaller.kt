package dev.koduki.kucode

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Persistent Debian seed plus separately managed OpenVSCode installation.
 * Never replace an existing rootfs during an APK update. Only managed server
 * directories participate in the READY/rollback transaction.
 */
internal class RuntimeInstaller(
    private val openAsset: (String) -> InputStream,
    private val querySystemDnsServers: () -> List<String> = { emptyList() },
    private val loadHostCaCertificates: () -> List<CaCertificateEntry> = { emptyList() },
    private val expectedVersion: String = PosixRuntimeConfig.RUNTIME_VERSION,
    private val installExtension: (File) -> File = { dir ->
        VsCodeExtensionAsset.installAndroidInto(dir, openAsset)
        VsCodeExtensionAsset.installInto(dir, openAsset)
    },
    private val installHostCli: (File) -> Unit = { dir ->
        val cli = File(dir, "kucode")
        writeBytesAtomically(cli, openAsset("kucode-cli.cjs").use { it.readBytes() }, worldReadable = true)
        TarGzExtractor.DefaultPosixFsOps.chmod(cli, 0b111_101_101)
    },
    private val onProgress: (message: String, progress: Int) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {},
) {
    enum class ExtractOutcome {
        ALREADY_INSTALLED,
        STAGED_NEW_INSTALL,
    }

    fun readInstalledVersion(paths: PosixRuntimeConfig.Paths): String? {
        val marker = paths.versionMarker
        if (!Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return runCatching { marker.readText(Charsets.UTF_8).trim() }.getOrNull()
    }

    private fun exists(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun readManagedVersion(paths: PosixRuntimeConfig.Paths): String? =
        paths.openvscodeVersionMarker.takeIf { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            ?.let { runCatching { it.readText(Charsets.UTF_8).trim() }.getOrNull() }

    fun recoverInterruptedInstall(paths: PosixRuntimeConfig.Paths) {
        // Old versions swapped the entire rootfs. Preserve both trees if the
        // legacy transaction was interrupted; never delete an ambiguous user OS.
        if (exists(paths.backupRootfsDir)) {
            if (!exists(paths.rootfsDir) || readInstalledVersion(paths) == null) {
                if (exists(paths.rootfsDir)) {
                    val recovered = Files.createTempDirectory(paths.runtimeRoot.toPath(), "rootfs-recovered-").toFile()
                    Files.delete(recovered.toPath())
                    moveDirectoryAtomically(paths.rootfsDir, recovered)
                    onLog("[runtime] Preserved interrupted legacy rootfs at ${recovered.name}")
                }
                moveDirectoryAtomically(paths.backupRootfsDir, paths.rootfsDir)
                onLog("[runtime] Restored previous legacy Debian installation.")
            } else {
                onLog("[runtime] Retaining legacy rootfs.previous for manual recovery.")
            }
        }
        if (exists(paths.backupOpenvscodeDir)) {
            if (readManagedVersion(paths) != null && hasServerBinary(paths)) {
                deleteTreeNoFollow(paths.backupOpenvscodeDir)
            } else {
                deleteTreeNoFollow(paths.openvscodeDir)
                moveDirectoryAtomically(paths.backupOpenvscodeDir, paths.openvscodeDir)
                onLog("[runtime] Recovered previous managed OpenVSCode installation.")
            }
        }
    }

    fun ensureExtracted(paths: PosixRuntimeConfig.Paths): ExtractOutcome {
        paths.runtimeRoot.mkdirs()
        recoverInterruptedInstall(paths)
        if (!exists(paths.rootfsDir)) {
            onProgress("初回セットアップ: Debian minimal rootfs を展開中...", 10)
            deleteTreeNoFollow(paths.stagingDir)
            Files.createDirectory(paths.stagingDir.toPath())
            openAsset(PosixRuntimeConfig.ASSET_ROOTFS).use { stream ->
                val stats = TarGzExtractor.extract(stream, paths.stagingDir, stripComponents = 0) { entries, current ->
                    onProgress("Debian rootfs 展開中 ($entries)... $current", 10 + (entries / 250).coerceAtMost(35))
                }
                onLog("[runtime] Debian rootfs extracted: $stats")
            }
            require(File(paths.stagingDir, "bin/bash").isFile) { "Debian seed has no bash" }
            writeTextAtomically(File(paths.stagingDir, paths.versionMarker.name), expectedVersion, Charsets.US_ASCII, worldReadable = true)
            moveDirectoryAtomically(paths.stagingDir, paths.rootfsDir)
        } else {
            require(Files.isDirectory(paths.rootfsDir.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                File(paths.rootfsDir, "bin/bash").isFile) {
                "Existing Debian is incomplete; preserved for recovery. Refusing automatic replacement."
            }
            onLog("[runtime] Keeping persistent Debian (seed=${readInstalledVersion(paths) ?: "unknown"}).")
        }

        // One-time migration uses a same-filesystem rename; no duplicate server
        // archive, and no changes to other files in the Debian installation.
        if (!exists(paths.openvscodeDir) &&
            Files.isDirectory(paths.legacyOpenvscodeDir.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            File(paths.legacyOpenvscodeDir, "bin/openvscode-server").isFile) {
            moveDirectoryAtomically(paths.legacyOpenvscodeDir, paths.openvscodeDir)
            readInstalledVersion(paths)?.let {
                writeTextAtomically(paths.openvscodeVersionMarker, it, Charsets.US_ASCII, worldReadable = true)
            }
            onLog("[runtime] Migrated legacy OpenVSCode to managed storage.")
        }
        if (readManagedVersion(paths) == expectedVersion && hasServerBinary(paths)) {
            return ExtractOutcome.ALREADY_INSTALLED
        }

        onProgress("openvscode-server を展開中...", 50)
        deleteTreeNoFollow(paths.openvscodeStagingDir)
        Files.createDirectory(paths.openvscodeStagingDir.toPath())
        openAsset(PosixRuntimeConfig.ASSET_OPENVSCODE).use { stream ->
            val stats = TarGzExtractor.extract(stream, paths.openvscodeStagingDir, stripComponents = 1) { entries, current ->
                onProgress("openvscode-server 展開中 ($entries)... $current", 50 + (entries / 200).coerceAtMost(35))
            }
            onLog("[runtime] OpenVSCode extracted: $stats")
        }
        require(File(paths.openvscodeStagingDir, "bin/openvscode-server").isFile) { "Invalid managed OpenVSCode archive" }
        if (exists(paths.openvscodeDir)) {
            moveDirectoryAtomically(paths.openvscodeDir, paths.backupOpenvscodeDir)
        }
        moveDirectoryAtomically(paths.openvscodeStagingDir, paths.openvscodeDir)
        onProgress("展開完了。サーバーを起動します...", 88)
        return ExtractOutcome.STAGED_NEW_INSTALL
    }

    fun commitInstallation(paths: PosixRuntimeConfig.Paths) {
        if (readManagedVersion(paths) != expectedVersion) {
            writeTextAtomically(paths.openvscodeVersionMarker, expectedVersion, Charsets.US_ASCII, worldReadable = true)
        }
        deleteTreeNoFollow(paths.backupOpenvscodeDir)
    }

    fun rollbackInstallation(paths: PosixRuntimeConfig.Paths): Boolean {
        if (!exists(paths.backupOpenvscodeDir) || readManagedVersion(paths) == expectedVersion) return false
        deleteTreeNoFollow(paths.openvscodeDir)
        moveDirectoryAtomically(paths.backupOpenvscodeDir, paths.openvscodeDir)
        onLog("[runtime] Rolled back managed OpenVSCode; persistent Debian untouched.")
        return true
    }

    /**
     * Explicit reset never deletes the OS. Caller must stop the runtime and
     * obtain user confirmation; home and workspace remain separate.
     */
    fun archiveDebianForReset(paths: PosixRuntimeConfig.Paths): File? {
        recoverInterruptedInstall(paths)
        if (!exists(paths.rootfsDir)) return null
        val archive = Files.createTempDirectory(paths.runtimeRoot.toPath(), "rootfs-backup-").toFile()
        Files.delete(archive.toPath())
        moveDirectoryAtomically(paths.rootfsDir, archive)
        if (exists(paths.backupRootfsDir)) {
            val legacy = Files.createTempDirectory(paths.runtimeRoot.toPath(), "rootfs-recovered-").toFile()
            Files.delete(legacy.toPath())
            moveDirectoryAtomically(paths.backupRootfsDir, legacy)
        }
        return archive
    }

    fun ensureMountPointDirsExist(paths: PosixRuntimeConfig.Paths) {
        ensureDirectoryWithMode(paths.sysdataDir, 0b111_101_101)
        ensureDirectoryWithMode(paths.shmDir, 0b111_111_111)
        ensureDirectoryWithMode(paths.tmpDir, 0b111_111_111)
        ensureDirectoryWithMode(paths.diagnosticsDir, 0b111_101_101)
        paths.vscodeDataDir.mkdirs()
        paths.workspaceDir.mkdirs()
        paths.homeDir.mkdirs()
        paths.androidSharedDir.mkdirs()

        // Ensure mount target directories exist inside rootfs with POSIX-appropriate modes
        // (Android's default process umask 0077 would otherwise create 0700 directories).
        File(paths.rootfsDir, "workspace").mkdirs()
        File(paths.rootfsDir, PosixRuntimeConfig.GUEST_OPENVSCODE_DIR.trimStart('/')).mkdirs()
        File(paths.rootfsDir, "android/shared").mkdirs()
        File(paths.rootfsDir, "android/sdcard").mkdirs()
        File(paths.rootfsDir, PosixRuntimeConfig.GUEST_VSCODE_DATA.trimStart('/')).mkdirs()
        ensureDirectoryWithMode(
            File(paths.rootfsDir, PosixRuntimeConfig.GUEST_DIAGNOSTICS_DIR.trimStart('/')),
            0b111_101_101,
        )
        ensureDirectoryWithMode(File(paths.rootfsDir, "dev/shm"), 0b111_111_111)
        ensureDirectoryWithMode(File(paths.rootfsDir, "tmp"), 0b111_111_111)
        File(paths.rootfsDir, "root").mkdirs()
        ensureDirectoryWithMode(File(paths.rootfsDir, "etc/ssl"), 0b111_101_101)
        ensureDirectoryWithMode(File(paths.rootfsDir, "etc/ssl/certs"), 0b111_101_101)
        ensureDirectoryWithMode(File(paths.rootfsDir, "usr/local/share/ca-certificates"), 0b111_101_101)
        ensureDirectoryWithMode(File(paths.rootfsDir, "etc/apt/apt.conf.d"), 0b111_101_101)
    }

    fun prepareRuntimeDirectories(
        paths: PosixRuntimeConfig.Paths,
        availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
    ) {
        ensureMountPointDirsExist(paths)

        // Create /workspace/android -> /android symlink for convenient explorer access
        val workspaceAndroidLink = File(paths.workspaceDir, "android")
        runCatching {
            TarGzExtractor.DefaultPosixFsOps.symlink("/android", workspaceAndroidLink)
        }

        // Seed welcome README.md if workspace has no README yet
        val readme = File(paths.workspaceDir, "README.md")
        if (!readme.exists()) {
            writeTextAtomically(readme, PosixRuntimeConfig.buildWelcomeReadme(), Charsets.UTF_8, worldReadable = true)
        }

        // Seed persistent .bashrc, .profile, and ~/.local/bin in decoupled /root
        File(paths.homeDir, ".local/bin").mkdirs()
        val bashrc = File(paths.homeDir, ".bashrc")
        if (!bashrc.exists()) {
            writeTextAtomically(bashrc, PosixRuntimeConfig.buildDefaultBashrc(), Charsets.UTF_8, worldReadable = true)
        }
        val profile = File(paths.homeDir, ".profile")
        if (!profile.exists()) {
            writeTextAtomically(profile, PosixRuntimeConfig.buildDefaultProfile(), Charsets.UTF_8, worldReadable = true)
        }

        // Ensure login shells (/etc/profile) always keep $HOME/.local/bin on PATH
        val profileDDir = File(paths.rootfsDir, "etc/profile.d")
        ensureDirectoryWithMode(profileDDir, 0b111_101_101)
        val profileDEnv = File(profileDDir, "kucode-env.sh")
        if (Files.isSymbolicLink(profileDEnv.toPath()) ||
            Files.isDirectory(profileDEnv.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            deleteTreeNoFollow(profileDEnv)
        }
        writeTextAtomically(
            profileDEnv,
            PosixRuntimeConfig.buildProfileDEnvScript(),
            Charsets.UTF_8,
            worldReadable = true,
        )

        // Write fake /proc files to avoid Android SELinux denials in Node.js / Linux tools
        for ((name, content) in PosixRuntimeConfig.buildFakeSysdataFiles(availableProcessors)) {
            writeTextAtomically(File(paths.sysdataDir, name), content, Charsets.UTF_8, worldReadable = true)
        }

        // Sync active Android DNS servers into /etc/resolv.conf (fallback: 1.1.1.1 / 8.8.8.8)
        val dnsServers = querySystemDnsServers()
        val resolvConf = File(paths.rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        if (Files.isSymbolicLink(resolvConf.toPath()) ||
            Files.isDirectory(resolvConf.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            deleteTreeNoFollow(resolvConf)
        }
        writeTextAtomically(
            resolvConf,
            PosixRuntimeConfig.buildResolvConf(dnsServers),
            Charsets.UTF_8,
            worldReadable = true,
        )

        // Configure apt for PRoot compatibility (avoid _apt setgroups/sandbox drops) and HTTPS CA trust
        val aptProotConf = paths.aptProotConfFile
        aptProotConf.parentFile?.let { ensureDirectoryWithMode(it, 0b111_101_101) }
        if (Files.isSymbolicLink(aptProotConf.toPath()) ||
            Files.isDirectory(aptProotConf.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            deleteTreeNoFollow(aptProotConf)
        }
        writeTextAtomically(
            aptProotConf,
            PosixRuntimeConfig.buildAptProotConf(),
            Charsets.UTF_8,
            worldReadable = true,
        )

        // Sync Android trusted CA certificates (Conscrypt APEX system roots + user CAs) into Debian
        CaTrustSync.syncIntoRootfs(paths, loadHostCaCertificates(), onLog)

        // Install CLI host helpers (`kucode-copy`, `kucode-paste`, `kucode-open`, `xdg-open`, `kucode-logs`, `kucode-install-copilot`)
        val localBinDir = File(paths.rootfsDir, "usr/local/bin")
        ensureDirectoryWithMode(localBinDir, 0b111_101_101)
        for ((name, script) in PosixRuntimeConfig.buildHostCliHelperScripts()) {
            val target = File(localBinDir, name)
            if (Files.isSymbolicLink(target.toPath()) ||
                Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                deleteTreeNoFollow(target)
            }
            writeTextAtomically(target, script, Charsets.UTF_8, worldReadable = true)
            TarGzExtractor.DefaultPosixFsOps.chmod(target, 0b111_101_101)
        }

        installHostCli(localBinDir)

        // Expose bundled Node.js v22 at /usr/local/bin/node unless the user replaced it with a regular binary
        if (paths.openvscodeDir.exists()) {
            val nodeLink = File(localBinDir, "node")
            if (!Files.exists(nodeLink.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(nodeLink.toPath())
            ) {
                runCatching {
                    TarGzExtractor.DefaultPosixFsOps.symlink("${PosixRuntimeConfig.GUEST_OPENVSCODE_DIR}/node", nodeLink)
                }
            }
        }

        // Always sync the built-in extension (`kucode-tools`) so upgrades take effect
        // immediately without re-extracting the entire rootfs/openvscode-server archive
        if (paths.openvscodeDir.exists()) {
            val extDir = installExtension(paths.openvscodeDir)
            onLog("[runtime] Synced built-in extension: ${extDir.absolutePath}")
        }
    }

    /**
     * Seeds or merges spatial defaults ONLY into `user/Machine/settings.json` (matching
     * `--user-data-dir /var/lib/kucode-vscode/user`, never touching `user/User/settings.json`).
     * Migrates settings once from legacy `data/Machine/settings.json` if `user/Machine/settings.json`
     * does not exist yet. If an existing file is non-strict JSON / JSONC or unreadable,
     * it is preserved untouched; otherwise updates are replaced atomically.
     */
    fun ensureMachineVsCodeSettings(paths: PosixRuntimeConfig.Paths) {
        val target = paths.machineSettingsFile
        val legacy = paths.legacyMachineSettingsFile
        val targetExists = Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
        val sourceFile = when {
            targetExists -> target
            Files.isRegularFile(legacy.toPath(), LinkOption.NOFOLLOW_LINKS) -> legacy
            else -> null
        }

        val existing = if (sourceFile != null) {
            try {
                sourceFile.readText(Charsets.UTF_8)
            } catch (e: IOException) {
                onLog("[runtime] Skipped updating Machine settings.json (read error: ${e.message})")
                return
            }
        } else {
            null
        }

        // v0.9.x disabled Workspace Trust in the generated Machine defaults.
        // Change only this app-managed setting; user/User/settings.json is untouched.
        val migrated = existing?.let { text ->
            if (text.isNotBlank() && PosixRuntimeConfig.mergeDefaultVsCodeSettings(text) != null) {
                val obj = org.json.JSONObject(text)
                if (!obj.optBoolean("security.workspace.trust.enabled", true))
                    obj.put("security.workspace.trust.enabled", true).toString()
                else text
            } else text
        }
        val merged = PosixRuntimeConfig.mergeDefaultVsCodeSettings(migrated)
        if (merged == null) {
            if (!targetExists && existing != null) {
                // Preserve legacy JSONC settings verbatim when migrating to user/Machine/settings.json
                writeTextAtomically(target, existing, Charsets.UTF_8)
                onLog("[runtime] Migrated legacy JSONC Machine settings.json to ${target.name}")
            } else {
                onLog("[runtime] Preserving existing Machine settings.json (could not parse as strict JSON / JSONC)")
            }
            return
        }
        if (!targetExists || merged != existing) {
            writeTextAtomically(target, merged, Charsets.UTF_8)
        }
    }

    private fun hasServerBinary(paths: PosixRuntimeConfig.Paths): Boolean =
        File(paths.openvscodeDir, "bin/openvscode-server").exists()

    private fun moveDirectoryAtomically(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }
}
