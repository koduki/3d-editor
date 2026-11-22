package dev.koduki.xracp

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Intercepts desktop physical keyboard shortcuts at the Android Activity level
 * before Chrome/WebView or OS handlers swallow them (e.g. Ctrl+P print, Ctrl+W close,
 * Ctrl+L focus bar, Ctrl+S save page).
 */
class HardwareShortcutRouter {

    data class WebKeySpec(
        val key: String,
        val code: String,
        val ctrlKey: Boolean,
        val shiftKey: Boolean,
        val altKey: Boolean,
        val metaKey: Boolean,
    ) {
        fun toJsonString(): String = JSONObject()
            .put("key", key)
            .put("code", code)
            .put("ctrlKey", ctrlKey)
            .put("shiftKey", shiftKey)
            .put("altKey", altKey)
            .put("metaKey", metaKey)
            .toString()
    }

    @Volatile
    private var registeredChords: Set<String> = DEFAULT_CHORDS

    fun updateRegisteredShortcuts(shortcutsJson: String) {
        runCatching {
            val arr = JSONArray(shortcutsJson)
            val parsed = buildSet {
                for (i in 0 until arr.length()) {
                    val normalized = normalizeChordLabel(arr.optString(i))
                    if (normalized.isNotEmpty()) add(normalized)
                }
            }
            if (parsed.isNotEmpty()) {
                registeredChords = parsed
            }
        }
    }

    /**
     * Returns a WebKeySpec if this Android KeyEvent matches a registered desktop shortcut,
     * or null so normal typing / IME composition passes straight through to WebView.
     */
    fun match(event: KeyEvent): WebKeySpec? {
        if (event.action != KeyEvent.ACTION_DOWN) return null
        if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN) return null

        val ctrl = event.isCtrlPressed
        val meta = event.isMetaPressed
        val shift = event.isShiftPressed
        val alt = event.isAltPressed

        // Plain unmodified keys (except F1) must never be intercepted
        if (!ctrl && !meta && !alt && event.keyCode != KeyEvent.KEYCODE_F1) {
            return null
        }

        val mapped = mapKeyCode(event.keyCode, shift) ?: return null
        val chordLabel = buildChordLabel(
            ctrlOrMeta = ctrl || meta,
            shift = shift,
            alt = alt,
            token = mapped.chordToken,
        )

        if (!registeredChords.contains(chordLabel)) {
            return null
        }

        return WebKeySpec(
            key = mapped.webKey,
            code = mapped.webCode,
            ctrlKey = ctrl || meta,
            shiftKey = shift,
            altKey = alt,
            metaKey = false,
        )
    }

    private data class KeyMapping(val webKey: String, val webCode: String, val chordToken: String)

    private fun mapKeyCode(keyCode: Int, shift: Boolean): KeyMapping? = when (keyCode) {
        KeyEvent.KEYCODE_P -> KeyMapping(if (shift) "P" else "p", "KeyP", "p")
        KeyEvent.KEYCODE_F -> KeyMapping(if (shift) "F" else "f", "KeyF", "f")
        KeyEvent.KEYCODE_S -> KeyMapping(if (shift) "S" else "s", "KeyS", "s")
        KeyEvent.KEYCODE_W -> KeyMapping(if (shift) "W" else "w", "KeyW", "w")
        KeyEvent.KEYCODE_B -> KeyMapping(if (shift) "B" else "b", "KeyB", "b")
        KeyEvent.KEYCODE_J -> KeyMapping(if (shift) "J" else "j", "KeyJ", "j")
        KeyEvent.KEYCODE_L -> KeyMapping(if (shift) "L" else "l", "KeyL", "l")
        KeyEvent.KEYCODE_GRAVE -> KeyMapping("`", "Backquote", "`")
        KeyEvent.KEYCODE_SPACE -> KeyMapping(" ", "Space", "space")
        KeyEvent.KEYCODE_F1 -> KeyMapping("F1", "F1", "f1")
        KeyEvent.KEYCODE_1 -> KeyMapping("1", "Digit1", "1")
        KeyEvent.KEYCODE_2 -> KeyMapping("2", "Digit2", "2")
        KeyEvent.KEYCODE_3 -> KeyMapping("3", "Digit3", "3")
        KeyEvent.KEYCODE_4 -> KeyMapping("4", "Digit4", "4")
        else -> null
    }

    companion object {
        val DEFAULT_CHORDS: Set<String> = setOf(
            "ctrl+p",
            "ctrl+shift+p",
            "ctrl+shift+f",
            "ctrl+s",
            "ctrl+w",
            "ctrl+b",
            "ctrl+j",
            "ctrl+`",
            "ctrl+l",
            "ctrl+space",
            "f1",
            "alt+1",
            "alt+2",
            "alt+3",
            "alt+4",
        )

        fun normalizeChordLabel(raw: String): String {
            val parts = raw.trim().lowercase().split("+").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return ""
            val hasCtrl = parts.any { it == "ctrl" || it == "cmd" || it == "meta" }
            val hasShift = parts.any { it == "shift" }
            val hasAlt = parts.any { it == "alt" }
            val token = parts.lastOrNull { it !in setOf("ctrl", "cmd", "meta", "shift", "alt") } ?: return ""
            return buildChordLabel(hasCtrl, hasShift, hasAlt, token)
        }

        private fun buildChordLabel(ctrlOrMeta: Boolean, shift: Boolean, alt: Boolean, token: String): String {
            val out = mutableListOf<String>()
            if (ctrlOrMeta) out.add("ctrl")
            if (shift) out.add("shift")
            if (alt) out.add("alt")
            out.add(token.lowercase())
            return out.joinToString("+")
        }
    }
}
