package dev.koduki.xracp

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareShortcutRouterTest {

    @Test
    fun normalizeChordLabel_handlesAliasesAndOrdering() {
        assertEquals("ctrl+shift+p", HardwareShortcutRouter.normalizeChordLabel("Cmd+Shift+P"))
        assertEquals("ctrl+p", HardwareShortcutRouter.normalizeChordLabel("Ctrl + P"))
        assertEquals("alt+3", HardwareShortcutRouter.normalizeChordLabel("Alt+3"))
        assertEquals("f1", HardwareShortcutRouter.normalizeChordLabel("F1"))
    }

    @Test
    fun matchKey_interceptsRegisteredShortcutsAndIgnoresPlainTyping() {
        val router = HardwareShortcutRouter()

        val ctrlP = router.matchKey(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_P,
            ctrl = true,
            meta = false,
            shift = false,
            alt = false,
        )
        assertNotNull(ctrlP)
        assertEquals("p", ctrlP?.key)
        assertEquals("KeyP", ctrlP?.code)
        assertEquals(true, ctrlP?.ctrlKey)
        assertEquals(false, ctrlP?.shiftKey)

        val ctrlShiftP = router.matchKey(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_P,
            ctrl = true,
            meta = false,
            shift = true,
            alt = false,
        )
        assertNotNull(ctrlShiftP)
        assertEquals("P", ctrlShiftP?.key)
        assertEquals(true, ctrlShiftP?.shiftKey)

        // Plain typing without modifiers must pass straight through to WebView / IME
        val plainP = router.matchKey(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_P,
            ctrl = false,
            meta = false,
            shift = false,
            alt = false,
        )
        assertNull(plainP)

        // Key-up events must not fire duplicate shortcuts
        val ctrlPUp = router.matchKey(
            action = KeyEvent.ACTION_UP,
            keyCode = KeyEvent.KEYCODE_P,
            ctrl = true,
            meta = false,
            shift = false,
            alt = false,
        )
        assertNull(ctrlPUp)
    }

    @Test
    fun updateRegisteredShortcuts_restrictsInterceptedSet() {
        val router = HardwareShortcutRouter()
        router.updateRegisteredShortcuts("""["Ctrl+S","Alt+2"]""")

        assertNotNull(
            router.matchKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S, ctrl = true, meta = false, shift = false, alt = false),
        )
        assertNull(
            router.matchKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P, ctrl = true, meta = false, shift = false, alt = false),
        )
    }
}
