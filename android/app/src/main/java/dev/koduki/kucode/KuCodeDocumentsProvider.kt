package dev.koduki.kucode

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Clean Android Storage Access Framework (DocumentsProvider) implementation exposing:
 * 1. Primary root `KuCode` (`kucode_workspace`) -> POSIX `/workspace` (full read/write)
 * 2. Secondary read-only root `KuCode Logs` (`kucode_diagnostics`) -> `<filesDir>/diagnostics`
 *    so current and historical AI session diagnostic bundles can be attached directly from the
 *    Android XR Files app or browser file pickers even when the editor UI is not open.
 *
 * Security & correctness guarantees:
 * - Strictly confined to each root via NOFOLLOW_LINKS path checks (blocks symlink escapes & loops)
 * - Supports create, write, rename, symlink-safe recursive directory delete, and cycle-safe search on `/workspace`
 */
class KuCodeDocumentsProvider : DocumentsProvider() {

    private data class ResolvedRoot(
        val baseDir: File,
        val rootId: String,
        val rootDocId: String,
        val readOnly: Boolean,
    )

    @Volatile
    private var lastDiagnosticsRefreshElapsedMs: Long = 0L

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

    private fun documentsAuthority(): String? = context?.packageName?.let { "$it.documents" }

    private fun workspaceBaseDir(): File {
        val ctx = context ?: throw IllegalStateException("Context not available")
        val dir = File(ctx.filesDir, "workspace")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir.canonicalFile
    }

    private fun diagnosticsBaseDir(): File {
        val ctx = context ?: throw IllegalStateException("Context not available")
        val dir = File(ctx.filesDir, "diagnostics")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir.canonicalFile
    }

    private fun resolveRootForDocId(documentId: String): ResolvedRoot {
        val diagDoc = WorkspaceDocumentStore.DIAGNOSTICS_ROOT_DOC_ID
        val diagRoot = WorkspaceDocumentStore.DIAGNOSTICS_ROOT_ID
        return if (documentId == diagDoc || documentId == diagRoot || documentId.startsWith("$diagDoc/")) {
            ResolvedRoot(
                baseDir = diagnosticsBaseDir(),
                rootId = diagRoot,
                rootDocId = diagDoc,
                readOnly = true,
            )
        } else {
            ResolvedRoot(
                baseDir = workspaceBaseDir(),
                rootId = WorkspaceDocumentStore.ROOT_ID,
                rootDocId = WorkspaceDocumentStore.ROOT_DOC_ID,
                readOnly = false,
            )
        }
    }

