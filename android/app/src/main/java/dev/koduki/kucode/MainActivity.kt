package dev.koduki.kucode

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
import android.os.Message
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-window Android XR Activity hosting:
 * 1. Pure-black boot/diagnostics page (`file:///android_asset/boot.html`) with isolated `KuCodeHost` bridge
 * 2. Dedicated authenticated editor WebView, without a native bridge, with:
 *    - Multi-window popup interception (`setSupportMultipleWindows(true)` + `onCreateWindow`) so
 *      `window.open(..., "_blank", "noopener")` (e.g. `gh auth` or VS Code external links) opens a
 *      sibling XR browser window without triggering `beforeunload` / VS Code workbench shutdown
 *    - Automatic WebView renderer crash/LMKD recovery (`onRenderProcessGone`)
 *    - Native trusted KeyEvents flowing directly to Monaco / xterm.js, and XR multi-window focus dimming
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rootFrame: FrameLayout
    private lateinit var webView: WebView
    private lateinit var runtimeManager: PosixRuntimeManager
    private lateinit var telemetry: SessionTelemetryManager
    private lateinit var bootBridge: BootHostBridge
    private val shortcutRouter = HardwareShortcutRouter()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var showingEditor = false
    private var autoOpenedEditor = false
    private lateinit var bootView: WebView
    private var loadedEditorUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.setBackgroundColor(Color.BLACK)
        telemetry = SessionTelemetryManager.getInstance(this).also {
            it.ensureSessionActive("main_activity_create")
        }
        runtimeManager = PosixRuntimeManager.getInstance(this)

        bootBridge = BootHostBridge(
            context = applicationContext,
            runtimeManager = runtimeManager,
            telemetryManager = telemetry,
            onOpenEditor = { runOnUiThread { loadEditorIfReady() } },
            onRequestAllFilesAccess = { runOnUiThread { openAllFilesPermissionSettings() } },
            onOpenNotificationSettings = { runOnUiThread { openNotificationPermissionSettings() } },
            onOpenBatterySettings = { runOnUiThread { openBatteryOptimizationSettings() } },
            onCopyToClipboard = { text -> copyTextToClipboard(text) },
            onDiagnosticsCompleted = { report ->
                runOnUiThread {
                    if (!isDestroyed && ::bootView.isInitialized) {
                        bootView.evaluateJavascript(
                            "window.__onDiagnosticsResult && window.__onDiagnosticsResult(${JSONObject.quote(report)});",
                            null,
                        )
                    }
                }
            },
            onCommandCompleted = { command, resultJson ->
                runOnUiThread {
                    if (!isDestroyed && ::bootView.isInitialized) {
                        bootView.evaluateJavascript(
                            "window.__onCommandResult && window.__onCommandResult(${JSONObject.quote(command)}, $resultJson);",
                            null,
                        )
                    }
                }
            },
            onSessionReportLoaded = { selector, format, content ->
                runOnUiThread {
                    if (!isDestroyed && ::bootView.isInitialized) {
                        bootView.evaluateJavascript(
                            "window.__onSessionReportLoaded && window.__onSessionReportLoaded(${JSONObject.quote(selector)}, ${JSONObject.quote(format)}, ${JSONObject.quote(content)});",
                            null,
                        )
                    }
                }
            },
            onSessionExported = { message ->
                runOnUiThread {
                    if (!isDestroyed && ::bootView.isInitialized) {
                        bootView.evaluateJavascript(
                            "window.__onSessionExported && window.__onSessionExported(${JSONObject.quote(message)});",
                            null,
                        )
                    }
                }
            },
        )

        webView = createEditorWebView()

        bootView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    if (consoleMessage != null) {
                        telemetry.recordWebViewConsole(
                            viewTag = "boot",
                            levelName = consoleMessage.messageLevel()?.name ?: "LOG",
                            message = consoleMessage.message().orEmpty(),
                            sourceId = consoleMessage.sourceId(),
                            lineNumber = consoleMessage.lineNumber(),
                        )
                    }
                    return true
                }
            }
            addJavascriptInterface(bootBridge, "KuCodeHost")
            loadUrl("file:///android_asset/boot.html")
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
        rootFrame.addView(bootView)
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
                onReadFromClipboard = { readTextFromClipboard() },
                onOpenExternalUrl = { rawUrl ->
                    runOnMainThreadBlocking(false) {
                        val parsed = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return@runOnMainThreadBlocking false
                        openExternalBrowserUri(parsed, source = "host.api:/open-url")
                    }
                },
            ),
        )

        runtimeManager.setStatusListener { snapshot ->
            runOnUiThread {
                if (!showingEditor && ::bootView.isInitialized) {
                    bootView.evaluateJavascript(
                        "window.__onHostStatus && window.__onHostStatus(${snapshot.toJsonString()});",
                        null,
                    )
                }
                if (snapshot.state == PosixRuntimeManager.State.READY && (!autoOpenedEditor || showingEditor)) {
                    autoOpenedEditor = true
                    loadEditorIfReady()
                }
            }
        }

        loadBootPage()
        ServerKeepAliveService.start(this)
        runtimeManager.startIfNeeded()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createEditorWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // CRITICAL: Enable multi-window support so VS Code's `window.open(url, "_blank", "noopener")`
            // routes to `onCreateWindow` instead of navigating the main frame (which would trigger
            // `beforeunload` -> VS Code `BrowserLifecycleService` permanent shutdown).
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
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
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val parsed = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
                val editorPort = runtimeManager.getStatus().port
                telemetry.info("webview.nav", "onPageStarted: ${LogRedactor.redact(url)}")
                if (PosixRuntimeConfig.shouldKeepInsideWebView(parsed.scheme, parsed.host, parsed.port, editorPort)) {
                    // 1. Mask navigator.clipboard.readText() so rightClickBehavior="copyPaste" never triggers
                    //    a sticky Android WebView permission modal (paste runs via kucode-tools GET /clipboard).
                    // 2. Forward uncaught window errors & unhandled promise rejections to console.error so
                    //    WebChromeClient.onConsoleMessage captures full details in SessionTelemetryManager.
                    view.evaluateJavascript(
                        """
                        try {
                          if (navigator.clipboard && 'readText' in navigator.clipboard) {
                            Object.defineProperty(navigator.clipboard, 'readText', { value: undefined, configurable: true });
                          }
                          if (!window.__kucodeDiagHooked) {
                            window.__kucodeDiagHooked = true;
                            window.addEventListener('error', function(e) {
                              console.error('[window.onerror] ' + (e && (e.message || e.error) || 'unknown'));
                            });
                            window.addEventListener('unhandledrejection', function(e) {
                              var r = e && e.reason;
                              console.error('[unhandledrejection] ' + (r && (r.stack || r.message) || r));
                            });
                          }
                        } catch (_) {}
                        """.trimIndent(),
                        null,
                    )
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrEmpty()) {
                    telemetry.info("webview.nav", "onPageFinished: ${LogRedactor.redact(url)}")
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url
                val editorPort = runtimeManager.getStatus().port
                val isMain = request.isForMainFrame
                if (PosixRuntimeConfig.shouldKeepInsideWebView(
                        scheme = uri.scheme,
                        host = uri.host,
                        port = uri.port,
                        editorServerPort = editorPort,
                        isForMainFrame = isMain,
                    )
                ) {
                    return false
                }
                if (!PosixRuntimeConfig.isSafeExternalBrowserUri(
                        scheme = uri.scheme,
                        host = uri.host,
                        port = uri.port,
                        rawQuery = uri.encodedQuery,
                        editorServerPort = editorPort,
                    )
                ) {
                    telemetry.warn(
                        "webview.nav",
                        "Blocked disallowed navigation (mainFrame=$isMain): ${LogRedactor.redact(uri.toString())}",
                    )
                    return true
                }
                openExternalBrowserUri(
                    uri = uri,
                    source = if (isMain) "webview.mainFrame" else "webview.subFrame",
                )
                return true
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                val desc = "${error.errorCode} ${error.description} (mainFrame=${request.isForMainFrame}, url=${LogRedactor.redact(request.url.toString())})"
                if (request.isForMainFrame) {
                    telemetry.error("webview.error", desc)
                } else {
                    telemetry.warn("webview.error", desc)
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                val code = errorResponse.statusCode
                if (code >= 400) {
                    val desc = "HTTP $code on ${LogRedactor.redact(request.url.toString())} (mainFrame=${request.isForMainFrame})"
                    if (request.isForMainFrame && code >= 500) {
                        telemetry.error("webview.http", desc)
                    } else {
                        telemetry.info("webview.http", desc)
                    }
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                telemetry.error(
                    "webview.renderer",
                    "Editor WebView render process gone (didCrash=${detail.didCrash()}, priorityAtExit=${detail.rendererPriorityAtExit()}); recreating WebView",
                )
                telemetry.captureMetricSnapshotAsync("webview_renderer_gone", includePss = true)
                recoverEditorWebViewAfterRendererGone(view)
                return true
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                telemetry.info("webview.popup", "onCreateWindow(isDialog=$isDialog, isUserGesture=$isUserGesture)")

                val handled = AtomicBoolean(false)
                val destroyed = AtomicBoolean(false)
                val popupWebView = WebView(this@MainActivity).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                }

                fun cleanupPopup() {
                    if (destroyed.compareAndSet(false, true)) {
                        mainHandler.post {
                            runCatching {
                                popupWebView.stopLoading()
                                popupWebView.webViewClient = WebViewClient()
                                popupWebView.destroy()
                            }
                        }
                    }
                }

                fun interceptPopupUri(uri: Uri, via: String) {
                    val scheme = uri.scheme?.lowercase()
                    if (scheme == "about" && uri.toString().equals("about:blank", ignoreCase = true)) {
                        return
                    }
                    if (!handled.compareAndSet(false, true)) return
                    val editorPort = runtimeManager.getStatus().port
                    if (PosixRuntimeConfig.isSafeExternalBrowserUri(
                            scheme = uri.scheme,
                            host = uri.host,
                            port = uri.port,
                            rawQuery = uri.encodedQuery,
                            editorServerPort = editorPort,
                        )
                    ) {
                        openExternalBrowserUri(uri, source = "webview.onCreateWindow:$via")
                    } else {
                        telemetry.warn(
                            "webview.popup",
                            "Blocked popup target ($via): ${LogRedactor.redact(uri.toString())}",
                        )
                    }
                    cleanupPopup()
                }

                popupWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        popup: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        interceptPopupUri(request.url, "override")
                        return true
                    }

                    override fun onPageStarted(popup: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        val parsed = url?.let { runCatching { Uri.parse(it) }.getOrNull() }
                        if (parsed != null) {
                            interceptPopupUri(parsed, "pageStarted")
                        }
                    }
                }

                // Safety timeout so an unnavigated about:blank popup never leaks a detached WebView
                mainHandler.postDelayed({ cleanupPopup() }, POPUP_CLEANUP_TIMEOUT_MS)

                transport.webView = popupWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onJsBeforeUnload(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                telemetry.warn(
                    "webview.unload",
                    "onJsBeforeUnload triggered on ${LogRedactor.redact(url.orEmpty())}: ${message.orEmpty()}",
                )
                return super.onJsBeforeUnload(view, url, message, result)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    telemetry.recordWebViewConsole(
                        viewTag = "editor",
                        levelName = consoleMessage.messageLevel()?.name ?: "LOG",
                        message = consoleMessage.message().orEmpty(),
                        sourceId = consoleMessage.sourceId(),
                        lineNumber = consoleMessage.lineNumber(),
                    )
                }
                return true
            }
        }
    }

    private fun recoverEditorWebViewAfterRendererGone(deadView: WebView) {
        if (isDestroyed || !::rootFrame.isInitialized) return
        runCatching {
            rootFrame.removeView(deadView)
            deadView.destroy()
        }
        loadedEditorUrl = null
        webView = createEditorWebView().apply {
            visibility = if (showingEditor) View.VISIBLE else View.GONE
        }
        rootFrame.addView(
            webView,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        if (showingEditor) {
            loadEditorIfReady()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        telemetry.updateWindowState(hasFocus = hasFocus)
        telemetry.info("android.focus", "Window focus changed: hasFocus=$hasFocus, showingEditor=$showingEditor")
        if (::bootView.isInitialized && ::rootFrame.isInitialized && ::webView.isInitialized) {
            // Clear visual cue for keyboard focus + lower OST glare when looking at sibling XR windows
            webView.alpha = if (hasFocus) 1.0f else 0.55f
            bootView.alpha = webView.alpha
            rootFrame.setBackgroundColor(if (hasFocus) COLOR_BORDER_FOCUSED else COLOR_BORDER_UNFOCUSED)
            if (hasFocus) {
                if (showingEditor && !webView.hasFocus()) {
                    webView.requestFocus()
                } else if (!showingEditor && !bootView.hasFocus()) {
                    bootView.requestFocus()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            webView.resumeTimers()
        }
        val snapshot = runtimeManager.getStatus()
        // Re-assert foreground keep-alive if Android 15 dataSync service timed out earlier
        if (snapshot.state != PosixRuntimeManager.State.IDLE) {
            ServerKeepAliveService.start(this)
        }
        if (!showingEditor && ::bootView.isInitialized) {
            bootView.evaluateJavascript(
                "window.__onHostStatus && window.__onHostStatus(${snapshot.toJsonString()});",
                null,
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Only intercept host-owned Ctrl+Alt+D; let all real editor/terminal KeyEvents reach WebView untouched
        if (shortcutRouter.isDiagnosticsToggle(event)) {
            telemetry.info("android.shortcut", "Toggled view via Ctrl+Alt+D (wasShowingEditor=$showingEditor)")
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
            telemetry.endCurrentSession(
                reason = "MainActivity finished normally (isFinishing=true)",
                abnormal = false,
                exitCategory = "CLEAN",
            )
        }
        if (::rootFrame.isInitialized) {
            rootFrame.removeAllViews()
        }
        if (::bootView.isInitialized) {
            bootView.removeJavascriptInterface("KuCodeHost")
            bootView.destroy()
        }
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun loadBootPage() {
        showingEditor = false
        telemetry.updateWindowState(activeView = "BOOT")
        webView.visibility = View.GONE
        bootView.visibility = View.VISIBLE
        bootView.requestFocus()
    }

    private fun loadEditorIfReady() {
        val url = runtimeManager.editorLaunchUrl() ?: return
        if (loadedEditorUrl != url) {
            telemetry.info("webview.nav", "Loading editor WebView URL (port=${runtimeManager.getStatus().port})")
            webView.loadUrl(url)
            loadedEditorUrl = url
        }
        showingEditor = true
        telemetry.updateWindowState(activeView = "EDITOR")
        bootView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.requestFocus()
    }

    private fun openExternalBrowserUri(uri: Uri, source: String): Boolean {
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            telemetry.recordExternalBrowserLaunch(uri.toString(), source, launched = true, detail = "NEW_TASK")
            true
        }.getOrElse { e ->
            telemetry.recordExternalBrowserLaunch(
                uri.toString(),
                source,
                launched = false,
                detail = "${e.javaClass.simpleName}: ${e.message}",
            )
            false
        }
    }

    private fun openAllFilesPermissionSettings() {
        telemetry.info("android.settings", "Opening MANAGE_APP_ALL_FILES_ACCESS_PERMISSION settings")
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    private fun openNotificationPermissionSettings() {
        telemetry.info("android.settings", "Opening APP_NOTIFICATION_SETTINGS")
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        runCatching { startActivity(intent) }.onFailure {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    },
                )
            }
        }
    }

    private fun openBatteryOptimizationSettings() {
        telemetry.info("android.settings", "Opening IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    },
                )
            }
        }
    }

    /**
     * Always executes ClipboardManager operations on the Android MainLooper so calls from
     * LocalDiagnosticsServer worker threads or WebView's JavaBridge thread never fail silently.
     */
    private fun copyTextToClipboard(text: String): Boolean = runOnMainThreadBlocking(false) {
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@runCatching false
            val clip = ClipData.newPlainText("KuCode", text)
            clipboard.setPrimaryClip(clip)
            true
        }.getOrDefault(false)
    }

    private fun readTextFromClipboard(): String? = runOnMainThreadBlocking<String?>(null) {
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@runCatching null
            val clip = clipboard.primaryClip ?: return@runCatching ""
            if (clip.itemCount <= 0) return@runCatching ""
            clip.getItemAt(0).coerceToText(this)?.toString() ?: ""
        }.getOrNull()
    }

    private fun <T> runOnMainThreadBlocking(defaultValue: T, action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return action()
        }
        val result = AtomicReference(defaultValue)
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
        private const val POPUP_CLEANUP_TIMEOUT_MS = 10_000L
    }
}
