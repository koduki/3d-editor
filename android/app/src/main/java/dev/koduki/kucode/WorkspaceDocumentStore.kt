package dev.koduki.kucode

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque

/**
 * Pure-JVM filesystem layer for [KuCodeDocumentsProvider] enforcing strict confinement
 * to `/workspace` without following symbolic links during listing, search, or deletion.
 */
internal object WorkspaceDocumentStore {

    const val ROOT_ID = "kucode_workspace"
    const val ROOT_DOC_ID = "workspace"
    const val DIAGNOSTICS_ROOT_ID = "kucode_diagnostics"
    const val DIAGNOSTICS_ROOT_DOC_ID = "diagnostics"
    const val MAX_SEARCH_RESULTS = 50

    fun docIdForFile(
        baseDir: File,
        file: File,
        rootDocId: String = ROOT_DOC_ID,
    ): String {
        val baseReal = baseDir.toPath().toRealPath()
        val normalized = normalizeWithinBase(baseReal, file.toPath())
            ?: throw FileNotFoundException("Path outside $rootDocId: $file")
        if (normalized == baseReal) return rootDocId
        val rel = baseReal.relativize(normalized).toString().replace(File.separatorChar, '/')
        return "$rootDocId/$rel"
    }

    fun fileForDocId(
        baseDir: File,
        docId: String,
        rootDocId: String = ROOT_DOC_ID,
        rootId: String = ROOT_ID,
    ): File {
        val baseReal = baseDir.toPath().toRealPath()
        val candidate = resolveDocIdPath(baseReal, docId, rootDocId, rootId)
            ?: throw FileNotFoundException("Unknown or invalid documentId: $docId")
        if (!isSafeDescendantNoSymlinks(baseReal, candidate) ||
            !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw FileNotFoundException("Document not found: $docId")
        }
        return candidate.toFile()
    }

    fun listChildren(baseDir: File, parent: File): List<File> {
        val baseReal = baseDir.toPath().toRealPath()
        val parentPath = parent.toPath().toAbsolutePath().normalize()
        if (!isSafeDescendantNoSymlinks(baseReal, parentPath) ||
            !Files.isDirectory(parentPath, LinkOption.NOFOLLOW_LINKS)
        ) {
            return emptyList()
        }
        val children = mutableListOf<File>()
        try {
            Files.newDirectoryStream(parentPath).use { stream ->
                for (child in stream) {
                    if (Files.isSymbolicLink(child)) continue
                    if (!isSafeDescendantNoSymlinks(baseReal, child)) continue
                    children.add(child.toFile())
                }
            }
        } catch (_: IOException) {
            return emptyList()
        }
        children.sortBy { it.name.lowercase() }
        return children
    }

