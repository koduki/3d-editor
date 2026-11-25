package dev.koduki.kucode

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.view.DragAndDropPermissions
import android.view.DragEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-window Android XR Activity hosting:
 * 1. Pure-black boot/diagnostics page (`file:///android_asset/boot.html`) with isolated `KuCodeHost` bridge
 * 2. Dedicated authenticated editor WebView, without a native bridge, with:
 *    - Multi-window popup interception (`setSupportMultipleWindows(true)` + `onCreateWindow`) so
 *      `window.open(..., "_blank", "noopener")` (e.g. `gh auth`, Copilot CLI login, or VS Code external links)
 *      opens a sibling XR browser window without triggering `beforeunload` / VS Code workbench shutdown
 *    - Automatic WebView renderer crash/LMKD recovery (`onRenderProcessGone`) on both editor and boot views
 *    - Native trusted KeyEvents flowing directly to Monaco / xterm.js, file chooser & drag-and-drop support,
 *      edge-to-edge WindowInsets handling, and XR multi-window focus dimming
 */
class MainActivity : AppCompatActivity() {

    private data class ActivePopup(
        val webView: WebView,
        val cleanup: () -> Unit,
    )

    private lateinit var rootFrame: FrameLayout
    private lateinit var webView: WebView
    private lateinit var bootView: WebView
    private lateinit var runtimeManager: PosixRuntimeManager
    private lateinit var telemetry: SessionTelemetryManager
    private lateinit var bootBridge: BootHostBridge
    private val shortcutRouter = HardwareShortcutRouter()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var showingEditor = false
    private var autoOpenedEditor = false
    private var loadedEditorUrl: String? = null
    private var pendingFilePathCallback: ValueCallback<Array<Uri>>? = null
    private var activeDragPermissions: DragAndDropPermissions? = null
    private val activePopups = LinkedHashSet<ActivePopup>()

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFilePathCallback
        pendingFilePathCallback = null
        if (callback == null) return@registerForActivityResult
        val uris: Array<Uri>? = if (result.resultCode == RESULT_OK) {
            val data = result.data
            val clip = data?.clipData
            when {
                clip != null && clip.itemCount > 0 -> {
                    Array(clip.itemCount) { idx -> clip.getItemAt(idx).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
            }
        } else {
            null
        }
        runCatching { callback.onReceiveValue(uris) }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        telemetry.info("android.permission", "POST_NOTIFICATIONS runtime request completed (granted=$granted)")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.setBackgroundColor(Color.BLACK)
        showingEditor = savedInstanceState?.getBoolean(KEY_SHOWING_EDITOR, false) ?: false
        autoOpenedEditor = savedInstanceState?.getBoolean(KEY_AUTO_OPENED_EDITOR, false) ?: false

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
        bootView = createBootWebView()

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
            addView(
                bootView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootFrame) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(
                insets.left + borderPx,
                insets.top + borderPx,
                insets.right + borderPx,
                insets.bottom + borderPx,
            )
            WindowInsetsCompat.CONSUMED
        }
        setContentView(rootFrame)
        ViewCompat.requestApplyInsets(rootFrame)

        // Prevent stray Back/Esc gestures in XR from accidentally killing the editor & server,
        // while allowing the user to exit cleanly when stuck on a boot error/idle screen.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val state = runtimeManager.getStatus().state
                    if (!showingEditor && state == PosixRuntimeManager.State.READY) {
                        loadEditorIfReady()
                    } else if (!showingEditor && (state == PosixRuntimeManager.State.ERROR || state == PosixRuntimeManager.State.IDLE)) {
                        moveTaskToBack(true)
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
                if (isDestroyed) return@runOnUiThread
                if (showingEditor && (snapshot.state == PosixRuntimeManager.State.IDLE || snapshot.state == PosixRuntimeManager.State.ERROR)) {
                    loadedEditorUrl = null
                    loadBootPage()
                }
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

        if (showingEditor && runtimeManager.editorLaunchUrl() != null) {
            loadEditorIfReady()
        } else {
            loadBootPage()
        }
        maybeRequestNotificationPermissionOnLaunch()
        ServerKeepAliveService.start(this)
        runtimeManager.startIfNeeded()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHOWING_EDITOR, showingEditor)
        outState.putBoolean(KEY_AUTO_OPENED_EDITOR, autoOpenedEditor)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBootWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError?) {
                telemetry.error(
                    "webview.ssl",
                    "Boot WebView SSL error ${error?.primaryError} on ${LogRedactor.redact(error?.url.orEmpty())}",
                )
                handler.cancel()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                telemetry.error(
                    "webview.renderer",
                    "Boot WebView render process gone (didCrash=${detail.didCrash()}, priorityAtExit=${detail.rendererPriorityAtExit()}); recreating boot WebView",
                )
                telemetry.captureMetricSnapshotAsync("boot_webview_renderer_gone", includePss = true)
                recoverBootWebViewAfterRendererGone(view)
                return true
            }
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun createEditorWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true

