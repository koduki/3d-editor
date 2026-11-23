package dev.koduki.xreditor

import android.view.KeyEvent
import org.json.JSONObject

/**
 * Intercepts essential desktop editor shortcuts before Android XR / WebView swallows them
 * (e.g. Ctrl+S save page, Ctrl+W close tab, Ctrl+O open, Ctrl+P print, Ctrl+` terminal).
 * Plain typing and IME composition always pass straight through untouched.
 */
class HardwareShortcutRouter {

    data class WebKeySpec(
        val key: String,
        val code: String,
        val keyCode: Int,
        val ctrlKey: Boolean,
        val shiftKey: Boolean,
        val altKey: Boolean,
        val metaKey: Boolean,
    ) {
        fun toJsonString(): String = JSONObject()
            .put("key", key)
            .put("code", code)
            .put("keyCode", keyCode)
            .put("ctrlKey", ctrlKey)
            .put("shiftKey", shiftKey)
            .put("altKey", altKey)
            .put("metaKey", metaKey)
            .toString()
    }

    fun match(event: KeyEvent): WebKeySpec? = matchKey(
        action = event.action,
        keyCode = event.keyCode,
        ctrl = event.isCtrlPressed,
        meta = event.isMetaPressed,
        shift = event.isShiftPressed,
        alt = event.isAltPressed,
    )

    fun matchKey(
        action: Int,
        keyCode: Int,
        ctrl: Boolean,
        meta: Boolean,
        shift: Boolean,
        alt: Boolean,
    ): WebKeySpec? {
        if (action != KeyEvent.ACTION_DOWN) return null
        if (!ctrl && !meta) return null

        // Reserve Ctrl+Alt+D for toggling the native diagnostics / fallback console drawer
        if (alt) return null

        val mapped = when (keyCode) {
            KeyEvent.KEYCODE_S -> Triple(if (shift) "S" else "s", "KeyS", 83)
            KeyEvent.KEYCODE_W -> Triple(if (shift) "W" else "w", "KeyW", 87)
            KeyEvent.KEYCODE_O -> Triple(if (shift) "O" else "o", "KeyO", 79)
            KeyEvent.KEYCODE_P -> Triple(if (shift) "P" else "p", "KeyP", 80)
            KeyEvent.KEYCODE_B -> Triple(if (shift) "B" else "b", "KeyB", 66)
            KeyEvent.KEYCODE_J -> Triple(if (shift) "J" else "j", "KeyJ", 74)
            KeyEvent.KEYCODE_GRAVE -> Triple("`", "Backquote", 192)
            else -> return null
        }

        return WebKeySpec(
            key = mapped.first,
            code = mapped.second,
            keyCode = mapped.third,
            ctrlKey = true,
            shiftKey = shift,
            altKey = false,
            metaKey = false,
        )
    }

    fun isDiagnosticsToggle(event: KeyEvent): Boolean {
        return event.action == KeyEvent.ACTION_DOWN &&
            (event.isCtrlPressed || event.isMetaPressed) &&
            event.isAltPressed &&
            event.keyCode == KeyEvent.KEYCODE_D
    }
}
