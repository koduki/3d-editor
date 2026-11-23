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
    )

    fun isDiagnosticsToggleKey(
        action: Int,
        keyCode: Int,
        ctrl: Boolean,
        meta: Boolean,
        alt: Boolean,
    ): Boolean {
        return action == KeyEvent.ACTION_DOWN &&
            (ctrl || meta) &&
            alt &&
            keyCode == KeyEvent.KEYCODE_D
    }
}
