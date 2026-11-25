package dev.koduki.kucode

import android.view.KeyEvent

/**
 * Host-level physical keyboard shortcut detector.
 *
 * Android WebView does NOT steal browser shortcuts like Ctrl+S / Ctrl+W / Ctrl+P / Ctrl+`,
 * so normal editor & integrated terminal (xterm.js) keys must pass straight through as real,
 * trusted KeyEvents. Only the host-owned diagnostics toggle (Ctrl+Alt+D) is intercepted here.
 */
class HardwareShortcutRouter {

    fun isDiagnosticsToggle(event: KeyEvent): Boolean = isDiagnosticsToggleKey(
        action = event.action,
        keyCode = event.keyCode,
        ctrl = event.isCtrlPressed,
        meta = event.isMetaPressed,
        alt = event.isAltPressed,
        shift = event.isShiftPressed,
        repeatCount = event.repeatCount,
    )

    fun isDiagnosticsToggleKey(
        action: Int,
        keyCode: Int,
        ctrl: Boolean,
        meta: Boolean,
        alt: Boolean,
        shift: Boolean = false,
        repeatCount: Int = 0,
    ): Boolean {
        return action == KeyEvent.ACTION_DOWN &&
            repeatCount == 0 &&
            (ctrl || meta) &&
            alt &&
            !shift &&
            keyCode == KeyEvent.KEYCODE_D
    }
}
