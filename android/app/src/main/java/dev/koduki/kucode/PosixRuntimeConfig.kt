package dev.koduki.kucode

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pure-JVM configuration builder for PRoot + Debian minimal + embedded openvscode-server.
 * Kept free of Android framework dependencies so every flag, bind mount, routing guard,
 * DNS formatter, and OST/VST VS Code settings preset can be unit-tested on JVM.
 */
object PosixRuntimeConfig {

    const val RUNTIME_VERSION = "0.8.0-debian12-openvscode-1.109.5"
    const val ASSET_ROOTFS = "runtime/debian-arm64-rootfs.tgz"
    const val ASSET_OPENVSCODE = "runtime/openvscode-server-linux-arm64.tgz"

    const val PREFERRED_SERVER_PORT = 18765

    const val GUEST_WORKSPACE = "/workspace"
    const val GUEST_HOME = "/root"
    const val GUEST_OPENVSCODE_DIR = "/opt/openvscode-server"
    const val GUEST_OPENVSCODE_BIN = "$GUEST_OPENVSCODE_DIR/bin/openvscode-server"
    const val GUEST_VSCODE_DATA = "/var/lib/kucode-vscode"
    const val GUEST_DIAGNOSTICS_DIR = "/var/log/kucode"
    const val GUEST_CA_BUNDLE = "/etc/ssl/certs/ca-certificates.crt"
    const val GUEST_CA_DIR = "/etc/ssl/certs"
    const val GUEST_ANDROID_USER_CA_BUNDLE = "/etc/ssl/kucode-android-user-ca.crt"
    const val GUEST_APT_PROOT_CONF = "/etc/apt/apt.conf.d/99kucode-proot"

    data class Paths(
        val runtimeRoot: File,
        val workspaceDir: File,
        val homeDir: File,
        val nativeLibDir: File,
        val androidSharedDir: File,
        val androidSdcardDir: File? = null,
        val diagnosticsDir: File = File(runtimeRoot.parentFile ?: runtimeRoot, "diagnostics"),
    ) {
        val rootfsDir: File get() = File(runtimeRoot, "rootfs")
        val stagingDir: File get() = File(runtimeRoot, "staging")
        val openvscodeDir: File get() = File(rootfsDir, "opt/openvscode-server")
        val sysdataDir: File get() = File(runtimeRoot, "sysdata")
        val shmDir: File get() = File(runtimeRoot, "shm")
        val tmpDir: File get() = File(runtimeRoot, "tmp")
        val vscodeDataDir: File get() = File(runtimeRoot, "vscode-data")
        val machineSettingsFile: File get() = File(vscodeDataDir, "data/Machine/settings.json")
        val versionMarker: File get() = File(rootfsDir, ".kucode-runtime-version")
        val backupRootfsDir: File get() = File(runtimeRoot, "rootfs.previous")
        val guestCaBundleHostFile: File get() = File(rootfsDir, "etc/ssl/certs/ca-certificates.crt")
        val androidUserCaBundleFile: File get() = File(rootfsDir, "etc/ssl/kucode-android-user-ca.crt")
        val aptProotConfFile: File get() = File(rootfsDir, "etc/apt/apt.conf.d/99kucode-proot")

        val prootBin: File get() = File(nativeLibDir, "libproot.so")
        val prootLoader: File get() = File(nativeLibDir, "libproot-loader.so")
    }

