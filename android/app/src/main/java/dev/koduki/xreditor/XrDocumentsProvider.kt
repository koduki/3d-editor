package dev.koduki.xreditor

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.ArrayDeque

/**
 * Clean Android Storage Access Framework (DocumentsProvider) implementation
 * exposing the POSIX `/workspace` directory as a regular browsable folder
 * in the Android XR Files app and other document pickers.
 *
 * Security & correctness guarantees:
 * - Strictly confined to `/workspace` via canonicalPath checks (blocks symlink escapes into /android or /)
 * - Supports create, write, rename, recursive directory delete, and safe search
 */
class XrDocumentsProvider : DocumentsProvider() {

    private val defaultRootProjection = arrayOf(
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_AVAILABLE_BYTES,
    )

    private val defaultDocumentProjection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE,
    )

    private fun workspaceBaseDir(): File {
        val ctx = context ?: throw IllegalStateException("Context not available")
        val dir = File(ctx.filesDir, "workspace")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir.canonicalFile
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val baseDir = workspaceBaseDir()
        val result = MatrixCursor(projection ?: defaultRootProjection)
        val appName = context?.getString(R.string.app_name) ?: "XR-Editor"
        val summary = context?.getString(R.string.documents_root_summary) ?: "POSIX /workspace"

        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, docIdForFile(baseDir, baseDir))
            add(Root.COLUMN_TITLE, appName)
            add(Root.COLUMN_SUMMARY, summary)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or
                    Root.FLAG_SUPPORTS_SEARCH or
                    Root.FLAG_SUPPORTS_IS_CHILD or
                    Root.FLAG_LOCAL_ONLY,
            )
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, baseDir.usableSpace)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val baseDir = workspaceBaseDir()
        val target = fileForDocId(baseDir, documentId)
        val result = MatrixCursor(projection ?: defaultDocumentProjection)
        includeFile(result, baseDir, target)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val baseDir = workspaceBaseDir()
        val parent = fileForDocId(baseDir, parentDocumentId)
        val result = MatrixCursor(projection ?: defaultDocumentProjection)
        val children = parent.listFiles() ?: return result
        for (child in children.sortedBy { it.name.lowercase() }) {
            // Skip symlinks pointing outside /workspace (e.g. /workspace/android -> /android)
            if (!isWithinBase(baseDir, child)) continue
            includeFile(result, baseDir, child)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val baseDir = workspaceBaseDir()
        val file = fileForDocId(baseDir, documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val baseDir = workspaceBaseDir()
        val file = fileForDocId(baseDir, documentId)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val baseDir = workspaceBaseDir()
        val parent = fileForDocId(baseDir, parentDocumentId)
        val safeName = sanitizeDisplayName(displayName)
        var target = File(parent, safeName)
        var suffix = 2
        while (target.exists()) {
            target = File(parent, "$safeName ($suffix)")
            suffix++
        }
        if (!isWithinBase(baseDir, target)) {
            throw FileNotFoundException("Invalid path: $displayName")
        }
        try {
            val created = if (Document.MIME_TYPE_DIR == mimeType) {
                target.mkdir()
            } else {
                target.createNewFile()
            }
            if (!created) {
                throw FileNotFoundException("Failed to create: ${target.name}")
            }
        } catch (e: IOException) {
            throw FileNotFoundException("Failed to create ${target.name}: ${e.message}")
        }
        return docIdForFile(baseDir, target)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val baseDir = workspaceBaseDir()
        val source = fileForDocId(baseDir, documentId)
        val parent = source.parentFile ?: throw FileNotFoundException("Cannot rename root")
        val target = File(parent, sanitizeDisplayName(displayName))
        if (!isWithinBase(baseDir, target) || target.exists() || !source.renameTo(target)) {
            throw FileNotFoundException("Failed to rename ${source.name} to $displayName")
        }
        return docIdForFile(baseDir, target)
    }

    override fun deleteDocument(documentId: String) {
        val baseDir = workspaceBaseDir()
        val target = fileForDocId(baseDir, documentId)
        if (target.canonicalFile == baseDir) {
            throw FileNotFoundException("Cannot delete workspace root")
        }
        val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (!deleted) {
            throw FileNotFoundException("Failed to delete $documentId")
        }
    }

    override fun getDocumentType(documentId: String): String {
        val baseDir = workspaceBaseDir()
        return mimeTypeForFile(fileForDocId(baseDir, documentId))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val normalizedParent = parentDocumentId.trimEnd('/')
        return documentId == normalizedParent || documentId.startsWith("$normalizedParent/")
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor {
        val baseDir = workspaceBaseDir()
        val result = MatrixCursor(projection ?: defaultDocumentProjection)
        val needle = query.lowercase()
        val queue = ArrayDeque<File>()
        queue.add(baseDir)

        while (queue.isNotEmpty() && result.count < MAX_SEARCH_RESULTS) {
            val current = queue.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (!isWithinBase(baseDir, child)) continue
                if (child.isDirectory) {
                    queue.add(child)
                } else if (child.name.lowercase().contains(needle)) {
                    includeFile(result, baseDir, child)
                    if (result.count >= MAX_SEARCH_RESULTS) break
                }
            }
        }
        return result
    }

    private fun includeFile(cursor: MatrixCursor, baseDir: File, file: File) {
        val isRoot = file.canonicalFile == baseDir
        var flags = 0
        if (file.isDirectory) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (!isRoot && file.parentFile?.canWrite() == true) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        val mimeType = mimeTypeForFile(file)
        if (mimeType.startsWith("image/")) {
            flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }

        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docIdForFile(baseDir, file))
            add(Document.COLUMN_DISPLAY_NAME, if (isRoot) "workspace" else file.name)
            add(Document.COLUMN_SIZE, if (file.isDirectory) 0L else file.length())
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_ICON, R.mipmap.ic_launcher)
        }
    }

    private fun docIdForFile(baseDir: File, file: File): String {
        val basePath = baseDir.canonicalPath
        val filePath = file.canonicalPath
        if (filePath == basePath) return ROOT_DOC_ID
        val rel = filePath.removePrefix("$basePath/")
        return "$ROOT_DOC_ID/$rel"
    }

    private fun fileForDocId(baseDir: File, docId: String): File {
        val target = if (docId == ROOT_DOC_ID || docId == ROOT_ID) {
            baseDir
        } else if (docId.startsWith("$ROOT_DOC_ID/")) {
            val rel = docId.removePrefix("$ROOT_DOC_ID/")
            File(baseDir, rel)
        } else {
            throw FileNotFoundException("Unknown documentId: $docId")
        }
        if (!target.exists() || !isWithinBase(baseDir, target)) {
            throw FileNotFoundException("Document not found: $docId")
        }
        return target
    }

    private fun isWithinBase(baseDir: File, candidate: File): Boolean {
        return try {
            val basePath = baseDir.canonicalPath
            val targetPath = candidate.canonicalPath
            targetPath == basePath || targetPath.startsWith("$basePath${File.separator}")
        } catch (_: IOException) {
            false
        }
    }

    private fun sanitizeDisplayName(raw: String): String {
        val trimmed = raw.trim().replace('/', '_').replace('\\', '_')
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
            throw FileNotFoundException("Invalid display name: $raw")
        }
        return trimmed
    }

    private fun mimeTypeForFile(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        if (ext == "md" || ext == "markdown") return "text/markdown"
        if (ext in setOf("txt", "json", "yml", "yaml", "kt", "js", "ts", "py", "sh", "xml", "css", "html")) {
            return "text/plain"
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        private const val ROOT_ID = "xr_workspace"
        private const val ROOT_DOC_ID = "workspace"
        private const val MAX_SEARCH_RESULTS = 50
    }
}
