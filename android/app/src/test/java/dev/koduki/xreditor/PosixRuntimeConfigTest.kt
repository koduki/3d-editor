package dev.koduki.xreditor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PosixRuntimeConfigTest {

    @Test
    fun buildProotCommand_includesAndroidMountsAndUnbundledLoader() {
        val paths = PosixRuntimeConfig.Paths(
            runtimeRoot = File("/data/user/0/dev.koduki.xreditor/files/runtime"),
            nativeLibDir = File("/data/app/xreditor/lib/arm64"),
            androidSharedDir = File("/sdcard/Android/data/dev.koduki.xreditor/files/shared"),
            androidSdcardDir = File("/storage/emulated/0"),
        )

        val cmd = PosixRuntimeConfig.buildProotCommand(
            paths = paths,
            guestCommand = PosixRuntimeConfig.buildOpenVsCodeServerCommand(18765),
        )
        assertEquals("/data/app/xreditor/lib/arm64/libproot.so", cmd.first())
        assertTrue(cmd.contains("--link2symlink"))
        assertTrue(cmd.contains("--kill-on-exit"))
        assertTrue(cmd.contains("/sdcard/Android/data/dev.koduki.xreditor/files/shared:/android/shared"))
        assertTrue(cmd.contains("/storage/emulated/0:/android/sdcard"))
        assertTrue(cmd.contains("--without-connection-token"))
        assertTrue(cmd.contains("18765"))

        val env = PosixRuntimeConfig.buildProotEnvironment(paths, disableSeccomp = true)
        assertEquals("/data/app/xreditor/lib/arm64/libproot-loader.so", env["PROOT_LOADER"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
    }

    @Test
    fun buildMinimalVsCodeSettingsJson_setsOstBlackAndMinimalUi() {
        val json = JSONObject(PosixRuntimeConfig.buildMinimalVsCodeSettingsJson())
        assertEquals(false, json.getBoolean("editor.minimap.enabled"))
        assertEquals(false, json.getBoolean("breadcrumbs.enabled"))
        assertEquals(false, json.getBoolean("security.workspace.trust.enabled"))
        assertEquals("off", json.getString("telemetry.telemetryLevel"))
        assertEquals("off", json.getString("files.autoSave"))
        assertEquals("bash", json.getString("terminal.integrated.defaultProfile.linux"))

        val colors = json.getJSONObject("workbench.colorCustomizations")
        assertEquals("#000000", colors.getString("editor.background"))
        assertEquals("#000000", colors.getString("terminal.background"))
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
