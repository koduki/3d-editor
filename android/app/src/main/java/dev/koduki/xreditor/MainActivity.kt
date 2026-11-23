package dev.koduki.xreditor

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Single-window Android XR Activity hosting:
 * 1. Minimal pure-black boot/diagnostics page (`file:///android_asset/boot.html`)
 *    during first-run extraction or when toggled via Ctrl+Alt+D
 * 2. Embedded `openvscode-server` (`http://127.0.0.1:<port>/?folder=/workspace`)
 *    once ready
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var runtimeManager: PosixRuntimeManager
    private val shortcutRouter = HardwareShortcutRouter()

    private var showingEditor = false
    private var autoOpenedEditor = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.setBackgroundColor(Color.BLACK)
        runtimeManager = PosixRuntimeManager.getInstance(this)

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = false
                useWideViewPort = true
                loadWithOverviewMode = true
                // Desktop user agent ensures VS Code Web treats XR monitor as full desktop
                userAgentString = settings.userAgentString
                    .replace("Mobile", "")
                    .replace("; wv", "")
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val uri = request.url
                    // Keep localhost openvscode-server & local boot page inside WebView
                    if (uri.scheme == "file" || uri.host == "127.0.0.1" || uri.host == "localhost") {
                        return false
                    }
                    // Open external URLs (e.g. GitHub auth device URL) in XR browser
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                    return true
                }
            }

            webChromeClient = WebChromeClient()
        }

        val bridge = BootHostBridge(
            runtimeManager = runtimeManager,
            onOpenEditor = { runOnUiThread { loadEditorIfReady() } },
            onRequestAllFilesAccess = { runOnUiThread { openAllFilesPermissionSettings() } },
        )
        webView.addJavascriptInterface(bridge, "XREditorHost")
        setContentView(webView)

        runtimeManager.setStatusListener { snapshot ->
            runOnUiThread {
                if (!showingEditor) {
                    webView.evaluateJavascript(
                        "window.__onHostStatus && window.__onHostStatus(${snapshot.toJsonString()});",
                        null,
                    )
                }
                if (snapshot.state == PosixRuntimeManager.State.READY && !autoOpenedEditor) {
                    autoOpenedEditor = true
                    loadEditorIfReady()
                }
            }
        }

        loadBootPage()
        ServerKeepAliveService.start(this)
        runtimeManager.startIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (!showingEditor && ::webView.isInitialized) {
            val snapshot = runtimeManager.getStatus()
            webView.evaluateJavascript(
                "window.__onHostStatus && window.__onHostStatus(${snapshot.toJsonString()});",
                null,
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Ctrl+Alt+D toggles between embedded openvscode-server and the boot/fallback CLI view
        if (shortcutRouter.isDiagnosticsToggle(event)) {
            if (showingEditor) {
                loadBootPage()
            } else {
                loadEditorIfReady()
            }
            return true
        }

        val matched = shortcutRouter.match(event)
        if (matched != null && showingEditor && ::webView.isInitialized) {
            val specJson = matched.toJsonString()
            val js = """
                (function(spec) {
                  var target = document.activeElement || document.body || window;
                  var ev = new KeyboardEvent('keydown', {
                    key: spec.key,
                    code: spec.code,
                    keyCode: spec.keyCode,
                    which: spec.keyCode,
                    ctrlKey: spec.ctrlKey,
                    shiftKey: spec.shiftKey,
                    altKey: spec.altKey,
                    metaKey: spec.metaKey,
                    bubbles: true,
                    cancelable: true
                  });
                  target.dispatchEvent(ev);
                })($specJson);
            """.trimIndent()
            webView.evaluateJavascript(js, null)
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        runtimeManager.setStatusListener(null)
        if (isFinishing) {
            runtimeManager.stop()
            ServerKeepAliveService.stop(this)
        }
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("XREditorHost")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun loadBootPage() {
        showingEditor = false
        webView.loadUrl("file:///android_asset/boot.html")
        webView.requestFocus()
    }

    private fun loadEditorIfReady() {
        val status = runtimeManager.getStatus()
        if (status.state == PosixRuntimeManager.State.READY && status.serverUrl.isNotEmpty()) {
            showingEditor = true
            webView.loadUrl(status.serverUrl)
            webView.requestFocus()
        }
    }

    private fun openAllFilesPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }
}
