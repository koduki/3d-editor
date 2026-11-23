package dev.koduki.kucode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PosixRuntimeConfigTest {

    @Test
    fun buildProotCommand_includesDecoupledWorkspaceHomeAndAndroidMounts() {
        val paths = PosixRuntimeConfig.Paths(
            runtimeRoot = File("/data/user/0/dev.koduki.kucode/files/runtime"),
            workspaceDir = File("/data/user/0/dev.koduki.kucode/files/workspace"),
            homeDir = File("/data/user/0/dev.koduki.kucode/files/home"),
            nativeLibDir = File("/data/app/kucode/lib/arm64"),
            androidSharedDir = File("/sdcard/Android/data/dev.koduki.kucode/files/shared"),
            androidSdcardDir = File("/storage/emulated/0"),
        )

        assertEquals(File(paths.rootfsDir, ".kucode-runtime-version"), paths.versionMarker)
        assertEquals(File(paths.vscodeDataDir, "data/Machine/settings.json"), paths.machineSettingsFile)

        val cmd = PosixRuntimeConfig.buildProotCommand(
            paths = paths,
            guestCommand = PosixRuntimeConfig.buildOpenVsCodeServerCommand(PosixRuntimeConfig.PREFERRED_SERVER_PORT),
        )
        assertEquals("/data/app/kucode/lib/arm64/libproot.so", cmd.first())
        assertTrue(cmd.contains("--link2symlink"))
        assertTrue(cmd.contains("--kill-on-exit"))
        assertTrue(cmd.contains("/data/user/0/dev.koduki.kucode/files/workspace:/workspace"))
        assertTrue(cmd.contains("/data/user/0/dev.koduki.kucode/files/home:/root"))
        assertTrue(cmd.contains("/sdcard/Android/data/dev.koduki.kucode/files/shared:/android/shared"))
        assertTrue(cmd.contains("/storage/emulated/0:/android/sdcard"))
        assertFalse(cmd.contains("--without-connection-token"))
        assertEquals("/var/lib/kucode-vscode/.editor-token", cmd[cmd.indexOf("--connection-token-file") + 1])
        assertTrue(cmd.contains("18765"))

        val env = PosixRuntimeConfig.buildProotEnvironment(
            paths = paths,
            disableSeccomp = true,
            adminPort = 43210,
            adminToken = "secret-token",
        )
        assertEquals("/data/app/kucode/lib/arm64/libproot-loader.so", env["PROOT_LOADER"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertEquals("43210", env["KUCODE_ADMIN_PORT"])
        assertEquals("secret-token", env["KUCODE_ADMIN_TOKEN"])
    }

    @Test
    fun shouldKeepInsideWebView_routesOtherLocalhostPortsToExternalBrowser() {
        assertFalse(PosixRuntimeConfig.shouldKeepInsideWebView("file", "", -1, 18765))
        assertTrue(PosixRuntimeConfig.shouldKeepInsideWebView("http", "127.0.0.1", 18765, 18765))
        assertTrue(PosixRuntimeConfig.shouldKeepInsideWebView("http", "localhost", 18765, 18765))

        // Dev servers on other ports and external websites must open in sibling XR browser
        assertFalse(PosixRuntimeConfig.shouldKeepInsideWebView("http", "127.0.0.1", 3000, 18765))
        assertFalse(PosixRuntimeConfig.shouldKeepInsideWebView("http", "localhost", 5173, 18765))
        assertFalse(PosixRuntimeConfig.shouldKeepInsideWebView("https", "github.com", -1, 18765))
    }

    @Test
    fun buildMinimalVsCodeSettingsJson_setsOstHighContrastCursorZoomAndTerminalDom() {
        val json = JSONObject(PosixRuntimeConfig.buildMinimalVsCodeSettingsJson())
        assertEquals(false, json.getBoolean("editor.minimap.enabled"))
        assertEquals(false, json.getBoolean("breadcrumbs.enabled"))
        assertEquals(false, json.getBoolean("security.workspace.trust.enabled"))
        assertEquals("off", json.getString("telemetry.telemetryLevel"))
        assertEquals("off", json.getString("files.autoSave"))
        assertEquals("off", json.getString("terminal.integrated.gpuAcceleration"))
        assertEquals("500", json.getString("editor.fontWeight"))
        assertEquals(3, json.getInt("editor.cursorWidth"))
        assertEquals("solid", json.getString("editor.cursorBlinking"))
        assertEquals(0.5, json.getDouble("window.zoomLevel"), 0.001)
        assertEquals("bash", json.getString("terminal.integrated.defaultProfile.linux"))

        val colors = json.getJSONObject("workbench.colorCustomizations")
        assertEquals("#000000", colors.getString("editor.background"))
        assertEquals("#000000", colors.getString("terminal.background"))
        assertEquals("#38bdf8", colors.getString("tab.activeBorderTop"))
    }

    @Test
    fun mergeDefaultVsCodeSettings_preservesUserOverridesAndAddsMissingDefaults() {
        val existing = """{"editor.fontSize": 20, "custom.flag": true}"""
        val mergedJson = PosixRuntimeConfig.mergeDefaultVsCodeSettings(existing)
        assertNotNull(mergedJson)
        val merged = JSONObject(mergedJson!!)
        assertEquals(20, merged.getInt("editor.fontSize"))
        assertEquals(true, merged.getBoolean("custom.flag"))
        assertEquals("off", merged.getString("terminal.integrated.gpuAcceleration"))
        assertEquals("500", merged.getString("editor.fontWeight"))
        assertEquals(3, merged.getInt("editor.cursorWidth"))
    }

    @Test
    fun mergeDefaultVsCodeSettings_returnsDefaultsWhenNullOrBlank() {
        val fromNull = PosixRuntimeConfig.mergeDefaultVsCodeSettings(null)
        val fromBlank = PosixRuntimeConfig.mergeDefaultVsCodeSettings("   \n\t ")
        assertNotNull(fromNull)
        assertEquals(fromNull, fromBlank)
        assertEquals(16, JSONObject(fromNull!!).getInt("editor.fontSize"))
    }

    @Test
    fun mergeDefaultVsCodeSettings_returnsNullOnJsoncOrMalformedInput() {
        assertNull(PosixRuntimeConfig.mergeDefaultVsCodeSettings("""{"editor.fontSize": 20,}"""))
        assertNull(PosixRuntimeConfig.mergeDefaultVsCodeSettings("""{"arr": [1, 2,]}"""))
        assertNull(PosixRuntimeConfig.mergeDefaultVsCodeSettings("// user comment\n{\"editor.fontSize\": 20}"))
        assertNull(PosixRuntimeConfig.mergeDefaultVsCodeSettings("""{"editor.fontSize": 20 /* inline */}"""))
        assertNull(PosixRuntimeConfig.mergeDefaultVsCodeSettings("{broken json"))
        assertNull(PosixRuntimeConfig.mergeDefaultVsCodeSettings("""{"editor.fontSize": 20} trailing"""))
    }

    @Test
    fun mergeDefaultVsCodeSettings_allowsCommentLikeSequencesInsideJsonStrings() {
        val existing = """{"custom.url": "https://example.com/a,}", "custom.escaped": "quote \" // ok"}"""
        val mergedJson = PosixRuntimeConfig.mergeDefaultVsCodeSettings(existing)
        assertNotNull(mergedJson)
        val merged = JSONObject(mergedJson!!)
        assertEquals("https://example.com/a,}", merged.getString("custom.url"))
        assertEquals("quote \" // ok", merged.getString("custom.escaped"))
        assertEquals(16, merged.getInt("editor.fontSize"))
    }

    @Test
    fun mergeDefaultVsCodeSettings_returnsOriginalStringWhenNoKeysMissing() {
        val complete = PosixRuntimeConfig.buildMinimalVsCodeSettingsObject()
            .put("custom.keep", "yes")
            .toString()
        assertEquals(complete, PosixRuntimeConfig.mergeDefaultVsCodeSettings(complete))
    }

    @Test
    fun buildResolvConf_prefersSystemDnsAndFiltersLoopback() {
        val dynamic = PosixRuntimeConfig.buildResolvConf(listOf("127.0.0.1", "100.100.100.100", "192.168.1.1%wlan0", "100.100.100.100"))
        assertEquals("nameserver 100.100.100.100\nnameserver 192.168.1.1\n", dynamic)

        val fallback = PosixRuntimeConfig.buildResolvConf(listOf("127.0.0.53", "::1"))
        assertEquals("nameserver 1.1.1.1\nnameserver 8.8.8.8\n", fallback)
    }

    @Test
    fun buildFakeSysdataFiles_providesRequiredProcEntries() {
        val files = PosixRuntimeConfig.buildFakeSysdataFiles(cpuCount = 4)
        assertTrue(files.containsKey("stat"))
        assertTrue(files.containsKey("loadavg"))
        assertTrue(files.containsKey("uptime"))
        assertTrue(files.containsKey("version"))
        assertTrue(files.containsKey("vmstat"))
        assertTrue(files.getValue("stat").contains("cpu3 "))
        assertFalse(files.getValue("stat").contains("cpu4 "))
    }
}
