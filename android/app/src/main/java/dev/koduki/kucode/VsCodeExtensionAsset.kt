package dev.koduki.kucode

import java.io.File
import java.io.InputStream

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
    ): File {
        val extDir = File(openVsCodeDir, "extensions/$EXTENSION_DIR_NAME")
        extDir.mkdirs()
        for (fileName in ASSET_FILES) {
            val bytes = openAsset("$EXTENSION_DIR_NAME/$fileName").use { it.readBytes() }
            writeBytesAtomically(File(extDir, fileName), bytes)
        }
        return extDir
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
