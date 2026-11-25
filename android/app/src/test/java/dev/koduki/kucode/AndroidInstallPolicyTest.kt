package dev.koduki.kucode

import android.content.pm.PackageInstaller
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

class AndroidInstallPolicyTest {
    @Test
    fun stableAndRunningHostAreNeverInstallTargets() {
        val stableInstallErr = assertThrows(IllegalArgumentException::class.java) {
            AndroidInstallPolicy.checkTarget("dev.koduki.kucode", "dev.koduki.kucode.debug")
        }
        assertTrue(stableInstallErr.message!!.contains("changed"))

        val stableLaunchErr = assertThrows(IllegalArgumentException::class.java) {
            AndroidInstallPolicy.checkTarget("dev.koduki.kucode", "dev.koduki.kucode.debug", allowDevSelf = true, forLaunch = true)
        }
        assertTrue(stableLaunchErr.message!!.contains("launched"))

        assertThrows(IllegalArgumentException::class.java) {
            AndroidInstallPolicy.checkTarget("dev.koduki.kucode.debug", "dev.koduki.kucode.debug")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidInstallPolicy.checkTarget("invalid", "dev.koduki.kucode")
        }
        AndroidInstallPolicy.checkTarget("dev.koduki.kucode.debug", "dev.koduki.kucode")
        AndroidInstallPolicy.checkTarget("dev.koduki.kucode.debug", "dev.koduki.kucode.debug", allowDevSelf = true, forLaunch = true)
        AndroidInstallPolicy.checkTarget("com.example.test", "dev.koduki.kucode")
    }

