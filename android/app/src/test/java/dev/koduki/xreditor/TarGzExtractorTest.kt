package dev.koduki.xreditor

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files

class TarGzExtractorTest {

    @Test
    fun extract_stripsComponentsPreservesExecutableAndSymlink() {
        val archiveBytes = buildTarGz { tar ->
            val binEntry = TarArchiveEntry("pkg-v1/bin/tool.sh")
            val content = "#!/bin/sh\necho ok\n".toByteArray()
            binEntry.size = content.size.toLong()
            binEntry.mode = 0b111_101_101 // 0755
            tar.putArchiveEntry(binEntry)
            tar.write(content)
            tar.closeArchiveEntry()

            val linkEntry = TarArchiveEntry("pkg-v1/bin/latest", TarArchiveEntry.LF_SYMLINK)
            linkEntry.linkName = "tool.sh"
            tar.putArchiveEntry(linkEntry)
            tar.closeArchiveEntry()
        }

        val dest = Files.createTempDirectory("xr-extract-test").toFile()
        try {
            val stats = TarGzExtractor.extract(
                input = ByteArrayInputStream(archiveBytes),
                destDir = dest,
                stripComponents = 1,
            )
            assertEquals(1, stats.files)
            assertEquals(1, stats.symlinks)

            val extractedFile = dest.resolve("bin/tool.sh")
            assertTrue(extractedFile.exists())
            assertTrue(extractedFile.canExecute())
            assertTrue(Files.isSymbolicLink(dest.resolve("bin/latest").toPath()))
        } finally {
            dest.deleteRecursively()
        }
    }

    @Test
    fun extract_blocksPathTraversal() {
        val maliciousBytes = buildTarGz { tar ->
            val bad = TarArchiveEntry("../escape.txt")
            val content = "nope".toByteArray()
            bad.size = content.size.toLong()
            tar.putArchiveEntry(bad)
            tar.write(content)
            tar.closeArchiveEntry()
        }

        val dest = Files.createTempDirectory("xr-extract-sec").toFile()
        try {
            try {
                TarGzExtractor.extract(ByteArrayInputStream(maliciousBytes), dest)
                fail("Expected IOException for path traversal entry")
            } catch (_: IOException) {
                // expected
            }
        } finally {
            dest.deleteRecursively()
        }
    }

    private fun buildTarGz(block: (TarArchiveOutputStream) -> Unit): ByteArray {
        val baos = ByteArrayOutputStream()
        GzipCompressorOutputStream(baos).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                block(tar)
            }
        }
        return baos.toByteArray()
    }
}
