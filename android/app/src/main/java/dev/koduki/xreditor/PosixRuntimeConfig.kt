package dev.koduki.xreditor

import org.json.JSONObject
import java.io.File

/**
 * Pure-JVM configuration builder for PRoot + Debian minimal + embedded openvscode-server.
 * Kept free of Android framework dependencies so every flag, bind mount, fake /proc file,
 * and initial VS Code OST settings.json can be unit-tested on JVM.
 */
object PosixRuntimeConfig {

    const val RUNTIME_VERSION = "0.5.0-debian12-openvscode-1.109.5"
    const val ASSET_ROOTFS = "runtime/debian-arm64-rootfs.tgz"
    const val ASSET_OPENVSCODE = "runtime/openvscode-server-linux-arm64.tgz"

    const val GUEST_WORKSPACE = "/workspace"
    const val GUEST_OPENVSCODE_DIR = "/opt/openvscode-server"
    const val GUEST_OPENVSCODE_BIN = "$GUEST_OPENVSCODE_DIR/bin/openvscode-server"
    const val GUEST_VSCODE_DATA = "/var/lib/xr-vscode"

    data class Paths(
        val runtimeRoot: File,
        val nativeLibDir: File,
        val androidSharedDir: File,
        val androidSdcardDir: File? = null,
    ) {
        val rootfsDir: File get() = File(runtimeRoot, "rootfs")
        val stagingDir: File get() = File(runtimeRoot, "staging")
        val workspaceDir: File get() = File(rootfsDir, "workspace")
        val openvscodeDir: File get() = File(rootfsDir, "opt/openvscode-server")
        val sysdataDir: File get() = File(runtimeRoot, "sysdata")
        val shmDir: File get() = File(runtimeRoot, "shm")
        val tmpDir: File get() = File(runtimeRoot, "tmp")
        val vscodeDataDir: File get() = File(runtimeRoot, "vscode-data")
        val versionMarker: File get() = File(runtimeRoot, ".runtime-version")

        val prootBin: File get() = File(nativeLibDir, "libproot.so")
        val prootLoader: File get() = File(nativeLibDir, "libproot-loader.so")
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
            "-b", "${paths.vscodeDataDir.absolutePath}:$GUEST_VSCODE_DATA",
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
    ): Map<String, String> {
        val env = linkedMapOf(
            "PROOT_LOADER" to paths.prootLoader.absolutePath,
            "PROOT_TMP_DIR" to paths.tmpDir.absolutePath,
            "HOME" to "/root",
            "USER" to "root",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "VSCODE_AGENT_FOLDER" to GUEST_VSCODE_DATA,
        )
        if (disableSeccomp) {
            env["PROOT_NO_SECCOMP"] = "1"
        }
        if (adminPort > 0 && adminToken.isNotEmpty()) {
            env["XR_ADMIN_PORT"] = adminPort.toString()
            env["XR_ADMIN_TOKEN"] = adminToken
        }
        return env
    }

    fun buildOpenVsCodeServerCommand(port: Int): List<String> = listOf(
        GUEST_OPENVSCODE_BIN,
        "--host", "127.0.0.1",
        "--port", port.toString(),
        "--without-connection-token",
        "--accept-server-license-terms",
        "--telemetry-level", "off",
        "--disable-workspace-trust",
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
     * Minimal spatial OST (#000000) VS Code settings:
     * focuses on Open / Close / Edit / Save + minimal integrated terminal with zero UI clutter.
     */
    fun buildMinimalVsCodeSettingsJson(): String {
        val colorCustomizations = JSONObject()
            .put("editor.background", "#000000")
            .put("editorGutter.background", "#000000")
            .put("sideBar.background", "#050505")
            .put("sideBarSectionHeader.background", "#0a0a0a")
            .put("activityBar.background", "#000000")
            .put("panel.background", "#000000")
            .put("terminal.background", "#000000")
            .put("titleBar.activeBackground", "#000000")
            .put("titleBar.inactiveBackground", "#000000")
            .put("statusBar.background", "#080808")
            .put("tab.activeBackground", "#0c0c0c")
            .put("tab.inactiveBackground", "#000000")
            .put("editorGroupHeader.tabsBackground", "#000000")

        val bashProfile = JSONObject()
            .put("path", "/bin/bash")
            .put("args", org.json.JSONArray().put("-l"))

        val root = JSONObject()
            .put("workbench.colorTheme", "Default Dark Modern")
            .put("workbench.colorCustomizations", colorCustomizations)
            .put("workbench.startupEditor", "readme")
            .put("workbench.tips.enabled", false)
            .put("workbench.welcomePage.walkthroughs.openOnInstall", false)
            .put("workbench.secondarySideBar.defaultVisibility", "hidden")
            .put("editor.minimap.enabled", false)
            .put("editor.fontSize", 15)
            .put("editor.lineHeight", 24)
            .put("breadcrumbs.enabled", false)
            .put("files.autoSave", "off")
            .put("security.workspace.trust.enabled", false)
            .put("telemetry.telemetryLevel", "off")
            .put("extensions.ignoreRecommendations", true)
            .put("terminal.integrated.defaultProfile.linux", "bash")
            .put("terminal.integrated.profiles.linux", JSONObject().put("bash", bashProfile))

        return root.toString(2) + "\n"
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
            "version" to "Linux version 6.6.0-android-xr (xr-editor) #1 SMP PREEMPT aarch64\n",
            "vmstat" to "nr_free_pages 262144\nnr_active_anon 65536\npgpgin 1024\npgpgout 512\n",
        )
    }

    fun buildDefaultResolvConf(): String =
        "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"

    fun buildWelcomeReadme(): String = """
# XR-Editor Workspace (`/workspace`)

Android XR 向けの単体完結エディタ（Debian minimal + 内蔵 `openvscode-server`）です。

## 基本操作（物理キーボード）
- **ファイルを開く**: 左サイドバーをクリック、または `Ctrl+P` / `Ctrl+O`
- **保存する**: `Ctrl+S`
- **タブを閉じる**: `Ctrl+W`
- **ターミナル開閉**: `Ctrl+` `（バッククォート）

## Android ↔ POSIX ファイル共有
- **POSIX → Android**: `/workspace/android`（`/android/shared` および全ファイルアクセス許可時は `/android/sdcard`）
- **Android → POSIX**: Android XR の「Files」アプリから **XR-Editor** を開くと、この `/workspace` が通常のフォルダとして直接読み書きできます。

## `gh` / `git` などの CLI 導入（POSIX ターミナル内）
```bash
apt update && apt install -y ca-certificates git gh
gh --version
```
""".trimIndent() + "\n"
}
