package dev.koduki.xracp

import android.os.Build
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Native JavascriptInterface exposed to the Web Spatial Workbench as `window.XRHostBridge`.
 * Matches `public/xr-host.js` method-for-method.
 */
class XrHostBridge(
    private val shortcutRouter: HardwareShortcutRouter,
    private val hasSpatialApi: Boolean,
    private val onOpenSpatialPanel: (paneId: String) -> Unit,
    private val onWindowTitleChanged: (title: String) -> Unit,
) {

    @JavascriptInterface
    fun getCapabilities(): String {
        val local = LocalBridgeProbe.probe(timeoutMs = 350)
        return JSONObject()
            .put("nativeHost", true)
            .put("spatialPanels", true)
            .put("hasSpatialApi", hasSpatialApi)
            .put("shortcutCapture", true)
            .put("android15Avf", Build.VERSION.SDK_INT >= 35)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("localBridgeReachable", local.reachable)
            .toString()
    }

    @JavascriptInterface
    fun registerShortcuts(shortcutsJson: String) {
        shortcutRouter.updateRegisteredShortcuts(shortcutsJson)
    }

    @JavascriptInterface
    fun openSpatialPanel(paneId: String) {
        val sanitized = when (paneId.lowercase()) {
            "ai", "editor", "console", "explorer" -> paneId.lowercase()
            else -> "ai"
        }
        onOpenSpatialPanel(sanitized)
    }

    @JavascriptInterface
    fun setWindowTitle(title: String) {
        if (title.isNotBlank()) {
            onWindowTitleChanged(title.take(120))
        }
    }

    @JavascriptInterface
    fun probeLocalBridge(): String {
        return LocalBridgeProbe.probe(timeoutMs = 800).toJsonString()
    }
}
