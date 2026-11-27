package dev.koduki.kucode

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/**
 * Pure-JVM validator and importer for shared diagnostic documents (`ACTION_SEND`).
 * Enforces strict size bounds, NOFOLLOW_LINKS symlink rejection, canonical containment
 * inside `/workspace`, secret redaction via [LogRedactor], and atomic persistence.
 */
object DiagnosticShareImporter {

    const val MIME_TYPE = "application/vnd.kucode.diagnostic"
    const val IMPORT_DIR_NAME = "diagnostics-import"
    const val MAX_IMPORT_BYTES = 1024 * 1024
    private const val ACTION_SEND = "android.intent.action.SEND"

    data class ImportResult(
        val file: File,
        val guestPath: String,
    )

    fun isValidShareRequest(
        action: String?,
        mimeType: String?,
        uriScheme: String?,
    ): Boolean {
        if (action != ACTION_SEND) return false
        if (!mimeType?.trim().equals(MIME_TYPE, ignoreCase = true)) return false
        return uriScheme?.trim().equals("content", ignoreCase = true)
    }

    fun importFromStream(
        input: InputStream,
        workspaceDir: File,
        maxBytes: Int = MAX_IMPORT_BYTES,
        idGenerator: () -> String = { UUID.randomUUID().toString() },
    ): ImportResult {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val payload = readBounded(input, maxBytes)

        val wsPath = workspaceDir.toPath()
        if (Files.isSymbolicLink(wsPath)) {
            throw IOException("Workspace root must not be a symbolic link")
        }
        if (!Files.exists(wsPath, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(wsPath)
        } else if (!Files.isDirectory(wsPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Workspace root is not a regular directory")
        }
        val canonicalWorkspace = workspaceDir.canonicalFile

        val importDir = File(canonicalWorkspace, IMPORT_DIR_NAME)
        val importDirPath = importDir.toPath()
        if (Files.isSymbolicLink(importDirPath)) {
            throw IOException("$IMPORT_DIR_NAME must be a regular directory")
        }
        if (!Files.exists(importDirPath, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(importDirPath)
        } else if (!Files.isDirectory(importDirPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("$IMPORT_DIR_NAME must be a regular directory")
        }
        val canonicalImportDir = importDir.canonicalFile
        if (!canonicalImportDir.toPath().startsWith(canonicalWorkspace.toPath())) {
            throw IOException("Invalid $IMPORT_DIR_NAME destination")
        }

        val safeId = sanitizeId(idGenerator())
        val fileName = "import-$safeId.md"
        val target = File(canonicalImportDir, fileName)
        val targetPath = target.toPath()
        if (Files.isSymbolicLink(targetPath)) {
            throw IOException("Import target must not be a symbolic link")
        }
        if (Files.isDirectory(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Import target is a directory")
        }
        val canonicalTarget = target.canonicalFile
        if (!canonicalTarget.toPath().startsWith(canonicalImportDir.toPath())) {
            throw IOException("Import target escapes $IMPORT_DIR_NAME")
        }

        val redacted = LogRedactor.redact(payload.toString(Charsets.UTF_8))
        writeTextAtomically(canonicalTarget, redacted, Charsets.UTF_8)
        return ImportResult(
            file = canonicalTarget,
            guestPath = "${PosixRuntimeConfig.GUEST_WORKSPACE}/$IMPORT_DIR_NAME/$fileName",
        )
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("Diagnostic exceeds $maxBytes bytes")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun sanitizeId(raw: String): String {
        val cleaned = raw
            .replace(Regex("[^A-Za-z0-9_-]"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
            .take(64)
        return cleaned.ifEmpty { UUID.randomUUID().toString() }
    }
}
