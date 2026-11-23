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

internal fun writeBytesAtomically(target: File, content: ByteArray) {
    val parent = target.parentFile
        ?: throw IOException("Target file has no parent directory: $target")
    parent.mkdirs()
    val temporary = Files.createTempFile(parent.toPath(), ".pending-", ".tmp")
    try {
        Files.write(temporary, content)
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

internal fun writeTextAtomically(target: File, content: String, charset: Charset = Charsets.UTF_8) {
    writeBytesAtomically(target, content.toByteArray(charset))
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
