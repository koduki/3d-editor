package dev.koduki.xreditor

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-window Android XR Activity hosting:
 * 1. Pure-black boot/diagnostics page (`file:///android_asset/boot.html`) with isolated `XREditorHost` bridge
 * 2. Embedded `openvscode-server` (`http://127.0.0.1:18765/?folder=/workspace`) with `XREditorHost` removed,
 *    native trusted KeyEvents flowing directly to Monaco / xterm.js, and XR multi-window focus dimming
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rootFrame: FrameLayout
    private lateinit var webView: WebView
    private lateinit var runtimeManager: PosixRuntimeManager
    private lateinit var bootBridge: BootHostBridge
    private val shortcutRouter = HardwareShortcutRouter()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var showingEditor = false
    private var autoOpenedEditor = false
    private var hostBridgeAttached = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.setBackgroundColor(Color.BLACK)
        runtimeManager = PosixRuntimeManager.getInstance(this)

        bootBridge = BootHostBridge(
            context = applicationContext,
            runtimeManager = runtimeManager,
            onOpenEditor = { runOnUiThread { loadEditorIfReady() } },
            onRequestAllFilesAccess = { runOnUiThread { openAllFilesPermissionSettings() } },
            onCopyToClipboard = { text -> copyTextToClipboard(text) },
            onDiagnosticsCompleted = { report ->
                runOnUiThread {
                    if (!showingEditor && ::webView.isInitialized) {
                        webView.evaluateJavascript(
                            "window.__onDiagnosticsResult && window.__onDiagnosticsResult(${JSONObject.quote(report)});",
                            null,
                        )
                    }
                }
            },
            onCommandCompleted = { command, resultJson ->
                runOnUiThread {
                    if (!showingEditor && ::webView.isInitialized) {
                        webView.evaluateJavascript(
                            "window.__onCommandResult && window.__onCommandResult(${JSONObject.quote(command)}, $resultJson);",
                            null,
                        )
                    }
                }
            },
        )

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
                // Prevent accidental trackpad pinch-zoom from breaking VS Code layout;
                // UI scaling is controlled cleanly via VS Code `window.zoomLevel`.
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
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
                    val editorPort = runtimeManager.getStatus().port
                    if (PosixRuntimeConfig.shouldKeepInsideWebView(uri.scheme, uri.host, uri.port, editorPort)) {
                        return false
                    }
                    // Open external URLs AND dev servers on other localhost ports (e.g. :3000) in XR browser
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                    return true
                }
            }

            webChromeClient = WebChromeClient()
        }

        val borderPx = (resources.displayMetrics.density * 1.5f).toInt().coerceAtLeast(1)
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BORDER_FOCUSED)
            setPadding(borderPx, borderPx, borderPx, borderPx)
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(rootFrame)

        // Prevent stray Back/Esc gestures in XR from accidentally killing the editor & server
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!showingEditor && runtimeManager.getStatus().state == PosixRuntimeManager.State.READY) {
                        loadEditorIfReady()
                    }
                }
            },
        )

        runtimeManager.setHostActions(
            PosixRuntimeManager.HostActions(
                onOpenBootPage = { runOnUiThread { loadBootPage() } },
                onRequestStoragePermission = { runOnUiThread { openAllFilesPermissionSettings() } },
                onCopyToClipboard = { text -> copyTextToClipboard(text) },
            ),
        )

        runtimeManager.setStatusListener { snapshot ->
            runOnUiThread {
                if (!showingEditor && ::webView.isInitialized) {
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::webView.isInitialized && ::rootFrame.isInitialized) {
            // Clear visual cue for keyboard focus + lower OST glare when looking at sibling XR windows
            webView.alpha = if (hasFocus) 1.0f else 0.55f
            rootFrame.setBackgroundColor(if (hasFocus) COLOR_BORDER_FOCUSED else COLOR_BORDER_UNFOCUSED)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-assert foreground keep-alive if Android 15 dataSync service timed out earlier
        ServerKeepAliveService.start(this)
        if (!showingEditor && ::webView.isInitialized) {
            val snapshot = runtimeManager.getStatus()
            webView.evaluateJavascript(
                "window.__onHostStatus && window.__onHostStatus(${snapshot.toJsonString()});",
                null,
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Only intercept host-owned Ctrl+Alt+D; let all real editor/terminal KeyEvents reach WebView untouched
        if (shortcutRouter.isDiagnosticsToggle(event)) {
            if (showingEditor) {
                loadBootPage()
            } else {
                loadEditorIfReady()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        runtimeManager.setStatusListener(null)
        runtimeManager.setHostActions(null)
        if (isFinishing) {
            runtimeManager.stop()
            ServerKeepAliveService.stop(this)
        }
        if (::webView.isInitialized) {
            detachBootBridge()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun attachBootBridge() {
        if (!hostBridgeAttached) {
            webView.addJavascriptInterface(bootBridge, "XREditorHost")
            hostBridgeAttached = true
        }
        webView.settings.allowFileAccess = true
    }

    private fun detachBootBridge() {
        if (hostBridgeAttached) {
            webView.removeJavascriptInterface("XREditorHost")
            hostBridgeAttached = false
        }
        webView.settings.allowFileAccess = false
    }

    private fun loadBootPage() {
        showingEditor = false
        attachBootBridge()
        webView.loadUrl("file:///android_asset/boot.html")
        webView.requestFocus()
    }

    private fun loadEditorIfReady() {
        val status = runtimeManager.getStatus()
        if (status.state == PosixRuntimeManager.State.READY && status.serverUrl.isNotEmpty()) {
            showingEditor = true
            detachBootBridge()
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

    /**
     * Always executes ClipboardManager.setPrimaryClip on the Android MainLooper so calls from
     * LocalDiagnosticsServer worker threads or WebView's JavaBridge thread never fail silently.
     */
    private fun copyTextToClipboard(text: String): Boolean {
        val action = {
            runCatching {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@runCatching false
                val clip = ClipData.newPlainText("XR-Editor Diagnostics", text)
                clipboard.setPrimaryClip(clip)
                true
            }.getOrDefault(false)
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return action()
        }

        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result.set(action())
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        return result.get()
    }

    companion object {
        private const val COLOR_BORDER_FOCUSED = 0xFF38BDF8.toInt()
        private const val COLOR_BORDER_UNFOCUSED = 0xFF18181B.toInt()
    }
}