    /**
     * Decides whether a URL navigation stays inside the editor WebView or should open in
     * an external Android XR browser window (e.g. a dev server on 127.0.0.1:3000 or GitHub auth).
     *
     * Allows VS Code's own sandboxed webview subframes (`*.vscode-cdn.net`, `about:blank`, `blob:`,
     * `data:`) when [isForMainFrame] is `false` so Markdown previews and extension pages work
     * without misfiring external browser intents.
     */
    fun shouldKeepInsideWebView(
        scheme: String?,
        host: String?,
        port: Int,
        editorServerPort: Int,
        isForMainFrame: Boolean = true,
    ): Boolean {
        val normalizedScheme = scheme?.lowercase()
        val normalizedHost = host?.lowercase()
        if (normalizedScheme == "http" &&
            (normalizedHost == "127.0.0.1" || normalizedHost == "localhost") &&
            editorServerPort > 0 &&
            port == editorServerPort
        ) {
            return true
        }
        if (!isForMainFrame) {
            if (normalizedScheme == "about" || normalizedScheme == "blob" || normalizedScheme == "data") {
                return true
            }
            if (normalizedScheme == "https" &&
                normalizedHost != null &&
                (normalizedHost == "vscode-cdn.net" || normalizedHost.endsWith(".vscode-cdn.net"))
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Validates that a target URI is safe to open in an external Android XR browser window
     * (`http`/`https` only, never exposing the embedded editor's connection token or own port).
     */
    fun isSafeExternalBrowserUri(
        scheme: String?,
        host: String?,
        port: Int,
        rawQuery: String?,
        editorServerPort: Int,
    ): Boolean {
        val normalizedScheme = scheme?.lowercase() ?: return false
        if (normalizedScheme != "http" && normalizedScheme != "https") return false
        val normalizedHost = host?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        if ((normalizedHost == "127.0.0.1" || normalizedHost == "localhost") &&
            editorServerPort > 0 &&
            port == editorServerPort
        ) {
            return false
        }
        if (!rawQuery.isNullOrEmpty()) {
            val hasConnectionToken = rawQuery.split('&').any { param ->
                param.substringBefore('=').equals("tkn", ignoreCase = true)
            }
            if (hasConnectionToken) return false
        }
        return true
    }

    fun buildProotCommand(
        paths: Paths,
        guestCommand: List<String>,
        workingDir: String = GUEST_WORKSPACE,
    ): List<String> {
        val cmd = mutableListOf(
            paths.prootBin.absolutePath,
            "--link2symlink",
            "--kill-on-exit",
            "-0",
            "-r", paths.rootfsDir.absolutePath,
            "-w", workingDir,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${File(paths.sysdataDir, "stat").absolutePath}:/proc/stat",
            "-b", "${File(paths.sysdataDir, "loadavg").absolutePath}:/proc/loadavg",
            "-b", "${File(paths.sysdataDir, "uptime").absolutePath}:/proc/uptime",
            "-b", "${File(paths.sysdataDir, "version").absolutePath}:/proc/version",
            "-b", "${File(paths.sysdataDir, "vmstat").absolutePath}:/proc/vmstat",
            "-b", "${paths.shmDir.absolutePath}:/dev/shm",
            "-b", "${paths.tmpDir.absolutePath}:/tmp",
            "-b", "${paths.workspaceDir.absolutePath}:$GUEST_WORKSPACE",
            "-b", "${paths.homeDir.absolutePath}:$GUEST_HOME",
            "-b", "${paths.vscodeDataDir.absolutePath}:$GUEST_VSCODE_DATA",
            "-b", "${paths.diagnosticsDir.absolutePath}:$GUEST_DIAGNOSTICS_DIR",
            "-b", "${paths.androidSharedDir.absolutePath}:/android/shared",
        )

        val sdcard = paths.androidSdcardDir
        if (sdcard != null) {
            cmd.add("-b")
            cmd.add("${sdcard.absolutePath}:/android/sdcard")
        }

        cmd.addAll(guestCommand)
        return cmd
    }

    fun buildProotEnvironment(
        paths: Paths,
        disableSeccomp: Boolean = false,
        adminPort: Int = 0,
        adminToken: String = "",
        includeNodeExtraCaCerts: Boolean = paths.androidUserCaBundleFile.isFile,
    ): Map<String, String> {
        val env = linkedMapOf(
            "PROOT_LOADER" to paths.prootLoader.absolutePath,
            "PROOT_TMP_DIR" to paths.tmpDir.absolutePath,
            "TMPDIR" to "/tmp",
            "HOME" to GUEST_HOME,
            "USER" to "root",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "VSCODE_AGENT_FOLDER" to GUEST_VSCODE_DATA,
            "SSL_CERT_FILE" to GUEST_CA_BUNDLE,
            "SSL_CERT_DIR" to GUEST_CA_DIR,
            "CURL_CA_BUNDLE" to GUEST_CA_BUNDLE,
            "GIT_SSL_CAINFO" to GUEST_CA_BUNDLE,
            "REQUESTS_CA_BUNDLE" to GUEST_CA_BUNDLE,
            "PIP_CERT" to GUEST_CA_BUNDLE,
        )
        if (includeNodeExtraCaCerts) {
            env["NODE_EXTRA_CA_CERTS"] = GUEST_ANDROID_USER_CA_BUNDLE
        }
        if (disableSeccomp) {
            env["PROOT_NO_SECCOMP"] = "1"
        }
        if (adminPort > 0 && adminToken.isNotEmpty()) {
            env["KUCODE_ADMIN_PORT"] = adminPort.toString()
            env["KUCODE_ADMIN_TOKEN"] = adminToken
            env["BROWSER"] = "/usr/local/bin/kucode-open"
            env["GH_BROWSER"] = "/usr/local/bin/kucode-open"
        }
        return env
    }

    fun buildOpenVsCodeServerCommand(port: Int): List<String> = listOf(
        GUEST_OPENVSCODE_BIN,
        "--host", "127.0.0.1",
        "--port", port.toString(),
        "--connection-token-file", "$GUEST_VSCODE_DATA/.editor-token",
        "--accept-server-license-terms",
        "--telemetry-level", "off",
        "--disable-workspace-trust",
        "--without-browser-env-var",
        "--default-folder", GUEST_WORKSPACE,
        "--server-data-dir", GUEST_VSCODE_DATA,
        "--user-data-dir", "$GUEST_VSCODE_DATA/user",
        "--extensions-dir", "$GUEST_VSCODE_DATA/extensions",
    )

    fun buildShellCommand(command: String): List<String> = listOf(
        "/bin/bash",
        "-lc",
        command,
    )

    /**
     * OST (#000000 optical see-through) high-contrast developer preset for Android XR glasses:
     * - Pure black background (zero Micro-OLED glow) with clear borders & active indicators
     * - Medium font weight (500), 16px editor font, 0.5 zoom for 70° FOV legibility
     * - Thick solid cursor (3px) and brightened comments so text survives real-world ambient light
     * - DOM-rendered integrated terminal (`gpuAcceleration: "off"`) for WebView stability
     */
    fun buildMinimalVsCodeSettingsObject(): JSONObject {
        val colorCustomizations = JSONObject()
            .put("editor.background", "#000000")
            .put("editor.foreground", "#f3f4f6")
            .put("editorGutter.background", "#000000")
            .put("editorLineNumber.foreground", "#6b7280")
            .put("editorLineNumber.activeForeground", "#f9fafb")
            .put("editorCursor.foreground", "#38bdf8")
            .put("sideBar.background", "#050505")
            .put("sideBar.foreground", "#e5e7eb")
            .put("sideBar.border", "#1f2937")
            .put("sideBarSectionHeader.background", "#0a0a0c")
            .put("activityBar.background", "#000000")
            .put("activityBar.foreground", "#f3f4f6")
            .put("activityBar.activeBorder", "#38bdf8")
            .put("panel.background", "#000000")
            .put("panel.border", "#1f2937")
            .put("terminal.background", "#000000")
            .put("terminal.foreground", "#f3f4f6")
            .put("terminalCursor.foreground", "#38bdf8")
            .put("titleBar.activeBackground", "#000000")
            .put("titleBar.inactiveBackground", "#000000")
            .put("statusBar.background", "#08080a")
            .put("statusBar.foreground", "#e5e7eb")
            .put("statusBar.border", "#1f2937")
            .put("tab.activeBackground", "#0d1117")
            .put("tab.activeForeground", "#ffffff")
            .put("tab.activeBorderTop", "#38bdf8")
            .put("tab.inactiveBackground", "#000000")
            .put("tab.inactiveForeground", "#9ca3af")
            .put("editorGroupHeader.tabsBackground", "#000000")

        val tokenColorCustomizations = JSONObject()
            .put("comments", "#8da2be")

        val bashProfile = JSONObject()
            .put("path", "/bin/bash")
            .put("args", JSONArray().put("-l"))

        return JSONObject()
            .put("workbench.colorTheme", "Default Dark Modern")
            .put("workbench.colorCustomizations", colorCustomizations)
            .put("editor.tokenColorCustomizations", tokenColorCustomizations)
            .put("workbench.startupEditor", "readme")
            .put("workbench.tips.enabled", false)
            .put("workbench.welcomePage.walkthroughs.openOnInstall", false)
            .put("workbench.secondarySideBar.defaultVisibility", "hidden")
            .put("window.zoomLevel", 0.5)
            .put("editor.minimap.enabled", false)
            .put("editor.fontSize", 16)
            .put("editor.lineHeight", 26)
            .put("editor.fontWeight", "500")
            .put("editor.cursorWidth", 3)
            .put("editor.cursorBlinking", "solid")
            .put("breadcrumbs.enabled", false)
            .put("files.autoSave", "off")
            .put("security.workspace.trust.enabled", false)
            .put("telemetry.telemetryLevel", "off")
            .put("extensions.ignoreRecommendations", true)
            // Disable WebGL terminal renderer to avoid black/garbled text on Android WebView
            .put("terminal.integrated.gpuAcceleration", "off")
            .put("terminal.integrated.fontSize", 15)
            .put("terminal.integrated.defaultProfile.linux", "bash")
            .put("terminal.integrated.profiles.linux", JSONObject().put("bash", bashProfile))
            .put("terminal.integrated.rightClickBehavior", "copyPaste")
            .put(
                "terminal.integrated.commandsToSkipShell",
                JSONArray().put("kucode.terminalCopy").put("kucode.terminalPaste"),
            )
    }

    fun buildMinimalVsCodeSettingsJson(): String =
        buildMinimalVsCodeSettingsObject().toString(2) + "\n"

    /**
     * Merges missing default keys into an existing settings.json without overwriting
     * values the user has already customized.
     *
     * Returns `null` if [existingJson] is non-blank and cannot be parsed as strict JSON
     * (for example, JSONC with comments or trailing commas) so callers preserve the file
     * untouched instead of overwriting user settings.
     */
    fun mergeDefaultVsCodeSettings(existingJson: String?): String? {
        val defaults = buildMinimalVsCodeSettingsObject()
        if (existingJson.isNullOrBlank()) {
            return defaults.toString(2) + "\n"
        }
        if (hasJsoncSyntaxOrTrailingContent(existingJson)) {
            return null
        }
        val merged = runCatching { JSONObject(existingJson) }.getOrNull()
            ?: return null
        var changed = false
        val keys = defaults.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!merged.has(key)) {
                merged.put(key, defaults.get(key))
                changed = true
            }
        }
        return if (changed) merged.toString(2) + "\n" else existingJson
    }

    private fun hasJsoncSyntaxOrTrailingContent(raw: String): Boolean {
        var i = 0
        val len = raw.length
        var inString = false
        var escaped = false
        var depth = 0
        var rootClosed = false

        while (i < len) {
            val c = raw[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                i++
                continue
            }

            if (c.isWhitespace()) {
                i++
                continue
            }

            if (rootClosed) return true

            if (c == '"') {
                if (depth == 0) return true
                inString = true
                i++
                continue
            }

            if (c == '/' && i + 1 < len && (raw[i + 1] == '/' || raw[i + 1] == '*')) {
                return true
            }

            if (c == '{' || c == '[') {
                if (depth == 0 && c != '{') return true
                depth++
                i++
                continue
            }

            if (c == '}' || c == ']') {
                depth--
                if (depth < 0) return true
                if (depth == 0) rootClosed = true
                i++
                continue
            }

            if (c == ',') {
                var j = i + 1
                while (j < len && raw[j].isWhitespace()) j++
                if (j < len && (raw[j] == '}' || raw[j] == ']')) {
                    return true
                }
            }

            i++
        }

        return inString || !rootClosed || depth != 0
    }

    fun buildFakeSysdataFiles(cpuCount: Int = 4): Map<String, String> {
        val safeCpus = cpuCount.coerceIn(1, 16)
        val statBuilder = StringBuilder()
        statBuilder.append("cpu  1200 10 800 48000 120 0 20 0 0 0\n")
        for (i in 0 until safeCpus) {
            statBuilder.append("cpu$i 300 2 200 12000 30 0 5 0 0 0\n")
        }
        statBuilder.append("intr 10000\nctxt 50000\nbtime 1760000000\nprocesses 200\nprocs_running 1\nprocs_blocked 0\n")

        return mapOf(
            "stat" to statBuilder.toString(),
            "loadavg" to "0.10 0.08 0.05 1/120 100\n",
            "uptime" to "3600.00 7200.00\n",
            "version" to "Linux version 6.6.0-android-xr (kucode) #1 SMP PREEMPT aarch64\n",
            "vmstat" to "nr_free_pages 262144\nnr_active_anon 65536\npgpgin 1024\npgpgout 512\n",
        )
    }

    fun buildResolvConf(systemDnsServers: List<String> = emptyList()): String {
        val cleaned = systemDnsServers
            .map { it.trim().substringBefore('%') }
            .filter { it.isNotEmpty() && !it.startsWith("127.") && it != "::1" }
            .distinct()
            .take(3)
        val servers = if (cleaned.isNotEmpty()) {
            cleaned
        } else {
            listOf("1.1.1.1", "8.8.8.8")
        }
        return servers.joinToString("\n", postfix = "\n") { "nameserver $it" }
    }

    fun buildAptProotConf(): String = """
        // Managed by KuCode for Android PRoot + TLS compatibility
        APT::Sandbox::User "root";
        APT::Sandbox::Seccomp "false";
        Acquire::https::CaInfo "$GUEST_CA_BUNDLE";
    """.trimIndent() + "\n"

    fun buildClipboardHelperScripts(
        nodeBin: String = "$GUEST_OPENVSCODE_DIR/node",
    ): Map<String, String> = linkedMapOf(
        "kucode-copy" to """
            #!$nodeBin
            'use strict';
            const http = require('node:http');
            const port = Number(process.env.KUCODE_ADMIN_PORT || 0);
            const token = process.env.KUCODE_ADMIN_TOKEN || '';
            if (!port || !token) {
              console.error('kucode-copy: KuCode host clipboard service is unavailable in this shell.');
              process.exit(1);
            }
            const MAX_BYTES = 1024 * 1024;
            function send(payload) {
              if (payload.length > MAX_BYTES) {
                console.error('kucode-copy: input exceeds 1 MiB limit.');
                process.exit(1);
              }
              const req = http.request(
                {
                  hostname: '127.0.0.1',
                  port,
                  path: '/copy',
                  method: 'POST',
                  headers: {
                    'X-KuCode-Token': token,
                    'Content-Type': 'text/plain; charset=utf-8',
                    'Content-Length': String(payload.length),
                  },
                  timeout: 5000,
                },
                (res) => {
                  const chunks = [];
                  res.on('data', (c) => chunks.push(c));
                  res.on('end', () => {
                    if (res.statusCode >= 200 && res.statusCode < 300) {
                      process.exit(0);
                    } else {
                      console.error('kucode-copy: HTTP ' + res.statusCode + ': ' + Buffer.concat(chunks).toString('utf8'));
                      process.exit(1);
                    }
                  });
                },
              );
              req.on('timeout', () => req.destroy(new Error('timeout')));
              req.on('error', (err) => {
                console.error('kucode-copy: ' + err.message);
                process.exit(1);
              });
              req.end(payload);
            }
            if (process.argv.length > 2) {
              send(Buffer.from(process.argv.slice(2).join(' '), 'utf8'));
            } else {
              const chunks = [];
              let total = 0;
              process.stdin.on('data', (chunk) => {
                total += chunk.length;
                if (total > MAX_BYTES) {
                  console.error('kucode-copy: input exceeds 1 MiB limit.');
                  process.exit(1);
                }
                chunks.push(chunk);
              });
              process.stdin.on('end', () => send(Buffer.concat(chunks)));
              process.stdin.on('error', (err) => {
                console.error('kucode-copy: ' + err.message);
                process.exit(1);
              });
            }
        """.trimIndent() + "\n",
        "kucode-paste" to """
            #!$nodeBin
            'use strict';
            const http = require('node:http');
            const port = Number(process.env.KUCODE_ADMIN_PORT || 0);
            const token = process.env.KUCODE_ADMIN_TOKEN || '';
            if (!port || !token) {
              console.error('kucode-paste: KuCode host clipboard service is unavailable in this shell.');
              process.exit(1);
            }
            const req = http.request(
              {
                hostname: '127.0.0.1',
                port,
                path: '/clipboard',
                method: 'GET',
                headers: { 'X-KuCode-Token': token },
                timeout: 5000,
              },
              (res) => {
                const chunks = [];
                res.on('data', (c) => chunks.push(c));
                res.on('end', () => {
                  const buf = Buffer.concat(chunks);
                  if (res.statusCode >= 200 && res.statusCode < 300) {
                    process.stdout.on('error', (err) => {
                      if (err && err.code === 'EPIPE') process.exit(0);
                      console.error('kucode-paste: ' + err.message);
                      process.exit(1);
                    });
                    process.stdout.write(buf, () => process.exit(0));
                  } else {
                    console.error('kucode-paste: HTTP ' + res.statusCode + ': ' + buf.toString('utf8'));
                    process.exit(1);
                  }
                });
              },
            );
            req.on('timeout', () => req.destroy(new Error('timeout')));
            req.on('error', (err) => {
              console.error('kucode-paste: ' + err.message);
              process.exit(1);
            });
            req.end();
        """.trimIndent() + "\n",
    )

    fun buildHostCliHelperScripts(
        nodeBin: String = "$GUEST_OPENVSCODE_DIR/node",
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        result.putAll(buildClipboardHelperScripts(nodeBin))

        val openScript = """
            #!$nodeBin
            'use strict';
            const http = require('node:http');
            const port = Number(process.env.KUCODE_ADMIN_PORT || 0);
            const token = process.env.KUCODE_ADMIN_TOKEN || '';
            const args = process.argv.slice(2);
            const target = args.find((a) => /^https?:\/\//i.test(a)) || args[0] || '';
            if (!target) {
              console.error('Usage: kucode-open <http(s)://url>');
              process.exit(1);
            }
            if (!/^https?:\/\//i.test(target)) {
              console.error('kucode-open: only http:// and https:// URLs are supported.');
              process.exit(1);
            }
            if (!port || !token) {
              console.error('kucode-open: KuCode host browser bridge is unavailable in this shell. Open manually: ' + target);
              process.exit(1);
            }
            const payload = Buffer.from(target, 'utf8');
            const req = http.request(
              {
                hostname: '127.0.0.1',
                port,
                path: '/open-url',
                method: 'POST',
                headers: {
                  'X-KuCode-Token': token,
                  'Content-Type': 'text/plain; charset=utf-8',
                  'Content-Length': String(payload.length),
                },
                timeout: 5000,
              },
              (res) => {
                const chunks = [];
                res.on('data', (c) => chunks.push(c));
                res.on('end', () => {
                  if (res.statusCode >= 200 && res.statusCode < 300) {
                    process.exit(0);
                  } else {
                    console.error('kucode-open: HTTP ' + res.statusCode + ': ' + Buffer.concat(chunks).toString('utf8'));
                    process.exit(1);
                  }
                });
              },
            );
            req.on('timeout', () => req.destroy(new Error('timeout')));
            req.on('error', (err) => {
              console.error('kucode-open: ' + err.message);
              process.exit(1);
            });
            req.end(payload);
        """.trimIndent() + "\n"

        result["kucode-open"] = openScript
        result["xdg-open"] = openScript

        result["kucode-logs"] = """
            #!$nodeBin
            'use strict';
            const fs = require('node:fs');
            const path = require('node:path');
            const http = require('node:http');
            const port = Number(process.env.KUCODE_ADMIN_PORT || 0);
            const token = process.env.KUCODE_ADMIN_TOKEN || '';
            const rawArgs = process.argv.slice(2);
            let format = 'ai';
            const positional = [];
            for (const a of rawArgs) {
              if (a === '--raw' || a === '--timeline') format = 'raw';
              else if (a === '--json') format = 'json';
              else if (a === '--ai') format = 'ai';
              else if (a === '-h' || a === '--help') {
                console.log('Usage:');
                console.log('  kucode-logs [show] [latest|prev|prev-abnormal|<sessionId>] [--ai|--raw|--json]');
                console.log('  kucode-logs list [--json]');
                console.log('  kucode-logs export [latest|prev|prev-abnormal|<sessionId>] [outputPath]');
                process.exit(0);
              } else {
                positional.push(a);
              }
            }
            const sub = positional[0] || 'show';
            function fetchHost(urlPath) {
              return new Promise((resolve, reject) => {
                if (!port || !token) {
                  reject(new Error('admin API unavailable'));
                  return;
                }
                const req = http.request(
                  { hostname: '127.0.0.1', port, path: urlPath, method: 'GET', headers: { 'X-KuCode-Token': token }, timeout: 15000 },
                  (res) => {
                    const chunks = [];
                    res.on('data', (c) => chunks.push(c));
                    res.on('end', () => {
                      const body = Buffer.concat(chunks).toString('utf8');
                      if (res.statusCode >= 200 && res.statusCode < 300) resolve(body);
                      else reject(new Error('HTTP ' + res.statusCode + ': ' + body));
                    });
                  },
                );
                req.on('timeout', () => req.destroy(new Error('timeout')));
                req.on('error', reject);
                req.end();
              });
            }
            (async () => {
              if (sub === 'list') {
                const jsonText = await fetchHost('/sessions');
                if (format === 'json') {
                  process.stdout.write(jsonText + '\n');
                  return;
                }
                const parsed = JSON.parse(jsonText);
                const sessions = parsed.sessions || [];
                if (sessions.length === 0) {
                  console.log('(no recorded sessions)');
                  return;
                }
                for (const s of sessions) {
                  const tag = s.isCurrentSession ? '[CURRENT]' : '[' + s.status + ']';
                  console.log(
                    s.sessionId + '  ' + tag + '  ' + s.exitCategory +
                    '  dur=' + s.durationHuman + '  err=' + s.errorCount + '  warn=' + s.warnCount +
                    '  started=' + s.startedAtUtc + (s.exitSummary ? '  (' + s.exitSummary + ')' : ''),
                  );
                }
                return;
              }
              const isExplicitCmd = (sub === 'show' || sub === 'export');
              const selector = encodeURIComponent((isExplicitCmd ? positional[1] : positional[0]) || 'latest');
              let content;
              try {
                content = await fetchHost('/sessions/' + selector + '?format=' + encodeURIComponent(format));
              } catch (err) {
                const fallback = '$GUEST_DIAGNOSTICS_DIR/latest-ai-report.md';
                if (selector === 'latest' && fs.existsSync(fallback)) {
                  content = fs.readFileSync(fallback, 'utf8');
                } else {
                  throw err;
                }
              }
              if (sub === 'export') {
                const dest = positional[2] || path.resolve(process.cwd(), 'kucode-session-' + decodeURIComponent(selector) + (format === 'json' ? '.json' : format === 'raw' ? '.log' : '-ai.md'));
                fs.writeFileSync(dest, content, 'utf8');
                console.log(dest);
              } else {
                process.stdout.write(content.endsWith('\n') ? content : content + '\n');
              }
            })().catch((err) => {
              console.error('kucode-logs: ' + err.message);
              process.exit(1);
            });
        """.trimIndent() + "\n"

        return result
    }

    fun buildDefaultBashrc(): String = """
        # KuCode persistent Debian shell config (/root is mounted outside rootfs)
        export LANG=C.UTF-8
        export LC_ALL=C.UTF-8
        export HISTCONTROL=ignoredups:erasedups
        export HISTSIZE=2000
        alias ls='ls --color=auto'
        alias ll='ls -la'
        PS1='\[\e[36m\]kucode\[\e[0m\]:\[\e[32m\]\w\[\e[0m\]\$ '
    """.trimIndent() + "\n"

    fun buildWelcomeReadme(): String = """
# KuCode Workspace (`/workspace`)

**空間に、書く。** — APK 1 本で完結する、空間コンピューティングのためのコードエディタ（Debian minimal + 内蔵 `openvscode-server`）です。

## 基本操作（物理キーボード）
- **ファイルを開く**: 左サイドバーをクリック、または `Ctrl+P` / `Ctrl+O`
- **コピー / 切り取り / 貼り付け（エディタ）**: `Ctrl+C` / `Ctrl+X` / `Ctrl+V`、または右クリックメニュー
- **コピー / 貼り付け（ターミナル）**: 選択後に**右クリックで自動コピー**（または `Ctrl+Shift+C`）、貼り付けは `Ctrl+Shift+V` / `Ctrl+V`（`Shift+右クリック` でメニュー）、CLI では `kucode-copy` / `kucode-paste`
- **外部ブラウザ起動（`gh auth` / 開発サーバー）**: `gh auth login` や `kucode-open <URL>` / `xdg-open <URL>` でエディタを維持したまま隣の XR ブラウザウィンドウを開きます
- **セッション診断履歴（AI インプット対応）**: 左端 **KuCode** の「セッション診断履歴 & AI レポート」、または CLI で `kucode-logs list` / `kucode-logs show prev`（`/var/log/kucode` にも常時保存）
- **保存する**: `Ctrl+S`
- **タブを閉じる**: `Ctrl+W`
- **ターミナル開閉**: `Ctrl+J` または左端 **KuCode** メニューの「ターミナル開閉」
- **実機チェック / 表示サイズ / OST・VST切替**: 左端 Activity Bar の **KuCode** アイコン（または `Ctrl+Alt+D`）

## Android ↔ POSIX ファイル共有 & 永続領域
- **POSIX → Android**: `/workspace/android`（`/android/shared` および全ファイルアクセス許可時は `/android/sdcard`）
- **Android → POSIX**: Android XR の「Files」アプリから **KuCode**（`/workspace`）および **KuCode Logs**（起動〜終了ごとの診断レポート）を直接参照できます。
- **永続ホーム (`/root`)**: `~/.ssh`, `~/.gitconfig`, `~/.config/gh` は rootfs 外に分離保存されるため、アプリ更新後も引き継がれます。

## `gh` / `git` などの CLI 導入（POSIX ターミナル内）
```bash
apt update && apt install -y ca-certificates git gh curl openssh-client
gh auth login
```
""".trimIndent() + "\n"
}