    private fun refreshLatestDiagnosticsReportDebounced() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagnosticsRefreshElapsedMs < DIAGNOSTICS_REFRESH_DEBOUNCE_MS) return
        lastDiagnosticsRefreshElapsedMs = now
        context?.let { ctx ->
            runCatching { SessionTelemetryManager.getInstance(ctx).renderSessionBundle("latest", "ai") }
        }
    }

    private fun notifyParentChildrenChanged(parentDocId: String) {
        val ctx = context ?: return
        val authority = documentsAuthority() ?: return
        runCatching {
            ctx.contentResolver.notifyChange(
                DocumentsContract.buildChildDocumentsUri(authority, parentDocId),
                null,
            )
        }
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val wsBase = workspaceBaseDir()
        val diagBase = diagnosticsBaseDir()
        val result = MatrixCursor(projection ?: defaultRootProjection)
        val appName = context?.getString(R.string.app_name) ?: "KuCode"
        val wsSummary = context?.getString(R.string.documents_root_summary) ?: "POSIX /workspace"
        val diagTitle = context?.getString(R.string.diagnostics_root_title) ?: "KuCode Logs"
        val diagSummary = context?.getString(R.string.diagnostics_root_summary) ?: "Session AI Diagnostics"

        // Primary root MUST remain first row so DiagnosticsRunner probes /workspace as expected
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, WorkspaceDocumentStore.ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, WorkspaceDocumentStore.docIdForFile(wsBase, wsBase))
            add(Root.COLUMN_TITLE, appName)
            add(Root.COLUMN_SUMMARY, wsSummary)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or
                    Root.FLAG_SUPPORTS_SEARCH or
                    Root.FLAG_SUPPORTS_IS_CHILD or
                    Root.FLAG_LOCAL_ONLY,
            )
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, wsBase.usableSpace)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }

        // Secondary read-only root exposing current & historical session diagnostic bundles
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, WorkspaceDocumentStore.DIAGNOSTICS_ROOT_ID)
            add(
                Root.COLUMN_DOCUMENT_ID,
                WorkspaceDocumentStore.docIdForFile(
                    diagBase,
                    diagBase,
                    WorkspaceDocumentStore.DIAGNOSTICS_ROOT_DOC_ID,
                ),
            )
            add(Root.COLUMN_TITLE, diagTitle)
            add(Root.COLUMN_SUMMARY, diagSummary)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_SEARCH or
                    Root.FLAG_SUPPORTS_IS_CHILD or
                    Root.FLAG_LOCAL_ONLY,
            )
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, diagBase.usableSpace)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val root = resolveRootForDocId(documentId)
        // Refresh latest-ai-report.md on demand (debounced) when browsing the diagnostics root
        if (root.readOnly && (documentId == root.rootDocId || documentId == "${root.rootDocId}/latest-ai-report.md")) {
            refreshLatestDiagnosticsReportDebounced()
        }
        val target = WorkspaceDocumentStore.fileForDocId(root.baseDir, documentId, root.rootDocId, root.rootId)
        val result = MatrixCursor(projection ?: defaultDocumentProjection)
        includeFile(result, root, target)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val root = resolveRootForDocId(parentDocumentId)
        if (root.readOnly && parentDocumentId == root.rootDocId) {
            refreshLatestDiagnosticsReportDebounced()
        }
        val parent = WorkspaceDocumentStore.fileForDocId(root.baseDir, parentDocumentId, root.rootDocId, root.rootId)
        val result = MatrixCursor(projection ?: defaultDocumentProjection)
        for (child in WorkspaceDocumentStore.listChildren(root.baseDir, parent)) {
            includeFile(result, root, child)
        }
        val ctx = context
        val authority = documentsAuthority()
        if (ctx != null && authority != null) {
            result.setNotificationUri(
                ctx.contentResolver,
                DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId),
            )
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val root = resolveRootForDocId(documentId)
        if (root.readOnly && mode != "r") {
            throw FileNotFoundException("Diagnostics logs root is read-only")
        }
        if (root.readOnly && documentId == "${root.rootDocId}/latest-ai-report.md") {
            refreshLatestDiagnosticsReportDebounced()
        }
        val file = WorkspaceDocumentStore.fileForDocId(root.baseDir, documentId, root.rootDocId, root.rootId)
        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw FileNotFoundException("Cannot open directory as file: $documentId")
        }
        val parsedMode = if (root.readOnly) ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, parsedMode)
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val root = resolveRootForDocId(documentId)
        val file = WorkspaceDocumentStore.fileForDocId(root.baseDir, documentId, root.rootDocId, root.rootId)
        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw FileNotFoundException("Cannot open thumbnail for directory: $documentId")
        }
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        } catch (e: Throwable) {
            runCatching { pfd.close() }
            throw e
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val root = resolveRootForDocId(parentDocumentId)
        if (root.readOnly) throw FileNotFoundException("Cannot create files inside read-only diagnostics root")
        val parent = WorkspaceDocumentStore.fileForDocId(root.baseDir, parentDocumentId, root.rootDocId, root.rootId)
        val target = WorkspaceDocumentStore.create(
            baseDir = root.baseDir,
            parent = parent,
            isDirectory = Document.MIME_TYPE_DIR == mimeType,
            displayName = displayName,
        )
        val newDocId = WorkspaceDocumentStore.docIdForFile(root.baseDir, target, root.rootDocId)
        notifyParentChildrenChanged(WorkspaceDocumentStore.docIdForFile(root.baseDir, parent, root.rootDocId))
        return newDocId
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val root = resolveRootForDocId(documentId)
        if (root.readOnly) throw FileNotFoundException("Cannot rename files inside read-only diagnostics root")
        val source = WorkspaceDocumentStore.fileForDocId(root.baseDir, documentId, root.rootDocId, root.rootId)
        val parentDocId = source.parentFile?.let {
            runCatching { WorkspaceDocumentStore.docIdForFile(root.baseDir, it, root.rootDocId) }.getOrNull()
        }
        val target = WorkspaceDocumentStore.rename(root.baseDir, source, displayName)
        val newDocId = WorkspaceDocumentStore.docIdForFile(root.baseDir, target, root.rootDocId)
        if (parentDocId != null) notifyParentChildrenChanged(parentDocId)
        return newDocId
    }

    override fun deleteDocument(documentId: String) {
        val root = resolveRootForDocId(documentId)
        if (root.readOnly) throw FileNotFoundException("Cannot delete files inside read-only diagnostics root")
        val target = WorkspaceDocumentStore.fileForDocId(root.baseDir, documentId, root.rootDocId, root.rootId)
        val parentDocId = target.parentFile?.let {
            runCatching { WorkspaceDocumentStore.docIdForFile(root.baseDir, it, root.rootDocId) }.getOrNull()
        }
        WorkspaceDocumentStore.delete(root.baseDir, target)
        if (parentDocId != null) notifyParentChildrenChanged(parentDocId)
    }

    override fun getDocumentType(documentId: String): String {
        val root = resolveRootForDocId(documentId)
        return mimeTypeForFile(WorkspaceDocumentStore.fileForDocId(root.baseDir, documentId, root.rootDocId, root.rootId))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parentRoot = resolveRootForDocId(parentDocumentId)
        val childRoot = resolveRootForDocId(documentId)
        if (parentRoot.rootId != childRoot.rootId) return false
        return WorkspaceDocumentStore.isChildDocument(
            baseDir = parentRoot.baseDir,
            parentDocumentId = parentDocumentId,
            documentId = documentId,
            rootDocId = parentRoot.rootDocId,
            rootId = parentRoot.rootId,
        )
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor {
        val root = resolveRootForDocId(rootId)
        val result = MatrixCursor(projection ?: defaultDocumentProjection)
        for (match in WorkspaceDocumentStore.search(root.baseDir, query)) {
            includeFile(result, root, match)
        }
        return result
    }

    private fun includeFile(cursor: MatrixCursor, root: ResolvedRoot, file: File) {
        val path = file.toPath()
        val isDir = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        val isRoot = path.toAbsolutePath().normalize() == root.baseDir.toPath().toRealPath()
        var flags = 0
        if (!root.readOnly) {
            if (isDir) {
                if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
            } else if (file.canWrite()) {
                flags = flags or Document.FLAG_SUPPORTS_WRITE
            }
            if (!isRoot && file.parentFile?.canWrite() == true) {
                flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            }
        }
        val mimeType = mimeTypeForFile(file)
        if (mimeType.startsWith("image/") && mimeType != "image/svg+xml") {
            flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }

        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, WorkspaceDocumentStore.docIdForFile(root.baseDir, file, root.rootDocId))
            add(Document.COLUMN_DISPLAY_NAME, if (isRoot) root.rootDocId else file.name)
            add(Document.COLUMN_SIZE, if (isDir) 0L else file.length())
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_ICON, R.mipmap.ic_launcher)
        }
    }

    companion object {
        private const val DIAGNOSTICS_REFRESH_DEBOUNCE_MS = 1_500L
    }

    private fun mimeTypeForFile(file: File): String {
        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        if (ext == "md" || ext == "markdown") return "text/markdown"
        if (ext in setOf("txt", "log", "json", "jsonl", "yml", "yaml", "kt", "js", "ts", "py", "sh", "xml", "css", "html")) {
            return "text/plain"
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
