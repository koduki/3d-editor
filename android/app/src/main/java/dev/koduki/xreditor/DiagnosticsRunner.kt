package dev.koduki.xreditor

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.PowerManager
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.view.InputDevice
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Built-in on-device diagnostics script for XREAL AURA / Android XR verification.
 * Runs non-destructive checks (parallelized after PRoot Gate 1 for fast completion)
 * and outputs a single copy-pasteable Markdown report with recent error logs.
 */
object DiagnosticsRunner {

    data class CheckItem(
        val name: String,
        val ok: Boolean,
        val detail: String,
        val durationMs: Long = 0L,
    )

    data class ReportInput(
        val timestampUtc: String,
        val deviceInfo: Map<String, String>,
        val checks: List<CheckItem>,
        val recentLogs: String,
    )

    fun formatMarkdownReport(input: ReportInput): String {
        val sb = StringBuilder()
        sb.appendLine("# XR-Editor 実機診断レポート")
        sb.appendLine("- Timestamp (UTC): `${input.timestampUtc}`")
        sb.appendLine()

        sb.appendLine("## 1. デバイス & アプリ基礎情報")
        for ((k, v) in input.deviceInfo) {
            sb.appendLine("- $k: `$v`")
        }
        sb.appendLine()

        val passed = input.checks.count { it.ok }
        sb.appendLine("## 2. 動作チェック結果 ($passed/${input.checks.size} PASS)")
        for (c in input.checks) {
            val badge = if (c.ok) "PASS" else "FAIL"
            val dur = if (c.durationMs > 0) " (${c.durationMs}ms)" else ""
            sb.appendLine("### [$badge] ${c.name}$dur")
            sb.appendLine("```text")
            sb.appendLine(c.detail.trim().ifEmpty { "(no output)" })
            sb.appendLine("```")
        }
        sb.appendLine()

        sb.appendLine("### XR での手動確認（自動集計外）")
        sb.appendLine("- 日本語入力中に Enter で変換を確定し、余分な改行が入らないことを確認する。")
        sb.appendLine("- 隣のプレビューから戻り、最初のキー入力が元のエディタに届くことを確認する。")
        sb.appendLine("- 管理画面を往復し、未保存の文字、カーソル位置、ターミナルセッションを確認する。")
        sb.appendLine()
        sb.appendLine("## 3. 直近のサーバー / エラーログ")
        sb.appendLine("```text")
        val trimmedLogs = input.recentLogs.trim()
        sb.appendLine(if (trimmedLogs.isEmpty()) "(no logs)" else trimmedLogs)
        sb.appendLine("```")
        return sb.toString()
    }