    fun search(baseDir: File, query: String, maxResults: Int = MAX_SEARCH_RESULTS): List<File> {
        val baseReal = baseDir.toPath().toRealPath()
        val needle = query.lowercase()
        val matches = mutableListOf<File>()
        val queue = ArrayDeque<Path>()
        val visited = HashSet<Path>()
        queue.add(baseReal)
        visited.add(baseReal)

        while (queue.isNotEmpty() && matches.size < maxResults) {
            val current = queue.removeFirst()
            try {
                Files.newDirectoryStream(current).use { stream ->
                    for (child in stream) {
                        if (Files.isSymbolicLink(child)) continue
                        val attrs = try {
                            Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        } catch (_: IOException) {
                            continue
                        }
                        if (attrs.isSymbolicLink) continue
                        if (attrs.isDirectory) {
                            val realChild = try {
                                child.toRealPath()
                            } catch (_: IOException) {
                                continue
                            }
                            if (!realChild.startsWith(baseReal)) continue
                            if (visited.add(realChild)) {
                                queue.add(child)
                            }
                        } else if (attrs.isRegularFile) {
                            if (child.fileName.toString().lowercase().contains(needle)) {
                                matches.add(child.toFile())
                                if (matches.size >= maxResults) break
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                continue
            }
        }
        return matches
    }

    fun create(baseDir: File, parent: File, isDirectory: Boolean, displayName: String): File {
        val baseReal = baseDir.toPath().toRealPath()
        val parentPath = parent.toPath().toAbsolutePath().normalize()
        if (!isSafeDescendantNoSymlinks(baseReal, parentPath) ||
            !Files.isDirectory(parentPath, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw FileNotFoundException("Invalid parent directory")
        }
        val safeName = sanitizeDisplayName(displayName)
        for (attempt in 1..1000) {
            val candidateName = if (attempt == 1) safeName else "$safeName ($attempt)"
            val target = parentPath.resolve(candidateName)
            if (!isSafeDescendantNoSymlinks(baseReal, target)) {
                throw FileNotFoundException("Invalid path: $displayName")
            }
            try {
                if (isDirectory) {
                    Files.createDirectory(target)
                } else {
                    Files.createFile(target)
                }
                return target.toFile()
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Concurrent create or pre-existing file: retry with next suffix atomically
                continue
            } catch (e: IOException) {
                throw FileNotFoundException("Failed to create ${target.fileName}: ${e.message}")
            }
        }
        throw FileNotFoundException("Failed to allocate unique filename for $displayName")
    }

    fun rename(baseDir: File, source: File, displayName: String): File {
        val baseReal = baseDir.toPath().toRealPath()
        val sourcePath = source.toPath().toAbsolutePath().normalize()
        if (sourcePath == baseReal || !isSafeDescendantNoSymlinks(baseReal, sourcePath)) {
            throw FileNotFoundException("Cannot rename ${source.name}")
        }
        val parentPath = sourcePath.parent ?: throw FileNotFoundException("Cannot rename root")
        val targetPath = parentPath.resolve(sanitizeDisplayName(displayName)).normalize()
        if (!isSafeDescendantNoSymlinks(baseReal, targetPath) ||
            Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw FileNotFoundException("Failed to rename ${source.name} to $displayName")
        }
        try {
            Files.move(sourcePath, targetPath)
        } catch (e: IOException) {
            throw FileNotFoundException("Failed to rename ${source.name} to $displayName: ${e.message}")
        }
        return targetPath.toFile()
    }

    fun delete(baseDir: File, target: File) {
        val baseReal = baseDir.toPath().toRealPath()
        val targetPath = target.toPath().toAbsolutePath().normalize()
        if (targetPath == baseReal) {
            throw FileNotFoundException("Cannot delete workspace root")
        }
        if (!hasSafeAncestorsNoSymlinks(baseReal, targetPath) ||
            !Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw FileNotFoundException("Cannot delete path outside workspace: $target")
        }
        try {
            deleteTreeNoFollow(targetPath)
        } catch (e: IOException) {
            throw FileNotFoundException("Failed to delete ${target.name}: ${e.message}")
        }
    }

    /**
     * Strictly validates that [documentId] is inside [parentDocumentId] within the same SAF root,
     * rejecting any `..`, `.`, backslash, or empty path segments that would bypass tree confinement.
     */
    fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (parentRoot, parentSegs) = parseCanonicalDocId(parentDocumentId) ?: return false
        val (childRoot, childSegs) = parseCanonicalDocId(documentId) ?: return false
        if (parentRoot != childRoot) return false
        if (childSegs.size < parentSegs.size) return false
        for (i in parentSegs.indices) {
            if (childSegs[i] != parentSegs[i]) return false
        }
        return true
    }

    /**
     * Filesystem-backed version of [isChildDocument] that additionally verifies neither path
     * escapes [baseDir] or traverses a symbolic link.
     */
    fun isChildDocument(
        baseDir: File,
        parentDocumentId: String,
        documentId: String,
        rootDocId: String = ROOT_DOC_ID,
        rootId: String = ROOT_ID,
    ): Boolean {
        if (!isChildDocument(parentDocumentId, documentId)) return false
        val baseReal = runCatching { baseDir.toPath().toRealPath() }.getOrNull() ?: return false
        val parentPath = resolveDocIdPath(baseReal, parentDocumentId, rootDocId, rootId) ?: return false
        val childPath = resolveDocIdPath(baseReal, documentId, rootDocId, rootId) ?: return false
        if (!isSafeDescendantNoSymlinks(baseReal, parentPath)) return false
        if (!isSafeDescendantNoSymlinks(baseReal, childPath)) return false
        return childPath.startsWith(parentPath)
    }

    private fun resolveDocIdPath(
        baseReal: Path,
        docId: String,
        rootDocId: String,
        rootId: String,
    ): Path? {
        if (docId.contains('\\') || docId.contains('\u0000')) return null
        val trimmed = docId.trimEnd('/')
        if (trimmed == rootDocId || trimmed == rootId) return baseReal
        if (!trimmed.startsWith("$rootDocId/")) return null
        val rel = trimmed.removePrefix("$rootDocId/")
        if (rel.isEmpty()) return null
        val segments = rel.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        var current = baseReal
        for (seg in segments) {
            current = current.resolve(seg)
        }
        return current.normalize()
    }

    private fun parseCanonicalDocId(docId: String): Pair<String, List<String>>? {
        if (docId.isEmpty() || docId.contains('\\') || docId.contains('\u0000')) return null
        val trimmed = docId.trimEnd('/')
        val rootPrefix = when {
            trimmed == ROOT_DOC_ID || trimmed == ROOT_ID -> return ROOT_DOC_ID to emptyList()
            trimmed == DIAGNOSTICS_ROOT_DOC_ID || trimmed == DIAGNOSTICS_ROOT_ID ->
                return DIAGNOSTICS_ROOT_DOC_ID to emptyList()
            trimmed.startsWith("$ROOT_DOC_ID/") -> ROOT_DOC_ID
            trimmed.startsWith("$DIAGNOSTICS_ROOT_DOC_ID/") -> DIAGNOSTICS_ROOT_DOC_ID
            else -> return null
        }
        val rel = trimmed.removePrefix("$rootPrefix/")
        if (rel.isEmpty()) return null
        val segments = rel.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return rootPrefix to segments
    }

    fun sanitizeDisplayName(raw: String): String {
        val trimmed = raw.trim().replace('/', '_').replace('\\', '_')
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
            throw FileNotFoundException("Invalid display name: $raw")
        }
        return trimmed
    }

    private fun normalizeWithinBase(baseReal: Path, candidate: Path): Path? {
        val normalized = candidate.toAbsolutePath().normalize()
        return if (normalized.startsWith(baseReal)) normalized else null
    }

    /**
     * Checks that [candidate] is inside [baseReal] and that neither any ancestor under
     * [baseReal] nor [candidate] itself is a symbolic link.
     */
    internal fun isSafeDescendantNoSymlinks(baseReal: Path, candidate: Path): Boolean {
        val normalized = normalizeWithinBase(baseReal, candidate) ?: return false
        if (normalized == baseReal) return true
        val relative = baseReal.relativize(normalized)
        var current = baseReal
        for (segment in relative) {
            if (segment.toString() == "..") return false
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return false
        }
        return true
    }

    /**
     * Checks that [target] is strictly under [baseReal] and no ancestor directory
     * between [baseReal] and [target] is a symbolic link (allowing [target] itself
     * to be unlinked safely if it is a symbolic link).
     */
    private fun hasSafeAncestorsNoSymlinks(baseReal: Path, target: Path): Boolean {
        val normalized = normalizeWithinBase(baseReal, target) ?: return false
        if (normalized == baseReal) return false
        val parent = normalized.parent ?: return false
        return isSafeDescendantNoSymlinks(baseReal, parent)
    }
}