        setOnDragListener { _, event ->
            if (event.action == DragEvent.ACTION_DROP) {
                runCatching {
                    activeDragPermissions?.release()
                    activeDragPermissions = requestDragAndDropPermissions(event)
                }
            }
            false
        }

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
                val adminPort = runtimeManager.getAdminPort()
                telemetry.info("webview.nav", "onPageStarted: ${LogRedactor.redact(url)}")
                if (PosixRuntimeConfig.shouldKeepInsideWebView(
                        scheme = parsed.scheme,
                        host = parsed.host,
                        port = parsed.port,
                        editorServerPort = editorPort,
                        isForMainFrame = true,
                        adminServerPort = adminPort,
                    )
                ) {
                    // 1. Mask navigator.clipboard.readText() so rightClickBehavior="copyPaste" never triggers
                    //    a sticky Android WebView permission modal (paste runs via kucode-tools GET /clipboard).
                    // 2. Forward uncaught window errors & unhandled promise rejections to console.error so
                    //    WebChromeClient.onConsoleMessage captures full details in SessionTelemetryManager
                    //    (filtering benign Chromium ResizeObserver loop notifications).
                    view.evaluateJavascript(
                        """
                        try {
                          if (navigator.clipboard && 'readText' in navigator.clipboard) {
                            Object.defineProperty(navigator.clipboard, 'readText', { value: undefined, configurable: true });
                          }
                          if (!window.__kucodeDiagHooked) {
                            window.__kucodeDiagHooked = true;
                            window.addEventListener('error', function(e) {
                              var msg = String(e && (e.message || e.error) || 'unknown');
                              if (msg.indexOf('ResizeObserver loop') !== -1) return;
                              console.error('[window.onerror] ' + msg);
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
                val adminPort = runtimeManager.getAdminPort()
                val isMain = request.isForMainFrame
                if (PosixRuntimeConfig.shouldKeepInsideWebView(
                        scheme = uri.scheme,
                        host = uri.host,
                        port = uri.port,
                        editorServerPort = editorPort,
                        isForMainFrame = isMain,
                        adminServerPort = adminPort,
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
                if (!isMain && !request.hasGesture()) {
                    telemetry.warn(
                        "webview.nav",
                        "Blocked non-gesture subframe external navigation: ${LogRedactor.redact(uri.toString())}",
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

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError?) {
                telemetry.error(
                    "webview.ssl",
                    "Editor WebView SSL error ${error?.primaryError} on ${LogRedactor.redact(error?.url.orEmpty())}",
                )
                handler.cancel()
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
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null) return false
                pendingFilePathCallback?.let { prev -> runCatching { prev.onReceiveValue(null) } }
                pendingFilePathCallback = filePathCallback
                val intent = runCatching { fileChooserParams?.createIntent() }.getOrNull()
                    ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return runCatching {
                    fileChooserLauncher.launch(intent)
                    true
                }.getOrElse { e ->
                    telemetry.warn("webview.chooser", "Failed to open file chooser: ${e.message}")
                    pendingFilePathCallback = null
                    false
                }
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                if (activePopups.size >= MAX_CONCURRENT_POPUPS) {
                    telemetry.warn(
                        "webview.popup",
                        "Rejected onCreateWindow: concurrent popup limit ($MAX_CONCURRENT_POPUPS) reached",
                    )
                    return false
                }
                telemetry.info("webview.popup", "onCreateWindow(isDialog=$isDialog, isUserGesture=$isUserGesture)")

                val handled = AtomicBoolean(false)
                val destroyed = AtomicBoolean(false)
                val popupWebView = WebView(this@MainActivity).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                }

                lateinit var timeoutRunnable: Runnable
                lateinit var activeEntry: ActivePopup

                fun cleanupPopup() {
                    if (destroyed.compareAndSet(false, true)) {
                        val destroyBlock = {
                            mainHandler.removeCallbacks(timeoutRunnable)
                            activePopups.remove(activeEntry)
                            runCatching {
                                popupWebView.stopLoading()
                                popupWebView.webViewClient = WebViewClient()
                                popupWebView.destroy()
                            }
                        }
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            destroyBlock()
                        } else {
                            mainHandler.post { destroyBlock() }
                        }
                    }
                }

                timeoutRunnable = Runnable { cleanupPopup() }
                activeEntry = ActivePopup(popupWebView, ::cleanupPopup)
                activePopups.add(activeEntry)

                fun isAboutBlank(uri: Uri): Boolean {
                    if (!uri.scheme.equals("about", ignoreCase = true)) return false
                    val ssp = uri.schemeSpecificPart.orEmpty()
                    return ssp.equals("blank", ignoreCase = true) || ssp.startsWith("blank?", ignoreCase = true)
                }

                fun interceptPopupUri(uri: Uri, via: String) {
                    if (isAboutBlank(uri)) return
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

                    override fun shouldInterceptRequest(
                        popup: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val uri = request.url
                        val scheme = uri.scheme?.lowercase()
                        if (request.isForMainFrame && (scheme == "http" || scheme == "https")) {
                            // Intercept initial popup GET before WebView sends an actual HTTP request
                            // (prevents consuming single-use OAuth/auth URLs twice).
                            mainHandler.post { interceptPopupUri(uri, "intercept") }
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        return super.shouldInterceptRequest(popup, request)
                    }

                    override fun onPageStarted(popup: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        val parsed = url?.let { runCatching { Uri.parse(it) }.getOrNull() }
                        if (parsed != null) {
                            interceptPopupUri(parsed, "pageStarted")
                        }
                    }

                    override fun onReceivedSslError(popup: WebView, handler: SslErrorHandler, error: SslError?) {
                        handler.cancel()
                        cleanupPopup()
                    }

                    override fun onRenderProcessGone(popup: WebView, detail: RenderProcessGoneDetail): Boolean {
                        cleanupPopup()
                        return true
                    }
                }

                // Safety timeout so an unnavigated about:blank popup never leaks a detached WebView
                mainHandler.postDelayed(timeoutRunnable, POPUP_CLEANUP_TIMEOUT_MS)

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
        if (isDestroyed || !::rootFrame.isInitialized || !::webView.isInitialized || deadView !== webView) return
        runCatching {
            rootFrame.removeView(deadView)
            deadView.destroy()
        }
        loadedEditorUrl = null
        val currentAlpha = if (hasWindowFocus()) 1.0f else 0.55f
        webView = createEditorWebView().apply {
            visibility = if (showingEditor) View.VISIBLE else View.GONE
            alpha = currentAlpha
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
        } else {
            webView.onPause()
        }
    }

    private fun recoverBootWebViewAfterRendererGone(deadView: WebView) {
        if (isDestroyed || !::rootFrame.isInitialized || !::bootView.isInitialized || deadView !== bootView) return
        runCatching {
            deadView.removeJavascriptInterface("KuCodeHost")
            rootFrame.removeView(deadView)
            deadView.destroy()
        }
        val currentAlpha = if (hasWindowFocus()) 1.0f else 0.55f
        bootView = createBootWebView().apply {
            visibility = if (showingEditor) View.GONE else View.VISIBLE
            alpha = currentAlpha
        }
        rootFrame.addView(
            bootView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        if (showingEditor) {
            bootView.onPause()
        } else {
            bootView.onResume()
            bootView.requestFocus()
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

    override fun onPause() {
        if (showingEditor && ::webView.isInitialized) {
            webView.onPause()
        } else if (!showingEditor && ::bootView.isInitialized) {
            bootView.onPause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (showingEditor && ::webView.isInitialized) {
            webView.onResume()
            webView.resumeTimers()
        } else if (!showingEditor && ::bootView.isInitialized) {
            bootView.onResume()
            bootView.resumeTimers()
        }
        val snapshot = runtimeManager.getStatus()
        // Re-assert foreground keep-alive if the foreground service was previously stopped
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
        mainHandler.removeCallbacksAndMessages(null)
        activePopups.toList().forEach { runCatching { it.cleanup() } }
        activePopups.clear()
        pendingFilePathCallback?.let { runCatching { it.onReceiveValue(null) } }
        pendingFilePathCallback = null
        activeDragPermissions?.let { runCatching { it.release() } }
        activeDragPermissions = null

        runtimeManager.setStatusListener(null)
        runtimeManager.setHostActions(null)
        if (::bootBridge.isInitialized) {
            bootBridge.close()
        }
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
        webView.onPause()
        webView.visibility = View.GONE
        bootView.visibility = View.VISIBLE
        bootView.onResume()
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
        bootView.onPause()
        bootView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.onResume()
        webView.requestFocus()
    }

    private fun maybeRequestNotificationPermissionOnLaunch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(PREF_ASKED_NOTIFICATIONS, true).apply()
        runCatching {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            telemetry.info("android.settings", "Requesting POST_NOTIFICATIONS runtime permission")
            runCatching {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
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

    @SuppressLint("BatteryLife")
    private fun openBatteryOptimizationSettings() {
        telemetry.info("android.settings", "Opening REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
        val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(directIntent) }.onFailure {
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
            val clip = clipboard.primaryClip
            if (clip == null) {
                return@runCatching if (!hasWindowFocus()) null else ""
            }
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
        private const val MAX_CONCURRENT_POPUPS = 2
        private const val KEY_SHOWING_EDITOR = "kucode_showing_editor"
        private const val KEY_AUTO_OPENED_EDITOR = "kucode_auto_opened_editor"
        private const val PREFS_NAME = "kucode_host_prefs"
        private const val PREF_ASKED_NOTIFICATIONS = "asked_post_notifications"
    }
}