    suspend fun collect(context: Context, runtime: PosixRuntimeManager): String =
        withContext(Dispatchers.IO) {
            val paths = runtime.getPaths()
            val status = runtime.getStatus()
            val systemDns = runtime.querySystemDnsServers()

            val deviceInfo = linkedMapOf<String, String>()
            deviceInfo["Device"] = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
            deviceInfo["Android"] = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            deviceInfo["ABIs"] = Build.SUPPORTED_ABIS.joinToString(", ")
            deviceInfo["Page Size"] = queryPageSize()
            deviceInfo["SELinux Context"] = querySelinuxContext()
            deviceInfo["WebView"] = queryWebViewInfo()
            deviceInfo["Display / Window"] = queryDisplayInfo(context)
            deviceInfo["Input Devices (KB/Mouse)"] = queryInputDevices(context)
            deviceInfo["System DNS"] = if (systemDns.isEmpty()) "(fallback 1.1.1.1/8.8.8.8)" else systemDns.joinToString(", ")
            deviceInfo["App"] = "${context.packageName} (targetSdk=${context.applicationInfo.targetSdkVersion})"
            deviceInfo["XR Spatial Feature"] =
                context.packageManager.hasSystemFeature("android.software.xr.api.spatial").toString()
            deviceInfo["All Files Access"] = status.hasAllFilesAccess.toString()
            deviceInfo["Battery Opt Ignored"] = queryBatteryOptIgnored(context)
            deviceInfo["Notifications Enabled"] = queryNotificationsEnabled(context)
            deviceInfo["Free Disk (filesDir)"] = "${context.filesDir.usableSpace / (1024 * 1024)} MB"
            deviceInfo["Memory"] = queryMemorySummary(context)
            deviceInfo["Runtime State"] = "${status.state} (port=${status.port}, msg=${status.message})"
            deviceInfo["Workspace Path"] = paths.workspaceDir.absolutePath
            deviceInfo["Home Path (/root)"] = paths.homeDir.absolutePath
            deviceInfo["NativeLibDir"] = paths.nativeLibDir.absolutePath

            val checks = mutableListOf<CheckItem>()

            // 1-3. Fast local Android-side checks
            checks.add(checkNativeBinaries(paths))
            checks.add(checkExtractedRuntime(paths))

            // 4. PRoot Gate 1
            val prootCheck = runProotProbe(runtime)
            checks.add(prootCheck)

            // 5-9. Run independent POSIX probes in parallel to keep total runtime well under timeout
            if (prootCheck.ok) {
                val probeScript = context.assets.open("runtime-probes.cjs").bufferedReader().use { it.readText() }
                val parallelChecks = coroutineScope {
                    val documentsTask = async { checkDocumentsProvider(context, runtime) }
                    val nodeTask = async {
                        runTimedCommand(
                            name = "Node.js & openvscode-server 実行確認",
                            runtime = runtime,
                            command = "${PosixRuntimeConfig.GUEST_OPENVSCODE_DIR}/node -v && ${PosixRuntimeConfig.GUEST_OPENVSCODE_BIN} --version",
                            timeoutSeconds = 15,
                        )
                    }
                    val ptyTask = async {
                        runTimedCommand(
                            name = "統合ターミナル (PTY 入出力・リサイズ)",
                            runtime = runtime,
                            command = nativeProbeCommand("pty", probeScript),
                            timeoutSeconds = 15,
                        )
                    }
                    val watcherTask = async {
                        runTimedCommand(
                            name = "ワークスペースのファイル作成通知 (@parcel/watcher)",
                            runtime = runtime,
                            command = nativeProbeCommand("watcher", probeScript),
                            timeoutSeconds = 15,
                        )
                    }
                    val mountTask = async {
                        runTimedCommand(
                            name = "POSIX /workspace & /android マウント確認",
                            runtime = runtime,
                            command = """
                                set -e
                                TEST_F=$(mktemp /workspace/.xr-diag.XXXXXXXX)
                                trap 'rm -f -- "${'$'}TEST_F"' EXIT
                                echo ok > "${'$'}TEST_F"
                                test "$(cat "${'$'}TEST_F")" = ok
                                echo "workspace_rw=OK"
                                ls -ld /workspace /workspace/android /android/shared /android/sdcard 2>&1 || true
                                echo "android_shared_entries=$(ls -A /android/shared 2>/dev/null | wc -l)"
                                echo "android_sdcard_entries=$(ls -A /android/sdcard 2>/dev/null | wc -l)"
                            """.trimIndent(),
                            timeoutSeconds = 15,
                        )
                    }
                    val networkTasks = networkChecks().map { (name, command) ->
                        async { runTimedCommand(name, runtime, command, timeoutSeconds = 15) }
                    }
                    listOf(
                        documentsTask.await(),
                        nodeTask.await(),
                        ptyTask.await(),
                        watcherTask.await(),
                        mountTask.await(),
                    ) + networkTasks.awaitAll()
                }
                checks.addAll(parallelChecks)
            } else {
                checks.add(checkDocumentsProvider(context, null))
            }

            // 10. Embedded HTTP server health check
            checks.add(checkLocalServerHttp(runtime, status))

            val tailLogs = runtime.getLogsText()
                .lines()
                .takeLast(80)
                .joinToString("\n")

            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            formatMarkdownReport(
                ReportInput(
                    timestampUtc = fmt.format(Date()),
                    deviceInfo = deviceInfo,
                    checks = checks,
                    recentLogs = tailLogs,
                ),
            )
        }

