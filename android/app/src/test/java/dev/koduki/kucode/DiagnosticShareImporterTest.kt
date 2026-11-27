package dev.koduki.kucode

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class DiagnosticShareImporterTest {

    @After
    fun tearDown() {
        LogRedactor.clearDynamicSecrets()
    }

    @Test
    fun isValidShareRequest_acceptsOnlySendWithDiagnosticMimeAndContentScheme() {
        assertTrue(
            DiagnosticShareImporter.isValidShareRequest(
                action = "android.intent.action.SEND",
                mimeType = DiagnosticShareImporter.MIME_TYPE,
                uriScheme = "content",
            ),
        )
        assertFalse(
            DiagnosticShareImporter.isValidShareRequest(
                action = "android.intent.action.VIEW",
                mimeType = DiagnosticShareImporter.MIME_TYPE,
                uriScheme = "content",
            ),
        )
        assertFalse(
            DiagnosticShareImporter.isValidShareRequest(
                action = "android.intent.action.SEND",
                mimeType = "text/plain",
                uriScheme = "content",
            ),
        )
        assertFalse(
            DiagnosticShareImporter.isValidShareRequest(
                action = "android.intent.action.SEND",
                mimeType = DiagnosticShareImporter.MIME_TYPE,
                uriScheme = "file",
            ),
        )
        assertFalse(
            DiagnosticShareImporter.isValidShareRequest(
                action = null,
                mimeType = null,
                uriScheme = null,
            ),
        )
    }

    @Test
    fun importFromStream_sanitizesIdRedactsSecretsAndWritesAtomically() {
        val base = Files.createTempDirectory("diag-share-happy").toFile()
        try {
            val workspace = File(base, "workspace").apply { mkdirs() }
            val dynSecret = "kucode_dyn_share_secret_987654"
            LogRedactor.registerSecret(dynSecret)
            val ghToken = "ghp_" + "z".repeat(24)
            val content = "# Diagnostic\nAuthorization: Bearer secret_bearer_xyz\nToken=$ghToken\nDyn=$dynSecret\n"

            val result = DiagnosticShareImporter.importFromStream(
                input = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)),
                workspaceDir = workspace,
                idGenerator = { "../evil/../../report 01" },
            )

            assertTrue(result.file.isFile)
            assertTrue(result.file.canonicalPath.startsWith(workspace.canonicalPath))
            assertEquals("/workspace/diagnostics-import/${result.file.name}", result.guestPath)
            assertTrue(result.file.name.startsWith("import-"))
            assertTrue(result.file.name.endsWith(".md"))
            assertFalse(result.file.name.contains(".."))

            val text = result.file.readText(Charsets.UTF_8)
            assertTrue(text.contains("# Diagnostic"))
            assertFalse(text.contains("secret_bearer_xyz"))
            assertFalse(text.contains(ghToken))
            assertFalse(text.contains(dynSecret))

            val importDir = File(workspace, DiagnosticShareImporter.IMPORT_DIR_NAME)
            assertTrue(importDir.listFiles()!!.none { it.name.startsWith(".pending-") })
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    fun importFromStream_enforcesSizeCapAndAllowsExactLimit() {
        val base = Files.createTempDirectory("diag-share-size").toFile()
        try {
            val workspace = File(base, "workspace").apply { mkdirs() }
            val exact = ByteArray(128) { 0x61 }
            val ok = DiagnosticShareImporter.importFromStream(
                input = ByteArrayInputStream(exact),
                workspaceDir = workspace,
                maxBytes = 128,
                idGenerator = { "exact-128" },
            )
            assertEquals(128, ok.file.readBytes().size)

            val oversized = ByteArray(129) { 0x62 }
            assertThrows(IllegalArgumentException::class.java) {
                DiagnosticShareImporter.importFromStream(
                    input = ByteArrayInputStream(oversized),
                    workspaceDir = workspace,
                    maxBytes = 128,
                    idGenerator = { "over-129" },
                )
            }
            assertFalse(File(workspace, "diagnostics-import/import-over-129.md").exists())
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    fun importFromStream_rejectsSymlinksOnWorkspaceImportDirAndTarget() {
        val base = Files.createTempDirectory("diag-share-symlink").toFile()
        try {
            val outside = File(base, "outside").apply { mkdirs() }
            val outsideFile = File(outside, "target.md").apply { writeText("keep") }

            val symlinkWs = File(base, "ws-symlink")
            Files.createSymbolicLink(symlinkWs.toPath(), outside.toPath())
            assertThrows(IOException::class.java) {
                DiagnosticShareImporter.importFromStream(
                    input = ByteArrayInputStream("hi".toByteArray()),
                    workspaceDir = symlinkWs,
                    idGenerator = { "ws-link" },
                )
            }

            val wsDirSymlink = File(base, "ws-dir-symlink").apply { mkdirs() }
            Files.createSymbolicLink(
                File(wsDirSymlink, DiagnosticShareImporter.IMPORT_DIR_NAME).toPath(),
                outside.toPath(),
            )
            assertThrows(IOException::class.java) {
                DiagnosticShareImporter.importFromStream(
                    input = ByteArrayInputStream("hi".toByteArray()),
                    workspaceDir = wsDirSymlink,
                    idGenerator = { "dir-link" },
                )
            }

            val wsTargetSymlink = File(base, "ws-target-symlink").apply { mkdirs() }
            val importDir = File(wsTargetSymlink, DiagnosticShareImporter.IMPORT_DIR_NAME).apply { mkdirs() }
            Files.createSymbolicLink(File(importDir, "import-target-link.md").toPath(), outsideFile.toPath())
            assertThrows(IOException::class.java) {
                DiagnosticShareImporter.importFromStream(
                    input = ByteArrayInputStream("hi".toByteArray()),
                    workspaceDir = wsTargetSymlink,
                    idGenerator = { "target-link" },
                )
            }
            assertEquals("keep", outsideFile.readText())
        } finally {
            deleteTreeNoFollow(base)
        }
    }
}
