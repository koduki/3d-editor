package dev.koduki.kucode

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(MediumTest::class)
class ServerStartupTest {
    @Test
    fun slowServerIsReapedBeforeRetry() = runBlocking {
        // The child waits for stdin until the test closes it or the startup timeout kills it.
        val slow = ProcessBuilder("cat").start()
        try {
            assertEquals(ServerStartup.TIMED_OUT, awaitServerStartup(slow, 100) { false })
            assertFalse(slow.isAlive)
            val retry = ProcessBuilder("cat").start()
            try {
                assertEquals(ServerStartup.READY, awaitServerStartup(retry, 1000) { true })
                assertTrue(retry.isAlive)
            } finally {
                retry.outputStream.close()
                retry.waitFor()
            }
        } finally {
            slow.destroyForcibly()
        }
        Unit
    }
}
