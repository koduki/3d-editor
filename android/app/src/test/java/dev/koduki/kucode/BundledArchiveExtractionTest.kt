package dev.koduki.kucode

import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File
import java.nio.file.Files

@Category(MediumTest::class)
class BundledArchiveExtractionTest {

    @Test
    fun extractBundledArm64ArchivesWhenAvailable() {
        val runtime = File("src/main/assets/runtime")
        val debian = runtime.resolve(PosixRuntimeConfig.ASSET_ROOTFS.substringAfterLast('/'))
        val editor = runtime.resolve(PosixRuntimeConfig.ASSET_OPENVSCODE.substringAfterLast('/'))
        if (System.getenv("GITHUB_ACTIONS") == "true") {
            assertTrue("CI must supply both bundled archives at ${runtime.absolutePath}", debian.isFile && editor.isFile)
        } else {
            Assume.assumeTrue(debian.isFile && editor.isFile)
        }
        val root = Files.createTempDirectory("kucode-runtime").toFile()
        try {
            debian.inputStream().use { TarGzExtractor.extract(it, root) }
            val editorDir = TarGzExtractor.resolveSafeChild(root, "opt/openvscode-server")
            editor.inputStream().use { TarGzExtractor.extract(it, editorDir, stripComponents = 1) }
            assertTrue(root.resolve("usr/bin/bash").canExecute())
            assertTrue(editorDir.resolve("bin/openvscode-server").canExecute())
        } finally {
            deleteTreeNoFollow(root)
        }
    }
}