    internal fun nativeProbeCommand(mode: String, script: String): String {
        require(mode == "pty" || mode == "watcher")
        return "${PosixRuntimeConfig.GUEST_OPENVSCODE_DIR}/node - $mode <<'XR_PROBE_JS'\n$script\nXR_PROBE_JS"
    }

    internal fun networkChecks(): Map<String, String> {
        val node = "${PosixRuntimeConfig.GUEST_OPENVSCODE_DIR}/node"
        fun httpProbe(url: String) = """
            $node -e '
              fetch(process.argv[1], { method: "HEAD", signal: AbortSignal.timeout(5000) })
                .then(r => { console.log("HTTP " + r.status); process.exitCode = r.ok ? 0 : 1; })
                .catch(e => { console.error(e.cause?.code || e.message); process.exitCode = 1; });
            ' '$url'
        """.trimIndent()
        return linkedMapOf(
            "DNS (deb.debian.org)" to "getent hosts deb.debian.org",
            "Debian apt HTTP" to httpProbe("http://deb.debian.org/debian/dists/bookworm/Release"),
            "GitHub HTTPS" to httpProbe("https://api.github.com/zen"),
            "CLI (必須: bash/apt, 任意: git/gh/curl)" to """
                set -e
                command -v bash
                command -v apt
                for bin in git gh curl; do
                    printf '%s=%s\n' "${'$'}bin" "$(command -v "${'$'}bin" || echo 未導入)"
                done
            """.trimIndent(),
        )
    }

    private fun queryPageSize(): String {
        return runCatching {
            val bytes = Os.sysconf(OsConstants._SC_PAGESIZE)
            "${bytes} bytes (${bytes / 1024}KB)"
        }.getOrDefault("unknown")
    }

    private fun querySelinuxContext(): String {
        return runCatching {
            File("/proc/self/attr/current").readText().trim().trim('\u0000')
        }.getOrDefault("unreadable")
    }

    private fun queryWebViewInfo(): String {
        return runCatching {
            val pkg = WebView.getCurrentWebViewPackage()
            if (pkg != null) "${pkg.packageName} ${pkg.versionName}" else "null"
        }.getOrDefault("unknown")
    }

    private fun queryDisplayInfo(context: Context): String {
        return runCatching {
            val dm = context.resources.displayMetrics
            val wDp = (dm.widthPixels / dm.density).toInt()
            val hDp = (dm.heightPixels / dm.density).toInt()
            "${dm.widthPixels}x${dm.heightPixels}px (${wDp}x${hDp}dp, density=${dm.density})"
        }.getOrDefault("unknown")
    }

    private fun queryInputDevices(context: Context): String {
        return runCatching {
            val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return "unavailable"
            val names = im.inputDeviceIds.toList().mapNotNull { id ->
                val dev = im.getInputDevice(id) ?: return@mapNotNull null
                if (dev.isVirtual) return@mapNotNull null
                val src = dev.sources
                val tags = mutableListOf<String>()
                if ((src and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
                    dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
                ) {
                    tags.add("KB")
                }
                if ((src and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) tags.add("Mouse")
                if ((src and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) tags.add("Touchpad")
                if (tags.isEmpty()) null else "${dev.name}[${tags.joinToString("/")}]"
            }
            if (names.isEmpty()) "none detected" else names.joinToString(", ")
        }.getOrDefault("unknown")
    }

    private fun queryBatteryOptIgnored(context: Context): String {
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return "unknown"
            pm.isIgnoringBatteryOptimizations(context.packageName).toString()
        }.getOrDefault("unknown")
    }

    private fun queryNotificationsEnabled(context: Context): String {
        return runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return "unknown"
            nm.areNotificationsEnabled().toString()
        }.getOrDefault("unknown")
    }

    private fun queryMemorySummary(context: Context): String {
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            val availMb = mi.availMem / (1024 * 1024)
            val totalMb = mi.totalMem / (1024 * 1024)
            "${availMb}MB free / ${totalMb}MB total"
        }.getOrDefault("unknown")
    }

