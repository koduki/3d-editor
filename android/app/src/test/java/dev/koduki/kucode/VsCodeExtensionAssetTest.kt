package dev.koduki.kucode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class VsCodeExtensionAssetTest {

    @Test
    fun installInto_copiesBundledExtensionAssetsAtomically() {
        val dir = Files.createTempDirectory("kucode-ext-test").toFile()
        try {
            val extDir = VsCodeExtensionAsset.installInto(dir)
            assertTrue(extDir.resolve("icon.svg").isFile)
            assertTrue(extDir.resolve("acp-client.js").isFile)
            assertTrue(extDir.resolve("acp-webview.js").isFile)
            val pkgJson = JSONObject(extDir.resolve("package.json").readText())
            assertEquals("kucode-tools", pkgJson.getString("name"))
            assertEquals("0.9.0", pkgJson.getString("version"))
            assertEquals("./extension.js", pkgJson.getString("main"))

            val contributes = pkgJson.getJSONObject("contributes")
            val commands = contributes.getJSONArray("commands")
            val commandIds = (0 until commands.length()).map { commands.getJSONObject(it).getString("command") }
            assertTrue(commandIds.contains("kucode.openAcpChat"))
            assertTrue(commandIds.contains("kucode.sendSelectionToAcp"))
            assertTrue(commandIds.contains("kucode.setupCopilotCli"))
            assertTrue(commandIds.contains("kucode.openMenu"))
            assertTrue(commandIds.contains("kucode.runDiagnostics"))
            assertTrue(commandIds.contains("kucode.openLogs"))
            assertTrue(commandIds.contains("kucode.toggleTerminal"))
            assertTrue(commandIds.contains("kucode.selectDisplayScale"))
            assertTrue(commandIds.contains("kucode.selectOpticalTheme"))
            assertTrue(commandIds.contains("kucode.openManager"))
            assertTrue(commandIds.contains("kucode.requestStorage"))

            val menus = contributes.getJSONObject("menus")
            assertTrue(menus.has("editor/context"))
            assertTrue(menus.has("explorer/context"))
            assertTrue(contributes.getJSONObject("viewsContainers").has("activitybar"))
            assertTrue(contributes.getJSONObject("views").has("kucode"))

            val js = extDir.resolve("extension.js").readText()
            assertTrue(js.contains("KUCODE_ADMIN_PORT"))
            assertTrue(js.contains("KUCODE_ADMIN_TOKEN"))
            assertTrue(js.contains("registerTreeDataProvider"))
            assertTrue(js.contains("registerWebviewViewProvider"))
            assertTrue(js.contains("StatusBarAlignment.Left"))
            assertTrue(js.contains("OST_COLORS"))
            assertTrue(js.contains("VST_COLORS"))
            assertTrue(extDir.listFiles()!!.none { it.name.startsWith(".pending-") })
        } finally {
            deleteTreeNoFollow(dir)
        }
    }
}
