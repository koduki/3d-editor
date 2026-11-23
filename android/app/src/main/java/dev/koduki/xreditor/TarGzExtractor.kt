package dev.koduki.xreditor

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/**
 * Extracts POSIX .tar.gz archives (Debian rootfs / openvscode-server) while preserving
 * symlinks, hardlinks (with copy fallback for Android filesystems), and executable bits.
 */
object TarGzExtractor {

    interface PosixFsOps {
        fun symlink(target: String, linkPath: File)
        fun hardlinkOrCopy(existingTarget: File, linkPath: File)
        fun chmod(file: File, mode: Int)
    }

    object DefaultPosixFsOps : PosixFsOps {
        override fun symlink(target: String, linkPath: File) {
            if (linkPath.exists() || Files.isSymbolicLink(linkPath.toPath())) {
                Files.delete(linkPath.toPath())
            }
            linkPath.parentFile?.mkdirs()
            Files.createSymbolicLink(linkPath.toPath(), Paths.get(target))
        }

        override fun hardlinkOrCopy(existingTarget: File, linkPath: File) {
            if (!Files.isRegularFile(existingTarget.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Missing or invalid hardlink target: $existingTarget")
            }
            if (linkPath.exists() || Files.isSymbolicLink(linkPath.toPath())) {
                Files.delete(linkPath.toPath())
            }
            linkPath.parentFile?.mkdirs()
            try {
                Files.createLink(linkPath.toPath(), existingTarget.toPath())
            } catch (_: Throwable) {
                existingTarget.copyTo(linkPath, overwrite = true)
                linkPath.setExecutable(existingTarget.canExecute(), false)
            }
        }

        override fun chmod(file: File, mode: Int) {
            val perm = mode and 0x1FF
            if (perm == 0) return
            val perms = mutableSetOf<PosixFilePermission>()
            if ((perm and 0b100_000_000) != 0) perms.add(PosixFilePermission.OWNER_READ)
            if ((perm and 0b010_000_000) != 0) perms.add(PosixFilePermission.OWNER_WRITE)
            if ((perm and 0b001_000_000) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE)
            if ((perm and 0b000_100_000) != 0) perms.add(PosixFilePermission.GROUP_READ)
            if ((perm and 0b000_010_000) != 0) perms.add(PosixFilePermission.GROUP_WRITE)
            if ((perm and 0b000_001_000) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE)
            if ((perm and 0b000_000_100) != 0) perms.add(PosixFilePermission.OTHERS_READ)
            if ((perm and 0b000_000_010) != 0) perms.add(PosixFilePermission.OTHERS_WRITE)
            if ((perm and 0b000_000_001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE)
            try {
                Files.setPosixFilePermissions(file.toPath(), perms)
            } catch (_: Throwable) {
                file.setReadable((perm and 0b100_000_000) != 0, (perm and 0b000_100_100) == 0)
                file.setWritable((perm and 0b010_000_000) != 0, (perm and 0b000_010_010) == 0)
                file.setExecutable((perm and 0b001_000_000) != 0, (perm and 0b000_001_001) == 0)
            }
        }
    }

    data class ExtractStats(
        val files: Int,
        val directories: Int,
        val symlinks: Int,
        val hardlinks: Int,
    )

    fun extract(
        input: InputStream,
        destDir: File,
        stripComponents: Int = 0,
        fsOps: PosixFsOps = DefaultPosixFsOps,
        onProgress: ((extractedEntries: Int, currentName: String) -> Unit)? = null,
    ): ExtractStats {
        destDir.mkdirs()
        val canonicalRoot = destDir.canonicalFile

        var fileCount = 0
        var dirCount = 0
        var symlinkCount = 0
        var hardlinkCount = 0
        var totalEntries = 0

        val deferredHardlinks = mutableListOf<Pair<File, File>>()
        val dirModes = mutableListOf<Pair<File, Int>>()
        val buffer = ByteArray(64 * 1024)

        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(input))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val relPath = normalizeEntryPath(entry.name, stripComponents)
                if (relPath != null) {
                    val outFile = resolveSafeChild(canonicalRoot, relPath)
                    when {
                        entry.isDirectory -> {
                            Files.createDirectories(outFile.toPath())
                            dirModes.add(outFile to entry.mode)
                            dirCount++
                        }

                        entry.isSymbolicLink -> {
                            fsOps.symlink(entry.linkName, outFile)
                            symlinkCount++
                        }

                        entry.isLink -> {
                            val linkRel = normalizeEntryPath(entry.linkName, stripComponents)
                            if (linkRel != null) {
                                val target = resolveSafeChild(canonicalRoot, linkRel)
                                if (target.exists()) {
                                    fsOps.hardlinkOrCopy(target, outFile)
                                } else {
                                    deferredHardlinks.add(target to outFile)
                                }
                                hardlinkCount++
                            }
                        }

                        entry.isFile -> {
                            outFile.parentFile?.mkdirs()
                            if (outFile.exists() || Files.isSymbolicLink(outFile.toPath())) {
                                Files.delete(outFile.toPath())
                            }
                            Files.newOutputStream(outFile.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { fos ->
                                while (true) {
                                    val read = tar.read(buffer)
                                    if (read <= 0) break
                                    fos.write(buffer, 0, read)
                                }
                            }
                            fsOps.chmod(outFile, entry.mode)
                            fileCount++
                        }
                    }

                    totalEntries++
                    if (totalEntries % 200 == 0) {
                        onProgress?.invoke(totalEntries, relPath)
                    }
                }
                entry = tar.nextEntry
            }
        }

        for ((target, linkPath) in deferredHardlinks) {
            verifyEntryPath(canonicalRoot, target)
            verifyEntryPath(canonicalRoot, linkPath)
            fsOps.hardlinkOrCopy(target, linkPath)
        }

        // Apply directory modes bottom-up so read-only dirs don't block extracting children
        for ((dir, mode) in dirModes.asReversed()) {
            val safeMode = mode or 0b111_000_000 // ensure owner rwx so app can update/clean later
            verifyEntryPath(canonicalRoot, dir)
            fsOps.chmod(dir, safeMode)
        }

        onProgress?.invoke(totalEntries, "done")
        return ExtractStats(fileCount, dirCount, symlinkCount, hardlinkCount)
    }

    internal fun normalizeEntryPath(rawName: String, stripComponents: Int): String? {
        var cleaned = rawName.replace('\\', '/').trimStart('/')
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.removePrefix("./")
        }
        cleaned = cleaned.trimEnd('/')
        if (cleaned.isEmpty() || cleaned == ".") return null

        val segments = cleaned.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.size <= stripComponents) return null
        val sliced = segments.drop(stripComponents)
        if (sliced.any { it == ".." }) {
            throw IOException("Blocked path traversal in tar entry: $rawName")
        }
        return sliced.joinToString("/")
    }

    internal fun resolveSafeChild(canonicalRoot: File, relPath: String): File {
        val candidate = File(canonicalRoot, relPath)
        verifyEntryPath(canonicalRoot, candidate)
        return candidate
    }

    // Guest symlinks may be absolute. Only PRoot may resolve them, never the host extractor.
    private fun verifyEntryPath(root: File, entry: File) {
        val relative = root.toPath().relativize(entry.toPath())
        var current = root.toPath()
        for (component in relative) {
            if (component.toString() == "..") throw IOException("Tar entry escapes extraction root: $entry")
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) throw IOException("Symlink in extraction path: $current")
        }
    }
}
