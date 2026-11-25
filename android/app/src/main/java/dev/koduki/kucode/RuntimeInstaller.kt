package dev.koduki.kucode

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Pure-JVM installer for the bundled Debian rootfs + openvscode-server archives.
 *
 * Atomic install & rollback invariants:
 * - The version marker lives INSIDE `rootfs/.kucode-runtime-version` so moving `rootfs`
 *   to `rootfs.previous` preserves the previous installation's version metadata intact.
 * - New extractions are staged without a version marker and only committed via
 *   [commitInstallation] once openvscode-server reaches READY, which writes the marker
 *   and immediately removes `rootfs.previous`.
 * - If startup fails before READY (or crashes mid-swap), [rollbackInstallation] /
 *   [recoverInterruptedInstall] restores `rootfs.previous` back to `rootfs` with its
 *   original version marker intact.
 * - Default settings are written ONLY to `user/Machine/settings.json` (migrating once from
 *   legacy `data/Machine/settings.json`, never touching `user/User/settings.json`), atomically,
 *   and never overwrite unparseable JSONC files.
 */
internal class RuntimeInstaller(
    private val openAsset: (String) -> InputStream,
    private val querySystemDnsServers: () -> List<String> = { emptyList() },
    private val loadHostCaCertificates: () -> List<CaCertificateEntry> = { emptyList() },
    private val expectedVersion: String = PosixRuntimeConfig.RUNTIME_VERSION,
    private val installExtension: (File) -> File = { dir -> VsCodeExtensionAsset.installInto(dir, openAsset) },
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

    fun recoverInterruptedInstall(paths: PosixRuntimeConfig.Paths) {
        val backup = paths.backupRootfsDir
        if (!Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) return

        if (paths.rootfsDir.exists() && readInstalledVersion(paths) == expectedVersion) {
            deleteTreeNoFollow(backup)
            onLog("[runtime] Cleaned up old rootfs backup after completed upgrade.")
        } else {
            deleteTreeNoFollow(paths.rootfsDir)
            moveDirectoryAtomically(backup, paths.rootfsDir)
            onLog("[runtime] Recovered previous rootfs installation (${readInstalledVersion(paths) ?: "unknown"}).")
        }
    }

    fun ensureExtracted(paths: PosixRuntimeConfig.Paths): ExtractOutcome {
        recoverInterruptedInstall(paths)

        val installed = readInstalledVersion(paths)
        if (installed == expectedVersion && hasServerBinary(paths)) {
            onLog("[runtime] Existing Debian + openvscode-server installation verified ($installed)")
            return ExtractOutcome.ALREADY_INSTALLED
        }

        onProgress("初回セットアップ: Debian minimal rootfs を展開中...", 10)
        onLog("[runtime] Extracting embedded Debian minimal rootfs...")

        paths.runtimeRoot.mkdirs()
        deleteTreeNoFollow(paths.stagingDir)
        Files.createDirectory(paths.stagingDir.toPath())

        openAsset(PosixRuntimeConfig.ASSET_ROOTFS).use { stream ->
            val stats = TarGzExtractor.extract(
                input = stream,
                destDir = paths.stagingDir,
                stripComponents = 0,
            ) { entries, current ->
                val pct = 10 + (entries / 250).coerceAtMost(35)
                onProgress("Debian rootfs 展開中 ($entries)... $current", pct)
            }
            onLog("[runtime] Debian rootfs extracted: $stats")
        }

        onProgress("初回セットアップ: openvscode-server を展開中...", 50)
        onLog("[runtime] Extracting embedded openvscode-server...")
        val stagingVscode = TarGzExtractor.resolveSafeChild(paths.stagingDir.canonicalFile, "opt/openvscode-server")
        openAsset(PosixRuntimeConfig.ASSET_OPENVSCODE).use { stream ->
            val stats = TarGzExtractor.extract(
                input = stream,
                destDir = stagingVscode,
                stripComponents = 1,
            ) { entries, current ->
                val pct = 50 + (entries / 200).coerceAtMost(35)
                onProgress("openvscode-server 展開中 ($entries)... $current", pct)
            }
            onLog("[runtime] openvscode-server extracted: $stats")
        }

        // Preserve any previous rootfs (including its internal .kucode-runtime-version marker)
        // in rootfs.previous until openvscode-server reaches READY.
        if (Files.exists(paths.rootfsDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            deleteTreeNoFollow(paths.backupRootfsDir)
            moveDirectoryAtomically(paths.rootfsDir, paths.backupRootfsDir)
        }
        moveDirectoryAtomically(paths.stagingDir, paths.rootfsDir)

        onProgress("展開完了。サーバーを起動します...", 88)
        onLog("[runtime] Extraction staged cleanly; awaiting server READY to commit.")
        return ExtractOutcome.STAGED_NEW_INSTALL
    }

    /**
     * Commits the active rootfs once openvscode-server is READY by writing the version marker
     * inside `rootfs/` and immediately removing any previous rootfs backup.
     */
    fun commitInstallation(paths: PosixRuntimeConfig.Paths) {
        if (readInstalledVersion(paths) != expectedVersion) {
            writeTextAtomically(paths.versionMarker, expectedVersion, Charsets.US_ASCII, worldReadable = true)
        }
        if (Files.exists(paths.backupRootfsDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            deleteTreeNoFollow(paths.backupRootfsDir)
            onLog("[runtime] Removed previous rootfs backup after READY.")
        }
    }

    /**
     * Restores `rootfs.previous` over an uncommitted `rootfs` when startup fails before READY.
     */
    fun rollbackInstallation(paths: PosixRuntimeConfig.Paths): Boolean {
        val backup = paths.backupRootfsDir
        if (!Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        if (readInstalledVersion(paths) == expectedVersion) return false

        deleteTreeNoFollow(paths.rootfsDir)
        moveDirectoryAtomically(backup, paths.rootfsDir)
        onLog("[runtime] Rolled back failed upgrade to previous rootfs (${readInstalledVersion(paths) ?: "unknown"}).")
        return true
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

        val merged = PosixRuntimeConfig.mergeDefaultVsCodeSettings(existing)
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
