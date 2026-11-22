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
    fun match_interceptsRegisteredShortcutsAndIgnoresPlainTyping() {
        val router = HardwareShortcutRouter()

        val ctrlP = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P, 0, KeyEvent.META_CTRL_ON)
        val matched = router.match(ctrlP)
        assertNotNull(matched)
        assertEquals("p", matched?.key)
        assertEquals(true, matched?.ctrlKey)

        val plainP = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P, 0, 0)
        assertNull(router.match(plainP))
    }
}