    private fun checkNativeBinaries(paths: PosixRuntimeConfig.Paths): CheckItem {
        val proot = paths.prootBin
        val loader = paths.prootLoader
        val ok = proot.exists() && proot.canExecute() && loader.exists() && loader.canExecute()
        val detail = buildString {
            appendLine("libproot.so: exists=${proot.exists()}, exec=${proot.canExecute()}, size=${proot.length()}")
            appendLine("libproot-loader.so: exists=${loader.exists()}, exec=${loader.canExecute()}, size=${loader.length()}")
        }
        return CheckItem("PRoot ネイティブバイナリ (jniLibs)", ok, detail)
    }

    private fun checkExtractedRuntime(paths: PosixRuntimeConfig.Paths): CheckItem {
        val marker = paths.versionMarker.takeIf { it.exists() }?.readText()?.trim() ?: "(none)"
        val bash = File(paths.rootfsDir, "bin/bash")
        val serverBin = File(paths.openvscodeDir, "bin/openvscode-server")
        val nodeBin = File(paths.openvscodeDir, "node")
        val ok = bash.exists() && serverBin.exists() && nodeBin.exists()
        val detail = buildString {
            appendLine("marker=$marker (expected=${PosixRuntimeConfig.RUNTIME_VERSION})")
            appendLine("/bin/bash: exists=${bash.exists()}, exec=${bash.canExecute()}")
            appendLine("/opt/openvscode-server/bin/openvscode-server: exists=${serverBin.exists()}, exec=${serverBin.canExecute()}")
            appendLine("/opt/openvscode-server/node: exists=${nodeBin.exists()}, exec=${nodeBin.canExecute()}")
        }
        return CheckItem("Debian rootfs & openvscode-server 展開状態", ok, detail)
    }

