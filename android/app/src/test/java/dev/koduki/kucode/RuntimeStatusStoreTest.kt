package dev.koduki.kucode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class RuntimeStatusStoreTest {

    @Test
    fun transitionsEmitConsistentAtomicSnapshotsAndEditorLaunchUrl() {
        val store = RuntimeStatusStore(
            initialWorkspacePath = "/data/workspace",
            initialProotReady = true,
        )
        val observed = CopyOnWriteArrayList<PosixRuntimeManager.StatusSnapshot>()
        store.setListener { observed.add(it) }

        val initial = store.snapshot(hasAllFilesAccess = false)
        assertEquals(PosixRuntimeManager.State.IDLE, initial.state)
        assertEquals("", initial.serverUrl)
        assertNull(store.editorLaunchUrl())

        store.update(hasAllFilesAccess = false) {
            it.copy(
                state = PosixRuntimeManager.State.STARTING,
                message = "starting",
                progress = 92,
                port = 18765,
                editorToken = null,
            )
        }
        // Not READY yet -> serverUrl and editorLaunchUrl must stay empty/null
        assertEquals("", store.snapshot(hasAllFilesAccess = false).serverUrl)
        assertNull(store.editorLaunchUrl())

        store.update(hasAllFilesAccess = true) {
            it.copy(
                state = PosixRuntimeManager.State.READY,
                message = "ready",
                progress = 100,
                port = 18765,
                editorToken = "abc123",
            )
        }
        val ready = store.snapshot(hasAllFilesAccess = true)
        assertEquals(PosixRuntimeManager.State.READY, ready.state)
        assertEquals("http://127.0.0.1:18765/?folder=/workspace", ready.serverUrl)
        assertEquals("http://127.0.0.1:18765/?folder=/workspace&tkn=abc123", store.editorLaunchUrl())
        assertTrue(ready.hasAllFilesAccess)
        assertEquals("/data/workspace", ready.workspacePath)
        assertTrue(ready.prootReady)

        assertEquals(2, observed.size)
        assertEquals(PosixRuntimeManager.State.STARTING, observed[0].state)
        assertEquals(PosixRuntimeManager.State.READY, observed[1].state)

        // Clearing listener stops further deliveries
        store.setListener(null)
        store.update(hasAllFilesAccess = false) {
            it.copy(state = PosixRuntimeManager.State.IDLE, port = 0, editorToken = null)
        }
        assertEquals(2, observed.size)
        assertNull(store.editorLaunchUrl())
    }

    @Test
    fun logBufferBoundsEntriesAndSupportsClear() {
        val store = RuntimeStatusStore(maxLogLines = 3)
        store.appendLog("line-1")
        store.appendLog("line-2")
        store.appendLog("line-3")
        store.appendLog("line-4")

        assertEquals("line-2\nline-3\nline-4", store.getLogsText())

        store.clearLogs()
        assertEquals("", store.getLogsText())
    }
}