    @Test
    fun onlyWorkspaceOrHomeRegularApksAreAccepted() {
        val base = Files.createTempDirectory("apk-policy").toFile()
        try {
            val wsApk = File(base, "workspace/app.apk").apply { parentFile!!.mkdirs(); writeText("fixture") }
            val homeApk = File(base, "home/builds/app.apk").apply { parentFile!!.mkdirs(); writeText("fixture") }
            val outsideApk = File(base, "outside/secret.apk").apply { parentFile!!.mkdirs(); writeText("outside") }
            val linkApk = File(base, "workspace/link.apk")
            Files.createSymbolicLink(linkApk.toPath(), wsApk.toPath())
            val dirLink = File(base, "workspace/escape")
            Files.createSymbolicLink(dirLink.toPath(), outsideApk.parentFile!!.toPath())

            assertEquals(wsApk.canonicalFile, AndroidInstallPolicy.resolveApk("/workspace/app.apk", base))
            assertEquals(homeApk.canonicalFile, AndroidInstallPolicy.resolveApk("/root/builds/app.apk", base))

            for (badPath in listOf(
                "/tmp/app.apk",
                "/workspace/missing.apk",
                "/workspace/link.apk",
                "/workspace/escape/secret.apk",
                "/workspace/../outside/secret.apk",
                "/workspace/app.txt",
                "/workspace/app\u0000.apk",
            )) {
                assertThrows("Should reject $badPath", IllegalArgumentException::class.java) {
                    AndroidInstallPolicy.resolveApk(badPath, base)
                }
            }
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    fun bridgeCoreHandlesInstallApprovalSuccessDeclineCancelTimeoutAndRestart() {
        val base = Files.createTempDirectory("bridge-core-test").toFile()
        var now = 1_000_000L
        val directExecutor = Executor { it.run() }
        try {
            File(base, "workspace/app-debug.apk").apply { parentFile!!.mkdirs(); writeText("fake-apk-bytes") }
            val fake = FakeAndroidBridgePlatform(base)
            val core = AndroidHostBridgeCore(fake, directExecutor, clockMs = { now })

            // 1. Capabilities reflects install + notification + channel status
            val (capCode, capBody) = core.handle("GET", "/api/v1/android/capabilities", "") { false }
            assertEquals(200, capCode)
            val capJson = JSONObject(capBody)
            assertEquals(1, capJson.getInt("version"))
            assertTrue(capJson.getBoolean("canInstall"))
            assertTrue(capJson.getBoolean("notifications"))

            // 2. Permission checks fail fast (403) before staging
            fake.canInstall = false
            assertEquals(403, core.handle("POST", "/api/v1/android/install", """{"path":"/workspace/app-debug.apk"}""") { false }.first)
            fake.canInstall = true
            fake.channelEnabled = false
            assertEquals(403, core.handle("POST", "/api/v1/android/install", """{"path":"/workspace/app-debug.apk"}""") { false }.first)
            assertFalse(JSONObject(core.handle("GET", "/api/v1/android/capabilities", "") { false }.second).getBoolean("notifications"))
            fake.channelEnabled = true

            // 3. Happy path: install -> pending user action -> success
            val (postCode, postBody) = core.handle(
                "POST",
                "/api/v1/android/install",
                """{"path":"/workspace/app-debug.apk","packageName":"dev.koduki.kucode.debug"}""",
            ) { false }
            assertEquals(202, postCode)
            val opId = JSONObject(postBody).getString("operationId")
            assertEquals("submitted", JSONObject(core.handle("GET", "/api/v1/android/operations/$opId", "") { false }.second).getString("state"))
            assertEquals("Staged temp file must be cleaned up", 0, fake.stageDir.listFiles()?.size ?: 0)

            core.onResult(
                action = AndroidHostBridgeCore.RESULT_ACTION,
                operationId = opId,
                sessionId = fake.lastSessionId,
                status = PackageInstaller.STATUS_PENDING_USER_ACTION,
                statusMessage = null,
                confirmIntent = Any(),
            )
            assertEquals("awaiting_user", JSONObject(core.handle("GET", "/api/v1/android/operations/$opId", "") { false }.second).getString("state"))
            assertTrue(fake.lastSessionId in fake.notifiedSessions)

            core.onResult(
                action = AndroidHostBridgeCore.RESULT_ACTION,
                operationId = opId,
                sessionId = fake.lastSessionId,
                status = PackageInstaller.STATUS_SUCCESS,
                statusMessage = null,
                confirmIntent = null,
            )
            val done = JSONObject(core.handle("GET", "/api/v1/android/operations/$opId", "") { false }.second)
            assertEquals("succeeded", done.getString("state"))
            assertTrue(fake.lastSessionId in fake.cancelledNotifications)

            // 4. Signature mismatch fails cleanly without creating session
            val sessionsBefore = fake.createdSessions
            fake.installedSigners["dev.koduki.kucode.debug"] = InstalledPackageSigners(setOf("other-cert"))
            val mismatchId = JSONObject(
                core.handle("POST", "/api/v1/android/install", """{"path":"/workspace/app-debug.apk"}""") { false }.second,
            ).getString("operationId")
            val mismatch = JSONObject(core.handle("GET", "/api/v1/android/operations/$mismatchId", "") { false }.second)
            assertEquals("failed", mismatch.getString("state"))
            assertTrue(mismatch.getString("message").contains("signature"))
            assertEquals(sessionsBefore, fake.createdSessions)

            // 5. Key rotation lineage containing installed cert is permitted
            fake.parsedApk = ParsedApkInfo("dev.koduki.kucode.debug", setOf("new-cert"), setOf("other-cert", "new-cert"))
            val rotatedId = JSONObject(
                core.handle("POST", "/api/v1/android/install", """{"path":"/workspace/app-debug.apk"}""") { false }.second,
            ).getString("operationId")
            assertEquals("submitted", JSONObject(core.handle("GET", "/api/v1/android/operations/$rotatedId", "") { false }.second).getString("state"))

            // 6. Cancel succeeds with 200 and state=cancelled and abandons session
            val (cancelCode, cancelBody) = core.handle("POST", "/api/v1/android/cancel", """{"operationId":"$rotatedId"}""") { false }
            assertEquals(200, cancelCode)
            assertEquals("cancelled", JSONObject(cancelBody).getString("state"))
            assertTrue(fake.lastSessionId in fake.abandonedSessions)

            // 7. User declines in OS confirmation -> cancelled
            val declinedId = JSONObject(
                core.handle("POST", "/api/v1/android/install", """{"path":"/workspace/app-debug.apk"}""") { false }.second,
            ).getString("operationId")
            core.onResult(
                action = AndroidHostBridgeCore.RESULT_ACTION,
                operationId = declinedId,
                sessionId = fake.lastSessionId,
                status = PackageInstaller.STATUS_FAILURE_ABORTED,
                statusMessage = "user declined",
                confirmIntent = null,
            )
            assertEquals("cancelled", JSONObject(core.handle("GET", "/api/v1/android/operations/$declinedId", "") { false }.second).getString("state"))

            // 8. Concurrency limit (max 2 active) and expiry after 15 minutes
            val active1 = JSONObject(core.handle("POST", "/api/v1/android/install", """{"path":"/workspace/app-debug.apk"}""") { false }.second).getString("operationId")
            val active2 = JSONObject(core.handle("POST", "/api/v1/android/install", """{"path":"/workspace/app-debug.apk"}""") { false }.second).getString("operationId")
            assertEquals(409, core.handle("POST", "/api/v1/android/install", """{"path":"/workspace/app-debug.apk"}""") { false }.first)
            now += AndroidHostBridgeCore.EXPIRY_MS + 1_000L
            core.expire()
            assertEquals("timed_out", JSONObject(core.handle("GET", "/api/v1/android/operations/$active1", "") { false }.second).getString("state"))
            assertEquals("timed_out", JSONObject(core.handle("GET", "/api/v1/android/operations/$active2", "") { false }.second).getString("state"))

            // 9. Unknown operation (host restart) and extra path segments -> 404 interrupted
            val (missingCode, missingBody) = core.handle("GET", "/api/v1/android/operations/nonexistent", "") { false }
            assertEquals(404, missingCode)
            assertEquals("interrupted", JSONObject(missingBody).getString("state"))
            assertEquals(404, core.handle("GET", "/api/v1/android/operations/bad/extra", "") { false }.first)

            // 10. Launch success, foreground conflict (409), and Stable block (400)
            assertEquals(200, core.handle("POST", "/api/v1/android/launch", """{"packageName":"dev.koduki.kucode.debug"}""") { true }.first)
            assertEquals(409, core.handle("POST", "/api/v1/android/launch", """{"packageName":"dev.koduki.kucode.debug"}""") { false }.first)
            assertEquals(400, core.handle("POST", "/api/v1/android/launch", """{"packageName":"dev.koduki.kucode"}""") { true }.first)
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    fun truncateUtf8BytesNeverSplitsMultibyteJapaneseCharacters() {
        val text = "あいうえお" // 5 chars * 3 bytes = 15 bytes
        for (limit in 0..16) {
            val truncated = BootHostBridge.truncateUtf8Bytes(text, limit)
            assertTrue(truncated.size <= limit)
            val decoded = String(truncated, Charsets.UTF_8)
            assertFalse("Must not produce replacement char at limit=$limit", decoded.contains('\uFFFD'))
            assertTrue(text.startsWith(decoded))
        }
        assertEquals("あ", String(BootHostBridge.truncateUtf8Bytes(text, 5), Charsets.UTF_8))
        assertEquals("あい", String(BootHostBridge.truncateUtf8Bytes(text, 6), Charsets.UTF_8))
    }

    private class FakeAndroidBridgePlatform(baseDir: File) : AndroidBridgePlatform {
        override val ownPackageName: String = "dev.koduki.kucode"
        override val filesDir: File = baseDir
        override val stageDir: File = File(baseDir, "cache/android-install")
        var canInstall: Boolean = true
        var notificationsEnabled: Boolean = true
        var channelEnabled: Boolean = true
        var parsedApk: ParsedApkInfo? = ParsedApkInfo("dev.koduki.kucode.debug", setOf("cert-a"))
        val installedSigners = mutableMapOf<String, InstalledPackageSigners>()
        var createdSessions = 0
        var lastSessionId = 0
        val abandonedSessions = mutableSetOf<Int>()
        val notifiedSessions = mutableSetOf<Int>()
        val cancelledNotifications = mutableSetOf<Int>()

        override fun canRequestPackageInstalls(): Boolean = canInstall
        override fun areNotificationsEnabled(): Boolean = notificationsEnabled
        override fun isInstallChannelEnabled(): Boolean = channelEnabled
        override fun parseApk(apk: File): ParsedApkInfo? = parsedApk
        override fun getInstalledSigners(packageName: String): InstalledPackageSigners? = installedSigners[packageName]
        override fun createSession(packageName: String, sizeBytes: Long): Int {
            createdSessions++
            lastSessionId = 100 + createdSessions
            return lastSessionId
        }
        override fun writeAndCommitSession(sessionId: Int, apk: File, operationId: String) = Unit
        override fun abandonSession(sessionId: Int) { abandonedSessions.add(sessionId) }
        override fun abandonOwnedSessions() = Unit
        override fun notifyPendingUserAction(sessionId: Int, packageName: String, confirmIntent: Any) {
            notifiedSessions.add(sessionId)
        }
        override fun cancelNotification(sessionId: Int) { cancelledNotifications.add(sessionId) }
    }
}