    private suspend fun checkDocumentsProvider(context: Context, runtime: PosixRuntimeManager?): CheckItem {
        val authority = "${context.packageName}.documents"
        val resolver = context.contentResolver
        val t0 = System.currentTimeMillis()
        val name = if (runtime == null) "Android Files ルート一覧" else "Android Files ↔ /workspace 双方向読み書き"
        var step = "共有ルートの取得"
        return try {
            val column = DocumentsContract.Root.COLUMN_DOCUMENT_ID
            val rootId = resolver.query(DocumentsContract.buildRootsUri(authority), arrayOf(column), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(column)) else null }
            check(!rootId.isNullOrBlank()) { "共有ルートがありません" }
            if (runtime != null) {
                val id = UUID.randomUUID()
                val root = DocumentsContract.buildDocumentUri(authority, rootId)
                step = "一時ドキュメントの作成"
                val doc = checkNotNull(DocumentsContract.createDocument(resolver, root, "text/plain", ".xr-files-$id"))
                try {
                    step = "Android Files から書き込み"
                    checkNotNull(resolver.openOutputStream(doc, "wt")).use { it.write("android-$id".toByteArray()) }
                    step = "ゲストから読み込み・上書き"
                    val result = runtime.runShellCommand(workspaceExchangeCommand(id), timeoutSeconds = 10)
                    check(result.exitCode == 0) { "guest exit=${result.exitCode}" }
                    step = "Android Files からゲストの変更を読み込み"
                    val expected = "guest-$id".toByteArray()
                    val matches = checkNotNull(resolver.openInputStream(doc)).use {
                        it.readNBytes(expected.size + 1).contentEquals(expected)
                    }
                    check(matches) { "読み戻した内容が一致しません" }
                } finally {
                    check(DocumentsContract.deleteDocument(resolver, doc)) { "一時ドキュメントの削除に失敗しました" }
                }
            }
            val detail = if (runtime == null) "ルート取得成功。PRoot 起動失敗のため双方向確認は未実行" else "作成 → ゲストで照合・上書き → Android で照合 → 削除"
            CheckItem(name, true, detail, System.currentTimeMillis() - t0)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            CheckItem(name, false, "$step: ${failure.message}", System.currentTimeMillis() - t0)
        }
    }

    internal fun workspaceExchangeCommand(id: UUID): String = """
        set -eu
        file="/workspace/.xr-files-$id"
        test "$(cat -- "${'$'}file")" = "android-$id"
        printf '%s' 'guest-$id' > "${'$'}file"
    """.trimIndent()

    private suspend fun runProotProbe(runtime: PosixRuntimeManager): CheckItem {
        val probeCmd = "uname -a && id && grep -E '^(PRETTY_NAME|VERSION_ID)=' /etc/os-release || true"
        val t0 = System.currentTimeMillis()
        val res = runtime.runShellCommand(probeCmd, timeoutSeconds = 15)
        val elapsed = System.currentTimeMillis() - t0
        val ok = res.exitCode == 0 && res.output.contains("Debian", ignoreCase = true)
        if (ok) {
            return CheckItem(
                name = "PRoot + Debian シェル実行 (Gate 1)",
                ok = true,
                detail = "exitCode=${res.exitCode}\n${res.output}",
                durationMs = elapsed,
            )
        }

        // Retry with PROOT_NO_SECCOMP=1 to clearly identify kernel seccomp incompatibility
        val t1 = System.currentTimeMillis()
        val retry = runtime.runShellCommand(probeCmd, timeoutSeconds = 15, forceNoSeccomp = true)
        val retryElapsed = System.currentTimeMillis() - t1
        val retryOk = retry.exitCode == 0 && retry.output.contains("Debian", ignoreCase = true)
        val combined = buildString {
            appendLine("[default] exitCode=${res.exitCode} (${elapsed}ms)")
            appendLine(res.output.trim().ifEmpty { "(no output)" })
            appendLine("[PROOT_NO_SECCOMP=1] exitCode=${retry.exitCode} (${retryElapsed}ms)")
            appendLine(retry.output.trim().ifEmpty { "(no output)" })
        }
        return CheckItem(
            name = "PRoot + Debian シェル実行 (Gate 1)",
            ok = retryOk,
            detail = combined,
            durationMs = elapsed + retryElapsed,
        )
    }

    private suspend fun runTimedCommand(
        name: String,
        runtime: PosixRuntimeManager,
        command: String,
        timeoutSeconds: Long = 15,
    ): CheckItem {
        val t0 = System.currentTimeMillis()
        val res = runtime.runShellCommand(command, timeoutSeconds = timeoutSeconds)
        val elapsed = System.currentTimeMillis() - t0
        return CheckItem(
            name = name,
            ok = res.exitCode == 0,
            detail = "exitCode=${res.exitCode}\n${res.output}",
            durationMs = elapsed,
        )
    }

    private fun checkLocalServerHttp(
        runtime: PosixRuntimeManager,
        status: PosixRuntimeManager.StatusSnapshot,
    ): CheckItem {
        val t0 = System.currentTimeMillis()
        val ok = runtime.editorAccessVerified()
        return CheckItem(
            name = "内蔵 openvscode-server 認証 (Gate 2)",
            ok = ok,
            detail = "state=${status.state}, port=${status.port}, anonymousDeniedAndEditorAuthenticated=$ok",
            durationMs = System.currentTimeMillis() - t0,
        )
    }
}
