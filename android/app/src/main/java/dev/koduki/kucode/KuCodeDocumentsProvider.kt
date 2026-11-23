package dev.koduki.kucode

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
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Clean Android Storage Access Framework (DocumentsProvider) implementation
 * exposing the POSIX `/workspace` directory as a regular browsable folder
 * in the Android XR Files app and other document pickers.
 *
 * Security & correctness guarantees:
 * - Strictly confined to `/workspace` via NOFOLLOW_LINKS path checks (blocks symlink escapes & loops)
 * - Supports create, write, rename, symlink-safe recursive directory delete, and cycle-safe search
 */
class KuCodeDocumentsProvider : DocumentsProvider() {

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
        val appName = context?.getString(R.string.app_name) ?: "KuCode"
        val summary = context?.getString(R.string.documents_root_summary) ?: "POSIX /workspace"

        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, WorkspaceDocumentStore.ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, WorkspaceDocumentStore.docIdForFile(baseDir, baseDir))
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
        val target = WorkspaceDocumentStore.fileForDocId(baseDir, documentId)
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
        val parent = WorkspaceDocumentStore.fileForDocId(baseDir, parentDocumentId)
        val result = MatrixCursor(projection ?: defaultDocumentProjection)
        for (child in WorkspaceDocumentStore.listChildren(baseDir, parent)) {
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
        val file = WorkspaceDocumentStore.fileForDocId(baseDir, documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val baseDir = workspaceBaseDir()
        val file = WorkspaceDocumentStore.fileForDocId(baseDir, documentId)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val baseDir = workspaceBaseDir()
        val parent = WorkspaceDocumentStore.fileForDocId(baseDir, parentDocumentId)
        val target = WorkspaceDocumentStore.create(
            baseDir = baseDir,
            parent = parent,
            isDirectory = Document.MIME_TYPE_DIR == mimeType,
            displayName = displayName,
        )
        return WorkspaceDocumentStore.docIdForFile(baseDir, target)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val baseDir = workspaceBaseDir()
        val source = WorkspaceDocumentStore.fileForDocId(baseDir, documentId)
        val target = WorkspaceDocumentStore.rename(baseDir, source, displayName)
        return WorkspaceDocumentStore.docIdForFile(baseDir, target)
    }

    override fun deleteDocument(documentId: String) {
        val baseDir = workspaceBaseDir()
        val target = WorkspaceDocumentStore.fileForDocId(baseDir, documentId)
        WorkspaceDocumentStore.delete(baseDir, target)
    }

    override fun getDocumentType(documentId: String): String {
        val baseDir = workspaceBaseDir()
        return mimeTypeForFile(WorkspaceDocumentStore.fileForDocId(baseDir, documentId))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        WorkspaceDocumentStore.isChildDocument(parentDocumentId, documentId)

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor {
        val baseDir = workspaceBaseDir()
        val result = MatrixCursor(projection ?: defaultDocumentProjection)
        for (match in WorkspaceDocumentStore.search(baseDir, query)) {
            includeFile(result, baseDir, match)
        }
        return result
    }

    private fun includeFile(cursor: MatrixCursor, baseDir: File, file: File) {
        val path = file.toPath()
        val isDir = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        val isRoot = path.toAbsolutePath().normalize() == baseDir.toPath().toRealPath()
        var flags = 0
        if (isDir) {
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
            add(Document.COLUMN_DOCUMENT_ID, WorkspaceDocumentStore.docIdForFile(baseDir, file))
            add(Document.COLUMN_DISPLAY_NAME, if (isRoot) "workspace" else file.name)
            add(Document.COLUMN_SIZE, if (isDir) 0L else file.length())
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_ICON, R.mipmap.ic_launcher)
        }
    }

    private fun mimeTypeForFile(file: File): String {
        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        if (ext == "md" || ext == "markdown") return "text/markdown"
        if (ext in setOf("txt", "json", "yml", "yaml", "kt", "js", "ts", "py", "sh", "xml", "css", "html")) {
            return "text/plain"
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
