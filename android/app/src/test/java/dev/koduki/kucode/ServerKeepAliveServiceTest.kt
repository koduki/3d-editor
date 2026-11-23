package dev.koduki.kucode

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerKeepAliveServiceTest {

    @Test
    fun resolveAction_handlesSystemNullRestartStopAndNormalStart() {
        assertEquals(
            ServerKeepAliveService.KeepAliveAction.STOP_SERVICE_ONLY,
            ServerKeepAliveService.resolveAction(intentAction = null, hasIntent = false),
        )
        assertEquals(
            ServerKeepAliveService.KeepAliveAction.STOP_RUNTIME_AND_SERVICE,
            ServerKeepAliveService.resolveAction(
                intentAction = ServerKeepAliveService.ACTION_STOP,
                hasIntent = true,
            ),
        )
        assertEquals(
            ServerKeepAliveService.KeepAliveAction.START_FOREGROUND_AND_RUNTIME,
            ServerKeepAliveService.resolveAction(intentAction = null, hasIntent = true),
        )
    }
}
