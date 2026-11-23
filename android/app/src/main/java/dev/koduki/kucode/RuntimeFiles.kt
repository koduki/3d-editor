package dev.koduki.kucode

import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

internal fun writeBytesAtomically(
    target: File,
    content: ByteArray,
    worldReadable: Boolean = false,
) {
    val parent = target.parentFile
        ?: throw IOException("Target file has no parent directory: $target")
    parent.mkdirs()
    val temporary = Files.createTempFile(parent.toPath(), ".pending-", ".tmp")
    try {
        Files.write(temporary, content)
        if (worldReadable) {
            TarGzExtractor.DefaultPosixFsOps.chmod(temporary.toFile(), 0b110_100_100)
        }
        try {
            Files.move(
                temporary,
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary,
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal fun writeTextAtomically(
    target: File,
    content: String,
    charset: Charset = Charsets.UTF_8,
    worldReadable: Boolean = false,
) {
    writeBytesAtomically(target, content.toByteArray(charset), worldReadable = worldReadable)
}

internal fun ensureDirectoryWithMode(dir: File, mode: Int) {
    val missing = mutableListOf<File>()
    var current: File? = dir
    while (current != null && !current.exists()) {
        missing.add(current)
        current = current.parentFile
    }
    dir.mkdirs()
    for (created in missing.asReversed()) {
        if (created.isDirectory) {
            val createdMode = if (created == dir) mode else 0b111_101_101
            TarGzExtractor.DefaultPosixFsOps.chmod(created, createdMode)
        }
    }
    if (dir.isDirectory) {
        TarGzExtractor.DefaultPosixFsOps.chmod(dir, mode)
    }
}

/**
 * Deletes [root] and any descendants without following symbolic links.
 * Symbolic links (including links to directories) are unlinked directly
 * without traversing into their targets.
 */
internal fun deleteTreeNoFollow(root: Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        Files.delete(root)
        return
    }
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

internal fun deleteTreeNoFollow(root: File) {
    deleteTreeNoFollow(root.toPath())
}
