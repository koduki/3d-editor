package dev.koduki.xreditor

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareShortcutRouterTest {

    @Test
    fun matchKey_interceptsCoreEditorShortcutsOnly() {
        val router = HardwareShortcutRouter()

        val ctrlS = router.matchKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S, ctrl = true, meta = false, shift = false, alt = false)
        assertNotNull(ctrlS)
        assertEquals("s", ctrlS?.key)
        assertEquals("KeyS", ctrlS?.code)
        assertEquals(83, ctrlS?.keyCode)
        assertEquals(true, ctrlS?.ctrlKey)

        val ctrlW = router.matchKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, ctrl = true, meta = false, shift = false, alt = false)
        assertNotNull(ctrlW)
        assertEquals("w", ctrlW?.key)

        val ctrlGrave = router.matchKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_GRAVE, ctrl = true, meta = false, shift = false, alt = false)
        assertNotNull(ctrlGrave)
        assertEquals("`", ctrlGrave?.key)

        // Plain typing and key-up events pass through untouched
        assertNull(router.matchKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S, ctrl = false, meta = false, shift = false, alt = false))
        assertNull(router.matchKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_S, ctrl = true, meta = false, shift = false, alt = false))
        assertNull(router.matchKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D, ctrl = true, meta = false, shift = false, alt = true))
    }
}
