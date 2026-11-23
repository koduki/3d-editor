package dev.koduki.xreditor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class VsCodeExtensionAssetTest {

    @Test
    fun installInto_writesValidExtensionManifestAndScript() {
        val dir = Files.createTempDirectory("xr-vscode-ext").toFile()
        try {
            val extDir = VsCodeExtensionAsset.installInto(dir)
            val pkgJson = JSONObject(extDir.resolve("package.json").readText())
            assertEquals("xr-editor-tools", pkgJson.getString("name"))
            assertEquals("./extension.js", pkgJson.getString("main"))

            val commands = pkgJson.getJSONObject("contributes").getJSONArray("commands")
            val commandIds = (0 until commands.length()).map { commands.getJSONObject(it).getString("command") }
            assertTrue(commandIds.contains("xrEditor.openMenu"))
            assertTrue(commandIds.contains("xrEditor.runDiagnostics"))
            assertTrue(commandIds.contains("xrEditor.openLogs"))
            assertTrue(commandIds.contains("xrEditor.openManager"))

            val js = extDir.resolve("extension.js").readText()
            assertTrue(js.contains("XR_ADMIN_PORT"))
            assertTrue(js.contains("XR_ADMIN_TOKEN"))
            assertTrue(js.contains("createStatusBarItem"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun localDiagnosticsServer_enforcesTokenAndServesEndpoints() {
        val copied = AtomicReference("")
        val openedBoot = AtomicBoolean(false)
        val server = LocalDiagnosticsServer(
            onRunDiagnostics = { "# Report\nPASS" },
            onGetLogs = { "log-line-1" },
            onCopyToClipboard = { txt -> copied.set(txt); true },
            onOpenBootPage = { openedBoot.set(true) },
            onRequestStoragePermission = {},
            onRestartServer = {},
        )
        val port = server.start()
        try {
            // Unauthorized request must be rejected with 403
            val unauth = (URL("http://127.0.0.1:$port/diagnostics").openConnection() as HttpURLConnection)
            assertEquals(403, unauth.responseCode)
            unauth.disconnect()

            // Authorized GET /diagnostics
            val diag = (URL("http://127.0.0.1:$port/diagnostics").openConnection() as HttpURLConnection).apply {
                setRequestProperty("X-XR-Token", server.authToken)
            }
            assertEquals(200, diag.responseCode)
            val diagBody = diag.inputStream.bufferedReader().readText()
            assertEquals("# Report\nPASS", diagBody)
            diag.disconnect()

            // Authorized POST /copy
            val copyConn = (URL("http://127.0.0.1:$port/copy").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("X-XR-Token", server.authToken)
            }
            copyConn.outputStream.use { it.write("hello-xr".toByteArray()) }
            assertEquals(200, copyConn.responseCode)
            assertEquals("hello-xr", copied.get())
            copyConn.disconnect()

            // Authorized POST /open-boot
            val bootConn = (URL("http://127.0.0.1:$port/open-boot").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("X-XR-Token", server.authToken)
            }
            assertEquals(200, bootConn.responseCode)
            assertTrue(openedBoot.get())
            bootConn.disconnect()
        } finally {
            server.stop()
        }
    }
}
