package dev.koduki.kucode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

class WorkspaceDocumentStoreTest {

    @Test
    fun search_doesNotHangOnParentOrMutualDirectorySymlinkCycles() {
        val root = Files.createTempDirectory("xr-doc-search").toFile()
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            val sub = File(workspace, "sub").apply { mkdirs() }
            val dirA = File(workspace, "a").apply { mkdirs() }
            val dirB = File(workspace, "b").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }

            File(workspace, "hello-root.txt").writeText("root")
            File(sub, "hello-sub.txt").writeText("sub")
            File(outside, "hello-outside.txt").writeText("secret")

            // Self/parent loop and mutual cycle and external escape symlinks
            Files.createSymbolicLink(File(sub, "parent-loop").toPath(), workspace.toPath())
            Files.createSymbolicLink(File(dirA, "to-b").toPath(), dirB.toPath())
            Files.createSymbolicLink(File(dirB, "to-a").toPath(), dirA.toPath())
            Files.createSymbolicLink(File(workspace, "escape").toPath(), outside.toPath())

            val matches = WorkspaceDocumentStore.search(workspace, "hello")
            val names = matches.map { it.name }.sorted()
            assertEquals(listOf("hello-root.txt", "hello-sub.txt"), names)
        } finally {
            deleteTreeNoFollow(root)
        }
    }

    @Test
    fun delete_doesNotFollowSymlinksToPersistentHomeOrSiblingDirectories() {
        val root = Files.createTempDirectory("xr-doc-delete").toFile()
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            val persistentHome = File(root, "home").apply { mkdirs() }
            val sshDir = File(persistentHome, ".ssh").apply { mkdirs() }
            val sshKey = File(sshDir, "id_ed25519").apply { writeText("PRIVATE-KEY") }

            val keepDir = File(workspace, "keep").apply { mkdirs() }
            val keepFile = File(keepDir, "important.txt").apply { writeText("keep-me") }

            val doomed = File(workspace, "doomed").apply { mkdirs() }
            File(doomed, "temp.txt").writeText("bye")
            Files.createSymbolicLink(File(doomed, "home-link").toPath(), persistentHome.toPath())
            Files.createSymbolicLink(File(doomed, "keep-link").toPath(), keepDir.toPath())

            WorkspaceDocumentStore.delete(workspace, doomed)

            assertFalse(doomed.exists())
            assertTrue("Persistent home must survive deleting a workspace directory containing a symlink", sshKey.isFile)
            assertEquals("PRIVATE-KEY", sshKey.readText())
            assertTrue("Sibling workspace directory referenced by symlink must survive", keepFile.isFile)
            assertEquals("keep-me", keepFile.readText())
        } finally {
            deleteTreeNoFollow(root)
        }
    }

    @Test
    fun listChildrenAndFileForDocId_blockSymlinksAndTraversal() {
        val root = Files.createTempDirectory("xr-doc-jail").toFile()
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            val home = File(root, "home").apply { mkdirs() }
            File(home, "secret.txt").writeText("secret")
            File(workspace, "notes.md").writeText("hi")
            Files.createSymbolicLink(File(workspace, "android").toPath(), home.toPath())

            val children = WorkspaceDocumentStore.listChildren(workspace, workspace)
            assertEquals(listOf("notes.md"), children.map { it.name })

            try {
                WorkspaceDocumentStore.fileForDocId(workspace, "workspace/../home/secret.txt")
                fail("Path traversal must throw FileNotFoundException")
            } catch (_: FileNotFoundException) {
                // expected
            }

            try {
                WorkspaceDocumentStore.fileForDocId(workspace, "workspace/android")
                fail("Symlink document must throw FileNotFoundException")
            } catch (_: FileNotFoundException) {
                // expected
            }

            try {
                WorkspaceDocumentStore.delete(workspace, workspace)
                fail("Deleting workspace root must throw FileNotFoundException")
            } catch (_: FileNotFoundException) {
                // expected
            }
        } finally {
            deleteTreeNoFollow(root)
        }
    }

    @Test
    fun createAndRename_workWithinWorkspace() {
        val root = Files.createTempDirectory("xr-doc-crud").toFile()
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            val created = WorkspaceDocumentStore.create(workspace, workspace, isDirectory = false, displayName = "hello.txt")
            assertTrue(created.isFile)
            val duplicate = WorkspaceDocumentStore.create(workspace, workspace, isDirectory = false, displayName = "hello.txt")
            assertEquals("hello.txt (2)", duplicate.name)

            val renamed = WorkspaceDocumentStore.rename(workspace, duplicate, "world.txt")
            assertEquals("world.txt", renamed.name)
            assertTrue(renamed.isFile)
            assertEquals("workspace/world.txt", WorkspaceDocumentStore.docIdForFile(workspace, renamed))
        } finally {
            deleteTreeNoFollow(root)
        }
    }

    @Test
    fun isChildDocument_blocksTraversalPrefixConfusionBackslashesAndSymlinks() {
        val root = Files.createTempDirectory("xr-doc-child").toFile()
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            val sub = File(workspace, "sub").apply { mkdirs() }
            File(sub, "a.txt").writeText("ok")
            val outside = File(root, "outside").apply { mkdirs() }
            Files.createSymbolicLink(File(sub, "link").toPath(), outside.toPath())

            assertTrue(WorkspaceDocumentStore.isChildDocument("workspace", "workspace/sub/a.txt"))
            assertTrue(WorkspaceDocumentStore.isChildDocument("workspace/sub", "workspace/sub/a.txt"))
            assertTrue(WorkspaceDocumentStore.isChildDocument("workspace/sub", "workspace/sub"))

            assertFalse(WorkspaceDocumentStore.isChildDocument("workspace/sub", "workspace/sub2/a.txt"))
            assertFalse(WorkspaceDocumentStore.isChildDocument("workspace/sub", "workspace/sub/../other.txt"))
            assertFalse(WorkspaceDocumentStore.isChildDocument("workspace", "workspace/.."))
            assertFalse(WorkspaceDocumentStore.isChildDocument("workspace", "workspace/."))
            assertFalse(WorkspaceDocumentStore.isChildDocument("workspace", "other/a.txt"))
            assertFalse(WorkspaceDocumentStore.isChildDocument("workspace", "workspace/a\\b"))
            assertFalse(WorkspaceDocumentStore.isChildDocument("workspace", "workspace/a\u0000b"))

            // Filesystem-backed overload also rejects symlinks
            assertTrue(WorkspaceDocumentStore.isChildDocument(workspace, "workspace/sub", "workspace/sub/a.txt"))
            assertFalse(WorkspaceDocumentStore.isChildDocument(workspace, "workspace/sub", "workspace/sub/link"))
            assertFalse(WorkspaceDocumentStore.isChildDocument(workspace, "workspace/sub", "workspace/sub/../other.txt"))
        } finally {
            deleteTreeNoFollow(root)
        }
    }

    @Test
    fun create_handlesConcurrentSameNameRequestsAtomically() {
        val root = Files.createTempDirectory("xr-doc-concurrent").toFile()
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            val createdNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val threads = (1..8).map {
                Thread {
                    val f = WorkspaceDocumentStore.create(workspace, workspace, isDirectory = false, displayName = "race.txt")
                    createdNames.add(f.name)
                }.apply { start() }
            }
            threads.forEach { it.join(5_000) }
            assertEquals(8, createdNames.size)
        } finally {
            deleteTreeNoFollow(root)
        }
    }
}
