package dev.koduki.xreditor

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Built-in on-device diagnostics script for XREAL AURA / Android XR verification.
 * Runs non-destructive checks across device metadata, PRoot (seccomp on/off),
 * Node.js / openvscode-server, filesystem mounts, PTY/DNS/CLI availability, and
 * appends recent server/error logs as a single copy-pasteable Markdown report.
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

            val deviceInfo = linkedMapOf<String, String>()
            deviceInfo["Device"] = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
            deviceInfo["Android"] = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            deviceInfo["ABIs"] = Build.SUPPORTED_ABIS.joinToString(", ")
            deviceInfo["App"] = "${context.packageName} (targetSdk=${context.applicationInfo.targetSdkVersion})"
            deviceInfo["XR Spatial Feature"] =
                context.packageManager.hasSystemFeature("android.software.xr.api.spatial").toString()
            deviceInfo["All Files Access"] = status.hasAllFilesAccess.toString()
            deviceInfo["Free Disk (filesDir)"] = "${context.filesDir.usableSpace / (1024 * 1024)} MB"
            deviceInfo["Memory"] = queryMemorySummary(context)
            deviceInfo["Runtime State"] = "${status.state} (port=${status.port}, msg=${status.message})"
            deviceInfo["NativeLibDir"] = paths.nativeLibDir.absolutePath

            val checks = mutableListOf<CheckItem>()

            // 1. Embedded native PRoot binaries check
            checks.add(checkNativeBinaries(paths))

            // 2. Extracted Debian rootfs & openvscode-server check
            checks.add(checkExtractedRuntime(paths))

            // 3. PRoot basic execution (with seccomp fallback probe if needed)
            val prootCheck = runProotProbe(runtime)
            checks.add(prootCheck)

            if (prootCheck.ok) {
                // 4. Bundled Node.js & openvscode-server --version
                checks.add(
                    runTimedCommand(
                        name = "Node.js & openvscode-server 実行確認",
                        runtime = runtime,
                        command = "${PosixRuntimeConfig.GUEST_OPENVSCODE_DIR}/node -v && ${PosixRuntimeConfig.GUEST_OPENVSCODE_BIN} --version | head -n 3",
                    ),
                )

                // 5. Filesystem R/W & Android mount visibility
                checks.add(
                    runTimedCommand(
                        name = "POSIX /workspace & /android マウント確認",
                        runtime = runtime,
                        command = """
                            set -e
                            TEST_F=/workspace/.xr-diag-rw-test
                            echo ok > "${'$'}TEST_F" && cat "${'$'}TEST_F" >/dev/null && rm -f "${'$'}TEST_F"
                            echo "workspace_rw=OK"
                            ls -ld /workspace /workspace/android /android/shared /android/sdcard 2>&1 || true
                            echo "android_shared_entries=$(ls -A /android/shared 2>/dev/null | wc -l)"
                            echo "android_sdcard_entries=$(ls -A /android/sdcard 2>/dev/null | wc -l)"
                        """.trimIndent(),
                    ),
                )

                // 6. PTY (/dev/ptmx) & DNS & CLI availability
                checks.add(
                    runTimedCommand(
                        name = "PTY (/dev/ptmx)・DNS・CLI (apt/git/gh) 確認",
                        runtime = runtime,
                        command = """
                            ls -l /dev/ptmx /dev/pts 2>&1 | head -n 6 || true
                            cat /etc/resolv.conf 2>&1 || true
                            (getent hosts deb.debian.org 2>&1 | head -n 2) || echo "dns_lookup=FAILED"
                            for bin in bash apt git gh curl; do
                              printf '%s=%s\n' "${'$'}bin" "$(command -v "${'$'}bin" 2>/dev/null || echo missing)"
                            done
                        """.trimIndent(),
                    ),
                )
            }

            // 7. Embedded HTTP server health check
            checks.add(checkLocalServerHttp(status))

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

    private suspend fun runProotProbe(runtime: PosixRuntimeManager): CheckItem {
        val probeCmd = "uname -a && id && grep -E '^(PRETTY_NAME|VERSION_ID)=' /etc/os-release || true"
        val t0 = System.currentTimeMillis()
        val res = runtime.runShellCommand(probeCmd, timeoutSeconds = 20)
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
        val retry = runtime.runShellCommand(probeCmd, timeoutSeconds = 20, forceNoSeccomp = true)
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
    ): CheckItem {
        val t0 = System.currentTimeMillis()
        val res = runtime.runShellCommand(command, timeoutSeconds = 25)
        val elapsed = System.currentTimeMillis() - t0
        return CheckItem(
            name = name,
            ok = res.exitCode == 0,
            detail = "exitCode=${res.exitCode}\n${res.output}",
            durationMs = elapsed,
        )
    }

    private fun checkLocalServerHttp(status: PosixRuntimeManager.StatusSnapshot): CheckItem {
        if (status.port <= 0) {
            return CheckItem(
                name = "内蔵 openvscode-server HTTP 応答 (Gate 2)",
                ok = false,
                detail = "state=${status.state}, port=${status.port}, message=${status.message}",
            )
        }
        val t0 = System.currentTimeMillis()
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL("http://127.0.0.1:${status.port}/").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2000
                readTimeout = 2000
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            val elapsed = System.currentTimeMillis() - t0
            CheckItem(
                name = "内蔵 openvscode-server HTTP 応答 (Gate 2)",
                ok = code in 200..399,
                detail = "url=http://127.0.0.1:${status.port}/ -> HTTP $code (state=${status.state})",
                durationMs = elapsed,
            )
        } catch (t: Throwable) {
            CheckItem(
                name = "内蔵 openvscode-server HTTP 応答 (Gate 2)",
                ok = false,
                detail = "url=http://127.0.0.1:${status.port}/ -> ${t.javaClass.simpleName}: ${t.message}",
                durationMs = System.currentTimeMillis() - t0,
            )
        } finally {
            conn?.disconnect()
        }
    }
}
