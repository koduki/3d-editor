package dev.koduki.kucode

import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Installs the bundled built-in extension (`kucode-tools`) from Android assets
 * (`assets/kucode-tools/{icon.svg,package.json,extension.js}`) into
 * `/opt/openvscode-server/extensions/kucode-tools/`.
 */
object VsCodeExtensionAsset {

    const val EXTENSION_DIR_NAME = "kucode-tools"

    val ASSET_FILES: List<String> = listOf(
        "icon.svg",
        "package.json",
        "extension.js",
        "acp-client.js",
        "acp-webview.js",
    )

    fun installInto(
        openVsCodeDir: File,
        openAsset: (String) -> InputStream,
    ): File = installManagedExtension(openVsCodeDir, EXTENSION_DIR_NAME, ASSET_FILES, openAsset)

    fun installAndroidInto(openVsCodeDir: File, openAsset: (String) -> InputStream): File =
        installManagedExtension(openVsCodeDir, "kucode-android", listOf("package.json", "extension.js"), openAsset)

    /**
     * Only built-in app-managed extension directories are swapped. User-installed
     * extensions and settings live in the independent VS Code data directory.
     */
    private fun installManagedExtension(
        serverDir: File,
        name: String,
        files: List<String>,
        openAsset: (String) -> InputStream,
    ): File {
        val parent = File(serverDir, "extensions").apply { mkdirs() }
        val target = File(parent, name)
        val staging = File(parent, ".$name.staging")
        val backup = File(parent, ".$name.previous")
        fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        fun move(from: File, to: File) {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(from.toPath(), to.toPath())
            }
        }
        // Recover the only possible interrupted swap before preparing a new set.
        if (exists(backup)) {
            if (exists(target)) deleteTreeNoFollow(backup) else move(backup, target)
        }
        deleteTreeNoFollow(staging)
        Files.createDirectory(staging.toPath())
        try {
            for (file in files) {
                val bytes = openAsset("$name/$file").use { it.readBytes() }
                writeBytesAtomically(File(staging, file), bytes)
            }
            if (exists(target)) move(target, backup)
            try {
                move(staging, target)
            } catch (e: Exception) {
                if (exists(backup) && !exists(target)) move(backup, target)
                throw e
            }
            deleteTreeNoFollow(backup)
            return target
        } finally {
            deleteTreeNoFollow(staging)
        }
    }

    /**
     * Convenience overload for JVM tests that load extension assets directly from disk.
     */
    fun installInto(
        openVsCodeDir: File,
        assetRootDir: File = File("src/main/assets"),
    ): File = installInto(openVsCodeDir) { assetPath ->
        File(assetRootDir, assetPath).inputStream()
    }
}
