package dev.koduki.xracp

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main Android 15 / Android XR Spatial Desktop IDE Activity.
 * - Pure #000000 black OST startup surface (no white flash on XREAL AURA Micro-OLED)
 * - Auto-probes localhost:8765 (Android 15 AVF Linux / PRoot) and falls back cleanly
 *   to bundled WebViewAssetLoader assets (for Tailscale remote profiles & Offline Demo Mode)
 * - Intercepts hardware keyboard desktop shortcuts before WebView/OS swallows them
 * - Forwards window focus state to drive 50% automatic blur dimming in XR multi-window
 */
open class WorkbenchActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val shortcutRouter = HardwareShortcutRouter()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingMicPermissionRequest: PermissionRequest? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val req = pendingMicPermissionRequest
        pendingMicPermissionRequest = null
        if (req != null) {
            if (granted) req.grant(req.resources) else req.deny()
        }
    }

    protected open fun initialFocusPane(): String? = intent?.getStringExtra(EXTRA_FOCUS_PANE)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.setBackgroundColor(Color.BLACK)

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(BUNDLED_ASSET_DOMAIN)
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
                // Use desktop-class WebView rendering density on spatial monitors
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (request.url.host == BUNDLED_ASSET_DOMAIN) {
                        val rewritten = rewriteBundledUrl(request.url)
                        return assetLoader.shouldInterceptRequest(rewritten)
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (!wantsAudio) {
                        request.deny()
                        return
                    }
                    val hasMic = ContextCompat.checkSelfPermission(
                        this@WorkbenchActivity,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasMic) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        pendingMicPermissionRequest = request
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }

        val hasSpatialApi = packageManager.hasSystemFeature("android.software.xr.api.spatial")
        val hostBridge = XrHostBridge(
            shortcutRouter = shortcutRouter,
            hasSpatialApi = hasSpatialApi,
            onOpenSpatialPanel = { paneId ->
                runOnUiThread { launchDetachedSpatialPanel(paneId) }
            },
            onWindowTitleChanged = { title ->
                runOnUiThread {
                    @Suppress("DEPRECATION")
                    setTaskDescription(ActivityManager.TaskDescription(title))
                }
            },
        )
        webView.addJavascriptInterface(hostBridge, "XRHostBridge")

        setContentView(webView)
        BridgeKeepAliveService.start(this)

        // Probe Android 15 local bridge first; fall back seamlessly to bundled app assets
        uiScope.launch {
            val probe = withContext(Dispatchers.IO) { LocalBridgeProbe.probe() }
            val querySuffix = buildStartupQuery(initialFocusPane())
            val targetUrl = if (probe.reachable) {
                "${probe.origin}/$querySuffix"
            } else {
                "https://$BUNDLED_ASSET_DOMAIN/index.html$querySuffix"
            }
            webView.loadUrl(targetUrl)
            webView.requestFocus()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val matched = shortcutRouter.match(event)
        if (matched != null && ::webView.isInitialized) {
            val js = "window.__xrDispatchShortcut && window.__xrDispatchShortcut(${matched.toJsonString()});"
            webView.evaluateJavascript(js, null)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "window.__xrSetWindowFocused && window.__xrSetWindowFocused($hasFocus);",
                null,
            )
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        if (isFinishing && this::class.java == WorkbenchActivity::class.java) {
            BridgeKeepAliveService.stop(this)
        }
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("XRHostBridge")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun launchDetachedSpatialPanel(paneId: String) {
        val intent = Intent(this, SpatialPanelActivity::class.java).apply {
            putExtra(EXTRA_FOCUS_PANE, paneId)
            flags = Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        }
        startActivity(intent)
    }

    private fun rewriteBundledUrl(uri: Uri): Uri {
        val path = uri.path ?: "/"
        val mappedPath = when {
            path == "/" || path.isEmpty() -> "/www/index.html"
            path.startsWith("/www/") -> path
            else -> "/www$path"
        }
        return uri.buildUpon().path(mappedPath).build()
    }

    private fun buildStartupQuery(focusPane: String?): String {
        if (focusPane.isNullOrBlank()) return ""
        return "?focusPane=${Uri.encode(focusPane)}"
    }

    companion object {
        const val BUNDLED_ASSET_DOMAIN = "appassets.androidplatform.net"
        const val EXTRA_FOCUS_PANE = "focusPane"
    }
}
