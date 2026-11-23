package dev.koduki.kucode

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareShortcutRouterTest {

    @Test
    fun isDiagnosticsToggleKey_matchesOnlyCtrlOrMetaAltDOnKeyDown() {
        val router = HardwareShortcutRouter()

        assertTrue(router.isDiagnosticsToggleKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D, ctrl = true, meta = false, alt = true))
        assertTrue(router.isDiagnosticsToggleKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D, ctrl = false, meta = true, alt = true))

        // Normal editor/terminal shortcuts must NOT be intercepted
        assertFalse(router.isDiagnosticsToggleKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S, ctrl = true, meta = false, alt = false))
        assertFalse(router.isDiagnosticsToggleKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, ctrl = true, meta = false, alt = false))
        assertFalse(router.isDiagnosticsToggleKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D, ctrl = true, meta = false, alt = false))
        assertFalse(router.isDiagnosticsToggleKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D, ctrl = false, meta = false, alt = false))
        assertFalse(router.isDiagnosticsToggleKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_D, ctrl = true, meta = false, alt = true))
    }
}
